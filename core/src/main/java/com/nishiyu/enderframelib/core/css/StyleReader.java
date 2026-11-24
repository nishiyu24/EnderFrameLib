package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.css.model.CssKeywords;

/**
 * 生成された型付き群の構築が、算出スタイルから 1 つの宣言を解く読み口。宣言が無い（または {@code cs} が null）ときは
 * {@link PropertyRegistry} の初期値を使う。解き方は旧 {@code ComputedStyle} の型付き読み取りと同じ。
 */
final class StyleReader {
    private StyleReader() {}

    /** 型付き群の構築が宣言を読むための最小限の入力（{@link StyleTable} やマップからのアダプタ）。 */
    interface Source {
        String get(int id);
        float fontSizePx();
        float rootFontSizePx();
    }

    private static String raw(Source cs, int id) {
        String t = cs != null ? cs.get(id) : null;
        return t != null ? t : ComputedStyle.initialOf(id);
    }

    /** 長さ。宣言が無ければ {@link CssLength#UNDECLARED}（0 px に解け、旧 {@code ComputedStyle#length} と同じ。「宣言があるか」を読み手が問える）。 */
    static long length(Source cs, int id, CssExprs.Builder exprs) {
        String t = cs != null ? cs.get(id) : null;
        return t == null ? CssLength.UNDECLARED : CssLength.parse(t, exprs);
    }

    /** 単位なしの数値。解けない値（{@code auto} など）は NaN。 */
    static float number(Source cs, int id) {
        String t = raw(cs, id);
        if (t == null) return Float.NaN;
        String s = t.trim();
        if (s.isEmpty()) return Float.NaN;
        char c = s.charAt(0);
        if ((c < '0' || c > '9') && c != '-' && c != '+' && c != '.') return Float.NaN;
        try {
            return Float.parseFloat(s);
        } catch (NumberFormatException e) {
            return Float.NaN;
        }
    }

    static int color(Source cs, int id) {
        String t = raw(cs, id);
        if (t == null || "currentcolor".equalsIgnoreCase(t) || "invert".equalsIgnoreCase(t)) return 0;
        return ColorParser.parseColor(t);
    }

    /** 文字列のまま持つ値。宣言が無ければ null（旧 {@code ComputedStyle#get} と同じ。呼び出し側が既定値を決める）。 */
    static String text(Source cs, int id) {
        return cs != null ? cs.get(id) : null;
    }

    static <E extends Enum<E> & CssKeywords.Keyword> E keyword(Source cs, int id, Class<E> type) {
        E parsed = CssKeywords.parse(type, cs != null ? cs.get(id) : null);
        return parsed != null ? parsed : PropertyRegistry.initialKeyword(ComputedStyle.CssPropertyId.name(id), type);
    }

    /** 揃えの宣言に {@code safe } 修飾が付いているか（{@link CssKeywords#parse} が落とす情報を補う）。 */
    static boolean isSafeKeyword(Source cs, int id) {
        String raw = cs != null ? cs.get(id) : null;
        if (raw == null) return false;
        String t = raw.trim();
        return t.regionMatches(true, 0, "safe", 0, 4) && t.length() > 4 && Character.isWhitespace(t.charAt(4));
    }

    /** 色が未宣言・{@code currentcolor}・（{@code allowInvert} 時は {@code invert}）か。 */
    static boolean isCurrentColor(Source cs, int id, boolean allowInvert) {
        String raw = cs != null ? cs.get(id) : null;
        if (raw == null) return true;
        String t = raw.trim();
        return t.isEmpty() || "currentcolor".equalsIgnoreCase(t) || (allowInvert && "invert".equalsIgnoreCase(t));
    }

    static boolean isInvertKeyword(Source cs, int id) {
        String raw = cs != null ? cs.get(id) : null;
        return raw != null && "invert".equalsIgnoreCase(raw.trim());
    }

    static boolean isDeclared(Source cs, int id) {
        return cs != null && cs.get(id) != null;
    }

    static int fontWeight(Source cs, int id) {
        String t = raw(cs, id);
        return ValueKeywords.numericFontWeight(t == null ? "normal" : t);
    }

    /** 上位に {@code LH_*}、下位 32 bit に値（{@code ComputedStyle.LH_*} を返す）。 */
    static long lineHeightBits(Source cs, int id) {
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

    static float lineHeightValue(Source cs, int id) {
        return Float.intBitsToFloat((int) lineHeightBits(cs, id));
    }
}
