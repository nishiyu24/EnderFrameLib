package com.nishiyu.enderframelib.core.raster.svg;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.raster.Matrix2D;

import java.util.Locale;

/** {@code svg} 要素の寸法・{@code viewBox}・{@code preserveAspectRatio}（SVG 1.1 §7.8）と長さの単位。 */
public final class SvgSize {

    private SvgSize() {}

    /** {@code width} / {@code height} 属性（px）。無い・百分率なら NaN。 */
    public record Size(double width, double height) {}

    public static Size of(HtmlNode svg) {
        return new Size(absolute(svg.attrs.get("width")), absolute(svg.attrs.get("height")));
    }

    private static double absolute(String s) {
        if (s == null || s.trim().endsWith("%")) return Double.NaN;
        double v = length(s, Double.NaN, 0, 16);
        return v > 0 ? v : Double.NaN;
    }

    /** {@code [minX, minY, width, height]}。無い・不正（幅か高さが 0 以下）なら null。 */
    public static double[] viewBox(HtmlNode svg) {
        String s = svg.attrs.get("viewbox");
        if (s == null) return null;
        double[] v = SvgRenderer.numbers(s);
        if (v.length != 4 || !(v[2] > 0) || !(v[3] > 0)) return null;
        return v;
    }

    /**
     * 画像としての固有寸法。{@code width} / {@code height} が揃えばそれ、片方だけなら {@code viewBox} の比で補い、
     * 無ければ {@code viewBox} の大きさ。どれも無ければ幅・高さとも NaN。
     *
     * @return {@code [width, height]}（決められない方は NaN）
     */
    public static double[] intrinsic(HtmlNode svg) {
        Size s = of(svg);
        double[] vb = viewBox(svg);
        double w = s.width(), h = s.height();
        if (Double.isNaN(w) && Double.isNaN(h)) {
            return vb != null && !hasPercent(svg) ? new double[]{vb[2], vb[3]} : new double[]{Double.NaN, Double.NaN};
        }
        if (vb != null) {
            if (Double.isNaN(w)) w = h * vb[2] / vb[3];
            if (Double.isNaN(h)) h = w * vb[3] / vb[2];
        }
        return new double[]{w, h};
    }

    /** 縦横比（無ければ NaN）。 */
    public static double aspectRatio(HtmlNode svg) {
        double[] d = intrinsic(svg);
        if (!Double.isNaN(d[0]) && !Double.isNaN(d[1]) && d[1] > 0) return d[0] / d[1];
        double[] vb = viewBox(svg);
        return vb != null ? vb[2] / vb[3] : Double.NaN;
    }

    private static boolean hasPercent(HtmlNode svg) {
        String w = svg.attrs.get("width"), h = svg.attrs.get("height");
        return (w != null && w.trim().endsWith("%")) || (h != null && h.trim().endsWith("%"));
    }

    public static boolean isSlice(String preserveAspectRatio) {
        return preserveAspectRatio != null && preserveAspectRatio.toLowerCase(Locale.ROOT).contains("slice");
    }

    /** viewBox の座標を、{@code w × h} の領域へ写す変換（{@code preserveAspectRatio} 対応）。 */
    public static Matrix2D viewBoxTransform(double[] vb, double vbW, double vbH, double w, double h, String par) {
        double minX = vb != null ? vb[0] : 0, minY = vb != null ? vb[1] : 0;
        double sx = w / vbW, sy = h / vbH;
        String p = par == null ? "xmidymid" : par.trim().toLowerCase(Locale.ROOT);
        if (p.startsWith("defer")) p = p.substring(5).trim();
        if (p.startsWith("none")) {
            return Matrix2D.scale(sx, sy).multiply(Matrix2D.translate(-minX, -minY));
        }
        boolean slice = p.contains("slice");
        double s = slice ? Math.max(sx, sy) : Math.min(sx, sy);
        double extraX = w - vbW * s, extraY = h - vbH * s;
        double ax = p.contains("xmin") ? 0 : p.contains("xmax") ? 1 : 0.5;
        double ay = p.contains("ymin") ? 0 : p.contains("ymax") ? 1 : 0.5;
        return Matrix2D.translate(extraX * ax, extraY * ay).multiply(Matrix2D.scale(s, s))
                .multiply(Matrix2D.translate(-minX, -minY));
    }

    /**
     * SVG の長さを px にする。単位: なし / px / pt / pc / mm / cm / in / em / ex / %（{@code ref} に対する割合）。
     * 読めなければ {@code def}。
     */
    public static double length(String s, double def, double ref, double fontPx) {
        if (s == null) return def;
        String t = s.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return def;
        double mul = 1;
        String num = t;
        if (t.endsWith("%")) {
            num = t.substring(0, t.length() - 1);
            mul = ref / 100.0;
        } else if (t.endsWith("px")) {
            num = t.substring(0, t.length() - 2);
        } else if (t.endsWith("pt")) {
            num = t.substring(0, t.length() - 2);
            mul = 4.0 / 3.0;
        } else if (t.endsWith("pc")) {
            num = t.substring(0, t.length() - 2);
            mul = 16;
        } else if (t.endsWith("mm")) {
            num = t.substring(0, t.length() - 2);
            mul = 96 / 25.4;
        } else if (t.endsWith("cm")) {
            num = t.substring(0, t.length() - 2);
            mul = 96 / 2.54;
        } else if (t.endsWith("in")) {
            num = t.substring(0, t.length() - 2);
            mul = 96;
        } else if (t.endsWith("em")) {
            num = t.substring(0, t.length() - 2);
            mul = fontPx;
        } else if (t.endsWith("ex")) {
            num = t.substring(0, t.length() - 2);
            mul = fontPx / 2;
        }
        try {
            double v = Double.parseDouble(num.trim()) * mul;
            return Double.isFinite(v) ? v : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
