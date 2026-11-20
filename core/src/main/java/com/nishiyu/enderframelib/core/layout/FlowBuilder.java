package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.CssPropertyIndex;
import com.nishiyu.enderframelib.core.kernel.CompactList;
import com.nishiyu.enderframelib.core.kernel.layout.FenwickVirtualList;
import com.nishiyu.enderframelib.core.text.ParagraphSegments;
import com.nishiyu.enderframelib.core.layout.model.*;

import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.layout.LayoutContext;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.model.CssKeywords;
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

import com.nishiyu.enderframelib.core.css.model.Overflow;
import com.nishiyu.enderframelib.core.css.model.TextAlign;
import com.nishiyu.enderframelib.core.css.model.BorderStyle;

/** A8 で {@link BlockLayoutEngine} の入れ子のクラスから最上位のクラスへ移した。 */
final class FlowBuilder {
    final BlockLayoutEngine.BlockScratch scratch;
    LayoutBox box;
    /** 流す子。段組は区間ごとに分けた子を渡す。 */
    List<LayoutBox> children;
    LayoutContext ctx;
    BlockLayoutEngine.FlowState state;
    FloatSpace floats;
    /** この箱の内容ボックスの原点の、float 領域の中での位置。 */
    float ox, oy;
    float innerW;
    float childPercentBase;
    FormattingContext childCtx;
    boolean collapseTop;
    /** この箱が {@code direction: rtl} か。ブロックの子の寄せ先と行の揃えが右始まりになる。 */
    boolean rtl;

    /** 次の箱の前で 1 つに畳まれるのを待っているマージン（正の最大・負の最小）。 */
    float pendingPos = 0, pendingNeg = 0;
    /** 親（この箱）の上マージンと畳まれるマージン（正の最大・負の最小）。 */
    float topPos = 0, topNeg = 0;
    /** マージンの相殺を断つもの（行・高さのある箱）がもう置かれたか。 */
    boolean hasContent = false;
    float cursor = 0;
    boolean firstRun = true;

    FlowBuilder(BlockLayoutEngine.BlockScratch scratch) {
        this.scratch = scratch;
    }

    FlowBuilder init(LayoutBox box, ConstraintSpace space, LayoutContext ctx, List<LayoutBox> children,
                     float innerWOverride, BlockLayoutEngine.FlowState state) {
        state.reset();
        this.state = state;
        this.box = box;
        this.ctx = ctx;
        this.children = children;
        this.innerW = Float.isNaN(innerWOverride) ? Math.max(0, box.w - box.horizontalDecoration()) : innerWOverride;
        this.rtl = box.style.layoutFacts().rtl;
        if (!box.establishesBfc && box.floatSpace != null) {
            floats = box.floatSpace;
            ox = box.floatSpaceX;
            oy = box.floatSpaceY;
        } else {
            floats = BlockLayoutEngine.EMPTY_FLOAT_SPACE;
            ox = 0;
            oy = 0;
        }
        box.floatSpace = null;
        this.childPercentBase = BlockLayoutEngine.childPercentageBase(box, space.percentageBlockSize(), ctx);
        this.childCtx = FormattingContext.of(FormattingContext.Display.BLOCK, true, box.sizingMode == SizingMode.SHRINK_TO_FIT);
        this.collapseTop = BlockLayoutEngine.collapsesThroughTop(box);
        this.pendingPos = 0;
        this.pendingNeg = 0;
        this.topPos = 0;
        this.topNeg = 0;
        this.hasContent = false;
        this.cursor = 0;
        this.firstRun = true;
        return this;
    }

    void clearRefs() {
        this.box = null;
        this.children = null;
        this.ctx = null;
        this.state = null;
        this.floats = null;
        this.childCtx = null;
    }

    void addPending(float margin) {
        if (margin > 0) pendingPos = Math.max(pendingPos, margin);
        else pendingNeg = Math.min(pendingNeg, margin);
    }

    void clearPending() {
        pendingPos = 0;
        pendingNeg = 0;
    }

    float pendingResolve() {
        return pendingPos + pendingNeg;
    }

    float pendingWith(float margin) {
        return (margin > 0 ? Math.max(pendingPos, margin) : pendingPos)
                + (margin <= 0 ? Math.min(pendingNeg, margin) : pendingNeg);
    }

    void addTop(float margin) {
        if (margin > 0) topPos = Math.max(topPos, margin);
        else topNeg = Math.min(topNeg, margin);
    }

    float topWith(float margin) {
        return (margin > 0 ? Math.max(topPos, margin) : topPos)
                + (margin <= 0 ? Math.min(topNeg, margin) : topNeg);
    }

    BlockLayoutEngine.FlowState build() {
        state.innerW = innerW;
        List<LayoutBox> run = scratch.run;
        run.clear();
        for (int i = 0, n = children.size(); i < n; i++) {
            LayoutBox child = children.get(i);
            if ("none".equals(child.style.get(CssPropertyIndex.DISPLAY)) || child.isOutsideMarker || child.isRenderedLegend) continue;
            if (child.isAbsolute) {
                // 行の中の絶対配置は、行を組むときにその位置を静的位置として記録する
                if (!run.isEmpty()) run.add(child);
                else state.items.add(new BlockLayoutEngine.StaticPosition(child, 0, cursor + pendingResolve()));
                continue;
            }
            if (BlockLayoutEngine.isInlineBox(child) || (BlockLayoutEngine.isFloating(child) && !run.isEmpty())) {
                run.add(child);
                continue;
            }
            if (!run.isEmpty()) flushRun(run);
            if (BlockLayoutEngine.isFloating(child)) {
                placeFloat(child, cursor + (hasContent || !collapseTop ? pendingResolve() : 0));
            } else {
                placeBlock(child);
            }
        }
        if (!run.isEmpty()) flushRun(run);

        if (BlockLayoutEngine.collapsesThroughBottom(box) && (hasContent || !collapseTop)) {
            box.mB = pendingWith(box.ownMarginBottom);
        } else {
            cursor += pendingResolve();
        }
        if (collapseTop) box.mT = topWith(box.ownMarginTop);

        float height = cursor;
        // BFC は自分の中の float を高さに含める（CSS 2.2 §10.6.7）。通常のブロックは含めない
        if (box.establishesBfc) height = Math.max(height, floats.bottom() - oy);
        state.height = height;
        return state;
    }

    void placeBlock(LayoutBox child) {
        boolean intoParentTop = !hasContent && collapseTop;
        if (!child.establishesBfc && LayoutEngine.getEngine(child.display) instanceof BlockLayoutEngine
                && (!floats.isEmpty() || BlockLayoutEngine.containsFloats(child))) {
            // BFC を作らない子は同じ float 領域の中で組み直す（float を避け、自分の float を置く）。
            // 高さ 0 で自分を貫通する箱の中の float も親の領域に入る
            boolean hadIncomingFloats = !floats.isEmpty();
            if (floats == BlockLayoutEngine.EMPTY_FLOAT_SPACE) floats = new FloatSpace();
            float y = clearedY(child, intoParentTop ? cursor : cursor + pendingWith(child.mT));
            child.floatSpace = floats;
            child.floatSpaceX = ox + child.mL + child.bL + child.pL;
            child.floatSpaceY = oy + y + child.bT + child.pT;
            child.h = 0;
            BoxMeasurer.resolveFlexibleHeights(child, new ConstraintSpace(innerW, childPercentBase, childCtx), ctx, hadIncomingFloats);
        }
        if (BlockLayoutEngine.collapsesThrough(child) && !clearsFloats(child)) {
            // 上端の位置は「下にボーダーがあったら置かれた位置」（CSS 2.2 §8.3.1）
            float y;
            if (intoParentTop) {
                addTop(child.mT);
                addTop(child.mB);
                y = cursor;
            } else {
                y = cursor + pendingWith(child.mT);
                addPending(child.mT);
                addPending(child.mB);
            }
            state.items.add(new BlockLayoutEngine.Placement(child, 0, y - child.mT));
            return;
        }

        float y;
        if (intoParentTop) {
            addTop(child.mT);
            y = cursor;
        } else {
            addPending(child.mT);
            y = cursor + pendingResolve();
        }
        clearPending();

        y = clearedY(child, y);

        float x = 0;
        if (child.establishesBfc && !floats.isEmpty()) {
            // BFC の border box は float と重ならない。横の残り幅に入らなければ float の下へ下げる
            boolean autoWidth = "auto".equals(child.axisStyle().getOrDefault(CssPropertyIndex.WIDTH, "auto").trim());
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

        if (floats.isEmpty()) x = startOffsetForDirection(child);
        state.items.add(new BlockLayoutEngine.Placement(child, x, y - child.mT));
        cursor = y + child.h;
        addPending(child.mB);
        hasContent = true;
    }

    /**
     * {@code direction: rtl} のブロックの子の横位置（マージンボックスの左端、内容ボックス相対）。
     * 幅とマージンが決まって余りが出るとき（over-constrained、CSS 2.2 §10.3.3）、ltr は右のマージンが余りを受けて
     * 子は左に寄るが、rtl は左のマージンが受けて子は右に寄る。右マージンが {@code auto} なら余りはそちらが受ける。
     */
    float startOffsetForDirection(LayoutBox child) {
        if (!rtl || child.isAbsolute) return 0;
        if ("auto".equals(child.axisStyle().getOrDefault(CssPropertyIndex.MARGIN_RIGHT, "").trim())) return 0;
        return Math.max(0, innerW - (child.w + child.mL + child.mR));
    }

    /**
     * {@code clear} が float の下へ下げる（クリアランスが付く）か。クリアランスを持つ空の箱はマージンを突き抜けず、
     * float の下に置かれて後続と親の高さを押し下げる（CSS 2.2 §9.5.2。clearfix の {@code ::after} はこれに頼る）。
     */
    boolean clearsFloats(LayoutBox child) {
        if (floats.isEmpty()) return false;
        CssKeywords.Clear clear = child.style.keywordOf(CssPropertyIndex.CLEAR, CssKeywords.Clear.class);
        return clear != CssKeywords.Clear.NONE && floats.clearance(clear) - oy > cursor + pendingWith(child.mT);
    }

    /** {@code clear} を持つ箱を、その側の float の下端まで下げた位置。 */
    float clearedY(LayoutBox child, float y) {
        CssKeywords.Clear clear = child.style.keywordOf(CssPropertyIndex.CLEAR, CssKeywords.Clear.class);
        if (clear == CssKeywords.Clear.NONE || floats.isEmpty()) return y;
        return Math.max(y, floats.clearance(clear) - oy);
    }

    /** float を置き、領域に登録する。{@code minY} は置ける最も上の位置（内容ボックス相対）。 */
    void placeFloat(LayoutBox f, float minY) {
        if (floats == BlockLayoutEngine.EMPTY_FLOAT_SPACE) floats = new FloatSpace();
        CssKeywords.FloatSide side = f.style.keywordOf(CssPropertyIndex.FLOAT, CssKeywords.FloatSide.class);
        boolean left = side != CssKeywords.FloatSide.RIGHT && side != CssKeywords.FloatSide.INLINE_END;
        float w = f.w + f.mL + f.mR;
        float h = f.h + f.mT + f.mB;
        // float の上端は先に置いた float より上にはならない（CSS 2.2 §9.5.1 の規則 5）
        float y = Math.max(oy + minY, floats.lastTop());
        CssKeywords.Clear clear = f.style.keywordOf(CssPropertyIndex.CLEAR, CssKeywords.Clear.class);
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
        state.items.add(new BlockLayoutEngine.Placement(f, x - ox, y - oy));
    }

    void flushRun(List<LayoutBox> run) {
        if (run.isEmpty()) return;
        float y;
        if (!hasContent && collapseTop) {
            topPos = Math.max(topPos, pendingPos);
            topNeg = Math.min(topNeg, pendingNeg);
            y = cursor;
        } else {
            y = cursor + pendingResolve();
        }
        clearPending();

        // text-indent は要素の最初の行だけ。匿名ブロックは持ち主の最初の子のときだけ字下げする
        float indent = 0;
        if (firstRun && (!box.isAnonymous || BlockLayoutEngine.isFirstFlowChild(box))) {
            indent = box.style.length(BlockLayoutEngine.ID_TEXT_INDENT, innerW, ctx.viewport().rootW(), ctx.viewport().rootH());
        }
        firstRun = false;

        // 組むための写しと LineRun が持つ写しは同じ中身で、どちらも読むだけ（build は引数を書き換えない）
        List<LayoutBox> boxes = run.size() == 1 ? List.of(run.get(0)) : List.copyOf(run);
        List<InlineLine> result;
        scratch.line.init(this, y, indent, !hasContent);
        scratch.line.balanceShrink = 0;
        try {
            result = scratch.line.build(boxes);
        } finally {
            scratch.line.clearRefs();
        }
        if (result.size() > 1 && result.size() <= 10 && BlockLayoutEngine.isBalanced(box.style) && BlockLayoutEngine.balanceable(boxes)) {
            result = balanceLines(boxes, result, y, indent);
        }
        state.items.add(new BlockLayoutEngine.LineRun(boxes, result));
        if (!result.isEmpty()) {
            InlineLine last = result.getLast();
            cursor = last.top() + last.height();
            hasContent = true;
        }
        run.clear();
    }

    /**
     * text-wrap: balance（CSS Text 4 §6.1）: 行数を変えずに、折り返しの判定に使う幅をできるだけ狭めて組み直す（行の長さが揃う）。
     * 行の箱の幅と揃えは元のまま。狭める量は二分探索で 1px 単位まで。
     */
    List<InlineLine> balanceLines(List<LayoutBox> boxes, List<InlineLine> first, float y, float indent) {
        int lines = first.size();
        float lo = 0, hi = innerW;
        List<InlineLine> best = first;
        for (int iter = 0; iter < 12 && hi - lo > 1f; iter++) {
            float mid = (lo + hi) / 2;
            List<InlineLine> trial = buildWithShrink(boxes, y, indent, mid);
            if (trial.size() == lines) {
                lo = mid;
                best = trial;
            } else {
                hi = mid;
            }
        }
        return best;
    }

    List<InlineLine> buildWithShrink(List<LayoutBox> boxes, float y, float indent, float shrink) {
        scratch.line.init(this, y, indent, !hasContent);
        scratch.line.balanceShrink = shrink;
        try {
            return scratch.line.build(boxes);
        } finally {
            scratch.line.balanceShrink = 0;
            scratch.line.clearRefs();
        }
    }
}
