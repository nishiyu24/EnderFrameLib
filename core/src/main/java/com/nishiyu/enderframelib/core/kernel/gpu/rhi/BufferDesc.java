package com.nishiyu.enderframelib.core.kernel.gpu.rhi;

public record BufferDesc(long size, BufferUsage usage) {
    public BufferDesc {
        if (size <= 0) throw new IllegalArgumentException("size: " + size);
    }
}
