package com.nishiyu.enderframelib.core.paint.scene;

import com.nishiyu.enderframelib.core.box.BoxKind;
import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.box.Display;
import com.nishiyu.enderframelib.core.box.DomValues;
import com.nishiyu.enderframelib.core.css.AnimationParser;
import com.nishiyu.enderframelib.core.css.AnimationTiming;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.css.CssSyntax;
import com.nishiyu.enderframelib.core.css.LengthResolver;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.css.VisualStyle;
import com.nishiyu.enderframelib.core.css.model.CssKeywords;
import com.nishiyu.enderframelib.core.css.model.KeyframeBlock;
import com.nishiyu.enderframelib.core.css.model.Keyframes;
import com.nishiyu.enderframelib.core.css.model.Position;
import com.nishiyu.enderframelib.core.css.model.Visibility;
import com.nishiyu.enderframelib.core.dom.node.ElementTraits;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.media.ImageSources;
import com.nishiyu.enderframelib.core.paint.model.BlendMode;
import com.nishiyu.enderframelib.core.paint.model.ClipShape;
import com.nishiyu.enderframelib.core.paint.model.FilterOp;
import com.nishiyu.enderframelib.core.replaced.ReplacedElements;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@link SceneLayer} 1 つのスタイル派生値（重なりの種類・フラグ・クリップ・変形・合成効果・伝播層ビット）を
 * {@code (BoxNode, ComputedStyle, Display, 子の層)} から純粋に求める（M3b-2）。
 */
final class SceneLayerProps {
    static final int TOP_LAYER_Z = Integer.MAX_VALUE;

    private SceneLayerProps() { }

    record Computed(
            StackingKind kind,
            int zIndex,
            int layerFlags,
            int propagatedLayers,
            Transform2D transform,
            ClipShape clip,
            LayerEffects effects,
            StickyConstraint sticky
    ) { }

    static Computed compute(BoxNode lb, HtmlNode node, ComputedStyle style, Display display,
                            float w, float h, float bT, float bR, float bB, float bL, float pT, float pR, float pB, float pL,
                            float mT, float mR, float mB, float mL,
                            boolean anonymous, boolean verticalWrapper, boolean interactive,
                            boolean parentInFlexOrGrid, boolean parentBlocksFloat, SceneChildren children,
                            boolean isRoot, int vw, int vh, Map<String, Keyframes> keyframes) {
        Style st = style.typed();
        VisualStyle v = st.visual;
        String tag = node != null ? node.tag : "";
        boolean isText = "#text".equals(tag) || (lb != null && lb.kind == BoxKind.TEXT);
        boolean isRootTag = "root".equals(tag);
        boolean displayNone = st.facts.displayNone || (display != null && display.isNone());
        boolean hidden = displayNone || ElementTraits.isOption(tag);

        Position pos = st.box.position;
        boolean absolute = pos == Position.ABSOLUTE || pos == Position.FIXED;
        boolean fixed = pos == Position.FIXED;
        boolean positioned = pos == Position.RELATIVE || pos == Position.ABSOLUTE || pos == Position.FIXED || pos == Position.STICKY;
        boolean zAuto = Float.isNaN(v.zIndex);
        int zIndex = zAuto ? 0 : (int) Style.num(v.zIndex, 0f);
        boolean topLayer = fixed && zIndex == TOP_LAYER_Z && !zAuto;

        boolean floating = !absolute && !isText && st.facts.floating && !parentBlocksFloat;
        boolean atomicInline = !isText && ((display != null && display.isInlineLevel() && display.inner() != Display.Inner.FLOW)
                || (parentInFlexOrGrid && !absolute));

        float opacity = Style.num(v.opacity, 1f);
        boolean animOpacity = animatesOpacity(style, keyframes);
        boolean masked = styleHasMask(style);
        BlendMode blend = BlendMode.fromCss(v.mixBlendMode);
        boolean stackingByStyle = !isNone(v.transform) || !isNone(v.filter) || !isNone(v.clipPath)
                || !isNone(v.backdropFilter) || masked || v.isolation == CssKeywords.Isolation.ISOLATE
                || blend != BlendMode.NORMAL;

        boolean createsStacking = (positioned && !zAuto) || fixed || pos == Position.STICKY
                || (!zAuto && parentInFlexOrGrid && !absolute)
                || opacity < 1f || animOpacity || stackingByStyle;

        StackingKind kind = isRoot ? StackingKind.ROOT
                : createsStacking ? StackingKind.STACKING
                : positioned ? StackingKind.POSITIONED
                : floating ? StackingKind.FLOAT
                : atomicInline ? StackingKind.ATOMIC
                : (display != null && display.isInlineLevel()) ? StackingKind.INLINE
                : StackingKind.BLOCK;

        boolean hasSelf = w > 0f && h > 0f && !anonymous;
        boolean scrollContainer = st.facts.scrollContainer;
        DomValues values = lb != null ? lb.values : DomValues.NONE;
        boolean panZoom = !values.attrs().isEmpty() && "true".equalsIgnoreCase(values.attrs().get("data-pan-zoom"));
        String[] ov = overflowPair(style);
        boolean clipsX = panZoom || !"visible".equals(ov[0]);
        boolean clipsY = panZoom || !"visible".equals(ov[1]);
        boolean overflowScrollbars = "scroll".equals(ov[0]) || "auto".equals(ov[0]) || "scroll".equals(ov[1]) || "auto".equals(ov[1]);
        boolean invisible = st.inherited.visibility != Visibility.VISIBLE;
        boolean wantsScrollbars = !isRootTag && w > 0f && h > 0f && overflowScrollbars && !invisible;

        boolean isOrigin = (display == null || !display.isInlineLevel() || display.inner() != Display.Inner.FLOW)
                || parentInFlexOrGrid || ReplacedElements.isReplacedTag(tag);

        boolean hasBlendDesc = children.propagatesBlend;
        boolean propagatesBlend = !hidden && (blend != BlendMode.NORMAL || (kind != StackingKind.STACKING && hasBlendDesc));

        int flags = 0;
        if (anonymous) flags |= SceneLayer.FLAG_ANONYMOUS;
        if (verticalWrapper) flags |= SceneLayer.FLAG_VERTICAL_WRAPPER;
        if (isText) flags |= SceneLayer.FLAG_TEXT;
        if (interactive) flags |= SceneLayer.FLAG_INTERACTIVE;
        if (scrollContainer) flags |= SceneLayer.FLAG_SCROLL_CONTAINER;
        if (clipsX) flags |= SceneLayer.FLAG_CLIPS_X;
        if (clipsY) flags |= SceneLayer.FLAG_CLIPS_Y;
        if (topLayer) flags |= SceneLayer.FLAG_TOP_LAYER;
        if (style.isContentVisibilityAuto()) flags |= SceneLayer.FLAG_CONTENT_VISIBILITY_AUTO;
        if (hasBlendDesc) flags |= SceneLayer.FLAG_BLEND_DESCENDANT;
        if (propagatesBlend) flags |= SceneLayer.FLAG_PROPAGATES_BLEND;
        if (wantsScrollbars) flags |= SceneLayer.FLAG_WANTS_SCROLLBARS;
        if (isOrigin) flags |= SceneLayer.FLAG_IS_ORIGIN;
        if (absolute) flags |= SceneLayer.FLAG_ABSOLUTE;
        if (fixed) flags |= SceneLayer.FLAG_FIXED;
        if (zAuto) flags |= SceneLayer.FLAG_Z_AUTO;
        if (displayNone) flags |= SceneLayer.FLAG_DISPLAY_NONE;
        if (hasSelf) flags |= SceneLayer.FLAG_HAS_SELF;


        Transform2D transform = isText ? null : Transform2D.parse(st, values, w, h, vw, vh);
        StickyConstraint sticky = isText ? null : StickyConstraint.of(st, mT, mR, mB, mL);
        if (transform != null || sticky != null || topLayer || hasAnimationOrTransition(v) || children.hasDynamicBounds) {
            flags |= SceneLayer.FLAG_DYNAMIC_BOUNDS;
        }

        ClipShape clip = null;
        LayerEffects effects = null;
        if (kind == StackingKind.STACKING && hasSelf) {
            float fontPx = st.inherited.fontSize, rootFontPx = st.inherited.rootFontSizePx;
            int fgColor = st.inheritedPaint.color;
            List<FilterOp> filters = FilterOp.parse(v.filter, new FilterOp.Context(vw, vh, fontPx, rootFontPx, fgColor));
            boolean isolate = hasBlendDesc;
            float[] border = {0f, 0f, w, h};
            float[] padding = {bL, bT, Math.max(0f, w - bL - bR), Math.max(0f, h - bT - bB)};
            float[] content = {padding[0] + pL, padding[1] + pT, Math.max(0f, padding[2] - pL - pR), Math.max(0f, padding[3] - pT - pB)};
            clip = ClipShape.parse(v.clipPath, ClipShape.Context.of(border, padding, content, vw, vh, fontPx, rootFontPx));
            boolean luminance = masked && maskIsLuminance(v.maskMode);
            List<FilterOp> backdropFilters = FilterOp.parse(v.backdropFilter, new FilterOp.Context(vw, vh, fontPx, rootFontPx, fgColor));
            ClipShape backdropShape = null;
            if (!backdropFilters.isEmpty()) {
                float[] r = LengthResolver.cornerRadiiXYShared(style, w, h, vw, vh);
                float[] d = new float[]{0f, 0f, w, h, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f};
                System.arraycopy(r, 0, d, 4, 8);
                backdropShape = new ClipShape(ClipShape.Kind.INSET, d, false);
            }
            if (opacity < 1f || animOpacity || !filters.isEmpty() || blend != BlendMode.NORMAL || isolate
                    || clip != null || masked || !backdropFilters.isEmpty()) {
                effects = new LayerEffects(opacity, animOpacity, filters, blend, isolate, masked, luminance, backdropFilters, backdropShape);
            }
        }

        int propagated = computePropagatedLayers(kind, zIndex, hidden, hasSelf, topLayer,
                ElementTraits.isValueControl(tag), wantsScrollbars, children.propagatedLayers);

        return new Computed(kind, zIndex, flags, propagated, transform, clip, effects, sticky);
    }

    private static int computePropagatedLayers(StackingKind kind, int zIndex, boolean hidden, boolean hasSelf,
                                               boolean topLayer, boolean valueControl, boolean wantsScrollbars, int childLayers) {
        if (hidden) return 0;
        int kids = valueControl ? 0 : childLayers;
        int selfAfter = (!valueControl && wantsScrollbars) ? SceneLayer.LAYER_AFTER : 0;
        final int scMask = SceneLayer.LAYER_NEG | SceneLayer.LAYER_POS0 | SceneLayer.LAYER_POSZ | SceneLayer.LAYER_HOIST;
        return switch (kind) {
            case ROOT -> 0;
            case BLOCK -> (hasSelf ? SceneLayer.LAYER_BLOCK : 0) | kids | selfAfter;
            case INLINE -> (hasSelf ? SceneLayer.LAYER_INLINE : 0) | kids | selfAfter;
            case FLOAT -> SceneLayer.LAYER_FLOAT | (kids & scMask);
            case ATOMIC -> SceneLayer.LAYER_INLINE | (kids & scMask);
            case POSITIONED -> SceneLayer.LAYER_POS0 | (kids & scMask);
            case STACKING -> {
                int ownLayer = topLayer ? SceneLayer.LAYER_HOIST
                        : zIndex < 0 ? SceneLayer.LAYER_NEG
                        : zIndex > 0 ? SceneLayer.LAYER_POSZ
                        : SceneLayer.LAYER_POS0;
                yield ownLayer | (kids & SceneLayer.LAYER_HOIST);
            }
        };
    }

    static boolean animatesOpacity(ComputedStyle style, Map<String, Keyframes> keyframes) {
        VisualStyle v = style.typed().visual;
        if (v.animation == null && v.animationName == null) return false;
        if (keyframes == null || keyframes.isEmpty()) return false;
        AnimationTiming timing = AnimationParser.parse(style, keyframes::containsKey);
        if (timing == null) return false;
        Keyframes kf = keyframes.get(timing.name());
        if (kf == null) return false;
        for (KeyframeBlock block : kf.blocks()) {
            String op = block.declarations().get(CssProperty.OPACITY);
            if (op != null) {
                try {
                    Float.parseFloat(op.trim());
                    return true;
                } catch (NumberFormatException ignored) { /* 読めない値は無視 */ }
            }
        }
        return false;
    }

    static boolean styleHasMask(ComputedStyle style) {
        VisualStyle v = style.typed().visual;
        if (v.backgroundClip != null) {
            for (String c : CssSyntax.parseCommaList(v.backgroundClip)) {
                if ("text".equalsIgnoreCase(c.trim())) return true;
            }
        }
        if (v.maskImage == null || v.maskImage.isBlank()) return false;
        for (String img : CssSyntax.parseCommaList(v.maskImage)) {
            String t = img.trim();
            String low = t.toLowerCase(Locale.ROOT);
            if (low.contains("gradient(") || (low.startsWith("url(") && t.endsWith(")"))
                    || (ImageSources.isImageSet(t) && ImageSources.selectImageSet(t, 1.0) != null)) {
                return true;
            }
        }
        return false;
    }

    private static boolean maskIsLuminance(String maskMode) {
        if (maskMode == null || maskMode.isBlank()) return false;
        List<String> list = CssSyntax.parseCommaList(maskMode);
        return !list.isEmpty() && "luminance".equalsIgnoreCase(list.getFirst().trim());
    }

    static String[] overflowPair(ComputedStyle style) {
        String x = style.overflowX().cssName();
        String y = style.overflowY().cssName();
        boolean xPlain = "visible".equals(x) || "clip".equals(x);
        boolean yPlain = "visible".equals(y) || "clip".equals(y);
        if (xPlain != yPlain) {
            if (xPlain) x = "visible".equals(x) ? "auto" : "hidden";
            else y = "visible".equals(y) ? "auto" : "hidden";
        }
        return new String[]{x, y};
    }

    static boolean isNone(String value) {
        return value == null || "none".equals(value) || value.isBlank() || "none".equalsIgnoreCase(value.trim());
    }

    private static boolean hasAnimationOrTransition(VisualStyle v) {
        return (v.animation != null && !v.animation.isBlank() && !"none".equalsIgnoreCase(v.animation.trim()))
                || (v.animationName != null && !v.animationName.isBlank() && !"none".equalsIgnoreCase(v.animationName.trim()))
                || (v.transition != null && !v.transition.isBlank() && !"none".equalsIgnoreCase(v.transition.trim()))
                || (v.transitionDuration != null && !v.transitionDuration.isBlank()
                && !"0s".equalsIgnoreCase(v.transitionDuration.trim()) && !"0ms".equalsIgnoreCase(v.transitionDuration.trim()));
    }
}
