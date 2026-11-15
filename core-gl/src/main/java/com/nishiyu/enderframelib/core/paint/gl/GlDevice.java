package com.nishiyu.enderframelib.core.paint.gl;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.BufferDesc;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.BufferUsage;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.CullMode;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.FilterMode;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.LoadOp;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.PipelineDesc;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.TextureDesc;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.TextureFormat;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.VertexAttr;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.VertexLayout;
import com.nishiyu.enderframelib.core.kernel.gpu.shader.RecordView;
import com.nishiyu.enderframelib.core.kernel.gpu.shader.ViewFormat;

import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuBindings;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuBuffer;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuCaps;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuDevice;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuFrame;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuPass;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuPipeline;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuTexture;
import com.nishiyu.enderframelib.core.kernel.gpu.shader.Glsl;
import com.nishiyu.enderframelib.core.kernel.gpu.shader.ShaderProgram;
import com.nishiyu.enderframelib.core.kernel.gpu.shader.ShaderPrograms;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBBufferStorage;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL44;
import org.lwjgl.opengl.GLCapabilities;

/**
 * {@link GpuDevice} の OpenGL（3.2 core）実装。
 *
 * <ul>
 *   <li><b>座標</b>: RHI は左上原点・事前乗算。GL は下端原点なので、領域の y は {@code 高さ - (y + h)} へ写し、行の並びを反転する
 *       （アップロード・読み出し）。コピーとクリアは両端が同じ向きなので、領域だけを写す。</li>
 *   <li><b>状態</b>: {@link #beginFrame} が GL の現在の状態（FBO・ビューポート・シザー・テクスチャ・深度・カリング・ピクセル転送）を退避し、
 *       {@code end()} が戻す（ホストのコンテキストに相乗りしても壊さない）。命令は「現在の GL 状態」に依らない。</li>
 *   <li><b>永続 3 面リング</b>: {@link BufferUsage#RECORD} バッファは対応環境では 3 面の永続コヒーレントマップとして確保し、
 *       フレーム間の同期（フェンス）を {@link #beginFrame()} / {@link GpuFrame#end()} へ集約する。</li>
 * </ul>
 * 呼び出しは GL コンテキストが現在のスレッドで行う。
 */
public final class GlDevice implements GpuDevice {
    /** 永続マップの面の既定数。CPU は GPU を待たずに 2 フレーム先まで書ける。 */
    public static final int DEFAULT_RING = 3;

    private static final int MAP_FLAGS = GL30.GL_MAP_WRITE_BIT | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT;
    private static final int STORAGE_FLAGS = GL30.GL_MAP_WRITE_BIT | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT;

    private final GpuCaps caps;
    private final boolean allowPersistent;
    private final int ringSize;
    private final FenceRing fences = new FenceRing();
    private final java.util.List<Buffer> buffers = new java.util.ArrayList<>();
    private final ByteBuffer scratchMask = BufferUtils.createByteBuffer(4);
    private ByteBuffer uboScratch = BufferUtils.createByteBuffer(2048);
    private ByteBuffer scratchFlipBuf;
    private byte[] scratchFlipRow;

    private boolean inFrame;
    private boolean closed;
    private int vao;
    private int meshVao;
    private int meshVaoVbo;
    private int meshVaoIbo;
    private VertexLayout meshVaoLayout;

    /** GL コンテキストが現在のスレッドにあることが前提。 */
    public GlDevice() {
        this(true, DEFAULT_RING);
    }

    /** {@code allowPersistent = false} なら対応環境でも {@code glBufferSubData} 経路を使う（テスト・回避用）。 */
    public GlDevice(boolean allowPersistent, int ringSize) {
        if (ringSize < 1) throw new IllegalArgumentException("ringSize: " + ringSize);
        GLCapabilities c = GL.getCapabilities();
        this.allowPersistent = allowPersistent;
        this.ringSize = ringSize;
        boolean persistent = allowPersistent && (c.OpenGL44 || c.GL_ARB_buffer_storage);
        boolean msaa = c.OpenGL30 || c.GL_EXT_framebuffer_multisample;
        int maxSamples = msaa ? Math.max(1, GL11.glGetInteger(GL30.GL_MAX_SAMPLES)) : 1;
        this.caps = new GpuCaps(persistent, c.OpenGL43, c.OpenGL43, c.OpenGL33 || c.GL_ARB_timer_query,
                GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE), msaa && maxSamples > 1, maxSamples, true, false);
    }

    /** 現在のコンテキストが永続マップを使えるか。 */
    public static boolean supportsPersistentMapping() {
        GLCapabilities c = GL.getCapabilities();
        return c.OpenGL44 || c.GL_ARB_buffer_storage;
    }

    @Override
    public GpuCaps caps() {
        return caps;
    }

    @Override
    public GpuBuffer createBuffer(BufferDesc desc) {
        if (closed) throw new IllegalStateException("closed");
        Buffer b = new Buffer(desc);
        buffers.add(b);
        return b;
    }

    @Override
    public GpuTexture createTexture(TextureDesc desc) {
        if (closed) throw new IllegalStateException("closed");
        if (desc.width() > caps.maxTextureSize() || desc.height() > caps.maxTextureSize()) {
            throw new IllegalArgumentException("texture too large: " + desc);
        }
        int samples = desc.samples() <= 1 ? 1 : Math.min(desc.samples(), caps.maxSamples());
        if (samples > 1 && !desc.renderTarget()) {
            throw new IllegalArgumentException("MSAA texture must be a renderTarget: " + desc);
        }
        if (desc.format() == TextureFormat.DEPTH24) {
            int rbo = GL30.glGenRenderbuffers();
            int prevRbo = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, rbo);
            if (samples > 1) GL30.glRenderbufferStorageMultisample(GL30.GL_RENDERBUFFER, samples, GL14.GL_DEPTH_COMPONENT24, desc.width(), desc.height());
            else GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL14.GL_DEPTH_COMPONENT24, desc.width(), desc.height());
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, prevRbo);
            return new Texture(0, rbo, desc.width(), desc.height(), desc.format(), samples, desc.renderTarget(), true);
        }
        int internal, format, type;
        switch (desc.format()) {
            case RGBA8 -> { internal = GL11.GL_RGBA8; format = GL11.GL_RGBA; type = GL11.GL_UNSIGNED_BYTE; }
            case R8 -> { internal = GL30.GL_R8; format = GL11.GL_RED; type = GL11.GL_UNSIGNED_BYTE; }
            case RGBA16F -> { internal = GL30.GL_RGBA16F; format = GL11.GL_RGBA; type = GL30.GL_HALF_FLOAT; }
            default -> throw new UnsupportedOperationException(desc.format().name());
        }
        if (samples > 1) {
            int rbo = GL30.glGenRenderbuffers();
            int prevRbo = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, rbo);
            GL30.glRenderbufferStorageMultisample(GL30.GL_RENDERBUFFER, samples, internal, desc.width(), desc.height());
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, prevRbo);
            return new Texture(0, rbo, desc.width(), desc.height(), desc.format(), samples, true, true);
        }
        int id = GL11.glGenTextures();
        int prev = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        int glFilter = desc.filter() == FilterMode.LINEAR ? GL11.GL_LINEAR : GL11.GL_NEAREST;
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internal, desc.width(), desc.height(), 0, format, type, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, glFilter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, glFilter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev);
        return new Texture(id, 0, desc.width(), desc.height(), desc.format(), 1, desc.renderTarget(), true);
    }

    @Override
    public GpuTexture wrapExternal(long nativeHandle, int width, int height) {
        return new Texture((int) nativeHandle, 0, width, height, TextureFormat.RGBA8, 1, false, false);
    }

    @Override
    public GpuPipeline createPipeline(PipelineDesc desc) {
        if (closed) throw new IllegalStateException("closed");
        return new Pipeline(desc, ShaderPrograms.byId(desc.shaderId()));
    }

    private int vao() {
        if (vao == 0) vao = GL30.glGenVertexArrays();
        return vao;
    }

    private int meshVao() {
        if (meshVao == 0) meshVao = GL30.glGenVertexArrays();
        return meshVao;
    }

    @Override
    public GpuFrame beginFrame() {
        if (closed) throw new IllegalStateException("closed");
        if (inFrame) throw new IllegalStateException("フレームが開いたまま");
        inFrame = true;
        fences.await(fences.current());
        return new Frame();
    }

    @Override
    public long nativeHandle(GpuTexture texture) {
        return ((Texture) texture).id;
    }

    @Override
    public long maxBufferBytes() {
        return (long) GL11.glGetInteger(GL31.GL_MAX_TEXTURE_BUFFER_SIZE) * 2L;
    }

    /** テスト・診断用: バッファが永続マップリングで動いているか。 */
    public boolean isPersistent(GpuBuffer buffer) {
        return ((Buffer) buffer).persistent;
    }

    /** テスト・診断用: リングの面の数・現在面・待ち回数・設置フェンス数。 */
    int ringSegments() { return fences.size(); }
    int currentSegment() { return fences.current(); }
    int fenceWaits() { return fences.waits(); }
    int fencesPlaced() { return fences.fencesPlaced(); }

    /** テスト用: パイプラインのサンプラーの値を設定した回数（最初の bind で 1 度だけ）。 */
    public int samplerUniformSets(GpuPipeline pipeline) {
        return ((Pipeline) pipeline).samplerSets;
    }

    /** テスト・診断用: バッファの GPU 側の内容を読む（最後に書き込んだ面）。 */
    public void readBuffer(GpuBuffer buffer, ByteBuffer dst) {
        readBuffer(buffer, dst, ((Buffer) buffer).lastFlushedSeg);
    }

    /** テスト・診断用: 指定リング面の GPU 側バッファ内容を読む。 */
    void readBuffer(GpuBuffer buffer, ByteBuffer dst, int seg) {
        Buffer b = (Buffer) buffer;
        int prev = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, b.buffers[seg]);
        GL15.glGetBufferSubData(GL15.GL_ARRAY_BUFFER, 0, dst);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prev);
    }

    int segmentCount(GpuBuffer buffer) { return ((Buffer) buffer).buffers.length; }
    void flushBuffersForTest() { int seg = fences.current(); for (Buffer b : buffers) if (!b.closed) b.flushSegment(seg); }
    void bindBufferViewForTest(GpuBuffer buffer, int unit, ViewFormat fmt) { ((Buffer) buffer).bindView(unit, fmt, fences.current()); }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        fences.close();
        for (Buffer b : buffers) b.close();
        buffers.clear();
        if (vao != 0) { GL30.glDeleteVertexArrays(vao); vao = 0; }
        if (meshVao != 0) { GL30.glDeleteVertexArrays(meshVao); meshVao = 0; }
    }

    // ------------------------------------------------------------------ 永続リングのフェンス

    private static final class FenceRing {
        private long[] fences = new long[1];
        private int cur;
        private boolean active;
        private int waits, fencesPlaced;

        void activate(int size) {
            if (active) return;
            fences = new long[size];
            cur = 0;
            active = true;
        }

        int current() { return cur; }
        int size() { return fences.length; }
        int waits() { return waits; }
        int fencesPlaced() { return fencesPlaced; }

        void await(int i) {
            if (!active || i >= fences.length || fences[i] == 0L) return;
            int r = GL32.glClientWaitSync(fences[i], GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 1_000_000_000L);
            if (r == GL32.GL_TIMEOUT_EXPIRED) throw new IllegalStateException("GPU が 1 秒以内に前フレームを読み終えない");
            GL32.glDeleteSync(fences[i]);
            fences[i] = 0L;
            waits++;
        }

        void end() {
            if (!active) return;
            if (fences[cur] != 0L) GL32.glDeleteSync(fences[cur]);
            fences[cur] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            fencesPlaced++;
            cur = (cur + 1) % fences.length;
        }

        void close() {
            for (int i = 0; i < fences.length; i++) {
                if (fences[i] != 0L) { GL32.glDeleteSync(fences[i]); fences[i] = 0L; }
            }
        }
    }

    // ------------------------------------------------------------------ パス

    private final class PassImpl implements GpuPass {
        private final Frame frame;
        private Pipeline boundPipeline;
        private boolean done;
        private boolean bound;

        PassImpl(Frame frame) {
            this.frame = frame;
        }

        @Override
        public void bind(GpuPipeline pipeline, GpuBindings bindings) {
            if (done) throw new IllegalStateException("終えたパス");
            Pipeline pl = (Pipeline) pipeline;
            ShaderProgram sh = pl.shader;
            if (bindings.textures().length < sh.samplers().size()) throw new IllegalArgumentException("テクスチャが足りない: " + sh.id());
            if (bindings.records().length < sh.recordBuffers()) throw new IllegalArgumentException("記録列のバッファが足りない: " + sh.id());
            int need = sh.uniformBytes();
            if (need > 0 && (bindings.uniforms() == null || bindings.uniforms().remaining() < need)) {
                throw new IllegalArgumentException("定数が足りない: " + sh.id() + " は " + need + " バイト");
            }
            GL20.glUseProgram(pl.program);
            if (pl.desc.premultipliedBlend()) {
                GL11.glEnable(GL11.GL_BLEND);
                GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            } else {
                GL11.glDisable(GL11.GL_BLEND);
            }
            if (pl.desc.depthTest()) {
                GL11.glEnable(GL11.GL_DEPTH_TEST);
                GL11.glDepthFunc(GL11.GL_LESS);
            } else {
                GL11.glDisable(GL11.GL_DEPTH_TEST);
            }
            GL11.glDepthMask(pl.desc.depthWrite());
            if (pl.desc.cullMode() == CullMode.BACK) {
                GL11.glEnable(GL11.GL_CULL_FACE);
                GL11.glFrontFace(GL11.GL_CCW);
                GL11.glCullFace(GL11.GL_BACK);
            } else {
                GL11.glDisable(GL11.GL_CULL_FACE);
            }

            for (int i = 0; i < sh.samplers().size(); i++) {
                Texture tex = (Texture) bindings.textures()[i];
                int unit = sh.firstTextureUnit() + i;
                if (frame != null) frame.saveUnit2D(unit);
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
                if (tex == null) {
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
                } else if (tex.id >= 0) {
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex.id);
                }
                if (!pl.samplersSet) { GL20.glUniform1i(pl.samplerLocs[i], unit); pl.samplerSets++; }
            }

            int seg = fences.current();
            for (int i = 0; i < bindings.records().length; i++) {
                if (bindings.records()[i] instanceof Buffer buf) {
                    buf.flushSegment(seg);
                }
            }
            for (RecordView v : sh.records()) {
                Buffer buf = (Buffer) bindings.records()[v.bufferIndex()];
                if (frame != null) frame.saveUnitBuf(v.unit());
                buf.bindView(v.unit(), v.format(), seg);
            }
            if (!pl.samplersSet) {
                for (int i = 0; i < sh.records().size(); i++) {
                    GL20.glUniform1i(pl.recordLocs[i], sh.records().get(i).unit());
                    pl.samplerSets++;
                }
                pl.samplersSet = true;
            }
            GL13.glActiveTexture(frame != null ? frame.prevActiveTexture : GL13.GL_TEXTURE0);

            if (need > 0) {
                uploadUbo(pl, bindings.uniforms(), need);
            }
            boundPipeline = pl;
            bound = true;
        }

        private void uploadUbo(Pipeline pl, ByteBuffer src, int activeBytes) {
            if (pl.ubo == 0 || pl.uboBlockBytes <= 0) return;
            int cap = pl.uboBlockBytes;
            if (uboScratch.capacity() < cap) uboScratch = BufferUtils.createByteBuffer(cap);
            uboScratch.clear().limit(cap);
            ByteBuffer dup = src.duplicate();
            dup.limit(dup.position() + activeBytes);
            uboScratch.put(dup);
            while (uboScratch.hasRemaining()) uboScratch.put((byte) 0);
            uboScratch.flip();
            GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, pl.ubo);
            GL15.glBufferData(GL31.GL_UNIFORM_BUFFER, uboScratch, GL15.GL_STREAM_DRAW);
            GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, 0, pl.ubo);
        }

        @Override
        public void bindMesh(GpuBuffer vertices, GpuBuffer indices) {
            if (done) throw new IllegalStateException("終えたパス");
            if (!bound) throw new IllegalStateException("パイプラインを結んでいない");
            if (vertices.usage() != BufferUsage.VERTEX || indices.usage() != BufferUsage.INDEX) {
                throw new IllegalArgumentException("頂点・インデックスバッファの種別が違う");
            }
            Buffer vb = (Buffer) vertices;
            Buffer ib = (Buffer) indices;
            // VAO の ELEMENT_ARRAY_BUFFER 結合を壊さないよう VAO を切り替える前に転送する
            GL30.glBindVertexArray(0);
            vb.flushSegment(0);
            ib.flushSegment(0);

            VertexLayout layout = boundPipeline.desc.vertexLayout();
            int vboId = vb.buffers[0];
            int iboId = ib.buffers[0];
            int mVao = meshVao();
            GL30.glBindVertexArray(mVao);
            if (meshVaoVbo != vboId || meshVaoIbo != iboId || !layout.equals(meshVaoLayout)) {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId);
                GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, iboId);
                for (VertexAttr attr : layout.attrs()) {
                    GL20.glEnableVertexAttribArray(attr.location());
                    GL20.glVertexAttribPointer(attr.location(), attr.components(), GL11.GL_FLOAT, false,
                            layout.strideBytes(), attr.offsetBytes());
                }
                meshVaoVbo = vboId;
                meshVaoIbo = iboId;
                meshVaoLayout = layout;
            }
        }

        @Override
        public void drawInstanced(int vertexCount, int instanceCount, int baseInstance) {
            if (done) throw new IllegalStateException("終えたパス");
            if (!bound) throw new IllegalStateException("パイプラインを結んでいない");
            if (baseInstance != 0) throw new UnsupportedOperationException("baseInstance は GL 3.2 に無い（シェーダーの定数で渡す）");
            if (frame != null) GL30.glBindVertexArray(vao());
            GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_STRIP, 0, vertexCount, instanceCount);
        }

        @Override
        public void drawIndexedInstanced(int indexCount, long indexOffsetBytes, int instanceCount, int baseVertex, int baseInstance) {
            if (done) throw new IllegalStateException("終えたパス");
            if (!bound) throw new IllegalStateException("パイプラインを結んでいない");
            if (baseInstance != 0) throw new UnsupportedOperationException("baseInstance は GL 3.2 に無い（シェーダーの定数で渡す）");
            if (baseVertex == 0) {
                GL31.glDrawElementsInstanced(GL11.GL_TRIANGLES, indexCount, GL11.GL_UNSIGNED_INT, indexOffsetBytes, instanceCount);
            } else {
                GL32.glDrawElementsInstancedBaseVertex(GL11.GL_TRIANGLES, indexCount, GL11.GL_UNSIGNED_INT, indexOffsetBytes, instanceCount, baseVertex);
            }
        }

        @Override
        public void end() {
            if (done) throw new IllegalStateException("終えたパス");
            done = true;
            if (frame != null) frame.passOpen = false;
        }
    }

    // ------------------------------------------------------------------ パイプライン

    private static final class Pipeline implements GpuPipeline {
        final PipelineDesc desc;
        final ShaderProgram shader;
        final int program;
        final int ubo;
        final int uboBlockBytes;
        final int[] samplerLocs, recordLocs;
        boolean samplersSet;
        int samplerSets;

        Pipeline(PipelineDesc desc, ShaderProgram shader) {
            this.desc = desc;
            this.shader = shader;
            int vs = compile(GL20.GL_VERTEX_SHADER, Glsl.gl150(shader.vertexBody()), shader.id());
            int fs = compile(GL20.GL_FRAGMENT_SHADER, Glsl.gl150(shader.fragmentBody()), shader.id());
            int p = GL20.glCreateProgram();
            GL20.glAttachShader(p, vs);
            GL20.glAttachShader(p, fs);
            for (VertexAttr attr : desc.vertexLayout().attrs()) {
                GL20.glBindAttribLocation(p, attr.location(), attr.name());
            }
            GL20.glLinkProgram(p);
            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);
            if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) {
                String log = GL20.glGetProgramInfoLog(p);
                GL20.glDeleteProgram(p);
                throw new IllegalStateException("シェーダーのリンクに失敗（" + shader.id() + "）: " + log);
            }
            this.program = p;
            int blockBytes = shader.uniformBlockBytes();
            if (blockBytes > 0) {
                int blockIdx = GL31.glGetUniformBlockIndex(p, "PassParams");
                if (blockIdx != GL31.GL_INVALID_INDEX) GL31.glUniformBlockBinding(p, blockIdx, 0);
                this.ubo = GL15.glGenBuffers();
                this.uboBlockBytes = blockBytes;
            } else {
                this.ubo = 0;
                this.uboBlockBytes = 0;
            }
            this.samplerLocs = new int[shader.samplers().size()];
            for (int i = 0; i < samplerLocs.length; i++) samplerLocs[i] = GL20.glGetUniformLocation(p, shader.samplers().get(i));
            this.recordLocs = new int[shader.records().size()];
            for (int i = 0; i < recordLocs.length; i++) recordLocs[i] = GL20.glGetUniformLocation(p, shader.records().get(i).sampler());
        }

        private static int compile(int type, String src, String id) {
            int sh = GL20.glCreateShader(type);
            GL20.glShaderSource(sh, src);
            GL20.glCompileShader(sh);
            if (GL20.glGetShaderi(sh, GL20.GL_COMPILE_STATUS) == 0) {
                String log = GL20.glGetShaderInfoLog(sh);
                GL20.glDeleteShader(sh);
                throw new IllegalStateException("シェーダーのコンパイルに失敗（" + id + "）: " + log);
            }
            return sh;
        }

        @Override public PipelineDesc desc() { return desc; }
        @Override public void close() {
            if (ubo != 0) GL15.glDeleteBuffers(ubo);
            GL20.glDeleteProgram(program);
        }
    }

    // ------------------------------------------------------------------ バッファ（永続 3 面リング統合）

    private final class Buffer implements GpuBuffer {
        final BufferDesc desc;
        final ByteBuffer shadow;
        final int[] buffers;
        final ByteBuffer[] maps;
        final boolean persistent;
        final int[] dirtyLo, dirtyHi;
        final int[] floatViews, intViews, halfViews, rgbaFloatViews, rgbaIntViews;
        boolean explicitDirty;
        int lastFlushedSeg;
        boolean closed;

        Buffer(BufferDesc desc) {
            if (desc.size() > Integer.MAX_VALUE - 64) throw new IllegalArgumentException("size: " + desc.size());
            this.desc = desc;
            int bytes = (int) desc.size();
            ByteBuffer raw = BufferUtils.createByteBuffer(bytes + 64);
            int off = (64 - raw.alignmentOffset(0, 64)) & 63;
            this.shadow = raw.slice(off, bytes).order(ByteOrder.nativeOrder());

            int prev = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            boolean useRing = desc.usage() == BufferUsage.RECORD && allowPersistent && supportsPersistentMapping();
            int[] bufs = null;
            ByteBuffer[] mapped = null;
            boolean ok = false;
            if (useRing) {
                bufs = new int[ringSize];
                mapped = new ByteBuffer[ringSize];
                GLCapabilities c = GL.getCapabilities();
                ok = true;
                for (int i = 0; i < ringSize; i++) {
                    bufs[i] = GL15.glGenBuffers();
                    GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, bufs[i]);
                    if (c.OpenGL44) GL44.glBufferStorage(GL15.GL_ARRAY_BUFFER, bytes, STORAGE_FLAGS);
                    else ARBBufferStorage.glBufferStorage(GL15.GL_ARRAY_BUFFER, bytes, STORAGE_FLAGS);
                    ByteBuffer m = GL30.glMapBufferRange(GL15.GL_ARRAY_BUFFER, 0, bytes, MAP_FLAGS);
                    if (m == null) { ok = false; break; }
                    for (int k = 0; k < bytes; k++) m.put(k, (byte) 0);
                    mapped[i] = m;
                }
                if (!ok) {
                    for (int i = 0; i < ringSize; i++) {
                        if (bufs[i] == 0) continue;
                        if (mapped[i] != null) { GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, bufs[i]); GL15.glUnmapBuffer(GL15.GL_ARRAY_BUFFER); }
                        GL15.glDeleteBuffers(bufs[i]);
                    }
                }
            }
            if (ok) {
                this.persistent = true;
                this.buffers = bufs;
                this.maps = mapped;
                fences.activate(ringSize);
            } else {
                this.persistent = false;
                this.buffers = new int[]{GL15.glGenBuffers()};
                this.maps = null;
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.buffers[0]);
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, shadow.duplicate(), GL15.GL_DYNAMIC_DRAW);
            }
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prev);

            int n = this.buffers.length;
            this.dirtyLo = new int[n];
            this.dirtyHi = new int[n];
            java.util.Arrays.fill(this.dirtyLo, Integer.MAX_VALUE);
            this.floatViews = new int[n];
            this.intViews = new int[n];
            this.halfViews = new int[n];
            this.rgbaFloatViews = new int[n];
            this.rgbaIntViews = new int[n];
        }

        @Override
        public void markDirty(long offset, long length) {
            if (offset < 0 || length < 0 || offset + length > desc.size()) {
                throw new IllegalArgumentException("dirty range out of bounds: " + offset + " + " + length + " > " + desc.size());
            }
            explicitDirty = true;
            if (length == 0) return;
            int lo = (int) offset;
            int hi = (int) (offset + length);
            for (int s = 0; s < dirtyLo.length; s++) {
                if (lo < dirtyLo[s]) dirtyLo[s] = lo;
                if (hi > dirtyHi[s]) dirtyHi[s] = hi;
            }
        }

        void flushSegment(int cur) {
            int seg = persistent ? cur : 0;
            if (!explicitDirty) {
                copyToGpu(seg, 0, (int) desc.size());
                lastFlushedSeg = seg;
                return;
            }
            int lo = dirtyLo[seg], hi = dirtyHi[seg];
            if (hi <= lo) return;
            copyToGpu(seg, lo, hi - lo);
            dirtyLo[seg] = Integer.MAX_VALUE;
            dirtyHi[seg] = 0;
            lastFlushedSeg = seg;
        }

        private void copyToGpu(int seg, int offset, int length) {
            if (persistent) {
                maps[seg].put(offset, shadow, offset, length);
            } else {
                int prev = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffers[seg]);
                ByteBuffer view = shadow.duplicate().order(shadow.order());
                view.limit(offset + length).position(offset);
                GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, offset, view);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prev);
            }
        }

        void bindView(int unit, ViewFormat format, int cur) {
            int seg = persistent ? cur : 0;
            int tex = switch (format) {
                case FLOAT32 -> floatViews[seg] != 0 ? floatViews[seg] : (floatViews[seg] = newView(GL30.GL_R32F, seg));
                case INT32 -> intViews[seg] != 0 ? intViews[seg] : (intViews[seg] = newView(GL30.GL_R32I, seg));
                case HALF16 -> halfViews[seg] != 0 ? halfViews[seg] : (halfViews[seg] = newView(GL30.GL_R16F, seg));
                case RGBA_FLOAT32 -> rgbaFloatViews[seg] != 0 ? rgbaFloatViews[seg] : (rgbaFloatViews[seg] = newView(GL30.GL_RGBA32F, seg));
                case RGBA_INT32 -> rgbaIntViews[seg] != 0 ? rgbaIntViews[seg] : (rgbaIntViews[seg] = newView(GL30.GL_RGBA32I, seg));
            };
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, tex);
        }

        private int newView(int internalFormat, int seg) {
            int prevTex = GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER);
            int tex = GL11.glGenTextures();
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, tex);
            GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, internalFormat, buffers[seg]);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, prevTex);
            return tex;
        }

        @Override public long size() { return desc.size(); }
        @Override public BufferUsage usage() { return desc.usage(); }
        @Override public ByteBuffer map() { return shadow; }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            int prev = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            for (int i = 0; i < buffers.length; i++) {
                if (floatViews[i] != 0) GL11.glDeleteTextures(floatViews[i]);
                if (intViews[i] != 0) GL11.glDeleteTextures(intViews[i]);
                if (halfViews[i] != 0) GL11.glDeleteTextures(halfViews[i]);
                if (rgbaFloatViews[i] != 0) GL11.glDeleteTextures(rgbaFloatViews[i]);
                if (rgbaIntViews[i] != 0) GL11.glDeleteTextures(rgbaIntViews[i]);
                if (buffers[i] != 0) {
                    if (persistent) {
                        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffers[i]);
                        GL15.glUnmapBuffer(GL15.GL_ARRAY_BUFFER);
                    }
                    GL15.glDeleteBuffers(buffers[i]);
                    buffers[i] = 0;
                }
            }
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prev);
        }
    }

    // ------------------------------------------------------------------ テクスチャ・レンダーターゲット

    private static final class Texture implements GpuTexture {
        final int id, rboId, w, h, samples;
        final TextureFormat format;
        final boolean renderTarget, owned;
        int fbo;
        int depthFbo;
        int attachedDepthRbo;

        Texture(int id, int rboId, int w, int h, TextureFormat format, int samples, boolean renderTarget, boolean owned) {
            this.id = id;
            this.rboId = rboId;
            this.w = w;
            this.h = h;
            this.format = format;
            this.samples = samples;
            this.renderTarget = renderTarget;
            this.owned = owned;
        }

        int fbo(Texture depth) {
            if (depth == null) {
                if (fbo == 0) fbo = createFbo(null);
                return fbo;
            }
            if (depthFbo == 0) {
                depthFbo = createFbo(depth);
                attachedDepthRbo = depth.rboId;
            } else if (attachedDepthRbo != depth.rboId) {
                int prev = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, depthFbo);
                GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_RENDERBUFFER, depth.rboId);
                attachedDepthRbo = depth.rboId;
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prev);
            }
            return depthFbo;
        }

        private int createFbo(Texture depth) {
            int prev = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
            int f = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, f);
            if (rboId != 0) {
                GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_RENDERBUFFER, rboId);
            } else {
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, id, 0);
            }
            if (depth != null) {
                GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_RENDERBUFFER, depth.rboId);
            }
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prev);
                GL30.glDeleteFramebuffers(f);
                throw new IllegalStateException("描画先にできないテクスチャ: " + format);
            }
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prev);
            return f;
        }

        void contentOk() {
            if ((format != TextureFormat.RGBA8 && format != TextureFormat.R8) || samples > 1) {
                throw new UnsupportedOperationException("内容を読み書きできない形式: " + format);
            }
        }

        /** コピー・MSAA の解決は形式を問わない画素の移動なので、内容を読み書きできない RGBA16F（Bloom の HDR）も通す。 */
        void copyableOk() {
            if (format != TextureFormat.RGBA8 && format != TextureFormat.R8 && format != TextureFormat.RGBA16F) {
                throw new UnsupportedOperationException("内容を読み書きできない形式: " + format);
            }
        }

        @Override public int width() { return w; }
        @Override public int height() { return h; }
        @Override public TextureFormat format() { return format; }

        @Override
        public void close() {
            if (fbo != 0) { GL30.glDeleteFramebuffers(fbo); fbo = 0; }
            if (depthFbo != 0) { GL30.glDeleteFramebuffers(depthFbo); depthFbo = 0; }
            if (owned) {
                if (id != 0) GL11.glDeleteTextures(id);
                if (rboId != 0) GL30.glDeleteRenderbuffers(rboId);
            }
        }
    }

    private static void checkRegion(Texture t, int x, int y, int w, int h) {
        if (x < 0 || y < 0 || w < 0 || h < 0 || x + w > t.w || y + h > t.h) {
            throw new IllegalArgumentException("領域がテクスチャの外: " + x + "," + y + " " + w + "x" + h + " in " + t.w + "x" + t.h);
        }
    }

    /** 行を逆順に並べ替えたコピー（左上原点 ⇔ GL の下端原点。スクラッチを再利用）。 */
    private ByteBuffer flipRows(ByteBuffer src, int rowBytes, int rows) {
        int total = rowBytes * rows;
        if (scratchFlipBuf == null || scratchFlipBuf.capacity() < total) {
            scratchFlipBuf = BufferUtils.createByteBuffer(total);
        }
        if (scratchFlipRow == null || scratchFlipRow.length < rowBytes) {
            scratchFlipRow = new byte[rowBytes];
        }
        ByteBuffer out = scratchFlipBuf;
        out.clear().limit(total);
        ByteBuffer s = src.duplicate();
        int base = s.position();
        for (int r = 0; r < rows; r++) {
            s.position(base + (rows - 1 - r) * rowBytes);
            s.get(scratchFlipRow, 0, rowBytes);
            out.put(scratchFlipRow, 0, rowBytes);
        }
        out.flip();
        return out;
    }

    // ------------------------------------------------------------------ フレーム

    private final class Frame implements GpuFrame {
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
        private final int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        private final int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        private final int prevArrayBuf = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        private final int prevElementBuf = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        private final int prevUniformBuf = GL11.glGetInteger(GL31.GL_UNIFORM_BUFFER_BINDING);
        private final int prevUniformBuf0 = GL30.glGetIntegeri(GL31.GL_UNIFORM_BUFFER_BINDING, 0);
        private final boolean prevBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        private final int[] prevBlendFunc = {GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),
                GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA)};
        private final boolean prevDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        private final boolean prevDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        private final boolean prevCullFace = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        private final int prevCullMode = GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);
        private final int prevFrontFace = GL11.glGetInteger(GL11.GL_FRONT_FACE);
        final int prevActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        private final int[] prevUnitTex = new int[32];
        private final int[] prevUnitBufTex = new int[32];
        private int touched2dMask;
        private int touchedBufMask;
        boolean passOpen;
        private boolean ended;

        Frame() {
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, prevViewport);
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, prevScissor);
            GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, prevClear);
            scratchMask.clear();
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, scratchMask);
            for (int i = 0; i < 4; i++) prevMask[i] = scratchMask.get(i) != 0;
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
        }

        void saveUnit2D(int unit) {
            if (unit >= 32) return;
            int bit = 1 << unit;
            if ((touched2dMask & bit) == 0) {
                touched2dMask |= bit;
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
                prevUnitTex[unit] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            }
        }

        void saveUnitBuf(int unit) {
            if (unit >= 32) return;
            int bit = 1 << unit;
            if ((touchedBufMask & bit) == 0) {
                touchedBufMask |= bit;
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
                prevUnitBufTex[unit] = GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER);
            }
        }

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
            Texture dt = (Texture) depthTarget;
            if (dt != null && (dt.format != TextureFormat.DEPTH24 || dt.w != t.w || dt.h != t.h)) {
                throw new IllegalArgumentException("深度ターゲットが一致しない");
            }
            int[] sc = scissor == null ? new int[]{0, 0, t.w, t.h} : scissor;
            checkRegion(t, sc[0], sc[1], sc[2], sc[3]);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, t.fbo(dt));
            GL11.glViewport(0, 0, t.w, t.h);
            if (load == LoadOp.CLEAR) {
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
                GL11.glScissor(sc[0], t.h - (sc[1] + sc[3]), sc[2], sc[3]);
                GL11.glColorMask(true, true, true, true);
                GL11.glClearColor(((clearArgb >> 16) & 0xFF) / 255f, ((clearArgb >> 8) & 0xFF) / 255f,
                        (clearArgb & 0xFF) / 255f, ((clearArgb >>> 24) & 0xFF) / 255f);
                if (dt != null) {
                    GL11.glDepthMask(true);
                    GL11.glClearDepth(clearDepth);
                    GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
                } else {
                    GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
                }
            }
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(sc[0], t.h - (sc[1] + sc[3]), sc[2], sc[3]);
            passOpen = true;
            return new PassImpl(this);
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
            s.copyableOk();
            if (s.format != d.format) throw new IllegalArgumentException("形式が違う: " + s.format + " / " + d.format);
            checkRegion(s, region[0], region[1], region[2], region[3]);
            checkRegion(d, region[0], region[1], region[2], region[3]);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, s.fbo(null));
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, d.fbo(null));
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
            if (scratchFlipBuf == null || scratchFlipBuf.capacity() < row * region[3]) {
                scratchFlipBuf = BufferUtils.createByteBuffer(row * region[3]);
            }
            ByteBuffer tmp = BufferUtils.createByteBuffer(row * region[3]);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, s.fbo(null));
            GL11.glReadPixels(region[0], s.h - (region[1] + region[3]), region[2], region[3],
                    s.format == TextureFormat.R8 ? GL11.GL_RED : GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, tmp);
            dst.put(flipRows(tmp, row, region[3]));
        }

        @Override
        public void end() {
            live();
            ended = true;
            inFrame = false;
            int seg = fences.current();
            for (Buffer b : buffers) if (!b.closed) b.flushSegment(seg);
            fences.end();

            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDrawFbo);
            GL11.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
            if (prevScissorTest) GL11.glEnable(GL11.GL_SCISSOR_TEST); else GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(prevScissor[0], prevScissor[1], prevScissor[2], prevScissor[3]);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, prevAlign);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, prevPackAlign);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, prevUnpackRow);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, prevPackRow);
            for (int u = 0; u < 32; u++) {
                int bit = 1 << u;
                if ((touched2dMask & bit) != 0) {
                    GL13.glActiveTexture(GL13.GL_TEXTURE0 + u);
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevUnitTex[u]);
                }
                if ((touchedBufMask & bit) != 0) {
                    GL13.glActiveTexture(GL13.GL_TEXTURE0 + u);
                    GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, prevUnitBufTex[u]);
                }
            }
            GL13.glActiveTexture(prevActiveTexture);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
            GL20.glUseProgram(prevProgram);
            GL30.glBindVertexArray(prevVao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevArrayBuf);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, prevElementBuf);
            GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, 0, prevUniformBuf0);
            GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, prevUniformBuf);
            if (prevBlend) GL11.glEnable(GL11.GL_BLEND); else GL11.glDisable(GL11.GL_BLEND);
            GL14.glBlendFuncSeparate(prevBlendFunc[0], prevBlendFunc[1], prevBlendFunc[2], prevBlendFunc[3]);
            if (prevDepthTest) GL11.glEnable(GL11.GL_DEPTH_TEST); else GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(prevDepthMask);
            if (prevCullFace) GL11.glEnable(GL11.GL_CULL_FACE); else GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glCullFace(prevCullMode);
            GL11.glFrontFace(prevFrontFace);
            GL11.glClearColor(prevClear[0], prevClear[1], prevClear[2], prevClear[3]);
            GL11.glColorMask(prevMask[0], prevMask[1], prevMask[2], prevMask[3]);
            GL11.glFlush();
        }
    }
}
