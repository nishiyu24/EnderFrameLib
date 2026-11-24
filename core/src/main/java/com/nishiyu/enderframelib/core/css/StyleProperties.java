package com.nishiyu.enderframelib.core.css;

/** 生成物（{@code scripts/GenStyleGroups.java}。手で直さない）: {@link Style} から名前で CSS 文字列表現を取り出す。 */
public final class StyleProperties {
    private StyleProperties() {}

    private static final String[] INSPECTABLE = {

        "visibility", "border-collapse", "border-spacing", "caption-side", "empty-cells", "list-style-type", "list-style-position", "list-style-image", 
        "font-family", "font-size", "font-weight", "font-style", "font-variant", "font-kerning", "line-height", "letter-spacing", 
        "word-spacing", "text-indent", "text-align", "text-align-last", "text-justify", "tab-size", "text-wrap", "text-wrap-style", 
        "text-transform", "white-space", "word-break", "overflow-wrap", "direction", "color", "accent-color", "text-decoration", 
        "text-shadow", "-webkit-text-stroke-width", "-webkit-text-stroke-color", "writing-mode", "text-orientation", "cursor", "pointer-events", "display", 
        "position", "top", "right", "bottom", "left", "float", "clear", "z-index", 
        "width", "height", "min-width", "min-height", "max-width", "max-height", "box-sizing", "aspect-ratio", 
        "margin-top", "margin-right", "margin-bottom", "margin-left", "padding-top", "padding-right", "padding-bottom", "padding-left", 
        "border-top-width", "border-top-style", "border-right-width", "border-right-style", "border-bottom-width", "border-bottom-style", "border-left-width", "border-left-style", 
        "overflow-x", "overflow-y", "scrollbar-width", "vertical-align", "text-overflow", "border-top-color", "border-right-color", "border-bottom-color", 
        "border-left-color", "border-top-left-radius", "border-top-right-radius", "border-bottom-right-radius", "border-bottom-left-radius", "outline-color", "outline-style", "outline-width", 
        "outline-offset", "object-position", "background-color", "background-image", "background-position", "background-size", "background-repeat", "background-clip", 
        "background-origin", "background-attachment", "box-shadow", "border-image-source", "border-image-slice", "border-image-width", "border-image-outset", "border-image-repeat", 
        "opacity", "transform", "transform-origin", "rotate", "translate", "scale", "filter", "mix-blend-mode", 
        "isolation", "clip-path", "backdrop-filter", "mask-image", "mask-position", "mask-size", "mask-repeat", "mask-origin", 
        "mask-clip", "mask-mode", "mask-composite", "flex-direction", "flex-wrap", "flex-grow", "flex-shrink", "flex-basis", 
        "order", "justify-content", "align-content", "justify-items", "align-items", "justify-self", "align-self", "row-gap", 
        "column-gap", "grid-template-columns", "grid-template-rows", "grid-template-areas", "grid-auto-columns", "grid-auto-rows", "grid-auto-flow", "grid-column-start", 
        "grid-column-end", "grid-row-start", "grid-row-end", "grid-column", "grid-row", "grid-area", "table-layout", "column-count", 
        "column-width", "column-fill", "column-span", "column-rule-color", "column-rule-style", "column-rule-width", "appearance", "contain", 
        "content-visibility", "contain-intrinsic-width", "contain-intrinsic-height", "container-type", "container-name", "anchor-name", "position-anchor", "position-area", 
        "position-try-fallbacks", "position-try-order", "content", "counter-reset", "counter-increment", "counter-set", "src", "object-fit", 
        "break-inside", "perspective", "user-select", "transition-property", "transition-duration", "transition-delay", "transition-timing-function", "transition", 
        "animation-name", "animation-duration", "animation-delay", "animation-timing-function", "animation-iteration-count", "animation-direction", "animation-fill-mode", "animation"
    };

    private static final byte[] GROUP_BITS = initGroupBits();
    static int groupBit(int id) { return id >= 0 && id < GROUP_BITS.length ? GROUP_BITS[id] : 0; }
    private static byte[] initGroupBits() {
        byte[] b = new byte[ComputedStyle.CssPropertyId.count()];
        b[InheritedStyle.ID_visibility] = 1;
        b[InheritedStyle.ID_borderCollapse] = 1;
        b[InheritedStyle.ID_borderSpacing] = 1;
        b[InheritedStyle.ID_captionSide] = 1;
        b[InheritedStyle.ID_emptyCells] = 1;
        b[InheritedStyle.ID_listStyleType] = 1;
        b[InheritedStyle.ID_listStylePosition] = 1;
        b[InheritedStyle.ID_listStyleImage] = 1;
        b[InheritedStyle.ID_fontFamily] = 1;
        b[InheritedStyle.ID_fontSize] = 1;
        b[InheritedStyle.ID_fontWeight] = 1;
        b[InheritedStyle.ID_fontStyle] = 1;
        b[InheritedStyle.ID_fontVariant] = 1;
        b[InheritedStyle.ID_fontKerning] = 1;
        b[InheritedStyle.ID_lineHeightValue] = 1;
        b[InheritedStyle.ID_letterSpacing] = 1;
        b[InheritedStyle.ID_wordSpacing] = 1;
        b[InheritedStyle.ID_textIndent] = 1;
        b[InheritedStyle.ID_textAlign] = 1;
        b[InheritedStyle.ID_textAlignLast] = 1;
        b[InheritedStyle.ID_textJustify] = 1;
        b[InheritedStyle.ID_tabSize] = 1;
        b[InheritedStyle.ID_textWrap] = 1;
        b[InheritedStyle.ID_textWrapStyle] = 1;
        b[InheritedStyle.ID_textTransform] = 1;
        b[InheritedStyle.ID_whiteSpace] = 1;
        b[InheritedStyle.ID_wordBreak] = 1;
        b[InheritedStyle.ID_overflowWrap] = 1;
        b[InheritedStyle.ID_direction] = 1;
        b[InheritedStyle.ID_color] = 1;
        b[InheritedStyle.ID_accentColor] = 1;
        b[InheritedStyle.ID_textDecoration] = 1;
        b[InheritedStyle.ID_textShadow] = 1;
        b[InheritedStyle.ID_webkitTextStrokeWidth] = 1;
        b[InheritedStyle.ID_webkitTextStrokeColor] = 1;
        b[InheritedStyle.ID_writingMode] = 1;
        b[InheritedStyle.ID_textOrientation] = 1;
        b[InheritedStyle.ID_cursor] = 1;
        b[InheritedStyle.ID_pointerEvents] = 1;
        b[BoxGeomStyle.ID_display] = 2;
        b[BoxGeomStyle.ID_position] = 2;
        b[BoxGeomStyle.ID_top] = 2;
        b[BoxGeomStyle.ID_right] = 2;
        b[BoxGeomStyle.ID_bottom] = 2;
        b[BoxGeomStyle.ID_left] = 2;
        b[BoxGeomStyle.ID_floatValue] = 2;
        b[BoxGeomStyle.ID_clear] = 2;
        b[BoxGeomStyle.ID_zIndex] = 2;
        b[BoxGeomStyle.ID_width] = 2;
        b[BoxGeomStyle.ID_height] = 2;
        b[BoxGeomStyle.ID_minWidth] = 2;
        b[BoxGeomStyle.ID_minHeight] = 2;
        b[BoxGeomStyle.ID_maxWidth] = 2;
        b[BoxGeomStyle.ID_maxHeight] = 2;
        b[BoxGeomStyle.ID_boxSizing] = 2;
        b[BoxGeomStyle.ID_aspectRatio] = 2;
        b[BoxGeomStyle.ID_marginTop] = 2;
        b[BoxGeomStyle.ID_marginRight] = 2;
        b[BoxGeomStyle.ID_marginBottom] = 2;
        b[BoxGeomStyle.ID_marginLeft] = 2;
        b[BoxGeomStyle.ID_paddingTop] = 2;
        b[BoxGeomStyle.ID_paddingRight] = 2;
        b[BoxGeomStyle.ID_paddingBottom] = 2;
        b[BoxGeomStyle.ID_paddingLeft] = 2;
        b[BoxGeomStyle.ID_borderTopWidth] = 2;
        b[BoxGeomStyle.ID_borderTopStyle] = 2;
        b[BoxGeomStyle.ID_borderRightWidth] = 2;
        b[BoxGeomStyle.ID_borderRightStyle] = 2;
        b[BoxGeomStyle.ID_borderBottomWidth] = 2;
        b[BoxGeomStyle.ID_borderBottomStyle] = 2;
        b[BoxGeomStyle.ID_borderLeftWidth] = 2;
        b[BoxGeomStyle.ID_borderLeftStyle] = 2;
        b[BoxGeomStyle.ID_overflowX] = 2;
        b[BoxGeomStyle.ID_overflowY] = 2;
        b[BoxGeomStyle.ID_scrollbarWidth] = 2;
        b[BoxGeomStyle.ID_verticalAlign] = 2;
        b[BoxGeomStyle.ID_textOverflow] = 2;
        b[VisualStyle.ID_borderTopColor] = 4;
        b[VisualStyle.ID_borderRightColor] = 4;
        b[VisualStyle.ID_borderBottomColor] = 4;
        b[VisualStyle.ID_borderLeftColor] = 4;
        b[VisualStyle.ID_borderTopLeftRadius] = 4;
        b[VisualStyle.ID_borderTopRightRadius] = 4;
        b[VisualStyle.ID_borderBottomRightRadius] = 4;
        b[VisualStyle.ID_borderBottomLeftRadius] = 4;
        b[VisualStyle.ID_outlineColor] = 4;
        b[VisualStyle.ID_outlineStyle] = 4;
        b[VisualStyle.ID_outlineWidth] = 4;
        b[VisualStyle.ID_outlineOffset] = 4;
        b[VisualStyle.ID_objectPosition] = 4;
        b[VisualStyle.ID_backgroundColor] = 4;
        b[VisualStyle.ID_backgroundImage] = 4;
        b[VisualStyle.ID_backgroundPosition] = 4;
        b[VisualStyle.ID_backgroundSize] = 4;
        b[VisualStyle.ID_backgroundRepeat] = 4;
        b[VisualStyle.ID_backgroundClip] = 4;
        b[VisualStyle.ID_backgroundOrigin] = 4;
        b[VisualStyle.ID_backgroundAttachment] = 4;
        b[VisualStyle.ID_boxShadow] = 4;
        b[VisualStyle.ID_borderImageSource] = 4;
        b[VisualStyle.ID_borderImageSlice] = 4;
        b[VisualStyle.ID_borderImageWidth] = 4;
        b[VisualStyle.ID_borderImageOutset] = 4;
        b[VisualStyle.ID_borderImageRepeat] = 4;
        b[VisualStyle.ID_opacity] = 4;
        b[VisualStyle.ID_transform] = 4;
        b[VisualStyle.ID_transformOrigin] = 4;
        b[VisualStyle.ID_rotate] = 4;
        b[VisualStyle.ID_translate] = 4;
        b[VisualStyle.ID_scale] = 4;
        b[VisualStyle.ID_filter] = 4;
        b[VisualStyle.ID_mixBlendMode] = 4;
        b[VisualStyle.ID_isolation] = 4;
        b[VisualStyle.ID_clipPath] = 4;
        b[VisualStyle.ID_backdropFilter] = 4;
        b[VisualStyle.ID_maskImage] = 4;
        b[VisualStyle.ID_maskPosition] = 4;
        b[VisualStyle.ID_maskSize] = 4;
        b[VisualStyle.ID_maskRepeat] = 4;
        b[VisualStyle.ID_maskOrigin] = 4;
        b[VisualStyle.ID_maskClip] = 4;
        b[VisualStyle.ID_maskMode] = 4;
        b[VisualStyle.ID_maskComposite] = 4;
        b[FlexGridStyle.ID_flexDirection] = 8;
        b[FlexGridStyle.ID_flexWrap] = 8;
        b[FlexGridStyle.ID_flexGrow] = 8;
        b[FlexGridStyle.ID_flexShrink] = 8;
        b[FlexGridStyle.ID_flexBasis] = 8;
        b[FlexGridStyle.ID_order] = 8;
        b[FlexGridStyle.ID_justifyContent] = 8;
        b[FlexGridStyle.ID_alignContent] = 8;
        b[FlexGridStyle.ID_justifyItems] = 8;
        b[FlexGridStyle.ID_alignItems] = 8;
        b[FlexGridStyle.ID_justifySelf] = 8;
        b[FlexGridStyle.ID_alignSelf] = 8;
        b[FlexGridStyle.ID_rowGap] = 8;
        b[FlexGridStyle.ID_columnGap] = 8;
        b[FlexGridStyle.ID_gridTemplateColumns] = 8;
        b[FlexGridStyle.ID_gridTemplateRows] = 8;
        b[FlexGridStyle.ID_gridTemplateAreas] = 8;
        b[FlexGridStyle.ID_gridAutoColumns] = 8;
        b[FlexGridStyle.ID_gridAutoRows] = 8;
        b[FlexGridStyle.ID_gridAutoFlow] = 8;
        b[FlexGridStyle.ID_gridColumnStart] = 8;
        b[FlexGridStyle.ID_gridColumnEnd] = 8;
        b[FlexGridStyle.ID_gridRowStart] = 8;
        b[FlexGridStyle.ID_gridRowEnd] = 8;
        b[FlexGridStyle.ID_gridColumn] = 8;
        b[FlexGridStyle.ID_gridRow] = 8;
        b[FlexGridStyle.ID_gridArea] = 8;
        b[TableStyle.ID_tableLayout] = 16;
        b[RareStyle.ID_columnCount] = 32;
        b[RareStyle.ID_columnWidth] = 32;
        b[RareStyle.ID_columnFill] = 32;
        b[RareStyle.ID_columnSpan] = 32;
        b[RareStyle.ID_columnRuleColor] = 32;
        b[RareStyle.ID_columnRuleStyle] = 32;
        b[RareStyle.ID_columnRuleWidth] = 32;
        b[RareStyle.ID_appearance] = 32;
        b[RareStyle.ID_contain] = 32;
        b[RareStyle.ID_contentVisibility] = 32;
        b[RareStyle.ID_containIntrinsicWidth] = 32;
        b[RareStyle.ID_containIntrinsicHeight] = 32;
        b[RareStyle.ID_containerType] = 32;
        b[RareStyle.ID_containerName] = 32;
        b[RareStyle.ID_anchorName] = 32;
        b[RareStyle.ID_positionAnchor] = 32;
        b[RareStyle.ID_positionArea] = 32;
        b[RareStyle.ID_positionTryFallbacks] = 32;
        b[RareStyle.ID_positionTryOrder] = 32;
        b[RareStyle.ID_content] = 32;
        b[RareStyle.ID_counterReset] = 32;
        b[RareStyle.ID_counterIncrement] = 32;
        b[RareStyle.ID_counterSet] = 32;
        b[RareStyle.ID_src] = 32;
        b[RareStyle.ID_objectFit] = 32;
        b[RareStyle.ID_breakInside] = 32;
        b[RareStyle.ID_perspective] = 32;
        b[RareStyle.ID_userSelect] = 32;
        b[RareStyle.ID_transitionProperty] = 32;
        b[RareStyle.ID_transitionDuration] = 32;
        b[RareStyle.ID_transitionDelay] = 32;
        b[RareStyle.ID_transitionTimingFunction] = 32;
        b[RareStyle.ID_transition] = 32;
        b[RareStyle.ID_animationName] = 32;
        b[RareStyle.ID_animationDuration] = 32;
        b[RareStyle.ID_animationDelay] = 32;
        b[RareStyle.ID_animationTimingFunction] = 32;
        b[RareStyle.ID_animationIterationCount] = 32;
        b[RareStyle.ID_animationDirection] = 32;
        b[RareStyle.ID_animationFillMode] = 32;
        b[RareStyle.ID_animation] = 32;
        return b;
    }

    public static String get(Style s, String name) {
        if (s == null || name == null) return null;
        return switch (name) {
            case "visibility" -> s.inherited.visibility != null ? s.inherited.visibility.cssName() : null;
            case "border-collapse" -> s.inherited.borderCollapse != null ? s.inherited.borderCollapse.cssName() : null;
            case "border-spacing" -> s.inherited.borderSpacing;
            case "caption-side" -> s.inherited.captionSide != null ? s.inherited.captionSide.cssName() : null;
            case "empty-cells" -> s.inherited.emptyCells;
            case "list-style-type" -> s.inherited.listStyleType;
            case "list-style-position" -> s.inherited.listStylePosition != null ? s.inherited.listStylePosition.cssName() : null;
            case "list-style-image" -> s.inherited.listStyleImage;
            case "font-family" -> s.inherited.fontFamily;
            case "font-size" -> px(s.inherited.fontSize);
            case "font-weight" -> s.inherited.fontWeight == 700 ? "bold" : s.inherited.fontWeight == 400 ? "normal" : Integer.toString(s.inherited.fontWeight);
            case "font-style" -> s.inherited.fontStyle != null ? s.inherited.fontStyle.cssName() : null;
            case "font-variant" -> s.inherited.fontVariant;
            case "font-kerning" -> s.inherited.fontKerning;
            case "line-height" -> lh(s.inherited.lineHeightKind, s.inherited.lineHeightValue);
            case "letter-spacing" -> CssLength.toCssString(s.inherited.letterSpacing, s.inherited.exprs);
            case "word-spacing" -> CssLength.toCssString(s.inherited.wordSpacing, s.inherited.exprs);
            case "text-indent" -> CssLength.toCssString(s.inherited.textIndent, s.inherited.exprs);
            case "text-align" -> s.inherited.textAlign != null ? s.inherited.textAlign.cssName() : null;
            case "text-align-last" -> s.inherited.textAlignLast;
            case "text-justify" -> s.inherited.textJustify;
            case "tab-size" -> s.inherited.tabSize;
            case "text-wrap" -> s.inherited.textWrap;
            case "text-wrap-style" -> s.inherited.textWrapStyle;
            case "text-transform" -> s.inherited.textTransform != null ? s.inherited.textTransform.cssName() : null;
            case "white-space" -> s.inherited.whiteSpace;
            case "word-break" -> s.inherited.wordBreak;
            case "overflow-wrap" -> s.inherited.overflowWrap != null ? s.inherited.overflowWrap.cssName() : null;
            case "direction" -> s.inherited.direction;
            case "color" -> s.inherited.hasColor ? col(s.inherited.color) : null;
            case "accent-color" -> s.inherited.accentColor;
            case "text-decoration" -> s.inherited.textDecoration;
            case "text-shadow" -> s.inherited.textShadow;
            case "-webkit-text-stroke-width" -> s.inherited.webkitTextStrokeWidth;
            case "-webkit-text-stroke-color" -> s.inherited.webkitTextStrokeColor;
            case "writing-mode" -> s.inherited.writingMode;
            case "text-orientation" -> s.inherited.textOrientation;
            case "cursor" -> s.inherited.cursor;
            case "pointer-events" -> s.inherited.pointerEvents != null ? s.inherited.pointerEvents.cssName() : null;
            case "display" -> s.box.display;
            case "position" -> s.box.position != null ? s.box.position.cssName() : null;
            case "top" -> CssLength.toCssString(s.box.top, s.box.exprs);
            case "right" -> CssLength.toCssString(s.box.right, s.box.exprs);
            case "bottom" -> CssLength.toCssString(s.box.bottom, s.box.exprs);
            case "left" -> CssLength.toCssString(s.box.left, s.box.exprs);
            case "float" -> s.box.floatValue != null ? s.box.floatValue.cssName() : null;
            case "clear" -> s.box.clear != null ? s.box.clear.cssName() : null;
            case "z-index" -> num(s.box.zIndex);
            case "width" -> CssLength.toCssString(s.box.width, s.box.exprs);
            case "height" -> CssLength.toCssString(s.box.height, s.box.exprs);
            case "min-width" -> CssLength.toCssString(s.box.minWidth, s.box.exprs);
            case "min-height" -> CssLength.toCssString(s.box.minHeight, s.box.exprs);
            case "max-width" -> CssLength.toCssString(s.box.maxWidth, s.box.exprs);
            case "max-height" -> CssLength.toCssString(s.box.maxHeight, s.box.exprs);
            case "box-sizing" -> s.box.boxSizing != null ? s.box.boxSizing.cssName() : null;
            case "aspect-ratio" -> s.box.aspectRatio;
            case "margin-top" -> CssLength.toCssString(s.box.marginTop, s.box.exprs);
            case "margin-right" -> CssLength.toCssString(s.box.marginRight, s.box.exprs);
            case "margin-bottom" -> CssLength.toCssString(s.box.marginBottom, s.box.exprs);
            case "margin-left" -> CssLength.toCssString(s.box.marginLeft, s.box.exprs);
            case "padding-top" -> CssLength.toCssString(s.box.paddingTop, s.box.exprs);
            case "padding-right" -> CssLength.toCssString(s.box.paddingRight, s.box.exprs);
            case "padding-bottom" -> CssLength.toCssString(s.box.paddingBottom, s.box.exprs);
            case "padding-left" -> CssLength.toCssString(s.box.paddingLeft, s.box.exprs);
            case "border-top-width" -> CssLength.toCssString(s.box.borderTopWidth, s.box.exprs);
            case "border-top-style" -> s.box.borderTopStyle != null ? s.box.borderTopStyle.cssName() : null;
            case "border-right-width" -> CssLength.toCssString(s.box.borderRightWidth, s.box.exprs);
            case "border-right-style" -> s.box.borderRightStyle != null ? s.box.borderRightStyle.cssName() : null;
            case "border-bottom-width" -> CssLength.toCssString(s.box.borderBottomWidth, s.box.exprs);
            case "border-bottom-style" -> s.box.borderBottomStyle != null ? s.box.borderBottomStyle.cssName() : null;
            case "border-left-width" -> CssLength.toCssString(s.box.borderLeftWidth, s.box.exprs);
            case "border-left-style" -> s.box.borderLeftStyle != null ? s.box.borderLeftStyle.cssName() : null;
            case "overflow-x" -> s.box.overflowX != null ? s.box.overflowX.cssName() : null;
            case "overflow-y" -> s.box.overflowY != null ? s.box.overflowY.cssName() : null;
            case "scrollbar-width" -> s.box.scrollbarWidth != null ? s.box.scrollbarWidth.cssName() : null;
            case "vertical-align" -> CssLength.toCssString(s.box.verticalAlign, s.box.exprs);
            case "text-overflow" -> s.box.textOverflow != null ? s.box.textOverflow.cssName() : null;
            case "border-top-color" -> (s.visual.currentColorMask & 1) != 0 ? null : col(s.visual.borderTopColor);
            case "border-right-color" -> (s.visual.currentColorMask & 2) != 0 ? null : col(s.visual.borderRightColor);
            case "border-bottom-color" -> (s.visual.currentColorMask & 4) != 0 ? null : col(s.visual.borderBottomColor);
            case "border-left-color" -> (s.visual.currentColorMask & 8) != 0 ? null : col(s.visual.borderLeftColor);
            case "border-top-left-radius" -> s.visual.borderTopLeftRadius;
            case "border-top-right-radius" -> s.visual.borderTopRightRadius;
            case "border-bottom-right-radius" -> s.visual.borderBottomRightRadius;
            case "border-bottom-left-radius" -> s.visual.borderBottomLeftRadius;
            case "outline-color" -> (s.visual.currentColorMask & 32) != 0 ? "invert" : ((s.visual.currentColorMask & 16) != 0 ? null : col(s.visual.outlineColor));
            case "outline-style" -> s.visual.outlineStyle != null ? s.visual.outlineStyle.cssName() : null;
            case "outline-width" -> CssLength.toCssString(s.visual.outlineWidth, s.visual.exprs);
            case "outline-offset" -> CssLength.toCssString(s.visual.outlineOffset, s.visual.exprs);
            case "object-position" -> s.visual.objectPosition;
            case "background-color" -> s.visual.hasBackgroundColor ? col(s.visual.backgroundColor) : null;
            case "background-image" -> s.visual.backgroundImage;
            case "background-position" -> s.visual.backgroundPosition;
            case "background-size" -> s.visual.backgroundSize;
            case "background-repeat" -> s.visual.backgroundRepeat;
            case "background-clip" -> s.visual.backgroundClip;
            case "background-origin" -> s.visual.backgroundOrigin;
            case "background-attachment" -> s.visual.backgroundAttachment;
            case "box-shadow" -> s.visual.boxShadow;
            case "border-image-source" -> s.visual.borderImageSource;
            case "border-image-slice" -> s.visual.borderImageSlice;
            case "border-image-width" -> s.visual.borderImageWidth;
            case "border-image-outset" -> s.visual.borderImageOutset;
            case "border-image-repeat" -> s.visual.borderImageRepeat;
            case "opacity" -> num(s.visual.opacity);
            case "transform" -> s.visual.transform;
            case "transform-origin" -> s.visual.transformOrigin;
            case "rotate" -> s.visual.rotate;
            case "translate" -> s.visual.translate;
            case "scale" -> s.visual.scale;
            case "filter" -> s.visual.filter;
            case "mix-blend-mode" -> s.visual.mixBlendMode;
            case "isolation" -> s.visual.isolation != null ? s.visual.isolation.cssName() : null;
            case "clip-path" -> s.visual.clipPath;
            case "backdrop-filter" -> s.visual.backdropFilter;
            case "mask-image" -> s.visual.maskImage;
            case "mask-position" -> s.visual.maskPosition;
            case "mask-size" -> s.visual.maskSize;
            case "mask-repeat" -> s.visual.maskRepeat;
            case "mask-origin" -> s.visual.maskOrigin;
            case "mask-clip" -> s.visual.maskClip;
            case "mask-mode" -> s.visual.maskMode;
            case "mask-composite" -> s.visual.maskComposite;
            case "flex-direction" -> s.flexGrid.flexDirection != null ? s.flexGrid.flexDirection.cssName() : null;
            case "flex-wrap" -> s.flexGrid.flexWrap != null ? s.flexGrid.flexWrap.cssName() : null;
            case "flex-grow" -> num(s.flexGrid.flexGrow);
            case "flex-shrink" -> num(s.flexGrid.flexShrink);
            case "flex-basis" -> CssLength.toCssString(s.flexGrid.flexBasis, s.flexGrid.exprs);
            case "order" -> num(s.flexGrid.order);
            case "justify-content" -> (s.flexGrid.justifyContentSafe ? "safe " : "") + s.flexGrid.justifyContent.cssName();
            case "align-content" -> (s.flexGrid.alignContentSafe ? "safe " : "") + s.flexGrid.alignContent.cssName();
            case "justify-items" -> s.flexGrid.justifyItems != null ? s.flexGrid.justifyItems.cssName() : null;
            case "align-items" -> s.flexGrid.alignItems != null ? s.flexGrid.alignItems.cssName() : null;
            case "justify-self" -> s.flexGrid.justifySelf != null ? s.flexGrid.justifySelf.cssName() : null;
            case "align-self" -> s.flexGrid.alignSelf != null ? s.flexGrid.alignSelf.cssName() : null;
            case "row-gap" -> CssLength.toCssString(s.flexGrid.rowGap, s.flexGrid.exprs);
            case "column-gap" -> CssLength.toCssString(s.flexGrid.columnGap, s.flexGrid.exprs);
            case "grid-template-columns" -> s.flexGrid.gridTemplateColumns;
            case "grid-template-rows" -> s.flexGrid.gridTemplateRows;
            case "grid-template-areas" -> s.flexGrid.gridTemplateAreas;
            case "grid-auto-columns" -> s.flexGrid.gridAutoColumns;
            case "grid-auto-rows" -> s.flexGrid.gridAutoRows;
            case "grid-auto-flow" -> s.flexGrid.gridAutoFlow;
            case "grid-column-start" -> s.flexGrid.gridColumnStart;
            case "grid-column-end" -> s.flexGrid.gridColumnEnd;
            case "grid-row-start" -> s.flexGrid.gridRowStart;
            case "grid-row-end" -> s.flexGrid.gridRowEnd;
            case "grid-column" -> s.flexGrid.gridColumn;
            case "grid-row" -> s.flexGrid.gridRow;
            case "grid-area" -> s.flexGrid.gridArea;
            case "table-layout" -> s.table.tableLayout != null ? s.table.tableLayout.cssName() : null;
            case "column-count" -> num(s.rare.columnCount);
            case "column-width" -> CssLength.toCssString(s.rare.columnWidth, s.rare.exprs);
            case "column-fill" -> s.rare.columnFill;
            case "column-span" -> s.rare.columnSpan;
            case "column-rule-color" -> s.rare.columnRuleColorCurrent ? null : col(s.rare.columnRuleColor);
            case "column-rule-style" -> s.rare.columnRuleStyle != null ? s.rare.columnRuleStyle.cssName() : null;
            case "column-rule-width" -> CssLength.toCssString(s.rare.columnRuleWidth, s.rare.exprs);
            case "appearance" -> s.rare.appearance;
            case "contain" -> s.rare.contain;
            case "content-visibility" -> s.rare.contentVisibility != null ? s.rare.contentVisibility.cssName() : null;
            case "contain-intrinsic-width" -> s.rare.containIntrinsicWidth;
            case "contain-intrinsic-height" -> s.rare.containIntrinsicHeight;
            case "container-type" -> s.rare.containerType != null ? s.rare.containerType.cssName() : null;
            case "container-name" -> s.rare.containerName;
            case "anchor-name" -> s.rare.anchorName;
            case "position-anchor" -> s.rare.positionAnchor;
            case "position-area" -> s.rare.positionArea;
            case "position-try-fallbacks" -> s.rare.positionTryFallbacks;
            case "position-try-order" -> s.rare.positionTryOrder;
            case "content" -> s.rare.content;
            case "counter-reset" -> s.rare.counterReset;
            case "counter-increment" -> s.rare.counterIncrement;
            case "counter-set" -> s.rare.counterSet;
            case "src" -> s.rare.src;
            case "object-fit" -> s.rare.objectFit;
            case "break-inside" -> s.rare.breakInside;
            case "perspective" -> s.rare.perspective;
            case "user-select" -> s.rare.userSelect;
            case "transition-property" -> s.rare.transitionProperty;
            case "transition-duration" -> s.rare.transitionDuration;
            case "transition-delay" -> s.rare.transitionDelay;
            case "transition-timing-function" -> s.rare.transitionTimingFunction;
            case "transition" -> s.rare.transition;
            case "animation-name" -> s.rare.animationName;
            case "animation-duration" -> s.rare.animationDuration;
            case "animation-delay" -> s.rare.animationDelay;
            case "animation-timing-function" -> s.rare.animationTimingFunction;
            case "animation-iteration-count" -> s.rare.animationIterationCount;
            case "animation-direction" -> s.rare.animationDirection;
            case "animation-fill-mode" -> s.rare.animationFillMode;
            case "animation" -> s.rare.animation;
            default -> s.custom != null ? s.custom.get(name) : null;
        };
    }

    public static java.util.Map<String, String> toMap(Style s) {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        if (s == null) return m;
        String[] defs = DefaultsHolder.VALS;
        for (int i = 0; i < INSPECTABLE.length; i++) {
            String v = get(s, INSPECTABLE[i]);
            if (v != null && !v.equals(defs[i])) m.put(INSPECTABLE[i], v);
        }
        if (s.custom != null) m.putAll(s.custom.asMap());
        return m;
    }

    private static final class DefaultsHolder {
        static final String[] VALS = init();
        private static String[] init() {
            String[] d = new String[INSPECTABLE.length];
            for (int i = 0; i < d.length; i++) d[i] = get(Style.DEFAULT, INSPECTABLE[i]);
            return d;
        }
    }

    private static String num(float v) { return Float.isNaN(v) ? null : v == (long) v ? Long.toString((long) v) : Float.toString(v); }
    private static String px(float v) { return num(v) + "px"; }
    private static String lh(byte k, float v) {
        return switch (k) {
            case ComputedStyle.LH_NORMAL -> "normal";
            case ComputedStyle.LH_PX -> px(v);
            case ComputedStyle.LH_PERCENT -> num(v * 100f) + "%";
            case ComputedStyle.LH_EM -> num(v) + "em";
            default -> num(v);
        };
    }
    private static String col(int argb) {
        int a = (argb >>> 24) & 0xFF, r = (argb >>> 16) & 0xFF, g = (argb >>> 8) & 0xFF, b = argb & 0xFF;
        if (a == 0xFF) return CssNumbers.hexRgb(r, g, b);
        return argb == 0 ? "transparent" : "rgba(" + r + "," + g + "," + b + "," + CssNumbers.trimmed4(a / 255.0) + ")";
    }

    public static void inheritInto(Style parent, StyleTable into) {
        if (parent == null || parent == Style.DEFAULT) return;
        InheritedStyle inh = parent.inherited;
        if (inh != InheritedStyle.DEFAULT) {
            if (inh.visibility != InheritedStyle.DEFAULT.visibility && inh.visibility != null) into.putById(InheritedStyle.ID_visibility, inh.visibility.cssName());
            if (inh.borderCollapse != InheritedStyle.DEFAULT.borderCollapse && inh.borderCollapse != null) into.putById(InheritedStyle.ID_borderCollapse, inh.borderCollapse.cssName());
            if (inh.borderSpacing != null) into.putById(InheritedStyle.ID_borderSpacing, inh.borderSpacing);
            if (inh.captionSide != InheritedStyle.DEFAULT.captionSide && inh.captionSide != null) into.putById(InheritedStyle.ID_captionSide, inh.captionSide.cssName());
            if (inh.emptyCells != null) into.putById(InheritedStyle.ID_emptyCells, inh.emptyCells);
            if (inh.listStyleType != null) into.putById(InheritedStyle.ID_listStyleType, inh.listStyleType);
            if (inh.listStylePosition != InheritedStyle.DEFAULT.listStylePosition && inh.listStylePosition != null) into.putById(InheritedStyle.ID_listStylePosition, inh.listStylePosition.cssName());
            if (inh.listStyleImage != null) into.putById(InheritedStyle.ID_listStyleImage, inh.listStyleImage);
            if (inh.fontFamily != null) into.putById(InheritedStyle.ID_fontFamily, inh.fontFamily);
            if (Float.floatToIntBits(inh.fontSize) != Float.floatToIntBits(InheritedStyle.DEFAULT.fontSize)) into.putById(InheritedStyle.ID_fontSize, px(inh.fontSize));
            if (inh.fontWeight != 400) into.putById(InheritedStyle.ID_fontWeight, inh.fontWeight == 700 ? "bold" : Integer.toString(inh.fontWeight));
            if (inh.fontStyle != InheritedStyle.DEFAULT.fontStyle && inh.fontStyle != null) into.putById(InheritedStyle.ID_fontStyle, inh.fontStyle.cssName());
            if (inh.fontVariant != null) into.putById(InheritedStyle.ID_fontVariant, inh.fontVariant);
            if (inh.fontKerning != null) into.putById(InheritedStyle.ID_fontKerning, inh.fontKerning);
            if (inh.lineHeightKind != InheritedStyle.DEFAULT.lineHeightKind || Float.floatToIntBits(inh.lineHeightValue) != Float.floatToIntBits(InheritedStyle.DEFAULT.lineHeightValue)) into.putById(InheritedStyle.ID_lineHeightValue, lh(inh.lineHeightKind, inh.lineHeightValue));
            if (CssLength.isDeclared(inh.letterSpacing)) into.putById(InheritedStyle.ID_letterSpacing, CssLength.toCssString(inh.letterSpacing, inh.exprs));
            if (CssLength.isDeclared(inh.wordSpacing)) into.putById(InheritedStyle.ID_wordSpacing, CssLength.toCssString(inh.wordSpacing, inh.exprs));
            if (CssLength.isDeclared(inh.textIndent)) into.putById(InheritedStyle.ID_textIndent, CssLength.toCssString(inh.textIndent, inh.exprs));
            if (inh.textAlign != InheritedStyle.DEFAULT.textAlign && inh.textAlign != null) into.putById(InheritedStyle.ID_textAlign, inh.textAlign.cssName());
            if (inh.textAlignLast != null) into.putById(InheritedStyle.ID_textAlignLast, inh.textAlignLast);
            if (inh.textJustify != null) into.putById(InheritedStyle.ID_textJustify, inh.textJustify);
            if (inh.tabSize != null) into.putById(InheritedStyle.ID_tabSize, inh.tabSize);
            if (inh.textWrap != null) into.putById(InheritedStyle.ID_textWrap, inh.textWrap);
            if (inh.textWrapStyle != null) into.putById(InheritedStyle.ID_textWrapStyle, inh.textWrapStyle);
            if (inh.textTransform != InheritedStyle.DEFAULT.textTransform && inh.textTransform != null) into.putById(InheritedStyle.ID_textTransform, inh.textTransform.cssName());
            if (inh.whiteSpace != null) into.putById(InheritedStyle.ID_whiteSpace, inh.whiteSpace);
            if (inh.wordBreak != null) into.putById(InheritedStyle.ID_wordBreak, inh.wordBreak);
            if (inh.overflowWrap != InheritedStyle.DEFAULT.overflowWrap && inh.overflowWrap != null) into.putById(InheritedStyle.ID_overflowWrap, inh.overflowWrap.cssName());
            if (inh.direction != null) into.putById(InheritedStyle.ID_direction, inh.direction);
            if (inh.hasColor) into.putById(InheritedStyle.ID_color, col(inh.color));
            if (inh.accentColor != null) into.putById(InheritedStyle.ID_accentColor, inh.accentColor);
            if (inh.textDecoration != null) into.putById(InheritedStyle.ID_textDecoration, inh.textDecoration);
            if (inh.textShadow != null) into.putById(InheritedStyle.ID_textShadow, inh.textShadow);
            if (inh.webkitTextStrokeWidth != null) into.putById(InheritedStyle.ID_webkitTextStrokeWidth, inh.webkitTextStrokeWidth);
            if (inh.webkitTextStrokeColor != null) into.putById(InheritedStyle.ID_webkitTextStrokeColor, inh.webkitTextStrokeColor);
            if (inh.writingMode != null) into.putById(InheritedStyle.ID_writingMode, inh.writingMode);
            if (inh.textOrientation != null) into.putById(InheritedStyle.ID_textOrientation, inh.textOrientation);
            if (inh.cursor != null) into.putById(InheritedStyle.ID_cursor, inh.cursor);
            if (inh.pointerEvents != InheritedStyle.DEFAULT.pointerEvents && inh.pointerEvents != null) into.putById(InheritedStyle.ID_pointerEvents, inh.pointerEvents.cssName());
        }
        if (parent.custom != null) {
            for (var e : parent.custom.asMap().entrySet()) {
                String k = e.getKey();
                if (k.startsWith("--") || StyleResolver.inheritsAsLonghand(k)) into.put(k, e.getValue());
            }
        }
    }

    /** CSSOM {@code getComputedStyle} 形式（色は {@code rgb(...)} / {@code rgba(...)}）。 */
    public static String getCssom(Style s, String name) {
        if (s == null || name == null) return null;
        return switch (name) {
            case "color" -> s.inherited.hasColor ? rgbCol(s.inherited.color) : null;
            case "background-color" -> s.visual.hasBackgroundColor ? rgbCol(s.visual.backgroundColor) : null;
            case "border-top-color" -> (s.visual.currentColorMask & 1) != 0 ? null : rgbCol(s.visual.borderTopColor);
            case "border-right-color" -> (s.visual.currentColorMask & 2) != 0 ? null : rgbCol(s.visual.borderRightColor);
            case "border-bottom-color" -> (s.visual.currentColorMask & 4) != 0 ? null : rgbCol(s.visual.borderBottomColor);
            case "border-left-color" -> (s.visual.currentColorMask & 8) != 0 ? null : rgbCol(s.visual.borderLeftColor);
            case "outline-color" -> (s.visual.currentColorMask & 32) != 0 ? "invert" : ((s.visual.currentColorMask & 16) != 0 ? null : rgbCol(s.visual.outlineColor));
            case "column-rule-color" -> s.rare.columnRuleColorCurrent ? null : rgbCol(s.rare.columnRuleColor);
            default -> get(s, name);
        };
    }

    private static String rgbCol(int argb) {
        int a = (argb >>> 24) & 0xFF, r = (argb >>> 16) & 0xFF, g = (argb >>> 8) & 0xFF, b = argb & 0xFF;
        if (a == 0xFF) return "rgb(" + r + ", " + g + ", " + b + ")";
        return argb == 0 ? "rgba(0, 0, 0, 0)" : "rgba(" + r + ", " + g + ", " + b + ", " + CssNumbers.trimmed4(a / 255.0) + ")";
    }
}
