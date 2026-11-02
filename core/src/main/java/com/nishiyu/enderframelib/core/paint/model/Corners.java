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
        int[] h = new int[4], v = new int[4];
        for (int i = 0; i < 4; i++) {
            boolean square = horizontal[i] <= 0 || vertical[i] <= 0;
            h[i] = square ? 0 : horizontal[i];
            v[i] = square ? 0 : vertical[i];
        }
        return new Corners(h[0], h[1], h[2], h[3], v[0], v[1], v[2], v[3]);
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
        if (arr.length >= 8) return ofEllipses(java.util.Arrays.copyOfRange(arr, 0, 4), java.util.Arrays.copyOfRange(arr, 4, 8));
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
