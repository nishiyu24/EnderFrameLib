package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.model.ShaderGradient;

import java.util.List;

/**
 * フラグメントシェーダー（{@code WebSdfShader}）のグラデーションの計算を CPU で再現する。
 * 式・範囲の外を捨てる・繰り返し・ヒント・色の補間を、シェーダーと同じ順で行う。
 * テストの基準との比較と、ソフトウェア描画バックエンド（SoftwareRenderBackend）が使う。
 */
public final class ShaderGradientEmulator {
    private ShaderGradientEmulator() {}

    /** 1 区間が 1 画素に付ける色（ARGB）。範囲の外で捨てられるなら null。 */
    public static Integer colorOf(ShaderGradient s, float x, float y) {
        float t = position(s, x, y);
        return Float.isNaN(t) ? null : Gradients.lerpPremultiplied(s.color0(), s.color1(), t);
    }

    /**
     * {@link #colorOf(ShaderGradient, float, float)} の箱詰めなし版。範囲の外で捨てられる点は 0 を返す
     * （描画側は「捨てる」と「透明」を区別しない）。
     */
    public static int colorOfOrZero(ShaderGradient s, float x, float y) {
        float t = position(s, x, y);
        return Float.isNaN(t) ? 0 : Gradients.lerpPremultiplied(s.color0(), s.color1(), t);
    }

    /** 勾配 1 つぶんの定数を先に畳んだ評価器（画素ごとに呼ぶ側向け）。結果は {@link #colorOfOrZero} とビットまで同じ。 */
    public static Prepared prepare(ShaderGradient s) {
        return new Prepared(s);
    }

    /** 1 スレッドで使う（直前の位置の色を覚える）。 */
    public static final class Prepared {
        private final int kind, bounds;
        private final float a0, a1, a2, b0, b1, b2, b3, period, origin;
        private final float alpha1, alpha2, r1, r2, g1, g2, bl1, bl2;
        private float lastT = Float.NaN;
        private int lastColor;

        private Prepared(ShaderGradient s) {
            float[] a = s.gradA(), b = s.gradB();
            int g = Math.round(a[3]);
            kind = g % 10;
            bounds = g / 10;
            a0 = a[0]; a1 = a[1]; a2 = a[2];
            b0 = b[0]; b1 = b[1]; b2 = b[2]; b3 = b[3];
            period = s.repeatPeriod();
            origin = s.repeatOrigin();
            int c1 = s.color0(), c2 = s.color1();
            alpha1 = (c1 >>> 24) / 255f;
            alpha2 = (c2 >>> 24) / 255f;
            r1 = ((c1 >>> 16) & 0xFF) * alpha1; r2 = ((c2 >>> 16) & 0xFF) * alpha2;
            g1 = ((c1 >>> 8) & 0xFF) * alpha1; g2 = ((c2 >>> 8) & 0xFF) * alpha2;
            bl1 = (c1 & 0xFF) * alpha1; bl2 = (c2 & 0xFF) * alpha2;
        }

        /** 線形で、色が x に依らない（行の中で一定）。 */
        public boolean constantAlongRows() { return kind == ShaderGradient.LINEAR && a0 == 0f; }

        /** 線形で、色が y に依らない（列の中で一定）。 */
        public boolean constantAlongColumns() { return kind == ShaderGradient.LINEAR && a1 == 0f; }

        /** 局所座標 {@code (x, y)} の色（捨てる点は 0）。 */
        public int colorOrZero(float x, float y) {
            float u;
            if (kind == ShaderGradient.LINEAR) {
                u = x * a0 + y * a1 + a2;
            } else if (kind == ShaderGradient.RADIAL) {
                float dx = (x - a0) * a2, dy = (y - a1) * b0;
                u = (float) Math.sqrt((double) dx * dx + (double) dy * dy);
            } else {
                double angle = atan2(x - a0, -(y - a1)) * 0.15915494;
                double v = angle - a2;
                u = (float) (v - Math.floor(v));
            }
            if (period > 0) {
                float d = u - origin;
                u = origin + (d - period * (float) Math.floor(d / period));
            }
            float t = (u - b1) * b2;
            if (((bounds & 1) != 0 && t < -0.001f) || ((bounds & 2) != 0 && t > 1.001f)) return 0;
            t = Math.clamp(t, 0f, 1f);
            if (b3 != 1f) t = (float) Math.pow(t, b3);
            if (t == lastT) return lastColor;
            float a = alpha1 + (alpha2 - alpha1) * t;
            int out = 0;
            if (a > 0) {
                out = Math.round(a * 255) << 24;
                out |= Math.clamp(Math.round((r1 + (r2 - r1) * t) / a), 0, 255) << 16;
                out |= Math.clamp(Math.round((g1 + (g2 - g1) * t) / a), 0, 255) << 8;
                out |= Math.clamp(Math.round((bl1 + (bl2 - bl1) * t) / a), 0, 255);
            }
            lastT = t;
            lastColor = out;
            return out;
        }
    }

    private static final double SQRT3 = Math.sqrt(3.0), TAN_PI_12 = 2.0 - SQRT3;

    /**
     * {@code Math.atan2} と同じ値を、{@code StrictMath} より桁違いに速く返す（誤差 1e-9 rad 以下）。
     * 円錐グラデーションが画素ごとに呼ぶので、ここが支配的になる。
     */
    static double atan2(double y, double x) {
        double ax = Math.abs(x), ay = Math.abs(y);
        if (ax == 0.0 && ay == 0.0) return Math.atan2(y, x); // 符号つきゼロの扱いを標準に任せる
        boolean swap = ay > ax;
        double z = swap ? ax / ay : ay / ax; // 0..1
        double r;
        if (z > TAN_PI_12) {
            double w = (z * SQRT3 - 1.0) / (SQRT3 + z); // |w| <= tan(pi/12)
            r = Math.PI / 6 + atanSmall(w);
        } else {
            r = atanSmall(z);
        }
        if (swap) r = Math.PI / 2 - r;
        if (x < 0) r = Math.PI - r;
        return Math.copySign(r, y);
    }

    private static double atanSmall(double z) {
        double z2 = z * z;
        return z * (1.0 + z2 * (-1.0 / 3 + z2 * (1.0 / 5 + z2 * (-1.0 / 7 + z2 * (1.0 / 9 + z2 * (-1.0 / 11 + z2 * (1.0 / 13)))))));
    }

    /** 色の補間位置 0〜1（ガンマ適用後）。範囲の外で捨てられるなら NaN。 */
    private static float position(ShaderGradient s, float x, float y) {
        float[] b = s.gradB();
        int bounds = Math.round(s.gradA()[3]) / 10;
        float t = (rawU(s, x, y) - b[1]) * b[2];
        if (((bounds & 1) != 0 && t < -0.001f) || ((bounds & 2) != 0 && t > 1.001f)) return Float.NaN;
        t = Math.clamp(t, 0f, 1f);
        return b[3] != 1f ? (float) Math.pow(t, b[3]) : t;
    }

    private static float rawU(ShaderGradient s, float x, float y) {
        float[] a = s.gradA(), b = s.gradB();
        int kind = Math.round(a[3]) % 10;
        float u;
        if (kind == ShaderGradient.LINEAR) {
            u = x * a[0] + y * a[1] + a[2];
        } else if (kind == ShaderGradient.RADIAL) {
            float dx = (x - a[0]) * a[2], dy = (y - a[1]) * b[0];
            u = (float) Math.sqrt((double) dx * dx + (double) dy * dy);
        } else {
            double angle = atan2(x - a[0], -(y - a[1])) * 0.15915494;
            double v = angle - a[2];
            u = (float) (v - Math.floor(v));
        }
        if (s.repeatPeriod() > 0) {
            float d = u - s.repeatOrigin();
            u = s.repeatOrigin() + (d - s.repeatPeriod() * (float) Math.floor(d / s.repeatPeriod()));
        }
        return u;
    }

    /** 区間を重ねたときの 1 画素の色。どの区間も塗らなければ 0（抜け）。重なれば最初のもの。 */
    public static int colorOf(List<ShaderGradient> segments, float x, float y) {
        for (ShaderGradient s : segments) {
            Integer c = colorOf(s, x, y);
            if (c != null) return c;
        }
        return 0;
    }
}
