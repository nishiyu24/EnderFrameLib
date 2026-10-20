package com.nishiyu.enderframelib.core.layout.model;

import com.nishiyu.enderframelib.core.layout.*;
import com.nishiyu.enderframelib.core.layout.model.*;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssKeywords;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontStyle;
import com.nishiyu.enderframelib.core.text.TextShaper;

import java.util.regex.Pattern;

/**
 * レイアウト全体で共有する定数と、文字列まわりの小さな共通処理。
 *
 * 折り返し・空白・省略そのものは {@link TextShaper} が持つ。
 */
public final class LayoutMetrics {

    /** 「幅の制約が無い」ことを表す十分大きな幅。 */
    public static final int UNBOUNDED_WIDTH = 9999;

    /** 置換要素のサイズが取得できなかったときの既定サイズ（px）。 */
    public static final int FALLBACK_REPLACED_SIZE = 16;
    /** リモート画像のサイズが未取得のときの暫定サイズ（px）。 */
    public static final int PENDING_IMAGE_SIZE = 32;

    /** font-weight が太字扱いになる値。 */
    private static final Pattern BOLD_FONT_WEIGHT = Pattern.compile("(?i)bold|bolder|700|800|900");

    private LayoutMetrics() {}

    /**
     * 長さではなく内容から決まるサイズのキーワードか。
     */
    public static boolean isIntrinsicSizeKeyword(String value) {
        if (value == null) return false;
        String v = value.trim().toLowerCase();
        return v.equals("min-content") || v.equals("max-content")
                || v.equals("fit-content") || v.startsWith("fit-content(") || v.equals("auto");
    }

    public static boolean isBold(ComputedStyle style) {
        return isBoldWeight(style.getOrDefault(CssProperty.FONT_WEIGHT, ""));
    }

    public static boolean isBoldWeight(String weight) {
        return weight != null && BOLD_FONT_WEIGHT.matcher(weight.trim()).matches();
    }

    /**
     * {@code text-transform} を適用する。
     */
    public static String applyTextTransform(String text, ComputedStyle style) {
        if (text == null || text.isEmpty()) return text;
        return switch (style.keywordOf(CssProperty.TEXT_TRANSFORM, CssKeywords.TextTransform.class)) {
            case UPPERCASE -> text.toUpperCase(java.util.Locale.ROOT);
            case LOWERCASE -> text.toLowerCase(java.util.Locale.ROOT);
            case CAPITALIZE -> capitalizeWords(text);
            case NONE -> text;
        };
    }

    private static String capitalizeWords(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        boolean atWordStart = true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            sb.append(atWordStart ? Character.toUpperCase(c) : c);
            atWordStart = Character.isWhitespace(c);
        }
        return sb.toString();
    }

    /**
     * 1 行が占める高さ（px）。
     */
    public static float lineHeightPx(ComputedStyle style, FontFace face, FontStyle font) {
        String raw = style.get(CssProperty.LINE_HEIGHT);
        if (raw == null) return face.normalLineHeight(font);

        String value = raw.trim();
        if (value.isEmpty() || value.equalsIgnoreCase("normal")) return face.normalLineHeight(font);

        try {
            if (value.endsWith("px")) {
                return Float.parseFloat(value.substring(0, value.length() - 2).trim());
            }
            if (value.endsWith("%")) {
                return Float.parseFloat(value.substring(0, value.length() - 1).trim()) / 100f * font.fontSizePx();
            }
            if (value.endsWith("em")) {
                return Float.parseFloat(value.substring(0, value.length() - 2).trim()) * font.fontSizePx();
            }
            return Float.parseFloat(value) * font.fontSizePx();
        } catch (NumberFormatException e) {
            return face.normalLineHeight(font);
        }
    }
}
