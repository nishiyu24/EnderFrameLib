package com.nishiyu.enderframelib.core.kernel.gpu.rhi;

/** {@code shaderId} は G2 で集めるシェーダー源の名前。{@code premultipliedBlend} は事前乗算アルファの合成。 */
public record PipelineDesc(String shaderId, boolean premultipliedBlend, boolean depthTest, boolean depthWrite,
                           CullMode cullMode, VertexLayout vertexLayout) {
    public PipelineDesc {
        if (cullMode == null) cullMode = CullMode.NONE;
        if (vertexLayout == null) vertexLayout = VertexLayout.NONE;
    }

    public PipelineDesc(String shaderId, boolean premultipliedBlend, boolean depthTest) {
        this(shaderId, premultipliedBlend, depthTest, false, CullMode.NONE, VertexLayout.NONE);
    }
}
