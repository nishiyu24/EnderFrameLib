package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutContext;
import com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutMetrics;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssProperty.CssKeywords;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.css.LengthResolver;
import com.nishiyu.enderframelib.core.dom.HtmlNode.NodeType;
import com.nishiyu.enderframelib.core.layout.LayoutBox.ScrollExtent;
import com.nishiyu.enderframelib.core.layout.LayoutBox.SizingMode;
import com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutContext.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutContext.FormattingContext;
import com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutEngineState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * grid レイアウト（CSS Grid 1）。
 *
 * 段の分け方: {@link #measureContent} がトラックの定義を読み、アイテムを配置し（§8.5 の自動配置）、
 * 列の寸法を解いてアイテムを列の幅で測る。{@link #contentBlockSize} が行の寸法を解き、
 * {@link #finishLayout} がコンテナの高さに合わせて行を確定し、揃え（トラックの {@code *-content}・
 * アイテムの {@code *-items} / {@code *-self}・auto マージン）で位置を決める。
 *
 * 何が壊れていたか: 以前は正の線番号と固定長・{@code fr}・{@code minmax()} の列だけを読み、
 * 暗黙の行を作らなかった（溢れたアイテムの高さが 0 になっていた）。{@code span}・負の線番号・
 * 名前付きの線・{@code grid-template-areas}・{@code repeat(auto-fill)}・{@code grid-auto-flow}・
 * {@code auto} トラックの内容の寸法・揃えも無かった。
 *
 * LIMITATION(制約): トラックの寸法は CSS Grid §11 の簡略版で、複数のトラックにまたがるアイテムの
 * 寸法は、またがる内容依存のトラックに等分する。サブグリッド・{@code grid-template} /
 * {@code grid} ショートハンド・明示的なグリッドより前の暗黙のトラック（負の方向への拡張）・
 * {@code baseline} 揃えは無い。README の "Not supported" に対応。
 */
public class GridLayoutEngine extends LayoutEngine {

    /** 列の寸法を測るための文脈。内容の寸法に縮む。 */
    private static final FormattingContext CONTRIBUTION_CONTEXT = new FormattingContext("grid", "start", true);

    @Override
    public float measureContent(LayoutBox box, ConstraintSpace space, LayoutContext ctx) {
        float availableW = space.availableInlineSize();
        float parentH = space.percentageBlockSize();
        boolean definiteWidth = box.sizingMode == SizingMode.DEFINITE;
        float innerW = definiteWidth ? Math.max(0, box.w - box.horizontalDecoration()) : availableW;

        GridState gs = new GridState();
        box.engineState = gs;
        float rootW = ctx.viewport().rootW(), rootH = ctx.viewport().rootH();

        Areas areas = Areas.parse(box.style.get(CssProperty.GRID_TEMPLATE_AREAS));
        float innerHForPercent = definiteHeight(box, parentH, ctx);
        gs.columns = TrackList.parse(box.style.get(CssProperty.GRID_TEMPLATE_COLUMNS), definiteWidth ? innerW : Float.NaN,
                box.columnGap, box.style, rootW, rootH);
        gs.rows = TrackList.parse(box.style.get(CssProperty.GRID_TEMPLATE_ROWS), innerHForPercent, box.rowGap,
                box.style, rootW, rootH);
        gs.autoColumns = TrackList.parse(box.style.getOrDefault(CssProperty.GRID_AUTO_COLUMNS, "auto"),
                definiteWidth ? innerW : Float.NaN, 0, box.style, rootW, rootH).tracks;
        gs.autoRows = TrackList.parse(box.style.getOrDefault(CssProperty.GRID_AUTO_ROWS, "auto"),
                innerHForPercent, 0, box.style, rootW, rootH).tracks;
        areas.addLineNames(gs.columns, gs.rows);

        placeItems(box, gs, areas);

        // 列の寸法。内容依存のトラックがあれば、アイテムの最小・最大の寸法を先に測る
        List<TrackDef> colDefs = gs.trackDefs(true);
        for (GridItem item : gs.items) {
            if (spansIntrinsic(colDefs, item.colStart, item.colSpan)) {
                item.box.measure(new ConstraintSpace(LayoutMetrics.UNBOUNDED_WIDTH, parentH, CONTRIBUTION_CONTEXT), ctx);
                item.maxContribution = item.box.w + item.box.mL + item.box.mR;
                item.minContribution = minContentContribution(item.box, ctx);
            }
        }
        gs.colSizes = sizeTracks(colDefs, gs.items, true, definiteWidth ? innerW : Float.NaN, box.columnGap,
                contentDistribution(box.style, CssProperty.JUSTIFY_CONTENT));

        for (GridItem item : gs.items) measureInArea(box, gs, item, parentH, ctx);
        return sum(gs.colSizes) + box.columnGap * Math.max(0, gs.colSizes.length - 1);
    }

    /** 行の寸法を解き、内容の高さを返す。 */
    @Override
    public float contentBlockSize(LayoutBox box, ConstraintSpace space, LayoutContext ctx) {
        if (!(box.engineState instanceof GridState gs)) return 0;
        float innerH = definiteHeight(box, space.percentageBlockSize(), ctx);
        sizeRows(box, gs, innerH);
        return sum(gs.rowSizes) + box.rowGap * Math.max(0, gs.rowSizes.length - 1);
    }

    @Override
    public void finishLayout(LayoutBox box, ConstraintSpace space, LayoutContext ctx) {
        if (box.engineState instanceof GridState gs) {
            float innerH = Math.max(0, box.h - box.verticalDecoration());
            sizeRows(box, gs, innerH);
            alignItems(box, gs, space.percentageBlockSize(), ctx);
        }
        measureAbsoluteChildren(box, ctx);
    }

    @Override
    public void arrangeChildren(LayoutBox box, float innerX, float innerY, LayoutContext ctx) {
        if (!(box.engineState instanceof GridState gs)) return;
        for (GridItem item : gs.items) {
            item.box.layout(innerX + item.x, innerY + item.y, item.box.w, item.box.h, ctx);
        }
    }

    /** 高さが指定されていれば内容ボックスの高さ、無ければ NaN（行の % と fr は不定として扱う）。 */
    private static float definiteHeight(LayoutBox box, float parentH, LayoutContext ctx) {
        String h = box.style.get(CssProperty.HEIGHT);
        if (h == null || "auto".equalsIgnoreCase(h.trim()) || LayoutMetrics.isIntrinsicSizeKeyword(h)) return Float.NaN;
        if (h.contains("%") && parentH <= 0) return Float.NaN;
        float v = box.style.length(CssProperty.HEIGHT, parentH, ctx.viewport().rootW(), ctx.viewport().rootH());
        boolean isBorderBox = box.style.isBorderBox();
        return Math.max(0, isBorderBox ? v - box.verticalDecoration() : v);
    }

    private void sizeRows(LayoutBox box, GridState gs, float innerH) {
        for (GridItem item : gs.items) {
            item.minContribution = item.box.h + item.box.mT + item.box.mB;
            item.maxContribution = item.minContribution;
        }
        gs.rowSizes = sizeTracks(gs.trackDefs(false), gs.items, false, innerH, box.rowGap,
                contentDistribution(box.style, CssProperty.ALIGN_CONTENT));
    }

    // ==========================================
    // 配置（CSS Grid §8）
    // ==========================================

    private void placeItems(LayoutBox box, GridState gs, Areas areas) {
        String flow = box.style.keyword(CssProperty.GRID_AUTO_FLOW, "row");
        boolean columnFlow = flow.contains("column");
        boolean dense = flow.contains("dense");

        List<LayoutBox> children = new ArrayList<>(box.getFlowChildren());
        children.sort(Comparator.comparingInt(c -> (int) c.style.number(CssProperty.ORDER, 0f)));

        int explicitCols = Math.max(gs.columns.tracks.size(), areas.columns);
        int explicitRows = Math.max(gs.rows.tracks.size(), areas.rows.size());
        for (LayoutBox child : children) {
            GridItem item = new GridItem(child);
            Placement p = Placement.of(child.style);
            int[] col = resolveAxis(p.colStart, p.colEnd, gs.columns, explicitCols);
            int[] row = resolveAxis(p.rowStart, p.rowEnd, gs.rows, explicitRows);
            item.colStart = col[0];
            item.colSpan = col[1];
            item.rowStart = row[0];
            item.rowSpan = row[1];
            gs.items.add(item);
        }

        Occupancy occupied = new Occupancy();
        int colCount = explicitCols, rowCount = explicitRows;
        for (GridItem item : gs.items) {
            if (item.colStart >= 0) colCount = Math.max(colCount, item.colStart + item.colSpan);
            else colCount = Math.max(colCount, columnFlow ? 0 : item.colSpan);
            if (item.rowStart >= 0) rowCount = Math.max(rowCount, item.rowStart + item.rowSpan);
            else rowCount = Math.max(rowCount, columnFlow ? item.rowSpan : 0);
        }
        // 自動配置で伸ばさない方の軸（行優先なら列）の数。最低 1 本
        int fixedCount = Math.max(1, columnFlow ? rowCount : colCount);

        // 1. 両方の位置が決まっているアイテム
        for (GridItem item : gs.items) {
            if (item.colStart >= 0 && item.rowStart >= 0) occupied.mark(item);
        }
        // 2. 伸ばさない方の軸の位置だけが決まっているアイテム（その行 / 列の中で詰める）
        Map<Integer, Integer> laneCursor = new LinkedHashMap<>();
        for (GridItem item : gs.items) {
            boolean lockedLane = columnFlow ? (item.colStart >= 0 && item.rowStart < 0) : (item.rowStart >= 0 && item.colStart < 0);
            if (!lockedLane) continue;
            int lane = columnFlow ? item.colStart : item.rowStart;
            int pos = dense ? 0 : laneCursor.getOrDefault(lane, 0);
            while (!fits(occupied, item, columnFlow, lane, pos)) pos++;
            setPosition(item, columnFlow, lane, pos);
            occupied.mark(item);
            laneCursor.put(lane, pos + (columnFlow ? item.rowSpan : item.colSpan));
        }
        // 3. 残り（伸ばす方の軸の位置だけ決まっているか、両方 auto）
        int cursorLane = 0, cursorPos = 0;
        for (GridItem item : gs.items) {
            boolean placed = item.colStart >= 0 && item.rowStart >= 0;
            if (placed) continue;
            int span = columnFlow ? item.rowSpan : item.colSpan;
            int fixedPos = columnFlow ? item.rowStart : item.colStart;
            if (dense) {
                cursorLane = 0;
                cursorPos = 0;
            }
            if (fixedPos >= 0) {
                if (!dense && fixedPos < cursorPos) cursorLane++;
                cursorPos = fixedPos;
                while (!fits(occupied, item, columnFlow, cursorLane, cursorPos)) cursorLane++;
            } else {
                while (true) {
                    if (cursorPos + span > fixedCount) {
                        cursorLane++;
                        cursorPos = 0;
                    }
                    if (fits(occupied, item, columnFlow, cursorLane, cursorPos)) break;
                    cursorPos++;
                }
            }
            setPosition(item, columnFlow, cursorLane, cursorPos);
            occupied.mark(item);
            cursorPos += span;
        }

        int cols = explicitCols, rows = explicitRows;
        for (GridItem item : gs.items) {
            cols = Math.max(cols, item.colStart + item.colSpan);
            rows = Math.max(rows, item.rowStart + item.rowSpan);
        }
        gs.colCount = Math.max(1, cols);
        gs.rowCount = rows;
    }

    /** 行優先なら lane = 行・pos = 列、列優先なら lane = 列・pos = 行。 */
    private static void setPosition(GridItem item, boolean columnFlow, int lane, int pos) {
        if (columnFlow) {
            item.colStart = lane;
            item.rowStart = pos;
        } else {
            item.rowStart = lane;
            item.colStart = pos;
        }
    }

    private static boolean fits(Occupancy occupied, GridItem item, boolean columnFlow, int lane, int pos) {
        int row = columnFlow ? pos : lane;
        int col = columnFlow ? lane : pos;
        for (int r = row; r < row + item.rowSpan; r++) {
            for (int c = col; c < col + item.colSpan; c++) {
                if (occupied.has(r, c)) return false;
            }
        }
        return true;
    }

    /**
     * 1 軸の始点と終点の線から、始点のトラック番号（auto なら -1）とトラック数を決める（CSS Grid §8.3）。
     * 負の番号は明示的なグリッドの末尾から数える。
     */
    private static int[] resolveAxis(GridLine start, GridLine end, TrackList tracks, int explicitTracks) {
        Integer s = start.resolve(tracks, explicitTracks, true);
        Integer e = end.resolve(tracks, explicitTracks, false);
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
        int span = Math.max(start.span, end.span);
        return new int[]{-1, Math.max(1, span)};
    }

    // ==========================================
    // トラックの寸法（CSS Grid §11 の簡略版）
    // ==========================================

    private static boolean spansIntrinsic(List<TrackDef> defs, int start, int span) {
        for (int i = start; i < start + span; i++) {
            TrackDef d = defs.get(i);
            if (d.min.isIntrinsic() || d.max.isIntrinsic()) return true;
        }
        return false;
    }

    /**
     * トラックの寸法を解く。
     * 固定長 → 内容依存のトラックを 1 本だけにまたがるアイテムで決め、またがる幅を等分 →
     * 上限まで伸ばす → fr に残りを配る → {@code *-content} が normal / stretch なら auto トラックを伸ばす。
     */
    private static float[] sizeTracks(List<TrackDef> defs, List<GridItem> items, boolean columns, float available,
                                      float gap, String distribution) {
        int n = defs.size();
        float[] base = new float[n];
        float[] limit = new float[n];
        for (int i = 0; i < n; i++) {
            TrackDef d = defs.get(i);
            base[i] = d.min.kind == SizeKind.FIXED ? d.min.value : 0;
            limit[i] = switch (d.max.kind) {
                case FIXED -> d.max.value;
                case FR -> Float.POSITIVE_INFINITY;
                default -> 0;
            };
        }

        // 1 本だけにまたがるアイテム
        for (GridItem item : items) {
            int start = columns ? item.colStart : item.rowStart;
            int span = columns ? item.colSpan : item.rowSpan;
            if (span != 1) continue;
            TrackDef d = defs.get(start);
            if (d.min.isIntrinsic()) {
                float c = d.min.kind == SizeKind.MAX_CONTENT ? item.maxContribution : item.minContribution;
                base[start] = Math.max(base[start], c);
            }
            if (d.max.isIntrinsic()) {
                float c = d.max.kind == SizeKind.MIN_CONTENT ? item.minContribution : item.maxContribution;
                if (d.max.kind == SizeKind.FIT_CONTENT) c = Math.min(c, Math.max(base[start], d.max.value));
                limit[start] = Math.max(limit[start], c);
            }
        }
        // 複数にまたがるアイテム: 足りない分を、またがる内容依存のトラックに等分する
        for (GridItem item : items) {
            int start = columns ? item.colStart : item.rowStart;
            int span = columns ? item.colSpan : item.rowSpan;
            if (span < 2) continue;
            List<Integer> intrinsic = new ArrayList<>();
            boolean hasFlexible = false;
            float covered = gap * (span - 1);
            for (int i = start; i < start + span; i++) {
                covered += base[i];
                if (defs.get(i).max.kind == SizeKind.FR) hasFlexible = true;
                else if (defs.get(i).min.isIntrinsic() || defs.get(i).max.isIntrinsic()) intrinsic.add(i);
            }
            if (hasFlexible || intrinsic.isEmpty()) continue;
            float deficit = item.minContribution - covered;
            if (deficit > 0) for (int i : intrinsic) base[i] += deficit / intrinsic.size();
            float growth = item.maxContribution - covered;
            if (growth > 0) for (int i : intrinsic) limit[i] = Math.max(limit[i], base[i] + growth / intrinsic.size());
        }
        for (int i = 0; i < n; i++) {
            if (limit[i] < base[i]) limit[i] = base[i];
        }

        float gaps = gap * Math.max(0, n - 1);
        boolean definite = !Float.isNaN(available);
        // 上限まで伸ばす（不定なら上限そのもの = max-content）
        if (!definite) {
            for (int i = 0; i < n; i++) if (!Float.isInfinite(limit[i])) base[i] = limit[i];
        } else {
            float free = available - gaps - sum(base);
            distributeUpToLimits(base, limit, free);
        }

        // fr
        float frSum = 0;
        for (TrackDef d : defs) if (d.max.kind == SizeKind.FR) frSum += d.max.value;
        if (frSum > 0) {
            float fraction;
            if (definite) {
                fraction = flexFraction(defs, base, available - gaps);
            } else {
                // 不定の空間では、各トラックの内容を収める最大の 1fr（CSS Grid §11.7.1）
                fraction = 0;
                for (int i = 0; i < n; i++) {
                    TrackDef d = defs.get(i);
                    if (d.max.kind == SizeKind.FR && d.max.value > 0) {
                        fraction = Math.max(fraction, Math.max(base[i], d.max.value >= 1 ? base[i] : 0) / Math.max(1, d.max.value));
                    }
                }
                for (GridItem item : items) {
                    int start = columns ? item.colStart : item.rowStart;
                    int span = columns ? item.colSpan : item.rowSpan;
                    if (span == 1 && defs.get(start).max.kind == SizeKind.FR) {
                        fraction = Math.max(fraction, item.maxContribution / Math.max(1, defs.get(start).max.value));
                    }
                }
            }
            for (int i = 0; i < n; i++) {
                TrackDef d = defs.get(i);
                if (d.max.kind == SizeKind.FR) base[i] = Math.max(base[i], fraction * d.max.value);
            }
        } else if (definite && ("normal".equals(distribution) || "stretch".equals(distribution))) {
            // auto トラックの stretch: 残りを等分する（CSS Grid §11.8）
            List<Integer> autos = new ArrayList<>();
            for (int i = 0; i < n; i++) if (defs.get(i).max.kind == SizeKind.AUTO) autos.add(i);
            float free = available - gaps - sum(base);
            if (free > 0 && !autos.isEmpty()) for (int i : autos) base[i] += free / autos.size();
        }
        return base;
    }

    /** 余りを上限に届くまで等分する。上限に届いたトラックの分は残りに回す。 */
    private static void distributeUpToLimits(float[] base, float[] limit, float free) {
        for (int guard = 0; guard < base.length + 1 && free > 0.001f; guard++) {
            int growable = 0;
            for (int i = 0; i < base.length; i++) if (base[i] < limit[i] && !Float.isInfinite(limit[i])) growable++;
            if (growable == 0) return;
            float share = free / growable;
            float used = 0;
            for (int i = 0; i < base.length; i++) {
                if (base[i] >= limit[i] || Float.isInfinite(limit[i])) continue;
                float add = Math.min(share, limit[i] - base[i]);
                base[i] += add;
                used += add;
            }
            free -= used;
        }
    }

    /** 確定した空間での 1fr の大きさ。fr 分より大きな下限を持つトラックは固定扱いにして解き直す。 */
    private static float flexFraction(List<TrackDef> defs, float[] base, float space) {
        Set<Integer> inflexible = new HashSet<>();
        for (int guard = 0; guard < defs.size() + 1; guard++) {
            float leftover = space;
            float frSum = 0;
            for (int i = 0; i < defs.size(); i++) {
                if (defs.get(i).max.kind == SizeKind.FR && !inflexible.contains(i)) frSum += defs.get(i).max.value;
                else leftover -= base[i];
            }
            if (frSum <= 0) return 0;
            // 係数の和が 1 未満なら空間の一部しか使わない
            float fraction = Math.max(0, leftover) / Math.max(1, frSum);
            boolean changed = false;
            for (int i = 0; i < defs.size(); i++) {
                TrackDef d = defs.get(i);
                if (d.max.kind == SizeKind.FR && !inflexible.contains(i) && fraction * d.max.value < base[i]) {
                    inflexible.add(i);
                    changed = true;
                }
            }
            if (!changed) return fraction;
        }
        return 0;
    }

    private static float minContentContribution(LayoutBox b, LayoutContext ctx) {
        float margins = b.mL + b.mR;
        String width = b.style.get(CssProperty.WIDTH);
        if (width != null && !"auto".equalsIgnoreCase(width.trim()) && !width.contains("%")
                && !LayoutMetrics.isIntrinsicSizeKeyword(width)) {
            return b.w + margins;
        }
        boolean hasContent = b.node.nodeType == NodeType.TEXT || !b.getFlowChildren().isEmpty() || isReplaced(b);
        return (hasContent ? b.getMinContentWidth(ctx) : b.horizontalDecoration()) + margins;
    }

    private static boolean isReplaced(LayoutBox b) {
        String tag = b.node.tag;
        return tag.equals("img") || tag.equals("input") || tag.equals("select") || tag.equals("button")
                || tag.equals("progress") || tag.equals("mc-fluid") || tag.equals("mc-entity") || tag.equals("mc-scene");
    }

    // ==========================================
    // アイテムの寸法と揃え
    // ==========================================

    /** アイテムを列の幅で測る。stretch なら領域の幅いっぱい、それ以外は内容の幅に縮む。 */
    private void measureInArea(LayoutBox box, GridState gs, GridItem item, float parentH, LayoutContext ctx) {
        float areaW = span(gs.colSizes, item.colStart, item.colSpan, box.columnGap);
        String justify = selfAlignment(item.box, CssProperty.JUSTIFY_SELF, box.style, CssProperty.JUSTIFY_ITEMS);
        boolean stretch = "stretch".equals(justify) && !hasAutoMargin(item.box, true);
        FormattingContext childCtx = new FormattingContext("grid", stretch ? "stretch" : justify, !stretch);
        item.box.measure(new ConstraintSpace(areaW, parentH, childCtx), ctx);
    }

    /** 行が確定してから、アイテムを領域の中で揃える（stretch・start / end / center・auto マージン）。 */
    private void alignItems(LayoutBox box, GridState gs, float parentH, LayoutContext ctx) {
        float innerW = Math.max(0, box.w - box.horizontalDecoration());
        float innerH = Math.max(0, box.h - box.verticalDecoration());
        float[] colOffsets = trackOffsets(gs.colSizes, box.columnGap, innerW,
                contentDistribution(box.style, CssProperty.JUSTIFY_CONTENT));
        float[] rowOffsets = trackOffsets(gs.rowSizes, box.rowGap, innerH,
                contentDistribution(box.style, CssProperty.ALIGN_CONTENT));
        gs.colOffsets = colOffsets;
        gs.rowOffsets = rowOffsets;

        for (GridItem item : gs.items) {
            LayoutBox b = item.box;
            float areaX = colOffsets[item.colStart];
            float areaY = rowOffsets[item.rowStart];
            float areaW = colOffsets[item.colStart + item.colSpan] - areaX - gapAfter(gs.colSizes, item.colStart + item.colSpan, box.columnGap, colOffsets);
            float areaH = rowOffsets[item.rowStart + item.rowSpan] - areaY - gapAfter(gs.rowSizes, item.rowStart + item.rowSpan, box.rowGap, rowOffsets);

            String alignSelf = selfAlignment(b, CssProperty.ALIGN_SELF, box.style, CssProperty.ALIGN_ITEMS);
            if ("stretch".equals(alignSelf) && !hasAutoMargin(b, false) && !hasSize(b.style, CssProperty.HEIGHT)) {
                float target = areaH - b.mT - b.mB;
                if (b.h < target) {
                    // 伸ばした高さは確定した高さとして測り直す（CSS Grid §6.2: 子の % の高さの基準になる）。
                    // 以前は高さだけを書き換えて解き直していて、中の height: 100% が 0 のままだった
                    b.overrideWidth = b.w;
                    b.overrideHeight = Math.max(0, target);
                    try {
                        b.measure(new ConstraintSpace(areaW, parentH, new FormattingContext("grid", alignSelf, false)), ctx);
                    } finally {
                        b.overrideWidth = Float.NaN;
                        b.overrideHeight = Float.NaN;
                    }
                }
            }
            String justifySelf = selfAlignment(b, CssProperty.JUSTIFY_SELF, box.style, CssProperty.JUSTIFY_ITEMS);
            item.x = areaX + alignWithin(b, true, areaW, justifySelf);
            item.y = areaY + alignWithin(b, false, areaH, alignSelf);
        }
    }

    /** 領域の中での位置（マージンボックスの始点の、領域の始点からのずれ）。auto マージンが先に余りを吸う。 */
    private static float alignWithin(LayoutBox b, boolean inline, float area, String alignment) {
        String startProp = inline ? CssProperty.MARGIN_LEFT : CssProperty.MARGIN_TOP;
        String endProp = inline ? CssProperty.MARGIN_RIGHT : CssProperty.MARGIN_BOTTOM;
        boolean autoStart = isAuto(b.style, startProp), autoEnd = isAuto(b.style, endProp);
        float size = inline ? b.w : b.h;
        float margins = (autoStart ? 0 : (inline ? b.mL : b.mT)) + (autoEnd ? 0 : (inline ? b.mR : b.mB));
        float free = area - size - margins;
        if (autoStart || autoEnd) {
            float share = Math.max(0, free) / ((autoStart ? 1 : 0) + (autoEnd ? 1 : 0));
            if (inline) {
                if (autoStart) b.mL = share;
                if (autoEnd) b.mR = share;
            } else {
                if (autoStart) b.mT = share;
                if (autoEnd) b.mB = share;
            }
            return 0;
        }
        return switch (alignment) {
            case "center" -> free / 2;
            case "end", "flex-end", "self-end", "right" -> free;
            default -> 0;
        };
    }

    /** トラックの始点の位置（末尾に終端を 1 つ足す）。トラックの合計が狭ければ {@code *-content} で寄せる。 */
    private static float[] trackOffsets(float[] sizes, float gap, float inner, String distribution) {
        int n = sizes.length;
        float total = sum(sizes);
        String justify = switch (distribution) {
            case "normal", "stretch", "start", "left" -> "flex-start";
            case "end", "right" -> "flex-end";
            default -> distribution;
        };
        MainAxisSpacing spacing = distributeMainAxis(justify, 0, inner, total, gap, n);
        float[] offsets = new float[n + 1];
        float pos = spacing.start();
        for (int i = 0; i < n; i++) {
            offsets[i] = pos;
            pos += sizes[i] + (i < n - 1 ? spacing.gap() : 0);
        }
        offsets[n] = pos;
        return offsets;
    }

    /** 終端の線の手前にある gap の幅（最後の線の手前には無い）。 */
    private static float gapAfter(float[] sizes, int lineIndex, float gap, float[] offsets) {
        if (lineIndex >= sizes.length) return 0;
        return offsets[lineIndex] - offsets[lineIndex - 1] - sizes[lineIndex - 1];
    }

    private static float span(float[] sizes, int start, int count, float gap) {
        float w = 0;
        for (int i = start; i < start + count && i < sizes.length; i++) w += sizes[i];
        return w + gap * Math.max(0, count - 1);
    }

    private static float sum(float[] values) {
        float s = 0;
        for (float v : values) s += v;
        return s;
    }

    /**
     * アイテムの揃え。{@code *-self} が auto ならコンテナの {@code *-items}、{@code normal} / {@code legacy} は
     * 置換要素でなければ stretch。
     */
    private static String selfAlignment(LayoutBox b, String selfProp, ComputedStyle container, String itemsProp) {
        CssKeywords.Alignment v = b.style.keywordOf(selfProp, CssKeywords.Alignment.class);
        if (v == CssKeywords.Alignment.AUTO) v = container.keywordOf(itemsProp, CssKeywords.Alignment.class);
        if (v == CssKeywords.Alignment.NORMAL || v == CssKeywords.Alignment.LEGACY || v == CssKeywords.Alignment.AUTO) {
            boolean sized = hasSize(b.style, CssProperty.JUSTIFY_SELF.equals(selfProp) ? CssProperty.WIDTH : CssProperty.HEIGHT);
            return isReplaced(b) || sized ? "start" : "stretch";
        }
        // first baseline / last baseline は baseline（safe / unsafe は読み取りで落とし済み）
        return v == CssKeywords.Alignment.FIRST_BASELINE || v == CssKeywords.Alignment.LAST_BASELINE ? "baseline" : v.cssName();
    }

    private static boolean hasAutoMargin(LayoutBox b, boolean inline) {
        return inline
                ? isAuto(b.style, CssProperty.MARGIN_LEFT) || isAuto(b.style, CssProperty.MARGIN_RIGHT)
                : isAuto(b.style, CssProperty.MARGIN_TOP) || isAuto(b.style, CssProperty.MARGIN_BOTTOM);
    }

    private static boolean isAuto(ComputedStyle style, String prop) {
        return "auto".equalsIgnoreCase(style.getOrDefault(prop, "0").trim());
    }

    private static boolean hasSize(ComputedStyle style, String prop) {
        String v = style.get(prop);
        return v != null && !"auto".equalsIgnoreCase(v.trim());
    }

    private static String contentDistribution(ComputedStyle style, String prop) {
        return style.keywordOf(prop, CssKeywords.Alignment.class).cssName();
    }

    // ==========================================
    // トラックの定義
    // ==========================================

    enum SizeKind { FIXED, FR, AUTO, MIN_CONTENT, MAX_CONTENT, FIT_CONTENT }

    record TrackSize(SizeKind kind, float value) {
        static final TrackSize AUTO = new TrackSize(SizeKind.AUTO, 0);

        boolean isIntrinsic() {
            return kind == SizeKind.AUTO || kind == SizeKind.MIN_CONTENT || kind == SizeKind.MAX_CONTENT
                    || kind == SizeKind.FIT_CONTENT;
        }
    }

    /** 1 本のトラックの寸法の関数（min と max）。 */
    record TrackDef(TrackSize min, TrackSize max) {
        static final TrackDef AUTO = new TrackDef(TrackSize.AUTO, TrackSize.AUTO);
    }

    /** トラックの並びと、各線の名前（{@code lineNames.get(i)} は i 番目の線）。 */
    static final class TrackList {
        final List<TrackDef> tracks = new ArrayList<>();
        final List<List<String>> lineNames = new ArrayList<>();

        private void addNames(List<String> names) {
            while (lineNames.size() <= tracks.size()) lineNames.add(new ArrayList<>());
            lineNames.get(tracks.size()).addAll(names);
        }

        private void addTrack(TrackDef def) {
            while (lineNames.size() <= tracks.size()) lineNames.add(new ArrayList<>());
            tracks.add(def);
        }

        /** {@code name} を持つ {@code nth} 番目（1 始まり）の線の番号。無ければ null。 */
        Integer findLine(String name, int nth) {
            int seen = 0;
            for (int i = 0; i < lineNames.size(); i++) {
                if (lineNames.get(i).contains(name) && ++seen == nth) return i;
            }
            return null;
        }

        /**
         * {@code grid-template-columns} / {@code -rows} を読む。{@code available} は % と
         * {@code repeat(auto-fill)} の基準（不定なら NaN）。
         */
        static TrackList parse(String value, float available, float gap, ComputedStyle style, float rootW, float rootH) {
            TrackList list = new TrackList();
            if (value == null || value.isBlank() || "none".equalsIgnoreCase(value.trim())) return list;
            parseInto(list, value, available, gap, style, rootW, rootH);
            while (list.lineNames.size() <= list.tracks.size()) list.lineNames.add(new ArrayList<>());
            return list;
        }

        private static void parseInto(TrackList list, String value, float available, float gap,
                                      ComputedStyle style, float rootW, float rootH) {
            for (String token : splitTrackList(value)) {
                String t = token.trim();
                String lower = t.toLowerCase(Locale.ROOT);
                if (t.startsWith("[") && t.endsWith("]")) {
                    List<String> names = new ArrayList<>();
                    for (String n : t.substring(1, t.length() - 1).trim().split("\\s+")) if (!n.isEmpty()) names.add(n);
                    list.addNames(names);
                } else if (lower.startsWith("repeat(") && lower.endsWith(")")) {
                    String inner = t.substring(7, t.length() - 1);
                    int comma = topLevelComma(inner);
                    if (comma < 0) continue;
                    String countStr = inner.substring(0, comma).trim().toLowerCase(Locale.ROOT);
                    String body = inner.substring(comma + 1).trim();
                    int count;
                    if (countStr.equals("auto-fill") || countStr.equals("auto-fit")) {
                        count = autoRepeatCount(body, available, gap, style, rootW, rootH);
                    } else {
                        try {
                            count = Math.max(1, Math.min(10000, Integer.parseInt(countStr)));
                        } catch (NumberFormatException e) {
                            continue;
                        }
                    }
                    for (int i = 0; i < count; i++) parseInto(list, body, available, gap, style, rootW, rootH);
                } else {
                    list.addTrack(parseTrack(t, available, style, rootW, rootH));
                }
            }
        }

        /**
         * {@code repeat(auto-fill, ...)} の繰り返し数。トラックの寸法（max が確定していなければ min）で
         * 利用できる幅に入るだけ。空間が不定なら 1。
         *
         * LIMITATION(制約): {@code auto-fit} の空のトラックを潰さない（auto-fill と同じに扱う）。
         */
        private static int autoRepeatCount(String body, float available, float gap, ComputedStyle style, float rootW, float rootH) {
            if (Float.isNaN(available)) return 1;
            TrackList one = new TrackList();
            parseInto(one, body, available, gap, style, rootW, rootH);
            float size = 0;
            for (TrackDef d : one.tracks) {
                float s = d.max.kind == SizeKind.FIXED ? d.max.value : (d.min.kind == SizeKind.FIXED ? d.min.value : 0);
                size += s;
            }
            int n = one.tracks.size();
            float unit = size + gap * n;
            if (unit <= 0) return 1;
            return Math.max(1, (int) Math.floor((available + gap) / unit + 1e-4));
        }

        private static TrackDef parseTrack(String t, float available, ComputedStyle style, float rootW, float rootH) {
            String lower = t.toLowerCase(Locale.ROOT);
            if (lower.startsWith("minmax(") && lower.endsWith(")")) {
                String inner = t.substring(7, t.length() - 1);
                int comma = topLevelComma(inner);
                if (comma < 0) return TrackDef.AUTO;
                TrackSize min = parseSize(inner.substring(0, comma).trim(), available, style, rootW, rootH);
                TrackSize max = parseSize(inner.substring(comma + 1).trim(), available, style, rootW, rootH);
                // fr は min に置けない（auto として扱う）
                if (min.kind == SizeKind.FR) min = TrackSize.AUTO;
                return new TrackDef(min, max);
            }
            TrackSize size = parseSize(t, available, style, rootW, rootH);
            return switch (size.kind) {
                case FR -> new TrackDef(TrackSize.AUTO, size);
                case FIT_CONTENT -> new TrackDef(TrackSize.AUTO, size);
                default -> new TrackDef(size, size);
            };
        }

        private static TrackSize parseSize(String t, float available, ComputedStyle style, float rootW, float rootH) {
            String lower = t.toLowerCase(Locale.ROOT);
            switch (lower) {
                case "auto" -> {
                    return TrackSize.AUTO;
                }
                case "min-content" -> {
                    return new TrackSize(SizeKind.MIN_CONTENT, 0);
                }
                case "max-content" -> {
                    return new TrackSize(SizeKind.MAX_CONTENT, 0);
                }
                default -> {
                }
            }
            if (lower.endsWith("fr")) {
                String num = lower.substring(0, lower.length() - 2).trim();
                try {
                    return new TrackSize(SizeKind.FR, num.isEmpty() ? 1f : Math.max(0, Float.parseFloat(num)));
                } catch (NumberFormatException e) {
                    return TrackSize.AUTO;
                }
            }
            if (lower.startsWith("fit-content(") && lower.endsWith(")")) {
                String arg = t.substring(12, t.length() - 1).trim();
                if (arg.contains("%") && Float.isNaN(available)) return new TrackSize(SizeKind.MAX_CONTENT, 0);
                return new TrackSize(SizeKind.FIT_CONTENT, LengthResolver.parseDim(arg, Float.isNaN(available) ? 0 : available,
                        rootW, rootH, style.fontSizePx(), style.rootFontSizePx()));
            }
            // 不定の空間に対する % は auto（CSS Grid §7.2.1）
            if (lower.contains("%") && !lower.startsWith("calc") && Float.isNaN(available)) return TrackSize.AUTO;
            return new TrackSize(SizeKind.FIXED, LengthResolver.parseDim(t, Float.isNaN(available) ? 0 : available,
                    rootW, rootH, style.fontSizePx(), style.rootFontSizePx()));
        }
    }

    private static int topLevelComma(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) return i;
        }
        return -1;
    }

    /** 最上位の空白で区切る。括弧（関数）と角括弧（線の名前）の中は区切らない。 */
    private static List<String> splitTrackList(String def) {
        List<String> list = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < def.length(); i++) {
            char c = def.charAt(i);
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
            if (Character.isWhitespace(c) && depth == 0) {
                if (!current.isEmpty()) {
                    list.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) list.add(current.toString());
        return list;
    }

    /** {@code grid-template-areas}。名前付きの領域は {@code name-start} / {@code name-end} の線を作る。 */
    private record Areas(List<String[]> rows, int columns) {
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

        void addLineNames(TrackList cols, TrackList rowTracks) {
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
                nameLine(rowTracks, b[0], e.getKey() + "-start");
                nameLine(rowTracks, b[2], e.getKey() + "-end");
                nameLine(cols, b[1], e.getKey() + "-start");
                nameLine(cols, b[3], e.getKey() + "-end");
            }
        }

        private static void nameLine(TrackList list, int line, String name) {
            while (list.lineNames.size() <= line) list.lineNames.add(new ArrayList<>());
            list.lineNames.get(line).add(name);
        }
    }

    /** 1 本のグリッド線の指定（{@code auto} / 番号 / 名前 / {@code span}）。 */
    private record GridLine(Integer number, String name, int span) {
        static final GridLine AUTO = new GridLine(null, null, 0);

        static GridLine parse(String value) {
            if (value == null) return AUTO;
            String v = value.trim();
            if (v.isEmpty() || v.equalsIgnoreCase("auto")) return AUTO;
            String[] parts = v.split("\\s+");
            boolean span = false;
            Integer number = null;
            String name = null;
            for (String p : parts) {
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
            if (span) return new GridLine(null, name, Math.max(1, number != null ? number : 1));
            if (number != null && number == 0) return AUTO;
            return new GridLine(number, name, 0);
        }

        boolean isIdent() {
            return name != null && number == null && span == 0;
        }

        /** 線の番号（0 始まり）。auto と span は null。 */
        Integer resolve(TrackList tracks, int explicitTracks, boolean isStart) {
            if (span > 0) return null;
            if (name != null) {
                int nth = number != null ? number : 1;
                // 領域の名前は start / end の暗黙の線を先に探す（CSS Grid §8.3.1）
                if (number == null) {
                    Integer implicit = tracks.findLine(name + (isStart ? "-start" : "-end"), 1);
                    if (implicit != null) return implicit;
                }
                return tracks.findLine(name, Math.abs(nth));
            }
            if (number == null) return null;
            if (number > 0) return number - 1;
            return Math.max(0, explicitTracks + 1 + number);
        }
    }

    /** アイテムの 4 本の線。ロングハンドがあればそれを、無ければ grid-row / grid-column / grid-area を読む。 */
    private record Placement(GridLine rowStart, GridLine colStart, GridLine rowEnd, GridLine colEnd) {
        static Placement of(ComputedStyle s) {
            String[] area = split(s.get(CssProperty.GRID_AREA), 4);
            String[] row = split(s.get(CssProperty.GRID_ROW), 2);
            String[] col = split(s.get(CssProperty.GRID_COLUMN), 2);
            String rs = first(s.get(CssProperty.GRID_ROW_START), row[0], area[0]);
            String cs = first(s.get(CssProperty.GRID_COLUMN_START), col[0], area[1]);
            String re = first(s.get(CssProperty.GRID_ROW_END), row[1], area[2]);
            String ce = first(s.get(CssProperty.GRID_COLUMN_END), col[1], area[3]);
            GridLine rsl = GridLine.parse(rs), csl = GridLine.parse(cs);
            // 終点が省略されたとき、始点が名前ならその名前、それ以外は auto（CSS Grid §8.4）
            GridLine rel = re != null ? GridLine.parse(re) : (rsl.isIdent() ? rsl : GridLine.AUTO);
            GridLine cel = ce != null ? GridLine.parse(ce) : (csl.isIdent() ? csl : GridLine.AUTO);
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

    /** 埋まったセル。 */
    private static final class Occupancy {
        private final Set<Long> cells = new HashSet<>();

        boolean has(int row, int col) {
            return cells.contains(((long) row << 32) | (col & 0xFFFFFFFFL));
        }

        void mark(GridItem item) {
            for (int r = item.rowStart; r < item.rowStart + item.rowSpan; r++) {
                for (int c = item.colStart; c < item.colStart + item.colSpan; c++) {
                    cells.add(((long) r << 32) | (c & 0xFFFFFFFFL));
                }
            }
        }
    }

    public static class GridItem {
        public int colStart, colSpan, rowStart, rowSpan;
        public final LayoutBox box;
        float minContribution, maxContribution;
        /** 位置（マージンボックスの左上、内容ボックス相対）。 */
        float x, y;

        GridItem(LayoutBox box) {
            this.box = box;
        }
    }

    /** グリッドのトラックと配置済みアイテム。 */
    public static class GridState implements LayoutEngineState {
        TrackList columns = new TrackList(), rows = new TrackList();
        List<TrackDef> autoColumns = List.of(), autoRows = List.of();
        public float[] colSizes = new float[0];
        public float[] rowSizes = new float[0];
        float[] colOffsets = new float[1], rowOffsets = new float[1];
        public final List<GridItem> items = new ArrayList<>();
        public int colCount = 1, rowCount = 0;

        /** 明示的なトラックの後に、暗黙のトラックを {@code grid-auto-*} の繰り返しで足した並び。 */
        List<TrackDef> trackDefs(boolean cols) {
            List<TrackDef> explicit = (cols ? columns : rows).tracks;
            List<TrackDef> auto = cols ? autoColumns : autoRows;
            int count = cols ? colCount : rowCount;
            List<TrackDef> defs = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                if (i < explicit.size()) defs.add(explicit.get(i));
                else if (auto.isEmpty()) defs.add(TrackDef.AUTO);
                else defs.add(auto.get((i - explicit.size()) % auto.size()));
            }
            return defs;
        }

        @Override
        public void contributeScrollExtent(ScrollExtent extent, float scrollX, float scrollY) {
            for (GridItem item : items) {
                if (item.box.isAbsolute) continue;
                extent.include(item.box, scrollX, scrollY);
            }
        }
    }
}
