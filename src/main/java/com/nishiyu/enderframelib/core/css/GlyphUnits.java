package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import com.nishiyu.enderframelib.core.text.model.FontStyle;

import java.util.Locale;
import java.util.Map;

/**
 * 書体の指標から決める単位 {@code ch}（「0」の送り幅）と {@code ex}（x ハイト）。
 *
 * 字形を測る書体の解決（{@link FontResolver}）はスタイル計算より下の層が持つので、使う側（クライアントの起動時、
 * テスト）が {@link #install} で渡す。渡されていなければ CSS Values 4 §6.1.1 の代替（0.5em）になる。
 * 要素ごとに作るが、書体を引くのは {@code ch} / {@code ex} が実際に使われたときだけ。
 */
public final class GlyphUnits {

    private static volatile FontResolver resolver;

    private final Map<String, String> declared;
    private final Map<String, String> parent;
    private final float fontSizePx;
    private String fontFamily;
    private FontStyle style;
    private float ch = Float.NaN;
    private float ex = Float.NaN;

    private GlyphUnits(Map<String, String> declared, Map<String, String> parent, float fontSizePx) {
        this.declared = declared;
        this.parent = parent;
        this.fontSizePx = fontSizePx;
    }

    /** 測定に使う書体の解決を渡す。null で外す（代替の 0.5em に戻る）。 */
    public static void install(FontResolver fonts) {
        resolver = fonts;
    }

    /**
     * この要素の {@code ch} / {@code ex} の源。{@code style} に無い書体の指定は {@code parent}（継承）から取る。
     * 書体を解決する手段が渡されていなければ null。
     */
    static GlyphUnits of(Map<String, String> style, Map<String, String> parent, float fontSizePx) {
        return resolver == null ? null : new GlyphUnits(style, parent, fontSizePx);
    }

    /** 書体の指定を読む。要素ごとに作るので、{@code ch} / {@code ex} が使われるまで読まない。 */
    private FontStyle fontStyle() {
        if (style == null) {
            fontFamily = inherited(declared, parent, "font-family");
            String fontStyleValue = inherited(declared, parent, "font-style");
            boolean italic = fontStyleValue != null && !fontStyleValue.trim().equalsIgnoreCase("normal");
            int w = numericWeight(inherited(declared, parent, "font-weight"));
            style = new FontStyle(fontSizePx, w >= 600, italic, 0f, w, true);
        }
        return style;
    }

    private static String inherited(Map<String, String> style, Map<String, String> parent, String property) {
        String v = style.get(property);
        return v != null ? v : parent.get(property);
    }

    private static int numericWeight(String value) {
        if (value == null) return FontStyle.NORMAL_WEIGHT;
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) return FontStyle.NORMAL_WEIGHT;
        if (Character.isDigit(v.charAt(0))) {
            int n = 0;
            for (int i = 0; i < v.length() && Character.isDigit(v.charAt(i)); i++) n = n * 10 + (v.charAt(i) - '0');
            return Math.clamp(n, 1, 1000);
        }
        return v.startsWith("bold") ? FontStyle.BOLD_WEIGHT : FontStyle.NORMAL_WEIGHT;
    }

    private FontFace face() {
        FontResolver r = resolver;
        fontStyle();
        return r == null ? FontFace.FALLBACK : r.resolve(fontFamily);
    }

    /** 「0」の送り幅（px）。測れなければ 0.5em。 */
    float ch() {
        if (Float.isNaN(ch)) {
            float w = face().advance("0", fontStyle());
            ch = w > 0 ? w : fontSizePx * LengthResolver.GLYPH_FALLBACK_EM;
        }
        return ch;
    }

    /** x ハイト（px）。測れなければ 0.5em。 */
    float ex() {
        if (Float.isNaN(ex)) {
            float h = face().xHeight(fontStyle());
            ex = h > 0 ? h : fontSizePx * LengthResolver.GLYPH_FALLBACK_EM;
        }
        return ex;
    }
}
