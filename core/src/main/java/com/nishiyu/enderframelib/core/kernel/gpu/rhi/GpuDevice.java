package com.nishiyu.enderframelib.core.kernel.gpu.rhi;

/**
 * 描画 API 中立のデバイス（ENGINE_REARCHITECTURE_PLAN G1）。OpenGL・Vulkan の型は一切出さない。
 *
 * <p>設計規則: ①粒度は「パス」と「バッチ」（図形ごとの口は無い）②状態は明示（ブレンド・シザー・描画先は記述子と引数で渡す。
 * 「現在の GL 状態」に依らない）③座標は左上原点・事前乗算アルファで 1 つ（API ごとの違いはデバイス実装が吸収する）
 * ④デバイスの呼び出しは 1 本の描画スレッドから。</p>
 */
public interface GpuDevice extends AutoCloseable {
    enum BufferUsage { RECORD, UNIFORM, STAGING, VERTEX, INDEX }

    enum FilterMode { NEAREST, LINEAR }

    enum CullMode { NONE, BACK }

    /** {@code RGBA8}・{@code R8}・{@code RGBA16F}・{@code DEPTH24}。内容を読み書きする命令は RGBA8 と R8 だけ。 */
    enum TextureFormat {
        RGBA8(4), R8(1), RGBA16F(8), DEPTH24(4);

        public final int bytesPerPixel;

        TextureFormat(int bytesPerPixel) {
            this.bytesPerPixel = bytesPerPixel;
        }
    }

    enum LoadOp { LOAD, CLEAR }

    record BufferDesc(long size, BufferUsage usage) {
        public BufferDesc {
            if (size <= 0) throw new IllegalArgumentException("size: " + size);
        }
    }

    record TextureDesc(int width, int height, TextureFormat format, boolean renderTarget, int samples, FilterMode filter) {
        public TextureDesc {
            if (width <= 0 || height <= 0) throw new IllegalArgumentException("size: " + width + "x" + height);
            if (samples < 1) throw new IllegalArgumentException("samples: " + samples);
            if (filter == null) filter = FilterMode.NEAREST;
        }

        public TextureDesc(int width, int height, TextureFormat format, boolean renderTarget, int samples) {
            this(width, height, format, renderTarget, samples, FilterMode.NEAREST);
        }

        public static TextureDesc rgba8(int w, int h, boolean renderTarget) {
            return new TextureDesc(w, h, TextureFormat.RGBA8, renderTarget, 1, FilterMode.NEAREST);
        }

        public static TextureDesc rgba8Linear(int w, int h, boolean renderTarget) {
            return new TextureDesc(w, h, TextureFormat.RGBA8, renderTarget, 1, FilterMode.LINEAR);
        }

        public static TextureDesc r8(int w, int h) {
            return new TextureDesc(w, h, TextureFormat.R8, false, 1, FilterMode.NEAREST);
        }
    }

    record VertexAttr(int location, String name, int components, int offsetBytes) {
    }

    record VertexLayout(int strideBytes, VertexAttr... attrs) {
        public static final VertexLayout NONE = new VertexLayout(0);
        public static final VertexLayout SCENE3D = new VertexLayout(48,
                new VertexAttr(0, "aPos", 3, 0),
                new VertexAttr(1, "aNorm", 3, 12),
                new VertexAttr(2, "aUv", 2, 24),
                new VertexAttr(3, "aTangent", 4, 32));
    }

    /** {@code shaderId} は G2 で集めるシェーダー源の名前。{@code premultipliedBlend} は事前乗算アルファの合成。 */
    record PipelineDesc(String shaderId, boolean premultipliedBlend, boolean depthTest, boolean depthWrite,
                        CullMode cullMode, VertexLayout vertexLayout) {
        public PipelineDesc {
            if (cullMode == null) cullMode = CullMode.NONE;
            if (vertexLayout == null) vertexLayout = VertexLayout.NONE;
        }

        public PipelineDesc(String shaderId, boolean premultipliedBlend, boolean depthTest) {
            this(shaderId, premultipliedBlend, depthTest, false, CullMode.NONE, VertexLayout.NONE);
        }
    }

    GpuCaps caps();

    GpuBuffer createBuffer(BufferDesc desc);

    GpuTexture createTexture(TextureDesc desc);

    /** ホストが持つテクスチャを包む（コピーなし。閉じてもホストのテクスチャは消さない）。 */
    GpuTexture wrapExternal(long nativeHandle, int width, int height);

    GpuPipeline createPipeline(PipelineDesc desc);

    /** リングの面を進める。フェンス待ちはここだけ。 */
    GpuFrame beginFrame();

    /** 結果をホストへ渡すための、API 固有のテクスチャ名（{@code TextureHandle} の中身）。 */
    long nativeHandle(GpuTexture texture);

    /** 記録列（texel buffer / SSBO）1 本に載せられる最大バイト数。 */
    default long maxBufferBytes() {
        return 64L * 1024 * 1024;
    }

    @Override
    void close();
}
