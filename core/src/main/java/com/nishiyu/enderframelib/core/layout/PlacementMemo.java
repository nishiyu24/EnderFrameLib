package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.kernel.LayoutUnit;
import com.nishiyu.enderframelib.core.kernel.Shortcut;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/**
 * ボックスの配置再利用・平行移動・リセットを一元管理する記憶（A7）。
 *
 * <p>すべての記憶欄を {@code private} としてこのクラスに閉じ、テキストノードや未配置の箱では
 * 遅延生成してヒープ消費 0 を保つ。</p>
 */
final class PlacementMemo {

    private static final byte F_VALID = 1 << 0;
    private static final byte F_PURE = 1 << 1;
    private static final byte F_KEPT_PURE = 1 << 2;
    private static final byte F_HAS_FLOAT_CHILD = 1 << 3;

    private static final LongAdder LAYOUTS_SKIPPED = new LongAdder();
    private static final LongAdder LAYOUTS_SHIFTED = new LongAdder();

    private byte flags;
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
    boolean isPure() { return flag(F_PURE); }
    boolean isKeptPure() { return flag(F_KEPT_PURE); }
    boolean hasFloatChild() { return flag(F_HAS_FLOAT_CHILD); }
    void setHasFloatChild(boolean v) { setFlag(F_HAS_FLOAT_CHILD, v); }
    boolean isLaidOut() { return !Float.isNaN(lastLayoutX); }

    void invalidateValid() { flags &= ~F_VALID; }
    void clearLastLayoutX() { lastLayoutX = Float.NaN; }

    void invalidateOnDisplayChange() {
        lastLayoutX = Float.NaN;
        lastLayoutY = Float.NaN;
        flags &= ~(F_PURE | F_KEPT_PURE | F_VALID);
    }

    private void onReset(boolean full) {
        flags &= ~F_VALID;
        if (full) {
            flags &= ~(F_PURE | F_KEPT_PURE);
            lastLayoutX = Float.NaN;
        }
    }

    static boolean holdsPlacement(LayoutBox box) {
        return Shortcut.KEEP_PLACEMENTS.on() && box.placementMemo != null && box.placementMemo.isPure();
    }

    static boolean needsWork(LayoutBox box, float parentX, float parentY, LayoutContext ctx) {
        PlacementMemo pm = box.placementMemo;
        return !ctx.reuseMeasurements()
                || box.measureMemo.isSubtreeChanged()
                || box.measureMemo.isLayoutChanged()
                || pm == null
                || pm.lastLayoutX != parentX || pm.lastLayoutY != parentY
                || !canKeepPlacement(box, ctx.layoutRun());
    }

    static String debugInfo(LayoutBox box) {
        PlacementMemo pm = box.placementMemo;
        boolean pure = pm != null && pm.isPure();
        float lx = pm != null ? pm.lastLayoutX : Float.NaN;
        float ly = pm != null ? pm.lastLayoutY : Float.NaN;
        return box.node.tag + "#" + box.node.key() + " disp=" + box.display + " pure=" + pure
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
        PlacementMemo pm = box.placementMemo;
        if (pm == null) return false;
        boolean sameScroll = !box.isScrollContainer()
                || (ctx.scrollState().getScrollX(box.node) == pm.lastScrollX
                        && ctx.scrollState().getScrollY(box.node) == pm.lastScrollY);
        boolean sameMargins = pm.lastLayoutML == box.mL && pm.lastLayoutMR == box.mR
                && pm.lastLayoutMT == box.mT && pm.lastLayoutMB == box.mB;
        boolean keptPure = pm.flag(F_KEPT_PURE);
        boolean sameInputs = pm.lastLayoutX == parentX && pm.lastLayoutY == parentY && sameScroll && sameMargins
                && (keptPure || (pm.lastLayoutOuterW == parentOuterW && pm.lastLayoutOuterH == parentOuterH));
        if (ctx.reuseMeasurements() && !checkAutoSubtree && sameInputs && canKeepPlacement(box, ctx.layoutRun())) {
            LAYOUTS_SKIPPED.increment();
            return true;
        }
        if (!sameInputs && !checkAutoSubtree && sameScroll && sameMargins
                && Shortcut.SHIFT.on() && ctx.reuseMeasurements()
                && canKeepPlacement(box, ctx.layoutRun())
                && !Float.isNaN(pm.lastLayoutX) && !Float.isNaN(pm.lastLayoutY)
                && isShiftableDelta(parentX, pm.lastLayoutX) && isShiftableDelta(parentY, pm.lastLayoutY)
                && isShiftableSubtree(box, parentX != pm.lastLayoutX, parentY != pm.lastLayoutY)) {
            shiftSubtree(box, parentX - pm.lastLayoutX, parentY - pm.lastLayoutY);
            pm.lastLayoutX = parentX;
            pm.lastLayoutY = parentY;
            LAYOUTS_SHIFTED.increment();
            return true;
        }
        if (Shortcut.KEEP_PLACEMENTS.on() && keptPure && !sameInputs) {
            resetChildrenPlacements(box.children);
        }
        return false;
    }

    void record(LayoutBox box, float parentX, float parentY, float parentOuterW, float parentOuterH,
                boolean hasLegend, float scrollXAtPlacement, float scrollYAtPlacement) {
        lastLayoutX = parentX;
        lastLayoutY = parentY;
        lastLayoutOuterW = parentOuterW;
        lastLayoutOuterH = parentOuterH;
        lastLayoutML = box.mL;
        lastLayoutMR = box.mR;
        lastLayoutMT = box.mT;
        lastLayoutMB = box.mB;
        boolean pure = !hasLegend && subtreeIsSelfContained(box, true);
        boolean kept = pure || (!hasLegend && box.isScrollContainer() && subtreeIsSelfContained(box, false));
        setFlag(F_PURE, pure);
        setFlag(F_KEPT_PURE, kept);
        if (box.isScrollContainer()) {
            lastScrollX = scrollXAtPlacement;
            lastScrollY = scrollYAtPlacement;
        }
        setFlag(F_VALID, true);
    }

    private static boolean subtreeIsSelfContained(LayoutBox box, boolean selfScrollBreaks) {
        if (box.isAbsolute || box.isFixed || box.isRelative || box.isSticky
                || (selfScrollBreaks && box.isScrollContainer()) || box.floatSpace != null
                || LayoutBox.isTableBox(box) || box.collapsedBorders != null
                || box.style.facts().floating) {
            return false;
        }
        List<LayoutBox> kids = box.children;
        for (int i = 0, n = kids.size(); i < n; i++) {
            LayoutBox child = kids.get(i);
            if (child.style.facts().displayNone) continue;
            PlacementMemo cpm = child.placementMemo;
            boolean laidOutByLayout = cpm != null && cpm.isLaidOut();
            if (laidOutByLayout ? !cpm.isPure() : !subtreeIsSelfContained(child, true)) return false;
        }
        return true;
    }

    private static boolean canKeepPlacement(LayoutBox box, long run) {
        return canKeepPlacement(box, run, floatsAffectPlacement(box));
    }

    private static boolean canKeepPlacement(LayoutBox box, long run, boolean floatsOrOffset) {
        PlacementMemo pm = box.placementMemo;
        return pm != null && Shortcut.KEEP_PLACEMENTS.on()
                && pm.flag(F_KEPT_PURE) && pm.flag(F_VALID)
                && box.measureMemo.isValid() && box.measureMemo.measuredRun() != run
                && !box.measureMemo.isLayoutChanged() && !box.measureMemo.isSubtreeChanged()
                && box.parent != null && box.parent.display != null
                && (box.parent.display.inner() == LayoutBox.Display.Inner.FLOW
                        || box.parent.display.inner() == LayoutBox.Display.Inner.FLOW_ROOT
                        || box.parent.display.inner() == LayoutBox.Display.Inner.FLEX)
                && !box.display.isInlineLevel()
                && !floatsOrOffset;
    }

    private static boolean floatsAffectPlacement(LayoutBox box) {
        boolean inBfc = true;
        for (LayoutBox p = box.parent; p != null; p = p.parent) {
            if (p.isRelative || p.isSticky) return true;
            if (inBfc && p.placementMemo != null && p.placementMemo.hasFloatChild()) return true;
            if (p.establishesBfc) inBfc = false;
        }
        return false;
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
        shiftSubtree(box, dx, dy, true);
    }

    private static void shiftSubtree(LayoutBox box, float dx, float dy, boolean fitReachable) {
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
        List<LayoutBox.InlineFragment> frags = box.inlineFragments;
        for (int i = 0, n = frags.size(); i < n; i++) {
            LayoutBox.InlineFragment frag = frags.get(i);
            frag.x += dx;
            frag.y += dy;
        }
        box.touchPaint();
        List<LayoutBox> kids = box.children;
        for (int i = 0, n = kids.size(); i < n; i++) {
            LayoutBox child = kids.get(i);
            if (child.style.facts().displayNone) continue;
            PlacementMemo cpm = child.placementMemo;
            if (cpm != null && cpm.isLaidOut()) {
                cpm.lastLayoutX += dx;
                cpm.lastLayoutY += dy;
            }
            shiftSubtree(child, dx, dy, nextFitReachable);
        }
    }

    // ------------------------------------------------------------------ リセット

    static void resetPlacements(LayoutBox box, long keepRun) {
        if (keepRun >= 0 && canKeepPlacement(box, keepRun)) return;
        box.touchPaint();
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
        if (keepRun >= 0 && canKeepPlacement(box, keepRun, ancestorOffset || inBfcFloat)) return;
        box.touchPaint();
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
        boolean nextOffset = ancestorOffset || box.isRelative || box.isSticky;
        boolean hasFloat = pm != null && pm.hasFloatChild();
        boolean nextBfcFloat = box.establishesBfc ? hasFloat : (inBfcFloat || hasFloat);
        if (box.engineState instanceof VirtualFlowState vf && vf.canResetSparse()) {
            vf.resetPlacedChildren(keepRun, nextOffset, nextBfcFloat, staleFrags);
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
                    resetPlacementsFast(kids.get(i), keepRun, nextOffset, nextBfcFloat, local);
                }
                if (!local.isEmpty()) perChunk[c] = local;
            });
            for (int c = 0; c < chunks; c++) {
                if (perChunk[c] != null) staleFrags.addAll(perChunk[c]);
            }
            return;
        }
        for (int i = 0; i < n; i++) {
            resetPlacementsFast(kids.get(i), keepRun, nextOffset, nextBfcFloat, staleFrags);
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
