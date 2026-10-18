package com.nishiyu.enderframelib.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nishiyu.enderframelib.client.renderer.WebRenderCore.ZLayers;
import com.nishiyu.enderframelib.core.model.UIElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * <canvas id="..."> 要素に対してJava側から動的な図形・ベジェ曲線・ノードリンク・グラフ・テキストを描画するための2Dコンテキスト
 */
public class WebCanvasContext {

    private static final Map<String, Map<String, WebCanvasContext>> SESSION_CANVASES = new ConcurrentHashMap<>();

    public static WebCanvasContext getOrCreate(String sessionId, String canvasId) {
        String safeSessionId = (sessionId != null && !sessionId.isEmpty()) ? sessionId : "unknown";
        String baseId = stripCanvasSuffix(canvasId);
        return SESSION_CANVASES
                .computeIfAbsent(safeSessionId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(baseId, k -> new WebCanvasContext());
    }

    public static WebCanvasContext get(String sessionId, String canvasId) {
        if (sessionId == null || canvasId == null) return null;
        Map<String, WebCanvasContext> map = SESSION_CANVASES.get(sessionId);
        return map != null ? map.get(stripCanvasSuffix(canvasId)) : null;
    }

    public static void clearSession(String sessionId) {
        if (sessionId != null) {
            SESSION_CANVASES.remove(sessionId);
        }
    }

    private static String stripCanvasSuffix(String id) {
        return (id != null && id.endsWith("_canvas")) ? id.substring(0, id.length() - 7) : id;
    }

    private final List<DrawCmd> commands = new CopyOnWriteArrayList<>();

    public WebCanvasContext clear() {
        commands.clear();
        return this;
    }

    public WebCanvasContext fillRect(float x, float y, float w, float h, int argbColor) {
        if (w <= 0f || h <= 0f || !Float.isFinite(x) || !Float.isFinite(y)) return this;
        commands.add(new FillRectCmd(x, y, w, h, argbColor, 0f));
        return this;
    }

    public WebCanvasContext fillRoundedRect(float x, float y, float w, float h, float radius, int argbColor) {
        if (w <= 0f || h <= 0f || !Float.isFinite(x) || !Float.isFinite(y)) return this;
        commands.add(new FillRectCmd(x, y, w, h, argbColor, Math.max(0f, radius)));
        return this;
    }

    public WebCanvasContext drawLine(float x1, float y1, float x2, float y2, float thickness, int argbColor) {
        if (thickness <= 0f || !Float.isFinite(x1) || !Float.isFinite(y1) || !Float.isFinite(x2) || !Float.isFinite(y2)) {
            return this;
        }
        commands.add(new LineCmd(x1, y1, x2, y2, thickness, argbColor));
        return this;
    }

    public WebCanvasContext drawBezier(float x1, float y1, float cx1, float cy1, float cx2, float cy2, float x2, float y2, float thickness, int argbColor) {
        if (thickness <= 0f) return this;
        int segments = 20;
        List<DrawCmd> batch = new ArrayList<>(segments);
        float prevX = x1, prevY = y1;
        for (int i = 1; i <= segments; i++) {
            float t = (float) i / segments;
            float u = 1.0f - t;
            float tt = t * t, uu = u * u;
            float uuu = uu * u, ttt = tt * t;

            float px = uuu * x1 + 3 * uu * t * cx1 + 3 * u * tt * cx2 + ttt * x2;
            float py = uuu * y1 + 3 * uu * t * cy1 + 3 * u * tt * cy2 + ttt * y2;
            batch.add(new LineCmd(prevX, prevY, px, py, thickness, argbColor));
            prevX = px;
            prevY = py;
        }
        commands.addAll(batch);
        return this;
    }

    public WebCanvasContext drawNodeLink(float x1, float y1, float x2, float y2, float thickness, int argbColor) {
        float curvature = Math.max(30.0f, Math.abs(x2 - x1) * 0.5f);
        return drawBezier(x1, y1, x1 + curvature, y1, x2 - curvature, y2, x2, y2, thickness, argbColor);
    }

    public WebCanvasContext drawArrow(float x1, float y1, float x2, float y2, float thickness, float headSize, int argbColor) {
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

        commands.add(new LineCmd(x2, y2, wx1, wy1, thickness, argbColor));
        commands.add(new LineCmd(x2, y2, wx2, wy2, thickness, argbColor));
        return this;
    }

    public WebCanvasContext drawSparkline(float[] normalizedValues, float areaX, float areaY, float areaW, float areaH, float thickness, int lineColor, int fillColor) {
        if (normalizedValues == null || normalizedValues.length < 2 || areaW <= 0f || areaH <= 0f) return this;
        int n = normalizedValues.length;
        float stepX = areaW / (float) (n - 1);
        List<DrawCmd> batch = new ArrayList<>((n - 1) * 2);

        for (int i = 0; i < n - 1; i++) {
            float raw0 = Float.isFinite(normalizedValues[i]) ? normalizedValues[i] : 0f;
            float raw1 = Float.isFinite(normalizedValues[i + 1]) ? normalizedValues[i + 1] : 0f;
            float v0 = Math.clamp(raw0, 0f, 1f);
            float v1 = Math.clamp(raw1, 0f, 1f);
            float x0 = areaX + i * stepX;
            float y0 = areaY + areaH - (v0 * areaH);
            float x1 = areaX + (i + 1) * stepX;
            float y1 = areaY + areaH - (v1 * areaH);

            if ((fillColor >>> 24) > 0) {
                float midY = (y0 + y1) * 0.5f;
                float fillH = (areaY + areaH) - midY;
                if (fillH > 0.05f) {
                    batch.add(new FillRectCmd(x0, midY, Math.max(1f, x1 - x0), fillH, fillColor, 0f));
                }
            }
            batch.add(new LineCmd(x0, y0, x1, y1, Math.max(0.5f, thickness), lineColor));
        }
        commands.addAll(batch);
        return this;
    }

    public WebCanvasContext drawCircle(float cx, float cy, float radius, float thickness, int argbColor) {
        if (radius <= 0f || !Float.isFinite(cx) || !Float.isFinite(cy)) return this;
        commands.add(new CircleCmd(cx, cy, radius, thickness, argbColor));
        return this;
    }

    public WebCanvasContext drawText(String text, float x, float y, int argbColor, float scale) {
        if (text == null || text.isEmpty() || scale <= 0f || !Float.isFinite(x) || !Float.isFinite(y)) return this;
        commands.add(new TextCmd(text, x, y, argbColor, scale));
        return this;
    }

    public void renderCommands(UIElement el, PoseStack poseStack, MultiBufferSource buffer, float finalOpacity, float zOffset,
                               float clipMinX, float clipMinY, float clipMaxX, float clipMaxY,
                               float cRTL, float cRTR, float cRBR, float cRBL, int packedLight) {
        if (this.commands.isEmpty() || finalOpacity <= 0.005f) return;

        float originX = el.x();
        float originY = el.y();
        float cMinX = Math.max(clipMinX, originX);
        float cMinY = Math.max(clipMinY, originY);
        float cMaxX = Math.min(clipMaxX, originX + el.width());
        float cMaxY = Math.min(clipMaxY, originY + el.height());
        if (cMinX >= cMaxX || cMinY >= cMaxY) return;

        Matrix4f mat = poseStack.last().pose();
        VertexConsumer guiBuilder = null;
        float cmdZ = zOffset;
        List<ScheduledText> deferredTexts = null;

        for (DrawCmd cmd : this.commands) {
            cmdZ += ZLayers.SUB_Z_OFFSET;
            switch (cmd) {
                case TextCmd txt -> {
                    if (deferredTexts == null) deferredTexts = new ArrayList<>();
                    deferredTexts.add(new ScheduledText(txt, cmdZ));
                }
                case FillRectCmd rect -> {
                    float a = ((rect.color >>> 24) & 0xFF) / 255.0f * finalOpacity;
                    if (a <= 0.001f) continue;
                    float r = ((rect.color >>> 16) & 0xFF) / 255.0f;
                    float g = ((rect.color >>> 8) & 0xFF) / 255.0f;
                    float b = (rect.color & 0xFF) / 255.0f;

                    if (guiBuilder == null) guiBuilder = buffer.getBuffer(RenderType.gui());
                    if (rect.radius > 0f) {
                        WebRenderCore.drawRoundedRect(
                                guiBuilder, mat,
                                originX + rect.x, originY + rect.y,
                                originX + rect.x + rect.w, originY + rect.y + rect.h,
                                rect.radius, rect.radius, rect.radius, rect.radius,
                                r, g, b, a, cmdZ,
                                cMinX, cMinY, cMaxX, cMaxY, cRTL, cRTR, cRBR, cRBL
                        );
                    } else {
                        WebRenderCore.drawClippedQuad(
                                guiBuilder, mat,
                                originX + rect.x, originY + rect.y,
                                originX + rect.x + rect.w, originY + rect.y + rect.h,
                                r, g, b, a, cmdZ,
                                cMinX, cMinY, cMaxX, cMaxY, cRTL, cRTR, cRBR, cRBL
                        );
                    }
                }
                case LineCmd line -> {
                    float a = ((line.color >>> 24) & 0xFF) / 255.0f * finalOpacity;
                    if (a <= 0.001f) continue;
                    float r = ((line.color >>> 16) & 0xFF) / 255.0f;
                    float g = ((line.color >>> 8) & 0xFF) / 255.0f;
                    float b = (line.color & 0xFF) / 255.0f;

                    float x1 = Math.clamp(originX + line.x1, cMinX, cMaxX);
                    float y1 = Math.clamp(originY + line.y1, cMinY, cMaxY);
                    float x2 = Math.clamp(originX + line.x2, cMinX, cMaxX);
                    float y2 = Math.clamp(originY + line.y2, cMinY, cMaxY);

                    float dx = x2 - x1;
                    float dy = y2 - y1;
                    float len = (float) Math.sqrt(dx * dx + dy * dy);
                    if (len <= 0.001f) continue;

                    float nx = -dy / len * (line.thickness * 0.5f);
                    float ny = dx / len * (line.thickness * 0.5f);

                    if (guiBuilder == null) guiBuilder = buffer.getBuffer(RenderType.gui());
                    guiBuilder.addVertex(mat, x1 + nx, y1 + ny, cmdZ).setColor(r, g, b, a);
                    guiBuilder.addVertex(mat, x2 + nx, y2 + ny, cmdZ).setColor(r, g, b, a);
                    guiBuilder.addVertex(mat, x2 - nx, y2 - ny, cmdZ).setColor(r, g, b, a);
                    guiBuilder.addVertex(mat, x1 - nx, y1 - ny, cmdZ).setColor(r, g, b, a);
                }
                case CircleCmd circle -> {
                    float a = ((circle.color >>> 24) & 0xFF) / 255.0f * finalOpacity;
                    if (a <= 0.001f) continue;

                    float cx = originX + circle.cx;
                    float cy = originY + circle.cy;
                    if (cx + circle.radius < cMinX || cx - circle.radius > cMaxX ||
                            cy + circle.radius < cMinY || cy - circle.radius > cMaxY) {
                        continue;
                    }

                    if (guiBuilder == null) guiBuilder = buffer.getBuffer(RenderType.gui());
                    WebRenderCore.drawRingSegment(
                            guiBuilder, mat, cx, cy, circle.radius,
                            circle.thickness <= 0f ? circle.radius : circle.thickness,
                            0f, (float) (Math.PI * 2.0), circle.color, finalOpacity, cmdZ
                    );
                }
            }
        }

        if (deferredTexts != null) {
            Font font = Minecraft.getInstance().font;
            for (ScheduledText st : deferredTexts) {
                TextCmd txt = st.cmd();
                float tx = originX + txt.x;
                float ty = originY + txt.y;
                if (tx < cMinX || tx > cMaxX || ty < cMinY || ty > cMaxY) continue;

                int a = (int) (((txt.color >>> 24) & 0xFF) * finalOpacity);
                if (a <= 4) continue;

                poseStack.pushPose();
                try {
                    poseStack.translate(tx, ty, st.z());
                    poseStack.scale(txt.scale, txt.scale, 1.0f);
                    font.drawInBatch(
                            txt.text, 0, 0,
                            (a << 24) | (txt.color & 0x00FFFFFF),
                            false, poseStack.last().pose(), buffer,
                            Font.DisplayMode.NORMAL, 0, packedLight
                    );
                } finally {
                    poseStack.popPose();
                }
            }
        }
    }

    private sealed interface DrawCmd permits FillRectCmd, LineCmd, CircleCmd, TextCmd {}
    private record FillRectCmd(float x, float y, float w, float h, int color, float radius) implements DrawCmd {}
    private record LineCmd(float x1, float y1, float x2, float y2, float thickness, int color) implements DrawCmd {}
    private record CircleCmd(float cx, float cy, float radius, float thickness, int color) implements DrawCmd {}
    private record TextCmd(String text, float x, float y, int color, float scale) implements DrawCmd {}
    private record ScheduledText(TextCmd cmd, float z) {}
}