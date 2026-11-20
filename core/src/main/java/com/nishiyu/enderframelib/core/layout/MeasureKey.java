package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;

/**
 * 測定の記憶を引く不変の鍵（制約空間＋親の上書き寸法＋サイジングモード）。
 */
record MeasureKey(
        ConstraintSpace space,
        float overrideW,
        float overrideH,
        LayoutBox.SizingMode sizingMode
) {
    boolean matchesInput(ConstraintSpace s, float ow, float oh) {
        return java.util.Objects.equals(space, s)
                && MeasureAnalysis.sameLength(overrideW, ow)
                && MeasureAnalysis.sameLength(overrideH, oh);
    }
}
