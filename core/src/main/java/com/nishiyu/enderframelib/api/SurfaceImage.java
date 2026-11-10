package com.nishiyu.enderframelib.api;

import org.jetbrains.annotations.ApiStatus;

/**
 * ホストが差し込む画像。画素は事前乗算でない（ストレートな）ARGB、左上原点。
 *
 * @param cssWidth  固有の幅（CSS px。{@code <img>} の既定の大きさになる）
 * @param cssHeight 固有の高さ（CSS px）
 */
@ApiStatus.AvailableSince("1.0")
public record SurfaceImage(int width, int height, int[] argb, float cssWidth, float cssHeight) {

    public SurfaceImage {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("size must be positive");
        if (argb == null || argb.length != width * height) throw new IllegalArgumentException("argb must hold width*height pixels");
        if (!(cssWidth > 0f) || !(cssHeight > 0f)) throw new IllegalArgumentException("css size must be positive");
    }

    /** 画素の大きさをそのまま CSS px の固有サイズにする。 */
    public static SurfaceImage of(int width, int height, int[] argb) {
        return new SurfaceImage(width, height, argb, width, height);
    }
}
