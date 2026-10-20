package com.nishiyu.enderframelib.core.layout.model;

import com.nishiyu.enderframelib.core.layout.*;
import com.nishiyu.enderframelib.core.layout.model.*;

/**
 * 親が子の整形に渡す制約（エンジン SPI の入力）。
 *
 * @param availableInlineSize 内容が使える幅（px）。shrink-to-fit の上限でもある
 * @param percentageBlockSize 高さの % を解く基準（px）
 * @param parent              親の整形コンテキスト（flex の方向・揃え・shrink の有無）
 */
public record ConstraintSpace(float availableInlineSize, float percentageBlockSize, FormattingContext parent) {

    /** 通常のブロックフローの子として、幅と高さの基準だけを与える。 */
    public static ConstraintSpace block(float availableInlineSize, float percentageBlockSize) {
        return new ConstraintSpace(availableInlineSize, percentageBlockSize, FormattingContext.BLOCK);
    }
}
