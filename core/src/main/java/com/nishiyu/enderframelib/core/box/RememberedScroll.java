package com.nishiyu.enderframelib.core.box;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;

import java.util.Arrays;

/**
 * 流れの外の箱のアンカーの<b>覚えたスクロール量</b>（CSS Anchor Positioning 1 §3.3 の remembered scroll offset。計画書 §3.2 の G8）。
 * アンカーの再計算点で、箱が参照するアンカーの上にある（包含ブロックより下の）スクロールコンテナの量をコンテナごとに写したもの。
 *
 * <p>レイアウトは {@code anchor()} を全てのスクロールコンテナが初期位置にあるものとして解き、この量だけずらす。その後のスクロールは描画の
 * 平行移動で追う（スクロール量そのものはレイアウトの入力にしない）。値を持つのはビュー（{@code V3Frame}）で、再計算点でだけ書き換え、
 * 台帳の {@code ANCHOR} で箱の木へ入る（{@link BoxNode#anchorScroll}）。値等価。</p>
 */
public record RememberedScroll(HtmlNode[] scrollers, float[] offsets) {

    /** スクロールコンテナ {@code scroller} の覚えた量 {x, y}（含まなければ 0）を {@code out} へ足す。 */
    public void addTo(HtmlNode scroller, float[] out) {
        for (int i = 0; i < scrollers.length; i++) {
            if (scrollers[i] == scroller) {
                out[0] += offsets[2 * i];
                out[1] += offsets[2 * i + 1];
                return;
            }
        }
    }

    /** どのコンテナの量も 0 か（初期位置と同じ。箱の木には載せない）。 */
    public boolean zero() {
        for (float o : offsets) if (o != 0f) return false;
        return true;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RememberedScroll r && Arrays.equals(scrollers, r.scrollers) && Arrays.equals(offsets, r.offsets);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(scrollers) * 31 + Arrays.hashCode(offsets);
    }

    @Override
    public String toString() {
        return "RememberedScroll" + Arrays.toString(offsets);
    }
}
