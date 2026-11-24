package com.nishiyu.enderframelib.core.css;

/** 生成物（{@code scripts/GenStyleGroups.java}。手で直さない）: 2 つの {@link Style} の型付き差分。 */
final class StyleDiff {
    private StyleDiff() {}

    static void diff(Style a, Style b, Style.DiffVisitor v) {
        if (a == b) return;
        if (a.inherited != b.inherited) {
            InheritedStyle x = a.inherited, y = b.inherited;
            if (x.visibility != y.visibility) v.changed("visibility", true, true);
            if (x.borderCollapse != y.borderCollapse) v.changed("border-collapse", false, true);
            if (!java.util.Objects.equals(x.borderSpacing, y.borderSpacing)) v.changed("border-spacing", false, true);
            if (x.captionSide != y.captionSide) v.changed("caption-side", false, true);
            if (!java.util.Objects.equals(x.emptyCells, y.emptyCells)) v.changed("empty-cells", true, true);
            if (!java.util.Objects.equals(x.listStyleType, y.listStyleType)) v.changed("list-style-type", false, true);
            if (x.listStylePosition != y.listStylePosition) v.changed("list-style-position", false, true);
            if (!java.util.Objects.equals(x.listStyleImage, y.listStyleImage)) v.changed("list-style-image", false, true);
            if (!java.util.Objects.equals(x.fontFamily, y.fontFamily)) v.changed("font-family", false, true);
            if (Float.floatToIntBits(x.fontSize) != Float.floatToIntBits(y.fontSize)) v.changed("font-size", false, true);
            if (x.fontWeight != y.fontWeight) v.changed("font-weight", false, true);
            if (x.fontStyle != y.fontStyle) v.changed("font-style", false, true);
            if (!java.util.Objects.equals(x.fontVariant, y.fontVariant)) v.changed("font-variant", false, true);
            if (!java.util.Objects.equals(x.fontKerning, y.fontKerning)) v.changed("font-kerning", false, true);
            if (x.lineHeightKind != y.lineHeightKind || Float.floatToIntBits(x.lineHeightValue) != Float.floatToIntBits(y.lineHeightValue)) v.changed("line-height", false, true);
            if (!CssLength.same(x.letterSpacing, x.exprs, y.letterSpacing, y.exprs)) v.changed("letter-spacing", false, true);
            if (!CssLength.same(x.wordSpacing, x.exprs, y.wordSpacing, y.exprs)) v.changed("word-spacing", false, true);
            if (!CssLength.same(x.textIndent, x.exprs, y.textIndent, y.exprs)) v.changed("text-indent", false, true);
            if (x.textAlign != y.textAlign) v.changed("text-align", false, true);
            if (!java.util.Objects.equals(x.textAlignLast, y.textAlignLast)) v.changed("text-align-last", false, true);
            if (!java.util.Objects.equals(x.textJustify, y.textJustify)) v.changed("text-justify", false, true);
            if (!java.util.Objects.equals(x.tabSize, y.tabSize)) v.changed("tab-size", false, true);
            if (!java.util.Objects.equals(x.textWrap, y.textWrap)) v.changed("text-wrap", false, true);
            if (!java.util.Objects.equals(x.textWrapStyle, y.textWrapStyle)) v.changed("text-wrap-style", false, true);
            if (x.textTransform != y.textTransform) v.changed("text-transform", false, true);
            if (!java.util.Objects.equals(x.whiteSpace, y.whiteSpace)) v.changed("white-space", false, true);
            if (!java.util.Objects.equals(x.wordBreak, y.wordBreak)) v.changed("word-break", false, true);
            if (x.overflowWrap != y.overflowWrap) v.changed("overflow-wrap", false, true);
            if (!java.util.Objects.equals(x.direction, y.direction)) v.changed("direction", false, true);
            if (x.hasColor != y.hasColor || (x.hasColor && x.color != y.color)) v.changed("color", true, true);
            if (!java.util.Objects.equals(x.accentColor, y.accentColor)) v.changed("accent-color", true, true);
            if (!java.util.Objects.equals(x.textDecoration, y.textDecoration)) v.changed("text-decoration", true, true);
            if (!java.util.Objects.equals(x.textShadow, y.textShadow)) v.changed("text-shadow", true, true);
            if (!java.util.Objects.equals(x.webkitTextStrokeWidth, y.webkitTextStrokeWidth)) v.changed("-webkit-text-stroke-width", true, true);
            if (!java.util.Objects.equals(x.webkitTextStrokeColor, y.webkitTextStrokeColor)) v.changed("-webkit-text-stroke-color", true, true);
            if (!java.util.Objects.equals(x.writingMode, y.writingMode)) v.changed("writing-mode", false, true);
            if (!java.util.Objects.equals(x.textOrientation, y.textOrientation)) v.changed("text-orientation", false, true);
            if (!java.util.Objects.equals(x.cursor, y.cursor)) v.changed("cursor", true, true);
            if (x.pointerEvents != y.pointerEvents) v.changed("pointer-events", true, true);
        }
        if (a.box != b.box) {
            BoxGeomStyle x = a.box, y = b.box;
            if (!java.util.Objects.equals(x.display, y.display)) v.changed("display", false, false);
            if (x.position != y.position) v.changed("position", false, false);
            if (!CssLength.same(x.top, x.exprs, y.top, y.exprs)) v.changed("top", false, false);
            if (!CssLength.same(x.right, x.exprs, y.right, y.exprs)) v.changed("right", false, false);
            if (!CssLength.same(x.bottom, x.exprs, y.bottom, y.exprs)) v.changed("bottom", false, false);
            if (!CssLength.same(x.left, x.exprs, y.left, y.exprs)) v.changed("left", false, false);
            if (x.floatValue != y.floatValue) v.changed("float", false, false);
            if (x.clear != y.clear) v.changed("clear", false, false);
            if (Float.floatToIntBits(x.zIndex) != Float.floatToIntBits(y.zIndex)) v.changed("z-index", true, false);
            if (!CssLength.same(x.width, x.exprs, y.width, y.exprs)) v.changed("width", false, false);
            if (!CssLength.same(x.height, x.exprs, y.height, y.exprs)) v.changed("height", false, false);
            if (!CssLength.same(x.minWidth, x.exprs, y.minWidth, y.exprs)) v.changed("min-width", false, false);
            if (!CssLength.same(x.minHeight, x.exprs, y.minHeight, y.exprs)) v.changed("min-height", false, false);
            if (!CssLength.same(x.maxWidth, x.exprs, y.maxWidth, y.exprs)) v.changed("max-width", false, false);
            if (!CssLength.same(x.maxHeight, x.exprs, y.maxHeight, y.exprs)) v.changed("max-height", false, false);
            if (x.boxSizing != y.boxSizing) v.changed("box-sizing", false, false);
            if (!java.util.Objects.equals(x.aspectRatio, y.aspectRatio)) v.changed("aspect-ratio", false, false);
            if (!CssLength.same(x.marginTop, x.exprs, y.marginTop, y.exprs)) v.changed("margin-top", false, false);
            if (!CssLength.same(x.marginRight, x.exprs, y.marginRight, y.exprs)) v.changed("margin-right", false, false);
            if (!CssLength.same(x.marginBottom, x.exprs, y.marginBottom, y.exprs)) v.changed("margin-bottom", false, false);
            if (!CssLength.same(x.marginLeft, x.exprs, y.marginLeft, y.exprs)) v.changed("margin-left", false, false);
            if (!CssLength.same(x.paddingTop, x.exprs, y.paddingTop, y.exprs)) v.changed("padding-top", false, false);
            if (!CssLength.same(x.paddingRight, x.exprs, y.paddingRight, y.exprs)) v.changed("padding-right", false, false);
            if (!CssLength.same(x.paddingBottom, x.exprs, y.paddingBottom, y.exprs)) v.changed("padding-bottom", false, false);
            if (!CssLength.same(x.paddingLeft, x.exprs, y.paddingLeft, y.exprs)) v.changed("padding-left", false, false);
            if (!CssLength.same(x.borderTopWidth, x.exprs, y.borderTopWidth, y.exprs)) v.changed("border-top-width", false, false);
            if (x.borderTopStyle != y.borderTopStyle) v.changed("border-top-style", false, false);
            if (!CssLength.same(x.borderRightWidth, x.exprs, y.borderRightWidth, y.exprs)) v.changed("border-right-width", false, false);
            if (x.borderRightStyle != y.borderRightStyle) v.changed("border-right-style", false, false);
            if (!CssLength.same(x.borderBottomWidth, x.exprs, y.borderBottomWidth, y.exprs)) v.changed("border-bottom-width", false, false);
            if (x.borderBottomStyle != y.borderBottomStyle) v.changed("border-bottom-style", false, false);
            if (!CssLength.same(x.borderLeftWidth, x.exprs, y.borderLeftWidth, y.exprs)) v.changed("border-left-width", false, false);
            if (x.borderLeftStyle != y.borderLeftStyle) v.changed("border-left-style", false, false);
            if (x.overflowX != y.overflowX) v.changed("overflow-x", false, false);
            if (x.overflowY != y.overflowY) v.changed("overflow-y", false, false);
            if (x.scrollbarWidth != y.scrollbarWidth) v.changed("scrollbar-width", false, false);
            if (!CssLength.same(x.verticalAlign, x.exprs, y.verticalAlign, y.exprs)) v.changed("vertical-align", false, false);
            if (x.textOverflow != y.textOverflow) v.changed("text-overflow", false, false);
        }
        if (a.visual != b.visual) {
            VisualStyle x = a.visual, y = b.visual;
            if (((x.currentColorMask & 1) != (y.currentColorMask & 1)) || ((x.currentColorMask & 1) == 0 && x.borderTopColor != y.borderTopColor)) v.changed("border-top-color", true, false);
            if (((x.currentColorMask & 2) != (y.currentColorMask & 2)) || ((x.currentColorMask & 2) == 0 && x.borderRightColor != y.borderRightColor)) v.changed("border-right-color", true, false);
            if (((x.currentColorMask & 4) != (y.currentColorMask & 4)) || ((x.currentColorMask & 4) == 0 && x.borderBottomColor != y.borderBottomColor)) v.changed("border-bottom-color", true, false);
            if (((x.currentColorMask & 8) != (y.currentColorMask & 8)) || ((x.currentColorMask & 8) == 0 && x.borderLeftColor != y.borderLeftColor)) v.changed("border-left-color", true, false);
            if (!java.util.Objects.equals(x.borderTopLeftRadius, y.borderTopLeftRadius)) v.changed("border-top-left-radius", true, false);
            if (!java.util.Objects.equals(x.borderTopRightRadius, y.borderTopRightRadius)) v.changed("border-top-right-radius", true, false);
            if (!java.util.Objects.equals(x.borderBottomRightRadius, y.borderBottomRightRadius)) v.changed("border-bottom-right-radius", true, false);
            if (!java.util.Objects.equals(x.borderBottomLeftRadius, y.borderBottomLeftRadius)) v.changed("border-bottom-left-radius", true, false);
            if (((x.currentColorMask & 48) != (y.currentColorMask & 48)) || ((x.currentColorMask & 16) == 0 && x.outlineColor != y.outlineColor)) v.changed("outline-color", true, false);
            if (x.outlineStyle != y.outlineStyle) v.changed("outline-style", true, false);
            if (!CssLength.same(x.outlineWidth, x.exprs, y.outlineWidth, y.exprs)) v.changed("outline-width", true, false);
            if (!CssLength.same(x.outlineOffset, x.exprs, y.outlineOffset, y.exprs)) v.changed("outline-offset", true, false);
            if (!java.util.Objects.equals(x.objectPosition, y.objectPosition)) v.changed("object-position", true, false);
            if (x.hasBackgroundColor != y.hasBackgroundColor || (x.hasBackgroundColor && x.backgroundColor != y.backgroundColor)) v.changed("background-color", true, false);
            if (!java.util.Objects.equals(x.backgroundImage, y.backgroundImage)) v.changed("background-image", true, false);
            if (!java.util.Objects.equals(x.backgroundPosition, y.backgroundPosition)) v.changed("background-position", true, false);
            if (!java.util.Objects.equals(x.backgroundSize, y.backgroundSize)) v.changed("background-size", true, false);
            if (!java.util.Objects.equals(x.backgroundRepeat, y.backgroundRepeat)) v.changed("background-repeat", true, false);
            if (!java.util.Objects.equals(x.backgroundClip, y.backgroundClip)) v.changed("background-clip", true, false);
            if (!java.util.Objects.equals(x.backgroundOrigin, y.backgroundOrigin)) v.changed("background-origin", true, false);
            if (!java.util.Objects.equals(x.backgroundAttachment, y.backgroundAttachment)) v.changed("background-attachment", true, false);
            if (!java.util.Objects.equals(x.boxShadow, y.boxShadow)) v.changed("box-shadow", true, false);
            if (!java.util.Objects.equals(x.borderImageSource, y.borderImageSource)) v.changed("border-image-source", true, false);
            if (!java.util.Objects.equals(x.borderImageSlice, y.borderImageSlice)) v.changed("border-image-slice", true, false);
            if (!java.util.Objects.equals(x.borderImageWidth, y.borderImageWidth)) v.changed("border-image-width", true, false);
            if (!java.util.Objects.equals(x.borderImageOutset, y.borderImageOutset)) v.changed("border-image-outset", true, false);
            if (!java.util.Objects.equals(x.borderImageRepeat, y.borderImageRepeat)) v.changed("border-image-repeat", true, false);
            if (Float.floatToIntBits(x.opacity) != Float.floatToIntBits(y.opacity)) v.changed("opacity", true, false);
            if (!java.util.Objects.equals(x.transform, y.transform)) v.changed("transform", true, false);
            if (!java.util.Objects.equals(x.transformOrigin, y.transformOrigin)) v.changed("transform-origin", true, false);
            if (!java.util.Objects.equals(x.rotate, y.rotate)) v.changed("rotate", false, false);
            if (!java.util.Objects.equals(x.translate, y.translate)) v.changed("translate", false, false);
            if (!java.util.Objects.equals(x.scale, y.scale)) v.changed("scale", false, false);
            if (!java.util.Objects.equals(x.filter, y.filter)) v.changed("filter", true, false);
            if (!java.util.Objects.equals(x.mixBlendMode, y.mixBlendMode)) v.changed("mix-blend-mode", true, false);
            if (x.isolation != y.isolation) v.changed("isolation", true, false);
            if (!java.util.Objects.equals(x.clipPath, y.clipPath)) v.changed("clip-path", true, false);
            if (!java.util.Objects.equals(x.backdropFilter, y.backdropFilter)) v.changed("backdrop-filter", true, false);
            if (!java.util.Objects.equals(x.maskImage, y.maskImage)) v.changed("mask-image", true, false);
            if (!java.util.Objects.equals(x.maskPosition, y.maskPosition)) v.changed("mask-position", true, false);
            if (!java.util.Objects.equals(x.maskSize, y.maskSize)) v.changed("mask-size", true, false);
            if (!java.util.Objects.equals(x.maskRepeat, y.maskRepeat)) v.changed("mask-repeat", true, false);
            if (!java.util.Objects.equals(x.maskOrigin, y.maskOrigin)) v.changed("mask-origin", true, false);
            if (!java.util.Objects.equals(x.maskClip, y.maskClip)) v.changed("mask-clip", true, false);
            if (!java.util.Objects.equals(x.maskMode, y.maskMode)) v.changed("mask-mode", true, false);
            if (!java.util.Objects.equals(x.maskComposite, y.maskComposite)) v.changed("mask-composite", true, false);
        }
        if (a.flexGrid != b.flexGrid) {
            FlexGridStyle x = a.flexGrid, y = b.flexGrid;
            if (x.flexDirection != y.flexDirection) v.changed("flex-direction", false, false);
            if (x.flexWrap != y.flexWrap) v.changed("flex-wrap", false, false);
            if (Float.floatToIntBits(x.flexGrow) != Float.floatToIntBits(y.flexGrow)) v.changed("flex-grow", false, false);
            if (Float.floatToIntBits(x.flexShrink) != Float.floatToIntBits(y.flexShrink)) v.changed("flex-shrink", false, false);
            if (!CssLength.same(x.flexBasis, x.exprs, y.flexBasis, y.exprs)) v.changed("flex-basis", false, false);
            if (Float.floatToIntBits(x.order) != Float.floatToIntBits(y.order)) v.changed("order", false, false);
            if (x.justifyContent != y.justifyContent || x.justifyContentSafe != y.justifyContentSafe) v.changed("justify-content", false, false);
            if (x.alignContent != y.alignContent || x.alignContentSafe != y.alignContentSafe) v.changed("align-content", false, false);
            if (x.justifyItems != y.justifyItems) v.changed("justify-items", false, false);
            if (x.alignItems != y.alignItems) v.changed("align-items", false, false);
            if (x.justifySelf != y.justifySelf) v.changed("justify-self", false, false);
            if (x.alignSelf != y.alignSelf) v.changed("align-self", false, false);
            if (!CssLength.same(x.rowGap, x.exprs, y.rowGap, y.exprs)) v.changed("row-gap", false, false);
            if (!CssLength.same(x.columnGap, x.exprs, y.columnGap, y.exprs)) v.changed("column-gap", false, false);
            if (!java.util.Objects.equals(x.gridTemplateColumns, y.gridTemplateColumns)) v.changed("grid-template-columns", false, false);
            if (!java.util.Objects.equals(x.gridTemplateRows, y.gridTemplateRows)) v.changed("grid-template-rows", false, false);
            if (!java.util.Objects.equals(x.gridTemplateAreas, y.gridTemplateAreas)) v.changed("grid-template-areas", false, false);
            if (!java.util.Objects.equals(x.gridAutoColumns, y.gridAutoColumns)) v.changed("grid-auto-columns", false, false);
            if (!java.util.Objects.equals(x.gridAutoRows, y.gridAutoRows)) v.changed("grid-auto-rows", false, false);
            if (!java.util.Objects.equals(x.gridAutoFlow, y.gridAutoFlow)) v.changed("grid-auto-flow", false, false);
            if (!java.util.Objects.equals(x.gridColumnStart, y.gridColumnStart)) v.changed("grid-column-start", false, false);
            if (!java.util.Objects.equals(x.gridColumnEnd, y.gridColumnEnd)) v.changed("grid-column-end", false, false);
            if (!java.util.Objects.equals(x.gridRowStart, y.gridRowStart)) v.changed("grid-row-start", false, false);
            if (!java.util.Objects.equals(x.gridRowEnd, y.gridRowEnd)) v.changed("grid-row-end", false, false);
            if (!java.util.Objects.equals(x.gridColumn, y.gridColumn)) v.changed("grid-column", false, false);
            if (!java.util.Objects.equals(x.gridRow, y.gridRow)) v.changed("grid-row", false, false);
            if (!java.util.Objects.equals(x.gridArea, y.gridArea)) v.changed("grid-area", false, false);
        }
        if (a.table != b.table) {
            TableStyle x = a.table, y = b.table;
            if (x.tableLayout != y.tableLayout) v.changed("table-layout", false, false);
        }
        if (a.rare != b.rare) {
            RareStyle x = a.rare, y = b.rare;
            if (Float.floatToIntBits(x.columnCount) != Float.floatToIntBits(y.columnCount)) v.changed("column-count", false, false);
            if (!CssLength.same(x.columnWidth, x.exprs, y.columnWidth, y.exprs)) v.changed("column-width", false, false);
            if (!java.util.Objects.equals(x.columnFill, y.columnFill)) v.changed("column-fill", false, false);
            if (!java.util.Objects.equals(x.columnSpan, y.columnSpan)) v.changed("column-span", false, false);
            if (x.columnRuleColorCurrent != y.columnRuleColorCurrent || (!x.columnRuleColorCurrent && x.columnRuleColor != y.columnRuleColor)) v.changed("column-rule-color", true, false);
            if (x.columnRuleStyle != y.columnRuleStyle) v.changed("column-rule-style", false, false);
            if (!CssLength.same(x.columnRuleWidth, x.exprs, y.columnRuleWidth, y.exprs)) v.changed("column-rule-width", false, false);
            if (!java.util.Objects.equals(x.appearance, y.appearance)) v.changed("appearance", false, false);
            if (!java.util.Objects.equals(x.contain, y.contain)) v.changed("contain", false, false);
            if (x.contentVisibility != y.contentVisibility) v.changed("content-visibility", false, false);
            if (!java.util.Objects.equals(x.containIntrinsicWidth, y.containIntrinsicWidth)) v.changed("contain-intrinsic-width", false, false);
            if (!java.util.Objects.equals(x.containIntrinsicHeight, y.containIntrinsicHeight)) v.changed("contain-intrinsic-height", false, false);
            if (x.containerType != y.containerType) v.changed("container-type", false, false);
            if (!java.util.Objects.equals(x.containerName, y.containerName)) v.changed("container-name", false, false);
            if (!java.util.Objects.equals(x.anchorName, y.anchorName)) v.changed("anchor-name", false, false);
            if (!java.util.Objects.equals(x.positionAnchor, y.positionAnchor)) v.changed("position-anchor", false, false);
            if (!java.util.Objects.equals(x.positionArea, y.positionArea)) v.changed("position-area", false, false);
            if (!java.util.Objects.equals(x.positionTryFallbacks, y.positionTryFallbacks)) v.changed("position-try-fallbacks", false, false);
            if (!java.util.Objects.equals(x.positionTryOrder, y.positionTryOrder)) v.changed("position-try-order", false, false);
            if (!java.util.Objects.equals(x.content, y.content)) v.changed("content", false, false);
            if (!java.util.Objects.equals(x.counterReset, y.counterReset)) v.changed("counter-reset", false, false);
            if (!java.util.Objects.equals(x.counterIncrement, y.counterIncrement)) v.changed("counter-increment", false, false);
            if (!java.util.Objects.equals(x.counterSet, y.counterSet)) v.changed("counter-set", false, false);
            if (!java.util.Objects.equals(x.src, y.src)) v.changed("src", false, false);
            if (!java.util.Objects.equals(x.objectFit, y.objectFit)) v.changed("object-fit", false, false);
            if (!java.util.Objects.equals(x.breakInside, y.breakInside)) v.changed("break-inside", false, false);
            if (!java.util.Objects.equals(x.perspective, y.perspective)) v.changed("perspective", false, false);
            if (!java.util.Objects.equals(x.userSelect, y.userSelect)) v.changed("user-select", true, false);
            if (!java.util.Objects.equals(x.transitionProperty, y.transitionProperty)) v.changed("transition-property", true, false);
            if (!java.util.Objects.equals(x.transitionDuration, y.transitionDuration)) v.changed("transition-duration", true, false);
            if (!java.util.Objects.equals(x.transitionDelay, y.transitionDelay)) v.changed("transition-delay", true, false);
            if (!java.util.Objects.equals(x.transitionTimingFunction, y.transitionTimingFunction)) v.changed("transition-timing-function", true, false);
            if (!java.util.Objects.equals(x.transition, y.transition)) v.changed("transition", true, false);
            if (!java.util.Objects.equals(x.animationName, y.animationName)) v.changed("animation-name", true, false);
            if (!java.util.Objects.equals(x.animationDuration, y.animationDuration)) v.changed("animation-duration", true, false);
            if (!java.util.Objects.equals(x.animationDelay, y.animationDelay)) v.changed("animation-delay", true, false);
            if (!java.util.Objects.equals(x.animationTimingFunction, y.animationTimingFunction)) v.changed("animation-timing-function", true, false);
            if (!java.util.Objects.equals(x.animationIterationCount, y.animationIterationCount)) v.changed("animation-iteration-count", true, false);
            if (!java.util.Objects.equals(x.animationDirection, y.animationDirection)) v.changed("animation-direction", true, false);
            if (!java.util.Objects.equals(x.animationFillMode, y.animationFillMode)) v.changed("animation-fill-mode", true, false);
            if (!java.util.Objects.equals(x.animation, y.animation)) v.changed("animation", true, false);
        }
        if (a.custom != b.custom && (a.custom == null || !a.custom.equals(b.custom))) {
            java.util.Map<String, String> ex = a.custom != null ? a.custom.asMap() : java.util.Map.of();
            java.util.Map<String, String> ey = b.custom != null ? b.custom.asMap() : java.util.Map.of();
            ex.forEach((k, val) -> { if (!java.util.Objects.equals(val, ey.get(k))) v.changed(k, false, k.startsWith("--")); });
            ey.forEach((k, val) -> { if (!ex.containsKey(k)) v.changed(k, false, k.startsWith("--")); });
        }
    }

    static boolean hasLayoutDiff(Style a, Style b) {
        if (a == b) return false;
        if (a.inherited != b.inherited) {
            InheritedStyle x = a.inherited, y = b.inherited;
            if (x.borderCollapse != y.borderCollapse) return true;
            if (!java.util.Objects.equals(x.borderSpacing, y.borderSpacing)) return true;
            if (x.captionSide != y.captionSide) return true;
            if (!java.util.Objects.equals(x.listStyleType, y.listStyleType)) return true;
            if (x.listStylePosition != y.listStylePosition) return true;
            if (!java.util.Objects.equals(x.listStyleImage, y.listStyleImage)) return true;
            if (!java.util.Objects.equals(x.fontFamily, y.fontFamily)) return true;
            if (Float.floatToIntBits(x.fontSize) != Float.floatToIntBits(y.fontSize)) return true;
            if (x.fontWeight != y.fontWeight) return true;
            if (x.fontStyle != y.fontStyle) return true;
            if (!java.util.Objects.equals(x.fontVariant, y.fontVariant)) return true;
            if (!java.util.Objects.equals(x.fontKerning, y.fontKerning)) return true;
            if (x.lineHeightKind != y.lineHeightKind || Float.floatToIntBits(x.lineHeightValue) != Float.floatToIntBits(y.lineHeightValue)) return true;
            if (!CssLength.same(x.letterSpacing, x.exprs, y.letterSpacing, y.exprs)) return true;
            if (!CssLength.same(x.wordSpacing, x.exprs, y.wordSpacing, y.exprs)) return true;
            if (!CssLength.same(x.textIndent, x.exprs, y.textIndent, y.exprs)) return true;
            if (x.textAlign != y.textAlign) return true;
            if (!java.util.Objects.equals(x.textAlignLast, y.textAlignLast)) return true;
            if (!java.util.Objects.equals(x.textJustify, y.textJustify)) return true;
            if (!java.util.Objects.equals(x.tabSize, y.tabSize)) return true;
            if (!java.util.Objects.equals(x.textWrap, y.textWrap)) return true;
            if (!java.util.Objects.equals(x.textWrapStyle, y.textWrapStyle)) return true;
            if (x.textTransform != y.textTransform) return true;
            if (!java.util.Objects.equals(x.whiteSpace, y.whiteSpace)) return true;
            if (!java.util.Objects.equals(x.wordBreak, y.wordBreak)) return true;
            if (x.overflowWrap != y.overflowWrap) return true;
            if (!java.util.Objects.equals(x.direction, y.direction)) return true;
            if (!java.util.Objects.equals(x.writingMode, y.writingMode)) return true;
            if (!java.util.Objects.equals(x.textOrientation, y.textOrientation)) return true;
        }
        if (a.box != b.box) {
            BoxGeomStyle x = a.box, y = b.box;
            if (!java.util.Objects.equals(x.display, y.display)) return true;
            if (x.position != y.position) return true;
            if (!CssLength.same(x.top, x.exprs, y.top, y.exprs)) return true;
            if (!CssLength.same(x.right, x.exprs, y.right, y.exprs)) return true;
            if (!CssLength.same(x.bottom, x.exprs, y.bottom, y.exprs)) return true;
            if (!CssLength.same(x.left, x.exprs, y.left, y.exprs)) return true;
            if (x.floatValue != y.floatValue) return true;
            if (x.clear != y.clear) return true;
            if (!CssLength.same(x.width, x.exprs, y.width, y.exprs)) return true;
            if (!CssLength.same(x.height, x.exprs, y.height, y.exprs)) return true;
            if (!CssLength.same(x.minWidth, x.exprs, y.minWidth, y.exprs)) return true;
            if (!CssLength.same(x.minHeight, x.exprs, y.minHeight, y.exprs)) return true;
            if (!CssLength.same(x.maxWidth, x.exprs, y.maxWidth, y.exprs)) return true;
            if (!CssLength.same(x.maxHeight, x.exprs, y.maxHeight, y.exprs)) return true;
            if (x.boxSizing != y.boxSizing) return true;
            if (!java.util.Objects.equals(x.aspectRatio, y.aspectRatio)) return true;
            if (!CssLength.same(x.marginTop, x.exprs, y.marginTop, y.exprs)) return true;
            if (!CssLength.same(x.marginRight, x.exprs, y.marginRight, y.exprs)) return true;
            if (!CssLength.same(x.marginBottom, x.exprs, y.marginBottom, y.exprs)) return true;
            if (!CssLength.same(x.marginLeft, x.exprs, y.marginLeft, y.exprs)) return true;
            if (!CssLength.same(x.paddingTop, x.exprs, y.paddingTop, y.exprs)) return true;
            if (!CssLength.same(x.paddingRight, x.exprs, y.paddingRight, y.exprs)) return true;
            if (!CssLength.same(x.paddingBottom, x.exprs, y.paddingBottom, y.exprs)) return true;
            if (!CssLength.same(x.paddingLeft, x.exprs, y.paddingLeft, y.exprs)) return true;
            if (!CssLength.same(x.borderTopWidth, x.exprs, y.borderTopWidth, y.exprs)) return true;
            if (x.borderTopStyle != y.borderTopStyle) return true;
            if (!CssLength.same(x.borderRightWidth, x.exprs, y.borderRightWidth, y.exprs)) return true;
            if (x.borderRightStyle != y.borderRightStyle) return true;
            if (!CssLength.same(x.borderBottomWidth, x.exprs, y.borderBottomWidth, y.exprs)) return true;
            if (x.borderBottomStyle != y.borderBottomStyle) return true;
            if (!CssLength.same(x.borderLeftWidth, x.exprs, y.borderLeftWidth, y.exprs)) return true;
            if (x.borderLeftStyle != y.borderLeftStyle) return true;
            if (x.overflowX != y.overflowX) return true;
            if (x.overflowY != y.overflowY) return true;
            if (x.scrollbarWidth != y.scrollbarWidth) return true;
            if (!CssLength.same(x.verticalAlign, x.exprs, y.verticalAlign, y.exprs)) return true;
            if (x.textOverflow != y.textOverflow) return true;
        }
        if (a.visual != b.visual) {
            VisualStyle x = a.visual, y = b.visual;
            if (!java.util.Objects.equals(x.rotate, y.rotate)) return true;
            if (!java.util.Objects.equals(x.translate, y.translate)) return true;
            if (!java.util.Objects.equals(x.scale, y.scale)) return true;
        }
        if (a.flexGrid != b.flexGrid) {
            FlexGridStyle x = a.flexGrid, y = b.flexGrid;
            if (x.flexDirection != y.flexDirection) return true;
            if (x.flexWrap != y.flexWrap) return true;
            if (Float.floatToIntBits(x.flexGrow) != Float.floatToIntBits(y.flexGrow)) return true;
            if (Float.floatToIntBits(x.flexShrink) != Float.floatToIntBits(y.flexShrink)) return true;
            if (!CssLength.same(x.flexBasis, x.exprs, y.flexBasis, y.exprs)) return true;
            if (Float.floatToIntBits(x.order) != Float.floatToIntBits(y.order)) return true;
            if (x.justifyContent != y.justifyContent || x.justifyContentSafe != y.justifyContentSafe) return true;
            if (x.alignContent != y.alignContent || x.alignContentSafe != y.alignContentSafe) return true;
            if (x.justifyItems != y.justifyItems) return true;
            if (x.alignItems != y.alignItems) return true;
            if (x.justifySelf != y.justifySelf) return true;
            if (x.alignSelf != y.alignSelf) return true;
            if (!CssLength.same(x.rowGap, x.exprs, y.rowGap, y.exprs)) return true;
            if (!CssLength.same(x.columnGap, x.exprs, y.columnGap, y.exprs)) return true;
            if (!java.util.Objects.equals(x.gridTemplateColumns, y.gridTemplateColumns)) return true;
            if (!java.util.Objects.equals(x.gridTemplateRows, y.gridTemplateRows)) return true;
            if (!java.util.Objects.equals(x.gridTemplateAreas, y.gridTemplateAreas)) return true;
            if (!java.util.Objects.equals(x.gridAutoColumns, y.gridAutoColumns)) return true;
            if (!java.util.Objects.equals(x.gridAutoRows, y.gridAutoRows)) return true;
            if (!java.util.Objects.equals(x.gridAutoFlow, y.gridAutoFlow)) return true;
            if (!java.util.Objects.equals(x.gridColumnStart, y.gridColumnStart)) return true;
            if (!java.util.Objects.equals(x.gridColumnEnd, y.gridColumnEnd)) return true;
            if (!java.util.Objects.equals(x.gridRowStart, y.gridRowStart)) return true;
            if (!java.util.Objects.equals(x.gridRowEnd, y.gridRowEnd)) return true;
            if (!java.util.Objects.equals(x.gridColumn, y.gridColumn)) return true;
            if (!java.util.Objects.equals(x.gridRow, y.gridRow)) return true;
            if (!java.util.Objects.equals(x.gridArea, y.gridArea)) return true;
        }
        if (a.table != b.table) {
            TableStyle x = a.table, y = b.table;
            if (x.tableLayout != y.tableLayout) return true;
        }
        if (a.rare != b.rare) {
            RareStyle x = a.rare, y = b.rare;
            if (Float.floatToIntBits(x.columnCount) != Float.floatToIntBits(y.columnCount)) return true;
            if (!CssLength.same(x.columnWidth, x.exprs, y.columnWidth, y.exprs)) return true;
            if (!java.util.Objects.equals(x.columnFill, y.columnFill)) return true;
            if (!java.util.Objects.equals(x.columnSpan, y.columnSpan)) return true;
            if (x.columnRuleStyle != y.columnRuleStyle) return true;
            if (!CssLength.same(x.columnRuleWidth, x.exprs, y.columnRuleWidth, y.exprs)) return true;
            if (!java.util.Objects.equals(x.appearance, y.appearance)) return true;
            if (!java.util.Objects.equals(x.contain, y.contain)) return true;
            if (x.contentVisibility != y.contentVisibility) return true;
            if (!java.util.Objects.equals(x.containIntrinsicWidth, y.containIntrinsicWidth)) return true;
            if (!java.util.Objects.equals(x.containIntrinsicHeight, y.containIntrinsicHeight)) return true;
            if (x.containerType != y.containerType) return true;
            if (!java.util.Objects.equals(x.containerName, y.containerName)) return true;
            if (!java.util.Objects.equals(x.anchorName, y.anchorName)) return true;
            if (!java.util.Objects.equals(x.positionAnchor, y.positionAnchor)) return true;
            if (!java.util.Objects.equals(x.positionArea, y.positionArea)) return true;
            if (!java.util.Objects.equals(x.positionTryFallbacks, y.positionTryFallbacks)) return true;
            if (!java.util.Objects.equals(x.positionTryOrder, y.positionTryOrder)) return true;
            if (!java.util.Objects.equals(x.content, y.content)) return true;
            if (!java.util.Objects.equals(x.counterReset, y.counterReset)) return true;
            if (!java.util.Objects.equals(x.counterIncrement, y.counterIncrement)) return true;
            if (!java.util.Objects.equals(x.counterSet, y.counterSet)) return true;
            if (!java.util.Objects.equals(x.src, y.src)) return true;
            if (!java.util.Objects.equals(x.objectFit, y.objectFit)) return true;
            if (!java.util.Objects.equals(x.breakInside, y.breakInside)) return true;
            if (!java.util.Objects.equals(x.perspective, y.perspective)) return true;
        }
        return !java.util.Objects.equals(a.custom, b.custom);
    }

    static int positionOnlyAxes(Style a, Style b) {
        if (a == b) return 0;
        if (a.inherited != b.inherited) {
            InheritedStyle x = a.inherited, y = b.inherited;
            if (x.borderCollapse != y.borderCollapse) return -1;
            if (!java.util.Objects.equals(x.borderSpacing, y.borderSpacing)) return -1;
            if (x.captionSide != y.captionSide) return -1;
            if (!java.util.Objects.equals(x.listStyleType, y.listStyleType)) return -1;
            if (x.listStylePosition != y.listStylePosition) return -1;
            if (!java.util.Objects.equals(x.listStyleImage, y.listStyleImage)) return -1;
            if (!java.util.Objects.equals(x.fontFamily, y.fontFamily)) return -1;
            if (Float.floatToIntBits(x.fontSize) != Float.floatToIntBits(y.fontSize)) return -1;
            if (x.fontWeight != y.fontWeight) return -1;
            if (x.fontStyle != y.fontStyle) return -1;
            if (!java.util.Objects.equals(x.fontVariant, y.fontVariant)) return -1;
            if (!java.util.Objects.equals(x.fontKerning, y.fontKerning)) return -1;
            if (x.lineHeightKind != y.lineHeightKind || Float.floatToIntBits(x.lineHeightValue) != Float.floatToIntBits(y.lineHeightValue)) return -1;
            if (!CssLength.same(x.letterSpacing, x.exprs, y.letterSpacing, y.exprs)) return -1;
            if (!CssLength.same(x.wordSpacing, x.exprs, y.wordSpacing, y.exprs)) return -1;
            if (!CssLength.same(x.textIndent, x.exprs, y.textIndent, y.exprs)) return -1;
            if (x.textAlign != y.textAlign) return -1;
            if (!java.util.Objects.equals(x.textAlignLast, y.textAlignLast)) return -1;
            if (!java.util.Objects.equals(x.textJustify, y.textJustify)) return -1;
            if (!java.util.Objects.equals(x.tabSize, y.tabSize)) return -1;
            if (!java.util.Objects.equals(x.textWrap, y.textWrap)) return -1;
            if (!java.util.Objects.equals(x.textWrapStyle, y.textWrapStyle)) return -1;
            if (x.textTransform != y.textTransform) return -1;
            if (!java.util.Objects.equals(x.whiteSpace, y.whiteSpace)) return -1;
            if (!java.util.Objects.equals(x.wordBreak, y.wordBreak)) return -1;
            if (x.overflowWrap != y.overflowWrap) return -1;
            if (!java.util.Objects.equals(x.direction, y.direction)) return -1;
            if (!java.util.Objects.equals(x.writingMode, y.writingMode)) return -1;
            if (!java.util.Objects.equals(x.textOrientation, y.textOrientation)) return -1;
        }
        if (a.box != b.box) {
            BoxGeomStyle x = a.box, y = b.box;
            if (!java.util.Objects.equals(x.display, y.display)) return -1;
            if (x.position != y.position) return -1;
            if (x.floatValue != y.floatValue) return -1;
            if (x.clear != y.clear) return -1;
            if (!CssLength.same(x.width, x.exprs, y.width, y.exprs)) return -1;
            if (!CssLength.same(x.height, x.exprs, y.height, y.exprs)) return -1;
            if (!CssLength.same(x.minWidth, x.exprs, y.minWidth, y.exprs)) return -1;
            if (!CssLength.same(x.minHeight, x.exprs, y.minHeight, y.exprs)) return -1;
            if (!CssLength.same(x.maxWidth, x.exprs, y.maxWidth, y.exprs)) return -1;
            if (!CssLength.same(x.maxHeight, x.exprs, y.maxHeight, y.exprs)) return -1;
            if (x.boxSizing != y.boxSizing) return -1;
            if (!java.util.Objects.equals(x.aspectRatio, y.aspectRatio)) return -1;
            if (!CssLength.same(x.marginTop, x.exprs, y.marginTop, y.exprs)) return -1;
            if (!CssLength.same(x.marginRight, x.exprs, y.marginRight, y.exprs)) return -1;
            if (!CssLength.same(x.marginBottom, x.exprs, y.marginBottom, y.exprs)) return -1;
            if (!CssLength.same(x.marginLeft, x.exprs, y.marginLeft, y.exprs)) return -1;
            if (!CssLength.same(x.paddingTop, x.exprs, y.paddingTop, y.exprs)) return -1;
            if (!CssLength.same(x.paddingRight, x.exprs, y.paddingRight, y.exprs)) return -1;
            if (!CssLength.same(x.paddingBottom, x.exprs, y.paddingBottom, y.exprs)) return -1;
            if (!CssLength.same(x.paddingLeft, x.exprs, y.paddingLeft, y.exprs)) return -1;
            if (!CssLength.same(x.borderTopWidth, x.exprs, y.borderTopWidth, y.exprs)) return -1;
            if (x.borderTopStyle != y.borderTopStyle) return -1;
            if (!CssLength.same(x.borderRightWidth, x.exprs, y.borderRightWidth, y.exprs)) return -1;
            if (x.borderRightStyle != y.borderRightStyle) return -1;
            if (!CssLength.same(x.borderBottomWidth, x.exprs, y.borderBottomWidth, y.exprs)) return -1;
            if (x.borderBottomStyle != y.borderBottomStyle) return -1;
            if (!CssLength.same(x.borderLeftWidth, x.exprs, y.borderLeftWidth, y.exprs)) return -1;
            if (x.borderLeftStyle != y.borderLeftStyle) return -1;
            if (x.overflowX != y.overflowX) return -1;
            if (x.overflowY != y.overflowY) return -1;
            if (x.scrollbarWidth != y.scrollbarWidth) return -1;
            if (!CssLength.same(x.verticalAlign, x.exprs, y.verticalAlign, y.exprs)) return -1;
            if (x.textOverflow != y.textOverflow) return -1;
        }
        if (a.visual != b.visual) {
            VisualStyle x = a.visual, y = b.visual;
            if (!java.util.Objects.equals(x.rotate, y.rotate)) return -1;
            if (!java.util.Objects.equals(x.translate, y.translate)) return -1;
            if (!java.util.Objects.equals(x.scale, y.scale)) return -1;
        }
        if (a.flexGrid != b.flexGrid) {
            FlexGridStyle x = a.flexGrid, y = b.flexGrid;
            if (x.flexDirection != y.flexDirection) return -1;
            if (x.flexWrap != y.flexWrap) return -1;
            if (Float.floatToIntBits(x.flexGrow) != Float.floatToIntBits(y.flexGrow)) return -1;
            if (Float.floatToIntBits(x.flexShrink) != Float.floatToIntBits(y.flexShrink)) return -1;
            if (!CssLength.same(x.flexBasis, x.exprs, y.flexBasis, y.exprs)) return -1;
            if (Float.floatToIntBits(x.order) != Float.floatToIntBits(y.order)) return -1;
            if (x.justifyContent != y.justifyContent || x.justifyContentSafe != y.justifyContentSafe) return -1;
            if (x.alignContent != y.alignContent || x.alignContentSafe != y.alignContentSafe) return -1;
            if (x.justifyItems != y.justifyItems) return -1;
            if (x.alignItems != y.alignItems) return -1;
            if (x.justifySelf != y.justifySelf) return -1;
            if (x.alignSelf != y.alignSelf) return -1;
            if (!CssLength.same(x.rowGap, x.exprs, y.rowGap, y.exprs)) return -1;
            if (!CssLength.same(x.columnGap, x.exprs, y.columnGap, y.exprs)) return -1;
            if (!java.util.Objects.equals(x.gridTemplateColumns, y.gridTemplateColumns)) return -1;
            if (!java.util.Objects.equals(x.gridTemplateRows, y.gridTemplateRows)) return -1;
            if (!java.util.Objects.equals(x.gridTemplateAreas, y.gridTemplateAreas)) return -1;
            if (!java.util.Objects.equals(x.gridAutoColumns, y.gridAutoColumns)) return -1;
            if (!java.util.Objects.equals(x.gridAutoRows, y.gridAutoRows)) return -1;
            if (!java.util.Objects.equals(x.gridAutoFlow, y.gridAutoFlow)) return -1;
            if (!java.util.Objects.equals(x.gridColumnStart, y.gridColumnStart)) return -1;
            if (!java.util.Objects.equals(x.gridColumnEnd, y.gridColumnEnd)) return -1;
            if (!java.util.Objects.equals(x.gridRowStart, y.gridRowStart)) return -1;
            if (!java.util.Objects.equals(x.gridRowEnd, y.gridRowEnd)) return -1;
            if (!java.util.Objects.equals(x.gridColumn, y.gridColumn)) return -1;
            if (!java.util.Objects.equals(x.gridRow, y.gridRow)) return -1;
            if (!java.util.Objects.equals(x.gridArea, y.gridArea)) return -1;
        }
        if (a.table != b.table) {
            TableStyle x = a.table, y = b.table;
            if (x.tableLayout != y.tableLayout) return -1;
        }
        if (a.rare != b.rare) {
            RareStyle x = a.rare, y = b.rare;
            if (Float.floatToIntBits(x.columnCount) != Float.floatToIntBits(y.columnCount)) return -1;
            if (!CssLength.same(x.columnWidth, x.exprs, y.columnWidth, y.exprs)) return -1;
            if (!java.util.Objects.equals(x.columnFill, y.columnFill)) return -1;
            if (!java.util.Objects.equals(x.columnSpan, y.columnSpan)) return -1;
            if (x.columnRuleStyle != y.columnRuleStyle) return -1;
            if (!CssLength.same(x.columnRuleWidth, x.exprs, y.columnRuleWidth, y.exprs)) return -1;
            if (!java.util.Objects.equals(x.appearance, y.appearance)) return -1;
            if (!java.util.Objects.equals(x.contain, y.contain)) return -1;
            if (x.contentVisibility != y.contentVisibility) return -1;
            if (!java.util.Objects.equals(x.containIntrinsicWidth, y.containIntrinsicWidth)) return -1;
            if (!java.util.Objects.equals(x.containIntrinsicHeight, y.containIntrinsicHeight)) return -1;
            if (x.containerType != y.containerType) return -1;
            if (!java.util.Objects.equals(x.containerName, y.containerName)) return -1;
            if (!java.util.Objects.equals(x.anchorName, y.anchorName)) return -1;
            if (!java.util.Objects.equals(x.positionAnchor, y.positionAnchor)) return -1;
            if (!java.util.Objects.equals(x.positionArea, y.positionArea)) return -1;
            if (!java.util.Objects.equals(x.positionTryFallbacks, y.positionTryFallbacks)) return -1;
            if (!java.util.Objects.equals(x.positionTryOrder, y.positionTryOrder)) return -1;
            if (!java.util.Objects.equals(x.content, y.content)) return -1;
            if (!java.util.Objects.equals(x.counterReset, y.counterReset)) return -1;
            if (!java.util.Objects.equals(x.counterIncrement, y.counterIncrement)) return -1;
            if (!java.util.Objects.equals(x.counterSet, y.counterSet)) return -1;
            if (!java.util.Objects.equals(x.src, y.src)) return -1;
            if (!java.util.Objects.equals(x.objectFit, y.objectFit)) return -1;
            if (!java.util.Objects.equals(x.breakInside, y.breakInside)) return -1;
            if (!java.util.Objects.equals(x.perspective, y.perspective)) return -1;
        }
        if (!java.util.Objects.equals(a.custom, b.custom)) return -1;
        int axes = 0;
        if (a.box != b.box) {
            BoxGeomStyle x = a.box, y = b.box;
            if (!CssLength.same(x.left, x.exprs, y.left, y.exprs) || !CssLength.same(x.right, x.exprs, y.right, y.exprs)) axes |= 1;
            if (!CssLength.same(x.top, x.exprs, y.top, y.exprs) || !CssLength.same(x.bottom, x.exprs, y.bottom, y.exprs)) axes |= 2;
        }
        return axes;
    }
}
