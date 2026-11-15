package com.nishiyu.enderframelib.core.paint.gl;

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
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL31;
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
        if (desc.depthTest()) throw new UnsupportedOperationException("深度テストは後の段（G1 段 3: 3D の RHI 化）");
        return new Pipeline(desc, ShaderPrograms.byId(desc.shaderId()));
    }

    /** 描画に使う VAO（コア・プロファイルは VAO 無しで描けない）。頂点は {@code gl_VertexID} から作るので属性は無い。 */
    private int vao;

    private int vao() {
        if (vao == 0) vao = GL30.glGenVertexArrays();
        return vao;
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

    /**
     * GL 固有: テクスチャユニット {@code unit} に、呼び出し側が既に結んでいる 2D テクスチャを指す（パスは結び直さない）。
     * バックエンドが字形アトラスや画像を自分で結んでいる間に、描画だけを RHI へ移すための橋（段 3）。
     */
    public GpuTexture wrapCurrentUnit(int unit) {
        return new Texture(-1, 0, 0, TextureFormat.RGBA8, false);
    }

    /** テスト用: パイプラインのサンプラーの値を設定した回数（最初の bind で 1 度だけ）。 */
    public int samplerUniformSets(GpuPipeline pipeline) {
        return ((Pipeline) pipeline).samplerSets;
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

    /**
     * GL 固有: 今結ばれている描画先（FBO・ビューポート・シザーはそのまま）に描くパス。{@code LwjglGlRenderBackend} が自分で描画先を
     * 管理している間に、描画だけを RHI へ移すための橋（段 3）。GL の状態の退避と復元は呼び出し側の責任で、ここは何もしない。
     */
    public GpuPass passOnCurrentFramebuffer() {
        return new PassImpl(null);
    }

    /** パスの実体。{@code frame} が null なら呼び出し側が GL の状態を持つ（退避なし）。 */
    private final class PassImpl implements GpuPass {
        private final Frame frame;
        private boolean done;
        private boolean bound;

        PassImpl(Frame frame) {
            this.frame = frame;
        }

        @Override
        public void bind(GpuPipeline pipeline, Bindings bindings) {
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
            // ブレンドは記述子で決める（現在の GL 状態に依らない）
            if (pl.desc.premultipliedBlend()) {
                GL11.glEnable(GL11.GL_BLEND);
                GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            } else {
                GL11.glDisable(GL11.GL_BLEND);
            }
            for (int i = 0; i < sh.samplers().size(); i++) {
                Texture tex = (Texture) bindings.textures()[i];
                int unit = sh.firstTextureUnit() + i;
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
                if (frame != null && unit >= frame.unitsTouched && unit < frame.prevUnitTex.length) {
                    // 初めて触るユニットは、結ぶ前の状態を覚える（飛ばしたユニットは触っていないので覚えない）
                    for (int k = frame.unitsTouched; k <= unit; k++) {
                        GL13.glActiveTexture(GL13.GL_TEXTURE0 + k);
                        frame.prevUnitTex[k] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                    }
                    frame.unitsTouched = unit + 1;
                    GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
                }
                if (tex.id >= 0) GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex.id); // 呼び出し側が結んでいるもの（wrapCurrentUnit）は触らない
                if (!pl.samplersSet) { GL20.glUniform1i(pl.samplerLocs[i], unit); pl.samplerSets++; }
            }
            // 記録列: バッファごとの読み方（ビュー）を、宣言されたユニットへ結ぶ
            for (ShaderProgram.RecordView v : sh.records()) {
                GlVramDevice buf = (GlVramDevice) bindings.records()[v.bufferIndex()];
                switch (v.format()) {
                    case FLOAT32 -> buf.bindFloatView(v.unit());
                    case INT32 -> buf.bindIntView(v.unit());
                    case HALF16 -> buf.bindHalfView(v.unit());
                }
            }
            if (!pl.samplersSet) {
                for (int i = 0; i < sh.records().size(); i++) { GL20.glUniform1i(pl.recordLocs[i], sh.records().get(i).unit()); pl.samplerSets++; }
                pl.samplersSet = true;
            }
            GL13.glActiveTexture(frame != null ? frame.prevActiveTexture : GL13.GL_TEXTURE0);
            if (need > 0) {
                ByteBuffer u = bindings.uniforms().duplicate().order(ByteOrder.nativeOrder());
                int o = u.position();
                for (int i = 0; i < sh.uniforms().size(); i++) {
                    int loc = pl.uniformLocs[i];
                    switch (sh.uniforms().get(i).type()) {
                        case FLOAT -> { float a = u.getFloat(o); if (loc >= 0) GL20.glUniform1f(loc, a); o += 4; }
                        case INT -> { int a = u.getInt(o); if (loc >= 0) GL20.glUniform1i(loc, a); o += 4; }
                        case VEC2 -> { float a = u.getFloat(o), b = u.getFloat(o + 4); if (loc >= 0) GL20.glUniform2f(loc, a, b); o += 8; }
                        case VEC4 -> {
                            float a = u.getFloat(o), b = u.getFloat(o + 4), c = u.getFloat(o + 8), d = u.getFloat(o + 12);
                            if (loc >= 0) GL20.glUniform4f(loc, a, b, c, d);
                            o += 16;
                        }
                    }
                }
            }
            bound = true;
        }

        @Override
        public void drawInstanced(int vertexCount, int instanceCount, int baseInstance) {
            if (done) throw new IllegalStateException("終えたパス");
            if (!bound) throw new IllegalStateException("パイプラインを結んでいない");
            if (baseInstance != 0) throw new UnsupportedOperationException("baseInstance は GL 3.2 に無い（シェーダーの定数で渡す）");
            if (frame != null) GL30.glBindVertexArray(vao()); // 呼び出し側が VAO を持つ（passOnCurrentFramebuffer）ときは触らない
            GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_STRIP, 0, vertexCount, instanceCount);
        }

        @Override
        public void end() {
            if (done) throw new IllegalStateException("終えたパス");
            done = true;
            if (frame != null) frame.passOpen = false;
        }
    }

    private static final class Pipeline implements GpuPipeline {
        final PipelineDesc desc;
        final ShaderProgram shader;
        final int program;
        final int[] uniformLocs, samplerLocs, recordLocs;
        /** サンプラーの値（ユニット）はプログラムに残るので、最初の bind で 1 度だけ設定する。 */
        boolean samplersSet;
        /** 設定したサンプラーの数（テスト用: 描画を重ねても増えない）。 */
        int samplerSets;

        Pipeline(PipelineDesc desc, ShaderProgram shader) {
            this.desc = desc;
            this.shader = shader;
            int vs = compile(GL20.GL_VERTEX_SHADER, Glsl.gl150(shader.vertexBody()), shader.id());
            int fs = compile(GL20.GL_FRAGMENT_SHADER, Glsl.gl150(shader.fragmentBody()), shader.id());
            int p = GL20.glCreateProgram();
            GL20.glAttachShader(p, vs);
            GL20.glAttachShader(p, fs);
            GL20.glLinkProgram(p);
            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);
            if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) {
                String log = GL20.glGetProgramInfoLog(p);
                GL20.glDeleteProgram(p);
                throw new IllegalStateException("シェーダーのリンクに失敗（" + shader.id() + "）: " + log);
            }
            this.program = p;
            this.uniformLocs = new int[shader.uniforms().size()];
            for (int i = 0; i < uniformLocs.length; i++) uniformLocs[i] = GL20.glGetUniformLocation(p, shader.uniforms().get(i).name());
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
        @Override public void close() { GL20.glDeleteProgram(program); }
    }

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
        private final int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        private final int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        private final boolean prevBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        private final int[] prevBlendFunc = {GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),
                GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA)};
        final int prevActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        /** 描画で結んだテクスチャユニットの、結ぶ前の 2D テクスチャ（{@code end()} で戻す）。 */
        final int[] prevUnitTex = new int[16];
        int unitsTouched;
        boolean passOpen;
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
            for (int i = 0; i < unitsTouched; i++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevUnitTex[i]);
            }
            GL13.glActiveTexture(prevActiveTexture);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex); // 作業中に結んだ「現在のユニット」の分を戻す
            GL20.glUseProgram(prevProgram);
            GL30.glBindVertexArray(prevVao);
            if (prevBlend) GL11.glEnable(GL11.GL_BLEND); else GL11.glDisable(GL11.GL_BLEND);
            GL14.glBlendFuncSeparate(prevBlendFunc[0], prevBlendFunc[1], prevBlendFunc[2], prevBlendFunc[3]);
            GL11.glClearColor(prevClear[0], prevClear[1], prevClear[2], prevClear[3]);
            GL11.glColorMask(prevMask[0], prevMask[1], prevMask[2], prevMask[3]);
            GL11.glFlush();
        }
    }
}
