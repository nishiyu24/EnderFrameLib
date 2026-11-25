package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.kernel.LayoutUnit;
import com.nishiyu.enderframelib.core.text.TextShaper;

/**
 * 行を組む間に開いているインライン要素の箱と、行に出したその分（{@link InlineLineBuilder} の作業域。M2c ④ で行数の上限のため分けた）。
 */
final class InlineSpans {
    private InlineSpans() { }

    /** 開いているインライン要素の箱（1 回の整形の間だけの作業域。旧 {@code BlockLayoutEngine.OpenBox}）。 */
    static final class Open {
        final BoxNode box;
        final int depth;
        final BoxGeometry g;
        final byte align;
        final float raise, ascent, descent, lineHeight;
        float start, marginStart;
        boolean startEdge = true, hasContent, reopen;

        Open(BoxNode box, int depth, BoxGeometry g, byte align, float raise, float ascent, float descent, float lineHeight) {
            this.box = box;
            this.depth = depth;
            this.g = g;
            this.align = align;
            this.raise = raise;
            this.ascent = ascent;
            this.descent = descent;
            this.lineHeight = lineHeight;
        }
    }

    /** 今の行に出した箱の分（左右の端は行の左端基準）。 */
    record SpanAt(Open ob, float left, float right, boolean startEdge, boolean endEdge) {
        /** 縦の位置を決めた {@link LineFragment.Span}（行の高さ {@code lineH}・行のベースライン {@code lineBase}）。 */
        LineFragment.Span span(float l, float r, float lineH, float lineBase) {
            float above = TextShaper.leadingAbove(ob.lineHeight, ob.ascent, ob.descent) + ob.ascent; // 自分の行の高さの上端からベースライン
            float baseY = ob.align == VerticalAlign.TOP ? above
                    : ob.align == VerticalAlign.BOTTOM ? lineH - ob.lineHeight + above : lineBase - ob.raise;
            BoxGeometry g = ob.g;
            float top = LayoutUnit.snap(baseY - ob.ascent - g.pT - g.bT);
            float h = LayoutUnit.snap(g.bT + g.pT + ob.ascent + ob.descent + g.pB + g.bB);
            return new LineFragment.Span(ob.box, l, top, Math.max(0, r - l), h, // 横は文字片と同じく丸めない（行の左端と二重に丸めない）
                    LayoutUnit.snap(baseY) - top, startEdge, endEdge);
        }
    }

    /** 入れ子の浅い順に並べる（同じ深さは出した順のまま。挿入整列: 1 行の箱は数個）。 */
    static void sortByDepth(LineFragment.Span[] spans, int[] depth) {
        for (int i = 1; i < spans.length; i++) {
            LineFragment.Span v = spans[i];
            int d = depth[i], j = i - 1;
            while (j >= 0 && depth[j] > d) {
                spans[j + 1] = spans[j];
                depth[j + 1] = depth[j];
                j--;
            }
            spans[j + 1] = v;
            depth[j + 1] = d;
        }
    }
}
