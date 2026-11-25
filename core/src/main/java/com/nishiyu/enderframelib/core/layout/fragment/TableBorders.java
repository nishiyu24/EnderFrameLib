package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.css.model.BorderStyle;
import com.nishiyu.enderframelib.core.kernel.LayoutUnit;

/**
 * 枠線を重ねる表（{@code border-collapse: collapse}。CSS 2.2 §17.6.2。旧 {@code TableLayoutEngine.collapseBorders} の移植）。
 * 隣り合う 2 本の枠線は太い方の 1 本になり（どちらかが {@code hidden} なら 0）、それを両側の箱が半分ずつ持つ。表の外周は表の枠線と
 * 外側のセルの枠線の太い方で、表が半分、セルが残りの半分を持つ。
 *
 * <p>結果は値（{@link Edges}）で返し、箱へ書き込まない。セルの分は {@link ConstraintSpace#borders} として整形に渡す
 * （旧経路の {@code collapsedBorders} の逆流書き込みの代わり）。</p>
 *
 * <p>LIMITATION(制約・旧経路と同じ): 重ねるのはセルどうしと表の枠線だけで、行・行グループ・列の枠線は重ねない。{@code border-style} の
 * 優先順位は {@code hidden} が勝つことだけを見る。</p>
 */
public final class TableBorders {
    /** 使う枠線の幅（上・右・下・左。px）。値等価。 */
    public record Edges(float top, float right, float bottom, float left) { }

    private TableBorders() { }

    /** 枠線を重ねる表 {@code table} の使う枠線（描画の一時アダプタが読む。表の格子から求め直す）。 */
    public static Edges ofTable(BoxNode table, LayoutSession s) {
        return collapse(table, TableStructure.of(table), s);
    }

    /** 格子の各セルに使う枠線を置き（{@link TableStructure.Cell#borders}）、表の使う枠線を返す。 */
    static Edges collapse(BoxNode table, TableStructure t, LayoutSession s) {
        int rows = t.rows.size(), cols = t.columnCount;
        TableStructure.Cell[][] at = new TableStructure.Cell[rows][cols];
        for (TableStructure.Cell c : t.cells) {
            for (int r = c.row; r < c.row + c.rowSpan; r++)
                for (int k = c.col; k < c.col + c.colSpan && k < cols; k++) at[r][k] = c;
        }
        Style ts = table.style;
        float tableTop = width(ts, 0, s), tableRight = width(ts, 1, s), tableBottom = width(ts, 2, s), tableLeft = width(ts, 3, s);
        float outerTop = tableTop, outerRight = tableRight, outerBottom = tableBottom, outerLeft = tableLeft;
        for (TableStructure.Cell c : t.cells) {
            Style cs = c.box.style;
            TableStructure.Cell above = c.row > 0 ? at[c.row - 1][c.col] : null;
            TableStructure.Cell below = c.row + c.rowSpan < rows ? at[c.row + c.rowSpan][c.col] : null;
            TableStructure.Cell before = c.col > 0 ? at[c.row][c.col - 1] : null;
            TableStructure.Cell after = c.col + c.colSpan < cols ? at[c.row][c.col + c.colSpan] : null;
            float top = joint(cs, 0, above != null ? above.box.style : ts, 2, above != null ? width(above.box.style, 2, s) : tableTop, s);
            float bottom = joint(cs, 2, below != null ? below.box.style : ts, 0, below != null ? width(below.box.style, 0, s) : tableBottom, s);
            float left = joint(cs, 3, before != null ? before.box.style : ts, 1, before != null ? width(before.box.style, 1, s) : tableLeft, s);
            float right = joint(cs, 1, after != null ? after.box.style : ts, 3, after != null ? width(after.box.style, 3, s) : tableRight, s);
            if (above == null) outerTop = Math.max(outerTop, top);
            if (below == null) outerBottom = Math.max(outerBottom, bottom);
            if (before == null) outerLeft = Math.max(outerLeft, left);
            if (after == null) outerRight = Math.max(outerRight, right);
            c.borders = half(top, right, bottom, left);
        }
        return half(outerTop, outerRight, outerBottom, outerLeft);
    }

    private static Edges half(float top, float right, float bottom, float left) {
        return new Edges(LayoutUnit.snap(top / 2), LayoutUnit.snap(right / 2), LayoutUnit.snap(bottom / 2), LayoutUnit.snap(left / 2));
    }

    /** 隣り合う 2 本の枠線の幅。どちらかが {@code hidden} なら 0、そうでなければ太い方（CSS 2.2 §17.6.2.1）。 */
    private static float joint(Style a, int sideA, Style b, int sideB, float widthB, LayoutSession s) {
        if (style(a, sideA) == BorderStyle.HIDDEN || style(b, sideB) == BorderStyle.HIDDEN) return 0;
        return Math.max(width(a, sideA, s), widthB);
    }

    private static BorderStyle style(Style st, int side) {
        var b = st.box;
        return side == 0 ? b.borderTopStyle : side == 1 ? b.borderRightStyle : side == 2 ? b.borderBottomStyle : b.borderLeftStyle;
    }

    /** 枠線の幅（{@code border-style} が none / hidden なら 0。上 0・右 1・下 2・左 3）。 */
    private static float width(Style st, int side, LayoutSession s) {
        BorderStyle bs = style(st, side);
        if (bs == null || bs == BorderStyle.NONE || bs == BorderStyle.HIDDEN) return 0;
        var b = st.box;
        long w = side == 0 ? b.borderTopWidth : side == 1 ? b.borderRightWidth : side == 2 ? b.borderBottomWidth : b.borderLeftWidth;
        return Math.max(0, BoxGeometry.len(st, w, 0, s));
    }
}
