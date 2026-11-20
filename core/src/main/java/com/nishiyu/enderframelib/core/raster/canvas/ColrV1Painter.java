package com.nishiyu.enderframelib.core.raster.canvas;

import com.nishiyu.enderframelib.core.text.font.ColorBitmap;
import com.nishiyu.enderframelib.core.text.font.ColrV1Renderer;
import com.nishiyu.enderframelib.core.raster.CanvasGradient;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import com.nishiyu.enderframelib.core.raster.Paint;
import com.nishiyu.enderframelib.core.raster.Path2D;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.freetype.FT_COLR_Paint;
import org.lwjgl.util.freetype.FT_Color;
import org.lwjgl.util.freetype.FT_ColorLine;
import org.lwjgl.util.freetype.FT_ColorStop;
import org.lwjgl.util.freetype.FT_ColorStopIterator;
import org.lwjgl.util.freetype.FT_Face;
import org.lwjgl.util.freetype.FT_OpaquePaint;
import org.lwjgl.util.freetype.FT_Palette_Data;
import org.lwjgl.util.freetype.FreeType;

import java.util.ArrayList;
import java.util.List;

/**
 * COLR v1（グラデーション・変形・合成を持つカラー字形）のペイントグラフを、{@link CanvasRenderingContext2D} の上に描いて
 * 色付きのビットマップにする。FreeType がペイントを 1 つずつ返し（{@code FT_Get_Paint}）、ここで
 * {@code PaintGlyph} = 字形の輪郭でクリップ、{@code PaintSolid} / 3 種のグラデーション = クリップ内の塗り、
 * {@code PaintTransform} 系 = 変換、{@code PaintComposite} = 2 枚のレイヤーの合成、として解釈する。
 *
 * 座標はすべて設計単位（ヒンティング・拡縮なし。グラデーションの点・半径・変換の移動量は 16.16 固定小数点で返るので 65536 で割る。FreeType のルート変換は文字サイズ未設定だと 0 行列なので使わない）。字形の y は上向きなので、全体を {@code translate · scale(k, -k)} で
 * 画面の座標へ写す。{@link LoadedFace} の面ロックの中から呼ぶ。
 */
public final class ColrV1Painter implements ColrV1Renderer {

    private static final int MAX_DEPTH = 24;
    private static final double FIXED = 65536.0;
    private static final double F2DOT14 = 16384.0;

    /** {@link java.util.ServiceLoader} 用（描画ごとの状態は {@link #render} が作る別のインスタンスが持つ）。 */
    public ColrV1Painter() {
        this(null, null, null, 0, 0, 0, 0);
    }

    private final FT_Face face;
    private final ColrV1Renderer.Face owner;
    private final FT_Color.Buffer palette;
    private final int paletteEntries;
    private final int foregroundArgb;
    private final int width, height;
    private boolean usedForeground;

    private ColrV1Painter(FT_Face face, ColrV1Renderer.Face owner, FT_Color.Buffer palette, int entries, int foregroundArgb, int width, int height) {
        this.face = face;
        this.owner = owner;
        this.palette = palette;
        this.paletteEntries = entries;
        this.foregroundArgb = foregroundArgb;
        this.width = width;
        this.height = height;
    }

    /**
     * {@code glyphIndex} の COLR v1 ペイントを {@code pixelSize}（em の画素数）で描く。v1 のペイントを持たない・描けないなら null。
     * 返すビットマップは墨の外接矩形だけ（{@code left} は原点から左端、{@code top} は原点から上端の画素数）。
     */
    @Override
    public Result render(ColrV1Renderer.Face owner, FT_Face face, int glyphIndex, float pixelSize, int foregroundArgb) {
        if (!(pixelSize > 0f) || pixelSize > MAX_PIXEL_SIZE) return null;
        FT_OpaquePaint root = FT_OpaquePaint.calloc();
        try {
            if (!FreeType.FT_Get_Color_Glyph_Paint(face, glyphIndex, FreeType.FT_COLOR_NO_ROOT_TRANSFORM, root)) return null;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                FT_Palette_Data data = FT_Palette_Data.malloc(stack);
                if (FreeType.FT_Palette_Data_Get(face, data) != 0 || data.num_palettes() == 0) return null;
                PointerBuffer out = stack.mallocPointer(1);
                if (FreeType.FT_Palette_Select(face, (short) 0, out) != 0 || out.get(0) == 0) return null;
                int entries = data.num_palette_entries() & 0xFFFF;
                FT_Color.Buffer pal = FT_Color.create(out.get(0), entries);

                // em の箱を中心に、左 0.25em・上 1.25em・右 1.75em・下 0.75em の範囲を描く
                int size = (int) Math.ceil(pixelSize * 2f) + 2;
                double ox = pixelSize * 0.25, oy = pixelSize * 1.25;
                double k = pixelSize / (double) owner.unitsPerEm();
                CanvasRenderingContext2D cv = new CanvasRenderingContext2D(size, size);
                cv.setTransform(Matrix2D.translate(ox, oy).multiply(Matrix2D.scale(k, -k)));
                ColrV1Painter p = new ColrV1Painter(face, owner, pal, entries, foregroundArgb, size, size);
                p.paint(root, cv, 0);
                return new Result(trim(cv.bitmap(), ox, oy), p.usedForeground);
            }
        } finally {
            root.free();
        }
    }

    /** 墨（不透明度のある画素）の外接矩形だけを取り出す。何も描かれていなければ null。 */
    private static ColorBitmap trim(Pixmap full, double ox, double oy) {
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
        for (int y = 0; y < full.height(); y++) {
            for (int x = 0; x < full.width(); x++) {
                if ((full.get(x, y) >>> 24) != 0) {
                    if (x < x0) x0 = x;
                    if (x > x1) x1 = x;
                    if (y < y0) y0 = y;
                    if (y > y1) y1 = y;
                }
            }
        }
        if (x1 < 0) return null;
        Pixmap out = new Pixmap(x1 - x0 + 1, y1 - y0 + 1);
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) out.set(x - x0, y - y0, full.get(x, y));
        }
        return new ColorBitmap(out, (int) Math.round(x0 - ox), (int) Math.round(oy - y0));
    }

    // ------------------------------------------------------------------ ペイントの解釈

    private void paint(FT_OpaquePaint op, CanvasRenderingContext2D cv, int depth) {
        if (depth > MAX_DEPTH) return;
        FT_COLR_Paint p = FT_COLR_Paint.calloc();
        try {
            if (!FreeType.FT_Get_Paint(face, op, p)) return;
            int format = p.format();
            if (format == FreeType.FT_COLR_PAINTFORMAT_COLR_LAYERS) {
                FT_OpaquePaint layer = FT_OpaquePaint.calloc();
                try {
                    var it = p.u_colr_layers().layer_iterator();
                    for (int guard = 0; guard < 512 && FreeType.FT_Get_Paint_Layers(face, it, layer); guard++) {
                        paint(layer, cv, depth + 1);
                    }
                } finally {
                    layer.free();
                }
            } else if (format == FreeType.FT_COLR_PAINTFORMAT_SOLID) {
                var ci = p.u_solid().color();
                fillAll(cv, Paint.solid(color(ci.palette_index(), ci.alpha())));
            } else if (format == FreeType.FT_COLR_PAINTFORMAT_LINEAR_GRADIENT) {
                var g = p.u_linear_gradient();
                paintLinear(cv, g.colorline(), g.p0().x() / FIXED, g.p0().y() / FIXED, g.p1().x() / FIXED, g.p1().y() / FIXED,
                        g.p2().x() / FIXED, g.p2().y() / FIXED);
            } else if (format == FreeType.FT_COLR_PAINTFORMAT_RADIAL_GRADIENT) {
                var g = p.u_radial_gradient();
                paintRadial(cv, g.colorline(), g.c0().x() / FIXED, g.c0().y() / FIXED, g.r0() / FIXED,
                        g.c1().x() / FIXED, g.c1().y() / FIXED, g.r1() / FIXED);
            } else if (format == FreeType.FT_COLR_PAINTFORMAT_SWEEP_GRADIENT) {
                var g = p.u_sweep_gradient();
                paintSweep(cv, g.colorline(), g.center().x() / FIXED, g.center().y() / FIXED, g.start_angle() / FIXED, g.end_angle() / FIXED);
            } else if (format == FreeType.FT_COLR_PAINTFORMAT_GLYPH) {
                var g = p.u_glyph();
                Path2D outline = owner.glyphOutline(g.glyphID());
                cv.save();
                if (outline.isEmpty()) {
                    cv.beginPath();
                    cv.rect(0, 0, 0, 0);
                    cv.clip(false);
                } else {
                    cv.clip(outline, false);
                }
                paint(g.paint(), cv, depth + 1);
                cv.restore();
            } else if (format == FreeType.FT_COLR_PAINTFORMAT_COLR_GLYPH) {
                FT_OpaquePaint sub = FT_OpaquePaint.calloc();
                try {
                    if (FreeType.FT_Get_Color_Glyph_Paint(face, p.u_colr_glyph().glyphID(), FreeType.FT_COLOR_NO_ROOT_TRANSFORM, sub)) {
                        paint(sub, cv, depth + 1);
                    }
                } finally {
                    sub.free();
                }
            } else if (format == FreeType.FT_COLR_PAINTFORMAT_TRANSFORM) {
                var t = p.u_transform();
                var a = t.affine();
                cv.save();
                cv.transform(a.xx() / FIXED, a.yx() / FIXED, a.xy() / FIXED, a.yy() / FIXED, a.dx() / FIXED, a.dy() / FIXED);
                paint(t.paint(), cv, depth + 1);
                cv.restore();
            } else if (format == FreeType.FT_COLR_PAINTFORMAT_TRANSLATE) {
                var t = p.u_translate();
                cv.save();
                cv.translate(t.dx() / FIXED, t.dy() / FIXED);
                paint(t.paint(), cv, depth + 1);
                cv.restore();
            } else if (format == FreeType.FT_COLR_PAINTFORMAT_SCALE) {
                var t = p.u_scale();
                double cx = t.center_x() / FIXED, cy = t.center_y() / FIXED;
                cv.save();
                cv.translate(cx, cy);
                cv.scale(t.scale_x() / FIXED, t.scale_y() / FIXED);
                cv.translate(-cx, -cy);
                paint(t.paint(), cv, depth + 1);
                cv.restore();
            } else if (format == FreeType.FT_COLR_PAINTFORMAT_ROTATE) {
                var t = p.u_rotate();
                double cx = t.center_x() / FIXED, cy = t.center_y() / FIXED;
                cv.save();
                cv.translate(cx, cy);
                cv.rotate(t.angle() / FIXED * Math.PI); // 角度は 180° を 1 とする（反時計回り・y 上向き）
                cv.translate(-cx, -cy);
                paint(t.paint(), cv, depth + 1);
                cv.restore();
            } else if (format == FreeType.FT_COLR_PAINTFORMAT_SKEW) {
                var t = p.u_skew();
                double cx = t.center_x() / FIXED, cy = t.center_y() / FIXED;
                double kx = Math.tan(t.x_skew_angle() / FIXED * Math.PI), ky = Math.tan(t.y_skew_angle() / FIXED * Math.PI);
                cv.save();
                cv.translate(cx, cy);
                cv.transform(1, -ky, kx, 1, 0, 0); // y 上向き: x' = x + tan(xa)·y、y' = y − tan(ya)·x
                cv.translate(-cx, -cy);
                paint(t.paint(), cv, depth + 1);
                cv.restore();
            } else if (format == FreeType.FT_COLR_PAINTFORMAT_COMPOSITE) {
                paintComposite(cv, p.u_composite().source_paint(), p.u_composite().composite_mode(),
                        p.u_composite().backdrop_paint(), depth);
            }
        } finally {
            p.free();
        }
    }

    /** 2 枚のレイヤー（裏・表）を別々に描き、{@code mode} で合成した結果を {@code cv} へ重ねる。 */
    private void paintComposite(CanvasRenderingContext2D cv, FT_OpaquePaint source, int mode, FT_OpaquePaint backdrop, int depth) {
        CanvasRenderingContext2D back = new CanvasRenderingContext2D(width, height);
        back.setTransform(cv.getTransform());
        paint(backdrop, back, depth + 1);
        if (mode == FreeType.FT_COLR_COMPOSITE_CLEAR) {
            return; // 何も残らない
        }
        if (mode != FreeType.FT_COLR_COMPOSITE_DEST) {
            CanvasRenderingContext2D src = new CanvasRenderingContext2D(width, height);
            src.setTransform(cv.getTransform());
            paint(source, src, depth + 1);
            if (mode == FreeType.FT_COLR_COMPOSITE_SRC) {
                back = src;
            } else {
                back.save();
                back.resetTransform();
                back.globalCompositeOperation(canvasOperation(mode));
                back.drawImage(src, 0, 0);
                back.restore();
            }
        }
        cv.save();
        cv.resetTransform();
        cv.drawImage(back, 0, 0);
        cv.restore();
    }

    private static String canvasOperation(int mode) {
        if (mode == FreeType.FT_COLR_COMPOSITE_SRC_OVER) return "source-over";
        if (mode == FreeType.FT_COLR_COMPOSITE_DEST_OVER) return "destination-over";
        if (mode == FreeType.FT_COLR_COMPOSITE_SRC_IN) return "source-in";
        if (mode == FreeType.FT_COLR_COMPOSITE_DEST_IN) return "destination-in";
        if (mode == FreeType.FT_COLR_COMPOSITE_SRC_OUT) return "source-out";
        if (mode == FreeType.FT_COLR_COMPOSITE_DEST_OUT) return "destination-out";
        if (mode == FreeType.FT_COLR_COMPOSITE_SRC_ATOP) return "source-atop";
        if (mode == FreeType.FT_COLR_COMPOSITE_DEST_ATOP) return "destination-atop";
        if (mode == FreeType.FT_COLR_COMPOSITE_XOR) return "xor";
        if (mode == FreeType.FT_COLR_COMPOSITE_PLUS) return "lighter";
        if (mode == FreeType.FT_COLR_COMPOSITE_SCREEN) return "screen";
        if (mode == FreeType.FT_COLR_COMPOSITE_OVERLAY) return "overlay";
        if (mode == FreeType.FT_COLR_COMPOSITE_DARKEN) return "darken";
        if (mode == FreeType.FT_COLR_COMPOSITE_LIGHTEN) return "lighten";
        if (mode == FreeType.FT_COLR_COMPOSITE_COLOR_DODGE) return "color-dodge";
        if (mode == FreeType.FT_COLR_COMPOSITE_COLOR_BURN) return "color-burn";
        if (mode == FreeType.FT_COLR_COMPOSITE_HARD_LIGHT) return "hard-light";
        if (mode == FreeType.FT_COLR_COMPOSITE_SOFT_LIGHT) return "soft-light";
        if (mode == FreeType.FT_COLR_COMPOSITE_DIFFERENCE) return "difference";
        if (mode == FreeType.FT_COLR_COMPOSITE_EXCLUSION) return "exclusion";
        if (mode == FreeType.FT_COLR_COMPOSITE_MULTIPLY) return "multiply";
        return "source-over"; // HSL 系はキャンバスの合成演算に無い（通常の重ねで近似）
    }

    // ------------------------------------------------------------------ 塗り

    /** 現在のクリップの内側を、変換に関係なく全面塗る。 */
    private static void fillAll(CanvasRenderingContext2D cv, Paint paint) {
        cv.fillStyle(paint);
        cv.fillRect(-100_000, -100_000, 200_000, 200_000);
    }

    /** パレットの色（非乗算 ARGB）。{@code alpha} は F2Dot14。文字色（{@code 0xFFFF}）は {@code foregroundArgb}。 */
    private int color(short paletteIndex, short alpha) {
        int idx = paletteIndex & 0xFFFF;
        int argb;
        if (idx == 0xFFFF) {
            usedForeground = true;
            argb = foregroundArgb;
        } else {
            argb = 0xFF000000;
            if (idx < paletteEntries) {
                FT_Color c = palette.get(idx);
                argb = ((c.alpha() & 0xFF) << 24) | ((c.red() & 0xFF) << 16) | ((c.green() & 0xFF) << 8) | (c.blue() & 0xFF);
            }
        }
        double a = Math.clamp((alpha & 0xFFFF) / F2DOT14, 0.0, 1.0);
        int outAlpha = (int) Math.round(((argb >>> 24) & 0xFF) * a);
        return (outAlpha << 24) | (argb & 0xFFFFFF);
    }

    private record Stop(double offset, int argb) {}

    private List<Stop> stops(FT_ColorLine line) {
        List<Stop> out = new ArrayList<>();
        FT_ColorStop stop = FT_ColorStop.calloc();
        try {
            FT_ColorStopIterator it = line.color_stop_iterator();
            for (int guard = 0; guard < 256 && FreeType.FT_Get_Colorline_Stops(face, stop, it); guard++) {
                var ci = stop.color();
                out.add(new Stop(stop.stop_offset() / FIXED, color(ci.palette_index(), ci.alpha())));
            }
        } finally {
            stop.free();
        }
        out.sort((a, b) -> Double.compare(a.offset(), b.offset()));
        return out;
    }

    private static String spread(FT_ColorLine line) {
        int e = line.extend();
        if (e == FreeType.FT_COLR_PAINT_EXTEND_REPEAT) return "repeat";
        if (e == FreeType.FT_COLR_PAINT_EXTEND_REFLECT) return "reflect";
        return "pad";
    }

    private void paintLinear(CanvasRenderingContext2D cv, FT_ColorLine line, double p0x, double p0y, double p1x, double p1y,
                             double p2x, double p2y) {
        List<Stop> stops = stops(line);
        if (stops.isEmpty()) return;
        // p0→p1 を、p0→p2 に垂直な直線へ射影した点を終点とする（COLR v1 の線形グラデーションの定義）
        double ax = p0x, ay = p0y;
        double v1x = p1x - ax, v1y = p1y - ay;
        double v2x = p2x - ax, v2y = p2y - ay;
        double nx = -v2y, ny = v2x;
        double nn = nx * nx + ny * ny;
        double ex, ey;
        if (nn < 1e-9) {
            ex = p1x;
            ey = p1y;
        } else {
            double s = (v1x * nx + v1y * ny) / nn;
            ex = ax + nx * s;
            ey = ay + ny * s;
        }
        if (Math.abs(ex - ax) < 1e-9 && Math.abs(ey - ay) < 1e-9) {
            fillAll(cv, Paint.solid(stops.get(stops.size() - 1).argb()));
            return;
        }
        CanvasGradient g = CanvasGradient.linear(ax, ay, ex, ey).spread(spread(line));
        for (Stop s : stops) g.addColorStop(Math.clamp(s.offset(), 0.0, 1.0), s.argb());
        fillAll(cv, g);
    }

    private void paintRadial(CanvasRenderingContext2D cv, FT_ColorLine line, double c0x, double c0y, double r0,
                             double c1x, double c1y, double r1) {
        List<Stop> stops = stops(line);
        if (stops.isEmpty()) return;
        CanvasGradient g = CanvasGradient.radial(c0x, c0y, Math.max(0, r0), c1x, c1y, Math.max(0, r1)).spread(spread(line));
        for (Stop s : stops) g.addColorStop(Math.clamp(s.offset(), 0.0, 1.0), s.argb());
        fillAll(cv, g);
    }

    /**
     * 扇形のグラデーション。角度は 180° を 1 とした値（反時計回り）。キャンバスの円錐グラデーションは 1 周を 0..1 とするので、
     * 色の位置を {@code (end - start) / 2} 倍し、終端より先は最後の色を保つ。
     */
    private void paintSweep(CanvasRenderingContext2D cv, FT_ColorLine line, double cx, double cy, double startHalfTurns, double endHalfTurns) {
        List<Stop> stops = stops(line);
        if (stops.isEmpty()) return;
        double span = (endHalfTurns - startHalfTurns) / 2.0;
        CanvasGradient g = CanvasGradient.conic(startHalfTurns * Math.PI, cx, cy);
        for (Stop s : stops) g.addColorStop(Math.clamp(s.offset() * span, 0.0, 1.0), s.argb());
        if (span < 1.0) g.addColorStop(1.0, stops.get(stops.size() - 1).argb());
        fillAll(cv, g);
    }
}
