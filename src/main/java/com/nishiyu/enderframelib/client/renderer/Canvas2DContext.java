package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.SessionContext;
import com.nishiyu.enderframelib.core.raster.CanvasRenderingContext2D;
import com.nishiyu.enderframelib.core.raster.Paint;
import com.nishiyu.enderframelib.core.raster.Path2D;

import java.util.Map;

/**
 * {@code <canvas id="...">} の描画面。標準の {@link CanvasRenderingContext2D}（HTML Standard の Canvas 2D）を
 * {@link #context2d()} で渡し、矢印・スパークライン・ノード間の曲線のような Java 側の補助はその上の薄い関数にしている。
 *
 * ビットマップの大きさは {@code width} / {@code height} 属性（既定 300×150）で、要素の内容ボックスへは
 * {@code object-fit} で収める。座標はビットマップの画素（CSS px ではない）。属性が変わると仕様どおり内容は消える。
 * 補助関数は呼び出しごとに同期する。{@link #context2d()} を直接使うときは、描画スレッド以外から触る側が自分で排他する。
 *
 * LIMITATION(R8): 画素はソフトウェアで描いて 1 枚のテクスチャにする。{@code filter} と {@code OffscreenCanvas} は持たない。
 */
public class Canvas2DContext {

    public static final int DEFAULT_WIDTH = 300;
    public static final int DEFAULT_HEIGHT = 150;

    public static Canvas2DContext getOrCreate(String sessionId, String canvasId) {
        SessionContext session = ClientWebManager.getOrCreateSession(sessionId);
        return session.canvases().computeIfAbsent(stripCanvasSuffix(canvasId), k -> new Canvas2DContext());
    }

    public static Canvas2DContext get(String sessionId, String canvasId) {
        if (sessionId == null || canvasId == null) return null;
        SessionContext session = ClientWebManager.getSession(sessionId);
        return session != null ? session.canvases().get(stripCanvasSuffix(canvasId)) : null;
    }

    private static String stripCanvasSuffix(String id) {
        return (id != null && id.endsWith("_canvas")) ? id.substring(0, id.length() - 7) : id;
    }

    private final CanvasRenderingContext2D ctx = new CanvasRenderingContext2D(DEFAULT_WIDTH, DEFAULT_HEIGHT);
    /** レイアウトの属性で大きさを決めたか。決める前に描かれた内容は、決まった大きさのビットマップへ左上に写す。 */
    private boolean sized;

    /** 標準の 2D コンテキスト。 */
    public CanvasRenderingContext2D context2d() {
        return ctx;
    }

    public synchronized int width() {
        return ctx.width();
    }

    public synchronized int height() {
        return ctx.height();
    }

    /** ビットマップの大きさを変える（内容は消える。{@code canvas.width = ...} と同じ）。 */
    public synchronized Canvas2DContext resize(int width, int height) {
        ctx.resize(width, height);
        sized = true;
        return this;
    }

    public synchronized Canvas2DContext clear() {
        ctx.save();
        ctx.resetTransform();
        ctx.clearRect(0, 0, ctx.width(), ctx.height());
        ctx.restore();
        return this;
    }

    public synchronized Canvas2DContext fillRect(float x, float y, float w, float h, int argbColor) {
        if (w <= 0f || h <= 0f || !Float.isFinite(x) || !Float.isFinite(y)) return this;
        ctx.save();
        ctx.fillStyle(Paint.solid(argbColor));
        ctx.fillRect(x, y, w, h);
        ctx.restore();
        return this;
    }

    public synchronized Canvas2DContext fillRoundedRect(float x, float y, float w, float h, float radius, int argbColor) {
        if (w <= 0f || h <= 0f || !Float.isFinite(x) || !Float.isFinite(y)) return this;
        ctx.save();
        ctx.fillStyle(Paint.solid(argbColor));
        ctx.beginPath();
        ctx.roundRect(x, y, w, h, Math.max(0f, radius));
        ctx.fill();
        ctx.restore();
        return this;
    }

    public synchronized Canvas2DContext drawLine(float x1, float y1, float x2, float y2, float thickness, int argbColor) {
        if (thickness <= 0f || !Float.isFinite(x1) || !Float.isFinite(y1) || !Float.isFinite(x2) || !Float.isFinite(y2)) {
            return this;
        }
        ctx.save();
        stroke(thickness, argbColor);
        ctx.beginPath();
        ctx.moveTo(x1, y1);
        ctx.lineTo(x2, y2);
        ctx.stroke();
        ctx.restore();
        return this;
    }

    public synchronized Canvas2DContext drawBezier(float x1, float y1, float cx1, float cy1, float cx2, float cy2, float x2, float y2, float thickness, int argbColor) {
        if (thickness <= 0f) return this;
        ctx.save();
        stroke(thickness, argbColor);
        ctx.beginPath();
        ctx.moveTo(x1, y1);
        ctx.bezierCurveTo(cx1, cy1, cx2, cy2, x2, y2);
        ctx.stroke();
        ctx.restore();
        return this;
    }

    public Canvas2DContext drawNodeLink(float x1, float y1, float x2, float y2, float thickness, int argbColor) {
        float curvature = Math.max(30.0f, Math.abs(x2 - x1) * 0.5f);
        return drawBezier(x1, y1, x1 + curvature, y1, x2 - curvature, y2, x2, y2, thickness, argbColor);
    }

    public synchronized Canvas2DContext drawArrow(float x1, float y1, float x2, float y2, float thickness, float headSize, int argbColor) {
        drawLine(x1, y1, x2, y2, thickness, argbColor);
        float dx = x2 - x1, dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len <= 0.001f || headSize <= 0f) return this;

        float angle = (float) Math.atan2(dy, dx);
        float wingAngle = (float) Math.toRadians(28.0);
        float wx1 = x2 - headSize * (float) Math.cos(angle - wingAngle);
        float wy1 = y2 - headSize * (float) Math.sin(angle - wingAngle);
        float wx2 = x2 - headSize * (float) Math.cos(angle + wingAngle);
        float wy2 = y2 - headSize * (float) Math.sin(angle + wingAngle);
        drawLine(x2, y2, wx1, wy1, thickness, argbColor);
        drawLine(x2, y2, wx2, wy2, thickness, argbColor);
        return this;
    }

    public synchronized Canvas2DContext drawSparkline(float[] normalizedValues, float areaX, float areaY, float areaW, float areaH, float thickness, int lineColor, int fillColor) {
        if (normalizedValues == null || normalizedValues.length < 2 || areaW <= 0f || areaH <= 0f) return this;
        int n = normalizedValues.length;
        float stepX = areaW / (float) (n - 1);
        Path2D line = new Path2D();
        for (int i = 0; i < n; i++) {
            float raw = Float.isFinite(normalizedValues[i]) ? normalizedValues[i] : 0f;
            float v = Math.clamp(raw, 0f, 1f);
            float x = areaX + i * stepX;
            float y = areaY + areaH - (v * areaH);
            if (i == 0) line.moveTo(x, y);
            else line.lineTo(x, y);
        }
        ctx.save();
        if ((fillColor >>> 24) > 0) {
            Path2D area = new Path2D(line);
            area.lineTo(areaX + areaW, areaY + areaH);
            area.lineTo(areaX, areaY + areaH);
            area.closePath();
            ctx.fillStyle(Paint.solid(fillColor));
            ctx.fill(area, false);
        }
        stroke(Math.max(0.5f, thickness), lineColor);
        ctx.lineJoin("round");
        ctx.stroke(line);
        ctx.restore();
        return this;
    }

    /** {@code thickness} が 0 以下なら塗りつぶした円、そうでなければ外半径 {@code radius} の輪。 */
    public synchronized Canvas2DContext drawCircle(float cx, float cy, float radius, float thickness, int argbColor) {
        if (radius <= 0f || !Float.isFinite(cx) || !Float.isFinite(cy)) return this;
        ctx.save();
        ctx.beginPath();
        if (thickness <= 0f || thickness >= radius) {
            ctx.fillStyle(Paint.solid(argbColor));
            ctx.arc(cx, cy, radius, 0, Math.PI * 2, false);
            ctx.fill();
        } else {
            stroke(thickness, argbColor);
            ctx.arc(cx, cy, radius - thickness / 2f, 0, Math.PI * 2, false);
            ctx.stroke();
        }
        ctx.restore();
        return this;
    }

    /** {@code scale} 倍の Minecraft の文字の高さ（9px）で、左上を {@code (x, y)} に置く。 */
    public synchronized Canvas2DContext drawText(String text, float x, float y, int argbColor, float scale) {
        if (text == null || text.isEmpty() || scale <= 0f || !Float.isFinite(x) || !Float.isFinite(y)) return this;
        ctx.save();
        ctx.fonts(WebFontManager.McFontResolver.INSTANCE);
        ctx.font(9f * scale + "px sans-serif");
        ctx.textAlign("left");
        ctx.textBaseline("top");
        ctx.fillStyle(Paint.solid(argbColor));
        ctx.fillText(text, x, y);
        ctx.restore();
        return this;
    }

    private void stroke(float thickness, int argbColor) {
        ctx.strokeStyle(Paint.solid(argbColor));
        ctx.lineWidth(thickness);
        ctx.lineCap("butt");
    }

    // ------------------------------------------------------------------ 要素との結び付き

    /**
     * 要素の {@code width} / {@code height} 属性に大きさを合わせる（描画項目の付帯情報から）。違えば仕様どおり内容は消える。
     * ただし、まだレイアウトから大きさを受けていない間に描かれた内容は、新しいビットマップの左上へ写して残す。
     */
    public synchronized void adoptSize(Map<String, String> events) {
        int w = dimension(events == null ? null : events.get("canvas-width"), DEFAULT_WIDTH);
        int h = dimension(events == null ? null : events.get("canvas-height"), DEFAULT_HEIGHT);
        if (w != ctx.width() || h != ctx.height()) {
            if (!sized && ctx.modCount() > 0) {
                var old = ctx.bitmap().copy();
                ctx.resize(w, h);
                var bmp = ctx.bitmap();
                for (int y = 0; y < Math.min(h, old.height()); y++) {
                    for (int x = 0; x < Math.min(w, old.width()); x++) bmp.set(x, y, old.get(x, y));
                }
            } else {
                ctx.resize(w, h);
            }
        }
        sized = true;
    }

    private static int dimension(String attr, int def) {
        if (attr == null || attr.isBlank()) return def;
        try {
            int v = Integer.parseInt(attr.trim());
            return v >= 0 && v <= 8192 ? Math.max(1, v) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 描画用のテクスチャ。何も描いていなければ null。 */
    synchronized WebMediaManager.ImageInfo texture(String key) {
        if (ctx.modCount() == 0) return null;
        return RasterTextures.textureFor(key, ctx.bitmap(), ctx.modCount(), ctx.width(), ctx.height());
    }
}
