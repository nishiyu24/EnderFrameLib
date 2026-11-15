package com.nishiyu.enderframelib.core.kernel.gpu.rhi;

/** シェーダー・ブレンド・深度・描画先の形式を前もって固定したもの。作ったあとは変えない。 */
public interface GpuPipeline extends AutoCloseable {
    GpuDevice.PipelineDesc desc();

    @Override
    void close();
}
