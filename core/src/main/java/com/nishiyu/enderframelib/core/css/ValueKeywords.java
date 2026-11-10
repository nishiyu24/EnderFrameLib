package com.nishiyu.enderframelib.core.css;

import java.util.regex.Pattern;

/**
 * 値の文字列だけで決まる、レイアウトに依らない CSS キーワード判定（L3）。
 * {@code layout.model.LayoutMetrics} が持っていた純粋関数を、算出スタイル側（css）が逆方向（L3→L4）に参照しないよう移したもの。
 */
public final class ValueKeywords {
    private static final Pattern BOLD_FONT_WEIGHT = Pattern.compile("(?i)bold|bolder");

    private ValueKeywords() {}

    /** 長さではなく内容から決まるサイズのキーワード（{@code min-content} / {@code max-content} / {@code fit-content} / {@code auto}）か。 */
    public static boolean isIntrinsicSizeKeyword(String value) {
        if (value == null) return false;
        String v = value.trim().toLowerCase();
        return v.equals("min-content") || v.equals("max-content")
                || v.equals("fit-content") || v.startsWith("fit-content(") || v.equals("auto");
    }

    /**
     * {@code font-weight} の数値（1〜1000）。{@code normal} は 400、{@code bold} は 700。
     * {@code bolder} / {@code lighter} は親の太さが要るが、ここでは親が通常の太さとして 700 / 100 にする。
     */
    public static int numericFontWeight(String weight) {
        if (weight == null) return 400;
        String w = weight.trim();
        if (w.isEmpty()) return 400;
        if (!Character.isDigit(w.charAt(0))) {
            if (BOLD_FONT_WEIGHT.matcher(w).matches()) return 700;
            return w.equalsIgnoreCase("lighter") ? 100 : 400;
        }
        // 文字ごとの測定のたびに呼ばれるので、数値でない値で例外を投げない
        int value = 0;
        for (int i = 0; i < w.length() && Character.isDigit(w.charAt(i)); i++) value = value * 10 + (w.charAt(i) - '0');
        return Math.clamp(value, 1, 1000);
    }
}
