package com.nishiyu.enderframelib.core.paint;

/** 画像のパスからヒットテスト用の 1 ビット不透明マスクを引く差し込み口（ホストが画像の読み方を知っている）。未読み込み・不明なら null（矩形で判定する）。 */
@FunctionalInterface
public interface AlphaMaskProvider {
    AlphaMask.Source maskFor(String src, int boxW, int boxH, int threshold);
}
