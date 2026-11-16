package com.nishiyu.enderframelib.core.raster;

/** 復号した画像: 非事前乗算の ARGB 画素（行優先）。複数の層が使うのでトップレベル。 */
public record DecodedBitmap(int width, int height, int[] argb) {}
