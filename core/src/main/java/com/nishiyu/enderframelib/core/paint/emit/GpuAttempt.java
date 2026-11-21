package com.nishiyu.enderframelib.core.paint.emit;

import com.nishiyu.enderframelib.core.paint.port.GpuClip;
import com.nishiyu.enderframelib.core.paint.port.GpuCoverage;
import com.nishiyu.enderframelib.core.paint.port.GpuSink;
import com.nishiyu.enderframelib.core.paint.BorderDashPattern;
import com.nishiyu.enderframelib.core.paint.ImagePlacement;
import com.nishiyu.enderframelib.core.paint.ObjectFit;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.TextureRef;
import com.nishiyu.enderframelib.core.paint.model.RoundedBorderSpec;
import com.nishiyu.enderframelib.core.paint.model.ShaderGradient;
import com.nishiyu.enderframelib.core.paint.model.ShadowShape;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.text.font.GlyphBitmap;

import java.util.List;

/**
 * 図形ごとの「GPU へ渡せるか」の試行。CPU の命令生成（{@link SoftwareRenderBackend} の記録）とは別に、
 * 渡せたら true（CPU は何も塗らない）、渡せなければ false（CPU が塗る）を返すだけの純粋な窓口。
 * 図形を 1 種類ずつここへ移していく（単色角丸矩形 → 影 → 枠線 → 字形 → 画像）。
 */
public final class GpuAttempt {
    private GpuAttempt() {}

    /** GPU へ渡せるクリップの列。多すぎれば {@code null}（CPU が塗る）。同じ {@link Clip} では覚えた同じ配列を返す。 */
    public static GpuClip[] gpuClips(Clip clip) {
        return clip.gpuClips();
    }

    /** {@link #gpuClips} の本体（{@link Clip#gpuClips} が 1 度だけ呼ぶ）。 */
    public static GpuClip[] buildGpuClips(Clip clip) {
        List<ClipPart> parts = clip.parts();
        if (parts.size() > GpuSink.MAX_CLIPS) return null;
        GpuClip[] out = new GpuClip[parts.size()];
        for (int i = 0; i < out.length; i++) {
            ClipPart p = parts.get(i);
            out[i] = new GpuClip(p.inv(), p.scale(), p.x0(), p.y0(), p.x1(), p.y1(), p.rx(), p.ry());
        }
        return out;
    }

    /** 単色の角丸矩形を GPU が受け取ったら true。{@code sink} が無い・クリップが多すぎる・GPU が断った、のいずれかなら false。 */
    public static boolean fillRoundRect(GpuSink sink, Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                 int argb, Clip clip, float opacity) {
        if (sink == null) return false;
        GpuClip[] gc = gpuClips(clip);
        return GpuCoverage.note(GpuCoverage.Kind.FILL, gc != null && sink.fillRoundRect(pose, x0, y0, x1, y1, rx, ry, argb, gc, opacity));
    }

    /** 影の穴の形の角の横半径（左上・右上・右下・左下）。 */
    public static float[] shapeRx(ShadowShape shape) {
        return new float[]{shape.rTL(), shape.rTR(), shape.rBR(), shape.rBL()};
    }

    /** 影の穴の形の角の縦半径。 */
    public static float[] shapeRy(ShadowShape shape) {
        return new float[]{shape.rTLv(), shape.rTRv(), shape.rBRv(), shape.rBLv()};
    }

    private static float[] shapeRect(ShadowShape shape) {
        return new float[]{shape.x0(), shape.y0(), shape.x1(), shape.y1()};
    }

    /** 内側のぼかし影を GPU が受け取ったら true。{@code shape} は穴の形（内側の影では必ずある）。 */
    public static boolean insetShadow(GpuSink sink, Matrix2D pose, float qx0, float qy0, float qx1, float qy1, float[] rx, float[] ry,
                               float sigma, ShadowShape shape, int argb, Clip clip, float opacity) {
        if (sink == null) return false;
        GpuClip[] gc = gpuClips(clip);
        return GpuCoverage.note(GpuCoverage.Kind.INSET_SHADOW, gc != null && sink.insetShadow(pose, qx0, qy0, qx1, qy1, rx, ry, sigma,
                shapeRect(shape), shapeRx(shape), shapeRy(shape), argb, gc, opacity));
    }

    /** 外側のぼかし影を GPU が受け取ったら true。{@code shape} は抜く穴（無ければ null）。 */
    public static boolean outerShadow(GpuSink sink, Matrix2D pose, float qx0, float qy0, float qx1, float qy1,
                               float sx0, float sy0, float sx1, float sy1, float[] srx, float[] sry, float sigma,
                               ShadowShape shape, int argb, Clip clip, float opacity) {
        if (sink == null) return false;
        GpuClip[] gc = gpuClips(clip);
        if (gc == null) return GpuCoverage.note(GpuCoverage.Kind.OUTER_SHADOW, false);
        return GpuCoverage.note(GpuCoverage.Kind.OUTER_SHADOW, sink.outerShadow(pose, qx0, qy0, qx1, qy1, sx0, sy0, sx1, sy1, srx, sry, sigma,
                shape != null ? shapeRect(shape) : null, shape != null ? shapeRx(shape) : null,
                shape != null ? shapeRy(shape) : null, argb, gc, opacity));
    }

    private static int[] straightColors(RoundedBorderSpec spec) {
        return new int[]{spec.color(0), spec.color(1), spec.color(2), spec.color(3)};
    }

    /** 実線の角丸枠を GPU が受け取ったら true。色は非乗算（上・右・下・左）。 */
    public static boolean roundBorder(GpuSink sink, Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                               float ix0, float iy0, float ix1, float iy1, float[] irx, float[] iry, boolean innerEmpty,
                               RoundedBorderSpec spec, float[] widths, Clip clip, float opacity) {
        if (sink == null) return false;
        GpuClip[] gc = gpuClips(clip);
        return GpuCoverage.note(GpuCoverage.Kind.BORDER, gc != null && sink.strokeRoundBorder(pose, x0, y0, x1, y1, rx, ry, ix0, iy0, ix1, iy1, irx, iry, innerEmpty,
                straightColors(spec), widths, gc, opacity));
    }

    /** 破線・点線の角丸枠を GPU が受け取ったら true（4 辺が同じ太さ {@code thickness} のときだけ呼ぶ）。 */
    public static boolean dashedBorder(GpuSink sink, Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                float ix0, float iy0, float ix1, float iy1, float[] irx, float[] iry, boolean innerEmpty,
                                RoundedBorderSpec spec, float[] widths,
                                float bx, float by, float cxm, float cym, float[] pcx, float[] pcy,
                                BorderDashPattern dash, float thickness, Clip clip, float opacity) {
        if (sink == null) return false;
        GpuClip[] gc = gpuClips(clip);
        return GpuCoverage.note(GpuCoverage.Kind.DASHED_BORDER, gc != null && sink.strokeDashedBorder(pose, x0, y0, x1, y1, rx, ry, ix0, iy0, ix1, iy1, irx, iry, innerEmpty,
                straightColors(spec), widths, bx, by, cxm, cym, pcx, pcy, dash.period(), dash.dash(), dash.round(), thickness,
                gc, opacity));
    }

    /** 円の枠を GPU が受け取ったら true。{@code straightColors} は非乗算の上・右・下・左。 */
    public static boolean circleBorder(GpuSink sink, Matrix2D pose, float cx, float cy, float outerR, float innerR,
                                float bx0, float by0, float bx1, float by1, int[] straightColors, Clip clip, float opacity) {
        if (sink == null) return false;
        GpuClip[] gc = gpuClips(clip);
        return GpuCoverage.note(GpuCoverage.Kind.CIRCLE_BORDER, gc != null && sink.strokeCircleBorder(pose, cx, cy, outerR, innerR, bx0, by0, bx1, by1, straightColors, gc, opacity));
    }

    /**
     * 字形 1 つ（デバイス画素の格子に揃える）を GPU が受け取った、または不透明度 0 で描くものが無ければ true。
     * {@code sink} が無い・クリップが多すぎる・GPU が断った、なら false（CPU が貼る）。原点は四捨五入で画素へ揃える。
     */
    public static boolean glyph(GpuSink sink, GlyphBitmap g, float originX, float originY, int argb, Clip clip, float opacity) {
        if (sink == null) return false;
        if (opacity <= 0f) return true;
        GpuClip[] gc = gpuClips(clip);
        if (gc == null) return GpuCoverage.note(GpuCoverage.Kind.GLYPH, false);
        return GpuCoverage.note(GpuCoverage.Kind.GLYPH, sink.drawGlyph(g, Math.round(originX + g.left()), Math.round(originY - g.top()), argb, gc, opacity));
    }

    /** 字形の縁取り（符号付き距離の字形 {@code sdf}）。戻り値の意味は {@link #glyph}。 */
    public static boolean glyphStroke(GpuSink sink, GlyphBitmap sdf, float originX, float originY, float half, int argb, Clip clip,
                               float opacity) {
        if (sink == null) return false;
        if (opacity <= 0f) return true;
        GpuClip[] gc = gpuClips(clip);
        if (gc == null) return GpuCoverage.note(GpuCoverage.Kind.GLYPH_STROKE, false);
        return GpuCoverage.note(GpuCoverage.Kind.GLYPH_STROKE, sink.drawGlyphStroke(sdf, Math.round(originX + sdf.left()), Math.round(originY - sdf.top()), half, argb, gc, opacity));
    }

    /** 回転・せん断の姿勢の字形。描く範囲は字形の局所の外接矩形を 1 単位広げたもの。戻り値の意味は {@link #glyph}。 */
    public static boolean glyphTransformed(GpuSink sink, GlyphBitmap g, float ox, float oy, float s, Matrix2D pose, int argb, Clip clip,
                                    float opacity) {
        if (sink == null) return false;
        if (opacity <= 0f) return true;
        GpuClip[] gc = gpuClips(clip);
        if (gc == null) return GpuCoverage.note(GpuCoverage.Kind.GLYPH_TRANSFORMED, false);
        float[] lb = ShapeGeometry.glyphLocalBounds(g, ox, oy, s);
        return GpuCoverage.note(GpuCoverage.Kind.GLYPH_TRANSFORMED, sink.drawGlyphTransformed(pose, g, ox, oy, s, lb[0], lb[1], lb[2], lb[3], argb, gc, opacity));
    }

    /**
     * 画像（CPU で読み込んだ {@code pm}）を GPU が受け取ったら true。{@code (px0,py0)} は画像の左上（局所）、{@code (plW,plH)} は貼る大きさ、
     * {@code (sx,sy)} は局所 1 単位あたりの画像の画素数、{@code taps} は縮小時の標本数、{@code scale} は姿勢の平均拡大率。
     * 描く範囲は箱を 1 画素ぶん広げたものと画像の範囲との交わり。
     */
    public static boolean image(GpuSink sink, Matrix2D pose, Pixmap pm, float x0, float y0, float w, float h,
                         float px0, float py0, float plW, float plH, float sx, float sy, float[] rx, float[] ry,
                         int taps, float scale, Clip clip, float opacity) {
        if (sink == null) return false;
        GpuClip[] gc = gpuClips(clip);
        if (gc == null) return GpuCoverage.note(GpuCoverage.Kind.IMAGE, false);
        float m = 1f / Math.max(scale, 0.01f);
        return GpuCoverage.note(GpuCoverage.Kind.IMAGE, sink.drawImage(pose, pm, Math.max(x0 - m, px0), Math.max(y0 - m, py0),
                Math.min(x0 + w + m, px0 + plW), Math.min(y0 + h + m, py0 + plH), px0, py0, sx, sy,
                x0, y0, x0 + w, y0 + h, rx, ry, taps, sx / scale, sy / scale, gc, opacity));
    }

    /**
     * 外部テクスチャを GPU の受け口へ渡す。貼る範囲（{@code uv}）と上下反転を、{@code drawTexture} の写像
     * （テクスチャ全体の左上・画素を基準にした {@code px0/py0/sx/sy}）に織り込む。受け口が断る（CPU 版など）ときは false。
     * 貼る大きさが 0 のときは描くものが無いので、画像の項目としては描いたことにして true。
     */
    public static boolean externalTexture(GpuSink sink, DisplayItem el, TextureRef t, float[] rx, float[] ry, Matrix2D pose,
                                   Clip clip, float opacity, float scale) {
        if (sink == null) return false;
        float x0 = el.x(), y0 = el.y(), w = el.width(), h = el.height();
        float sw = t.regionWidth(), sh = t.regionHeight();
        String position = el.events() != null ? el.events().get("object-position") : null;
        float fontPx = el.meta() != null && el.meta().fontHeight() > 0 ? el.meta().fontHeight() : 16f;
        ImagePlacement pl = ObjectFit.place(el.fitMode(), position, sw, sh, w, h, fontPx);
        if (!(pl.w() > 0) || !(pl.h() > 0)) return true;
        float px0 = x0 + pl.x(), py0 = y0 + pl.y();
        float kx = sw / pl.w(), ky = sh / pl.h();           // 局所の 1 単位あたりのテクスチャの画素数
        float regionX = t.u0() * t.width(), regionY = t.v0() * t.height();
        float effPx0 = px0 - regionX / kx;                  // 範囲の左上を、貼る位置の左上に合わせる
        float effPy0, sy;
        if (t.flipY()) { // 下から上へ並んだテクスチャ: 見た目の上端は、テクスチャの上端（行 height）から下へ数える
            sy = -ky;
            effPy0 = py0 + (t.height() - regionY) / ky;
        } else {
            sy = ky;
            effPy0 = py0 - regionY / ky;
        }
        float m = 1f / Math.max(scale, 0.01f);
        GpuClip[] gc = gpuClips(clip);
        return GpuCoverage.note(GpuCoverage.Kind.EXTERNAL_TEXTURE, gc != null && sink.drawTexture(pose, t.handle(), t.width(), t.height(),
                Math.max(x0 - m, px0), Math.max(y0 - m, py0), Math.min(x0 + w + m, px0 + pl.w()), Math.min(y0 + h + m, py0 + pl.h()),
                effPx0, effPy0, kx, sy, x0, y0, x0 + w, y0 + h, rx, ry, gc, opacity));
    }

    /** 角丸矩形のシェーダーグラデーションを GPU が受け取ったら true。 */
    public static boolean gradient(GpuSink sink, Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                            ShaderGradient g, Clip clip, float opacity) {
        if (sink == null) return false;
        GpuClip[] gc = gpuClips(clip);
        return GpuCoverage.note(GpuCoverage.Kind.GRADIENT, gc != null && sink.fillGradient(pose, x0, y0, x1, y1, rx, ry, g, gc, opacity));
    }

    /** {@code <scene-3d>} を GPU 側 FBO ＋ゼロコピーテクスチャ合成で描けたら true。 */
    public static boolean scene3D(GpuSink sink, Matrix2D pose,
                           com.nishiyu.enderframelib.core.scene3d.Scene3DWorld world,
                           int fboW, int fboH, float x0, float y0, float x1, float y1,
                           float[] rx, float[] ry, Clip clip, float opacity) {
        if (sink == null) return false;
        GpuClip[] gc = gpuClips(clip);
        return gc != null && sink.drawScene3D(pose, world, fboW, fboH, x0, y0, x1, y1, rx, ry, gc, opacity);
    }

    /** フォールバック合成用に 3D シーンの描画結果を事前乗算 ARGB ピクセルへ読み戻す。 */
    public static Pixmap renderScene3DPixmap(GpuSink sink, com.nishiyu.enderframelib.core.scene3d.Scene3DWorld world,
                                      int fboW, int fboH) {
        return sink == null ? null : sink.renderScene3DPixmap(world, fboW, fboH);
    }
}
