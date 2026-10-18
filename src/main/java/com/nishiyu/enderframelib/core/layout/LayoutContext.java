package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.text.FontFace;
import com.nishiyu.enderframelib.core.text.FontResolver;
import com.nishiyu.enderframelib.core.text.FontStyle;
import com.nishiyu.enderframelib.core.text.TextShaper;

/**
 * レイアウト 1 回分の外部依存。
 *
 * 書体の引き当てをここに置いているのは、測定と整形が必ず同じ
 * {@link FontFace} を通るようにするため。
 * 以前は測定が常に既定フォントを使い、描画だけが {@code font-family} を見ていた。
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
                "italic".equals(style.keyword(CssProperty.FONT_STYLE, "normal")),
                style.length(CssProperty.LETTER_SPACING, 0, 0, 0));
    }

    /** 整形（折り返し・空白・省略）に必要なスタイルをまとめる。 */
    public TextShaper.TextStyle textStyleFor(ComputedStyle style) {
        FontFace face = faceFor(style);
        FontStyle font = fontStyleFor(style);
        String overflowWrap = style.keyword(CssProperty.OVERFLOW_WRAP, "normal");
        return new TextShaper.TextStyle(
                font,
                TextShaper.WhiteSpace.of(style.get(CssProperty.WHITE_SPACE)),
                TextShaper.WordBreak.of(style.get(CssProperty.WORD_BREAK)),
                overflowWrap.equals("break-word") || overflowWrap.equals("anywhere"),
                "ellipsis".equals(style.keyword(CssProperty.TEXT_OVERFLOW, "clip")),
                LayoutMetrics.lineHeightPx(style, face, font));
    }
}
