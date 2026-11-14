package com.nishiyu.enderframelib.core.paint.model;

import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;

/**
 * 4隅の角丸半径。{@code topLeft} など無印の値は横の半径、{@code *V} は縦の半径。
 * 円の角は両者が同じで、楕円の角（{@code 10px / 20px}・長方形の箱の {@code 50%}）だけ違う。
 * 横か縦のどちらかが 0 の角は直角として扱う（CSS Backgrounds 3 §5.1）。
 */
public record Corners(int topLeft, int topRight, int bottomRight, int bottomLeft,
                      int topLeftV, int topRightV, int bottomRightV, int bottomLeftV) {
    public static final Corners ZERO = new Corners(0, 0, 0, 0);
    public static final int[] ZERO_RAW = new int[4];

    /** 円の角（縦の半径は横と同じ）。 */
    public Corners(int topLeft, int topRight, int bottomRight, int bottomLeft) {
        this(topLeft, topRight, bottomRight, bottomLeft, topLeft, topRight, bottomRight, bottomLeft);
    }

    public static Corners all(int radius) {
        if (radius <= 0) return ZERO;
        return new Corners(radius, radius, radius, radius);
    }

    /** 横 4 つ・縦 4 つ（左上・右上・右下・左下の順）から。どちらかが 0 の角は直角にする。 */
    public static Corners ofEllipses(int[] horizontal, int[] vertical) {
        return ofEllipses(horizontal, 0, vertical, 0);
    }

    private static Corners ofEllipses(int[] hArr, int hOff, int[] vArr, int vOff) {
        int h0 = (hArr[hOff] <= 0 || vArr[vOff] <= 0) ? 0 : hArr[hOff];
        int h1 = (hArr[hOff + 1] <= 0 || vArr[vOff + 1] <= 0) ? 0 : hArr[hOff + 1];
        int h2 = (hArr[hOff + 2] <= 0 || vArr[vOff + 2] <= 0) ? 0 : hArr[hOff + 2];
        int h3 = (hArr[hOff + 3] <= 0 || vArr[vOff + 3] <= 0) ? 0 : hArr[hOff + 3];
        if ((h0 | h1 | h2 | h3) == 0) return ZERO;
        return new Corners(h0, h1, h2, h3,
                h0 == 0 ? 0 : vArr[vOff], h1 == 0 ? 0 : vArr[vOff + 1],
                h2 == 0 ? 0 : vArr[vOff + 2], h3 == 0 ? 0 : vArr[vOff + 3]);
    }

    /** 要素 8 の浮動小数配列 {@code {rx0..rx3, ry0..ry3}} から丸めて作る。全角 0 なら {@link #ZERO}。 */
    public static Corners ofFloatXY(float[] xy) {
        if (xy == null) return ZERO;
        int h0 = Math.round(xy[0]), v0 = Math.round(xy[4]);
        int h1 = Math.round(xy[1]), v1 = Math.round(xy[5]);
        int h2 = Math.round(xy[2]), v2 = Math.round(xy[6]);
        int h3 = Math.round(xy[3]), v3 = Math.round(xy[7]);
        if (h0 <= 0 || v0 <= 0) { h0 = 0; v0 = 0; }
        if (h1 <= 0 || v1 <= 0) { h1 = 0; v1 = 0; }
        if (h2 <= 0 || v2 <= 0) { h2 = 0; v2 = 0; }
        if (h3 <= 0 || v3 <= 0) { h3 = 0; v3 = 0; }
        if ((h0 | h1 | h2 | h3) == 0) return ZERO;
        return new Corners(h0, h1, h2, h3, v0, v1, v2, v3);
    }

    public int[] toArray() {
        return new int[]{topLeft, topRight, bottomRight, bottomLeft};
    }

    public float[] toFloatArray() {
        return new float[]{topLeft, topRight, bottomRight, bottomLeft};
    }

    /** 縦の半径（左上・右上・右下・左下の順）。 */
    public float[] toFloatArrayV() {
        return new float[]{topLeftV, topRightV, bottomRightV, bottomLeftV};
    }

    public static Corners fromArray(int[] arr) {
        if (arr == null || arr.length == 0) return ZERO;
        if (arr.length == 1) return all(arr[0]);
        if (arr.length >= 8) return ofEllipses(arr, 0, arr, 4);
        if (arr.length >= 4) return new Corners(arr[0], arr[1], arr[2], arr[3]);
        return ZERO;
    }

    public boolean hasRadius() {
        return topLeft > 0 || topRight > 0 || bottomRight > 0 || bottomLeft > 0;
    }

    /** 横と縦の半径が違う角があるか。 */
    public boolean isElliptical() {
        return topLeft != topLeftV || topRight != topRightV || bottomRight != bottomRightV || bottomLeft != bottomLeftV;
    }
}
