package com.nishiyu.enderframelib.core.layout.model;

import com.nishiyu.enderframelib.core.text.model.FontResolver;

/**
 * 測定の記憶が暗黙に依存し得る外部世代・解決器の組（C3 ①）。
 *
 * <p>{@code MeasureKey} の一部として保持し、全体の測定世代・float 木の形世代・書体解決器とその世代・
 * 固有サイズ解決器とその世代のどれかが進んだ測定の使い回しを型と等価比較で防ぐ。</p>
 */
public record ExternalEpochs(
        int measurementEpoch,
        int shapeEpoch,
        FontResolver fonts,
        long fontGeneration,
        IntrinsicSizeResolver intrinsics,
        long intrinsicGeneration
) {
    public boolean matchesExact(ExternalEpochs other) {
        return other != null && matchesExact(other.measurementEpoch, other.shapeEpoch,
                other.fonts, other.fontGeneration, other.intrinsics, other.intrinsicGeneration);
    }

    /** すべての外部世代・解決器が完全に一致するか（鍵インスタンスの再利用判定用）。 */
    public boolean matchesExact(int measurementEpoch, int shapeEpoch,
                                FontResolver fonts, long fontGeneration,
                                IntrinsicSizeResolver intrinsics, long intrinsicGeneration) {
        return this.measurementEpoch == measurementEpoch
                && this.shapeEpoch == shapeEpoch
                && this.fonts == fonts
                && this.fontGeneration == fontGeneration
                && this.intrinsics == intrinsics
                && this.intrinsicGeneration == intrinsicGeneration;
    }

    /** 読み取り集合に応じて、必要な外部世代が今の値と一致するか（{@code checkShape} は {@code READ_SHAPE_EPOCH} のときだけ）。 */
    public boolean matches(int curMeasurementEpoch, int curShapeEpoch, boolean checkShape,
                           FontResolver curFonts, long curFontGen,
                           IntrinsicSizeResolver curIntrinsics, long curIntrinsicGen) {
        return measurementEpoch == curMeasurementEpoch
                && (!checkShape || shapeEpoch == curShapeEpoch)
                && fonts == curFonts
                && fontGeneration == curFontGen
                && intrinsics == curIntrinsics
                && intrinsicGeneration == curIntrinsicGen;
    }
}
