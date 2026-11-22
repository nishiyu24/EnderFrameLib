package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.StyleFacts;
import com.nishiyu.enderframelib.core.kernel.Shortcut;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * ボックスツリーのレイアウト変更印（{@code layoutChanged} / {@code subtreeChanged}）と再レイアウト境界の判定（A7）。
 */
final class MeasureChanges {
    private MeasureChanges() {}

    static boolean markLayoutChanges(LayoutBox root) {
        return markLayoutChanges(root, false);
    }

    private static boolean markLayoutChanges(LayoutBox box, boolean forcedByParent) {
        MeasureMemo mm = box.measureMemo;
        if (forcedByParent && box.children.isEmpty() && !isRelayoutBoundary(box)) {
            mm.markDirtyLeaf();
            return true;
        }
        boolean selfChanged = mm.selfSignatureChanged(box);
        boolean listChanged = mm.childListChanged(box);
        boolean blockFlow = box.display != null && !LayoutBox.isTableBox(box)
                && (box.display.inner() == LayoutBox.Display.Inner.FLOW
                        || box.display.inner() == LayoutBox.Display.Inner.FLOW_ROOT);
        boolean forceAllChildren = selfChanged
                || (listChanged && (!blockFlow || mm.hadFloatChild() || MeasureAnalysis.hasFloatingChild(box)));
        boolean childAffects = false;
        boolean subtreeChanged = false;
        List<LayoutBox> kids = box.children;
        int n = kids.size();
        if (n >= LayoutTaskPool.PARALLEL_THRESHOLD && Shortcut.LAYOUT_PARALLEL.on() && !LayoutTaskPool.inWorker()) {
            int chunks = LayoutTaskPool.chunksFor(n, 16);
            boolean[] flags = new boolean[chunks * 2];
            LayoutTaskPool.forChunks(n, chunks, null, span -> {
                int c = LayoutTaskPool.spanChunk(span);
                int start = LayoutTaskPool.spanStart(span);
                int end = LayoutTaskPool.spanEnd(span);
                boolean ca = false;
                boolean sc = false;
                for (int i = start; i < end; i++) {
                    LayoutBox child = kids.get(i);
                    boolean force = forceAllChildren || (listChanged && child.measureMemo.firstFlowStatusChanged(child));
                    ca |= markLayoutChanges(child, force);
                    sc |= child.measureMemo.isSubtreeChanged();
                }
                flags[c * 2] = ca;
                flags[c * 2 + 1] = sc;
            });
            for (int c = 0; c < chunks; c++) {
                childAffects |= flags[c * 2];
                subtreeChanged |= flags[c * 2 + 1];
            }
        } else {
            for (int i = 0; i < n; i++) {
                LayoutBox child = kids.get(i);
                boolean force = forceAllChildren || (listChanged && child.measureMemo.firstFlowStatusChanged(child));
                childAffects |= markLayoutChanges(child, force);
                subtreeChanged |= child.measureMemo.isSubtreeChanged();
            }
        }
        boolean remeasure = selfChanged || listChanged || forcedByParent || childAffects || !mm.isValid();
        mm.updateDirtyFlags(remeasure, remeasure || subtreeChanged);
        return isRelayoutBoundary(box) ? selfChanged : remeasure;
    }

    static List<LayoutBox> markLayoutChangesAlong(LayoutBox root, Collection<LayoutBox> changed, Set<LayoutBox> shallow) {
        List<LayoutBox> path = new ArrayList<>();
        for (LayoutBox leaf : changed) {
            if (leaf == null || leaf == root) return null;
            MeasureMemo lm = leaf.measureMemo;
            boolean selfChanged = lm.selfSignatureChanged(leaf);
            boolean affects;
            if (leaf.children.isEmpty() || shallow.contains(leaf)) {
                boolean remeasure = selfChanged || lm.childListChanged(leaf) || !lm.isValid();
                lm.markDirtyAlong(remeasure);
                affects = isRelayoutBoundary(leaf) ? selfChanged : remeasure;
            } else {
                affects = markLayoutChanges(leaf, false);
                lm.markSubtreeChanged();
            }
            path.add(leaf);
            boolean reachedRoot = false;
            boolean lazy = affects && lm.canAttemptCutoff(leaf);
            for (LayoutBox a = leaf.parent; a != null; a = a.parent) {
                MeasureMemo am = a.measureMemo;
                if (am.selfSignatureChanged(a) || am.childListChanged(a)) return null;
                am.markSubtreeChanged();
                path.add(a);
                if (lazy) {
                    if (isRelayoutBoundary(a)) {
                        affects = false;
                        lazy = false;
                    } else if (!am.canAttemptCutoff(a) && !MeasureMemo.LAZY_THROUGH_INELIGIBLE) {
                        affects = true;
                        lazy = false;
                    }
                } else {
                    am.orLayoutChanged(affects);
                    if (isRelayoutBoundary(a)) affects = false;
                    else if (affects && am.canAttemptCutoff(a)) lazy = true;
                }
                if (a == root) {
                    reachedRoot = true;
                    break;
                }
            }
            if (!reachedRoot) return null;
        }
        return path;
    }

    static boolean markRootRestyleChanges(LayoutBox rootBox) {
        MeasureMemo rm = rootBox.measureMemo;
        boolean selfChanged = rm.selfSignatureChanged(rootBox);
        boolean listChanged = rm.childListChanged(rootBox);
        boolean blockFlow = rootBox.display != null && !LayoutBox.isTableBox(rootBox)
                && (rootBox.display.inner() == LayoutBox.Display.Inner.FLOW
                        || rootBox.display.inner() == LayoutBox.Display.Inner.FLOW_ROOT);
        boolean forceAllChildren = selfChanged
                || (listChanged && (!blockFlow || rm.hadFloatChild() || MeasureAnalysis.hasFloatingChild(rootBox)));
        boolean childAffects = false;
        boolean subtreeChanged = false;
        List<LayoutBox> kids = rootBox.children;
        for (int i = 0, n = kids.size(); i < n; i++) {
            LayoutBox child = kids.get(i);
            MeasureMemo cm = child.measureMemo;
            boolean force = forceAllChildren || (listChanged && cm.firstFlowStatusChanged(child));
            if (child.isAnonymous || cm.isSubtreeChanged() || cm.selfSignatureChanged(child) || cm.childListChanged(child)) {
                childAffects |= markLayoutChanges(child, force);
            } else {
                boolean cRemeasure = force || !cm.isValid();
                cm.setDirtyFlags(cRemeasure);
                childAffects |= !isRelayoutBoundary(child) && cRemeasure;
            }
            subtreeChanged |= cm.isSubtreeChanged();
        }
        boolean remeasure = selfChanged || listChanged || childAffects || !rm.isValid();
        rm.updateDirtyFlags(remeasure, remeasure || subtreeChanged);
        return remeasure;
    }

    static void clearAllSubtreeMarks(LayoutBox root) {
        if (!root.measureMemo.isSubtreeChanged()) return;
        root.measureMemo.clearSubtreeChangedIfClean();
        if (root.engineState instanceof VirtualFlowState vf) {
            int start = vf.placedRangeStart();
            int end = vf.placedRangeEnd();
            List<LayoutBox> kids = root.children;
            int n = kids.size();
            if (start >= 0 && end >= start) {
                int limit = Math.min(end, n - 1);
                for (int i = start; i <= limit; i++) clearAllSubtreeMarks(kids.get(i));
            }
            for (int k = 0, m = vf.extraPlacedCount(); k < m; k++) {
                int idx = vf.extraPlacedAt(k);
                if (idx >= 0 && idx < n && (idx < start || idx > end)) clearAllSubtreeMarks(kids.get(idx));
            }
            return;
        }
        List<LayoutBox> kids = root.children;
        int n = kids.size();
        if (n >= LayoutTaskPool.PARALLEL_THRESHOLD && Shortcut.LAYOUT_PARALLEL.on() && !LayoutTaskPool.inWorker()) {
            int grain = LayoutTaskPool.grainFor(n, 8);
            LayoutTaskPool.forRange(n, grain, null, i -> clearAllSubtreeMarks(kids.get(i)));
            return;
        }
        for (int i = 0; i < n; i++) clearAllSubtreeMarks(kids.get(i));
    }

    static void clearSubtreeMarks(List<LayoutBox> path) {
        for (LayoutBox b : path) {
            b.measureMemo.clearSubtreeChangedIfClean();
        }
    }

    /**
     * 自分の寸法が子孫に依らず、中身の変化が祖先の layout の入力にならない箱（再レイアウト境界）。
     */
    static boolean isRelayoutBoundary(LayoutBox box) {
        if (box.parent == null || box.display == null || box.display.isInlineLevel()
                || LayoutBox.isTableBox(box) || !box.establishesBfc) {
            return false;
        }
        if (box.effectiveContainsInlineSize() && box.effectiveContainsBlockSize()) return true;
        if (!box.isScrollContainer()) return false;
        StyleFacts facts = box.style.facts();
        return facts.definiteWidth && facts.definiteHeight;
    }
}
