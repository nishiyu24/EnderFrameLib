package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.dom.HtmlNode.NodeType;
import com.nishiyu.enderframelib.core.layout.LayoutBox.SizingMode;
import com.nishiyu.enderframelib.core.layout.LayoutContext.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.LayoutContext.FormattingContext;
import com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutEngineState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * flex レイアウト（CSS Flexbox 1 §9 のアルゴリズム）。
 *
 * 段の分け方: {@link #measureContent} がアイテムの仮の寸法を測り、主軸が幅の行方向は
 * {@link #contentBlockSize} が伸縮長を解いてから高さを測る。主軸が高さの列方向は、コンテナの高さが
 * 決まる {@link #finishLayout} で伸縮長を解く。位置は {@link #finishLayout} が内容ボックス相対で決め
 * （{@link FlexState}）、{@link #arrangeChildren} は平行移動するだけ。
 *
 * 何が壊れていたか: 以前は {@code order}・{@code *-reverse}・{@code wrap-reverse}・{@code align-content}・
 * auto マージン・{@code baseline} を読まず、縮小は {@code width} を持つアイテムに効かず、伸長は
 * {@code max-width} で止まらず（止まった分を他のアイテムに回さず）、{@code min-width: auto} は
 * 内容の最小幅ではなく基本サイズだった。
 *
 * LIMITATION(制約): 列方向のアイテムの {@code min-height: auto} は、高さが指定されていれば指定値、
 * 無ければ内容の高さで近似する（指定された高さより内容が低くても、そこまでしか縮まない）。
 * 行の折り返しは仮の主軸の寸法で決め、{@code baseline} 揃えは行方向の最初のベースラインだけ。
 * README の "Not supported" に対応。
 */
public class FlexLayoutEngine extends LayoutEngine {

    @Override
    public float measureContent(LayoutBox box, ConstraintSpace space, LayoutContext ctx) {
        float tentativeInnerW = space.availableInlineSize();
        float parentH = space.percentageBlockSize();
        Axes axes = Axes.of(box.style);
        String alignItems = alignItemsOf(box.style);

        float maxChildW = 0, sumChildW = 0;
        int count = 0;
        for (LayoutBox child : box.getFlowChildren()) {
            String childAlign = alignSelfOf(child, alignItems);
            FormattingContext childCtx = new FormattingContext(axes.direction, childAlign, box.sizingMode == SizingMode.SHRINK_TO_FIT);
            child.measure(new ConstraintSpace(tentativeInnerW, parentH, childCtx), ctx);

            float outerW = axes.row
                    ? hypotheticalMain(child, true, tentativeInnerW, ctx) + mainMargins(child, true)
                    : child.w + child.mL + child.mR;
            maxChildW = Math.max(maxChildW, outerW);
            sumChildW += outerW;
            count++;
        }
        if (!axes.row) return maxChildW;
        float sum = sumChildW + (count > 1 ? box.columnGap * (count - 1) : 0);
        // shrink-to-fit は利用できる幅で止まる（アイテムは伸縮長の解決で縮む）
        return tentativeInnerW > 0 ? Math.min(sum, tentativeInnerW) : sum;
    }

    @Override
    public float contentBlockSize(LayoutBox box, ConstraintSpace space, LayoutContext ctx) {
        Axes axes = Axes.of(box.style);
        FlexState state = new FlexState();
        box.engineState = state;
        state.items = orderedItems(box);

        float innerW = Math.max(0, box.w - box.horizontalDecoration());
        if (axes.row) {
            buildLines(box, state, axes, innerW, space, ctx);
            for (FlexLine line : state.lines) {
                resolveFlexibleLengths(box, line, axes, innerW, space, ctx);
                computeLineCross(line, axes);
            }
            float h = 0;
            for (FlexLine line : state.lines) h += line.cross;
            if (state.lines.size() > 1) h += box.rowGap * (state.lines.size() - 1);
            return h;
        }

        // 列方向: 高さが不定なら仮の寸法の和が内容の高さ（伸縮は高さが決まってから finishLayout で解く）
        float h = 0;
        for (FlexItem item : state.items) h += hypotheticalMain(item.box, false, Float.NaN, ctx) + mainMargins(item.box, false);
        if (state.items.size() > 1) h += box.rowGap * (state.items.size() - 1);
        return h;
    }

    /** 箱の高さが決まってから、列方向の伸縮・行の交差軸の寸法・stretch・位置を解く。 */
    @Override
    public void finishLayout(LayoutBox box, ConstraintSpace space, LayoutContext ctx) {
        if (box.engineState instanceof FlexState state) layoutLines(box, state, space, ctx);
        measureAbsoluteChildren(box, ctx);
    }

    private void layoutLines(LayoutBox box, FlexState state, ConstraintSpace space, LayoutContext ctx) {
        Axes axes = Axes.of(box.style);
        float innerW = Math.max(0, box.w - box.horizontalDecoration());
        float innerH = Math.max(0, box.h - box.verticalDecoration());
        float innerMain = axes.row ? innerW : innerH;
        float innerCross = axes.row ? innerH : innerW;
        state.innerW = innerW;
        state.innerH = innerH;

        if (!axes.row) {
            buildLines(box, state, axes, innerH, space, ctx);
            for (FlexLine line : state.lines) resolveFlexibleLengths(box, line, axes, innerH, space, ctx);
        }
        // 解き直しても結果が変わらないよう、行の交差軸の寸法は毎回アイテムから求め直す
        for (FlexLine line : state.lines) computeLineCross(line, axes);

        float crossGap = axes.row ? box.rowGap : box.columnGap;
        float mainGap = axes.row ? box.columnGap : box.rowGap;
        String alignContent = box.style.keyword(CssProperty.ALIGN_CONTENT, "normal");
        int lineCount = state.lines.size();

        // 1 行のコンテナでは行の交差軸の寸法はコンテナの寸法（CSS Flexbox §9.4 の 15）
        if (!axes.wrap && lineCount == 1) {
            state.lines.getFirst().cross = innerCross;
        } else if (lineCount > 0 && (alignContent.equals("normal") || alignContent.equals("stretch"))) {
            float used = crossGap * (lineCount - 1);
            for (FlexLine line : state.lines) used += line.cross;
            float extra = innerCross - used;
            if (extra > 0) for (FlexLine line : state.lines) line.cross += extra / lineCount;
        }

        String alignItems = alignItemsOf(box.style);
        for (FlexLine line : state.lines) stretchItems(box, line, axes, alignItems, space, ctx);

        // 行の交差軸の位置（align-content）。wrap-reverse は求めた位置を交差軸で裏返す
        float usedCross = crossGap * Math.max(0, lineCount - 1);
        for (FlexLine line : state.lines) usedCross += line.cross;
        MainAxisSpacing lineSpacing = lineCount > 1 || axes.wrap
                ? distributeMainAxis(contentDistribution(alignContent), 0, innerCross, usedCross - crossGap * Math.max(0, lineCount - 1), crossGap, lineCount)
                : new MainAxisSpacing(0, crossGap);
        float lineOffset = lineSpacing.start();

        for (FlexLine line : state.lines) {
            float lineStart = axes.wrapReverse ? innerCross - lineOffset - line.cross : lineOffset;
            placeLineItems(box, line, axes, innerMain, mainGap, lineStart, alignItems);
            lineOffset += line.cross + lineSpacing.gap();
        }
    }

    @Override
    public void arrangeChildren(LayoutBox box, float innerX, float innerY, LayoutContext ctx) {
        if (!(box.engineState instanceof FlexState state)) return;
        float innerW = Math.max(0, box.w - box.horizontalDecoration());
        float innerH = Math.max(0, box.h - box.verticalDecoration());
        if (Math.abs(state.innerW - innerW) > 0.01f || Math.abs(state.innerH - innerH) > 0.01f) {
            // 高さを決めた後に寸法だけが変わった（親が stretch した）ときは位置を解き直す
            layoutLines(box, state, ConstraintSpace.block(innerW, innerH), ctx);
        }
        for (FlexItem item : state.items) {
            item.box.layout(innerX + item.x, innerY + item.y, item.box.w, item.box.h, ctx);
        }
    }

    // ==========================================
    // アイテムと行
    // ==========================================

    /** 通常フローのアイテムを {@code order} の昇順に（同値は文書順）。 */
    private static List<FlexItem> orderedItems(LayoutBox box) {
        List<FlexItem> items = new ArrayList<>();
        for (LayoutBox child : box.getFlowChildren()) items.add(new FlexItem(child));
        items.sort(Comparator.comparingInt(item -> (int) item.box.style.number(CssProperty.ORDER, 0f)));
        return items;
    }

    /** 仮の主軸の寸法で行に分ける（CSS Flexbox §9.3）。 */
    private static void buildLines(LayoutBox box, FlexState state, Axes axes, float innerMain,
                                   ConstraintSpace space, LayoutContext ctx) {
        state.lines.clear();
        float gap = axes.row ? box.columnGap : box.rowGap;
        FlexLine line = new FlexLine();
        float used = 0;
        for (FlexItem item : state.items) {
            item.base = flexBaseSize(item.box, axes.row, innerMain, ctx);
            item.min = minMainSize(item.box, axes.row, ctx);
            item.max = maxMainSize(item.box, axes.row, innerMain, space, ctx);
            item.hypothetical = clamp(item.base, item.min, item.max);
            float outer = item.hypothetical + mainMargins(item.box, axes.row);
            if (axes.wrap && !line.items.isEmpty() && used + gap + outer > innerMain + 0.01f) {
                state.lines.add(line);
                line = new FlexLine();
                used = 0;
            }
            used += (line.items.isEmpty() ? 0 : gap) + outer;
            line.items.add(item);
        }
        if (!line.items.isEmpty()) state.lines.add(line);
    }

    /**
     * 伸縮長の解決（CSS Flexbox §9.7）。min / max に当たったアイテムを凍結し、残りに余りを配り直す。
     * 決まった主軸の寸法でアイテムを測り直し、交差軸の寸法を得る。
     */
    private static void resolveFlexibleLengths(LayoutBox box, FlexLine line, Axes axes, float innerMain,
                                               ConstraintSpace space, LayoutContext ctx) {
        List<FlexItem> items = line.items;
        float gap = (axes.row ? box.columnGap : box.rowGap) * Math.max(0, items.size() - 1);
        float sumHypothetical = gap;
        for (FlexItem item : items) sumHypothetical += item.hypothetical + mainMargins(item.box, axes.row);
        boolean growing = sumHypothetical < innerMain;
        // スクロールしない向きで溢れるときだけ縮める（以前の挙動。スクロールコンテナの中身は縮めない）
        boolean shrinkable = !growing && !scrollsAlong(box, axes.row);

        for (FlexItem item : items) {
            float factor = growing ? grow(item.box) : shrink(item.box);
            item.frozen = factor == 0 || (!growing && !shrinkable)
                    || (growing && item.base > item.hypothetical) || (!growing && item.base < item.hypothetical);
            item.target = item.frozen ? item.hypothetical : item.base;
        }
        float initialFree = innerMain - outerSum(items, axes.row) - gap;

        for (int guard = 0; guard < items.size() + 1; guard++) {
            if (items.stream().allMatch(i -> i.frozen)) break;
            float free = innerMain - outerSum(items, axes.row) - gap;
            float sumFactors = 0;
            for (FlexItem item : items) if (!item.frozen) sumFactors += growing ? grow(item.box) : shrink(item.box);
            // 係数の和が 1 未満なら余りの一部しか配らない
            if (sumFactors < 1 && Math.abs(initialFree * sumFactors) < Math.abs(free)) free = initialFree * sumFactors;

            float sumScaled = 0;
            for (FlexItem item : items) if (!item.frozen) sumScaled += shrink(item.box) * item.base;
            float violation = 0;
            for (FlexItem item : items) {
                if (item.frozen) continue;
                float unclamped;
                if (growing) {
                    unclamped = item.base + free * (grow(item.box) / sumFactors);
                } else {
                    float scaled = shrink(item.box) * item.base;
                    unclamped = sumScaled > 0 ? item.base + free * (scaled / sumScaled) : item.base;
                }
                item.target = clamp(unclamped, item.min, item.max);
                item.violation = item.target - unclamped;
                violation += item.violation;
            }
            for (FlexItem item : items) {
                if (item.frozen) continue;
                if (violation == 0 || (violation > 0 && item.violation > 0) || (violation < 0 && item.violation < 0)) {
                    item.frozen = true;
                }
            }
        }

        for (FlexItem item : items) setMainSize(item.box, axes, item.target, space, ctx);
    }

    /** 凍結したアイテムは決まった寸法、それ以外は flex base size で数えた外側の寸法の和。 */
    private static float outerSum(List<FlexItem> items, boolean row) {
        float sum = 0;
        for (FlexItem item : items) sum += (item.frozen ? item.target : item.base) + mainMargins(item.box, row);
        return sum;
    }

    /** 主軸の寸法を決めて測り直す。行方向なら幅を固定して高さを、列方向なら高さを固定する。 */
    private static void setMainSize(LayoutBox child, Axes axes, float size, ConstraintSpace space, LayoutContext ctx) {
        if (axes.row) {
            if (Math.abs(child.w - size) > 0.001f || child.sizingMode != SizingMode.DEFINITE) {
                remeasure(child, size, Float.NaN, axes, space, ctx);
            }
        } else if (Math.abs(child.h - size) > 0.001f) {
            remeasure(child, child.w, size, axes, space, ctx);
        }
    }

    private static void remeasure(LayoutBox child, float width, float height, Axes axes, ConstraintSpace space, LayoutContext ctx) {
        child.overrideWidth = width;
        child.overrideHeight = height;
        try {
            FormattingContext childCtx = new FormattingContext(axes.direction, "stretch", false);
            child.measure(new ConstraintSpace(width, space.percentageBlockSize(), childCtx), ctx);
        } finally {
            child.overrideWidth = Float.NaN;
            child.overrideHeight = Float.NaN;
        }
    }

    /** 行の交差軸の寸法。baseline で揃えるアイテムはベースラインの上と下を別々に数える。 */
    private static void computeLineCross(FlexLine line, Axes axes) {
        float cross = 0, above = 0, below = 0;
        for (FlexItem item : line.items) {
            LayoutBox b = item.box;
            float outer = axes.row ? b.h + b.mT + b.mB : b.w + b.mL + b.mR;
            if (axes.row && "baseline".equals(alignSelfOf(b, alignItemsOf(b.parent.style))) && !hasAutoCrossMargin(b, true)) {
                float baseline = b.mT + itemBaseline(b);
                above = Math.max(above, baseline);
                below = Math.max(below, outer - baseline);
            } else {
                cross = Math.max(cross, outer);
            }
        }
        line.cross = Math.max(cross, above + below);
        line.baseline = above;
    }

    /** align-self: stretch で交差軸の寸法が auto のアイテムを、行の交差軸の寸法まで伸ばす。 */
    private static void stretchItems(LayoutBox box, FlexLine line, Axes axes, String alignItems,
                                     ConstraintSpace space, LayoutContext ctx) {
        for (FlexItem item : line.items) {
            LayoutBox b = item.box;
            if (!"stretch".equals(alignSelfOf(b, alignItems)) || hasAutoCrossMargin(b, axes.row)) continue;
            if (axes.row) {
                if (hasSize(b.style, CssProperty.HEIGHT)) continue;
                float target = line.cross - b.mT - b.mB;
                if (b.h < target) {
                    b.h = Math.max(0, target);
                    b.resolveFlexibleHeights(space.percentageBlockSize(), new FormattingContext(axes.direction, "stretch", false), ctx);
                }
            } else {
                if (hasSize(b.style, CssProperty.WIDTH)) continue;
                float target = line.cross - b.mL - b.mR;
                if (Math.abs(b.w - target) > 0.001f && target >= 0) remeasure(b, target, b.h, axes, space, ctx);
            }
        }
    }

    /** 1 行のアイテムの位置（マージンボックスの左上、内容ボックス相対）を決める。 */
    private static void placeLineItems(LayoutBox box, FlexLine line, Axes axes, float innerMain, float mainGap,
                                       float lineStart, String alignItems) {
        List<FlexItem> items = line.items;
        float used = mainGap * Math.max(0, items.size() - 1);
        int autoMargins = 0;
        for (FlexItem item : items) {
            LayoutBox b = item.box;
            used += mainSize(b, axes.row) + mainMargins(b, axes.row);
            if (isAuto(b.style, axes.row ? CssProperty.MARGIN_LEFT : CssProperty.MARGIN_TOP)) autoMargins++;
            if (isAuto(b.style, axes.row ? CssProperty.MARGIN_RIGHT : CssProperty.MARGIN_BOTTOM)) autoMargins++;
        }
        float free = innerMain - used;

        // auto マージンは justify-content より先に正の余りを吸う（CSS Flexbox §8.1）
        float autoMargin = free > 0 && autoMargins > 0 ? free / autoMargins : 0;
        MainAxisSpacing spacing;
        if (autoMargins > 0) {
            spacing = new MainAxisSpacing(0, mainGap);
        } else {
            String justify = box.style.keyword(CssProperty.JUSTIFY_CONTENT, "flex-start");
            spacing = distributeMainAxis(contentDistribution(justify), 0, innerMain, used - mainGap * Math.max(0, items.size() - 1),
                    mainGap, items.size());
        }

        float pos = spacing.start();
        for (FlexItem item : items) {
            LayoutBox b = item.box;
            if (axes.row) {
                if (isAuto(b.style, CssProperty.MARGIN_LEFT)) b.mL = autoMargin;
                if (isAuto(b.style, CssProperty.MARGIN_RIGHT)) b.mR = autoMargin;
            } else {
                if (isAuto(b.style, CssProperty.MARGIN_TOP)) b.mT = autoMargin;
                if (isAuto(b.style, CssProperty.MARGIN_BOTTOM)) b.mB = autoMargin;
            }
            float outerMain = mainSize(b, axes.row) + (axes.row ? b.mL + b.mR : b.mT + b.mB);
            float main = axes.reverse ? innerMain - pos - outerMain : pos;
            float cross = lineStart + crossOffset(b, line, axes, alignItems);
            item.x = axes.row ? main : cross;
            item.y = axes.row ? cross : main;
            pos += outerMain + spacing.gap();
        }
    }

    /** 行の中での交差軸の位置。auto マージンは align-self より先に余りを吸う。 */
    private static float crossOffset(LayoutBox b, FlexLine line, Axes axes, String alignItems) {
        float outer = axes.row ? b.h + b.mT + b.mB : b.w + b.mL + b.mR;
        String startSide = axes.row ? CssProperty.MARGIN_TOP : CssProperty.MARGIN_LEFT;
        String endSide = axes.row ? CssProperty.MARGIN_BOTTOM : CssProperty.MARGIN_RIGHT;
        boolean autoStart = isAuto(b.style, startSide);
        boolean autoEnd = isAuto(b.style, endSide);
        if (autoStart || autoEnd) {
            float free = Math.max(0, line.cross - outer);
            float startMargin = autoStart && autoEnd ? free / 2 : (autoStart ? free : 0);
            float endMargin = autoStart && autoEnd ? free / 2 : (autoEnd ? free : 0);
            if (axes.row) {
                if (autoStart) b.mT = startMargin;
                if (autoEnd) b.mB = endMargin;
            } else {
                if (autoStart) b.mL = startMargin;
                if (autoEnd) b.mR = endMargin;
            }
            return 0;
        }
        float offset = switch (alignSelfOf(b, alignItems)) {
            case "center" -> (line.cross - outer) / 2;
            case "flex-end", "end", "self-end" -> line.cross - outer;
            case "baseline" -> axes.row ? line.baseline - (b.mT + itemBaseline(b)) : 0;
            default -> 0;
        };
        // wrap-reverse では交差軸の始点が行の下（右）になる
        return axes.wrapReverse && !"baseline".equals(alignSelfOf(b, alignItems)) ? line.cross - outer - offset : offset;
    }

    // ==========================================
    // 寸法
    // ==========================================

    /**
     * flex base size（border box）。{@code flex-basis} が長さならそれ、{@code auto} なら主軸の寸法の
     * 指定、それも無ければ内容の寸法（測定済みの幅・高さ）。主軸が不定のときの % は内容の寸法に落とす。
     */
    private static float flexBaseSize(LayoutBox child, boolean row, float innerMain, LayoutContext ctx) {
        String basis = child.style.get(CssProperty.FLEX_BASIS);
        float decoration = row ? child.horizontalDecoration() : child.verticalDecoration();
        if (basis != null) {
            String b = basis.trim().toLowerCase();
            boolean percentOfIndefinite = b.contains("%") && Float.isNaN(innerMain);
            if (!b.equals("auto") && !b.equals("content") && !percentOfIndefinite) {
                float value = child.style.length(CssProperty.FLEX_BASIS, Float.isNaN(innerMain) ? 0 : innerMain,
                        ctx.viewport().rootW(), ctx.viewport().rootH());
                boolean isBorderBox = "border-box".equals(child.style.get(CssProperty.BOX_SIZING));
                return Math.max(decoration, isBorderBox ? value : value + decoration);
            }
        }
        return row ? child.w : child.h;
    }

    /** min / max で切り詰めた仮の主軸の寸法。 */
    private static float hypotheticalMain(LayoutBox child, boolean row, float innerMain, LayoutContext ctx) {
        float base = flexBaseSize(child, row, row ? innerMain : Float.NaN, ctx);
        return clamp(base, minMainSize(child, row, ctx), maxMainSize(child, row, innerMain, null, ctx));
    }

    /**
     * 主軸の最小寸法。{@code min-width} / {@code min-height} が auto なら自動の最小寸法
     * （CSS Flexbox §4.5: 指定された寸法と内容の最小寸法の小さい方。スクロールコンテナは 0）。
     */
    private static float minMainSize(LayoutBox child, boolean row, LayoutContext ctx) {
        String prop = row ? CssProperty.MIN_WIDTH : CssProperty.MIN_HEIGHT;
        String sizeProp = row ? CssProperty.WIDTH : CssProperty.HEIGHT;
        float decoration = row ? child.horizontalDecoration() : child.verticalDecoration();
        String min = child.style.get(prop);
        boolean isBorderBox = "border-box".equals(child.style.get(CssProperty.BOX_SIZING));
        if (min != null && !"auto".equalsIgnoreCase(min.trim()) && !LayoutMetrics.isIntrinsicSizeKeyword(min)) {
            float v = child.style.length(prop, row ? child.parentInnerWidth() : 0, ctx.viewport().rootW(), ctx.viewport().rootH());
            return isBorderBox ? Math.max(v, decoration) : v + decoration;
        }
        if (child.isScrollContainer()) return decoration;

        float content;
        if (row) {
            boolean hasContent = child.node.nodeType == NodeType.TEXT || child.getFlowChildren().size() > 0
                    || isReplaced(child);
            content = hasContent ? child.getMinContentWidth(ctx) : decoration;
        } else {
            content = hasSize(child.style, sizeProp) && child.getFlowChildren().isEmpty() ? decoration : child.h;
        }
        if (hasSize(child.style, sizeProp)) content = Math.min(content, row ? child.w : child.h);
        float max = maxMainSize(child, row, Float.NaN, null, ctx);
        return Math.max(decoration, Math.min(content, max));
    }

    private static float maxMainSize(LayoutBox child, boolean row, float innerMain, ConstraintSpace space, LayoutContext ctx) {
        String prop = row ? CssProperty.MAX_WIDTH : CssProperty.MAX_HEIGHT;
        String max = child.style.get(prop);
        if (max == null || "none".equalsIgnoreCase(max.trim()) || LayoutMetrics.isIntrinsicSizeKeyword(max)) {
            return Float.POSITIVE_INFINITY;
        }
        if (max.contains("%") && Float.isNaN(innerMain)) return Float.POSITIVE_INFINITY;
        float decoration = row ? child.horizontalDecoration() : child.verticalDecoration();
        float v = child.style.length(prop, Float.isNaN(innerMain) ? 0 : innerMain, ctx.viewport().rootW(), ctx.viewport().rootH());
        boolean isBorderBox = "border-box".equals(child.style.get(CssProperty.BOX_SIZING));
        return isBorderBox ? Math.max(v, decoration) : v + decoration;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(v, max));
    }

    private static boolean isReplaced(LayoutBox b) {
        String tag = b.node.tag;
        return tag.equals("img") || tag.equals("input") || tag.equals("select") || tag.equals("button")
                || tag.equals("progress") || tag.equals("mc-fluid") || tag.equals("mc-entity") || tag.equals("mc-scene");
    }

    private static float mainSize(LayoutBox b, boolean row) {
        return row ? b.w : b.h;
    }

    /** 主軸方向のマージンの和。auto は 0 として数える（余りは位置を決めるときに配る）。 */
    private static float mainMargins(LayoutBox b, boolean row) {
        if (row) {
            return (isAuto(b.style, CssProperty.MARGIN_LEFT) ? 0 : b.mL) + (isAuto(b.style, CssProperty.MARGIN_RIGHT) ? 0 : b.mR);
        }
        return (isAuto(b.style, CssProperty.MARGIN_TOP) ? 0 : b.mT) + (isAuto(b.style, CssProperty.MARGIN_BOTTOM) ? 0 : b.mB);
    }

    private static boolean hasAutoCrossMargin(LayoutBox b, boolean row) {
        return row
                ? isAuto(b.style, CssProperty.MARGIN_TOP) || isAuto(b.style, CssProperty.MARGIN_BOTTOM)
                : isAuto(b.style, CssProperty.MARGIN_LEFT) || isAuto(b.style, CssProperty.MARGIN_RIGHT);
    }

    private static boolean isAuto(ComputedStyle style, String prop) {
        return "auto".equalsIgnoreCase(style.getOrDefault(prop, "0").trim());
    }

    private static boolean hasSize(ComputedStyle style, String prop) {
        String v = style.get(prop);
        return v != null && !"auto".equalsIgnoreCase(v.trim());
    }

    private static float grow(LayoutBox b) {
        return Math.max(0, b.style.number(CssProperty.FLEX_GROW, 0f));
    }

    private static float shrink(LayoutBox b) {
        return Math.max(0, b.style.number(CssProperty.FLEX_SHRINK, 1f));
    }

    private static boolean scrollsAlong(LayoutBox box, boolean row) {
        String overflow = box.style.getOrDefault(CssProperty.OVERFLOW, "visible");
        String v = box.style.getOrDefault(row ? CssProperty.OVERFLOW_X : CssProperty.OVERFLOW_Y, overflow).trim();
        return v.equals("scroll") || v.equals("auto");
    }

    /** アイテムのベースライン（border box の上端から）。行を持たなければ border box の下端から合成する。 */
    private static float itemBaseline(LayoutBox b) {
        Float baseline = BlockLayoutEngine.firstBaseline(b);
        return baseline != null ? baseline : b.h;
    }

    /** {@code align-items} の {@code normal} は flex では stretch。 */
    private static String alignItemsOf(ComputedStyle style) {
        String v = style.keyword(CssProperty.ALIGN_ITEMS, "stretch");
        return v.equals("normal") ? "stretch" : stripSafety(v);
    }

    static String alignSelfOf(LayoutBox child, String alignItems) {
        String v = child.style.keyword(CssProperty.ALIGN_SELF, "auto");
        return v.equals("auto") ? alignItems : (v.equals("normal") ? "stretch" : stripSafety(v));
    }

    /** {@code safe center} / {@code first baseline} などの前置きを落とす。 */
    private static String stripSafety(String v) {
        int space = v.lastIndexOf(' ');
        return space >= 0 ? v.substring(space + 1) : v;
    }

    /** {@code justify-content} / {@code align-content} を {@link #distributeMainAxis} の語に寄せる。 */
    private static String contentDistribution(String v) {
        return switch (stripSafety(v)) {
            case "normal", "stretch", "start", "left" -> "flex-start";
            case "end", "right" -> "flex-end";
            default -> stripSafety(v);
        };
    }

    /** 主軸と交差軸の向き。 */
    private record Axes(String direction, boolean row, boolean reverse, boolean wrap, boolean wrapReverse) {
        static Axes of(ComputedStyle style) {
            String dir = style.keyword(CssProperty.FLEX_DIRECTION, "row");
            String wrap = style.keyword(CssProperty.FLEX_WRAP, "nowrap");
            return new Axes(dir, dir.startsWith("row"), dir.endsWith("-reverse"),
                    !wrap.equals("nowrap"), wrap.equals("wrap-reverse"));
        }
    }

    private static final class FlexItem {
        final LayoutBox box;
        float base, hypothetical, min, max, target, violation;
        boolean frozen;
        /** 位置（マージンボックスの左上、内容ボックス相対）。 */
        float x, y;

        FlexItem(LayoutBox box) {
            this.box = box;
        }
    }

    private static final class FlexLine {
        final List<FlexItem> items = new ArrayList<>();
        float cross;
        /** 行の交差軸の始点からベースラインまで。 */
        float baseline;
    }

    /** flex コンテナの行とアイテムの配置。 */
    static final class FlexState implements LayoutEngineState {
        List<FlexItem> items = new ArrayList<>();
        final List<FlexLine> lines = new ArrayList<>();
        float innerW = -1, innerH = -1;
    }
}
