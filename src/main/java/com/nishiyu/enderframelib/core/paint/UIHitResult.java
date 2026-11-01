package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.model.DisplayItem;

/** ヒットテストの結果。要素と、その左上を原点にした局所座標。 */
public record UIHitResult(DisplayItem element, double localX, double localY) {}
