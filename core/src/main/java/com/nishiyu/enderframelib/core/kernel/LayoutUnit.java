package com.nishiyu.enderframelib.core.kernel;

/**
 * レイアウト座標の格子（1/64 px。Chrome の {@code LayoutUnit} と同じ分解能）。
 *
 * <p>座標は float のままだが、<b>縦方向の寸法の源（行の高さ）をこの格子に載せる</b>ことで、
 * 格子の倍数どうしの加減算が float で厳密になる（絶対値が {@link #EXACT_LIMIT} 未満の間は仮数部に収まる）。
 * すると「部分木を小数ぶん平行移動した結果」が「原点を変えて置き直した結果」とビット単位で一致するので、
 * 整数ぶんの移動に限っていた平行移動の近道（{@code LayoutBox.layout}）を格子の倍数へ広げられる。
 */
public final class LayoutUnit {
    private LayoutUnit() {}

    public static final int SCALE = 64;
    /**
     * この絶対値未満なら、格子の倍数は float の仮数部（24 bit）に収まり、和も厳密になる（2^24 / 64 = 2^18）。
     * 和そのものがこの範囲に収まることは、呼び出し側が余裕を見て確かめる（{@link #SHIFT_MARGIN}）。
     */
    public static final float EXACT_LIMIT = 262144f;
    /** 平行移動の原点の絶対値に取る余裕（部分木の内側の相対位置と移動量がこの範囲に収まる前提）。 */
    public static final float SHIFT_MARGIN = 16384f;

    /** 最も近い格子点へ丸める。非有限値・極端に大きい値はそのまま返す。 */
    public static float snap(float v) {
        if (!(Math.abs(v) < EXACT_LIMIT)) return v;
        return Math.round(v * SCALE) / (float) SCALE;
    }

    /** 格子の倍数で、かつ厳密に足せる大きさか。 */
    public static boolean isOnGrid(float v) {
        return Math.abs(v) < EXACT_LIMIT && v * SCALE == Math.rint(v * SCALE);
    }
}
