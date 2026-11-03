package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.model.DisplayItem;

/** 画像の項目のマスクを引く差し込み口（ホストが画像の読み方を知っている）。未読み込み・不明なら null（矩形で判定する）。 */
@FunctionalInterface
public interface AlphaMaskProvider {
    AlphaMask.Source maskFor(DisplayItem item, int threshold);
}
