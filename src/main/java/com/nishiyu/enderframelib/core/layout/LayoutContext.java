package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.layout.model.*;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssKeywords;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import com.nishiyu.enderframelib.core.text.model.FontStyle;
import com.nishiyu.enderframelib.core.text.model.TextStyle;
import com.nishiyu.enderframelib.core.text.model.WhiteSpace;
import com.nishiyu.enderframelib.core.text.model.WordBreak;

/**
 * レイアウト 1 回分の外部依存。
 */
public record LayoutContext(
        Viewport viewport,
        IntrinsicSizeResolver intrinsicResolver,
        ScrollState scrollState,
        FontResolver fonts
) {

    /** 算出スタイルの {@code font-family} に対応する書体。 */
    public FontFace faceFor(ComputedStyle style) {
        return fonts.resolve(style.get(CssProperty.FONT_FAMILY));
    }

    /** 算出スタイルから測定に必要な指定だけを取り出す。 */
    public FontStyle fontStyleFor(ComputedStyle style) {
        return new FontStyle(
                style.fontSizePx(),
                LayoutMetrics.isBold(style),
                style.keywordOf(CssProperty.FONT_STYLE, CssKeywords.FontStyle.class) != CssKeywords.FontStyle.NORMAL,
                style.length(CssProperty.LETTER_SPACING, 0, 0, 0));
    }

    /** 整形（折り返し・空白・省略）に必要なスタイルをまとめる。 */
    public TextStyle textStyleFor(ComputedStyle style) {
        FontFace face = faceFor(style);
        FontStyle font = fontStyleFor(style);
        CssKeywords.OverflowWrap overflowWrap = style.keywordOf(CssProperty.OVERFLOW_WRAP, CssKeywords.OverflowWrap.class);
        return new TextStyle(
                font,
                WhiteSpace.of(style.get(CssProperty.WHITE_SPACE)),
                WordBreak.of(style.get(CssProperty.WORD_BREAK)),
                overflowWrap != CssKeywords.OverflowWrap.NORMAL,
                style.keywordOf(CssProperty.TEXT_OVERFLOW, CssKeywords.TextOverflow.class) == CssKeywords.TextOverflow.ELLIPSIS,
                LayoutMetrics.lineHeightPx(style, face, font));
    }
}
