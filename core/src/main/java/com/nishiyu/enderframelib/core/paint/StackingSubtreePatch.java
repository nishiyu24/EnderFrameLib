package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;

import java.util.ArrayList;
import java.util.List;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;
import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;
import static com.nishiyu.enderframelib.core.paint.PaintOrder.canvasBackgroundSource;
import com.nishiyu.enderframelib.core.paint.RenderTask;

/**
 * スタッキングコンテキストの部分木だけを組み直す差分パッチ（B12。{@link DisplayListBuilder#patchBoxes} の近道の 1 つ）。
 *
 * <p>変わった箱を含み、前後とも変わらないいちばん近いスタッキングコンテキストの部分木だけを、全体の作り直しと同じ手順で
 * 組み直して差し替える。根しか無い文書は全体へ落ちる。A8 で {@code DisplayListBuilder} から切り出した。</p>
 */
final class StackingSubtreePatch {
    private final DisplayListBuilder builder;

    StackingSubtreePatch(DisplayListBuilder builder) {
        this.builder = builder;
    }

    /**
     * 箱のスライスの差し替えでは表せない変更（スタッキングコンテキストを作る・やめる箱。{@code :hover} で {@code transform} が付くボタンなど）を、
     * その箱を含む、前後とも変わらないいちばん近いスタッキングコンテキストの部分木だけを組み直して表す。スタッキングコンテキストの
     * 項目は表示リストの中で連続する（付録 E）ので、その範囲を全体の作り直しと同じ手順（{@link PaintOrder#stackingSubtreeTasks} →
     * {@link DisplayListBuilder#executeTasks}）で作り直して差し替え、後ろの箱の位置をずらす。残りの変わった箱は従来の箱のスライスの差し替え。
     * 成り立たなければ何も変えずに null（呼び出し側が全体を作り直す）。
     */
    List<DisplayItem> patchViaStackingSubtrees(java.util.Collection<LayoutBox> boxes) {
        if (builder.currentDisplayList == null || builder.currentDisplayList.isEmpty() || builder.currentRoot == null || boxes == null || boxes.isEmpty()) return null;
        if (!(builder.currentDisplayList instanceof PaintSupport.CaptureList) && !(builder.currentDisplayList instanceof ArrayList)) return null;
        // 1. スタッキングコンテキストにかかわる箱ごとに、組み直す部分木の根を決める（入れ子なら外側だけ）
        List<LayoutBox> roots = new ArrayList<>(2);
        List<LayoutBox> rest = new ArrayList<>(boxes.size());
        for (LayoutBox b : boxes) {
            if (b == null) continue;
            BoxMemo m = builder.boxMemo.get(b);
            boolean stacking = b.style != null && builder.order.createsStackingContext(b, b.style)
                    || (m != null && m.style != null && builder.order.createsStackingContext(b, m.style));
            if (!stacking) {
                rest.add(b);
                continue;
            }
            LayoutBox root = stableStackingAncestor(b, boxes);
            if (root == null) return null;
            boolean covered = false;
            for (int i = roots.size() - 1; i >= 0; i--) {
                LayoutBox r = roots.get(i);
                if (r == root || isAncestorOrSelf(r, root)) { covered = true; break; }
                if (isAncestorOrSelf(root, r)) roots.remove(i);
            }
            if (!covered) roots.add(root);
        }
        if (roots.isEmpty()) return null;
        rest.removeIf(b -> { for (LayoutBox r : roots) if (isAncestorOrSelf(r, b)) return true; return false; });
        // 2. 何かを変える前に、全部が成り立つかを確かめる
        int[][] ranges = new int[roots.size()][];
        for (int i = 0; i < roots.size(); i++) {
            if ((ranges[i] = stackingRange(roots.get(i))) == null) return null;
        }
        for (int i = 0; i < roots.size(); i++) {
            for (int j = i + 1; j < roots.size(); j++) {
                if (ranges[i][0] < ranges[j][1] && ranges[j][0] < ranges[i][1]) return null; // 重なる範囲（念のため）
            }
        }
        if (!rest.isEmpty() && builder.preparePatchBoxes(rest) == null) return null;

        builder.decor.setDevicePixelRatio(builder.currentRoot.devicePixelRatio);
        boolean relMode = DisplayListBuilder.parentRelativeSource.getAsBoolean();
        builder.parentRelative = relMode;
        builder.order.setParentRelative(relMode);
        // 3. 部分木を組み直して差し替える（範囲は直前に取り直す。前の差し替えで後ろがずれる）
        for (LayoutBox root : roots) {
            int[] range = stackingRange(root);
            if (range == null) throw new IllegalStateException("組み直す範囲が途中で崩れた: " + root);
            rebuildStackingSubtree(root, range);
            DisplayListBuilder.STAT_STACKING_PATCH_HITS.increment();
        }
        // 4. 残りは箱のスライスの差し替え
        List<LayoutBox> sortedRest = rest.isEmpty() ? List.of() : builder.preparePatchBoxes(rest);
        if (sortedRest == null) throw new IllegalStateException("組み直しのあとで箱のスライスの差し替えが成り立たない");
        builder.patchSortedSlices(sortedRest, relMode);

        List<LayoutBox> all = new ArrayList<>(roots);
        all.addAll(sortedRest);
        builder.syncCanvasBackground(all);
        builder.generation++;
        builder.buildSerial++;
        builder.buildEpoch = LayoutBox.paintEpoch();
        builder.lastElementCount = builder.currentDisplayList.size();
        DisplayListBuilder.STAT_SLICE_PATCH_HITS.increment();
        return builder.currentDisplayList;
    }

    static boolean isAncestorOrSelf(LayoutBox ancestor, LayoutBox box) {
        for (LayoutBox b = box; b != null; b = b.parent) if (b == ancestor) return true;
        return false;
    }

    /**
     * {@code b} を含む、組み直しの根にできるいちばん近い祖先: 今も前回の描画でもスタッキングコンテキストを作り、自身は変わっていない
     * （{@code changed} に無い）、描画済みの、文書の根でない箱。無ければ null。
     */
    LayoutBox stableStackingAncestor(LayoutBox b, java.util.Collection<LayoutBox> changed) {
        for (LayoutBox a = b.parent; a != null; a = a.parent) {
            if (a.node == null || "root".equals(a.node.tag)) return null;
            if (a.isAnonymous || a.style == null || !builder.order.createsStackingContext(a, a.style)) continue;
            if (changed.contains(a)) continue;
            BoxMemo m = builder.boxMemo.get(a);
            if (m == null || m.style == null || !builder.order.createsStackingContext(a, m.style)) continue;
            if (a.dlStart < 0 || a.dlOrder < 0 || a.dlOrder >= builder.renderedBoxes.size() || builder.renderedBoxes.get(a.dlOrder) != a) return null;
            return a;
        }
        return null;
    }

    /**
     * 部分木 {@code root} の、表示リストでの範囲 {@code {開始, 終わり, 描画順の先頭, 描画順の数}}。部分木の箱のスライスが隙間なく
     * 連続して範囲を埋め、描画順の一覧でも連続しているときだけ。組み直しが全体の作り直しと同じ結果にならない部分木
     * （スクロールする箱・Top Layer・マスク・内容を省いた箱・仮想化した並び・キャンバスの背景を譲った箱を含む）は null。
     */
    int[] stackingRange(LayoutBox root) {
        if (root.dlStart < 0 || root.dlOrder < 0 || root.dlOrder >= builder.renderedBoxes.size() || builder.renderedBoxes.get(root.dlOrder) != root) return null;
        int[] acc = {Integer.MAX_VALUE, Integer.MIN_VALUE, 0, 0, Integer.MAX_VALUE, Integer.MIN_VALUE}; // 最小開始・最大終わり・項目数・箱数・最小順・最大順
        if (!scanStackingSubtree(root, acc)) return null;
        int start = acc[0], end = acc[1], items = acc[2], count = acc[3], o0 = acc[4], o1 = acc[5];
        if (count == 0 || start != root.dlStart || o0 != root.dlOrder) return null;
        if (end - start != items || o1 - o0 + 1 != count) return null; // 隙間（持ち主の無い項目）か、描画順の飛び
        if (end > builder.currentDisplayList.size()) return null;
        return new int[]{start, end, o0, count};
    }

    boolean scanStackingSubtree(LayoutBox b, int[] acc) {
        if (b == null) return true;
        if (b.contentAutoSkipped || b.childrenDeferred || b.isVirtualizedFlow()) return false;
        if (b == builder.canvasBackgroundSource) return false;
        if (b.style != null && !b.isAnonymous) {
            var facts = PaintOrder.factsOf(b.style);
            if (facts.scrollbars() || facts.hasMask() || builder.order.isTopLayerBox(b) || b.backdropStyle != null) return false;
        }
        if (b.dlOrder >= 0) {
            if (b.dlStart < 0 || b.dlEnd < b.dlStart || b.dlOrder >= builder.renderedBoxes.size() || builder.renderedBoxes.get(b.dlOrder) != b) return false;
            acc[0] = Math.min(acc[0], b.dlStart);
            acc[1] = Math.max(acc[1], b.dlEnd);
            acc[2] += b.dlEnd - b.dlStart;
            acc[3]++;
            acc[4] = Math.min(acc[4], b.dlOrder);
            acc[5] = Math.max(acc[5], b.dlOrder);
        }
        if (b.children != null) {
            for (int i = 0, n = b.children.size(); i < n; i++) if (!scanStackingSubtree(b.children.get(i), acc)) return false;
        }
        return true;
    }

    /** 部分木 {@code root}（範囲 {@code range}）を組み直して表示リストと描画順の一覧へ差し替え、後ろの箱の位置をずらす。 */
    void rebuildStackingSubtree(LayoutBox root, int[] range) {
        int start = range[0], end = range[1], orderStart = range[2], oldCount = range[3];
        // 前の範囲にいた箱（窓から出る・消える箱があっても位置が残らないように、先に取り消す）
        for (int k = orderStart, e = orderStart + oldCount; k < e; k++) builder.renderedBoxes.get(k).clearDlSlice();
        if (builder.windowedBuild && !builder.culledBoxes.isEmpty()) builder.culledBoxes.removeIf(c -> isAncestorOrSelf(root, c));

        boolean cull = builder.translating || builder.windowedBuild;
        // 作業用の一覧は使い回す（差し替えで表示リストへ写したあとは読まない。hover の毎フレーム走る）
        PaintSupport.CaptureList items = builder.stackItemsScratch;
        if (items == null) builder.stackItemsScratch = items = new PaintSupport.CaptureList(end - start + 8);
        else items.resetForBuild();
        List<LayoutBox> newBoxes = builder.stackBoxesScratch;
        newBoxes.clear();
        int prevEpoch = builder.activeMovingEpoch;
        builder.activeMovingEpoch = ++builder.movingEpochSeq;
        try {
            boolean ancestorMoving = builder.hasMovingAncestor(root.parent);
            List<RenderTask> tasks = builder.order.stackingSubtreeTasks(root, builder.clipForBox(root), builder.parentIdForBox(root), ancestorMoving);
            builder.executeTasks(tasks, items, cull, builder.windowedBuild, orderStart, newBoxes);
        } finally {
            builder.activeMovingEpoch = prevEpoch;
        }
        // 表示リストと描画順の一覧を差し替え、箱の位置を全体の添字へ直す
        int delta = items.size() - (end - start);
        if (delta == 0) {
            for (int k = 0, n = items.size(); k < n; k++) builder.currentDisplayList.set(start + k, items.get(k));
        } else {
            builder.currentDisplayList.subList(start, end).clear();
            builder.currentDisplayList.addAll(start, items);
        }
        int newCount = newBoxes.size();
        for (int k = 0; k < newCount; k++) {
            LayoutBox b = newBoxes.get(k);
            b.dlStart += start;
            b.dlEnd += start;
        }
        int orderDelta = newCount - oldCount;
        if (orderDelta == 0) {
            for (int k = 0; k < oldCount; k++) builder.renderedBoxes.set(orderStart + k, newBoxes.get(k));
        } else {
            builder.renderedBoxes.subList(orderStart, orderStart + oldCount).clear();
            builder.renderedBoxes.addAll(orderStart, newBoxes);
        }
        newBoxes.clear();
        items.resetForBuild();
        if (delta == 0 && orderDelta == 0) return; // 後ろの箱は動かない
        for (int k = orderStart + newCount, n = builder.renderedBoxes.size(); k < n; k++) {
            LayoutBox succ = builder.renderedBoxes.get(k);
            succ.dlStart += delta;
            succ.dlEnd += delta;
            if (orderDelta != 0) succ.dlOrder = k;
        }
    }
}
