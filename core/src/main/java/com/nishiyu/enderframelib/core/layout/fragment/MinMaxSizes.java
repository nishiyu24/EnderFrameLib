package com.nishiyu.enderframelib.core.layout.fragment;

/**
 * 内在サイズ（Engine v3 M2a）。内容に基づく {@code min-content} / {@code max-content} の幅（パディングと枠線を含む border-box。
 * 余白と、箱自身の {@code width} / {@code min-width} / {@code max-width} は含まない。それらを掛けた「親への寄与」は呼び出し側が作る）。
 * {@code (BoxNode ==, MinMaxContext) → MinMaxSizes} の 1 層メモで覚える（{@link LayoutCache}）。
 */
public final class MinMaxSizes {
    public static final MinMaxSizes ZERO = new MinMaxSizes(0, 0);

    public final float min;
    public final float max;

    public MinMaxSizes(float min, float max) {
        this.min = min;
        this.max = Math.max(min, max);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MinMaxSizes m && Float.compare(min, m.min) == 0 && Float.compare(max, m.max) == 0;
    }

    @Override
    public int hashCode() {
        return Float.floatToIntBits(min) * 31 + Float.floatToIntBits(max);
    }

    @Override
    public String toString() {
        return "MinMax[" + min + ", " + max + "]";
    }
}
