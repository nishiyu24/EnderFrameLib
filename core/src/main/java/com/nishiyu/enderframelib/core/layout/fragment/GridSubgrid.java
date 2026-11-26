package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxKind;
import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.css.CssLength;
import com.nishiyu.enderframelib.core.css.Style;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * サブグリッド（CSS Grid 2 §9。計画書 §3.2 の G2。旧 {@code GridLayoutEngine} のサブグリッドの移植）。グリッドの項目である
 * グリッドコンテナが軸に {@code subgrid} を指定すると、その軸のトラックと線の名前を親と共有する。
 *
 * <ul>
 *   <li>親は、サブグリッドの子の寄与を親のトラックへ回す（{@link #addProxies}。端の子にはサブグリッドの余白・枠線・padding を足す）。
 *       サブグリッド自身はその軸の寄与を持たない。</li>
 *   <li>親は、共有する軸の線の名前と（決まっていれば）寸法を {@link Tracks} として {@link ConstraintSpace} に載せてサブグリッドを測る
 *       （値等価で鍵に入るので、親のトラックが変われば測り直す）。</li>
 *   <li>サブグリッドは、その軸のトラックを親の寸法から作り（gap が親と違う分は隣り合うトラックが半分ずつ受け持つ）、線は自分の端の
 *       余白・枠線・padding の分だけ外へはみ出す。項目の領域は内容ボックスで切る。トラックの数は親でまたがる数で、外へ出る項目は最後の
 *       トラックへ寄せる（暗黙のトラックを作らない）。</li>
 * </ul>
 *
 * <p>グリッドの項目でない（親がグリッドでない）サブグリッドは none と同じ（{@link GridTracks#parse}）。</p>
 */
final class GridSubgrid {
    private static final Pattern NAMES = Pattern.compile("\\[([^\\]]*)]");

    private GridSubgrid() { }

    /**
     * 共有する 1 軸: 線の名前（またがる数 + 1 本）・親の gap・親が決めたトラックの寸法（まだ決まっていなければ null）。値等価。
     */
    record Axis(List<List<String>> names, float parentGap, float[] sizes) {
        int span() {
            return names.size() - 1;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Axis a && names.equals(a.names) && Float.floatToIntBits(parentGap) == Float.floatToIntBits(a.parentGap)
                    && Arrays.equals(sizes, a.sizes);
        }

        @Override
        public int hashCode() {
            return (names.hashCode() * 31 + Float.floatToIntBits(parentGap)) * 31 + Arrays.hashCode(sizes);
        }
    }

    /** 親がサブグリッドの項目へ渡す、共有する軸（共有しない軸は null）。 */
    record Tracks(Axis cols, Axis rows) implements ConstraintSpace.Extra { }

    /** 箱 {@code b} がグリッドの項目なら、軸 {@code columns} を親と共有するサブグリッドか。 */
    static boolean subgridded(BoxNode b, boolean columns) {
        if (b.kind != BoxKind.GRID && b.kind != BoxKind.INLINE_GRID) return false;
        return GridTracks.isSubgrid(columns ? b.style.flexGrid.gridTemplateColumns : b.style.flexGrid.gridTemplateRows);
    }

    /** {@code gap} が {@code normal}（宣言が無い）か。サブグリッドの共有する軸では親の gap を使う。 */
    static boolean normalGap(long gap) {
        return !CssLength.isDeclared(gap) || CssLength.isKeyword(gap, CssLength.KW_NORMAL) || CssLength.isKeyword(gap, CssLength.KW_EMPTY);
    }

    /** 項目 {@code it}（親 {@code grid} の中に置いたもの）へ渡す共有の軸。 */
    static Tracks tracks(GridItem it, GridFlow.Grid grid) {
        Axis c = it.colSub ? axis(grid.cols, it.colStart, it.colSpan, grid.colGap, grid.colSizes) : null;
        Axis r = it.rowSub ? axis(grid.rows, it.rowStart, it.rowSpan, grid.rowGap, grid.rowSizes) : null;
        return new Tracks(c, r);
    }

    private static Axis axis(GridTracks.Template t, int start, int span, float gap, float[] sizes) {
        List<List<String>> names = new ArrayList<>(span + 1);
        for (int k = 0; k <= span; k++) {
            int line = start + k;
            names.add(line < t.lineNames.size() ? List.copyOf(t.lineNames.get(line)) : List.of());
        }
        float[] slice = null;
        if (sizes != null) {
            slice = new float[span];
            for (int i = 0; i < span; i++) slice[i] = start + i < sizes.length ? sizes[start + i] : 0;
        }
        return new Axis(List.copyOf(names), gap, slice);
    }

    /** 共有する軸のトラックの定義: またがる数の auto と、親から引き継いだ線の名前に自身の {@code subgrid [名前]...} を足したもの。 */
    static GridTracks.Template template(String value, Axis axis) {
        GridTracks.Template t = new GridTracks.Template();
        int span = axis.span();
        for (int i = 0; i < span; i++) t.tracks.add(GridTracks.Def.AUTO);
        for (int k = 0; k <= span; k++) t.lineNames.add(new ArrayList<>(axis.names().get(k)));
        String own = value == null ? "" : value.trim().substring(Math.min(7, value.trim().length()));
        Matcher m = NAMES.matcher(own);
        for (int line = 0; m.find() && line <= span; line++) {
            for (String n : m.group(1).trim().split("\\s+")) if (!n.isEmpty()) t.lineNames.get(line).add(n);
        }
        return t;
    }

    /** 共有する軸の外へ出た項目を最後のトラックへ寄せる（暗黙のトラックを作らない）。 */
    static void clamp(List<GridItem> items, boolean columns, int span) {
        for (GridItem it : items) {
            int start = it.start(columns), n = it.span(columns);
            if (start >= span) { start = Math.max(0, span - 1); n = 1; }
            else if (start + n > span) n = span - start;
            if (columns) { it.colStart = start; it.colSpan = n; }
            else { it.rowStart = start; it.rowSpan = n; }
        }
    }

    /** 親の寸法から作る共有する軸のトラックの寸法（gap が親と違う分は隣り合うトラックが半分ずつ受け持つ。合計は親のまたがる幅）。 */
    static float[] sizes(Axis axis, float ownGap) {
        float[] parent = axis.sizes();
        int count = parent.length;
        float[] out = new float[count];
        float half = (axis.parentGap() - ownGap) / 2;
        for (int i = 0; i < count; i++) {
            int sides = (i > 0 ? 1 : 0) + (i < count - 1 ? 1 : 0);
            out[i] = Math.max(0, parent[i] + half * sides);
        }
        return out;
    }

    /** 共有する軸の線の位置（内容ボックスの始点が原点。端の余白・枠線・padding の分 {@code edgeStart} だけ外へはみ出す）。 */
    static float[] offsets(float[] sizes, float gap, float edgeStart) {
        int n = sizes.length;
        float[] off = new float[n + 1];
        float pos = -edgeStart;
        for (int i = 0; i < n; i++) {
            off[i] = pos;
            pos += sizes[i] + (i < n - 1 ? gap : 0);
        }
        off[n] = pos;
        return off;
    }

    /** 共有する軸の項目の領域 {始点, 長さ}（線が示す領域を内容ボックス {@code [0, len]} で切る）。 */
    static float[] area(float[] off, float[] sizes, int start, int span, float len) {
        int end = Math.min(sizes.length, start + span);
        float from = Math.max(0, off[start]);
        float to = end > start ? Math.min(len, off[end - 1] + sizes[end - 1]) : from;
        return new float[]{from, Math.max(0, to - from)};
    }

    /**
     * サブグリッドの項目 {@code sg}（親 {@code parent} の中に置いたもの）の子を、親の軸 {@code columns} の寄与として {@code out} へ足す。
     * 行の寄与は {@code sg} を測った断片の中の子の断片から読む。入れ子のサブグリッドは中へ降りる（{@link GridFlow#contributions}）。
     */
    static void addProxies(GridItem sg, GridFlow.Grid parent, boolean columns, List<GridItem> out, LayoutSession s) {
        GridFlow.Grid inner = GridFlow.build(sg.box, Float.NaN, Float.NaN, null, null, s, tracks(sg, parent));
        if (!columns) {
            if (sg.frag == null) return;
            for (GridItem ii : inner.items) {
                ii.frag = childFragment(sg.frag, ii.box);
                if (ii.frag == null) return; // 測った断片と配置が合わない（起きないはず）: 寄与を足さない
            }
        }
        List<GridItem> items = GridFlow.contributions(inner, columns, s);
        int count = columns ? inner.colDefs.size() : inner.rowDefs.size();
        BoxGeometry g = sg.g;
        float edgeStart = columns ? g.mL + g.bL + g.pL : g.mT + g.bT + g.pT;
        float edgeEnd = columns ? g.mR + g.bR + g.pR : g.mB + g.bB + g.pB;
        for (GridItem ii : items) {
            int start = ii.start(columns), span = ii.span(columns);
            float add = (start == 0 ? edgeStart : 0) + (start + span >= count ? edgeEnd : 0);
            GridItem p = new GridItem(ii.box, ii.g, ii.slots);
            if (columns) {
                p.colStart = sg.colStart + start;
                p.colSpan = span;
                p.colMin = ii.colMin + add;
                p.colMax = ii.colMax + add;
            } else {
                p.rowStart = sg.rowStart + start;
                p.rowSpan = span;
                p.rowMin = ii.rowMin + add;
                p.rowMax = ii.rowMax + add;
            }
            out.add(p);
        }
    }

    /** グリッドの断片 {@code f} の中の、箱 {@code b} の子の断片（無ければ null）。 */
    private static LayoutFragment childFragment(LayoutFragment f, BoxNode b) {
        if (f.flow == null) return null;
        for (FlowChunk c : f.flow.chunks) for (LayoutFragment k : c.frags) if (k.box == b) return k;
        return null;
    }
}
