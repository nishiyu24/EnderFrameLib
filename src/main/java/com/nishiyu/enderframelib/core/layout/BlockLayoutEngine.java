package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.dom.HtmlNode.NodeType;
import com.nishiyu.enderframelib.core.layout.LayoutBox.Display.Inner;
import com.nishiyu.enderframelib.core.layout.LayoutBox.InlineFragment;
import com.nishiyu.enderframelib.core.layout.LayoutBox.SizingMode;
import com.nishiyu.enderframelib.core.layout.LayoutContext.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.LayoutContext.FormattingContext;
import com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutEngineState;
import com.nishiyu.enderframelib.core.text.FontFace;
import com.nishiyu.enderframelib.core.text.FontFace.FontStyle;
import com.nishiyu.enderframelib.core.text.TextShaper;

import java.util.ArrayList;
import java.util.List;

/**
 * ブロックとインラインの整形（float と BFC を含む）。
 *
 * 位置は {@link #contentBlockSize} が内容ボックスの原点からの相対座標で決め（{@link FlowState}）、
 * {@link #arrangeChildren} はそれを平行移動するだけにしている。float の配置と、float を避ける行・BFC は
 * 高さを決める段で分かっていないといけないため。
 *
 * 何が壊れていたか: 以前は測定と配置がそれぞれ子を積み直していて、配置の段では「直前の子の
 * 下端」から次の子を置いていた。{@code position: relative} のずれ（配置の後で足される）が
 * 後続の兄弟まで動かしていたのはこのため。
 *
 * LIMITATION(制約): float の横を流れる行は、行の上端で float を避ける幅を決める（行の高さが
 * 後から高くなっても幅は測り直さない）。インラインボックスの中の float は行の途中では前の行へ
 * 戻らない。README の "Not supported" に対応。
 */
public class BlockLayoutEngine extends LayoutEngine {

    @Override
    public float measureContent(LayoutBox box, ConstraintSpace space, LayoutContext ctx) {
        float tentativeInnerW = space.availableInlineSize();
        float maxChildW = 0;
        float currentInlineW = 0;

        float childParentH = childPercentageBase(box, space.percentageBlockSize(), ctx);
        FormattingContext childCtx = new FormattingContext("block", "stretch", box.sizingMode == SizingMode.SHRINK_TO_FIT);

        for (LayoutBox child : box.children) {
            if (child.isAbsolute || isDisplayNone(child.style)) continue;
            if (child.isOutsideMarker) {
                // 外側のマーカーは通常フローの外にあり、リスト項目の幅に寄与しない
                child.measure(new ConstraintSpace(LayoutMetrics.UNBOUNDED_WIDTH, childParentH, MARKER_CONTEXT), ctx);
                continue;
            }
            child.measure(new ConstraintSpace(tentativeInnerW, childParentH, childCtx), ctx);
            float childOuterW = child.w + child.mL + child.mR;
            // float は行の内容と同じく横に並ぶので、shrink-to-fit の幅には横に足して数える
            if (isInlineBox(child) || isFloating(child)) {
                currentInlineW += childOuterW;
                maxChildW = Math.max(maxChildW, Math.min(tentativeInnerW > 0 ? tentativeInnerW : currentInlineW, currentInlineW));
            } else {
                currentInlineW = 0;
                maxChildW = Math.max(maxChildW, childOuterW);
            }
        }
        return maxChildW;
    }

    /** 子の高さの % を解く基準。親の高さが指定されているときだけ確定している。 */
    private static float childPercentageBase(LayoutBox box, float parentH, LayoutContext ctx) {
        if (box.parent == null) return Math.max(0, parentH - box.verticalDecoration());
        if (!hasSpecifiedHeight(box.style)) return 0;
        float resolvedH = box.style.length(CssProperty.HEIGHT, parentH, ctx.viewport().rootW(), ctx.viewport().rootH());
        boolean isBorderBox = "border-box".equals(box.style.get(CssProperty.BOX_SIZING));
        return Math.max(0, isBorderBox ? resolvedH - box.verticalDecoration() : resolvedH);
    }

    private static boolean hasSpecifiedHeight(ComputedStyle style) {
        return style.containsKey(CssProperty.HEIGHT) && !"auto".equals(style.getOrDefault(CssProperty.HEIGHT, "auto").trim());
    }

    @Override
    public float contentBlockSize(LayoutBox box, ConstraintSpace space, LayoutContext ctx) {
        FlowState state = new FlowBuilder(box, space, ctx).build();
        box.engineState = state;
        return state.height;
    }

    @Override
    public void finishLayout(LayoutBox box, ConstraintSpace space, LayoutContext ctx) {
        measureAbsoluteChildren(box, ctx);
    }

    @Override
    public void arrangeChildren(LayoutBox box, float innerX, float innerY, LayoutContext ctx) {
        float innerW = Math.max(0, box.w - box.horizontalDecoration());
        FlowState state = box.engineState instanceof FlowState s ? s : null;
        if (state == null || Math.abs(state.innerW - innerW) > 0.01f) {
            // 高さを決めた後に幅だけが変わった（親が stretch した）ときは組み直す
            state = new FlowBuilder(box, ConstraintSpace.block(innerW, 0), ctx).build();
            box.engineState = state;
        }
        String textAlign = box.style.getOrDefault(CssProperty.TEXT_ALIGN, "left");

        for (Object item : state.items) {
            switch (item) {
                case Placement p -> p.box().layout(innerX + p.x(), innerY + p.y(), innerW, p.box().h, ctx);
                case StaticPosition sp -> {
                    sp.box().staticX = innerX + sp.x();
                    sp.box().staticY = innerY + sp.y();
                    // インラインボックスの中の絶対配置は、その箱の layout を通らないのでここで置く
                    if (sp.box().parent != box) sp.box().layout(innerX, innerY, innerW, 0, ctx);
                }
                case LineRun run -> {
                    for (LayoutBox inlineBox : run.boxes()) clearFragmentsRecursively(inlineBox);
                    for (InlineLine line : run.lines()) {
                        placeLine(line, innerX + line.left(), innerY + line.top(), line.width(), textAlign, ctx);
                    }
                }
                default -> throw new IllegalStateException(String.valueOf(item));
            }
        }
        placeOutsideMarkers(box, innerY, ctx);
    }

    /** 外側のマーカーを測るときの文脈。折り返さず、内容の幅に縮む。 */
    private static final FormattingContext MARKER_CONTEXT = new FormattingContext("block", "stretch", true);

    /**
     * 外側の {@code ::marker} を、リスト項目の境界ボックスの左外に、最初の行のベースラインに揃えて置く。
     * 行が無い（中身がブロックだけで文字を持たない）ときは内容の上端に揃える。
     */
    private void placeOutsideMarkers(LayoutBox box, float innerY, LayoutContext ctx) {
        for (LayoutBox marker : box.children) {
            if (!marker.isOutsideMarker || isDisplayNone(marker.style)) continue;
            float markerX = box.x - marker.w - marker.mR;
            marker.layout(markerX - marker.mL, 0, marker.w, marker.h, ctx);
            Float markerBaseline = placedFirstBaseline(marker);
            Float lineBaseline = placedFirstBaseline(box);
            float y = (markerBaseline != null && lineBaseline != null)
                    ? lineBaseline - (markerBaseline - marker.y)
                    : innerY;
            marker.layout(markerX - marker.mL, y - marker.mT, marker.w, marker.h, ctx);
        }
    }

    /** 配置済みの箱の中で最初に置かれた行のベースライン（絶対座標）。マーカーと位置指定の外は見ない。 */
    private static Float placedFirstBaseline(LayoutBox box) {
        if (!box.inlineFragments.isEmpty()) {
            InlineFragment first = box.inlineFragments.getFirst();
            return first.y + first.baseline;
        }
        for (LayoutBox child : box.children) {
            if (child.isOutsideMarker || child.isAbsolute || child.display.isNone()) continue;
            Float found = placedFirstBaseline(child);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * 最初の子の上マージンが親を貫通するか。
     * 親が上パディング・上ボーダーを持つか、BFC を作るなら貫通しない（CSS 2.2 §8.3.1）。
     */
    private static boolean collapsesThroughTop(LayoutBox box) {
        return !box.establishesBfc && box.pT == 0 && box.bT == 0;
    }

    /** 末子の下マージンが親を貫通するか。高さ・min-height の指定・下パディング・下ボーダー・BFC が断つ。 */
    private static boolean collapsesThroughBottom(LayoutBox box) {
        return !box.establishesBfc && box.pB == 0 && box.bB == 0 && !hasSpecifiedHeight(box.style)
                && box.style.length(CssProperty.MIN_HEIGHT, 0, 0, 0) <= 0;
    }

    /**
     * 自分の上下のマージンが自分を貫通して 1 つに畳まれる箱（高さ 0 で行もボーダーも無い）。
     *
     * 何が壊れていたか: 以前は空の箱も高さのある箱と同じに積み、前後のマージンを別々に足していた。
     */
    private static boolean collapsesThrough(LayoutBox child) {
        return child.h == 0 && child.verticalDecoration() == 0 && !child.establishesBfc
                && child.display.isBlockContainer();
    }

    public static float collapseMargins(float m1, float m2) {
        if (m1 >= 0 && m2 >= 0) return Math.max(m1, m2);
        if (m1 <= 0 && m2 <= 0) return Math.min(m1, m2);
        return m1 + m2;
    }

    /** float かどうか。float はブロックフローの子のときだけ効く（flex / grid アイテムでは無視される）。 */
    public static boolean isFloating(LayoutBox box) {
        if (box.isAbsolute || box.parent == null || box.node.nodeType == NodeType.TEXT) return false;
        if ("none".equals(box.style.keyword(CssProperty.FLOAT, "none"))) return false;
        return LayoutEngine.getEngine(box.parent.display) instanceof BlockLayoutEngine;
    }

    /** 子孫（同じ BFC の中）に float があるか。あれば親の float 領域の中で高さを解き直す。 */
    private static boolean containsFloats(LayoutBox box) {
        for (LayoutBox c : box.children) {
            if (c.isAbsolute || c.display.isNone()) continue;
            if (isFloating(c)) return true;
            if (!c.establishesBfc && LayoutEngine.getEngine(c.display) instanceof BlockLayoutEngine && containsFloats(c)) {
                return true;
            }
        }
        return false;
    }

    // ==========================================
    // ブロックフロー
    // ==========================================

    /**
     * 1 つのブロックコンテナの子を積む。マージンの相殺、float の配置と回避、clear、行の分割を
     * 文書順に 1 回で行う。
     */
    private final class FlowBuilder {
        final LayoutBox box;
        final LayoutContext ctx;
        final FlowState state = new FlowState();
        final FloatSpace floats;
        /** この箱の内容ボックスの原点の、float 領域の中での位置。 */
        final float ox, oy;
        final float innerW;
        final float childPercentBase;
        final FormattingContext childCtx;
        final boolean collapseTop;

        /** 次の箱の前で 1 つに畳まれるのを待っているマージン。 */
        final MarginStrut pending = new MarginStrut();
        /** 親（この箱）の上マージンと畳まれるマージン。 */
        final MarginStrut topStrut = new MarginStrut();
        /** マージンの相殺を断つもの（行・高さのある箱）がもう置かれたか。 */
        boolean hasContent = false;
        float cursor = 0;
        boolean firstRun = true;

        FlowBuilder(LayoutBox box, ConstraintSpace space, LayoutContext ctx) {
            this.box = box;
            this.ctx = ctx;
            this.innerW = Math.max(0, box.w - box.horizontalDecoration());
            if (!box.establishesBfc && box.floatSpace != null) {
                floats = box.floatSpace;
                ox = box.floatSpaceX;
                oy = box.floatSpaceY;
            } else {
                floats = new FloatSpace();
                ox = 0;
                oy = 0;
            }
            box.floatSpace = null;
            this.childPercentBase = childPercentageBase(box, space.percentageBlockSize(), ctx);
            this.childCtx = new FormattingContext("block", "stretch", box.sizingMode == SizingMode.SHRINK_TO_FIT);
            this.collapseTop = collapsesThroughTop(box);
        }

        FlowState build() {
            state.innerW = innerW;
            List<LayoutBox> run = new ArrayList<>();
            for (LayoutBox child : box.children) {
                if (isDisplayNone(child.style) || child.isOutsideMarker) continue;
                if (child.isAbsolute) {
                    // 行の中の絶対配置は、行を組むときにその位置を静的位置として記録する
                    if (!run.isEmpty()) run.add(child);
                    else state.items.add(new StaticPosition(child, 0, cursor + pending.resolve()));
                    continue;
                }
                if (isInlineBox(child) || (isFloating(child) && !run.isEmpty())) {
                    run.add(child);
                    continue;
                }
                flushRun(run);
                if (isFloating(child)) {
                    placeFloat(child, cursor + (hasContent || !collapseTop ? pending.resolve() : 0));
                } else {
                    placeBlock(child);
                }
            }
            flushRun(run);

            if (collapsesThroughBottom(box) && (hasContent || !collapseTop)) {
                box.mB = pending.with(box.ownMarginBottom);
            } else {
                cursor += pending.resolve();
            }
            if (collapseTop) box.mT = topStrut.with(box.ownMarginTop);

            float height = cursor;
            // BFC は自分の中の float を高さに含める（CSS 2.2 §10.6.7）。通常のブロックは含めない
            if (box.establishesBfc) height = Math.max(height, floats.bottom() - oy);
            state.height = height;
            return state;
        }

        private void placeBlock(LayoutBox child) {
            boolean intoParentTop = !hasContent && collapseTop;
            if (!child.establishesBfc && LayoutEngine.getEngine(child.display) instanceof BlockLayoutEngine
                    && (!floats.isEmpty() || containsFloats(child))) {
                // BFC を作らない子は同じ float 領域の中で組み直す（float を避け、自分の float を置く）。
                // 高さ 0 で自分を貫通する箱の中の float も親の領域に入る
                float y = clearedY(child, intoParentTop ? cursor : cursor + pending.copy().add(child.mT).resolve());
                child.floatSpace = floats;
                child.floatSpaceX = ox + child.mL + child.bL + child.pL;
                child.floatSpaceY = oy + y + child.bT + child.pT;
                child.h = 0;
                BoxMeasurer.resolveFlexibleHeights(child, new ConstraintSpace(innerW, childPercentBase, childCtx), ctx);
            }
            if (collapsesThrough(child)) {
                // 上端の位置は「下にボーダーがあったら置かれた位置」（CSS 2.2 §8.3.1）
                float y;
                if (intoParentTop) {
                    topStrut.add(child.mT).add(child.mB);
                    y = cursor;
                } else {
                    y = cursor + pending.copy().add(child.mT).resolve();
                    pending.add(child.mT).add(child.mB);
                }
                state.items.add(new Placement(child, 0, y - child.mT));
                return;
            }

            float y;
            if (intoParentTop) {
                topStrut.add(child.mT);
                y = cursor;
            } else {
                y = cursor + pending.add(child.mT).resolve();
            }
            pending.clear();

            y = clearedY(child, y);

            float x = 0;
            if (child.establishesBfc && !floats.isEmpty()) {
                // BFC の border box は float と重ならない。横の残り幅に入らなければ float の下へ下げる
                boolean autoWidth = "auto".equals(child.style.getOrDefault(CssProperty.WIDTH, "auto").trim());
                for (int guard = 0; guard < 256; guard++) {
                    float left = floats.leftEdge(oy + y, child.h, ox) - ox;
                    float right = floats.rightEdge(oy + y, child.h, ox + innerW) - ox;
                    float available = Math.max(0, right - left);
                    if (autoWidth && Math.abs(child.w + child.mL + child.mR - available) > 0.01f) {
                        child.measure(new ConstraintSpace(available, childPercentBase, childCtx), ctx);
                    }
                    if (!floats.intrudes(oy + y, child.h) || autoWidth
                            || child.w + child.mL + child.mR <= available + 0.01f) {
                        x = left;
                        break;
                    }
                    float next = floats.nextBottom(oy + y);
                    if (Float.isInfinite(next)) break;
                    y = next - oy;
                }
            }

            state.items.add(new Placement(child, x, y - child.mT));
            cursor = y + child.h;
            pending.add(child.mB);
            hasContent = true;
        }

        /** {@code clear} を持つ箱を、その側の float の下端まで下げた位置。 */
        private float clearedY(LayoutBox child, float y) {
            String clear = child.style.keyword(CssProperty.CLEAR, "none");
            if ("none".equals(clear) || floats.isEmpty()) return y;
            return Math.max(y, floats.clearance(clear) - oy);
        }

        /** float を置き、領域に登録する。{@code minY} は置ける最も上の位置（内容ボックス相対）。 */
        void placeFloat(LayoutBox f, float minY) {
            boolean left = !"right".equals(f.style.keyword(CssProperty.FLOAT, "left"));
            float w = f.w + f.mL + f.mR;
            float h = f.h + f.mT + f.mB;
            // float の上端は先に置いた float より上にはならない（CSS 2.2 §9.5.1 の規則 5）
            float y = Math.max(oy + minY, floats.lastTop());
            String clear = f.style.keyword(CssProperty.CLEAR, "none");
            if (!"none".equals(clear)) y = Math.max(y, floats.clearance(clear));
            float x;
            for (int guard = 0; ; guard++) {
                float l = floats.leftEdge(y, h, ox);
                float r = floats.rightEdge(y, h, ox + innerW);
                float next = floats.nextBottom(y);
                if (r - l >= w - 0.01f || !floats.intrudes(y, h) || Float.isInfinite(next) || guard > 256) {
                    x = left ? l : r - w;
                    break;
                }
                y = next;
            }
            floats.add(left, x, y, w, h);
            state.items.add(new Placement(f, x - ox, y - oy));
        }

        private void flushRun(List<LayoutBox> run) {
            if (run.isEmpty()) return;
            float y;
            if (!hasContent && collapseTop) {
                topStrut.add(pending);
                y = cursor;
            } else {
                y = cursor + pending.resolve();
            }
            pending.clear();

            // text-indent は要素の最初の行だけ。匿名ブロックは持ち主の最初の子のときだけ字下げする
            float indent = 0;
            if (firstRun && (!box.isAnonymous || isFirstFlowChild(box))) {
                indent = box.style.length(CssProperty.TEXT_INDENT, innerW, ctx.viewport().rootW(), ctx.viewport().rootH());
            }
            firstRun = false;

            LineBuilder lines = new LineBuilder(this, y, indent);
            List<InlineLine> result = lines.build(new ArrayList<>(run));
            state.items.add(new LineRun(new ArrayList<>(run), result));
            if (!result.isEmpty()) {
                InlineLine last = result.getLast();
                cursor = last.top() + last.height();
                hasContent = true;
            }
            run.clear();
        }
    }

    private static boolean isFirstFlowChild(LayoutBox box) {
        if (box.parent == null) return true;
        for (LayoutBox c : box.parent.children) {
            if (c.isAbsolute || c.isOutsideMarker || c.display.isNone()) continue;
            return c == box;
        }
        return false;
    }

    /** 相殺を待つマージンの集まり。正の最大と負の最小の和が畳まれた結果（CSS 2.2 §8.3.1）。 */
    private static final class MarginStrut {
        private float positive = 0, negative = 0;

        MarginStrut add(float margin) {
            if (margin > 0) positive = Math.max(positive, margin);
            else negative = Math.min(negative, margin);
            return this;
        }

        MarginStrut add(MarginStrut other) {
            positive = Math.max(positive, other.positive);
            negative = Math.min(negative, other.negative);
            return this;
        }

        MarginStrut copy() {
            return new MarginStrut().add(this);
        }

        float with(float margin) {
            return copy().add(margin).resolve();
        }

        float resolve() {
            return positive + negative;
        }

        void clear() {
            positive = 0;
            negative = 0;
        }
    }

    /**
     * 1 つの BFC の中の float の配置（BFC の内容ボックスの原点からの座標、マージンボックス）。
     * BFC を作らない子孫のブロックは同じ領域を共有する。
     */
    static final class FloatSpace {
        /** {x, y, right, bottom} */
        private final List<float[]> lefts = new ArrayList<>();
        private final List<float[]> rights = new ArrayList<>();
        private float lastTop = Float.NEGATIVE_INFINITY;

        boolean isEmpty() {
            return lefts.isEmpty() && rights.isEmpty();
        }

        void add(boolean left, float x, float y, float w, float h) {
            (left ? lefts : rights).add(new float[]{x, y, x + w, y + h});
            lastTop = Math.max(lastTop, y);
        }

        float lastTop() {
            return lastTop;
        }

        /** 全 float の下端。無ければ負の無限大。 */
        float bottom() {
            float b = Float.NEGATIVE_INFINITY;
            for (float[] f : lefts) b = Math.max(b, f[3]);
            for (float[] f : rights) b = Math.max(b, f[3]);
            return b;
        }

        /** {@code clear} の値に対応する側の float の下端。 */
        float clearance(String clear) {
            float b = Float.NEGATIVE_INFINITY;
            if (!"right".equals(clear)) for (float[] f : lefts) b = Math.max(b, f[3]);
            if (!"left".equals(clear)) for (float[] f : rights) b = Math.max(b, f[3]);
            return b;
        }

        private static boolean overlaps(float[] f, float y, float h) {
            return f[1] < y + Math.max(h, 0.01f) && f[3] > y;
        }

        boolean intrudes(float y, float h) {
            for (float[] f : lefts) if (overlaps(f, y, h)) return true;
            for (float[] f : rights) if (overlaps(f, y, h)) return true;
            return false;
        }

        /** 帯 [y, y + h) で左の float を避けた左端。 */
        float leftEdge(float y, float h, float minX) {
            float x = minX;
            for (float[] f : lefts) if (overlaps(f, y, h)) x = Math.max(x, f[2]);
            return x;
        }

        /** 帯 [y, y + h) で右の float を避けた右端。 */
        float rightEdge(float y, float h, float maxX) {
            float x = maxX;
            for (float[] f : rights) if (overlaps(f, y, h)) x = Math.min(x, f[0]);
            return x;
        }

        /** {@code y} より下で、いずれかの float が終わる最も近い位置。無ければ正の無限大。 */
        float nextBottom(float y) {
            float next = Float.POSITIVE_INFINITY;
            for (float[] f : lefts) if (f[3] > y) next = Math.min(next, f[3]);
            for (float[] f : rights) if (f[3] > y) next = Math.min(next, f[3]);
            return next;
        }
    }

    /** ブロックフローの配置結果。座標は内容ボックスの原点からの相対値。 */
    static final class FlowState implements LayoutEngineState {
        /** {@link Placement} / {@link StaticPosition} / {@link LineRun} を文書順に。 */
        final List<Object> items = new ArrayList<>();
        float innerW;
        float height;
    }

    /** 子の箱の位置（マージンボックスの左上。{@link LayoutBox#layout} がマージンを足す）。 */
    private record Placement(LayoutBox box, float x, float y) {}

    /** 絶対配置の静的位置。 */
    private record StaticPosition(LayoutBox box, float x, float y) {}

    /** 匿名ブロックに包まれていないインライン内容の並びと、その行。 */
    private record LineRun(List<LayoutBox> boxes, List<InlineLine> lines) {}

    // ==========================================
    // ベースライン
    // ==========================================

    /**
     * 最初の行のベースライン（border box の上端から）。行が無ければ null。
     * flex / grid の {@code baseline} 揃えが読む。
     */
    public static Float firstBaseline(LayoutBox box) {
        if (!(box.engineState instanceof FlowState state)) return null;
        for (Object item : state.items) {
            Float b = baselineOf(box, item);
            if (b != null) return b;
        }
        return null;
    }

    /** 最後の行のベースライン（border box の上端から）。インラインブロックのベースラインになる。 */
    static Float lastBaseline(LayoutBox box) {
        if (!(box.engineState instanceof FlowState state)) return null;
        for (int i = state.items.size() - 1; i >= 0; i--) {
            Object item = state.items.get(i);
            if (item instanceof Placement p && !isFloating(p.box())) {
                Float b = lastBaseline(p.box());
                if (b != null) return box.bT + box.pT + p.y() + p.box().mT + b;
            } else if (item instanceof LineRun run && !run.lines().isEmpty()) {
                InlineLine line = run.lines().getLast();
                return box.bT + box.pT + line.top() + line.baseline();
            }
        }
        return null;
    }

    private static Float baselineOf(LayoutBox box, Object item) {
        if (item instanceof Placement p && !isFloating(p.box())) {
            Float b = firstBaseline(p.box());
            return b != null ? box.bT + box.pT + p.y() + p.box().mT + b : null;
        }
        if (item instanceof LineRun run && !run.lines().isEmpty()) {
            InlineLine line = run.lines().getFirst();
            return box.bT + box.pT + line.top() + line.baseline();
        }
        return null;
    }

    /**
     * 不可分なインラインのベースライン（border box の上端から）。
     * 行を持ち overflow が visible のインラインブロックは最後の行のベースライン、
     * それ以外（行が無い・置換要素・overflow が visible 以外）は下マージンの端（CSS 2.2 §10.8.1）。
     *
     * 何が壊れていたか: 以前は常に border box の下端で近似していて、下マージンを持つ
     * インラインブロックが行のベースラインより下にはみ出していた。
     */
    private static float atomicBaseline(LayoutBox box) {
        Float b = null;
        if (box.display.inner() == Inner.FLOW_ROOT && !box.isScrollContainer()) {
            b = lastBaseline(box);
        } else if (box.display.inner() != Inner.FLOW_ROOT) {
            b = firstBaseline(box);
        }
        return b != null ? b : box.h + box.mB;
    }

    // ==========================================
    // インライン整形
    // ==========================================

    /** {@code vertical-align} の揃え方。 */
    private enum VAlign { BASELINE, TOP, BOTTOM }

    /** 行の中の 1 断片。{@code raise} はベースラインから持ち上げる量（px）。 */
    private record LineItem(InlineFragment frag, VAlign align, float raise) {}

    /** 行分割の結果 1 行。座標は内容ボックス相対、{@code baseline} は行の上端から。 */
    private record InlineLine(List<LineItem> items, float top, float left, float width, float height, float baseline) {}

    /**
     * インライン内容を行に分割する。float を避けて各行の左端と幅を決め、行の中の float を置く。
     */
    private final class LineBuilder {
        final FlowBuilder flow;
        final LayoutContext ctx;
        final List<InlineLine> lines = new ArrayList<>();
        final float strutHeight, strutBaseline;
        List<LineItem> current = new ArrayList<>();
        float lineTop;
        float lineLeft, lineWidth;
        /** 行の左端からの進み位置。float のまま持つ（断片ごとの切り上げで誤差が溜まっていた）。 */
        float lineX;
        float indent;
        boolean lineStarted = false;
        final List<LayoutBox> deferredFloats = new ArrayList<>();

        LineBuilder(FlowBuilder flow, float startY, float indent) {
            this.flow = flow;
            this.ctx = flow.ctx;
            this.lineTop = startY;
            this.indent = indent;
            // 行ボックスの strut: 内容の無い行でも、ブロックの書体と line-height の高さを持つ（CSS 2.2 §10.8.1）
            TextShaper.TextStyle strut = ctx.textStyleFor(flow.box.style);
            this.strutHeight = strut.lineHeightPx();
            this.strutBaseline = baselineWithin(strut, ctx.faceFor(flow.box.style));
        }

        List<InlineLine> build(List<LayoutBox> inlines) {
            List<FlatRun> runs = new ArrayList<>();
            flattenInlines(inlines, runs, new ArrayList<>());
            for (FlatRun run : runs) {
                switch (run.kind) {
                    case TEXT -> addText(run);
                    case BREAK -> addBreak(run);
                    case ATOMIC -> addAtomic(run);
                    case FLOAT -> addFloat(run.box);
                    case ABSOLUTE -> {
                        ensureLine();
                        flow.state.items.add(new StaticPosition(run.box, lineLeft + lineX, lineTop));
                    }
                }
            }
            if (!current.isEmpty()) newLine(0, 0);
            for (LayoutBox f : deferredFloats) flow.placeFloat(f, lineTop);
            deferredFloats.clear();
            return lines;
        }

        private boolean constrained() {
            return flow.innerW > 0;
        }

        private void ensureLine() {
            if (lineStarted) return;
            for (LayoutBox f : deferredFloats) flow.placeFloat(f, lineTop);
            deferredFloats.clear();
            computeBand();
            lineX = indent;
            indent = 0;
            lineStarted = true;
        }

        /** 行の上端で float を避けた左端と幅。 */
        private void computeBand() {
            FloatSpace fs = flow.floats;
            if (fs.isEmpty()) {
                lineLeft = 0;
                lineWidth = flow.innerW;
                return;
            }
            float y = flow.oy + lineTop;
            float l = fs.leftEdge(y, strutHeight, flow.ox) - flow.ox;
            float r = fs.rightEdge(y, strutHeight, flow.ox + flow.innerW) - flow.ox;
            lineLeft = l;
            lineWidth = Math.max(0, r - l);
        }

        /** 空の行に幅 {@code w} の内容が入らず、float が幅を狭めているなら、float の下まで行を下げる。 */
        private void dropBelowFloats(float w) {
            FloatSpace fs = flow.floats;
            if (!constrained() || fs.isEmpty() || !current.isEmpty()) return;
            for (int guard = 0; guard < 256 && w > lineWidth + 0.01f && lineWidth < flow.innerW; guard++) {
                float next = fs.nextBottom(flow.oy + lineTop);
                if (Float.isInfinite(next)) return;
                lineTop = next - flow.oy;
                computeBand();
            }
        }

        private void newLine(float fallbackHeight, float fallbackBaseline) {
            InlineLine line = finishLine(fallbackHeight, fallbackBaseline);
            lines.add(line);
            lineTop += line.height();
            current = new ArrayList<>();
            lineStarted = false;
        }

        private void addText(FlatRun run) {
            Decoration deco = decorationOf(run.inlineParents);
            TextShaper.TextStyle textStyle = ctx.textStyleFor(run.box.style);
            FontFace face = ctx.faceFor(run.box.style);
            FontStyle font = textStyle.font();
            float runH = textStyle.lineHeightPx();
            float baseline = baselineWithin(textStyle, face);
            Alignment alignment = alignmentOf(run, runH, baseline, deco);

            ensureLine();
            lineX += deco.left();
            // white-space が改行を残す（pre / pre-wrap / pre-line）なら原文の改行で行を割る。
            // 以前は改行が区切りとして扱われず、<pre> の中身が 1 行に潰れていた。
            String[] paragraphs = textStyle.whiteSpace().preservesNewlines()
                    ? run.text.split("\n", -1)
                    : new String[]{run.text};

            for (int p = 0; p < paragraphs.length; p++) {
                if (p > 0) {
                    newLine(runH, baseline);
                    ensureLine();
                    lineX += deco.left();
                }
                for (String segment : TextShaper.segments(paragraphs[p], textStyle.wordBreak())) {
                    if (segment.isEmpty()) continue;
                    float advance = face.advance(segment, font);
                    // 行末の空白は幅に数えない（CSS と同じ）
                    float trimmedW = face.advance(segment.stripTrailing(), font);

                    if (textStyle.whiteSpace().wraps() && !segment.isBlank()) {
                        if (needsWrap(lineX + trimmedW + deco.right())) {
                            newLine(runH, baseline);
                            ensureLine();
                            lineX += deco.left();
                        }
                        dropBelowFloats(lineX + trimmedW + deco.right());
                    }
                    // 断片の座標も float のまま持つ。丸めるのは描画（UIRenderer）の 1 箇所だけ
                    InlineFragment frag = new InlineFragment(run.box, lineX, deco.top(), advance, runH, segment);
                    frag.baseline = baseline;
                    frag.parents = run.inlineParents;
                    current.add(new LineItem(frag, alignment.align, alignment.raise));
                    lineX += advance;
                }
            }
            lineX += deco.right();
        }

        private void addBreak(FlatRun run) {
            // <br>: 内容が無い行でもフォント 1 行分の高さを確保する
            TextShaper.TextStyle brStyle = ctx.textStyleFor(run.box.style);
            ensureLine();
            newLine(brStyle.lineHeightPx(), baselineWithin(brStyle, ctx.faceFor(run.box.style)));
        }

        private void addAtomic(FlatRun run) {
            LayoutBox b = run.box;
            Decoration deco = decorationOf(run.inlineParents);
            float boxOuterW = b.w + b.mL + b.mR;
            float baseline = atomicBaseline(b);
            Alignment alignment = alignmentOf(run, b.h, baseline, deco);

            ensureLine();
            // 折り返せるかは不可分なインラインを含む側（親）の white-space で決まる。
            // 以前は見ておらず、nowrap の行でもインラインブロックが折り返していた
            ComputedStyle around = run.inlineParents.isEmpty() ? flow.box.style : run.inlineParents.getLast().style;
            if (TextShaper.WhiteSpace.of(around.get(CssProperty.WHITE_SPACE)).wraps() && needsWrap(lineX + boxOuterW)) {
                newLine(0, 0);
                ensureLine();
            }
            dropBelowFloats(lineX + boxOuterW);
            InlineFragment frag = new InlineFragment(b, lineX + b.mL, 0, b.w, b.h, null);
            frag.baseline = baseline;
            frag.parents = run.inlineParents;
            current.add(new LineItem(frag, alignment.align, alignment.raise));
            lineX += boxOuterW;
        }

        /** 行の中の float。今の行に入るなら行の上端に置き、入らなければ次の行の上端に置く。 */
        private void addFloat(LayoutBox f) {
            float w = f.w + f.mL + f.mR;
            if (!lineStarted || current.isEmpty() || w <= lineWidth - lineX + 0.01f) {
                flow.placeFloat(f, lineTop);
                if (lineStarted) computeBand();
            } else {
                deferredFloats.add(f);
            }
        }

        /**
         * 折り返すかどうか。
         * 幅の制約が無いとき（内容ボックスの幅が 0）と、行にまだ何も無いときは折り返さない。
         *
         * 何が壊れていたか: 以前は 4px の余裕（INLINE_WRAP_SLOP）を足していた。
         * 測定が幅を切り上げ、描画が別の基準で測り直していたぶんを吸収するためのものだったが、
         * 測定と描画が同じ送り幅を見るようになったので余裕は不要になり、
         * 代わりに「ブラウザなら折り返す幅で折り返さない」差を生むだけになっていた。
         */
        private boolean needsWrap(float lineEnd) {
            return constrained() && !current.isEmpty() && lineEnd > lineWidth;
        }

        /**
         * 行を確定する。高さとベースラインは断片の実寸と strut から求め、
         * 断片が無い行は渡された fallback を使う。
         */
        private InlineLine finishLine(float fallbackHeight, float fallbackBaseline) {
            if (current.isEmpty()) {
                return new InlineLine(current, lineTop, lineLeft, lineWidth, fallbackHeight, fallbackBaseline);
            }
            float baseline = strutBaseline;
            float belowBaseline = strutHeight - strutBaseline;
            for (LineItem item : current) {
                if (item.align() != VAlign.BASELINE) continue;
                InlineFragment f = item.frag();
                Decoration deco = decorationOf(f.parents);
                baseline = Math.max(baseline, deco.top() + f.box.mT + f.baseline + item.raise());
                belowBaseline = Math.max(belowBaseline, f.h - f.baseline + f.box.mB + deco.bottom() - item.raise());
            }
            float height = baseline + belowBaseline;
            // top / bottom は行ボックスの端に揃える。ベースラインで揃えた内容より高ければ行を広げる
            for (LineItem item : current) {
                if (item.align() == VAlign.BASELINE) continue;
                height = Math.max(height, outerHeight(item.frag()));
            }
            return new InlineLine(current, lineTop, lineLeft, lineWidth, height, baseline);
        }

        /**
         * 断片の {@code vertical-align}。不可分なインラインは自分の、テキストはインラインの祖先の値を
         * 外側まで足し合わせる（入れ子の span の持ち上げは累積する）。
         */
        private Alignment alignmentOf(FlatRun run, float height, float baseline, Decoration deco) {
            float raise = 0;
            VAlign align = VAlign.BASELINE;
            List<LayoutBox> chain = new ArrayList<>();
            if (run.kind == FlatRun.Kind.ATOMIC) chain.add(run.box);
            for (int i = run.inlineParents.size() - 1; i >= 0; i--) chain.add(run.inlineParents.get(i));

            float boxHeight = deco.top() + deco.bottom() + run.box.mT + run.box.mB + height;
            float above = deco.top() + run.box.mT + baseline;
            for (LayoutBox el : chain) {
                String v = el.style.keyword(CssProperty.VERTICAL_ALIGN, "baseline");
                LayoutBox parentBox = el.parent != null ? el.parent : flow.box;
                FontFace face = ctx.faceFor(parentBox.style);
                FontStyle font = ctx.fontStyleFor(parentBox.style);
                float parentFont = parentBox.style.fontSizePx();
                switch (v) {
                    case "baseline" -> {
                    }
                    case "top" -> align = VAlign.TOP;
                    case "bottom" -> align = VAlign.BOTTOM;
                    // 中央を親のベースライン + x ハイトの半分に置く
                    case "middle" -> raise += boxHeight / 2 + face.xHeight(font) / 2 - above;
                    // Chromium と同じ近似（下付き: 1/5 em + 1、上付き: 1/3 em + 1）
                    case "sub" -> raise -= parentFont / 5 + 1;
                    case "super" -> raise += parentFont / 3 + 1;
                    case "text-top" -> raise += face.ascent(font) - above;
                    case "text-bottom" -> raise += (boxHeight - above) - face.descent(font);
                    default -> raise += el.style.length(CssProperty.VERTICAL_ALIGN, el.lineHeightPx,
                            ctx.viewport().rootW(), ctx.viewport().rootH());
                }
            }
            return new Alignment(align, raise);
        }
    }

    private record Alignment(VAlign align, float raise) {}

    /** 断片のマージン・祖先の装飾を含む高さ。 */
    private static float outerHeight(InlineFragment f) {
        Decoration deco = decorationOf(f.parents);
        return deco.top() + f.box.mT + f.h + f.box.mB + deco.bottom();
    }

    /**
     * 1 行分の断片を水平に揃え、縦に揃えて確定する。断片は行の相対座標から複製して置くので、
     * 同じ行を何度置いても結果は変わらない。
     *
     * 何が壊れていたか: 以前は断片を行の高さに対して中央寄せしていたため、
     * サイズの違う文字やインラインブロックが混ざった行で文字の足元が揃わなかった。
     * CSS はベースラインを揃える。また不可分なインラインの左マージンを断片の位置と
     * {@link LayoutBox#layout} の両方で足していて、二重にずれていた。
     */
    private void placeLine(InlineLine line, float startX, float drawY, float maxW, String textAlign, LayoutContext ctx) {
        List<LineItem> items = line.items();
        if (items.isEmpty()) return;

        InlineFragment firstFrag = items.getFirst().frag();
        InlineFragment lastFrag = items.getLast().frag();
        float actualLineW = lastFrag.x + lastFrag.w - firstFrag.x;
        float offsetX = startX;
        if (textAlign.equals("center")) offsetX += Math.max(0, (maxW - actualLineW) / 2);
        else if (textAlign.equals("right")) offsetX += Math.max(0, maxW - actualLineW);

        for (LineItem item : items) {
            InlineFragment src = item.frag();
            Decoration deco = decorationOf(src.parents);
            // 装飾箱の上端
            float top = switch (item.align()) {
                case TOP -> drawY;
                case BOTTOM -> drawY + line.height() - outerHeight(src);
                default -> drawY + line.baseline() - (deco.top() + src.box.mT + src.baseline + item.raise());
            };

            InlineFragment f = new InlineFragment(src.box, src.x + offsetX, top + deco.top() + src.box.mT, src.w, src.h, src.text);
            f.baseline = src.baseline;
            f.parents = src.parents;
            f.box.inlineFragments.add(f);

            if (f.parents != null) {
                for (LayoutBox p : f.parents) {
                    InlineFragment decoFrag = new InlineFragment(
                            p, f.x - deco.left(), top, f.w + deco.left() + deco.right(),
                            f.h + deco.top() + deco.bottom(), null);
                    decoFrag.baseline = deco.top() + f.baseline;
                    p.inlineFragments.add(decoFrag);
                }
            }
            if (!f.isText()) f.box.layout(f.x - f.box.mL, f.y - f.box.mT, f.w, f.h, ctx);
        }
    }

    /** インラインの祖先が足す padding + border の合計。 */
    private static Decoration decorationOf(List<LayoutBox> parents) {
        float t = 0, r = 0, b = 0, l = 0;
        if (parents != null) {
            for (LayoutBox p : parents) {
                t += p.pT + p.bT;
                r += p.pR + p.bR;
                b += p.pB + p.bB;
                l += p.pL + p.bL;
            }
        }
        return new Decoration(t, r, b, l);
    }

    private record Decoration(float top, float right, float bottom, float left) {}

    /** 断片の上端からベースラインまでの距離。整形器と同じ half-leading の分け方を使う。 */
    private static float baselineWithin(TextShaper.TextStyle textStyle, FontFace face) {
        FontStyle font = textStyle.font();
        float ascent = face.ascent(font);
        float descent = face.descent(font);
        return (textStyle.lineHeightPx() - (ascent + descent)) / 2f + ascent;
    }

    public static boolean isInlineBox(LayoutBox box) {
        if (box.isAbsolute) return false;
        return box.display.isInlineLevel();
    }

    private void flattenInlines(List<LayoutBox> boxes, List<FlatRun> runs, List<LayoutBox> parents) {
        for (LayoutBox b : boxes) {
            if (b.display.isNone()) continue;
            if (b.isAbsolute) {
                runs.add(new FlatRun(FlatRun.Kind.ABSOLUTE, b, null, new ArrayList<>(parents)));
            } else if (isFloating(b)) {
                runs.add(new FlatRun(FlatRun.Kind.FLOAT, b, null, new ArrayList<>(parents)));
            } else if (b.node.nodeType == NodeType.TEXT) {
                if (b.displayText != null && !b.displayText.isEmpty()) {
                    runs.add(new FlatRun(FlatRun.Kind.TEXT, b, b.displayText, new ArrayList<>(parents)));
                }
            } else if (b.node.tag.equals("br")) {
                runs.add(new FlatRun(FlatRun.Kind.BREAK, b, "\n", new ArrayList<>(parents)));
            } else if (isAtomicInline(b)) {
                runs.add(new FlatRun(FlatRun.Kind.ATOMIC, b, null, new ArrayList<>(parents)));
            } else {
                parents.add(b);
                flattenInlines(b.children, runs, parents);
                parents.removeLast();
            }
        }
    }

    /** 中身を分割せず 1 つの塊として置くインライン要素。 */
    private static boolean isAtomicInline(LayoutBox b) {
        // inline-block / inline-flex / inline-grid / inline-table は中身を自分の整形コンテキストで組む。
        // 以前は inline-block だけを見ていて、inline-flex の子がインライン内容として平坦化されていた
        if (b.display.isInlineLevel() && b.display.inner() != Inner.FLOW) return true;
        String tag = b.node.tag;
        return tag.equals("img") || tag.equals("input") || tag.equals("button")
                || tag.equals("select") || tag.equals("progress")
                || tag.equals("mc-fluid") || tag.equals("mc-entity") || tag.equals("mc-scene");
    }

    private static void clearFragmentsRecursively(LayoutBox box) {
        box.inlineFragments.clear();
        for (LayoutBox child : box.children) clearFragmentsRecursively(child);
    }

    /** 入れ子のインライン要素を平坦化した 1 要素。 */
    private record FlatRun(Kind kind, LayoutBox box, String text, List<LayoutBox> inlineParents) {
        enum Kind { TEXT, BREAK, ATOMIC, FLOAT, ABSOLUTE }
    }
}
