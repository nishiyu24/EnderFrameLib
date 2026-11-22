package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.StyleResolver;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.kernel.EngineFlags;
import com.nishiyu.enderframelib.core.kernel.Shortcut;
import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.model.ExternalEpochs;
import com.nishiyu.enderframelib.core.layout.model.Viewport;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * ボックスの測定結果・仮測定・確定葉スロット・読み取り集合・変更印を一元管理する記憶（A7・C3）。
 *
 * <p>すべての記憶欄を {@code private} としてこのクラスに閉じ、誰がいつ書き換えたかを型で追えるようにする。
 * 鍵（{@link MeasureKey}＋{@link ExternalEpochs}）・入力読み取り集合（{@code readMask}）・親が子から読んだ出力集合
 * （{@code parentReadMask}）を一体で持ち、免除と cutoff の規則を 1 箇所から導出する。</p>
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
    private static final int F_MIN_CONTENT_VAR = 1 << 11;
    private static final int F_FLOW_FLOAT_FREE = 1 << 12;
    private static final int F_HAS_TENTATIVE_CHILDREN = 1 << 13;
    private static final int F_CHILD_FIT_BLOCKED = 1 << 14;
    private static final int F_SUBGRID_DEPENDENT = 1 << 15;
    private static final int F_OUT_BFC = 1 << 16;

    private static final int FUEL_PER_BOX = 200;
    private static final int FUEL_BASE = 50_000;
    private static final int MAX_TENTATIVES = 4;
    private static final int MAX_DEFINITE_TENTATIVES = 2;
    private static final LayoutBox[] EMPTY_BOX_ARRAY = new LayoutBox[0];

    /** 外から測り直しを強いる世代（画像の読み込み・書体の登録など、箱の外の資源が変わったとき）。 */
    private static final AtomicInteger MEASUREMENT_EPOCH = new AtomicInteger();
    private static final LongAdder MEASUREMENTS_COMPUTED = new LongAdder();
    private static final LongAdder TENTATIVE_SKIPPED = new LongAdder();
    private static final LongAdder FUEL_REUSED = new LongAdder();
    static final boolean LAZY_THROUGH_INELIGIBLE = EngineFlags.LAZY_THROUGH_INELIGIBLE;

    private int flags = F_LAYOUT_CHANGED | F_SUBTREE_CHANGED;
    private MeasureKey measuredKey;
    private int readMask;
    private long measuredRun = -1;

    // 親が自分から読んだ出力集合（前回の測定パス中に親が記録する）
    private int parentReadMask;
    private ConstraintSpace parentFirstSpace;
    private float parentReadMinContent = Float.NaN;
    private float parentReadFirstBaseline = Float.NaN;
    private float parentReadLastBaseline = Float.NaN;

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
    boolean isBlockPercentFree() { return (readMask & MeasureReads.READ_PERCENT_BLOCK) == 0; }
    boolean isOutsideFree() { return (readMask & MeasureReads.READ_VIEWPORT_W) == 0; }
    boolean isWidthFree() { return (readMask & (MeasureReads.READ_AVAIL_INLINE | MeasureReads.READ_VIEWPORT_W)) == 0; }
    boolean isFlowFloatFree() { return flag(F_FLOW_FLOAT_FREE); }
    boolean isMinContentVar() { return flag(F_MIN_CONTENT_VAR); }
    boolean hasTentativeChildren() { return flag(F_HAS_TENTATIVE_CHILDREN); }
    boolean isTentativeStashed() { return tentativeSlots != null && tentativeSlots.stashed; }
    boolean measuredWithoutFixedHeight() { return measuredKey != null && Float.isNaN(measuredKey.space().fixedBlockSize()); }
    boolean needsParallelWork() { return flag(F_SUBTREE_CHANGED | F_LAYOUT_CHANGED) || !flag(F_VALID); }
    void setSubgridDependent(boolean v) { setFlag(F_SUBGRID_DEPENDENT, v); }
    ConstraintSpace measuredSpace() { return measuredKey != null ? measuredKey.space() : null; }
    long measuredRun() { return measuredRun; }
    int readMask() { return readMask; }
    int parentReadMask() { return parentReadMask; }
    int transitiveReads() { return readMask & MeasureReads.TRANSITIVE_MASK; }
    boolean hasParentReadMinContent() { return (parentReadMask & MeasureReads.PARENT_READ_MIN_CONTENT) != 0; }

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
    MeasureKey primaryKey() { return measuredKey; }

    /** テスト・検証用: 主スロットの不変な出力（未記録は null）。 */
    MeasureOutputs primaryOutputs() {
        if (!flag(F_HAS_OUTPUTS)) return null;
        return new MeasureOutputs(
                outW, outH, outMT, outMR, outMB, outML, outPT, outPR, outPB, outPL, outBT, outBR, outBB, outBL,
                outLegendSpill, outContentBlockExtent, outOwnMarginTop, outOwnMarginBottom, outSizingMode);
    }

    int slotCount(Tentative.Kind kind) {
        TentativeSlots ts = tentativeSlots;
        if (ts == null || ts.entries == null) return 0;
        int n = 0;
        for (Tentative t : ts.entries) if (t != null && t.kind() == kind) n++;
        return n;
    }

    // ------------------------------------------------------------------ 親が子から読んだ集合の記録と cutoff

    private void resetParentReads() {
        parentReadMask = 0;
        parentFirstSpace = null;
        parentReadMinContent = parentReadFirstBaseline = parentReadLastBaseline = Float.NaN;
    }

    private static void beginParentReadOfChildren(LayoutBox box) {
        List<LayoutBox> kids = box.children;
        for (int i = 0, n = kids.size(); i < n; i++) kids.get(i).measureMemo.resetParentReads();
    }

    void noteParentMeasured(ConstraintSpace space) {
        parentReadMask |= MeasureReads.PARENT_READ_OUTER;
        if (parentFirstSpace == null) parentFirstSpace = space;
        else if (!parentFirstSpace.equals(space)) parentReadMask |= MeasureReads.PARENT_READ_MULTI_SPACE;
        if (!Float.isNaN(space.fixedInlineSize()) || !Float.isNaN(space.fixedBlockSize())) parentReadMask |= MeasureReads.PARENT_READ_STRETCHED;
    }

    void noteParentReadOuter() { parentReadMask |= MeasureReads.PARENT_READ_OUTER; }
    void noteParentReadContentExtent() { parentReadMask |= MeasureReads.PARENT_READ_CONTENT_EXTENT; }
    void noteParentReadFitNeed() { parentReadMask |= MeasureReads.PARENT_READ_FIT_NEED; }
    void noteParentReadStretched() { parentReadMask |= MeasureReads.PARENT_READ_STRETCHED; }
    void noteParentReadOpaque() { parentReadMask |= MeasureReads.PARENT_READ_OPAQUE; }
    void noteParentReadMinContent(float w) { parentReadMask |= MeasureReads.PARENT_READ_MIN_CONTENT; parentReadMinContent = w; }
    void noteParentReadFirstBaseline(float b) { parentReadMask |= MeasureReads.PARENT_READ_FIRST_BASELINE; parentReadFirstBaseline = b; }
    void noteParentReadLastBaseline(float b) { parentReadMask |= MeasureReads.PARENT_READ_LAST_BASELINE; parentReadLastBaseline = b; }

    boolean canAttemptCutoff(LayoutBox box) {
        if (!Shortcut.CUTOFF.on() || box.parent == null || measuredKey == null) return false;
        MeasureMemo pm = box.parent.measureMemo;
        if (!pm.isValid() || MeasureReads.isOnStack(box.parent)) return false;
        if (parentReadMask == 0 || (parentReadMask & MeasureReads.PARENT_CUTOFF_BLOCKERS) != 0) return false;
        if (box.node == null || box.node.nodeType == NodeType.TEXT || box.display == null || box.display.isNone()
                || box.display.isInlineLevel() || box.isAbsolute || box.isRelative || box.isSticky
                || box.isOutsideMarker || box.isRenderedLegend || LayoutBox.isTableBox(box)
                || box.collapsedBorders != null || flag(F_EVER_TABLE) || box.floatSpace != null
                || BlockLayoutEngine.isFloating(box) || BlockLayoutEngine.containsFloats(box)) {
            return false;
        }
        int relayoutExtraMask = MeasureReads.PARENT_READ_CONTENT_EXTENT | MeasureReads.PARENT_READ_MIN_CONTENT
                | MeasureReads.PARENT_READ_FIRST_BASELINE | MeasureReads.PARENT_READ_LAST_BASELINE;
        for (LayoutBox a = box.parent; a != null; a = a.parent) {
            MeasureMemo am = a.measureMemo;
            if (!am.isFlowFloatFree()) return false;
            if (a.parent != null && (am.parentReadMask & MeasureReads.PARENT_CUTOFF_BLOCKERS) != 0) {
                if (MeasureChanges.isRelayoutBoundary(a) && (am.parentReadMask & relayoutExtraMask) == 0) break;
                return false;
            }
        }
        return true;
    }

    private boolean extraParentReadsMatch(LayoutBox box, LayoutContext ctx) {
        if ((parentReadMask & MeasureReads.PARENT_READ_CONTENT_EXTENT) != 0 && box.contentBlockExtent != outContentBlockExtent) return false;
        int slot = MeasureReads.pushMeasuring(null);
        try {
            if ((parentReadMask & MeasureReads.PARENT_READ_MIN_CONTENT) != 0
                    && Float.floatToIntBits(IntrinsicSizing.getMinContentWidth(box, ctx)) != Float.floatToIntBits(parentReadMinContent)) return false;
            if ((parentReadMask & MeasureReads.PARENT_READ_FIRST_BASELINE) != 0) {
                Float cur = BlockLayoutEngine.firstBaseline(box);
                if (Float.floatToIntBits(cur != null ? cur : Float.NaN) != Float.floatToIntBits(parentReadFirstBaseline)) return false;
            }
            if ((parentReadMask & MeasureReads.PARENT_READ_LAST_BASELINE) != 0) {
                Float cur = BlockLayoutEngine.lastBaseline(box);
                if (Float.floatToIntBits(cur != null ? cur : Float.NaN) != Float.floatToIntBits(parentReadLastBaseline)) return false;
            }
            return true;
        } finally {
            MeasureReads.popMeasuring(slot);
        }
    }

    private boolean parentReadStillMatches(LayoutBox box, LayoutContext ctx, boolean selfChanged) {
        if (measuredStyle == null || selfChanged || !canAttemptCutoff(box)) return false;
        if ((parentReadMask & MeasureReads.PARENT_READ_OUTER) != 0 && (!outputsMatchOuter(box) || box.sizingMode != outSizingMode)) return false;
        return extraParentReadsMatch(box, ctx);
    }

    private boolean outputsMatchOuter(LayoutBox box) {
        return box.w == outW && box.h == outH && box.mT == outMT && box.mR == outMR && box.mB == outMB && box.mL == outML
                && box.pT == outPT && box.pR == outPR && box.pB == outPB && box.pL == outPL && box.bT == outBT
                && box.bR == outBR && box.bB == outBB && box.bL == outBL && box.legendSpill == outLegendSpill
                && box.establishesBfc == flag(F_OUT_BFC);
    }

    private void refreshTransitiveParentReads(LayoutBox box, LayoutContext ctx) {
        int slot = MeasureReads.pushMeasuring(box.parent);
        try {
            if ((parentReadMask & MeasureReads.PARENT_READ_FIRST_BASELINE) != 0) BlockLayoutEngine.firstBaseline(box);
            if ((parentReadMask & MeasureReads.PARENT_READ_LAST_BASELINE) != 0) BlockLayoutEngine.lastBaseline(box);
            if ((parentReadMask & MeasureReads.PARENT_READ_MIN_CONTENT) != 0) IntrinsicSizing.readChildMinContentWidth(box, ctx);
        } finally {
            MeasureReads.popMeasuring(slot);
        }
    }

    private void propagateFitNeedOnCutoff(LayoutBox box) {
        if ((parentReadMask & MeasureReads.PARENT_READ_FIT_NEED) == 0) return;
        float needForParent = blockChildFitWidth(box);
        for (LayoutBox a = box.parent; a != null; a = a.parent) {
            MeasureMemo am = a.measureMemo;
            if (!am.isValid()) break;
            if (needForParent == Float.POSITIVE_INFINITY) {
                am.setFlag(F_CHILD_FIT_BLOCKED, true);
                am.measuredFitNeed = Float.POSITIVE_INFINITY;
                am.readMask &= ~(MeasureReads.READ_FIT_BOUNDED | MeasureReads.READ_FILL_FIT);
            } else {
                if (needForParent > am.childFitNeedMax) am.childFitNeedMax = needForParent;
                if (am.measuredFitNeed != Float.POSITIVE_INFINITY) {
                    float ancNeed = needForParent + a.horizontalDecoration() + a.mL + a.mR + (needForParent > 0 ? 0.02f : 0f);
                    if (ancNeed > am.measuredFitNeed) am.measuredFitNeed = ancNeed;
                }
            }
            if ((am.parentReadMask & MeasureReads.PARENT_READ_FIT_NEED) == 0) break;
            needForParent = am.blockChildFitWidth(a);
        }
    }

    // ------------------------------------------------------------------ 測定入口・中心ループ

    void measureWith(LayoutBox box, ConstraintSpace space, LayoutContext ctx, boolean restoreOk) {
        boolean fixedWidth = !Float.isNaN(space.fixedInlineSize()), fromParent = MeasureReads.currentMeasuringBox() == box.parent && box.parent != null;
        if (fromParent) noteParentMeasured(space);
        measureCoreWithOverrides(box, space, ctx, restoreOk);
        if (fromParent) { MeasureReads.propagateChildReads(box); if (!fixedWidth) noteParentReadFitNeed(); }
        noteFitToParent(box, measuredFitNeed, fixedWidth);
    }

    void measureParallel(LayoutBox box, ConstraintSpace space, LayoutContext ctx) { measureCoreWithOverrides(box, space, ctx, true); }

    void noteParallelMeasured(LayoutBox box, ConstraintSpace childSpace, boolean fixedWidth) {
        if (MeasureReads.currentMeasuringBox() == box.parent && box.parent != null) {
            noteParentMeasured(childSpace); MeasureReads.propagateChildReads(box); if (!fixedWidth) noteParentReadFitNeed();
        }
        noteFitToParent(box, measuredFitNeed, fixedWidth);
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

    float blockChildFitWidth(LayoutBox box) {
        return (isWidthFree() && Shortcut.WIDTH_INDEPENDENT_REUSE.on()) ? (box.w + box.mL + box.mR) : measuredFitNeed;
    }

    private static void noteFitToParent(LayoutBox box, float need, boolean fixedWidth) {
        LayoutBox p = box.parent;
        if (p == null || fixedWidth) return;
        MeasureMemo pm = p.measureMemo;
        if (need == Float.POSITIVE_INFINITY) {
            if (!(box.measureMemo.isWidthFree() && Shortcut.WIDTH_INDEPENDENT_REUSE.on())) pm.setFlag(F_CHILD_FIT_BLOCKED, true);
        } else if (need > pm.childFitNeedMax) pm.childFitNeedMax = need;
    }

    private boolean measureFuelExhausted(LayoutContext ctx) {
        MeasureReads.ThreadState st = MeasureReads.state();
        if (st.fuelRun != ctx.layoutRun()) {
            st.fuelRun = ctx.layoutRun();
            st.fuelCount = 0;
            st.fuelFirstBoxes = 0;
        }
        st.fuelCount++;
        if (measuredKey == null) {
            st.fuelFirstBoxes++;
            return false;
        }
        return st.fuelCount > FUEL_BASE + (long) FUEL_PER_BOX * st.fuelFirstBoxes;
    }

    private void measureCore(LayoutBox box, ConstraintSpace space, LayoutContext ctx, boolean restoreOk) {
        restoreTentativeStash(box); MemoKpi.MEASURE_CALLS.increment();
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
            MeasureReads.ThreadState st = MeasureReads.state();
            st.descentDepth++;
            try {
                remeasureChanged(box, ctx);
            } finally {
                st.descentDepth--;
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
        int mSlot = MeasureReads.pushMeasuring(box);
        beginParentReadOfChildren(box);
        int recordedReads;
        try {
            BoxMeasurer.measure(box, space, ctx);
            if (flag(F_HAS_TENTATIVE_CHILDREN)) settleTentativeChildren(box);
        } finally {
            recordedReads = MeasureReads.popMeasuring(mSlot);
        }
        recordMeasurement(box, space, ctx, recordedReads);
        PaintStamps.touchLayout(box);
    }

    /** 検証モード（{@code VERIFY_MEMO}）: 記憶で飛ばした箱を測り直して比べる（{@link MeasureVerifier}）。計数は進めない。 */
    private void verifyReused(LayoutBox box, ConstraintSpace space, LayoutContext ctx, String how) {
        if (MeasureVerifier.active() || box.floatSpace != null || box.collapsedBorders != null) return;
        MeasureReads.ThreadState st = MeasureReads.state();
        long savedRun = st.fuelRun, savedCount = st.fuelCount, savedFirst = st.fuelFirstBoxes;
        long computedBefore = MEASUREMENTS_COMPUTED.sum();
        try {
            MeasureVerifier.verify(box, space, ctx, how);
        } finally {
            MEASUREMENTS_COMPUTED.add(-(MEASUREMENTS_COMPUTED.sum() - computedBefore));
            st.fuelRun = savedRun;
            st.fuelCount = savedCount;
            st.fuelFirstBoxes = savedFirst;
        }
    }

    Object snapshotSlots() { return tentativeSlots == null ? null : tentativeSlots.copy(); }
    void restoreSlots(Object snapshot) { tentativeSlots = snapshot == null ? null : ((TentativeSlots) snapshot).copy(); }

    // ------------------------------------------------------------------ 鍵照合・仮測定・確定葉スロット

    private boolean matchesCurrent(MeasureKey key, ConstraintSpace s, float ow, float oh, LayoutContext ctx,
                                   boolean autoSkipped, int mask, float fitNeed, boolean isText, boolean allowFillFit) {
        return key.matches(s, ow, oh, ctx.viewport(), MEASUREMENT_EPOCH.get(), LayoutBox.shapeEpoch(),
                ctx.fonts(), MeasureReads.fontGeneration(ctx), ctx.intrinsicResolver(), MeasureReads.intrinsicGeneration(ctx),
                autoSkipped, mask, fitNeed, isText, allowFillFit);
    }

    private void refreshMeasuredKey(ConstraintSpace space, float ow, float oh, Viewport vp, ExternalEpochs ep, boolean autoSkipped) {
        if (measuredKey == null || !measuredKey.matchesExact(space, ow, oh, vp, ep, autoSkipped)) {
            measuredKey = new MeasureKey(space, ow, oh, vp, ep, autoSkipped);
        }
    }

    boolean measureTentatively(LayoutBox box, ConstraintSpace space, LayoutContext ctx) {
        restoreTentativeStash(box);
        TentativeSlots ts = tentativeSlots;
        Tentative[] all = ts == null ? null : ts.entries;
        if (flag(F_SUBGRID_DEPENDENT) || !Shortcut.TENTATIVE_REUSE.on() || all == null
                || !ctx.reuseMeasurements() || box.parent == null || measuredKey == null) return false;
        if (!Float.isNaN(box.overrideWidth) || !Float.isNaN(box.overrideHeight)) return false;
        if (!flag(F_VALID) || flag(F_LAYOUT_CHANGED) || hasUnmeasuredSubtreeChange(ctx) || flag(F_HAS_TABLE)) return false;
        if (!(ctx.firstPass() || measuredRun != ctx.layoutRun() || Shortcut.SAME_PASS_REUSE.on())) return false;
        if (box.floatSpace != null || box.collapsedBorders != null) return false;
        int mEpoch = MEASUREMENT_EPOCH.get();
        if (measuredKey.epochs().measurementEpoch() != mEpoch) return false;

        boolean isText = box.node.nodeType == NodeType.TEXT;
        Tentative t = null;
        for (Tentative candidate : all) {
            if (candidate != null && candidate.kind() == Tentative.Kind.TENTATIVE
                    && matchesCurrent(candidate.key(), space, Float.NaN, Float.NaN, ctx,
                            box.contentAutoSkipped, candidate.readMask(), candidate.fitNeed(), isText, true)) {
                t = candidate;
                break;
            }
        }
        if (t == null) return false;
        if (box.needsAdoption) {
            for (int i = 0, n = box.children.size(); i < n; i++) box.adopt(box.children.get(i));
            box.needsAdoption = false;
        }
        ts.captureStash(box);
        ts.stashed = true;
        ts.stashedSpace = space;
        ts.stashedCtx = ctx;
        t.outputs().apply(box);
        if (t.isFillFit()) box.w = Math.max(0, space.availableInlineSize() - box.mL - box.mR);
        invalidateMinContentCache();
        box.parent.measureMemo.setFlag(F_HAS_TENTATIVE_CHILDREN, true);
        TENTATIVE_SKIPPED.increment();
        if (MeasureReads.currentMeasuringBox() == box.parent) {
            noteParentMeasured(space);
            MeasureReads.propagateChildReads(box);
            noteParentReadFitNeed();
        }
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
        for (int i = 0, n = kids.size(); i < n; i++) kids.get(i).measureMemo.settleTentative(kids.get(i));
    }

    private void storeTentativeSlot(Tentative.Kind kind, MeasureKey key, LayoutBox box, int slotMask, float fitNeed) {
        TentativeSlots ts = tentativeSlots;
        if (ts == null) tentativeSlots = ts = new TentativeSlots();
        int maxForKind = kind == Tentative.Kind.TENTATIVE ? MAX_TENTATIVES : MAX_DEFINITE_TENTATIVES;
        int idx = ts.obtainSlotIndex(kind, key.space(), maxForKind);
        Tentative old = ts.entries[idx];
        if (old != null && old.matchesExactRecord(kind, key, box, slotMask, fitNeed)) return;
        MeasureOutputs outputs = (old != null && old.outputs().matchesAll(box)) ? old.outputs() : MeasureOutputs.capture(box);
        ts.entries[idx] = new Tentative(kind, key, outputs, slotMask, fitNeed);
    }

    private boolean reuseDefiniteTentative(LayoutBox box, ConstraintSpace space, LayoutContext ctx, boolean restoreOk) {
        TentativeSlots ts = tentativeSlots;
        if (flag(F_SUBGRID_DEPENDENT) || !Shortcut.TENTATIVE_REUSE.on() || ts == null || ts.entries == null
                || measuredKey == null || (Float.isNaN(box.overrideWidth) && Float.isNaN(box.overrideHeight))) {
            return false;
        }
        if (!flag(F_VALID) || flag(F_LAYOUT_CHANGED) || hasUnmeasuredSubtreeChange(ctx) || !MeasureAnalysis.isDefiniteCacheableLeaf(box)) {
            return false;
        }
        if (!(ctx.firstPass() || measuredRun != ctx.layoutRun() || Shortcut.SAME_PASS_REUSE.on())) return false;
        int mEpoch = MEASUREMENT_EPOCH.get();
        if (measuredKey.epochs().measurementEpoch() != mEpoch) return false;
        boolean isText = box.node.nodeType == NodeType.TEXT;
        Tentative match = null;
        for (Tentative t : ts.entries) {
            if (t != null && t.kind() == Tentative.Kind.DEFINITE
                    && matchesCurrent(t.key(), space, box.overrideWidth, box.overrideHeight, ctx,
                            box.contentAutoSkipped, t.readMask(), t.fitNeed(), isText, false)) {
                match = t;
                break;
            }
        }
        if (match == null) return false;
        MeasureOutputs o = match.outputs();
        boolean outputsDiffer = !o.matchesNatural(box);
        if (!restoreOk && outputsDiffer) return false;
        if (restoreOk && outputsDiffer) {
            o.apply(box); invalidateMinContentCache(); PaintStamps.touchLayout(box);
        }
        measuredFitNeed = match.fitNeed();
        int nextMask = match.readMask() & ~(MeasureReads.READ_FIT_BOUNDED | MeasureReads.READ_FILL_FIT);
        if (!Float.isNaN(box.overrideWidth) && measuredFitNeed != Float.POSITIVE_INFINITY) nextMask |= MeasureReads.READ_FIT_BOUNDED;
        readMask = nextMask;
        refreshMeasuredKey(space, box.overrideWidth, box.overrideHeight, ctx.viewport(),
                MeasureReads.currentEpochs(ctx, mEpoch, LayoutBox.shapeEpoch()), box.contentAutoSkipped);
        measuredRun = ctx.layoutRun();
        capturePrimaryOutputs(box);
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
            if (!cm.flag(F_SUBTREE_CHANGED) || cm.measuredKey == null) continue;
            if (cm.flag(F_LAYOUT_CHANGED) || MeasureChanges.isRelayoutBoundary(child) || cm.canAttemptCutoff(child)) {
                cm.measureWith(child, cm.measuredKey.space(), ctx, false);
            } else {
                cm.remeasureChanged(child, ctx);
                if (!cm.flag(F_VALID)) {
                    if (cm.canAttemptCutoff(child) || MeasureChanges.isRelayoutBoundary(child)) cm.measureWith(child, cm.measuredKey.space(), ctx, false);
                    else setFlag(F_VALID, false);
                }
            }
        }
    }

    private boolean hasUnmeasuredSubtreeChange(LayoutContext ctx) { return flag(F_SUBTREE_CHANGED) && measuredRun != ctx.layoutRun(); }

    private boolean fillFitReuse(LayoutBox box, ConstraintSpace space, LayoutContext ctx, boolean restoreOk) {
        if (flag(F_SUBGRID_DEPENDENT) || !restoreOk || measuredKey == null
                || (readMask & MeasureReads.READ_FILL_FIT) == 0
                || !Float.isNaN(box.overrideWidth) || !Float.isNaN(box.overrideHeight)) return false;
        if (!flag(F_VALID) || flag(F_LAYOUT_CHANGED) || hasUnmeasuredSubtreeChange(ctx)) return false;
        if (!(ctx.firstPass() || measuredRun != ctx.layoutRun() || (Shortcut.SAME_PASS_REUSE.on() && !flag(F_HAS_TABLE)))) return false;
        if (box.floatSpace != null || box.collapsedBorders != null) return false;
        if (!matchesCurrent(measuredKey, space, box.overrideWidth, box.overrideHeight, ctx,
                box.contentAutoSkipped, readMask, measuredFitNeed, false, true)) return false;
        restoreNaturalOutputs(box);
        float newW = Math.max(0, space.availableInlineSize() - box.mL - box.mR);
        if (newW != box.w) {
            box.w = newW;
            invalidateMinContentCache();
            PaintStamps.touchLayout(box);
            box.invalidatePlacementValid();
        }
        refreshMeasuredKey(space, box.overrideWidth, box.overrideHeight, ctx.viewport(),
                MeasureReads.currentEpochs(ctx, MEASUREMENT_EPOCH.get(), LayoutBox.shapeEpoch()), box.contentAutoSkipped);
        measuredRun = ctx.layoutRun();
        capturePrimaryOutputs(box);
        return true;
    }

    boolean canPreserveForEmptyFloatFlow(LayoutBox box, ConstraintSpace space, LayoutContext ctx) {
        if (flag(F_SUBGRID_DEPENDENT) || !flag(F_VALID) || flag(F_LAYOUT_CHANGED) || hasUnmeasuredSubtreeChange(ctx)
                || measuredKey == null || flag(F_HAS_TABLE) || box.contentAutoSkipped
                || !Float.isNaN(box.overrideWidth) || !Float.isNaN(box.overrideHeight)) return false;
        return matchesCurrent(measuredKey, space, Float.NaN, Float.NaN, ctx, false,
                readMask & ~MeasureReads.READ_FILL_FIT, measuredFitNeed, box.node.nodeType == NodeType.TEXT, false);
    }

    boolean outputsMatchRecorded(LayoutBox box) {
        return flag(F_VALID) && box.w == outW && box.h == outH
                && box.mT == outMT && box.mR == outMR && box.mB == outMB && box.mL == outML
                && (!flag(F_HAS_OUTPUTS) || box.contentBlockExtent == outContentBlockExtent);
    }

    private boolean canReuseMeasurement(LayoutBox box, ConstraintSpace space, LayoutContext ctx, boolean restoreOk) {
        return !flag(F_SUBGRID_DEPENDENT) && flag(F_VALID) && !flag(F_LAYOUT_CHANGED) && measuredKey != null
                && (ctx.firstPass() || measuredRun != ctx.layoutRun() || (Shortcut.SAME_PASS_REUSE.on() && !flag(F_HAS_TABLE)))
                && box.floatSpace == null && box.collapsedBorders == null
                && matchesCurrent(measuredKey, space, box.overrideWidth, box.overrideHeight, ctx,
                        box.contentAutoSkipped, readMask, measuredFitNeed, box.node.nodeType == NodeType.TEXT, false)
                && (restoreOk || (box.w == outW && box.h == outH
                        && box.mT == outMT && box.mR == outMR && box.mB == outMB && box.mL == outML));
    }

    private void restoreNaturalOutputs(LayoutBox box) {
        if (!flag(F_HAS_OUTPUTS)) return;
        if (box.w == outW && box.h == outH && box.mT == outMT && box.mR == outMR && box.mB == outMB && box.mL == outML
                && box.contentBlockExtent == outContentBlockExtent) return;
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
        outLegendSpill = b.legendSpill; outContentBlockExtent = b.contentBlockExtent;
        outOwnMarginTop = b.ownMarginTop; outOwnMarginBottom = b.ownMarginBottom;
        outSizingMode = b.sizingMode;
        setFlag(F_OUT_BFC, b.establishesBfc);
        setFlag(F_HAS_OUTPUTS, true);
    }

    private void recordMeasurement(LayoutBox box, ConstraintSpace space, LayoutContext ctx, int recordedReads) {
        boolean selfChanged = selfSignatureChanged(box);
        boolean outerSizeStable = measuredStyle != null && box.w == outW && box.h == outH
                && box.mT == outMT && box.mR == outMR && box.mB == outMB && box.mL == outML;
        boolean cutoffMatched = !MeasureVerifier.active()
                && MeasureReads.state().descentDepth > 0
                && parentReadStillMatches(box, ctx, selfChanged);
        boolean boundaryAbsorbed = !MeasureVerifier.active()
                && outerSizeStable && MeasureChanges.isRelayoutBoundary(box)
                && extraParentReadsMatch(box, ctx);

        ExternalEpochs epochs = MeasureReads.currentEpochs(ctx, MEASUREMENT_EPOCH.get(), LayoutBox.shapeEpoch());
        refreshMeasuredKey(space, box.overrideWidth, box.overrideHeight, ctx.viewport(), epochs, box.contentAutoSkipped);
        measuredRun = ctx.layoutRun();
        capturePrimaryOutputs(box);
        measuredStyle = box.style;
        measuredPlaceholderStyle = box.placeholderStyle;
        measuredText = box.displayText;
        measuredContentHash = contentHash(box);
        setFlag(F_OUTSIDE_MARKER, box.isOutsideMarker);
        setFlag(F_RENDERED_LEGEND, box.isRenderedLegend);
        setFlag(F_CONTENT_AUTO_SKIPPED, box.contentAutoSkipped);
        if (LayoutBox.isTableBox(box) || box.collapsedBorders != null) setFlag(F_EVER_TABLE, true);
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
        boolean hasTable = flag(F_EVER_TABLE), minContentVar = false;
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

        int mask = recordedReads;
        if (hasTable) mask |= MeasureReads.READ_AVAIL_INLINE | MeasureReads.READ_PERCENT_BLOCK;
        if (!floatFree || box.floatSpace != null) mask |= MeasureReads.READ_SHAPE_EPOCH;
        float fitNeed = MeasureAnalysis.computeFitNeed(box, (mask & MeasureReads.READ_VIEWPORT_W) == 0,
                hasTable, floatFree, flag(F_CHILD_FIT_BLOCKED), childFitNeedMax);
        measuredFitNeed = fitNeed;
        boolean fitFixedW = MeasureAnalysis.isFitFixedW(box, fitNeed);
        boolean fillFitReusable = MeasureAnalysis.isFillFitReusable(box, fitNeed);
        if (fitFixedW) mask |= MeasureReads.READ_FIT_BOUNDED;
        if (fillFitReusable) mask |= MeasureReads.READ_FILL_FIT;
        readMask = mask;

        if (Shortcut.TENTATIVE_REUSE.on() && MeasureAnalysis.parentUsesTentatives(box)
                && box.floatSpace == null && box.collapsedBorders == null) {
            boolean tentativePhase = Float.isNaN(box.overrideWidth) && Float.isNaN(box.overrideHeight);
            if (tentativePhase || MeasureAnalysis.isDefiniteCacheableLeaf(box)) {
                Tentative.Kind kind = tentativePhase ? Tentative.Kind.TENTATIVE : Tentative.Kind.DEFINITE;
                boolean fillFit = tentativePhase && fillFitReusable;
                float tentFitNeed = tentativePhase
                        ? ((fitFixedW || fillFit) ? measuredFitNeed : Float.POSITIVE_INFINITY)
                        : (!Float.isNaN(box.overrideWidth) && !box.axisStyle().layoutFacts().edgesAvailableFree
                                ? Float.POSITIVE_INFINITY : measuredFitNeed);
                int slotMask = mask & ~(MeasureReads.READ_FIT_BOUNDED | MeasureReads.READ_FILL_FIT);
                if (tentFitNeed != Float.POSITIVE_INFINITY) {
                    if (fillFit) slotMask |= MeasureReads.READ_FILL_FIT;
                    if (fitFixedW || !tentativePhase) slotMask |= MeasureReads.READ_FIT_BOUNDED;
                }
                storeTentativeSlot(kind, measuredKey, box, slotMask, tentFitNeed);
            }
        }
        setFlag(F_VALID, true);
        boolean absorbed = cutoffMatched || boundaryAbsorbed;
        if (absorbed && (parentReadMask & (MeasureReads.PARENT_READ_FIRST_BASELINE
                | MeasureReads.PARENT_READ_LAST_BASELINE | MeasureReads.PARENT_READ_MIN_CONTENT)) != 0) {
            refreshTransitiveParentReads(box, ctx);
        }
        if (absorbed) propagateFitNeedOnCutoff(box);
        if (MeasureVerifier.active()) return;
        if (MeasureReads.state().descentDepth > 0) invalidateParentOnly(box, !absorbed);
        else invalidateAncestors(box, !boundaryAbsorbed);
    }

    // ------------------------------------------------------------------ 無効化・署名照合

    static void invalidate(LayoutBox box) {
        PaintStamps.touchLayout(box);
        for (LayoutBox b = box; b != null; b = b.parent) { b.measureMemo.setFlag(F_VALID, false); b.invalidatePlacementValid(); }
    }

    private static void invalidateParentOnly(LayoutBox box, boolean measureAffected) {
        for (LayoutBox p = box.parent; p != null; p = p.parent) {
            p.invalidatePlacementValid();
            if (p.measureMemo.tentativeSlots != null && !MeasureReads.isOnStack(p)) p.measureMemo.tentativeSlots.entries = null;
        }
        if (measureAffected && box.parent != null) box.parent.measureMemo.setFlag(F_VALID, false);
    }

    static void invalidateAncestors(LayoutBox box, boolean measureAffected) {
        boolean measureStillValid = measureAffected;
        for (LayoutBox p = box.parent; p != null; p = p.parent) {
            p.invalidatePlacementValid();
            if (measureStillValid) { p.measureMemo.setFlag(F_VALID, false); if (MeasureChanges.isRelayoutBoundary(p)) measureStillValid = false; }
        }
    }

    void markVisibilityChangedForStabilize(LayoutBox box) {
        setFlag(F_VALID, false); flags |= F_LAYOUT_CHANGED | F_SUBTREE_CHANGED; invalidateMinContentCache();
        for (LayoutBox p = box.parent; p != null; p = p.parent) p.measureMemo.markSubtreeChanged();
        invalidateAncestors(box, true);
    }

    private int contentHash(LayoutBox box) {
        HtmlNode node = box.node;
        if (node == null) return 0;
        int version = node.attrVersion();
        if (attrHashVersion != version || attrHashNode != node) {
            attrHash = MeasureAnalysis.computeAttrHash(node);
            attrHashVersion = version;
            attrHashNode = node;
        }
        int hash = attrHash;
        return com.nishiyu.enderframelib.core.dom.node.ElementTraits.contentTextAffectsMeasure(node.tag)
                ? 31 * hash + node.getTextContent().hashCode()
                : hash;
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
