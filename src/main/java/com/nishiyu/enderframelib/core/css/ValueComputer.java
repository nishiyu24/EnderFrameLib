package com.nishiyu.enderframelib.core.css;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 指定値から算出値を作る段。
 *
 * 子が継承するのは<b>算出値</b>なので、ここで値を「親に掛け直されない形」にしておく。
 *
 * <ul>
 *   <li>CSS 全域キーワード（{@code inherit} / {@code initial} / {@code unset}）を親の算出値か初期値に置き換える</li>
 *   <li>{@code font-size} を親のフォントサイズとビューポートで px に解く</li>
 *   <li>それ以外の値の {@code em} / {@code rem} を px に解く（{@code %} と {@code vw} / {@code vh} は使用値の段で解く）</li>
 *   <li>{@code line-height} の {@code %} を px に解く（単位の無い数値はそのまま継承する）</li>
 *   <li>{@code currentcolor} を {@code color} の算出値に置き換える</li>
 * </ul>
 *
 * 何が壊れていたか: 以前は子がカスケード直後の文字列を継承していたため、
 * {@code font-size: 2em} を指定した要素の子孫が 2em を自分の親（既に 32px）に掛け直して
 * 複利で大きくなり、{@code width: inherit} は 0 になっていた。{@code font-size} の
 * {@code vw} はビューポート 0 で解かれて無効扱いになり、既定のサイズのままだった。
 */
final class ValueComputer {

    /**
     * @param parent         親の算出スタイル（ルートでは空）
     * @param parentFontSize 親のフォントサイズ（px）
     * @param rootFontSize   ルート要素のフォントサイズ（px、{@code rem} の基準）
     */
    record Context(Map<String, String> parent, float parentFontSize, float rootFontSize,
                   int viewportW, int viewportH) {}

    /** 絶対サイズのキーワード（CSS Fonts の medium = 16px からの比率表）。 */
    private static final Map<String, Float> ABSOLUTE_SIZES = Map.of(
            "xx-small", 9f, "x-small", 10f, "small", 13f, "medium", 16f,
            "large", 18f, "x-large", 24f, "xx-large", 32f, "xxx-large", 48f);
    /** {@code smaller} / {@code larger} の比率。 */
    private static final float RELATIVE_SIZE_RATIO = 1.2f;

    /** 長さとして解かない（文字列・名前・URL を持つ）プロパティ。 */
    private static final Set<String> OPAQUE_PROPERTIES = Set.of(
            CssProperty.FONT_FAMILY, CssProperty.CONTENT, CssProperty.SRC,
            CssProperty.ANIMATION_NAME, CssProperty.TRANSITION_PROPERTY,
            "grid-template-areas", "list-style-image", "background-image", "font");

    private ValueComputer() {}

    static void compute(Map<String, String> style, Context ctx) {
        resolveCssWideKeywords(style, ctx);

        String fontSize = style.get(CssProperty.FONT_SIZE);
        float ownFontSize = ctx.parentFontSize();
        if (fontSize != null) {
            ownFontSize = resolveFontSize(fontSize, ctx);
            style.put(CssProperty.FONT_SIZE, px(ownFontSize));
        }

        if ("currentcolor".equalsIgnoreCase(trimmed(style.get(CssProperty.COLOR)))) {
            putInheritedOrInitial(style, CssProperty.COLOR, ctx);
        }
        String color = style.get(CssProperty.COLOR);
        if (color == null) color = ctx.parent().get(CssProperty.COLOR);
        if (color == null) color = PropertyRegistry.initialValue(CssProperty.COLOR);

        List<String> keys = new ArrayList<>(style.keySet());
        for (String key : keys) {
            if (key.startsWith("--") || CssProperty.FONT_SIZE.equals(key)) continue;
            String value = style.get(key);
            if (value == null) continue;

            if (CssProperty.LINE_HEIGHT.equals(key) && value.trim().endsWith("%")) {
                Float pct = parseNumber(value.trim().substring(0, value.trim().length() - 1));
                if (pct != null) value = px(ownFontSize * pct / 100f);
            }
            if (!OPAQUE_PROPERTIES.contains(key)) {
                value = absolutizeFontRelative(value, ownFontSize, ctx.rootFontSize());
            }
            if (!CssProperty.COLOR.equals(key)) {
                value = replaceCurrentColor(value, color);
            }
            style.put(key, value);
        }
    }

    private static void resolveCssWideKeywords(Map<String, String> style, Context ctx) {
        List<String> keys = new ArrayList<>(style.keySet());
        for (String key : keys) {
            CssWideKeyword kw = CssWideKeyword.of(style.get(key));
            if (kw == null) continue;
            switch (kw) {
                case INHERIT -> putInheritedOrInitial(style, key, ctx);
                case INITIAL -> putInitial(style, key);
                // カスケードで解けなかった revert（UA 自身の revert や var() 経由）は unset と同じ
                case UNSET, REVERT -> {
                    if (PropertyRegistry.isInherited(key) || key.startsWith("--")) putInheritedOrInitial(style, key, ctx);
                    else putInitial(style, key);
                }
            }
        }
    }

    private static void putInheritedOrInitial(Map<String, String> style, String key, Context ctx) {
        String parentValue = ctx.parent().get(key);
        if (parentValue != null) style.put(key, parentValue);
        else putInitial(style, key);
    }

    private static void putInitial(Map<String, String> style, String key) {
        String initial = PropertyRegistry.initialValue(key);
        if (initial != null) style.put(key, initial);
        else style.remove(key);
    }

    /** font-size の em / % / smaller / larger は「親の」フォントサイズが基準。 */
    static float resolveFontSize(String value, Context ctx) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        float parent = ctx.parentFontSize();
        Float absolute = ABSOLUTE_SIZES.get(v);
        if (absolute != null) return absolute;
        if ("smaller".equals(v)) return parent / RELATIVE_SIZE_RATIO;
        if ("larger".equals(v)) return parent * RELATIVE_SIZE_RATIO;
        if (v.indexOf('(') >= 0) {
            float resolved = LengthResolver.parseDim(v, parent, ctx.viewportW(), ctx.viewportH(),
                    parent, ctx.rootFontSize());
            return resolved >= 0 ? resolved : parent;
        }

        Float result = null;
        if (v.endsWith("rem")) result = scaled(v, 3, ctx.rootFontSize());
        else if (v.endsWith("em")) result = scaled(v, 2, parent);
        else if (v.endsWith("px")) result = scaled(v, 2, 1f);
        else if (v.endsWith("%")) result = scaled(v, 1, parent / 100f);
        else if (v.endsWith("vw")) result = scaled(v, 2, ctx.viewportW() / 100f);
        else if (v.endsWith("vh")) result = scaled(v, 2, ctx.viewportH() / 100f);
        else if (v.endsWith("vmin")) result = scaled(v, 4, Math.min(ctx.viewportW(), ctx.viewportH()) / 100f);
        else if (v.endsWith("vmax")) result = scaled(v, 4, Math.max(ctx.viewportW(), ctx.viewportH()) / 100f);
        else result = parseNumber(v); // 単位の無い数値は px とみなす（エンジンの従来の扱い）

        // 負のサイズと解けない値は宣言ごと無効なので、親の値を継承したのと同じになる
        return result != null && result >= 0 ? result : parent;
    }

    private static Float scaled(String v, int unitLength, float factor) {
        Float n = parseNumber(v.substring(0, v.length() - unitLength));
        return n != null ? n * factor : null;
    }

    /**
     * 値の中の {@code <number>em} / {@code <number>rem} を px に置き換える。
     * 文字列リテラルと {@code url()} の中、識別子の一部（{@code h1em} など）は触らない。
     */
    static String absolutizeFontRelative(String value, float fontSize, float rootFontSize) {
        if (value.indexOf("em") < 0 && value.indexOf("EM") < 0) return value;
        StringBuilder out = null;
        int len = value.length();
        int i = 0;
        int copied = 0;
        while (i < len) {
            char c = value.charAt(i);
            if (c == '"' || c == '\'') {
                int end = value.indexOf(c, i + 1);
                i = end < 0 ? len : end + 1;
                continue;
            }
            if (regionStartsWithIgnoreCase(value, i, "url(")) {
                int end = value.indexOf(')', i);
                i = end < 0 ? len : end + 1;
                continue;
            }
            boolean numberStart = Character.isDigit(c)
                    || (c == '.' && i + 1 < len && Character.isDigit(value.charAt(i + 1)));
            boolean signed = (c == '-' || c == '+') && i + 1 < len
                    && (Character.isDigit(value.charAt(i + 1)) || value.charAt(i + 1) == '.');
            if ((numberStart || signed) && !isIdentChar(i > 0 ? value.charAt(i - 1) : ' ')) {
                int numEnd = i + 1;
                while (numEnd < len && (Character.isDigit(value.charAt(numEnd)) || value.charAt(numEnd) == '.')) numEnd++;
                int unitEnd = numEnd;
                while (unitEnd < len && Character.isLetter(value.charAt(unitEnd))) unitEnd++;
                String unit = value.substring(numEnd, unitEnd).toLowerCase(Locale.ROOT);
                float base = "em".equals(unit) ? fontSize : "rem".equals(unit) ? rootFontSize : -1f;
                Float number = base >= 0 ? parseNumber(value.substring(i, numEnd)) : null;
                if (number != null) {
                    if (out == null) out = new StringBuilder(len + 8);
                    out.append(value, copied, i).append(px(number * base));
                    copied = unitEnd;
                }
                i = unitEnd;
                continue;
            }
            i++;
        }
        if (out == null) return value;
        out.append(value, copied, len);
        return out.toString();
    }

    private static String replaceCurrentColor(String value, String color) {
        String lower = value.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf("currentcolor");
        if (idx < 0 || color == null) return value;
        StringBuilder out = new StringBuilder();
        int copied = 0;
        while (idx >= 0) {
            boolean boundary = (idx == 0 || !isIdentChar(lower.charAt(idx - 1)))
                    && (idx + 12 >= lower.length() || !isIdentChar(lower.charAt(idx + 12)));
            if (boundary) {
                out.append(value, copied, idx).append(color);
                copied = idx + 12;
            }
            idx = lower.indexOf("currentcolor", idx + 12);
        }
        out.append(value, copied, value.length());
        return out.toString();
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '#';
    }

    private static boolean regionStartsWithIgnoreCase(String s, int at, String prefix) {
        return s.regionMatches(true, at, prefix, 0, prefix.length());
    }

    private static String trimmed(String s) {
        return s == null ? null : s.trim();
    }

    private static Float parseNumber(String s) {
        try {
            return Float.parseFloat(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** px の値を算出値の文字列にする。整数なら小数点を付けない（ゴールデンの差分を読みやすくするため）。 */
    static String px(float value) {
        float rounded = Math.round(value);
        if (Math.abs(value - rounded) < 1e-4f) return (long) rounded + "px";
        String s = String.format(Locale.ROOT, "%.4f", value);
        s = s.replaceAll("0+$", "");
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s + "px";
    }
}
