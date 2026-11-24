package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.css.Style;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * グリッドの項目の配置（Engine v3 M2b-3。CSS Grid §8。旧 {@code GridLayoutEngine.placeItems} ほかの移植）: 線の指定の解析
 * （番号・名前・{@code span}・{@code grid-area}・{@code grid-template-areas} の暗黙の名前の線）・自動配置（{@code grid-auto-flow} の
 * row / column / dense）・空の {@code repeat(auto-fit)} のトラックを潰す。純粋な static 関数。
 */
final class GridPlacement {
    private GridPlacement() { }

    /** {@code grid-template-areas}。名前付きの領域は {@code name-start} / {@code name-end} の線を作る。 */
    record Areas(List<String[]> rows, int columns) {
        static Areas parse(String value) {
            List<String[]> rows = new ArrayList<>();
            int columns = 0;
            if (value != null && !"none".equalsIgnoreCase(value.trim())) {
                int i = 0;
                while (i < value.length()) {
                    char q = value.charAt(i);
                    if (q == '"' || q == '\'') {
                        int end = value.indexOf(q, i + 1);
                        if (end < 0) break;
                        String[] cells = value.substring(i + 1, end).trim().split("\\s+");
                        rows.add(cells);
                        columns = Math.max(columns, cells.length);
                        i = end + 1;
                    } else {
                        i++;
                    }
                }
            }
            return new Areas(rows, columns);
        }

        void addLineNames(GridTracks.Template cols, GridTracks.Template rowTracks) {
            Map<String, int[]> bounds = new LinkedHashMap<>();
            for (int r = 0; r < rows.size(); r++) {
                String[] cells = rows.get(r);
                for (int c = 0; c < cells.length; c++) {
                    String name = cells[c];
                    if (name.isEmpty() || name.chars().allMatch(ch -> ch == '.')) continue;
                    int[] b = bounds.computeIfAbsent(name, k -> new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE, -1, -1});
                    b[0] = Math.min(b[0], r);
                    b[1] = Math.min(b[1], c);
                    b[2] = Math.max(b[2], r + 1);
                    b[3] = Math.max(b[3], c + 1);
                }
            }
            for (var e : bounds.entrySet()) {
                int[] b = e.getValue();
                rowTracks.nameLine(b[0], e.getKey() + "-start");
                rowTracks.nameLine(b[2], e.getKey() + "-end");
                cols.nameLine(b[1], e.getKey() + "-start");
                cols.nameLine(b[3], e.getKey() + "-end");
            }
        }
    }

    /** 1 本のグリッド線の指定（{@code auto} / 番号 / 名前 / {@code span}）。 */
    record Line(Integer number, String name, int span) {
        static final Line AUTO = new Line(null, null, 0);

        static Line parse(String value) {
            if (value == null) return AUTO;
            String v = value.trim();
            if (v.isEmpty() || v.equalsIgnoreCase("auto")) return AUTO;
            boolean span = false;
            Integer number = null;
            String name = null;
            for (String p : v.split("\\s+")) {
                if (p.equalsIgnoreCase("span")) {
                    span = true;
                } else {
                    try {
                        number = Integer.parseInt(p);
                    } catch (NumberFormatException e) {
                        name = p;
                    }
                }
            }
            if (span) return new Line(null, name, Math.max(1, number != null ? number : 1));
            if (number != null && number == 0) return AUTO;
            return new Line(number, name, 0);
        }

        boolean isIdent() {
            return name != null && number == null && span == 0;
        }

        /** 線の番号（0 始まり）。auto と span は null。 */
        Integer resolve(GridTracks.Template tracks, int explicit, boolean isStart) {
            if (span > 0) return null;
            if (name != null) {
                int nth = number != null ? number : 1;
                if (number == null) { // 領域の名前は start / end の暗黙の線を先に探す（§8.3.1）
                    Integer implicit = tracks.findLine(name + (isStart ? "-start" : "-end"), 1);
                    if (implicit != null) return implicit;
                }
                return tracks.findLine(name, Math.abs(nth));
            }
            if (number == null) return null;
            if (number > 0) return number - 1;
            return Math.max(0, explicit + 1 + number);
        }
    }

    /** 項目の 4 本の線。ロングハンドがあればそれを、無ければ {@code grid-row} / {@code grid-column} / {@code grid-area} を読む。 */
    record Placement(Line rowStart, Line colStart, Line rowEnd, Line colEnd) {
        static Placement of(Style s) {
            var fg = s.flexGrid;
            String[] area = split(fg.gridArea, 4), row = split(fg.gridRow, 2), col = split(fg.gridColumn, 2);
            String rs = first(fg.gridRowStart, row[0], area[0]);
            String cs = first(fg.gridColumnStart, col[0], area[1]);
            String re = first(fg.gridRowEnd, row[1], area[2]);
            String ce = first(fg.gridColumnEnd, col[1], area[3]);
            Line rsl = Line.parse(rs), csl = Line.parse(cs);
            // 終点が省略されたとき、始点が名前ならその名前、それ以外は auto（§8.4）
            Line rel = re != null ? Line.parse(re) : (rsl.isIdent() ? rsl : Line.AUTO);
            Line cel = ce != null ? Line.parse(ce) : (csl.isIdent() ? csl : Line.AUTO);
            // grid-area: a は 4 本とも a、grid-area: a / b は列の始点が b
            if (area[0] != null && area[1] == null && rsl.isIdent()) csl = rsl;
            if (area[0] != null && area[3] == null && ce == null && csl.isIdent()) cel = csl;
            if (area[0] != null && area[2] == null && re == null && rsl.isIdent()) rel = rsl;
            return new Placement(rsl, csl, rel, cel);
        }

        private static String first(String... values) {
            for (String v : values) if (v != null && !v.isBlank() && !"auto".equalsIgnoreCase(v.trim())) return v;
            return null;
        }

        private static String[] split(String value, int n) {
            String[] out = new String[n];
            if (value == null || "auto".equalsIgnoreCase(value.trim())) return out;
            String[] parts = value.split("/");
            for (int i = 0; i < n && i < parts.length; i++) out[i] = parts[i].trim();
            return out;
        }
    }

    /** 1 軸の始点と終点の線から {始点のトラック（auto なら -1）, またぐ数}（§8.3）。負の番号は明示的なグリッドの末尾から。 */
    static int[] resolveAxis(Line start, Line end, GridTracks.Template tracks, int explicit) {
        Integer s = start.resolve(tracks, explicit, true);
        Integer e = end.resolve(tracks, explicit, false);
        if (s != null && e != null) {
            if (e < s) return new int[]{e, s - e};
            if (e.equals(s)) return new int[]{s, 1};
            return new int[]{s, e - s};
        }
        if (s != null) return new int[]{s, end.span > 0 ? end.span : 1};
        if (e != null) {
            int span = start.span > 0 ? start.span : 1;
            return new int[]{Math.max(0, e - span), Math.min(span, Math.max(1, e))};
        }
        return new int[]{-1, Math.max(1, Math.max(start.span, end.span))};
    }

    /**
     * 項目を置く（§8.5 の自動配置）。返り値は {列の数, 行の数}。{@code explicitCols/Rows} は明示的なグリッドの大きさ
     * （{@code grid-template-areas} を含む）。
     */
    static int[] place(List<GridItem> items, GridTracks.Template cols, GridTracks.Template rows, int explicitCols, int explicitRows,
                       String autoFlow) {
        String flow = autoFlow == null ? "row" : autoFlow.trim().toLowerCase(java.util.Locale.ROOT);
        boolean columnFlow = flow.contains("column"), dense = flow.contains("dense");
        for (GridItem it : items) {
            Placement p = Placement.of(it.box.style);
            int[] c = resolveAxis(p.colStart(), p.colEnd(), cols, explicitCols);
            int[] r = resolveAxis(p.rowStart(), p.rowEnd(), rows, explicitRows);
            it.colStart = c[0];
            it.colSpan = c[1];
            it.rowStart = r[0];
            it.rowSpan = r[1];
        }
        Set<Long> occupied = new HashSet<>();
        int colCount = explicitCols, rowCount = explicitRows;
        for (GridItem it : items) {
            colCount = Math.max(colCount, it.colStart >= 0 ? it.colStart + it.colSpan : columnFlow ? 0 : it.colSpan);
            rowCount = Math.max(rowCount, it.rowStart >= 0 ? it.rowStart + it.rowSpan : columnFlow ? it.rowSpan : 0);
        }
        int fixedCount = Math.max(1, columnFlow ? rowCount : colCount); // 自動配置で伸ばさない方の軸の数
        for (GridItem it : items) if (it.colStart >= 0 && it.rowStart >= 0) mark(occupied, it); // 1. 両方決まっている
        Map<Integer, Integer> laneCursor = new HashMap<>();
        for (GridItem it : items) { // 2. 伸ばさない方の軸だけ決まっている（その行・列の中で詰める）
            boolean locked = columnFlow ? it.colStart >= 0 && it.rowStart < 0 : it.rowStart >= 0 && it.colStart < 0;
            if (!locked) continue;
            int lane = columnFlow ? it.colStart : it.rowStart;
            int pos = dense ? 0 : laneCursor.getOrDefault(lane, 0);
            while (!fits(occupied, it, columnFlow, lane, pos)) pos++;
            setPosition(it, columnFlow, lane, pos);
            mark(occupied, it);
            laneCursor.put(lane, pos + (columnFlow ? it.rowSpan : it.colSpan));
        }
        int cursorLane = 0, cursorPos = 0;
        for (GridItem it : items) { // 3. 残り
            if (it.colStart >= 0 && it.rowStart >= 0) continue;
            int span = columnFlow ? it.rowSpan : it.colSpan;
            int fixedPos = columnFlow ? it.rowStart : it.colStart;
            if (dense) {
                cursorLane = 0;
                cursorPos = 0;
            }
            if (fixedPos >= 0) {
                if (!dense && fixedPos < cursorPos) cursorLane++;
                cursorPos = fixedPos;
                while (!fits(occupied, it, columnFlow, cursorLane, cursorPos)) cursorLane++;
            } else {
                while (true) {
                    if (cursorPos + span > fixedCount) {
                        cursorLane++;
                        cursorPos = 0;
                    }
                    if (fits(occupied, it, columnFlow, cursorLane, cursorPos)) break;
                    cursorPos++;
                }
            }
            setPosition(it, columnFlow, cursorLane, cursorPos);
            mark(occupied, it);
            cursorPos += span;
        }
        int c = explicitCols, r = explicitRows;
        for (GridItem it : items) {
            c = Math.max(c, it.colStart + it.colSpan);
            r = Math.max(r, it.rowStart + it.rowSpan);
        }
        return new int[]{Math.max(1, c), r};
    }

    private static void setPosition(GridItem it, boolean columnFlow, int lane, int pos) {
        if (columnFlow) {
            it.colStart = lane;
            it.rowStart = pos;
        } else {
            it.rowStart = lane;
            it.colStart = pos;
        }
    }

    private static boolean fits(Set<Long> occupied, GridItem it, boolean columnFlow, int lane, int pos) {
        int row = columnFlow ? pos : lane, col = columnFlow ? lane : pos;
        for (int r = row; r < row + it.rowSpan; r++) {
            for (int c = col; c < col + it.colSpan; c++) if (occupied.contains(key(r, c))) return false;
        }
        return true;
    }

    private static void mark(Set<Long> occupied, GridItem it) {
        for (int r = it.rowStart; r < it.rowStart + it.rowSpan; r++) {
            for (int c = it.colStart; c < it.colStart + it.colSpan; c++) occupied.add(key(r, c));
        }
    }

    private static long key(int r, int c) {
        return ((long) r << 32) | (c & 0xFFFFFFFFL);
    }

    /**
     * {@code repeat(auto-fit)} の繰り返しのうち、項目が 1 つも重ならないトラックを取り除く（§7.2.3.2。前後の gap も 1 本に重なる
     * ので、トラックを無かったことにするのと同じ）。返り値は取り除いた数。
     */
    static int collapseEmptyAutoFit(List<GridItem> items, GridTracks.Template list, boolean columns) {
        if (list.autoFit.isEmpty()) return 0;
        int n = list.tracks.size();
        boolean[] empty = new boolean[n];
        int emptyCount = 0;
        for (int i = list.autoFit.nextSetBit(0); i >= 0 && i < n; i = list.autoFit.nextSetBit(i + 1)) {
            boolean used = false;
            for (GridItem it : items) {
                if (i >= it.start(columns) && i < it.start(columns) + it.span(columns)) {
                    used = true;
                    break;
                }
            }
            if (!used) {
                empty[i] = true;
                emptyCount++;
            }
        }
        if (emptyCount == 0 || emptyCount == n) return 0;
        int[] removedBefore = new int[n + 1];
        for (int i = 0; i < n; i++) removedBefore[i + 1] = removedBefore[i] + (empty[i] ? 1 : 0);
        for (GridItem it : items) {
            int start = it.start(columns), end = start + it.span(columns);
            int ns = start - removedBefore[Math.min(start, n)], ne = end - removedBefore[Math.min(end, n)];
            if (columns) {
                it.colStart = ns;
                it.colSpan = Math.max(1, ne - ns);
            } else {
                it.rowStart = ns;
                it.rowSpan = Math.max(1, ne - ns);
            }
        }
        List<GridTracks.Def> kept = new ArrayList<>();
        for (int i = 0; i < n; i++) if (!empty[i]) kept.add(list.tracks.get(i));
        list.tracks.clear();
        list.tracks.addAll(kept);
        list.lineNames.clear();
        return emptyCount;
    }
}
