package com.nishiyu.enderframelib.core.css;

/**
 * 生成物（{@code scripts/GenStyleGroups.java}。手で直さない）: 子孫へ継承されるプロパティの型付き不変構造体。
 * 値は構築時に 1 回だけ解いた型付きのフィールドで、文字列を読み直さない。{@link #equals} は全フィールドの値等価。
 */
public final class InheritedStyle {
    static final int ID_visibility = ComputedStyle.idOf("visibility");
    static final int ID_borderCollapse = ComputedStyle.idOf("border-collapse");
    static final int ID_borderSpacing = ComputedStyle.idOf("border-spacing");
    static final int ID_captionSide = ComputedStyle.idOf("caption-side");
    static final int ID_emptyCells = ComputedStyle.idOf("empty-cells");
    static final int ID_listStyleType = ComputedStyle.idOf("list-style-type");
    static final int ID_listStylePosition = ComputedStyle.idOf("list-style-position");
    static final int ID_listStyleImage = ComputedStyle.idOf("list-style-image");
    static final int ID_fontFamily = ComputedStyle.idOf("font-family");
    static final int ID_fontSize = ComputedStyle.idOf("font-size");
    static final int ID_fontWeight = ComputedStyle.idOf("font-weight");
    static final int ID_fontStyle = ComputedStyle.idOf("font-style");
    static final int ID_fontVariant = ComputedStyle.idOf("font-variant");
    static final int ID_fontKerning = ComputedStyle.idOf("font-kerning");
    static final int ID_lineHeightValue = ComputedStyle.idOf("line-height");
    static final int ID_letterSpacing = ComputedStyle.idOf("letter-spacing");
    static final int ID_wordSpacing = ComputedStyle.idOf("word-spacing");
    static final int ID_textIndent = ComputedStyle.idOf("text-indent");
    static final int ID_textAlign = ComputedStyle.idOf("text-align");
    static final int ID_textAlignLast = ComputedStyle.idOf("text-align-last");
    static final int ID_textJustify = ComputedStyle.idOf("text-justify");
    static final int ID_tabSize = ComputedStyle.idOf("tab-size");
    static final int ID_textWrap = ComputedStyle.idOf("text-wrap");
    static final int ID_textWrapStyle = ComputedStyle.idOf("text-wrap-style");
    static final int ID_textTransform = ComputedStyle.idOf("text-transform");
    static final int ID_whiteSpace = ComputedStyle.idOf("white-space");
    static final int ID_wordBreak = ComputedStyle.idOf("word-break");
    static final int ID_overflowWrap = ComputedStyle.idOf("overflow-wrap");
    static final int ID_direction = ComputedStyle.idOf("direction");
    static final int ID_color = ComputedStyle.idOf("color");
    static final int ID_accentColor = ComputedStyle.idOf("accent-color");
    static final int ID_textDecoration = ComputedStyle.idOf("text-decoration");
    static final int ID_textShadow = ComputedStyle.idOf("text-shadow");
    static final int ID_webkitTextStrokeWidth = ComputedStyle.idOf("-webkit-text-stroke-width");
    static final int ID_webkitTextStrokeColor = ComputedStyle.idOf("-webkit-text-stroke-color");
    static final int ID_quotes = ComputedStyle.idOf("quotes");
    static final int ID_writingMode = ComputedStyle.idOf("writing-mode");
    static final int ID_textOrientation = ComputedStyle.idOf("text-orientation");
    static final int ID_cursor = ComputedStyle.idOf("cursor");
    static final int ID_pointerEvents = ComputedStyle.idOf("pointer-events");

    /** 宣言が 1 つも無いときの共有インスタンス（初期値だけ）。ID_* の初期化より後に置く。 */
    public static final InheritedStyle DEFAULT = new InheritedStyle(null);

    /** {@code calc()} 等の式の表。式を持たない値は共有の空表。 */
    public final CssExprs exprs;
    /** {@code visibility}。 */
    public final com.nishiyu.enderframelib.core.css.model.Visibility visibility;
    /** {@code border-collapse}。 */
    public final com.nishiyu.enderframelib.core.css.model.CssKeywords.BorderCollapse borderCollapse;
    /** {@code border-spacing}。 */
    public final String borderSpacing;
    /** {@code caption-side}。 */
    public final com.nishiyu.enderframelib.core.css.model.CssKeywords.CaptionSide captionSide;
    /** {@code empty-cells}。 */
    public final String emptyCells;
    /** {@code list-style-type}。 */
    public final String listStyleType;
    /** {@code list-style-position}。 */
    public final com.nishiyu.enderframelib.core.css.model.CssKeywords.ListStylePosition listStylePosition;
    /** {@code list-style-image}。 */
    public final String listStyleImage;
    /** {@code font-family}。 */
    public final String fontFamily;
    /** {@code font-size}。 */
    public final float fontSize;
    /** {@code font-weight}。 */
    public final int fontWeight;
    /** {@code font-style}。 */
    public final com.nishiyu.enderframelib.core.css.model.CssKeywords.FontStyle fontStyle;
    /** {@code font-variant}。 */
    public final String fontVariant;
    /** {@code font-kerning}。 */
    public final String fontKerning;
    /** {@code line-height} の種類（{@code ComputedStyle.LH_*}）。 */
    public final byte lineHeightKind;
    /** {@code line-height}。 */
    public final float lineHeightValue;
    /** {@code letter-spacing}。 */
    public final long letterSpacing;
    /** {@code word-spacing}。 */
    public final long wordSpacing;
    /** {@code text-indent}。 */
    public final long textIndent;
    /** {@code text-align}。 */
    public final com.nishiyu.enderframelib.core.css.model.TextAlign textAlign;
    /** {@code text-align-last}。 */
    public final String textAlignLast;
    /** {@code text-justify}。 */
    public final String textJustify;
    /** {@code tab-size}。 */
    public final String tabSize;
    /** {@code text-wrap}。 */
    public final String textWrap;
    /** {@code text-wrap-style}。 */
    public final String textWrapStyle;
    /** {@code text-transform}。 */
    public final com.nishiyu.enderframelib.core.css.model.CssKeywords.TextTransform textTransform;
    /** {@code white-space}。 */
    public final String whiteSpace;
    /** {@code word-break}。 */
    public final String wordBreak;
    /** {@code overflow-wrap}。 */
    public final com.nishiyu.enderframelib.core.css.model.CssKeywords.OverflowWrap overflowWrap;
    /** {@code direction}。 */
    public final String direction;
    /** {@code color}。 */
    public final int color;
    /** {@code accent-color}。 */
    public final String accentColor;
    /** {@code text-decoration}。 */
    public final String textDecoration;
    /** {@code text-shadow}。 */
    public final String textShadow;
    /** {@code -webkit-text-stroke-width}。 */
    public final String webkitTextStrokeWidth;
    /** {@code -webkit-text-stroke-color}。 */
    public final String webkitTextStrokeColor;
    /** {@code quotes}。 */
    public final String quotes;
    /** {@code writing-mode}。 */
    public final String writingMode;
    /** {@code text-orientation}。 */
    public final String textOrientation;
    /** {@code cursor}。 */
    public final String cursor;
    /** {@code pointer-events}。 */
    public final com.nishiyu.enderframelib.core.css.model.CssKeywords.PointerEvents pointerEvents;
    /** 文書のルートのフォントサイズ（px）。{@code rem} の基準で、ルートから子孫へ受け継がれる。 */
    public final float rootFontSizePx;
    /** {@code color} が明示宣言（または継承）されているか（{@code ::placeholder}/{@code ::first-line} 用）。 */
    public final boolean hasColor;
    /** 書体と字間の不変指定（継承群と同一寿命）。 */
    public final com.nishiyu.enderframelib.core.text.model.FontStyle fontStyleSpec;

    private InheritedStyle(StyleReader.Source cs) {
        CssExprs.Builder ex = new CssExprs.Builder();
        this.visibility = StyleReader.keyword(cs, ID_visibility, com.nishiyu.enderframelib.core.css.model.Visibility.class);
        this.borderCollapse = StyleReader.keyword(cs, ID_borderCollapse, com.nishiyu.enderframelib.core.css.model.CssKeywords.BorderCollapse.class);
        this.borderSpacing = StyleReader.text(cs, ID_borderSpacing);
        this.captionSide = StyleReader.keyword(cs, ID_captionSide, com.nishiyu.enderframelib.core.css.model.CssKeywords.CaptionSide.class);
        this.emptyCells = StyleReader.text(cs, ID_emptyCells);
        this.listStyleType = StyleReader.text(cs, ID_listStyleType);
        this.listStylePosition = StyleReader.keyword(cs, ID_listStylePosition, com.nishiyu.enderframelib.core.css.model.CssKeywords.ListStylePosition.class);
        this.listStyleImage = StyleReader.text(cs, ID_listStyleImage);
        this.fontFamily = StyleReader.text(cs, ID_fontFamily);
        this.fontSize = cs != null ? cs.fontSizePx() : ComputedStyle.DEFAULT_FONT_SIZE;
        this.fontWeight = StyleReader.fontWeight(cs, ID_fontWeight);
        this.fontStyle = StyleReader.keyword(cs, ID_fontStyle, com.nishiyu.enderframelib.core.css.model.CssKeywords.FontStyle.class);
        this.fontVariant = StyleReader.text(cs, ID_fontVariant);
        this.fontKerning = StyleReader.text(cs, ID_fontKerning);
        long lh = StyleReader.lineHeightBits(cs, ID_lineHeightValue);
        this.lineHeightKind = (byte) (lh >>> 32);
        this.lineHeightValue = Float.intBitsToFloat((int) lh);
        this.letterSpacing = StyleReader.length(cs, ID_letterSpacing, ex);
        this.wordSpacing = StyleReader.length(cs, ID_wordSpacing, ex);
        this.textIndent = StyleReader.length(cs, ID_textIndent, ex);
        this.textAlign = StyleReader.keyword(cs, ID_textAlign, com.nishiyu.enderframelib.core.css.model.TextAlign.class);
        this.textAlignLast = StyleReader.text(cs, ID_textAlignLast);
        this.textJustify = StyleReader.text(cs, ID_textJustify);
        this.tabSize = StyleReader.text(cs, ID_tabSize);
        this.textWrap = StyleReader.text(cs, ID_textWrap);
        this.textWrapStyle = StyleReader.text(cs, ID_textWrapStyle);
        this.textTransform = StyleReader.keyword(cs, ID_textTransform, com.nishiyu.enderframelib.core.css.model.CssKeywords.TextTransform.class);
        this.whiteSpace = StyleReader.text(cs, ID_whiteSpace);
        this.wordBreak = StyleReader.text(cs, ID_wordBreak);
        this.overflowWrap = StyleReader.keyword(cs, ID_overflowWrap, com.nishiyu.enderframelib.core.css.model.CssKeywords.OverflowWrap.class);
        this.direction = StyleReader.text(cs, ID_direction);
        this.color = StyleReader.color(cs, ID_color);
        this.accentColor = StyleReader.text(cs, ID_accentColor);
        this.textDecoration = StyleReader.text(cs, ID_textDecoration);
        this.textShadow = StyleReader.text(cs, ID_textShadow);
        this.webkitTextStrokeWidth = StyleReader.text(cs, ID_webkitTextStrokeWidth);
        this.webkitTextStrokeColor = StyleReader.text(cs, ID_webkitTextStrokeColor);
        this.quotes = StyleReader.text(cs, ID_quotes);
        this.writingMode = StyleReader.text(cs, ID_writingMode);
        this.textOrientation = StyleReader.text(cs, ID_textOrientation);
        this.cursor = StyleReader.text(cs, ID_cursor);
        this.pointerEvents = StyleReader.keyword(cs, ID_pointerEvents, com.nishiyu.enderframelib.core.css.model.CssKeywords.PointerEvents.class);
        this.rootFontSizePx = cs != null ? cs.rootFontSizePx() : ComputedStyle.DEFAULT_FONT_SIZE;
        this.hasColor = StyleReader.isDeclared(cs, ID_color);
        this.exprs = ex.build();
        this.fontStyleSpec = new com.nishiyu.enderframelib.core.text.model.FontStyle(this.fontSize, this.fontWeight >= 600,
                this.fontStyle != com.nishiyu.enderframelib.core.css.model.CssKeywords.FontStyle.NORMAL,
                CssLength.resolve(this.letterSpacing, this.exprs, 0, this.fontSize, this.rootFontSizePx, 0, 0), this.fontWeight,
                !com.nishiyu.enderframelib.core.kernel.style.KeywordMatch.is(this.fontKerning, "none"));
    }

    /** 宣言入力から、この群の値を取り出す。 */
    public static InheritedStyle of(StyleReader.Source cs) {
        return new InheritedStyle(cs);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof InheritedStyle o)) return false;
        return exprs.equals(o.exprs)
                && visibility == o.visibility
                && borderCollapse == o.borderCollapse
                && java.util.Objects.equals(borderSpacing, o.borderSpacing)
                && captionSide == o.captionSide
                && java.util.Objects.equals(emptyCells, o.emptyCells)
                && java.util.Objects.equals(listStyleType, o.listStyleType)
                && listStylePosition == o.listStylePosition
                && java.util.Objects.equals(listStyleImage, o.listStyleImage)
                && java.util.Objects.equals(fontFamily, o.fontFamily)
                && Float.floatToIntBits(fontSize) == Float.floatToIntBits(o.fontSize)
                && fontWeight == o.fontWeight
                && fontStyle == o.fontStyle
                && java.util.Objects.equals(fontVariant, o.fontVariant)
                && java.util.Objects.equals(fontKerning, o.fontKerning)
                && Float.floatToIntBits(lineHeightValue) == Float.floatToIntBits(o.lineHeightValue)
                && lineHeightKind == o.lineHeightKind
                && letterSpacing == o.letterSpacing
                && wordSpacing == o.wordSpacing
                && textIndent == o.textIndent
                && textAlign == o.textAlign
                && java.util.Objects.equals(textAlignLast, o.textAlignLast)
                && java.util.Objects.equals(textJustify, o.textJustify)
                && java.util.Objects.equals(tabSize, o.tabSize)
                && java.util.Objects.equals(textWrap, o.textWrap)
                && java.util.Objects.equals(textWrapStyle, o.textWrapStyle)
                && textTransform == o.textTransform
                && java.util.Objects.equals(whiteSpace, o.whiteSpace)
                && java.util.Objects.equals(wordBreak, o.wordBreak)
                && overflowWrap == o.overflowWrap
                && java.util.Objects.equals(direction, o.direction)
                && color == o.color
                && java.util.Objects.equals(accentColor, o.accentColor)
                && java.util.Objects.equals(textDecoration, o.textDecoration)
                && java.util.Objects.equals(textShadow, o.textShadow)
                && java.util.Objects.equals(webkitTextStrokeWidth, o.webkitTextStrokeWidth)
                && java.util.Objects.equals(webkitTextStrokeColor, o.webkitTextStrokeColor)
                && java.util.Objects.equals(quotes, o.quotes)
                && java.util.Objects.equals(writingMode, o.writingMode)
                && java.util.Objects.equals(textOrientation, o.textOrientation)
                && java.util.Objects.equals(cursor, o.cursor)
                && pointerEvents == o.pointerEvents
                && Float.floatToIntBits(rootFontSizePx) == Float.floatToIntBits(o.rootFontSizePx)
                && hasColor == o.hasColor;
    }

    @Override
    public int hashCode() {
        int h = 40;
        h = h * 31 + System.identityHashCode(visibility);
        h = h * 31 + System.identityHashCode(borderCollapse);
        h = h * 31 + java.util.Objects.hashCode(borderSpacing);
        h = h * 31 + System.identityHashCode(captionSide);
        h = h * 31 + java.util.Objects.hashCode(emptyCells);
        h = h * 31 + java.util.Objects.hashCode(listStyleType);
        h = h * 31 + System.identityHashCode(listStylePosition);
        h = h * 31 + java.util.Objects.hashCode(listStyleImage);
        h = h * 31 + java.util.Objects.hashCode(fontFamily);
        h = h * 31 + Float.floatToIntBits(fontSize);
        h = h * 31 + fontWeight;
        h = h * 31 + System.identityHashCode(fontStyle);
        h = h * 31 + java.util.Objects.hashCode(fontVariant);
        h = h * 31 + java.util.Objects.hashCode(fontKerning);
        h = h * 31 + Float.floatToIntBits(lineHeightValue);
        h = h * 31 + Long.hashCode(letterSpacing);
        h = h * 31 + Long.hashCode(wordSpacing);
        h = h * 31 + Long.hashCode(textIndent);
        h = h * 31 + System.identityHashCode(textAlign);
        h = h * 31 + java.util.Objects.hashCode(textAlignLast);
        h = h * 31 + java.util.Objects.hashCode(textJustify);
        h = h * 31 + java.util.Objects.hashCode(tabSize);
        h = h * 31 + java.util.Objects.hashCode(textWrap);
        h = h * 31 + java.util.Objects.hashCode(textWrapStyle);
        h = h * 31 + System.identityHashCode(textTransform);
        h = h * 31 + java.util.Objects.hashCode(whiteSpace);
        h = h * 31 + java.util.Objects.hashCode(wordBreak);
        h = h * 31 + System.identityHashCode(overflowWrap);
        h = h * 31 + java.util.Objects.hashCode(direction);
        h = h * 31 + color;
        h = h * 31 + java.util.Objects.hashCode(accentColor);
        h = h * 31 + java.util.Objects.hashCode(textDecoration);
        h = h * 31 + java.util.Objects.hashCode(textShadow);
        h = h * 31 + java.util.Objects.hashCode(webkitTextStrokeWidth);
        h = h * 31 + java.util.Objects.hashCode(webkitTextStrokeColor);
        h = h * 31 + java.util.Objects.hashCode(quotes);
        h = h * 31 + java.util.Objects.hashCode(writingMode);
        h = h * 31 + java.util.Objects.hashCode(textOrientation);
        h = h * 31 + java.util.Objects.hashCode(cursor);
        h = h * 31 + System.identityHashCode(pointerEvents);
        return h;
    }
}
