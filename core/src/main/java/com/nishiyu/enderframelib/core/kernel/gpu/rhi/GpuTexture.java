package com.nishiyu.enderframelib.core.kernel.gpu.rhi;

/** テクスチャ（描画先にもなる）。画素は左上原点・事前乗算アルファ。 */
public interface GpuTexture extends AutoCloseable {
    int width();

    int height();

    TextureFormat format();

    @Override
    void close();
}
