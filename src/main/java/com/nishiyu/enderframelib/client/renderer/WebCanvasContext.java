package com.nishiyu.enderframelib.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nishiyu.enderframelib.core.model.UIElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * <canvas id="..."> 要素に対してJava側から動的な図形・グラフ・テキストを描画するための2Dコンテキスト
 */
public class WebCanvasContext {

    private static final Map<String, WebCanvasContext> CONTEXTS = new ConcurrentHashMap<>();

    public static WebCanvasContext getOrCreate(String sessionId, String canvasId) {
        String key = sessionId + "::" + canvasId;
        return CONTEXTS.computeIfAbsent(key, k -> new WebCanvasContext());
    }

    public static WebCanvasContext get(String sessionId, String canvasId) {
        if (sessionId == null || canvasId == null) return null;
        String baseId = canvasId.endsWith("_canvas") ? canvasId.substring(0, canvasId.length() - 7) : canvasId;
        return CONTEXTS.get(sessionId + "::" + baseId);
    }

    public static void clearSession(String sessionId) {
        String prefix = sessionId + "::";
        CONTEXTS.keySet().removeIf(k -> k.startsWith(prefix));
    }

    private final List<DrawCmd> commands = new CopyOnWriteArrayList<>();

    public WebCanvasContext clear() {
        commands.clear();
        return this;
    }

    public WebCanvasContext fillRect(float x, float y, float w, float h, int argbColor) {
        commands.add(new FillRectCmd(x, y, w, h, argbColor, 0));
        return this;
    }

    public WebCanvasContext fillRoundedRect(float x, float y, float w, float h, float radius, int argbColor) {
        commands.add(new FillRectCmd(x, y, w, h, argbColor, radius));
        return this;
    }

    public WebCanvasContext drawLine(float x1, float y1, float x2, float y2, float thickness, int argbColor) {
        commands.add(new LineCmd(x1, y1, x2, y2, thickness, argbColor));
        return this;
    }

    /**
     * 折れ線グラフ等を一括描画する (values は 0.0f ~ 1.0f に正規化された配列)
     */
    public WebCanvasContext drawSparkline(float[] normalizedValues, float areaX, float areaY, float areaW, float areaH, float thickness, int lineColor, int fillColor) {
        if (normalizedValues == null || normalizedValues.length < 2) return this;
        int n = normalizedValues.length;
        float stepX = areaW / (float) (n - 1);
        for (int i = 0; i < n - 1; i++) {
            float v0 = Math.clamp(normalizedValues[i], 0f, 1f);
            float v1 = Math.clamp(normalizedValues[i + 1], 0f, 1f);
            float x0 = areaX + i * stepX;
            float y0 = areaY + areaH - (v0 * areaH);
            float x1 = areaX + (i + 1) * stepX;
            float y1 = areaY + areaH - (v1 * areaH);
            if ((fillColor >>> 24) > 0) {
                float midY = (y0 + y1) * 0.5f;
                commands.add(new FillRectCmd(x0, midY, Math.max(1f, x1 - x0), (areaY + areaH) - midY, fillColor, 0));
            }
            commands.add(new LineCmd(x0, y0, x1, y1, thickness, lineColor));
        }
        return this;
    }

    public WebCanvasContext drawCircle(float cx, float cy, float radius, float thickness, int argbColor) {
        commands.add(new CircleCmd(cx, cy, radius, thickness, argbColor));
        return this;
    }

    public WebCanvasContext drawText(String text, float x, float y, int argbColor, float scale) {
        commands.add(new TextCmd(text, x, y, argbColor, scale));
        return this;
    }

    public void renderCommands(UIElement el, PoseStack poseStack, MultiBufferSource buffer, float finalOpacity, float zOffset,
                               float clipMinX, float clipMinY, float clipMaxX, float clipMaxY,
                               float cRTL, float cRTR, float cRBR, float cRBL, int packedLight) {
        if (commands.isEmpty()) return;
        float originX = el.x();
        float originY = el.y();
        float cMinX = Math.max(clipMinX, originX);
        float cMinY = Math.max(clipMinY, originY);
        float cMaxX = Math.min(clipMaxX, originX + el.width());
        float cMaxY = Math.min(clipMaxY, originY + el.height());

        Matrix4f mat = poseStack.last().pose();
        Font font = Minecraft.getInstance().font;
        float cmdZ = zOffset;

        for (DrawCmd cmd : commands) {
            cmdZ += 0.0005f;
            if (cmd instanceof FillRectCmd rect) {
                float a = ((rect.color >> 24) & 0xFF) / 255.0f * finalOpacity;
                if (a <= 0) continue;
                float r = ((rect.color >> 16) & 0xFF) / 255.0f, g = ((rect.color >> 8) & 0xFF) / 255.0f, b = (rect.color & 0xFF) / 255.0f;
                VertexConsumer builder = buffer.getBuffer(RenderType.gui());
                if (rect.radius > 0) {
                    WebRenderCore.drawRoundedRect(builder, mat, originX + rect.x, originY + rect.y, originX + rect.x + rect.w, originY + rect.y + rect.h,
                            rect.radius, rect.radius, rect.radius, rect.radius, r, g, b, a, cmdZ, cMinX, cMinY, cMaxX, cMaxY, cRTL, cRTR, cRBR, cRBL);
                } else {
                    WebRenderCore.drawClippedQuad(builder, mat, originX + rect.x, originY + rect.y, originX + rect.x + rect.w, originY + rect.y + rect.h,
                            r, g, b, a, cmdZ, cMinX, cMinY, cMaxX, cMaxY, cRTL, cRTR, cRBR, cRBL);
                }
            } else if (cmd instanceof LineCmd line) {
                float a = ((line.color >> 24) & 0xFF) / 255.0f * finalOpacity;
                if (a <= 0) continue;
                float r = ((line.color >> 16) & 0xFF) / 255.0f, g = ((line.color >> 8) & 0xFF) / 255.0f, b = (line.color & 0xFF) / 255.0f;
                float x1 = originX + line.x1, y1 = originY + line.y1;
                float x2 = originX + line.x2, y2 = originY + line.y2;
                float dx = x2 - x1, dy = y2 - y1;
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len <= 0.001f) continue;
                float nx = -dy / len * (line.thickness * 0.5f);
                float ny = dx / len * (line.thickness * 0.5f);
                VertexConsumer builder = buffer.getBuffer(RenderType.gui());
                builder.addVertex(mat, x1 + nx, y1 + ny, cmdZ).setColor(r, g, b, a);
                builder.addVertex(mat, x2 + nx, y2 + ny, cmdZ).setColor(r, g, b, a);
                builder.addVertex(mat, x2 - nx, y2 - ny, cmdZ).setColor(r, g, b, a);
                builder.addVertex(mat, x1 - nx, y1 - ny, cmdZ).setColor(r, g, b, a);
            } else if (cmd instanceof CircleCmd circle) {
                VertexConsumer builder = buffer.getBuffer(RenderType.gui());
                WebRenderCore.drawRingSegment(builder, mat, originX + circle.cx, originY + circle.cy, circle.radius,
                        circle.thickness <= 0 ? circle.radius : circle.thickness, 0f, (float) (Math.PI * 2.0), circle.color, finalOpacity, cmdZ);
            } else if (cmd instanceof TextCmd txt) {
                float tx = originX + txt.x, ty = originY + txt.y;
                if (tx < cMinX || tx > cMaxX || ty < cMinY || ty > cMaxY) continue;
                int a = (int) (((txt.color >> 24) & 0xFF) * finalOpacity);
                if (a <= 0) continue;
                poseStack.pushPose();
                poseStack.translate(tx, ty, cmdZ);
                poseStack.scale(txt.scale, txt.scale, 1.0f);
                font.drawInBatch(txt.text, 0, 0, (a << 24) | (txt.color & 0x00FFFFFF), false, poseStack.last().pose(), buffer, Font.DisplayMode.NORMAL, 0, packedLight);
                poseStack.popPose();
            }
        }
    }

    private sealed interface DrawCmd permits FillRectCmd, LineCmd, CircleCmd, TextCmd {}
    private record FillRectCmd(float x, float y, float w, float h, int color, float radius) implements DrawCmd {}
    private record LineCmd(float x1, float y1, float x2, float y2, float thickness, int color) implements DrawCmd {}
    private record CircleCmd(float cx, float cy, float radius, float thickness, int color) implements DrawCmd {}
    private record TextCmd(String text, float x, float y, int color, float scale) implements DrawCmd {}
}