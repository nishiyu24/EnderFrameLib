package com.nishiyu.enderframelib.core.css;

/**
 * 生成物（{@code scripts/GenStyleGroups.java}。手で直さない）: 継承されないプロパティの型付き不変構造体。
 * 値は構築時に 1 回だけ解いた型付きのフィールドで、文字列を読み直さない。{@link #equals} は全フィールドの値等価。
 */
public final class RareStyle {
    static final int ID_columnCount = ComputedStyle.idOf("column-count");
    static final int ID_columnWidth = ComputedStyle.idOf("column-width");
    static final int ID_columnFill = ComputedStyle.idOf("column-fill");
    static final int ID_columnSpan = ComputedStyle.idOf("column-span");
    static final int ID_columnRuleColor = ComputedStyle.idOf("column-rule-color");
    static final int ID_columnRuleStyle = ComputedStyle.idOf("column-rule-style");
    static final int ID_columnRuleWidth = ComputedStyle.idOf("column-rule-width");
    static final int ID_appearance = ComputedStyle.idOf("appearance");
    static final int ID_contain = ComputedStyle.idOf("contain");
    static final int ID_contentVisibility = ComputedStyle.idOf("content-visibility");
    static final int ID_containIntrinsicWidth = ComputedStyle.idOf("contain-intrinsic-width");
    static final int ID_containIntrinsicHeight = ComputedStyle.idOf("contain-intrinsic-height");
    static final int ID_containerType = ComputedStyle.idOf("container-type");
    static final int ID_containerName = ComputedStyle.idOf("container-name");
    static final int ID_anchorName = ComputedStyle.idOf("anchor-name");
    static final int ID_positionAnchor = ComputedStyle.idOf("position-anchor");
    static final int ID_positionArea = ComputedStyle.idOf("position-area");
    static final int ID_positionTryFallbacks = ComputedStyle.idOf("position-try-fallbacks");
    static final int ID_positionTryOrder = ComputedStyle.idOf("position-try-order");
    static final int ID_content = ComputedStyle.idOf("content");
    static final int ID_counterReset = ComputedStyle.idOf("counter-reset");
    static final int ID_counterIncrement = ComputedStyle.idOf("counter-increment");
    static final int ID_counterSet = ComputedStyle.idOf("counter-set");
    static final int ID_src = ComputedStyle.idOf("src");
    static final int ID_objectFit = ComputedStyle.idOf("object-fit");
    static final int ID_breakInside = ComputedStyle.idOf("break-inside");
    static final int ID_perspective = ComputedStyle.idOf("perspective");
    static final int ID_userSelect = ComputedStyle.idOf("user-select");
    static final int ID_transitionProperty = ComputedStyle.idOf("transition-property");
    static final int ID_transitionDuration = ComputedStyle.idOf("transition-duration");
    static final int ID_transitionDelay = ComputedStyle.idOf("transition-delay");
    static final int ID_transitionTimingFunction = ComputedStyle.idOf("transition-timing-function");
    static final int ID_transition = ComputedStyle.idOf("transition");
    static final int ID_animationName = ComputedStyle.idOf("animation-name");
    static final int ID_animationDuration = ComputedStyle.idOf("animation-duration");
    static final int ID_animationDelay = ComputedStyle.idOf("animation-delay");
    static final int ID_animationTimingFunction = ComputedStyle.idOf("animation-timing-function");
    static final int ID_animationIterationCount = ComputedStyle.idOf("animation-iteration-count");
    static final int ID_animationDirection = ComputedStyle.idOf("animation-direction");
    static final int ID_animationFillMode = ComputedStyle.idOf("animation-fill-mode");
    static final int ID_animation = ComputedStyle.idOf("animation");

    /** 宣言が 1 つも無いときの共有インスタンス（初期値だけ）。ID_* の初期化より後に置く。 */
    public static final RareStyle DEFAULT = new RareStyle(null);

    /** {@code calc()} 等の式の表。式を持たない値は共有の空表。 */
    public final CssExprs exprs;
    /** {@code column-count}。 */
    public final float columnCount;
    /** {@code column-width}。 */
    public final long columnWidth;
    /** {@code column-fill}。 */
    public final String columnFill;
    /** {@code column-span}。 */
    public final String columnSpan;
    /** {@code column-rule-color}。 */
    public final int columnRuleColor;
    /** {@code column-rule-style}。 */
    public final com.nishiyu.enderframelib.core.css.model.BorderStyle columnRuleStyle;
    /** {@code column-rule-width}。 */
    public final long columnRuleWidth;
    /** {@code appearance}。 */
    public final String appearance;
    /** {@code contain}。 */
    public final String contain;
    /** {@code content-visibility}。 */
    public final com.nishiyu.enderframelib.core.css.model.CssKeywords.ContentVisibility contentVisibility;
    /** {@code contain-intrinsic-width}。 */
    public final String containIntrinsicWidth;
    /** {@code contain-intrinsic-height}。 */
    public final String containIntrinsicHeight;
    /** {@code container-type}。 */
    public final com.nishiyu.enderframelib.core.css.model.CssKeywords.ContainerType containerType;
    /** {@code container-name}。 */
    public final String containerName;
    /** {@code anchor-name}。 */
    public final String anchorName;
    /** {@code position-anchor}。 */
    public final String positionAnchor;
    /** {@code position-area}。 */
    public final String positionArea;
    /** {@code position-try-fallbacks}。 */
    public final String positionTryFallbacks;
    /** {@code position-try-order}。 */
    public final String positionTryOrder;
    /** {@code content}。 */
    public final String content;
    /** {@code counter-reset}。 */
    public final String counterReset;
    /** {@code counter-increment}。 */
    public final String counterIncrement;
    /** {@code counter-set}。 */
    public final String counterSet;
    /** {@code src}。 */
    public final String src;
    /** {@code object-fit}。 */
    public final String objectFit;
    /** {@code break-inside}。 */
    public final String breakInside;
    /** {@code perspective}。 */
    public final String perspective;
    /** {@code user-select}。 */
    public final String userSelect;
    /** {@code transition-property}。 */
    public final String transitionProperty;
    /** {@code transition-duration}。 */
    public final String transitionDuration;
    /** {@code transition-delay}。 */
    public final String transitionDelay;
    /** {@code transition-timing-function}。 */
    public final String transitionTimingFunction;
    /** {@code transition}。 */
    public final String transition;
    /** {@code animation-name}。 */
    public final String animationName;
    /** {@code animation-duration}。 */
    public final String animationDuration;
    /** {@code animation-delay}。 */
    public final String animationDelay;
    /** {@code animation-timing-function}。 */
    public final String animationTimingFunction;
    /** {@code animation-iteration-count}。 */
    public final String animationIterationCount;
    /** {@code animation-direction}。 */
    public final String animationDirection;
    /** {@code animation-fill-mode}。 */
    public final String animationFillMode;
    /** {@code animation}。 */
    public final String animation;
    /** {@code column-rule-color} が {@code currentcolor}/未宣言か。 */
    public final boolean columnRuleColorCurrent;

    private RareStyle(StyleReader.Source cs) {
        CssExprs.Builder ex = new CssExprs.Builder();
        this.columnCount = StyleReader.number(cs, ID_columnCount);
        this.columnWidth = StyleReader.length(cs, ID_columnWidth, ex);
        this.columnFill = StyleReader.text(cs, ID_columnFill);
        this.columnSpan = StyleReader.text(cs, ID_columnSpan);
        this.columnRuleColor = StyleReader.color(cs, ID_columnRuleColor);
        this.columnRuleStyle = StyleReader.keyword(cs, ID_columnRuleStyle, com.nishiyu.enderframelib.core.css.model.BorderStyle.class);
        this.columnRuleWidth = StyleReader.length(cs, ID_columnRuleWidth, ex);
        this.appearance = StyleReader.text(cs, ID_appearance);
        this.contain = StyleReader.text(cs, ID_contain);
        this.contentVisibility = StyleReader.keyword(cs, ID_contentVisibility, com.nishiyu.enderframelib.core.css.model.CssKeywords.ContentVisibility.class);
        this.containIntrinsicWidth = StyleReader.text(cs, ID_containIntrinsicWidth);
        this.containIntrinsicHeight = StyleReader.text(cs, ID_containIntrinsicHeight);
        this.containerType = StyleReader.keyword(cs, ID_containerType, com.nishiyu.enderframelib.core.css.model.CssKeywords.ContainerType.class);
        this.containerName = StyleReader.text(cs, ID_containerName);
        this.anchorName = StyleReader.text(cs, ID_anchorName);
        this.positionAnchor = StyleReader.text(cs, ID_positionAnchor);
        this.positionArea = StyleReader.text(cs, ID_positionArea);
        this.positionTryFallbacks = StyleReader.text(cs, ID_positionTryFallbacks);
        this.positionTryOrder = StyleReader.text(cs, ID_positionTryOrder);
        this.content = StyleReader.text(cs, ID_content);
        this.counterReset = StyleReader.text(cs, ID_counterReset);
        this.counterIncrement = StyleReader.text(cs, ID_counterIncrement);
        this.counterSet = StyleReader.text(cs, ID_counterSet);
        this.src = StyleReader.text(cs, ID_src);
        this.objectFit = StyleReader.text(cs, ID_objectFit);
        this.breakInside = StyleReader.text(cs, ID_breakInside);
        this.perspective = StyleReader.text(cs, ID_perspective);
        this.userSelect = StyleReader.text(cs, ID_userSelect);
        this.transitionProperty = StyleReader.text(cs, ID_transitionProperty);
        this.transitionDuration = StyleReader.text(cs, ID_transitionDuration);
        this.transitionDelay = StyleReader.text(cs, ID_transitionDelay);
        this.transitionTimingFunction = StyleReader.text(cs, ID_transitionTimingFunction);
        this.transition = StyleReader.text(cs, ID_transition);
        this.animationName = StyleReader.text(cs, ID_animationName);
        this.animationDuration = StyleReader.text(cs, ID_animationDuration);
        this.animationDelay = StyleReader.text(cs, ID_animationDelay);
        this.animationTimingFunction = StyleReader.text(cs, ID_animationTimingFunction);
        this.animationIterationCount = StyleReader.text(cs, ID_animationIterationCount);
        this.animationDirection = StyleReader.text(cs, ID_animationDirection);
        this.animationFillMode = StyleReader.text(cs, ID_animationFillMode);
        this.animation = StyleReader.text(cs, ID_animation);
        this.columnRuleColorCurrent = StyleReader.isCurrentColor(cs, ID_columnRuleColor, false);
        this.exprs = ex.build();
    }

    /** 宣言入力から、この群の値を取り出す。 */
    public static RareStyle of(StyleReader.Source cs) {
        return new RareStyle(cs);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof RareStyle o)) return false;
        return exprs.equals(o.exprs)
                && Float.floatToIntBits(columnCount) == Float.floatToIntBits(o.columnCount)
                && columnWidth == o.columnWidth
                && java.util.Objects.equals(columnFill, o.columnFill)
                && java.util.Objects.equals(columnSpan, o.columnSpan)
                && columnRuleColor == o.columnRuleColor
                && columnRuleStyle == o.columnRuleStyle
                && columnRuleWidth == o.columnRuleWidth
                && java.util.Objects.equals(appearance, o.appearance)
                && java.util.Objects.equals(contain, o.contain)
                && contentVisibility == o.contentVisibility
                && java.util.Objects.equals(containIntrinsicWidth, o.containIntrinsicWidth)
                && java.util.Objects.equals(containIntrinsicHeight, o.containIntrinsicHeight)
                && containerType == o.containerType
                && java.util.Objects.equals(containerName, o.containerName)
                && java.util.Objects.equals(anchorName, o.anchorName)
                && java.util.Objects.equals(positionAnchor, o.positionAnchor)
                && java.util.Objects.equals(positionArea, o.positionArea)
                && java.util.Objects.equals(positionTryFallbacks, o.positionTryFallbacks)
                && java.util.Objects.equals(positionTryOrder, o.positionTryOrder)
                && java.util.Objects.equals(content, o.content)
                && java.util.Objects.equals(counterReset, o.counterReset)
                && java.util.Objects.equals(counterIncrement, o.counterIncrement)
                && java.util.Objects.equals(counterSet, o.counterSet)
                && java.util.Objects.equals(src, o.src)
                && java.util.Objects.equals(objectFit, o.objectFit)
                && java.util.Objects.equals(breakInside, o.breakInside)
                && java.util.Objects.equals(perspective, o.perspective)
                && java.util.Objects.equals(userSelect, o.userSelect)
                && java.util.Objects.equals(transitionProperty, o.transitionProperty)
                && java.util.Objects.equals(transitionDuration, o.transitionDuration)
                && java.util.Objects.equals(transitionDelay, o.transitionDelay)
                && java.util.Objects.equals(transitionTimingFunction, o.transitionTimingFunction)
                && java.util.Objects.equals(transition, o.transition)
                && java.util.Objects.equals(animationName, o.animationName)
                && java.util.Objects.equals(animationDuration, o.animationDuration)
                && java.util.Objects.equals(animationDelay, o.animationDelay)
                && java.util.Objects.equals(animationTimingFunction, o.animationTimingFunction)
                && java.util.Objects.equals(animationIterationCount, o.animationIterationCount)
                && java.util.Objects.equals(animationDirection, o.animationDirection)
                && java.util.Objects.equals(animationFillMode, o.animationFillMode)
                && java.util.Objects.equals(animation, o.animation)
                && columnRuleColorCurrent == o.columnRuleColorCurrent;
    }

    @Override
    public int hashCode() {
        int h = 41;
        h = h * 31 + Float.floatToIntBits(columnCount);
        h = h * 31 + Long.hashCode(columnWidth);
        h = h * 31 + java.util.Objects.hashCode(columnFill);
        h = h * 31 + java.util.Objects.hashCode(columnSpan);
        h = h * 31 + columnRuleColor;
        h = h * 31 + System.identityHashCode(columnRuleStyle);
        h = h * 31 + Long.hashCode(columnRuleWidth);
        h = h * 31 + java.util.Objects.hashCode(appearance);
        h = h * 31 + java.util.Objects.hashCode(contain);
        h = h * 31 + System.identityHashCode(contentVisibility);
        h = h * 31 + java.util.Objects.hashCode(containIntrinsicWidth);
        h = h * 31 + java.util.Objects.hashCode(containIntrinsicHeight);
        h = h * 31 + System.identityHashCode(containerType);
        h = h * 31 + java.util.Objects.hashCode(containerName);
        h = h * 31 + java.util.Objects.hashCode(anchorName);
        h = h * 31 + java.util.Objects.hashCode(positionAnchor);
        h = h * 31 + java.util.Objects.hashCode(positionArea);
        h = h * 31 + java.util.Objects.hashCode(positionTryFallbacks);
        h = h * 31 + java.util.Objects.hashCode(positionTryOrder);
        h = h * 31 + java.util.Objects.hashCode(content);
        h = h * 31 + java.util.Objects.hashCode(counterReset);
        h = h * 31 + java.util.Objects.hashCode(counterIncrement);
        h = h * 31 + java.util.Objects.hashCode(counterSet);
        h = h * 31 + java.util.Objects.hashCode(src);
        h = h * 31 + java.util.Objects.hashCode(objectFit);
        h = h * 31 + java.util.Objects.hashCode(breakInside);
        h = h * 31 + java.util.Objects.hashCode(perspective);
        h = h * 31 + java.util.Objects.hashCode(userSelect);
        h = h * 31 + java.util.Objects.hashCode(transitionProperty);
        h = h * 31 + java.util.Objects.hashCode(transitionDuration);
        h = h * 31 + java.util.Objects.hashCode(transitionDelay);
        h = h * 31 + java.util.Objects.hashCode(transitionTimingFunction);
        h = h * 31 + java.util.Objects.hashCode(transition);
        h = h * 31 + java.util.Objects.hashCode(animationName);
        h = h * 31 + java.util.Objects.hashCode(animationDuration);
        h = h * 31 + java.util.Objects.hashCode(animationDelay);
        h = h * 31 + java.util.Objects.hashCode(animationTimingFunction);
        h = h * 31 + java.util.Objects.hashCode(animationIterationCount);
        h = h * 31 + java.util.Objects.hashCode(animationDirection);
        h = h * 31 + java.util.Objects.hashCode(animationFillMode);
        h = h * 31 + java.util.Objects.hashCode(animation);
        return h;
    }
}
