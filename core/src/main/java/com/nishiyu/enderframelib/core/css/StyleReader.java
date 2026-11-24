package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.css.model.CssKeywords;

/**
 * 生成された型付き群の構築が、算出スタイルから 1 つの宣言を解く読み口。宣言が無い（または {@code cs} が null）ときは
 * {@link PropertyRegistry} の初期値を使う。解き方は旧 {@code ComputedStyle} の型付き読み取りと同じ。
 */
final class StyleReader {
    private StyleReader() {}

    private static String raw(ComputedStyle cs, int id) {
        String t = cs != null ? cs.get(id) : null;
        return t != null ? t : ComputedStyle.initialOf(id);
    }

    /** 長さ。宣言が無ければ {@link CssLength#UNDECLARED}（0 px に解け、旧 {@code ComputedStyle#length} と同じ。「宣言があるか」を読み手が問える）。 */
    static long length(ComputedStyle cs, int id, CssExprs.Builder exprs) {
        String t = cs != null ? cs.get(id) : null;
        return t == null ? CssLength.UNDECLARED : CssLength.parse(t, exprs);
    }

    /** 単位なしの数値。解けない値（{@code auto} など）は NaN。 */
    static float number(ComputedStyle cs, int id) {
        String t = raw(cs, id);
        if (t == null) return Float.NaN;
        try {
            return Float.parseFloat(t.trim());
        } catch (NumberFormatException e) {
            return Float.NaN;
        }
    }

    static int color(ComputedStyle cs, int id) {
        String t = raw(cs, id);
        return t == null ? 0 : ColorParser.parseColor(t);
    }

    /** 文字列のまま持つ値。宣言が無ければ null（旧 {@code ComputedStyle#get} と同じ。呼び出し側が既定値を決める）。 */
    static String text(ComputedStyle cs, int id) {
        return cs != null ? cs.get(id) : null;
    }

    static <E extends Enum<E> & CssKeywords.Keyword> E keyword(ComputedStyle cs, int id, Class<E> type) {
        E parsed = CssKeywords.parse(type, cs != null ? cs.get(id) : null);
        return parsed != null ? parsed : PropertyRegistry.initialKeyword(ComputedStyle.CssPropertyId.name(id), type);
    }

    static int fontWeight(ComputedStyle cs, int id) {
        String t = raw(cs, id);
        return ValueKeywords.numericFontWeight(t == null ? "normal" : t);
    }

    /** 上位に {@code LH_*}、下位 32 bit に値（旧 {@code CssValue#parseLineHeight} と同じ解き方）。 */
    static long lineHeightBits(ComputedStyle cs, int id) {
        String v = raw(cs, id);
        v = v == null ? "" : v.trim();
        byte kind = ComputedStyle.LH_NORMAL;
        float value = 0f;
        if (!v.isEmpty() && !v.equalsIgnoreCase("normal")) {
            try {
                if (v.endsWith("px")) {
                    value = Float.parseFloat(v.substring(0, v.length() - 2).trim());
                    kind = ComputedStyle.LH_PX;
                } else if (v.endsWith("%")) {
                    value = Float.parseFloat(v.substring(0, v.length() - 1).trim()) / 100f;
                    kind = ComputedStyle.LH_PERCENT;
                } else if (v.endsWith("em")) {
                    value = Float.parseFloat(v.substring(0, v.length() - 2).trim());
                    kind = ComputedStyle.LH_EM;
                } else {
                    value = Float.parseFloat(v);
                    kind = ComputedStyle.LH_NUMBER;
                }
            } catch (NumberFormatException e) {
                kind = ComputedStyle.LH_NORMAL;
                value = 0f;
            }
        }
        return ((long) (kind & 0xFF) << 32) | (Float.floatToRawIntBits(value) & 0xFFFFFFFFL);
    }

    static float lineHeightValue(ComputedStyle cs, int id) {
        return Float.intBitsToFloat((int) lineHeightBits(cs, id));
    }
}
