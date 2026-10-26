package com.nishiyu.enderframelib.core.raster;

import java.util.Arrays;

/**
 * 事前乗算済みアルファの ARGB ピクセル面（Canvas 2D のビットマップ）。
 * 1 画素は {@code 0xAARRGGBB}（R・G・B はアルファを掛けた値）。
 */
public final class Pixmap {

    /** 1 辺の上限。これを超える確保は拒む（メモリの暴走を防ぐ）。 */
    public static final int MAX_DIMENSION = 8192;

    private final int width;
    private final int height;
    private final int[] pixels;

    public Pixmap(int width, int height) {
        if (width < 0 || height < 0 || width > MAX_DIMENSION || height > MAX_DIMENSION
                || (long) width * height > 64L * 1024 * 1024) {
            throw new IllegalArgumentException("Bad pixmap size " + width + "x" + height);
        }
        this.width = width;
        this.height = height;
        this.pixels = new int[width * height];
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** 事前乗算済みの画素の配列（直接書き換えてよい）。 */
    public int[] pixels() {
        return pixels;
    }

    public int get(int x, int y) {
        return pixels[y * width + x];
    }

    public void set(int x, int y, int premultipliedArgb) {
        pixels[y * width + x] = premultipliedArgb;
    }

    public void clear() {
        Arrays.fill(pixels, 0);
    }

    public Pixmap copy() {
        Pixmap p = new Pixmap(width, height);
        System.arraycopy(pixels, 0, p.pixels, 0, pixels.length);
        return p;
    }

    /** 事前乗算を外した非乗算の ARGB（範囲外は透明）。 */
    public int getStraight(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) return 0;
        return unpremultiply(pixels[y * width + x]);
    }

    /** 非乗算の ARGB を書き込む（事前乗算して保存する）。 */
    public void setStraight(int x, int y, int argb) {
        pixels[y * width + x] = premultiply(argb);
    }

    /** 非乗算の ARGB 配列（行優先）から作る。 */
    public static Pixmap fromStraightArgb(int width, int height, int[] argb) {
        Pixmap p = new Pixmap(width, height);
        for (int i = 0; i < p.pixels.length; i++) p.pixels[i] = premultiply(argb[i]);
        return p;
    }

    /** 非乗算の ARGB 配列（行優先）へ。 */
    public int[] toStraightArgb() {
        int[] out = new int[pixels.length];
        for (int i = 0; i < out.length; i++) out[i] = unpremultiply(pixels[i]);
        return out;
    }

    public static int premultiply(int argb) {
        int a = argb >>> 24;
        if (a == 255) return argb;
        if (a == 0) return 0;
        int r = div255(((argb >> 16) & 0xFF) * a);
        int g = div255(((argb >> 8) & 0xFF) * a);
        int b = div255((argb & 0xFF) * a);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static int unpremultiply(int pm) {
        int a = pm >>> 24;
        if (a == 255 || a == 0) return a == 0 ? 0 : pm;
        int r = Math.min(255, (((pm >> 16) & 0xFF) * 255 + a / 2) / a);
        int g = Math.min(255, (((pm >> 8) & 0xFF) * 255 + a / 2) / a);
        int b = Math.min(255, ((pm & 0xFF) * 255 + a / 2) / a);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    static int div255(int v) {
        return ((v + 128) * 257) >> 16;
    }
}
