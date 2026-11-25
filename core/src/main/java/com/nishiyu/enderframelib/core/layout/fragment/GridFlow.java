package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxKind;
import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.css.CssLength;
import com.nishiyu.enderframelib.core.css.FlexGridStyle;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.css.model.CssKeywords.Alignment;
import com.nishiyu.enderframelib.core.kernel.LayoutUnit;

import java.util.ArrayList;
import java.util.List;

/**
 * グリッドレイアウト（Engine v3 M2b-3。CSS Grid 1）。旧 {@code GridLayoutEngine} の計算（配置 {@link GridPlacement}・トラックの寸法
 * {@link GridTracks}）を移植し、子を測るつなぎ目だけを {@link LayoutCache}（{@code minMax}・{@code layout}）へ差し替えた
 * （計画書 §3.4.2-2）。結果は断片で返し、箱へ書き込まない。
 *
 * <ol>
 *   <li>トラックの定義を読み、項目を置く（自動配置・空の {@code auto-fit} を潰す）</li>
 *   <li>列の寸法（内容依存のトラックは項目の内在サイズの寄与から）→ 項目を列の領域の幅で測る</li>
 *   <li>行の寸法（項目の高さの寄与から）→ 高さが決まったら行を確定し直す</li>
 *   <li>{@code *-content} でトラックを寄せ、項目を領域の中で揃える（stretch は確定した大きさで測り直す・auto 余白・{@code *-self}）</li>
 * </ol>
 *
 * <p>範囲外: サブグリッド（{@code subgrid} は none と同じ）・ベースライン揃え・縦書き・明示的なグリッドより前の暗黙のトラック
 * （旧経路と同じ）。</p>
 */
final class GridFlow implements FormattingEngine {
    static final GridFlow INSTANCE = new GridFlow();

    private GridFlow() { }

    /** 1 回の整形の作業域: 項目・トラックの定義・寸法。 */
    private static final class Grid {
        final List<GridItem> items;
        final GridTracks.Template cols, rows;
        final List<GridTracks.Def> colDefs, rowDefs;
        final float colGap, rowGap;

        Grid(List<GridItem> items, GridTracks.Template cols, GridTracks.Template rows, List<GridTracks.Def> colDefs,
             List<GridTracks.Def> rowDefs, float colGap, float rowGap) {
            this.items = items;
            this.cols = cols;
            this.rows = rows;
            this.colDefs = colDefs;
            this.rowDefs = rowDefs;
            this.colGap = colGap;
            this.rowGap = rowGap;
        }
    }

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

        List<BoxNode> outOfFlow = new ArrayList<>();
        Grid grid = build(node, cw, ch, prior, outOfFlow, s);
        FlexGridStyle fg = st.flexGrid;
        LayoutCache cache = s.cache();
        columnContributions(grid, s);
        float[] colSizes = GridTracks.size(grid.colDefs, grid.items, true, cw, grid.colGap, fg.justifyContent, false);
        for (GridItem it : grid.items) { // 列の領域の幅で測る（高さは内容から。% の高さはグリッドの高さが確定していればそれ）
            float areaW = span(colSizes, it.colStart, it.colSpan, grid.colGap);
            it.frag = cache.layout(it.box, itemSpace(it, st, areaW, Float.NaN, BoxGeometry.usesPercentHeight(it.box) ? ch : Float.NaN, s),
                    it.slots, s);
        }
        float[] rowSizes = sizeRows(grid, fg, ch, s);
        float contentH = GridTracks.sum(rowSizes) + grid.rowGap * Math.max(0, rowSizes.length - 1);
        float h = Float.isNaN(fixedH) ? contentH + g.vdec() + spill : fixedH;
        if (Float.isNaN(space.fixedHeight) && !space.contentBlockSize()) h = g.clampHeight(st, h, space.percentHeightBase, s);
        h = LayoutUnit.snap(h);
        float innerH = Math.max(0, h - g.vdec() - spill);
        if (Float.isNaN(ch)) { // 高さが決まった: % の高さの項目をその高さで測り直し、行を確定し直す
            for (GridItem it : grid.items) {
                if (!BoxGeometry.usesPercentHeight(it.box)) continue;
                float areaW = span(colSizes, it.colStart, it.colSpan, grid.colGap);
                it.frag = cache.layout(it.box, itemSpace(it, st, areaW, Float.NaN, innerH, s), it.slots, s);
            }
            rowSizes = sizeRows(grid, fg, innerH, s);
        }
        float[] colOff = GridTracks.offsets(colSizes, grid.colGap, cw, fg.justifyContent, fg.justifyContentSafe);
        float[] rowOff = GridTracks.offsets(rowSizes, grid.rowGap, innerH, fg.alignContent, fg.alignContentSafe);
        boolean rtl = st.facts.rtl;
        for (GridItem it : grid.items) align(it, st, colSizes, rowSizes, colOff, rowOff, grid, cw, rtl, s);

        float insetL = g.bL + g.pL, insetT = g.bT + g.pT + spill;
        int n = grid.items.size();
        LayoutFragment[] frags = new LayoutFragment[n];
        float[] xs = new float[n], ys = new float[n];
        LayoutFragment[][] slots = null;
        List<OutOfFlow.Pending> pend = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            GridItem it = grid.items.get(i);
            frags[i] = it.frag;
            float[] rel = OutOfFlow.relativeOffset(it.box.style, cw, innerH, s);
            xs[i] = LayoutUnit.snap(it.x + (rel != null ? rel[0] : 0));
            ys[i] = LayoutUnit.snap(it.y + (rel != null ? rel[1] : 0));
            if (it.frag.pending != null) for (OutOfFlow.Pending p : it.frag.pending) pend.add(p.shift(insetL + xs[i], insetT + ys[i]));
            LayoutFragment[] used = it.slots.used(it.frag);
            if (used != null) {
                if (slots == null) slots = new LayoutFragment[n][];
                slots[i] = used;
            }
        }
        FlowChunk fc = new FlowChunk(null, null, frags, xs, ys, LayoutUnit.snap(innerH), 0, 0, 0, 0, false, slots, null, 0);
        boolean containing = OutOfFlow.containsOutOfFlow(st);
        PlacedFragment[] oof = null;
        OutOfFlow.Pending[] up = null;
        List<PlacedFragment> placed = new ArrayList<>();
        for (BoxNode b : outOfFlow) {
            float fx = selfFactor(b.style, st, true, rtl), fy = selfFactor(b.style, st, false, false);
            if (containing) { // 包含ブロックはグリッドの線が示す領域（§10.1）。静的位置はその領域の中で揃える
                float padW = Math.max(0, w - g.bL - g.bR), padH = Math.max(0, h - g.bT - g.bB);
                GridPlacement.Placement pl = GridPlacement.Placement.of(b.style);
                float[] x = absoluteAxis(pl.colStart(), pl.colEnd(), grid.cols, colSizes, colOff, rtl ? g.pR : g.pL, padW);
                float[] y = absoluteAxis(pl.rowStart(), pl.rowEnd(), grid.rows, rowSizes, rowOff, g.pT, padH);
                if (rtl) x[0] = padW - x[0] - x[1]; // rtl の列は右の padding 端から数える
                float[] area = {x[0], y[0], x[1], y[1]};
                OutOfFlow.Pending p = new OutOfFlow.Pending(b, g.bL + area[0], g.bT + area[1], area[2], area[3], fx, fy);
                placed.addAll(List.of(OutOfFlow.layout(List.of(p), g.bL + area[0], g.bT + area[1], area[2], area[3], prior, s)));
            } else {
                pend.add(new OutOfFlow.Pending(b, insetL, insetT, cw, innerH, fx, fy));
            }
        }
        if (!pend.isEmpty()) {
            if (containing) placed.addAll(List.of(OutOfFlow.layout(pend, g.bL, g.bT, Math.max(0, w - g.bL - g.bR), Math.max(0, h - g.bT - g.bB), prior, s)));
            else up = pend.toArray(OutOfFlow.NONE);
        }
        if (!placed.isEmpty()) oof = placed.toArray(new PlacedFragment[0]);
        if (lp != null) { // fieldset の描画される legend は置いた断片として持つ（FieldsetLegend）
            PlacedFragment[] all = new PlacedFragment[1 + (oof == null ? 0 : oof.length)];
            all[0] = lp.frag();
            if (oof != null) System.arraycopy(oof, 0, all, 1, oof.length);
            oof = all;
        }
        return new LayoutFragment(node, space, w, h, LayoutUnit.snap(ml), Math.max(0, g.mT), Math.min(0, g.mT),
                Math.max(0, g.mB), Math.min(0, g.mB), insetL, insetT, false,
                new ChunkedFlow(new FlowChunk[]{fc}, new float[]{0}, 0, false), null, up, oof, baseline(grid, insetT));
    }

    /** 項目を集めて置き、トラックの定義を作る。{@code cw} / {@code ch} は % と {@code auto-fill} の基準（不定なら NaN）。 */
    private static Grid build(BoxNode node, float cw, float ch, LayoutFragment prior, List<BoxNode> outOfFlow, LayoutSession s) {
        Style st = node.style;
        FlexGridStyle fg = st.flexGrid;
        float colGap = FlexLines.gap(st, fg.columnGap, cw, s), rowGap = FlexLines.gap(st, fg.rowGap, ch, s);
        FlowChunk pc = prior != null && prior.flow != null && prior.flow.chunks.length == 1 ? prior.flow.chunks[0] : null;
        List<BoxNode> kids = new ArrayList<>();
        BoxNode legend = FieldsetLegend.of(node);
        for (BoxNode k : node.flow.toList()) {
            if (k.kind == BoxKind.NONE || k.collapsibleWhitespace() || k == legend) continue;
            if (outOfFlow != null && OutOfFlow.is(k)) {
                outOfFlow.add(k);
                continue;
            }
            if (OutOfFlow.is(k)) continue;
            kids.add(k);
        }
        kids.sort((a, b) -> Float.compare(Style.num(a.style.flexGrid.order, 0), Style.num(b.style.flexGrid.order, 0)));
        List<GridItem> items = new ArrayList<>(kids.size());
        for (BoxNode k : kids) {
            int j = pc == null ? -1 : indexOf(pc, k);
            LayoutCache.Slots slots = j < 0 ? new LayoutCache.Slots(null, null)
                    : new LayoutCache.Slots(pc.frags[j], pc.slots != null ? pc.slots[j] : null);
            items.add(new GridItem(k, new BoxGeometry(k.style, Float.NaN, s), slots));
        }
        GridTracks.Template cols = GridTracks.parse(fg.gridTemplateColumns, cw, colGap, st, s);
        GridTracks.Template rows = GridTracks.parse(fg.gridTemplateRows, ch, rowGap, st, s);
        List<GridTracks.Def> autoCols = GridTracks.parse(Style.or(fg.gridAutoColumns, "auto"), cw, 0, st, s).tracks;
        List<GridTracks.Def> autoRows = GridTracks.parse(Style.or(fg.gridAutoRows, "auto"), ch, 0, st, s).tracks;
        GridPlacement.Areas areas = GridPlacement.Areas.parse(fg.gridTemplateAreas);
        areas.addLineNames(cols, rows);
        int[] count = GridPlacement.place(items, cols, rows, Math.max(cols.tracks.size(), areas.columns()),
                Math.max(rows.tracks.size(), areas.rows().size()), fg.gridAutoFlow);
        int colCount = Math.max(1, count[0] - GridPlacement.collapseEmptyAutoFit(items, cols, true));
        int rowCount = Math.max(0, count[1] - GridPlacement.collapseEmptyAutoFit(items, rows, false));
        return new Grid(items, cols, rows, defs(cols.tracks, autoCols, colCount), defs(rows.tracks, autoRows, rowCount), colGap, rowGap);
    }

    /** 明示的なトラックの後に、暗黙のトラックを {@code grid-auto-*} の繰り返しで足した並び。 */
    private static List<GridTracks.Def> defs(List<GridTracks.Def> explicit, List<GridTracks.Def> auto, int count) {
        List<GridTracks.Def> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (i < explicit.size()) out.add(explicit.get(i));
            else out.add(auto.isEmpty() ? GridTracks.Def.AUTO : auto.get((i - explicit.size()) % auto.size()));
        }
        return out;
    }

    private static int indexOf(FlowChunk pc, BoxNode k) {
        for (int i = 0; i < pc.frags.length; i++) if (pc.frags[i].box != null && pc.frags[i].box.node == k.node) return i;
        return -1;
    }

    /** 列の寄与（余白を含む min-content / max-content。内在サイズの 1 層メモから）。 */
    private static void columnContributions(Grid grid, LayoutSession s) {
        for (GridItem it : grid.items) {
            Style cst = it.box.style;
            MinMaxSizes m = it.g.contribution(cst, s.cache().minMax(it.box, s), s);
            float margins = BlockFlow.fixedMargin(cst, cst.box.marginLeft, s) + BlockFlow.fixedMargin(cst, cst.box.marginRight, s);
            it.colMin = m.min + margins;
            it.colMax = m.max + margins;
        }
    }

    /** 行の寄与を測った断片から求め、行の寸法を解く。 */
    private static float[] sizeRows(Grid grid, FlexGridStyle fg, float innerH, LayoutSession s) {
        for (GridItem it : grid.items) {
            Style cst = it.box.style;
            float margins = BlockFlow.fixedMargin(cst, cst.box.marginTop, s) + BlockFlow.fixedMargin(cst, cst.box.marginBottom, s);
            float h = it.frag.height;
            it.rowMax = h + margins;
            it.rowMin = rowMinContribution(it, h, s) + margins;
        }
        return GridTracks.size(grid.rowDefs, grid.items, false, innerH, grid.rowGap, fg.alignContent, false);
    }

    /**
     * 行の最小の寄与（§6.6）: {@code min-height} が長さならその値（高さ以下）、auto なら内容の高さ（スクロールコンテナは枠と
     * パディングだけ）。{@code min-height: 0; overflow: auto} の列が確定した高さのグリッドの行を押し広げないため。
     */
    private static float rowMinContribution(GridItem it, float h, LayoutSession s) {
        Style cst = it.box.style;
        if (BoxGeometry.definite(cst, cst.box.minHeight, Float.NaN)) return Math.min(h, it.g.clampHeight(cst, 0, Float.NaN, s));
        if (!FlexLines.overflowVisible(cst)) return Math.min(h, it.g.vdec());
        return h;
    }

    /**
     * 項目を測る制約。領域の幅 {@code areaW} が包含ブロックの幅。{@code justify-self} が stretch なら領域いっぱい（余白を引いて
     * min/max で挟む）、それ以外で幅が auto なら内容に合わせて縮める。
     */
    private static ConstraintSpace itemSpace(GridItem it, Style container, float areaW, float fixedH, float pct, LayoutSession s) {
        Style cst = it.box.style;
        BoxGeometry ig = new BoxGeometry(cst, areaW, s);
        float avail = Math.max(0, areaW - ig.mL - ig.mR);
        float fw = Float.NaN;
        if (CssLength.isAutoOrUndeclared(cst.box.width)) {
            boolean autoMargin = cst.facts.autoMarginL || cst.facts.autoMarginR;
            if (selfAlignment(cst, container, true) == Alignment.STRETCH && !autoMargin) {
                fw = ig.clampWidth(cst, avail, areaW, s);
            } else {
                MinMaxSizes mm = s.cache().minMax(it.box, s);
                fw = ig.clampWidth(cst, Math.min(mm.max, Math.max(mm.min, avail)), areaW, s);
            }
            fw = LayoutUnit.snap(fw);
        }
        return s.space(areaW, fw, fixedH, BoxGeometry.usesPercentHeight(it.box) ? pct : Float.NaN, ConstraintSpace.FORMATTING_ROOT);
    }

    /** 行が確定してから、項目を領域の中で揃える（stretch・% の高さの測り直し・auto 余白・{@code *-self}）。 */
    private static void align(GridItem it, Style st, float[] colSizes, float[] rowSizes, float[] colOff, float[] rowOff, Grid grid,
                              float cw, boolean rtl, LayoutSession s) {
        float areaX = colOff[it.colStart], areaY = rowOff[it.rowStart];
        float areaW = colOff[it.colStart + it.colSpan] - areaX - gapAfter(colSizes, it.colStart + it.colSpan, colOff);
        float areaH = rowOff[it.rowStart + it.rowSpan] - areaY - gapAfter(rowSizes, it.rowStart + it.rowSpan, rowOff);
        Style cst = it.box.style;
        BoxGeometry ig = new BoxGeometry(cst, areaW, s);
        Alignment alignSelf = selfAlignment(cst, st, false);
        boolean autoV = cst.facts.autoMarginT || cst.facts.autoMarginB;
        LayoutCache cache = s.cache();
        if (alignSelf == Alignment.STRETCH && !autoV && CssLength.isAutoOrUndeclared(cst.box.height)) {
            float t = LayoutUnit.snap(ig.clampHeight(cst, Math.max(0, areaH - ig.mT - ig.mB), areaH, s));
            // 伸ばした高さは確定した高さ（§6.2: 子の % の高さ・相対位置の % の基準）。内容の高さと同じでも高さを確定して測り直す
            // （子のチャンクは % を使う子のほかは当たる）
            if (t != it.frag.height || Float.isNaN(it.frag.space.fixedHeight) || it.frag.space.availableWidth != areaW) {
                it.frag = cache.layout(it.box, itemSpace(it, st, areaW, t, areaH, s), it.slots, s);
            }
        } else if (BoxGeometry.usesPercentHeight(it.box) && it.frag.space.percentHeightBase != areaH) {
            it.frag = cache.layout(it.box, itemSpace(it, st, areaW, Float.NaN, areaH, s), it.slots, s);
        }
        Alignment justifySelf = selfAlignment(cst, st, true);
        if (rtl) justifySelf = justifySelf == Alignment.LEFT ? Alignment.RIGHT : justifySelf == Alignment.RIGHT ? Alignment.LEFT : justifySelf;
        float x = areaX + within(it.frag.width, ig.mL, ig.mR, cst.facts.autoMarginL, cst.facts.autoMarginR, areaW, justifySelf);
        it.y = areaY + within(it.frag.height, ig.mT, ig.mB, cst.facts.autoMarginT, cst.facts.autoMarginB, areaH, alignSelf);
        if (rtl) { // 左から組んだ余白の箱を内容ボックスの中で左右に裏返す
            float mbX = x - ig.mL;
            x = cw - mbX - (it.frag.width + ig.mL + ig.mR) + ig.mL;
        }
        it.x = x;
    }

    /**
     * 包含ブロックがグリッドの容器である abspos の箱の、1 軸の {始点, 長さ}（padding の箱の始点から。§10.1）: 線が示す領域で、
     * auto の側・グリッドに無い線の側は padding の箱の端。
     */
    private static float[] absoluteAxis(GridPlacement.Line start, GridPlacement.Line end, GridTracks.Template tracks, float[] sizes,
                                        float[] off, float padStart, float padLen) {
        int n = sizes.length, explicit = tracks.tracks.size();
        Integer a = start.resolve(tracks, explicit, true), e = end.resolve(tracks, explicit, false);
        if (a == null && e != null && start.span() > 0) a = e - start.span();
        if (e == null && a != null && end.span() > 0) e = a + end.span();
        if (a != null && e != null && e < a) {
            Integer t = a;
            a = e;
            e = t;
        }
        if (a != null && a.equals(e)) e = null; // 始点と同じ終点は捨てる（§8.3.1）
        if (a != null && (a < 0 || a > n)) a = null;
        if (e != null && (e < 0 || e > n)) e = null;
        float from = a == null ? 0 : padStart + off[a];
        float to = e == null ? padLen : padStart + off[e] - (e > 0 ? gapAfter(sizes, e, off) : 0);
        return new float[]{from, Math.max(0, to - from)};
    }

    /** 領域の中での border-box の始点（領域の始点から）。auto 余白が先に余りを吸う。 */
    private static float within(float size, float mStart, float mEnd, boolean autoStart, boolean autoEnd, float area, Alignment a) {
        float ms = autoStart ? 0 : mStart, me = autoEnd ? 0 : mEnd;
        float free = area - size - ms - me;
        if (autoStart || autoEnd) {
            float share = Math.max(0, free) / ((autoStart ? 1 : 0) + (autoEnd ? 1 : 0));
            return autoStart ? share : ms;
        }
        return ms + switch (a) {
            case CENTER -> free / 2;
            case END, FLEX_END, SELF_END, RIGHT -> free;
            default -> 0;
        };
    }

    /** 終端の線の手前にある gap の幅（最後の線の手前には無い）。 */
    private static float gapAfter(float[] sizes, int line, float[] off) {
        if (line >= sizes.length) return 0;
        return off[line] - off[line - 1] - sizes[line - 1];
    }

    static float span(float[] sizes, int start, int count, float gap) {
        float w = 0;
        for (int i = start; i < start + count && i < sizes.length; i++) w += sizes[i];
        return w + gap * Math.max(0, count - 1);
    }

    /**
     * 項目の揃え。{@code *-self} が auto なら容器の {@code *-items}、{@code normal} / {@code legacy} は置換要素でなく大きさの指定も
     * 無ければ stretch（{@code first/last baseline} は baseline）。
     */
    static Alignment selfAlignment(Style item, Style container, boolean inline) {
        Alignment v = inline ? item.flexGrid.justifySelf : item.flexGrid.alignSelf;
        if (v == null || v == Alignment.AUTO) v = inline ? container.flexGrid.justifyItems : container.flexGrid.alignItems;
        if (v == null || v == Alignment.NORMAL || v == Alignment.LEGACY || v == Alignment.AUTO) {
            boolean sized = !CssLength.isAutoOrUndeclared(inline ? item.box.width : item.box.height);
            return sized ? Alignment.START : Alignment.STRETCH;
        }
        return v == Alignment.FIRST_BASELINE || v == Alignment.LAST_BASELINE ? Alignment.BASELINE : v;
    }

    /** abspos の子の静的位置を領域の中で揃える割合（0 / 0.5 / 1。§9.4）。 */
    private static float selfFactor(Style item, Style container, boolean inline, boolean rtl) {
        Alignment a = inline ? item.flexGrid.justifySelf : item.flexGrid.alignSelf;
        if (a == null || a == Alignment.AUTO) a = inline ? container.flexGrid.justifyItems : container.flexGrid.alignItems;
        float f = switch (a == null ? Alignment.NORMAL : a) {
            case CENTER -> 0.5f;
            case END, SELF_END, FLEX_END, LAST_BASELINE -> 1;
            case RIGHT -> inline ? (rtl ? 0 : 1) : 0;
            case LEFT -> inline ? (rtl ? 1 : 0) : 0;
            default -> 0;
        };
        return inline && rtl ? 1 - f : f;
    }

    /** グリッドのベースライン: 最初の行の最も左の項目のベースライン（無ければ合成。項目が無ければ NaN）。 */
    private static float baseline(Grid grid, float insetT) {
        GridItem pick = null;
        for (GridItem it : grid.items) {
            if (it.rowStart == 0 && (pick == null || it.colStart < pick.colStart)) pick = it;
        }
        if (pick == null) return Float.NaN;
        return LayoutUnit.snap(insetT + pick.y + FlexLines.itemBaseline(pick.frag));
    }

    /** 内在サイズ: 列を不定の空間で解いた和（max）と、min-content の制約で解いた和（min）。 */
    @Override
    public MinMaxSizes computeMinMaxSizes(BoxNode node, MinMaxContext ctx, LayoutSession s) {
        Style st = node.style;
        BoxGeometry g = new BoxGeometry(st, Float.NaN, s);
        Grid grid = build(node, Float.NaN, Float.NaN, null, null, s);
        columnContributions(grid, s);
        float gaps = grid.colGap * Math.max(0, grid.colDefs.size() - 1);
        float max = GridTracks.sum(GridTracks.size(grid.colDefs, grid.items, true, Float.NaN, grid.colGap, null, false)) + gaps;
        float min = GridTracks.sum(GridTracks.size(grid.colDefs, grid.items, true, Float.NaN, grid.colGap, null, true)) + gaps;
        return FieldsetLegend.withLegend(new MinMaxSizes(LayoutUnit.snap(min + g.hdec()), LayoutUnit.snap(max + g.hdec())), node, g, s);
    }
}
