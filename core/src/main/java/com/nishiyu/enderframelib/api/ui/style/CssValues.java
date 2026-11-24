package com.nishiyu.enderframelib.api.ui.style;

import java.util.Objects;

/** 生成したプロパティ関数（{@link CssProperties}）が値を組み立てる補助。 */
final class CssValues {

    private CssValues() {}

    /** 空白で連ねた値（{@code 9px 16px} など）。 */
    private record Joined(String css) implements CssValue {}

    /** 数（単位なし）。 */
    record Num(String css) implements CssValue {}

    static CssValue join(CssValue... parts) {
        StringBuilder sb = new StringBuilder();
        for (CssValue p : parts) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Objects.requireNonNull(p, "value").css());
        }
        return new Joined(sb.toString());
    }

    /** 上・右・下・左の 1〜4 個（CSS の順）。 */
    static CssValue sides(CssValue[] sides) {
        if (sides.length < 1 || sides.length > 4) throw new IllegalArgumentException("辺の値は 1〜4 個: " + sides.length);
        return join(sides);
    }

    static Num num(double v) {
        return new Num(Length.num(v));
    }

    static Num integer(int v) {
        return new Num(Integer.toString(v));
    }

    static CssValue ratio(double w, double h) {
        if (!(w > 0 && h > 0)) throw new IllegalArgumentException("比は正の数: " + w + " / " + h);
        return new Num(Length.num(w) + " / " + Length.num(h));
    }

    static Num weight(int v) {
        if (v < 1 || v > 1000) throw new IllegalArgumentException("font-weight は 1〜1000: " + v);
        return integer(v);
    }
}
