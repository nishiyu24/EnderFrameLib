package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.css.LengthResolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * grid レイアウト。
 *
 * LIMITATION(制約): トラックの定義は grid-template-columns / grid-template-rows の
 * px ・% ・fr ・auto ・repeat() ・minmax() のみ。名前付きグリッド線と
 * grid-template-areas は未対応で、grid-column / grid-row は番号指定だけを解釈する。
 * README の "Not supported" と対応。
 */
public class GridLayoutEngine extends AbstractLayoutEngine {

    // 信頼できないCSSがキーになるため、メモリリーク防止でLRUキャッシュ化
    private static final Map<String, String> REPEAT_CACHE = new LinkedHashMap<>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 1000;
        }
    };

    private String expandRepeat(String def) {
        if (def == null) return "1fr";
        if (!def.contains("repeat(")) return def;
        return REPEAT_CACHE.computeIfAbsent(def, k -> {
            Matcher m = Pattern.compile("repeat\\(\\s*(\\d+)\\s*,\\s*([^)]+)\\)").matcher(k);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                try {
                    int count = Integer.parseInt(m.group(1));
                    String val = m.group(2).trim();
                    StringBuilder rep = new StringBuilder();
                    for (int i = 0; i < count; i++) rep.append(val).append(" ");
                    m.appendReplacement(sb, rep.toString().trim());
                } catch (Exception e) {
                    m.appendReplacement(sb, "");
                }
            }
            m.appendTail(sb);
            return sb.toString().trim();
        });
    }

    /** "2fr" の係数。単位だけ・不正値は 1fr として扱う。 */
    private static float parseFr(String def) {
        String num = def.substring(0, def.length() - 2).trim();
        if (num.isEmpty()) return 1f;
        try {
            return Float.parseFloat(num);
        } catch (NumberFormatException e) {
            return 1f;
        }
    }

    private int parseGridLine(String val) {
        try { return Integer.parseInt(val.replaceAll("[^0-9-]", "")); } catch (Exception e) { return 1; }
    }

    private static boolean isCellOccupied(List<boolean[]> occupied, int row, int col) {
        if (row < 0 || col < 0 || row >= occupied.size()) return false;
        boolean[] rowArr = occupied.get(row);
        return col < rowArr.length && rowArr[col];
    }

    private static void markCellOccupied(List<boolean[]> occupied, int colsCount, int row, int col) {
        if (row < 0 || col < 0 || col >= colsCount) return;
        while (occupied.size() <= row) {
            occupied.add(new boolean[colsCount]);
        }
        occupied.get(row)[col] = true;
    }

    @Override
    public void measure(LayoutBox box, int tentativeInnerW, int parentH, FormattingContext fmtCtx, LayoutContext ctx) {
        GridState gs = new GridState();
        box.engineState = gs;

        int rootW = ctx.viewport().rootW();
        int rootH = ctx.viewport().rootH();

        String gridCols = expandRepeat(box.style.getOrDefault(CssProperty.GRID_TEMPLATE_COLUMNS, "1fr"));
        String[] colDefs = gridCols.split("\\s+");
        gs.colsCount = Math.max(1, colDefs.length);
        gs.colWidths = new float[gs.colsCount];
        float totalFr = 0;
        int fixedW = 0;
        for (int i = 0; i < gs.colsCount; i++) {
            if (colDefs[i].endsWith("fr")) {
                totalFr += parseFr(colDefs[i]);
            } else {
                gs.colWidths[i] = LengthResolver.parseDim(colDefs[i], tentativeInnerW, rootW, rootH,
                        box.fontSize * LayoutMetrics.BASE_FONT_SIZE, LayoutMetrics.BASE_FONT_SIZE);
                fixedW = (int) (fixedW + gs.colWidths[i]);
            }
        }
        int availableW = Math.max(0, tentativeInnerW - (gs.colsCount - 1) * box.columnGap - fixedW);
        for (int i = 0; i < gs.colsCount; i++) {
            if (colDefs[i].endsWith("fr") && totalFr > 0) {
                gs.colWidths[i] = availableW * (parseFr(colDefs[i]) / totalFr);
            }
        }

        List<boolean[]> occupied = new ArrayList<>();
        int currentR = 0, currentC = 0;

        FormattingContext childCtx = new FormattingContext("grid", "stretch", false);

        for (LayoutBox child : box.getFlowChildren()) {
            GridItem item = new GridItem();
            item.box = child;

            String gc = child.style.get(CssProperty.GRID_COLUMN);
            item.colSpan = 1; item.colStart = -1;
            if (gc != null) {
                if (gc.contains("/")) {
                    String[] parts = gc.split("/");
                    item.colStart = Math.max(0, parseGridLine(parts[0].trim()) - 1);
                    String end = parts[1].trim();
                    if (end.startsWith("span ")) item.colSpan = Math.max(1, parseGridLine(end.substring(5)));
                    else item.colSpan = Math.max(1, parseGridLine(end) - 1 - item.colStart);
                } else if (gc.startsWith("span ")) {
                    item.colSpan = Math.max(1, parseGridLine(gc.substring(5)));
                } else {
                    item.colStart = Math.max(0, parseGridLine(gc) - 1);
                }
            }
            item.colSpan = Math.min(item.colSpan, gs.colsCount);

            String gr = child.style.get(CssProperty.GRID_ROW);
            item.rowSpan = 1; item.rowStart = -1;
            if (gr != null) {
                if (gr.contains("/")) {
                    String[] parts = gr.split("/");
                    item.rowStart = Math.max(0, parseGridLine(parts[0].trim()) - 1);
                    String end = parts[1].trim();
                    if (end.startsWith("span ")) item.rowSpan = Math.max(1, parseGridLine(end.substring(5)));
                    else item.rowSpan = Math.max(1, parseGridLine(end) - 1 - item.rowStart);
                } else if (gr.startsWith("span ")) {
                    item.rowSpan = Math.max(1, parseGridLine(gr.substring(5)));
                } else {
                    item.rowStart = Math.max(0, parseGridLine(gr) - 1);
                }
            }

            if (item.rowStart == -1 || item.colStart == -1) {
                while (true) {
                    if (item.colStart != -1) currentC = Math.min(item.colStart, gs.colsCount - item.colSpan);
                    if (currentC + item.colSpan > gs.colsCount) { currentC = 0; currentR++; }

                    boolean fit = true;
                    for (int r = 0; r < item.rowSpan; r++) {
                        for (int c = 0; c < item.colSpan; c++) {
                            int checkR = (item.rowStart != -1 ? item.rowStart : currentR) + r;
                            int checkC = currentC + c;
                            if (isCellOccupied(occupied, checkR, checkC)) {
                                fit = false; break;
                            }
                        }
                        if (!fit) break;
                    }
                    if (fit) {
                        if (item.rowStart == -1) item.rowStart = currentR;
                        if (item.colStart == -1) item.colStart = currentC;
                        break;
                    } else {
                        if (item.colStart != -1) currentR++;
                        else currentC++;
                    }
                }
            }

            gs.rowsCount = Math.max(gs.rowsCount, item.rowStart + item.rowSpan);
            for (int r = 0; r < item.rowSpan; r++) {
                for (int c = 0; c < item.colSpan; c++) {
                    markCellOccupied(occupied, gs.colsCount, item.rowStart + r, item.colStart + c);
                }
            }
            gs.items.add(item);

            float childGridW = 0;
            for (int c = 0; c < item.colSpan; c++) {
                if (item.colStart + c < gs.colsCount) childGridW += gs.colWidths[item.colStart + c];
            }
            childGridW += Math.max(0, item.colSpan - 1) * box.columnGap;

            int w = (int) childGridW;
            item.box.w = w;
            item.box.sizingMode = SizingMode.DEFINITE; // A-5修正
            item.box.measure(w, parentH, childCtx, ctx);
        }

        if (box.sizingMode == SizingMode.SHRINK_TO_FIT) {
            int totalW = 0;
            for (int c = 0; c < gs.colsCount; c++) totalW = (int) (totalW + gs.colWidths[c]);
            totalW += Math.max(0, gs.colsCount - 1) * box.columnGap;
            box.w = totalW + box.horizontalDecoration();
        }
    }

    @Override
    public void resolveFlexibleHeights(LayoutBox box, int parentH, FormattingContext fmtCtx, LayoutContext ctx) {
        GridState gs = (GridState) box.engineState;
        if (gs == null) return;

        int rootW = ctx.viewport().rootW();
        int rootH = ctx.viewport().rootH();

        String gridRows = expandRepeat(box.style.getOrDefault(CssProperty.GRID_TEMPLATE_ROWS, ""));
        String[] rowDefs = gridRows.isEmpty() ? new String[0] : gridRows.split("\\s+");
        gs.rowHeights = new float[gs.rowsCount];

        for (int r = 0; r < gs.rowsCount; r++) {
            if (r < rowDefs.length && !"auto".equals(rowDefs[r]) && !rowDefs[r].endsWith("fr")) {
                gs.rowHeights[r] = LengthResolver.parseDim(rowDefs[r], parentH, rootW, rootH,
                        box.fontSize * LayoutMetrics.BASE_FONT_SIZE, LayoutMetrics.BASE_FONT_SIZE);
            } else {
                float maxH = 0;
                for (GridItem item : gs.items) {
                    if (item.rowStart == r && item.rowSpan == 1)
                        maxH = Math.max(maxH, item.box.h + item.box.mT + item.box.mB);
                }
                gs.rowHeights[r] = maxH;
            }
        }

        String align = box.style.getOrDefault(CssProperty.ALIGN_ITEMS, "stretch");
        if ("stretch".equals(align)) {
            for (GridItem item : gs.items) {
                float childGridH = 0;
                for (int r = 0; r < item.rowSpan; r++) {
                    if (item.rowStart + r < gs.rowsCount) childGridH += gs.rowHeights[item.rowStart + r];
                }
                childGridH += Math.max(0, item.rowSpan - 1) * box.rowGap;

                int targetH = (int) childGridH - item.box.mT - item.box.mB;
                if (item.box.h < targetH && !item.box.style.containsKey(CssProperty.HEIGHT)) {
                    item.box.h = Math.max(0, targetH);
                    item.box.resolveFlexibleHeights(parentH, new FormattingContext("grid", align, false), ctx);
                }
            }
        }

        if (!box.style.containsKey(CssProperty.HEIGHT) || "auto".equals(box.style.getOrDefault(CssProperty.HEIGHT, "auto").trim())) {
            int totalH = 0;
            for (GridItem item : gs.items) {
                float itemY = item.box.mT + item.box.mB + item.box.h;
                for (int r = 0; r < item.rowStart; r++) itemY += gs.rowHeights[r] + box.rowGap;
                totalH = Math.max(totalH, (int) itemY);
            }
            box.h = Math.max(box.h, totalH + box.verticalDecoration());
        }

        measureAbsoluteChildren(box, ctx);
    }

    @Override
    public void arrangeChildren(LayoutBox box, int innerX, int innerY, LayoutContext ctx) {
        GridState gs = (GridState) box.engineState;
        if (gs == null) return;
        for (GridItem item : gs.items) {
            float itemX = innerX;
            for (int c = 0; c < item.colStart; c++) itemX += gs.colWidths[c] + box.columnGap;

            float itemY = innerY;
            for (int r = 0; r < item.rowStart; r++) itemY += gs.rowHeights[r] + box.rowGap;

            item.box.layout((int) itemX + item.box.mL, (int) itemY + item.box.mT, item.box.w, item.box.h, ctx);
        }
    }
}