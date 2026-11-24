package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxNode;

/**
 * 不変のレイアウト出力（Engine v3 M2a）。{@code (BoxNode, ConstraintSpace) → LayoutFragment} の純粋な変換の結果で、
 * 作った後は一切書き換えない。一時的な通信欄（旧 {@code overrideWidth}・{@code floatSpace}）は持たない。
 *
 * <p>入力の {@link #box} と {@link #space} を自分で持つので、断片がそのままキャッシュのスロットになる
 * （{@code box == 子 && space.equals(制約)} なら使い回せる）。</p>
 *
 * <p>余白の相殺: 親と相殺できる上下の余白は、正の最大と負の最小の組（margin strut）として外へ出す。
 * 親はこれを兄弟の間・自分の上下と組み合わせる（{@link BlockFlow}）。</p>
 */
public final class LayoutFragment {
    /** 元の箱（匿名のインラインの塊は null）。 */
    public final BoxNode box;
    /** この断片を作った制約。 */
    public final ConstraintSpace space;
    /** border-box の寸法。 */
    public final float width, height;
    /** ブロックの流れでの左の余白（auto の中央寄せを解いた後）。 */
    public final float marginLeft;
    /** 外へ出す上・下の余白（正の最大・負の最小）。親と相殺しない箱では自分の余白だけ。 */
    public final float topPos, topNeg, bottomPos, bottomNeg;
    /** 内容ボックスの左上（border-box の左上から。パディング＋枠線）。 */
    public final float insetLeft, insetTop;
    /** 高さ 0 で上下の余白が素通しに相殺される箱か。 */
    public final boolean collapseThrough;
    /** 子の断片（ブロック・flex の子。無ければ null）。 */
    public final ChunkedFlow flow;
    /** インライン整形の行（無ければ null）。 */
    public final LineFragment[] lines;

    LayoutFragment(BoxNode box, ConstraintSpace space, float width, float height, float marginLeft,
                   float topPos, float topNeg, float bottomPos, float bottomNeg, float insetLeft, float insetTop,
                   boolean collapseThrough, ChunkedFlow flow, LineFragment[] lines) {
        this.box = box;
        this.space = space;
        this.width = width;
        this.height = height;
        this.marginLeft = marginLeft;
        this.topPos = topPos;
        this.topNeg = topNeg;
        this.bottomPos = bottomPos;
        this.bottomNeg = bottomNeg;
        this.insetLeft = insetLeft;
        this.insetTop = insetTop;
        this.collapseThrough = collapseThrough;
        this.flow = flow;
        this.lines = lines;
    }

    /** この制約で作った、この箱の断片か（キャッシュの照合。{@code ==} の高速路つきの値等価）。 */
    public boolean matches(BoxNode b, ConstraintSpace s) {
        return box == b && (space == s || space.equals(s));
    }

    @Override
    public String toString() {
        return "Fragment[" + box + " " + width + "x" + height + "]";
    }
}
