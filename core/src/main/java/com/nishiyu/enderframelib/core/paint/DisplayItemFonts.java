package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.css.ValueKeywords;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontStyle;

import java.util.Map;

/** 描画要素（L5）に載っている指定から {@link FontStyle}（L2）を作る。書体規則（L2）が DisplayItem を知らなくて済むよう、ここに置く。 */
public final class DisplayItemFonts {
    private DisplayItemFonts() {}

    /** 描画要素に載っている指定から、測定と同じ {@link FontStyle} を作る。 */
    public static FontStyle fontStyleFor(DisplayItem el, float fontSizePx) {
        Map<String, String> ev = el.events() != null ? el.events() : Map.of();
        int weight = ValueKeywords.numericFontWeight(ev.get("font-weight"));
        String style = ev.getOrDefault("font-style", "normal").trim();
        float letterSpacing = 0;
        try {
            letterSpacing = Float.parseFloat(ev.getOrDefault("letter-spacing", "0"));
        } catch (NumberFormatException ignored) {
        }
        boolean kerning = !"none".equalsIgnoreCase(ev.getOrDefault("font-kerning", "auto").trim());
        return new FontStyle(fontSizePx, weight >= 600, !style.equalsIgnoreCase("normal"), letterSpacing, weight, kerning);
    }
}
