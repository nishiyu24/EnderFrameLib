package com.nishiyu.enderframelib.core.css;

/**
 * 生成物（{@code scripts/GenStyleGroups.java}。手で直さない）: 継承されないプロパティの型付き不変構造体。
 * 値は構築時に 1 回だけ解いた型付きのフィールドで、文字列を読み直さない。{@link #equals} は全フィールドの値等価。
 */
public final class BoxGeomStyle {
    static final int ID_display = ComputedStyle.idOf("display");
    static final int ID_position = ComputedStyle.idOf("position");
    static final int ID_top = ComputedStyle.idOf("top");
    static final int ID_right = ComputedStyle.idOf("right");
    static final int ID_bottom = ComputedStyle.idOf("bottom");
    static final int ID_left = ComputedStyle.idOf("left");
    static final int ID_floatValue = ComputedStyle.idOf("float");
    static final int ID_clear = ComputedStyle.idOf("clear");
    static final int ID_width = ComputedStyle.idOf("width");
    static final int ID_height = ComputedStyle.idOf("height");
    static final int ID_minWidth = ComputedStyle.idOf("min-width");
    static final int ID_minHeight = ComputedStyle.idOf("min-height");
    static final int ID_maxWidth = ComputedStyle.idOf("max-width");
    static final int ID_maxHeight = ComputedStyle.idOf("max-height");
    static final int ID_boxSizing = ComputedStyle.idOf("box-sizing");
    static final int ID_aspectRatio = ComputedStyle.idOf("aspect-ratio");
    static final int ID_marginTop = ComputedStyle.idOf("margin-top");
    static final int ID_marginRight = ComputedStyle.idOf("margin-right");
    static final int ID_marginBottom = ComputedStyle.idOf("margin-bottom");
    static final int ID_marginLeft = ComputedStyle.idOf("margin-left");
    static final int ID_paddingTop = ComputedStyle.idOf("padding-top");
    static final int ID_paddingRight = ComputedStyle.idOf("padding-right");
    static final int ID_paddingBottom = ComputedStyle.idOf("padding-bottom");
    static final int ID_paddingLeft = ComputedStyle.idOf("padding-left");
    static final int ID_borderTopWidth = ComputedStyle.idOf("border-top-width");
    static final int ID_borderTopStyle = ComputedStyle.idOf("border-top-style");
    static final int ID_borderRightWidth = ComputedStyle.idOf("border-right-width");
    static final int ID_borderRightStyle = ComputedStyle.idOf("border-right-style");
    static final int ID_borderBottomWidth = ComputedStyle.idOf("border-bottom-width");
    static final int ID_borderBottomStyle = ComputedStyle.idOf("border-bottom-style");
    static final int ID_borderLeftWidth = ComputedStyle.idOf("border-left-width");
    static final int ID_borderLeftStyle = ComputedStyle.idOf("border-left-style");
    static final int ID_overflowX = ComputedStyle.idOf("overflow-x");
    static final int ID_overflowY = ComputedStyle.idOf("overflow-y");
    static final int ID_scrollbarWidth = ComputedStyle.idOf("scrollbar-width");
    static final int ID_verticalAlign = ComputedStyle.idOf("vertical-align");
    static final int ID_textOverflow = ComputedStyle.idOf("text-overflow");

    /** 宣言が 1 つも無いときの共有インスタンス（初期値だけ）。ID_* の初期化より後に置く。 */
    public static final BoxGeomStyle DEFAULT = new BoxGeomStyle(null);

    /** {@code calc()} 等の式の表。式を持たない値は共有の空表。 */
    public final CssExprs exprs;
    /** {@code display}。 */
    public final String display;
    /** {@code position}。 */
    public final com.nishiyu.enderframelib.core.css.model.Position position;
    /** {@code top}。 */
    public final long top;
    /** {@code right}。 */
    public final long right;
    /** {@code bottom}。 */
    public final long bottom;
    /** {@code left}。 */
    public final long left;
    /** {@code float}。 */
    public final com.nishiyu.enderframelib.core.css.model.CssKeywords.FloatSide floatValue;
    /** {@code clear}。 */
    public final com.nishiyu.enderframelib.core.css.model.CssKeywords.Clear clear;
    /** {@code width}。 */
    public final long width;
    /** {@code height}。 */
    public final long height;
    /** {@code min-width}。 */
    public final long minWidth;
    /** {@code min-height}。 */
    public final long minHeight;
    /** {@code max-width}。 */
    public final long maxWidth;
    /** {@code max-height}。 */
    public final long maxHeight;
    /** {@code box-sizing}。 */
    public final com.nishiyu.enderframelib.core.css.model.CssKeywords.BoxSizing boxSizing;
    /** {@code aspect-ratio}。 */
    public final String aspectRatio;
    /** {@code margin-top}。 */
    public final long marginTop;
    /** {@code margin-right}。 */
    public final long marginRight;
    /** {@code margin-bottom}。 */
    public final long marginBottom;
    /** {@code margin-left}。 */
    public final long marginLeft;
    /** {@code padding-top}。 */
    public final long paddingTop;
    /** {@code padding-right}。 */
    public final long paddingRight;
    /** {@code padding-bottom}。 */
    public final long paddingBottom;
    /** {@code padding-left}。 */
    public final long paddingLeft;
    /** {@code border-top-width}。 */
    public final long borderTopWidth;
    /** {@code border-top-style}。 */
    public final com.nishiyu.enderframelib.core.css.model.BorderStyle borderTopStyle;
    /** {@code border-right-width}。 */
    public final long borderRightWidth;
    /** {@code border-right-style}。 */
    public final com.nishiyu.enderframelib.core.css.model.BorderStyle borderRightStyle;
    /** {@code border-bottom-width}。 */
    public final long borderBottomWidth;
    /** {@code border-bottom-style}。 */
    public final com.nishiyu.enderframelib.core.css.model.BorderStyle borderBottomStyle;
    /** {@code border-left-width}。 */
    public final long borderLeftWidth;
    /** {@code border-left-style}。 */
    public final com.nishiyu.enderframelib.core.css.model.BorderStyle borderLeftStyle;
    /** {@code overflow-x}。 */
    public final com.nishiyu.enderframelib.core.css.model.Overflow overflowX;
    /** {@code overflow-y}。 */
    public final com.nishiyu.enderframelib.core.css.model.Overflow overflowY;
    /** {@code scrollbar-width}。 */
    public final com.nishiyu.enderframelib.core.css.model.CssKeywords.ScrollbarWidth scrollbarWidth;
    /** {@code vertical-align}。 */
    public final long verticalAlign;
    /** {@code text-overflow}。 */
    public final com.nishiyu.enderframelib.core.css.model.CssKeywords.TextOverflow textOverflow;

    private BoxGeomStyle(StyleReader.Source cs) {
        CssExprs.Builder ex = new CssExprs.Builder();
        this.display = StyleReader.text(cs, ID_display);
        this.position = StyleReader.keyword(cs, ID_position, com.nishiyu.enderframelib.core.css.model.Position.class);
        this.top = StyleReader.length(cs, ID_top, ex);
        this.right = StyleReader.length(cs, ID_right, ex);
        this.bottom = StyleReader.length(cs, ID_bottom, ex);
        this.left = StyleReader.length(cs, ID_left, ex);
        this.floatValue = StyleReader.keyword(cs, ID_floatValue, com.nishiyu.enderframelib.core.css.model.CssKeywords.FloatSide.class);
        this.clear = StyleReader.keyword(cs, ID_clear, com.nishiyu.enderframelib.core.css.model.CssKeywords.Clear.class);
        this.width = StyleReader.length(cs, ID_width, ex);
        this.height = StyleReader.length(cs, ID_height, ex);
        this.minWidth = StyleReader.length(cs, ID_minWidth, ex);
        this.minHeight = StyleReader.length(cs, ID_minHeight, ex);
        this.maxWidth = StyleReader.length(cs, ID_maxWidth, ex);
        this.maxHeight = StyleReader.length(cs, ID_maxHeight, ex);
        this.boxSizing = StyleReader.keyword(cs, ID_boxSizing, com.nishiyu.enderframelib.core.css.model.CssKeywords.BoxSizing.class);
        this.aspectRatio = StyleReader.text(cs, ID_aspectRatio);
        this.marginTop = StyleReader.length(cs, ID_marginTop, ex);
        this.marginRight = StyleReader.length(cs, ID_marginRight, ex);
        this.marginBottom = StyleReader.length(cs, ID_marginBottom, ex);
        this.marginLeft = StyleReader.length(cs, ID_marginLeft, ex);
        this.paddingTop = StyleReader.length(cs, ID_paddingTop, ex);
        this.paddingRight = StyleReader.length(cs, ID_paddingRight, ex);
        this.paddingBottom = StyleReader.length(cs, ID_paddingBottom, ex);
        this.paddingLeft = StyleReader.length(cs, ID_paddingLeft, ex);
        this.borderTopWidth = StyleReader.length(cs, ID_borderTopWidth, ex);
        this.borderTopStyle = StyleReader.keyword(cs, ID_borderTopStyle, com.nishiyu.enderframelib.core.css.model.BorderStyle.class);
        this.borderRightWidth = StyleReader.length(cs, ID_borderRightWidth, ex);
        this.borderRightStyle = StyleReader.keyword(cs, ID_borderRightStyle, com.nishiyu.enderframelib.core.css.model.BorderStyle.class);
        this.borderBottomWidth = StyleReader.length(cs, ID_borderBottomWidth, ex);
        this.borderBottomStyle = StyleReader.keyword(cs, ID_borderBottomStyle, com.nishiyu.enderframelib.core.css.model.BorderStyle.class);
        this.borderLeftWidth = StyleReader.length(cs, ID_borderLeftWidth, ex);
        this.borderLeftStyle = StyleReader.keyword(cs, ID_borderLeftStyle, com.nishiyu.enderframelib.core.css.model.BorderStyle.class);
        this.overflowX = StyleReader.keyword(cs, ID_overflowX, com.nishiyu.enderframelib.core.css.model.Overflow.class);
        this.overflowY = StyleReader.keyword(cs, ID_overflowY, com.nishiyu.enderframelib.core.css.model.Overflow.class);
        this.scrollbarWidth = StyleReader.keyword(cs, ID_scrollbarWidth, com.nishiyu.enderframelib.core.css.model.CssKeywords.ScrollbarWidth.class);
        this.verticalAlign = StyleReader.length(cs, ID_verticalAlign, ex);
        this.textOverflow = StyleReader.keyword(cs, ID_textOverflow, com.nishiyu.enderframelib.core.css.model.CssKeywords.TextOverflow.class);
        this.exprs = ex.build();
    }

    /** {@code left/right} (bit 0)・{@code top/bottom} (bit 1) だけが違うならそのビット和、それ以外の差は -1。 */
    static int positionOnlyAxes(BoxGeomStyle a, BoxGeomStyle b) { return 0; }
    /** 宣言入力から、この群の値を取り出す。 */
    public static BoxGeomStyle of(StyleReader.Source cs) {
        return new BoxGeomStyle(cs);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof BoxGeomStyle o)) return false;
        return exprs.equals(o.exprs)
                && java.util.Objects.equals(display, o.display)
                && position == o.position
                && top == o.top
                && right == o.right
                && bottom == o.bottom
                && left == o.left
                && floatValue == o.floatValue
                && clear == o.clear
                && width == o.width
                && height == o.height
                && minWidth == o.minWidth
                && minHeight == o.minHeight
                && maxWidth == o.maxWidth
                && maxHeight == o.maxHeight
                && boxSizing == o.boxSizing
                && java.util.Objects.equals(aspectRatio, o.aspectRatio)
                && marginTop == o.marginTop
                && marginRight == o.marginRight
                && marginBottom == o.marginBottom
                && marginLeft == o.marginLeft
                && paddingTop == o.paddingTop
                && paddingRight == o.paddingRight
                && paddingBottom == o.paddingBottom
                && paddingLeft == o.paddingLeft
                && borderTopWidth == o.borderTopWidth
                && borderTopStyle == o.borderTopStyle
                && borderRightWidth == o.borderRightWidth
                && borderRightStyle == o.borderRightStyle
                && borderBottomWidth == o.borderBottomWidth
                && borderBottomStyle == o.borderBottomStyle
                && borderLeftWidth == o.borderLeftWidth
                && borderLeftStyle == o.borderLeftStyle
                && overflowX == o.overflowX
                && overflowY == o.overflowY
                && scrollbarWidth == o.scrollbarWidth
                && verticalAlign == o.verticalAlign
                && textOverflow == o.textOverflow;
    }

    @Override
    public int hashCode() {
        int h = 37;
        h = h * 31 + java.util.Objects.hashCode(display);
        h = h * 31 + System.identityHashCode(position);
        h = h * 31 + Long.hashCode(top);
        h = h * 31 + Long.hashCode(right);
        h = h * 31 + Long.hashCode(bottom);
        h = h * 31 + Long.hashCode(left);
        h = h * 31 + System.identityHashCode(floatValue);
        h = h * 31 + System.identityHashCode(clear);
        h = h * 31 + Long.hashCode(width);
        h = h * 31 + Long.hashCode(height);
        h = h * 31 + Long.hashCode(minWidth);
        h = h * 31 + Long.hashCode(minHeight);
        h = h * 31 + Long.hashCode(maxWidth);
        h = h * 31 + Long.hashCode(maxHeight);
        h = h * 31 + System.identityHashCode(boxSizing);
        h = h * 31 + java.util.Objects.hashCode(aspectRatio);
        h = h * 31 + Long.hashCode(marginTop);
        h = h * 31 + Long.hashCode(marginRight);
        h = h * 31 + Long.hashCode(marginBottom);
        h = h * 31 + Long.hashCode(marginLeft);
        h = h * 31 + Long.hashCode(paddingTop);
        h = h * 31 + Long.hashCode(paddingRight);
        h = h * 31 + Long.hashCode(paddingBottom);
        h = h * 31 + Long.hashCode(paddingLeft);
        h = h * 31 + Long.hashCode(borderTopWidth);
        h = h * 31 + System.identityHashCode(borderTopStyle);
        h = h * 31 + Long.hashCode(borderRightWidth);
        h = h * 31 + System.identityHashCode(borderRightStyle);
        h = h * 31 + Long.hashCode(borderBottomWidth);
        h = h * 31 + System.identityHashCode(borderBottomStyle);
        h = h * 31 + Long.hashCode(borderLeftWidth);
        h = h * 31 + System.identityHashCode(borderLeftStyle);
        h = h * 31 + System.identityHashCode(overflowX);
        h = h * 31 + System.identityHashCode(overflowY);
        h = h * 31 + System.identityHashCode(scrollbarWidth);
        h = h * 31 + Long.hashCode(verticalAlign);
        h = h * 31 + System.identityHashCode(textOverflow);
        return h;
    }
}
