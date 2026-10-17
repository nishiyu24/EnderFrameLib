package com.nishiyu.enderframelib.core.model;

/**
 * 4隅の角丸半径（4値タプル）。
 */
public record Corners(int topLeft, int topRight, int bottomRight, int bottomLeft) {
    public static final Corners ZERO = new Corners(0, 0, 0, 0);

    public static Corners all(int radius) {
        if (radius <= 0) return ZERO;
        return new Corners(radius, radius, radius, radius);
    }

    public int[] toArray() {
        return new int[]{topLeft, topRight, bottomRight, bottomLeft};
    }

    public float[] toFloatArray() {
        return new float[]{topLeft, topRight, bottomRight, bottomLeft};
    }

    public static Corners fromArray(int[] arr) {
        if (arr == null || arr.length == 0) return ZERO;
        if (arr.length == 1) return all(arr[0]);
        if (arr.length >= 4) return new Corners(arr[0], arr[1], arr[2], arr[3]);
        return ZERO;
    }

    public boolean hasRadius() {
        return topLeft > 0 || topRight > 0 || bottomRight > 0 || bottomLeft > 0;
    }
}
