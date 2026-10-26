package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.paint.model.*;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.model.ContainerBox;
import com.nishiyu.enderframelib.core.css.model.ContainerType;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.css.PropertyRegistry;
import com.nishiyu.enderframelib.core.css.model.ElementState;
import com.nishiyu.enderframelib.core.css.model.Keyframes;
import com.nishiyu.enderframelib.core.css.SelectorMatcher;
import com.nishiyu.enderframelib.core.css.model.StateLookup;
import com.nishiyu.enderframelib.core.css.StyleAnimator;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

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
    /** レイアウトの通し番号（{@link LayoutContext#layoutRun()}）。 */
    private static final java.util.concurrent.atomic.AtomicLong LAYOUT_RUNS = new java.util.concurrent.atomic.AtomicLong();
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
        return layoutDocument(doc, rootW, rootH, env, scrollState, fonts, interactionState, null);
    }

    /**
     * 差分レイアウトを有効にするか。false なら {@code previousRoot} を渡されても全部を作り直して測り直す
     * （差分レイアウトを疑うときの切り分け用）。
     */
    public static volatile boolean incrementalLayout = true;

    /**
     * 文書をレイアウトする。{@code previousRoot}（同じ文書の前回の結果）があれば差分レイアウトにする:
     * 箱は DOM の節ごとに使い回し、中身も入力も前回と同じ部分木は measure を飛ばす。
     * 結果は全部を作り直したときと同じ（{@code IncrementalLayoutTest} が確かめる）。
     */
    public static LayoutBox layoutDocument(Document doc, int rootW, int rootH, Map<String, String> env,
                                           ScrollState scrollState, FontResolver fonts, InteractionState interactionState,
                                           LayoutBox previousRoot) {
        boolean incremental = incrementalLayout && previousRoot != null;
        Map<String, String> rootStyleRaw = rootViewportStyle(rootW, rootH);

        InteractionState effectiveState = interactionState != null ? interactionState : new InteractionState();
        effectiveState.bindStyleSheet(doc.sheet);
        if (doc.sheet.updateMediaEnvironment(doc.sheet.mediaEnvironment().withSize(rootW, rootH))) {
            effectiveState.markAllDirty();
        }
        boolean hasContainers = doc.sheet.declaresContainers() || doc.sheet.usesContainerQueries();

        LayoutBox rootBox = incremental ? previousRoot : null;
        for (int settle = 0; settle <= MAX_CONTAINER_PASSES; settle++) {
            Map<HtmlNode, LayoutBox> reusable = incremental ? LayoutBox.reusableBoxes(rootBox) : null;
            rootBox = buildLayoutTree(doc.root, rootStyleRaw, doc.sheet, env, FORCE_NONE,
                    ComputedStyle.DEFAULT_FONT_SIZE, StyleScope.initial(rootW, rootH),
                    effectiveState, StyleResolver.hasSiblingStateRules(doc.sheet), reusable);
            BoxTreeBuilder.build(rootBox);
            if (incremental) LayoutBox.markLayoutChanges(rootBox);
            runLayoutPasses(rootBox, rootW, rootH, scrollState, fonts, incremental);
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
        runLayoutPasses(rootBox, rootW, rootH, scrollState, fonts, false);
    }

    private static void runLayoutPasses(LayoutBox rootBox, int rootW, int rootH, ScrollState scrollState, FontResolver fonts,
                                        boolean reuseMeasurements) {
        rootBox.linkParents();
        Viewport viewport = new Viewport(rootW, rootH);
        long run = LAYOUT_RUNS.incrementAndGet();
        LayoutContext ctx = null;
        FormattingContext fmtCtx = FormattingContext.BLOCK;

        for (int pass = 0; pass < LAYOUT_PASSES; pass++) {
            ctx = new LayoutContext(viewport, effectiveIntrinsicResolver(), scrollState, fonts, reuseMeasurements, run, pass == 0);
            rootBox.measure(rootW, rootH, fmtCtx, ctx);
        }
        LayoutBox.resetPlacements(rootBox, reuseMeasurements ? run : -1);
        rootBox.layout(0, 0, rootW, rootH, ctx);
    }

    /**
     * 測った寸法はそのままに、位置だけを決め直す（measure を飛ばして layout だけ走らせる）。
     *
     * <p>スクロール量や配置済みの箱の {@code top} / {@code left} は寸法に響かず、{@link LayoutBox#layout} の中でしか読まれない。
     * スクロールバーやウィンドウのドラッグのように毎フレーム変わる値で、木の作り直しと 2 回の measure を省く。</p>
     */
    public static void repositionLayout(LayoutBox rootBox, int rootW, int rootH, ScrollState scrollState, FontResolver fonts) {
        LayoutContext ctx = new LayoutContext(new Viewport(rootW, rootH), effectiveIntrinsicResolver(), scrollState, fonts);
        LayoutBox.resetPlacements(rootBox);
        rootBox.layout(0, 0, rootW, rootH, ctx);
    }

    /**
     * 変わったのが配置済みの箱の inset（{@code top} / {@code left} / {@code right} / {@code bottom}）だけで、
     * 寸法に響かないか。響かなければ {@link #repositionLayout} で足りる。
     *
     * <p>相対配置・sticky の inset は layout で足すずれにしか使わない。絶対配置の inset は、両側とも指定された軸だけが
     * 寸法を決める（CSS 2.2 §10.3.7 / §10.6.4）ので、その軸が動いたときは measure からやり直す。</p>
     */
    static boolean isPositionOnlyChange(ComputedStyle oldStyle, ComputedStyle newStyle) {
        if (oldStyle == null || newStyle == null) return false;
        if (Float.compare(oldStyle.fontSizePx(), newStyle.fontSizePx()) != 0
                || Float.compare(oldStyle.rootFontSizePx(), newStyle.rootFontSizePx()) != 0) {
            return false;
        }
        String position = newStyle.get(CssProperty.POSITION);
        if (!Objects.equals(position, oldStyle.get(CssProperty.POSITION))) return false;
        boolean offsetOnly = "relative".equals(position) || "sticky".equals(position);
        boolean outOfFlow = "absolute".equals(position) || "fixed".equals(position);
        if (!offsetOnly && !outOfFlow) return false;

        Map<String, String> before = oldStyle.asMap();
        Map<String, String> after = newStyle.asMap();
        boolean horizontal = false;
        boolean vertical = false;
        Set<String> keys = new HashSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            if (Objects.equals(before.get(key), after.get(key)) || PropertyRegistry.isPaintOnly(key)) continue;
            switch (key) {
                case CssProperty.LEFT, CssProperty.RIGHT -> horizontal = true;
                case CssProperty.TOP, CssProperty.BOTTOM -> vertical = true;
                default -> {
                    return false;
                }
            }
        }
        if (offsetOnly) return true;
        if (horizontal && (bothInsets(oldStyle, CssProperty.LEFT, CssProperty.RIGHT)
                || bothInsets(newStyle, CssProperty.LEFT, CssProperty.RIGHT))) {
            return false;
        }
        return !vertical || !(bothInsets(oldStyle, CssProperty.TOP, CssProperty.BOTTOM)
                || bothInsets(newStyle, CssProperty.TOP, CssProperty.BOTTOM));
    }

    private static boolean bothInsets(ComputedStyle style, String start, String end) {
        return !isAutoInset(style.get(start)) && !isAutoInset(style.get(end));
    }

    private static boolean isAutoInset(String value) {
        return value == null || value.isBlank() || "auto".equalsIgnoreCase(value.trim());
    }

    public static Map<String, String> rootViewportStyle(int rootW, int rootH) {
        Map<String, String> rootStyleRaw = new HashMap<>();
        rootStyleRaw.put("width", String.valueOf(rootW));
        rootStyleRaw.put("min-height", String.valueOf(rootH));
        rootStyleRaw.put("box-sizing", "border-box");
        return rootStyleRaw;
    }

    /**
     * 再スタイルの結果。{@code layoutRequired} は寸法から測り直す（measure + layout）、
     * {@code repositionRequired} は寸法は変わらず位置だけ決め直せば足りる（layout のみ）ことを表す。
     */
    public record RestyleResult(boolean styleChanged, boolean layoutRequired, boolean repositionRequired) {
        public RestyleResult(boolean styleChanged, boolean layoutRequired) {
            this(styleChanged, layoutRequired, false);
        }
    }

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

    /**
     * 算出スタイルのキャッシュの状態。
     * <ul>
     *   <li>{@link #VALID}: そのまま使える</li>
     *   <li>{@link #VIEWPORT_STALE}: ビューポートだけが変わり、宣言値がビューポートに依存する。算出し直すが、
     *       結果が前と同じなら子孫へ再スタイルを強いない（React の bailout と同じ考え方）</li>
     *   <li>{@link #INVALID}: 状態・DOM・継承元が変わった。子孫もまとめて算出し直す</li>
     * </ul>
     */
    private enum StyleCache { VALID, VIEWPORT_STALE, INVALID }

    /** 宣言値がビューポートの大きさで変わり得るか（{@code vw} / {@code svh} / {@code cqw} など、または参照先の分からない {@code var()}）。 */
    private static final Pattern VIEWPORT_DEPENDENT_VALUE = Pattern.compile(
            "\\d(?:[sld]?v(?:w|h|i|b|min|max)|cq(?:w|h|i|b|min|max))(?![a-z])|var\\(|env\\(",
            Pattern.CASE_INSENSITIVE);

    private static StyleCache styleCacheState(InteractionState.StateFlags flags, float parentFontSize, StyleScope scope) {
        if (flags == null
                || flags.isStyleDirty
                || flags.computedStyle == null
                || Float.compare(flags.cachedParentFontSize, parentFontSize) != 0
                || Float.compare(flags.cachedRootFontSize, scope.rootFontSize()) != 0
                || Float.compare(flags.cachedRootLineHeight, scope.rootLineHeight()) != 0) {
            return StyleCache.INVALID;
        }
        boolean sameViewport = flags.cachedViewportW == scope.viewportW() && flags.cachedViewportH == scope.viewportH();
        return sameViewport || !flags.viewportDependent ? StyleCache.VALID : StyleCache.VIEWPORT_STALE;
    }

    /** 親から子へ渡す「算出し直せ」の強さ。 */
    private static final int FORCE_NONE = 0;
    /** この要素だけ算出し直す（継承元が変わった・兄弟に状態の変化がある）。子孫への強制は、算出結果から改めて決める。 */
    private static final int FORCE_SELF = 1;
    /** この要素と全ての子孫を算出し直す（祖先の状態・class・属性を読む規則が子孫に効き得る）。 */
    private static final int FORCE_SUBTREE = 2;

    /** 算出し直した結果が、子孫の算出に及ぶ度合い。 */
    private enum Propagation {
        /** 子孫が読む値は変わっていない。子孫はそれぞれのキャッシュの状態で判断する。 */
        NONE,
        /** 継承しないプロパティだけが変わった。{@code inherit} を明示した子孫だけが影響を受ける。 */
        NON_INHERITED,
        /** 継承する値（カスタムプロパティ・フォントサイズを含む）が変わった。子孫は全て算出し直す。 */
        INHERITED
    }

    /**
     * 算出し直した要素の子孫へ、どこまで再スタイルを及ぼすか。
     * <ul>
     *   <li>要素自身が子孫の規則の入力になる（{@code .a:hover .b}）なら {@code subtreeDirty}（無効化セットが立てる）で全部</li>
     *   <li>継承する値が変わったなら全部。子孫の継承元が変わる</li>
     *   <li>継承しない値だけが変わったなら、{@code inherit} を使う規則・style 属性が無い限り子孫は影響を受けない
     *       （背景色の変化で子孫全員を再スタイルしない）</li>
     * </ul>
     */
    private static Propagation propagationOf(ComputedStyle previous, ComputedStyle computed) {
        if (previous == null) return Propagation.INHERITED;
        if (Float.compare(previous.fontSizePx(), computed.fontSizePx()) != 0
                || Float.compare(previous.rootFontSizePx(), computed.rootFontSizePx()) != 0) {
            return Propagation.INHERITED;
        }
        Map<String, String> before = previous.asMap();
        Map<String, String> after = computed.asMap();
        if (before.equals(after)) return Propagation.NONE;
        Propagation result = Propagation.NONE;
        for (Map.Entry<String, String> e : before.entrySet()) {
            if (Objects.equals(e.getValue(), after.get(e.getKey()))) continue;
            if (isInheritedKey(e.getKey())) return Propagation.INHERITED;
            result = Propagation.NON_INHERITED;
        }
        for (Map.Entry<String, String> e : after.entrySet()) {
            if (before.containsKey(e.getKey())) continue;
            if (isInheritedKey(e.getKey())) return Propagation.INHERITED;
            result = Propagation.NON_INHERITED;
        }
        return result;
    }

    private static int childForceOf(boolean subtreeDirty, Propagation propagation, StyleSheet sheet) {
        if (subtreeDirty) return FORCE_SUBTREE;
        if (propagation == Propagation.INHERITED
                || (propagation == Propagation.NON_INHERITED && sheet != null && sheet.usesInheritKeyword())) {
            return FORCE_SELF;
        }
        return FORCE_NONE;
    }

    /** 親の値が子孫の算出に入る名前か。未登録の名前は安全側に継承するとみなす。 */
    private static boolean isInheritedKey(String key) {
        return key.startsWith("--") || PropertyRegistry.get(key) == null || PropertyRegistry.isInherited(key);
    }

    private static boolean dependsOnViewport(HtmlNode node, Map<String, String> rawStyle) {
        // ルートはビューポートの寸法を継承元の表として受け取る
        if (node.parent == null) return true;
        for (String value : rawStyle.values()) {
            if (value != null && VIEWPORT_DEPENDENT_VALUE.matcher(value).find()) return true;
        }
        return false;
    }

    private static void cacheStyle(InteractionState.StateFlags flags, HtmlNode node, Map<String, String> rawStyle,
                                   ComputedStyle computed, float parentFontSize, StyleScope scope,
                                   boolean hasInteractive) {
        flags.computedStyleCache = rawStyle;
        flags.computedStyle = computed;
        flags.cachedParentFontSize = parentFontSize;
        flags.cachedRootFontSize = scope.rootFontSize();
        flags.cachedRootLineHeight = scope.rootLineHeight();
        flags.cachedViewportW = scope.viewportW();
        flags.cachedViewportH = scope.viewportH();
        flags.viewportDependent = dependsOnViewport(node, rawStyle);
        flags.hasInteractiveRules = hasInteractive;
        flags.isStyleDirty = false;
        flags.subtreeDirty = false;
        flags.unscoped = false;
        String inlineStyle = node.attrs.get("style");
        flags.inheritsExplicitly = inlineStyle != null && inlineStyle.toLowerCase(java.util.Locale.ROOT).contains("inherit");
    }

    private static ComputedStyle computeStyle(Map<String, String> rawStyle, Map<String, String> parentStyle,
                                              StyleSheet sheet,
                                              Map<String, String> env, float parentFontSize, StyleScope scope,
                                              ContainerBox container) {
        return StyleResolver.finalizeComputedStyle(rawStyle, sheet, env, parentStyle,
                parentFontSize, scope.rootFontSize(), scope.rootLineHeight(), scope.viewportW(), scope.viewportH(), container);
    }

    /**
     * 目標の算出スタイルに、トランジションと {@code @keyframes} の値（{@link StyleAnimator}）を重ねる。
     * 動いているものが無ければ目標をそのまま返す。{@code opacity} / {@code transform} / 色は描画項目の補間が担うので対象外。
     */
    private static ComputedStyle animated(HtmlNode node, ComputedStyle before, ComputedStyle target,
                                          Map<String, String> rawStyle, Map<String, String> inheritedStyle,
                                          StyleSheet sheet, Map<String, String> env, float parentFontSize,
                                          StyleScope scope, InteractionState interactionState) {
        if (interactionState == null || node.isPseudoNode || sheet == null) return target;
        long now = interactionState.animationTimeMs();
        StyleAnimator animator = interactionState.animator();
        animator.observe(node, before, target, inheritedStyle, sheet.keyframes, now);
        Map<String, String> values = animator.sample(node, now);
        if (values.isEmpty()) return target;
        rawStyle.putAll(values);
        return computeStyle(rawStyle, inheritedStyle, sheet, env, parentFontSize, scope, containerFor(node, interactionState));
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
        interactionState.bindStyleSheet(doc.sheet);
        if (doc.sheet != null && doc.sheet.usesHasSelectors()) interactionState.propagateDirtyToAncestors();
        return restyleBoxRecursive(rootBox, rootStyleRaw, doc.sheet, env, FORCE_NONE,
                ComputedStyle.DEFAULT_FONT_SIZE, StyleScope.initial(rootW, rootH),
                interactionState, StyleResolver.hasSiblingStateRules(doc.sheet));
    }

    private static RestyleResult restyleBoxRecursive(LayoutBox box, Map<String, String> inheritedStyle,
                                                     StyleSheet sheet,
                                                     Map<String, String> env, int force,
                                                     float parentFontSize, StyleScope scope,
                                                     InteractionState interactionState, boolean siblingInvalidation) {
        HtmlNode node = box.node;
        InteractionState.StateFlags flags = interactionState.getOrCreate(node);
        StyleCache cache = force != FORCE_NONE ? StyleCache.INVALID : styleCacheState(flags, parentFontSize, scope);
        boolean isDirty = cache != StyleCache.VALID;

        boolean styleChanged = false;
        boolean layoutRequired = false;
        boolean repositionRequired = false;
        int childForce = FORCE_NONE;
        boolean childForceIfInherits = false;
        ComputedStyle computed;
        // 要素自身が子孫の規則の入力になる変化（祖先の状態・class を読む規則）。算出値が変わらなくても子孫を算出し直す。
        // 祖先から受けた FORCE_SUBTREE も、さらに下の子孫まで続ける
        boolean subtreeDirty = flags.subtreeDirty || force == FORCE_SUBTREE;

        if (isDirty) {
            StyleSet styleSet = StyleResolver.resolveStyleSet(node, inheritedStyle, sheet);
            Map<String, String> rawStyle = styleSet.resolve(interactionState);
            if (node.attrs.containsKey("hidden")) {
                rawStyle.put(CssProperty.DISPLAY, "none");
            }
            ComputedStyle prevStyle = box.style;
            computed = computeStyle(rawStyle, inheritedStyle, sheet, env, parentFontSize, scope,
                    containerFor(node, interactionState));
            computed = animated(node, prevStyle, computed, rawStyle, inheritedStyle, sheet, env, parentFontSize, scope,
                    interactionState);
            boolean hasInteractive = styleSet.hasInteractiveRules();

            if (prevStyle == null || !prevStyle.asMap().equals(computed.asMap())) {
                styleChanged = true;
                if (StyleResolver.isLayoutAffectingChange(prevStyle, computed)) {
                    if (isPositionOnlyChange(prevStyle, computed)) {
                        repositionRequired = true;
                    } else {
                        layoutRequired = true;
                    }
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

            Propagation propagation = propagationOf(prevStyle, computed);
            childForce = childForceOf(subtreeDirty, propagation, sheet);
            childForceIfInherits = propagation == Propagation.NON_INHERITED;
            box.updateStyle(computed);
            resolveHighlightPseudos(box, computed, sheet, env, scope.enter(node, computed), interactionState);
            BoxTreeBuilder.restyleGeneratedBoxes(box);
            box.hasInteractiveRules = hasInteractive;
            cacheStyle(flags, node, rawStyle, computed, parentFontSize, scope, hasInteractive);
        } else {
            computed = flags.computedStyle;
            childForce = subtreeDirty ? FORCE_SUBTREE : FORCE_NONE;
            flags.subtreeDirty = false;
        }

        float nextParentFontSize = computed.fontSizePx();
        StyleScope childScope = scope.enter(node, computed);

        if (isDirty && !computed.skipsContents()) {
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
            int forceChild = siblingForceDirty ? FORCE_SUBTREE : childForce;
            if (forceChild == FORCE_NONE && childForceIfInherits) {
                InteractionState.StateFlags childFlags = interactionState.get(child.node);
                if (childFlags != null && childFlags.inheritsExplicitly) forceChild = FORCE_SELF;
            }
            RestyleResult childRes = restyleBoxRecursive(child, computed.asMap(), sheet, env,
                    forceChild, nextParentFontSize, childScope, interactionState,
                    siblingInvalidation);
            styleChanged |= childRes.styleChanged();
            layoutRequired |= childRes.layoutRequired();
            repositionRequired |= childRes.repositionRequired();
            siblingForceDirty |= stateChanged;
        }

        return new RestyleResult(styleChanged, layoutRequired, repositionRequired);
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
            RestyleResult textRes = restyleBoxRecursive(text, next.asMap(), sheet, env, FORCE_SUBTREE, next.fontSizePx(),
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
        return buildLayoutTree(node, inheritedStyle, sheet, env, forceDirty ? FORCE_SUBTREE : FORCE_NONE,
                parentFontSize, scope, interactionState, siblingInvalidation, null);
    }

    /**
     * 箱の木を組む。{@code reusable} があれば、同じ DOM の節の前回の箱を作り直さずに使い回す（React の差分適用と同じく、
     * 変わらない部分の実体を保つ）。使い回した箱は前回の measure の結果を持っているので、中身も入力も変わっていなければ
     * {@link LayoutBox#measure} がそれを使う。表の中は使い回さない（{@link LayoutBox#reusableBoxes} を参照）。
     */
    private static LayoutBox buildLayoutTree(HtmlNode node, Map<String, String> inheritedStyle,
                                             StyleSheet sheet,
                                             Map<String, String> env, int force,
                                             float parentFontSize, StyleScope scope, InteractionState interactionState,
                                             boolean siblingInvalidation, Map<HtmlNode, LayoutBox> reusable) {
        InteractionState.StateFlags flags = interactionState != null ? interactionState.getOrCreate(node) : null;
        StyleCache cache = force != FORCE_NONE ? StyleCache.INVALID : styleCacheState(flags, parentFontSize, scope);
        boolean needsRestyle = cache != StyleCache.VALID;

        ComputedStyle computed;
        boolean hasInteractive;
        int childForce = FORCE_NONE;
        boolean childForceIfInherits = false;
        boolean subtreeDirty = (flags != null && flags.subtreeDirty) || force == FORCE_SUBTREE;

        if (needsRestyle) {
            StyleSet styleSet = StyleResolver.resolveStyleSet(node, inheritedStyle, sheet);
            Map<String, String> rawStyle = interactionState != null
                    ? styleSet.resolve(interactionState)
                    : styleSet.resolve(ElementState.NONE);
            if (node.attrs.containsKey("hidden")) {
                rawStyle.put(CssProperty.DISPLAY, "none");
            }

            ComputedStyle previous = flags != null ? flags.computedStyle : null;
            computed = computeStyle(rawStyle, inheritedStyle, sheet, env, parentFontSize, scope,
                    containerFor(node, interactionState));
            computed = animated(node, previous, computed, rawStyle, inheritedStyle, sheet, env, parentFontSize, scope,
                    interactionState);
            hasInteractive = styleSet.hasInteractiveRules();

            Propagation propagation = propagationOf(previous, computed);
            childForce = childForceOf(subtreeDirty, propagation, sheet);
            childForceIfInherits = propagation == Propagation.NON_INHERITED;
            if (flags != null) cacheStyle(flags, node, rawStyle, computed, parentFontSize, scope, hasInteractive);
        } else {
            computed = flags.computedStyle;
            hasInteractive = flags.hasInteractiveRules;
            childForce = subtreeDirty ? FORCE_SUBTREE : FORCE_NONE;
            flags.subtreeDirty = false;
        }
        Map<String, String> childInherited = computed.asMap();
        StyleScope childScope = scope.enter(node, computed);

        LayoutBox box = reusable != null ? reusable.remove(node) : null;
        List<LayoutBox> previousChildren = List.of();
        boolean reusedBox = false;
        if (box != null) {
            previousChildren = new ArrayList<>(box.elementChildren);
            box.elementChildren.clear();
            box.refreshStyle(computed);
            if (LayoutBox.isTableBox(box) || box.display.isNone()) box = null;
            else reusedBox = true;
        }
        if (box == null) {
            box = new LayoutBox(node, computed);
            previousChildren = List.of();
        }
        // 表のエンジンは中の箱へ直接書き込むので、表の中は作り直す。display: none の部分木は測られないので、
        // 使い回すと前回の寸法が残る（新しく作った箱は 0）。コンテナクエリの寸法などが読むので作り直す
        Map<HtmlNode, LayoutBox> childReusable = LayoutBox.isTableBox(box) || box.display.isNone() ? null : reusable;
        box.hasInteractiveRules = hasInteractive;
        // 持ち主のスタイルのキャッシュが有効なら、状態も祖先・兄弟も前回のままなので疑似要素の規則の当たり方も前回のまま。
        // 疑似要素だけがビューポート単位を使うこともあるので、ビューポートが同じときに限って前回の疑似要素をそのまま使う
        // （再スタイルの経路 restyleBoxRecursive も、汚れていない要素の疑似要素は解き直さない）
        long viewportKey = ((long) scope.viewportW() << 32) | (scope.viewportH() & 0xffffffffL);
        boolean reusePseudos = reusedBox && !needsRestyle && box.pseudoStylesViewport == viewportKey;
        if (!reusePseudos) {
            resolveHighlightPseudos(box, computed, sheet, env, childScope, interactionState);
            box.pseudoStylesViewport = viewportKey;
        }

        // content-visibility: hidden の中身は測らず描かない（箱の木に入れない。疑似要素も同じ）
        boolean skipContents = computed.skipsContents();
        if (skipContents) {
            // 子を持たない箱として扱う
        } else if (reusePseudos) {
            LayoutBox before = previousPseudo(previousChildren, pseudoElementId(node, PseudoType.BEFORE));
            if (before != null) box.addElementChild(before);
        } else {
            ComputedStyle beforeStyle = resolvePseudoStyle(node, PseudoType.BEFORE, computed, sheet, env, childScope, interactionState);
            if (beforeStyle != null) {
                box.addElementChild(buildPseudoBox(node, PseudoType.BEFORE, beforeStyle, hasInteractive, sheet, env,
                        childScope, interactionState, siblingInvalidation, childReusable, previousChildren));
            }
        }

        // svg の子（図形）は箱にしない。svg 要素が木を読んで自分でラスタライズする
        if (!skipContents && !"textarea".equalsIgnoreCase(node.tag) && !"svg".equalsIgnoreCase(node.tag)) {
            boolean isOpen = interactionState != null
                    ? interactionState.stateOf(node).open()
                    : node.attrs.containsKey("open");
            boolean isClosedDetails = "details".equalsIgnoreCase(node.tag) && !isOpen;
            boolean siblingForceDirty = false;
            for (HtmlNode child : node.children) {
                if (child.tag.equals("#text") && child.text.isEmpty()) continue;
                if (isClosedDetails && !"summary".equalsIgnoreCase(child.tag)) continue;
                boolean stateChanged = siblingInvalidation && isStateDirty(interactionState, child);
                int forceChild = siblingForceDirty ? FORCE_SUBTREE : childForce;
                if (forceChild == FORCE_NONE && childForceIfInherits && interactionState != null) {
                    InteractionState.StateFlags childFlags = interactionState.get(child);
                    if (childFlags != null && childFlags.inheritsExplicitly) forceChild = FORCE_SELF;
                }
                box.addElementChild(buildLayoutTree(child, childInherited, sheet, env, forceChild,
                        computed.fontSizePx(), childScope, interactionState, siblingInvalidation, childReusable));
                siblingForceDirty |= stateChanged;
            }
        }

        if (skipContents) {
            // 同上
        } else if (reusePseudos) {
            LayoutBox after = previousPseudo(previousChildren, pseudoElementId(node, PseudoType.AFTER));
            if (after != null) box.addElementChild(after);
        } else {
            ComputedStyle afterStyle = resolvePseudoStyle(node, PseudoType.AFTER, computed, sheet, env, childScope, interactionState);
            if (afterStyle != null) {
                box.addElementChild(buildPseudoBox(node, PseudoType.AFTER, afterStyle, hasInteractive, sheet, env,
                        childScope, interactionState, siblingInvalidation, childReusable, previousChildren));
            }
        }

        return box;
    }

    /** 持ち主の前回の子から、id が {@code pseudoId} の疑似要素の箱を探す。無ければ null。 */
    private static LayoutBox previousPseudo(List<LayoutBox> previousChildren, String pseudoId) {
        for (LayoutBox previous : previousChildren) {
            if (previous.node.isPseudoNode && pseudoId.equals(previous.node.getId())) return previous;
        }
        return null;
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
        return buildPseudoBox(owner, type, style, ownerInteractive, sheet, env, scope, interactionState,
                siblingInvalidation, null, List.of());
    }

    /**
     * {@code ::before} / {@code ::after} の箱を作る。持ち主の前回の子に同じ id の疑似要素があれば、
     * その合成ノードと箱を使い回す（中の文字は {@link BoxTreeBuilder} が組むたびに入れ直す）。
     */
    private static LayoutBox buildPseudoBox(HtmlNode owner, PseudoType type, ComputedStyle style, boolean ownerInteractive,
                                           StyleSheet sheet,
                                           Map<String, String> env, StyleScope scope,
                                           InteractionState interactionState, boolean siblingInvalidation,
                                           Map<HtmlNode, LayoutBox> reusable, List<LayoutBox> ownerPreviousChildren) {
        String pseudoId = pseudoElementId(owner, type);
        LayoutBox box = null;
        if (reusable != null) {
            for (LayoutBox previous : ownerPreviousChildren) {
                if (previous.node.isPseudoNode && pseudoId.equals(previous.node.getId())
                        && previous.node.children.size() == 1) {
                    box = previous;
                    break;
                }
            }
        }

        HtmlNode textNode;
        if (box != null) {
            box.node.parent = owner;
            box.elementChildren.clear();
            box.updateStyle(style);
            textNode = box.node.children.getFirst();
        } else {
            HtmlNode pseudo = new HtmlNode("span");
            pseudo.isPseudoNode = true;
            pseudo.parent = owner;
            pseudo.setId(pseudoId);

            textNode = new HtmlText("");
            textNode.parent = pseudo;
            textNode.setId(pseudo.getId() + "_txt");
            pseudo.children.add(textNode);
            box = new LayoutBox(pseudo, style);
        }
        box.hasInteractiveRules = ownerInteractive;
        box.addElementChild(buildLayoutTree(textNode, style.asMap(), sheet, env, FORCE_SUBTREE, style.fontSizePx(), scope,
                interactionState, siblingInvalidation, reusable));
        return box;
    }
}
