package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.css.LengthResolver;
import com.nishiyu.enderframelib.core.layout.LayoutBox.ScrollExtent;
import com.nishiyu.enderframelib.core.layout.LayoutBox.SizingMode;
import com.nishiyu.enderframelib.core.layout.LayoutContext.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.LayoutContext.FormattingContext;
import com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutEngineState;
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
 * px ・% ・fr ・repeat() ・minmax() のみ。名前付きグリッド線と grid-template-areas、
 * repeat(auto-fill / auto-fit) は未対応で、grid-column / grid-row は正の番号指定だけを解釈する
 * （span と負の番号は読まない）。暗黙のトラック（grid-auto-rows / -columns）・
 * grid-auto-flow（column / dense）・auto トラックの内容サイズ・justify-items /
 * align-items / justify-content によるアイテムとトラックの揃えも無い。W4 で直す。
 * FIDELITY.md の「W4 — 整形コンテキスト」と README の "Not supported" と対応。
 */
public class GridLayoutEngine extends LayoutEngine {

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

    private static List<String> splitTrackList(String def) {
        List<String> list = new ArrayList<>();
        if (def == null || def.isBlank()) return list;
        StringBuilder current = new StringBuilder();
        float depth = 0;
        for (int i = 0; i < def.length(); i++) {
            char c = def.charAt(i);
            if (c == '(') {
                depth++;
                current.append(c);
            } else if (c == ')') {
                depth--;
                current.append(c);
            } else if (Character.isWhitespace(c) && depth == 0) {
                if (current.length() > 0) {
                    list.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            list.add(current.toString());
        }
        return list;
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
    public float measureContent(LayoutBox box, ConstraintSpace space, LayoutContext ctx) {
        float tentativeInnerW = space.availableInlineSize();
        float parentH = space.percentageBlockSize();
        GridState gs = new GridState();
        box.engineState = gs;

        float rootW = ctx.viewport().rootW();
        float rootH = ctx.viewport().rootH();

        String gridCols = expandRepeat(box.style.getOrDefault(CssProperty.GRID_TEMPLATE_COLUMNS, "1fr"));
        List<String> colTokens = splitTrackList(gridCols);
        if (colTokens.isEmpty()) colTokens = List.of("1fr");
        gs.colsCount = colTokens.size();
        gs.colWidths = new float[gs.colsCount];
        float totalFr = 0;
        float fixedW = 0;
        float[] minWidths = new float[gs.colsCount];

        for (int i = 0; i < gs.colsCount; i++) {
            String def = colTokens.get(i);
            if (def.startsWith("minmax(") && def.endsWith(")")) {
                String inner = def.substring(7, def.length() - 1).trim();
                int comma = inner.indexOf(',');
                if (comma >= 0) {
                    String minStr = inner.substring(0, comma).trim();
                    String maxStr = inner.substring(comma + 1).trim();
                    minWidths[i] = LengthResolver.parseDim(minStr, tentativeInnerW, rootW, rootH,
                            box.style.fontSizePx(), box.style.rootFontSizePx());
                    if (maxStr.endsWith("fr")) {
                        totalFr += parseFr(maxStr);
                    } else {
                        float maxW = LengthResolver.parseDim(maxStr, tentativeInnerW, rootW, rootH,
                                box.style.fontSizePx(), box.style.rootFontSizePx());
                        gs.colWidths[i] = Math.max(minWidths[i], maxW);
                        fixedW = (fixedW + gs.colWidths[i]);
                    }
                }
            } else if (def.endsWith("fr")) {
                totalFr += parseFr(def);
            } else {
                gs.colWidths[i] = LengthResolver.parseDim(def, tentativeInnerW, rootW, rootH,
                        box.style.fontSizePx(), box.style.rootFontSizePx());
                fixedW = (fixedW + gs.colWidths[i]);
            }
        }
        float availableW = Math.max(0, tentativeInnerW - (gs.colsCount - 1) * box.columnGap - fixedW);
        for (int i = 0; i < gs.colsCount; i++) {
            String def = colTokens.get(i);
            if (def.startsWith("minmax(") && def.endsWith(")")) {
                String inner = def.substring(7, def.length() - 1).trim();
                int comma = inner.indexOf(',');
                if (comma >= 0) {
                    String maxStr = inner.substring(comma + 1).trim();
                    if (maxStr.endsWith("fr") && totalFr > 0) {
                        float frW = availableW * (parseFr(maxStr) / totalFr);
                        gs.colWidths[i] = Math.max(minWidths[i], frW);
                    }
                }
            } else if (def.endsWith("fr") && totalFr > 0) {
                gs.colWidths[i] = availableW * (parseFr(def) / totalFr);
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

            float w = childGridW;
            item.box.w = w;
            item.box.sizingMode = SizingMode.DEFINITE; // A-5修正
            item.box.measure(new ConstraintSpace(w, parentH, childCtx), ctx);
        }

        float totalW = 0;
        for (int c = 0; c < gs.colsCount; c++) totalW = (totalW + gs.colWidths[c]);
        totalW += Math.max(0, gs.colsCount - 1) * box.columnGap;
        return totalW;
    }

    /** 行の高さを決め、アイテムをセルの高さまで伸ばしてから、内容の高さを返す。 */
    @Override
    public float contentBlockSize(LayoutBox box, ConstraintSpace space, LayoutContext ctx) {
        float parentH = space.percentageBlockSize();
        GridState gs = (GridState) box.engineState;
        if (gs == null) return 0;

        float rootW = ctx.viewport().rootW();
        float rootH = ctx.viewport().rootH();

        String gridRows = expandRepeat(box.style.getOrDefault(CssProperty.GRID_TEMPLATE_ROWS, ""));
        List<String> rowTokens = splitTrackList(gridRows);
        gs.rowHeights = new float[gs.rowsCount];

        for (int r = 0; r < gs.rowsCount; r++) {
            if (r < rowTokens.size() && !"auto".equals(rowTokens.get(r)) && !rowTokens.get(r).endsWith("fr")) {
                gs.rowHeights[r] = LengthResolver.parseDim(rowTokens.get(r), parentH, rootW, rootH,
                        box.style.fontSizePx(), box.style.rootFontSizePx());
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

                float targetH = childGridH - item.box.mT - item.box.mB;
                if (item.box.h < targetH && !item.box.style.containsKey(CssProperty.HEIGHT)) {
                    item.box.h = Math.max(0, targetH);
                    item.box.resolveFlexibleHeights(parentH, new FormattingContext("grid", align, false), ctx);
                }
            }
        }

        float totalH = 0;
        for (GridItem item : gs.items) {
            float itemY = item.box.mT + item.box.mB + item.box.h;
            for (int r = 0; r < item.rowStart; r++) itemY += gs.rowHeights[r] + box.rowGap;
            totalH = Math.max(totalH, itemY);
        }
        return totalH;
    }

    @Override
    public void finishLayout(LayoutBox box, ConstraintSpace space, LayoutContext ctx) {
        measureAbsoluteChildren(box, ctx);
    }

    @Override
    public void arrangeChildren(LayoutBox box, float innerX, float innerY, LayoutContext ctx) {
        GridState gs = (GridState) box.engineState;
        if (gs == null) return;
        for (GridItem item : gs.items) {
            float itemX = innerX;
            for (int c = 0; c < item.colStart; c++) itemX += gs.colWidths[c] + box.columnGap;

            float itemY = innerY;
            for (int r = 0; r < item.rowStart; r++) itemY += gs.rowHeights[r] + box.rowGap;

            item.box.layout(itemX + item.box.mL, itemY + item.box.mT, item.box.w, item.box.h, ctx);
        }
    }

    public static class GridItem {
        public int colStart, colSpan, rowStart, rowSpan;
        public LayoutBox box;
    }

    /** グリッドの行列サイズと配置済みアイテム。 */
    public static class GridState implements LayoutEngineState {
        public float[] colWidths;
        public float[] rowHeights;
        public List<GridItem> items = new ArrayList<>();
        public int colsCount = 1, rowsCount = 1;

        @Override
        public void contributeScrollExtent(ScrollExtent extent, float scrollX, float scrollY) {
            // グリッドアイテムは box.children とは別に保持しているため、ここで範囲に足し込む
            for (GridItem item : items) {
                if (item.box.isAbsolute) continue;
                extent.include(item.box, scrollX, scrollY);
            }
        }
    }
}