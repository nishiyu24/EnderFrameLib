package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.css.CounterStyles;
import com.nishiyu.enderframelib.core.layout.BoxTreeBuilder;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;

import java.util.*;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.RenderPipeline;
import com.nishiyu.enderframelib.core.paint.model.*;

/**
 * {@link UiView} の局所パッチ: 文字だけの変更（{@code TEXT_PATCH}）・子の増減（{@code CHILD_PATCH}）・その場での再レイアウトを、
 * 文書全体の再レイアウトなしで表示リストへ反映する。どれも成り立たなければ false（または理由の文字列）を返し、呼び出し側が
 * 全体の作り直しへ落ちる。計数器と切り分けのスイッチは公開の診断口なので {@code UiView} に残した。
 * A8 で {@code UiView} から切り出した。
 */
final class LocalPatches {
    private final UiView view;

    LocalPatches(UiView view) {
        this.view = view;
    }

    boolean combinedTextPatch;

    boolean tryTextPatch() {
        return tryTextPatch(false);
    }

    /** @param allowOtherDirty true なら、文字の節以外に汚れた節（これから再スタイルする）があってもよい。 */
    boolean tryTextPatch(boolean allowOtherDirty) {
        List<HtmlNode> changed;
        synchronized (view.pendingTextNodes) {
            changed = new ArrayList<>(view.pendingTextNodes);
            view.pendingTextNodes.clear();
        }
        Document doc = view.document;
        if (changed.isEmpty() || view.layoutRoot == null || doc == null || doc.root == null || doc.sheet == null) return false;
        if (doc.sheet.declaresContainers() || doc.sheet.usesContainerQueries()) return false;
        if (!allowOtherDirty && !view.interactionState.styleDirtyOnlyIn(changed)) return false;

        Collection<LayoutBox> patchedBoxes;
        Map<HtmlNode, LayoutBox> index = view.nodeBoxIndex();
        if (changed.size() == 1) {
            HtmlNode n = changed.get(0);
            if (n.text == null || n.text.isBlank() || !n.isConnected() || n.parent == null) return false;
            if (!isPatchableParent(n.parent)) return false;
            LayoutBox b = index.get(n);
            if (b == null || b.displayText() == null || b.displayText().isBlank()) return false;
            patchedBoxes = List.of(b);
        } else {
            Set<HtmlNode> wanted = Collections.newSetFromMap(new IdentityHashMap<>());
            for (int i = 0, cn = changed.size(); i < cn; i++) {
                HtmlNode n = changed.get(i);
                if (n.text == null || n.text.isBlank() || !n.isConnected() || n.parent == null) return false;
                if (!isPatchableParent(n.parent)) return false;
                wanted.add(n);
            }
            Map<HtmlNode, LayoutBox> found = new IdentityHashMap<>();
            for (HtmlNode n : wanted) {
                LayoutBox b = index.get(n);
                if (b != null || index.containsKey(n)) found.put(n, b);
            }
            if (found.size() != wanted.size() || found.containsValue(null)) return false;
            for (LayoutBox box : found.values()) {
                // 前回の文字が空白だけだった箱は、前回の木に今の形で入っていたか分からない
                if (box.displayText() == null || box.displayText().isBlank()) return false;
            }
            patchedBoxes = found.values();
        }

        long startedAt = view.clock.nanos();
        for (LayoutBox box : patchedBoxes) box.refreshStyle(box.style);
        view.applyOverrides();
        // 位置の決め直しを後回しにしている間は、箱は表示リストを作ったときのスクロール位置のまま。同じ位置へ一時的に戻して、その枠で直す
        boolean stale = view.layoutStale;
        Map<HtmlNode, float[]> scrollNow = null;
        Set<HtmlNode> scrollKeys = null;
        List<DisplayItem> elements = null;
        long layoutDone;
        try {
            if (stale) {
                scrollNow = view.scrollState.snapshot();
                scrollKeys = Collections.newSetFromMap(new IdentityHashMap<>());
                scrollKeys.addAll(scrollNow.keySet());
                scrollKeys.addAll(view.scrollAtBuild.keySet());
                for (HtmlNode k : scrollKeys) {
                    float[] f = view.scrollAtBuild.get(k);
                    view.scrollState.setScroll(k, f != null ? f[0] : 0f, f != null ? f[1] : 0f);
                }
            }
            RenderPipeline.relayoutInPlace(view.layoutRoot, view.rootW, view.rootH, view.layoutRoot.devicePixelRatio(), view.scrollState, view.fontResolver(),
                    patchedBoxes);
            for (int i = 0, cn = changed.size(); i < cn; i++) view.interactionState.clearStyleDirty(changed.get(i));
            view.publishLayoutToDom(); // 箱の木は作り替えていないので、節の箱の索引は捨てない
            layoutDone = view.clock.nanos();
            if (view.paintBuilder == null) UiView.SLICE_NO_BUILDER.incrementAndGet();
        } finally {
            if (stale) {
                for (HtmlNode k : scrollKeys) {
                    float[] n = scrollNow.get(k);
                    view.scrollState.setScroll(k, n != null ? n[0] : 0f, n != null ? n[1] : 0f);
                }
            }
        }
        if (stale && elements != null) { // 表示リストのスクロールの基準は動かさない（位置は後回しのまま）
            UiView.STALE_TEXT_PATCH_HITS.incrementAndGet();
            view.setRenderCache(elements);
            int nodes = patchedBoxes.size();
            view.lastBreakdown = () -> "stale-text-patch nodes=" + nodes + " layout=" + UiView.ms(startedAt, layoutDone) + "ms";
            view.applyOverrides();
            view.lastLayoutFinishedNanos = view.clock.nanos();
            view.lastLayoutCostNanos = view.lastLayoutFinishedNanos - startedAt;
            return true;
        }
        if (stale) view.ensureLayoutFresh(); // 局所パッチで済まなかったので、位置を決め直してから全体を描く
        if (elements == null) {
            elements = view.paintBoxes(view.layoutRoot, view.rootW, view.rootH, false);
        }
        if (elements == null) elements = new ArrayList<>();
        long paintDone = view.clock.nanos();
        view.publishBuilt(elements);
        int nodes = patchedBoxes.size();
        long publishDone = view.clock.nanos();
        view.lastBreakdown = () -> "text-patch nodes=" + nodes + " layout=" + UiView.ms(startedAt, layoutDone)
                + "ms paint=" + UiView.ms(layoutDone, paintDone) + "ms publish=" + UiView.ms(paintDone, publishDone) + "ms";
        view.applyOverrides();
        view.lastLayoutFinishedNanos = view.clock.nanos();
        view.lastLayoutCostNanos = view.lastLayoutFinishedNanos - startedAt;
        return true;
    }
    /** 1 回の局所パッチで直す親の最大数・親ごとに増減できる子の最大数（超えたら全体の再レイアウトの方が安い）。 */
    static final int CHILD_PATCH_MAX_PARENTS = 4;
    static final int CHILD_PATCH_MAX_CHANGES = 16;

    /** 子の並びが変わった親（同一性で比べる）。{@code synchronized (pendingChildParents)} で触る。 */
    final Set<HtmlNode> pendingChildParents = Collections.newSetFromMap(new IdentityHashMap<>());

    void requestChildPatch(HtmlNode parent) {
        boolean overflow;
        synchronized (pendingChildParents) {
            pendingChildParents.add(parent);
            overflow = pendingChildParents.size() > CHILD_PATCH_MAX_PARENTS;
        }
        if (overflow) {
            view.requestRender();
            return;
        }
        view.markInvalidation(UiView.InvalidationLevel.CHILD_PATCH);
        view.invalidated();
    }

    /** 1 つの親の子の増減の計画（読むだけ。適用は全ての親の計画が通ってから）。 */
    record ChildPlan(HtmlNode parent, LayoutBox parentBox, List<LayoutBox> removed, List<HtmlNode> added,
                             HtmlNode previousNode) {}

    /** 文書のこの子が、要素ツリーの箱になるか（{@link RenderPipeline#buildLayoutTree} の子の走査と同じ条件）。 */
    static boolean hasElementBox(HtmlNode child) {
        return !(child.tag.equals("#text") && child.text.isEmpty());
    }

    static boolean isBlankText(HtmlNode n) {
        return n.nodeType == NodeType.TEXT && n.text != null && n.text.isBlank();
    }

    ChildPlan planChildPatch(HtmlNode parent, Map<HtmlNode, LayoutBox> index) {
        if (parent.nodeType != NodeType.ELEMENT || !parent.isConnected()) return null;
        LayoutBox pb = index.get(parent);
        if (pb == null || !BoxTreeBuilder.canPatchChildren(pb)) return null;
        List<HtmlNode> dom = new ArrayList<>(parent.children.size());
        for (HtmlNode c : parent.children) if (hasElementBox(c)) dom.add(c);
        List<LayoutBox> old = pb.elementChildren;
        for (LayoutBox b : old) if (b.node == null || b.node.isPseudoNode) return null;
        int n = dom.size(), m = old.size();
        int prefix = 0;
        while (prefix < n && prefix < m && old.get(prefix).node == dom.get(prefix)) prefix++;
        int suffix = 0;
        while (suffix < n - prefix && suffix < m - prefix && old.get(m - 1 - suffix).node == dom.get(n - 1 - suffix)) suffix++;
        int removedCount = m - prefix - suffix, addedCount = n - prefix - suffix;
        if (removedCount + addedCount == 0 || removedCount + addedCount > CHILD_PATCH_MAX_CHANGES) return null;
        List<LayoutBox> removed = new ArrayList<>(old.subList(prefix, m - suffix));
        List<HtmlNode> added = new ArrayList<>(dom.subList(prefix, n - suffix));
        // 移動（同じ節が削除側と追加側の両方にいる）は箱を使い回す必要があるので扱わない
        for (LayoutBox r : removed) for (HtmlNode a : added) if (r.node == a) return null;
        for (HtmlNode a : added) {
            if (a.nodeType == NodeType.ELEMENT) continue;
            if (!isBlankText(a)) return null; // 文字は匿名ブロック・行の高さに響く: 全体に任せる
        }
        for (LayoutBox r : removed) {
            if (r.node.nodeType != NodeType.ELEMENT && !isBlankText(r.node)) return null;
            if (BoxTreeBuilder.affectsSiblingsOrPaintOrder(r)) return null; // float・位置指定の子を消すと、兄弟の行や描画順が変わる
        }
        return new ChildPlan(parent, pb, removed, added, prefix > 0 ? dom.get(prefix - 1) : null);
    }

    static void collectDescendants(HtmlNode n, Set<HtmlNode> out) {
        out.add(n);
        for (HtmlNode c : n.children) collectDescendants(c, out);
    }

    static void unindexBoxes(LayoutBox box, Map<HtmlNode, LayoutBox> index) {
        if (box.node != null && !box.isAnonymous) index.remove(box.node);
        for (LayoutBox c : box.children) unindexBoxes(c, index);
    }

    boolean tryChildPatch(List<HtmlNode> attrNodes, boolean repositionRequested) {
        String why = tryChildPatchReason(attrNodes, repositionRequested);
        UiView.lastChildPatchRejection = why == null ? "ok" : why;
        return why == null;
    }

    String tryChildPatchReason(List<HtmlNode> attrNodes, boolean repositionRequested) {
        List<HtmlNode> parents;
        synchronized (pendingChildParents) {
            parents = new ArrayList<>(pendingChildParents);
            pendingChildParents.clear();
        }
        Document doc = view.document;
        if (!com.nishiyu.enderframelib.core.kernel.Shortcut.CHILD_PATCH.on() || parents.isEmpty() || (!attrNodes.isEmpty() || repositionRequested) || view.layoutRoot == null
                || doc == null || doc.root == null || doc.sheet == null) {
            return "preconditions";
        }
        if (doc.sheet.declaresContainers() || doc.sheet.usesContainerQueries()) return "containers";
        if (doc.authorCss != null && doc.authorCss.contains("counter")) return "counters";

        Map<HtmlNode, LayoutBox> index = view.nodeBoxIndex();
        List<ChildPlan> plans = new ArrayList<>();
        Set<HtmlNode> allowed = Collections.newSetFromMap(new IdentityHashMap<>());
        for (HtmlNode p : parents) {
            ChildPlan plan = planChildPatch(p, index);
            if (plan == null) return "plan:" + p.getId();
            plans.add(plan);
            allowed.add(p);
            for (HtmlNode a : plan.added()) collectDescendants(a, allowed);
        }
        if (!view.interactionState.styleDirtyOnlyIn(allowed)) return "otherDirty";

        long startedAt = view.clock.nanos();
        // 1. 増える子の箱を単独で組む。差し込めない形ならここで退く（まだ木には触れていない）
        List<List<LayoutBox>> built = new ArrayList<>();
        for (ChildPlan plan : plans) {
            List<LayoutBox> boxes = new ArrayList<>();
            boolean ok = true;
            for (HtmlNode a : plan.added()) {
                LayoutBox box = RenderPipeline.buildDetachedBox(a, plan.parentBox(), doc.sheet, view.env, view.rootW, view.rootH, view.interactionState);
                boxes.add(box);
                if (a.nodeType == NodeType.ELEMENT && !BoxTreeBuilder.canInsertBuiltChild(plan.parentBox(), box)) ok = false;
            }
            built.add(boxes);
            if (!ok) {
                // 組んだ節の印は全体の組み立てで読み直させる
                for (List<LayoutBox> bs : built) for (LayoutBox b : bs) view.interactionState.markDirty(b.node);
                return "notInsertable";
            }
        }
        // 2. 木へ差し込む
        CounterStyles counterStyles = CounterStyles.of(doc.sheet.counterStyles);
        List<LayoutBox> changedParents = new ArrayList<>();
        for (int i = 0; i < plans.size(); i++) {
            ChildPlan plan = plans.get(i);
            LayoutBox pb = plan.parentBox();
            for (LayoutBox r : plan.removed()) {
                int at = BoxTreeBuilder.indexOfBox(pb.elementChildren, r);
                if (at >= 0) pb.elementChildren.remove(at);
                BoxTreeBuilder.removeBuiltChild(pb, r);
                if (view.layoutRoot != null) view.layoutRoot.unregisterReusableSubtree(r);
                unindexBoxes(r, index);
                index.remove(r.node);
            }
            LayoutBox previous = null;
            if (plan.previousNode() != null) {
                for (LayoutBox b : pb.elementChildren) if (b.node == plan.previousNode()) { previous = b; break; }
            }
            List<LayoutBox> boxes = built.get(i);
            boolean pbInTableScope = pb.isInTableScope();
            for (LayoutBox box : boxes) {
                int at = previous == null ? 0 : BoxTreeBuilder.indexOfBox(pb.elementChildren, previous) + 1;
                pb.elementChildren.add(at, box);
                box.parent = pb;
                if (box.node.nodeType == NodeType.ELEMENT) {
                    // 直前の箱のうち、組み立て済みの子の並びに入っているもの（空白の文字の箱は入っていない）
                    LayoutBox after = null;
                    for (int k = at - 1; k >= 0 && after == null; k--) {
                        LayoutBox cand = pb.elementChildren.get(k);
                        if (BoxTreeBuilder.indexOfBox(pb.children, cand) >= 0) after = cand;
                    }
                    BoxTreeBuilder.insertBuiltChild(pb, after, box, counterStyles);
                    index.put(box.node, box);
                    UiView.indexBoxes(box, index, false);
                }
                if (view.layoutRoot != null && !pbInTableScope) view.layoutRoot.registerReusableSubtree(box);
                previous = box;
            }
            changedParents.add(pb);
        }
        for (HtmlNode n : allowed) view.interactionState.clearStyleDirty(n);
        view.applyOverrides();
        // 3. 変わった親だけを測り直して配置し直す
        RenderPipeline.relayoutInPlace(view.layoutRoot, view.rootW, view.rootH, view.layoutRoot.devicePixelRatio(), view.scrollState, view.fontResolver(),
                changedParents, new java.util.HashSet<>(changedParents));
        view.publishLayoutToDom();
        long layoutDone = view.clock.nanos();
        List<DisplayItem> elements = null;
        if (view.paintBuilder == null) UiView.SLICE_NO_BUILDER.incrementAndGet();
        else if (plans.size() != 1) UiView.SLICE_MULTI_PLAN.incrementAndGet();
        if (elements == null) {
            elements = view.paintBoxes(view.layoutRoot, view.rootW, view.rootH, false);
        }
        if (elements == null) elements = new ArrayList<>();
        long paintDone = view.clock.nanos();
        view.publishBuilt(elements);
        int parentCount = plans.size();
        long publishDone = view.clock.nanos();
        view.lastBreakdown = () -> "child-patch parents=" + parentCount + " layout=" + UiView.ms(startedAt, layoutDone)
                + "ms paint=" + UiView.ms(layoutDone, paintDone) + "ms publish=" + UiView.ms(paintDone, publishDone) + "ms";
        view.applyOverrides();
        view.lastLayoutFinishedNanos = view.clock.nanos();
        view.lastLayoutCostNanos = view.lastLayoutFinishedNanos - startedAt;
        return null;
    }

    /**
     * 再スタイル済みの箱の木を、作り直さずに測り直して配置し直す（寸法の入力だけが変わったとき）。成功したら true。
     * 条件に合わないとき（false）は何も変えないので、呼び出し側が全体の再レイアウトに退く。
     * 箱の木の形を変える変化（表示の種類・生成内容など）は、呼び出し側が {@link RenderPipeline.RestyleResult#structural()} で除いてある。
     */
    boolean tryRelayoutInPlace() {
        Document doc = view.document;
        if (!com.nishiyu.enderframelib.core.EngineContext.current().flags().inPlaceRestyleRelayout() || view.layoutRoot == null || doc == null || doc.root == null
                || doc.sheet == null) {
            return false;
        }
        if (doc.sheet.declaresContainers() || doc.sheet.usesContainerQueries()) return false;
        long startedAt = view.clock.nanos();
        view.applyOverrides();
        List<LayoutBox> changedBoxes = new ArrayList<>(RenderPipeline.layoutChangedBoxes());
        RenderPipeline.relayoutInPlace(view.layoutRoot, view.rootW, view.rootH, view.layoutRoot.devicePixelRatio(), view.scrollState, view.fontResolver(),
                changedBoxes);
        view.setLayoutRoot(view.layoutRoot);
        long layoutDone = view.clock.nanos();
        // 再スタイルは変わった箱に updateStyle で印を付け、レイアウトは触った箱に印を付ける（検証モードが付け漏れを検出する）
        List<DisplayItem> elements = view.paintBoxes(view.layoutRoot, view.rootW, view.rootH, false);
        if (elements == null) elements = new ArrayList<>();
        long paintDone = view.clock.nanos();
        view.publishBuilt(elements);
        long publishDone = view.clock.nanos();
        view.lastBreakdown = () -> "inplace-relayout layout=" + UiView.ms(startedAt, layoutDone)
                + "ms paint=" + UiView.ms(layoutDone, paintDone) + "ms publish=" + UiView.ms(paintDone, publishDone) + "ms";
        view.applyOverrides();
        view.lastLayoutFinishedNanos = view.clock.nanos();
        view.lastLayoutCostNanos = view.lastLayoutFinishedNanos - startedAt;
        UiView.INPLACE_RELAYOUT_HITS.incrementAndGet();
        return true;
    }

    /** 文字の箱の親が、局所パッチしてよい要素か（文字が箱の寸法以外の意味を持つ要素を除く）。 */
    static boolean isPatchableParent(HtmlNode parent) {
        if (parent.nodeType != NodeType.ELEMENT) return false;
        String tag = parent.tag == null ? "" : parent.tag.toLowerCase(Locale.ROOT);
        return switch (tag) {
            case "textarea", "select", "option", "optgroup", "style", "script", "title", "noscript", "svg", "template",
                 "table", "thead", "tbody", "tfoot", "tr", "td", "th", "caption", "colgroup", "col",
                 "legend", "summary", "details", "pre" -> false;
            default -> true;
        };
    }

    /** {@code wanted} の節が持つ文字の箱（匿名でない）を集める。表の中に入ったら何も集めない（退く）。 */
    static void collectTextBoxes(LayoutBox box, Set<HtmlNode> wanted, Map<HtmlNode, LayoutBox> out, boolean inTable) {
        boolean table = inTable || LayoutBox.isTableBox(box);
        for (LayoutBox child : box.children) {
            if (child.node != null && !child.isAnonymous && wanted.contains(child.node)) {
                if (table) {
                    out.put(child.node, null); // 大きさを合わせて失敗させる印（null は下で弾く）
                    continue;
                }
                out.put(child.node, child);
            }
            collectTextBoxes(child, wanted, out, table);
        }
    }
}
