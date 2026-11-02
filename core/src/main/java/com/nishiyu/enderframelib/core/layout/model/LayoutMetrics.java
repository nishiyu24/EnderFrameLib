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

    /**
     * font-weight が太字扱いになる値。書体は通常の 1 面だけなので、600 以上は太字を合成する
     * （CSS Fonts 4 §5.2）。何が壊れていたか: 以前は 600（semibold）を通常の太さで描いていた。
     */
    private static final Pattern BOLD_FONT_WEIGHT = Pattern.compile("(?i)bold|bolder");

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
        return numericWeight(weight) >= 600;
    }

    /**
     * {@code font-weight} の数値（100〜900）。{@code normal} は 400、{@code bold} は 700。
     * {@code bolder} / {@code lighter} は親の太さが要るが、ここでは親が通常の太さとして 700 / 100 にする。
     */
    public static int numericWeight(String weight) {
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

    public static int numericWeight(ComputedStyle style) {
        return numericWeight(style.getOrDefault(CssProperty.FONT_WEIGHT, ""));
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

    /**
     * 画面の実ピクセルとデバイスピクセル比から決まる CSS のビューポート。
     *
     * ブラウザと同じく、ビューポートの大きさ（CSS px）は「実ピクセル ÷ デバイスピクセル比」。
     * デバイスピクセル比は OS の拡大率 × ページのズームで、画面が大きいほど多くの CSS px が入る。
     * 以前は 16:9 の画面なら常に 854×480 に固定し、それを画面いっぱいに引き伸ばしていたので、
     * 大きな画面でも一度に表示できる量が増えず、1 CSS px が 2〜4 実ピクセルの粗い表示になっていた。
     *
     * @param devicePixelRatio 1 CSS px あたりの実ピクセル（{@code window.devicePixelRatio}、{@code @media (resolution)} の dppx）
     */
    public record DeviceViewport(int cssWidth, int cssHeight, float devicePixelRatio) {

        /** デバイスピクセル比の下限。0 や負の値（取得の失敗）から守る。 */
        public static final float MIN_DEVICE_PIXEL_RATIO = 0.25f;

        public static DeviceViewport of(int framebufferWidth, int framebufferHeight, float devicePixelRatio) {
            float dpr = Float.isFinite(devicePixelRatio) ? Math.max(MIN_DEVICE_PIXEL_RATIO, devicePixelRatio) : 1.0f;
            int w = Math.max(1, Math.round(framebufferWidth / dpr));
            int h = Math.max(1, Math.round(framebufferHeight / dpr));
            return new DeviceViewport(w, h, dpr);
        }

        /** OS の拡大率とズーム（%）からデバイスピクセル比を求める。 */
        public static float devicePixelRatio(float osContentScale, int zoomPercent) {
            float os = Float.isFinite(osContentScale) && osContentScale > 0 ? osContentScale : 1.0f;
            return Math.max(MIN_DEVICE_PIXEL_RATIO, os * Math.max(1, zoomPercent) / 100f);
        }
    }
}
