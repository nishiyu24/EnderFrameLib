package com.nishiyu.enderframelib.core.paint.model;

import java.util.List;

/**
 * 構築後に変更されない不変の {@link DisplayItem} 列であることを示すマーカー（S7 ③）。
 * フレームの値（{@code RenderFrame#snapshot}）がフレーム境界を越える際、この実装に対しては O(1) で防御コピーを省ける。
 */
public interface FrozenItemList extends List<DisplayItem> {}
