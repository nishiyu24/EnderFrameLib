package com.nishiyu.enderframelib.core.raster;

/**
 * Canvas 2D の {@code TextMetrics}。すべて {@code textAlign} / {@code textBaseline} が決める位置（呼び出しの {@code x, y}）を
 * 原点にした値で、横は右向き・縦は上向きが正（{@code actualBoundingBoxLeft} は左向きが正）。
 *
 * @param actualBoundingBoxAscent  字の墨（輪郭）の上端（原点から上）
 * @param actualBoundingBoxDescent 字の墨の下端（原点から下）
 * @param emHeightAscent           em の箱の上端（原点から上）
 * @param emHeightDescent          em の箱の下端（原点から下）
 * @param hangingBaseline          ぶら下がりベースライン（原点から上）
 * @param alphabeticBaseline       欧文のベースライン（原点から上）
 * @param ideographicBaseline      表意文字のベースライン（原点から上）
 */
public record TextMetrics(double width, double actualBoundingBoxLeft, double actualBoundingBoxRight,
                          double fontBoundingBoxAscent, double fontBoundingBoxDescent,
                          double actualBoundingBoxAscent, double actualBoundingBoxDescent,
                          double emHeightAscent, double emHeightDescent,
                          double hangingBaseline, double alphabeticBaseline, double ideographicBaseline) {

    /** 墨の範囲を持たない（箱 = 送り幅・フォントの上下で近似する）形。 */
    public TextMetrics(double width, double actualBoundingBoxLeft, double actualBoundingBoxRight,
                       double fontBoundingBoxAscent, double fontBoundingBoxDescent) {
        this(width, actualBoundingBoxLeft, actualBoundingBoxRight, fontBoundingBoxAscent, fontBoundingBoxDescent,
                fontBoundingBoxAscent, fontBoundingBoxDescent, fontBoundingBoxAscent, fontBoundingBoxDescent,
                fontBoundingBoxAscent * 0.8, 0, -fontBoundingBoxDescent);
    }
}
