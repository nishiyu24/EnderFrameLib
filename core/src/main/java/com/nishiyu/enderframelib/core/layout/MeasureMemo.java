package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.StyleResolver;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.kernel.EngineFlags;
import com.nishiyu.enderframelib.core.kernel.Shortcut;
import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import com.nishiyu.enderframelib.core.layout.model.IntrinsicSizeResolver;
import com.nishiyu.enderframelib.core.layout.model.Viewport;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * ボックスの測定結果・仮測定・確定葉スロット・変更印を一元管理する記憶（A7）。
 *
 * <p>すべての記憶欄を {@code private} としてこのクラスに閉じ、誰がいつ書き換えたかを型で追えるようにする。
 * 主スロットの自然出力はフィールドに展開して再確保を省き、仮測定／確定測定の複数スロット（{@link TentativeSlots}）は
 * 必要な flex / grid の子でのみ遅延生成する。</p>
 */
final class MeasureMemo {

    private static final int F_VALID = 1 << 0;
    private static final int F_LAYOUT_CHANGED = 1 << 1;
    private static final int F_SUBTREE_CHANGED = 1 << 2;
    private static final int F_HAS_OUTPUTS = 1 << 3;
    private static final int F_OUTSIDE_MARKER = 1 << 4;
    private static final int F_RENDERED_LEGEND = 1 << 5;
    private static final int F_CONTENT_AUTO_SKIPPED = 1 << 6;
    private static final int F_EVER_TABLE = 1 << 7;
    private static final int F_FIRST_FLOW_CHILD = 1 << 8;
    private static final int F_HAD_FLOAT_CHILD = 1 << 9;
    private static final int F_HAS_TABLE = 1 << 10;
    private static final int F_WIDTH_FREE = 1 << 11;
    private static final int F_OUTSIDE_FREE = 1 << 12;
    private static final int F_SHRINK_INDEPENDENT = 1 << 13;
    private static final int F_MIN_CONTENT_VAR = 1 << 14;
    private static final int F_FIT_FIXED_W = 1 << 15;
    private static final int F_FILL_FIT_REUSABLE = 1 << 16;
    private static final int F_BLOCK_PERCENT_FREE = 1 << 17;
    private static final int F_FLOW_FLOAT_FREE = 1 << 18;
    private static final int F_HAS_TENTATIVE_CHILDREN = 1 << 19;
    private static final int F_CHILD_FIT_BLOCKED = 1 << 20;
    private static final int F_SUBGRID_DEPENDENT = 1 << 21;

    private static final int FUEL_PER_BOX = 200;
    private static final int FUEL_BASE = 50_000;
    private static final int MAX_TENTATIVES = 4;
    private static final int MAX_DEFINITE_TENTATIVES = 2;
    private static final int MAX_TOTAL_TENTATIVES = MAX_TENTATIVES + MAX_DEFINITE_TENTATIVES;
    private static final LayoutBox[] EMPTY_BOX_ARRAY = new LayoutBox[0];

    /** 外から測り直しを強いる世代（画像の読み込み・書体の登録など、箱の外の資源が変わったとき）。 */
    private static final AtomicInteger MEASUREMENT_EPOCH = new AtomicInteger();
    private static final LongAdder MEASUREMENTS_COMPUTED = new LongAdder();
    private static final LongAdder TENTATIVE_SKIPPED = new LongAdder();
    private static final LongAdder FUEL_REUSED = new LongAdder();
    /** スレッドごとの {実行番号, 測った回数, 初めて測った箱の数}。 */
    private static final ThreadLocal<long[]> FUEL = ThreadLocal.withInitial(() -> new long[]{-1, 0, 0});
    /** 測り直しの結果を、直接の親の無効化だけで伝える下降（{@link #remeasureChanged}）の深さ。スレッドごと。 */
    private static final ThreadLocal<int[]> DESCENT = ThreadLocal.withInitial(() -> new int[1]);
    static final boolean LAZY_THROUGH_INELIGIBLE = EngineFlags.LAZY_THROUGH_INELIGIBLE;

    private static final class TentativeSlots {
        private Tentative[] entries;
        private ConstraintSpace stashedSpace;
        private LayoutContext stashedCtx;
        private boolean stashed;
        private float stW, stH, stMT, stMR, stMB, stML;
        private float stPT, stPR, stPB, stPL, stBT, stBR, stBB, stBL;
        private float stLegendSpill, stContentBlockExtent, stOwnMarginTop, stOwnMarginBottom;
        private LayoutBox.SizingMode stSizingMode;

        TentativeSlots copy() {
            TentativeSlots c = new TentativeSlots();
            c.entries = entries == null ? null : entries.clone();
            c.stashedSpace = stashedSpace; c.stashedCtx = stashedCtx; c.stashed = stashed;
            c.stW = stW; c.stH = stH; c.stMT = stMT; c.stMR = stMR; c.stMB = stMB; c.stML = stML;
            c.stPT = stPT; c.stPR = stPR; c.stPB = stPB; c.stPL = stPL;
            c.stBT = stBT; c.stBR = stBR; c.stBB = stBB; c.stBL = stBL;
            c.stLegendSpill = stLegendSpill; c.stContentBlockExtent = stContentBlockExtent;
            c.stOwnMarginTop = stOwnMarginTop; c.stOwnMarginBottom = stOwnMarginBottom; c.stSizingMode = stSizingMode;
            return c;
        }

        void captureStash(LayoutBox b) {
            stW = b.w; stH = b.h; stMT = b.mT; stMR = b.mR; stMB = b.mB; stML = b.mL;
            stPT = b.pT; stPR = b.pR; stPB = b.pB; stPL = b.pL;
            stBT = b.bT; stBR = b.bR; stBB = b.bB; stBL = b.bL;
            stLegendSpill = b.legendSpill; stContentBlockExtent = b.contentBlockExtent;
            stOwnMarginTop = b.ownMarginTop; stOwnMarginBottom = b.ownMarginBottom; stSizingMode = b.sizingMode;
        }

        void applyStash(LayoutBox b) {
            b.w = stW; b.h = stH; b.mT = stMT; b.mR = stMR; b.mB = stMB; b.mL = stML;
            b.pT = stPT; b.pR = stPR; b.pB = stPB; b.pL = stPL;
            b.bT = stBT; b.bR = stBR; b.bB = stBB; b.bL = stBL;
            b.legendSpill = stLegendSpill; b.contentBlockExtent = stContentBlockExtent;
            b.ownMarginTop = stOwnMarginTop; b.ownMarginBottom = stOwnMarginBottom; b.sizingMode = stSizingMode;
        }
    }

    private int flags = F_LAYOUT_CHANGED | F_SUBTREE_CHANGED;
    private ConstraintSpace measuredSpace;
    private float measuredOverrideW = Float.NaN, measuredOverrideH = Float.NaN;
    private Viewport measuredViewport;
    private FontResolver measuredFonts;
    private IntrinsicSizeResolver measuredIntrinsics;
    private int measuredEpoch;
    private long measuredRun = -1;

    // 主スロットの自然な出力（親が stretch 等で後から書き換える前）
    private float outW, outH, outMT, outMR, outMB, outML;
    private float outPT, outPR, outPB, outPL, outBT, outBR, outBB, outBL;
    private float outLegendSpill, outContentBlockExtent, outOwnMarginTop, outOwnMarginBottom;
    private LayoutBox.SizingMode outSizingMode;

    // 中身の署名
    private ComputedStyle measuredStyle;
    private ComputedStyle measuredPlaceholderStyle;
    private String measuredText;
    private int measuredContentHash;
    private LayoutBox[] measuredChildren;

    // 属性ハッシュキャッシュ
    private int attrHash;
    private int attrHashVersion = -1;
    private HtmlNode attrHashNode;

    // fit / min-content キャッシュ
    private float measuredFitNeed = Float.POSITIVE_INFINITY;
    private float childFitNeedMax;
    private float cachedMinContentWidth = -1;
    private long cachedMinContentKey = Long.MIN_VALUE;

    private TentativeSlots tentativeSlots;

    // ------------------------------------------------------------------ 静的統計・世代

    static void invalidateAll() { MEASUREMENT_EPOCH.incrementAndGet(); }
    static long measurementsComputed() { return MEASUREMENTS_COMPUTED.sum(); }
    static long tentativeSkipped() { return TENTATIVE_SKIPPED.sum(); }
    static long fuelReused() { return FUEL_REUSED.sum(); }

    // ------------------------------------------------------------------ フラグ・読み取り口

    private boolean flag(int bit) { return (flags & bit) != 0; }
    private void setFlag(int bit, boolean on) {
        if (on) flags |= bit;
        else flags &= ~bit;
    }

    boolean isValid() { return flag(F_VALID); }
    boolean isLayoutChanged() { return flag(F_LAYOUT_CHANGED); }
    boolean isSubtreeChanged() { return flag(F_SUBTREE_CHANGED); }
    boolean hasTable() { return flag(F_HAS_TABLE); }
    boolean everLaidOutAsTable() { return flag(F_EVER_TABLE); }
    boolean isBlockPercentFree() { return flag(F_BLOCK_PERCENT_FREE); }
    boolean isOutsideFree() { return flag(F_OUTSIDE_FREE); }
    boolean isWidthFree() { return flag(F_WIDTH_FREE); }
    boolean isFlowFloatFree() { return flag(F_FLOW_FLOAT_FREE); }
    boolean isMinContentVar() { return flag(F_MIN_CONTENT_VAR); }
    boolean hasTentativeChildren() { return flag(F_HAS_TENTATIVE_CHILDREN); }
    boolean isTentativeStashed() { return tentativeSlots != null && tentativeSlots.stashed; }
    boolean measuredWithoutFixedHeight() { return measuredSpace != null && Float.isNaN(measuredSpace.fixedBlockSize()); }
    boolean needsParallelWork() { return flag(F_SUBTREE_CHANGED | F_LAYOUT_CHANGED) || !flag(F_VALID); }
    void setSubgridDependent(boolean v) { setFlag(F_SUBGRID_DEPENDENT, v); }
    ConstraintSpace measuredSpace() { return measuredSpace; }
    long measuredRun() { return measuredRun; }

    void markDirtyLeaf() { flags |= F_LAYOUT_CHANGED | F_SUBTREE_CHANGED; invalidateMinContentCache(); }
    void markDirtyAlong(boolean remeasure) { flags |= F_SUBTREE_CHANGED | (remeasure ? F_LAYOUT_CHANGED : 0); invalidateMinContentCache(); }
    void markSubtreeChanged() { flags |= F_SUBTREE_CHANGED; invalidateMinContentCache(); }
    void orLayoutChanged(boolean affects) { if (affects) flags |= F_LAYOUT_CHANGED; }
    void updateDirtyFlags(boolean remeasure, boolean subtreeChanged) {
        setFlag(F_LAYOUT_CHANGED, remeasure);
        setFlag(F_SUBTREE_CHANGED, subtreeChanged);
        if (subtreeChanged) invalidateMinContentCache();
    }
    void setDirtyFlags(boolean dirty) { setFlag(F_LAYOUT_CHANGED, dirty); setFlag(F_SUBTREE_CHANGED, dirty); if (dirty) invalidateMinContentCache(); }
    void clearSubtreeChangedIfClean() { if (!flag(F_LAYOUT_CHANGED)) flags &= ~F_SUBTREE_CHANGED; }
    void invalidateMinContentCache() { cachedMinContentKey = Long.MIN_VALUE; cachedMinContentWidth = -1; }
    float cachedMinContentWidth(long key) { return cachedMinContentKey == key ? cachedMinContentWidth : -1; }
    void cacheMinContentWidth(long key, float width) { cachedMinContentKey = key; cachedMinContentWidth = width; }

    /** テスト・検証用: 主スロットの不変な鍵（未測定は null）。 */
    MeasureKey primaryKey() {
        return measuredSpace == null ? null : new MeasureKey(measuredSpace, measuredOverrideW, measuredOverrideH, outSizingMode);
    }

    /** テスト・検証用: 主スロットの不変な出力（未記録は null）。 */
    MeasureOutputs primaryOutputs() {
        if (!flag(F_HAS_OUTPUTS)) return null;
        return new MeasureOutputs(
                outW, outH, outMT, outMR, outMB, outML,
                outPT, outPR, outPB, outPL, outBT, outBR, outBB, outBL,
                outLegendSpill, outContentBlockExtent, outOwnMarginTop, outOwnMarginBottom, outSizingMode);
    }

    int slotCount(Tentative.Kind kind) {
        TentativeSlots ts = tentativeSlots;
        if (ts == null || ts.entries == null) return 0;
        int n = 0;
        for (Tentative t : ts.entries) if (t != null && t.kind() == kind) n++;
        return n;
    }

    // ------------------------------------------------------------------ 測定入口・中心ループ

    void measureWith(LayoutBox box, ConstraintSpace space, LayoutContext ctx, boolean restoreOk) {
        boolean fixedWidth = !Float.isNaN(space.fixedInlineSize());
        measureCoreWithOverrides(box, space, ctx, restoreOk);
        noteFitToParent(box, measuredFitNeed, fixedWidth);
    }

    void measureParallel(LayoutBox box, ConstraintSpace space, LayoutContext ctx) {
        measureCoreWithOverrides(box, space, ctx, true);
    }

    private void measureCoreWithOverrides(LayoutBox box, ConstraintSpace space, LayoutContext ctx, boolean restoreOk) {
        box.overrideWidth = space.fixedInlineSize();
        box.overrideHeight = space.fixedBlockSize();
        long probe = LayoutParallelProbe.enter(box, LayoutParallelProbe.MEASURE);
        try {
            measureCore(box, space, ctx, restoreOk);
        } finally {
            LayoutParallelProbe.exit(LayoutParallelProbe.MEASURE, probe);
            box.overrideWidth = Float.NaN;
            box.overrideHeight = Float.NaN;
        }
    }

    void applyFitToParent(LayoutBox box, boolean fixedWidth) {
        noteFitToParent(box, measuredFitNeed, fixedWidth);
    }

    float blockChildFitWidth(LayoutBox box) {
        return (flag(F_WIDTH_FREE) && Shortcut.WIDTH_INDEPENDENT_REUSE.on()) ? (box.w + box.mL + box.mR) : measuredFitNeed;
    }

    private static void noteFitToParent(LayoutBox box, float need, boolean fixedWidth) {
        LayoutBox p = box.parent;
        if (p == null || fixedWidth) return;
        MeasureMemo pm = p.measureMemo;
        if (need == Float.POSITIVE_INFINITY) {
            if (!(box.measureMemo.flag(F_WIDTH_FREE) && Shortcut.WIDTH_INDEPENDENT_REUSE.on())) {
                pm.setFlag(F_CHILD_FIT_BLOCKED, true);
            }
        } else if (need > pm.childFitNeedMax) {
            pm.childFitNeedMax = need;
        }
    }

    private boolean measureFuelExhausted(LayoutContext ctx) {
        long[] f = FUEL.get();
        if (f[0] != ctx.layoutRun()) {
            f[0] = ctx.layoutRun();
            f[1] = 0;
            f[2] = 0;
        }
        f[1]++;
        if (measuredSpace == null) {
            f[2]++;
            return false;
        }
        return f[1] > FUEL_BASE + (long) FUEL_PER_BOX * f[2];
    }

    private void measureCore(LayoutBox box, ConstraintSpace space, LayoutContext ctx, boolean restoreOk) {
        restoreTentativeStash(box);
        if (box.needsAdoption) {
            for (int i = 0, n = box.children.size(); i < n; i++) box.adopt(box.children.get(i));
            box.needsAdoption = false;
        }
        if (ctx.reuseMeasurements() && canReuseMeasurement(box, space, ctx, restoreOk)) {
            if (restoreOk) restoreNaturalOutputs(box);
            if (!(flag(F_SUBTREE_CHANGED) && ctx.firstPass())) {
                if (Shortcut.VERIFY_MEMO.on()) verifyReused(box, space, ctx, "reuse");
                return;
            }
            int[] depth = DESCENT.get();
            depth[0]++;
            try {
                remeasureChanged(box, ctx);
            } finally {
                depth[0]--;
            }
            if (flag(F_VALID)) return;
        }
        if (ctx.reuseMeasurements() && (reuseDefiniteTentative(box, space, ctx, restoreOk)
                || fillFitReuse(box, space, ctx, restoreOk))) {
            if (Shortcut.VERIFY_MEMO.on()) verifyReused(box, space, ctx, "tentative/fillfit");
            return;
        }
        if (measureFuelExhausted(ctx)) {
            FUEL_REUSED.increment();
            if (restoreOk) restoreNaturalOutputs(box);
            return;
        }
        MEASUREMENTS_COMPUTED.increment();
        if ((flag(F_LAYOUT_CHANGED) || hasUnmeasuredSubtreeChange(ctx)) && tentativeSlots != null) {
            tentativeSlots.entries = null;
        }
        PaintStamps.touchLayout(box);
        setFlag(F_CHILD_FIT_BLOCKED, false);
        childFitNeedMax = 0;
        BoxMeasurer.measure(box, space, ctx);
        if (flag(F_HAS_TENTATIVE_CHILDREN)) settleTentativeChildren(box);
        recordMeasurement(box, space, ctx);
        PaintStamps.touchLayout(box);
    }

    /** 検証モード（{@code VERIFY_MEMO}）: 記憶で飛ばした箱を測り直して比べる（{@link MeasureVerifier}）。計数は進めない。 */
    private void verifyReused(LayoutBox box, ConstraintSpace space, LayoutContext ctx, String how) {
        if (MeasureVerifier.active() || box.floatSpace != null || box.collapsedBorders != null) return;
        long[] fuel = FUEL.get();
        long[] savedFuel = fuel.clone();
        long computedBefore = MEASUREMENTS_COMPUTED.sum();
        try {
            MeasureVerifier.verify(box, space, ctx, how);
        } finally {
            MEASUREMENTS_COMPUTED.add(-(MEASUREMENTS_COMPUTED.sum() - computedBefore));
            System.arraycopy(savedFuel, 0, fuel, 0, fuel.length);
        }
    }
    Object snapshotSlots() { return tentativeSlots == null ? null : tentativeSlots.copy(); }

    void restoreSlots(Object snapshot) { tentativeSlots = snapshot == null ? null : ((TentativeSlots) snapshot).copy(); }

    // ------------------------------------------------------------------ 仮測定・確定葉スロット

    boolean measureTentatively(LayoutBox box, ConstraintSpace space, LayoutContext ctx) {
        restoreTentativeStash(box);
        TentativeSlots ts = tentativeSlots;
        Tentative[] all = ts == null ? null : ts.entries;
        if (flag(F_SUBGRID_DEPENDENT) || !Shortcut.TENTATIVE_REUSE.on() || all == null
                || !ctx.reuseMeasurements() || box.parent == null) {
            return false;
        }
        Tentative t = null;
        for (Tentative candidate : all) {
            if (candidate != null && candidate.kind() == Tentative.Kind.TENTATIVE && candidate.matches(space)) {
                t = candidate;
                break;
            }
        }
        if (t == null) return false;
        if (!Float.isNaN(box.overrideWidth) || !Float.isNaN(box.overrideHeight)) return false;
        if (!flag(F_VALID) || flag(F_LAYOUT_CHANGED) || hasUnmeasuredSubtreeChange(ctx) || flag(F_HAS_TABLE)) return false;
        if (!(ctx.firstPass() || measuredRun != ctx.layoutRun() || Shortcut.SAME_PASS_REUSE.on())) return false;
        if (t.epoch() != MEASUREMENT_EPOCH.get() || measuredEpoch != t.epoch()
                || box.floatSpace != null || box.collapsedBorders != null
                || !t.matches(space)
                || !t.sameViewport(ctx.viewport()) || ctx.fonts() != t.fonts()
                || ctx.intrinsicResolver() != t.intrinsics()) {
            return false;
        }
        if (box.needsAdoption) {
            for (int i = 0, n = box.children.size(); i < n; i++) box.adopt(box.children.get(i));
            box.needsAdoption = false;
        }
        ts.captureStash(box);
        ts.stashed = true;
        ts.stashedSpace = space;
        ts.stashedCtx = ctx;
        t.outputs().apply(box);
        if (t.fillFit()) box.w = Math.max(0, space.availableInlineSize() - box.mL - box.mR);
        invalidateMinContentCache();
        box.parent.measureMemo.setFlag(F_HAS_TENTATIVE_CHILDREN, true);
        TENTATIVE_SKIPPED.increment();
        noteFitToParent(box, t.fitNeed(), false);
        return true;
    }

    private void restoreTentativeStash(LayoutBox box) {
        TentativeSlots ts = tentativeSlots;
        if (ts == null || !ts.stashed) return;
        ts.stashed = false;
        ts.stashedCtx = null;
        ts.applyStash(box);
        invalidateMinContentCache();
    }

    void settleTentative(LayoutBox box) {
        TentativeSlots ts = tentativeSlots;
        if (ts == null || !ts.stashed) return;
        ConstraintSpace s = ts.stashedSpace;
        LayoutContext c = ts.stashedCtx;
        restoreTentativeStash(box);
        box.measure(s, c);
    }

    void settleTentativeChildren(LayoutBox box) {
        setFlag(F_HAS_TENTATIVE_CHILDREN, false);
        List<LayoutBox> kids = box.children;
        for (int i = 0, n = kids.size(); i < n; i++) {
            LayoutBox child = kids.get(i);
            child.measureMemo.settleTentative(child);
        }
    }

    private void storeTentativeSlot(Tentative.Kind kind, ConstraintSpace space, Viewport viewport, FontResolver fonts,
                                    IntrinsicSizeResolver intrinsics, int epoch, LayoutBox box, boolean percentFree,
                                    boolean shrinkFree, boolean outsideFree, boolean widthFree, float fitNeed,
                                    boolean fillFit, float overrideW, float overrideH) {
        TentativeSlots ts = tentativeSlots;
        if (ts == null) tentativeSlots = ts = new TentativeSlots();
        int maxForKind = kind == Tentative.Kind.TENTATIVE ? MAX_TENTATIVES : MAX_DEFINITE_TENTATIVES;
        int idx = obtainTentativeSlotIndex(ts, kind, space, maxForKind);
        Tentative old = ts.entries[idx];
        if (old != null && old.matchesExactRecord(kind, space, viewport, fonts, intrinsics, epoch, box,
                percentFree, shrinkFree, outsideFree, widthFree, fitNeed, fillFit, overrideW, overrideH)) {
            return;
        }
        MeasureOutputs outputs = (old != null && old.outputs().matchesAll(box))
                ? old.outputs()
                : MeasureOutputs.capture(box);
        ts.entries[idx] = new Tentative(kind, space, viewport, fonts, intrinsics, epoch, outputs,
                percentFree, shrinkFree, outsideFree, widthFree, fitNeed, fillFit, overrideW, overrideH);
    }

    private static int obtainTentativeSlotIndex(TentativeSlots ts, Tentative.Kind kind, ConstraintSpace space, int maxForKind) {
        if (ts.entries == null) {
            ts.entries = new Tentative[MAX_TENTATIVES];
            return 0;
        }
        Tentative[] entries = ts.entries;
        int firstNull = -1, countForKind = 0, oldestOfKind = -1;
        for (int i = 0; i < entries.length; i++) {
            Tentative old = entries[i];
            if (old == null) {
                if (firstNull < 0) firstNull = i;
                continue;
            }
            if (old.kind() == kind) {
                if (old.matches(space)) return i;
                if (oldestOfKind < 0) oldestOfKind = i;
                countForKind++;
            }
        }
        if (countForKind < maxForKind) {
            if (firstNull >= 0) return firstNull;
            int nextLen = Math.min(MAX_TOTAL_TENTATIVES, entries.length + 2);
            ts.entries = Arrays.copyOf(entries, nextLen);
            return ts.entries.length - 2;
        }
        int lastNonNull = oldestOfKind;
        for (int i = oldestOfKind + 1; i < entries.length && entries[i] != null; i++) {
            entries[i - 1] = entries[i];
            lastNonNull = i;
        }
        entries[lastNonNull] = null;
        return lastNonNull;
    }

    private boolean reuseDefiniteTentative(LayoutBox box, ConstraintSpace space, LayoutContext ctx, boolean restoreOk) {
        TentativeSlots ts = tentativeSlots;
        if (flag(F_SUBGRID_DEPENDENT) || !Shortcut.TENTATIVE_REUSE.on() || ts == null || ts.entries == null
                || (Float.isNaN(box.overrideWidth) && Float.isNaN(box.overrideHeight))) {
            return false;
        }
        if (!flag(F_VALID) || flag(F_LAYOUT_CHANGED) || hasUnmeasuredSubtreeChange(ctx)
                || !MeasureAnalysis.isDefiniteCacheableLeaf(box)) {
            return false;
        }
        if (!(ctx.firstPass() || measuredRun != ctx.layoutRun() || Shortcut.SAME_PASS_REUSE.on())) return false;
        int epoch = MEASUREMENT_EPOCH.get();
        if (measuredEpoch != epoch) return false;
        Tentative match = null;
        for (Tentative t : ts.entries) {
            if (t == null || t.kind() != Tentative.Kind.DEFINITE) continue;
            if (t.epoch() == epoch && t.fonts() == ctx.fonts() && t.intrinsics() == ctx.intrinsicResolver()
                    && MeasureAnalysis.sameLength(t.overrideW(), box.overrideWidth)
                    && MeasureAnalysis.sameLength(t.overrideH(), box.overrideHeight)
                    && t.matches(space) && t.sameViewport(ctx.viewport())) {
                match = t;
                break;
            }
        }
        if (match == null) return false;
        MeasureOutputs o = match.outputs();
        boolean outputsDiffer = !o.matchesNatural(box);
        if (!restoreOk && outputsDiffer) return false;
        if (restoreOk && outputsDiffer) {
            o.apply(box);
            invalidateMinContentCache();
            PaintStamps.touchLayout(box);
        }
        setFlag(F_BLOCK_PERCENT_FREE, match.percentFree());
        setFlag(F_OUTSIDE_FREE, match.outsideFree());
        setFlag(F_WIDTH_FREE, match.widthFree());
        measuredSpace = space;
        measuredOverrideW = box.overrideWidth;
        measuredOverrideH = box.overrideHeight;
        measuredViewport = ctx.viewport();
        measuredFonts = match.fonts();
        measuredIntrinsics = match.intrinsics();
        measuredEpoch = epoch;
        measuredRun = ctx.layoutRun();
        capturePrimaryOutputs(box);
        measuredFitNeed = match.fitNeed();
        setFlag(F_FIT_FIXED_W, !Float.isNaN(box.overrideWidth) && measuredFitNeed != Float.POSITIVE_INFINITY);
        setFlag(F_FILL_FIT_REUSABLE, false);
        box.measuredClamped = false;
        setFlag(F_CHILD_FIT_BLOCKED, false);
        childFitNeedMax = 0;
        return true;
    }

    // ------------------------------------------------------------------ 下降再測定・主スロット再利用

    private void remeasureChanged(LayoutBox box, LayoutContext ctx) {
        List<LayoutBox> kids = box.children;
        for (int i = 0, n = kids.size(); i < n; i++) {
            LayoutBox child = kids.get(i);
            MeasureMemo cm = child.measureMemo;
            if (!cm.flag(F_SUBTREE_CHANGED) || cm.measuredSpace == null) continue;
            if (cm.flag(F_LAYOUT_CHANGED) || MeasureChanges.isRelayoutBoundary(child) || MeasureAnalysis.isCutoffEligible(child)) {
                cm.measureWith(child, cm.measuredSpace, ctx, false);
            } else {
                cm.remeasureChanged(child, ctx);
                if (!cm.flag(F_VALID)) cm.measureWith(child, cm.measuredSpace, ctx, false);
            }
        }
    }

    private boolean hasUnmeasuredSubtreeChange(LayoutContext ctx) {
        return flag(F_SUBTREE_CHANGED) && measuredRun != ctx.layoutRun();
    }

    private boolean fillFitReuse(LayoutBox box, ConstraintSpace space, LayoutContext ctx, boolean restoreOk) {
        if (flag(F_SUBGRID_DEPENDENT) || !restoreOk || !flag(F_FILL_FIT_REUSABLE)
                || !Float.isNaN(box.overrideWidth) || !Float.isNaN(box.overrideHeight)) {
            return false;
        }
        if (!flag(F_VALID) || flag(F_LAYOUT_CHANGED) || hasUnmeasuredSubtreeChange(ctx)) return false;
        if (!(ctx.firstPass() || measuredRun != ctx.layoutRun() || (Shortcut.SAME_PASS_REUSE.on() && !flag(F_HAS_TABLE)))) {
            return false;
        }
        if (measuredEpoch != MEASUREMENT_EPOCH.get() || box.floatSpace != null || box.collapsedBorders != null) return false;
        if (ctx.fonts() != measuredFonts || ctx.intrinsicResolver() != measuredIntrinsics) return false;
        if (!MeasureAnalysis.matchesFitConstraint(box, space, ctx, measuredFitNeed,
                flag(F_BLOCK_PERCENT_FREE), flag(F_SHRINK_INDEPENDENT), measuredSpace, measuredViewport)) {
            return false;
        }
        restoreNaturalOutputs(box);
        float newW = Math.max(0, space.availableInlineSize() - box.mL - box.mR);
        if (newW != box.w) {
            box.w = newW;
            invalidateMinContentCache();
            PaintStamps.touchLayout(box);
            box.invalidatePlacementValid();
        }
        measuredSpace = space;
        measuredViewport = ctx.viewport();
        measuredRun = ctx.layoutRun();
        capturePrimaryOutputs(box);
        return true;
    }

    boolean canPreserveForEmptyFloatFlow(LayoutBox box, ConstraintSpace space, LayoutContext ctx) {
        if (flag(F_SUBGRID_DEPENDENT) || !flag(F_VALID) || flag(F_LAYOUT_CHANGED) || hasUnmeasuredSubtreeChange(ctx)
                || measuredSpace == null || flag(F_HAS_TABLE) || box.contentAutoSkipped
                || measuredEpoch != MEASUREMENT_EPOCH.get()
                || !Float.isNaN(box.overrideWidth) || !Float.isNaN(box.overrideHeight)
                || !Float.isNaN(measuredOverrideW) || !Float.isNaN(measuredOverrideH)) {
            return false;
        }
        float avail = space.availableInlineSize();
        float prevAvail = measuredSpace.availableInlineSize();
        boolean sameWidth = Float.floatToIntBits(avail) == Float.floatToIntBits(prevAvail)
                || (flag(F_FIT_FIXED_W) && measuredFitNeed != Float.POSITIVE_INFINITY
                        && avail >= measuredFitNeed && prevAvail >= measuredFitNeed);
        return sameWidth
                && (flag(F_BLOCK_PERCENT_FREE)
                        || Float.floatToIntBits(measuredSpace.percentageBlockSize()) == Float.floatToIntBits(space.percentageBlockSize()))
                && MeasureAnalysis.sameViewportForMeasure(ctx.viewport(), measuredViewport, flag(F_OUTSIDE_FREE))
                && ctx.fonts() == measuredFonts
                && ctx.intrinsicResolver() == measuredIntrinsics;
    }

    boolean outputsMatchRecorded(LayoutBox box) {
        return flag(F_VALID) && box.w == outW && box.h == outH
                && box.mT == outMT && box.mR == outMR && box.mB == outMB && box.mL == outML
                && (!flag(F_HAS_OUTPUTS) || box.contentBlockExtent == outContentBlockExtent);
    }

    private boolean canReuseMeasurement(LayoutBox box, ConstraintSpace space, LayoutContext ctx, boolean restoreOk) {
        boolean pctFree = flag(F_BLOCK_PERCENT_FREE);
        boolean shrinkInd = flag(F_SHRINK_INDEPENDENT);
        return !flag(F_SUBGRID_DEPENDENT)
                && flag(F_VALID)
                && !flag(F_LAYOUT_CHANGED)
                && (ctx.firstPass() || measuredRun != ctx.layoutRun() || (Shortcut.SAME_PASS_REUSE.on() && !flag(F_HAS_TABLE)))
                && measuredEpoch == MEASUREMENT_EPOCH.get()
                && box.floatSpace == null
                && box.collapsedBorders == null
                && ((MeasureAnalysis.sameConstraintForReuse(space, measuredSpace, pctFree, shrinkInd)
                                && MeasureAnalysis.sameViewportForMeasure(ctx.viewport(), measuredViewport, flag(F_OUTSIDE_FREE)))
                        || MeasureAnalysis.widthOnlyChange(space, ctx, flag(F_WIDTH_FREE), pctFree, shrinkInd, measuredSpace, measuredViewport)
                        || (flag(F_FIT_FIXED_W) && MeasureAnalysis.matchesFitConstraint(box, space, ctx,
                                measuredFitNeed, pctFree, shrinkInd, measuredSpace, measuredViewport)))
                && MeasureAnalysis.sameLength(box.overrideWidth, measuredOverrideW)
                && MeasureAnalysis.sameLength(box.overrideHeight, measuredOverrideH)
                && box.contentAutoSkipped == flag(F_CONTENT_AUTO_SKIPPED)
                && ctx.fonts() == measuredFonts
                && ctx.intrinsicResolver() == measuredIntrinsics
                && (restoreOk || (box.w == outW && box.h == outH
                        && box.mT == outMT && box.mR == outMR && box.mB == outMB && box.mL == outML));
    }

    private void restoreNaturalOutputs(LayoutBox box) {
        if (!flag(F_HAS_OUTPUTS)) return;
        if (box.w == outW && box.h == outH && box.mT == outMT && box.mR == outMR && box.mB == outMB && box.mL == outML
                && box.contentBlockExtent == outContentBlockExtent) {
            return;
        }
        box.w = outW; box.h = outH; box.mT = outMT; box.mR = outMR; box.mB = outMB; box.mL = outML;
        box.pT = outPT; box.pR = outPR; box.pB = outPB; box.pL = outPL;
        box.bT = outBT; box.bR = outBR; box.bB = outBB; box.bL = outBL;
        box.legendSpill = outLegendSpill; box.contentBlockExtent = outContentBlockExtent;
        box.ownMarginTop = outOwnMarginTop; box.ownMarginBottom = outOwnMarginBottom;
        box.sizingMode = outSizingMode;
        invalidateMinContentCache();
        PaintStamps.touchLayout(box);
    }

    private void capturePrimaryOutputs(LayoutBox b) {
        outW = b.w; outH = b.h; outMT = b.mT; outMR = b.mR; outMB = b.mB; outML = b.mL;
        outPT = b.pT; outPR = b.pR; outPB = b.pB; outPL = b.pL;
        outBT = b.bT; outBR = b.bR; outBB = b.bB; outBL = b.bL;
        outLegendSpill = b.legendSpill;
        outContentBlockExtent = b.contentBlockExtent;
        outOwnMarginTop = b.ownMarginTop;
        outOwnMarginBottom = b.ownMarginBottom;
        outSizingMode = b.sizingMode;
        setFlag(F_HAS_OUTPUTS, true);
    }

    private void recordMeasurement(LayoutBox box, ConstraintSpace space, LayoutContext ctx) {
        boolean outerSizeStable = measuredStyle != null && box.w == outW && box.h == outH
                && box.mT == outMT && box.mR == outMR && box.mB == outMB && box.mL == outML;
        setFlag(F_BLOCK_PERCENT_FREE, MeasureAnalysis.computeBlockPercentFree(box));
        measuredSpace = space;
        measuredOverrideW = box.overrideWidth;
        measuredOverrideH = box.overrideHeight;
        measuredViewport = ctx.viewport();
        measuredFonts = ctx.fonts();
        measuredIntrinsics = ctx.intrinsicResolver();
        measuredEpoch = MEASUREMENT_EPOCH.get();
        measuredRun = ctx.layoutRun();
        capturePrimaryOutputs(box);
        measuredStyle = box.style;
        measuredPlaceholderStyle = box.placeholderStyle;
        measuredText = box.displayText;
        measuredContentHash = contentHash(box);
        setFlag(F_OUTSIDE_MARKER, box.isOutsideMarker);
        setFlag(F_RENDERED_LEGEND, box.isRenderedLegend);
        setFlag(F_CONTENT_AUTO_SKIPPED, box.contentAutoSkipped);
        boolean isTbl = LayoutBox.isTableBox(box) || box.collapsedBorders != null;
        if (isTbl) setFlag(F_EVER_TABLE, true);
        measuredChildren = MeasureAnalysis.sameChildren(measuredChildren, box.children) ? measuredChildren
                : box.children.isEmpty() ? EMPTY_BOX_ARRAY : box.children.toArray(EMPTY_BOX_ARRAY);
        setFlag(F_FIRST_FLOW_CHILD, BlockLayoutEngine.isFirstFlowChild(box));
        setFlag(F_HAD_FLOAT_CHILD, MeasureAnalysis.hasFloatingChild(box));
        boolean floatFree = true;
        List<LayoutBox> kids = box.children;
        for (int i = 0, n = kids.size(); i < n && floatFree; i++) {
            LayoutBox c = kids.get(i);
            floatFree = !BlockLayoutEngine.isFloating(c) && !BlockLayoutEngine.containsFloats(c);
        }
        setFlag(F_FLOW_FLOAT_FREE, floatFree);
        setFlag(F_LAYOUT_CHANGED, false);
        boolean hasTable = flag(F_EVER_TABLE);
        boolean minContentVar = false;
        for (int i = 0, n = kids.size(); i < n; i++) {
            LayoutBox c = kids.get(i);
            if (!hasTable && c.measureMemo.flag(F_HAS_TABLE)) hasTable = true;
            if (!minContentVar && (c.participatesInFlow() || c.isRenderedLegend)) {
                minContentVar = c.measureMemo.flag(F_MIN_CONTENT_VAR)
                        || c.axisStyle().layoutFacts().percentGeometry
                        || (c.children.isEmpty() && MeasureAnalysis.isFallbackReplacedForMinContent(c));
            }
        }
        setFlag(F_HAS_TABLE, hasTable);
        setFlag(F_MIN_CONTENT_VAR, minContentVar);
        boolean outsideFree = MeasureAnalysis.computeOutsideFree(box);
        setFlag(F_OUTSIDE_FREE, outsideFree);
        setFlag(F_WIDTH_FREE, MeasureAnalysis.computeWidthFree(box, outsideFree, hasTable));
        setFlag(F_SHRINK_INDEPENDENT, MeasureAnalysis.computeShrinkIndependent(box, space.parent()));
        float fitNeed = MeasureAnalysis.computeFitNeed(box, outsideFree, hasTable, floatFree,
                flag(F_CHILD_FIT_BLOCKED), childFitNeedMax);
        measuredFitNeed = fitNeed;
        setFlag(F_FIT_FIXED_W, MeasureAnalysis.isFitFixedW(box, fitNeed));
        setFlag(F_FILL_FIT_REUSABLE, MeasureAnalysis.isFillFitReusable(box, fitNeed));
        if (Shortcut.TENTATIVE_REUSE.on() && MeasureAnalysis.parentUsesTentatives(box)
                && box.floatSpace == null && box.collapsedBorders == null) {
            boolean tentativePhase = Float.isNaN(box.overrideWidth) && Float.isNaN(box.overrideHeight);
            if (tentativePhase || MeasureAnalysis.isDefiniteCacheableLeaf(box)) {
                Tentative.Kind kind = tentativePhase ? Tentative.Kind.TENTATIVE : Tentative.Kind.DEFINITE;
                boolean fillFit = tentativePhase && flag(F_FILL_FIT_REUSABLE);
                float tentFitNeed = tentativePhase
                        ? ((flag(F_FIT_FIXED_W) || fillFit) ? measuredFitNeed : Float.POSITIVE_INFINITY)
                        : (!Float.isNaN(box.overrideWidth) && !box.axisStyle().layoutFacts().edgesAvailableFree
                                ? Float.POSITIVE_INFINITY : measuredFitNeed);
                storeTentativeSlot(kind, space, ctx.viewport(), ctx.fonts(), ctx.intrinsicResolver(),
                        measuredEpoch, box, flag(F_BLOCK_PERCENT_FREE), flag(F_SHRINK_INDEPENDENT),
                        outsideFree, flag(F_WIDTH_FREE), tentFitNeed, fillFit,
                        box.overrideWidth, box.overrideHeight);
            }
        }
        setFlag(F_VALID, true);
        boolean absorbed = outerSizeStable && (MeasureChanges.isRelayoutBoundary(box) || MeasureAnalysis.isCutoffEligible(box));
        if (MeasureVerifier.active()) return;
        if (DESCENT.get()[0] > 0) invalidateParentOnly(box, !absorbed);
        else invalidateAncestors(box, !(outerSizeStable && MeasureChanges.isRelayoutBoundary(box)));
    }

    // ------------------------------------------------------------------ 無効化・署名照合

    static void invalidate(LayoutBox box) {
        PaintStamps.touchLayout(box);
        for (LayoutBox b = box; b != null; b = b.parent) {
            b.measureMemo.setFlag(F_VALID, false);
            b.invalidatePlacementValid();
        }
    }

    private static void invalidateParentOnly(LayoutBox box, boolean measureAffected) {
        for (LayoutBox p = box.parent; p != null; p = p.parent) p.invalidatePlacementValid();
        if (measureAffected && box.parent != null) box.parent.measureMemo.setFlag(F_VALID, false);
    }

    static void invalidateAncestors(LayoutBox box, boolean measureAffected) {
        boolean measureStillValid = measureAffected;
        for (LayoutBox p = box.parent; p != null; p = p.parent) {
            p.invalidatePlacementValid();
            if (measureStillValid) {
                p.measureMemo.setFlag(F_VALID, false);
                if (MeasureChanges.isRelayoutBoundary(p)) measureStillValid = false;
            }
        }
    }

    void markVisibilityChangedForStabilize(LayoutBox box) {
        setFlag(F_VALID, false);
        flags |= F_LAYOUT_CHANGED | F_SUBTREE_CHANGED;
        invalidateMinContentCache();
        for (LayoutBox p = box.parent; p != null; p = p.parent) {
            p.measureMemo.markSubtreeChanged();
        }
        invalidateAncestors(box, true);
    }

    private int contentHash(LayoutBox box) {
        HtmlNode node = box.node;
        if (node == null) return 0;
        int version = node.attrVersion();
        if (attrHashVersion != version || attrHashNode != node) {
            int hash = 1;
            for (Map.Entry<String, String> attr : node.attrs.entrySet()) {
                String key = attr.getKey();
                if ("style".equals(key) || "class".equals(key)) continue;
                hash = 31 * hash + key.hashCode();
                hash = 31 * hash + Objects.hashCode(attr.getValue());
            }
            attrHash = hash;
            attrHashVersion = version;
            attrHashNode = node;
        }
        int hash = attrHash;
        String tag = node.tag;
        if (com.nishiyu.enderframelib.core.dom.node.ElementTraits.contentTextAffectsMeasure(tag)) {
            hash = 31 * hash + node.getTextContent().hashCode();
        }
        return hash;
    }

    boolean selfSignatureChanged(LayoutBox box) {
        if (measuredStyle == null) return true;
        if (box.style != measuredStyle && StyleResolver.isLayoutAffectingChange(measuredStyle, box.style)) return true;
        if (box.placeholderStyle != measuredPlaceholderStyle
                && StyleResolver.isLayoutAffectingChange(measuredPlaceholderStyle, box.placeholderStyle)) return true;
        if (!Objects.equals(box.displayText, measuredText)) return true;
        if (box.isOutsideMarker != flag(F_OUTSIDE_MARKER) || box.isRenderedLegend != flag(F_RENDERED_LEGEND)) return true;
        return contentHash(box) != measuredContentHash;
    }

    boolean childListChanged(LayoutBox box) { return !MeasureAnalysis.sameChildren(measuredChildren, box.children); }
    boolean hadFloatChild() { return flag(F_HAD_FLOAT_CHILD); }
    boolean firstFlowStatusChanged(LayoutBox box) { return BlockLayoutEngine.isFirstFlowChild(box) != flag(F_FIRST_FLOW_CHILD); }
}
