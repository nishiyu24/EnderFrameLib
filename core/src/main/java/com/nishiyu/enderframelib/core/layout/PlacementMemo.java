package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.kernel.LayoutUnit;
import com.nishiyu.enderframelib.core.kernel.Shortcut;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/**
 * ボックスの配置再利用・平行移動・リセットを一元管理する記憶（A7・K2）。
 *
 * <p>K2 により、部分木ごとに記録した配置の読み取り集合（{@code placeReadMask} と外部依存 {@link PlacementDeps}）を
 * 再配置の鍵として直接照合する。従来の純粋性判定（{@code selfContained} 等）と {@code PlacementVerifier} を置き換え、
 * すべての記憶欄を {@code private} としてこのクラスに閉じ、未配置の箱では遅延生成してヒープ消費 0 を保つ。</p>
 */
final class PlacementMemo {

    private static final byte F_VALID = 1 << 0;
    private static final byte F_HAS_FLOAT_CHILD = 1 << 1;

    private static final LongAdder LAYOUTS_SKIPPED = new LongAdder();
    private static final LongAdder LAYOUTS_SHIFTED = new LongAdder();

    private byte flags;
    private int placeReadMask;
    private PlacementDeps deps;
    private float lastScrollX = Float.NaN, lastScrollY = Float.NaN;
    private float lastLayoutX = Float.NaN, lastLayoutY = Float.NaN;
    private float lastLayoutOuterW = Float.NaN, lastLayoutOuterH = Float.NaN;
    private float lastLayoutML = Float.NaN, lastLayoutMR = Float.NaN, lastLayoutMT = Float.NaN, lastLayoutMB = Float.NaN;

    static long layoutsSkipped() { return LAYOUTS_SKIPPED.sum(); }
    static long layoutsShifted() { return LAYOUTS_SHIFTED.sum(); }

    private boolean flag(byte bit) { return (flags & bit) != 0; }
    private void setFlag(byte bit, boolean on) {
        if (on) flags |= bit;
        else flags &= ~bit;
    }

    boolean isValid() { return flag(F_VALID); }
    boolean hasFloatChild() { return flag(F_HAS_FLOAT_CHILD); }
    void setHasFloatChild(boolean v) { setFlag(F_HAS_FLOAT_CHILD, v); }
    boolean isLaidOut() { return !Float.isNaN(lastLayoutX); }

    void invalidateValid() { flags &= ~F_VALID; }

    void invalidateOnDisplayChange() {
        lastLayoutX = Float.NaN;
        lastLayoutY = Float.NaN;
        flags &= ~F_VALID;
        placeReadMask = 0;
        deps = null;
    }

    private void onReset(boolean full) {
        flags &= ~F_VALID;
        lastLayoutX = Float.NaN;
        if (full) {
            placeReadMask = 0;
            deps = null;
        }
    }

    static boolean holdsPlacement(LayoutBox box) {
        return Shortcut.KEEP_PLACEMENTS.on() && box.placementMemo != null
                && box.placementMemo.flag(F_VALID) && box.placementMemo.isLaidOut()
                && (!box.display.isInlineLevel() || BlockLayoutEngine.isAtomicInline(box));
    }

    static boolean needsWork(LayoutBox box, float parentX, float parentY, LayoutContext ctx) {
        PlacementMemo pm = box.placementMemo;
        return !ctx.reuseMeasurements()
                || box.measureMemo.isSubtreeChanged()
                || box.measureMemo.isLayoutChanged()
                || pm == null
                || pm.lastLayoutX != parentX || pm.lastLayoutY != parentY
                || !canKeepAtReset(box, ctx.layoutRun());
    }

    static String debugInfo(LayoutBox box) {
        PlacementMemo pm = box.placementMemo;
        boolean valid = pm != null && pm.flag(F_VALID);
        float lx = pm != null ? pm.lastLayoutX : Float.NaN;
        float ly = pm != null ? pm.lastLayoutY : Float.NaN;
        return box.node.tag + "#" + box.node.key() + " disp=" + box.display + " pvalid=" + valid
                + " lastX=" + lx + " lastY=" + ly + " x=" + box.x + " y=" + box.y
                + " valid=" + box.measureMemo.isValid() + " runM=" + box.measureMemo.measuredRun()
                + " anon=" + box.isAnonymous;
    }

    /**
     * 配置を省略または平行移動で済ませられるなら実行して true を返す。
     * 入力が変わって子が古い場合は、ここで子の配置をリセットしてから false を返す。
     */
    static boolean tryReuseOrShift(LayoutBox box, float parentX, float parentY,
                                   float parentOuterW, float parentOuterH,
                                   LayoutContext ctx, boolean checkAutoSubtree) {
        PlacementMemo pm = box.placementMemo; com.nishiyu.enderframelib.core.kernel.Kernel.stats.placeCall();
        if (pm == null) return false;
        if (!ctx.reuseMeasurements() || checkAutoSubtree || !canKeepAtReset(box, ctx.layoutRun())) {
            return false;
        }
        int mask = pm.placeReadMask;
        boolean sameScroll = !box.isScrollContainer()
                || (LayoutReads.peekScrollX(box, ctx) == pm.lastScrollX
                        && LayoutReads.peekScrollY(box, ctx) == pm.lastScrollY);
        boolean sameMargins = pm.lastLayoutML == box.mL && pm.lastLayoutMR == box.mR
                && pm.lastLayoutMT == box.mT && pm.lastLayoutMB == box.mB;
        boolean outerOk = (mask & ReadKind.PLACE_PARENT_OUTER.stageBit()) == 0
                || (pm.lastLayoutOuterW == parentOuterW && pm.lastLayoutOuterH == parentOuterH);
        boolean depsOk = pm.deps == null || pm.deps.matches(box, mask, ctx);
        if (sameScroll && sameMargins && outerOk && depsOk) {
            if (pm.lastLayoutX == parentX && pm.lastLayoutY == parentY) {
                LayoutReads.propagateReused(mask, pm.deps);
                LAYOUTS_SKIPPED.increment();
                return true;
            }
            if (Shortcut.SHIFT.on()
                    && (mask & PlacementDeps.SHIFT_BLOCKERS) == 0
                    && !box.isAbsolute && !box.isSticky && box.floatSpace == null
                    && isShiftableDelta(parentX, pm.lastLayoutX) && isShiftableDelta(parentY, pm.lastLayoutY)
                    && isShiftableSubtree(box, parentX != pm.lastLayoutX, parentY != pm.lastLayoutY)) {
                shiftSubtree(box, parentX - pm.lastLayoutX, parentY - pm.lastLayoutY);
                pm.lastLayoutX = parentX;
                pm.lastLayoutY = parentY;
                LayoutReads.propagateReused(mask, pm.deps);
                LAYOUTS_SHIFTED.increment();
                return true;
            }
        }
        // リセット時に子の保持を許して残したまま、この箱だけ再配置になったときは子の古い配置を捨てる
        resetChildrenPlacements(box.children);
        return false;
    }

    private static boolean isAllowedParent(LayoutBox box) {
        if (box.parent == null || box.parent.display == null || box.display == null) return false;
        if (box.display.isInlineLevel() && !BlockLayoutEngine.isAtomicInline(box)) return false;
        LayoutBox.Display pd = box.parent.display;
        LayoutBox.Display.Inner in = pd.inner();
        return pd.isBlockContainer()
                || in == LayoutBox.Display.Inner.FLEX
                || in == LayoutBox.Display.Inner.GRID;
    }

    private static boolean canKeepAtReset(LayoutBox box, long run) {
        PlacementMemo pm = box.placementMemo;
        return pm != null && Shortcut.KEEP_PLACEMENTS.on()
                && pm.flag(F_VALID) && pm.isLaidOut()
                && box.measureMemo.isValid() && box.measureMemo.measuredRun() != run
                && !box.measureMemo.isLayoutChanged() && !box.measureMemo.isSubtreeChanged()
                && isAllowedParent(box);
    }

    void record(LayoutBox box, boolean hasLegend, float scrollXAtPlacement, float scrollYAtPlacement,
                LayoutContext ctx, MeasureReads.ThreadState readState, int pSlot) {
        lastLayoutX = LayoutReads.peekOriginX(readState, pSlot);
        lastLayoutY = LayoutReads.peekOriginY(readState, pSlot);
        lastLayoutOuterW = LayoutReads.peekParentOuterW(readState, pSlot);
        lastLayoutOuterH = LayoutReads.peekParentOuterH(readState, pSlot);
        lastLayoutML = box.mL;
        lastLayoutMR = box.mR;
        lastLayoutMT = box.mT;
        lastLayoutMB = box.mB;
        int mask = LayoutReads.absorbSelfAndMask(box, readState, pSlot);
        if (hasLegend || (LayoutBox.isTableBox(box) && box.display.inner() != LayoutBox.Display.Inner.TABLE)) {
            mask |= ReadKind.PLACE_PARENT_OUTER.stageBit();
        }
        placeReadMask = mask;
        deps = PlacementDeps.captureOrReuse(deps, box, mask, ctx, readState,
                readState.placeRefStart[pSlot], readState.placeRefCount);
        if (box.isScrollContainer()) {
            lastScrollX = scrollXAtPlacement;
            lastScrollY = scrollYAtPlacement;
        }
        setFlag(F_VALID, true);
    }

    // ------------------------------------------------------------------ 平行移動

    private static boolean isShiftableSubtree(LayoutBox box, boolean needGridX, boolean needGridY) {
        if ((needGridX || needGridY) && !isGridAligned(box, needGridX, needGridY)) return false;
        if (box.columnShifts != null || box.collapsedBorders != null || box.renderedLegend() != null) return false;
        if (box.engineState != null && !(box.engineState instanceof BlockLayoutEngine.FlowState
                || box.engineState instanceof FlexLayoutEngine.FlexState
                || box.engineState instanceof GridLayoutEngine.GridState)) {
            return false;
        }
        if (box.display != null && box.display.inner() != LayoutBox.Display.Inner.FLOW
                && box.display.inner() != LayoutBox.Display.Inner.FLOW_ROOT
                && box.display.inner() != LayoutBox.Display.Inner.FLEX
                && box.display.inner() != LayoutBox.Display.Inner.GRID) {
            return false;
        }
        List<LayoutBox> kids = box.children;
        for (int i = 0, n = kids.size(); i < n; i++) {
            LayoutBox child = kids.get(i);
            if (child.style.facts().displayNone) continue;
            if (!isShiftableSubtree(child, needGridX, needGridY)) return false;
        }
        return true;
    }

    private static boolean isIntegral(float v) {
        return v == Math.rint(v);
    }

    private static boolean isShiftableDelta(float parentCoord, float lastCoord) {
        float d = parentCoord - lastCoord;
        if (lastCoord + d != parentCoord) return false;
        if (isIntegral(d)) return true;
        float limit = LayoutUnit.EXACT_LIMIT - LayoutUnit.SHIFT_MARGIN;
        return Math.abs(parentCoord) < limit && Math.abs(lastCoord) < limit
                && LayoutUnit.isOnGrid(parentCoord) && LayoutUnit.isOnGrid(lastCoord) && LayoutUnit.isOnGrid(d);
    }

    private static boolean isGridAligned(LayoutBox box, boolean checkX, boolean checkY) {
        boolean isText = box.node != null && box.node.nodeType == NodeType.TEXT;
        PlacementMemo pm = box.placementMemo;
        if (checkX) {
            if (!isText && !LayoutUnit.isOnGrid(box.x)) return false;
            if (!Float.isNaN(box.staticX) && !LayoutUnit.isOnGrid(box.staticX)) return false;
            for (int i = 0, n = box.inlineFragments.size(); i < n; i++) {
                if (!LayoutUnit.isOnGrid(box.inlineFragments.get(i).x)) return false;
            }
            if (pm != null && !Float.isNaN(pm.lastLayoutX) && !LayoutUnit.isOnGrid(pm.lastLayoutX)) return false;
        }
        if (checkY) {
            if (!isText && !LayoutUnit.isOnGrid(box.y)) return false;
            if (box.inlineContentBottom > Float.NEGATIVE_INFINITY && !LayoutUnit.isOnGrid(box.inlineContentBottom)) return false;
            if (!Float.isNaN(box.staticY) && !LayoutUnit.isOnGrid(box.staticY)) return false;
            for (int i = 0, n = box.inlineFragments.size(); i < n; i++) {
                if (!LayoutUnit.isOnGrid(box.inlineFragments.get(i).y)) return false;
            }
            if (pm != null && !Float.isNaN(pm.lastLayoutY) && !LayoutUnit.isOnGrid(pm.lastLayoutY)) return false;
        }
        return true;
    }

    private static boolean canRecurseFitToFragments(LayoutBox box) {
        if (box.node == null || box.node.nodeType == NodeType.TEXT || "br".equals(box.node.tag)) return false;
        if (box.isAbsolute || box.isFixed || box.style.facts().displayNone || box.style.facts().floating) return false;
        if (box.display == null || !box.display.isInlineLevel()) return false;
        return !BlockLayoutEngine.isAtomicInline(box);
    }

    private static boolean hasNonTextFragment(LayoutBox box) {
        for (int i = 0, n = box.inlineFragments.size(); i < n; i++) {
            if (!box.inlineFragments.get(i).isText()) return true;
        }
        return false;
    }

    private static void shiftSubtree(LayoutBox box, float dx, float dy) {
        shiftSubtree(box, dx, dy, true, !BlockLayoutEngine.isAtomicInline(box));
    }

    private static void shiftSubtree(LayoutBox box, float dx, float dy, boolean fitReachable, boolean shiftOwnFrags) {
        PlacementMemo pm = box.placementMemo;
        boolean laidOut = pm != null && pm.isLaidOut();
        boolean canFit = !laidOut && fitReachable && canRecurseFitToFragments(box);
        if (laidOut || (canFit && hasNonTextFragment(box))) {
            box.x += dx;
            box.y += dy;
        }
        boolean nextFitReachable = laidOut || canFit;
        if (box.inlineContentBottom > Float.NEGATIVE_INFINITY) box.inlineContentBottom += dy;
        if (!Float.isNaN(box.staticX)) box.staticX += dx;
        if (!Float.isNaN(box.staticY)) box.staticY += dy;
        if (shiftOwnFrags) {
            List<LayoutBox.InlineFragment> frags = box.inlineFragments;
            for (int i = 0, n = frags.size(); i < n; i++) {
                LayoutBox.InlineFragment frag = frags.get(i);
                frag.x += dx;
                frag.y += dy;
            }
        }
        PaintStamps.touchLayout(box);
        List<LayoutBox> kids = box.children;
        for (int i = 0, n = kids.size(); i < n; i++) {
            LayoutBox child = kids.get(i);
            if (child.style.facts().displayNone) continue;
            PlacementMemo cpm = child.placementMemo;
            if (cpm != null && cpm.isLaidOut()) {
                cpm.lastLayoutX += dx;
                cpm.lastLayoutY += dy;
            }
            shiftSubtree(child, dx, dy, nextFitReachable, true);
        }
    }

    // ------------------------------------------------------------------ リセット

    static void resetPlacements(LayoutBox box, long keepRun) {
        if (keepRun >= 0 && canKeepAtReset(box, keepRun)) return;
        PaintStamps.touchLayout(box);
        if (box.placementMemo != null) box.placementMemo.onReset(keepRun < 0);
        box.discardFragments();
        box.fragmentsStale = false;
        box.resetFragmentCursor();
        box.x = 0;
        box.y = 0;
        box.staticX = Float.NaN;
        box.staticY = Float.NaN;
        box.staticAreaW = Float.NaN;
        box.staticAreaH = Float.NaN;
        box.scrollWidth = 0;
        box.scrollHeight = 0;
        box.descendantOverflowRight = 0;
        box.descendantOverflowBottom = 0;
        box.inlineContentBottom = Float.NEGATIVE_INFINITY;
        if (box.engineState instanceof VirtualFlowState vf && vf.canResetSparse()) {
            vf.resetPlacedChildren(keepRun, false, false, null);
            return;
        }
        List<LayoutBox> kids = box.children;
        int n = kids.size();
        if (n >= LayoutTaskPool.PARALLEL_THRESHOLD && Shortcut.LAYOUT_PARALLEL.on() && !LayoutTaskPool.inWorker()) {
            int grain = LayoutTaskPool.grainFor(n, 8);
            LayoutTaskPool.forRange(n, grain, null, i -> resetPlacements(kids.get(i), keepRun));
            return;
        }
        for (int i = 0; i < n; i++) resetPlacements(kids.get(i), keepRun);
    }

    private static void resetChildrenPlacements(List<LayoutBox> kids) {
        int n = kids.size();
        if (n >= LayoutTaskPool.PARALLEL_THRESHOLD && Shortcut.LAYOUT_PARALLEL.on() && !LayoutTaskPool.inWorker()) {
            int grain = LayoutTaskPool.grainFor(n, 8);
            LayoutTaskPool.forRange(n, grain, null, i -> resetPlacements(kids.get(i), -1));
            return;
        }
        for (int i = 0; i < n; i++) resetPlacements(kids.get(i), -1);
    }

    static void resetPlacementChildFast(LayoutBox child, long keepRun, boolean nextOffset, boolean nextBfcFloat,
                                        List<LayoutBox> staleFrags) {
        if (staleFrags == null) resetPlacements(child, keepRun);
        else resetPlacementsFast(child, keepRun, nextOffset, nextBfcFloat, staleFrags);
    }

    static void resetPlacementsFast(LayoutBox box, long keepRun, List<LayoutBox> staleFrags) {
        resetPlacementsFast(box, keepRun, false, false, staleFrags);
    }

    private static void resetPlacementsFast(LayoutBox box, long keepRun,
                                            boolean ancestorOffset, boolean inBfcFloat,
                                            List<LayoutBox> staleFrags) {
        if (keepRun >= 0 && canKeepAtReset(box, keepRun)) return;
        PaintStamps.touchLayout(box);
        PlacementMemo pm = box.placementMemo;
        if (pm != null) pm.onReset(keepRun < 0);
        if (!box.inlineFragments.isEmpty()) {
            box.fragmentsStale = true;
            staleFrags.add(box);
        }
        box.resetFragmentCursor();
        box.x = 0;
        box.y = 0;
        box.staticX = Float.NaN;
        box.staticY = Float.NaN;
        box.staticAreaW = Float.NaN;
        box.staticAreaH = Float.NaN;
        box.scrollWidth = 0;
        box.scrollHeight = 0;
        box.descendantOverflowRight = 0;
        box.descendantOverflowBottom = 0;
        box.inlineContentBottom = Float.NEGATIVE_INFINITY;
        List<LayoutBox> kids = box.children;
        int n = kids.size();
        if (n == 0) return;
        if (box.engineState instanceof VirtualFlowState vf && vf.canResetSparse()) {
            vf.resetPlacedChildren(keepRun, false, false, staleFrags);
            return;
        }
        if (n >= LayoutTaskPool.PARALLEL_THRESHOLD && Shortcut.LAYOUT_PARALLEL.on() && !LayoutTaskPool.inWorker()) {
            int chunks = LayoutTaskPool.chunksFor(n, 16);
            @SuppressWarnings("unchecked")
            List<LayoutBox>[] perChunk = new List[chunks];
            LayoutTaskPool.forChunks(n, chunks, null, span -> {
                int c = LayoutTaskPool.spanChunk(span);
                int start = LayoutTaskPool.spanStart(span);
                int end = LayoutTaskPool.spanEnd(span);
                List<LayoutBox> local = new ArrayList<>();
                for (int i = start; i < end; i++) {
                    resetPlacementsFast(kids.get(i), keepRun, false, false, local);
                }
                if (!local.isEmpty()) perChunk[c] = local;
            });
            for (int c = 0; c < chunks; c++) {
                if (perChunk[c] != null) staleFrags.addAll(perChunk[c]);
            }
            return;
        }
        for (int i = 0; i < n; i++) {
            resetPlacementsFast(kids.get(i), keepRun, false, false, staleFrags);
        }
    }

    static void finishResetPlacements(List<LayoutBox> staleFrags) {
        for (int i = 0, n = staleFrags.size(); i < n; i++) {
            LayoutBox b = staleFrags.get(i);
            if (b.fragmentsStale) {
                b.discardFragments();
                b.fragmentsStale = false;
            }
        }
        staleFrags.clear();
    }
}
