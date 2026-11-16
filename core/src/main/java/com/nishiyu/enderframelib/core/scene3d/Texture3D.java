package com.nishiyu.enderframelib.core.scene3d;

/**
 * 3D 材質のベースカラー画像。非事前乗算の ARGB（行優先・先頭の行が画像の上 = {@code v = 0}）。作った後は変えない（同一性で GPU 側の
 * テクスチャを引き当てるので、内容を変えたいときは新しいインスタンスを作る）。
 * サンプリングは GPU の線形補間と同じ（テクセル中心 {@code +0.5}・端は最も外のテクセルへ張り付く）。{@code repeat} のときは {@code fract(uv)}
 * を取ってから同じ規則で引く（シェーダーと同じ式）。
 */
public final class Texture3D {
    private final int width, height;
    private final int[] argb;

    public Texture3D(int width, int height, int[] argb) {
        if (width <= 0 || height <= 0 || argb == null || argb.length < (long) width * height) {
            throw new IllegalArgumentException("texture " + width + "x" + height);
        }
        this.width = width;
        this.height = height;
        this.argb = argb.clone();
    }

    public int width() { return width; }
    public int height() { return height; }

    /** 画素（非事前乗算 ARGB）。 */
    public int argb(int x, int y) { return argb[y * width + x]; }

    /** 双線形補間で {@code (u, v)} を引き、非事前乗算の RGBA（0〜1）を {@code out[0..3]} へ。 */
    public void sample(float u, float v, boolean repeat, float[] out) {
        if (repeat) {
            u -= (float) Math.floor(u);
            v -= (float) Math.floor(v);
        }
        float x = u * width - 0.5f, y = v * height - 0.5f;
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        float fx = x - x0, fy = y - y0;
        int xa = clamp(x0, width), xb = clamp(x0 + 1, width), ya = clamp(y0, height), yb = clamp(y0 + 1, height);
        int p00 = argb[ya * width + xa], p10 = argb[ya * width + xb], p01 = argb[yb * width + xa], p11 = argb[yb * width + xb];
        for (int c = 0; c < 4; c++) {
            int sh = c == 3 ? 24 : 16 - 8 * c; // out の並びは R, G, B, A
            float top = ((p00 >>> sh) & 0xFF) * (1f - fx) + ((p10 >>> sh) & 0xFF) * fx;
            float bot = ((p01 >>> sh) & 0xFF) * (1f - fx) + ((p11 >>> sh) & 0xFF) * fx;
            out[c] = (top * (1f - fy) + bot * fy) / 255f;
        }
    }

    private static int clamp(int i, int n) { return i < 0 ? 0 : Math.min(i, n - 1); }
}
