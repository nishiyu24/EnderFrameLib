package com.nishiyu.enderframelib.core.paint.software;

/**
 * 距離を測らずに済む領域の記述（局所座標）。呼び出し側は、各領域の内側で {@code shader} が「その色」（色 0 は何も塗らない）を
 * 返すことを保証する。矩形の領域と、円の角を持つ角丸矩形の内側（行ごとに左右の端を解析的に求める）を持てる。
 */
final class Fast {
    /** 近道の領域は無いが、シェーダーが純粋なので溜めてタイルで実行してよい、という印。 */
    static final Fast PURE = new Fast();
    static final int MAX = 6;
    final float[] rect = new float[4 * MAX];
    final int[] color = new int[MAX];
    int n;
    /** 角丸矩形の内側（被覆率 1）の領域。最大 {@link #MAX_ROUND} 個。 */
    static final int MAX_ROUND = 2;
    final float[] rb = new float[5 * MAX_ROUND];
    final float[][] rr = new float[MAX_ROUND][];
    final int[] roundColor = new int[MAX_ROUND];
    int nRound;

    void rect(float x0, float y0, float x1, float y1, int argbPremul) {
        if (n == MAX || !(x1 > x0) || !(y1 > y0)) return;
        rect[4 * n] = x0; rect[4 * n + 1] = y0; rect[4 * n + 2] = x1; rect[4 * n + 3] = y1;
        color[n++] = argbPremul;
    }

    /** 円の角（{@code rx[i] == ry[i]} かつ半辺以下）の角丸矩形なら、行ごとの内側を使う。そうでなければ矩形 1 つで代用する。 */
    void roundRect(float x0, float y0, float x1, float y1, float[] rx, float[] ry, float reach, int argbPremul) {
        float hx = (x1 - x0) * 0.5f, hy = (y1 - y0) * 0.5f;
        boolean circular = true;
        for (int i = 0; i < 4; i++) {
            if (rx[i] != ry[i] || rx[i] > hx || ry[i] > hy) { circular = false; break; }
        }
        if (!circular) {
            float mrx = SoftShapes.maxOf(rx), mry = SoftShapes.maxOf(ry);
            rect(x0 + mrx + reach, y0 + mry + reach, x1 - mrx - reach, y1 - mry - reach, argbPremul);
            return;
        }
        if (nRound == MAX_ROUND) return;
        int k = nRound++;
        rb[5 * k] = x0; rb[5 * k + 1] = y0; rb[5 * k + 2] = x1; rb[5 * k + 3] = y1; rb[5 * k + 4] = reach + 2e-3f;
        rr[k] = rx; roundColor[k] = argbPremul;
    }

    /** 局所の高さ {@code ly} での内側の区間 {@code [out0, out1]}。無ければ false。 */
    boolean roundSpan(int k, double ly, double[] out) {
        double rx0 = rb[5 * k], ry0 = rb[5 * k + 1], rx1 = rb[5 * k + 2], ry1 = rb[5 * k + 3], e = rb[5 * k + 4];
        float[] roundR = rr[k];
        if (ly < ry0 + e || ly > ry1 - e) return false;
        double xl = rx0 + e, xr = rx1 - e;
        boolean top = ly < (ry0 + ry1) * 0.5;
        double rl = roundR[top ? 0 : 3], rr = roundR[top ? 1 : 2];
        if (rl > e && (top ? ly < ry0 + rl : ly > ry1 - rl)) {
            double dyc = Math.abs(ly - (top ? ry0 + rl : ry1 - rl)), rd = rl - e;
            double q = rd * rd - dyc * dyc;
            if (q < 0) return false;
            xl = Math.max(xl, rx0 + rl - Math.sqrt(q));
        }
        if (rr > e && (top ? ly < ry0 + rr : ly > ry1 - rr)) {
            double dyc = Math.abs(ly - (top ? ry0 + rr : ry1 - rr)), rd = rr - e;
            double q = rd * rd - dyc * dyc;
            if (q < 0) return false;
            xr = Math.min(xr, rx1 - rr + Math.sqrt(q));
        }
        if (xl > xr) return false;
        out[0] = xl; out[1] = xr;
        return true;
    }
}
