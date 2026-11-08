package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.core.raster.Matrix2D;

/** クリップの 1 つ。持ち主の姿勢の逆変換で画素を持ち主の空間へ戻し、角丸矩形の SDF で被覆率を出す。 */
record ClipPart(Matrix2D inv, float scale, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                        int bx0, int by0, int bx1, int by1, float ix0, float iy0, float ix1, float iy1,
                        double dix0, double diy0, double dix1, double diy1) {
    float coverage(double dx, double dy) {
        float lx = (float) inv.applyX(dx, dy), ly = (float) inv.applyY(dx, dy);
        if (lx >= ix0 && lx <= ix1 && ly >= iy0 && ly <= iy1) return 1f; // 角と縁から離れた内側は距離を測らない
        return SoftShapes.coverage(SoftShapes.sdRoundRect(lx, ly, x0, y0, x1, y1, rx, ry), scale);
    }
}
