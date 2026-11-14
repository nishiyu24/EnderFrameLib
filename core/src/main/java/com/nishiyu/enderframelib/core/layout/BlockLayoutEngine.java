package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.CssPropertyIndex;
import com.nishiyu.enderframelib.core.kernel.CompactList;
import com.nishiyu.enderframelib.core.text.ParagraphSegments;
import com.nishiyu.enderframelib.core.layout.model.*;

import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.layout.LayoutContext;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssKeywords;
import com.nishiyu.enderframelib.core.css.CssProperty;
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
import com.nishiyu.enderframelib.core.css.model.Overflow;
import com.nishiyu.enderframelib.core.css.model.TextAlign;
import com.nishiyu.enderframelib.core.css.model.BorderStyle;

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
        float lineCur = 0, lineMax = 0;
        // fill 幅ブロックの fit 境界判定では、前後の空白を削らない実送り幅（FlowBuilder が折り返し判定に使う幅）で数える
        float fitCur = 0, fitMax = 0;

        float childParentH = childPercentageBase(box, space.percentageBlockSize(), ctx);
        FormattingContext childCtx = FormattingContext.of(FormattingContext.Display.BLOCK, true, box.sizingMode == SizingMode.SHRINK_TO_FIT);
        ConstraintSpace sharedChildSpace = null;
        if (columns == null) {
            sharedChildSpace = ConstraintSpace.reuseOrCreate(box.childSpaceCache, tentativeInnerW, childParentH, childCtx);
            box.childSpaceCache = sharedChildSpace;
        }

        for (int i = 0, n = box.children.size(); i < n; i++) {
            LayoutBox child = box.children.get(i);
            if (child.isAbsolute || child.isRenderedLegend || isDisplayNone(child.style)) continue;
            if (child.isOutsideMarker) {
                // 外側のマーカーは通常フローの外にあり、リスト項目の幅に寄与しない
                child.measure(new ConstraintSpace(LayoutMetrics.UNBOUNDED_WIDTH, childParentH, MARKER_CONTEXT), ctx);
                continue;
            }
            ConstraintSpace childSpace = sharedChildSpace != null && !MulticolLayout.isSpanner(child)
                    ? sharedChildSpace
                    : new ConstraintSpace(columns != null && !MulticolLayout.isSpanner(child) ? columns.width() : tentativeInnerW, childParentH, childCtx);
            child.measure(childSpace, ctx);
            // float は行の内容と同じく横に並ぶので、shrink-to-fit の幅には横に足して数える
            if (isInlineBox(child) || isFloating(child)) {
                float[] wScratch = INLINE_WIDTH_SCRATCH.get();
                wScratch[0] = lineCur; wScratch[1] = lineMax;
                addInlineWidth(child, wScratch, ctx, false);
                lineCur = wScratch[0]; lineMax = wScratch[1];
                wScratch[0] = fitCur; wScratch[1] = fitMax;
                addInlineWidth(child, wScratch, ctx, true);
                fitCur = wScratch[0]; fitMax = wScratch[1];
                if (tentativeInnerW > 0 && lineMax > tentativeInnerW) box.measuredClamped = true;
                maxChildW = Math.max(maxChildW, tentativeInnerW > 0 ? Math.min(tentativeInnerW, lineMax) : lineMax);
                fitInnerW = Math.max(fitInnerW, fitMax);
            } else {
                lineCur = 0; lineMax = 0;
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
    private static final ThreadLocal<float[]> INLINE_WIDTH_SCRATCH = ThreadLocal.withInitial(() -> new float[2]);

    private static final class BlockScratch {
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
                }
                addLineWidth(line, w + b.mL + b.mR);
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
        } else if (isFloating(b) || isAtomicInline(b) || !isInlineBox(b)) {
            addLineWidth(line, b.w + b.mL + b.mR);
        } else {
            addLineWidth(line, b.mL + b.bL + b.pL);
            for (int i = 0, n = b.children.size(); i < n; i++) addInlineWidth(b.children.get(i), line, ctx, includeFullSpaces);
            addLineWidth(line, b.pR + b.bR + b.mR);
        }
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
    private static float childPercentageBase(LayoutBox box, float parentH, LayoutContext ctx) {
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
        if (hasSpecifiedHeight(box.style)) {
            float resolvedH = box.style.length(CssPropertyIndex.HEIGHT, parentH, rootW, rootH);
            boolean isBorderBox = box.style.isBorderBox();
            return Math.max(0, isBorderBox ? resolvedH - box.verticalDecoration() : resolvedH);
        }
        if (box.isAbsolute && BoxMeasurer.hasBothInsets(box.style, CssPropertyIndex.TOP, CssPropertyIndex.BOTTOM)) {
            float insets = box.style.length(CssPropertyIndex.TOP, parentH, rootW, rootH)
                    + box.style.length(CssPropertyIndex.BOTTOM, parentH, rootW, rootH);
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

    @Override
    public float contentBlockSize(LayoutBox box, ConstraintSpace space, LayoutContext ctx) {
        if (MulticolLayout.isMulticol(box)) return MulticolLayout.contentBlockSize(this, box, space, ctx);
        if (box.engineState instanceof MulticolLayout.State) box.engineState = null;
        if (box.sizingMode == SizingMode.SHRINK_TO_FIT) stretchBlockChildren(box, space, ctx);
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
            if (!"auto".equals(child.style.getOrDefault(CssPropertyIndex.WIDTH, "auto").trim())) continue;
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
        TextAlign textAlign = box.style.keywordOf(CssPropertyIndex.TEXT_ALIGN, TextAlign.class)
                .physical(isRtl(box.style));
        float textBottom = Float.NEGATIVE_INFINITY;
        boolean hasShifts = shifts != null && !shifts.isEmpty();

        for (int itemIdx = 0, itemLen = state.items.size(); itemIdx < itemLen; itemIdx++) {
            Object item = state.items.get(itemIdx);
            switch (item) {
                case Placement p -> {
                    if (hasShifts) {
                        float[] sh = shiftOf(shifts, p);
                        p.box().layout(innerX + sh[0] + p.x(), innerY + sh[1] + p.y(), innerW, p.box().h, ctx);
                    } else {
                        p.box().layout(innerX + p.x(), innerY + p.y(), innerW, p.box().h, ctx);
                    }
                }
                case StaticPosition sp -> {
                    sp.box().staticX = innerX + sp.x();
                    sp.box().staticY = innerY + sp.y();
                    // インラインボックスの中の絶対配置は、その箱の layout を通らないのでここで置く
                    if (sp.box().parent != box) sp.box().layout(innerX, innerY, innerW, 0, ctx);
                }
                case LineRun run -> {
                    List<LayoutBox> runBoxes = run.boxes();
                    for (int i = 0, n = runBoxes.size(); i < n; i++) clearFragmentsRecursively(runBoxes.get(i));
                    List<InlineLine> lines = run.lines();
                    if (hasShifts) {
                        for (int i = 0, n = lines.size(); i < n; i++) {
                            InlineLine line = lines.get(i);
                            float[] sh = shiftOf(shifts, line);
                            textBottom = Math.max(textBottom,
                                    placeLine(line, innerX + sh[0] + line.left(), innerY + sh[1] + line.top(), line.width(), textAlign, ctx));
                        }
                    } else {
                        for (int i = 0, n = lines.size(); i < n; i++) {
                            InlineLine line = lines.get(i);
                            textBottom = Math.max(textBottom,
                                    placeLine(line, innerX + line.left(), innerY + line.top(), line.width(), textAlign, ctx));
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
    private static boolean collapsesThroughTop(LayoutBox box) {
        return !box.establishesBfc && box.pT == 0 && box.bT == 0;
    }

    /** 末子の下マージンが親を貫通するか。高さ・min-height の指定・下パディング・下ボーダー・BFC が断つ。 */
    private static boolean collapsesThroughBottom(LayoutBox box) {
        return !box.establishesBfc && box.pB == 0 && box.bB == 0 && !hasSpecifiedHeight(box.style)
                && box.style.length(CssPropertyIndex.MIN_HEIGHT, 0, 0, 0) <= 0;
    }

    /**
     * 自分の上下のマージンが自分を貫通して 1 つに畳まれる箱（高さ 0 で行もボーダーも無い）。
     *
     * 何が壊れていたか: 以前は空の箱も高さのある箱と同じに積み、前後のマージンを別々に足していた。
     */
    private static boolean collapsesThrough(LayoutBox child) {
        return child.h == 0 && child.verticalDecoration() == 0 && !child.establishesBfc
                && child.display.isBlockContainer();
    }

    /** float かどうか。float はブロックフローの子のときだけ効く（flex / grid アイテムでは無視される）。 */
    private static final int ID_FLOAT = CssPropertyIndex.FLOAT;
    private static final int ID_DIRECTION = CssPropertyIndex.DIRECTION;
    private static final int ID_TEXT_INDENT = CssPropertyIndex.TEXT_INDENT;
    private static final int ID_HEIGHT = CssPropertyIndex.HEIGHT;

    public static boolean isFloating(LayoutBox box) {
        if (box.isAbsolute || box.parent == null || box.node.nodeType == NodeType.TEXT) return false;
        if (box.style.keywordOf(ID_FLOAT, CssKeywords.FloatSide.class) == CssKeywords.FloatSide.NONE) return false;
        return LayoutEngine.getEngine(box.parent.display) instanceof BlockLayoutEngine;
    }

    /**
     * {@link #containsFloats} の結果を箱ごとにメモするか（既定は有効）。切り分け用: {@code -Denderframe.layout.nofloatmemo=true}。
     * 無効にすると従来どおり毎回子孫を走査する（結果は同じ）。
     */
    public static volatile boolean floatMemoEnabled = !Boolean.getBoolean("enderframe.layout.nofloatmemo");
    /**
     * 検証モード: メモを使える場面でも走査し直し、メモと食い違えば {@link AssertionError}（無効化の付け漏れの検出）。
     * {@code -Denderframe.layout.verifyfloats=true}（{@code core/build.gradle} の {@code test} で常時有効）。
     */
    public static volatile boolean verifyFloatMemo = Boolean.getBoolean("enderframe.layout.verifyfloats");
    /** 診断用の計数（メモが使えた回数／走査した回数）。厳密である必要はない。 */
    public static long floatMemoHits, floatMemoScans;

    /**
     * 子孫（同じ BFC の中）に float があるか。あれば親の float 領域の中で高さを解き直す。
     * 結果は箱の木の形（{@link LayoutBox#shapeEpoch()}）が変わらない間は使い回す。
     * 以前は 1 つのブロックを置くたびに子孫を再帰で走査し、1,500 行の文字 1 か所の更新で CPU の約 15% を占めていた。
     */
    static boolean containsFloats(LayoutBox box) {
        if (!floatMemoEnabled) return scanFloats(box);
        int epoch = LayoutBox.shapeEpoch();
        if (box.floatsMemoEpoch == epoch) {
            floatMemoHits++;
            if (verifyFloatMemo && box.floatsMemo != scanFloatsUncached(box)) {
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
        for (LayoutBox c : box.children) {
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
    private static final FloatSpace EMPTY_FLOAT_SPACE = new FloatSpace();

    static final class FlowBuilder {
        final BlockScratch scratch;
        LayoutBox box;
        /** 流す子。段組は区間ごとに分けた子を渡す。 */
        List<LayoutBox> children;
        LayoutContext ctx;
        FlowState state;
        FloatSpace floats;
        /** この箱の内容ボックスの原点の、float 領域の中での位置。 */
        float ox, oy;
        float innerW;
        float childPercentBase;
        FormattingContext childCtx;
        boolean collapseTop;
        /** この箱が {@code direction: rtl} か。ブロックの子の寄せ先と行の揃えが右始まりになる。 */
        boolean rtl;

        /** 次の箱の前で 1 つに畳まれるのを待っているマージン（正の最大・負の最小）。 */
        float pendingPos = 0, pendingNeg = 0;
        /** 親（この箱）の上マージンと畳まれるマージン（正の最大・負の最小）。 */
        float topPos = 0, topNeg = 0;
        /** マージンの相殺を断つもの（行・高さのある箱）がもう置かれたか。 */
        boolean hasContent = false;
        float cursor = 0;
        boolean firstRun = true;

        FlowBuilder(BlockScratch scratch) {
            this.scratch = scratch;
        }

        FlowBuilder init(LayoutBox box, ConstraintSpace space, LayoutContext ctx, List<LayoutBox> children,
                         float innerWOverride, FlowState state) {
            state.reset();
            this.state = state;
            this.box = box;
            this.ctx = ctx;
            this.children = children;
            this.innerW = Float.isNaN(innerWOverride) ? Math.max(0, box.w - box.horizontalDecoration()) : innerWOverride;
            this.rtl = box.style.layoutFacts().rtl;
            if (!box.establishesBfc && box.floatSpace != null) {
                floats = box.floatSpace;
                ox = box.floatSpaceX;
                oy = box.floatSpaceY;
            } else {
                floats = EMPTY_FLOAT_SPACE;
                ox = 0;
                oy = 0;
            }
            box.floatSpace = null;
            this.childPercentBase = childPercentageBase(box, space.percentageBlockSize(), ctx);
            this.childCtx = FormattingContext.of(FormattingContext.Display.BLOCK, true, box.sizingMode == SizingMode.SHRINK_TO_FIT);
            this.collapseTop = collapsesThroughTop(box);
            this.pendingPos = 0;
            this.pendingNeg = 0;
            this.topPos = 0;
            this.topNeg = 0;
            this.hasContent = false;
            this.cursor = 0;
            this.firstRun = true;
            return this;
        }

        void clearRefs() {
            this.box = null;
            this.children = null;
            this.ctx = null;
            this.state = null;
            this.floats = null;
            this.childCtx = null;
        }

        private void addPending(float margin) {
            if (margin > 0) pendingPos = Math.max(pendingPos, margin);
            else pendingNeg = Math.min(pendingNeg, margin);
        }

        private void clearPending() {
            pendingPos = 0;
            pendingNeg = 0;
        }

        private float pendingResolve() {
            return pendingPos + pendingNeg;
        }

        private float pendingWith(float margin) {
            return (margin > 0 ? Math.max(pendingPos, margin) : pendingPos)
                    + (margin <= 0 ? Math.min(pendingNeg, margin) : pendingNeg);
        }

        private void addTop(float margin) {
            if (margin > 0) topPos = Math.max(topPos, margin);
            else topNeg = Math.min(topNeg, margin);
        }

        private float topWith(float margin) {
            return (margin > 0 ? Math.max(topPos, margin) : topPos)
                    + (margin <= 0 ? Math.min(topNeg, margin) : topNeg);
        }

        FlowState build() {
            state.innerW = innerW;
            List<LayoutBox> run = scratch.run;
            run.clear();
            for (int i = 0, n = children.size(); i < n; i++) {
                LayoutBox child = children.get(i);
                if ("none".equals(child.style.get(CssPropertyIndex.DISPLAY)) || child.isOutsideMarker || child.isRenderedLegend) continue;
                if (child.isAbsolute) {
                    // 行の中の絶対配置は、行を組むときにその位置を静的位置として記録する
                    if (!run.isEmpty()) run.add(child);
                    else state.items.add(new StaticPosition(child, 0, cursor + pendingResolve()));
                    continue;
                }
                if (isInlineBox(child) || (isFloating(child) && !run.isEmpty())) {
                    run.add(child);
                    continue;
                }
                if (!run.isEmpty()) flushRun(run);
                if (isFloating(child)) {
                    placeFloat(child, cursor + (hasContent || !collapseTop ? pendingResolve() : 0));
                } else {
                    placeBlock(child);
                }
            }
            if (!run.isEmpty()) flushRun(run);

            if (collapsesThroughBottom(box) && (hasContent || !collapseTop)) {
                box.mB = pendingWith(box.ownMarginBottom);
            } else {
                cursor += pendingResolve();
            }
            if (collapseTop) box.mT = topWith(box.ownMarginTop);

            float height = cursor;
            // BFC は自分の中の float を高さに含める（CSS 2.2 §10.6.7）。通常のブロックは含めない
            if (box.establishesBfc) height = Math.max(height, floats.bottom() - oy);
            state.height = height;
            return state;
        }

        private void placeBlock(LayoutBox child) {
            boolean intoParentTop = !hasContent && collapseTop;
            if (!child.establishesBfc && LayoutEngine.getEngine(child.display) instanceof BlockLayoutEngine
                    && (!floats.isEmpty() || containsFloats(child))) {
                // BFC を作らない子は同じ float 領域の中で組み直す（float を避け、自分の float を置く）。
                // 高さ 0 で自分を貫通する箱の中の float も親の領域に入る
                if (floats == EMPTY_FLOAT_SPACE) floats = new FloatSpace();
                float y = clearedY(child, intoParentTop ? cursor : cursor + pendingWith(child.mT));
                child.floatSpace = floats;
                child.floatSpaceX = ox + child.mL + child.bL + child.pL;
                child.floatSpaceY = oy + y + child.bT + child.pT;
                child.h = 0;
                BoxMeasurer.resolveFlexibleHeights(child, new ConstraintSpace(innerW, childPercentBase, childCtx), ctx);
            }
            if (collapsesThrough(child)) {
                // 上端の位置は「下にボーダーがあったら置かれた位置」（CSS 2.2 §8.3.1）
                float y;
                if (intoParentTop) {
                    addTop(child.mT);
                    addTop(child.mB);
                    y = cursor;
                } else {
                    y = cursor + pendingWith(child.mT);
                    addPending(child.mT);
                    addPending(child.mB);
                }
                state.items.add(new Placement(child, 0, y - child.mT));
                return;
            }

            float y;
            if (intoParentTop) {
                addTop(child.mT);
                y = cursor;
            } else {
                addPending(child.mT);
                y = cursor + pendingResolve();
            }
            clearPending();

            y = clearedY(child, y);

            float x = 0;
            if (child.establishesBfc && !floats.isEmpty()) {
                // BFC の border box は float と重ならない。横の残り幅に入らなければ float の下へ下げる
                boolean autoWidth = "auto".equals(child.style.getOrDefault(CssPropertyIndex.WIDTH, "auto").trim());
                for (int guard = 0; guard < 256; guard++) {
                    float left = floats.leftEdge(oy + y, child.h, ox) - ox;
                    float right = floats.rightEdge(oy + y, child.h, ox + innerW) - ox;
                    float available = Math.max(0, right - left);
                    if (autoWidth && Math.abs(child.w + child.mL + child.mR - available) > 0.01f) {
                        child.measure(new ConstraintSpace(available, childPercentBase, childCtx), ctx);
                    }
                    if (!floats.intrudes(oy + y, child.h) || autoWidth
                            || child.w + child.mL + child.mR <= available + 0.01f) {
                        x = left;
                        break;
                    }
                    float next = floats.nextBottom(oy + y);
                    if (Float.isInfinite(next)) break;
                    y = next - oy;
                }
            }

            if (floats.isEmpty()) x = startOffsetForDirection(child);
            state.items.add(new Placement(child, x, y - child.mT));
            cursor = y + child.h;
            addPending(child.mB);
            hasContent = true;
        }

        /**
         * {@code direction: rtl} のブロックの子の横位置（マージンボックスの左端、内容ボックス相対）。
         * 幅とマージンが決まって余りが出るとき（over-constrained、CSS 2.2 §10.3.3）、ltr は右のマージンが余りを受けて
         * 子は左に寄るが、rtl は左のマージンが受けて子は右に寄る。右マージンが {@code auto} なら余りはそちらが受ける。
         */
        private float startOffsetForDirection(LayoutBox child) {
            if (!rtl || child.isAbsolute) return 0;
            if ("auto".equals(child.style.getOrDefault(CssPropertyIndex.MARGIN_RIGHT, "").trim())) return 0;
            return Math.max(0, innerW - (child.w + child.mL + child.mR));
        }

        /** {@code clear} を持つ箱を、その側の float の下端まで下げた位置。 */
        private float clearedY(LayoutBox child, float y) {
            CssKeywords.Clear clear = child.style.keywordOf(CssPropertyIndex.CLEAR, CssKeywords.Clear.class);
            if (clear == CssKeywords.Clear.NONE || floats.isEmpty()) return y;
            return Math.max(y, floats.clearance(clear) - oy);
        }

        /** float を置き、領域に登録する。{@code minY} は置ける最も上の位置（内容ボックス相対）。 */
        void placeFloat(LayoutBox f, float minY) {
            if (floats == EMPTY_FLOAT_SPACE) floats = new FloatSpace();
            CssKeywords.FloatSide side = f.style.keywordOf(CssPropertyIndex.FLOAT, CssKeywords.FloatSide.class);
            boolean left = side != CssKeywords.FloatSide.RIGHT && side != CssKeywords.FloatSide.INLINE_END;
            float w = f.w + f.mL + f.mR;
            float h = f.h + f.mT + f.mB;
            // float の上端は先に置いた float より上にはならない（CSS 2.2 §9.5.1 の規則 5）
            float y = Math.max(oy + minY, floats.lastTop());
            CssKeywords.Clear clear = f.style.keywordOf(CssPropertyIndex.CLEAR, CssKeywords.Clear.class);
            if (clear != CssKeywords.Clear.NONE) y = Math.max(y, floats.clearance(clear));
            float x;
            for (int guard = 0; ; guard++) {
                float l = floats.leftEdge(y, h, ox);
                float r = floats.rightEdge(y, h, ox + innerW);
                float next = floats.nextBottom(y);
                if (r - l >= w - 0.01f || !floats.intrudes(y, h) || Float.isInfinite(next) || guard > 256) {
                    x = left ? l : r - w;
                    break;
                }
                y = next;
            }
            floats.add(left, x, y, w, h);
            state.items.add(new Placement(f, x - ox, y - oy));
        }

        private void flushRun(List<LayoutBox> run) {
            if (run.isEmpty()) return;
            float y;
            if (!hasContent && collapseTop) {
                topPos = Math.max(topPos, pendingPos);
                topNeg = Math.min(topNeg, pendingNeg);
                y = cursor;
            } else {
                y = cursor + pendingResolve();
            }
            clearPending();

            // text-indent は要素の最初の行だけ。匿名ブロックは持ち主の最初の子のときだけ字下げする
            float indent = 0;
            if (firstRun && (!box.isAnonymous || isFirstFlowChild(box))) {
                indent = box.style.length(ID_TEXT_INDENT, innerW, ctx.viewport().rootW(), ctx.viewport().rootH());
            }
            firstRun = false;

            // 組むための写しと LineRun が持つ写しは同じ中身で、どちらも読むだけ（build は引数を書き換えない）
            List<LayoutBox> boxes = run.size() == 1 ? List.of(run.get(0)) : List.copyOf(run);
            List<InlineLine> result;
            scratch.line.init(this, y, indent);
            try {
                result = scratch.line.build(boxes);
            } finally {
                scratch.line.clearRefs();
            }
            state.items.add(new LineRun(boxes, result));
            if (!result.isEmpty()) {
                InlineLine last = result.getLast();
                cursor = last.top() + last.height();
                hasContent = true;
            }
            run.clear();
        }
    }

    static boolean isFirstFlowChild(LayoutBox box) {
        if (box.parent == null) return true;
        for (LayoutBox c : box.parent.children) {
            if (c.isAbsolute || c.isOutsideMarker || c.isRenderedLegend || c.display.isNone()) continue;
            return c == box;
        }
        return false;
    }

    /**
     * 1 つの BFC の中の float の配置（BFC の内容ボックスの原点からの座標、マージンボックス）。
     * BFC を作らない子孫のブロックは同じ領域を共有する。
     */
    static final class FloatSpace {
        /** {x, y, right, bottom} */
        private static final List<float[]> NONE = List.of();
        /** 最初の追加まで共有の空の表（大半の箱は float を持たない）。 */
        private List<float[]> lefts = NONE;
        private List<float[]> rights = NONE;
        private float lastTop = Float.NEGATIVE_INFINITY;

        boolean isEmpty() {
            return lefts.isEmpty() && rights.isEmpty();
        }

        void add(boolean left, float x, float y, float w, float h) {
            if (left) {
                if (lefts == NONE) lefts = new ArrayList<>();
                lefts.add(new float[]{x, y, x + w, y + h});
            } else {
                if (rights == NONE) rights = new ArrayList<>();
                rights.add(new float[]{x, y, x + w, y + h});
            }
            lastTop = Math.max(lastTop, y);
        }

        float lastTop() {
            return lastTop;
        }

        /** 全 float の下端。無ければ負の無限大。 */
        float bottom() {
            float b = Float.NEGATIVE_INFINITY;
            for (float[] f : lefts) b = Math.max(b, f[3]);
            for (float[] f : rights) b = Math.max(b, f[3]);
            return b;
        }

        /** {@code clear} の値に対応する側の float の下端。 */
        float clearance(CssKeywords.Clear clear) {
            boolean clearLeft = clear == CssKeywords.Clear.LEFT || clear == CssKeywords.Clear.INLINE_START;
            boolean clearRight = clear == CssKeywords.Clear.RIGHT || clear == CssKeywords.Clear.INLINE_END;
            float b = Float.NEGATIVE_INFINITY;
            if (!clearRight) for (float[] f : lefts) b = Math.max(b, f[3]);
            if (!clearLeft) for (float[] f : rights) b = Math.max(b, f[3]);
            return b;
        }

        private static boolean overlaps(float[] f, float y, float h) {
            return f[1] < y + Math.max(h, 0.01f) && f[3] > y;
        }

        boolean intrudes(float y, float h) {
            for (float[] f : lefts) if (overlaps(f, y, h)) return true;
            for (float[] f : rights) if (overlaps(f, y, h)) return true;
            return false;
        }

        /** 帯 [y, y + h) で左の float を避けた左端。 */
        float leftEdge(float y, float h, float minX) {
            float x = minX;
            for (float[] f : lefts) if (overlaps(f, y, h)) x = Math.max(x, f[2]);
            return x;
        }

        /** 帯 [y, y + h) で右の float を避けた右端。 */
        float rightEdge(float y, float h, float maxX) {
            float x = maxX;
            for (float[] f : rights) if (overlaps(f, y, h)) x = Math.min(x, f[0]);
            return x;
        }

        /** {@code y} より下で、いずれかの float が終わる最も近い位置。無ければ正の無限大。 */
        float nextBottom(float y) {
            float next = Float.POSITIVE_INFINITY;
            for (float[] f : lefts) if (f[3] > y) next = Math.min(next, f[3]);
            for (float[] f : rights) if (f[3] > y) next = Math.min(next, f[3]);
            return next;
        }
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
    private record StaticPosition(LayoutBox box, float x, float y) {}

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
        if (synthesizesBaseline(box) || !(box.engineState instanceof FlowState state)) return null;
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
                || box.containsBlockSize();
    }

    /** 最後の行のベースライン（border box の上端から）。インラインブロックのベースラインになる。 */
    static Float lastBaseline(LayoutBox box) {
        if (synthesizesBaseline(box) || !(box.engineState instanceof FlowState state)) return null;
        for (int i = state.items.size() - 1; i >= 0; i--) {
            Object item = state.items.get(i);
            if (item instanceof Placement p && !isFloating(p.box())) {
                Float b = lastBaseline(p.box());
                if (b != null) return box.bT + box.pT + p.y() + p.box().mT + b;
            } else if (item instanceof LineRun run && !run.lines().isEmpty()) {
                InlineLine line = run.lines().getLast();
                return box.bT + box.pT + line.top() + line.baseline();
            }
        }
        return null;
    }

    private static Float baselineOf(LayoutBox box, Object item) {
        if (item instanceof Placement p && !isFloating(p.box())) {
            Float b = firstBaseline(p.box());
            return b != null ? box.bT + box.pT + p.y() + p.box().mT + b : null;
        }
        if (item instanceof LineRun run && !run.lines().isEmpty()) {
            InlineLine line = run.lines().getFirst();
            return box.bT + box.pT + line.top() + line.baseline();
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
    private static float atomicBaseline(LayoutBox box) {
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
        return "input".equals(box.node.tag) && "range".equalsIgnoreCase(box.node.attrs.getOrDefault("type", "").trim());
    }

    // ==========================================
    // インライン整形
    // ==========================================

    /** {@code vertical-align} の揃え方。 */
    private enum VAlign { BASELINE, TOP, BOTTOM }

    /** 行の中の 1 断片。{@code raise} はベースラインから持ち上げる量（px）。 */
    /**
     * 行の中の 1 断片（文字の語・不可分なインラインの箱）。座標は行の左端からの相対（{@code x}）で、{@link #placeLine} が行の位置と
     * 揃えを足して {@link InlineFragment} を作って置く。以前は行の相対座標も {@link InlineFragment} で持っており、
     * 語ごとに同じ大きさのオブジェクトが（行の記憶と置いた結果で）2 つずつ残っていた。
     */
    record SliceStyle(LayoutBox box, float runH, float runBaseline, float descent,
                      List<LayoutBox> parents, VAlign align, float raise) {}

    record LineItem(LayoutBox box, float x, float w, float h, String text, float baseline, float descent,
                    List<LayoutBox> parents, VAlign align, float raise) {}

    /**
     * 行分割の結果 1 行。座標は内容ボックス相対、{@code baseline} は行の上端から。
     * {@code used} は行の内容の幅（行の左端から、行末の空白を除く）で、{@code text-align} が読む。
     */
    static final class InlineLine {
        private List<LineItem> items;
        final List<BoxSpan> spans;
        final float top, left, width, height, baseline, used;
        final TextShaper.WrappedParagraph sliceWp;
        final int sliceStart, sliceEnd;
        /** スライス行が段落ごとに共有する、ランの寸法と揃え（行ごとに確保しない）。 */
        final SliceStyle slice;

        InlineLine(List<LineItem> items, List<BoxSpan> spans, float top, float left, float width,
                   float height, float baseline, float used) {
            this.items = items;
            this.spans = spans;
            this.top = top;
            this.left = left;
            this.width = width;
            this.height = height;
            this.baseline = baseline;
            this.used = used;
            this.sliceWp = null;
            this.sliceStart = 0;
            this.sliceEnd = 0;
            this.slice = null;
        }

        private InlineLine(TextShaper.WrappedParagraph sliceWp, int sliceStart, int sliceEnd,
                           SliceStyle slice,
                           float top, float left, float width, float height, float baseline, float used) {
            this.items = null;
            this.spans = List.of();
            this.top = top;
            this.left = left;
            this.width = width;
            this.height = height;
            this.baseline = baseline;
            this.used = used;
            this.sliceWp = sliceWp;
            this.sliceStart = sliceStart;
            this.sliceEnd = sliceEnd;
            this.slice = slice;
        }

        static InlineLine ofWrappedSlice(TextShaper.WrappedParagraph wp, int lineIdx, SliceStyle slice,
                                         float top, float left, float width, float height, float baseline) {
            return new InlineLine(wp, wp.lineStart(lineIdx), wp.lineStart(lineIdx + 1), slice,
                    top, left, width, height, baseline, wp.lineUsed(lineIdx));
        }

        List<LineItem> items() {
            List<LineItem> list = items;
            if (list == null && sliceWp != null) {
                int start = sliceStart, end = sliceEnd;
                list = new ArrayList<>(end - start);
                String[] segments = sliceWp.prepared().segs();
                SliceStyle st = slice;
                for (int k = start; k < end; k++) {
                    list.add(new LineItem(st.box(), sliceWp.itemX(k), sliceWp.advance(k), st.runH(),
                            segments[sliceWp.segIndex(k)], st.runBaseline(), st.descent(),
                            st.parents(), st.align(), st.raise()));
                }
                items = list;
            }
            return list;
        }

        List<BoxSpan> spans() { return spans; }
        float top() { return top; }
        float left() { return left; }
        float width() { return width; }
        float height() { return height; }
        float baseline() { return baseline; }
        float used() { return used; }
    }

    /**
     * 行の上で開いている（開始タグを過ぎて終了タグに達していない）インラインボックス。
     * 行をまたぐと、次の行の頭から続きの断片を始める。
     */
    private static final class OpenBox {
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

    /**
     * インライン内容を行に分割する。float を避けて各行の左端と幅を決め、行の中の float を置く。
     *
     * インラインボックス（{@code <span>} など）は開始タグと終了タグで 1 回ずつ装飾を足す（CSS Inline 3 /
     * {@code box-decoration-break: slice}）。開始端の margin + border + padding は最初の断片、終了端は最後の断片だけが
     * 持ち、同じ行の中身は 1 つの断片にまとめる。
     *
     * 何が壊れていたか: 以前は祖先の装飾を断片（語）ごとに足していた。{@code <span style="padding: 4px">Hello world</span>}
     * は語ごとに左右の padding が付いて背景が重なり、行を折り返すたびに開始端の装飾も足されていた。
     */
    private static final class LineBuilder {
        final BlockScratch scratch;
        FlowBuilder flow;
        LayoutContext ctx;
        InlineLine firstLine;
        List<InlineLine> lines;
        float strutHeight, strutBaseline;
        List<LineItem> current = null;
        List<BoxSpan> spans = null;
        List<OpenBox> open = null;
        float lineTop;
        float lineLeft, lineWidth;
        /** 行の左端からの進み位置。float のまま持つ（断片ごとの切り上げで誤差が溜まっていた）。 */
        float lineX;
        /** 最後に置いた語の行末の空白の幅。行を閉じるとき内容の幅から除く。 */
        float trailingSpace;
        float indent;
        boolean lineStarted = false;
        List<LayoutBox> deferredFloats = null;

        LineBuilder(BlockScratch scratch) {
            this.scratch = scratch;
        }

        LineBuilder init(FlowBuilder flow, float startY, float indent) {
            this.flow = flow;
            this.ctx = flow.ctx;
            this.lineTop = startY;
            this.indent = indent;
            this.firstLine = null;
            this.lines = null;
            if (this.current != null) this.current.clear();
            this.spans = null;
            if (this.open != null) this.open.clear();
            if (this.deferredFloats != null) this.deferredFloats.clear();
            this.lineLeft = 0;
            this.lineWidth = 0;
            this.lineX = 0;
            this.trailingSpace = 0;
            this.lineStarted = false;
            // 行ボックスの strut: 内容の無い行でも、ブロックの書体と line-height の高さを持つ（CSS 2.2 §10.8.1）
            TextStyle strut = ctx.textStyleFor(flow.box.style);
            this.strutHeight = strut.lineHeightPx();
            this.strutBaseline = baselineWithin(strut, ctx.faceFor(flow.box.style));
            return this;
        }

        void clearRefs() {
            this.flow = null;
            this.ctx = null;
            this.firstLine = null;
            this.lines = null;
            this.spans = null;
            if (this.open != null) this.open.clear();
            if (this.deferredFloats != null) this.deferredFloats.clear();
            if (this.current != null) {
                if (this.current.size() > 64) this.current = null;
                else this.current.clear();
            }
        }

        private void appendLine(InlineLine line) {
            if (lines != null) {
                lines.add(line);
            } else if (firstLine == null) {
                firstLine = line;
            } else {
                lines = new ArrayList<>(4);
                lines.add(firstLine);
                lines.add(line);
                firstLine = null;
            }
        }

        private List<LineItem> ensureCurrent() {
            if (current == null) current = new ArrayList<>();
            return current;
        }

        private List<LineItem> ensureCurrent(int minCapacity) {
            if (current == null) current = new ArrayList<>(minCapacity);
            else if (current instanceof ArrayList<LineItem> al) al.ensureCapacity(minCapacity);
            return current;
        }

        private boolean currentEmpty() {
            return current == null || current.isEmpty();
        }

        List<InlineLine> build(List<LayoutBox> inlines) {
            if (inlines.size() == 1) {
                LayoutBox only = inlines.get(0);
                if (!only.display.isNone() && !only.isAbsolute && !isFloating(only)
                        && only.node.nodeType == NodeType.TEXT) {
                    if (only.displayText != null && !only.displayText.isEmpty()) {
                        addText(only, only.displayText, List.of(), 0f, true);
                    }
                    if (!currentEmpty() || anyDecorated(spans)) newLine(0, 0, false);
                    flushDeferredFloats();
                    if (lines != null) return lines;
                    return firstLine != null ? List.of(firstLine) : List.of();
                }
            }
            List<FlatRun> runs = scratch.runs;
            List<LayoutBox> parents = scratch.parents;
            List<LayoutBox>[] snapshot = scratch.snapshot;
            runs.clear();
            parents.clear();
            snapshot[0] = null;
            try {
                flattenInlines(inlines, runs, parents, snapshot);
                for (int i = 0, runCount = runs.size(); i < runCount; i++) {
                    FlatRun run = runs.get(i);
                    switch (run.kind) {
                        case TEXT -> addText(run.box, run.text, run.inlineParents, closingEdges(runs, i), i == runCount - 1);
                        case BREAK -> addBreak(run);
                        case ATOMIC -> addAtomic(run, closingEdges(runs, i));
                        case FLOAT -> addFloat(run.box);
                        case ABSOLUTE -> {
                            ensureLine();
                            flow.state.items.add(new StaticPosition(run.box, lineLeft + lineX, lineTop));
                        }
                        case OPEN -> openBox(run);
                        case CLOSE -> closeBox();
                    }
                }
            } finally {
                runs.clear();
                parents.clear();
                snapshot[0] = null;
            }
            if (!currentEmpty() || anyDecorated(spans)) newLine(0, 0, false);
            flushDeferredFloats();
            if (lines != null) return lines;
            return firstLine != null ? List.of(firstLine) : List.of();
        }

        private void flushDeferredFloats() {
            if (deferredFloats == null || deferredFloats.isEmpty()) return;
            for (int i = 0, n = deferredFloats.size(); i < n; i++) {
                flow.placeFloat(deferredFloats.get(i), lineTop);
            }
            deferredFloats.clear();
        }

        private boolean anyDecorated(List<BoxSpan> list) {
            if (list == null || list.isEmpty()) return false;
            for (int i = 0, n = list.size(); i < n; i++) {
                if (isDecorated(list.get(i).open().box)) return true;
            }
            return false;
        }

        private void addSpan(BoxSpan span) {
            if (spans == null) spans = new ArrayList<>(4);
            spans.add(span);
        }

        /** {@code runs[i]} の直後に閉じるインラインボックスの終了端の装飾。最後の語と一緒に行に入らないといけない。 */
        private float closingEdges(List<FlatRun> runs, int i) {
            float w = 0;
            for (int j = i + 1; j < runs.size() && runs.get(j).kind == FlatRun.Kind.CLOSE; j++) {
                LayoutBox b = runs.get(j).box;
                w += b.pR + b.bR + b.mR;
            }
            return w;
        }

        private boolean constrained() {
            return flow.innerW > 0;
        }

        private void ensureLine() {
            if (lineStarted) return;
            flushDeferredFloats();
            computeBand();
            lineX = indent;
            indent = 0;
            trailingSpace = 0;
            lineStarted = true;
            // 前の行から続く箱は行の頭から断片を始める。中身を置く前に折り返した箱は開始端を付け直す
            if (open != null) {
                for (int i = 0, n = open.size(); i < n; i++) {
                    OpenBox ob = open.get(i);
                    ob.marginStart = lineX;
                    if (ob.reopen) {
                        lineX += ob.box.mL;
                        ob.start = lineX;
                        lineX += ob.box.bL + ob.box.pL;
                        ob.reopen = false;
                    } else {
                        ob.start = lineX;
                    }
                }
            }
        }

        /** 行の上端で float を避けた左端と幅。 */
        private void computeBand() {
            FloatSpace fs = flow.floats;
            if (fs.isEmpty()) {
                lineLeft = 0;
                lineWidth = flow.innerW;
                return;
            }
            float y = flow.oy + lineTop;
            float l = fs.leftEdge(y, strutHeight, flow.ox) - flow.ox;
            float r = fs.rightEdge(y, strutHeight, flow.ox + flow.innerW) - flow.ox;
            lineLeft = l;
            lineWidth = Math.max(0, r - l);
        }

        /** 空の行に幅 {@code w} の内容が入らず、float が幅を狭めているなら、float の下まで行を下げる。 */
        private void dropBelowFloats(float w) {
            FloatSpace fs = flow.floats;
            if (!constrained() || fs.isEmpty() || !currentEmpty()) return;
            for (int guard = 0; guard < 256 && w > lineWidth + 0.01f && lineWidth < flow.innerW; guard++) {
                float next = fs.nextBottom(flow.oy + lineTop);
                if (Float.isInfinite(next)) return;
                flow.state.wrapped = true;
                lineTop = next - flow.oy;
                computeBand();
            }
        }

        /**
         * 行を閉じる。開いている箱はこの行の断片を閉じて次の行へ続ける。
         * {@code wrapped}（自動の折り返し）なら、まだ中身を置いていない箱は開始端ごと次の行へ移す。
         */
        private void newLine(float fallbackHeight, float fallbackBaseline, boolean wrapped) {
            int openCount = open != null ? open.size() : 0;
            int carried = openCount;
            if (wrapped) {
                flow.state.wrapped = true;
                // 中身の無い箱は開いている箱の末尾に並ぶ（中の箱に中身があれば外の箱にもある）
                while (carried > 0 && open.get(carried - 1).startEdge && !open.get(carried - 1).hasContent) carried--;
            }
            float end = (carried < openCount ? open.get(carried).marginStart : lineX) - trailingSpace;
            for (int i = 0; i < openCount; i++) {
                OpenBox ob = open.get(i);
                if (i < carried) {
                    addSpan(new BoxSpan(ob, ob.start, Math.max(ob.start, end), ob.startEdge, false));
                    ob.startEdge = false;
                } else {
                    ob.reopen = true;
                }
                ob.hasContent = false;
            }
            InlineLine line = finishLine(fallbackHeight, fallbackBaseline, end);
            appendLine(line);
            lineTop += line.height();
            current = null;
            spans = null;
            lineStarted = false;
        }

        /** 開いている箱に中身が置かれた。 */
        private void markContent() {
            if (open == null) return;
            for (int i = 0, n = open.size(); i < n; i++) open.get(i).hasContent = true;
        }

        private void openBox(FlatRun run) {
            ensureLine();
            LayoutBox b = run.box;
            FontFace face = ctx.faceFor(b.style);
            FontStyle font = ctx.fontStyleFor(b.style);
            float ascent = face.ascent(font);
            OpenBox ob = new OpenBox(b, alignmentOf(b, run.inlineParents, ascent + face.descent(font), ascent));
            ob.marginStart = lineX;
            lineX += b.mL;
            ob.start = lineX;
            lineX += b.bL + b.pL;
            if (open == null) open = new ArrayList<>(2);
            open.add(ob);
        }

        private void closeBox() {
            ensureLine();
            OpenBox ob = open.removeLast();
            lineX += ob.box.pR + ob.box.bR;
            addSpan(new BoxSpan(ob, ob.start, lineX, ob.startEdge, true));
            lineX += ob.box.mR;
            trailingSpace = 0;
            markContent();
        }

        private void addText(LayoutBox runBox, String runText, List<LayoutBox> inlineParents, float closing, boolean isLastRun) {
            TextStyle textStyle = ctx.textStyleFor(runBox.style);
            FontFace face = ctx.faceFor(runBox.style);
            FontStyle font = textStyle.font();
            float runH = textStyle.lineHeightPx();
            float baseline = baselineWithin(textStyle, face);
            float descent = face.descent(font);
            Alignment alignment = inlineParents.isEmpty()
                    ? ALIGNMENT_BASELINE_ZERO
                    : alignmentOf(null, inlineParents, runH, baseline);

            boolean preserveNewlines = textStyle.whiteSpace().preservesNewlines();
            if (flow.floats.isEmpty() && (deferredFloats == null || deferredFloats.isEmpty())
                    && (open == null || open.isEmpty()) && currentEmpty() && !lineStarted && lineX == 0 && indent == 0
                    && closing == 0 && textStyle.whiteSpace().wraps() && constrained()
                    && (!preserveNewlines || runText.indexOf('\n') < 0)) {
                TextShaper.WrappedParagraph wp = runBox.cachedWrappedParagraph(
                        runText, face, font, textStyle.wordBreak(), flow.innerW);
                int lineCount = wp.lineCount();
                if (lineCount > 1) flow.state.wrapped = true;
                float lineBaseline;
                float lineHeight;
                if (alignment.align == VAlign.BASELINE) {
                    lineBaseline = Math.max(strutBaseline, baseline + alignment.raise);
                    float belowBaseline = Math.max(strutHeight - strutBaseline, runH - baseline - alignment.raise);
                    lineHeight = lineBaseline + belowBaseline;
                } else {
                    lineBaseline = strutBaseline;
                    lineHeight = Math.max(strutHeight, runH);
                }
                VAlign align = alignment.align;
                float raise = alignment.raise;

                int directLines = isLastRun ? lineCount : (lineCount - 1);
                SliceStyle sliceStyle = directLines > 0
                        ? new SliceStyle(runBox, runH, baseline, descent, inlineParents, align, raise) : null;
                for (int l = 0; l < directLines; l++) {
                    appendLine(InlineLine.ofWrappedSlice(wp, l, sliceStyle, lineTop, 0f, flow.innerW, lineHeight, lineBaseline));
                    lineTop += lineHeight;
                }
                if (isLastRun) {
                    return;
                }

                String[] segments = wp.prepared().segs();
                int start = wp.lineStart(lineCount - 1);
                int end = wp.lineStart(lineCount);
                lineLeft = 0;
                lineWidth = flow.innerW;
                lineStarted = true;
                List<LineItem> cur = ensureCurrent(Math.max(end - start, 1));
                for (int k = start; k < end; k++) {
                    cur.add(new LineItem(runBox, wp.itemX(k), wp.advance(k), runH,
                            segments[wp.segIndex(k)], baseline, descent, inlineParents, align, raise));
                }
                lineX = wp.finalLineX();
                trailingSpace = wp.finalTrailingSpace();
                markContent();
                return;
            }

            ensureLine();
            // white-space が改行を残す（pre / pre-wrap / pre-line）なら原文の改行で行を割る。
            // 以前は改行が区切りとして扱われず、<pre> の中身が 1 行に潰れていた。
            if (!preserveNewlines || runText.indexOf('\n') < 0) {
                addParagraph(runBox, inlineParents, face, font, textStyle, alignment, runH, baseline, descent, runText, closing, true);
                return;
            }
            String[] paragraphs = runText.split("\n", -1);
            for (int p = 0; p < paragraphs.length; p++) {
                if (p > 0) {
                    markContent();
                    newLine(runH, baseline, false);
                    ensureLine();
                }
                addParagraph(runBox, inlineParents, face, font, textStyle, alignment, runH, baseline, descent, paragraphs[p],
                        p == paragraphs.length - 1 ? closing : 0, false);
            }
        }

        private void addParagraph(LayoutBox runBox, List<LayoutBox> inlineParents, FontFace face, FontStyle font, TextStyle textStyle,
                                  Alignment alignment, float runH, float baseline, float descent,
                                  String paragraph, float closing, boolean cacheOnBox) {
            // 断片・幅・境目の詰めは文字列・書体・折り返しの規則だけで決まるので、同じ段落なら測り直さない（幅にも依らない）
            ParagraphSegments prepared = cacheOnBox
                    ? runBox.cachedSegments(paragraph, face, font, textStyle.wordBreak())
                    : TextShaper.segmented(paragraph, face, font, textStyle.wordBreak());
            String[] segments = prepared.segs();
            // 入るかどうかは語の幅の和に境目の詰め（TextShaper.kerningBetween）を足した幅、つまり続けて測った幅で
            // 見る（TextShaper と同じ。max-content もこの測り方）。kerned はこの run が今の行で詰めた量の合計。
            // 何が壊れていたか: 和だけで見ていたため、語の境目に詰め（- と v など）があると max-content ちょうどの
            // flex アイテムで "two-viewers" が "two-" の後で折り返していた（実機で確認）。
            String previousOnLine = null;
            float kerned = 0;
            int lastSegment = segments.length - 1;
            while (lastSegment >= 0 && segments[lastSegment].isEmpty()) lastSegment--;
            for (int s = 0; s < segments.length; s++) {
                String segment = segments[s];
                if (segment.isEmpty()) continue;
                float advance = prepared.fullW(s);
                // 行末の空白は幅に数えない（CSS と同じ）
                float trimmedW = prepared.strippedTrailingW(s);
                // 最後の語は、直後に閉じる箱の終了端の装飾と一緒に行に入らないといけない
                float edges = s == lastSegment ? closing : 0;

                // 直前の断片との境目の詰め（直前が断片の並びの隣なら下ごしらえの値、空の断片を挟んだときだけ測る）
                float join = previousOnLine == null ? 0
                        : s > 0 && previousOnLine == segments[s - 1] ? prepared.joinW(s)
                        : TextShaper.kerningBetween(face, font, previousOnLine, segment);
                if (textStyle.whiteSpace().wraps() && !segment.isBlank()) {
                    if (needsWrap(lineX + kerned + join + trimmedW + edges)) {
                        newLine(runH, baseline, true);
                        ensureLine();
                        kerned = 0;
                        join = 0;
                    }
                    dropBelowFloats(lineX + kerned + join + trimmedW + edges);
                }
                kerned += join;
                previousOnLine = segment;
                // 断片の座標も float のまま持つ。丸めるのは描画側の 1 箇所だけ
                ensureCurrent().add(new LineItem(runBox, lineX, advance, runH, segment, baseline, descent, inlineParents,
                        alignment.align, alignment.raise));
                lineX += advance;
                trailingSpace = advance - trimmedW;
                markContent();
            }
        }

        private void addBreak(FlatRun run) {
            // <br>: 内容が無い行でもフォント 1 行分の高さを確保する
            TextStyle brStyle = ctx.textStyleFor(run.box.style);
            ensureLine();
            markContent();
            newLine(brStyle.lineHeightPx(), baselineWithin(brStyle, ctx.faceFor(run.box.style)), false);
        }

        private void addAtomic(FlatRun run, float closing) {
            LayoutBox b = run.box;
            float boxOuterW = b.w + b.mL + b.mR;
            float baseline = atomicBaseline(b);
            Alignment alignment = alignmentOf(b, run.inlineParents, b.mT + b.mB + b.h, b.mT + baseline);

            ensureLine();
            // 折り返せるかは不可分なインラインを含む側（親）の white-space で決まる。
            // 以前は見ておらず、nowrap の行でもインラインブロックが折り返していた
            ComputedStyle around = run.inlineParents.isEmpty() ? flow.box.style : run.inlineParents.getLast().style;
            if (WhiteSpace.of(around.get(CssPropertyIndex.WHITE_SPACE)).wraps() && needsWrap(lineX + boxOuterW + closing)) {
                newLine(0, 0, true);
                ensureLine();
            }
            dropBelowFloats(lineX + boxOuterW + closing);
            ensureCurrent().add(new LineItem(b, lineX + b.mL, b.w, b.h, null, baseline, 0, run.inlineParents, alignment.align, alignment.raise));
            lineX += boxOuterW;
            trailingSpace = 0;
            markContent();
        }

        /** 行の中の float。今の行に入るなら行の上端に置き、入らなければ次の行の上端に置く。 */
        private void addFloat(LayoutBox f) {
            float w = f.w + f.mL + f.mR;
            if (!lineStarted || currentEmpty() || w <= lineWidth - lineX + 0.01f) {
                flow.placeFloat(f, lineTop);
                if (lineStarted) computeBand();
            } else {
                if (deferredFloats == null) deferredFloats = new ArrayList<>(2);
                deferredFloats.add(f);
            }
        }

        /**
         * 折り返すかどうか。
         * 幅の制約が無いとき（内容ボックスの幅が 0）と、行にまだ何も無いときは折り返さない。
         *
         * 何が壊れていたか: 以前は 4px の余裕（INLINE_WRAP_SLOP）を足していた。
         * 測定が幅を切り上げ、描画が別の基準で測り直していたぶんを吸収するためのものだったが、
         * 測定と描画が同じ送り幅を見るようになったので余裕は不要になり、
         * 代わりに「ブラウザなら折り返す幅で折り返さない」差を生むだけになっていた。
         */
        private boolean needsWrap(float lineEnd) {
            return constrained() && !currentEmpty() && lineEnd > lineWidth + TextShaper.WRAP_TOLERANCE;
        }

        /**
         * 行を確定する。高さとベースラインは断片の実寸と strut から求め、
         * 断片が無い行は渡された fallback を使う（装飾を持つインラインボックスだけの行は strut の高さ）。
         *
         * インラインボックスの上下の padding・border・margin は行の高さに入らない（CSS 2.2 §10.8.1）。
         * 何が壊れていたか: 以前は祖先の上下の装飾を断片ごとに足していて、{@code <code>} の padding が行を押し広げていた。
         */
        private InlineLine finishLine(float fallbackHeight, float fallbackBaseline, float used) {
            List<BoxSpan> outSpans = spans == null || spans.isEmpty() ? List.of() : spans;
            if (currentEmpty()) {
                boolean decorated = fallbackHeight == 0 && anyDecorated(outSpans);
                return new InlineLine(List.of(), outSpans, lineTop, lineLeft, lineWidth,
                        decorated ? strutHeight : fallbackHeight, decorated ? strutBaseline : fallbackBaseline, used);
            }
            float baseline = strutBaseline;
            float belowBaseline = strutHeight - strutBaseline;
            boolean hasNonBaseline = false;
            for (int i = 0, n = current.size(); i < n; i++) {
                LineItem item = current.get(i);
                if (item.align() != VAlign.BASELINE) {
                    hasNonBaseline = true;
                    continue;
                }
                baseline = Math.max(baseline, item.box().mT + item.baseline() + item.raise());
                belowBaseline = Math.max(belowBaseline, item.h() - item.baseline() + item.box().mB - item.raise());
            }
            float height = baseline + belowBaseline;
            // top / bottom は行ボックスの端に揃える。ベースラインで揃えた内容より高ければ行を広げる
            if (hasNonBaseline) {
                for (int i = 0, n = current.size(); i < n; i++) {
                    LineItem item = current.get(i);
                    if (item.align() == VAlign.BASELINE) continue;
                    height = Math.max(height, outerHeight(item));
                }
            }
            List<LineItem> outItems;
            if (current.size() == 1) {
                outItems = List.of(current.get(0));
                current.clear();
            } else {
                outItems = current;
                current = null;
            }
            return new InlineLine(outItems, outSpans, lineTop, lineLeft, lineWidth, height, baseline, used);
        }

        /**
         * {@code vertical-align} の揃え方と持ち上げる量。{@code self} と {@code inlineParents} を内側から外側へ辿り、
         * 入れ子の span の持ち上げを累積する。すべて既定の {@code baseline} なら {@link #ALIGNMENT_BASELINE_ZERO} を返す。
         */
        private Alignment alignmentOf(LayoutBox self, List<LayoutBox> inlineParents, float height, float above) {
            float raise = 0;
            VAlign align = VAlign.BASELINE;
            if (self != null) {
                String v = self.style.keyword(CssPropertyIndex.VERTICAL_ALIGN, "baseline");
                if (!"baseline".equals(v)) {
                    switch (v) {
                        case "top" -> align = VAlign.TOP;
                        case "bottom" -> align = VAlign.BOTTOM;
                        default -> raise += resolveVerticalAlignRaise(self, v, height, above);
                    }
                }
            }
            for (int i = inlineParents.size() - 1; i >= 0; i--) {
                LayoutBox el = inlineParents.get(i);
                String v = el.style.keyword(CssPropertyIndex.VERTICAL_ALIGN, "baseline");
                if ("baseline".equals(v)) continue;
                switch (v) {
                    case "top" -> align = VAlign.TOP;
                    case "bottom" -> align = VAlign.BOTTOM;
                    default -> raise += resolveVerticalAlignRaise(el, v, height, above);
                }
            }
            return align == VAlign.BASELINE && raise == 0 ? ALIGNMENT_BASELINE_ZERO : new Alignment(align, raise);
        }

        private float resolveVerticalAlignRaise(LayoutBox el, String v, float height, float above) {
            LayoutBox parentBox = el.parent != null ? el.parent : flow.box;
            FontFace face = ctx.faceFor(parentBox.style);
            FontStyle font = ctx.fontStyleFor(parentBox.style);
            float parentFont = parentBox.style.fontSizePx();
            return switch (v) {
                // 中央を親のベースライン + x ハイトの半分に置く
                case "middle" -> height / 2 + face.xHeight(font) / 2 - above;
                // Chromium と同じ近似（下付き: 1/5 em + 1、上付き: 1/3 em + 1）
                case "sub" -> -(parentFont / 5 + 1);
                case "super" -> parentFont / 3 + 1;
                case "text-top" -> face.ascent(font) - above;
                case "text-bottom" -> (height - above) - face.descent(font);
                default -> el.style.length(CssPropertyIndex.VERTICAL_ALIGN, el.lineHeightPx,
                        ctx.viewport().rootW(), ctx.viewport().rootH());
            };
        }
    }

    private static final Alignment ALIGNMENT_BASELINE_ZERO = new Alignment(VAlign.BASELINE, 0);

    private record Alignment(VAlign align, float raise) {}

    /** 行を空にしない装飾（margin・border・padding）を持つインラインボックスか（CSS 2.2 §9.4.2）。 */
    private static boolean isDecorated(LayoutBox b) {
        return b.mL != 0 || b.mR != 0 || b.horizontalDecoration() != 0 || b.verticalDecoration() != 0;
    }

    /** 断片のマージンを含む高さ。 */
    private static float outerHeight(LineItem f) {
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
                            LayoutContext ctx) {
        if (line.sliceWp != null) {
            int start = line.sliceStart, end = line.sliceEnd;
            if (start == end) return Float.NEGATIVE_INFINITY;
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
            for (int k = start; k < end; k++) {
                String text = segments[wp.segIndex(k)];
                box.addFragment(wp.itemX(k) + offsetX, fragY, wp.advance(k), runH, text,
                        runBase, runDesc, parents, true, true);
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

        for (int i = 0, n = items.size(); i < n; i++) {
            LineItem src = items.get(i);
            // マージンボックスの上端
            float top = switch (src.align()) {
                case TOP -> drawY;
                case BOTTOM -> drawY + line.height() - outerHeight(src);
                default -> drawY + line.baseline() - (src.box().mT + src.baseline() + src.raise());
            };

            InlineFragment f = src.box().addFragment(src.x() + offsetX, top + src.box().mT, src.w(), src.h(), src.text(),
                    src.baseline(), src.descent(), src.parents(), true, true);
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
            placeSpan(lineSpans.get(i), offsetX, drawY, line, ctx);
        }
        return textBottom;
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
    private static float baselineWithin(TextStyle textStyle, FontFace face) {
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
    private static void flattenInlines(List<LayoutBox> boxes, List<FlatRun> runs, List<LayoutBox> parents, List<LayoutBox>[] snapshot) {
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

    private static void clearFragmentsRecursively(LayoutBox box) {
        box.startFragmentPass();
        // テキストの箱は断片から位置を取らない（fitToFragments が飛ばす）ので、祖先の相対配置・sticky のずれだけが乗る。
        // 原点に戻してから足し直させないと、同じ木を配置し直すたびにずれが積み重なる
        if (box.node.nodeType == NodeType.TEXT) {
            box.x = 0;
            box.y = 0;
        }
        List<LayoutBox> children = box.children;
        for (int i = 0, n = children.size(); i < n; i++) {
            LayoutBox child = children.get(i);
            // 配置を使い回せる箱（原子的インラインの中身など）は、自分の layout が置き直すか前回の配置を残すかを決める。
            // ここで断片だけを消すと、残す側で断片が消えたままになる
            if (child.holdsPlacement()) continue;
            clearFragmentsRecursively(child);
        }
    }

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
    private record FlatRun(Kind kind, LayoutBox box, String text, List<LayoutBox> inlineParents) {
        enum Kind { TEXT, BREAK, ATOMIC, FLOAT, ABSOLUTE, OPEN, CLOSE }
    }
}