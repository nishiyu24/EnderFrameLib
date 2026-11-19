package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.CssPropertyIndex;
import com.nishiyu.enderframelib.core.layout.model.*;

import com.nishiyu.enderframelib.core.layout.LayoutContext;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssKeywords;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.layout.LayoutBox.ScrollExtent;
import com.nishiyu.enderframelib.core.layout.LayoutBox.SizingMode;
import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.model.FormattingContext;
import com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutEngineState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;

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
 * <p>{@code repeat(auto-fit, ...)} は、アイテムが 1 つも入らなかった繰り返しのトラックを潰す
 * （トラックと、その隣の gap を 1 本ぶん取り除いたのと同じ寸法になる）。
 *
 * <p>サブグリッド（{@code grid-template-columns / -rows: subgrid}）: 親グリッドの項目がグリッドコンテナで軸に {@code subgrid} を
 * 指定すると、その軸のトラックと線の名前を親と共有する。子の寄与は親のトラックへ回り（端の子にはサブグリッドの
 * 余白・枠線・padding を足す）、親が寸法を決めた後に子は親の寸法（gap の差は隣のトラックが半分ずつ）で配置される。
 * 親がグリッドでなければ none と同じ。測定が親の寸法に依るので {@link LayoutBox#subgridDependent} で再利用を止める。
 *
 * LIMITATION(制約): トラックの寸法は CSS Grid §11 の簡略版で、複数のトラックにまたがるアイテムの
 * 寸法は、またがる内容依存のトラックに等分する。サブグリッドの軸の {@code grid-template-areas}・明示的なグリッドより前の暗黙のトラック
 * （負の方向への拡張）・{@code baseline} 揃えは無い（{@code grid-template} / {@code grid} ショートハンドは
 * 読む側ではなく展開側 {@code ShorthandExpanders} が担う）。README の "Not supported" に対応。
 */
public class GridLayoutEngine extends LayoutEngine {

    /** 列の寸法を測るための文脈。内容の寸法に縮む。 */
    private static final FormattingContext CONTRIBUTION_CONTEXT = FormattingContext.of(FormattingContext.Display.GRID, false, true);

    @Override
    public float measureContent(LayoutBox box, ConstraintSpace space, LayoutContext ctx) {
        float availableW = space.availableInlineSize();
        float parentH = space.percentageBlockSize();
        boolean definiteWidth = box.sizingMode == SizingMode.DEFINITE;
        float innerW = definiteWidth ? Math.max(0, box.w - box.horizontalDecoration()) : availableW;

        GridState gs;
        if (box.engineState instanceof GridState existing) {
            gs = existing.resetForLayout();
        } else {
            gs = new GridState();
            box.engineState = gs;
        }
        float rootW = ctx.viewport().rootW(), rootH = ctx.viewport().rootH();

        Areas areas = Areas.parse(box.style.get(CssPropertyIndex.GRID_TEMPLATE_AREAS));
        float innerHForPercent = definiteHeight(box, parentH, ctx);
        gs.columns = TrackList.parse(box.style.get(CssPropertyIndex.GRID_TEMPLATE_COLUMNS), definiteWidth ? innerW : Float.NaN,
                box.columnGap, box.style, rootW, rootH);
        gs.rows = TrackList.parse(box.style.get(CssPropertyIndex.GRID_TEMPLATE_ROWS), innerHForPercent, box.rowGap,
                box.style, rootW, rootH);
        gs.autoColumns = TrackList.parse(box.style.getOrDefault(CssPropertyIndex.GRID_AUTO_COLUMNS, "auto"),
                definiteWidth ? innerW : Float.NaN, 0, box.style, rootW, rootH).tracks;
        gs.autoRows = TrackList.parse(box.style.getOrDefault(CssPropertyIndex.GRID_AUTO_ROWS, "auto"),
                innerHForPercent, 0, box.style, rootW, rootH).tracks;
        areas.addLineNames(gs.columns, gs.rows);

        // サブグリッド: 親グリッドの線・トラックを共有する軸。トラックの数は親でまたがる数に等しい
        GridState pgs = null;
        GridItem pItem = null;
        if (box.parent != null && box.parent.engineState instanceof GridState p
                && (isSubgridValue(box.style.get(CssPropertyIndex.GRID_TEMPLATE_COLUMNS))
                || isSubgridValue(box.style.get(CssPropertyIndex.GRID_TEMPLATE_ROWS)))) {
            for (GridItem it : p.items) {
                if (it.box == box) {
                    pItem = it;
                    pgs = p;
                    break;
                }
            }
        }
        gs.parentState = pgs;
        gs.parentItem = pItem;
        // グリッドの項目でない（または親がグリッドでない）サブグリッドは none と同じ
        if (pItem == null || !pItem.colSub) {
            if (isSubgridValue(box.style.get(CssPropertyIndex.GRID_TEMPLATE_COLUMNS))) gs.columns = new TrackList();
        }
        if (pItem == null || !pItem.rowSub) {
            if (isSubgridValue(box.style.get(CssPropertyIndex.GRID_TEMPLATE_ROWS))) gs.rows = new TrackList();
        }
        gs.colSub = pItem != null && pItem.colSub;
        gs.rowSub = pItem != null && pItem.rowSub;
        box.subgridDependent = gs.colSub || gs.rowSub;
        if (gs.colSub) {
            gs.columns = subgridTracks(box.style.get(CssPropertyIndex.GRID_TEMPLATE_COLUMNS), pgs.columns, pItem.colStart, pItem.colSpan);
            if (isNormalGap(box.style.get(CssPropertyIndex.COLUMN_GAP))) box.columnGap = box.parent.columnGap;
        }
        if (gs.rowSub) {
            gs.rows = subgridTracks(box.style.get(CssPropertyIndex.GRID_TEMPLATE_ROWS), pgs.rows, pItem.rowStart, pItem.rowSpan);
            if (isNormalGap(box.style.get(CssPropertyIndex.ROW_GAP))) box.rowGap = box.parent.rowGap;
        }

        placeItems(box, gs, areas);
        collapseEmptyAutoFitTracks(gs, true);
        collapseEmptyAutoFitTracks(gs, false);

        // 列の寸法。内容依存のトラックがあれば、アイテムの最小・最大の寸法を先に測る
        List<TrackDef> colDefs = gs.trackDefs(true);
        boolean colsFromParent = gs.colSub && pgs.colsFinal && pItem.colStart + pItem.colSpan <= pgs.colSizes.length;
        // サブグリッドの子の寄与は親のトラックへ回すので、サブグリッドの列を持つ項目は先に（仮の幅で）測って寄与を得る
        for (GridItem item : gs.items) {
            if (item.colSub) {
                item.box.measure(new ConstraintSpace(LayoutMetrics.UNBOUNDED_WIDTH, parentH, CONTRIBUTION_CONTEXT), ctx);
            } else if (!colsFromParent && spansIntrinsic(colDefs, item.colStart, item.colSpan)) {
                // 寄与の測定は寸法を読むだけで、直後の measureInArea が必ず測り直す。中身が同じなら前回の産物で済ませる
                ConstraintSpace contribution = new ConstraintSpace(LayoutMetrics.UNBOUNDED_WIDTH, parentH, CONTRIBUTION_CONTEXT);
                if (!item.box.measureTentatively(contribution, ctx)) item.box.measure(contribution, ctx);
                item.maxContribution = item.box.w + item.box.mL + item.box.mR;
                item.minContribution = minContentContribution(item.box, ctx);
                // 行の測定が同じ欄を上書きするので、サブグリッドの親が読む列の寄与は別に残す
                item.colMin = item.minContribution;
                item.colMax = item.maxContribution;
            }
        }
        gs.colEffective = effectiveItems(gs, true);
        if (colsFromParent) {
            gs.colSizes = subgridSizes(pgs.colSizes, pItem.colStart, gs.colCount, box.parent.columnGap, box.columnGap);
            gs.colEdgeStart = box.mL + box.bL + box.pL;
            gs.colEdgeEnd = box.mR + box.bR + box.pR;
            gs.colsFinal = true;
        } else {
            gs.colSizes = sizeTracks(colDefs, gs.colEffective, true, definiteWidth ? innerW : Float.NaN, box.columnGap,
                    contentDistribution(box.style, CssPropertyIndex.JUSTIFY_CONTENT));
            gs.colsFinal = !gs.colSub;
        }

        for (GridItem item : gs.items) {
            // グリッドの高さが指定で決まっていればそれを、決まっていなければ auto として（NaN）% の高さを測る
            float base = isPercentage(item.box.axisStyle().get(CssPropertyIndex.HEIGHT)) ? innerHForPercent : parentH;
            measureInArea(box, gs, item, base, ctx);
        }
        return Math.max(0, sum(gs.colSizes) + box.columnGap * Math.max(0, gs.colSizes.length - 1)
                - gs.colEdgeStart - gs.colEdgeEnd);
    }

    /** 行の寸法を解き、内容の高さを返す。 */
    @Override
    public float contentBlockSize(LayoutBox box, ConstraintSpace space, LayoutContext ctx) {
        if (!(box.engineState instanceof GridState gs)) return 0;
        float innerH = definiteHeight(box, space.percentageBlockSize(), ctx);
        sizeRows(box, gs, innerH, ctx);
        return Math.max(0, sum(gs.rowSizes) + box.rowGap * Math.max(0, gs.rowSizes.length - 1)
                - gs.rowEdgeStart - gs.rowEdgeEnd);
    }

    @Override
    public void finishLayout(LayoutBox box, ConstraintSpace space, LayoutContext ctx) {
        if (box.engineState instanceof GridState gs) {
            float innerH = Math.max(0, box.h - box.verticalDecoration());
            // グリッドの高さが確定したので、% の高さの子をその高さを基準に測り直してから行を決める
            // （Chrome と同じ。高さが不定のときは measureContent で auto として測った寄与のまま）
            for (GridItem item : gs.items) {
                if (isPercentage(item.box.axisStyle().get(CssPropertyIndex.HEIGHT)) && !(Math.abs(item.percentBase - innerH) < 0.01f)) {
                    measureInArea(box, gs, item, innerH, ctx);
                }
            }
            sizeRows(box, gs, innerH, ctx);
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
        if (!Float.isNaN(box.overrideHeight)) return Math.max(0, box.overrideHeight - box.verticalDecoration());
        ComputedStyle style = box.axisStyle();
        String h = style.get(CssPropertyIndex.HEIGHT);
        if (h == null || "auto".equalsIgnoreCase(h.trim()) || LayoutMetrics.isIntrinsicSizeKeyword(h)) return Float.NaN;
        if (h.contains("%") && parentH <= 0) return Float.NaN;
        float v = style.length(CssPropertyIndex.HEIGHT, parentH, ctx.viewport().rootW(), ctx.viewport().rootH());
        boolean isBorderBox = style.isBorderBox();
        return Math.max(0, isBorderBox ? v - box.verticalDecoration() : v);
    }

    private void sizeRows(LayoutBox box, GridState gs, float innerH, LayoutContext ctx) {
        List<GridItem> items = gs.items;
        for (int i = 0, n = items.size(); i < n; i++) {
            GridItem item = items.get(i);
            if (item.rowSub) continue;
            item.maxContribution = item.box.h + item.box.mT + item.box.mB;
            item.minContribution = rowMinContribution(item.box, ctx) + item.box.mT + item.box.mB;
        }
        gs.rowEffective = effectiveItems(gs, false);
        GridState pgs = gs.parentState;
        GridItem pItem = gs.parentItem;
        if (gs.rowSub && pgs.rowsFinal && pItem.rowStart + pItem.rowSpan <= pgs.rowSizes.length) {
            gs.rowSizes = subgridSizes(pgs.rowSizes, pItem.rowStart, gs.rowCount, box.parent.rowGap, box.rowGap);
            gs.rowEdgeStart = box.mT + box.bT + box.pT;
            gs.rowEdgeEnd = box.mB + box.bB + box.pB;
            gs.rowsOwnFinal = true;
            return;
        }
        gs.rowsOwnFinal = false;
        gs.rowSizes = sizeTracks(gs.trackDefs(false), gs.rowEffective, false, innerH, box.rowGap,
                contentDistribution(box.style, CssPropertyIndex.ALIGN_CONTENT));
    }

    // ==========================================
    // サブグリッド（CSS Grid 2 §9）
    // ==========================================

    /** {@code subgrid} か（{@code subgrid [名前]...} を含む）。 */
    static boolean isSubgridValue(String value) {
        if (value == null) return false;
        String v = value.trim();
        if (v.length() < 7 || !v.regionMatches(true, 0, "subgrid", 0, 7)) return false;
        return v.length() == 7 || Character.isWhitespace(v.charAt(7));
    }

    private static boolean isNormalGap(String value) {
        return value == null || value.isBlank() || "normal".equalsIgnoreCase(value.trim());
    }

    /** サブグリッドの軸のトラック。親の {@code span} 本を auto のプレースホルダで表し、線の名前は親から引き継ぎ自身の名前を足す。 */
    private static TrackList subgridTracks(String value, TrackList parent, int start, int span) {
        TrackList list = new TrackList();
        for (int i = 0; i < span; i++) list.addTrack(TrackDef.AUTO);
        while (list.lineNames.size() <= span) list.lineNames.add(new ArrayList<>());
        for (int k = 0; k <= span; k++) {
            if (start + k < parent.lineNames.size()) list.lineNames.get(k).addAll(parent.lineNames.get(start + k));
        }
        int line = 0;
        for (String token : splitTrackList(value.trim().substring(7))) {
            if (token.startsWith("[") && token.endsWith("]") && line <= span) {
                for (String n : token.substring(1, token.length() - 1).trim().split("\\s+")) {
                    if (!n.isEmpty()) list.lineNames.get(line).add(n);
                }
                line++;
            }
        }
        return list;
    }

    /**
     * 親のトラックの寸法をサブグリッドの寸法へ写す。gap が親と違うぶんは、隣り合うトラックが半分ずつ受け持つ
     * （合計は親のまたがる幅に一致する）。
     */
    private static float[] subgridSizes(float[] parent, int start, int count, float parentGap, float ownGap) {
        float[] out = new float[count];
        float half = (parentGap - ownGap) / 2;
        for (int i = 0; i < count; i++) {
            int sides = (i > 0 ? 1 : 0) + (i < count - 1 ? 1 : 0);
            out[i] = Math.max(0, (start + i < parent.length ? parent[start + i] : 0) + half * sides);
        }
        return out;
    }

    /** サブグリッドの線の位置（内容ボックスの左上が原点。端の余白ぶん外へはみ出す）。 */
    private static float[] subgridOffsets(float[] sizes, float gap, float edgeStart) {
        int n = sizes.length;
        float[] offsets = new float[n + 1];
        float pos = -edgeStart;
        for (int i = 0; i < n; i++) {
            offsets[i] = pos;
            pos += sizes[i] + (i < n - 1 ? gap : 0);
        }
        offsets[n] = pos;
        return offsets;
    }

    /**
     * トラックの寸法を決めるアイテム。サブグリッドの軸を持つ項目は自身の寄与を持たず、その子を親のトラックの番号に
     * 読み替えて（端の子にはサブグリッドの余白・枠線・padding を足して）入れる。無ければ {@code gs.items} をそのまま返す。
     */
    private static List<GridItem> effectiveItems(GridState gs, boolean columns) {
        boolean any = false;
        List<GridItem> items = gs.items;
        for (int i = 0, n = items.size(); i < n; i++) {
            GridItem item = items.get(i);
            if (columns ? item.colSub : item.rowSub) {
                any = true;
                break;
            }
        }
        if (!any) return items;
        List<GridItem> out = new ArrayList<>(gs.items.size() + 8);
        for (GridItem item : gs.items) {
            if (!(columns ? item.colSub : item.rowSub)) {
                out.add(item);
                continue;
            }
            if (!(item.box.engineState instanceof GridState sgs)) continue;
            LayoutBox s = item.box;
            float edgeStart = columns ? s.mL + s.bL + s.pL : s.mT + s.bT + s.pT;
            float edgeEnd = columns ? s.mR + s.bR + s.pR : s.mB + s.bB + s.pB;
            int count = columns ? sgs.colCount : sgs.rowCount;
            for (GridItem sub : columns ? sgs.colEffective : sgs.rowEffective) {
                int subStart = columns ? sub.colStart : sub.rowStart;
                int subSpan = columns ? sub.colSpan : sub.rowSpan;
                GridItem proxy = new GridItem(sub.box);
                int start = (columns ? item.colStart : item.rowStart) + subStart;
                if (columns) {
                    proxy.colStart = start;
                    proxy.colSpan = subSpan;
                } else {
                    proxy.rowStart = start;
                    proxy.rowSpan = subSpan;
                }
                float add = (subStart == 0 ? edgeStart : 0) + (subStart + subSpan >= count ? edgeEnd : 0);
                proxy.minContribution = (columns ? sub.colMin : sub.minContribution) + add;
                proxy.maxContribution = (columns ? sub.colMax : sub.maxContribution) + add;
                if (columns) {
                    proxy.colMin = proxy.minContribution;
                    proxy.colMax = proxy.maxContribution;
                }
                out.add(proxy);
            }
        }
        return out;
    }

    /**
     * 行方向の最小寄与（CSS Grid §6.6 / §11.5）。{@code min-height} が長さならその値、auto なら自動の最小寸法
     * （内容の高さ。スクロールコンテナは 0）。
     *
     * 何が壊れていたか: 常に内容の高さを最小寄与にしていたので、{@code min-height: 0; overflow-y: auto} の
     * 列が確定した高さのグリッドの auto 行を内容の高さまで押し広げ、グリッドの外へ突き抜けていた。
     * Chrome では行は「上限まで伸ばす」段階でグリッドの高さに収まり、列の中身はスクロールする。
     */
    private static float rowMinContribution(LayoutBox b, LayoutContext ctx) {
        float decoration = b.verticalDecoration();
        ComputedStyle bs = b.axisStyle();
        String min = bs.get(CssPropertyIndex.MIN_HEIGHT);
        if (min != null && !"auto".equalsIgnoreCase(min.trim()) && !min.contains("%")
                && !LayoutMetrics.isIntrinsicSizeKeyword(min)) {
            float v = bs.length(CssPropertyIndex.MIN_HEIGHT, 0, ctx.viewport().rootW(), ctx.viewport().rootH());
            float specified = bs.isBorderBox() ? Math.max(v, decoration) : v + decoration;
            return Math.min(b.h, specified);
        }
        if (b.isScrollContainer()) return Math.min(b.h, decoration);
        return b.h;
    }

    // ==========================================
    // 配置（CSS Grid §8）
    // ==========================================

    private void placeItems(LayoutBox box, GridState gs, Areas areas) {
        String flow = box.style.keyword(CssPropertyIndex.GRID_AUTO_FLOW, "row");
        boolean columnFlow = flow.contains("column");
        boolean dense = flow.contains("dense");

        List<LayoutBox> ch = box.children;
        List<LayoutBox> children = gs.flowChildren;
        children.clear();
        for (int i = 0, n = ch.size(); i < n; i++) {
            LayoutBox c = ch.get(i);
            if (c.participatesInFlow()) children.add(c);
        }
        if (children.size() > 1) {
            children.sort(ORDER_COMPARATOR);
        }

        int explicitCols = gs.colSub ? gs.columns.tracks.size() : Math.max(gs.columns.tracks.size(), areas.columns);
        int explicitRows = gs.rowSub ? gs.rows.tracks.size() : Math.max(gs.rows.tracks.size(), areas.rows.size());
        for (int i = 0, n = children.size(); i < n; i++) {
            LayoutBox child = children.get(i);
            GridItem item = gs.obtainItem(child);
            Placement p = Placement.of(child.style);
            int[] col = resolveAxis(p.colStart, p.colEnd, gs.columns, explicitCols);
            int[] row = resolveAxis(p.rowStart, p.rowEnd, gs.rows, explicitRows);
            item.colStart = col[0];
            item.colSpan = col[1];
            item.rowStart = row[0];
            item.rowSpan = row[1];
            boolean grid = child.display != null && child.display.inner() == LayoutBox.Display.Inner.GRID;
            item.colSub = grid && isSubgridValue(child.style.get(CssPropertyIndex.GRID_TEMPLATE_COLUMNS));
            item.rowSub = grid && isSubgridValue(child.style.get(CssPropertyIndex.GRID_TEMPLATE_ROWS));
            gs.items.add(item);
        }

        Occupancy occupied = gs.occupancy;
        occupied.clear();
        int colCount = explicitCols, rowCount = explicitRows;
        List<GridItem> items = gs.items;
        int itemCount = items.size();
        for (int i = 0; i < itemCount; i++) {
            GridItem item = items.get(i);
            if (item.colStart >= 0) colCount = Math.max(colCount, item.colStart + item.colSpan);
            else colCount = Math.max(colCount, columnFlow ? 0 : item.colSpan);
            if (item.rowStart >= 0) rowCount = Math.max(rowCount, item.rowStart + item.rowSpan);
            else rowCount = Math.max(rowCount, columnFlow ? item.rowSpan : 0);
        }
        // 自動配置で伸ばさない方の軸（行優先なら列）の数。最低 1 本
        int fixedCount = Math.max(1, columnFlow ? rowCount : colCount);

        // 1. 両方の位置が決まっているアイテム
        for (int i = 0; i < itemCount; i++) {
            GridItem item = items.get(i);
            if (item.colStart >= 0 && item.rowStart >= 0) occupied.mark(item);
        }
        // 2. 伸ばさない方の軸の位置だけが決まっているアイテム（その行 / 列の中で詰める）
        Map<Integer, Integer> laneCursor = new LinkedHashMap<>();
        for (int i = 0; i < itemCount; i++) {
            GridItem item = items.get(i);
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
        for (int i = 0; i < itemCount; i++) {
            GridItem item = items.get(i);
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

        // サブグリッドの軸には暗黙のトラックが無い。はみ出した項目は最後のトラックへ寄せる
        for (GridItem item : gs.items) {
            if (gs.colSub && explicitCols > 0) {
                item.colSpan = Math.min(item.colSpan, explicitCols);
                item.colStart = Math.min(item.colStart, explicitCols - item.colSpan);
            }
            if (gs.rowSub && explicitRows > 0) {
                item.rowSpan = Math.min(item.rowSpan, explicitRows);
                item.rowStart = Math.min(item.rowStart, explicitRows - item.rowSpan);
            }
        }
        int cols = explicitCols, rows = explicitRows;
        for (GridItem item : gs.items) {
            cols = Math.max(cols, item.colStart + item.colSpan);
            rows = Math.max(rows, item.rowStart + item.rowSpan);
        }
        gs.colCount = Math.max(1, cols);
        gs.rowCount = rows;
    }

    /**
     * {@code repeat(auto-fit)} の繰り返しのうち、アイテムが 1 つも重ならないトラックを取り除く（CSS Grid §7.2.3.2）。
     * 潰れたトラックは寸法 0 で、前後の gap は 1 本に重なる。つまりトラックを無かったことにするのと同じなので、
     * アイテムの始点を詰める。
     */
    private static void collapseEmptyAutoFitTracks(GridState gs, boolean columns) {
        TrackList list = columns ? gs.columns : gs.rows;
        if (list.autoFit.isEmpty()) return;
        int n = list.tracks.size();
        boolean[] empty = new boolean[n];
        int emptyCount = 0;
        for (int i = list.autoFit.nextSetBit(0); i >= 0 && i < n; i = list.autoFit.nextSetBit(i + 1)) {
            boolean used = false;
            for (GridItem item : gs.items) {
                int start = columns ? item.colStart : item.rowStart;
                int span = columns ? item.colSpan : item.rowSpan;
                if (i >= start && i < start + span) {
                    used = true;
                    break;
                }
            }
            if (!used) {
                empty[i] = true;
                emptyCount++;
            }
        }
        if (emptyCount == 0 || emptyCount == n) return;
        int[] removedBefore = new int[n + 1];
        for (int i = 0; i < n; i++) removedBefore[i + 1] = removedBefore[i] + (empty[i] ? 1 : 0);
        for (GridItem item : gs.items) {
            int start = columns ? item.colStart : item.rowStart;
            int end = start + (columns ? item.colSpan : item.rowSpan);
            int newStart = start - removedBefore[Math.min(start, n)];
            int newEnd = end - removedBefore[Math.min(end, n)];
            if (columns) {
                item.colStart = newStart;
                item.colSpan = Math.max(1, newEnd - newStart);
            } else {
                item.rowStart = newStart;
                item.rowSpan = Math.max(1, newEnd - newStart);
            }
        }
        List<TrackDef> kept = new ArrayList<>();
        for (int i = 0; i < n; i++) if (!empty[i]) kept.add(list.tracks.get(i));
        list.tracks.clear();
        list.tracks.addAll(kept);
        list.lineNames.clear();
        if (columns) gs.colCount = Math.max(1, gs.colCount - emptyCount);
        else gs.rowCount = Math.max(0, gs.rowCount - emptyCount);
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
        String width = b.axisStyle().get(CssPropertyIndex.WIDTH);
        if (width != null && !"auto".equalsIgnoreCase(width.trim()) && !width.contains("%")
                && !LayoutMetrics.isIntrinsicSizeKeyword(width)) {
            return b.w + margins;
        }
        boolean hasContent = b.node.nodeType == NodeType.TEXT || b.hasFlowChildren() || isReplaced(b);
        return (hasContent ? b.getMinContentWidth(ctx) : b.horizontalDecoration()) + margins;
    }

    private static boolean isReplaced(LayoutBox b) {
        return ReplacedElements.isReplacedTag(b.node.tag);
    }

    // ==========================================
    // アイテムの寸法と揃え
    // ==========================================

    /** アイテムを列の幅で測る。stretch なら領域の幅いっぱい、それ以外は内容の幅に縮む。 */
    private void measureInArea(LayoutBox box, GridState gs, GridItem item, float parentH, LayoutContext ctx) {
        float areaW = span(gs.colSizes, item.colStart, item.colSpan, box.columnGap);
        if (gs.colSub && gs.colsFinal) {
            if (item.colStart == 0) areaW -= gs.colEdgeStart;
            if (item.colStart + item.colSpan >= gs.colSizes.length) areaW -= gs.colEdgeEnd;
            areaW = Math.max(0, areaW);
        }
        CssKeywords.Alignment justify = selfAlignment(item.box, CssPropertyIndex.JUSTIFY_SELF, box.style, CssPropertyIndex.JUSTIFY_ITEMS);
        boolean stretch = justify == CssKeywords.Alignment.STRETCH && !hasAutoMargin(item.box, true);
        FormattingContext childCtx = FormattingContext.of(FormattingContext.Display.GRID,
                justify == CssKeywords.Alignment.STRETCH, !stretch);
        // % の高さの子は、行の大きさがまだ決まっていない段階では auto として測る（呼び出し側が NaN を渡す）。
        // 行が決まった後に alignItems が領域の高さで測り直す
        // 行を決めたあとの alignItems が、stretch・% の高さの測り直しをするか、settleTentative で本当に測る
        ConstraintSpace inArea = new ConstraintSpace(areaW, parentH, childCtx);
        if (item.colSub || item.rowSub || !item.box.measureTentatively(inArea, ctx)) item.box.measure(inArea, ctx);
        item.percentBase = parentH;
    }

    /** 行が確定してから、アイテムを領域の中で揃える（stretch・start / end / center・auto マージン）。 */
    private void alignItems(LayoutBox box, GridState gs, float parentH, LayoutContext ctx) {
        float innerW = Math.max(0, box.w - box.horizontalDecoration());
        float innerH = Math.max(0, box.h - box.verticalDecoration());
        boolean colEdges = gs.colSub && gs.colsFinal;
        boolean rowEdges = gs.rowSub && gs.rowsOwnFinal;
        float[] colOffsets = colEdges ? subgridOffsets(gs.colSizes, box.columnGap, gs.colEdgeStart)
                : trackOffsets(gs.colSizes, box.columnGap, innerW, contentDistribution(box.style, CssPropertyIndex.JUSTIFY_CONTENT));
        float[] rowOffsets = rowEdges ? subgridOffsets(gs.rowSizes, box.rowGap, gs.rowEdgeStart)
                : trackOffsets(gs.rowSizes, box.rowGap, innerH, contentDistribution(box.style, CssPropertyIndex.ALIGN_CONTENT));
        gs.colOffsets = colOffsets;
        gs.rowOffsets = rowOffsets;
        // 列の向き（direction: rtl なら右から）。サブグリッドの列は親の向きに従うので裏返さない
        boolean rtl = !gs.colSub && isRtl(box.style);
        // 行が確定した。サブグリッドの行を持つ子は、これ以降の測定で親の行の寸法を使う
        gs.rowsFinal = !gs.rowSub || gs.rowsOwnFinal;

        for (GridItem item : gs.items) {
            LayoutBox b = item.box;
            float areaX = colOffsets[item.colStart];
            float areaY = rowOffsets[item.rowStart];
            float areaW = colOffsets[item.colStart + item.colSpan] - areaX - gapAfter(gs.colSizes, item.colStart + item.colSpan, box.columnGap, colOffsets);
            float areaH = rowOffsets[item.rowStart + item.rowSpan] - areaY - gapAfter(gs.rowSizes, item.rowStart + item.rowSpan, box.rowGap, rowOffsets);
            // サブグリッドの端の項目は、サブグリッドの余白・枠線・padding ぶん内側へ寄る
            if (colEdges) {
                if (item.colStart == 0) {
                    areaX += gs.colEdgeStart;
                    areaW -= gs.colEdgeStart;
                }
                if (item.colStart + item.colSpan >= gs.colSizes.length) areaW -= gs.colEdgeEnd;
            }
            if (rowEdges) {
                if (item.rowStart == 0) {
                    areaY += gs.rowEdgeStart;
                    areaH -= gs.rowEdgeStart;
                }
                if (item.rowStart + item.rowSpan >= gs.rowSizes.length) areaH -= gs.rowEdgeEnd;
            }

            CssKeywords.Alignment alignSelf = selfAlignment(b, CssPropertyIndex.ALIGN_SELF, box.style, CssPropertyIndex.ALIGN_ITEMS);
            if (item.rowSub) {
                // サブグリッドの行は、確定した親の行の寸法で測り直す（行方向は領域いっぱいに伸びる）
                float target = Math.max(0, areaH - b.mT - b.mB);
                b.measure(new ConstraintSpace(areaW, parentH, FormattingContext.of(FormattingContext.Display.GRID, true, false))
                        .withFixed(b.w, target), ctx);
                item.percentBase = areaH;
            } else if (alignSelf == CssKeywords.Alignment.STRETCH && !hasAutoMargin(b, false) && !hasSize(b.axisStyle(), CssPropertyIndex.HEIGHT)) {
                float target = areaH - b.mT - b.mB;
                // 行が内容より低いとき（min-height: 0 やスクロールコンテナ）は縮めて領域に合わせる
                if (Math.abs(b.h - target) > 0.01f) {
                    // 伸ばした高さは確定した高さとして測り直す（CSS Grid §6.2: 子の % の高さの基準になる）。
                    // 以前は高さだけを書き換えて解き直していて、中の height: 100% が 0 のままだった
                    b.measure(new ConstraintSpace(areaW, parentH, FormattingContext.of(FormattingContext.Display.GRID, alignSelf == CssKeywords.Alignment.STRETCH, false))
                            .withFixed(b.w, Math.max(0, target)), ctx);
                }
            } else if (isPercentage(b.axisStyle().get(CssPropertyIndex.HEIGHT)) && !(Math.abs(item.percentBase - areaH) < 0.01f)) {
                // % の高さはグリッドの領域の高さが基準（CSS Grid §6.1 / CSS Sizing 3 §5.2）。
                // 以前はグリッドコンテナの基準で解いていたので、height: 100% の子が領域を越えて伸びていた。
                // 同じ基準で測り済み（行が 1 つで領域 = グリッドの高さ）なら測り直さない
                b.measure(new ConstraintSpace(areaW, areaH, FormattingContext.of(FormattingContext.Display.GRID, alignSelf == CssKeywords.Alignment.STRETCH, false)), ctx);
                item.percentBase = areaH;
            }
            // 測り直しが来なかった項目の仮の産物を終わらせる（以降の alignWithin は auto マージンを書き換える）
            b.settleTentative();
            CssKeywords.Alignment justifySelf = selfAlignment(b, CssPropertyIndex.JUSTIFY_SELF, box.style, CssPropertyIndex.JUSTIFY_ITEMS);
            // rtl は列を右から並べる。論理（ltr）で組んでから内容ボックスの中で左右を裏返すので、物理の left / right は先に入れ替えておく
            if (rtl) justifySelf = justifySelf == CssKeywords.Alignment.LEFT ? CssKeywords.Alignment.RIGHT
                    : justifySelf == CssKeywords.Alignment.RIGHT ? CssKeywords.Alignment.LEFT : justifySelf;
            item.x = areaX + alignWithin(b, true, areaW, justifySelf);
            if (rtl) item.x = innerW - item.x - (b.w + b.mL + b.mR);
            item.y = areaY + alignWithin(b, false, areaH, alignSelf);
        }
    }

    /** 領域の中での位置（マージンボックスの始点の、領域の始点からのずれ）。auto マージンが先に余りを吸う。 */
    private static float alignWithin(LayoutBox b, boolean inline, float area, CssKeywords.Alignment alignment) {
        int startProp = inline ? CssPropertyIndex.MARGIN_LEFT : CssPropertyIndex.MARGIN_TOP;
        int endProp = inline ? CssPropertyIndex.MARGIN_RIGHT : CssPropertyIndex.MARGIN_BOTTOM;
        ComputedStyle bs = b.axisStyle();
        boolean autoStart = isAuto(bs, startProp), autoEnd = isAuto(bs, endProp);
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
            case CENTER -> free / 2;
            case END, FLEX_END, SELF_END, RIGHT -> free;
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
    /**
     * 包含ブロックが grid 容器である絶対配置の箱の包含ブロック（CSS Grid §10.1）: grid-placement の線が示す領域で、
     * auto の側・グリッドに無い線の側は padding ボックスの端。{x, y, w, h} を grid 容器の padding ボックスの左上から測って返す。
     * 容器がまだ組まれていなければ null。
     */
    static float[] absoluteArea(LayoutBox grid, LayoutBox abs) {
        if (!(grid.engineState instanceof GridState gs) || gs.colOffsets.length != gs.colSizes.length + 1
                || gs.rowOffsets.length != gs.rowSizes.length + 1) return null;
        Placement p = Placement.of(abs.style);
        float padW = Math.max(0, grid.w - grid.bL - grid.bR), padH = Math.max(0, grid.h - grid.bT - grid.bB);
        boolean rtl = !gs.colSub && isRtl(grid.style);
        float[] x = absoluteAxis(p.colStart, p.colEnd, gs.columns, gs.columns.tracks.size(), gs.colSizes, gs.colOffsets, grid.columnGap,
                rtl ? grid.pR : grid.pL, padW);
        float[] y = absoluteAxis(p.rowStart, p.rowEnd, gs.rows, gs.rows.tracks.size(), gs.rowSizes, gs.rowOffsets, grid.rowGap, grid.pT, padH);
        // rtl の列は右の padding 端から数える
        if (rtl) x[0] = padW - x[0] - x[1];
        return new float[]{x[0], y[0], x[1], y[1]};
    }

    private static boolean isRtl(ComputedStyle style) {
        String d = style.get(CssPropertyIndex.DIRECTION);
        return d != null && "rtl".equalsIgnoreCase(d.trim());
    }

    /** 1 軸の {始点, 長さ}（padding ボックスの始点から）。 */
    private static float[] absoluteAxis(GridLine start, GridLine end, TrackList tracks, int explicit, float[] sizes, float[] offsets,
                                        float gap, float padStart, float padLen) {
        int n = sizes.length;
        Integer s = start.resolve(tracks, explicit, true);
        Integer e = end.resolve(tracks, explicit, false);
        if (s == null && e != null && start.span > 0) s = e - start.span;
        if (e == null && s != null && end.span > 0) e = s + end.span;
        if (s != null && e != null && e < s) { Integer t = s; s = e; e = t; }
        // 始点と同じ終点は捨てる（CSS Grid §8.3.1）。絶対配置では auto（padding の端）になる
        if (s != null && s.equals(e)) e = null;
        // グリッドに無い線は auto（padding の端）として扱う
        if (s != null && (s < 0 || s > n)) s = null;
        if (e != null && (e < 0 || e > n)) e = null;
        float a = s == null ? 0 : padStart + offsets[s];
        float b = e == null ? padLen : padStart + offsets[e] - (e > 0 ? gapAfter(sizes, e, gap, offsets) : 0);
        return new float[]{a, Math.max(0, b - a)};
    }

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
    private static CssKeywords.Alignment selfAlignment(LayoutBox b, int selfProp, ComputedStyle container, int itemsProp) {
        CssKeywords.Alignment v = b.style.keywordOf(selfProp, CssKeywords.Alignment.class);
        if (v == CssKeywords.Alignment.AUTO) v = container.keywordOf(itemsProp, CssKeywords.Alignment.class);
        if (v == CssKeywords.Alignment.NORMAL || v == CssKeywords.Alignment.LEGACY || v == CssKeywords.Alignment.AUTO) {
            boolean sized = hasSize(b.axisStyle(), selfProp == CssPropertyIndex.JUSTIFY_SELF ? CssPropertyIndex.WIDTH : CssPropertyIndex.HEIGHT);
            return isReplaced(b) || sized ? CssKeywords.Alignment.START : CssKeywords.Alignment.STRETCH;
        }
        // first baseline / last baseline は baseline（safe / unsafe は読み取りで落とし済み）
        return v == CssKeywords.Alignment.FIRST_BASELINE || v == CssKeywords.Alignment.LAST_BASELINE ? CssKeywords.Alignment.BASELINE : v;
    }

    private static boolean hasAutoMargin(LayoutBox b, boolean inline) {
        ComputedStyle bs = b.axisStyle();
        return inline
                ? isAuto(bs, CssPropertyIndex.MARGIN_LEFT) || isAuto(bs, CssPropertyIndex.MARGIN_RIGHT)
                : isAuto(bs, CssPropertyIndex.MARGIN_TOP) || isAuto(bs, CssPropertyIndex.MARGIN_BOTTOM);
    }

    private static boolean isAuto(ComputedStyle style, int prop) {
        return "auto".equalsIgnoreCase(style.getOrDefault(prop, "0").trim());
    }

    /** 割合（{@code 100%}、{@code calc(100% - 8px)} など）を含む長さか。 */
    private static boolean isPercentage(String value) {
        return value != null && value.contains("%");
    }

    private static boolean hasSize(ComputedStyle style, int prop) {
        String v = style.get(prop);
        return v != null && !"auto".equalsIgnoreCase(v.trim());
    }

    private static String contentDistribution(ComputedStyle style, int prop) {
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

    static int topLevelComma(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) return i;
        }
        return -1;
    }

    private static final ThreadLocal<Map<String, List<String>>> SPLIT_TRACK_CACHE =
            ThreadLocal.withInitial(java.util.HashMap::new);

    /** 最上位の空白で区切る。括弧（関数）と角括弧（線の名前）の中は区切らない。 */
    static List<String> splitTrackList(String def) {
        Map<String, List<String>> cache = SPLIT_TRACK_CACHE.get();
        List<String> cached = cache.get(def);
        if (cached != null) return cached;
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
        List<String> res = List.copyOf(list);
        if (cache.size() >= 256) cache.clear();
        cache.put(def, res);
        return res;
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

    private static final Comparator<LayoutBox> ORDER_COMPARATOR =
            Comparator.comparingInt(c -> (int) c.style.number(CssPropertyIndex.ORDER, 0f));

    /** アイテムの 4 本の線。ロングハンドがあればそれを、無ければ grid-row / grid-column / grid-area を読む。 */
    private record Placement(GridLine rowStart, GridLine colStart, GridLine rowEnd, GridLine colEnd) {
        private static final Object MEMO_KEY = new Object();

        static Placement of(ComputedStyle s) {
            return s.paintMemoSelf(MEMO_KEY, Placement::compute);
        }

        private static Placement compute(ComputedStyle s) {
            String[] area = split(s.get(CssPropertyIndex.GRID_AREA), 4);
            String[] row = split(s.get(CssPropertyIndex.GRID_ROW), 2);
            String[] col = split(s.get(CssPropertyIndex.GRID_COLUMN), 2);
            String rs = first(s.get(CssPropertyIndex.GRID_ROW_START), row[0], area[0]);
            String cs = first(s.get(CssPropertyIndex.GRID_COLUMN_START), col[0], area[1]);
            String re = first(s.get(CssPropertyIndex.GRID_ROW_END), row[1], area[2]);
            String ce = first(s.get(CssPropertyIndex.GRID_COLUMN_END), col[1], area[3]);
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

        void clear() {
            cells.clear();
        }

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
        /** この項目はグリッドコンテナで、列 / 行をサブグリッドとして親と共有する。 */
        boolean colSub, rowSub;
        public LayoutBox box;
        float minContribution, maxContribution;
        /** 列の寄与の控え（サブグリッドの親が読む。行の測定で minContribution / maxContribution が上書きされるため）。 */
        float colMin, colMax;
        /** 最後に測ったときの % の高さの基準（測り直しを省くため。NaN は auto として測った）。 */
        float percentBase = Float.NaN;
        /** 位置（マージンボックスの左上、内容ボックス相対）。 */
        float x, y;

        GridItem(LayoutBox box) {
            this.box = box;
        }

        GridItem reset(LayoutBox box) {
            this.box = box;
            this.colStart = this.colSpan = this.rowStart = this.rowSpan = 0;
            this.colSub = this.rowSub = false;
            this.minContribution = this.maxContribution = 0f;
            this.colMin = this.colMax = 0f;
            this.percentBase = Float.NaN;
            this.x = this.y = 0f;
            return this;
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
        final List<GridItem> itemPool = new ArrayList<>();
        final List<LayoutBox> flowChildren = new ArrayList<>();
        final Occupancy occupancy = new Occupancy();
        public int colCount = 1, rowCount = 0;
        /** 親グリッドの状態と、親から見たこの箱（サブグリッドでなければ null）。 */
        GridState parentState;
        GridItem parentItem;
        boolean colSub, rowSub;
        /** 列 / 行の寸法が確定した（子のサブグリッドが親の寸法を読んでよい）。 */
        boolean colsFinal, rowsFinal;
        /** この箱が親の行の寸法を使って確定した。 */
        boolean rowsOwnFinal;
        /** サブグリッドの端の項目へ足す、自身の余白・枠線・padding。 */
        float colEdgeStart, colEdgeEnd, rowEdgeStart, rowEdgeEnd;
        /** トラックの寸法を決めるアイテム（サブグリッドの子を親のトラックへ読み替えたものを含む）。 */
        List<GridItem> colEffective = List.of(), rowEffective = List.of();

        GridState resetForLayout() {
            for (int i = 0, n = items.size(); i < n; i++) itemPool.add(items.get(i));
            items.clear();
            flowChildren.clear();
            occupancy.clear();
            autoColumns = List.of();
            autoRows = List.of();
            colCount = 1;
            rowCount = 0;
            parentState = null;
            parentItem = null;
            colSub = rowSub = false;
            colsFinal = rowsFinal = rowsOwnFinal = false;
            colEdgeStart = colEdgeEnd = rowEdgeStart = rowEdgeEnd = 0f;
            colEffective = List.of();
            rowEffective = List.of();
            return this;
        }

        GridItem obtainItem(LayoutBox box) {
            return itemPool.isEmpty() ? new GridItem(box) : itemPool.remove(itemPool.size() - 1).reset(box);
        }

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
        public boolean contributesScrollExtent() {
            return !items.isEmpty();
        }

        @Override
        public void contributeScrollExtent(ScrollExtent extent, float scrollX, float scrollY) {
            for (int i = 0, n = items.size(); i < n; i++) {
                GridItem item = items.get(i);
                if (item.box.isAbsolute) continue;
                extent.include(item.box, scrollX, scrollY);
            }
        }
    }
}
