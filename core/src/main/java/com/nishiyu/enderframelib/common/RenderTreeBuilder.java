package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.paint.PaintOrder;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.model.ContainerBox;
import com.nishiyu.enderframelib.core.css.CounterStyles;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.css.model.ElementState;
import com.nishiyu.enderframelib.core.css.SelectorMatcher;
import com.nishiyu.enderframelib.core.css.model.StateLookup;
import com.nishiyu.enderframelib.core.css.StyleResolver;
import com.nishiyu.enderframelib.core.css.StyleResolver.PseudoType;
import com.nishiyu.enderframelib.core.css.StyleSet;
import com.nishiyu.enderframelib.core.css.StyleSheet;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.HtmlText;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.layout.BoxTreeBuilder;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.kernel.compiler.StateStyleMemo;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** DOM と計算済みスタイルから箱の木（{@link LayoutBox}）を組む: 要素・疑似要素（{@code ::before} / {@code ::after}・{@code ::first-line} ほか）の箱と、{@code content-visibility: auto} で後回しにした中身の具現化。入口は {@link RenderPipeline#buildLayoutTree}。A8 で {@code RenderPipeline} から分けた。 */
final class RenderTreeBuilder {

    private RenderTreeBuilder() { }

    /**
     * {@code content-visibility: auto} により遅延構築されていた {@code box} の中身（疑似要素＋子要素）をその場で組み立てる。
     */
    static void materializeAutoContent(LayoutBox box, StyleSheet sheet, Map<String, String> env,
                                               int rootW, int rootH, InteractionState interactionState) {
        if (box == null || !box.childrenDeferred || box.node == null || sheet == null) return;
        box.childrenDeferred = false;
        HtmlNode node = box.node;
        ComputedStyle computed = box.style;

        ArrayList<LayoutBox> chain = new ArrayList<>();
        for (LayoutBox b = box.parent; b != null; b = b.parent) chain.add(b);
        RenderPipeline.StyleScope scope = RenderPipeline.StyleScope.initial(rootW, rootH);
        for (int i = chain.size() - 1; i >= 0; i--) {
            LayoutBox b = chain.get(i);
            if (!b.isAnonymous && b.node != null) scope = scope.enter(b.node, b.style);
        }
        RenderPipeline.StyleScope childScope = scope.enter(node, computed);
        ContainerBox parentContainer = interactionState != null && box.parent != null && box.parent.node != null
                ? interactionState.nearestSizeContainer(box.parent.node)
                : ContainerBox.NONE_SIZE;
        boolean parentMoving = false;
        for (int i = chain.size() - 1; i >= 0; i--) {
            LayoutBox b = chain.get(i);
            if (b.movingStamped ? b.selfOrAncestorMoving : PaintOrder.hasMovingProps(b.style)) {
                parentMoving = true;
                break;
            }
        }
        boolean selfOrAncestorMoving = stampMoving(box, computed, parentMoving);
        ContainerBox selfContainer = RenderPipeline.containerForSelf(node, computed, parentContainer, interactionState);
        boolean siblingInvalidation = StyleResolver.hasSiblingStateRules(sheet);
        boolean hasInteractive = box.hasInteractiveRules;
        Map<String, String> childInherited = computed.asMap();

        box.elementChildren.clear();
        ComputedStyle beforeStyle = resolvePseudoStyle(node, PseudoType.BEFORE, computed, sheet, env, childScope, interactionState, selfContainer);
        if (beforeStyle != null) {
            box.addElementChild(buildPseudoBox(node, PseudoType.BEFORE, beforeStyle, hasInteractive, sheet, env,
                    childScope, interactionState, siblingInvalidation, selfContainer, selfOrAncestorMoving, false, null));
        }

        if (!com.nishiyu.enderframelib.core.dom.node.ElementTraits.hasOpaqueContent(node.tag)) {
            boolean isOpen = interactionState != null
                    ? interactionState.stateOf(node).open()
                    : node.attrs.containsKey("open");
            for (int ci = 0, cn = node.children.size(); ci < cn; ci++) {
                HtmlNode child = node.children.get(ci);
                if (child.tag.equals("#text") && child.text.isEmpty()) continue;
                if (com.nishiyu.enderframelib.core.dom.node.ElementTraits.isHiddenByClosedDetails(node, isOpen, child)) continue;
                box.addElementChild(buildLayoutTree(child, childInherited, sheet, env, RenderPipeline.FORCE_SUBTREE,
                        computed.fontSizePx(), childScope, interactionState, siblingInvalidation,
                        null, null, false, null, selfContainer, selfOrAncestorMoving));
            }
        }

        ComputedStyle afterStyle = resolvePseudoStyle(node, PseudoType.AFTER, computed, sheet, env, childScope, interactionState, selfContainer);
        if (afterStyle != null) {
            box.addElementChild(buildPseudoBox(node, PseudoType.AFTER, afterStyle, hasInteractive, sheet, env,
                    childScope, interactionState, siblingInvalidation, selfContainer, selfOrAncestorMoving, false, null));
        }

        BoxTreeBuilder.build(box, CounterStyles.of(sheet.counterStyles));
        box.linkParents();
        box.recomputeChildFlags();
    }

    /**
     * 箱の木を組む。{@code prevReusable} があれば、同じ DOM の節の前回の箱を作り直さずに使い回す（React の差分適用と同じく、
     * 変わらない部分の実体を保つ）。同時に {@code nextReusable} があれば次フレーム用の再利用テーブルを構築中に登録し、
     * 木組前の全木走査（collectReusableBoxes）を不要にする。表の中は使い回さない（{@link LayoutBox#recordReusableForBuild} を参照）。
     */
    static LayoutBox buildLayoutTree(HtmlNode node, Map<String, String> inheritedStyle,
                                             StyleSheet sheet,
                                             Map<String, String> env, int force,
                                             float parentFontSize, RenderPipeline.StyleScope scope, InteractionState interactionState,
                                             boolean siblingInvalidation,
                                             LayoutBox.ReusableTable prevReusable,
                                             LayoutBox.ReusableTable nextReusable,
                                             boolean inTableScope,
                                             LayoutBox directPrev,
                                             ContainerBox parentSizeContainer, boolean ancestorHasMoving) {
        return buildLayoutTree(node, inheritedStyle, sheet, env, force, parentFontSize, scope, interactionState, siblingInvalidation, prevReusable, nextReusable, inTableScope, directPrev, parentSizeContainer, ancestorHasMoving, null);
    }

    /** {@code partial} が null でなければ、台帳に触れられていない部分木は辿らず前回の箱を返す（{@link PartialBuild}）。 */
    static LayoutBox buildLayoutTree(HtmlNode node, Map<String, String> inheritedStyle,
                                             StyleSheet sheet,
                                             Map<String, String> env, int force,
                                             float parentFontSize, RenderPipeline.StyleScope scope, InteractionState interactionState,
                                             boolean siblingInvalidation,
                                             LayoutBox.ReusableTable prevReusable,
                                             LayoutBox.ReusableTable nextReusable,
                                             boolean inTableScope,
                                             LayoutBox directPrev,
                                             ContainerBox parentSizeContainer, boolean ancestorHasMoving,
                                     PartialBuild partial) {
        InteractionState.StateFlags flags = interactionState != null ? interactionState.getOrCreate(node) : null;
        RenderPipeline.StyleCache cache = force != RenderPipeline.FORCE_NONE ? RenderPipeline.StyleCache.INVALID : RenderPipeline.styleCacheState(flags, parentFontSize, scope);
        boolean needsRestyle = cache != RenderPipeline.StyleCache.VALID;
        var stats = RenderPipeline.stats();
        stats.boxes++;
        if (needsRestyle) stats.restyled++;

        ComputedStyle computed;
        boolean hasInteractive;
        int childForce = RenderPipeline.FORCE_NONE;
        boolean childForceIfInherits = false;
        boolean subtreeDirty = (flags != null && flags.subtreeDirty()) || force == RenderPipeline.FORCE_SUBTREE;
        if (partial != null && !needsRestyle && !subtreeDirty && !inTableScope && !flags.dirtyBelow()) {
            LayoutBox kept = partial.keep(node, prevReusable, ancestorHasMoving, flags.computedStyle);
            if (kept != null) {
                stats.reused++;
                return kept;
            }
        }

        if (needsRestyle) {
            boolean tk = RenderPipeline.ctx().flags().trackAllocations();
            long k0 = tk ? RenderPipeline.threadAllocatedBytes() : 0;
            ContainerBox container = parentSizeContainer;
            ComputedStyle previous = flags != null ? flags.computedStyle : null;
            ComputedStyle.TextChild textEntry = RenderPipeline.textNodeStyle(node, inheritedStyle, sheet, parentFontSize, scope);
            StyleSet styleSet = textEntry != null ? null : StyleResolver.resolveStyleSet(node, inheritedStyle, sheet);
            boolean viewportDependent;
            long k1;
            long k2;
            if (textEntry != null) {
                // テキストの節は animation・transition を継承しないので動かない（アニメーションの確認も飛ばす）
                viewportDependent = textEntry.viewportDependent();
                computed = textEntry.style();
                k1 = k2 = tk ? RenderPipeline.threadAllocatedBytes() : 0;
            } else if (interactionState != null) {
                StateStyleMemo.Entry entry = RenderPipeline.styleEntry(node, styleSet, inheritedStyle, sheet, env, parentFontSize,
                        scope, interactionState, container, force, flags, false);
                viewportDependent = entry.viewportDependent();
                computed = entry.computed();
                k1 = k2 = tk ? RenderPipeline.threadAllocatedBytes() : 0;
            } else {
                Map<String, String> rawStyle = styleSet.resolve(ElementState.NONE);
                if (node.attrs.containsKey("hidden")) {
                    rawStyle.put(CssProperty.DISPLAY, "none");
                }
                viewportDependent = flags != null && (node.parent == null || StyleResolver.rawDependsOnViewport(rawStyle));
                k1 = tk ? RenderPipeline.threadAllocatedBytes() : 0;
                computed = RenderPipeline.computeStyleOwned(rawStyle, inheritedStyle, sheet, env, parentFontSize, scope, container);
                k2 = tk ? RenderPipeline.threadAllocatedBytes() : 0;
            }
            Map<String, String> animation = textEntry != null ? Map.of()
                    : RenderPipeline.animationValues(node, previous, computed, inheritedStyle, sheet, interactionState);
            if (!animation.isEmpty()) {
                computed = RenderPipeline.recomputeWithAnimation(node, styleSet, inheritedStyle, sheet, env, parentFontSize, scope,
                        interactionState, container, animation);
                viewportDependent |= StyleResolver.rawDependsOnViewport(animation);
            }
            hasInteractive = styleSet != null && styleSet.hasInteractiveRules();
            if (tk) {
                RenderPipeline.stats().allocResolve += k1 - k0;
                RenderPipeline.stats().allocCompute += k2 - k1;
                RenderPipeline.stats().allocAnimated += RenderPipeline.threadAllocatedBytes() - k2;
            }

            RenderPipeline.Propagation propagation = RenderPipeline.propagationOf(previous, computed);
            childForce = RenderPipeline.childForceOf(subtreeDirty, propagation, sheet);
            childForceIfInherits = propagation == RenderPipeline.Propagation.NON_INHERITED;
            if (flags != null) RenderPipeline.cacheStyle(flags, node, viewportDependent, computed, parentFontSize, scope, hasInteractive, sheet);
        } else {
            computed = flags.computedStyle;
            hasInteractive = flags.hasInteractiveRules;
            childForce = subtreeDirty ? RenderPipeline.FORCE_SUBTREE : RenderPipeline.FORCE_NONE;
            flags.clearSubtreeDirty();
            flags.clearDirtyBelow();
        }
        Map<String, String> childInherited = computed.asMap();
        RenderPipeline.StyleScope childScope = scope.enter(node, computed);

        LayoutBox box = directPrev != null && directPrev.node == node
                ? directPrev
                : (prevReusable != null ? prevReusable.take(node) : null);
        LayoutBox prevBefore = null;
        LayoutBox prevAfter = null;
        boolean reusedBox = false;
        if (box != null) {
            if (box.hasPseudoElementChild) {
                List<LayoutBox> ec = box.elementChildren;
                String ownerKey = node.key();
                for (int i = 0, n = ec.size(); i < n; i++) {
                    LayoutBox ch = ec.get(i);
                    if (!ch.node.isPseudoNode) continue;
                    String k = ch.node.key();
                    if (prevBefore == null && isPseudoKey(k, ownerKey, "_before")) prevBefore = ch;
                    else if (prevAfter == null && isPseudoKey(k, ownerKey, "_after")) prevAfter = ch;
                }
                box.hasPseudoElementChild = false;
            }
            if (partial != null) partial.forgetRemoved(box, node);
            box.elementChildren.clear();
            box.refreshStyle(computed);
            if (box.display().isNone() || LayoutBox.isInternalTableBox(box)) {
                box = null;
                prevBefore = null;
                prevAfter = null;
            } else {
                box.resetEngineOutputs();
                reusedBox = true;
                stats.reused++;
            }
        }
        if (box == null) {
            box = new LayoutBox(node, computed);
        }
        // 表のエンジンは中の箱へ直接書き込むので、表の中は作り直す。display: none の部分木は測られないので、
        // 使い回すと前回の寸法が残る（新しく作った箱は 0）。コンテナクエリの寸法などが読むので作り直す
        boolean entersTable = LayoutBox.recordReusableForBuild(inTableScope ? null : nextReusable, box);
        if (partial != null) partial.visited(box);
        boolean childInTableScope = inTableScope || entersTable;
        boolean stopChildReuse = childInTableScope || box.display().isNone();
        LayoutBox.ReusableTable childPrevReusable = stopChildReuse ? null : prevReusable;
        LayoutBox.ReusableTable childNextReusable = childInTableScope ? null : nextReusable;
        box.hasInteractiveRules = hasInteractive;
        boolean selfOrAncestorMoving = stampMoving(box, computed, ancestorHasMoving);
        // 持ち主のスタイルのキャッシュが有効なら、状態も祖先・兄弟も前回のままなので疑似要素の規則の当たり方も前回のまま。
        // 疑似要素だけがビューポート単位を使うこともあるので、ビューポートが同じときに限って前回の疑似要素をそのまま使う
        // （再スタイルの経路 restyleBoxRecursive も、汚れていない要素の疑似要素は解き直さない）
        long viewportKey = ((long) scope.viewportW() << 32) | (scope.viewportH() & 0xffffffffL);
        ContainerBox selfContainer = RenderPipeline.containerForSelf(node, computed, parentSizeContainer, interactionState);
        boolean reusePseudos = reusedBox && !needsRestyle && box.pseudoStylesViewport == viewportKey;
        if (!reusePseudos) {
            resolveHighlightPseudos(box, computed, sheet, env, childScope, interactionState, selfContainer);
            box.pseudoStylesViewport = viewportKey;
        }

        // content-visibility: hidden の中身は測らず描かない（箱の木に入れない。疑似要素も同じ）
        // content-visibility: auto で前フレーム未表示かつ強制関連でない要素も中身を遅延する
        boolean skipContents = computed.skipsContents();
        boolean isCvAuto = !skipContents && computed.isContentVisibilityAuto();
        boolean deferContents = false;
        if (isCvAuto) {
            box.markContentAutoSubtree();
            box.originallyDeferred = true;
            boolean forceRel = RenderPipeline.isFocusWithin(node, interactionState);
            box.cvForceRelevant = forceRel;
            if (flags != null) {
                if (Float.isNaN(box.rememberedInnerW)) box.rememberedInnerW = flags.lastRememberedWidth;
                if (Float.isNaN(box.rememberedInnerH)) box.rememberedInnerH = flags.lastRememberedHeight;
            }
            boolean wasActive = (flags != null && flags.contentAutoActive) || forceRel;
            if (!wasActive) {
                box.contentAutoSkipped = true;
                box.childrenDeferred = true;
                deferContents = true;
            } else {
                box.contentAutoSkipped = false;
                box.childrenDeferred = false;
            }
        } else {
            box.contentAutoSkipped = false;
            box.childrenDeferred = false;
            box.originallyDeferred = false;
            box.cvForceRelevant = false;
        }
        boolean omitChildren = skipContents || deferContents;
        if (omitChildren) {
            // 子を持たない（または遅延構築する）箱として扱う
        } else if (reusePseudos) {
            if (prevBefore != null) {
                stampMovingSubtree(prevBefore, selfOrAncestorMoving);
                box.addElementChild(prevBefore);
            }
        } else {
            ComputedStyle beforeStyle = resolvePseudoStyle(node, PseudoType.BEFORE, computed, sheet, env, childScope, interactionState, selfContainer);
            if (beforeStyle != null) {
                box.addElementChild(buildPseudoBox(node, PseudoType.BEFORE, beforeStyle, hasInteractive, sheet, env,
                        childScope, interactionState, siblingInvalidation, selfContainer, selfOrAncestorMoving, !stopChildReuse, prevBefore));
            }
        }

        // svg の子（図形）は箱にしない。svg 要素が木を読んで自分でラスタライズする
        if (!omitChildren && !com.nishiyu.enderframelib.core.dom.node.ElementTraits.hasOpaqueContent(node.tag)) {
            boolean isOpen = interactionState != null
                    ? interactionState.stateOf(node).open()
                    : node.attrs.containsKey("open");
            boolean siblingForceDirty = false;
            // 自分が汚れていない親は、触れられた子だけを辿り、他の子は前回の箱をそのまま並べる（部分構築）
            java.util.Set<HtmlNode> touchedKids = partial != null && !needsRestyle && !subtreeDirty && flags != null && !flags.dirtyBelow() ? partial.touchedChildren(node) : null;
            for (int ci = 0, cn = node.children.size(); ci < cn; ci++) {
                HtmlNode child = node.children.get(ci);
                if (child.tag.equals("#text") && child.text.isEmpty()) continue;
                if (com.nishiyu.enderframelib.core.dom.node.ElementTraits.isHiddenByClosedDetails(node, isOpen, child)) continue;
                boolean stateChanged = siblingInvalidation && RenderPipeline.isStateDirty(interactionState, child);
                int forceChild = siblingForceDirty ? RenderPipeline.FORCE_SUBTREE : childForce;
                if (forceChild == RenderPipeline.FORCE_NONE && childForceIfInherits && interactionState != null) {
                    InteractionState.StateFlags childFlags = interactionState.get(child);
                    if (childFlags != null && childFlags.inheritsExplicitly) forceChild = RenderPipeline.FORCE_SELF;
                }
                LayoutBox keptKid = touchedKids != null && forceChild == RenderPipeline.FORCE_NONE
                        ? partial.keepFast(child, childPrevReusable, touchedKids, selfOrAncestorMoving, interactionState) : null;
                box.addElementChild(keptKid != null ? keptKid : buildLayoutTree(child, childInherited, sheet, env, forceChild,
                        computed.fontSizePx(), childScope, interactionState, siblingInvalidation,
                        childPrevReusable, childNextReusable, childInTableScope, null,
                        selfContainer, selfOrAncestorMoving, partial));
                siblingForceDirty |= stateChanged;
            }
        }

        if (omitChildren) {
            // 同上
        } else if (reusePseudos) {
            if (prevAfter != null) {
                stampMovingSubtree(prevAfter, selfOrAncestorMoving);
                box.addElementChild(prevAfter);
            }
        } else {
            ComputedStyle afterStyle = resolvePseudoStyle(node, PseudoType.AFTER, computed, sheet, env, childScope, interactionState, selfContainer);
            if (afterStyle != null) {
                box.addElementChild(buildPseudoBox(node, PseudoType.AFTER, afterStyle, hasInteractive, sheet, env,
                        childScope, interactionState, siblingInvalidation, selfContainer, selfOrAncestorMoving, !stopChildReuse, prevAfter));
            }
        }

        return box;
    }

    static void resolveHighlightPseudos(LayoutBox box, ComputedStyle computed,
                                                StyleSheet sheet,
                                                Map<String, String> env, RenderPipeline.StyleScope childScope,
                                                InteractionState interactionState, ContainerBox selfContainer) {
        HtmlNode node = box.node;
        ComputedStyle prevMarker = box.markerStyle, prevPlaceholder = box.placeholderStyle, prevBackdrop = box.backdropStyle, prevFirstLine = box.firstLineStyle;
        box.markerStyle = box.display().listItem()
                ? resolvePseudoStyle(node, PseudoType.MARKER, computed, sheet, env, childScope, interactionState, selfContainer)
                : null;
        boolean textInput = com.nishiyu.enderframelib.core.dom.node.ElementTraits.isTextEntryTag(node.tag);
        box.placeholderStyle = textInput
                ? resolvePseudoStyle(node, PseudoType.PLACEHOLDER, computed, sheet, env, childScope, interactionState, selfContainer)
                : null;
        // Top Layer の箱（dialog・popover）だけが ::backdrop を持つ
        boolean topLayerCapable = "dialog".equals(node.tag) || node.attrs != null && node.attrs.containsKey("popover");
        box.backdropStyle = topLayerCapable
                ? resolvePseudoStyle(node, PseudoType.BACKDROP, computed, sheet, env, childScope, interactionState, selfContainer)
                : null;
        box.firstLineStyle = isFirstLineContainer(box)
                ? resolvePseudoStyle(node, PseudoType.FIRST_LINE, computed, sheet, env, childScope, interactionState, selfContainer)
                : null;
        // 疑似要素のスタイルは updateStyle の外で代入される。値として変わったときだけ描画の印を進める（同じなら描画も同じ）
        if (!sameValue(prevMarker, box.markerStyle) || !sameValue(prevPlaceholder, box.placeholderStyle)
                || !sameValue(prevBackdrop, box.backdropStyle) || !sameValue(prevFirstLine, box.firstLineStyle)) {
            box.touchPaint();
        }
    }

    private static boolean sameValue(ComputedStyle a, ComputedStyle b) {
        if (a == b) return true;
        return a != null && b != null && a.fontSizePx() == b.fontSizePx() && a.rootFontSizePx() == b.rootFontSizePx() && ComputedStyle.sameDeclarations(a, b);
    }

    private static boolean isFirstLineContainer(LayoutBox box) {
        return box.isFirstLineContainer();
    }

    static void addFirstLineTextBoxes(LayoutBox parent, List<LayoutBox> out) {
        for (int i = 0, n = parent.children.size(); i < n; i++) {
            LayoutBox c = parent.children.get(i);
            if (!c.inlineFragments.isEmpty() && !c.isAnonymous) out.add(c);
            if (c.isAnonymous || c.isInlineFlow()) {
                addFirstLineTextBoxes(c, out);
            }
        }
    }

    static ComputedStyle resolvePseudoStyle(HtmlNode owner, PseudoType type, ComputedStyle ownerStyle,
                                                    StyleSheet sheet,
                                                    Map<String, String> env, RenderPipeline.StyleScope scope,
                                                    InteractionState interactionState, ContainerBox selfContainer) {
        StateLookup lookup =
                interactionState != null ? interactionState : SelectorMatcher.STATIC_ATTRIBUTES;
        return StyleResolver.resolvePseudoStyle(owner, type, sheet, lookup, ownerStyle, env,
                scope.rootFontSize(), scope.rootLineHeight(), scope.viewportW(), scope.viewportH(), selfContainer);
    }

    /** {@code key.equals(ownerKey + suffix)} を文字列を作らずに判定する。 */
    static boolean isPseudoKey(String key, String ownerKey, String suffix) {
        return key != null && key.length() == ownerKey.length() + suffix.length()
                && key.startsWith(ownerKey) && key.endsWith(suffix);
    }

    private static String pseudoElementId(HtmlNode owner, PseudoType type) {
        return owner.key() + (type == PseudoType.BEFORE ? "_before" : "_after");
    }

    static LayoutBox buildPseudoBox(HtmlNode owner, PseudoType type, ComputedStyle style, boolean ownerInteractive,
                                           StyleSheet sheet,
                                           Map<String, String> env, RenderPipeline.StyleScope scope,
                                           InteractionState interactionState, boolean siblingInvalidation,
                                           ContainerBox ownerSizeContainer, boolean ownerSelfOrAncestorMoving) {
        return buildPseudoBox(owner, type, style, ownerInteractive, sheet, env, scope, interactionState,
                siblingInvalidation, ownerSizeContainer, ownerSelfOrAncestorMoving, false, null);
    }

    /**
     * {@code ::before} / {@code ::after} の箱を作る。持ち主の前回の子に同じ id の疑似要素があれば、
     * その合成ノードと箱を使い回す（中の文字は {@link BoxTreeBuilder} が組むたびに入れ直す）。
     */
    static LayoutBox buildPseudoBox(HtmlNode owner, PseudoType type, ComputedStyle style, boolean ownerInteractive,
                                           StyleSheet sheet,
                                           Map<String, String> env, RenderPipeline.StyleScope scope,
                                           InteractionState interactionState, boolean siblingInvalidation,
                                           ContainerBox ownerSizeContainer, boolean ownerSelfOrAncestorMoving,
                                           boolean canReuse, LayoutBox prevPseudo) {
        LayoutBox box = null;
        LayoutBox prevTextBox = null;
        if (canReuse && prevPseudo != null && prevPseudo.node.children.size() == 1) {
            box = prevPseudo;
            prevTextBox = box.elementChildren.isEmpty() ? null : box.elementChildren.getFirst();
        }

        HtmlNode textNode;
        if (box != null) {
            box.node.parent = owner;
            box.elementChildren.clear();
            box.hasPseudoElementChild = false;
            box.updateStyle(style);
            textNode = box.node.children.getFirst();
        } else {
            String pseudoId = pseudoElementId(owner, type);
            HtmlNode pseudo = new HtmlNode("span");
            pseudo.isPseudoNode = true;
            pseudo.parent = owner;
            pseudo.setId(pseudoId);

            textNode = new HtmlText("");
            textNode.parent = pseudo;
            textNode.setId(pseudo.key() + "_txt");
            pseudo.children.add(textNode);
            box = new LayoutBox(pseudo, style);
        }
        box.hasInteractiveRules = ownerInteractive;
        boolean pseudoMoving = stampMoving(box, style, ownerSelfOrAncestorMoving);
        box.addElementChild(buildLayoutTree(textNode, style.asMap(), sheet, env, RenderPipeline.FORCE_SUBTREE, style.fontSizePx(), scope,
                interactionState, siblingInvalidation, null, null, false, prevTextBox, ownerSizeContainer, pseudoMoving));
        return box;
    }

    static boolean stampMoving(LayoutBox box, ComputedStyle computed, boolean ancestorHasMoving) {
        boolean selfOrAncestor = ancestorHasMoving || PaintOrder.hasMovingProps(computed);
        box.ancestorMoving = ancestorHasMoving;
        box.selfOrAncestorMoving = selfOrAncestor;
        box.movingStamped = true;
        return selfOrAncestor;
    }

    private static void stampMovingSubtree(LayoutBox box, boolean ancestorHasMoving) {
        if (box.movingStamped && box.ancestorMoving == ancestorHasMoving) return;
        boolean selfOrAncestor = stampMoving(box, box.style, ancestorHasMoving);
        for (int i = 0, n = box.elementChildren.size(); i < n; i++) {
            stampMovingSubtree(box.elementChildren.get(i), selfOrAncestor);
        }
    }
}
