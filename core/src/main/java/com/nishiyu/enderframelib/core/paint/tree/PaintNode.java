package com.nishiyu.enderframelib.core.paint.tree;

import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.box.Display;
import com.nishiyu.enderframelib.core.layout.fragment.LayoutFragment;

/**
 * 描画用の木の節（M2c ②-2。計画書 §3.4.2-4）。箱 1 つを描くのに要る入力（最新の箱・断片・寸法・縁・行の断片・子）を持つ不変の値で、
 * {@link PaintTreeBuilder} が断片木と最新の木から作る。
 *
 * <p><b>位置は持たない</b>: 子の位置（原点の箱の左上から。旧 {@code LayoutBox#x} と同じ約束）は親の {@link #childX} / {@link #childY} が
 * 持つ。前の兄弟が伸びて動いただけの箱は、節がそのまま（{@code ==}）使い回される。絶対位置と親は描く間だけ作る {@link PaintView} が
 * 結び付ける。</p>
 *
 * <p>ブロック水準の節（{@link #frag} が非 null）は {@code (frag ==, box ==)} が前回と同じなら前回の節を使う。インライン要素・文字の
 * 節は持ち主のブロックの行から作るので、持ち主と一緒に作り直す。</p>
 */
public final class PaintNode {
    static final PaintNode[] NO_CHILDREN = new PaintNode[0];
    static final TreeRun[] NO_RUNS = new TreeRun[0];
    static final float[] NO_POS = new float[0];

    /** 最新の木の箱（最新の全ての群を持つ。{@link BoxNode#layout} の側ではない）。 */
    public final BoxNode box;
    /** ブロック水準の節なら自分の断片、インライン要素・文字の節なら null。 */
    public final LayoutFragment frag;
    /** 描画の節（疑似要素・匿名の箱・直交フローの包む箱では使い捨ての合成の節）。 */
    public final HtmlNode node;
    /** 描画が読む算出スタイル（最新の木の箱のレイアウトのスタイル。縦書きの文脈では軸を入れ替えたもの）。 */
    public final ComputedStyle style;
    final Display display;
    final boolean anonymous, verticalWrapper, renderedLegend, text;
    final float w, h, mT, mR, mB, mL, pT, pR, pB, pL, bT, bR, bB, bL;
    final float[] collapsedBorders;
    final float scrollWidth, scrollHeight, lineHeight;
    /** ブロックコンテナの {@code ::first-line} のスタイルと、1 行目の背景を敷く矩形（箱の左上基準の {x, y, 幅, 高さ}。無ければ null）。 */
    final ComputedStyle firstLine;
    final float[] firstLineBgRect;
    /** 状態の擬似クラスの規則が掛かる要素か（背景が透明でも当たり判定の項目を出す）。 */
    final boolean interactive;
    final TreeRun[] runs;
    final PaintNode[] children;
    final float[] childX, childY;
    /** 匿名の箱だけを辿って届く匿名の箱の数（自分を含む。匿名でなければ 0）。合成の節の番号を前回と揃えるのに使う。 */
    final int anonChain;

    PaintNode(PaintTreeBuilder.Draft d, PaintNode[] children, float[] childX, float[] childY) {
        this.box = d.box;
        this.frag = d.frag;
        this.node = d.node;
        this.style = d.style;
        this.display = d.display;
        this.anonymous = d.anonymous;
        this.verticalWrapper = d.verticalWrapper;
        this.renderedLegend = d.renderedLegend;
        this.text = d.text;
        this.w = d.w;
        this.h = d.h;
        this.mT = d.mT;
        this.mR = d.mR;
        this.mB = d.mB;
        this.mL = d.mL;
        this.pT = d.pT;
        this.pR = d.pR;
        this.pB = d.pB;
        this.pL = d.pL;
        this.bT = d.bT;
        this.bR = d.bR;
        this.bB = d.bB;
        this.bL = d.bL;
        this.collapsedBorders = d.collapsedBorders;
        this.scrollWidth = d.scrollWidth;
        this.scrollHeight = d.scrollHeight;
        this.lineHeight = d.lineHeight;
        this.firstLine = d.firstLine;
        this.firstLineBgRect = d.firstLineBgRect;
        this.interactive = d.interactive;
        this.runs = d.runs.isEmpty() ? NO_RUNS : d.runs.toArray(NO_RUNS);
        this.children = children;
        this.childX = childX;
        this.childY = childY;
        int chain = 0;
        if (anonymous) {
            chain = 1;
            for (PaintNode c : children) chain += c.anonChain;
        }
        this.anonChain = chain;
    }

    /** 子の数。 */
    public int childCount() {
        return children.length;
    }

    /** {@code i} 番目の子。 */
    public PaintNode child(int i) {
        return children[i];
    }

    @Override
    public String toString() {
        return "PaintNode[" + node.key() + " " + w + "x" + h + " kids=" + children.length + "]";
    }
}
