package com.nishiyu.enderframelib.core.paint.software;

/**
 * ソフトウェア描画の図形計算。角ごとに楕円の半径を持つ角丸矩形の符号付き距離（SDF）と、
 * それを画素の被覆率・ぼかしの濃さに直す式。GLSL の {@code WebSdfShader} と同じ考え方を CPU で再現する。
 *
 * <p>角の順はすべて 左上・右上・右下・左下。
 */
final class SoftShapes {
    private SoftShapes() {}

    /**
     * 角丸矩形 {@code (x0,y0)-(x1,y1)} までの符号付き距離（外が正）。{@code rx} / {@code ry} は角ごとの横・縦の半径で、
     * どちらかが 0 以下の角は直角。楕円の角は一次近似の距離（円なら厳密）。
     */
    static float sdRoundRect(float px, float py, float x0, float y0, float x1, float y1, float[] rx, float[] ry) {
        float cx = (x0 + x1) * 0.5f, cy = (y0 + y1) * 0.5f;
        boolean right = px > cx, bottom = py > cy;
        int k = bottom ? (right ? 2 : 3) : (right ? 1 : 0);
        float rkx = rx[k], rky = ry[k];
        float hx = (x1 - x0) * 0.5f, hy = (y1 - y0) * 0.5f;
        if (rkx <= 0f || rky <= 0f) {
            float dx = Math.abs(px - cx) - hx, dy = Math.abs(py - cy) - hy;
            return (float) Math.hypot(Math.max(dx, 0f), Math.max(dy, 0f)) + Math.min(Math.max(dx, dy), 0f);
        }
        rkx = Math.min(rkx, hx);
        rky = Math.min(rky, hy);
        float qx = Math.abs(px - cx) - (hx - rkx), qy = Math.abs(py - cy) - (hy - rky);
        if (qx > 0f && qy > 0f) {
            float kx = qx / rkx, ky = qy / rky;
            float len = (float) Math.hypot(kx, ky);
            float gx = qx / (rkx * rkx), gy = qy / (rky * rky);
            float grad = (float) Math.hypot(gx, gy);
            return grad <= 0f ? 0f : (len - 1f) * len / grad;
        }
        return Math.max(qx - rkx, qy - rky);
    }

    /** 距離 {@code sd}（レイアウト単位）の縁の被覆率。{@code scale} は 1 単位が何画素か。 */
    static float coverage(float sd, float scale) {
        float c = 0.5f - sd * scale;
        return c <= 0f ? 0f : (c >= 1f ? 1f : c);
    }

    /** 相補誤差関数（Abramowitz-Stegun 7.1.26。誤差 1.5e-7 以下）。 */
    static float erfc(float x) {
        float z = Math.abs(x);
        float t = 1f / (1f + 0.3275911f * z);
        float poly = ((((1.061405429f * t - 1.453152027f) * t + 1.421413741f) * t - 0.284496736f) * t + 0.254829592f) * t;
        float r = poly * (float) Math.exp(-z * z);
        return x >= 0f ? r : 2f - r;
    }

    /** ガウスぼかし（標準偏差 {@code sigma}）をかけた図形の縁の濃さ。{@code sd} は図形までの符号付き距離。 */
    static float blurredEdge(float sd, float sigma) {
        if (sigma <= 0f) return sd <= 0f ? 1f : 0f;
        return 0.5f * erfc(sd / (sigma * 1.41421356f));
    }

    /** 事前乗算の ARGB に係数 {@code k}（0〜1）を掛ける。 */
    static int scalePremul(int pm, float k) {
        if (k >= 1f) return pm;
        if (k <= 0f) return 0;
        int f = Math.round(k * 256f);
        int a = ((pm >>> 24) * f) >> 8, r = (((pm >> 16) & 0xFF) * f) >> 8;
        int g = (((pm >> 8) & 0xFF) * f) >> 8, b = ((pm & 0xFF) * f) >> 8;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** {@code src} を {@code dst} に重ねる（どちらも事前乗算の ARGB。source-over）。 */
    static int over(int src, int dst) {
        int sa = src >>> 24;
        if (sa == 255) return src;
        if (sa == 0) return dst;
        int inv = 255 - sa;
        int a = sa + div255((dst >>> 24) * inv);
        int r = ((src >> 16) & 0xFF) + div255(((dst >> 16) & 0xFF) * inv);
        int g = ((src >> 8) & 0xFF) + div255(((dst >> 8) & 0xFF) * inv);
        int b = (src & 0xFF) + div255((dst & 0xFF) * inv);
        return (Math.min(a, 255) << 24) | (Math.min(r, 255) << 16) | (Math.min(g, 255) << 8) | Math.min(b, 255);
    }

    private static int div255(int v) {
        return ((v + 128) * 257) >> 16;
    }
}
