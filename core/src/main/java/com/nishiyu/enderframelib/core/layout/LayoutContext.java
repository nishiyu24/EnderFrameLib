package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.CssPropertyIndex;
import com.nishiyu.enderframelib.core.css.StyleFacts;
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
 *
 * @param reuseMeasurements 前回の measure の結果を、入力と中身が変わっていない箱で使い回してよいか
 *                          （差分レイアウト。{@link LayoutBox#measure(ConstraintSpace, LayoutContext)} を参照）
 * @param layoutRun         このレイアウト（measure の全パスと layout）の通し番号
 * @param firstPass         measure の 1 回目のパスか。2 回目以降は、同じレイアウトの中で記録した結果を使い回さない
 *                          （表の枠線の分担のように、1 回目の結果を読んで 2 回目が変わる箱があるため）
 */
public record LayoutContext(
        Viewport viewport,
        IntrinsicSizeResolver intrinsicResolver,
        ScrollState scrollState,
        FontResolver fonts,
        boolean reuseMeasurements,
        long layoutRun,
        boolean firstPass
) {

    /** measure の結果を使い回さない（何もかも測り直す）文脈。 */
    public LayoutContext(Viewport viewport, IntrinsicSizeResolver intrinsicResolver,
                         ScrollState scrollState, FontResolver fonts) {
        this(viewport, intrinsicResolver, scrollState, fonts, false, 0L, true);
    }

    // テキストの測定ごとに引くプロパティは、名前ではなく添字で引く
    private static final int ID_FONT_FAMILY = CssPropertyIndex.FONT_FAMILY;
    private static final int ID_FONT_STYLE = CssPropertyIndex.FONT_STYLE;
    private static final int ID_LETTER_SPACING = CssPropertyIndex.LETTER_SPACING;
    private static final int ID_FONT_KERNING = CssPropertyIndex.FONT_KERNING;
    private static final int ID_OVERFLOW_WRAP = CssPropertyIndex.OVERFLOW_WRAP;
    private static final int ID_WHITE_SPACE = CssPropertyIndex.WHITE_SPACE;
    private static final int ID_WORD_BREAK = CssPropertyIndex.WORD_BREAK;
    private static final int ID_TEXT_OVERFLOW = CssPropertyIndex.TEXT_OVERFLOW;

    /** 算出スタイルの {@code font-family} に対応する書体。 */
    public FontFace faceFor(ComputedStyle style) {
        return fonts.resolve(style.get(ID_FONT_FAMILY));
    }

    /** 算出スタイルから測定に必要な指定だけを取り出す。 */
    public FontStyle fontStyleFor(ComputedStyle style) {
        return style.layoutFacts().fontStyle;
    }

    /** 宣言が {@code none}（前後の空白・大小文字は無視）か。宣言が無ければ偽（既定は auto）。 */
    private static boolean isNone(String value) {
        return value != null && "none".equalsIgnoreCase(value.trim());
    }

    /** 整形（折り返し・空白・省略）に必要なスタイルをまとめる。 */
    public TextStyle textStyleFor(ComputedStyle style) {
        FontFace face = faceFor(style);
        TextStyle cached = style.peekTextStyle(face);
        if (cached != null) return cached;
        StyleFacts facts = style.layoutFacts();
        FontStyle font = facts.fontStyle;
        TextStyle built = new TextStyle(font, facts.whiteSpace, facts.wordBreak, facts.overflowWrapBreaks, facts.ellipsis,
                LayoutMetrics.lineHeightPx(style, face, font));
        style.cacheTextStyle(face, built);
        return built;
    }
}
