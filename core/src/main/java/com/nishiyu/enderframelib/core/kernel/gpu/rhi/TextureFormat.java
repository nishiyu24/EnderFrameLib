package com.nishiyu.enderframelib.core.kernel.gpu.rhi;

/** {@code RGBA8}・{@code R8}・{@code RGBA16F}・{@code DEPTH24}。内容を読み書きする命令は RGBA8 と R8 だけ。 */
public enum TextureFormat {
    RGBA8(4), R8(1), RGBA16F(8), DEPTH24(4);

    public final int bytesPerPixel;

    TextureFormat(int bytesPerPixel) {
        this.bytesPerPixel = bytesPerPixel;
    }
}
