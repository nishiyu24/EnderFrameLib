package com.nishiyu.enderframelib.api.ui.style;

import org.jetbrains.annotations.ApiStatus;

/** 時間（{@code ms}・{@code s}）。{@link Css#ms}・{@link Css#sec} で作る。 */
@ApiStatus.AvailableSince("1.0")
public final class Time implements Value<Time> {

    private final String css;

    private Time(String css) {
        this.css = css;
    }

    public static Time ms(double v) {
        return new Time(Length.num(v) + "ms");
    }

    public static Time sec(double v) {
        return new Time(Length.num(v) + "s");
    }

    @Override
    public String css() {
        return css;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Time t && t.css.equals(css);
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
