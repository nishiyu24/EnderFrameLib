package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.core.paint.BorderDashPattern;
import com.nishiyu.enderframelib.core.paint.BorderPath;
import com.nishiyu.enderframelib.core.paint.BoxDecorationPainter;
import com.nishiyu.enderframelib.core.paint.model.RoundedBorderSpec;
import com.nishiyu.enderframelib.core.raster.Pixmap;

/**
 * 図形の幾何（描く形そのもの）を、GPU 試行にも CPU 命令記録にも依らない値として求める純粋関数群
 * （Phase A 「試行の向きの反転」ステップ ①）。旧 {@code SoftwareRenderBackend} の {@code drawRoundedBorder} /
 * {@code drawShadow} が、幾何の算出・GPU 試行・CPU 記録を 1 メソッドで行っていたうちの「幾何」だけを、コードを変えずに移した。
 * 状態を持たず、{@code GpuSink} も {@code Pixmap} の画素も知らない。
 */
final class ShapeGeometry {
    private ShapeGeometry() {}

    /**
     * 角丸の枠線の形。外側の箱 {@code (x0,y0,x1,y1)}・内側の箱・内側の角半径（{@code irx/iry}）・辺ごとの事前乗算色と太さ・
     * 破線（4 辺が同じ太さで破線／点線のときだけ。それ以外は {@code dash == null}）と、その経路のパラメータ。
     */
    record Border(float x0, float y0, float x1, float y1, float ix0, float iy0, float ix1, float iy1, boolean innerEmpty,
                  float[] irx, float[] iry, int[] colors, float[] widths,
                  BorderDashPattern dash, float bx, float by, float cxm, float cym, float[] pcx, float[] pcy) {}

    /** 角丸枠線の形（{@code rx/ry} は外側の角半径、{@code w/h} は箱の幅・高さ）。 */
    static Border border(float x0, float y0, float w, float h, RoundedBorderSpec spec, float[] rx, float[] ry) {
        float x1 = x0 + w, y1 = y0 + h;
        float wt = spec.widthTop(), wr = spec.widthRight(), wb = spec.widthBottom(), wl = spec.widthLeft();
        float ix0 = x0 + wl, iy0 = y0 + wt, ix1 = x1 - wr, iy1 = y1 - wb;
        boolean innerEmpty = ix1 <= ix0 || iy1 <= iy0;
        float[] irx = new float[4], iry = new float[4];
        float[] sideH = {wl, wr, wr, wl}, sideV = {wt, wt, wb, wb};
        for (int i = 0; i < 4; i++) {
            irx[i] = Math.max(0f, rx[i] - sideH[i]);
            iry[i] = Math.max(0f, ry[i] - sideV[i]);
            if (irx[i] <= 0f || iry[i] <= 0f) irx[i] = iry[i] = 0f;
        }
        int[] colors = new int[4];
        for (int i = 0; i < 4; i++) colors[i] = Pixmap.premultiply(spec.color(i));
        float[] widths = {wt, wr, wb, wl};

        // 破線・点線は 4 辺が同じ太さのときだけ（GL 版と同じ）
        BorderDashPattern pattern = null;
        float bx = 0, by = 0, cxm = (x0 + x1) / 2f, cym = (y0 + y1) / 2f;
        float[] pcx = new float[4], pcy = new float[4];
        if (spec.dashKind() != BorderPath.SOLID && wt > 0f && wt == wr && wr == wb && wb == wl) {
            bx = Math.max(0.001f, w / 2f - wt / 2f);
            by = Math.max(0.001f, h / 2f - wt / 2f);
            for (int i = 0; i < 4; i++) {
                pcx[i] = Math.min(Math.max(rx[i] - wt / 2f, 0f), bx);
                pcy[i] = Math.min(Math.max(ry[i] - wt / 2f, 0f), by);
                if (pcx[i] <= 0f || pcy[i] <= 0f) pcx[i] = pcy[i] = 0f;
            }
            pattern = BorderPath.pattern(spec.dashKind(), wt, BorderPath.length(bx, by, pcx, pcy));
        }
        return new Border(x0, y0, x1, y1, ix0, iy0, ix1, iy1, innerEmpty, irx, iry, colors, widths, pattern, bx, by, cxm, cym, pcx, pcy);
    }

    /** 外側の影の形: 影の角丸矩形 {@code (sx0,sy0,sx1,sy1)} と、その角半径（{@code srx/sry}）。影が消える所より狭ければ null。 */
    record OuterShadow(float sx0, float sy0, float sx1, float sy1, float[] srx, float[] sry) {}

    /** 要素の矩形 {@code (qx0,qy0,qx1,qy1)} とぼかし半径 {@code blur}、箱の角半径から外側の影の形を求める。 */
    static OuterShadow outerShadow(float qx0, float qy0, float qx1, float qy1, float blur, float[] rx, float[] ry) {
        float reach = BoxDecorationPainter.SHADOW_REACH * blur;
        float sx0 = qx0 + reach, sy0 = qy0 + reach, sx1 = qx1 - reach, sy1 = qy1 - reach;
        if (sx1 <= sx0 || sy1 <= sy0) return null;
        float limit = Math.min(sx1 - sx0, sy1 - sy0) / 2f;
        float[] srx = new float[4], sry = new float[4];
        for (int i = 0; i < 4; i++) {
            srx[i] = Math.min(rx[i], limit);
            sry[i] = Math.min(ry[i], limit);
        }
        return new OuterShadow(sx0, sy0, sx1, sy1, srx, sry);
    }
}
