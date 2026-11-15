package com.nishiyu.enderframelib.core.paint.gl;

import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuBuffer;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuCaps;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuDevice;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuFrame;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuPass;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuPipeline;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuTexture;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL44;
import org.lwjgl.opengl.GLCapabilities;

/**
 * {@link GpuDevice} の OpenGL（3.2 core）実装（G1 の段 1: テクスチャ・バッファ・クリアつきパス・アップロード・コピー・読み出し）。
 *
 * <ul>
 *   <li><b>座標</b>: RHI は左上原点。GL は下端原点なので、領域の y は {@code 高さ - (y + h)} へ写し、行の並びを反転する
 *       （アップロード・読み出し）。コピーとクリアは両端が同じ向きなので、領域だけを写す。</li>
 *   <li><b>状態</b>: {@link #beginFrame} が GL の現在の状態（FBO・ビューポート・シザー・2D テクスチャ・ピクセル転送の設定）を退避し、
 *       {@code end()} が戻す（ホストのコンテキストに相乗りしても壊さない）。命令は「現在の GL 状態」に依らない。</li>
 *   <li><b>未対応（後の段）</b>: パイプラインと描画（シェーダーの単一ソース化 G2 の後）、深度・マルチサンプルの描画先。</li>
 * </ul>
 * 呼び出しは GL コンテキストが現在のスレッドで行う。
 */
public final class GlDevice implements GpuDevice {
    private final GpuCaps caps;
    private boolean inFrame;
    private boolean closed;
    /** 作ったバッファ。フレームの終わりに、CPU が書いた影を GPU へ送る。 */
    private final java.util.List<Buffer> buffers = new java.util.ArrayList<>();

    /** GL コンテキストが現在のスレッドにあることが前提。 */
    public GlDevice() {
        GLCapabilities c = org.lwjgl.opengl.GL.getCapabilities();
        boolean persistent = c.OpenGL44 || c.GL_ARB_buffer_storage;
        this.caps = new GpuCaps(persistent, c.OpenGL43, c.OpenGL43, c.OpenGL33 || c.GL_ARB_timer_query,
                GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE), c.OpenGL30);
    }

    @Override
    public GpuCaps caps() {
        return caps;
    }

    @Override
    public GpuBuffer createBuffer(BufferDesc desc) {
        Buffer b = new Buffer(desc);
        buffers.add(b);
        return b;
    }

    @Override
    public GpuTexture createTexture(TextureDesc desc) {
        if (desc.format() == TextureFormat.DEPTH24 || desc.samples() > 1) {
            throw new UnsupportedOperationException("深度・マルチサンプルの描画先は後の段（G1 段 3: 3D の RHI 化）");
        }
        if (desc.width() > caps.maxTextureSize() || desc.height() > caps.maxTextureSize()) {
            throw new IllegalArgumentException("texture too large: " + desc);
        }
        int id = GL11.glGenTextures();
        int prev = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        int internal, format, type;
        switch (desc.format()) {
            case RGBA8 -> { internal = GL11.GL_RGBA8; format = GL11.GL_RGBA; type = GL11.GL_UNSIGNED_BYTE; }
            case R8 -> { internal = GL30.GL_R8; format = GL11.GL_RED; type = GL11.GL_UNSIGNED_BYTE; }
            case RGBA16F -> { internal = GL30.GL_RGBA16F; format = GL11.GL_RGBA; type = org.lwjgl.opengl.GL30.GL_HALF_FLOAT; }
            default -> throw new UnsupportedOperationException(desc.format().name());
        }
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internal, desc.width(), desc.height(), 0, format, type, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12ClampToEdge.VALUE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12ClampToEdge.VALUE);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev);
        return new Texture(id, desc.width(), desc.height(), desc.format(), true);
    }

    /** GL12 の定数を 1 か所に（import を増やさない）。 */
    private static final class GL12ClampToEdge {
        static final int VALUE = org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
    }

    @Override
    public GpuTexture wrapExternal(long nativeHandle, int width, int height) {
        return new Texture((int) nativeHandle, width, height, TextureFormat.RGBA8, false);
    }

    @Override
    public GpuPipeline createPipeline(PipelineDesc desc) {
        throw new UnsupportedOperationException("パイプラインはシェーダーの単一ソース化（G2）の後（G1 段 2）");
    }

    @Override
    public GpuFrame beginFrame() {
        if (closed) throw new IllegalStateException("closed");
        if (inFrame) throw new IllegalStateException("フレームが開いたまま");
        inFrame = true;
        return new Frame();
    }

    @Override
    public long nativeHandle(GpuTexture texture) {
        return ((Texture) texture).id;
    }

    /** テスト・診断用: バッファの GPU 側の内容を読む（{@code dst} の先頭から {@code size} バイト）。 */
    public void readBuffer(GpuBuffer buffer, ByteBuffer dst) {
        Buffer b = (Buffer) buffer;
        int prev = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, b.id);
        GL15.glGetBufferSubData(GL15.GL_ARRAY_BUFFER, 0, dst);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prev);
    }

    @Override
    public void close() {
        closed = true;
    }

    // ------------------------------------------------------------------

    private static final class Buffer implements GpuBuffer {
        final BufferDesc desc;
        final int id;
        /** CPU が書く先（影）。フレームの終わりに GPU へ送る。 */
        final ByteBuffer shadow;
        boolean closed;

        Buffer(BufferDesc desc) {
            if (desc.size() > Integer.MAX_VALUE - 64) throw new IllegalArgumentException("size: " + desc.size());
            this.desc = desc;
            this.shadow = BufferUtils.createByteBuffer((int) desc.size()).order(ByteOrder.nativeOrder());
            int prev = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            this.id = GL15.glGenBuffers();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, id);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, desc.size(), GL15.GL_DYNAMIC_DRAW);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prev);
        }

        void flush() {
            int prev = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, id);
            shadow.clear();
            GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, shadow);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prev);
        }

        @Override public long size() { return desc.size(); }
        @Override public BufferUsage usage() { return desc.usage(); }
        @Override public ByteBuffer map() { return shadow; }
        @Override public void close() { if (!closed) { closed = true; GL15.glDeleteBuffers(id); } }
    }

    private static final class Texture implements GpuTexture {
        final int id, w, h;
        final TextureFormat format;
        final boolean owned;
        int fbo;

        Texture(int id, int w, int h, TextureFormat format, boolean owned) {
            this.id = id;
            this.w = w;
            this.h = h;
            this.format = format;
            this.owned = owned;
        }

        int fbo() {
            if (fbo == 0) {
                int prev = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
                fbo = GL30.glGenFramebuffers();
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, id, 0);
                if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                    GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prev);
                    GL30.glDeleteFramebuffers(fbo);
                    fbo = 0;
                    throw new IllegalStateException("描画先にできないテクスチャ: " + format);
                }
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prev);
            }
            return fbo;
        }

        void contentOk() {
            if (format != TextureFormat.RGBA8 && format != TextureFormat.R8) {
                throw new UnsupportedOperationException("内容を読み書きできない形式: " + format);
            }
        }

        @Override public int width() { return w; }
        @Override public int height() { return h; }
        @Override public TextureFormat format() { return format; }

        @Override
        public void close() {
            if (fbo != 0) GL30.glDeleteFramebuffers(fbo);
            fbo = 0;
            if (owned) GL11.glDeleteTextures(id);
        }
    }

    private static void checkRegion(Texture t, int x, int y, int w, int h) {
        if (x < 0 || y < 0 || w < 0 || h < 0 || x + w > t.w || y + h > t.h) {
            throw new IllegalArgumentException("領域がテクスチャの外: " + x + "," + y + " " + w + "x" + h + " in " + t.w + "x" + t.h);
        }
    }

    /** 行を逆順に並べ替えたコピー（左上原点 ⇔ GL の下端原点）。 */
    private static ByteBuffer flipRows(ByteBuffer src, int rowBytes, int rows) {
        ByteBuffer out = BufferUtils.createByteBuffer(rowBytes * rows);
        ByteBuffer s = src.duplicate();
        byte[] row = new byte[rowBytes];
        int base = s.position();
        for (int r = 0; r < rows; r++) {
            s.position(base + (rows - 1 - r) * rowBytes);
            s.get(row);
            out.put(row);
        }
        out.flip();
        return out;
    }

    private final class Frame implements GpuFrame {
        // 退避する GL の状態
        private final int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        private final int prevReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        private final int prevDrawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        private final int[] prevViewport = new int[4];
        private final boolean prevScissorTest = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        private final int[] prevScissor = new int[4];
        private final int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        private final int prevAlign = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        private final int prevPackAlign = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        private final int prevUnpackRow = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
        private final int prevPackRow = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
        private final float[] prevClear = new float[4];
        private final boolean[] prevMask = new boolean[4];
        private boolean passOpen;
        private boolean ended;

        Frame() {
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, prevViewport);
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, prevScissor);
            GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, prevClear);
            java.nio.ByteBuffer m = BufferUtils.createByteBuffer(4);
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, m);
            for (int i = 0; i < 4; i++) prevMask[i] = m.get(i) != 0;
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
        }

        private void live() {
            if (ended) throw new IllegalStateException("提出済みのフレーム");
            if (passOpen) throw new IllegalStateException("パスが開いたまま");
        }

        @Override
        public GpuPass beginPass(GpuTexture target, LoadOp load, int clearArgb, int[] scissor) {
            live();
            Texture t = (Texture) target;
            int[] sc = scissor == null ? new int[]{0, 0, t.w, t.h} : scissor;
            checkRegion(t, sc[0], sc[1], sc[2], sc[3]);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, t.fbo());
            GL11.glViewport(0, 0, t.w, t.h);
            if (load == LoadOp.CLEAR) {
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
                GL11.glScissor(sc[0], t.h - (sc[1] + sc[3]), sc[2], sc[3]);
                GL11.glColorMask(true, true, true, true);
                GL11.glClearColor(((clearArgb >> 16) & 0xFF) / 255f, ((clearArgb >> 8) & 0xFF) / 255f,
                        (clearArgb & 0xFF) / 255f, ((clearArgb >>> 24) & 0xFF) / 255f);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            }
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(sc[0], t.h - (sc[1] + sc[3]), sc[2], sc[3]);
            passOpen = true;
            return new GpuPass() {
                private boolean done;

                @Override
                public void bind(GpuPipeline pipeline, Bindings bindings) {
                    throw new UnsupportedOperationException("パイプラインは G1 段 2 以降");
                }

                @Override
                public void drawInstanced(int vertexCount, int instanceCount, int baseInstance) {
                    throw new UnsupportedOperationException("描画は G1 段 2 以降");
                }

                @Override
                public void end() {
                    if (done) throw new IllegalStateException("終えたパス");
                    done = true;
                    passOpen = false;
                }
            };
        }

        @Override
        public void upload(GpuTexture dst, int x, int y, int w, int h, ByteBuffer pixels) {
            live();
            Texture t = (Texture) dst;
            t.contentOk();
            checkRegion(t, x, y, w, h);
            int row = w * t.format.bytesPerPixel;
            if (pixels.remaining() < row * h) throw new IllegalArgumentException("画素が足りない: " + pixels.remaining() + " < " + row * h);
            ByteBuffer flipped = flipRows(pixels, row, h);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, t.id);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, x, t.h - (y + h), w, h,
                    t.format == TextureFormat.R8 ? GL11.GL_RED : GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, flipped);
        }

        @Override
        public void copy(GpuTexture src, GpuTexture dst, int[] region) {
            live();
            Texture s = (Texture) src, d = (Texture) dst;
            s.contentOk();
            if (s.format != d.format) throw new IllegalArgumentException("形式が違う: " + s.format + " / " + d.format);
            checkRegion(s, region[0], region[1], region[2], region[3]);
            checkRegion(d, region[0], region[1], region[2], region[3]);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, s.fbo());
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, d.fbo());
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glColorMask(true, true, true, true);
            int sy0 = s.h - (region[1] + region[3]), sy1 = s.h - region[1];
            int dy0 = d.h - (region[1] + region[3]), dy1 = d.h - region[1];
            GL30.glBlitFramebuffer(region[0], sy0, region[0] + region[2], sy1, region[0], dy0, region[0] + region[2], dy1,
                    GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        }

        @Override
        public void readback(GpuTexture src, int[] region, ByteBuffer dst) {
            live();
            Texture s = (Texture) src;
            s.contentOk();
            checkRegion(s, region[0], region[1], region[2], region[3]);
            int row = region[2] * s.format.bytesPerPixel;
            if (dst.remaining() < row * region[3]) throw new IllegalArgumentException("出力が足りない");
            ByteBuffer tmp = BufferUtils.createByteBuffer(row * region[3]);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, s.fbo());
            GL11.glReadPixels(region[0], s.h - (region[1] + region[3]), region[2], region[3],
                    s.format == TextureFormat.R8 ? GL11.GL_RED : GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, tmp);
            dst.put(flipRows(tmp, row, region[3]));
        }

        @Override
        public void end() {
            live();
            ended = true;
            inFrame = false;
            for (Buffer b : buffers) if (!b.closed) b.flush();
            // 状態を戻す
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDrawFbo);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDrawFbo);
            GL11.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
            if (prevScissorTest) GL11.glEnable(GL11.GL_SCISSOR_TEST); else GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(prevScissor[0], prevScissor[1], prevScissor[2], prevScissor[3]);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, prevAlign);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, prevPackAlign);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, prevUnpackRow);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, prevPackRow);
            GL11.glClearColor(prevClear[0], prevClear[1], prevClear[2], prevClear[3]);
            GL11.glColorMask(prevMask[0], prevMask[1], prevMask[2], prevMask[3]);
            GL11.glFlush();
        }
    }
}
