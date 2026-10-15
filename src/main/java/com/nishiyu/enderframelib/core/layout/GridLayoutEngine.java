package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.CssParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GridLayoutEngine implements LayoutEngine {

    public static class GridState {
        public float[] colWidths;
        public float[] rowHeights;
        public List<GridItem> items = new ArrayList<>();
        public int colsCount = 1, rowsCount = 1;
    }

    public static class GridItem {
        public int colStart, colSpan, rowStart, rowSpan;
        public LayoutBox box;
    }

    private static final Map<String, String> REPEAT_CACHE = new ConcurrentHashMap<>();

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
    public void computeSize(LayoutBox box, int tentativeInnerW, int parentH, boolean isParentFlexRow, boolean isParentFlexColumn, String parentAlignItems, boolean isParentShrink, int rootW, int rootH) {
        GridState gs = new GridState();
        box.engineState = gs;

        String gridCols = expandRepeat(box.style.getOrDefault("grid-template-columns", "1fr"));
        String[] colDefs = gridCols.split("\\s+");
        gs.colsCount = Math.max(1, colDefs.length);
        gs.colWidths = new float[gs.colsCount];
        float totalFr = 0;
        int fixedW = 0;
        for (int i = 0; i < gs.colsCount; i++) {
            if (colDefs[i].endsWith("fr")) {
                totalFr += Float.parseFloat(colDefs[i].replace("fr", ""));
            } else {
                gs.colWidths[i] = CssParser.parseDim(colDefs[i], tentativeInnerW, rootW, rootH);
                fixedW = (int) (fixedW + gs.colWidths[i]);
            }
        }
        int availableW = Math.max(0, tentativeInnerW - (gs.colsCount - 1) * box.gap - fixedW);
        for (int i = 0; i < gs.colsCount; i++) {
            if (colDefs[i].endsWith("fr") && totalFr > 0) {
                gs.colWidths[i] = availableW * (Float.parseFloat(colDefs[i].replace("fr", "")) / totalFr);
            }
        }

        List<boolean[]> occupied = new ArrayList<>();
        int currentR = 0, currentC = 0;

        for (LayoutBox child : box.getFlowChildren()) {
            GridItem item = new GridItem();
            item.box = child;

            String gc = child.style.get("grid-column");
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

            String gr = child.style.get("grid-row");
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
            childGridW += Math.max(0, item.colSpan - 1) * box.gap;

            int w = (int) childGridW;
            item.box.w = w;

            String oldW = item.box.style.get("width");
            int forcedW = w - (item.box.style.getOrDefault("box-sizing", "border-box").equals("content-box") ? item.box.horizontalDecoration() : 0);
            item.box.style.put("width", Math.max(0, forcedW) + "px");

            item.box.computeSize(w, parentH, false, false, "stretch", false, rootW, rootH);

            if (oldW != null) item.box.style.put("width", oldW); else item.box.style.remove("width");
        }

        if (box.w == -1) {
            int totalW = 0;
            for (int c = 0; c < gs.colsCount; c++) totalW = (int) (totalW + gs.colWidths[c]);
            totalW += Math.max(0, gs.colsCount - 1) * box.gap;
            box.w = totalW + box.horizontalDecoration();
        }
    }

    @Override
    public void computeHeightAndFinalizeChildren(LayoutBox box, int parentH, boolean isParentFlexRow, boolean isParentFlexColumn, int rootW, int rootH) {
        GridState gs = (GridState) box.engineState;
        if (gs == null) return;

        String gridRows = expandRepeat(box.style.getOrDefault("grid-template-rows", ""));
        String[] rowDefs = gridRows.isEmpty() ? new String[0] : gridRows.split("\\s+");
        gs.rowHeights = new float[gs.rowsCount];

        for (int r = 0; r < gs.rowsCount; r++) {
            if (r < rowDefs.length && !rowDefs[r].equals("auto") && !rowDefs[r].endsWith("fr")) {
                gs.rowHeights[r] = CssParser.parseDim(rowDefs[r], parentH, rootW, rootH);
            } else {
                float maxH = 0;
                for (GridItem item : gs.items) {
                    if (item.rowStart == r && item.rowSpan == 1)
                        maxH = Math.max(maxH, item.box.h + item.box.mT + item.box.mB);
                }
                gs.rowHeights[r] = maxH;
            }
        }

        String align = box.style.getOrDefault("align-items", "stretch");
        if (align.equals("stretch")) {
            for (GridItem item : gs.items) {
                float childGridH = 0;
                for (int r = 0; r < item.rowSpan; r++) {
                    if (item.rowStart + r < gs.rowsCount) childGridH += gs.rowHeights[item.rowStart + r];
                }
                childGridH += Math.max(0, item.rowSpan - 1) * box.gap;

                int targetH = (int) childGridH - item.box.mT - item.box.mB;
                if (item.box.h < targetH && !item.box.style.containsKey("height")) {
                    item.box.h = Math.max(0, targetH);
                    String oldH = item.box.style.get("height");
                    int forcedH = item.box.h - (item.box.style.getOrDefault("box-sizing", "border-box").equals("content-box") ? item.box.verticalDecoration() : 0);
                    item.box.style.put("height", Math.max(0, forcedH) + "px");
                    item.box.computeSize(item.box.w, item.box.h, false, false, "stretch", false, rootW, rootH);
                    if (oldH != null) item.box.style.put("height", oldH); else item.box.style.remove("height");
                }
            }
        }

        if (!box.style.containsKey("height") || box.style.getOrDefault("height", "").trim().equals("auto")) {
            int totalH = 0;
            for (GridItem item : gs.items) {
                float itemY = item.box.mT + item.box.mB + item.box.h;
                for (int r = 0; r < item.rowStart; r++) itemY += gs.rowHeights[r] + box.gap;
                totalH = Math.max(totalH, (int) itemY);
            }
            box.h = Math.max(box.h, totalH + box.verticalDecoration());
        }

        int actualInnerW = Math.max(0, box.w - box.horizontalDecoration());
        int actualInnerH = Math.max(0, box.h - box.verticalDecoration());
        for (LayoutBox child : box.children) {
            if (child.isAbsolute && !child.style.getOrDefault("display", "block").equals("none")) {
                child.computeSize(actualInnerW, actualInnerH, false, false, "stretch", false, rootW, rootH);
            }
        }
    }

    @Override
    public void layoutChildren(LayoutBox box, int innerX, int innerY, int rootW, int rootH) {
        GridState gs = (GridState) box.engineState;
        if (gs == null) return;
        for (GridItem item : gs.items) {
            float itemX = innerX;
            for (int c = 0; c < item.colStart; c++) itemX += gs.colWidths[c] + box.gap;

            float itemY = innerY;
            for (int r = 0; r < item.rowStart; r++) itemY += gs.rowHeights[r] + box.gap;

            item.box.layout((int) itemX + item.box.mL, (int) itemY + item.box.mT, item.box.w, item.box.h, rootW, rootH);
        }
    }
}