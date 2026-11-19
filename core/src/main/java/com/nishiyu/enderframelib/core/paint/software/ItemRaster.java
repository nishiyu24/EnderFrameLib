package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.core.scene3d.Scene3DTags;
import com.nishiyu.enderframelib.core.scene3d.Scene3DWorld;
import com.nishiyu.enderframelib.core.paint.AnimatedValues;
import com.nishiyu.enderframelib.core.paint.BorderDashPattern;
import com.nishiyu.enderframelib.core.paint.model.ShadowShape;
import com.nishiyu.enderframelib.core.paint.model.TextureRef;
import com.nishiyu.enderframelib.core.paint.BorderPath;
import com.nishiyu.enderframelib.core.paint.BoxDecorationPainter;
import com.nishiyu.enderframelib.core.paint.PaintSceneDriver.Resolved;
import com.nishiyu.enderframelib.core.paint.ShaderGradientEmulator;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.RenderHints;
import com.nishiyu.enderframelib.core.paint.model.RenderMeta;
import com.nishiyu.enderframelib.core.paint.model.RoundedBorderSpec;
import com.nishiyu.enderframelib.core.paint.model.ShaderGradient;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import com.nishiyu.enderframelib.core.raster.Pixmap;

/** ソフトウェア描画の、項目の種類ごとの塗り（背景・グラデーション・影・角丸と円の枠線・画像・3D の場面・range / progress / トグルの入力部品）。座標の変換・クリップ・塗りの記録は {@link SoftwareRenderBackend} が持つ。A8 で {@code SoftwareRenderBackend} から切り出した（文字は {@link TextRaster}）。 */
final class ItemRaster {

    private final SoftwareRenderBackend backend;

    ItemRaster(SoftwareRenderBackend backend) {
        this.backend = backend;
    }

    void drawScene3d(DisplayItem el, float[] rx, float[] ry, Resolved res, Clip clip, float opacity) {
        var events = el.events();
        if (events == null) {
            return;
        }
        String kind = events.get(Scene3DTags.KIND_ATTR);
        if (!Scene3DTags.KIND_3D.equals(kind)) {
            return;
        }
        String worldAttr = events.get(Scene3DTags.WORLD_ATTR);
        if (worldAttr == null || worldAttr.isEmpty()) {
            return;
        }
        long worldId;
        try {
            worldId = Long.parseLong(worldAttr);
        } catch (NumberFormatException ignored) {
            return;
        }
        Scene3DWorld world = backend.resources.scene3dWorld(worldId);
        if (world == null) {
            return;
        }
        float w = el.width(), h = el.height();
        if (!(w > 0f) || !(h > 0f)) {
            return;
        }
        float x0 = el.x(), y0 = el.y(), x1 = x0 + w, y1 = y0 + h;
        double pixelScale = Math.max(1.0, Math.max(backend.dpr, res.pose().meanScale()));
        int fboW = Math.max(1, (int) Math.ceil(w * pixelScale));
        int fboH = Math.max(1, (int) Math.ceil(h * pixelScale));

        if (GpuAttempt.scene3D(backend.route.current(), res.pose(), world, fboW, fboH, x0, y0, x1, y1, rx, ry, clip, opacity)) {
            return;
        }
        Pixmap gpuPm = GpuAttempt.renderScene3DPixmap(backend.route.current(), world, fboW, fboH);
        // GPU の無い経路（SOFTWARE ターゲット・ヘッドレス CI）は、同じシーンモデルをソフトウェアで描く（X3-2）
        final Pixmap pm = gpuPm != null ? gpuPm : SoftwareScene3DRasterizer.render(world, fboW, fboH);
        float scale = (float) res.pose().meanScale();
        float m = 1f / Math.max(scale, 0.01f);
        final float[] crx = backend.freezeRadii(rx), cry = backend.freezeRadii(ry);
        float sx = pm.width() / w, sy = pm.height() / h;
        backend.paint(res.pose(), x0 - m, y0 - m, x1 + m, y1 + m, clip, opacity, (lx, ly) -> {
            float cov = (crx == SoftwareRenderBackend.ZERO4 && cry == SoftwareRenderBackend.ZERO4) ? 1f
                    : SoftShapes.coverage(SoftShapes.sdRoundRect(lx, ly, x0, y0, x1, y1, crx, cry), scale);
            if (cov <= 0f) return 0;
            float u = (lx - x0) * sx, v = (ly - y0) * sy;
            if (u < -0.5f || v < -0.5f || u > pm.width() + 0.5f || v > pm.height() + 0.5f) return 0;
            int sample = SoftwareRenderBackend.bilinear(pm, u - 0.5f, v - 0.5f);
            return cov >= 1f ? sample : SoftShapes.scalePremul(sample, cov);
        }, Fast.PURE);
    }

    void drawBackground(DisplayItem el, AnimatedValues anim, float[] rx, float[] ry,
                                Resolved res, Clip clip, float opacity) {
        RenderHints hints = el.hints();
        float x0 = el.x(), y0 = el.y(), x1 = el.x() + el.width(), y1 = el.y() + el.height();
        if (hints.shadowBlur() > 0f) {
            drawShadow(el, anim, hints, rx, ry, res, clip, opacity);
        } else if (hints.shaderGradient() != null) {
            backend.drawGradient(hints.shaderGradient(), x0, y0, x1, y1, rx, ry, res, clip, opacity);
        } else {
            backend.fillRoundRect(res.pose(), x0, y0, x1, y1, rx, ry, anim.bgColor(), clip, opacity);
        }
    }

    /** シェーダーのグラデーションの CPU 命令を記録する（GPU に渡せなかったときだけ）。 */
    void recordGradient(ShaderGradient g, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                Resolved res, Clip clip, float opacity) {
        float scale = (float) res.pose().meanScale();
        float m = 1f / Math.max(scale, 0.01f);
        // 角の丸みと縁の半画素から十分に内側なら被覆率は 1（距離の計算を省ける）。
        float reach = 0.5f / Math.max(scale, 0.01f) + 1e-3f;
        float ix0 = x0 + SoftShapes.maxOf(rx) + reach, ix1 = x1 - SoftShapes.maxOf(rx) - reach;
        float iy0 = y0 + SoftShapes.maxOf(ry) + reach, iy1 = y1 - SoftShapes.maxOf(ry) - reach;
        Matrix2D pose = res.pose();
        if (opacity <= 0f) return;
        Matrix2D inv = pose.invert();
        if (inv == null || !inv.isFinite()) return;
        int[] r = backend.paintRange(pose, x0 - m, y0 - m, x1 + m, y1 + m, clip);
        if (r == null) return;
        int[] px = backend.raster.target().pixels();
        int w = backend.raster.targetWidth();
        boolean clipped = !clip.parts().isEmpty();
        final int rx0 = r[0], rx1 = r[2], ry0 = r[1];
        // 軸平行の姿勢では局所の x は列だけ、y は行だけで決まる（逆変換を画素ごとにしない）。被覆率 1 の内側の列の範囲 [spLo, spHi) と、
        // 内側の行（行ごとに判定）を作り、その中は被覆率・クリップの計算を省く。範囲の外と姿勢が斜めのときは従来の画素ごとの経路。
        PixelOps.GradientOp op = backend.pool.acquireGradient();
        op.hasCol = false;
        op.spLo = op.spHi = 0;
        op.px = px; op.w = w;
        op.x0 = r[0]; op.y0 = r[1]; op.x1 = r[2]; op.y1 = r[3];
        op.inv = inv; op.clip = clip; op.clipped = clipped; op.opacity = opacity; op.g = g;
        op.sx0 = x0; op.sy0 = y0; op.sx1 = x1; op.sy1 = y1; op.rx = backend.freezeRadii(rx); op.ry = backend.freezeRadii(ry); op.scale = scale;
        op.fIx0 = ix0; op.fIx1 = ix1; op.fIy0 = iy0; op.fIy1 = iy1;
        op.axis = backend.fastSolid && pose.b() == 0.0 && pose.c() == 0.0;
        int lo = 0, hi = 0;
        if (op.axis) {
            float[] lxs = op.lxs != null && op.lxs.length >= rx1 - rx0 ? op.lxs : new float[rx1 - rx0];
            float[] lys = op.lys != null && op.lys.length >= r[3] - ry0 ? op.lys : new float[r[3] - ry0];
            for (int x = rx0; x < rx1; x++) lxs[x - rx0] = (float) inv.applyX(x + 0.5, ry0 + 0.5);
            for (int y = ry0; y < r[3]; y++) lys[y - ry0] = (float) inv.applyY(rx0 + 0.5, y + 0.5);
            int n = 0;
            lo = Integer.MAX_VALUE; hi = Integer.MIN_VALUE;
            for (int x = rx0; x < rx1; x++) {
                float lx = lxs[x - rx0];
                double dx = x + 0.5;
                if (lx >= ix0 && lx <= ix1 && (!clipped || (dx >= clip.ix0() && dx <= clip.ix1()))) { lo = Math.min(lo, x); hi = Math.max(hi, x + 1); n++; }
            }
            if (n == 0 || hi - lo != n) { lo = hi = 0; } // 空、または連続でない（想定外）: 範囲の近道を使わない
            op.lxs = lxs; op.lys = lys;
            // 色が列の中で一定の線形グラデーションは、色を列ごとに 1 回だけ求める
            if (hi > lo && ShaderGradientEmulator.prepare(g).constantAlongColumns()) {
                ShaderGradientEmulator.Prepared sh0 = ShaderGradientEmulator.prepare(g);
                if (op.colFinal == null || op.colFinal.length < hi - lo) op.colFinal = new int[hi - lo];
                op.hasCol = true;
                for (int x = lo; x < hi; x++) op.colFinal[x - lo] = PixelOps.spanColor(sh0.colorOrZero(lxs[x - rx0], lys[0]), opacity);
            }
        }
        op.spLo = lo; op.spHi = hi;
        backend.cpuOps++;
        backend.queue.submit(op, true); // 行は互いに独立（書き込む画素も別）。タイルごとに並列で塗る
    }

    /**
     * ぼかした影。要素の矩形は影が消える所までの広さで、外側の影はそこから {@link BoxDecorationPainter#SHADOW_REACH} ×
     * ぼかし半径だけ内側が影の形、箱そのものは影を抜く穴。内側の影は穴の外を塗る。濃さはガウスぼかし（標準偏差 = 半径 / 2）。
     */
    private void drawShadow(DisplayItem el, AnimatedValues anim, RenderHints hints, float[] rx, float[] ry,
                            Resolved res, Clip clip, float opacity) {
        int color = anim.bgColor();
        if ((color >>> 24) == 0) return;
        int pm = Pixmap.premultiply(color);
        float blur = hints.shadowBlur();
        float sigma = blur / 2f;
        float scale = (float) res.pose().meanScale();
        float qx0 = el.x(), qy0 = el.y(), qx1 = el.x() + el.width(), qy1 = el.y() + el.height();
        ShadowShape shape = hints.shadowShape();

        if (shape != null && shape.inset()) {
            float[] hrx = GpuAttempt.shapeRx(shape);
            float[] hry = GpuAttempt.shapeRy(shape);
            if (GpuAttempt.insetShadow(backend.route.current(), res.pose(), qx0, qy0, qx1, qy1, rx, ry, sigma, shape, color, clip, opacity)) return;
            final float[] crx = backend.freezeRadii(rx), cry = backend.freezeRadii(ry);
            backend.paint(res.pose(), qx0 - 1, qy0 - 1, qx1 + 1, qy1 + 1, clip, opacity, (lx, ly) -> {
                float inRect = SoftShapes.coverage(SoftShapes.sdRoundRect(lx, ly, qx0, qy0, qx1, qy1, crx, cry), scale);
                if (inRect <= 0f) return 0;
                float sd = SoftShapes.sdRoundRect(lx, ly, shape.x0(), shape.y0(), shape.x1(), shape.y1(), hrx, hry);
                float a = SoftShapes.blurredEdge(-sd, sigma);
                return a <= 0f ? 0 : SoftShapes.scalePremul(pm, a * inRect);
            });
            return;
        }

        ShapeGeometry.OuterShadow os = ShapeGeometry.outerShadow(qx0, qy0, qx1, qy1, blur, rx, ry);
        if (os == null) return;
        float sx0 = os.sx0(), sy0 = os.sy0(), sx1 = os.sx1(), sy1 = os.sy1();
        float[] srx = os.srx(), sry = os.sry();
        float[] hrx = null, hry = null;
        if (shape != null) {
            hrx = GpuAttempt.shapeRx(shape);
            hry = GpuAttempt.shapeRy(shape);
        }
        final float[] holeRx = hrx, holeRy = hry;
        if (GpuAttempt.outerShadow(backend.route.current(), res.pose(), qx0, qy0, qx1, qy1, sx0, sy0, sx1, sy1, srx, sry, sigma, shape, color, clip, opacity)) return;
        backend.paint(res.pose(), qx0, qy0, qx1, qy1, clip, opacity, (lx, ly) -> {
            float a = SoftShapes.blurredEdge(SoftShapes.sdRoundRect(lx, ly, sx0, sy0, sx1, sy1, srx, sry), sigma);
            if (a <= 0f) return 0;
            if (shape != null) {
                float hole = SoftShapes.coverage(SoftShapes.sdRoundRect(lx, ly, shape.x0(), shape.y0(), shape.x1(), shape.y1(), holeRx, holeRy), scale);
                a *= 1f - hole;
            }
            return a <= 0f ? 0 : SoftShapes.scalePremul(pm, a);
        });
    }

    void drawRoundedBorder(DisplayItem el, RoundedBorderSpec spec, float[] rx, float[] ry,
                                   Resolved res, Clip clip, float opacity) {
        ShapeGeometry.Border g = ShapeGeometry.border(el.x(), el.y(), el.width(), el.height(), spec, rx, ry);
        float x0 = g.x0(), y0 = g.y0(), x1 = g.x1(), y1 = g.y1();
        float ix0 = g.ix0(), iy0 = g.iy0(), ix1 = g.ix1(), iy1 = g.iy1();
        boolean innerEmpty = g.innerEmpty();
        float[] irx = g.irx(), iry = g.iry(), widths = g.widths(), pcx = g.pcx(), pcy = g.pcy();
        int[] colors = g.colors();
        float wt = widths[0], wr = widths[1], wb = widths[2], wl = widths[3];
        float bx = g.bx(), by = g.by(), cxm = g.cxm(), cym = g.cym();
        BorderDashPattern pattern = g.dash();
        final BorderDashPattern dash = pattern;
        if (dash != null || spec.dashKind() == BorderPath.SOLID) {
            boolean taken = dash == null
                    ? GpuAttempt.roundBorder(backend.route.current(), res.pose(), x0, y0, x1, y1, rx, ry, ix0, iy0, ix1, iy1, irx, iry, innerEmpty,
                            spec, widths, clip, opacity)
                    : GpuAttempt.dashedBorder(backend.route.current(), res.pose(), x0, y0, x1, y1, rx, ry, ix0, iy0, ix1, iy1, irx, iry, innerEmpty,
                            spec, widths, bx, by, cxm, cym, pcx, pcy, dash, wt, clip, opacity);
            if (taken) return;
        }
        final float fbx = bx, fby = by;
        final float thickness = wt;

        float scale = (float) res.pose().meanScale();
        float m = 1f / Math.max(scale, 0.01f);
        // 距離を測らずに済む領域。穴（内側の角丸矩形から角の最大半径と縁の 0.5 画素を除いた内側）は内側の被覆率がちょうど 1 で何も塗らない。
        // 4 辺の直線部分は、外側の被覆率が 1・内側が 0 で、辺の色の判定も一意（その辺の比が 1 未満、ほかの辺は 1 以上）な帯
        Fast fast = new Fast();
        if (!innerEmpty) {
            float reach = 0.5f / Math.max(scale, 0.01f) + 1e-3f;
            float mx = SoftShapes.maxOf(irx), my = SoftShapes.maxOf(iry);
            fast.roundRect(ix0, iy0, ix1, iy1, irx, iry, reach, 0); // 穴（円の角なら行ごとに、そうでなければ矩形で）
            boolean circles = true; // 円の角だけ（楕円の近似距離では「枠の外ならいくら以上」を保証しない）
            float hx = (x1 - x0) * 0.5f, hy = (y1 - y0) * 0.5f, ihx = (ix1 - ix0) * 0.5f, ihy = (iy1 - iy0) * 0.5f;
            for (int i = 0; i < 4; i++) {
                if (rx[i] != ry[i] || rx[i] > hx || ry[i] > hy || irx[i] != iry[i] || irx[i] > ihx || iry[i] > ihy) circles = false;
            }
            if (dash == null && circles) {
                float e = reach + 2e-3f;
                fast.rect(x0 + Math.max(rx[0], wl) + e, y0 + e, x1 - Math.max(rx[1], wr) - e, iy0 - e, colors[0]);
                fast.rect(ix1 + e, y0 + Math.max(ry[1], wt) + e, x1 - e, y1 - Math.max(ry[2], wb) - e, colors[1]);
                fast.rect(x0 + Math.max(rx[3], wl) + e, iy1 + e, x1 - Math.max(rx[2], wr) - e, y1 - e, colors[2]);
                fast.rect(x0 + e, y0 + Math.max(ry[0], wt) + e, ix0 - e, y1 - Math.max(ry[3], wb) - e, colors[3]);
            }
        }
        final float[] crx = backend.freezeRadii(rx), cry = backend.freezeRadii(ry);
        backend.paint(res.pose(), x0 - m, y0 - m, x1 + m, y1 + m, clip, opacity, (lx, ly) -> {
            float outer = SoftShapes.coverage(SoftShapes.sdRoundRect(lx, ly, x0, y0, x1, y1, crx, cry), scale);
            if (outer <= 0f) return 0;
            float inner = innerEmpty ? 0f
                    : SoftShapes.coverage(SoftShapes.sdRoundRect(lx, ly, ix0, iy0, ix1, iy1, irx, iry), scale);
            float cov = outer * (1f - inner);
            if (cov <= 0f) return 0;
            // 辺の色: 外側の角と内側の角を結ぶ線で辺を分ける（辺からの距離 / 太さが小さい辺）
            int side = -1;
            float best = Float.MAX_VALUE;
            if (widths[0] > 0f) { float v = (ly - y0) / widths[0]; if (v < best) { best = v; side = 0; } }
            if (widths[1] > 0f) { float v = (x1 - lx) / widths[1]; if (v < best) { best = v; side = 1; } }
            if (widths[2] > 0f) { float v = (y1 - ly) / widths[2]; if (v < best) { best = v; side = 2; } }
            if (widths[3] > 0f) { float v = (lx - x0) / widths[3]; if (v < best) { best = v; side = 3; } }
            if (side < 0) return 0;
            int c = colors[side];
            if (dash != null) {
                float s = BorderPath.param(lx - cxm, ly - cym, fbx, fby, pcx, pcy);
                float phase = s - dash.period() * (float) Math.floor(s / dash.period());
                float along = Math.abs(phase - dash.period() / 2f);
                float on;
                if (dash.round()) {
                    float perp = Math.abs(SoftShapes.sdRoundRect(lx, ly, x0, y0, x1, y1, crx, cry) + thickness / 2f);
                    on = Math.clamp(dash.dash() / 2f - (float) Math.hypot(along, perp) + 0.5f, 0f, 1f);
                } else {
                    on = Math.clamp(dash.dash() / 2f - along + 0.5f, 0f, 1f);
                }
                cov *= on;
                if (cov <= 0f) return 0;
            }
            return SoftShapes.scalePremul(c, cov);
        }, fast);
    }

    /** 円の枠線（円の外半径 = 短い辺の半分、4 方向で色が違ってよい）。描いたら true。 */
    boolean drawCircleBorder(DisplayItem el, AnimatedValues anim, Resolved res, Clip clip, float opacity) {
        ShapeGeometry.Circle c = ShapeGeometry.circle(el, anim);
        if (c == null) return false;
        int[] colors = c.premul();
        float cx = c.cx(), cy = c.cy(), outerR = c.outerR(), innerR = c.innerR();
        if (GpuAttempt.circleBorder(backend.route.current(), res.pose(), cx, cy, outerR, innerR, c.bx0(), c.by0(), c.bx1(), c.by1(),
                c.straight(), clip, opacity)) return true;
        float scale = (float) res.pose().meanScale();
        backend.paint(res.pose(), c.bx0(), c.by0(), c.bx1(), c.by1(), clip, opacity, (lx, ly) -> {
            float dx = lx - cx, dy = ly - cy;
            float dist = (float) Math.hypot(dx, dy);
            float cov = SoftShapes.coverage(dist - outerR, scale) * (innerR > 0f ? 1f - SoftShapes.coverage(dist - innerR, scale) : 1f);
            if (cov <= 0f) return 0;
            int side = Math.abs(dy) >= Math.abs(dx) ? (dy < 0 ? 0 : 2) : (dx > 0 ? 1 : 3);
            return SoftShapes.scalePremul(colors[side], cov);
        });
        return true;
    }

    void drawImage(DisplayItem el, float[] rx, float[] ry, Resolved res, Clip clip, float opacity) {
        String src = el.text();
        if (src == null || src.isEmpty()) return;
        float x0 = el.x(), y0 = el.y(), w = el.width(), h = el.height();
        float scale = (float) res.pose().meanScale();
        TextureRef texture = backend.route.current() != null ? TextureRef.parse(src) : null;
        if (texture != null && GpuAttempt.externalTexture(backend.route.current(), el, texture, rx, ry, res.pose(), clip, opacity, scale)) return;
        ImageProvider.Image img = backend.resources.images().load(el, (int) Math.ceil(w * scale), (int) Math.ceil(h * scale));
        if (img == null || img.pixmap().width() <= 0 || img.pixmap().height() <= 0) return;
        Pixmap pm = img.pixmap();
        float iw = img.cssW() > 0 ? img.cssW() : pm.width(), ih = img.cssH() > 0 ? img.cssH() : pm.height();
        ShapeGeometry.Image gi = ShapeGeometry.image(el, iw, ih, pm.width(), pm.height(), scale);
        if (gi == null) return;
        float px0 = gi.px0(), py0 = gi.py0(), sx = gi.sx(), sy = gi.sy();
        int taps = gi.taps();
        if (GpuAttempt.image(backend.route.current(), res.pose(), pm, x0, y0, w, h, px0, py0, gi.plW(), gi.plH(), sx, sy, rx, ry, taps, scale, clip, opacity)) return;
        final float[] crx = backend.freezeRadii(rx), cry = backend.freezeRadii(ry);
        backend.paint(res.pose(), gi.bx0(), gi.by0(), gi.bx1(), gi.by1(),
                clip, opacity, (lx, ly) -> {
                    float box = SoftShapes.coverage(SoftShapes.sdRoundRect(lx, ly, x0, y0, x0 + w, y0 + h, crx, cry), scale);
                    if (box <= 0f) return 0;
                    float u = (lx - px0) * sx, v = (ly - py0) * sy;
                    if (u < -0.5f || v < -0.5f || u > pm.width() + 0.5f || v > pm.height() + 0.5f) return 0;
                    int c = taps == 1 ? SoftwareRenderBackend.bilinear(pm, u - 0.5f, v - 0.5f) : boxSample(pm, u, v, taps, sx / scale, sy / scale);
                    return c == 0 ? 0 : SoftShapes.scalePremul(c, box);
                });
    }

    private static int boxSample(Pixmap pm, float u, float v, int taps, float stepX, float stepY) {
        float a = 0, r = 0, g = 0, b = 0;
        for (int j = 0; j < taps; j++) {
            for (int i = 0; i < taps; i++) {
                float su = u + ((i + 0.5f) / taps - 0.5f) * stepX, sv = v + ((j + 0.5f) / taps - 0.5f) * stepY;
                int c = SoftwareRenderBackend.bilinear(pm, su - 0.5f, sv - 0.5f);
                a += c >>> 24;
                r += (c >> 16) & 0xFF;
                g += (c >> 8) & 0xFF;
                b += c & 0xFF;
            }
        }
        float n = taps * taps;
        return (Math.round(a / n) << 24) | (Math.round(r / n) << 16) | (Math.round(g / n) << 8) | Math.round(b / n);
    }

    void drawProgress(DisplayItem el, AnimatedValues anim, float[] rx, float[] ry, Resolved res, Clip clip, float opacity) {
        float min = anim.rangeMin, max = anim.rangeMax, val = anim.rangeVal;
        if (parseRangeInto(el.text(), backend.scratchRange)) {
            val = backend.scratchRange[0];
            min = backend.scratchRange[1];
            max = backend.scratchRange[2];
        }
        float ratio = max > min ? Math.clamp((val - min) / (max - min), 0f, 1f) : 0f;
        float fillW = el.width() * ratio;
        if (fillW <= 0.5f) return;
        int color = anim.color() != 0 ? anim.color() : 0xFF28A745;
        boolean rightRound = ratio > 0.98f;
        backend.scratchRadA[0] = rx[0]; backend.scratchRadA[1] = rightRound ? rx[1] : 0f; backend.scratchRadA[2] = rightRound ? rx[2] : 0f; backend.scratchRadA[3] = rx[3];
        backend.scratchRadB[0] = ry[0]; backend.scratchRadB[1] = rightRound ? ry[1] : 0f; backend.scratchRadB[2] = rightRound ? ry[2] : 0f; backend.scratchRadB[3] = ry[3];
        backend.fillRoundRect(res.pose(), el.x(), el.y(), el.x() + fillW, el.y() + el.height(), backend.scratchRadA, backend.scratchRadB, color, clip, opacity);
    }

    private static boolean parseRangeInto(String text, float[] out3) {
        if (text == null) return false;
        int c1 = text.indexOf(':');
        if (c1 < 0) return false;
        int c2 = text.indexOf(':', c1 + 1);
        if (c2 < 0) return false;
        int c3 = text.indexOf(':', c2 + 1);
        try {
            out3[0] = Float.parseFloat(text.substring(0, c1));
            out3[1] = Float.parseFloat(text.substring(c1 + 1, c2));
            out3[2] = Float.parseFloat(c3 < 0 ? text.substring(c2 + 1) : text.substring(c2 + 1, c3));
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Chromium のスライダーと同じ形: 溝は高さ 8px、つまみは直径 16px で中心が両端から 8px の間を動く。 */
    void drawRange(DisplayItem el, AnimatedValues anim, Resolved res, Clip clip, float opacity) {
        float val = anim.rangeVal, min = anim.rangeMin, max = anim.rangeMax;
        if (parseRangeInto(el.text(), backend.scratchRange)) {
            val = backend.scratchRange[0];
            min = backend.scratchRange[1];
            max = backend.scratchRange[2];
        }
        float ratio = max > min ? Math.clamp((val - min) / (max - min), 0f, 1f) : 0f;
        float cy = el.y() + el.height() / 2f;
        float trackTop = cy - SoftwareRenderBackend.RANGE_TRACK_HEIGHT / 2, trackBottom = cy + SoftwareRenderBackend.RANGE_TRACK_HEIGHT / 2;
        float trackRad = SoftwareRenderBackend.RANGE_TRACK_HEIGHT / 2, thumbRad = SoftwareRenderBackend.RANGE_THUMB_SIZE / 2;
        float left = el.x(), right = el.x() + el.width();
        float tcx = left + thumbRad + ratio * Math.max(0f, el.width() - SoftwareRenderBackend.RANGE_THUMB_SIZE);
        Matrix2D pose = res.pose();
        int fill = anim.bgColor();
        if ((fill >>> 24) != 0) {
            RenderMeta meta = el.meta();
            int edge = meta != null && meta.roundedBorder() != null ? meta.roundedBorder().colorTop() : 0;
            int accent = anim.color() | 0xFF000000;
            float[] rad = backend.fill4(backend.scratchRadA, trackRad);
            backend.fillRoundRect(pose, left, trackTop, right, trackBottom, rad, rad, backend.over(edge, fill), clip, opacity);
            rad = backend.fill4(backend.scratchRadA, trackRad - 1);
            backend.fillRoundRect(pose, left + 1, trackTop + 1, right - 1, trackBottom - 1, rad, rad, fill, clip, opacity);
            if (tcx > left) {
                float r = Math.min(trackRad, (tcx - left) / 2);
                rad = backend.fillLeft2(backend.scratchRadA, r);
                backend.fillRoundRect(pose, left, trackTop, tcx, trackBottom, rad, rad, backend.over(edge, accent), clip, opacity);
                if (tcx > left + 1) {
                    float ri = Math.max(0f, Math.min(trackRad - 1, (tcx - left - 1) / 2));
                    rad = backend.fillLeft2(backend.scratchRadB, ri);
                    backend.fillRoundRect(pose, left + 1, trackTop + 1, tcx, trackBottom - 1, rad, rad, accent, clip, opacity);
                }
            }
        }
        float[] tr = backend.fill4(backend.scratchRadA, thumbRad);
        backend.fillRoundRect(pose, tcx - thumbRad, cy - thumbRad, tcx + thumbRad, cy + thumbRad, tr, tr, anim.color(), clip, opacity);
    }

    void drawToggle(DisplayItem el, AnimatedValues anim, Resolved res, Clip clip, float opacity) {
        boolean mixed = el.type() == ElementType.CHECKBOX && "mixed".equals(el.text());
        if (!"true".equals(el.text()) && !mixed) return;
        int color = anim.color();
        if ((color >>> 24) == 0) return;
        float x = el.x(), y = el.y(), w = el.width(), h = el.height();
        Matrix2D pose = res.pose();
        if (el.type() == ElementType.RADIO) {
            float pad = w * 0.2f;
            float dot = (w - 2 * pad) / 2f;
            float[] rad = backend.fill4(backend.scratchRadA, dot);
            backend.fillRoundRect(pose, x + pad, y + h / 2f - dot, x + w - pad, y + h / 2f + dot, rad, rad, color, clip, opacity);
        } else if (el.type() == ElementType.CHECKBOX) {
            if (mixed) {
                float radius = Math.min(2f, h * 0.1f);
                float[] rad = backend.fill4(backend.scratchRadA, radius);
                backend.fillRoundRect(pose, x + w * 0.2f, y + h * 0.4f, x + w * 0.8f, y + h * 0.6f, rad, rad, color, clip, opacity);
            } else {
                // Chromium の NativeThemeBase::PaintCheckbox: (0.2w, 0.5h) → 右下へ (0.2w, 0.2h) → (0.8w, 0.2h)、太さ 0.16h
                float stroke = h * 0.16f;
                float x0 = x + w * 0.2f, y0 = y + h * 0.5f, x1 = x0 + w * 0.2f, y1 = y0 + h * 0.2f, x2 = x + w * 0.8f, y2 = y + h * 0.2f;
                drawSegment(pose, x0, y0, x1, y1, stroke, color, clip, opacity);
                drawSegment(pose, x1, y1, x2, y2, stroke, color, clip, opacity);
            }
        } else {
            float pad = w * 0.25f;
            backend.fillRect(pose, x + pad, y + pad, x + w - pad, y + h - pad, color, clip, opacity);
        }
    }

    /** 太さ {@code width} の線分（両端は丸）。 */
    private void drawSegment(Matrix2D pose, float x0, float y0, float x1, float y1, float width, int argb, Clip clip, float opacity) {
        float half = width / 2f;
        float[] segRad = backend.fill4(backend.scratchRadA, half);
        float dx = x1 - x0, dy = y1 - y0;
        double len = Math.hypot(dx, dy);
        if (len <= 1e-4) {
            backend.fillRoundRect(pose, x0 - half, y0 - half, x0 + half, y0 + half, segRad, segRad, argb, clip, opacity);
            return;
        }
        double c = dx / len, s = dy / len;
        Matrix2D segPose = new Matrix2D(
                pose.a() * c + pose.c() * s,
                pose.b() * c + pose.d() * s,
                pose.c() * c - pose.a() * s,
                pose.d() * c - pose.b() * s,
                pose.a() * x0 + pose.c() * y0 + pose.e(),
                pose.b() * x0 + pose.d() * y0 + pose.f());
        backend.fillRoundRect(segPose, -half, -half, (float) len + half, half, segRad, segRad, argb, clip, opacity);
    }
}
