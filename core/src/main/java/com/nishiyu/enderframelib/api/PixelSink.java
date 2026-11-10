package com.nishiyu.enderframelib.api;

import org.jetbrains.annotations.ApiStatus;

/**
 * カスタムホストが描画結果を受け取る窓口。{@link WebViewSurface#frame} が描くたびに、できあがった画素を渡す。
 * ホストはそれをテクスチャ・Swing の {@code BufferedImage}・ゲームのオーバーレイなど好きな先へ転送する。
 */
@FunctionalInterface
@ApiStatus.AvailableSince("1.0")
public interface PixelSink {

    /**
     * @param argbPremultiplied 事前乗算の ARGB、左上原点、{@code width * height} 個（呼び出しの間だけ有効。保持するなら複製する）
     * @param width 画素の幅
     * @param height 画素の高さ
     */
    void accept(int[] argbPremultiplied, int width, int height);
}
