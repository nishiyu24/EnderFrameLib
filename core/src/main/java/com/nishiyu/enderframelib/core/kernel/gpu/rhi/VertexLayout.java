package com.nishiyu.enderframelib.core.kernel.gpu.rhi;

public record VertexLayout(int strideBytes, VertexAttr... attrs) {
    public static final VertexLayout NONE = new VertexLayout(0);
    public static final VertexLayout SCENE3D = new VertexLayout(48,
            new VertexAttr(0, "aPos", 3, 0),
            new VertexAttr(1, "aNorm", 3, 12),
            new VertexAttr(2, "aUv", 2, 24));
}
