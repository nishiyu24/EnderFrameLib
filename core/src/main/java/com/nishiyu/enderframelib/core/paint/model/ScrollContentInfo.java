package com.nishiyu.enderframelib.core.paint.model;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;

/**
 * スクロールコンテナの中身を、表示リストの上で 1 つの節（{@code contentId}）の下にまとめたときの記録。
 * スクロールだけが変わったときは、表示リストを作り直さず、この節の移動量だけを {@code -(今のスクロール - sx0, sy0)} にすればよい
 * （ブラウザのコンポジタのスクロールと同じ）。つまみは線形に動く: 移動量は {@code kx * Δx} / {@code ky * Δy}。
 *
 * @param node      スクロールするコンテナの節
 * @param contentId 中身の節の項目 id
 * @param thumbVId  縦のつまみの節の項目 id。つまみが無ければ null
 * @param thumbHId  横のつまみの節の項目 id。つまみが無ければ null
 * @param sx0       表示リストを作ったときの横のスクロール量
 * @param sy0       同じく縦
 * @param kx        横のスクロール 1px あたりの、横のつまみの移動量
 * @param ky        縦のスクロール 1px あたりの、縦のつまみの移動量
 * @param fx0       作ったときの横のつまみの位置の丸めの端数（位置 - round(位置)）。つまみの移動量を round(fx0 + kx * Δx) にすると、
 *                  作り直したときの round(位置 + kx * Δx) と同じ整数の位置になる
 * @param fy0       同じく縦
 * @param stickies  中身の中の sticky の箱（上の inset だけ）。スクロールだけの更新で、節に貼り付きのずれの増減を足す（B12）。無ければ空
 */
public record ScrollContentInfo(HtmlNode node, String contentId, String thumbVId, String thumbHId,
                                float sx0, float sy0, float kx, float ky, float fx0, float fy0,
                                java.util.List<StickyShift> stickies) {

    public ScrollContentInfo {
        stickies = stickies == null ? java.util.List.of() : java.util.List.copyOf(stickies);
    }

    public ScrollContentInfo(HtmlNode node, String contentId, String thumbVId, String thumbHId,
                             float sx0, float sy0, float kx, float ky, float fx0, float fy0) {
        this(node, contentId, thumbVId, thumbHId, sx0, sy0, kx, ky, fx0, fy0, java.util.List.of());
    }
}
