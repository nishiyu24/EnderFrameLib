package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.layout.model.*;

import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.layout.LayoutContext;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssKeywords;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.css.StyleResolver;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.layout.LayoutBox.Display.Inner;
import com.nishiyu.enderframelib.core.layout.LayoutBox.InlineFragment;
import com.nishiyu.enderframelib.core.layout.LayoutBox.SizingMode;
import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.model.FormattingContext;
import com.nishiyu.enderframelib.core.text.model.FontStyle;

import java.util.ArrayList;
import java.util.List;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;
import com.nishiyu.enderframelib.core.css.model.Overflow;
import com.nishiyu.enderframelib.core.css.model.TextAlign;
import com.nishiyu.enderframelib.core.css.model.BorderStyle;

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
        // 行の内容の幅は強制改行（<br> と保持された改行）で区切って、最も広い行を取る。
        // 何が壊れていたか: 以前は区切らずに足し続けていて、<br> を含む inline-block・float・flex アイテムが
        // 全行を横に繋げた幅まで広がっていた
        InlineWidth line = new InlineWidth();

        float childParentH = childPercentageBase(box, space.percentageBlockSize(), ctx);
        FormattingContext childCtx = new FormattingContext("block", "stretch", box.sizingMode == SizingMode.SHRINK_TO_FIT);

        for (LayoutBox child : box.children) {
            if (child.isAbsolute || child.isRenderedLegend || isDisplayNone(child.style)) continue;
            if (child.isOutsideMarker) {
                // 外側のマーカーは通常フローの外にあり、リスト項目の幅に寄与しない
                child.measure(new ConstraintSpace(LayoutMetrics.UNBOUNDED_WIDTH, childParentH, MARKER_CONTEXT), ctx);
                continue;
            }
            child.measure(new ConstraintSpace(tentativeInnerW, childParentH, childCtx), ctx);
            // float は行の内容と同じく横に並ぶので、shrink-to-fit の幅には横に足して数える
            if (isInlineBox(child) || isFloating(child)) {
                addInlineWidth(child, line, ctx);
                maxChildW = Math.max(maxChildW, tentativeInnerW > 0 ? Math.min(tentativeInnerW, line.max) : line.max);
            } else {
                line.clear();
                maxChildW = Math.max(maxChildW, child.w + child.mL + child.mR);
            }
        }
        return maxChildW;
    }

    /** shrink-to-fit の測定で数える、強制改行で区切った行の幅。 */
    private static final class InlineWidth {
        float current, max;

        void add(float w) {
            current += w;
            max = Math.max(max, current);
        }

        void breakLine() {
            current = 0;
        }

        void clear() {
            current = 0;
            max = 0;
        }
    }

    /**
     * 測定済みのインラインレベルの箱を行の幅に足す。インラインボックスは中身を辿り、
     * 開始端と終了端の装飾（margin + border + padding）を 1 回ずつ数える。
     */
    private static void addInlineWidth(LayoutBox b, InlineWidth line, LayoutContext ctx) {
        if (b.isAbsolute || b.display.isNone() || b.isOutsideMarker) return;
        if (b.node.nodeType == NodeType.TEXT) {
            TextStyle textStyle = ctx.textStyleFor(b.style);
            if (b.displayText == null || !textStyle.whiteSpace().preservesNewlines() || b.displayText.indexOf('\n') < 0) {
                // 折り返した文字は、折り返さずに並べた幅（max-content）で数える。shrink-to-fit の幅は
                // min(max-content, 利用可能幅) なので、最も長い行の幅では利用可能幅より狭くなる
                boolean wrapped = b.shapedLines != null && b.shapedLines.size() > 1 && b.displayText != null;
                float w = wrapped ? ctx.faceFor(b.style).advance(b.displayText.strip(), textStyle.font()) : b.w;
                line.add(w + b.mL + b.mR);
                return;
            }
            FontFace face = ctx.faceFor(b.style);
            String[] paragraphs = b.displayText.split("\n", -1);
            for (int i = 0; i < paragraphs.length; i++) {
                if (i > 0) line.breakLine();
                line.add(face.advance(paragraphs[i], textStyle.font()));
            }
        } else if (b.node.tag.equals("br")) {
            line.breakLine();
        } else if (isFloating(b) || isAtomicInline(b) || !isInlineBox(b)) {
            line.add(b.w + b.mL + b.mR);
        } else {
            line.add(b.mL + b.bL + b.pL);
            for (LayoutBox c : b.children) addInlineWidth(c, line, ctx);
            line.add(b.pR + b.bR + b.mR);
        }
    }

    /** 子の高さの % を解く基準。高さが不定なら 0。 */
    private static float childPercentageBase(LayoutBox box, float parentH, LayoutContext ctx) {
        float h = definiteInnerHeight(box, parentH, ctx);
        return Float.isNaN(h) ? 0 : h;
    }

    /**
     * 内容ボックスの高さが確定していればその値、不定なら NaN（子の高さの % の基準。{@code parentH} は自分の % の基準）。
     * 確定しているのは、高さの指定があるとき、親のエンジンが高さを決めたとき（flex の伸縮・stretch、
     * grid の stretch。CSS Flexbox §9.8 の 11 と CSS Grid §6.2）、top と bottom で高さが決まる絶対配置。
     *
     * 何が壊れていたか: 以前は高さの指定しか見ておらず、stretch された flex / grid アイテムや
     * {@code top: 0; bottom: 0} の絶対配置の中で、子の {@code height: 100%} が 0 になっていた。
     */
    static float definiteInnerHeight(LayoutBox box, float parentH, LayoutContext ctx) {
        if (box.parent == null) return Math.max(0, parentH - box.verticalDecoration());
        if (!Float.isNaN(box.overrideHeight)) return Math.max(0, box.overrideHeight - box.verticalDecoration());
        float rootW = ctx.viewport().rootW(), rootH = ctx.viewport().rootH();
        if (hasSpecifiedHeight(box.style)) {
            float resolvedH = box.style.length(CssProperty.HEIGHT, parentH, rootW, rootH);
            boolean isBorderBox = box.style.isBorderBox();
            return Math.max(0, isBorderBox ? resolvedH - box.verticalDecoration() : resolvedH);
        }
        if (box.isAbsolute && BoxMeasurer.hasBothInsets(box.style, CssProperty.TOP, CssProperty.BOTTOM)) {
            float insets = box.style.length(CssProperty.TOP, parentH, rootW, rootH)
                    + box.style.length(CssProperty.BOTTOM, parentH, rootW, rootH);
            return Math.max(0, parentH - insets - box.mT - box.mB - box.verticalDecoration());
        }
        return Float.NaN;
    }

    private static boolean hasSpecifiedHeight(ComputedStyle style) {
        return style.containsKey(CssProperty.HEIGHT) && !"auto".equals(style.getOrDefault(CssProperty.HEIGHT, "auto").trim());
    }

    @Override
    public float contentBlockSize(LayoutBox box, ConstraintSpace space, LayoutContext ctx) {
        if (box.sizingMode == SizingMode.SHRINK_TO_FIT) stretchBlockChildren(box, space, ctx);
        FlowState state = new FlowBuilder(box, space, ctx).build();
        box.engineState = state;
        return state.height;
    }

    @Override
    public void finishLayout(LayoutBox box, ConstraintSpace space, LayoutContext ctx) {
        measureAbsoluteChildren(box, ctx);
    }

    /**
     * shrink-to-fit の箱（inline-block・float・絶対配置など）の幅が決まったら、幅が auto のブロックレベルの子を
     * その幅いっぱいに測り直す（CSS 2.2 §10.3.3: ブロックの幅は包含ブロックの幅を満たす）。
     *
     * 何が壊れていたか: 内容の幅を測る段階では子も縮めて測るので、いちばん広い子より狭い子は
     * 自分の内容の幅のままだった（inline-block の中の短い段落の背景や枠線が、箱の幅まで広がらなかった）。
     */
    private void stretchBlockChildren(LayoutBox box, ConstraintSpace space, LayoutContext ctx) {
        float innerW = Math.max(0, box.w - box.horizontalDecoration());
        float base = childPercentageBase(box, space.percentageBlockSize(), ctx);
        for (LayoutBox child : box.children) {
            if (child.isAbsolute || child.isOutsideMarker || child.isRenderedLegend || child.display.isNone()
                    || isInlineBox(child) || isFloating(child) || child.node.nodeType == NodeType.TEXT) continue;
            if (!"auto".equals(child.style.getOrDefault(CssProperty.WIDTH, "auto").trim())) continue;
            if (Math.abs(child.w + child.mL + child.mR - innerW) <= 0.01f) continue;
            child.measure(new ConstraintSpace(innerW, base, FormattingContext.BLOCK), ctx);
        }
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
        TextAlign textAlign = box.style.keywordOf(CssProperty.TEXT_ALIGN, TextAlign.class);

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
                    for (LayoutBox inlineBox : run.boxes()) fitToFragments(inlineBox);
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
            if (child.isOutsideMarker || child.isRenderedLegend || child.isAbsolute || child.display.isNone()) continue;
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

    /** float かどうか。float はブロックフローの子のときだけ効く（flex / grid アイテムでは無視される）。 */
    public static boolean isFloating(LayoutBox box) {
        if (box.isAbsolute || box.parent == null || box.node.nodeType == NodeType.TEXT) return false;
        if (box.style.keywordOf(CssProperty.FLOAT, CssKeywords.FloatSide.class) == CssKeywords.FloatSide.NONE) return false;
        return LayoutEngine.getEngine(box.parent.display) instanceof BlockLayoutEngine;
    }

    /** 子孫（同じ BFC の中）に float があるか。あれば親の float 領域の中で高さを解き直す。 */
    private static boolean containsFloats(LayoutBox box) {
        for (LayoutBox c : box.children) {
            if (c.isAbsolute || c.isRenderedLegend || c.display.isNone()) continue;
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
                if (isDisplayNone(child.style) || child.isOutsideMarker || child.isRenderedLegend) continue;
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
            CssKeywords.Clear clear = child.style.keywordOf(CssProperty.CLEAR, CssKeywords.Clear.class);
            if (clear == CssKeywords.Clear.NONE || floats.isEmpty()) return y;
            return Math.max(y, floats.clearance(clear) - oy);
        }

        /** float を置き、領域に登録する。{@code minY} は置ける最も上の位置（内容ボックス相対）。 */
        void placeFloat(LayoutBox f, float minY) {
            CssKeywords.FloatSide side = f.style.keywordOf(CssProperty.FLOAT, CssKeywords.FloatSide.class);
            boolean left = side != CssKeywords.FloatSide.RIGHT && side != CssKeywords.FloatSide.INLINE_END;
            float w = f.w + f.mL + f.mR;
            float h = f.h + f.mT + f.mB;
            // float の上端は先に置いた float より上にはならない（CSS 2.2 §9.5.1 の規則 5）
            float y = Math.max(oy + minY, floats.lastTop());
            CssKeywords.Clear clear = f.style.keywordOf(CssProperty.CLEAR, CssKeywords.Clear.class);
            if (clear != CssKeywords.Clear.NONE) y = Math.max(y, floats.clearance(clear));
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

    static boolean isFirstFlowChild(LayoutBox box) {
        if (box.parent == null) return true;
        for (LayoutBox c : box.parent.children) {
            if (c.isAbsolute || c.isOutsideMarker || c.isRenderedLegend || c.display.isNone()) continue;
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
        float clearance(CssKeywords.Clear clear) {
            boolean clearLeft = clear == CssKeywords.Clear.LEFT || clear == CssKeywords.Clear.INLINE_START;
            boolean clearRight = clear == CssKeywords.Clear.RIGHT || clear == CssKeywords.Clear.INLINE_END;
            float b = Float.NEGATIVE_INFINITY;
            if (!clearRight) for (float[] f : lefts) b = Math.max(b, f[3]);
            if (!clearLeft) for (float[] f : rights) b = Math.max(b, f[3]);
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
        if (synthesizesBaseline(box) || !(box.engineState instanceof FlowState state)) return null;
        for (Object item : state.items) {
            Float b = baselineOf(box, item);
            if (b != null) return b;
        }
        return null;
    }

    /**
     * size / layout containment を持つ箱のベースラインは、中身に依らず境界ボックスの端から合成する（CSS Containment 2 §3, §4）。
     * 中身の配置の状態は、測った後に layout が組むので、読むと前回の layout の残りに左右される。
     */
    private static boolean synthesizesBaseline(LayoutBox box) {
        return (box.style.containment() & (ComputedStyle.CONTAIN_SIZE | ComputedStyle.CONTAIN_LAYOUT)) != 0
                || box.containsBlockSize();
    }

    /** 最後の行のベースライン（border box の上端から）。インラインブロックのベースラインになる。 */
    static Float lastBaseline(LayoutBox box) {
        if (synthesizesBaseline(box) || !(box.engineState instanceof FlowState state)) return null;
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
     *
     * ただし UA の部品で描くチェックボックス・ラジオボタンと、レンジ（appearance によらない。Chrome の LayoutSlider）は
     * border box の下端（下マージンはベースラインの下に出る）。Chrome の実測。以前は下マージンの端にしていて、
     * チェックボックスだけの行が Chrome より 2.5px 高かった。
     */
    private static float atomicBaseline(LayoutBox box) {
        if (StyleResolver.isNativeToggle(box.node, box.style.asMap()) || isRangeInput(box)) return box.h;
        Float b = null;
        if (box.display.inner() == Inner.FLOW_ROOT && !box.isScrollContainer()) {
            b = lastBaseline(box);
        } else if (box.display.inner() != Inner.FLOW_ROOT) {
            b = firstBaseline(box);
        }
        return b != null ? b : box.h + box.mB;
    }

    private static boolean isRangeInput(LayoutBox box) {
        return "input".equals(box.node.tag) && "range".equalsIgnoreCase(box.node.attrs.getOrDefault("type", "").trim());
    }

    // ==========================================
    // インライン整形
    // ==========================================

    /** {@code vertical-align} の揃え方。 */
    private enum VAlign { BASELINE, TOP, BOTTOM }

    /** 行の中の 1 断片。{@code raise} はベースラインから持ち上げる量（px）。 */
    private record LineItem(InlineFragment frag, VAlign align, float raise) {}

    /**
     * 行分割の結果 1 行。座標は内容ボックス相対、{@code baseline} は行の上端から。
     * {@code used} は行の内容の幅（行の左端から、行末の空白を除く）で、{@code text-align} が読む。
     */
    private record InlineLine(List<LineItem> items, List<BoxSpan> spans, float top, float left, float width,
                              float height, float baseline, float used) {}

    /**
     * 行の上で開いている（開始タグを過ぎて終了タグに達していない）インラインボックス。
     * 行をまたぐと、次の行の頭から続きの断片を始める。
     */
    private static final class OpenBox {
        final LayoutBox box;
        final Alignment alignment;
        /** この行での断片の左端（border box）と、その手前の開始端の margin の位置。行の左端からの相対。 */
        float start, marginStart;
        /** この行の断片が開始端（左の margin / border / padding）を持つか。最初の断片だけが持つ。 */
        boolean startEdge = true;
        /** この行に中身を置いたか。中身を置く前に折り返したら、開始端ごと次の行へ移る。 */
        boolean hasContent;
        /** 次の行の頭で開始端を付け直す。 */
        boolean reopen;

        OpenBox(LayoutBox box, Alignment alignment) {
            this.box = box;
            this.alignment = alignment;
        }
    }

    /** 1 行に載ったインラインボックスの断片（border box の左右端、行の左端からの相対）。 */
    private record BoxSpan(OpenBox open, float left, float right, boolean startEdge, boolean endEdge) {}

    /**
     * インライン内容を行に分割する。float を避けて各行の左端と幅を決め、行の中の float を置く。
     *
     * インラインボックス（{@code <span>} など）は開始タグと終了タグで 1 回ずつ装飾を足す（CSS Inline 3 /
     * {@code box-decoration-break: slice}）。開始端の margin + border + padding は最初の断片、終了端は最後の断片だけが
     * 持ち、同じ行の中身は 1 つの断片にまとめる。
     *
     * 何が壊れていたか: 以前は祖先の装飾を断片（語）ごとに足していた。{@code <span style="padding: 4px">Hello world</span>}
     * は語ごとに左右の padding が付いて背景が重なり、行を折り返すたびに開始端の装飾も足されていた。
     */
    private final class LineBuilder {
        final FlowBuilder flow;
        final LayoutContext ctx;
        final List<InlineLine> lines = new ArrayList<>();
        final float strutHeight, strutBaseline;
        List<LineItem> current = new ArrayList<>();
        List<BoxSpan> spans = new ArrayList<>();
        final List<OpenBox> open = new ArrayList<>();
        float lineTop;
        float lineLeft, lineWidth;
        /** 行の左端からの進み位置。float のまま持つ（断片ごとの切り上げで誤差が溜まっていた）。 */
        float lineX;
        /** 最後に置いた語の行末の空白の幅。行を閉じるとき内容の幅から除く。 */
        float trailingSpace;
        float indent;
        boolean lineStarted = false;
        final List<LayoutBox> deferredFloats = new ArrayList<>();

        LineBuilder(FlowBuilder flow, float startY, float indent) {
            this.flow = flow;
            this.ctx = flow.ctx;
            this.lineTop = startY;
            this.indent = indent;
            // 行ボックスの strut: 内容の無い行でも、ブロックの書体と line-height の高さを持つ（CSS 2.2 §10.8.1）
            TextStyle strut = ctx.textStyleFor(flow.box.style);
            this.strutHeight = strut.lineHeightPx();
            this.strutBaseline = baselineWithin(strut, ctx.faceFor(flow.box.style));
        }

        List<InlineLine> build(List<LayoutBox> inlines) {
            List<FlatRun> runs = new ArrayList<>();
            flattenInlines(inlines, runs, new ArrayList<>());
            for (int i = 0; i < runs.size(); i++) {
                FlatRun run = runs.get(i);
                switch (run.kind) {
                    case TEXT -> addText(run, closingEdges(runs, i));
                    case BREAK -> addBreak(run);
                    case ATOMIC -> addAtomic(run, closingEdges(runs, i));
                    case FLOAT -> addFloat(run.box);
                    case ABSOLUTE -> {
                        ensureLine();
                        flow.state.items.add(new StaticPosition(run.box, lineLeft + lineX, lineTop));
                    }
                    case OPEN -> openBox(run);
                    case CLOSE -> closeBox();
                }
            }
            if (!current.isEmpty() || spans.stream().anyMatch(s -> isDecorated(s.open().box))) newLine(0, 0, false);
            for (LayoutBox f : deferredFloats) flow.placeFloat(f, lineTop);
            deferredFloats.clear();
            return lines;
        }

        /** {@code runs[i]} の直後に閉じるインラインボックスの終了端の装飾。最後の語と一緒に行に入らないといけない。 */
        private float closingEdges(List<FlatRun> runs, int i) {
            float w = 0;
            for (int j = i + 1; j < runs.size() && runs.get(j).kind == FlatRun.Kind.CLOSE; j++) {
                LayoutBox b = runs.get(j).box;
                w += b.pR + b.bR + b.mR;
            }
            return w;
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
            trailingSpace = 0;
            lineStarted = true;
            // 前の行から続く箱は行の頭から断片を始める。中身を置く前に折り返した箱は開始端を付け直す
            for (OpenBox ob : open) {
                ob.marginStart = lineX;
                if (ob.reopen) {
                    lineX += ob.box.mL;
                    ob.start = lineX;
                    lineX += ob.box.bL + ob.box.pL;
                    ob.reopen = false;
                } else {
                    ob.start = lineX;
                }
            }
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

        /**
         * 行を閉じる。開いている箱はこの行の断片を閉じて次の行へ続ける。
         * {@code wrapped}（自動の折り返し）なら、まだ中身を置いていない箱は開始端ごと次の行へ移す。
         */
        private void newLine(float fallbackHeight, float fallbackBaseline, boolean wrapped) {
            int carried = open.size();
            if (wrapped) {
                // 中身の無い箱は開いている箱の末尾に並ぶ（中の箱に中身があれば外の箱にもある）
                while (carried > 0 && open.get(carried - 1).startEdge && !open.get(carried - 1).hasContent) carried--;
            }
            float end = (carried < open.size() ? open.get(carried).marginStart : lineX) - trailingSpace;
            for (int i = 0; i < open.size(); i++) {
                OpenBox ob = open.get(i);
                if (i < carried) {
                    spans.add(new BoxSpan(ob, ob.start, Math.max(ob.start, end), ob.startEdge, false));
                    ob.startEdge = false;
                } else {
                    ob.reopen = true;
                }
                ob.hasContent = false;
            }
            InlineLine line = finishLine(fallbackHeight, fallbackBaseline, end);
            lines.add(line);
            lineTop += line.height();
            current = new ArrayList<>();
            spans = new ArrayList<>();
            lineStarted = false;
        }

        /** 開いている箱に中身が置かれた。 */
        private void markContent() {
            for (OpenBox ob : open) ob.hasContent = true;
        }

        private void openBox(FlatRun run) {
            ensureLine();
            LayoutBox b = run.box;
            FontFace face = ctx.faceFor(b.style);
            FontStyle font = ctx.fontStyleFor(b.style);
            float ascent = face.ascent(font);
            OpenBox ob = new OpenBox(b, alignmentOf(chainOf(b, run.inlineParents), ascent + face.descent(font), ascent));
            ob.marginStart = lineX;
            lineX += b.mL;
            ob.start = lineX;
            lineX += b.bL + b.pL;
            open.add(ob);
        }

        private void closeBox() {
            ensureLine();
            OpenBox ob = open.removeLast();
            lineX += ob.box.pR + ob.box.bR;
            spans.add(new BoxSpan(ob, ob.start, lineX, ob.startEdge, true));
            lineX += ob.box.mR;
            trailingSpace = 0;
            markContent();
        }

        private void addText(FlatRun run, float closing) {
            TextStyle textStyle = ctx.textStyleFor(run.box.style);
            FontFace face = ctx.faceFor(run.box.style);
            FontStyle font = textStyle.font();
            float runH = textStyle.lineHeightPx();
            float baseline = baselineWithin(textStyle, face);
            Alignment alignment = alignmentOf(chainOf(null, run.inlineParents), runH, baseline);

            ensureLine();
            // white-space が改行を残す（pre / pre-wrap / pre-line）なら原文の改行で行を割る。
            // 以前は改行が区切りとして扱われず、<pre> の中身が 1 行に潰れていた。
            String[] paragraphs = textStyle.whiteSpace().preservesNewlines()
                    ? run.text.split("\n", -1)
                    : new String[]{run.text};

            for (int p = 0; p < paragraphs.length; p++) {
                if (p > 0) {
                    markContent();
                    newLine(runH, baseline, false);
                    ensureLine();
                }
                List<String> segments = TextShaper.segments(paragraphs[p], textStyle.wordBreak());
                // 入るかどうかは語の幅の和に境目の詰め（TextShaper.kerningBetween）を足した幅、つまり続けて測った幅で
                // 見る（TextShaper と同じ。max-content もこの測り方）。kerned はこの run が今の行で詰めた量の合計。
                // 何が壊れていたか: 和だけで見ていたため、語の境目に詰め（- と v など）があると max-content ちょうどの
                // flex アイテムで "two-viewers" が "two-" の後で折り返していた（実機で確認）。
                String previousOnLine = null;
                float kerned = 0;
                int lastSegment = segments.size() - 1;
                while (lastSegment >= 0 && segments.get(lastSegment).isEmpty()) lastSegment--;
                for (int s = 0; s < segments.size(); s++) {
                    String segment = segments.get(s);
                    if (segment.isEmpty()) continue;
                    float advance = face.advance(segment, font);
                    // 行末の空白は幅に数えない（CSS と同じ）
                    float trimmedW = face.advance(segment.stripTrailing(), font);
                    // 最後の語は、直後に閉じる箱の終了端の装飾と一緒に行に入らないといけない
                    float edges = p == paragraphs.length - 1 && s == lastSegment ? closing : 0;

                    float join = previousOnLine == null ? 0 : TextShaper.kerningBetween(face, font, previousOnLine, segment);
                    if (textStyle.whiteSpace().wraps() && !segment.isBlank()) {
                        if (needsWrap(lineX + kerned + join + trimmedW + edges)) {
                            newLine(runH, baseline, true);
                            ensureLine();
                            kerned = 0;
                            join = 0;
                        }
                        dropBelowFloats(lineX + kerned + join + trimmedW + edges);
                    }
                    kerned += join;
                    previousOnLine = segment;
                    // 断片の座標も float のまま持つ。丸めるのは描画側の 1 箇所だけ
                    InlineFragment frag = new InlineFragment(run.box, lineX, 0, advance, runH, segment);
                    frag.baseline = baseline;
                    frag.parents = run.inlineParents;
                    current.add(new LineItem(frag, alignment.align, alignment.raise));
                    lineX += advance;
                    trailingSpace = advance - trimmedW;
                    markContent();
                }
            }
        }

        private void addBreak(FlatRun run) {
            // <br>: 内容が無い行でもフォント 1 行分の高さを確保する
            TextStyle brStyle = ctx.textStyleFor(run.box.style);
            ensureLine();
            markContent();
            newLine(brStyle.lineHeightPx(), baselineWithin(brStyle, ctx.faceFor(run.box.style)), false);
        }

        private void addAtomic(FlatRun run, float closing) {
            LayoutBox b = run.box;
            float boxOuterW = b.w + b.mL + b.mR;
            float baseline = atomicBaseline(b);
            Alignment alignment = alignmentOf(chainOf(b, run.inlineParents), b.mT + b.mB + b.h, b.mT + baseline);

            ensureLine();
            // 折り返せるかは不可分なインラインを含む側（親）の white-space で決まる。
            // 以前は見ておらず、nowrap の行でもインラインブロックが折り返していた
            ComputedStyle around = run.inlineParents.isEmpty() ? flow.box.style : run.inlineParents.getLast().style;
            if (WhiteSpace.of(around.get(CssProperty.WHITE_SPACE)).wraps() && needsWrap(lineX + boxOuterW + closing)) {
                newLine(0, 0, true);
                ensureLine();
            }
            dropBelowFloats(lineX + boxOuterW + closing);
            InlineFragment frag = new InlineFragment(b, lineX + b.mL, 0, b.w, b.h, null);
            frag.baseline = baseline;
            frag.parents = run.inlineParents;
            current.add(new LineItem(frag, alignment.align, alignment.raise));
            lineX += boxOuterW;
            trailingSpace = 0;
            markContent();
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
            return constrained() && !current.isEmpty() && lineEnd > lineWidth + TextShaper.WRAP_TOLERANCE;
        }

        /**
         * 行を確定する。高さとベースラインは断片の実寸と strut から求め、
         * 断片が無い行は渡された fallback を使う（装飾を持つインラインボックスだけの行は strut の高さ）。
         *
         * インラインボックスの上下の padding・border・margin は行の高さに入らない（CSS 2.2 §10.8.1）。
         * 何が壊れていたか: 以前は祖先の上下の装飾を断片ごとに足していて、{@code <code>} の padding が行を押し広げていた。
         */
        private InlineLine finishLine(float fallbackHeight, float fallbackBaseline, float used) {
            if (current.isEmpty()) {
                boolean decorated = fallbackHeight == 0 && spans.stream().anyMatch(s -> isDecorated(s.open().box));
                return new InlineLine(current, spans, lineTop, lineLeft, lineWidth,
                        decorated ? strutHeight : fallbackHeight, decorated ? strutBaseline : fallbackBaseline, used);
            }
            float baseline = strutBaseline;
            float belowBaseline = strutHeight - strutBaseline;
            for (LineItem item : current) {
                if (item.align() != VAlign.BASELINE) continue;
                InlineFragment f = item.frag();
                baseline = Math.max(baseline, f.box.mT + f.baseline + item.raise());
                belowBaseline = Math.max(belowBaseline, f.h - f.baseline + f.box.mB - item.raise());
            }
            float height = baseline + belowBaseline;
            // top / bottom は行ボックスの端に揃える。ベースラインで揃えた内容より高ければ行を広げる
            for (LineItem item : current) {
                if (item.align() == VAlign.BASELINE) continue;
                height = Math.max(height, outerHeight(item.frag()));
            }
            return new InlineLine(current, spans, lineTop, lineLeft, lineWidth, height, baseline, used);
        }

        /**
         * {@code vertical-align} の揃え方と持ち上げる量。{@code chain} は内側から外側へ、自分の値と
         * インラインの祖先の値を足し合わせる（入れ子の span の持ち上げは累積する）。
         * {@code height} と {@code above} は揃える箱の高さとその上端からベースラインまで。
         */
        private Alignment alignmentOf(List<LayoutBox> chain, float height, float above) {
            float raise = 0;
            VAlign align = VAlign.BASELINE;
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
                    case "middle" -> raise += height / 2 + face.xHeight(font) / 2 - above;
                    // Chromium と同じ近似（下付き: 1/5 em + 1、上付き: 1/3 em + 1）
                    case "sub" -> raise -= parentFont / 5 + 1;
                    case "super" -> raise += parentFont / 3 + 1;
                    case "text-top" -> raise += face.ascent(font) - above;
                    case "text-bottom" -> raise += (height - above) - face.descent(font);
                    default -> raise += el.style.length(CssProperty.VERTICAL_ALIGN, el.lineHeightPx,
                            ctx.viewport().rootW(), ctx.viewport().rootH());
                }
            }
            return new Alignment(align, raise);
        }
    }

    /** {@code vertical-align} を足し合わせる箱の並び（{@code self} とインラインの祖先、内側から外側へ）。 */
    private static List<LayoutBox> chainOf(LayoutBox self, List<LayoutBox> inlineParents) {
        List<LayoutBox> chain = new ArrayList<>(inlineParents.size() + 1);
        if (self != null) chain.add(self);
        for (int i = inlineParents.size() - 1; i >= 0; i--) chain.add(inlineParents.get(i));
        return chain;
    }

    private record Alignment(VAlign align, float raise) {}

    /** 行を空にしない装飾（margin・border・padding）を持つインラインボックスか（CSS 2.2 §9.4.2）。 */
    private static boolean isDecorated(LayoutBox b) {
        return b.mL != 0 || b.mR != 0 || b.horizontalDecoration() != 0 || b.verticalDecoration() != 0;
    }

    /** 断片のマージンを含む高さ。 */
    private static float outerHeight(InlineFragment f) {
        return f.box.mT + f.h + f.box.mB;
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
    private void placeLine(InlineLine line, float startX, float drawY, float maxW, TextAlign textAlign,
                           LayoutContext ctx) {
        List<LineItem> items = line.items();
        if (items.isEmpty() && line.spans().isEmpty()) return;

        // 揃えの幅は行の左端から内容の終わりまで（text-indent と開始端の装飾を含み、行末の空白を除く）
        float offsetX = startX;
        if (textAlign == TextAlign.CENTER) offsetX += Math.max(0, (maxW - line.used()) / 2);
        else if (textAlign == TextAlign.RIGHT || textAlign == TextAlign.END) {
            offsetX += Math.max(0, maxW - line.used());
        }

        for (LineItem item : items) {
            InlineFragment src = item.frag();
            // マージンボックスの上端
            float top = switch (item.align()) {
                case TOP -> drawY;
                case BOTTOM -> drawY + line.height() - outerHeight(src);
                default -> drawY + line.baseline() - (src.box.mT + src.baseline + item.raise());
            };

            InlineFragment f = new InlineFragment(src.box, src.x + offsetX, top + src.box.mT, src.w, src.h, src.text);
            f.baseline = src.baseline;
            f.parents = src.parents;
            f.box.inlineFragments.add(f);
            if (!f.isText()) f.box.layout(f.x - f.box.mL, f.y - f.box.mT, f.w, f.h, ctx);
        }
        for (BoxSpan span : line.spans()) placeSpan(span, offsetX, drawY, line, ctx);
    }

    /**
     * インラインボックスの 1 行分の断片を置く。縦は行の高さではなく自分の内容領域（書体の ascent + descent を
     * 自分のベースラインに置いたもの）に上下の padding と border を足した範囲（CSS 2.2 §10.6.1）。
     */
    private static void placeSpan(BoxSpan span, float offsetX, float drawY, InlineLine line, LayoutContext ctx) {
        LayoutBox b = span.open().box;
        FontFace face = ctx.faceFor(b.style);
        FontStyle font = ctx.fontStyleFor(b.style);
        float ascent = face.ascent(font);
        float descent = face.descent(font);
        float leadingAbove = TextShaper.leadingAbove(b.lineHeightPx, ascent, descent);
        float leadingBelow = b.lineHeightPx - (ascent + descent) - leadingAbove;
        Alignment alignment = span.open().alignment;
        float baselineY = switch (alignment.align()) {
            case TOP -> drawY + leadingAbove + ascent;
            case BOTTOM -> drawY + line.height() - leadingBelow - descent;
            default -> drawY + line.baseline() - alignment.raise();
        };
        float top = baselineY - ascent - b.pT - b.bT;
        InlineFragment f = new InlineFragment(b, offsetX + span.left(), top, span.right() - span.left(),
                b.bT + b.pT + ascent + descent + b.pB + b.bB, null);
        f.baseline = baselineY - top;
        f.startEdge = span.startEdge();
        f.endEdge = span.endEdge();
        b.inlineFragments.add(f);
    }

    /**
     * インラインボックスの矩形（{@code x / y / w / h}）を、置いた断片を囲む矩形にする。
     * ブラウザの {@code getBoundingClientRect} と同じく大きさ 0 の断片は除き、全部が 0 なら最初の断片。
     * ヒットテストの錨と忠実度のダンプがこの矩形を読む。
     *
     * 何が壊れていたか: 以前はインラインボックスに位置を入れておらず、矩形は測定の幅のまま原点にあった。
     */
    private static void fitToFragments(LayoutBox box) {
        if (box.isAbsolute || box.display.isNone() || isFloating(box) || isAtomicInline(box)
                || box.node.nodeType == NodeType.TEXT || !isInlineBox(box)) {
            return;
        }
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (InlineFragment f : box.inlineFragments) {
            if (f.isText() || f.w <= 0 || f.h <= 0) continue;
            minX = Math.min(minX, f.x);
            minY = Math.min(minY, f.y);
            maxX = Math.max(maxX, f.x + f.w);
            maxY = Math.max(maxY, f.y + f.h);
        }
        if (minX <= maxX) {
            box.x = minX;
            box.y = minY;
            box.w = maxX - minX;
            box.h = maxY - minY;
        } else {
            for (InlineFragment f : box.inlineFragments) {
                if (f.isText()) continue;
                box.x = f.x;
                box.y = f.y;
                box.w = f.w;
                box.h = f.h;
                break;
            }
        }
        for (LayoutBox child : box.children) fitToFragments(child);
    }

    /** 断片の上端からベースラインまでの距離。整形器と同じ half-leading の分け方を使う。 */
    private static float baselineWithin(TextStyle textStyle, FontFace face) {
        FontStyle font = textStyle.font();
        float ascent = face.ascent(font);
        float descent = face.descent(font);
        return TextShaper.leadingAbove(textStyle.lineHeightPx(), ascent, descent) + ascent;
    }

    public static boolean isInlineBox(LayoutBox box) {
        if (box.isAbsolute) return false;
        return box.display.isInlineLevel();
    }

    /** インラインボックスは開始タグと終了タグ（{@code OPEN} / {@code CLOSE}）で中身を挟む。 */
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
                runs.add(new FlatRun(FlatRun.Kind.OPEN, b, null, new ArrayList<>(parents)));
                parents.add(b);
                flattenInlines(b.children, runs, parents);
                parents.removeLast();
                runs.add(new FlatRun(FlatRun.Kind.CLOSE, b, null, new ArrayList<>(parents)));
            }
        }
    }

    /** 中身を分割せず 1 つの塊として置くインライン要素。 */
    public static boolean isAtomicInline(LayoutBox b) {
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
        // テキストの箱は断片から位置を取らない（fitToFragments が飛ばす）ので、祖先の相対配置・sticky のずれだけが乗る。
        // 原点に戻してから足し直させないと、同じ木を配置し直すたびにずれが積み重なる
        if (box.node.nodeType == NodeType.TEXT) {
            box.x = 0;
            box.y = 0;
        }
        for (LayoutBox child : box.children) {
            // 配置を使い回せる箱（原子的インラインの中身など）は、自分の layout が置き直すか前回の配置を残すかを決める。
            // ここで断片だけを消すと、残す側で断片が消えたままになる
            if (child.holdsPlacement()) continue;
            clearFragmentsRecursively(child);
        }
    }

    /** 入れ子のインライン要素を平坦化した 1 要素。 */
    private record FlatRun(Kind kind, LayoutBox box, String text, List<LayoutBox> inlineParents) {
        enum Kind { TEXT, BREAK, ATOMIC, FLOAT, ABSOLUTE, OPEN, CLOSE }
    }
}