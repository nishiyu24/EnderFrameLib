package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.text.FontMetrics;

import java.util.regex.Pattern;

/**
 * レイアウト全体で共有する定数と、文字列まわりの小さな共通処理。
 *
 * 以前はこれらが各エンジン・LayoutBox・レンダラにコピーされていて
 * （line-height の解析が 4 コピー、単語分割の正規表現が 4 コピー、
 *  太字判定が 4 コピー、基準フォントサイズ 16 が 11 箇所）、
 * 片方だけ直すと測定と配置がずれる状態だった。ここが唯一の出所。
 */
public final class LayoutMetrics {

    /** 基準フォントサイズ（px）。fontSize スケール 1.0 がこの値に対応する。 */
    public static final float BASE_FONT_SIZE = 16.0f;

    /** line-height の既定値（CSS の normal 相当として扱う）。 */
    public static final float DEFAULT_LINE_HEIGHT = 1.375f;

    /**
     * インラインの折り返し判定に与える余裕（px）。
     * 幅計算の丸めで最後の 1 単語が不必要に折り返されるのを防ぐためのもの。
     */
    public static final int INLINE_WRAP_SLOP = 4;

    /** 「幅の制約が無い」ことを表す十分大きな幅。 */
    public static final int UNBOUNDED_WIDTH = 9999;

    /** 置換要素のサイズが取得できなかったときの既定サイズ（px）。 */
    public static final int FALLBACK_REPLACED_SIZE = 16;
    /** リモート画像のサイズが未取得のときの暫定サイズ（px）。 */
    public static final int PENDING_IMAGE_SIZE = 32;

    /**
     * 単語分割の規則。英語は空白、日本語は句読点の直後で分割する。
     * 空白自体もトークンとして残すため、前後の空白が保たれる。
     */
    private static final Pattern WORD_SPLIT = Pattern.compile("(?<=\\s)|(?=\\s)|(?<=[、。！？.,])");

    /** font-weight が太字扱いになる値。 */
    private static final Pattern BOLD_FONT_WEIGHT = Pattern.compile("(?i)bold|bolder|700|800|900");

    /** 改行・タブを半角空白に置き換えるための規則。 */
    private static final Pattern LINE_BREAKS = Pattern.compile("[\\r\\n\\t]+");
    /** 連続した半角空白を 1 つに畳むための規則。 */
    private static final Pattern SPACE_RUN = Pattern.compile(" +");

    private LayoutMetrics() {}

    public static String[] splitWords(String text) {
        return WORD_SPLIT.split(text);
    }

    public static boolean isBold(ComputedStyle style) {
        return BOLD_FONT_WEIGHT.matcher(style.getOrDefault(CssProperty.FONT_WEIGHT, "")).matches();
    }

    /** 連続する空白を 1 つに畳む（white-space: normal の正規化）。 */
    public static String collapseWhitespace(String text) {
        if (text == null) return null;
        return SPACE_RUN.matcher(LINE_BREAKS.matcher(text).replaceAll(" ")).replaceAll(" ");
    }

    /**
     * line-height を「フォントサイズに対する倍率」として解決する。
     * 数値・px・% と normal に対応し、解釈できない値は {@link #DEFAULT_LINE_HEIGHT}。
     */
    public static float lineHeightFactor(ComputedStyle style) {
        String raw = style.get(CssProperty.LINE_HEIGHT);
        if (raw == null) return DEFAULT_LINE_HEIGHT;

        String value = raw.trim();
        if (value.isEmpty() || value.equalsIgnoreCase("normal")) return DEFAULT_LINE_HEIGHT;

        try {
            if (value.endsWith("px")) {
                return Float.parseFloat(value.substring(0, value.length() - 2).trim()) / BASE_FONT_SIZE;
            }
            if (value.endsWith("%")) {
                return Float.parseFloat(value.substring(0, value.length() - 1).trim()) / 100f;
            }
            if (value.endsWith("em")) {
                return Float.parseFloat(value.substring(0, value.length() - 2).trim());
            }
            return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            return DEFAULT_LINE_HEIGHT;
        }
    }

    /** フォントスケールに対応する 1 行分の高さ（px）。 */
    public static int lineHeightPx(float fontScale, float lineHeightFactor) {
        return (int) (BASE_FONT_SIZE * fontScale * lineHeightFactor);
    }

    /** フォントスケールに対応する文字の高さ（px）。 */
    public static int fontHeightPx(float fontScale) {
        return (int) (BASE_FONT_SIZE * fontScale);
    }

    /** 指定幅に収まるように単語単位で折り返す。単語内の強制分割はしない。 */
    public static java.util.List<String> wrapText(String text, int maxW, float fontScale, boolean bold,
                                                  FontMetrics fontMetrics) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (text == null || text.isEmpty()) return lines;

        int limit = maxW > 0 ? maxW : UNBOUNDED_WIDTH;
        StringBuilder current = new StringBuilder();
        int currentW = 0;

        for (String token : splitWords(text)) {
            int tokenW = fontMetrics.getDisplayWidth(token, fontScale, bold);
            if (currentW + tokenW > limit && !current.isEmpty() && !token.trim().isEmpty()) {
                lines.add(current.toString().trim());
                current = new StringBuilder();
                currentW = 0;
            }
            current.append(token);
            currentW += tokenW;
        }
        if (!current.isEmpty()) lines.add(current.toString().trim());
        return lines;
    }
}
