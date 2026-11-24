package com.nishiyu.enderframelib.api.ui.style;

import org.jetbrains.annotations.ApiStatus;

/**
 * sRGB の色（不透明度つき）。{@code Color.hex(0x38bdf8)} や {@code Color.rgba(56, 189, 248, 0.5)} で作る。
 */
@ApiStatus.AvailableSince("1.0")
public final class Color implements Value<Color> {

    public static final Color TRANSPARENT = new Color(0, 0, 0, 0);
    public static final Color WHITE = hex(0xffffff);
    public static final Color BLACK = hex(0x000000);

    private final int r, g, b;
    private final double a;

    private Color(int r, int g, int b, double a) {
        if ((r | g | b) >>> 8 != 0) throw new IllegalArgumentException("色の成分は 0〜255: " + r + "," + g + "," + b);
        if (!(a >= 0 && a <= 1)) throw new IllegalArgumentException("不透明度は 0〜1: " + a);
        this.r = r;
        this.g = g;
        this.b = b;
        this.a = a;
    }

    /** {@code 0xRRGGBB}（不透明）。 */
    public static Color hex(int rgb) {
        if (rgb >>> 24 != 0) throw new IllegalArgumentException("0xRRGGBB の範囲外: 0x" + Integer.toHexString(rgb));
        return new Color(rgb >> 16 & 0xff, rgb >> 8 & 0xff, rgb & 0xff, 1);
    }

    public static Color rgba(int r, int g, int b, double a) {
        return new Color(r, g, b, a);
    }

    /** 同じ色で不透明度だけを変える。 */
    public Color alpha(double a) {
        return new Color(r, g, b, a);
    }

    /** {@code #rrggbb}（不透明度は落とす。表示用）。 */
    public String hexString() {
        return String.format("#%02x%02x%02x", r, g, b);
    }

    @Override
    public String css() {
        return a == 1 ? hexString() : "rgba(" + r + "," + g + "," + b + "," + Length.num(a) + ")";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Color c && c.r == r && c.g == g && c.b == b && c.a == a;
    }

    @Override
    public int hashCode() {
        return ((r * 31 + g) * 31 + b) * 31 + Double.hashCode(a);
    }

    @Override
    public String toString() {
        return css();
    }
}
