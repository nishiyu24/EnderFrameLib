package com.nishiyu.enderframelib.core.kernel.gpu.rhi;

/**
 * 描画 API 中立のデバイス（ENGINE_REARCHITECTURE_PLAN G1）。OpenGL・Vulkan の型は一切出さない。
 *
 * <p>設計規則: ①粒度は「パス」と「バッチ」（図形ごとの口は無い）②状態は明示（ブレンド・シザー・描画先は記述子と引数で渡す。
 * 「現在の GL 状態」に依らない）③座標は左上原点・事前乗算アルファで 1 つ（API ごとの違いはデバイス実装が吸収する）
 * ④デバイスの呼び出しは 1 本の描画スレッドから。</p>
 */
public interface GpuDevice extends AutoCloseable {
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
