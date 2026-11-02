package com.nishiyu.enderframelib.core.paint;

/**
 * 角丸矩形を「1px の横帯」に分ける純粋な幾何。頂点の出力（Minecraft の {@code VertexConsumer} など）は含まない。
 * 帯の並びと値は、Minecraft 側の {@code RoundedRectRasterizer} が従来そのまま計算していたものと同じ。
 */
public final class RoundedRectGeometry {

    private RoundedRectGeometry() {}

    /** 帯 1 つ（x0〜x1、y0〜y1）を受け取る。 */
    @FunctionalInterface
    public interface RowSink {
        void row(float x0, float y0, float x1, float y1);
    }

    /**
     * 円弧角の内側への食い込み量（インセット）。
     * @param radius 角丸半径
     * @param distFromCenter 円弧中心からの法線距離（0〜radius）
     */
    public static float computeCornerInset(float radius, float distFromCenter) {
        if (radius <= 0f || distFromCenter <= 0f) return 0f;
        float clamped = Math.min(radius, distFromCenter);
        return radius - (float) Math.sqrt(Math.max(0f, radius * radius - clamped * clamped));
    }

    /**
     * 角丸矩形を、中央の帯 → 上の角の 1px 帯 → 下の角の 1px 帯の順で出す。空の帯（幅・高さが 0 以下）は出さない。
     * 半径がすべて 0 以下のときは矩形 1 枚。
     */
    public static void roundedRectRows(float minX, float minY, float maxX, float maxY,
                                       float rTL, float rTR, float rBR, float rBL, RowSink sink) {
        float maxR = Math.max(Math.max(rTL, rTR), Math.max(rBR, rBL));
        if (maxR <= 0) {
            if (minX < maxX && minY < maxY) sink.row(minX, minY, maxX, maxY);
            return;
        }
        float maxTop = Math.max(rTL, rTR), maxBottom = Math.max(rBL, rBR);
        if (minY + maxTop < maxY - maxBottom) sink.row(minX, minY + maxTop, maxX, maxY - maxBottom);
        for (int i = 0; i < maxTop; i++) {
            float x0 = minX + computeCornerInset(rTL, rTL - i - 0.5f);
            float x1 = maxX - computeCornerInset(rTR, rTR - i - 0.5f);
            if (x0 < x1) sink.row(x0, minY + i, x1, minY + i + 1);
        }
        for (int i = 0; i < maxBottom; i++) {
            float x0 = minX + computeCornerInset(rBL, rBL - i - 0.5f);
            float x1 = maxX - computeCornerInset(rBR, rBR - i - 0.5f);
            if (x0 < x1) sink.row(x0, maxY - i - 1, x1, maxY - i);
        }
    }

    /**
     * 矩形（minX..maxX, minY..maxY）を、角丸のクリップ（cx0..cx1, cy0..cy1。四隅の半径 cRTL・cRTR・cRBR・cRBL）で
     * 切り抜いた領域を、上の角の帯 → 中央 → 下の角の帯の順で出す。
     * クリップの半径がすべて（ほぼ）0 なら、交差した矩形 1 枚。
     */
    public static void clippedRows(float minX, float minY, float maxX, float maxY,
                                   float cx0, float cy0, float cx1, float cy1,
                                   float cRTL, float cRTR, float cRBR, float cRBL, RowSink sink) {
        float startY = Math.max(minY, cy0), endY = Math.min(maxY, cy1);
        if (startY >= endY) return;

        if (cRTL <= 0.01f && cRTR <= 0.01f && cRBR <= 0.01f && cRBL <= 0.01f) {
            float x0 = Math.max(minX, cx0), x1 = Math.min(maxX, cx1);
            if (x0 < x1) sink.row(x0, startY, x1, endY);
            return;
        }

        float maxTop = Math.max(cRTL, cRTR), maxBottom = Math.max(cRBL, cRBR);

        float topZoneEnd = Math.min(endY, cy0 + maxTop);
        for (float y = startY; y < topZoneEnd; y++) {
            float yEnd = Math.min(y + 1.0f, topZoneEnd);
            float midY = (y + yEnd) / 2.0f;
            float x0 = Math.max(minX, cx0 + computeCornerInset(cRTL, cy0 + cRTL - midY));
            float x1 = Math.min(maxX, cx1 - computeCornerInset(cRTR, cy0 + cRTR - midY));
            if (x0 < x1) sink.row(x0, y, x1, yEnd);
        }

        float midStart = Math.max(startY, cy0 + maxTop), midEnd = Math.min(endY, cy1 - maxBottom);
        if (midStart < midEnd) {
            float x0 = Math.max(minX, cx0), x1 = Math.min(maxX, cx1);
            if (x0 < x1) sink.row(x0, midStart, x1, midEnd);
        }

        float bottomStart = Math.max(midStart, Math.max(startY, cy1 - maxBottom));
        for (float y = bottomStart; y < endY; y++) {
            float yEnd = Math.min(y + 1.0f, endY);
            float midY = (y + yEnd) / 2.0f;
            float x0 = Math.max(minX, cx0 + computeCornerInset(cRBL, midY - (cy1 - cRBL)));
            float x1 = Math.min(maxX, cx1 - computeCornerInset(cRBR, midY - (cy1 - cRBR)));
            if (x0 < x1) sink.row(x0, y, x1, yEnd);
        }
    }
}
