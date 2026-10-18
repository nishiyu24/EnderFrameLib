package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.text.FontFace;
import com.nishiyu.enderframelib.core.text.FontStyle;

import java.util.regex.Pattern;

/**
 * レイアウト全体で共有する定数と、文字列まわりの小さな共通処理。
 *
 * 以前はこれらが各エンジン・LayoutBox・レンダラにコピーされていて
 * （line-height の解析が 4 コピー、単語分割の正規表現が 4 コピー、
 *  太字判定が 4 コピー、基準フォントサイズ 16 が 11 箇所）、
 * 片方だけ直すと測定と配置がずれる状態だった。ここが唯一の出所。
 *
 * 折り返し・空白・省略そのものは {@link com.nishiyu.enderframelib.core.text.TextShaper} が持つ。
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
     *
     * 何が壊れていたか: これらは {@code LengthResolver} で解析できず 0 px になるため、
     * ua.css の {@code button, input { min-width: min-content }} が
     * 「最小幅 0」として扱われ、flex の shrink でボタンが潰れていた。
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
     *
     * 何が壊れていたか: 以前は描画側（UIRenderer）だけが uppercase を適用し、
     * 測定側は変換前の文字列を測っていたため、大文字化したテキストは
     * 幅を狭く見積もられて描画がはみ出していた。しかもインラインフラグメント経路では
     * 変換そのものが抜けていた。変換は表示文字列を作るこの 1 箇所だけで行う。
     */
    public static String applyTextTransform(String text, ComputedStyle style) {
        if (text == null || text.isEmpty()) return text;
        String transform = style.keyword(CssProperty.TEXT_TRANSFORM, "none");
        return switch (transform) {
            case "uppercase" -> text.toUpperCase(java.util.Locale.ROOT);
            case "lowercase" -> text.toLowerCase(java.util.Locale.ROOT);
            case "capitalize" -> capitalizeWords(text);
            default -> text;
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
     *
     * 何が壊れていたか: 以前は line-height を「フォントサイズに対する倍率」に
     * 変換して返していたため、{@code line-height: 20px} が {@code 20/16} の倍率になり、
     * 使う側が {@code 16 * fontScale} を掛け戻すことで
     * 実際には {@code 20 * fontScale} px になっていた（font-size に比例してしまう）。
     * 長さ指定は長さのまま、倍率指定だけがフォントサイズに比例する。
     *
     * {@code normal} は書体の ascent + descent + 行間から決まる。
     * 以前は 1.375 という固定値だった。
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
