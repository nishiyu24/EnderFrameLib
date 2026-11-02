package com.nishiyu.enderframelib.core.raster;

/**
 * 2D のアフィン変換（Canvas 2D の {@code DOMMatrix} と同じ並び）。
 * {@code x' = a*x + c*y + e}、{@code y' = b*x + d*y + f}。
 */
public record Matrix2D(double a, double b, double c, double d, double e, double f) {

    public static final Matrix2D IDENTITY = new Matrix2D(1, 0, 0, 1, 0, 0);

    public static Matrix2D translate(double tx, double ty) {
        return new Matrix2D(1, 0, 0, 1, tx, ty);
    }

    public static Matrix2D scale(double sx, double sy) {
        return new Matrix2D(sx, 0, 0, sy, 0, 0);
    }

    public static Matrix2D rotate(double radians) {
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new Matrix2D(cos, sin, -sin, cos, 0, 0);
    }

    /** {@code this} の後に {@code o} を適用する変換ではなく、{@code this * o}（o を先に適用し、続けて this）。 */
    public Matrix2D multiply(Matrix2D o) {
        return new Matrix2D(
                a * o.a + c * o.b,
                b * o.a + d * o.b,
                a * o.c + c * o.d,
                b * o.c + d * o.d,
                a * o.e + c * o.f + e,
                b * o.e + d * o.f + f);
    }

    public double determinant() {
        return a * d - b * c;
    }

    public boolean isInvertible() {
        double det = determinant();
        return det != 0 && Double.isFinite(det);
    }

    public boolean isFinite() {
        return Double.isFinite(a) && Double.isFinite(b) && Double.isFinite(c)
                && Double.isFinite(d) && Double.isFinite(e) && Double.isFinite(f);
    }

    /** 逆変換。特異なら {@code null}。 */
    public Matrix2D invert() {
        double det = determinant();
        if (det == 0 || !Double.isFinite(det)) return null;
        double ia = d / det;
        double ib = -b / det;
        double ic = -c / det;
        double id = a / det;
        double ie = -(ia * e + ic * f);
        double iff = -(ib * e + id * f);
        return new Matrix2D(ia, ib, ic, id, ie, iff);
    }

    public double applyX(double x, double y) {
        return a * x + c * y + e;
    }

    public double applyY(double x, double y) {
        return b * x + d * y + f;
    }

    /** 平行移動だけの変換か。 */
    public boolean isTranslation() {
        return a == 1 && b == 0 && c == 0 && d == 1;
    }

    /** 長さの平均的な拡大率（{@code sqrt(|det|)}）。 */
    public double meanScale() {
        return Math.sqrt(Math.abs(determinant()));
    }
}
