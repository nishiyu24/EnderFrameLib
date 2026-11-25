package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.css.CssLength;
import com.nishiyu.enderframelib.core.css.LengthResolver;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.css.model.CssKeywords;
import com.nishiyu.enderframelib.core.kernel.LayoutUnit;

import java.util.ArrayList;
import java.util.List;

/**
 * 表の整形（Engine v3 M2b-4。CSS 2.2 §17）。旧 {@code TableLayoutEngine} の計算（格子 {@link TableStructure}・列幅 {@link TableColumns}・
 * 枠線の重ね {@link TableBorders}・行の高さ・縦揃え・キャプション）を移植し、セルを測るつなぎ目だけを {@link LayoutCache}（{@code minMax}・
 * {@code layout}）へ差し替えた（計画書 §3.4.2-2）。
 *
 * <p>旧経路は表が決めた列幅・行の高さ・枠線の分担・縦揃えのずれを行とセルの箱へ書き込んだ（{@code overrideWidth}・{@code collapsedBorders}・
 * {@code contentOffsetY}）。ここでは結果を断片で返し、親が決めた値はトップダウンの引数で渡す: セルは列幅を {@link ConstraintSpace#fixedWidth}、
 * 重ねた枠線を {@link ConstraintSpace#borders} で受け取って中身の高さで整形され、行の高さと縦揃えは表が作る新しい断片（行の高さ・内容の
 * 原点のずれ {@code insetTop}）に表す（中身の断片は同じ参照のまま。元の断片は {@link FlowChunk#slots} に残り、次のフレームで当たる）。</p>
 *
 * <p>行グループと行は自分では整形されず、表が大きさと子の位置を決めた断片になる。表の高さの指定は行の部分の下限で、キャプションは
 * その外に足す（CSS 2.2 §17.4。旧経路と同じく余りの高さは行へ配らない）。表の幅は列の最小幅の和より狭くならない（§17.5.2）。</p>
 */
final class TableFlow implements FormattingEngine {
    static final TableFlow INSTANCE = new TableFlow();

    private TableFlow() { }

    /** 1 回の整形の作業域: 格子・枠線・間隔・縁。 */
    private static final class Work {
        final TableStructure t;
        final boolean collapse;
        final float hs, vs;
        final float bT, bR, bB, bL, pT, pR, pB, pL;
        /** 行ごとのベースラインより上の最大（ベースラインに揃えたセル。{@link #rowHeights} が置く）。 */
        float[] above;

        Work(BoxNode node, BoxGeometry g, LayoutSession s) {
            Style st = node.style;
            t = TableStructure.of(node);
            collapse = st.inherited.borderCollapse == CssKeywords.BorderCollapse.COLLAPSE;
            TableBorders.Edges e = collapse ? TableBorders.collapse(node, t, s) : null;
            float[] spacing = collapse ? new float[2] : borderSpacing(st, s);
            hs = spacing[0];
            vs = spacing[1];
            // 枠線を重ねる表は padding を持たない（CSS 2.2 §17.6.2）
            bT = e != null ? e.top() : g.bT;
            bR = e != null ? e.right() : g.bR;
            bB = e != null ? e.bottom() : g.bB;
            bL = e != null ? e.left() : g.bL;
            pT = collapse ? 0 : g.pT;
            pR = collapse ? 0 : g.pR;
            pB = collapse ? 0 : g.pB;
            pL = collapse ? 0 : g.pL;
        }

        float hdec() { return bL + bR + pL + pR; }

        float vdec() { return bT + bB + pT + pB; }

        float spacingW() { return t.columnCount > 0 ? hs * (t.columnCount + 1) : 0; }
    }

    @Override
    public LayoutFragment layout(BoxNode node, ConstraintSpace space, LayoutFragment prior, LayoutSession s) {
        Style st = node.style;
        float cbw = space.availableWidth;
        BoxGeometry g = new BoxGeometry(st, cbw, s);
        Work w = new Work(node, g, s);
        TableStructure t = w.t;
        int n = t.columnCount;
        float spacingW = w.spacingW(), hdec = w.hdec();
        cellContributions(w, s);
        float[] min = new float[n], max = new float[n], percent = TableColumns.percents(t, n);
        boolean[] specified = new boolean[n];
        TableColumns.contributions(t, w.hs, min, max, specified, s);
        float minContentW = TableColumns.sum(min) + spacingW + hdec;

        // 表の幅（border-box）: 親が決めた幅・指定の幅（min/max で挟む）・内容に合わせた幅（使える幅まで）。列の最小幅の和を下回らない
        float given = space.fixedWidth, spec = specifiedWidth(node, w, cbw, s);
        boolean definite = !Float.isNaN(given) || !Float.isNaN(spec);
        boolean fixedLayout = !Float.isNaN(spec) && st.table.tableLayout == CssKeywords.TableLayout.FIXED;
        float W;
        if (!Float.isNaN(given)) {
            W = given;
        } else if (!Float.isNaN(spec)) {
            W = clampWidth(st, spec, cbw, hdec, s);
        } else {
            float avail = Float.isFinite(cbw) ? Math.max(0, cbw - g.mL - g.mR) : Float.POSITIVE_INFINITY;
            float wanted = Math.max(TableColumns.sum(max), TableColumns.percentRequiredWidth(percent, max)) + spacingW + hdec;
            W = clampWidth(st, Math.min(wanted, avail), cbw, hdec, s);
        }
        if (!fixedLayout) W = Math.max(W, minContentW);
        float target = Math.max(0, W - hdec - spacingW);
        float[] widths = fixedLayout ? TableColumns.fixed(t, target, w.hs, c -> cellSpecifiedOuter(c, s), s)
                : TableColumns.distributePercent(min, max, specified, percent, definite ? Math.max(TableColumns.sum(min), target) : target);
        for (int c = 0; c < n; c++) if (t.collapsedColumns[c]) widths[c] = 0;
        float contentW = LayoutUnit.snap(Math.max(TableColumns.sum(widths) + spacingW, W - hdec));
        W = LayoutUnit.snap(contentW + hdec);
        float ml = g.mL;
        if (Float.isNaN(given)) {
            if (g.autoML && g.autoMR) ml = Math.max(0, (cbw - W) / 2f);
            else if (g.autoML) ml = Math.max(0, cbw - W - g.mR);
        }
        float[] colX = new float[n + 1];
        float x = w.hs;
        for (int c = 0; c < n; c++) {
            colX[c] = LayoutUnit.snap(x);
            x += widths[c] + w.hs;
        }
        colX[n] = LayoutUnit.snap(x);

        // セルを列幅で整形する（高さは中身から）
        TablePriors pr = TablePriors.of(prior);
        LayoutCache cache = s.cache();
        float pct = space.percentHeightBase;
        for (TableStructure.Cell c : t.cells) {
            float cw = cellWidth(colX, c, w.hs, n);
            ConstraintSpace sp = s.space(cw, cw, Float.NaN, BoxGeometry.usesPercentHeight(c.box) ? pct : Float.NaN,
                    ConstraintSpace.FORMATTING_ROOT).withBorders(c.borders);
            c.slots = pr.slots(c.box);
            c.natural = cache.layout(c.box, sp, c.slots, s);
        }
        List<LayoutFragment> caps = new ArrayList<>();
        float captionTop = 0, captionBottom = 0;
        for (BoxNode cap : t.captions) {
            LayoutFragment f = cache.layout(cap, s.space(contentW, Float.NaN, Float.NaN, Float.NaN, ConstraintSpace.FORMATTING_ROOT),
                    pr.single(cap), s);
            caps.add(f);
            BoxGeometry cg = new BoxGeometry(cap.style, contentW, s);
            float outer = f.height + cg.mT + cg.mB;
            if (bottomCaption(cap)) captionBottom += outer;
            else captionTop += outer;
        }

        float[] heights = rowHeights(w, pct, s);
        int rows = t.rows.size();
        int visible = 0;
        for (int r = 0; r < rows; r++) if (!t.collapsedRows[r]) visible++; // 潰れた行は高さも前後の border-spacing も持たない
        float gridH = visible > 0 ? TableColumns.sum(heights) + w.vs * (visible + 1) : 0;
        float h = captionTop + gridH + captionBottom + w.vdec();
        // CONTENT_BLOCK_SIZE（column flex の内容基準の大きさ・自動の最小サイズ）は自分の height を掛けずに内容で測る
        float specH = !Float.isNaN(space.fixedHeight) ? space.fixedHeight
                : space.contentBlockSize() ? Float.NaN : specifiedHeight(st, w, space.percentHeightBase, s);
        // 表の高さの指定は行の部分（表の格子の箱）の下限で、キャプションはその外に足す。内容より低くはならない（CSS 2.2 §17.4・§17.5.3）
        if (!Float.isNaN(specH)) h = Math.max(h, specH + (Float.isNaN(space.fixedHeight) ? captionTop + captionBottom : 0));
        if (!space.contentBlockSize()) h = Math.max(h, minHeight(st, w, space.percentHeightBase, s));
        h = LayoutUnit.snap(h);
        float extra = h - w.vdec() - captionTop - captionBottom - gridH;
        if (extra > 0 && visible > 0) { // 余った高さは行へ高さの比で配る（全て 0 なら等分。CSS Tables 3 §3.10.3）
            distributeExtraHeight(heights, t.collapsedRows, extra);
            gridH += extra;
        }
        float[] rowY = new float[rows];
        float y = captionTop + w.vs;
        for (int r = 0; r < rows; r++) {
            rowY[r] = LayoutUnit.snap(y);
            if (!t.collapsedRows[r]) y += heights[r] + w.vs;
        }

        return assemble(node, space, g, w, W, h, ml, contentW, colX, heights, rowY, caps, captionTop, gridH, prior, s);
    }

    /** 行グループ・行・セル・キャプションの断片を組み、表の断片にする。 */
    private static LayoutFragment assemble(BoxNode node, ConstraintSpace space, BoxGeometry g, Work w, float W, float h, float ml,
                                           float contentW, float[] colX, float[] heights, float[] rowY, List<LayoutFragment> caps,
                                           float captionTop, float gridH, LayoutFragment prior, LayoutSession s) {
        TableStructure t = w.t;
        int rows = t.rows.size();
        float insetL = w.bL + w.pL, insetT = w.bT + w.pT;
        float rowW = Math.max(0, contentW - 2 * w.hs);
        List<OutOfFlow.Pending> pend = new ArrayList<>();
        // 行ごとのセル（縦揃えのずれを内容の原点に表した断片）
        List<List<TableStructure.Cell>> byRow = new ArrayList<>(rows);
        for (int r = 0; r < rows; r++) byRow.add(new ArrayList<>());
        for (TableStructure.Cell c : t.cells) byRow.get(c.row).add(c);
        LayoutFragment[] rowFrags = new LayoutFragment[rows];
        float baseline = Float.NaN;
        for (int r = 0; r < rows; r++) {
            List<TableStructure.Cell> cs = byRow.get(r);
            int k = cs.size();
            LayoutFragment[] frags = new LayoutFragment[k];
            float[] xs = new float[k], ys = new float[k];
            LayoutFragment[][] slots = null;
            for (int i = 0; i < k; i++) {
                TableStructure.Cell c = cs.get(i);
                float ch = w.vs * (c.rowSpan - 1);
                for (int rr = c.row; rr < c.row + c.rowSpan; rr++) ch += heights[rr];
                ch = LayoutUnit.snap(ch);
                float off = LayoutUnit.snap(alignOffset(c, ch, w, heights, r));
                frags[i] = stretched(c.natural, ch, off);
                float[] rel = OutOfFlow.relativeOffset(c.box.style, contentW, Float.NaN, s);
                xs[i] = colX[c.col] - w.hs + (rel != null ? rel[0] : 0);
                ys[i] = rel != null ? rel[1] : 0;
                LayoutFragment[] used = cellSlots(c, frags[i]);
                if (used != null) {
                    if (slots == null) slots = new LayoutFragment[k][];
                    slots[i] = used;
                }
                if (frags[i].pending != null) {
                    float ox = insetL + w.hs + xs[i], oy = insetT + rowY[r] + ys[i];
                    for (OutOfFlow.Pending p : frags[i].pending) pend.add(p.shift(ox, oy));
                }
            }
            if (r == firstVisibleRow(t)) baseline = LayoutUnit.snap(insetT + rowY[r] + rowBaseline(cs, frags, w, heights[r], r));
            FlowChunk fc = new FlowChunk(null, null, frags, xs, ys, heights[r], 0, 0, 0, 0, false, slots, null, 0);
            rowFrags[r] = new LayoutFragment(t.rows.get(r), s.space(rowW, rowW, heights[r], Float.NaN, ConstraintSpace.FORMATTING_ROOT),
                    rowW, heights[r], 0, 0, 0, 0, 0, 0, 0, false, single(fc), null, null, null);
        }

        // 表の直下: キャプション → 行グループ（文書順）→ 直下の行（旧経路の並び。描画の順もこの順）
        List<LayoutFragment> top = new ArrayList<>();
        List<float[]> pos = new ArrayList<>();
        float topY = 0, bottomY = captionTop + gridH;
        for (int i = 0; i < caps.size(); i++) {
            LayoutFragment f = caps.get(i);
            BoxGeometry cg = new BoxGeometry(f.box.style, contentW, s);
            boolean bottom = bottomCaption(f.box);
            float cy = (bottom ? bottomY : topY) + cg.mT;
            if (bottom) bottomY += f.height + cg.mT + cg.mB;
            else topY += f.height + cg.mT + cg.mB;
            top.add(f);
            pos.add(new float[]{f.marginLeft, LayoutUnit.snap(cy)});
            if (f.pending != null) for (OutOfFlow.Pending p : f.pending) pend.add(p.shift(insetL + f.marginLeft, insetT + cy));
        }
        for (BoxNode group : t.groups) {
            int first = t.rowGroupOf.indexOf(group), last = t.rowGroupOf.lastIndexOf(group);
            List<LayoutFragment> members = new ArrayList<>();
            List<Float> ry = new ArrayList<>();
            float gy = captionTop, gh = 0;
            if (first >= 0) {
                gy = rowY[first];
                gh = LayoutUnit.snap(rowY[last] + heights[last] - rowY[first]);
                for (int r = first; r <= last; r++) {
                    members.add(rowFrags[r]);
                    ry.add(rowY[r] - rowY[first]);
                }
            }
            LayoutFragment[] fs = members.toArray(new LayoutFragment[0]);
            float[] xs = new float[fs.length], ys = new float[fs.length];
            for (int i = 0; i < fs.length; i++) ys[i] = ry.get(i);
            FlowChunk fc = new FlowChunk(null, null, fs, xs, ys, gh, 0, 0, 0, 0, false, null, null, 0);
            top.add(new LayoutFragment(group, s.space(rowW, rowW, gh, Float.NaN, ConstraintSpace.FORMATTING_ROOT), rowW, gh, 0, 0, 0, 0, 0,
                    0, 0, false, single(fc), null, null, null));
            pos.add(new float[]{w.hs, gy});
        }
        for (int r = 0; r < rows; r++) {
            if (t.rowGroupOf.get(r) != null) continue;
            top.add(rowFrags[r]);
            pos.add(new float[]{w.hs, rowY[r]});
        }
        int m = top.size();
        float[] xs = new float[m], ys = new float[m];
        for (int i = 0; i < m; i++) {
            xs[i] = pos.get(i)[0];
            ys[i] = pos.get(i)[1];
        }
        FlowChunk fc = new FlowChunk(null, null, top.toArray(new LayoutFragment[0]), xs, ys, LayoutUnit.snap(h - w.vdec()), 0, 0, 0, 0, false,
                null, null, 0);

        OutOfFlow.Pending[] up = null;
        PlacedFragment[] oof = null;
        if (!pend.isEmpty()) {
            OutOfFlow.Split sp = OutOfFlow.split(pend, node.style); // 位置指定の箱は absolute だけを置き、fixed は上へ渡す
            if (!sp.here().isEmpty()) {
                oof = OutOfFlow.layout(sp.here(), w.bL, w.bT, Math.max(0, W - w.bL - w.bR), Math.max(0, h - w.bT - w.bB), prior,
                        Anchors.of(single(fc), null, insetL, insetT), s);
            }
            up = OutOfFlow.withEscaped(sp.up(), oof); // 置いた箱の中の fixed も上へ渡す
        }
        return new LayoutFragment(node, space, W, h, LayoutUnit.snap(ml), Math.max(0, g.mT), Math.min(0, g.mT), Math.max(0, g.mB),
                Math.min(0, g.mB), insetL, insetT, false, single(fc), null, up, oof, baseline);
    }

    /**
     * 表の断片のキャプションの外側の高さの和（余白を含む）。表の {@code height} は表の格子の箱に掛かり、親が外枠の箱の大きさを決めるとき
     * （column flex の基準の大きさ）はこれを足す。親が決めた高さ（{@link ConstraintSpace#fixedHeight}）はキャプションを含む外枠の高さ。
     */
    static float captionExtent(LayoutFragment table, LayoutSession s) {
        if (table.flow == null || table.box == null || !table.box.kind.tableContainer()) return 0;
        float sum = 0;
        for (FlowChunk c : table.flow.chunks) {
            for (LayoutFragment f : c.frags) {
                if (f.box == null || f.box.kind != com.nishiyu.enderframelib.core.box.BoxKind.TABLE_CAPTION) continue;
                BoxGeometry g = new BoxGeometry(f.box.style, f.space.availableWidth, s); // キャプションは表の内容の幅で整形した
                sum += f.height + g.mT + g.mB;
            }
        }
        return sum;
    }

    private static ChunkedFlow single(FlowChunk fc) {
        return new ChunkedFlow(new FlowChunk[]{fc}, new float[]{0}, 0, false);
    }

    private static int firstVisibleRow(TableStructure t) {
        for (int r = 0; r < t.rows.size(); r++) if (!t.collapsedRows[r]) return r;
        return -1;
    }

    /**
     * セルの寄与（border-box。内在サイズの 1 層メモから）。枠線を重ねる表では、内容の内在サイズに含まれるスタイルの枠線を使う枠線へ
     * 差し替える。幅の指定（px）があれば最小・最大はそれを下回らない。
     */
    private static void cellContributions(Work w, LayoutSession s) {
        for (TableStructure.Cell c : w.t.cells) {
            Style cs = c.box.style;
            BoxGeometry g = new BoxGeometry(cs, Float.NaN, s);
            MinMaxSizes mm = s.cache().minMax(c.box, s);
            float delta = c.borders == null ? 0 : c.borders.left() + c.borders.right() - g.bL - g.bR;
            float min = mm.min + delta, max = mm.max + delta;
            float spec = cellSpecifiedOuter(c, s);
            if (!Float.isNaN(spec)) {
                min = Math.max(min, spec);
                max = spec;
            }
            c.minW = min;
            c.maxW = Math.max(min, max);
        }
    }

    /** セルの px の {@code width} を border-box にした値（使う枠線で。無ければ NaN）。 */
    private static float cellSpecifiedOuter(TableStructure.Cell c, LayoutSession s) {
        Style cs = c.box.style;
        float spec = TableColumns.fixedWidth(cs, s);
        if (Float.isNaN(spec)) return Float.NaN;
        BoxGeometry g = new BoxGeometry(cs, Float.NaN, s);
        float bl = c.borders != null ? c.borders.left() : g.bL, br = c.borders != null ? c.borders.right() : g.bR;
        float dec = g.pL + g.pR + bl + br;
        return cs.box.boxSizing == CssKeywords.BoxSizing.BORDER_BOX ? Math.max(spec, dec) : spec + dec;
    }

    private static float cellWidth(float[] colX, TableStructure.Cell c, float hs, int n) {
        int end = Math.min(n, c.col + c.colSpan);
        return Math.max(0, colX[end] - hs - colX[c.col]);
    }

    /** 行の高さ: 行の {@code height}・1 行のセルの高さ・ベースライン揃えの上下、複数行のセルが入らなければ最後の行を高くする。 */
    private static float[] rowHeights(Work w, float pct, LayoutSession s) {
        TableStructure t = w.t;
        int rows = t.rows.size();
        float[] heights = new float[rows], above = new float[rows], below = new float[rows];
        for (int r = 0; r < rows; r++) {
            Style rs = t.rows.get(r).style;
            if (!t.collapsedRows[r] && BoxGeometry.definite(rs, rs.box.height, pct)) heights[r] = BoxGeometry.len(rs, rs.box.height, pct, s);
        }
        for (TableStructure.Cell c : t.cells) {
            if (c.rowSpan != 1 || t.collapsedRows[c.row]) continue;
            heights[c.row] = Math.max(heights[c.row], c.natural.height);
            if (align(c) == ALIGN_BASELINE) {
                c.baseline = cellBaseline(c, c.natural, s);
                above[c.row] = Math.max(above[c.row], c.baseline);
                below[c.row] = Math.max(below[c.row], c.natural.height - c.baseline);
                heights[c.row] = Math.max(heights[c.row], above[c.row] + below[c.row]);
            }
        }
        for (TableStructure.Cell c : t.cells) {
            if (c.rowSpan == 1 || t.collapsedRows[c.row]) continue;
            float have = w.vs * (c.rowSpan - 1);
            for (int r = c.row; r < c.row + c.rowSpan; r++) have += heights[r];
            if (c.natural.height > have) heights[c.row + c.rowSpan - 1] += c.natural.height - have;
        }
        for (int r = 0; r < rows; r++) heights[r] = LayoutUnit.snap(heights[r]);
        w.above = above;
        return heights;
    }

    private static void distributeExtraHeight(float[] heights, boolean[] collapsed, float extra) {
        float total = 0;
        int count = 0;
        for (int r = 0; r < heights.length; r++) {
            if (collapsed[r]) continue;
            total += heights[r];
            count++;
        }
        float given = 0;
        int last = -1;
        for (int r = 0; r < heights.length; r++) {
            if (collapsed[r]) continue;
            float add = LayoutUnit.snap(total > 0 ? extra * heights[r] / total : extra / count);
            heights[r] += add;
            given += add;
            last = r;
        }
        if (last >= 0) heights[last] += extra - given; // 丸めの残りは最後の行へ
    }

    private static final int ALIGN_BASELINE = 0, ALIGN_TOP = 1, ALIGN_MIDDLE = 2, ALIGN_BOTTOM = 3;

    /** セルの {@code vertical-align}（top / middle / bottom、それ以外は baseline）。 */
    private static int align(TableStructure.Cell c) {
        long va = c.box.style.box.verticalAlign;
        if (CssLength.isKeyword(va, CssLength.KW_TOP)) return ALIGN_TOP;
        if (CssLength.isKeyword(va, CssLength.KW_MIDDLE)) return ALIGN_MIDDLE;
        if (CssLength.isKeyword(va, CssLength.KW_BOTTOM)) return ALIGN_BOTTOM;
        return ALIGN_BASELINE;
    }

    /** 中身のずれ（行の高さに伸ばしたセルの中で、{@code vertical-align} で中身を下げる量）。 */
    private static float alignOffset(TableStructure.Cell c, float h, Work w, float[] heights, int r) {
        float free = h - c.natural.height;
        return switch (align(c)) {
            case ALIGN_MIDDLE -> free / 2;
            case ALIGN_BOTTOM -> free;
            case ALIGN_BASELINE -> c.rowSpan == 1 && w.above != null ? w.above[r] - c.baseline : 0;
            default -> 0;
        };
    }

    /** セルのベースライン（border-box の上端から）。行が無ければ内容ボックスの下端（CSS 2.2 §17.5.3）。 */
    private static float cellBaseline(TableStructure.Cell c, LayoutFragment f, LayoutSession s) {
        float b = InlineFlow.baselineOf(f, true);
        if (!Float.isNaN(b)) return b;
        BoxGeometry g = new BoxGeometry(c.box.style, f.space.availableWidth, f.space, s);
        return f.height - g.pB - g.bB;
    }

    /** 行のベースライン（行の上端から）: ベースラインに揃えたセルがあればそれ、無ければ最も下のセルの内容ボックスの下端。 */
    private static float rowBaseline(List<TableStructure.Cell> cs, LayoutFragment[] frags, Work w, float rowH, int r) {
        if (w.above != null && w.above[r] > 0) return w.above[r];
        float b = Float.NaN;
        for (int i = 0; i < cs.size(); i++) {
            float cb = InlineFlow.baselineOf(frags[i], true);
            if (!Float.isNaN(cb)) b = Float.isNaN(b) ? cb : Math.max(b, cb);
        }
        return Float.isNaN(b) ? rowH : b;
    }

    /**
     * 行の高さ {@code h} に伸ばし、中身を {@code off} だけ下げたセルの断片（中身・行・子の断片は同じ参照。中身の原点 {@code insetTop}
     * だけが変わる）。制約には伸ばした高さを載せ、中身の高さで整形した元の断片とキャッシュの上で取り違えない。
     */
    private static LayoutFragment stretched(LayoutFragment f, float h, float off) {
        if (h == f.height && off == 0) return f;
        ConstraintSpace sp = f.space;
        ConstraintSpace fixed = new ConstraintSpace(sp.availableWidth, sp.fixedWidth, h, sp.percentHeightBase, sp.flags, Floats.Space.EMPTY,
                sp.borders);
        OutOfFlow.Pending[] pending = f.pending;
        if (pending != null && off != 0) {
            pending = new OutOfFlow.Pending[f.pending.length];
            for (int i = 0; i < pending.length; i++) pending[i] = f.pending[i].shift(0, off);
        }
        return new LayoutFragment(f.box, fixed, f.width, h, f.marginLeft, f.topPos, f.topNeg, f.bottomPos, f.bottomNeg, f.insetLeft,
                f.insetTop + off, false, f.flow, f.lines, pending, f.oof, Float.isNaN(f.baseline) ? Float.NaN : f.baseline + off, f.floatsOut);
    }

    /** セルの断片の候補として次のフレームへ残すもの（中身の高さで整形した元の断片と、その他に使った断片）。 */
    private static LayoutFragment[] cellSlots(TableStructure.Cell c, LayoutFragment placed) {
        LayoutFragment[] used = c.slots.used(c.natural);
        if (placed == c.natural) return used;
        int k = used == null ? 0 : used.length;
        LayoutFragment[] out = new LayoutFragment[k + 1];
        out[0] = c.natural;
        if (used != null) System.arraycopy(used, 0, out, 1, k);
        return out;
    }

    private static boolean bottomCaption(BoxNode cap) {
        return cap.style.inherited.captionSide == CssKeywords.CaptionSide.BOTTOM;
    }

    /** {@code border-spacing} の横と縦（1 つなら両方）。 */
    private static float[] borderSpacing(Style st, LayoutSession s) {
        String v = st.inherited.borderSpacing;
        if (v == null || v.isBlank()) return new float[]{0, 0};
        String[] parts = v.trim().split("\\s+");
        float fs = st.inherited.fontSize, rfs = st.inherited.rootFontSizePx;
        float h = LengthResolver.parseDim(parts[0], 0, s.viewportW(), s.viewportH(), fs, rfs);
        float vert = parts.length > 1 ? LengthResolver.parseDim(parts[1], 0, s.viewportW(), s.viewportH(), fs, rfs) : h;
        return new float[]{Math.max(0, LayoutUnit.snap(h)), Math.max(0, LayoutUnit.snap(vert))};
    }

    /** 表の {@code width}（border-box。表の縁で。内在サイズの語は表の内在サイズ）。auto なら NaN。 */
    private static float specifiedWidth(BoxNode node, Work w, float cbw, LayoutSession s) {
        Style st = node.style;
        long v = st.box.width;
        if (CssLength.isKeyword(v, CssLength.KW_MIN_CONTENT) || CssLength.isKeyword(v, CssLength.KW_MAX_CONTENT)
                || CssLength.isKeyword(v, CssLength.KW_FIT_CONTENT)) {
            MinMaxSizes mm = s.cache().minMax(node, s);
            return CssLength.isKeyword(v, CssLength.KW_MAX_CONTENT) ? mm.max : mm.min;
        }
        if (!BoxGeometry.definite(st, v, cbw)) return Float.NaN;
        float raw = BoxGeometry.len(st, v, cbw, s);
        return st.box.boxSizing == CssKeywords.BoxSizing.BORDER_BOX ? Math.max(raw, w.hdec()) : raw + w.hdec();
    }

    /** {@code min-width} / {@code max-width} で幅を挟む（表の縁で）。 */
    private static float clampWidth(Style st, float width, float cbw, float hdec, LayoutSession s) {
        boolean bb = st.box.boxSizing == CssKeywords.BoxSizing.BORDER_BOX;
        if (BoxGeometry.definite(st, st.box.maxWidth, cbw)) {
            float m = BoxGeometry.len(st, st.box.maxWidth, cbw, s);
            width = Math.min(width, bb ? m : m + hdec);
        }
        if (BoxGeometry.definite(st, st.box.minWidth, cbw)) {
            float m = BoxGeometry.len(st, st.box.minWidth, cbw, s);
            width = Math.max(width, bb ? m : m + hdec);
        }
        return Math.max(width, hdec);
    }

    /** 表の {@code height}（border-box。表の縁で）。auto・基準の無い % なら NaN。 */
    private static float specifiedHeight(Style st, Work w, float pct, LayoutSession s) {
        if (!BoxGeometry.definite(st, st.box.height, pct)) return Float.NaN;
        float raw = BoxGeometry.len(st, st.box.height, pct, s);
        return st.box.boxSizing == CssKeywords.BoxSizing.BORDER_BOX ? Math.max(raw, w.vdec()) : raw + w.vdec();
    }

    private static float minHeight(Style st, Work w, float pct, LayoutSession s) {
        if (!BoxGeometry.definite(st, st.box.minHeight, pct)) return 0;
        float raw = BoxGeometry.len(st, st.box.minHeight, pct, s);
        return st.box.boxSizing == CssKeywords.BoxSizing.BORDER_BOX ? raw : raw + w.vdec();
    }

    /** 内在サイズ: 列の最小幅の和・最大幅の和（割合の列を収める幅）に列の間隔と縁を足す。キャプションの最小幅を下回らない。 */
    @Override
    public MinMaxSizes computeMinMaxSizes(BoxNode node, MinMaxContext ctx, LayoutSession s) {
        Work w = new Work(node, new BoxGeometry(node.style, Float.NaN, s), s);
        int n = w.t.columnCount;
        cellContributions(w, s);
        float[] min = new float[n], max = new float[n], percent = TableColumns.percents(w.t, n);
        TableColumns.contributions(w.t, w.hs, min, max, new boolean[n], s);
        float extra = w.spacingW() + w.hdec();
        float lo = TableColumns.sum(min) + extra;
        float hi = Math.max(TableColumns.sum(max), TableColumns.percentRequiredWidth(percent, max)) + extra;
        for (BoxNode cap : w.t.captions) {
            Style cs = cap.style;
            MinMaxSizes c = new BoxGeometry(cs, Float.NaN, s).contribution(cs, s.cache().minMax(cap, s), s);
            float margins = BlockFlow.fixedMargin(cs, cs.box.marginLeft, s) + BlockFlow.fixedMargin(cs, cs.box.marginRight, s);
            lo = Math.max(lo, c.min + margins + w.hdec());
        }
        return new MinMaxSizes(LayoutUnit.snap(lo), LayoutUnit.snap(Math.max(lo, hi)));
    }
}
