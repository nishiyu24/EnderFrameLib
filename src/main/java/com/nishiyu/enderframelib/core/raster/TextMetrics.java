package com.nishiyu.enderframelib.core.raster;

/** Canvas 2D の {@code TextMetrics}（実装している範囲）。 */
public record TextMetrics(double width, double actualBoundingBoxLeft, double actualBoundingBoxRight,
                          double fontBoundingBoxAscent, double fontBoundingBoxDescent) {
}
