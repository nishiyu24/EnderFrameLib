package com.nishiyu.enderframelib.api.ui.style;

import org.jetbrains.annotations.ApiStatus;

/**
 * 長さ（{@code px}・{@code %}・{@code em}・{@code rem}）と {@code auto}。{@link Css#px} などで作る。
 */
@ApiStatus.AvailableSince("1.0")
public final class Length implements Value<Length> {

    public static final Length ZERO = new Length("0");
    public static final Length AUTO = new Length("auto");

    public static Length vw(double v) {
        return new Length(num(v) + "vw");
    }

    public static Length vh(double v) {
        return new Length(num(v) + "vh");
    }

    private final String css;

    private Length(String css) {
        this.css = css;
    }

    public static Length px(double v) {
        return new Length(num(v) + "px");
    }

    public static Length pct(double v) {
        return new Length(num(v) + "%");
    }

    public static Length em(double v) {
        return new Length(num(v) + "em");
    }

    public static Length rem(double v) {
        return new Length(num(v) + "rem");
    }

    static String num(double v) {
        if (!Double.isFinite(v)) throw new IllegalArgumentException("長さが有限でない: " + v);
        return v == Math.rint(v) && Math.abs(v) < 1e15 ? Long.toString((long) v) : Double.toString(v);
    }

    @Override
    public String css() {
        return css;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Length l && l.css.equals(css);
    }

    @Override
    public int hashCode() {
        return css.hashCode();
    }

    @Override
    public String toString() {
        return css;
    }
}
