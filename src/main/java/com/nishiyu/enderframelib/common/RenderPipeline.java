package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.paint.model.*;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.model.ContainerBox;
import com.nishiyu.enderframelib.core.css.model.ContainerType;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.css.model.ElementState;
import com.nishiyu.enderframelib.core.css.model.Keyframes;
import com.nishiyu.enderframelib.core.css.SelectorMatcher;
import com.nishiyu.enderframelib.core.css.model.StateLookup;
import com.nishiyu.enderframelib.core.css.StyleResolver;
import com.nishiyu.enderframelib.core.css.StyleResolver.PseudoType;
import com.nishiyu.enderframelib.core.css.model.StyleSet;
import com.nishiyu.enderframelib.core.css.StyleSheet;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.HtmlText;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.layout.BoxTreeBuilder;
import com.nishiyu.enderframelib.core.layout.model.FormattingContext;
import com.nishiyu.enderframelib.core.layout.model.IntrinsicSizeResolver;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.LayoutContext;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.layout.model.Viewport;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 描画パイプラインの一元管理（スタイル解決・ボックスツリー・レイアウト計算・ディスプレイリスト生成）。
 */
@ApiStatus.Internal
public class RenderPipeline {

    @FunctionalInterface
    public interface ILayoutBoxRenderer {
        List<DisplayItem> render(LayoutBox rootBox, int rootW, int rootH,
                                Map<String, Keyframes> keyframes,
                                ScrollState scrollState);
    }

    private static final int LAYOUT_PASSES = 2;
    private static final int MAX_CONTAINER_PASSES = 4;

    public static ILayoutBoxRenderer clientBoxRenderer = null;
    public static IntrinsicSizeResolver intrinsicSizeResolver = null;

    private static ILayoutBoxRenderer effectiveRenderer() {
        return clientBoxRenderer != null ? clientBoxRenderer : UiDocumentCompiler.clientBoxRenderer;
    }

    private static IntrinsicSizeResolver effectiveIntrinsicResolver() {
        return intrinsicSizeResolver != null ? intrinsicSizeResolver : UiDocumentCompiler.intrinsicSizeResolver;
    }

    public static List<DisplayItem> renderDocument(Document doc, int rootW, int rootH, Map<String, String> env, ScrollState scrollState, FontResolver fonts) {
        return renderDocument(doc, rootW, rootH, env, scrollState, fonts, new InteractionState());
    }

    public static List<DisplayItem> renderDocument(Document doc, int rootW, int rootH, Map<String, String> env,
                                                  ScrollState scrollState, FontResolver fonts, InteractionState interactionState) {
        ILayoutBoxRenderer renderer = effectiveRenderer();
        if (renderer == null) return new ArrayList<>();

        LayoutBox rootBox = layoutDocument(doc, rootW, rootH, env, scrollState, fonts, interactionState);
        return renderer.render(rootBox, rootW, rootH, doc.sheet.keyframes, scrollState);
    }

    public static List<DisplayItem> renderBox(LayoutBox rootBox, int rootW, int rootH, Map<String, Keyframes> keyframes, ScrollState scrollState) {
        ILayoutBoxRenderer renderer = effectiveRenderer();
        if (renderer == null) return new ArrayList<>();
        return renderer.render(rootBox, rootW, rootH, keyframes, scrollState);
    }

    public static LayoutBox layoutDocument(Document doc, int rootW, int rootH, Map<String, String> env,
                                           ScrollState scrollState, FontResolver fonts) {
        return layoutDocument(doc, rootW, rootH, env, scrollState, fonts, new InteractionState());
    }

    public static LayoutBox layoutDocument(Document doc, int rootW, int rootH, Map<String, String> env,
                                           ScrollState scrollState, FontResolver fonts, InteractionState interactionState) {
        Map<String, String> rootStyleRaw = rootViewportStyle(rootW, rootH);

        InteractionState effectiveState = interactionState != null ? interactionState : new InteractionState();
        if (doc.sheet.updateMediaEnvironment(doc.sheet.mediaEnvironment().withSize(rootW, rootH))) {
            effectiveState.markAllDirty();
        }
        boolean hasContainers = doc.sheet.declaresContainers() || doc.sheet.usesContainerQueries();

        LayoutBox rootBox = null;
        for (int settle = 0; settle <= MAX_CONTAINER_PASSES; settle++) {
            rootBox = buildLayoutTree(doc.root, rootStyleRaw, doc.sheet, env, false,
                    ComputedStyle.DEFAULT_FONT_SIZE, StyleScope.initial(rootW, rootH),
                    effectiveState, StyleResolver.hasSiblingStateRules(doc.sheet));
            BoxTreeBuilder.build(rootBox);
            runLayoutPasses(rootBox, rootW, rootH, scrollState, fonts);
            if (!hasContainers || !measureContainers(rootBox, effectiveState)) break;
        }
        return rootBox;
    }

    /**
     * 部分木（HUD トラッカーなど）をレイアウトしてルートの {@link LayoutBox} を返す。
     */
    public static LayoutBox layoutSubtree(HtmlNode root, StyleSheet sheet, int rootW, int rootH,
                                          Map<String, String> env, ScrollState scrollState, FontResolver fonts) {
        LayoutBox rootBox = buildLayoutTree(root, Map.of("display", "inline-block"), sheet, env, false);
        BoxTreeBuilder.build(rootBox);
        runLayoutPasses(rootBox, rootW, rootH, scrollState, fonts);
        return rootBox;
    }

    private static boolean measureContainers(LayoutBox box, InteractionState interactionState) {
        boolean changed = false;
        if (box.node != null && !box.node.isPseudoNode) {
            InteractionState.StateFlags flags = interactionState.get(box.node);
            ContainerBox container = flags == null ? null : interactionState.containerOf(box.node);
            if (container != null && container.isSizeContainer()) {
                float width = Math.max(0, box.w - box.pL - box.pR - box.bL - box.bR);
                float height = container.type() == ContainerType.INLINE_SIZE
                        ? Float.NaN : Math.max(0, box.h - box.pT - box.pB - box.bT - box.bB);
                changed |= interactionState.setContainerSize(box.node, width, height);
            }
        }
        for (LayoutBox child : box.elementChildren) {
            changed |= measureContainers(child, interactionState);
        }
        return changed;
    }

    public static void runLayoutPasses(LayoutBox rootBox, int rootW, int rootH, ScrollState scrollState, FontResolver fonts) {
        rootBox.linkParents();
        Viewport viewport = new Viewport(rootW, rootH);
        LayoutContext ctx = new LayoutContext(viewport, effectiveIntrinsicResolver(), scrollState, fonts);
        FormattingContext fmtCtx = FormattingContext.BLOCK;

        for (int pass = 0; pass < LAYOUT_PASSES; pass++) {
            rootBox.measure(rootW, rootH, fmtCtx, ctx);
        }
        rootBox.layout(0, 0, rootW, rootH, ctx);
    }

    public static Map<String, String> rootViewportStyle(int rootW, int rootH) {
        Map<String, String> rootStyleRaw = new HashMap<>();
        rootStyleRaw.put("width", String.valueOf(rootW));
        rootStyleRaw.put("min-height", String.valueOf(rootH));
        rootStyleRaw.put("box-sizing", "border-box");
        return rootStyleRaw;
    }

    public record RestyleResult(boolean styleChanged, boolean layoutRequired) {}

    private record StyleScope(int viewportW, int viewportH, float rootFontSize, float rootLineHeight) {
        static StyleScope initial(int viewportW, int viewportH) {
            return new StyleScope(viewportW, viewportH, ComputedStyle.DEFAULT_FONT_SIZE, Float.NaN);
        }

        StyleScope enter(HtmlNode node, ComputedStyle computed) {
            return "html".equalsIgnoreCase(node.tag)
                    ? new StyleScope(viewportW, viewportH, computed.fontSizePx(), computed.lineHeightPx())
                    : this;
        }
    }

    private static boolean isStyleCacheValid(InteractionState.StateFlags flags, float parentFontSize, StyleScope scope) {
        return flags != null
                && !flags.isStyleDirty
                && flags.computedStyle != null
                && Float.compare(flags.cachedParentFontSize, parentFontSize) == 0
                && Float.compare(flags.cachedRootFontSize, scope.rootFontSize()) == 0
                && Float.compare(flags.cachedRootLineHeight, scope.rootLineHeight()) == 0
                && flags.cachedViewportW == scope.viewportW()
                && flags.cachedViewportH == scope.viewportH();
    }

    private static void cacheStyle(InteractionState.StateFlags flags, Map<String, String> rawStyle,
                                   ComputedStyle computed, float parentFontSize, StyleScope scope,
                                   boolean hasInteractive) {
        flags.computedStyleCache = rawStyle;
        flags.computedStyle = computed;
        flags.cachedParentFontSize = parentFontSize;
        flags.cachedRootFontSize = scope.rootFontSize();
        flags.cachedRootLineHeight = scope.rootLineHeight();
        flags.cachedViewportW = scope.viewportW();
        flags.cachedViewportH = scope.viewportH();
        flags.hasInteractiveRules = hasInteractive;
        flags.isStyleDirty = false;
    }

    private static ComputedStyle computeStyle(Map<String, String> rawStyle, Map<String, String> parentStyle,
                                              StyleSheet sheet,
                                              Map<String, String> env, float parentFontSize, StyleScope scope,
                                              ContainerBox container) {
        return StyleResolver.finalizeComputedStyle(rawStyle, sheet, env, parentStyle,
                parentFontSize, scope.rootFontSize(), scope.rootLineHeight(), scope.viewportW(), scope.viewportH(), container);
    }

    private static ContainerBox containerFor(HtmlNode node, InteractionState interactionState) {
        return interactionState != null
                ? interactionState.nearestSizeContainer(node.parent)
                : ContainerBox.NONE_SIZE;
    }

    public static RestyleResult restyleLayoutTree(LayoutBox rootBox, Document doc, int rootW, int rootH,
                                                  Map<String, String> env, InteractionState interactionState) {
        if (rootBox == null || doc == null || interactionState == null) {
            return new RestyleResult(true, true);
        }
        Map<String, String> rootStyleRaw = rootViewportStyle(rootW, rootH);
        if (doc.sheet != null && doc.sheet.usesHasSelectors()) interactionState.propagateDirtyToAncestors();
        return restyleBoxRecursive(rootBox, rootStyleRaw, doc.sheet, env, false,
                ComputedStyle.DEFAULT_FONT_SIZE, StyleScope.initial(rootW, rootH),
                interactionState, StyleResolver.hasSiblingStateRules(doc.sheet));
    }

    private static RestyleResult restyleBoxRecursive(LayoutBox box, Map<String, String> inheritedStyle,
                                                     StyleSheet sheet,
                                                     Map<String, String> env, boolean forceDirty,
                                                     float parentFontSize, StyleScope scope,
                                                     InteractionState interactionState, boolean siblingInvalidation) {
        HtmlNode node = box.node;
        InteractionState.StateFlags flags = interactionState.getOrCreate(node);
        boolean isDirty = forceDirty || !isStyleCacheValid(flags, parentFontSize, scope);

        boolean styleChanged = false;
        boolean layoutRequired = false;
        ComputedStyle computed;

        if (isDirty) {
            StyleSet styleSet = StyleResolver.resolveStyleSet(node, inheritedStyle, sheet);
            Map<String, String> rawStyle = styleSet.resolve(interactionState);
            if (node.attrs.containsKey("hidden")) {
                rawStyle.put(CssProperty.DISPLAY, "none");
            }
            computed = computeStyle(rawStyle, inheritedStyle, sheet, env, parentFontSize, scope,
                    containerFor(node, interactionState));
            boolean hasInteractive = styleSet.hasInteractiveRules();

            ComputedStyle prevStyle = box.style;
            if (prevStyle == null || !prevStyle.asMap().equals(computed.asMap())) {
                styleChanged = true;
                if (StyleResolver.isLayoutAffectingChange(prevStyle, computed)) {
                    layoutRequired = true;
                }
            }

            if ("details".equalsIgnoreCase(node.tag)) {
                boolean isOpen = interactionState.stateOf(node).open();
                boolean wasOpen = box.elementChildren.size() > 1;
                if (isOpen != wasOpen) {
                    styleChanged = true;
                    layoutRequired = true;
                }
            }

            box.updateStyle(computed);
            resolveHighlightPseudos(box, computed, sheet, env, scope.enter(node, computed), interactionState);
            BoxTreeBuilder.restyleGeneratedBoxes(box);
            box.hasInteractiveRules = hasInteractive;
            cacheStyle(flags, rawStyle, computed, parentFontSize, scope, hasInteractive);
        } else {
            computed = flags.computedStyle;
        }

        boolean childForceDirty = isDirty;
        float nextParentFontSize = computed.fontSizePx();
        StyleScope childScope = scope.enter(node, computed);

        if (isDirty) {
            for (PseudoType type : PseudoType.GENERATED) {
                RestyleResult pseudoRes = restylePseudoElement(box, type, computed, sheet, env, childScope,
                        interactionState, siblingInvalidation);
                styleChanged |= pseudoRes.styleChanged();
                layoutRequired |= pseudoRes.layoutRequired();
            }
        }

        boolean siblingForceDirty = false;
        for (LayoutBox child : box.elementChildren) {
            if (child.node.isPseudoNode) continue;
            boolean stateChanged = siblingInvalidation && isStateDirty(interactionState, child.node);
            RestyleResult childRes = restyleBoxRecursive(child, computed.asMap(), sheet, env,
                    childForceDirty || siblingForceDirty, nextParentFontSize, childScope, interactionState,
                    siblingInvalidation);
            styleChanged |= childRes.styleChanged();
            layoutRequired |= childRes.layoutRequired();
            siblingForceDirty |= stateChanged;
        }

        return new RestyleResult(styleChanged, layoutRequired);
    }

    private static boolean isStateDirty(InteractionState interactionState, HtmlNode node) {
        if (interactionState == null) return false;
        InteractionState.StateFlags flags = interactionState.get(node);
        return flags == null || flags.isStyleDirty;
    }

    private static RestyleResult restylePseudoElement(LayoutBox owner, PseudoType type, ComputedStyle ownerStyle,
                                                      StyleSheet sheet,
                                                      Map<String, String> env, StyleScope scope,
                                                      InteractionState interactionState, boolean siblingInvalidation) {
        String pseudoId = pseudoElementId(owner.node, type);
        LayoutBox existing = null;
        for (LayoutBox child : owner.elementChildren) {
            if (child.node.isPseudoNode && pseudoId.equals(child.node.getId())) {
                existing = child;
                break;
            }
        }
        ComputedStyle next = resolvePseudoStyle(owner.node, type, ownerStyle, sheet, env, scope, interactionState);
        if (existing == null && next == null) return new RestyleResult(false, false);

        if (existing == null || next == null) {
            if (existing != null) {
                owner.elementChildren.remove(existing);
            } else {
                LayoutBox created = buildPseudoBox(owner.node, type, next, owner.hasInteractiveRules, sheet, env,
                        scope, interactionState, siblingInvalidation);
                owner.addElementChild(created);
                if (type == PseudoType.BEFORE) {
                    owner.elementChildren.remove(created);
                    owner.elementChildren.add(0, created);
                }
            }
            return new RestyleResult(true, true);
        }

        boolean styleChanged = !existing.style.asMap().equals(next.asMap());
        boolean layoutRequired = styleChanged && StyleResolver.isLayoutAffectingChange(existing.style, next);
        existing.updateStyle(next);
        existing.hasInteractiveRules = owner.hasInteractiveRules;
        BoxTreeBuilder.restyleGeneratedBoxes(existing);
        for (LayoutBox text : existing.elementChildren) {
            RestyleResult textRes = restyleBoxRecursive(text, next.asMap(), sheet, env, true, next.fontSizePx(),
                    scope, interactionState, siblingInvalidation);
            styleChanged |= textRes.styleChanged();
            layoutRequired |= textRes.layoutRequired();
        }
        return new RestyleResult(styleChanged, layoutRequired);
    }

    public static LayoutBox buildLayoutTree(HtmlNode node, Map<String, String> inheritedStyle,
                                            StyleSheet sheet,
                                            Map<String, String> env, boolean forceDirty) {
        return buildLayoutTree(node, inheritedStyle, sheet, env, forceDirty, new InteractionState());
    }

    public static LayoutBox buildLayoutTree(HtmlNode node, Map<String, String> inheritedStyle,
                                            StyleSheet sheet,
                                            Map<String, String> env, boolean forceDirty,
                                            InteractionState interactionState) {
        InteractionState effectiveState = interactionState != null ? interactionState : new InteractionState();
        return buildLayoutTree(node, inheritedStyle, sheet, env, forceDirty,
                ComputedStyle.DEFAULT_FONT_SIZE, StyleScope.initial(0, 0), effectiveState,
                StyleResolver.hasSiblingStateRules(sheet));
    }

    public static LayoutBox buildLayoutTree(HtmlNode node, Map<String, String> inheritedStyle,
                                            StyleSheet sheet,
                                            Map<String, String> env, boolean forceDirty,
                                            float parentFontSize, StyleScope scope, InteractionState interactionState,
                                            boolean siblingInvalidation) {
        InteractionState.StateFlags flags = interactionState != null ? interactionState.getOrCreate(node) : null;
        boolean needsRestyle = forceDirty || !isStyleCacheValid(flags, parentFontSize, scope);

        ComputedStyle computed;
        boolean hasInteractive;

        if (needsRestyle) {
            StyleSet styleSet = StyleResolver.resolveStyleSet(node, inheritedStyle, sheet);
            Map<String, String> rawStyle = interactionState != null
                    ? styleSet.resolve(interactionState)
                    : styleSet.resolve(ElementState.NONE);
            if (node.attrs.containsKey("hidden")) {
                rawStyle.put(CssProperty.DISPLAY, "none");
            }

            computed = computeStyle(rawStyle, inheritedStyle, sheet, env, parentFontSize, scope,
                    containerFor(node, interactionState));
            hasInteractive = styleSet.hasInteractiveRules();

            if (flags != null) cacheStyle(flags, rawStyle, computed, parentFontSize, scope, hasInteractive);
        } else {
            computed = flags.computedStyle;
            hasInteractive = flags.hasInteractiveRules;
        }
        Map<String, String> childInherited = computed.asMap();
        StyleScope childScope = scope.enter(node, computed);

        LayoutBox box = new LayoutBox(node, computed);
        box.hasInteractiveRules = hasInteractive;
        resolveHighlightPseudos(box, computed, sheet, env, childScope, interactionState);

        boolean childForceDirty = needsRestyle;

        ComputedStyle beforeStyle = resolvePseudoStyle(node, PseudoType.BEFORE, computed, sheet, env, childScope, interactionState);
        if (beforeStyle != null) {
            box.addElementChild(buildPseudoBox(node, PseudoType.BEFORE, beforeStyle, hasInteractive, sheet, env,
                    childScope, interactionState, siblingInvalidation));
        }

        if (!"textarea".equalsIgnoreCase(node.tag)) {
            boolean isOpen = interactionState != null
                    ? interactionState.stateOf(node).open()
                    : node.attrs.containsKey("open");
            boolean isClosedDetails = "details".equalsIgnoreCase(node.tag) && !isOpen;
            boolean siblingForceDirty = false;
            for (HtmlNode child : node.children) {
                if (child.tag.equals("#text") && child.text.isEmpty()) continue;
                if (isClosedDetails && !"summary".equalsIgnoreCase(child.tag)) continue;
                boolean stateChanged = siblingInvalidation && isStateDirty(interactionState, child);
                box.addElementChild(buildLayoutTree(child, childInherited, sheet, env, childForceDirty || siblingForceDirty,
                        computed.fontSizePx(), childScope, interactionState, siblingInvalidation));
                siblingForceDirty |= stateChanged;
            }
        }

        ComputedStyle afterStyle = resolvePseudoStyle(node, PseudoType.AFTER, computed, sheet, env, childScope, interactionState);
        if (afterStyle != null) {
            box.addElementChild(buildPseudoBox(node, PseudoType.AFTER, afterStyle, hasInteractive, sheet, env,
                    childScope, interactionState, siblingInvalidation));
        }

        return box;
    }

    private static void resolveHighlightPseudos(LayoutBox box, ComputedStyle computed,
                                                StyleSheet sheet,
                                                Map<String, String> env, StyleScope childScope,
                                                InteractionState interactionState) {
        HtmlNode node = box.node;
        box.markerStyle = box.display.listItem()
                ? resolvePseudoStyle(node, PseudoType.MARKER, computed, sheet, env, childScope, interactionState)
                : null;
        boolean textInput = "input".equals(node.tag) || "textarea".equals(node.tag);
        box.placeholderStyle = textInput
                ? resolvePseudoStyle(node, PseudoType.PLACEHOLDER, computed, sheet, env, childScope, interactionState)
                : null;
    }

    private static ComputedStyle resolvePseudoStyle(HtmlNode owner, PseudoType type, ComputedStyle ownerStyle,
                                                    StyleSheet sheet,
                                                    Map<String, String> env, StyleScope scope,
                                                    InteractionState interactionState) {
        StateLookup lookup =
                interactionState != null ? interactionState : SelectorMatcher.STATIC_ATTRIBUTES;
        return StyleResolver.resolvePseudoStyle(owner, type, sheet, lookup, ownerStyle, env,
                scope.rootFontSize(), scope.rootLineHeight(), scope.viewportW(), scope.viewportH());
    }

    private static String pseudoElementId(HtmlNode owner, PseudoType type) {
        return owner.getId() + (type == PseudoType.BEFORE ? "_before" : "_after");
    }

    private static LayoutBox buildPseudoBox(HtmlNode owner, PseudoType type, ComputedStyle style, boolean ownerInteractive,
                                           StyleSheet sheet,
                                           Map<String, String> env, StyleScope scope,
                                           InteractionState interactionState, boolean siblingInvalidation) {
        HtmlNode pseudo = new HtmlNode("span");
        pseudo.isPseudoNode = true;
        pseudo.parent = owner;
        pseudo.setId(pseudoElementId(owner, type));

        HtmlNode textNode = new HtmlText("");
        textNode.parent = pseudo;
        textNode.setId(pseudo.getId() + "_txt");
        pseudo.children.add(textNode);

        LayoutBox box = new LayoutBox(pseudo, style);
        box.hasInteractiveRules = ownerInteractive;
        box.addElementChild(buildLayoutTree(textNode, style.asMap(), sheet, env, true, style.fontSizePx(), scope,
                interactionState, siblingInvalidation));
        return box;
    }
}
