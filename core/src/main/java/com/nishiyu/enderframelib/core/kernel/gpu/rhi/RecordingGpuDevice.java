package com.nishiyu.enderframelib.core.kernel.gpu.rhi;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * GL を使わない {@link GpuDevice} の実装。命令の列を記録し、RGBA8 / R8 のテクスチャは CPU 側の画像として持つ
 * （クリア・アップロード・コピー・読み出しはその画像に対して実際に働く。{@code drawInstanced} は記録するだけで画素は変えない）。
 *
 * <p>使い道: ①GL の無い CI で「描画側が RHI をどう呼んだか」を検証する（呼び出し数が図形の数に比例しないこと、など）
 * ②本物のデバイスが満たすべき振る舞いの基準（{@code GpuDeviceContractTest} が同じ検査をこれと GL に通す）。</p>
 */
public final class RecordingGpuDevice implements GpuDevice {
    /** 記録する命令。 */
    public sealed interface Command {
        record CreateBuffer(long size, BufferUsage usage) implements Command { }
        record CreateTexture(int width, int height, TextureFormat format, boolean renderTarget) implements Command { }
        record CreatePipeline(String shaderId) implements Command { }
        record BeginFrame(int frame) implements Command { }
        record BeginPass(int targetWidth, int targetHeight, LoadOp load, int clearArgb, int[] scissor) implements Command { }
        record Bind(String shaderId, int recordBuffers, int textures) implements Command { }
        record BindMesh() implements Command { }
        record Draw(int vertexCount, int instanceCount, int baseInstance) implements Command { }
        record DrawIndexed(int indexCount, long indexOffsetBytes, int instanceCount, int baseVertex, int baseInstance) implements Command { }
        record EndPass() implements Command { }
        record Upload(int x, int y, int w, int h) implements Command { }
        record Copy(int[] region) implements Command { }
        record Readback(int[] region) implements Command { }
        record EndFrame(int frame) implements Command { }
    }

    private final GpuCaps caps;
    private final List<Command> log = new ArrayList<>();
    private int frames;
    private boolean inFrame;
    private boolean closed;

    public RecordingGpuDevice() {
        this(new GpuCaps(true, false, false, false, 16384, true, 4));
    }

    public RecordingGpuDevice(GpuCaps caps) {
        this.caps = caps;
    }

    /** これまでに記録した命令（読み取り専用）。 */
    public List<Command> commands() {
        return Collections.unmodifiableList(log);
    }

    public int commandCount() {
        return log.size();
    }

    public int framesBegun() {
        return frames;
    }

    /** 記録を空にする（フレームの数は保つ）。 */
    public void clearLog() {
        log.clear();
    }

    /** 指定した種類の命令の数。 */
    public long count(Class<? extends Command> type) {
        return log.stream().filter(type::isInstance).count();
    }

    @Override
    public GpuCaps caps() {
        return caps;
    }

    @Override
    public GpuBuffer createBuffer(BufferDesc desc) {
        if (desc.size() > Integer.MAX_VALUE - 64) throw new IllegalArgumentException("size: " + desc.size());
        log.add(new Command.CreateBuffer(desc.size(), desc.usage()));
        return new Buffer(desc);
    }

    @Override
    public GpuTexture createTexture(TextureDesc desc) {
        if (desc.width() > caps.maxTextureSize() || desc.height() > caps.maxTextureSize()) {
            throw new IllegalArgumentException("texture too large: " + desc);
        }
        log.add(new Command.CreateTexture(desc.width(), desc.height(), desc.format(), desc.renderTarget()));
        return new Texture(desc.width(), desc.height(), desc.format(), 0, desc.samples(), desc.renderTarget());
    }

    @Override
    public GpuTexture wrapExternal(long nativeHandle, int width, int height) {
        return new Texture(width, height, TextureFormat.RGBA8, nativeHandle, 1, false);
    }

    @Override
    public GpuPipeline createPipeline(PipelineDesc desc) {
        com.nishiyu.enderframelib.core.kernel.gpu.shader.ShaderPrograms.byId(desc.shaderId()); // 未知の名前は IllegalArgumentException
        log.add(new Command.CreatePipeline(desc.shaderId()));
        return new Pipeline(desc);
    }

    @Override
    public GpuFrame beginFrame() {
        if (inFrame) throw new IllegalStateException("フレームが開いたまま");
        inFrame = true;
        log.add(new Command.BeginFrame(++frames));
        return new Frame();
    }

    @Override
    public long nativeHandle(GpuTexture texture) {
        return ((Texture) texture).handle;
    }

    @Override
    public void close() {
        closed = true;
    }

    public boolean isClosed() {
        return closed;
    }

    // ------------------------------------------------------------------

    private static final class Buffer implements GpuBuffer {
        private final BufferDesc desc;
        private final ByteBuffer data;

        Buffer(BufferDesc desc) {
            this.desc = desc;
            ByteBuffer raw = ByteBuffer.allocateDirect((int) desc.size() + 64);
            int off = (64 - raw.alignmentOffset(0, 64)) & 63;
            this.data = raw.slice(off, (int) desc.size()).order(java.nio.ByteOrder.nativeOrder());
        }

        @Override public long size() { return desc.size(); }
        @Override public BufferUsage usage() { return desc.usage(); }
        @Override public ByteBuffer map() { return data; }
        @Override public void close() { }
    }

    private static final class Texture implements GpuTexture {
        final int w, h, samples;
        final boolean renderTarget;
        final TextureFormat format;
        final long handle;
        /** RGBA8 / R8（非 MSAA）の画素（左上原点・行は詰める）。それ以外の形式・MSAA は null（内容の命令は使えない）。 */
        final byte[] pixels;

        Texture(int w, int h, TextureFormat format, long handle, int samples, boolean renderTarget) {
            this.w = w;
            this.h = h;
            this.samples = samples;
            this.renderTarget = renderTarget;
            this.format = format;
            this.handle = handle;
            this.pixels = ((format == TextureFormat.RGBA8 || format == TextureFormat.R8) && samples == 1)
                    ? new byte[w * h * format.bytesPerPixel] : null;
        }

        byte[] contents() {
            if (pixels == null) throw new UnsupportedOperationException("内容を読み書きできない形式: " + format);
            return pixels;
        }

        @Override public int width() { return w; }
        @Override public int height() { return h; }
        @Override public TextureFormat format() { return format; }
        @Override public void close() { }
    }

    private static final class Pipeline implements GpuPipeline {
        private final PipelineDesc desc;

        Pipeline(PipelineDesc desc) { this.desc = desc; }

        @Override public PipelineDesc desc() { return desc; }
        @Override public void close() { }
    }

    private static void checkRegion(Texture t, int x, int y, int w, int h) {
        if (x < 0 || y < 0 || w < 0 || h < 0 || x + w > t.w || y + h > t.h) {
            throw new IllegalArgumentException("領域がテクスチャの外: " + x + "," + y + " " + w + "x" + h + " in " + t.w + "x" + t.h);
        }
    }

    private final class Frame implements GpuFrame {
        private boolean passOpen;
        private boolean ended;

        private void live() {
            if (ended) throw new IllegalStateException("提出済みのフレーム");
            if (passOpen) throw new IllegalStateException("パスが開いたまま");
        }

        @Override
        public GpuPass beginPass(GpuTexture target, LoadOp load, int clearArgb, int[] scissor) {
            return beginPass(target, null, load, clearArgb, 1.0f, scissor);
        }

        @Override
        public GpuPass beginPass(GpuTexture colorTarget, GpuTexture depthTarget, LoadOp load,
                                 int clearArgb, float clearDepth, int[] scissor) {
            live();
            Texture t = (Texture) colorTarget;
            if (depthTarget != null) {
                Texture dt = (Texture) depthTarget;
                if (dt.format != TextureFormat.DEPTH24 || dt.w != t.w || dt.h != t.h || dt.samples != t.samples) {
                    throw new IllegalArgumentException("深度ターゲットが一致しない");
                }
            }
            int[] sc = scissor == null ? new int[]{0, 0, t.w, t.h} : scissor.clone();
            checkRegion(t, sc[0], sc[1], sc[2], sc[3]);
            log.add(new Command.BeginPass(t.w, t.h, load, clearArgb, sc));
            if (load == LoadOp.CLEAR && t.pixels != null) {
                int bpp = t.format.bytesPerPixel;
                byte r = (byte) (clearArgb >> 16), g = (byte) (clearArgb >> 8), b = (byte) clearArgb, a = (byte) (clearArgb >>> 24);
                for (int yy = sc[1]; yy < sc[1] + sc[3]; yy++) {
                    for (int xx = sc[0]; xx < sc[0] + sc[2]; xx++) {
                        int o = (yy * t.w + xx) * bpp;
                        if (bpp == 4) {
                            t.pixels[o] = r;
                            t.pixels[o + 1] = g;
                            t.pixels[o + 2] = b;
                            t.pixels[o + 3] = a;
                        } else {
                            t.pixels[o] = r;
                        }
                    }
                }
            }
            passOpen = true;
            return new Pass();
        }

        @Override
        public void upload(GpuTexture dst, int x, int y, int w, int h, ByteBuffer src) {
            live();
            Texture t = (Texture) dst;
            checkRegion(t, x, y, w, h);
            int bpp = t.format.bytesPerPixel, row = w * bpp;
            if (src.remaining() < row * h) throw new IllegalArgumentException("画素が足りない: " + src.remaining() + " < " + row * h);
            byte[] px = t.contents();
            ByteBuffer s = src.duplicate();
            for (int yy = 0; yy < h; yy++) {
                s.get(px, ((y + yy) * t.w + x) * bpp, row);
            }
            log.add(new Command.Upload(x, y, w, h));
        }

        @Override
        public void copy(GpuTexture src, GpuTexture dst, int[] region) {
            live();
            Texture s = (Texture) src, d = (Texture) dst;
            if (s.format != TextureFormat.RGBA8 && s.format != TextureFormat.R8 && s.format != TextureFormat.RGBA16F) {
                throw new UnsupportedOperationException("内容を読み書きできない形式: " + s.format);
            }
            if (s.format != d.format) throw new IllegalArgumentException("形式が違う: " + s.format + " / " + d.format);
            checkRegion(s, region[0], region[1], region[2], region[3]);
            checkRegion(d, region[0], region[1], region[2], region[3]);
            if (s.pixels != null && d.pixels != null) {
                int bpp = s.format.bytesPerPixel, row = region[2] * bpp;
                byte[] sp = s.pixels, dp = d.pixels;
                for (int yy = region[1]; yy < region[1] + region[3]; yy++) {
                    System.arraycopy(sp, (yy * s.w + region[0]) * bpp, dp, (yy * d.w + region[0]) * bpp, row);
                }
            }
            log.add(new Command.Copy(region.clone()));
        }

        @Override
        public void readback(GpuTexture src, int[] region, ByteBuffer dst) {
            live();
            Texture s = (Texture) src;
            checkRegion(s, region[0], region[1], region[2], region[3]);
            int bpp = s.format.bytesPerPixel, row = region[2] * bpp;
            if (dst.remaining() < row * region[3]) throw new IllegalArgumentException("出力が足りない");
            byte[] sp = s.contents();
            for (int yy = region[1]; yy < region[1] + region[3]; yy++) {
                dst.put(sp, (yy * s.w + region[0]) * bpp, row);
            }
            log.add(new Command.Readback(region.clone()));
        }

        @Override
        public void end() {
            live();
            ended = true;
            inFrame = false;
            log.add(new Command.EndFrame(frames));
        }

        private final class Pass implements GpuPass {
            private boolean bound;
            private boolean done;

            @Override
            public void bind(GpuPipeline pipeline, GpuBindings bindings) {
                if (done) throw new IllegalStateException("終えたパス");
                var program = com.nishiyu.enderframelib.core.kernel.gpu.shader.ShaderPrograms.byId(pipeline.desc().shaderId());
                if (bindings.textures().length < program.samplers().size()) throw new IllegalArgumentException("テクスチャが足りない: " + program.id());
                if (bindings.records().length < program.recordBuffers()) throw new IllegalArgumentException("記録列のバッファが足りない: " + program.id());
                if (program.uniformBytes() > 0 && (bindings.uniforms() == null || bindings.uniforms().remaining() < program.uniformBytes())) {
                    throw new IllegalArgumentException("定数が足りない: " + program.id() + " は " + program.uniformBytes() + " バイト");
                }
                bound = true;
                log.add(new Command.Bind(pipeline.desc().shaderId(), bindings.records().length, bindings.textures().length));
            }

            @Override
            public void bindMesh(GpuBuffer vertices, GpuBuffer indices) {
                if (done) throw new IllegalStateException("終えたパス");
                if (vertices.usage() != BufferUsage.VERTEX || indices.usage() != BufferUsage.INDEX) {
                    throw new IllegalArgumentException("頂点・インデックスバッファの種別が違う");
                }
                log.add(new Command.BindMesh());
            }

            @Override
            public void drawInstanced(int vertexCount, int instanceCount, int baseInstance) {
                if (done) throw new IllegalStateException("終えたパス");
                if (!bound) throw new IllegalStateException("パイプラインを結んでいない");
                log.add(new Command.Draw(vertexCount, instanceCount, baseInstance));
            }

            @Override
            public void drawIndexedInstanced(int indexCount, long indexOffsetBytes, int instanceCount, int baseVertex, int baseInstance) {
                if (done) throw new IllegalStateException("終えたパス");
                if (!bound) throw new IllegalStateException("パイプラインを結んでいない");
                log.add(new Command.DrawIndexed(indexCount, indexOffsetBytes, instanceCount, baseVertex, baseInstance));
            }

            @Override
            public void end() {
                if (done) throw new IllegalStateException("終えたパス");
                done = true;
                passOpen = false;
                log.add(new Command.EndPass());
            }
        }
    }
}
