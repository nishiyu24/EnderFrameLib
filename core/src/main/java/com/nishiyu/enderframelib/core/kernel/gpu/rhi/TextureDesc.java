package com.nishiyu.enderframelib.core.kernel.gpu.rhi;

public record TextureDesc(int width, int height, TextureFormat format, boolean renderTarget, int samples, FilterMode filter) {
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
