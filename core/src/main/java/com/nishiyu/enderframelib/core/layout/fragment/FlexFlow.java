package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxKind;
import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.css.CssLength;
import com.nishiyu.enderframelib.core.css.FlexGridStyle;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.css.model.CssKeywords.FlexWrap;
import com.nishiyu.enderframelib.core.kernel.LayoutUnit;

import java.util.ArrayList;
import java.util.List;

/**
 * フレックスレイアウトの入口（Engine v3 M2a・M2b-2）。容器の大きさを決め、項目を集め、行の解決（{@link FlexLines}）の結果を
 * 断片にする。{@code row} / {@code column}・{@code *-reverse}・{@code flex-wrap}（{@code wrap-reverse}）・{@code align-content}・
 * 柔軟な長さ・自動の最小サイズ・{@code align-items/self}（ベースライン揃えを含む）・主軸と交差軸の auto 余白・
 * {@code justify-content}（safe を含む）・{@code gap}・{@code order}・{@code visibility: collapse}・容器のベースライン・
 * abspos の子の静的位置の揃え（§4.1）。
 *
 * <p>項目は <b>pass 1</b>（主軸の大きさを決めて交差軸は内容から）と <b>pass 2</b>（stretch・確定した大きさで測り直す）の
 * 異なる制約で測られ得る。1 回の整形の間に使った断片は項目ごとの {@link LayoutCache.Slots} に集め、結果の
 * {@link FlowChunk#slots} に残すので、次のフレームは両方とも当たる（入れ子でも指数的に増えない）。
 * 基準の大きさ・自動の最小サイズは {@link LayoutCache#minMax}（内在サイズの 1 層メモ）から取り、仮のレイアウトをしない。</p>
 */
final class FlexFlow implements FormattingEngine {
    static final FlexFlow INSTANCE = new FlexFlow();

    private FlexFlow() { }

    @Override
    public LayoutFragment layout(BoxNode node, ConstraintSpace space, LayoutFragment prior, LayoutSession s) {
        Style st = node.style;
        float cbw = space.availableWidth;
        BoxGeometry g = new BoxGeometry(st, cbw, s);
        float w, ml = g.mL;
        if (!Float.isNaN(space.fixedWidth)) {
            w = space.fixedWidth;
        } else {
            float spec = g.specifiedWidth(node, cbw, cbw - g.mL - g.mR, s);
            w = g.clampWidth(st, Float.isNaN(spec) ? Math.max(0, cbw - g.mL - g.mR) : spec, cbw, s);
            if (g.autoML && g.autoMR) ml = Math.max(0, (cbw - w) / 2f);
            else if (g.autoML) ml = Math.max(0, cbw - w - g.mR);
        }
        w = LayoutUnit.snap(w);
        float cw = Math.max(0, w - g.hdec());
        float fixedH = !Float.isNaN(space.fixedHeight) ? space.fixedHeight
                : space.contentBlockSize() ? Float.NaN : g.specifiedHeight(st, space.percentHeightBase, s);
        BoxNode legend = FieldsetLegend.of(node);
        FieldsetLegend.Placed lp = legend == null ? null : FieldsetLegend.place(legend, g, cw, prior, s);
        float spill = lp == null ? 0 : lp.spill();
        float ch = Float.isNaN(fixedH) ? Float.NaN : Math.max(0, fixedH - g.vdec() - spill);
        // 高さが auto なら、内容の高さを min/max-height で挟む（内容だけを測る pass 1 では挟まない）
        boolean clampH = Float.isNaN(ch) && !space.contentBlockSize();
        float minCH = clampH ? g.clampHeight(st, 0, space.percentHeightBase, s) - g.vdec() : Float.isNaN(ch) ? 0 : ch;
        float maxCH = clampH ? g.clampHeight(st, Float.MAX_VALUE / 4, space.percentHeightBase, s) - g.vdec()
                : Float.isNaN(ch) ? Float.MAX_VALUE / 4 : ch;

        FlexLines fl = new FlexLines(st, cw, ch, minCH, maxCH, s);
        List<BoxNode> outOfFlow = new ArrayList<>();
        List<FlexItem> items = items(node, legend, cw, prior, outOfFlow, fl.row(), s);
        float contentH = LayoutUnit.snap(fl.run(items));
        fl.mirror(items);
        float insetL = g.bL + g.pL, insetT = g.bT + g.pT + spill;

        int n = items.size();
        LayoutFragment[] frags = new LayoutFragment[n];
        float[] xs = new float[n], ys = new float[n];
        LayoutFragment[][] slots = null;
        List<OutOfFlow.Pending> pend = new ArrayList<>();
        for (BoxNode b : outOfFlow) { // 静的位置: 内容ボックスを領域に、唯一の項目として揃える（§4.1）
            float mainF = fl.staticMainFactor(), crossF = fl.staticCrossFactor(b);
            pend.add(new OutOfFlow.Pending(b, insetL, insetT, cw, contentH, fl.row() ? mainF : crossF, fl.row() ? crossF : mainF));
        }
        for (int i = 0; i < n; i++) {
            FlexItem it = items.get(i);
            frags[i] = it.frag;
            float x = fl.row() ? it.mainPos : it.crossPos, y = fl.row() ? it.crossPos : it.mainPos;
            float[] rel = OutOfFlow.relativeOffset(it.box.style, cw, ch, s);
            xs[i] = LayoutUnit.snap(x + (rel != null ? rel[0] : 0));
            ys[i] = LayoutUnit.snap(y + (rel != null ? rel[1] : 0));
            if (it.frag.pending != null) for (OutOfFlow.Pending p : it.frag.pending) pend.add(p.shift(insetL + xs[i], insetT + ys[i]));
            LayoutFragment[] used = it.slots.used(it.frag);
            if (used != null) {
                if (slots == null) slots = new LayoutFragment[n][];
                slots[i] = used;
            }
        }
        FlowChunk fc = new FlowChunk(null, null, frags, xs, ys, contentH, 0, 0, 0, 0, false, slots, null, 0);
        float h = Float.isNaN(fixedH) ? contentH + g.vdec() + spill : fixedH;
        if (Float.isNaN(space.fixedHeight) && !space.contentBlockSize()) h = g.clampHeight(st, h, space.percentHeightBase, s);
        h = LayoutUnit.snap(h);
        OutOfFlow.Pending[] up = null;
        PlacedFragment[] oof = null;
        if (!pend.isEmpty()) {
            if (OutOfFlow.containsOutOfFlow(st)) {
                oof = OutOfFlow.layout(pend, g.bL, g.bT, Math.max(0, w - g.bL - g.bR), Math.max(0, h - g.bT - g.bB), prior, s);
            } else {
                up = pend.toArray(OutOfFlow.NONE);
            }
        }
        if (lp != null) { // fieldset の描画される legend は置いた断片として持つ（FieldsetLegend）
            PlacedFragment[] all = new PlacedFragment[1 + (oof == null ? 0 : oof.length)];
            all[0] = lp.frag();
            if (oof != null) System.arraycopy(oof, 0, all, 1, oof.length);
            oof = all;
        }
        float baseline = Float.isNaN(fl.baseline) ? Float.NaN : LayoutUnit.snap(insetT + fl.baseline);
        return new LayoutFragment(node, space, w, h, LayoutUnit.snap(ml), Math.max(0, g.mT), Math.min(0, g.mT),
                Math.max(0, g.mB), Math.min(0, g.mB), insetL, insetT, false,
                new ChunkedFlow(new FlowChunk[]{fc}, new float[]{0}, 0, false), null, up, oof, baseline);
    }

    /**
     * 項目（{@code display: none}・畳まれる空白・{@code visibility: collapse} を除き、{@code order} で安定に並べる）と、それぞれの
     * 前回の候補。
     */
    private static List<FlexItem> items(BoxNode node, BoxNode legend, float cw, LayoutFragment prior, List<BoxNode> outOfFlow,
                                        boolean row, LayoutSession s) {
        FlowChunk pc = prior != null && prior.flow != null && prior.flow.chunks.length == 1 ? prior.flow.chunks[0] : null;
        List<BoxNode> kids = new ArrayList<>();
        boolean ordered = false;
        for (BoxNode k : node.flow.toList()) {
            if (k.kind == BoxKind.NONE || k.collapsibleWhitespace() || k == legend) continue;
            if (OutOfFlow.is(k)) { // 流れの外: 項目にしない
                outOfFlow.add(k);
                continue;
            }
            if (FlexLines.isCollapsed(k)) continue; // 主軸の場所も gap も取らない（§4.4。交差軸の支えは省略）
            kids.add(k);
            ordered |= Style.num(k.style.flexGrid.order, 0) != 0;
        }
        if (ordered) kids.sort((a, b) -> Float.compare(Style.num(a.style.flexGrid.order, 0), Style.num(b.style.flexGrid.order, 0)));
        List<FlexItem> out = new ArrayList<>(kids.size());
        for (int i = 0; i < kids.size(); i++) {
            BoxNode k = kids.get(i);
            int j = pc == null ? -1 : indexOf(pc, k, i);
            LayoutCache.Slots slots = j < 0 ? new LayoutCache.Slots(null, null)
                    : new LayoutCache.Slots(pc.frags[j], pc.slots != null ? pc.slots[j] : null);
            out.add(new FlexItem(k, new BoxGeometry(k.style, cw, s), slots, node.style, row));
        }
        return out;
    }

    private static int indexOf(FlowChunk pc, BoxNode k, int hint) {
        LayoutFragment[] fs = pc.frags;
        if (hint < fs.length && fs[hint].box != null && fs[hint].box.node == k.node) return hint;
        for (int i = 0; i < fs.length; i++) if (fs[i].box != null && fs[i].box.node == k.node) return i;
        return -1;
    }

    /**
     * 内在サイズ（CSS Flexbox §9.9）。row は項目の寄与の和（折り返すなら min-content は最大）、column は最大。縮まない項目
     * （{@code flex-shrink: 0}）は確定した flex base size より小さく寄与しない（§9.9.1）。折り返す column は容器の確定した高さ
     * （無ければ {@code max-height}）で列に分け、列ごとの最大の幅と {@code column-gap} の和（{@code order} の順）。
     */
    @Override
    public MinMaxSizes computeMinMaxSizes(BoxNode node, MinMaxContext ctx, LayoutSession s) {
        Style st = node.style;
        BoxGeometry g = new BoxGeometry(st, Float.NaN, s);
        FlexGridStyle fg = st.flexGrid;
        boolean row = fg.flexDirection == null || fg.flexDirection.isRow();
        // balance（行の長さを揃える折り返し）は複数行の容器: 内在サイズは折り返す容器と同じ。整形は揃えを入れるまで 1 行のまま
        boolean wrap = fg.flexWrap != null && fg.flexWrap != FlexWrap.NOWRAP;
        List<BoxNode> kids = new ArrayList<>();
        for (BoxNode k : node.flow.toList()) {
            if (k.kind == BoxKind.NONE || k.collapsibleWhitespace() || OutOfFlow.is(k) || FlexLines.isCollapsed(k)) continue;
            if (k == FieldsetLegend.of(node)) continue;
            kids.add(k);
        }
        kids.sort((a, b) -> Float.compare(Style.num(a.style.flexGrid.order, 0), Style.num(b.style.flexGrid.order, 0)));
        if (!row && wrap) {
            float w = columnWrapWidth(node, g, kids, s);
            if (!Float.isNaN(w)) return new MinMaxSizes(LayoutUnit.snap(w + g.hdec()), LayoutUnit.snap(w + g.hdec()));
        }
        float gapW = row ? FlexLines.gap(st, fg.columnGap, Float.NaN, s) : 0;
        float min = 0, max = 0;
        for (BoxNode k : kids) {
            Style cst = k.style;
            BoxGeometry ig = new BoxGeometry(cst, Float.NaN, s);
            MinMaxSizes m = ig.contribution(cst, s.cache().minMax(k, s), s);
            float cmin = m.min, cmax = m.max;
            long fb = cst.flexGrid.flexBasis;
            if (row && Style.num(cst.flexGrid.flexShrink, 1) <= 0 && FlexLines.basisDefinite(cst, fb, Float.NaN)) {
                float b = LayoutUnit.snap(cst.gridPx(fb, 0, s.viewportW(), s.viewportH()));
                b = Math.max(ig.hdec(), cst.facts.borderBox ? b : b + ig.hdec());
                cmin = Math.max(cmin, b);
                cmax = Math.max(cmax, b);
            }
            float margins = BlockFlow.fixedMargin(cst, cst.box.marginLeft, s) + BlockFlow.fixedMargin(cst, cst.box.marginRight, s);
            if (row) {
                max += cmax + margins;
                min = wrap ? Math.max(min, cmin + margins) : min + cmin + margins;
            } else {
                max = Math.max(max, cmax + margins);
                min = Math.max(min, cmin + margins);
            }
        }
        int n = kids.size();
        if (row && n > 1) {
            max += gapW * (n - 1);
            if (!wrap) min += gapW * (n - 1);
        }
        return FieldsetLegend.withLegend(new MinMaxSizes(LayoutUnit.snap(min + g.hdec()), LayoutUnit.snap(max + g.hdec())), node, g, s);
    }

    /**
     * 折り返す column の内容の幅: 容器の確定した高さ（% でない長さ、無ければ {@code max-height}）で項目を列に分け、列ごとの最大の
     * max-content の寄与と {@code column-gap} の和。項目の主軸の大きさは flex base size（内容なら寄与の幅で測った高さ）を
     * min/max の高さで挟んだ値。高さが決まらなければ NaN（呼び出し側が 1 列として扱う）。
     */
    private static float columnWrapWidth(BoxNode node, BoxGeometry g, List<BoxNode> kids, LayoutSession s) {
        Style st = node.style;
        float limit = g.specifiedHeight(st, Float.NaN, s);
        if (Float.isNaN(limit)) limit = g.clampHeight(st, Float.MAX_VALUE / 4, Float.NaN, s);
        if (!(limit < Float.MAX_VALUE / 8)) return Float.NaN;
        limit = Math.max(0, limit - g.vdec());
        float rowGap = FlexLines.gap(st, st.flexGrid.rowGap, limit, s), colGap = FlexLines.gap(st, st.flexGrid.columnGap, Float.NaN, s);
        float total = 0, lineW = 0, used = 0;
        boolean empty = true;
        for (BoxNode k : kids) {
            Style cst = k.style;
            BoxGeometry ig = new BoxGeometry(cst, Float.NaN, s);
            float w = ig.contribution(cst, s.cache().minMax(k, s), s).max;
            float outerW = w + BlockFlow.fixedMargin(cst, cst.box.marginLeft, s) + BlockFlow.fixedMargin(cst, cst.box.marginRight, s);
            long fb = cst.flexGrid.flexBasis;
            float main;
            if (FlexLines.basisDefinite(cst, fb, limit)) {
                float b = LayoutUnit.snap(cst.gridPx(fb, limit, s.viewportW(), s.viewportH()));
                main = Math.max(ig.vdec(), cst.facts.borderBox ? b : b + ig.vdec());
            } else {
                main = CssLength.isAutoOrUndeclared(fb) ? ig.specifiedHeight(cst, limit, s) : Float.NaN;
                if (Float.isNaN(main)) {
                    ConstraintSpace sp = s.space(w, w, Float.NaN, Float.NaN, ConstraintSpace.FORMATTING_ROOT | ConstraintSpace.CONTENT_BLOCK_SIZE);
                    main = s.cache().layout(k, sp, (LayoutFragment) null, s).height;
                }
            }
            main = ig.clampHeight(cst, main, limit, s) + BlockFlow.fixedMargin(cst, cst.box.marginTop, s)
                    + BlockFlow.fixedMargin(cst, cst.box.marginBottom, s);
            if (!empty && used + rowGap + main > limit + 0.01f) {
                total += lineW + colGap;
                lineW = 0;
                used = 0;
                empty = true;
            }
            used += (empty ? 0 : rowGap) + main;
            lineW = Math.max(lineW, outerW);
            empty = false;
        }
        return total + lineW;
    }
}
