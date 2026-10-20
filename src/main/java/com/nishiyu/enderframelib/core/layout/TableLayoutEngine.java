package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutContext;
import com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutMetrics;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssProperty.CssKeywords;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.css.LengthResolver;
import com.nishiyu.enderframelib.core.dom.HtmlNode.NodeType;
import com.nishiyu.enderframelib.core.layout.LayoutBox.Display.Inner;
import com.nishiyu.enderframelib.core.layout.LayoutBox.ScrollExtent;
import com.nishiyu.enderframelib.core.layout.LayoutBox.SizingMode;
import com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutContext.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutContext.FormattingContext;
import com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutEngineState;

import java.util.ArrayList;
import java.util.List;

/**
 * 表の整形（{@code display: table} / {@code inline-table} と、その中の行グループ・行）。CSS 2.2 §17。
 *
 * 表がセルの格子（{@code colspan} / {@code rowspan} を含む）を組み、列幅・行の高さ・位置をすべて決める。
 * 行グループと行は測定されず、表が決めた寸法と、子を置く位置（{@link Placements}）を受け取るだけ。
 *
 * <ul>
 *   <li>列幅: 自動レイアウトはセルの min-content / max-content から（表の幅が max-content の合計より
 *       広ければ幅の指定が無い列へ max-content の比で配り、狭ければ min と max の間を補間する）。
 *       {@code table-layout: fixed} は列要素と 1 行目のセルの幅で決め、残りを等分する</li>
 *   <li>{@code border-spacing}（分離）/ {@code border-collapse: collapse}（隣り合う枠線を 1 本に重ね、
 *       セルと表の枠線は重ねた幅の半分ずつ）</li>
 *   <li>キャプション（{@code caption-side}）、セルの {@code vertical-align}（top / middle / bottom / baseline）</li>
 * </ul>
 *
 * 何が壊れていたか: W3 の最小の表レイアウトは列幅をセルの幅の最大で決めるだけで、{@code colspan} /
 * {@code rowspan}・{@code border-spacing} / {@code border-collapse}・キャプション・セルの縦揃えが無く、
 * HTML の {@code <table>} は ua.css が flex の行として組んでいた。
 *
 * LIMITATION(制約): 列幅の % 指定は読まない（auto として扱う）。{@code border-collapse} で重ねるのは
 * セルどうしと表の枠線だけで、行・行グループ・列の枠線は重ねない（枠線の太さだけを比べ、
 * {@code border-style} の優先順位は見ない）。{@code empty-cells} と {@code visibility: collapse} は無い。
 * README の "Not supported" に対応。
 */
public class TableLayoutEngine extends LayoutEngine {

    /** セルの max-content を測る文脈。内容の幅に縮む。 */
    private static final FormattingContext SHRINK_CELL = new FormattingContext("block", "stretch", true);
    private static final FormattingContext FIXED_CELL = FormattingContext.BLOCK;

    @Override
    public float measureContent(LayoutBox box, ConstraintSpace space, LayoutContext ctx) {
        // 行グループと行は表の測定の中で寸法が決まる
        if (box.display.inner() != Inner.TABLE) return Math.max(0, box.w - box.horizontalDecoration());
        float parentH = space.percentageBlockSize();
        float rootW = ctx.viewport().rootW(), rootH = ctx.viewport().rootH();

        TableState state = new TableState();
        box.engineState = state;
        collectStructure(box, state);
        buildGrid(state);

        state.collapse = box.style.keywordOf(CssProperty.BORDER_COLLAPSE, CssKeywords.BorderCollapse.class)
                == CssKeywords.BorderCollapse.COLLAPSE;
        if (state.collapse) {
            collapseBorders(box, state, rootW, rootH);
        } else {
            float[] spacing = borderSpacing(box.style, rootW, rootH);
            state.hSpacing = spacing[0];
            state.vSpacing = spacing[1];
            for (CellSlot slot : state.cells) slot.cell.collapsedBorders = null;
        }

        int n = state.columnCount;
        float spacingW = n > 0 ? state.hSpacing * (n + 1) : 0;
        boolean definite = box.sizingMode == SizingMode.DEFINITE;
        boolean fixed = definite
                && box.style.keywordOf(CssProperty.TABLE_LAYOUT, CssKeywords.TableLayout.class) == CssKeywords.TableLayout.FIXED;

        float[] widths;
        if (fixed) {
            float target = Math.max(0, box.w - box.horizontalDecoration() - spacingW);
            widths = fixedColumnWidths(state, target, ctx, rootW, rootH, parentH);
        } else {
            float[] min = new float[n], max = new float[n];
            boolean[] specified = new boolean[n];
            columnContributions(state, min, max, specified, parentH, ctx, rootW, rootH);
            float sumMin = sum(min), sumMax = sum(max);
            float target;
            if (definite) {
                target = Math.max(sumMin, box.w - box.horizontalDecoration() - spacingW);
            } else {
                float available = Math.max(0, space.availableInlineSize() - spacingW);
                target = Math.max(sumMin, Math.min(sumMax, available));
            }
            widths = distributeWidths(min, max, specified, target);
        }
        state.columnWidths = widths;

        float contentW = sum(widths) + spacingW;
        if (definite) contentW = Math.max(contentW, box.w - box.horizontalDecoration());
        state.contentW = contentW;

        // 列幅でセルを測り直す
        for (CellSlot slot : state.cells) {
            float w = spanWidth(state, slot.col, slot.colSpan);
            slot.cell.overrideWidth = w;
            try {
                slot.cell.measure(new ConstraintSpace(w, parentH, FIXED_CELL), ctx);
            } finally {
                slot.cell.overrideWidth = Float.NaN;
            }
            slot.naturalHeight = slot.cell.h;
        }
        for (LayoutBox caption : state.captions) {
            caption.measure(new ConstraintSpace(contentW, parentH, FormattingContext.BLOCK), ctx);
        }
        return contentW;
    }

    @Override
    public float contentBlockSize(LayoutBox box, ConstraintSpace space, LayoutContext ctx) {
        if (!(box.engineState instanceof TableState state) || box.display.inner() != Inner.TABLE) {
            return Math.max(0, box.h - box.verticalDecoration());
        }
        float rootW = ctx.viewport().rootW(), rootH = ctx.viewport().rootH();
        int rows = state.rows.size();
        float[] heights = new float[rows];
        float[] above = new float[rows];
        float[] below = new float[rows];

        for (int r = 0; r < rows; r++) {
            LayoutBox row = state.rows.get(r);
            if (row.style.containsKey(CssProperty.HEIGHT)) {
                heights[r] = row.style.length(CssProperty.HEIGHT, space.percentageBlockSize(), rootW, rootH);
            }
        }
        for (CellSlot slot : state.cells) {
            if (slot.rowSpan != 1) continue;
            heights[slot.row] = Math.max(heights[slot.row], slot.naturalHeight);
            if ("baseline".equals(verticalAlign(slot.cell))) {
                slot.baseline = cellBaseline(slot.cell, slot.naturalHeight);
                above[slot.row] = Math.max(above[slot.row], slot.baseline);
                below[slot.row] = Math.max(below[slot.row], slot.naturalHeight - slot.baseline);
                heights[slot.row] = Math.max(heights[slot.row], above[slot.row] + below[slot.row]);
            }
        }
        // 複数行にまたがるセルが入りきらなければ、またがる最後の行を高くする
        for (CellSlot slot : state.cells) {
            if (slot.rowSpan == 1) continue;
            float have = state.vSpacing * (slot.rowSpan - 1);
            for (int r = slot.row; r < slot.row + slot.rowSpan; r++) have += heights[r];
            if (slot.naturalHeight > have) heights[slot.row + slot.rowSpan - 1] += slot.naturalHeight - have;
        }
        state.rowHeights = heights;

        // セルは行の高さまで伸び、中身は vertical-align でずらす
        for (CellSlot slot : state.cells) {
            float h = state.vSpacing * (slot.rowSpan - 1);
            for (int r = slot.row; r < slot.row + slot.rowSpan; r++) h += heights[r];
            slot.cell.h = h;
            float free = h - slot.naturalHeight;
            slot.cell.contentOffsetY = switch (verticalAlign(slot.cell)) {
                case "middle" -> free / 2;
                case "bottom" -> free;
                case "baseline" -> slot.rowSpan == 1 ? above[slot.row] - slot.baseline : 0;
                default -> 0;
            };
        }

        float captionTop = 0, captionBottom = 0;
        for (LayoutBox caption : state.captions) {
            float outer = caption.h + caption.mT + caption.mB;
            if (caption.style.keywordOf(CssProperty.CAPTION_SIDE, CssKeywords.CaptionSide.class) == CssKeywords.CaptionSide.BOTTOM) {
                captionBottom += outer;
            }
            else captionTop += outer;
        }
        float gridH = rows > 0 ? sum(heights) + state.vSpacing * (rows + 1) : 0;
        placeChildren(box, state, captionTop, gridH);
        return captionTop + gridH + captionBottom;
    }

    @Override
    public void finishLayout(LayoutBox box, ConstraintSpace space, LayoutContext ctx) {
        measureAbsoluteChildren(box, ctx);
    }

    @Override
    public void arrangeChildren(LayoutBox box, float innerX, float innerY, LayoutContext ctx) {
        List<Placement> placements = box.engineState instanceof TableState t ? t.placements
                : box.engineState instanceof Placements p ? p.list() : List.of();
        float innerW = Math.max(0, box.w - box.horizontalDecoration());
        for (Placement p : placements) p.box().layout(innerX + p.x(), innerY + p.y(), innerW, p.box().h, ctx);
    }

    // ==========================================
    // 構造
    // ==========================================

    /** 子をキャプション・列・行に分ける。行は thead の行を先頭、tfoot の行を末尾に並べる（CSS 2.2 §17.2）。 */
    private static void collectStructure(LayoutBox table, TableState state) {
        List<LayoutBox> header = new ArrayList<>(), body = new ArrayList<>(), footer = new ArrayList<>();
        for (LayoutBox child : table.getFlowChildren()) {
            switch (child.display.inner()) {
                case TABLE_CAPTION -> state.captions.add(child);
                case TABLE_COLUMN -> state.columns.add(child);
                case TABLE_ROW -> body.add(child);
                case TABLE_ROW_GROUP -> {
                    String d = child.style.keyword(CssProperty.DISPLAY, "table-row-group");
                    // 最初の thead / tfoot だけが先頭・末尾に動く。2 つ目以降は tbody と同じ位置
                    List<LayoutBox> target = d.equals("table-header-group") && header.isEmpty() ? header
                            : d.equals("table-footer-group") && footer.isEmpty() ? footer : body;
                    target.add(child);
                    state.groups.add(child);
                }
                default -> {
                }
            }
        }
        for (List<LayoutBox> part : List.of(header, body, footer)) {
            for (LayoutBox item : part) {
                if (item.display.inner() == Inner.TABLE_ROW) {
                    state.rows.add(item);
                    state.rowGroups.add(null);
                } else {
                    for (LayoutBox row : item.getFlowChildren()) {
                        if (row.display.inner() != Inner.TABLE_ROW) continue;
                        state.rows.add(row);
                        state.rowGroups.add(item);
                    }
                }
            }
        }
    }

    /** セルを格子に並べる。{@code rowspan} で上の行から伸びてきたセルの位置は飛ばす。 */
    private static void buildGrid(TableState state) {
        List<boolean[]> occupied = new ArrayList<>();
        int columns = 0;
        for (int r = 0; r < state.rows.size(); r++) {
            LayoutBox row = state.rows.get(r);
            int c = 0;
            for (LayoutBox cell : row.getFlowChildren()) {
                if (cell.display.inner() != Inner.TABLE_CELL) continue;
                while (isOccupied(occupied, r, c)) c++;
                int colSpan = spanAttr(cell, "colspan", 1000);
                int rowSpan = spanAttr(cell, "rowspan", 65534);
                // rowspan="0" は行グループの最後まで
                if (rowSpan == 0 || r + rowSpan > state.rows.size()) rowSpan = lastRowInGroup(state, r) - r + 1;
                CellSlot slot = new CellSlot(cell, r, c, colSpan, Math.max(1, rowSpan));
                for (int rr = r; rr < r + slot.rowSpan; rr++) {
                    for (int cc = c; cc < c + colSpan; cc++) markOccupied(occupied, rr, cc);
                }
                state.cells.add(slot);
                c += colSpan;
                columns = Math.max(columns, c);
            }
        }
        for (LayoutBox col : expandColumns(state.columns)) state.columnElements.add(col);
        state.columnCount = Math.max(columns, state.columnElements.size());
    }

    private static int lastRowInGroup(TableState state, int r) {
        LayoutBox group = state.rowGroups.get(r);
        int last = r;
        while (last + 1 < state.rows.size() && group != null && state.rowGroups.get(last + 1) == group) last++;
        return last;
    }

    /** 列要素を 1 列 1 要素に展開する（{@code span} 属性と列グループの中の列）。 */
    private static List<LayoutBox> expandColumns(List<LayoutBox> columns) {
        List<LayoutBox> out = new ArrayList<>();
        for (LayoutBox col : columns) {
            List<LayoutBox> inner = col.getFlowChildren();
            if (!inner.isEmpty()) {
                out.addAll(expandColumns(inner));
            } else {
                int span = spanAttr(col, "span", 1000);
                for (int i = 0; i < Math.max(1, span); i++) out.add(col);
            }
        }
        return out;
    }

    private static int spanAttr(LayoutBox box, String name, int limit) {
        String v = box.node.attrs.get(name);
        if (v == null) return 1;
        try {
            int n = Integer.parseInt(v.trim());
            if (n == 0 && name.equals("rowspan")) return 0;
            return Math.max(1, Math.min(limit, n));
        } catch (NumberFormatException e) {
            return 1;
        }
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
            boolean[] grown = new boolean[Math.max(c + 1, row.length * 2)];
            System.arraycopy(row, 0, grown, 0, row.length);
            row = grown;
            occupied.set(r, row);
        }
        row[c] = true;
    }

    // ==========================================
    // 枠線
    // ==========================================

    /** {@code border-spacing} の横と縦（1 つなら両方）。 */
    private static float[] borderSpacing(ComputedStyle style, float rootW, float rootH) {
        String v = style.get(CssProperty.BORDER_SPACING);
        if (v == null || v.isBlank()) return new float[]{0, 0};
        String[] parts = v.trim().split("\\s+");
        float h = LengthResolver.parseDim(parts[0], 0, rootW, rootH, style.fontSizePx(), style.rootFontSizePx());
        float vert = parts.length > 1
                ? LengthResolver.parseDim(parts[1], 0, rootW, rootH, style.fontSizePx(), style.rootFontSizePx()) : h;
        return new float[]{Math.max(0, h), Math.max(0, vert)};
    }

    /**
     * 枠線を重ねる。隣り合う 2 本は太い方の 1 本になり、それを両側の箱が半分ずつ持つ。
     * 表の外周は表の枠線と外側のセルの枠線の太い方で、表がその半分、セルが残りの半分を持つ。
     */
    private static void collapseBorders(LayoutBox table, TableState state, float rootW, float rootH) {
        int rows = state.rows.size(), cols = state.columnCount;
        CellSlot[][] at = new CellSlot[rows][cols];
        for (CellSlot s : state.cells) {
            for (int r = s.row; r < s.row + s.rowSpan; r++) {
                for (int c = s.col; c < s.col + s.colSpan && c < cols; c++) at[r][c] = s;
            }
        }
        float tableTop = border(table, CssProperty.BORDER_TOP_WIDTH, rootW, rootH);
        float tableRight = border(table, CssProperty.BORDER_RIGHT_WIDTH, rootW, rootH);
        float tableBottom = border(table, CssProperty.BORDER_BOTTOM_WIDTH, rootW, rootH);
        float tableLeft = border(table, CssProperty.BORDER_LEFT_WIDTH, rootW, rootH);
        float outerTop = tableTop, outerRight = tableRight, outerBottom = tableBottom, outerLeft = tableLeft;

        for (CellSlot s : state.cells) {
            float top = border(s.cell, CssProperty.BORDER_TOP_WIDTH, rootW, rootH);
            float right = border(s.cell, CssProperty.BORDER_RIGHT_WIDTH, rootW, rootH);
            float bottom = border(s.cell, CssProperty.BORDER_BOTTOM_WIDTH, rootW, rootH);
            float left = border(s.cell, CssProperty.BORDER_LEFT_WIDTH, rootW, rootH);
            CellSlot above = s.row > 0 ? at[s.row - 1][s.col] : null;
            CellSlot below = s.row + s.rowSpan < rows ? at[s.row + s.rowSpan][s.col] : null;
            CellSlot before = s.col > 0 ? at[s.row][s.col - 1] : null;
            CellSlot after = s.col + s.colSpan < cols ? at[s.row][s.col + s.colSpan] : null;
            top = Math.max(top, above != null ? border(above.cell, CssProperty.BORDER_BOTTOM_WIDTH, rootW, rootH) : tableTop);
            bottom = Math.max(bottom, below != null ? border(below.cell, CssProperty.BORDER_TOP_WIDTH, rootW, rootH) : tableBottom);
            left = Math.max(left, before != null ? border(before.cell, CssProperty.BORDER_RIGHT_WIDTH, rootW, rootH) : tableLeft);
            right = Math.max(right, after != null ? border(after.cell, CssProperty.BORDER_LEFT_WIDTH, rootW, rootH) : tableRight);
            if (above == null) outerTop = Math.max(outerTop, top);
            if (below == null) outerBottom = Math.max(outerBottom, bottom);
            if (before == null) outerLeft = Math.max(outerLeft, left);
            if (after == null) outerRight = Math.max(outerRight, right);
            s.cell.collapsedBorders = new float[]{top / 2, right / 2, bottom / 2, left / 2};
        }
        table.collapsedBorders = new float[]{outerTop / 2, outerRight / 2, outerBottom / 2, outerLeft / 2};
        table.bT = outerTop / 2;
        table.bR = outerRight / 2;
        table.bB = outerBottom / 2;
        table.bL = outerLeft / 2;
    }

    /** 枠線の幅。{@code border-style} が none / hidden なら 0。 */
    private static float border(LayoutBox box, String widthProp, float rootW, float rootH) {
        String side = widthProp.substring("border-".length(), widthProp.length() - "-width".length());
        CssKeywords.BorderStyle style = box.style.keywordOf("border-" + side + "-style", CssKeywords.BorderStyle.class);
        if (style == CssKeywords.BorderStyle.NONE || style == CssKeywords.BorderStyle.HIDDEN) return 0;
        return box.style.length(widthProp, 0, rootW, rootH);
    }

    // ==========================================
    // 列幅
    // ==========================================

    /** 自動レイアウトの列ごとの最小・最大の幅（CSS 2.2 §17.5.2.2）。 */
    private static void columnContributions(TableState state, float[] min, float[] max, boolean[] specified,
                                            float parentH, LayoutContext ctx, float rootW, float rootH) {
        for (int c = 0; c < state.columnElements.size(); c++) {
            LayoutBox col = state.columnElements.get(c);
            Float w = fixedWidth(col.style, rootW, rootH);
            if (w != null) {
                min[c] = Math.max(min[c], w);
                max[c] = Math.max(max[c], w);
                specified[c] = true;
            }
        }
        for (CellSlot slot : state.cells) {
            LayoutBox cell = slot.cell;
            cell.measure(new ConstraintSpace(LayoutMetrics.UNBOUNDED_WIDTH, parentH, SHRINK_CELL), ctx);
            boolean hasWidth = fixedWidth(cell.style, rootW, rootH) != null;
            float cellMax = cell.w;
            boolean hasContent = cell.node.nodeType == NodeType.TEXT || !cell.getFlowChildren().isEmpty();
            float cellMin = hasContent ? cell.getMinContentWidth(ctx) : cell.horizontalDecoration();
            if (hasWidth) cellMin = Math.max(cellMin, cell.w);
            slot.minWidth = cellMin;
            slot.maxWidth = Math.max(cellMin, cellMax);
            if (slot.colSpan == 1) {
                min[slot.col] = Math.max(min[slot.col], slot.minWidth);
                max[slot.col] = Math.max(max[slot.col], slot.maxWidth);
                specified[slot.col] |= hasWidth;
            }
        }
        // 複数列にまたがるセル: 足りない分を、またがる列の最大幅の比で配る（全部 0 なら等分）
        for (CellSlot slot : state.cells) {
            if (slot.colSpan == 1) continue;
            float spacing = state.hSpacing * (slot.colSpan - 1);
            growSpan(min, slot.col, slot.colSpan, slot.minWidth - spacing, max);
            growSpan(max, slot.col, slot.colSpan, slot.maxWidth - spacing, max);
        }
        for (int c = 0; c < max.length; c++) max[c] = Math.max(max[c], min[c]);
    }

    private static void growSpan(float[] target, int start, int span, float needed, float[] weights) {
        float have = 0, weight = 0;
        for (int c = start; c < start + span; c++) {
            have += target[c];
            weight += weights[c];
        }
        float deficit = needed - have;
        if (deficit <= 0) return;
        for (int c = start; c < start + span; c++) {
            target[c] += weight > 0 ? deficit * (weights[c] / weight) : deficit / span;
        }
    }

    /**
     * 表の幅を列に配る。max の合計以上なら余りを幅の指定が無い列へ（無ければ全列へ）max の比で、
     * min と max の間なら各列を同じ割合だけ max に近づける。
     */
    private static float[] distributeWidths(float[] min, float[] max, boolean[] specified, float target) {
        int n = min.length;
        float[] widths = new float[n];
        float sumMin = sum(min), sumMax = sum(max);
        if (target >= sumMax) {
            System.arraycopy(max, 0, widths, 0, n);
            float extra = target - sumMax;
            if (extra <= 0 || n == 0) return widths;
            boolean hasAuto = false;
            for (boolean s : specified) hasAuto |= !s;
            float weight = 0;
            int count = 0;
            for (int c = 0; c < n; c++) {
                if (hasAuto && specified[c]) continue;
                weight += max[c];
                count++;
            }
            for (int c = 0; c < n; c++) {
                if (hasAuto && specified[c]) continue;
                widths[c] += weight > 0 ? extra * (max[c] / weight) : extra / count;
            }
        } else if (target > sumMin && sumMax > sumMin) {
            float ratio = (target - sumMin) / (sumMax - sumMin);
            for (int c = 0; c < n; c++) widths[c] = min[c] + (max[c] - min[c]) * ratio;
        } else {
            System.arraycopy(min, 0, widths, 0, n);
        }
        return widths;
    }

    /**
     * {@code table-layout: fixed}: 列要素と 1 行目のセルの幅で列を決め、幅の無い列で残りを等分する
     * （CSS 2.2 §17.5.2.1）。指定の合計が表より広ければ表が広がる。
     */
    private static float[] fixedColumnWidths(TableState state, float target, LayoutContext ctx,
                                             float rootW, float rootH, float parentH) {
        int n = state.columnCount;
        float[] widths = new float[n];
        boolean[] known = new boolean[n];
        for (int c = 0; c < state.columnElements.size() && c < n; c++) {
            Float w = fixedWidth(state.columnElements.get(c).style, rootW, rootH);
            if (w != null) {
                widths[c] = w;
                known[c] = true;
            }
        }
        for (CellSlot slot : state.cells) {
            if (slot.row != 0) continue;
            Float w = fixedWidth(slot.cell.style, rootW, rootH);
            if (w == null) continue;
            // 指定は内容ボックスの幅なので、パディングと枠線を足す（border-box ならそのまま）
            slot.cell.measure(new ConstraintSpace(target, parentH, FIXED_CELL), ctx);
            float outer = slot.cell.w;
            float spacing = state.hSpacing * (slot.colSpan - 1);
            for (int c = slot.col; c < slot.col + slot.colSpan; c++) {
                if (known[c]) continue;
                widths[c] = (outer - spacing) / slot.colSpan;
                known[c] = true;
            }
        }
        float used = 0;
        int unknown = 0;
        for (int c = 0; c < n; c++) {
            if (known[c]) used += widths[c];
            else unknown++;
        }
        float rest = Math.max(0, target - used);
        if (unknown > 0) {
            for (int c = 0; c < n; c++) if (!known[c]) widths[c] = rest / unknown;
        } else if (n > 0 && rest > 0) {
            for (int c = 0; c < n; c++) widths[c] += rest / n;
        }
        return widths;
    }

    /** px で確定する {@code width}（auto・%・内容のキーワードは null）。 */
    private static Float fixedWidth(ComputedStyle style, float rootW, float rootH) {
        String w = style.get(CssProperty.WIDTH);
        if (w == null || "auto".equalsIgnoreCase(w.trim()) || w.contains("%") || LayoutMetrics.isIntrinsicSizeKeyword(w)) {
            return null;
        }
        return style.length(CssProperty.WIDTH, 0, rootW, rootH);
    }

    private static float spanWidth(TableState state, int col, int span) {
        float w = state.hSpacing * (span - 1);
        for (int c = col; c < col + span && c < state.columnWidths.length; c++) w += state.columnWidths[c];
        return w;
    }

    // ==========================================
    // 高さと位置
    // ==========================================

    private static String verticalAlign(LayoutBox cell) {
        String v = cell.style.keyword(CssProperty.VERTICAL_ALIGN, "baseline");
        return switch (v) {
            case "top", "middle", "bottom" -> v;
            default -> "baseline";
        };
    }

    /** セルのベースライン（border box の上端から）。行が無ければ内容ボックスの下端（CSS 2.2 §17.5.3）。 */
    private static float cellBaseline(LayoutBox cell, float naturalHeight) {
        Float b = BlockLayoutEngine.firstBaseline(cell);
        return b != null ? b : naturalHeight - cell.pB - cell.bB;
    }

    /** 行グループ・行・セル・キャプションの寸法と、それぞれの親の内容ボックスからの位置を決める。 */
    private static void placeChildren(LayoutBox table, TableState state, float captionTop, float gridH) {
        float contentW = state.contentW;
        float hs = state.hSpacing, vs = state.vSpacing;
        List<Placement> tablePlacements = new ArrayList<>();

        float topY = 0, bottomY = captionTop + gridH;
        for (LayoutBox caption : state.captions) {
            if ("bottom".equals(caption.style.keyword(CssProperty.CAPTION_SIDE, "top"))) {
                tablePlacements.add(new Placement(caption, 0, bottomY));
                bottomY += caption.h + caption.mT + caption.mB;
            } else {
                tablePlacements.add(new Placement(caption, 0, topY));
                topY += caption.h + caption.mT + caption.mB;
            }
        }

        int rows = state.rows.size();
        float[] rowY = new float[rows];
        float y = captionTop + vs;
        for (int r = 0; r < rows; r++) {
            rowY[r] = y;
            y += state.rowHeights[r] + vs;
        }
        float[] colX = new float[state.columnCount];
        float x = hs;
        for (int c = 0; c < state.columnCount; c++) {
            colX[c] = x;
            x += state.columnWidths[c] + hs;
        }

        float rowW = Math.max(0, contentW - 2 * hs);
        List<List<Placement>> rowPlacements = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            LayoutBox row = state.rows.get(r);
            row.w = rowW;
            row.h = state.rowHeights[r];
            rowPlacements.add(new ArrayList<>());
        }
        for (CellSlot slot : state.cells) {
            rowPlacements.get(slot.row).add(new Placement(slot.cell, colX.length > slot.col ? colX[slot.col] - hs : 0, 0));
        }
        for (int r = 0; r < rows; r++) state.rows.get(r).engineState = new Placements(rowPlacements.get(r));

        // 行グループは自分の最初の行から最後の行まで
        for (LayoutBox group : state.groups) {
            int first = state.rowGroups.indexOf(group);
            List<Placement> list = new ArrayList<>();
            if (first < 0) {
                group.w = rowW;
                group.h = 0;
                group.engineState = new Placements(list);
                tablePlacements.add(new Placement(group, hs, captionTop));
                continue;
            }
            int last = state.rowGroups.lastIndexOf(group);
            for (int r = first; r <= last; r++) list.add(new Placement(state.rows.get(r), 0, rowY[r] - rowY[first]));
            group.w = rowW;
            group.h = rowY[last] + state.rowHeights[last] - rowY[first];
            group.engineState = new Placements(list);
            tablePlacements.add(new Placement(group, hs, rowY[first]));
        }
        for (int r = 0; r < rows; r++) {
            if (state.rowGroups.get(r) == null) tablePlacements.add(new Placement(state.rows.get(r), hs, rowY[r]));
        }
        state.placements = tablePlacements;
    }

    private static float sum(float[] values) {
        float s = 0;
        for (float v : values) s += v;
        return s;
    }

    /** 子の位置（マージンボックスの左上、親の内容ボックス相対）。 */
    private record Placement(LayoutBox box, float x, float y) {}

    /** 行グループと行が受け取る、子の位置。 */
    private record Placements(List<Placement> list) implements LayoutEngineState {}

    private static final class CellSlot {
        final LayoutBox cell;
        final int row, col, colSpan, rowSpan;
        float minWidth, maxWidth, naturalHeight, baseline;

        CellSlot(LayoutBox cell, int row, int col, int colSpan, int rowSpan) {
            this.cell = cell;
            this.row = row;
            this.col = col;
            this.colSpan = colSpan;
            this.rowSpan = rowSpan;
        }
    }

    /** 表の格子と寸法。arrangeChildren が読む位置は {@link #placements}。 */
    public static class TableState implements LayoutEngineState {
        List<Placement> placements = List.of();
        public final List<LayoutBox> rows = new ArrayList<>();
        final List<LayoutBox> rowGroups = new ArrayList<>();
        final List<LayoutBox> groups = new ArrayList<>();
        final List<LayoutBox> captions = new ArrayList<>();
        final List<LayoutBox> columns = new ArrayList<>();
        final List<LayoutBox> columnElements = new ArrayList<>();
        final List<CellSlot> cells = new ArrayList<>();
        public float[] columnWidths = new float[0];
        float[] rowHeights = new float[0];
        int columnCount;
        boolean collapse;
        float hSpacing, vSpacing, contentW;

        @Override
        public void contributeScrollExtent(ScrollExtent extent, float scrollX, float scrollY) {
            for (LayoutBox row : rows) extent.include(row, scrollX, scrollY);
        }
    }
}
