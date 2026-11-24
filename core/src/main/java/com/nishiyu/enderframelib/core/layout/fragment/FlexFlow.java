package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxKind;
import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.css.CssLength;
import com.nishiyu.enderframelib.core.css.FlexGridStyle;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.css.model.CssKeywords.Alignment;
import com.nishiyu.enderframelib.core.css.model.CssKeywords.FlexWrap;
import com.nishiyu.enderframelib.core.css.model.Overflow;
import com.nishiyu.enderframelib.core.kernel.LayoutUnit;

import java.util.ArrayList;
import java.util.List;

/**
 * フレックスレイアウト（Engine v3 M2a）。{@code row} / {@code column}・{@code flex-wrap}（row）・{@code flex-grow} /
 * {@code flex-shrink} / {@code flex-basis}・自動の最小サイズ（{@code min-content}）・{@code align-items/self}・
 * {@code justify-content}・{@code gap}。
 *
 * <p>項目は <b>pass 1</b>（主軸の大きさを決めて高さは内容から）と <b>pass 2</b>（stretch・確定した主軸の大きさで測り直す）の
 * 異なる制約で測られ得る。1 回の整形の間に使った断片は項目ごとの {@link LayoutCache.Slots} に集め、結果の
 * {@link FlowChunk#slots} に残すので、次のフレームは両方とも当たる（入れ子でも指数的に増えない）。
 * 基準の大きさ・自動の最小サイズは {@link LayoutCache#minMax}（内在サイズの 1 層メモ）から取り、仮のレイアウトをしない。</p>
 *
 * <p>M2a の範囲外（M2b）: {@code *-reverse}・{@code column} の折り返し・{@code align-content}・主軸の auto 余白・ベースライン揃え。</p>
 */
final class FlexFlow implements FormattingEngine {
    static final FlexFlow INSTANCE = new FlexFlow();

    private FlexFlow() { }

    /** 項目 1 つの作業域（1 回の整形の間だけ）。 */
    private static final class Item {
        final BoxNode box;
        final BoxGeometry g;
        final LayoutCache.Slots slots;
        float base, hyp, minMain, maxMain, target, raw, grow, shrink, outerMain;
        boolean frozen;
        LayoutFragment frag;
        float x, y;

        Item(BoxNode box, BoxGeometry g, LayoutCache.Slots slots) {
            this.box = box;
            this.g = g;
            this.slots = slots;
        }
    }

    @Override
    public LayoutFragment layout(BoxNode node, ConstraintSpace space, LayoutFragment prior, LayoutSession s) {
        Style st = node.style;
        float cbw = space.availableWidth;
        BoxGeometry g = new BoxGeometry(st, cbw, s);
        float w, ml = g.mL;
        if (!Float.isNaN(space.fixedWidth)) {
            w = space.fixedWidth;
        } else {
            float spec = g.specifiedWidth(st, cbw, s);
            w = g.clampWidth(st, Float.isNaN(spec) ? Math.max(0, cbw - g.mL - g.mR) : spec, cbw, s);
            if (g.autoML && g.autoMR) ml = Math.max(0, (cbw - w) / 2f);
        }
        w = LayoutUnit.snap(w);
        float cw = Math.max(0, w - g.hdec());
        float fixedH = !Float.isNaN(space.fixedHeight) ? space.fixedHeight
                : space.contentBlockSize() ? Float.NaN : g.specifiedHeight(st, space.percentHeightBase, s);
        float ch = Float.isNaN(fixedH) ? Float.NaN : Math.max(0, fixedH - g.vdec());

        FlexGridStyle fg = st.flexGrid;
        boolean row = fg.flexDirection == null || fg.flexDirection.isRow();
        List<BoxNode> outOfFlow = new ArrayList<>();
        List<Item> items = items(node, cw, prior, outOfFlow, s);
        float contentH = row ? row(st, items, cw, ch, s) : column(st, items, cw, ch, s);
        float insetL = g.bL + g.pL, insetT = g.bT + g.pT;
        List<OutOfFlow.Pending> pend = new ArrayList<>();
        for (BoxNode b : outOfFlow) pend.add(new OutOfFlow.Pending(b, insetL, insetT)); // 静的位置は内容ボックスの左上

        int n = items.size();
        LayoutFragment[] frags = new LayoutFragment[n];
        float[] xs = new float[n], ys = new float[n];
        LayoutFragment[][] slots = null;
        for (int i = 0; i < n; i++) {
            Item it = items.get(i);
            frags[i] = it.frag;
            float[] rel = OutOfFlow.relativeOffset(it.box.style, cw, ch, s);
            xs[i] = LayoutUnit.snap(it.x + (rel != null ? rel[0] : 0));
            ys[i] = LayoutUnit.snap(it.y + (rel != null ? rel[1] : 0));
            if (it.frag.pending != null) for (OutOfFlow.Pending p : it.frag.pending) pend.add(p.shift(insetL + xs[i], insetT + ys[i]));
            LayoutFragment[] used = it.slots.used(it.frag);
            if (used != null) {
                if (slots == null) slots = new LayoutFragment[n][];
                slots[i] = used;
            }
        }
        contentH = LayoutUnit.snap(contentH);
        FlowChunk fc = new FlowChunk(null, null, frags, xs, ys, contentH, 0, 0, 0, 0, false, slots, null);
        float h = Float.isNaN(fixedH) ? contentH + g.vdec() : fixedH;
        if (Float.isNaN(space.fixedHeight) && !space.contentBlockSize()) h = g.clampHeight(st, h, space.percentHeightBase, s);
        h = LayoutUnit.snap(h);
        OutOfFlow.Pending[] up = null;
        PlacedFragment[] oof = null;
        if (!pend.isEmpty()) {
            if (OutOfFlow.containsOutOfFlow(st)) {
                oof = OutOfFlow.layout(pend, g.bL, g.bT, Math.max(0, w - g.bL - g.bR), Math.max(0, h - g.bT - g.bB), prior, s);
            } else {
                up = pend.toArray(OutOfFlow.NONE);
            }
        }
        return new LayoutFragment(node, space, w, h, LayoutUnit.snap(ml), Math.max(0, g.mT), Math.min(0, g.mT),
                Math.max(0, g.mB), Math.min(0, g.mB), insetL, insetT, false,
                new ChunkedFlow(new FlowChunk[]{fc}, new float[]{0}), null, up, oof);
    }

    /** 項目（{@code display: none} と畳まれる空白を除き、{@code order} で安定に並べる）と、それぞれの前回の候補。 */
    private static List<Item> items(BoxNode node, float cw, LayoutFragment prior, List<BoxNode> outOfFlow, LayoutSession s) {
        FlowChunk pc = prior != null && prior.flow != null && prior.flow.chunks.length == 1 ? prior.flow.chunks[0] : null;
        List<BoxNode> kids = new ArrayList<>();
        boolean ordered = false;
        for (BoxNode k : node.children.toList()) {
            if (k.kind == BoxKind.NONE || k.collapsibleWhitespace()) continue;
            if (OutOfFlow.is(k)) { // 流れの外: 項目にしない
                outOfFlow.add(k);
                continue;
            }
            kids.add(k);
            ordered |= Style.num(k.style.flexGrid.order, 0) != 0;
        }
        if (ordered) kids.sort((a, b) -> Float.compare(Style.num(a.style.flexGrid.order, 0), Style.num(b.style.flexGrid.order, 0)));
        List<Item> out = new ArrayList<>(kids.size());
        for (int i = 0; i < kids.size(); i++) {
            BoxNode k = kids.get(i);
            int j = pc == null ? -1 : indexOf(pc, k, i);
            LayoutCache.Slots slots = j < 0 ? new LayoutCache.Slots(null, null)
                    : new LayoutCache.Slots(pc.frags[j], pc.slots != null ? pc.slots[j] : null);
            out.add(new Item(k, new BoxGeometry(k.style, cw, s), slots));
        }
        return out;
    }

    private static int indexOf(FlowChunk pc, BoxNode k, int hint) {
        LayoutFragment[] fs = pc.frags;
        if (hint < fs.length && fs[hint].box != null && fs[hint].box.node == k.node) return hint;
        for (int i = 0; i < fs.length; i++) if (fs[i].box != null && fs[i].box.node == k.node) return i;
        return -1;
    }

    /** {@code flex-basis} が確定した長さか（{@code auto} / {@code content} / 基準の無い % でない）。 */
    private static boolean basisDefinite(Style st, long v, float base) {
        if (!CssLength.isDeclared(v) || CssLength.isKeyword(v)) return false;
        if (CssLength.isCalc(v) && CssLength.isIntrinsicSize(v, st.flexGrid.exprs)) return false;
        return !CssLength.hasPercent(v, st.flexGrid.exprs) || Float.isFinite(base);
    }

    private static float gap(Style st, long v, float base, LayoutSession s) {
        if (!CssLength.isDeclared(v) || CssLength.isKeyword(v)) return 0;
        if (CssLength.hasPercent(v, st.flexGrid.exprs) && !Float.isFinite(base)) return 0;
        return LayoutUnit.snap(st.gridPx(v, Float.isFinite(base) ? base : 0, s.viewportW(), s.viewportH()));
    }

    private static Alignment alignOf(Style container, Style item) {
        Alignment a = item.flexGrid.alignSelf;
        if (a == null || a == Alignment.AUTO) a = container.flexGrid.alignItems;
        return a == null || a == Alignment.NORMAL ? Alignment.STRETCH : a;
    }

    private static boolean overflowVisible(Style st) {
        Overflow ox = st.box.overflowX, oy = st.box.overflowY;
        return (ox == null || ox == Overflow.VISIBLE) && (oy == null || oy == Overflow.VISIBLE);
    }

    private static ConstraintSpace itemSpace(Item it, float cw, float fixedW, float fixedH, float ch, LayoutSession s) {
        return itemSpace(it, cw, fixedW, fixedH, ch, 0, s);
    }

    private static ConstraintSpace itemSpace(Item it, float cw, float fixedW, float fixedH, float ch, int extra, LayoutSession s) {
        float pct = BoxGeometry.usesPercentHeight(it.box.style) ? ch : Float.NaN;
        return s.space(cw, fixedW, fixedH, pct, ConstraintSpace.FORMATTING_ROOT | extra);
    }

    // ---- row ----

    private static float row(Style st, List<Item> items, float cw, float ch, LayoutSession s) {
        FlexGridStyle fg = st.flexGrid;
        boolean wrap = fg.flexWrap == FlexWrap.WRAP || fg.flexWrap == FlexWrap.WRAP_REVERSE;
        float mainGap = gap(st, fg.columnGap, cw, s), crossGap = gap(st, fg.rowGap, ch, s);
        LayoutCache cache = s.cache();
        for (Item it : items) {
            Style cst = it.box.style;
            BoxGeometry ig = it.g;
            it.outerMain = ig.mL + ig.mR;
            it.grow = Math.max(0, Style.num(cst.flexGrid.flexGrow, 0));
            it.shrink = Math.max(0, Style.num(cst.flexGrid.flexShrink, 1));
            float specW = ig.specifiedWidth(cst, cw, s);
            long fb = cst.flexGrid.flexBasis;
            MinMaxSizes mm = null;
            if (basisDefinite(cst, fb, cw)) {
                float b = LayoutUnit.snap(cst.gridPx(fb, cw, s.viewportW(), s.viewportH()));
                it.base = cst.box.boxSizing == com.nishiyu.enderframelib.core.css.model.CssKeywords.BoxSizing.BORDER_BOX ? b : b + ig.hdec();
            } else if (CssLength.isAutoOrUndeclared(fb) && !Float.isNaN(specW)) { // 幅を見るのは flex-basis: auto だけ
                it.base = specW;
            } else {
                mm = cache.minMax(it.box, s); // 内容基準の基準の大きさ: max-content（仮のレイアウトをしない）
                it.base = mm.max;
            }
            if (BoxGeometry.definite(cst, cst.box.minWidth, cw)) {
                it.minMain = ig.clampWidth(cst, 0, cw, s);
            } else if (overflowVisible(cst)) { // 自動の最小サイズ: min-content（指定の幅があればそれ以下）
                if (mm == null) mm = cache.minMax(it.box, s);
                it.minMain = Float.isNaN(specW) ? mm.min : Math.min(mm.min, specW);
            } else {
                it.minMain = ig.hdec();
            }
            it.maxMain = BoxGeometry.definite(cst, cst.box.maxWidth, cw) ? ig.clampWidth(cst, Float.MAX_VALUE, cw, s) : Float.MAX_VALUE;
            it.hyp = clamp(it.base, it.minMain, it.maxMain);
        }
        float y = 0;
        int start = 0, lineCount = 0;
        while (start < items.size()) {
            int end = start + 1;
            if (wrap) {
                float used = items.get(start).hyp + items.get(start).outerMain;
                while (end < items.size() && used + mainGap + items.get(end).hyp + items.get(end).outerMain <= cw + 0.01f) {
                    used += mainGap + items.get(end).hyp + items.get(end).outerMain;
                    end++;
                }
            } else {
                end = items.size();
            }
            List<Item> line = items.subList(start, end);
            resolveFlexible(line, cw, mainGap);
            float lineCross = 0;
            for (Item it : line) { // pass 1: 主軸の大きさを確定し、高さは内容から
                it.frag = cache.layout(it.box, itemSpace(it, cw, it.target, Float.NaN, ch, s), it.slots, s);
                lineCross = Math.max(lineCross, it.frag.height + it.g.mT + it.g.mB);
            }
            if (!wrap && !Float.isNaN(ch)) lineCross = ch;
            for (Item it : line) { // pass 2: stretch
                Style cst = it.box.style;
                Alignment a = alignOf(st, cst);
                float hh = it.frag.height;
                if (a == Alignment.STRETCH && !BoxGeometry.definite(cst, cst.box.height, ch)) {
                    float target = it.g.clampHeight(cst, Math.max(0, lineCross - it.g.mT - it.g.mB), ch, s);
                    target = LayoutUnit.snap(target);
                    if (target != hh) it.frag = cache.layout(it.box, itemSpace(it, cw, it.target, target, ch, s), it.slots, s);
                    hh = it.frag.height;
                }
                float free = lineCross - hh - it.g.mT - it.g.mB;
                it.y = y + it.g.mT + switch (a) {
                    case CENTER -> free / 2f;
                    case END, FLEX_END, SELF_END -> free;
                    default -> 0f;
                };
            }
            justify(fg.justifyContent, line, cw, mainGap);
            y += lineCross + crossGap;
            lineCount++;
            start = end;
        }
        return lineCount == 0 ? 0 : (Float.isNaN(ch) || wrap ? y - crossGap : ch);
    }

    // ---- column ----

    private static float column(Style st, List<Item> items, float cw, float ch, LayoutSession s) {
        FlexGridStyle fg = st.flexGrid;
        float mainGap = gap(st, fg.rowGap, ch, s);
        LayoutCache cache = s.cache();
        float[] crossW = new float[items.size()];
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            Style cst = it.box.style;
            BoxGeometry ig = it.g;
            it.outerMain = ig.mT + ig.mB;
            it.grow = Math.max(0, Style.num(cst.flexGrid.flexGrow, 0));
            it.shrink = Math.max(0, Style.num(cst.flexGrid.flexShrink, 1));
            float avail = Math.max(0, cw - ig.mL - ig.mR);
            float specW = ig.specifiedWidth(cst, cw, s);
            Alignment a = alignOf(st, cst);
            float wv;
            if (!Float.isNaN(specW)) wv = ig.clampWidth(cst, specW, cw, s);
            else if (a == Alignment.STRETCH) wv = ig.clampWidth(cst, avail, cw, s);
            else {
                MinMaxSizes mm = cache.minMax(it.box, s); // fit-content
                wv = ig.clampWidth(cst, Math.min(mm.max, Math.max(mm.min, avail)), cw, s);
            }
            crossW[i] = LayoutUnit.snap(wv);
            float free = cw - crossW[i] - ig.mL - ig.mR;
            it.x = ig.mL + switch (a) {
                case CENTER -> free / 2f;
                case END, FLEX_END, SELF_END -> free;
                default -> 0f;
            };
            // pass 1: 幅を確定し、高さは自分の height を掛けない内容から（内容基準の基準の大きさ・自動の最小サイズ）
            it.frag = cache.layout(it.box, itemSpace(it, cw, crossW[i], Float.NaN, ch, ConstraintSpace.CONTENT_BLOCK_SIZE, s), it.slots, s);
            float content = it.frag.height;
            long fb = cst.flexGrid.flexBasis;
            float specH = ig.specifiedHeight(cst, ch, s);
            if (basisDefinite(cst, fb, ch)) {
                float b = LayoutUnit.snap(cst.gridPx(fb, Float.isFinite(ch) ? ch : 0, s.viewportW(), s.viewportH()));
                it.base = cst.box.boxSizing == com.nishiyu.enderframelib.core.css.model.CssKeywords.BoxSizing.BORDER_BOX ? b : b + ig.vdec();
            } else {
                // 高さを見るのは flex-basis: auto だけ（content・基準の無い % は内容から。CSS Flexbox §9.2 3.E）
                it.base = CssLength.isAutoOrUndeclared(fb) && !Float.isNaN(specH) ? specH : content;
            }
            if (BoxGeometry.definite(cst, cst.box.minHeight, ch)) it.minMain = ig.clampHeight(cst, 0, ch, s);
            else it.minMain = overflowVisible(cst) ? (Float.isNaN(specH) ? content : Math.min(content, specH)) : ig.vdec();
            it.maxMain = BoxGeometry.definite(cst, cst.box.maxHeight, ch) ? ig.clampHeight(cst, Float.MAX_VALUE, ch, s) : Float.MAX_VALUE;
            it.hyp = clamp(it.base, it.minMain, it.maxMain);
        }
        if (Float.isNaN(ch)) {
            for (Item it : items) it.target = it.hyp;
        } else {
            resolveFlexible(items, ch, mainGap);
        }
        for (int i = 0; i < items.size(); i++) { // pass 2: 確定した高さで測り直す
            Item it = items.get(i);
            if (it.target != it.frag.height) {
                it.frag = cache.layout(it.box, itemSpace(it, cw, crossW[i], it.target, ch, s), it.slots, s);
            }
        }
        float total = 0;
        for (Item it : items) total += it.frag.height + it.outerMain;
        total += mainGap * Math.max(0, items.size() - 1);
        float mainSize = Float.isNaN(ch) ? total : ch;
        float[] lead = distribute(fg.justifyContent, mainSize - total, items.size(), mainGap);
        float cursor = lead[0], between = lead[1];
        for (Item it : items) {
            it.y = cursor + it.g.mT;
            cursor += it.frag.height + it.outerMain + between;
        }
        return mainSize;
    }

    // ---- 共通 ----

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** 柔軟な長さの解決（CSS Flexbox §9.7。凍結を繰り返し、min/max の違反を解く）。結果は {@code target}。 */
    private static void resolveFlexible(List<Item> line, float avail, float gapW) {
        float gaps = gapW * Math.max(0, line.size() - 1);
        float used = gaps;
        for (Item it : line) used += it.hyp + it.outerMain;
        boolean growing = used < avail;
        float initialFree = avail - gaps;
        for (Item it : line) {
            initialFree -= it.outerMain + it.base;
            float factor = growing ? it.grow : it.shrink;
            it.frozen = factor == 0 || (growing ? it.base > it.hyp : it.base < it.hyp);
            it.target = it.frozen ? it.hyp : it.base;
        }
        for (int iter = 0; iter <= line.size(); iter++) {
            float free = avail - gaps, sumGrow = 0, sumScaled = 0;
            boolean anyOpen = false;
            for (Item it : line) {
                free -= it.outerMain + (it.frozen ? it.target : it.base);
                if (!it.frozen) {
                    anyOpen = true;
                    sumGrow += it.grow;
                    sumScaled += it.shrink * it.base;
                }
            }
            if (!anyOpen) break;
            if (growing && sumGrow < 1) free = Math.min(free, initialFree * sumGrow);
            float violation = 0;
            for (Item it : line) {
                if (it.frozen) continue;
                float t = it.base;
                if (growing && sumGrow > 0) t += free * it.grow / sumGrow;
                else if (!growing && sumScaled > 0) t += free * it.shrink * it.base / sumScaled;
                float c = clamp(t, it.minMain, it.maxMain);
                violation += c - t;
                it.raw = t;
                it.target = c;
            }
            for (Item it : line) { // 違反の総和の向きで凍結する（0 なら全部、正なら min 違反、負なら max 違反）
                if (it.frozen) continue;
                if (violation == 0 || (violation > 0 && it.target > it.raw) || (violation < 0 && it.target < it.raw)) it.frozen = true;
            }
        }
        for (Item it : line) it.target = LayoutUnit.snap(it.target);
    }

    /** {@code justify-content} の {先頭の余り, 項目の間}。 */
    private static float[] distribute(Alignment j, float free, int n, float gapW) {
        if (j == null) return new float[]{0, gapW};
        return switch (j) {
            case CENTER -> new float[]{free / 2f, gapW};
            case END, FLEX_END, RIGHT -> new float[]{free, gapW};
            case SPACE_BETWEEN -> free > 0 && n > 1 ? new float[]{0, gapW + free / (n - 1)} : new float[]{0, gapW};
            case SPACE_AROUND -> free > 0 && n > 0 ? new float[]{free / n / 2f, gapW + free / n} : new float[]{0, gapW};
            case SPACE_EVENLY -> free > 0 ? new float[]{free / (n + 1), gapW + free / (n + 1)} : new float[]{0, gapW};
            default -> new float[]{0, gapW};
        };
    }

    private static void justify(Alignment j, List<Item> line, float cw, float gapW) {
        float used = gapW * Math.max(0, line.size() - 1);
        for (Item it : line) used += it.target + it.outerMain;
        float[] d = distribute(j, cw - used, line.size(), gapW);
        float cursor = d[0];
        for (Item it : line) {
            it.x = cursor + it.g.mL;
            cursor += it.target + it.outerMain + d[1];
        }
    }

    @Override
    public MinMaxSizes computeMinMaxSizes(BoxNode node, MinMaxContext ctx, LayoutSession s) {
        Style st = node.style;
        BoxGeometry g = new BoxGeometry(st, Float.NaN, s);
        FlexGridStyle fg = st.flexGrid;
        boolean row = fg.flexDirection == null || fg.flexDirection.isRow();
        boolean wrap = fg.flexWrap == FlexWrap.WRAP || fg.flexWrap == FlexWrap.WRAP_REVERSE;
        float gapW = row ? gap(st, fg.columnGap, Float.NaN, s) : 0;
        float min = 0, max = 0;
        int n = 0;
        for (BoxNode k : node.children.toList()) {
            if (k.kind == BoxKind.NONE || k.collapsibleWhitespace() || OutOfFlow.is(k)) continue;
            Style cst = k.style;
            BoxGeometry ig = new BoxGeometry(cst, Float.NaN, s);
            MinMaxSizes m = ig.contribution(cst, s.cache().minMax(k, s), s);
            float margins = BlockFlow.fixedMargin(cst, cst.box.marginLeft, s) + BlockFlow.fixedMargin(cst, cst.box.marginRight, s);
            if (row) {
                max += m.max + margins;
                min = wrap ? Math.max(min, m.min + margins) : min + m.min + margins;
            } else {
                max = Math.max(max, m.max + margins);
                min = Math.max(min, m.min + margins);
            }
            n++;
        }
        if (row && n > 1) {
            max += gapW * (n - 1);
            if (!wrap) min += gapW * (n - 1);
        }
        return new MinMaxSizes(LayoutUnit.snap(min + g.hdec()), LayoutUnit.snap(max + g.hdec()));
    }
}
