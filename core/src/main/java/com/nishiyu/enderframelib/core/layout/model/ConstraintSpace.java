package com.nishiyu.enderframelib.core.layout.model;

import com.nishiyu.enderframelib.core.layout.*;
import com.nishiyu.enderframelib.core.layout.model.*;

/**
 * 親が子の整形に渡す制約（エンジン SPI の入力）。測定の結果は、この制約と箱自身のスタイル・中身だけで決まる。
 *
 * @param availableInlineSize 内容が使える幅（px）。shrink-to-fit の上限でもある
 * @param percentageBlockSize 高さの % を解く基準（px）
 * @param parent              親の整形コンテキスト（flex の方向・揃え・shrink の有無）
 * @param fixedInlineSize     親が決めた箱の幅（flex の伸縮・表の列幅など）。NaN なら箱自身が決める
 * @param fixedBlockSize      親が決めた箱の高さ（stretch・グリッドの領域）。NaN なら箱自身が決める
 */
public record ConstraintSpace(float availableInlineSize, float percentageBlockSize, FormattingContext parent,
                              float fixedInlineSize, float fixedBlockSize) {

    /** 親が寸法を決めない制約。 */
    public ConstraintSpace(float availableInlineSize, float percentageBlockSize, FormattingContext parent) {
        this(availableInlineSize, percentageBlockSize, parent, Float.NaN, Float.NaN);
    }

    /**
     * 直前に作った制約と同じ値なら、それを返す（同じ箱を何度も測る再レイアウトで、制約オブジェクトを確保し直さない）。
     * 値の比較は NaN も一致とみなすビット比較。
     */
    public static ConstraintSpace reuseOrCreate(ConstraintSpace prev, float availableInlineSize,
                                                float percentageBlockSize, FormattingContext parent) {
        if (prev != null && prev.parent == parent
                && Float.floatToIntBits(prev.availableInlineSize) == Float.floatToIntBits(availableInlineSize)
                && Float.floatToIntBits(prev.percentageBlockSize) == Float.floatToIntBits(percentageBlockSize)
                && Float.isNaN(prev.fixedInlineSize) && Float.isNaN(prev.fixedBlockSize)) {
            return prev;
        }
        return new ConstraintSpace(availableInlineSize, percentageBlockSize, parent);
    }

    /** 通常のブロックフローの子として、幅と高さの基準だけを与える。 */
    public static ConstraintSpace block(float availableInlineSize, float percentageBlockSize) {
        return new ConstraintSpace(availableInlineSize, percentageBlockSize, FormattingContext.BLOCK);
    }

    /** 親が箱の幅と高さを決めた制約（どちらかが NaN なら、その方向は箱自身が決める）。 */
    public ConstraintSpace withFixed(float inlineSize, float blockSize) {
        return new ConstraintSpace(availableInlineSize, percentageBlockSize, parent, inlineSize, blockSize);
    }
}
