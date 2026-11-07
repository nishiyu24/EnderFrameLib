package com.nishiyu.enderframelib.core.paint.gpu;

import com.nishiyu.enderframelib.core.raster.Matrix2D;

/**
 * シンクに渡すクリップの 1 つ。{@code inv} はデバイス画素を持ち主の空間へ戻す変換、{@code scale} は持ち主の空間の
 * 1 単位が何画素か。{@code (x0,y0)-(x1,y1)} が持ち主の空間の角丸矩形、{@code rx/ry} が角（左上・右上・右下・左下）。
 */
public record GpuClip(Matrix2D inv, float scale, float x0, float y0, float x1, float y1, float[] rx, float[] ry) {}
