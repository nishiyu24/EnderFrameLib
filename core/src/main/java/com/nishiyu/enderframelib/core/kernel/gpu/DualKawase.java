package com.nishiyu.enderframelib.core.kernel.gpu;

/**
 * Dual-Kawase ブラーの計画（標準偏差 σ → 段数とオフセット）と、検証用の CPU 参照実装。
 * 段ごとに 1/2 へダウンサンプル（5 タップ）→ 同じ段数だけアップサンプル（8 タップ）。1 段ごとに実効的な広がりが
 * 約 2 倍になるので、半径 100 px 級でも数段（各 1 パス）で済む。
 * シェーダー側は {@link #passes} 回の down と同数の up を、{@link #offset} をタップ間隔の倍率として回す。
 */
public final class DualKawase {
    public static final int MAX_PASSES = 8;
    /** 段数 p・オフセット 1 のときの実効 σ ≈ SIGMA_UNIT * 2^p（{@code DualKawaseTest} が参照実装で実測して決めた近似）。 */
    static final float SIGMA_UNIT = 0.75f;

    private DualKawase() {}

    /** σ（画素）に対する段数。σ が小さい（≤ 0.5）ときは 0（ブラー不要）。 */
    public static int passes(float sigma) {
        if (!(sigma > 0.5f)) return 0;
        int p = 1;
        while (p < MAX_PASSES && SIGMA_UNIT * (1 << p) * 1.5f < sigma) p++;
        return p;
    }

    /** 段数が決まった後の、タップ間隔の倍率（0.5〜2.5）。 */
    public static float offset(float sigma, int passes) {
        if (passes == 0) return 0f;
        float o = sigma / (SIGMA_UNIT * (1 << passes));
        return Math.max(0.5f, Math.min(2.5f, o));
    }

    /** 計画から見積もる実効 σ。 */
    public static float effectiveSigma(int passes, float offset) {
        return passes == 0 ? 0f : SIGMA_UNIT * (1 << passes) * offset;
    }

    /** 1 チャネルの画像を Dual-Kawase でぼかす参照実装（端は引き伸ばし）。 */
    public static float[] blur(float[] src, int w, int h, int passes, float offset) {
        if (passes == 0) return src.clone();
        float[][] level = new float[passes + 1][];
        int[] lw = new int[passes + 1], lh = new int[passes + 1];
        level[0] = src; lw[0] = w; lh[0] = h;
        for (int i = 1; i <= passes; i++) {
            lw[i] = Math.max(1, (lw[i - 1] + 1) / 2);
            lh[i] = Math.max(1, (lh[i - 1] + 1) / 2);
            level[i] = new float[lw[i] * lh[i]];
            down(level[i - 1], lw[i - 1], lh[i - 1], level[i], lw[i], lh[i], offset);
        }
        for (int i = passes; i >= 1; i--) {
            float[] up = new float[lw[i - 1] * lh[i - 1]];
            up(level[i], lw[i], lh[i], up, lw[i - 1], lh[i - 1], offset);
            level[i - 1] = up;
        }
        return level[0];
    }

    /** 双線形標本（端は引き伸ばし）。座標は画素の左上原点で、中心が +0.5。 */
    private static float bilinear(float[] s, int w, int h, float x, float y) {
        float fx = x - 0.5f, fy = y - 0.5f;
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        float tx = fx - x0, ty = fy - y0;
        float a = px(s, w, h, x0, y0), b = px(s, w, h, x0 + 1, y0), c = px(s, w, h, x0, y0 + 1), d = px(s, w, h, x0 + 1, y0 + 1);
        return (a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty;
    }

    private static float px(float[] s, int w, int h, int x, int y) {
        return s[Math.max(0, Math.min(h - 1, y)) * w + Math.max(0, Math.min(w - 1, x))];
    }

    private static void down(float[] s, int sw, int sh, float[] d, int dw, int dh, float o) {
        for (int y = 0; y < dh; y++) for (int x = 0; x < dw; x++) {
            float cx = (x + 0.5f) * 2f, cy = (y + 0.5f) * 2f;
            d[y * dw + x] = (bilinear(s, sw, sh, cx, cy) * 4f
                    + bilinear(s, sw, sh, cx - o, cy - o) + bilinear(s, sw, sh, cx + o, cy - o)
                    + bilinear(s, sw, sh, cx - o, cy + o) + bilinear(s, sw, sh, cx + o, cy + o)) / 8f;
        }
    }

    private static void up(float[] s, int sw, int sh, float[] d, int dw, int dh, float o) {
        for (int y = 0; y < dh; y++) for (int x = 0; x < dw; x++) {
            float cx = (x + 0.5f) / 2f, cy = (y + 0.5f) / 2f;
            float q = o * 0.5f;
            d[y * dw + x] = ((bilinear(s, sw, sh, cx - o, cy) + bilinear(s, sw, sh, cx + o, cy)
                    + bilinear(s, sw, sh, cx, cy - o) + bilinear(s, sw, sh, cx, cy + o)) * 2f
                    + bilinear(s, sw, sh, cx - q, cy - q) + bilinear(s, sw, sh, cx + q, cy - q)
                    + bilinear(s, sw, sh, cx - q, cy + q) + bilinear(s, sw, sh, cx + q, cy + q)) / 12f;
        }
    }
}
