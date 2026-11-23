package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.kernel.Shortcut;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/**
 * ボックスの親相対配置再利用・リセットを一元管理する記憶（A7・K2・K3）。
 *
 * <p>K2 の読み取り集合の鍵（{@code placeReadMask} と外部依存 {@link PlacementDeps}）に加え、
 * K3 で箱の {@code x / y} を親相対にしたことで平行移動（{@code shiftSubtree}・{@code lastLayoutX/Y}）を廃止し、
 * 親相対位置が変わった部分木も {@link #tryReuse} の O(1) 座標更新で再利用する。</p>
 */
final class PlacementMemo {

    private static final byte F_VALID = 1 << 0;
    private static final byte F_HAS_FLOAT_CHILD = 1 << 1;

    private static final LongAdder LAYOUTS_SKIPPED = new LongAdder();

    private byte flags;
    private int placeReadMask;
    private PlacementDeps deps;
    private float lastScrollX = Float.NaN, lastScrollY = Float.NaN;
    private float lastLayoutOuterW = Float.NaN, lastLayoutOuterH = Float.NaN;
    private float lastLayoutML = Float.NaN, lastLayoutMR = Float.NaN, lastLayoutMT = Float.NaN, lastLayoutMB = Float.NaN;

    static long layoutsSkipped() { return LAYOUTS_SKIPPED.sum(); }

    private boolean flag(byte bit) { return (flags & bit) != 0; }
    private void setFlag(byte bit, boolean on) {
        if (on) flags |= bit;
        else flags &= ~bit;
    }

    boolean isValid() { return flag(F_VALID); }
    boolean hasFloatChild() { return flag(F_HAS_FLOAT_CHILD); }
    void setHasFloatChild(boolean v) { setFlag(F_HAS_FLOAT_CHILD, v); }

    void invalidateValid() { flags &= ~F_VALID; }

    void invalidateOnDisplayChange() {
        flags &= ~F_VALID;
        placeReadMask = 0;
        deps = null;
    }

    private void onReset(boolean full) {
        flags &= ~F_VALID;
        if (full) {
            placeReadMask = 0;
            deps = null;
        }
    }

    static boolean holdsPlacement(LayoutBox box) {
        return box.placementMemo != null
                && box.placementMemo.flag(F_VALID)
                && (!box.display.isInlineLevel() || BlockLayoutEngine.isAtomicInline(box));
    }

    static boolean isInlineFlowForOrigin(LayoutBox b) {
        if (b.isAbsolute || b.display == null || !b.display.isInlineLevel()) return false;
        if (b.parent != null && b.parent.display != null) {
            LayoutBox.Display.Inner pin = b.parent.display.inner();
            if (pin == LayoutBox.Display.Inner.FLEX || pin == LayoutBox.Display.Inner.GRID) return false;
        }
        return !BlockLayoutEngine.isAtomicInline(b) && !BlockLayoutEngine.isFloating(b);
    }

    static LayoutBox originParent(LayoutBox box) {
        LayoutBox p = box.parent;
        while (p != null && !p.isAbsolute && p.display != null && p.display.isInlineLevel() && isInlineFlowForOrigin(p)) {
            p = p.parent;
        }
        return p;
    }

    static float originParentX(LayoutBox box) {
        LayoutBox p = originParent(box);
        return p != null ? p.x() : 0f;
    }

    static float originParentY(LayoutBox box) {
        LayoutBox p = originParent(box);
        return p != null ? p.y() : 0f;
    }

    static float resolveX(LayoutBox box) {
        if (box.display != null && (box.display.isNone() || box.display.isContents())) return box.x;
        float rx = box.x;
        LayoutBox p = box.parent;
        while (p != null) {
            if (!isInlineFlowForOrigin(p)) rx += p.x;
            p = p.parent;
        }
        return rx;
    }

    static float resolveY(LayoutBox box) {
        if (box.display != null && (box.display.isNone() || box.display.isContents())) return box.y;
        float ry = box.y;
        LayoutBox p = box.parent;
        while (p != null) {
            if (!isInlineFlowForOrigin(p)) ry += p.y;
            p = p.parent;
        }
        return ry;
    }

    static boolean needsWork(LayoutBox box, LayoutContext ctx) {
        PlacementMemo pm = box.placementMemo;
        return !ctx.reuseMeasurements()
                || box.measureMemo.isSubtreeChanged()
                || box.measureMemo.isLayoutChanged()
                || pm == null
                || !canKeepAtReset(box, ctx.layoutRun());
    }

    static String debugInfo(LayoutBox box) {
        PlacementMemo pm = box.placementMemo;
        boolean valid = pm != null && pm.flag(F_VALID);
        return box.node.tag + "#" + box.node.key() + " disp=" + box.display + " pvalid=" + valid
                + " x=" + box.x + " y=" + box.y
                + " valid=" + box.measureMemo.isValid() + " runM=" + box.measureMemo.measuredRun()
                + " anon=" + box.isAnonymous;
    }

    /**
     * 配置を再利用できるなら親相対座標を更新して true を返す。
     * 入力が変わって子が古い場合は、ここで子の配置をリセットしてから false を返す。
     */
    static boolean tryReuse(LayoutBox box, float relOriginX, float relOriginY,
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
        boolean depsOk = pm.deps == null || pm.deps.matches(box, relOriginX, relOriginY, mask, ctx);
        if (sameScroll && sameMargins && outerOk && depsOk) {
            if (!box.isAbsolute) {
                float prevX = box.x;
                float prevY = box.y;
                boolean wasPosRel = box.isRelative && !box.inlineFragments.isEmpty();
                box.x = (box.isFixed ? 0 : relOriginX) + box.mL;
                box.y = (box.isFixed ? 0 : relOriginY) + box.mT;
                if (box.isRelative) {
                    ScrollExtentCalc.applyRelativeOnReuse(box, parentOuterW, parentOuterH, ctx);
                }
                if (box.isSticky) {
                    ScrollExtentCalc.applyStickyOnReuse(box, ctx);
                }
                if (Float.floatToRawIntBits(box.x) != Float.floatToRawIntBits(prevX)
                        || Float.floatToRawIntBits(box.y) != Float.floatToRawIntBits(prevY)) {
                    if (!wasPosRel && !box.inlineFragments.isEmpty()) {
                        float dx = box.x - prevX;
                        float dy = box.y - prevY;
                        for (int i = 0, n = box.inlineFragments.size(); i < n; i++) {
                            LayoutBox.InlineFragment frag = box.inlineFragments.get(i);
                            frag.x += dx;
                            frag.y += dy;
                        }
                    }
                    PaintStamps.touchLayout(box);
                }
            }
            LayoutReads.propagateReused(mask, pm.deps);
            LAYOUTS_SKIPPED.increment();
            return true;
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
        return pm != null && pm.flag(F_VALID)
                && box.measureMemo.isValid() && box.measureMemo.measuredRun() != run
                && !box.measureMemo.isLayoutChanged() && !box.measureMemo.isSubtreeChanged()
                && isAllowedParent(box);
    }

    void record(LayoutBox box, boolean hasLegend, float scrollXAtPlacement, float scrollYAtPlacement,
                LayoutContext ctx, MeasureReads.ThreadState readState, int pSlot) {
        float relOriginX = LayoutReads.peekOriginX(readState, pSlot);
        float relOriginY = LayoutReads.peekOriginY(readState, pSlot);
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
        deps = PlacementDeps.captureOrReuse(deps, box, relOriginX, relOriginY, mask, ctx, readState,
                readState.placeRefStart[pSlot], readState.placeRefCount);
        if (box.isScrollContainer()) {
            lastScrollX = scrollXAtPlacement;
            lastScrollY = scrollYAtPlacement;
        }
        setFlag(F_VALID, true);
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
