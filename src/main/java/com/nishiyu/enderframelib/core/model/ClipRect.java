package com.nishiyu.enderframelib.core.model;

/**
 * クリップ矩形と角丸情報（8値タプル）。
 */
public record ClipRect(
        float minX, float minY, float maxX, float maxY,
        float radiusTopLeft, float radiusTopRight, float radiusBottomRight, float radiusBottomLeft
) {
    public static final ClipRect EMPTY = null;

    public float[] toArray() {
        return new float[]{minX, minY, maxX, maxY, radiusTopLeft, radiusTopRight, radiusBottomRight, radiusBottomLeft};
    }

    public static ClipRect fromArray(float[] arr) {
        if (arr == null || arr.length < 4) return null;
        float rtl = arr.length >= 5 ? arr[4] : 0f;
        float rtr = arr.length >= 6 ? arr[5] : rtl;
        float rbr = arr.length >= 7 ? arr[6] : rtl;
        float rbl = arr.length >= 8 ? arr[7] : rtl;
        return new ClipRect(arr[0], arr[1], arr[2], arr[3], rtl, rtr, rbr, rbl);
    }

    public boolean contains(double x, double y) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY;
    }
}
