package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.CssPropertyIndex;
import com.nishiyu.enderframelib.core.kernel.CompactList;
import com.nishiyu.enderframelib.core.kernel.layout.FenwickVirtualList;
import com.nishiyu.enderframelib.core.text.ParagraphSegments;
import com.nishiyu.enderframelib.core.layout.model.*;

import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.layout.LayoutContext;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssKeywords;
import com.nishiyu.enderframelib.core.css.StyleResolver;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.layout.LayoutBox.Display.Inner;
import com.nishiyu.enderframelib.core.layout.LayoutBox.InlineFragment;
import com.nishiyu.enderframelib.core.layout.LayoutBox.SizingMode;
import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.model.FormattingContext;
import com.nishiyu.enderframelib.core.text.model.FontStyle;

import java.util.ArrayList;
import java.util.List;

import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;
import com.nishiyu.enderframelib.core.css.model.TextAlign;

/**
 * ブロックとインラインの整形（float と BFC を含む）。
 *
 * 位置は {@link #contentBlockSize} が内容ボックスの原点からの相対座標で決め（{@link FlowState}）、
 * {@link #arrangeChildren} はそれを平行移動するだけにしている。float の配置と、float を避ける行・BFC は
 * 高さを決める段で分かっていないといけないため。
 *
 * 何が壊れていたか: 以前は測定と配置がそれぞれ子を積み直していて、配置の段では「直前の子の
 * 下端」から次の子を置いていた。{@code position: relative} のずれ（配置の後で足される）が
 * 後続の兄弟まで動かしていたのはこのため。
 *
 * LIMITATION(制約): float の横を流れる行は、行の上端で float を避ける幅を決める（行の高さが
 * 後から高くなっても幅は測り直さない）。インラインボックスの中の float は行の途中では前の行へ
 * 戻らない。README の "Not supported" に対応。
 */
public class BlockLayoutEngine extends LayoutEngine {

    @Override
    public float measureContent(LayoutBox box, ConstraintSpace space, LayoutContext ctx) {
        float tentativeInnerW = space.availableInlineSize();
        // 段組は、中身を列の幅で測る（全幅の column-span: all だけ段組全体の幅）
        MulticolLayout.Geometry columns = MulticolLayout.isMulticol(box)
                ? MulticolLayout.geometry(box, tentativeInnerW, ctx.viewport().rootW(), ctx.viewport().rootH()) : null;
        float maxChildW = 0;
        float fitInnerW = 0;
        // 行の内容の幅は強制改行（<br> と保持された改行）で区切って、最も広い行を取る。
        // 何が壊れていたか: 以前は区切らずに足し続けていて、<br> を含む inline-block・float・flex アイテムが
        // 全行を横に繋げた幅まで広がっていた
        float lineCur = 0, lineMax = 0, linePend = 0;
        // fill 幅ブロックの fit 境界判定では、前後の空白を削らない実送り幅（FlowBuilder が折り返し判定に使う幅）で数える
        float fitCur = 0, fitMax = 0;

        float childParentH = childPercentageBase(box, space.percentageBlockSize(), ctx);
        FormattingContext childCtx = FormattingContext.of(FormattingContext.Display.BLOCK, true, box.sizingMode == SizingMode.SHRINK_TO_FIT);
        ConstraintSpace sharedChildSpace = null;
        if (columns == null) {
            sharedChildSpace = ConstraintSpace.reuseOrCreate(box.childSpaceCache, tentativeInnerW, childParentH, childCtx);
            box.childSpaceCache = sharedChildSpace;
        }
        List<LayoutBox> kids = box.children;
        int n = kids.size();
        boolean[] premeasured = null;
        if (columns == null && sharedChildSpace != null && n >= LayoutTaskPool.PARALLEL_THRESHOLD
                && com.nishiyu.enderframelib.core.kernel.Shortcut.LAYOUT_PARALLEL.on() && !LayoutTaskPool.inWorker()) {
            int work = 0;
            for (int i = 0; i < n; i++) {
                LayoutBox c = kids.get(i);
                if (!c.isAbsolute && !c.isRenderedLegend && !c.isOutsideMarker
                        && !isDisplayNone(c.style) && !MulticolLayout.isSpanner(c)
                        && c.needsParallelMeasureWork()) {
                    if (++work >= LayoutTaskPool.PARALLEL_THRESHOLD) break;
                }
            }
            if (work >= LayoutTaskPool.PARALLEL_THRESHOLD) {
                premeasured = new boolean[n];
                int[] eligible = new int[n];
                int ec = 0;
                for (int i = 0; i < n; i++) {
                    LayoutBox c = kids.get(i);
                    if (!c.isAbsolute && !c.isRenderedLegend && !c.isOutsideMarker
                            && !isDisplayNone(c.style) && !MulticolLayout.isSpanner(c)) {
                        premeasured[i] = true;
                        eligible[ec++] = i;
                    }
                }
                final ConstraintSpace workerSpace = sharedChildSpace;
                int grain = LayoutTaskPool.grainFor(ec, 8);
                LayoutTaskPool.forRange(ec, grain, null, idx -> kids.get(eligible[idx]).measureParallel(workerSpace, ctx));
            }
        }
        boolean sharedFixedWidth = sharedChildSpace != null && !Float.isNaN(sharedChildSpace.fixedInlineSize());

        for (int i = 0; i < n; i++) {
            LayoutBox child = kids.get(i);
            if (child.isAbsolute || child.isRenderedLegend || isDisplayNone(child.style)) continue;
            if (child.isOutsideMarker) {
                // 外側のマーカーは通常フローの外にあり、リスト項目の幅に寄与しない
                child.measure(new ConstraintSpace(LayoutMetrics.UNBOUNDED_WIDTH, childParentH, MARKER_CONTEXT), ctx);
                continue;
            }
            ConstraintSpace childSpace = sharedChildSpace != null && !MulticolLayout.isSpanner(child)
                    ? sharedChildSpace
                    : new ConstraintSpace(columns != null && !MulticolLayout.isSpanner(child) ? columns.width() : tentativeInnerW, childParentH, childCtx);
            if (premeasured != null && premeasured[i]) {
                child.applyFitToParent(sharedFixedWidth);
            } else {
                child.measure(childSpace, ctx);
            }
            // float は行の内容と同じく横に並ぶので、shrink-to-fit の幅には横に足して数える
            if (isInlineBox(child) || isFloating(child)) {
                float[] wScratch = INLINE_WIDTH_SCRATCH.get();
                wScratch[0] = lineCur; wScratch[1] = lineMax; wScratch[2] = linePend;
                addInlineWidth(child, wScratch, ctx, false);
                lineCur = wScratch[0]; lineMax = wScratch[1]; linePend = wScratch[2];
                wScratch[0] = fitCur; wScratch[1] = fitMax; wScratch[2] = 0;
                addInlineWidth(child, wScratch, ctx, true);
                fitCur = wScratch[0]; fitMax = wScratch[1];
                if (tentativeInnerW > 0 && lineMax > tentativeInnerW) box.measuredClamped = true;
                maxChildW = Math.max(maxChildW, tentativeInnerW > 0 ? Math.min(tentativeInnerW, lineMax) : lineMax);
                fitInnerW = Math.max(fitInnerW, fitMax);
            } else {
                lineCur = 0; lineMax = 0; linePend = 0;
                fitCur = 0; fitMax = 0;
                maxChildW = Math.max(maxChildW, child.w + child.mL + child.mR);
                float childNeed = child.blockChildFitWidth();
                if (childNeed != Float.POSITIVE_INFINITY) fitInnerW = Math.max(fitInnerW, childNeed);
            }
        }
        if (columns != null) {
            box.naturalFitInnerWidth = Float.NaN;
            // 内容の幅は、列を並べた幅（確定した幅の箱では使われない）
            float natural = columns.count() * Math.max(maxChildW, 0) + (columns.count() - 1) * columns.gap();
            return tentativeInnerW > 0 && tentativeInnerW < LayoutMetrics.UNBOUNDED_WIDTH
                    ? Math.min(tentativeInnerW, natural) : natural;
        }
        box.naturalFitInnerWidth = fitInnerW;
        return maxChildW;
    }

    /** shrink-to-fit の測定で数える行幅の一時作業領域（{current, max}）。child.measure 再入の前後でローカル変数に退避して使う。 */
    private static final ThreadLocal<float[]> INLINE_WIDTH_SCRATCH = ThreadLocal.withInitial(() -> new float[3]);

    static final class BlockScratch {
        final FlowBuilder flow = new FlowBuilder(this);
        final LineBuilder line = new LineBuilder(this);
        final List<FlatRun> runs = new ArrayList<>(8);
        final List<LayoutBox> parents = new ArrayList<>(4);
        final List<LayoutBox> run = new ArrayList<>(8);
        @SuppressWarnings("unchecked")
        final List<LayoutBox>[] snapshot = new List[1];

        void clearRefs() {
            flow.clearRefs();
            line.clearRefs();
            runs.clear();
            parents.clear();
            run.clear();
            snapshot[0] = null;
        }
    }

    private static final class ScratchStack {
        private BlockScratch[] slots = new BlockScratch[4];
        private int depth;

        BlockScratch acquire() {
            int d = depth++;
            if (d >= slots.length) {
                slots = java.util.Arrays.copyOf(slots, slots.length << 1);
            }
            BlockScratch s = slots[d];
            if (s == null) {
                s = new BlockScratch();
                slots[d] = s;
            }
            return s;
        }

        void release(BlockScratch s) {
            s.clearRefs();
            depth--;
        }
    }

    private static final ThreadLocal<ScratchStack> SCRATCH_STACK = ThreadLocal.withInitial(ScratchStack::new);

    private static FlowState buildFlowState(LayoutBox box, ConstraintSpace space, LayoutContext ctx,
                                            List<LayoutBox> children, float innerWOverride, FlowState state) {
        ScratchStack stack = SCRATCH_STACK.get();
        BlockScratch s = stack.acquire();
        try {
            return s.flow.init(box, space, ctx, children, innerWOverride, state).build();
        } finally {
            stack.release(s);
        }
    }

    private static void addLineWidth(float[] line, float w) {
        float c = line[0] + w;
        line[0] = c;
        if (c > line[1]) line[1] = c;
    }

    /**
     * 測定済みのインラインレベルの箱を行の幅に足す。インラインボックスは中身を辿り、
     * 開始端と終了端の装飾（margin + border + padding）を 1 回ずつ数える。
     */
    private static void addInlineWidth(LayoutBox b, float[] line, LayoutContext ctx, boolean includeFullSpaces) {
        if (b.isAbsolute || b.display.isNone() || b.isOutsideMarker) return;
        if (b.node.nodeType == NodeType.TEXT) {
            TextStyle textStyle = ctx.textStyleFor(b.style);
            FontFace face = ctx.faceFor(b.style);
            if (b.displayText == null || !textStyle.whiteSpace().preservesNewlines() || b.displayText.indexOf('\n') < 0) {
                float w;
                if (includeFullSpaces && b.displayText != null && !b.displayText.isEmpty()) {
                    w = unwrappedTextAdvance(b, b.displayText, face, textStyle);
                } else {
                    // 折り返した文字は、折り返さずに並べた幅（max-content）で数える。shrink-to-fit の幅は
                    // min(max-content, 利用可能幅) なので、最も長い行の幅では利用可能幅より狭くなる
                    boolean wrapped = b.shapedLines != null && b.shapedLines.size() > 1 && b.displayText != null;
                    w = wrapped ? b.cachedStrippedAdvance(b.displayText, face, textStyle.font()) : b.w;
                    // 前後の空白は行の先頭と末尾でだけ消える。run の境目（"aa " + "<span>green</span>"）では幅に入る
                    String t = b.displayText;
                    if (t != null && !t.isEmpty() && textStyle.whiteSpace().collapses()) {
                        float space = face.advance(" ", textStyle.font());
                        if (t.charAt(0) == ' ' && line[0] > 0 && line[2] == 0) line[2] = space;
                        if (w > 0) addContent(line, w + b.mL + b.mR);
                        if (t.charAt(t.length() - 1) == ' ' && (w > 0 || line[0] > 0)) line[2] = space;
                        return;
                    }
                }
                addContent(line, w + b.mL + b.mR);
                return;
            }
            String[] paragraphs = b.displayText.split("\n", -1);
            for (int i = 0; i < paragraphs.length; i++) {
                if (i > 0) line[0] = 0;
                float w = includeFullSpaces
                        ? unwrappedTextAdvance(null, paragraphs[i], face, textStyle)
                        : face.advance(paragraphs[i], textStyle.font());
                addLineWidth(line, w);
            }
        } else if (b.node.tag.equals("br")) {
            line[0] = 0;
            line[2] = 0;
        } else if (isFloating(b) || isAtomicInline(b) || !isInlineBox(b)) {
            addContent(line, b.w + b.mL + b.mR);
        } else {
            float open = b.mL + b.bL + b.pL, close = b.pR + b.bR + b.mR;
            if (open > 0) addContent(line, open);
            for (int i = 0, n = b.children.size(); i < n; i++) addInlineWidth(b.children.get(i), line, ctx, includeFullSpaces);
            if (close > 0) addContent(line, close);
        }
    }

    /** 内容の幅を足す。保留していた空白（前の run の末尾）は、後に内容が続いたので行の幅に入る。 */
    private static void addContent(float[] line, float w) {
        if (line.length > 2) {
            w += line[2];
            line[2] = 0;
        }
        addLineWidth(line, w);
    }

    /** {@link FlowBuilder} が 1 行に置いたときに進む幅（前後の空白・正のカーニングを含む）。 */
    private static float unwrappedTextAdvance(LayoutBox box, String paragraph, FontFace face, TextStyle textStyle) {
        if (paragraph.isEmpty()) return 0;
        ParagraphSegments seg = box != null
                ? box.cachedSegments(paragraph, face, textStyle.font(), textStyle.wordBreak())
                : TextShaper.segmented(paragraph, face, textStyle.font(), textStyle.wordBreak());
        return seg.unwrappedAdvance();
    }

    /** 子の高さの % を解く基準。高さが不定なら 0。 */
    static float childPercentageBase(LayoutBox box, float parentH, LayoutContext ctx) {
        float h = definiteInnerHeight(box, parentH, ctx);
        return Float.isNaN(h) ? 0 : h;
    }

    /**
     * 内容ボックスの高さが確定していればその値、不定なら NaN（子の高さの % の基準。{@code parentH} は自分の % の基準）。
     * 確定しているのは、高さの指定があるとき、親のエンジンが高さを決めたとき（flex の伸縮・stretch、
     * grid の stretch。CSS Flexbox §9.8 の 11 と CSS Grid §6.2）、top と bottom で高さが決まる絶対配置。
     *
     * 何が壊れていたか: 以前は高さの指定しか見ておらず、stretch された flex / grid アイテムや
     * {@code top: 0; bottom: 0} の絶対配置の中で、子の {@code height: 100%} が 0 になっていた。
     */
    static float definiteInnerHeight(LayoutBox box, float parentH, LayoutContext ctx) {
        if (box.parent == null) return Math.max(0, parentH - box.verticalDecoration());
        if (!Float.isNaN(box.overrideHeight)) return Math.max(0, box.overrideHeight - box.verticalDecoration());
        float rootW = ctx.viewport().rootW(), rootH = ctx.viewport().rootH();
        ComputedStyle ax = box.axisStyle();
        float anchorH = box.isAbsolute ? box.anchorHeightPx() : -1;
        if (anchorH >= 0) {
            boolean isBorderBox = ax.isBorderBox();
            return Math.max(0, isBorderBox ? anchorH - box.verticalDecoration() : anchorH);
        }
        if (hasSpecifiedHeight(ax)) {
            float resolvedH = ax.length(CssPropertyIndex.HEIGHT, parentH, rootW, rootH);
            boolean isBorderBox = ax.isBorderBox();
            return Math.max(0, isBorderBox ? resolvedH - box.verticalDecoration() : resolvedH);
        }
        if (box.isAbsolute && BoxMeasurer.hasBothInsets(ax, CssPropertyIndex.TOP, CssPropertyIndex.BOTTOM)) {
            float insets = ax.length(CssPropertyIndex.TOP, parentH, rootW, rootH)
                    + ax.length(CssPropertyIndex.BOTTOM, parentH, rootW, rootH);
            return Math.max(0, parentH - insets - box.mT - box.mB - box.verticalDecoration());
        }
        return Float.NaN;
    }

    private static boolean isRtl(ComputedStyle style) {
        String direction = style.get(ID_DIRECTION);
        return direction != null && "rtl".equalsIgnoreCase(direction.trim());
    }

    private static boolean hasSpecifiedHeight(ComputedStyle style) {
        String height = style.get(ID_HEIGHT);
        return height != null && !"auto".equals(height.trim());
    }

    /** これ以上のブロック子を持ち、{@code content-visibility: auto} 要素を含むコンテナを {@link FenwickVirtualList} で仮想化する。 */
    public static final int VIRTUAL_FLOW_THRESHOLD = 32;

    private static final java.util.concurrent.atomic.LongAdder VIRTUAL_FLOWS_BUILT =
            new java.util.concurrent.atomic.LongAdder();

    /** 仮想フロー（{@link VirtualFlowState}）を構築した累積回数（テスト・診断用）。 */
    public static long virtualFlowsBuilt() {
        return VIRTUAL_FLOWS_BUILT.sum();
    }

    @Override
    public float contentBlockSize(LayoutBox box, ConstraintSpace space, LayoutContext ctx) {
        if (MulticolLayout.isMulticol(box)) return MulticolLayout.contentBlockSize(this, box, space, ctx);
        if (box.engineState instanceof MulticolLayout.State) box.engineState = null;
        if (box.sizingMode == SizingMode.SHRINK_TO_FIT) stretchBlockChildren(box, space, ctx);
        if (canVirtualize(box)) {
            VirtualFlowState reusedVf = box.engineState instanceof VirtualFlowState v ? v : new VirtualFlowState();
            VirtualFlowState vf = buildVirtualFlow(box, space, ctx, Float.NaN, reusedVf);
            box.engineState = vf;
            return vf.height;
        }
        FlowState reused = box.engineState instanceof FlowState fs ? fs : new FlowState();
        FlowState state = buildFlowState(box, space, ctx, box.children, Float.NaN, reused);
        box.engineState = state;
        if (state.wrapped) box.measuredClamped = true;
        return state.height;
    }

    @Override
    public void finishLayout(LayoutBox box, ConstraintSpace space, LayoutContext ctx) {
        measureAbsoluteChildren(box, ctx);
    }

    /**
     * shrink-to-fit の箱（inline-block・float・絶対配置など）の幅が決まったら、幅が auto のブロックレベルの子を
     * その幅いっぱいに測り直す（CSS 2.2 §10.3.3: ブロックの幅は包含ブロックの幅を満たす）。
     *
     * 何が壊れていたか: 内容の幅を測る段階では子も縮めて測るので、いちばん広い子より狭い子は
     * 自分の内容の幅のままだった（inline-block の中の短い段落の背景や枠線が、箱の幅まで広がらなかった）。
     */
    private void stretchBlockChildren(LayoutBox box, ConstraintSpace space, LayoutContext ctx) {
        float innerW = Math.max(0, box.w - box.horizontalDecoration());
        float base = childPercentageBase(box, space.percentageBlockSize(), ctx);
        for (LayoutBox child : box.children) {
            if (child.isAbsolute || child.isOutsideMarker || child.isRenderedLegend || child.display.isNone()
                    || isInlineBox(child) || isFloating(child) || child.node.nodeType == NodeType.TEXT) continue;
            if (!"auto".equals(child.axisStyle().getOrDefault(CssPropertyIndex.WIDTH, "auto").trim())) continue;
            if (Math.abs(child.w + child.mL + child.mR - innerW) <= 0.01f) continue;
            child.measure(new ConstraintSpace(innerW, base, FormattingContext.BLOCK), ctx);
        }
    }

    @Override
    public void arrangeChildren(LayoutBox box, float innerX, float innerY, LayoutContext ctx) {
        float innerW = Math.max(0, box.w - box.horizontalDecoration());
        if (box.engineState instanceof MulticolLayout.State columns) {
            box.inlineContentBottom = MulticolLayout.arrange(this, box, columns, innerX, innerY, ctx);
            placeOutsideMarkers(box, innerY, ctx);
            return;
        }
        if (box.engineState instanceof VirtualFlowState vf) {
            if (Math.abs(vf.innerW - innerW) > 0.01f) {
                vf = buildVirtualFlow(box, ConstraintSpace.block(innerW, 0), ctx, innerW, vf);
                box.engineState = vf;
            }
            box.inlineContentBottom = arrangeVirtualFlow(box, vf, innerX, innerY, innerW, ctx);
            placeOutsideMarkers(box, innerY, ctx);
            return;
        }
        FlowState state = box.engineState instanceof FlowState s ? s : null;
        if (state == null || Math.abs(state.innerW - innerW) > 0.01f) {
            // 高さを決めた後に幅だけが変わった（親が stretch した）ときは組み直す
            state = buildFlowState(box, ConstraintSpace.block(innerW, 0), ctx,
                    box.children, Float.NaN, state != null ? state : new FlowState());
            box.engineState = state;
        }
        box.inlineContentBottom = arrangeFlow(box, state, innerX, innerY, innerW, box.columnShifts, ctx);
        placeOutsideMarkers(box, innerY, ctx);
    }

    /** 段組の 1 区間の流れを、列幅 {@code columnW} で組む（{@link MulticolLayout} が呼ぶ）。 */
    FlowState buildFlow(LayoutBox box, List<LayoutBox> children, float columnW, ConstraintSpace space, LayoutContext ctx) {
        return buildFlowState(box, space, ctx, children, columnW, new FlowState());
    }

    private static final float[] NO_SHIFT = {0, 0};

    private static float[] shiftOf(java.util.Map<Object, float[]> shifts, Object key) {
        if (shifts == null) return NO_SHIFT;
        float[] s = shifts.get(key);
        return s == null ? NO_SHIFT : s;
    }

    /**
     * 組んだ流れの子と行を置く。{@code shifts} は段組が列に割り当てた単位ごとの移動量（無ければ全て 0）。
     * 戻り値は文字の最下端（スクロール範囲が読む）。
     */
    float arrangeFlow(LayoutBox box, FlowState state, float innerX, float innerY, float innerW,
                      java.util.Map<Object, float[]> shifts, LayoutContext ctx) {
        TextAlign specifiedAlign = box.style.keywordOf(CssPropertyIndex.TEXT_ALIGN, TextAlign.class);
        boolean alignRtl = isRtl(box.style);
        // match-parent は親の値を、start / end を親の direction で解いたもの（CSS Text 3 §7.1）
        for (LayoutBox p = box.parent; specifiedAlign == TextAlign.MATCH_PARENT && p != null; p = p.parent) {
            specifiedAlign = p.style.keywordOf(CssPropertyIndex.TEXT_ALIGN, TextAlign.class);
            alignRtl = isRtl(p.style);
        }
        TextAlign textAlign = specifiedAlign.physical(alignRtl);
        LineJustify lineJustify = LineJustify.of(box.style, specifiedAlign, textAlign, isRtl(box.style));
        float textBottom = Float.NEGATIVE_INFINITY;
        boolean hasShifts = shifts != null && !shifts.isEmpty();
        box.firstLineBgRect = null;
        LayoutBox flOwner = firstLineOwner(box);
        boolean recordedFirstLine = false;
        int itemLen = state.items.size();
        boolean[] parallelPlaced = null;
        if (!hasShifts && box.floatSpace == null && !containsFloats(box)
                && itemLen >= LayoutTaskPool.PARALLEL_THRESHOLD
                && com.nishiyu.enderframelib.core.kernel.Shortcut.LAYOUT_PARALLEL.on() && !LayoutTaskPool.inWorker()) {
            int work = 0;
            for (int itemIdx = 0; itemIdx < itemLen; itemIdx++) {
                if (state.items.get(itemIdx) instanceof Placement p) {
                    LayoutBox pb = p.box();
                    if (!pb.isAbsolute && !isFloating(pb)
                            && !pb.subtreeHasContentAuto && !pb.style.facts().contentVisibilityAuto
                            && pb.needsParallelLayoutWork(innerX + p.x(), innerY + p.y(), ctx)) {
                        if (++work >= LayoutTaskPool.PARALLEL_THRESHOLD) break;
                    }
                }
            }
            if (work >= LayoutTaskPool.PARALLEL_THRESHOLD) {
                parallelPlaced = new boolean[itemLen];
                int[] eligible = new int[itemLen];
                int ec = 0;
                for (int itemIdx = 0; itemIdx < itemLen; itemIdx++) {
                    if (state.items.get(itemIdx) instanceof Placement p) {
                        LayoutBox pb = p.box();
                        if (!pb.isAbsolute && !isFloating(pb)
                                && !pb.subtreeHasContentAuto && !pb.style.facts().contentVisibilityAuto) {
                            parallelPlaced[itemIdx] = true;
                            eligible[ec++] = itemIdx;
                        }
                    }
                }
                final LayoutContext workerCtx = ctx.withoutAutoVisibility();
                int grain = LayoutTaskPool.grainFor(ec, 8);
                LayoutTaskPool.forRange(ec, grain, null, idx -> {
                    Placement p = (Placement) state.items.get(eligible[idx]);
                    p.box().layout(innerX + p.x(), innerY + p.y(), innerW, p.box().h, workerCtx);
                });
            }
        }

        for (int itemIdx = 0; itemIdx < itemLen; itemIdx++) {
            Object item = state.items.get(itemIdx);
            switch (item) {
                case Placement p -> {
                    if (parallelPlaced == null || !parallelPlaced[itemIdx]) {
                        if (hasShifts) {
                            float[] sh = shiftOf(shifts, p);
                            p.box().layout(innerX + sh[0] + p.x(), innerY + sh[1] + p.y(), innerW, p.box().h, ctx);
                        } else {
                            p.box().layout(innerX + p.x(), innerY + p.y(), innerW, p.box().h, ctx);
                        }
                    }
                    if (!p.box().isAbsolute && !isFloating(p.box())) recordedFirstLine = true;
                }
                case StaticPosition sp -> {
                    sp.box().staticX = innerX + sp.x();
                    sp.box().staticY = innerY + sp.y();
                    // インラインボックスの中の絶対配置は、その箱の layout を通らないのでここで置く
                    if (sp.box().parent != box) sp.box().layout(innerX, innerY, innerW, 0, ctx);
                }
                case LineRun run -> {
                    List<LayoutBox> runBoxes = run.boxes();
                    for (int i = 0, n = runBoxes.size(); i < n; i++) {
                        // 並びの中の float・絶対配置は Placement / StaticPosition が先に置いている（ここで消すと中身の断片が消える）
                        LayoutBox rb = runBoxes.get(i);
                        if (!isFloating(rb) && !rb.isAbsolute) clearFragmentsRecursively(rb, true);
                    }
                    List<InlineLine> lines = run.lines();
                    if (hasShifts) {
                        for (int i = 0, n = lines.size(); i < n; i++) {
                            InlineLine line = lines.get(i);
                            float[] sh = shiftOf(shifts, line);
                            textBottom = Math.max(textBottom,
                                    placeLine(line, innerX + sh[0] + line.left(), innerY + sh[1] + line.top(), line.width(),
                                            lineJustify.align(line), lineJustify.justifies(line), lineJustify.mode(), ctx));
                        }
                    } else {
                        for (int i = 0, n = lines.size(); i < n; i++) {
                            InlineLine line = lines.get(i);
                            textBottom = Math.max(textBottom,
                                    placeLine(line, innerX + line.left(), innerY + line.top(), line.width(),
                                            lineJustify.align(line), lineJustify.justifies(line), lineJustify.mode(), ctx));
                        }
                    }
                    if (!recordedFirstLine && !lines.isEmpty()) {
                        recordedFirstLine = true;
                        if (flOwner != null && flOwner.firstLineStyle != null) {
                            InlineLine line0 = lines.get(0);
                            float[] sh0 = hasShifts ? shiftOf(shifts, line0) : NO_SHIFT;
                            float alignShift = switch (textAlign) {
                                case CENTER -> Math.max(0, (line0.width() - line0.used()) / 2);
                                case RIGHT -> Math.max(0, line0.width() - line0.used());
                                default -> 0f;
                            };
                            if (line0.used() > 0 && line0.height() > 0) {
                                box.firstLineBgRect = new float[]{
                                        innerX + sh0[0] + line0.left() + alignShift,
                                        innerY + sh0[1] + line0.top(),
                                        line0.used(), line0.height()
                                };
                            }
                            for (int ii = 0, in = line0.items().size(); ii < in; ii++) {
                                LineItem flItem = line0.items().get(ii);
                                if (flItem.firstLineStyle() == null || flItem.box() == null) continue;
                                List<InlineFragment> frags = flItem.box().inlineFragments;
                                for (int fi = frags.size() - 1; fi >= 0; fi--) {
                                    InlineFragment frag = frags.get(fi);
                                    if (frag.firstLineStyle != null && frag.firstLineOwner == null) {
                                        frag.firstLineOwner = flOwner;
                                    }
                                }
                            }
                        }
                    }
                    for (int i = 0, n = runBoxes.size(); i < n; i++) {
                        LayoutBox inlineBox = runBoxes.get(i);
                        finishFragmentsRecursively(inlineBox);
                        fitToFragments(inlineBox);
                    }
                }
                default -> throw new IllegalStateException(String.valueOf(item));
            }
        }
        return textBottom;
    }

    /** 外側のマーカーを測るときの文脈。折り返さず、内容の幅に縮む。 */
    private static final FormattingContext MARKER_CONTEXT = FormattingContext.of(FormattingContext.Display.BLOCK, true, true);

    /**
     * 外側の {@code ::marker} を、リスト項目の境界ボックスの左外に、最初の行のベースラインに揃えて置く。
     * 行が無い（中身がブロックだけで文字を持たない）ときは内容の上端に揃える。
     */
    private void placeOutsideMarkers(LayoutBox box, float innerY, LayoutContext ctx) {
        if (!box.hasOutsideMarker) return;
        for (int i = 0, n = box.children.size(); i < n; i++) {
            LayoutBox marker = box.children.get(i);
            if (!marker.isOutsideMarker || isDisplayNone(marker.style)) continue;
            float markerX = box.x - marker.w - marker.mR;
            marker.layout(markerX - marker.mL, 0, marker.w, marker.h, ctx);
            Float markerBaseline = placedFirstBaseline(marker);
            Float lineBaseline = placedFirstBaseline(box);
            float y = (markerBaseline != null && lineBaseline != null)
                    ? lineBaseline - (markerBaseline - marker.y)
                    : innerY;
            marker.layout(markerX - marker.mL, y - marker.mT, marker.w, marker.h, ctx);
        }
    }

    /** 配置済みの箱の中で最初に置かれた行のベースライン（絶対座標）。マーカーと位置指定の外は見ない。 */
    private static Float placedFirstBaseline(LayoutBox box) {
        if (!box.fragmentsStale && !box.inlineFragments.isEmpty()) {
            InlineFragment first = box.inlineFragments.getFirst();
            return first.y + first.baseline;
        }
        for (LayoutBox child : box.children) {
            if (child.isOutsideMarker || child.isRenderedLegend || child.isAbsolute || child.display.isNone()) continue;
            Float found = placedFirstBaseline(child);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * 最初の子の上マージンが親を貫通するか。
     * 親が上パディング・上ボーダーを持つか、BFC を作るなら貫通しない（CSS 2.2 §8.3.1）。
     */
    static boolean collapsesThroughTop(LayoutBox box) {
        return !box.establishesBfc && box.pT == 0 && box.bT == 0;
    }

    /** 末子の下マージンが親を貫通するか。高さ・min-height の指定・下パディング・下ボーダー・BFC が断つ。 */
    static boolean collapsesThroughBottom(LayoutBox box) {
        ComputedStyle ax = box.axisStyle();
        return !box.establishesBfc && box.pB == 0 && box.bB == 0 && !hasSpecifiedHeight(ax)
                && ax.length(CssPropertyIndex.MIN_HEIGHT, 0, 0, 0) <= 0;
    }

    /**
     * 自分の上下のマージンが自分を貫通して 1 つに畳まれる箱（高さ 0 で行もボーダーも無い）。
     *
     * 何が壊れていたか: 以前は空の箱も高さのある箱と同じに積み、前後のマージンを別々に足していた。
     */
    static boolean collapsesThrough(LayoutBox child) {
        return child.h == 0 && child.verticalDecoration() == 0 && !child.establishesBfc
                && child.display.isBlockContainer();
    }

    /** float かどうか。float はブロックフローの子のときだけ効く（flex / grid アイテムでは無視される）。 */
    private static final int ID_FLOAT = CssPropertyIndex.FLOAT;
    private static final int ID_DIRECTION = CssPropertyIndex.DIRECTION;
    static final int ID_TEXT_INDENT = CssPropertyIndex.TEXT_INDENT;
    private static final int ID_HEIGHT = CssPropertyIndex.HEIGHT;

    public static boolean isFloating(LayoutBox box) {
        if (box.isAbsolute || box.parent == null || box.node.nodeType == NodeType.TEXT) return false;
        if (box.style.keywordOf(ID_FLOAT, CssKeywords.FloatSide.class) == CssKeywords.FloatSide.NONE) return false;
        return LayoutEngine.getEngine(box.parent) instanceof BlockLayoutEngine;
    }

    /** 診断用の計数（メモが使えた回数／走査した回数）。厳密である必要はない。 */
    public static long floatMemoHits, floatMemoScans;

    /**
     * 子孫（同じ BFC の中）に float があるか。あれば親の float 領域の中で高さを解き直す。
     * 結果は箱の木の形（{@link LayoutBox#shapeEpoch()}）が変わらない間は使い回す。
     * 以前は 1 つのブロックを置くたびに子孫を再帰で走査し、1,500 行の文字 1 か所の更新で CPU の約 15% を占めていた。
     */
    static boolean containsFloats(LayoutBox box) {
        if (!com.nishiyu.enderframelib.core.kernel.Shortcut.FLOAT_MEMO.on()) return scanFloats(box);
        int epoch = LayoutBox.shapeEpoch();
        if (box.floatsMemoEpoch == epoch) {
            floatMemoHits++;
            if (com.nishiyu.enderframelib.core.kernel.Shortcut.VERIFY_MEMO.on() && box.floatsMemo != scanFloatsUncached(box)) {
                throw new AssertionError("float メモが古い: 箱 " + box.node.key() + " メモ=" + box.floatsMemo);
            }
            return box.floatsMemo;
        }
        floatMemoScans++;
        boolean found = scanFloats(box);
        box.floatsMemo = found;
        box.floatsMemoEpoch = epoch;
        return found;
    }

    private static boolean scanFloats(LayoutBox box) {
        List<LayoutBox> ch = box.children;
        for (int i = 0, n = ch.size(); i < n; i++) {
            LayoutBox c = ch.get(i);
            if (c.isAbsolute || c.isRenderedLegend || c.display.isNone()) continue;
            if (isFloating(c)) return true;
            if (!c.establishesBfc && LayoutEngine.getEngine(c.display) instanceof BlockLayoutEngine && containsFloats(c)) {
                return true;
            }
        }
        return false;
    }

    /** メモを一切使わない走査（検証モード用）。 */
    private static boolean scanFloatsUncached(LayoutBox box) {
        for (LayoutBox c : box.children) {
            if (c.isAbsolute || c.isRenderedLegend || c.display.isNone()) continue;
            if (isFloating(c)) return true;
            if (!c.establishesBfc && LayoutEngine.getEngine(c.display) instanceof BlockLayoutEngine && scanFloatsUncached(c)) {
                return true;
            }
        }
        return false;
    }

    // ==========================================
    // ブロックフロー
    // ==========================================

    /**
     * 1 つのブロックコンテナの子を積む。マージンの相殺、float の配置と回避、clear、行の分割を
     * 文書順に 1 回で行う。
     */
    static final FloatSpace EMPTY_FLOAT_SPACE = new FloatSpace();

    /** text-wrap: balance / text-wrap-style: balance。 */
    static boolean isBalanced(ComputedStyle style) {
        String s = style.get(CssPropertyIndex.TEXT_WRAP_STYLE);
        if (s != null && s.trim().equalsIgnoreCase("balance")) return true;
        String w = style.get(CssPropertyIndex.TEXT_WRAP);
        return w != null && w.toLowerCase(java.util.Locale.ROOT).contains("balance");
    }

    /** 組み直しても副作用の無い並び（float・絶対配置を含まない）か。 */
    static boolean balanceable(List<LayoutBox> boxes) {
        for (LayoutBox b : boxes) {
            if (b.isAbsolute || isFloating(b) || b.hasAbsoluteChild) return false;
            if (isInlineBox(b) && !balanceable(b.children)) return false;
        }
        return true;
    }

    static boolean isFirstFlowChild(LayoutBox box) {
        if (box.parent == null) return true;
        List<LayoutBox> ch = box.parent.children;
        for (int i = 0, n = ch.size(); i < n; i++) {
            LayoutBox c = ch.get(i);
            if (c.isAbsolute || c.isOutsideMarker || c.isRenderedLegend || c.display.isNone()) continue;
            return c == box;
        }
        return false;
    }

    static LayoutBox firstLineOwner(LayoutBox box) {
        if (box.firstLineStyle != null) return box;
        if (box.isVerticalWrapper && box.parent != null && box.parent.firstLineStyle != null) {
            return box.parent;
        }
        if (box.isAnonymous && box.parent != null && isFirstFlowChild(box)) {
            return firstLineOwner(box.parent);
        }
        return null;
    }

    /** ブロックフローの配置結果。座標は内容ボックスの原点からの相対値。 */
    static final class FlowState implements LayoutEngineState {
        /** {@link Placement} / {@link StaticPosition} / {@link LineRun} を文書順に。 */
        final List<Object> items = new CompactList<>();
        float innerW;
        float height;
        boolean wrapped;

        void reset() {
            items.clear();
            innerW = 0;
            height = 0;
            wrapped = false;
        }
    }

    /** 子の箱の位置（マージンボックスの左上。{@link LayoutBox#layout} がマージンを足す）。 */
    record Placement(LayoutBox box, float x, float y) {}

    /** 絶対配置の静的位置。 */
    record StaticPosition(LayoutBox box, float x, float y) {}

    /** 匿名ブロックに包まれていないインライン内容の並びと、その行。 */
    record LineRun(List<LayoutBox> boxes, List<InlineLine> lines) {}

    // ==========================================
    // ベースライン
    // ==========================================

    /**
     * 最初の行のベースライン（border box の上端から）。行が無ければ null。
     * flex / grid の {@code baseline} 揃えが読む。
     */
    public static Float firstBaseline(LayoutBox box) {
        if (synthesizesBaseline(box)) return null;
        if (box.engineState instanceof FlexLayoutEngine.FlexState) return FlexLayoutEngine.containerFirstBaseline(box);
        if (!(box.engineState instanceof FlowState state)) return null;
        for (Object item : state.items) {
            Float b = baselineOf(box, item);
            if (b != null) return b;
        }
        return null;
    }

    /**
     * size / layout containment を持つ箱のベースラインは、中身に依らず境界ボックスの端から合成する（CSS Containment 2 §3, §4）。
     * 中身の配置の状態は、測った後に layout が組むので、読むと前回の layout の残りに左右される。
     */
    private static boolean synthesizesBaseline(LayoutBox box) {
        return (box.style.containment() & (ComputedStyle.CONTAIN_SIZE | ComputedStyle.CONTAIN_LAYOUT)) != 0
                || box.effectiveContainsBlockSize();
    }

    // ==========================================
    // 仮想ブロックフロー（content-visibility: auto × FenwickVirtualList）
    // ==========================================

    private static boolean canVirtualize(LayoutBox box) {
        List<LayoutBox> kids = box.children;
        int n = kids.size();
        // 仮想化は content-visibility: auto の子があるときだけ。子孫に 1 つも無い（印が立っていない）なら子を走査しない
        // （以前は 2,000 行の親を測るたびに全部の子を直列に調べ、最後に「auto の子なし」で偽を返していた。B11）
        if (n < VIRTUAL_FLOW_THRESHOLD || !box.subtreeHasContentAuto || box.hasOutsideMarker || box.hasAbsoluteChild
                || MulticolLayout.isMulticol(box) || box.renderedLegend() != null) {
            return false;
        }
        if (!box.establishesBfc && box.floatSpace != null) return false;
        boolean anyCvAuto = false;
        for (int i = 0; i < n; i++) {
            LayoutBox c = kids.get(i);
            if (c.isAbsolute || c.isOutsideMarker || c.isRenderedLegend) return false;
            if (c.style.facts().displayNone) continue;
            if (isInlineBox(c) || isFloating(c) || c.node.nodeType == NodeType.TEXT) return false;
            if (!c.establishesBfc && containsFloats(c)) return false;
            if (collapsesThrough(c)) return false;
            if (c.style.facts().contentVisibilityAuto) anyCvAuto = true;
        }
        return anyCvAuto;
    }

    private static VirtualFlowState buildVirtualFlow(LayoutBox box, ConstraintSpace space, LayoutContext ctx,
                                                     float innerWOverride, VirtualFlowState vf) {
        VIRTUAL_FLOWS_BUILT.increment();
        List<LayoutBox> kids = box.children;
        int n = kids.size();
        float innerW = Float.isNaN(innerWOverride) ? Math.max(0, box.w - box.horizontalDecoration()) : innerWOverride;
        float percentBase = childPercentageBase(box, space.percentageBlockSize(), ctx);
        boolean rtl = box.style.layoutFacts().rtl;
        FormattingContext childFmt = FormattingContext.of(FormattingContext.Display.BLOCK, true,
                box.sizingMode == SizingMode.SHRINK_TO_FIT);
        vf.prepare(n, innerW, percentBase, box, childFmt);

        boolean collapseTop = collapsesThroughTop(box);
        float pendingPos = 0, pendingNeg = 0;
        float topPos = 0, topNeg = 0;
        boolean hasContent = false;

        for (int i = 0; i < n; i++) {
            LayoutBox c = kids.get(i);
            c.virtualChildIndex = i;
            if ("none".equals(c.style.get(CssPropertyIndex.DISPLAY))) {
                vf.gapBefore[i] = 0;
                vf.slotHeights[i] = 0;
                vf.xPos[i] = 0;
                continue;
            }
            if (c.cvForceRelevant) vf.addForceRelevant(i);

            boolean intoParentTop = !hasContent && collapseTop;
            if (intoParentTop) {
                if (c.mT > 0) topPos = Math.max(topPos, c.mT);
                else topNeg = Math.min(topNeg, c.mT);
                vf.gapBefore[i] = 0;
            } else {
                if (c.mT > 0) pendingPos = Math.max(pendingPos, c.mT);
                else pendingNeg = Math.min(pendingNeg, c.mT);
                vf.gapBefore[i] = pendingPos + pendingNeg;
                pendingPos = 0;
                pendingNeg = 0;
            }
            vf.slotHeights[i] = vf.gapBefore[i] + c.h;
            if (c.mB > 0) pendingPos = Math.max(pendingPos, c.mB);
            else pendingNeg = Math.min(pendingNeg, c.mB);
            hasContent = true;

            if (rtl && !"auto".equals(c.axisStyle().getOrDefault(CssPropertyIndex.MARGIN_RIGHT, "").trim())) {
                vf.xPos[i] = Math.max(0, innerW - (c.w + c.mL + c.mR));
            } else {
                vf.xPos[i] = 0;
            }
        }
        vf.fenwick.initFrom(vf.slotHeights, n);

        if (collapsesThroughBottom(box) && (hasContent || !collapseTop)) {
            float m = box.ownMarginBottom;
            if (m > 0) pendingPos = Math.max(pendingPos, m);
            else pendingNeg = Math.min(pendingNeg, m);
            box.mB = pendingPos + pendingNeg;
            vf.trailingMargin = 0;
        } else {
            vf.trailingMargin = pendingPos + pendingNeg;
        }
        if (collapseTop) {
            float m = box.ownMarginTop;
            if (m > 0) topPos = Math.max(topPos, m);
            else topNeg = Math.min(topNeg, m);
            box.mT = topPos + topNeg;
        }
        vf.height = vf.fenwick.totalHeight() + vf.trailingMargin;
        return vf;
    }

    private float arrangeVirtualFlow(LayoutBox box, VirtualFlowState vf, float innerX, float innerY, float innerW,
                                     LayoutContext ctx) {
        List<LayoutBox> kids = box.children;
        int n = kids.size();
        if (n == 0) {
            vf.placedStart = -1;
            vf.placedEnd = -1;
            vf.extraPlacedCount = 0;
            return Float.NEGATIVE_INFINITY;
        }
        LayoutContext.AutoVisibilitySession av = ctx.autoVisibility();
        float margin = LayoutContext.AutoVisibilitySession.CONTENT_VISIBILITY_MARGIN;
        float localTop = Math.max(0f, (ctx.visibilityClipTop() - margin) - innerY);
        float localBottom = Math.max(0f, (ctx.visibilityClipBottom() + margin) - innerY);
        float totalH = vf.fenwick.totalHeight();

        int estStart = 0, estEnd = -1;
        if (localBottom > 0f && localTop < totalH) {
            estStart = vf.fenwick.firstVisible(localTop);
            if (estStart < n) {
                double y = vf.fenwick.offsetOf(estStart);
                for (int i = estStart; i < n && y < localBottom; i++) {
                    estEnd = i;
                    y += vf.fenwick.heightAt(i);
                }
            }
        }

        int prevStart = vf.placedStart, prevEnd = vf.placedEnd;
        boolean heightChanged = false;

        // 先に前回の配置範囲から画面外へ出た行を再スキップして Fenwick を更新（O(K log N)）
        if (prevStart >= 0 && prevEnd >= prevStart) {
            int limit = Math.min(prevEnd, n - 1);
            for (int i = prevStart; i <= limit; i++) {
                if (i >= estStart && i <= estEnd) continue;
                if (reSkipVirtualRow(kids.get(i), i, vf, ctx)) heightChanged = true;
            }
        }
        for (int k = 0; k < vf.extraPlacedCount; k++) {
            int idx = vf.extraPlaced[k];
            if (idx >= 0 && idx < n && !(idx >= estStart && idx <= estEnd)) {
                if (reSkipVirtualRow(kids.get(idx), idx, vf, ctx)) heightChanged = true;
            }
        }
        vf.extraPlacedCount = 0;

        // 最新のオフセットで可視範囲を確定・具現化・配置（O(log N + K)）
        totalH = vf.fenwick.totalHeight();
        int newStart = -1, newEnd = -1;
        if (localBottom > 0f && localTop < totalH) {
            int first = vf.fenwick.firstVisible(localTop);
            if (first < n) {
                double curTop = vf.fenwick.offsetOf(first);
                for (int i = first; i < n && curTop < localBottom; i++) {
                    LayoutBox child = kids.get(i);
                    if (!child.style.facts().displayNone) {
                        if (materializeAndMeasureVirtualRow(child, i, vf, ctx, av)) heightChanged = true;
                        float childBoxY = (float) (curTop + vf.gapBefore[i]);
                        child.layout(innerX + vf.xPos[i], innerY + childBoxY - child.mT, innerW, child.h, ctx);
                        if (newStart < 0) newStart = i;
                        newEnd = i;
                    }
                    curTop += vf.fenwick.heightAt(i);
                }
            }
        }
        vf.placedStart = newStart;
        vf.placedEnd = newEnd;

        // フォーカス等により強制関連な画面外行を配置
        for (int k = 0; k < vf.forceRelevantCount; k++) {
            int idx = vf.forceRelevantIndices[k];
            if (idx < 0 || idx >= n || (idx >= newStart && idx <= newEnd)) continue;
            LayoutBox child = kids.get(idx);
            if (child.style.facts().displayNone) continue;
            if (materializeAndMeasureVirtualRow(child, idx, vf, ctx, av)) heightChanged = true;
            float rowTop = vf.fenwick.offsetOf(idx) + vf.gapBefore[idx];
            child.layout(innerX + vf.xPos[idx], innerY + rowTop - child.mT, innerW, child.h, ctx);
            vf.addExtraPlaced(idx);
        }

        vf.height = vf.fenwick.totalHeight() + vf.trailingMargin;
        if (heightChanged && !hasSpecifiedHeight(box.axisStyle())) {
            float newBoxH = vf.height + box.verticalDecoration();
            if (Math.abs(newBoxH - box.h) > 0.01f) {
                box.h = newBoxH;
                box.contentBlockExtent = newBoxH;
                if (av != null) av.noteNonVirtualVisibilityChanged();
            }
        }
        return Float.NEGATIVE_INFINITY;
    }

    private static boolean materializeAndMeasureVirtualRow(LayoutBox child, int index, VirtualFlowState vf,
                                                           LayoutContext ctx, LayoutContext.AutoVisibilitySession av) {
        if (!child.style.facts().contentVisibilityAuto) return false;
        if (av != null) av.noteContentAuto();
        if (!child.contentAutoSkipped && !child.childrenDeferred) return false;
        float prevH = child.h;
        child.contentAutoSkipped = false;
        if (child.childrenDeferred) ctx.materializeAutoBox(child);
        child.measure(vf.childMeasureSpace, ctx);
        if (Math.abs(child.h - prevH) > 0.01f) {
            vf.fenwick.setHeight(index, vf.gapBefore[index] + child.h);
            return true;
        }
        return false;
    }

    private static boolean reSkipVirtualRow(LayoutBox old, int index, VirtualFlowState vf, LayoutContext ctx) {
        if (old.style.facts().contentVisibilityAuto && !old.cvForceRelevant) {
            boolean changed = false;
            if (!old.contentAutoSkipped) {
                old.recordRememberedSize();
                old.releaseDeferredChildren();
                old.contentAutoSkipped = true;
                float prevH = old.h;
                old.measure(vf.childMeasureSpace, ctx);
                if (Math.abs(old.h - prevH) > 0.01f) {
                    vf.fenwick.setHeight(index, vf.gapBefore[index] + old.h);
                    changed = true;
                }
            }
            old.x = 0;
            old.y = 0;
            old.scrollWidth = 0;
            old.scrollHeight = 0;
            if (!old.inlineFragments.isEmpty()) old.discardFragments();
            return changed;
        }
        return false;
    }

    /** 最後の行のベースライン（border box の上端から）。インラインブロックのベースラインになる。 */
    static Float lastBaseline(LayoutBox box) {
        if (synthesizesBaseline(box) || !(box.engineState instanceof FlowState state)) return null;
        for (int i = state.items.size() - 1; i >= 0; i--) {
            Object item = state.items.get(i);
            if (item instanceof Placement p && !isFloating(p.box())) {
                Float b = lastBaseline(p.box());
                if (b != null) return box.bT + box.pT + box.legendSpill + p.y() + p.box().mT + b;
            } else if (item instanceof LineRun run && !run.lines().isEmpty()) {
                InlineLine line = run.lines().getLast();
                return box.bT + box.pT + box.legendSpill + line.top() + line.baseline();
            }
        }
        return null;
    }

    private static Float baselineOf(LayoutBox box, Object item) {
        if (item instanceof Placement p && !isFloating(p.box())) {
            Float b = firstBaseline(p.box());
            // fieldset の中身は legend のはみ出し（legendSpill）の下から始まる
            return b != null ? box.bT + box.pT + box.legendSpill + p.y() + p.box().mT + b : null;
        }
        if (item instanceof LineRun run && !run.lines().isEmpty()) {
            InlineLine line = run.lines().getFirst();
            return box.bT + box.pT + box.legendSpill + line.top() + line.baseline();
        }
        return null;
    }

    /**
     * 不可分なインラインのベースライン（border box の上端から）。
     * 行を持ち overflow が visible のインラインブロックは最後の行のベースライン、
     * それ以外（行が無い・置換要素・overflow が visible 以外）は下マージンの端（CSS 2.2 §10.8.1）。
     *
     * 何が壊れていたか: 以前は常に border box の下端で近似していて、下マージンを持つ
     * インラインブロックが行のベースラインより下にはみ出していた。
     *
     * ただし UA の部品で描くチェックボックス・ラジオボタンと、レンジ（appearance によらない。Chrome の LayoutSlider）は
     * border box の下端（下マージンはベースラインの下に出る）。Chrome の実測。以前は下マージンの端にしていて、
     * チェックボックスだけの行が Chrome より 2.5px 高かった。
     */
    static float atomicBaseline(LayoutBox box) {
        if (StyleResolver.isNativeToggle(box.node, box.style.asMap()) || isRangeInput(box)) return box.h;
        Float b = null;
        if (box.display.inner() == Inner.FLOW_ROOT && !box.isScrollContainer()) {
            b = lastBaseline(box);
        } else if (box.display.inner() != Inner.FLOW_ROOT) {
            b = firstBaseline(box);
        }
        return b != null ? b : box.h + box.mB;
    }

    private static boolean isRangeInput(LayoutBox box) {
        return com.nishiyu.enderframelib.core.dom.ElementTraits.isRangeInput(box.node);
    }

    // ==========================================
    // インライン整形
    // ==========================================

    /** {@code vertical-align} の揃え方。 */
    enum VAlign { BASELINE, TOP, BOTTOM }

    /** 行の中の 1 断片。{@code raise} はベースラインから持ち上げる量（px）。 */
    /**
     * 行の中の 1 断片（文字の語・不可分なインラインの箱）。座標は行の左端からの相対（{@code x}）で、{@link #placeLine} が行の位置と
     * 揃えを足して {@link InlineFragment} を作って置く。以前は行の相対座標も {@link InlineFragment} で持っており、
     * 語ごとに同じ大きさのオブジェクトが（行の記憶と置いた結果で）2 つずつ残っていた。
     */
    record SliceStyle(LayoutBox box, float runH, float runBaseline, float descent,
                      List<LayoutBox> parents, VAlign align, float raise) {}

    record LineItem(LayoutBox box, float x, float w, float h, String text, float baseline, float descent,
                    List<LayoutBox> parents, VAlign align, float raise, ComputedStyle firstLineStyle) {}

    /**
     * 行の上で開いている（開始タグを過ぎて終了タグに達していない）インラインボックス。
     * 行をまたぐと、次の行の頭から続きの断片を始める。
     */
    static final class OpenBox {
        final LayoutBox box;
        final Alignment alignment;
        /** この行での断片の左端（border box）と、その手前の開始端の margin の位置。行の左端からの相対。 */
        float start, marginStart;
        /** この行の断片が開始端（左の margin / border / padding）を持つか。最初の断片だけが持つ。 */
        boolean startEdge = true;
        /** この行に中身を置いたか。中身を置く前に折り返したら、開始端ごと次の行へ移る。 */
        boolean hasContent;
        /** 次の行の頭で開始端を付け直す。 */
        boolean reopen;

        OpenBox(LayoutBox box, Alignment alignment) {
            this.box = box;
            this.alignment = alignment;
        }
    }

    /** 1 行に載ったインラインボックスの断片（border box の左右端、行の左端からの相対）。 */
    record BoxSpan(OpenBox open, float left, float right, boolean startEdge, boolean endEdge) {}

    static final Alignment ALIGNMENT_BASELINE_ZERO = new Alignment(VAlign.BASELINE, 0);

    record Alignment(VAlign align, float raise) {}

    /** 行を空にしない装飾（margin・border・padding）を持つインラインボックスか（CSS 2.2 §9.4.2）。 */
    static boolean isDecorated(LayoutBox b) {
        return b.mL != 0 || b.mR != 0 || b.horizontalDecoration() != 0 || b.verticalDecoration() != 0;
    }

    /** 断片のマージンを含む高さ。 */
    static float outerHeight(LineItem f) {
        return f.box().mT + f.h() + f.box().mB;
    }

    /**
     * 1 行分の断片を水平に揃え、縦に揃えて確定する。断片は行の相対座標から複製して置くので、
     * 同じ行を何度置いても結果は変わらない。
     *
     * 何が壊れていたか: 以前は断片を行の高さに対して中央寄せしていたため、
     * サイズの違う文字やインラインブロックが混ざった行で文字の足元が揃わなかった。
     * CSS はベースラインを揃える。また不可分なインラインの左マージンを断片の位置と
     * {@link LayoutBox#layout} の両方で足していて、二重にずれていた。
     */
    private float placeLine(InlineLine line, float startX, float drawY, float maxW, TextAlign textAlign,
                            boolean justify, String justifyMode, LayoutContext ctx) {
        if (line.sliceWp != null) {
            int start = line.sliceStart, end = line.sliceEnd;
            if (start == end) return Float.NEGATIVE_INFINITY;
            String[] segs = line.sliceWp.prepared().segs();
            float extraS = 0;
            int lastS = end - 1;
            while (lastS > start && !isNotBlankFast(segs[line.sliceWp.segIndex(lastS)])) lastS--;
            if (justify && maxW > line.used + 0.01f) {
                int opps = 0;
                for (int k = start; k < lastS; k++) {
                    if (justifyOpportunity(segs[line.sliceWp.segIndex(k)], segs[line.sliceWp.segIndex(k + 1)], justifyMode)) opps++;
                }
                if (opps > 0) extraS = (maxW - line.used) / opps;
            }
            float offsetX = startX;
            if (textAlign == TextAlign.CENTER) offsetX += Math.max(0, (maxW - line.used) / 2);
            else if (textAlign == TextAlign.RIGHT) offsetX += Math.max(0, maxW - line.used);

            TextShaper.WrappedParagraph wp = line.sliceWp;
            String[] segments = wp.prepared().segs();
            SliceStyle st = line.slice;
            LayoutBox box = st.box();
            float top = switch (st.align()) {
                case TOP -> drawY;
                case BOTTOM -> drawY + line.height - (box.mT + st.runH() + box.mB);
                default -> drawY + line.baseline - (box.mT + st.runBaseline() + st.raise());
            };
            float fragY = top + box.mT;
            float runH = st.runH();
            float runBase = st.runBaseline();
            float runDesc = st.descent();
            List<LayoutBox> parents = st.parents();
            float textBottom = Float.NEGATIVE_INFINITY;
            float acc = 0;
            for (int k = start; k < end; k++) {
                String text = segments[wp.segIndex(k)];
                box.addFragment(wp.itemX(k) + offsetX + acc, fragY, wp.advance(k), runH, text,
                        runBase, runDesc, parents, true, true);
                if (extraS != 0 && k < lastS && justifyOpportunity(text, segments[wp.segIndex(k + 1)], justifyMode)) acc += extraS;
                if (isNotBlankFast(text)) {
                    textBottom = Math.max(textBottom, fragY + runBase + runDesc);
                }
            }
            return textBottom;
        }
        List<LineItem> items = line.items();
        if (items.isEmpty() && line.spans().isEmpty()) return Float.NEGATIVE_INFINITY;
        float textBottom = Float.NEGATIVE_INFINITY;

        // 揃えの幅は行の左端から内容の終わりまで（text-indent と開始端の装飾を含み、行末の空白を除く）
        float offsetX = startX;
        if (textAlign == TextAlign.CENTER) offsetX += Math.max(0, (maxW - line.used()) / 2);
        else if (textAlign == TextAlign.RIGHT) {
            offsetX += Math.max(0, maxW - line.used());
        }

        // 両端揃え: 行の余りを、語の間（と CJK の字の間）の機会に等しく配る（CSS Text 3 §7.3・§7.4）
        float extra = 0;
        int lastItem = items.size() - 1;
        float[] oppEnds = null;
        int oppCount = 0;
        if (justify && maxW > line.used() + 0.01f) {
            while (lastItem > 0 && (items.get(lastItem).text() == null || !isNotBlankFast(items.get(lastItem).text()))) lastItem--;
            for (int i = 0; i < lastItem; i++) {
                if (justifyOpportunity(items.get(i).text(), items.get(i + 1).text(), justifyMode)) {
                    if (oppEnds == null) oppEnds = new float[lastItem];
                    oppEnds[oppCount++] = items.get(i).x() + items.get(i).w();
                }
            }
            if (oppCount > 0) extra = (maxW - line.used()) / oppCount;
        }
        float acc = 0;
        for (int i = 0, n = items.size(); i < n; i++) {
            LineItem src = items.get(i);
            float itemShift = acc;
            if (extra != 0 && i < lastItem && justifyOpportunity(src.text(), items.get(i + 1).text(), justifyMode)) acc += extra;
            // マージンボックスの上端
            float top = switch (src.align()) {
                case TOP -> drawY;
                case BOTTOM -> drawY + line.height() - outerHeight(src);
                default -> drawY + line.baseline() - (src.box().mT + src.baseline() + src.raise());
            };

            InlineFragment f = src.box().addFragment(src.x() + offsetX + itemShift, top + src.box().mT, src.w(), src.h(), src.text(),
                    src.baseline(), src.descent(), src.parents(), true, true, src.firstLineStyle());
            if (!f.isText()) {
                f.box.layout(f.x - f.box.mL, f.y - f.box.mT, f.w, f.h, ctx);
            } else if (isNotBlankFast(src.text())) {
                // 文字の内容領域（ベースラインから descent まで）は行の高さより下に出ることがある。
                // ブラウザのスクロール範囲（scrollHeight）はこの内容領域を含む
                textBottom = Math.max(textBottom, f.y + f.baseline + f.descent);
            }
        }
        List<BoxSpan> lineSpans = line.spans();
        for (int i = 0, n = lineSpans.size(); i < n; i++) {
            BoxSpan sp = lineSpans.get(i);
            if (extra != 0) {
                // 箱の左右の端も、その手前にある揃えの機会の数だけずらす
                float sl = 0, sr = 0;
                for (int k = 0; k < oppCount; k++) {
                    if (oppEnds[k] <= sp.left() + 0.01f) sl += extra;
                    if (oppEnds[k] <= sp.right() + 0.01f) sr += extra;
                }
                sp = new BoxSpan(sp.open(), sp.left() + sl, sp.right() + sr, sp.startEdge(), sp.endEdge());
            }
            placeSpan(sp, offsetX, drawY, line, ctx);
        }
        return textBottom;
    }

    /** 2 つの隣り合う項目の間が両端揃えで広がるか（語の後ろの空白、CJK の字の間、inter-character なら文字の間すべて）。 */
    static boolean justifyOpportunity(String a, String b, String mode) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty() || mode.equals("none")) return false;
        char last = a.charAt(a.length() - 1);
        if (last == ' ' || last == '\u00A0') return true;
        if (mode.equals("inter-word")) return false;
        if (mode.equals("inter-character")) return true;
        return isCjk(last) || isCjk(b.charAt(0));
    }

    private static boolean isCjk(char c) {
        return (c >= 0x2E80 && c <= 0x9FFF) || (c >= 0xAC00 && c <= 0xD7AF) || (c >= 0xF900 && c <= 0xFAFF) || (c >= 0xFF00 && c <= 0xFFEF);
    }

    /**
     * 文字列が空白のみでないかを先頭・末尾文字から高速に判定する（isBlank の文字走査を回避）。
     * ASCII 表示可能文字（0x21〜0x7e）であれば Unicode 空白の可能性はないため即座に true を返す。
     */
    static boolean isNotBlankFast(String s) {
        if (s == null || s.isEmpty()) return false;
        char first = s.charAt(0);
        if (first > ' ' && first <= '~') return true;
        char last = s.charAt(s.length() - 1);
        if (last > ' ' && last <= '~') return true;
        return !s.isBlank();
    }

    /**
     * インラインボックスの 1 行分の断片を置く。縦は行の高さではなく自分の内容領域（書体の ascent + descent を
     * 自分のベースラインに置いたもの）に上下の padding と border を足した範囲（CSS 2.2 §10.6.1）。
     */
    private static void placeSpan(BoxSpan span, float offsetX, float drawY, InlineLine line, LayoutContext ctx) {
        LayoutBox b = span.open().box;
        FontFace face = ctx.faceFor(b.style);
        FontStyle font = ctx.fontStyleFor(b.style);
        float ascent = face.ascent(font);
        float descent = face.descent(font);
        float leadingAbove = TextShaper.leadingAbove(b.lineHeightPx, ascent, descent);
        float leadingBelow = b.lineHeightPx - (ascent + descent) - leadingAbove;
        Alignment alignment = span.open().alignment;
        float baselineY = switch (alignment.align()) {
            case TOP -> drawY + leadingAbove + ascent;
            case BOTTOM -> drawY + line.height() - leadingBelow - descent;
            default -> drawY + line.baseline() - alignment.raise();
        };
        float top = baselineY - ascent - b.pT - b.bT;
        b.addFragment(offsetX + span.left(), top, span.right() - span.left(),
                b.bT + b.pT + ascent + descent + b.pB + b.bB, null,
                baselineY - top, 0, null, span.startEdge(), span.endEdge());
    }

    /**
     * インラインボックスの矩形（{@code x / y / w / h}）を、置いた断片を囲む矩形にする。
     * ブラウザの {@code getBoundingClientRect} と同じく大きさ 0 の断片は除き、全部が 0 なら最初の断片。
     * ヒットテストの錨と忠実度のダンプがこの矩形を読む。
     *
     * 何が壊れていたか: 以前はインラインボックスに位置を入れておらず、矩形は測定の幅のまま原点にあった。
     */
    private static void fitToFragments(LayoutBox box) {
        if (box.isAbsolute || box.display.isNone() || isFloating(box) || isAtomicInline(box)
                || box.node.nodeType == NodeType.TEXT || !isInlineBox(box)) {
            return;
        }
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        List<InlineFragment> frags = box.inlineFragments;
        for (int i = 0, n = frags.size(); i < n; i++) {
            InlineFragment f = frags.get(i);
            if (f.isText() || f.w <= 0 || f.h <= 0) continue;
            minX = Math.min(minX, f.x);
            minY = Math.min(minY, f.y);
            maxX = Math.max(maxX, f.x + f.w);
            maxY = Math.max(maxY, f.y + f.h);
        }
        if (minX <= maxX) {
            box.x = minX;
            box.y = minY;
            box.w = maxX - minX;
            box.h = maxY - minY;
        } else {
            for (int i = 0, n = frags.size(); i < n; i++) {
                InlineFragment f = frags.get(i);
                if (f.isText()) continue;
                box.x = f.x;
                box.y = f.y;
                box.w = f.w;
                box.h = f.h;
                break;
            }
        }
        List<LayoutBox> children = box.children;
        for (int i = 0, n = children.size(); i < n; i++) fitToFragments(children.get(i));
    }

    /** 断片の上端からベースラインまでの距離。整形器と同じ half-leading の分け方を使う。 */
    static float baselineWithin(TextStyle textStyle, FontFace face) {
        FontStyle font = textStyle.font();
        float ascent = face.ascent(font);
        float descent = face.descent(font);
        return TextShaper.leadingAbove(textStyle.lineHeightPx(), ascent, descent) + ascent;
    }

    public static boolean isInlineBox(LayoutBox box) {
        if (box.isAbsolute) return false;
        return box.display.isInlineLevel();
    }

    /** インラインボックスは開始タグと終了タグ（{@code OPEN} / {@code CLOSE}）で中身を挟む。 */
    /**
     * {@code snapshot[0]} は、いまの {@code parents} の読み取り専用の写し（{@code parents} が変わるまで同じものを渡す）。
     * 断片ごとに写しを作ると、親の無い（ブロック直下の）文字では空の表を、入れ子の span の中では同じ道筋の表を、
     * 語の数だけ作っていた。写しは読むだけ（{@code InlineFragment.parents} の使い手は {@code get} / {@code size} だけ）。
     */
    static void flattenInlines(List<LayoutBox> boxes, List<FlatRun> runs, List<LayoutBox> parents, List<LayoutBox>[] snapshot) {
        for (int i = 0, n = boxes.size(); i < n; i++) {
            LayoutBox b = boxes.get(i);
            if (b.display.isNone()) continue;
            if (b.isAbsolute) {
                runs.add(new FlatRun(FlatRun.Kind.ABSOLUTE, b, null, chainOf(parents, snapshot)));
            } else if (isFloating(b)) {
                runs.add(new FlatRun(FlatRun.Kind.FLOAT, b, null, chainOf(parents, snapshot)));
            } else if (b.node.nodeType == NodeType.TEXT) {
                if (b.displayText != null && !b.displayText.isEmpty()) {
                    runs.add(new FlatRun(FlatRun.Kind.TEXT, b, b.displayText, chainOf(parents, snapshot)));
                }
            } else if (b.node.tag.equals("br")) {
                runs.add(new FlatRun(FlatRun.Kind.BREAK, b, "\n", chainOf(parents, snapshot)));
            } else if (isAtomicInline(b)) {
                runs.add(new FlatRun(FlatRun.Kind.ATOMIC, b, null, chainOf(parents, snapshot)));
            } else {
                runs.add(new FlatRun(FlatRun.Kind.OPEN, b, null, chainOf(parents, snapshot)));
                parents.add(b);
                snapshot[0] = null;
                flattenInlines(b.children, runs, parents, snapshot);
                parents.removeLast();
                snapshot[0] = null;
                runs.add(new FlatRun(FlatRun.Kind.CLOSE, b, null, chainOf(parents, snapshot)));
            }
        }
    }

    private static List<LayoutBox> chainOf(List<LayoutBox> parents, List<LayoutBox>[] snapshot) {
        List<LayoutBox> s = snapshot[0];
        if (s == null) {
            int n = parents.size();
            snapshot[0] = s = n == 0 ? List.of() : (n == 1 ? List.of(parents.get(0)) : List.copyOf(parents));
        }
        return s;
    }

    /** 中身を分割せず 1 つの塊として置くインライン要素。 */
    public static boolean isAtomicInline(LayoutBox b) {
        // inline-block / inline-flex / inline-grid / inline-table は中身を自分の整形コンテキストで組む。
        // 以前は inline-block だけを見ていて、inline-flex の子がインライン内容として平坦化されていた
        if (b.display.isInlineLevel() && b.display.inner() != Inner.FLOW) return true;
        String tag = b.node.tag;
        return ReplacedElements.isReplacedTag(tag);
    }

    /** {@code inFlow}: {@code box} がこの行の流れの中身（{@link #flattenInlines} が平坦化し、自分の layout を通らない）か。 */
    private static void clearFragmentsRecursively(LayoutBox box, boolean inFlow) {
        box.startFragmentPass();
        // テキストの箱は断片から位置を取らない（fitToFragments が飛ばす）ので、祖先の相対配置・sticky のずれだけが乗る。
        // 原点に戻してから足し直させないと、同じ木を配置し直すたびにずれが積み重なる
        if (box.node.nodeType == NodeType.TEXT) {
            box.x = 0;
            box.y = 0;
        }
        boolean childrenInFlow = inFlow && !isAtomicInline(box);
        List<LayoutBox> children = box.children;
        for (int i = 0, n = children.size(); i < n; i++) {
            LayoutBox child = children.get(i);
            if (isFloating(child) || child.isAbsolute) continue;
            // 配置を使い回せる箱（原子的インラインの中身など）は、自分の layout が置き直すか前回の配置を残すかを決める。
            // ここで断片だけを消すと、残す側で断片が消えたままになる。ただし流れの中身（アトミックな箱を除く）は、前に flex の項目などとして
            // 置かれた印が残っていても layout を通らないので消す（残すと遅延消去の印が解けず、行が足した断片が捨てられる。§6-119）
            if (child.holdsPlacement() && (!childrenInFlow || isAtomicInline(child))) continue;
            clearFragmentsRecursively(child, childrenInFlow);
        }
    }

    /**
     * 配置を使い回せる印の箱は切り詰めない。流れの中の箱まで切り詰めると、断片を毎フレーム作り直して確保が増えた（costGate の
     * voidwave text +12%。§6-119）。
     */
    private static void finishFragmentsRecursively(LayoutBox box) {
        box.finishFragmentPass();
        List<LayoutBox> children = box.children;
        for (int i = 0, n = children.size(); i < n; i++) {
            LayoutBox child = children.get(i);
            if (child.holdsPlacement()) continue;
            finishFragmentsRecursively(child);
        }
    }

    /** 入れ子のインライン要素を平坦化した 1 要素。 */
    record FlatRun(Kind kind, LayoutBox box, String text, List<LayoutBox> inlineParents) {
        enum Kind { TEXT, BREAK, ATOMIC, FLOAT, ABSOLUTE, OPEN, CLOSE }
    }
}