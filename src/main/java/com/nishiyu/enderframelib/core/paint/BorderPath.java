package com.nishiyu.enderframelib.core.paint;

/**
 * 角丸（楕円の角を含む）の枠線の中心線に沿った長さと、破線・点線の割り付け。
 *
 * <p>{@code WebSdfShader} のフラグメントシェーダーの {@code pathParam} / {@code pathLength} と同じ式を Java で持ち、
 * 描画側が点線の個数を決める（{@link #pattern}）のと、テストがシェーダーの式を直接確かめる（{@link #param}）のに使う。
 * 中心線は箱の中心を原点にした半サイズ {@code b} の角丸矩形で、上辺の左の角の終わりを 0 とし時計回りに進む。
 * 角は 4 分の 1 の楕円弧で、弧の上の位置は角度の媒介変数に比例させる（弧長の厳密な積分ではない）。</p>
 */
public final class BorderPath {
    private BorderPath() {}

    /** 破線 1 周分の割り付け。{@code count} 個の周期に分け、周期ごとに長さ {@code dash} の線（または直径 {@code dash} の点）を中央に置く。 */
    public record Pattern(int count, float period, float dash, boolean round) {}

    public static final int SOLID = 0;
    public static final int DASHED = 1;
    public static final int DOTTED = 2;

    /** 楕円の 4 分の 1 の弧長（Ramanujan の近似。円なら厳密）。 */
    public static float quarterArc(float rx, float ry) {
        if (rx <= 0 || ry <= 0) return 0f;
        return (float) (0.25 * Math.PI * (3.0 * (rx + ry) - Math.sqrt((3.0 * rx + ry) * (rx + 3.0 * ry))));
    }

    /** 中心線の全長。{@code rx} / {@code ry} は左上・右上・右下・左下の順。 */
    public static float length(float bx, float by, float[] rx, float[] ry) {
        float straight = (2 * bx - rx[0] - rx[1]) + (2 * by - ry[1] - ry[2]) + (2 * bx - rx[2] - rx[3]) + (2 * by - ry[3] - ry[0]);
        float arcs = 0;
        for (int i = 0; i < 4; i++) arcs += quarterArc(rx[i], ry[i]);
        return straight + arcs;
    }

    /**
     * 点 {@code (px, py)}（箱の中心が原点）に最も近い中心線の位置。シェーダーの {@code pathParam} と同じ。
     * 辺の上は辺に沿った座標、角の領域は弧の角度に比例した位置。
     */
    public static float param(float px, float py, float bx, float by, float[] rx, float[] ry) {
        float lt = 2 * bx - rx[0] - rx[1];
        float lr = 2 * by - ry[1] - ry[2];
        float lb = 2 * bx - rx[2] - rx[3];
        float ll = 2 * by - ry[3] - ry[0];
        float aTR = quarterArc(rx[1], ry[1]), aBR = quarterArc(rx[2], ry[2]);
        float aBL = quarterArc(rx[3], ry[3]), aTL = quarterArc(rx[0], ry[0]);
        float sTR = lt, sR = sTR + aTR, sBR = sR + lr, sB = sBR + aBR, sBL = sB + lb, sL = sBL + aBL, sTL = sL + ll;
        double half = Math.PI / 2;
        if (py < 0) {
            if (px >= 0) {
                float cx = bx - rx[1], cy = -(by - ry[1]);
                if (px > cx && py < cy) return sTR + (float) (Math.atan2((px - cx) / Math.max(rx[1], 1e-4f), (cy - py) / Math.max(ry[1], 1e-4f)) / half) * aTR;
                if (px <= cx) return px + bx - rx[0];
                return sR + (py + by - ry[1]);
            }
            float cx = -(bx - rx[0]), cy = -(by - ry[0]);
            if (px < cx && py < cy) return sTL + (float) (Math.atan2((cy - py) / Math.max(ry[0], 1e-4f), (cx - px) / Math.max(rx[0], 1e-4f)) / half) * aTL;
            if (px >= cx) return px + bx - rx[0];
            return sL + (by - ry[3] - py);
        }
        if (px >= 0) {
            float cx = bx - rx[2], cy = by - ry[2];
            if (px > cx && py > cy) return sBR + (float) (Math.atan2((py - cy) / Math.max(ry[2], 1e-4f), (px - cx) / Math.max(rx[2], 1e-4f)) / half) * aBR;
            if (py <= cy) return sR + (py + by - ry[1]);
            return sB + (bx - rx[2] - px);
        }
        float cx = -(bx - rx[3]), cy = by - ry[3];
        if (px < cx && py > cy) return sBL + (float) (Math.atan2((cx - px) / Math.max(rx[3], 1e-4f), (py - cy) / Math.max(ry[3], 1e-4f)) / half) * aBL;
        if (px >= cx) return sB + (bx - rx[2] - px);
        return sL + (by - ry[3] - py);
    }

    /** 線の長さ（点線は点の直径）。Blink の破線と同じ: 太さ 3px 以上は 2 倍、未満は 3 倍。点線は太さ。 */
    public static float dashLength(int kind, float width) {
        if (kind != DASHED) return width;
        return width >= 3f ? width * 2f : width * 3f;
    }

    /** 線と線の間（目標値。実際は経路が閉じるように均す）。破線は太さ 3px 以上で 1 倍、未満で 2 倍。点線は太さ。 */
    public static float gapLength(int kind, float width) {
        if (kind != DASHED) return width;
        return width >= 3f ? width : width * 2f;
    }

    /**
     * 太さ {@code width} の破線（{@link #DASHED}）・点線（{@link #DOTTED}）を、全長 {@code length} の閉じた経路に割り付ける。
     * 寸法は直線の辺（{@code BoxDecorations.sideSegments}）と同じ {@link #dashLength} / {@link #gapLength} で、
     * 継ぎ目なく閉じるように周期の数を丸めて間隔を均す。点線は太さが 2px 以上なら丸い点。
     */
    public static Pattern pattern(int kind, float width, float length) {
        float dash = dashLength(kind, width);
        float gap = gapLength(kind, width);
        int count = Math.max(4, Math.round(length / (dash + gap)));
        float period = length / count;
        return new Pattern(count, period, Math.min(dash, period * 0.95f), kind == DOTTED && width >= 2f);
    }
}
