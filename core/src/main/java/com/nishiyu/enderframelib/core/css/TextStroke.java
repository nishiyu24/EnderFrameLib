package com.nishiyu.enderframelib.core.css;

import java.util.Locale;

/**
 * {@code -webkit-text-stroke-width} の値の解釈（L3）。描画層（L5）は文字列を読まず、この型が返す CSS px だけを受け取る
 * （計画 S7 ②: L5 に残る CSS 値文字列パースの L3 への集約）。
 */
public final class TextStroke {

    private TextStroke() {
    }

    /** {@code thin / medium / thick} は 1 / 3 / 5。値が無い・解釈できない・0 以下・非有限は 0。 */
    public static float widthPx(ComputedStyle style) {
        Style st = style.typed();
        return widthPx(st.inherited.webkitTextStrokeWidth, st.inherited.fontSize, st.inherited.rootFontSizePx);
    }

    /** 文字列から CSS px へ。{@code em} は要素、{@code rem} はルートのフォントサイズが基準。 */
    public static float widthPx(String value, float fontSizePx, float rootFontSizePx) {
        if (value == null) return 0f;
        String v = value.trim().toLowerCase(Locale.ROOT);
        try {
            float w = switch (v) {
                case "thin" -> 1f;
                case "medium" -> 3f;
                case "thick" -> 5f;
                default -> v.endsWith("rem") ? Float.parseFloat(v.substring(0, v.length() - 3)) * rootFontSizePx
                        : v.endsWith("em") ? Float.parseFloat(v.substring(0, v.length() - 2)) * fontSizePx
                        : v.endsWith("px") ? Float.parseFloat(v.substring(0, v.length() - 2))
                        : Float.parseFloat(v);
            };
            return w > 0f && Float.isFinite(w) ? w : 0f;
        } catch (NumberFormatException e) {
            return 0f;
        }
    }
}
