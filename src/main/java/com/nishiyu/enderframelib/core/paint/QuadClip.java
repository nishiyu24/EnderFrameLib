package com.nishiyu.enderframelib.core.paint;

/**
 * 軸に平行な四角形（字形の板）を矩形で切り、テクスチャ座標を位置に合わせて詰め直す。
 * シザーが使えない世界内の面で、スクロール端や {@code overflow: hidden} にかかる文字を部分的に隠すために使う。
 */
public final class QuadClip {
    private QuadClip() {}

    /**
     * 4 頂点の四角形を {@code [minX, maxX] × [minY, maxY]} で切る。{@code x / y / u / v} は頂点ごとの値（長さ 4。
     * 軸に平行なら頂点の順は問わない）。結果は同じ長さの配列を {@code x, y, u, v} の順に並べた 16 要素
     * （頂点の順は入力のまま）。矩形の外に完全に出るか、面積が無ければ {@code null}。
     */
    public static float[] clip(float[] x, float[] y, float[] u, float[] v, float minX, float minY, float maxX, float maxY) {
        float qx0 = Float.POSITIVE_INFINITY, qx1 = Float.NEGATIVE_INFINITY;
        float qy0 = Float.POSITIVE_INFINITY, qy1 = Float.NEGATIVE_INFINITY;
        float uLeft = 0, uRight = 0, vTop = 0, vBottom = 0;
        for (int i = 0; i < 4; i++) {
            if (x[i] < qx0) { qx0 = x[i]; uLeft = u[i]; }
            if (x[i] > qx1) { qx1 = x[i]; uRight = u[i]; }
            if (y[i] < qy0) { qy0 = y[i]; vTop = v[i]; }
            if (y[i] > qy1) { qy1 = y[i]; vBottom = v[i]; }
        }
        float cx0 = Math.max(qx0, minX), cx1 = Math.min(qx1, maxX);
        float cy0 = Math.max(qy0, minY), cy1 = Math.min(qy1, maxY);
        if (cx1 <= cx0 || cy1 <= cy0) return null;
        float w = qx1 - qx0, h = qy1 - qy0;
        float[] out = new float[16];
        for (int i = 0; i < 4; i++) {
            float nx = Math.max(cx0, Math.min(cx1, x[i]));
            float ny = Math.max(cy0, Math.min(cy1, y[i]));
            out[i] = nx;
            out[4 + i] = ny;
            out[8 + i] = w > 0 ? uLeft + (nx - qx0) / w * (uRight - uLeft) : u[i];
            out[12 + i] = h > 0 ? vTop + (ny - qy0) / h * (vBottom - vTop) : v[i];
        }
        return out;
    }

    /**
     * まだ描き切っていない描画（バッファに積んだ文字・画像）の範囲の覚え。世界内の面は SDF の Quad を後でまとめて描くので、
     * 積んだ文字・画像の上に重なる Quad が来たら、その前に文字・画像を描き切る必要がある（描かないと Quad が先に描かれ、
     * 半透明の Quad の下の文字が奥行きで隠れる）。重なりがなければ描く順は変えず、バッチを保つ。
     */
    public static final class Pending {
        private float[] rects = new float[64];
        private int count;
        private boolean everywhere;

        /** 範囲を足す。変形などで範囲を決められない描画は {@code everywhere} を立てる（以後の重なりの判定が常に真）。 */
        public void add(float x0, float y0, float x1, float y1) {
            if (count * 4 + 4 > rects.length) rects = java.util.Arrays.copyOf(rects, rects.length * 2);
            int o = count++ * 4;
            rects[o] = x0;
            rects[o + 1] = y0;
            rects[o + 2] = x1;
            rects[o + 3] = y1;
        }

        public void addEverywhere() {
            everywhere = true;
        }

        public boolean isEmpty() {
            return count == 0 && !everywhere;
        }

        /** 積んだ描画のどれかと重なるか（{@code unknown} なら、積んだものが 1 つでもあれば真）。 */
        public boolean overlaps(float x0, float y0, float x1, float y1, boolean unknown) {
            if (isEmpty()) return false;
            if (everywhere || unknown) return true;
            for (int i = 0; i < count; i++) {
                int o = i * 4;
                if (x0 < rects[o + 2] && x1 > rects[o] && y0 < rects[o + 3] && y1 > rects[o + 1]) return true;
            }
            return false;
        }

        public void clear() {
            count = 0;
            everywhere = false;
        }
    }
}
