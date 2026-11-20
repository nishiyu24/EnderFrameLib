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

    /** 検証モード用の許容（px）。幅だけ違う再利用などで 1 ULP ずれる（既存の比較テストも丸めて比べている）ので、見る粒度は 0.01px。 */
    private static final float VERIFY_TOLERANCE = 0.01f;

    private static boolean near(float a, float b) {
        return a == b || Math.abs(a - b) <= VERIFY_TOLERANCE;
    }

    /** 検証モード用: 自然出力（外寸・余白・content 高さ）が同じか（{@link #VERIFY_TOLERANCE} 以内）。 */
    boolean matchesNatural(MeasureOutputs o) {
        return near(w, o.w) && near(h, o.h) && near(mT, o.mT) && near(mR, o.mR) && near(mB, o.mB) && near(mL, o.mL)
                && near(contentBlockExtent, o.contentBlockExtent);
    }

    /** 検証モード用: padding・border・legend・自身の余白が同じか（sizingMode は親が決める入力なので比べない）。 */
    boolean matchesBox(MeasureOutputs o) {
        return near(pT, o.pT) && near(pR, o.pR) && near(pB, o.pB) && near(pL, o.pL)
                && near(bT, o.bT) && near(bR, o.bR) && near(bB, o.bB) && near(bL, o.bL)
                && near(legendSpill, o.legendSpill)
                && near(ownMarginTop, o.ownMarginTop) && near(ownMarginBottom, o.ownMarginBottom);
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
