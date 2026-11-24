package com.nishiyu.enderframelib.core.css;

/**
 * 生成物（{@code scripts/GenStyleGroups.java}。手で直さない）: 継承されないプロパティの型付き不変構造体。
 * 値は構築時に 1 回だけ解いた型付きのフィールドで、文字列を読み直さない。{@link #equals} は全フィールドの値等価。
 */
public final class FlexGridStyle {
    private static final int ID_flexDirection = ComputedStyle.idOf("flex-direction");
    private static final int ID_flexWrap = ComputedStyle.idOf("flex-wrap");
    private static final int ID_flexGrow = ComputedStyle.idOf("flex-grow");
    private static final int ID_flexShrink = ComputedStyle.idOf("flex-shrink");
    private static final int ID_flexBasis = ComputedStyle.idOf("flex-basis");
    private static final int ID_order = ComputedStyle.idOf("order");
    private static final int ID_justifyContent = ComputedStyle.idOf("justify-content");
    private static final int ID_alignContent = ComputedStyle.idOf("align-content");
    private static final int ID_justifyItems = ComputedStyle.idOf("justify-items");
    private static final int ID_alignItems = ComputedStyle.idOf("align-items");
    private static final int ID_justifySelf = ComputedStyle.idOf("justify-self");
    private static final int ID_alignSelf = ComputedStyle.idOf("align-self");
    private static final int ID_rowGap = ComputedStyle.idOf("row-gap");
    private static final int ID_gridTemplateColumns = ComputedStyle.idOf("grid-template-columns");
    private static final int ID_gridTemplateRows = ComputedStyle.idOf("grid-template-rows");
    private static final int ID_gridTemplateAreas = ComputedStyle.idOf("grid-template-areas");
    private static final int ID_gridAutoColumns = ComputedStyle.idOf("grid-auto-columns");
    private static final int ID_gridAutoRows = ComputedStyle.idOf("grid-auto-rows");
    private static final int ID_gridAutoFlow = ComputedStyle.idOf("grid-auto-flow");
    private static final int ID_gridColumnStart = ComputedStyle.idOf("grid-column-start");
    private static final int ID_gridColumnEnd = ComputedStyle.idOf("grid-column-end");
    private static final int ID_gridRowStart = ComputedStyle.idOf("grid-row-start");
    private static final int ID_gridRowEnd = ComputedStyle.idOf("grid-row-end");
    private static final int ID_gridColumn = ComputedStyle.idOf("grid-column");
    private static final int ID_gridRow = ComputedStyle.idOf("grid-row");
    private static final int ID_gridArea = ComputedStyle.idOf("grid-area");

    /** 宣言が 1 つも無いときの共有インスタンス（初期値だけ）。ID_* の初期化より後に置く。 */
    public static final FlexGridStyle DEFAULT = new FlexGridStyle(null);

    /** {@code calc()} 等の式の表。式を持たない値は共有の空表。 */
    public final CssExprs exprs;
    /** {@code flex-direction}。 */
    public final com.nishiyu.enderframelib.core.css.model.CssKeywords.FlexDirection flexDirection;
    /** {@code flex-wrap}。 */
    public final com.nishiyu.enderframelib.core.css.model.CssKeywords.FlexWrap flexWrap;
    /** {@code flex-grow}。 */
    public final float flexGrow;
    /** {@code flex-shrink}。 */
    public final float flexShrink;
    /** {@code flex-basis}。 */
    public final long flexBasis;
    /** {@code order}。 */
    public final float order;
    /** {@code justify-content}。 */
    public final com.nishiyu.enderframelib.core.css.model.CssKeywords.Alignment justifyContent;
    /** {@code align-content}。 */
    public final com.nishiyu.enderframelib.core.css.model.CssKeywords.Alignment alignContent;
    /** {@code justify-items}。 */
    public final com.nishiyu.enderframelib.core.css.model.CssKeywords.Alignment justifyItems;
    /** {@code align-items}。 */
    public final com.nishiyu.enderframelib.core.css.model.CssKeywords.Alignment alignItems;
    /** {@code justify-self}。 */
    public final com.nishiyu.enderframelib.core.css.model.CssKeywords.Alignment justifySelf;
    /** {@code align-self}。 */
    public final com.nishiyu.enderframelib.core.css.model.CssKeywords.Alignment alignSelf;
    /** {@code row-gap}。 */
    public final long rowGap;
    /** {@code grid-template-columns}。 */
    public final String gridTemplateColumns;
    /** {@code grid-template-rows}。 */
    public final String gridTemplateRows;
    /** {@code grid-template-areas}。 */
    public final String gridTemplateAreas;
    /** {@code grid-auto-columns}。 */
    public final String gridAutoColumns;
    /** {@code grid-auto-rows}。 */
    public final String gridAutoRows;
    /** {@code grid-auto-flow}。 */
    public final String gridAutoFlow;
    /** {@code grid-column-start}。 */
    public final String gridColumnStart;
    /** {@code grid-column-end}。 */
    public final String gridColumnEnd;
    /** {@code grid-row-start}。 */
    public final String gridRowStart;
    /** {@code grid-row-end}。 */
    public final String gridRowEnd;
    /** {@code grid-column}。 */
    public final String gridColumn;
    /** {@code grid-row}。 */
    public final String gridRow;
    /** {@code grid-area}。 */
    public final String gridArea;

    private FlexGridStyle(ComputedStyle cs) {
        CssExprs.Builder ex = new CssExprs.Builder();
        this.flexDirection = StyleReader.keyword(cs, ID_flexDirection, com.nishiyu.enderframelib.core.css.model.CssKeywords.FlexDirection.class);
        this.flexWrap = StyleReader.keyword(cs, ID_flexWrap, com.nishiyu.enderframelib.core.css.model.CssKeywords.FlexWrap.class);
        this.flexGrow = StyleReader.number(cs, ID_flexGrow);
        this.flexShrink = StyleReader.number(cs, ID_flexShrink);
        this.flexBasis = StyleReader.length(cs, ID_flexBasis, ex);
        this.order = StyleReader.number(cs, ID_order);
        this.justifyContent = StyleReader.keyword(cs, ID_justifyContent, com.nishiyu.enderframelib.core.css.model.CssKeywords.Alignment.class);
        this.alignContent = StyleReader.keyword(cs, ID_alignContent, com.nishiyu.enderframelib.core.css.model.CssKeywords.Alignment.class);
        this.justifyItems = StyleReader.keyword(cs, ID_justifyItems, com.nishiyu.enderframelib.core.css.model.CssKeywords.Alignment.class);
        this.alignItems = StyleReader.keyword(cs, ID_alignItems, com.nishiyu.enderframelib.core.css.model.CssKeywords.Alignment.class);
        this.justifySelf = StyleReader.keyword(cs, ID_justifySelf, com.nishiyu.enderframelib.core.css.model.CssKeywords.Alignment.class);
        this.alignSelf = StyleReader.keyword(cs, ID_alignSelf, com.nishiyu.enderframelib.core.css.model.CssKeywords.Alignment.class);
        this.rowGap = StyleReader.length(cs, ID_rowGap, ex);
        this.gridTemplateColumns = StyleReader.text(cs, ID_gridTemplateColumns);
        this.gridTemplateRows = StyleReader.text(cs, ID_gridTemplateRows);
        this.gridTemplateAreas = StyleReader.text(cs, ID_gridTemplateAreas);
        this.gridAutoColumns = StyleReader.text(cs, ID_gridAutoColumns);
        this.gridAutoRows = StyleReader.text(cs, ID_gridAutoRows);
        this.gridAutoFlow = StyleReader.text(cs, ID_gridAutoFlow);
        this.gridColumnStart = StyleReader.text(cs, ID_gridColumnStart);
        this.gridColumnEnd = StyleReader.text(cs, ID_gridColumnEnd);
        this.gridRowStart = StyleReader.text(cs, ID_gridRowStart);
        this.gridRowEnd = StyleReader.text(cs, ID_gridRowEnd);
        this.gridColumn = StyleReader.text(cs, ID_gridColumn);
        this.gridRow = StyleReader.text(cs, ID_gridRow);
        this.gridArea = StyleReader.text(cs, ID_gridArea);
        this.exprs = ex.build();
    }

    /** 算出スタイルから、この群の値を取り出す。 */
    public static FlexGridStyle of(ComputedStyle cs) {
        return new FlexGridStyle(cs);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof FlexGridStyle o)) return false;
        return exprs.equals(o.exprs)
                && flexDirection == o.flexDirection
                && flexWrap == o.flexWrap
                && Float.floatToIntBits(flexGrow) == Float.floatToIntBits(o.flexGrow)
                && Float.floatToIntBits(flexShrink) == Float.floatToIntBits(o.flexShrink)
                && flexBasis == o.flexBasis
                && Float.floatToIntBits(order) == Float.floatToIntBits(o.order)
                && justifyContent == o.justifyContent
                && alignContent == o.alignContent
                && justifyItems == o.justifyItems
                && alignItems == o.alignItems
                && justifySelf == o.justifySelf
                && alignSelf == o.alignSelf
                && rowGap == o.rowGap
                && java.util.Objects.equals(gridTemplateColumns, o.gridTemplateColumns)
                && java.util.Objects.equals(gridTemplateRows, o.gridTemplateRows)
                && java.util.Objects.equals(gridTemplateAreas, o.gridTemplateAreas)
                && java.util.Objects.equals(gridAutoColumns, o.gridAutoColumns)
                && java.util.Objects.equals(gridAutoRows, o.gridAutoRows)
                && java.util.Objects.equals(gridAutoFlow, o.gridAutoFlow)
                && java.util.Objects.equals(gridColumnStart, o.gridColumnStart)
                && java.util.Objects.equals(gridColumnEnd, o.gridColumnEnd)
                && java.util.Objects.equals(gridRowStart, o.gridRowStart)
                && java.util.Objects.equals(gridRowEnd, o.gridRowEnd)
                && java.util.Objects.equals(gridColumn, o.gridColumn)
                && java.util.Objects.equals(gridRow, o.gridRow)
                && java.util.Objects.equals(gridArea, o.gridArea);
    }

    @Override
    public int hashCode() {
        int h = 26;
        h = h * 31 + System.identityHashCode(flexDirection);
        h = h * 31 + System.identityHashCode(flexWrap);
        h = h * 31 + Float.floatToIntBits(flexGrow);
        h = h * 31 + Float.floatToIntBits(flexShrink);
        h = h * 31 + Long.hashCode(flexBasis);
        h = h * 31 + Float.floatToIntBits(order);
        h = h * 31 + System.identityHashCode(justifyContent);
        h = h * 31 + System.identityHashCode(alignContent);
        h = h * 31 + System.identityHashCode(justifyItems);
        h = h * 31 + System.identityHashCode(alignItems);
        h = h * 31 + System.identityHashCode(justifySelf);
        h = h * 31 + System.identityHashCode(alignSelf);
        h = h * 31 + Long.hashCode(rowGap);
        h = h * 31 + java.util.Objects.hashCode(gridTemplateColumns);
        h = h * 31 + java.util.Objects.hashCode(gridTemplateRows);
        h = h * 31 + java.util.Objects.hashCode(gridTemplateAreas);
        h = h * 31 + java.util.Objects.hashCode(gridAutoColumns);
        h = h * 31 + java.util.Objects.hashCode(gridAutoRows);
        h = h * 31 + java.util.Objects.hashCode(gridAutoFlow);
        h = h * 31 + java.util.Objects.hashCode(gridColumnStart);
        h = h * 31 + java.util.Objects.hashCode(gridColumnEnd);
        h = h * 31 + java.util.Objects.hashCode(gridRowStart);
        h = h * 31 + java.util.Objects.hashCode(gridRowEnd);
        h = h * 31 + java.util.Objects.hashCode(gridColumn);
        h = h * 31 + java.util.Objects.hashCode(gridRow);
        h = h * 31 + java.util.Objects.hashCode(gridArea);
        return h;
    }
}
