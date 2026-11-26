package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;

/**
 * スクロールコンテナの中のアンカーに合わせて置いた流れの外の箱の、スクロールへの追従の入力（CSS Anchor Positioning 1 §3.3・§6.5。
 * 計画書 §3.2 の G8）。レイアウトの出力で、{@link PlacedFragment#follow} が持つ。スクロール量はレイアウトの入力にしないので、描画の側が
 * 今のスクロール量からこの箱をずらし（default scroll shift）、はみ出したらビューが覚えたスクロール量を取り直す（アンカーの再計算点）。
 *
 * <ul>
 *   <li>{@code scrollers}: 参照したアンカーの上の（包含ブロックより下の）スクロールコンテナ全て。覚えたスクロール量はこれらの今の量を写す。</li>
 *   <li>{@code followed}: 既定のアンカー（{@code position-anchor}・暗黙のアンカー）の上のスクロールコンテナ。ずらす量はこれらの今の量の和と
 *       {@code usedX} / {@code usedY}（レイアウトに使った覚えた量の和）の差。</li>
 *   <li>{@code x} / {@code y}: その軸でずらすか（選んだ候補がその軸で {@code anchor()} か {@code position-area} を使う）。</li>
 *   <li>{@code marginBox} / {@code cb}: 選んだ候補の余白の箱と包含ブロック {左, 上, 右, 下}（包含ブロックの border-box 基準）。ずらした
 *       余白の箱が包含ブロックからはみ出せば、候補を選び直す（{@code retries} のときだけ。候補の判定と同じ規則）。</li>
 * </ul>
 */
public record AnchorScroll(HtmlNode[] scrollers, HtmlNode[] followed, boolean x, boolean y, float usedX, float usedY,
                           float[] marginBox, float[] cb, boolean retries) {
    private static final float OVERFLOW_EPS = 0.05f;

    /** ずらす量が {@code (dx, dy)} のとき、余白の箱が包含ブロックからはみ出すか。 */
    public boolean overflowsAfter(float dx, float dy) {
        return marginBox[0] + dx < cb[0] - OVERFLOW_EPS || marginBox[1] + dy < cb[1] - OVERFLOW_EPS
                || marginBox[2] + dx > cb[2] + OVERFLOW_EPS || marginBox[3] + dy > cb[3] + OVERFLOW_EPS;
    }
}
