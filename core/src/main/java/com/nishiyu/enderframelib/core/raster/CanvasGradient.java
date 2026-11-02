package com.nishiyu.enderframelib.core.raster;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Canvas 2D の {@code CanvasGradient}（線形・放射・円錐）と、SVG のグラデーション。
 *
 * 色は非乗算の RGBA のまま補間し、補間したあとで事前乗算する（Canvas 2D の仕様）。
 * 放射グラデーションは 2 つの円を結ぶ円錐（Canvas の {@code createRadialGradient} と同じ）。
 */
public final class CanvasGradient extends Paint {

    public enum Kind { LINEAR, RADIAL, CONIC }

    /** 範囲の外の扱い。Canvas は常に {@code PAD}、SVG は {@code spreadMethod}。 */
    public enum Spread { PAD, REFLECT, REPEAT }

    private static final int LUT_SIZE = 1024;

    private record Stop(double offset, int argb) {}

    private final Kind kind;
    private final double x0;
    private final double y0;
    private final double r0;
    private final double x1;
    private final double y1;
    private final double r1;
    private final double startAngle;
    private final List<Stop> stops = new ArrayList<>();
    private Spread spread = Spread.PAD;
    private Matrix2D transform = Matrix2D.IDENTITY;
    private int[] lut;

    private CanvasGradient(Kind kind, double x0, double y0, double r0, double x1, double y1, double r1, double startAngle) {
        this.kind = kind;
        this.x0 = x0;
        this.y0 = y0;
        this.r0 = r0;
        this.x1 = x1;
        this.y1 = y1;
        this.r1 = r1;
        this.startAngle = startAngle;
    }

    public static CanvasGradient linear(double x0, double y0, double x1, double y1) {
        requireFinite(x0, y0, x1, y1);
        return new CanvasGradient(Kind.LINEAR, x0, y0, 0, x1, y1, 0, 0);
    }

    public static CanvasGradient radial(double x0, double y0, double r0, double x1, double y1, double r1) {
        requireFinite(x0, y0, r0, x1, y1, r1);
        if (r0 < 0 || r1 < 0) throw new IllegalArgumentException("IndexSizeError: negative radius");
        return new CanvasGradient(Kind.RADIAL, x0, y0, r0, x1, y1, r1, 0);
    }

    public static CanvasGradient conic(double startAngle, double x, double y) {
        requireFinite(startAngle, x, y);
        return new CanvasGradient(Kind.CONIC, x, y, 0, x, y, 0, startAngle);
    }

    private static void requireFinite(double... v) {
        for (double d : v) if (!Double.isFinite(d)) throw new IllegalArgumentException("TypeError: non-finite");
    }

    /** {@code offset} は 0〜1。色は非乗算の ARGB。 */
    public CanvasGradient addColorStop(double offset, int straightArgb) {
        if (!(offset >= 0 && offset <= 1)) throw new IllegalArgumentException("IndexSizeError: offset " + offset);
        stops.add(new Stop(offset, straightArgb));
        lut = null;
        return this;
    }

    public CanvasGradient spread(Spread spread) {
        this.spread = spread;
        return this;
    }

    /** グラデーションの座標系から、塗りの座標系への変換（SVG の {@code gradientTransform} など）。 */
    public CanvasGradient transform(Matrix2D transform) {
        this.transform = transform;
        return this;
    }

    public int stopCount() {
        return stops.size();
    }

    private int[] lut() {
        int[] l = lut;
        if (l != null) return l;
        List<Stop> sorted = new ArrayList<>(stops);
        sorted.sort(Comparator.comparingDouble(Stop::offset));
        l = new int[LUT_SIZE];
        if (sorted.isEmpty()) {
            lut = l;
            return l;
        }
        int seg = 0;
        for (int i = 0; i < LUT_SIZE; i++) {
            double t = (double) i / (LUT_SIZE - 1);
            int argb;
            if (t <= sorted.get(0).offset()) {
                argb = sorted.get(0).argb();
            } else if (t >= sorted.get(sorted.size() - 1).offset()) {
                argb = sorted.get(sorted.size() - 1).argb();
            } else {
                while (seg < sorted.size() - 2 && sorted.get(seg + 1).offset() < t) seg++;
                Stop a = sorted.get(seg);
                Stop b = sorted.get(seg + 1);
                double span = b.offset() - a.offset();
                double f = span <= 0 ? 1 : (t - a.offset()) / span;
                argb = mix(a.argb(), b.argb(), f);
            }
            l[i] = Pixmap.premultiply(argb);
        }
        lut = l;
        return l;
    }

    private static int mix(int a, int b, double f) {
        int aa = (int) Math.round(((a >>> 24) & 0xFF) * (1 - f) + ((b >>> 24) & 0xFF) * f);
        int ar = (int) Math.round(((a >> 16) & 0xFF) * (1 - f) + ((b >> 16) & 0xFF) * f);
        int ag = (int) Math.round(((a >> 8) & 0xFF) * (1 - f) + ((b >> 8) & 0xFF) * f);
        int ab = (int) Math.round((a & 0xFF) * (1 - f) + (b & 0xFF) * f);
        return (aa << 24) | (ar << 16) | (ag << 8) | ab;
    }

    private double applySpread(double t) {
        switch (spread) {
            case REPEAT -> {
                return t - Math.floor(t);
            }
            case REFLECT -> {
                double m = t - 2 * Math.floor(t / 2);
                return m > 1 ? 2 - m : m;
            }
            default -> {
                return t < 0 ? 0 : (t > 1 ? 1 : t);
            }
        }
    }

    @Override
    Shader shader(Matrix2D toDevice) {
        if (stops.isEmpty()) return (x, y) -> 0;
        Matrix2D inv = toDevice.multiply(transform).invert();
        if (inv == null) return (x, y) -> 0;
        final int[] table = lut();
        switch (kind) {
            case LINEAR -> {
                final double dx = x1 - x0;
                final double dy = y1 - y0;
                final double len2 = dx * dx + dy * dy;
                if (len2 == 0) return (x, y) -> 0;
                return (px, py) -> {
                    double gx = inv.applyX(px, py);
                    double gy = inv.applyY(px, py);
                    double t = ((gx - x0) * dx + (gy - y0) * dy) / len2;
                    return table[(int) Math.round(applySpread(t) * (LUT_SIZE - 1))];
                };
            }
            case RADIAL -> {
                final double cdx = x1 - x0;
                final double cdy = y1 - y0;
                final double dr = r1 - r0;
                final double a = cdx * cdx + cdy * cdy - dr * dr;
                return (px, py) -> {
                    double gx = inv.applyX(px, py) - x0;
                    double gy = inv.applyY(px, py) - y0;
                    double b = gx * cdx + gy * cdy + r0 * dr;
                    double c = gx * gx + gy * gy - r0 * r0;
                    double s;
                    if (Math.abs(a) < 1e-12) {
                        if (Math.abs(b) < 1e-12) return 0;
                        s = c / (2 * b);
                        if (r0 + s * dr < 0) return 0;
                    } else {
                        double disc = b * b - a * c;
                        if (disc < 0) return 0;
                        double sq = Math.sqrt(disc);
                        double s1 = (b + sq) / a;
                        double s2 = (b - sq) / a;
                        double hi = Math.max(s1, s2);
                        double lo = Math.min(s1, s2);
                        if (r0 + hi * dr >= 0) s = hi;
                        else if (r0 + lo * dr >= 0) s = lo;
                        else return 0;
                    }
                    return table[(int) Math.round(applySpread(s) * (LUT_SIZE - 1))];
                };
            }
            default -> {
                return (px, py) -> {
                    double gx = inv.applyX(px, py) - x0;
                    double gy = inv.applyY(px, py) - y0;
                    double angle = Math.atan2(gy, gx) - startAngle;
                    double t = angle / (Math.PI * 2);
                    t -= Math.floor(t);
                    return table[(int) Math.round(t * (LUT_SIZE - 1))];
                };
            }
        }
    }
}
