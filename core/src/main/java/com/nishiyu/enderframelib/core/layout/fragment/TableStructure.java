package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxKind;
import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.box.TableBoxes;
import com.nishiyu.enderframelib.core.css.model.Visibility;

import java.util.ArrayList;
import java.util.List;

/**
 * 表の格子（Engine v3 M2b-4。CSS 2.2 §17.2・§17.5。旧 {@code TableLayoutEngine} の {@code collectStructure}・{@code buildGrid}・
 * {@code markCollapsed} の移植）。表の {@link BoxNode#flow}（匿名の箱で補った後の並び。{@code TableBoxes}）から、キャプション・列・
 * 行（{@code thead} の行が先頭、{@code tfoot} の行が末尾）・セルの格子の位置を作る。1 回の整形の間だけの作業域で、断片には残さない。
 */
final class TableStructure {
    /** 格子に置いたセル 1 つと、整形の途中の値。 */
    static final class Cell {
        final BoxNode box;
        final int row, col, colSpan, rowSpan;
        /** 枠線を重ねる表なら表が決めた使う枠線（{@link TableBorders}。分離した表は null）。 */
        TableBorders.Edges borders;
        /** 列幅の寄与（border-box）。 */
        float minW, maxW;
        /** 列幅で測った断片（高さは中身から）と、そのベースライン（border-box の上端から）。 */
        LayoutFragment natural;
        float baseline;
        LayoutCache.Slots slots;

        Cell(BoxNode box, int row, int col, int colSpan, int rowSpan) {
            this.box = box;
            this.row = row;
            this.col = col;
            this.colSpan = colSpan;
            this.rowSpan = rowSpan;
        }
    }

    final List<BoxNode> captions = new ArrayList<>();
    /** 列要素を 1 列 1 要素に展開した並び（{@code span} と列グループの中の列）。 */
    final List<BoxNode> columns = new ArrayList<>();
    /** 行グループ（文書順）。 */
    final List<BoxNode> groups = new ArrayList<>();
    /** 行（表示の順）と、それぞれの行グループ（表の直下の行は null）。 */
    final List<BoxNode> rows = new ArrayList<>();
    final List<BoxNode> rowGroupOf = new ArrayList<>();
    final List<Cell> cells = new ArrayList<>();
    int columnCount;
    boolean[] collapsedRows, collapsedColumns;

    private TableStructure() { }

    static TableStructure of(BoxNode table) {
        TableStructure t = new TableStructure();
        List<BoxNode> header = new ArrayList<>(), body = new ArrayList<>(), footer = new ArrayList<>(), columnEls = new ArrayList<>();
        for (BoxNode child : table.flow.toList()) {
            switch (child.kind) {
                case TABLE_CAPTION -> t.captions.add(child);
                case TABLE_COLUMN -> columnEls.add(child);
                case TABLE_ROW -> body.add(child);
                case TABLE_ROW_GROUP -> {
                    String d = child.style.box.display == null ? "" : child.style.box.display.trim().toLowerCase(java.util.Locale.ROOT);
                    // 最初の thead / tfoot だけが先頭・末尾に動く。2 つ目以降は tbody と同じ位置
                    List<BoxNode> target = d.equals("table-header-group") && header.isEmpty() ? header
                            : d.equals("table-footer-group") && footer.isEmpty() ? footer : body;
                    target.add(child);
                    t.groups.add(child);
                }
                default -> { } // 匿名の箱で補った後は来ない（display: none・流れの外の箱）
            }
        }
        for (List<BoxNode> part : List.of(header, body, footer)) {
            for (BoxNode item : part) {
                if (item.kind == BoxKind.TABLE_ROW) {
                    t.rows.add(item);
                    t.rowGroupOf.add(null);
                    continue;
                }
                for (BoxNode row : item.flow.toList()) {
                    if (row.kind != BoxKind.TABLE_ROW) continue;
                    t.rows.add(row);
                    t.rowGroupOf.add(item);
                }
            }
        }
        t.buildGrid(columnEls);
        t.markCollapsed();
        return t;
    }

    /** セルを格子に並べる。{@code rowspan} で上の行から伸びてきたセルの位置は飛ばす。 */
    private void buildGrid(List<BoxNode> columnEls) {
        List<boolean[]> occupied = new ArrayList<>();
        int cols = 0;
        for (int r = 0; r < rows.size(); r++) {
            int c = 0;
            for (BoxNode cell : rows.get(r).flow.toList()) {
                if (cell.kind != BoxKind.TABLE_CELL) continue;
                while (isOccupied(occupied, r, c)) c++;
                int colSpan = TableBoxes.colSpan(cell);
                int rowSpan = TableBoxes.rowSpan(cell);
                // rowspan="0" は行グループの最後まで（はみ出す指定も同じ）
                if (rowSpan == 0 || r + rowSpan > rows.size()) rowSpan = lastRowInGroup(r) - r + 1;
                Cell slot = new Cell(cell, r, c, colSpan, Math.max(1, rowSpan));
                for (int rr = r; rr < r + slot.rowSpan; rr++) {
                    for (int cc = c; cc < c + colSpan; cc++) markOccupied(occupied, rr, cc);
                }
                cells.add(slot);
                c += colSpan;
                cols = Math.max(cols, c);
            }
        }
        expandColumns(columnEls, columns);
        columnCount = Math.max(cols, columns.size());
    }

    private int lastRowInGroup(int r) {
        BoxNode group = rowGroupOf.get(r);
        int last = r;
        while (last + 1 < rows.size() && group != null && rowGroupOf.get(last + 1) == group) last++;
        return last;
    }

    /** 列要素を 1 列 1 要素に展開する（列グループは中の列、列を持たない列グループと列は {@code span} の数）。 */
    private static void expandColumns(List<BoxNode> els, List<BoxNode> out) {
        for (BoxNode col : els) {
            List<BoxNode> inner = new ArrayList<>();
            for (BoxNode k : col.flow.toList()) if (k.kind == BoxKind.TABLE_COLUMN) inner.add(k);
            if (!inner.isEmpty()) {
                expandColumns(inner, out);
                continue;
            }
            int span = Math.max(1, col.span & 0xFFFF);
            for (int i = 0; i < span; i++) out.add(col);
        }
    }

    /** {@code visibility: collapse} の行と列（可視性は継承されるので、行グループ・列グループの指定も届く）。 */
    private void markCollapsed() {
        collapsedRows = new boolean[rows.size()];
        for (int r = 0; r < rows.size(); r++) collapsedRows[r] = collapsed(rows.get(r));
        collapsedColumns = new boolean[columnCount];
        for (int c = 0; c < columns.size() && c < columnCount; c++) collapsedColumns[c] = collapsed(columns.get(c));
    }

    private static boolean collapsed(BoxNode b) {
        return b.style.inherited.visibility == Visibility.COLLAPSE;
    }

    private static boolean isOccupied(List<boolean[]> occupied, int r, int c) {
        if (r >= occupied.size()) return false;
        boolean[] row = occupied.get(r);
        return c < row.length && row[c];
    }

    private static void markOccupied(List<boolean[]> occupied, int r, int c) {
        while (occupied.size() <= r) occupied.add(new boolean[0]);
        boolean[] row = occupied.get(r);
        if (c >= row.length) {
            row = java.util.Arrays.copyOf(row, Math.max(c + 1, row.length * 2));
            occupied.set(r, row);
        }
        row[c] = true;
    }
}
