package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.kernel.LayoutUnit;
import com.nishiyu.enderframelib.core.text.TextShaper;

import java.util.List;

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

    /**
     * 行の原子の位置（{@code aPos} は {x, ascent, marginTop, 揃え, 外側の高さ}。x は両端揃えの後の {@code atomX}）。箱の位置は
     * LayoutUnit の格子。
     */
    static PlacedFragment[] atoms(List<LayoutFragment> aFrag, List<float[]> aPos, float[] atomX, float lineH, float asc) {
        if (aFrag.isEmpty()) return LineFragment.NO_ATOMS;
        PlacedFragment[] atoms = new PlacedFragment[aFrag.size()];
        for (int i = 0; i < atoms.length; i++) {
            float[] p = aPos.get(i);
            float ay = p[3] == VerticalAlign.TOP ? p[2] : p[3] == VerticalAlign.BOTTOM ? lineH - p[4] + p[2] : asc - p[1] + p[2];
            atoms[i] = new PlacedFragment(aFrag.get(i), LayoutUnit.snap(atomX[i]), LayoutUnit.snap(ay));
        }
        return atoms;
    }

    /** 行に出した箱の分を、縦の位置を決めて外側の箱が先（描く順）に並べる（左右の端は {@code atomX} の {@code na} 番目から）。 */
    static LineFragment.Span[] spans(List<SpanAt> spanAt, float[] atomX, int na, float lineH, float asc) {
        int ns = spanAt.size();
        if (ns == 0) return LineFragment.NO_SPANS;
        LineFragment.Span[] spans = new LineFragment.Span[ns];
        int[] depth = new int[ns];
        for (int i = 0; i < ns; i++) {
            spans[i] = spanAt.get(i).span(atomX[na + 2 * i], atomX[na + 2 * i + 1], lineH, asc);
            depth[i] = spanAt.get(i).ob().depth;
        }
        sortByDepth(spans, depth);
        return spans;
    }

    /** 今の行の箱に、余白・枠線・padding を持つものがあるか（中身の無い行でも、そういう箱があれば行を作る。CSS 2.2 §9.4.2）。 */
    static boolean decorated(List<SpanAt> spanAt) {
        for (SpanAt a : spanAt) {
            BoxGeometry g = a.ob().g;
            if (g.mL != 0 || g.mR != 0 || g.bL + g.pL + g.pR + g.bR != 0 || g.bT + g.pT + g.pB + g.bB != 0) return true;
        }
        return false;
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
