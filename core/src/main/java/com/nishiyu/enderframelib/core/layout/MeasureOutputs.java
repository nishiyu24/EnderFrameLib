package com.nishiyu.enderframelib.core.layout;

/**
 * 測定結果の自然出力の不変値（A7）。
 *
 * <p>記録と同じ実体を共有して後から書き換えてしまう不具合（§6-20）を型で防ぐため、
 * すべての欄を {@code final} とした {@code record} にする。</p>
 */
record MeasureOutputs(
        float w, float h,
        float mT, float mR, float mB, float mL,
        float pT, float pR, float pB, float pL,
        float bT, float bR, float bB, float bL,
        float legendSpill, float contentBlockExtent,
        float ownMarginTop, float ownMarginBottom,
        LayoutBox.SizingMode sizingMode
) {
    static MeasureOutputs capture(LayoutBox b) {
        return new MeasureOutputs(
                b.w, b.h, b.mT, b.mR, b.mB, b.mL,
                b.pT, b.pR, b.pB, b.pL, b.bT, b.bR, b.bB, b.bL,
                b.legendSpill, b.contentBlockExtent, b.ownMarginTop, b.ownMarginBottom, b.sizingMode);
    }

    void apply(LayoutBox b) {
        b.w = w; b.h = h; b.mT = mT; b.mR = mR; b.mB = mB; b.mL = mL;
        b.pT = pT; b.pR = pR; b.pB = pB; b.pL = pL; b.bT = bT; b.bR = bR; b.bB = bB; b.bL = bL;
        b.legendSpill = legendSpill; b.contentBlockExtent = contentBlockExtent;
        b.ownMarginTop = ownMarginTop; b.ownMarginBottom = ownMarginBottom; b.sizingMode = sizingMode;
    }

    boolean matchesOuter(LayoutBox b) {
        return b.w == w && b.h == h && b.mT == mT && b.mR == mR && b.mB == mB && b.mL == mL;
    }

    boolean matchesNatural(LayoutBox b) {
        return matchesOuter(b) && b.contentBlockExtent == contentBlockExtent;
    }

    boolean matchesAll(LayoutBox b) {
        return matchesNatural(b)
                && b.pT == pT && b.pR == pR && b.pB == pB && b.pL == pL
                && b.bT == bT && b.bR == bR && b.bB == bB && b.bL == bL
                && b.legendSpill == legendSpill
                && b.ownMarginTop == ownMarginTop && b.ownMarginBottom == ownMarginBottom
                && b.sizingMode == sizingMode;
    }
}
