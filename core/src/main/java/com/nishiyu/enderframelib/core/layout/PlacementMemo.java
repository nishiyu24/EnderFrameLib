package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.CssPropertyIndex;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.kernel.LayoutUnit;
import com.nishiyu.enderframelib.core.kernel.Shortcut;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;

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
    /** 部分木に（自分以外の）スクロールコンテナがあり、保持できるのは {@link #scrollVersion} がスクロール量の版と同じときだけ（C9）。 */
    private static final byte F_SCROLL_DEP = 1 << 4;
    /** 最後の配置で、インセットを持つ relative として子孫の座標へオフセットを足した（足したオフセットは保持した子孫に残る）。 */
    private static final byte F_REL_INSET = 1 << 5;
    /** {@code cbUp}: 部分木の絶対配置が必要とする包含ブロックの、この箱から数えた段数（1 以上は部分木の外。{@code CB_OUTSIDE} は初期包含ブロック）。無ければ {@code NO_CB}。 */
    private static final int NO_CB = Integer.MIN_VALUE / 2, CB_OUTSIDE = 1 << 20;

    private static final LongAdder LAYOUTS_SKIPPED = new LongAdder();
    private static final LongAdder LAYOUTS_SHIFTED = new LongAdder();

    private byte flags, levelNoCb;
    private int scrollVersion, cbUp = NO_CB;
    /** 記録の途中の作業欄（{@link #record} が使う）: 絶対配置か relative を含む＝厳密な純粋性ではない。 */
    private boolean strictBroken;
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
        flags &= ~(F_PURE | F_KEPT_PURE | F_SCROLL_DEP | F_VALID);
    }

    private void onReset(boolean full) {
        flags &= ~F_VALID;
        if (full) {
            flags &= ~(F_PURE | F_KEPT_PURE | F_SCROLL_DEP);
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
        PlacementMemo pm = box.placementMemo; com.nishiyu.enderframelib.core.layout.MemoKpi.PLACE_CALLS.increment();
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
        cbUp = NO_CB; // 部分木の絶対配置が必要とする包含ブロックの最大の段数を selfContained が集める（確保しないよう自分の欄へ）
        strictBroken = false;
        int below = hasLegend ? IMPURE : selfContained(box, false, 0, this); // 自分のスクロールは tryReuseOrShift が比べるので数えない
        levelNoCb = (byte) below;
        if (below != IMPURE && cbUp > 0) below = IMPURE; // 絶対配置の包含ブロックが部分木の外にある: 外の幾何に依る
        boolean pure = below == PURE && !box.isScrollContainer() && !strictBroken;
        setFlag(F_REL_INSET, box.isRelative && relInset(box));
        setFlag(F_PURE, pure);
        setFlag(F_KEPT_PURE, below != IMPURE);
        setFlag(F_SCROLL_DEP, below == SCROLL_DEP);
        scrollVersion = ScrollState.version();
        if (box.isScrollContainer()) {
            lastScrollX = scrollXAtPlacement;
            lastScrollY = scrollYAtPlacement;
        }
        setFlag(F_VALID, true);
    }

    /** 部分木の純粋性: 外の状態に依らず置ける（{@code PURE}）・スクロール量が前回のままなら置ける（{@code SCROLL_DEP}）・どちらでもない（{@code IMPURE}）。 */
    private static final int IMPURE = 0, PURE = 1, SCROLL_DEP = 2;

    /**
     * 部分木の純粋性（外の幾何に依らず置けるか）。絶対配置は、包含ブロックが部分木の内側ならその部分木の中で完結する〔包含ブロックの必要段数を {@code acc.cbUp} に集め、
     * 呼び出し側が部分木の外を指していないか見る〕。アンカー位置指定・ビューポート単位・{@code fixed}・インセットを持つ relative・sticky・float・表は外へ出る。
     * {@code depth} は記録する根からの段数。
     */
    private static int selfContained(LayoutBox box, boolean selfScrollBreaks, int depth, PlacementMemo acc) {
        if (box.isFixed || box.isSticky || box.floatSpace != null || LayoutBox.isTableBox(box) || box.collapsedBorders != null
                || box.style.facts().floating || (box.isRelative && relInset(box))) {
            return IMPURE;
        }
        if (box.isAbsolute || box.isRelative) acc.strictBroken = true;
        if (box.isAbsolute) {
            if (anchored(box) || box.style.usesViewportUnits()) return IMPURE;
            acc.cbUp = Math.max(acc.cbUp, stepsToContainingBlock(box) - depth);
        }
        int level = selfScrollBreaks && box.isScrollContainer() ? SCROLL_DEP : PURE;
        List<LayoutBox> kids = box.children;
        for (int i = 0, n = kids.size(); i < n; i++) {
            LayoutBox child = kids.get(i);
            if (child.style.facts().displayNone) continue;
            PlacementMemo cpm = child.placementMemo;
            int r;
            if (cpm != null && cpm.isLaidOut()) {
                r = cpm.levelNoCb == IMPURE ? IMPURE : Math.max(cpm.levelNoCb, child.isScrollContainer() ? SCROLL_DEP : PURE); // 子自身のスクロールは親から見ると外の状態
                if (!cpm.isPure()) acc.strictBroken = true;
                if (cpm.cbUp != NO_CB) acc.cbUp = Math.max(acc.cbUp, cpm.cbUp - (depth + 1));
            } else {
                r = selfContained(child, true, depth + 1, acc);
            }
            if (r == IMPURE) return IMPURE;
            level = Math.max(level, r);
        }
        return level;
    }

    private static boolean canKeepPlacement(LayoutBox box, long run) {
        return canKeepPlacement(box, run, floatsAffectPlacement(box));
    }

    private static boolean canKeepPlacement(LayoutBox box, long run, boolean floatsOrOffset) {
        PlacementMemo pm = box.placementMemo;
        return pm != null && Shortcut.KEEP_PLACEMENTS.on()
                && pm.flag(F_KEPT_PURE) && pm.flag(F_VALID) && (!pm.flag(F_SCROLL_DEP) || pm.scrollVersion == ScrollState.version())
                && box.measureMemo.isValid() && box.measureMemo.measuredRun() != run
                && !box.measureMemo.isLayoutChanged() && !box.measureMemo.isSubtreeChanged()
                && box.parent != null && box.parent.display != null
                && (box.parent.display.inner() == LayoutBox.Display.Inner.FLOW
                        || box.parent.display.inner() == LayoutBox.Display.Inner.FLOW_ROOT
                        || box.parent.display.inner() == LayoutBox.Display.Inner.FLEX)
                && !box.display.isInlineLevel()
                && !floatsOrOffset;
    }

    /** relative がオフセットを決めるインセット（left / right / top / bottom）を 1 つでも持つか。{@code applyRelativePositioning} と同じ見方。 */
    private static boolean relInset(LayoutBox b) {
        var ax = b.axisStyle();
        return ax.containsKey(CssPropertyIndex.LEFT) || ax.containsKey(CssPropertyIndex.RIGHT) || ax.containsKey(CssPropertyIndex.TOP) || ax.containsKey(CssPropertyIndex.BOTTOM);
    }

    /** 子孫の座標にオフセットが入っている（前回の配置でインセット付きの relative だった）か、今回入る。保持した子孫へ足し直すと二重にずれる。 */
    private static boolean relOffsetEmbedded(LayoutBox p) {
        return (p.placementMemo != null && p.placementMemo.flag(F_REL_INSET)) || (p.isRelative && relInset(p));
    }

    /** アンカー位置指定（位置・寸法がほかの箱に依る）・位置の試行は、部分木の外の箱を読む。 */
    private static boolean anchored(LayoutBox b) {
        var s = b.style;
        if (isSet(s.get(CssPropertyIndex.POSITION_ANCHOR)) || isSet(s.get(CssPropertyIndex.POSITION_AREA)) || isSet(s.get(CssPropertyIndex.POSITION_TRY_FALLBACKS))) return true;
        for (int id : ANCHOR_REFS) { String v = s.get(id); if (v != null && v.contains("anchor")) return true; }
        return false;
    }

    private static final int[] ANCHOR_REFS = {CssPropertyIndex.LEFT, CssPropertyIndex.RIGHT, CssPropertyIndex.TOP, CssPropertyIndex.BOTTOM, CssPropertyIndex.WIDTH, CssPropertyIndex.HEIGHT};

    private static boolean isSet(String v) { return v != null && !v.isBlank() && !"none".equalsIgnoreCase(v.trim()); }

    /** 絶対配置の箱から包含ブロックまでの段数（初期包含ブロックは {@code CB_OUTSIDE}）。 */
    private static int stepsToContainingBlock(LayoutBox box) {
        LayoutBox cb = box.findContainingBlock();
        if (cb == null) return CB_OUTSIDE;
        int n = 0;
        for (LayoutBox p = box; p != null && p != cb; p = p.parent) n++;
        return n;
    }

    private static boolean floatsAffectPlacement(LayoutBox box) {
        boolean inBfc = true;
        for (LayoutBox p = box.parent; p != null; p = p.parent) {
            if (p.isSticky || relOffsetEmbedded(p)) return true;
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
            shiftSubtree(child, dx, dy, nextFitReachable);
        }
    }

    // ------------------------------------------------------------------ リセット

    static void resetPlacements(LayoutBox box, long keepRun) {
        if (keepRun >= 0 && canKeepPlacement(box, keepRun)) return;
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
        if (keepRun >= 0 && canKeepPlacement(box, keepRun, ancestorOffset || inBfcFloat)) return;
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
        boolean nextOffset = ancestorOffset || relOffsetEmbedded(box) || box.isSticky;
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
