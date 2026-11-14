package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.core.paint.AnimatedValues;
import com.nishiyu.enderframelib.core.paint.BorderDashPattern;
import com.nishiyu.enderframelib.core.paint.ImagePlacement;
import com.nishiyu.enderframelib.core.paint.ObjectFit;
import com.nishiyu.enderframelib.core.paint.model.CircleBorderSpec;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import com.nishiyu.enderframelib.core.text.font.GlyphBitmap;
import com.nishiyu.enderframelib.core.text.font.LoadedFace;
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
    private static final float[] ZERO_F4 = new float[4];

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
        float[] irx, iry;
        if (rx[0] <= 0f && rx[1] <= 0f && rx[2] <= 0f && rx[3] <= 0f
                && ry[0] <= 0f && ry[1] <= 0f && ry[2] <= 0f && ry[3] <= 0f) {
            irx = ZERO_F4;
            iry = ZERO_F4;
        } else {
            irx = new float[4];
            iry = new float[4];
            irx[0] = Math.max(0f, rx[0] - wl);
            irx[1] = Math.max(0f, rx[1] - wr);
            irx[2] = Math.max(0f, rx[2] - wr);
            irx[3] = Math.max(0f, rx[3] - wl);
            iry[0] = Math.max(0f, ry[0] - wt);
            iry[1] = Math.max(0f, ry[1] - wt);
            iry[2] = Math.max(0f, ry[2] - wb);
            iry[3] = Math.max(0f, ry[3] - wb);
            for (int i = 0; i < 4; i++) {
                if (irx[i] <= 0f || iry[i] <= 0f) irx[i] = iry[i] = 0f;
            }
        }
        int[] colors = {
                Pixmap.premultiply(spec.color(0)),
                Pixmap.premultiply(spec.color(1)),
                Pixmap.premultiply(spec.color(2)),
                Pixmap.premultiply(spec.color(3))
        };
        float[] widths = {wt, wr, wb, wl};

        // 破線・点線は 4 辺が同じ太さのときだけ（GL 版と同じ）
        BorderDashPattern pattern = null;
        float bx = 0, by = 0, cxm = (x0 + x1) / 2f, cym = (y0 + y1) / 2f;
        float[] pcx = ZERO_F4, pcy = ZERO_F4;
        if (spec.dashKind() != BorderPath.SOLID && wt > 0f && wt == wr && wr == wb && wb == wl) {
            pcx = new float[4];
            pcy = new float[4];
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

    // ------------------------------------------------------------------ 円の枠線

    /** 円の枠線の形。{@code straight} は非乗算の 4 方向（上・右・下・左）の色、{@code premul} は事前乗算色。範囲は箱を 1 単位広げたもの。 */
    record Circle(int[] straight, int[] premul, float cx, float cy, float outerR, float innerR,
                  float bx0, float by0, float bx1, float by1) {}

    /** 円の枠線の形。枠線の指定もアニメーションの色も無ければ null。 */
    static Circle circle(DisplayItem el, AnimatedValues anim) {
        CircleBorderSpec cb = el.meta() != null ? el.meta().circleBorder() : null;
        if (cb == null && anim.circleColors == null) return null;
        int[] colors = new int[4];
        float thick;
        if (cb != null) {
            colors[0] = cb.colorTop();
            colors[1] = cb.colorRight();
            colors[2] = cb.colorBottom();
            colors[3] = cb.colorLeft();
            thick = cb.thickness();
        } else {
            System.arraycopy(anim.circleColors, 0, colors, 0, 4);
            thick = anim.circleThickness;
        }
        int[] premul = new int[4];
        for (int i = 0; i < 4; i++) premul[i] = Pixmap.premultiply(colors[i]);
        float cx = el.x() + el.width() / 2f, cy = el.y() + el.height() / 2f;
        float outerR = Math.min(el.width(), el.height()) / 2f;
        return new Circle(colors, premul, cx, cy, outerR, outerR - thick,
                el.x() - 1, el.y() - 1, el.x() + el.width() + 1, el.y() + el.height() + 1);
    }

    // ------------------------------------------------------------------ 画像

    /** 画像の置き方: 貼る位置 {@code (px0,py0)}・大きさ・局所 1 単位あたりの画像の画素数・縮小時の標本数・描く範囲。 */
    record Image(float px0, float py0, float plW, float plH, float sx, float sy, int taps,
                 float bx0, float by0, float bx1, float by1) {}

    /**
     * 画像（実寸 {@code iw x ih} CSS 単位、画素 {@code pmW x pmH}）を箱 {@code el} に {@code object-fit} で置く形。
     * 貼る大きさが 0 以下なら null。描く範囲は箱を 1 画素ぶん広げたものと画像の範囲との交わり。
     */
    static Image image(DisplayItem el, float iw, float ih, int pmW, int pmH, float scale) {
        float x0 = el.x(), y0 = el.y(), w = el.width(), h = el.height();
        String position = el.events() != null ? el.events().get("object-position") : null;
        float fontPx = el.meta() != null && el.meta().fontHeight() > 0 ? el.meta().fontHeight() : 16f;
        ImagePlacement pl = ObjectFit.place(el.fitMode(), position, iw, ih, w, h, fontPx);
        if (!(pl.w() > 0) || !(pl.h() > 0)) return null;
        float px0 = x0 + pl.x(), py0 = y0 + pl.y();
        float sx = pmW / pl.w(), sy = pmH / pl.h();
        // 縮小するときは対象の画素の中を複数点で標本化して、折り返しの粗さを抑える
        int taps = (int) Math.min(4, Math.max(1, Math.ceil(Math.max(sx, sy) / Math.max(scale, 0.01f))));
        float m = 1f / Math.max(scale, 0.01f);
        return new Image(px0, py0, pl.w(), pl.h(), sx, sy, taps,
                Math.max(x0 - m, px0), Math.max(y0 - m, py0), Math.min(x0 + w + m, px0 + pl.w()), Math.min(y0 + h + m, py0 + pl.h()));
    }

    // ------------------------------------------------------------------ 字形の置き方

    /** 回転・せん断・縦横で違う拡縮が無ければ、字をデバイス画素の格子に揃えて置ける。 */
    static boolean glyphAxisAligned(Matrix2D pose) {
        return Math.abs(pose.b()) < 1e-9 && Math.abs(pose.c()) < 1e-9 && pose.a() > 0 && Math.abs(pose.a() - pose.d()) < 1e-6;
    }

    /** デバイス x 座標 {@code devX} の字を置く、画素の格子の x（整数）。サブピクセルが 1 画素に繰り上がるときは次の画素。 */
    static float glyphOriginX(double devX) {
        double whole = Math.floor(devX);
        int sub = (int) Math.round((devX - whole) * LoadedFace.SUBPIXEL_STEPS);
        if (sub == LoadedFace.SUBPIXEL_STEPS) whole += 1;
        return (float) whole;
    }

    /** {@link #glyphOriginX} と対になる、1/{@code SUBPIXEL_STEPS} 画素のサブピクセル位置（0 以上 STEPS 未満）。 */
    static int glyphSubpixel(double devX) {
        double whole = Math.floor(devX);
        int sub = (int) Math.round((devX - whole) * LoadedFace.SUBPIXEL_STEPS);
        return sub == LoadedFace.SUBPIXEL_STEPS ? 0 : sub;
    }

    /** 字形の y（デバイス）を画素の行へ四捨五入する。 */
    static float glyphOriginY(double devY) {
        return Math.round(devY);
    }

    /** 回転・せん断の姿勢で貼る字形の、局所の描画範囲 {@code {x0,y0,x1,y1}}（外接矩形を 1 単位広げたもの）。{@code s} は局所 1 単位あたりの字形の画素数。 */
    static float[] glyphLocalBounds(GlyphBitmap g, float ox, float oy, float s) {
        float lx0 = ox + g.left() / s, ly0 = oy - g.top() / s;
        float lx1 = ox + (g.left() + g.width()) / s, ly1 = oy + (g.height() - g.top()) / s;
        return new float[]{lx0 - 1, ly0 - 1, lx1 + 1, ly1 + 1};
    }
}
