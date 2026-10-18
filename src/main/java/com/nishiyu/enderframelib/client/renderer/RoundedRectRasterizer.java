package com.nishiyu.enderframelib.client.renderer;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nishiyu.enderframelib.core.model.UIElement.ClipRect;
import com.nishiyu.enderframelib.core.model.UIElement.Corners;
import org.joml.Matrix4f;

/**
 * 角丸矩形・グラデーション・テクスチャ付き矩形などのラスタライズ幾何ユーティリティ。
 */
public final class RoundedRectRasterizer {

    private RoundedRectRasterizer() {}

    /**
     * 円弧角の内側への食い込み量（インセット）を計算する。
     * @param radius 角丸半径
     * @param distFromCenter 円弧中心からの法線距離（0〜radius）
     * @return インセット量（px）
     */
    public static float computeCornerInset(float radius, float distFromCenter) {
        if (radius <= 0f || distFromCenter <= 0f) return 0f;
        float clamped = Math.min(radius, distFromCenter);
        return radius - (float) Math.sqrt(Math.max(0f, radius * radius - clamped * clamped));
    }

    public static void addQuadRaw(VertexConsumer builder, Matrix4f matrix,
                                  float minX, float minY, float maxX, float maxY,
                                  float r, float g, float b, float a, float zOffset) {
        builder.addVertex(matrix, minX, maxY, zOffset).setColor(r, g, b, a);
        builder.addVertex(matrix, maxX, maxY, zOffset).setColor(r, g, b, a);
        builder.addVertex(matrix, maxX, minY, zOffset).setColor(r, g, b, a);
        builder.addVertex(matrix, minX, minY, zOffset).setColor(r, g, b, a);
    }

    public static void addTexturedQuadRaw(VertexConsumer builder, Matrix4f matrix,
                                          float minX, float minY, float maxX, float maxY,
                                          float u0, float v0, float u1, float v1,
                                          float r, float g, float b, float a, float zOffset,
                                          int packedLight, int packedOverlay) {
        builder.addVertex(matrix, minX, maxY, zOffset).setColor(r, g, b, a).setUv(u0, v1).setOverlay(packedOverlay).setLight(packedLight).setNormal(0, 0, 1);
        builder.addVertex(matrix, maxX, maxY, zOffset).setColor(r, g, b, a).setUv(u1, v1).setOverlay(packedOverlay).setLight(packedLight).setNormal(0, 0, 1);
        builder.addVertex(matrix, maxX, minY, zOffset).setColor(r, g, b, a).setUv(u1, v0).setOverlay(packedOverlay).setLight(packedLight).setNormal(0, 0, 1);
        builder.addVertex(matrix, minX, minY, zOffset).setColor(r, g, b, a).setUv(u0, v0).setOverlay(packedOverlay).setLight(packedLight).setNormal(0, 0, 1);
    }

    public static void addGradientQuadRaw(VertexConsumer builder, Matrix4f matrix,
                                          float minX, float minY, float maxX, float maxY,
                                          String dir, int c1, int c2, float opacity, float zOffset) {
        float a1 = ((c1 >>> 24) & 0xFF) / 255.0f * opacity, r1 = ((c1 >>> 16) & 0xFF) / 255.0f, g1 = ((c1 >>> 8) & 0xFF) / 255.0f, b1 = (c1 & 0xFF) / 255.0f;
        float a2 = ((c2 >>> 24) & 0xFF) / 255.0f * opacity, r2 = ((c2 >>> 16) & 0xFF) / 255.0f, g2 = ((c2 >>> 8) & 0xFF) / 255.0f, b2 = (c2 & 0xFF) / 255.0f;

        switch (dir != null ? dir : "bottom") {
            case "right" -> {
                builder.addVertex(matrix, minX, maxY, zOffset).setColor(r1, g1, b1, a1);
                builder.addVertex(matrix, maxX, maxY, zOffset).setColor(r2, g2, b2, a2);
                builder.addVertex(matrix, maxX, minY, zOffset).setColor(r2, g2, b2, a2);
                builder.addVertex(matrix, minX, minY, zOffset).setColor(r1, g1, b1, a1);
            }
            case "left" -> {
                builder.addVertex(matrix, minX, maxY, zOffset).setColor(r2, g2, b2, a2);
                builder.addVertex(matrix, maxX, maxY, zOffset).setColor(r1, g1, b1, a1);
                builder.addVertex(matrix, maxX, minY, zOffset).setColor(r1, g1, b1, a1);
                builder.addVertex(matrix, minX, minY, zOffset).setColor(r2, g2, b2, a2);
            }
            case "top" -> {
                builder.addVertex(matrix, minX, maxY, zOffset).setColor(r1, g1, b1, a1);
                builder.addVertex(matrix, maxX, maxY, zOffset).setColor(r1, g1, b1, a1);
                builder.addVertex(matrix, maxX, minY, zOffset).setColor(r2, g2, b2, a2);
                builder.addVertex(matrix, minX, minY, zOffset).setColor(r2, g2, b2, a2);
            }
            case "diag" -> {
                float rm = (r1 + r2) * 0.5f, gm = (g1 + g2) * 0.5f, bm = (b1 + b2) * 0.5f, am = (a1 + a2) * 0.5f;
                builder.addVertex(matrix, minX, maxY, zOffset).setColor(rm, gm, bm, am);
                builder.addVertex(matrix, maxX, maxY, zOffset).setColor(r2, g2, b2, a2);
                builder.addVertex(matrix, maxX, minY, zOffset).setColor(rm, gm, bm, am);
                builder.addVertex(matrix, minX, minY, zOffset).setColor(r1, g1, b1, a1);
            }
            default -> {
                builder.addVertex(matrix, minX, maxY, zOffset).setColor(r2, g2, b2, a2);
                builder.addVertex(matrix, maxX, maxY, zOffset).setColor(r2, g2, b2, a2);
                builder.addVertex(matrix, maxX, minY, zOffset).setColor(r1, g1, b1, a1);
                builder.addVertex(matrix, minX, minY, zOffset).setColor(r1, g1, b1, a1);
            }
        }
    }

    public static void drawGradientRoundedRect(
            VertexConsumer builder, Matrix4f matrix,
            float minX, float minY, float maxX, float maxY,
            float rTL, float rTR, float rBR, float rBL,
            String dir, int c1, int c2, float opacity, float zOffset,
            float cx0, float cy0, float cx1, float cy1,
            float cRTL, float cRTR, float cRBR, float cRBL,
            boolean useSdfShader
    ) {
        float a1 = ((c1 >>> 24) & 0xFF) / 255.0f * opacity, r1 = ((c1 >>> 16) & 0xFF) / 255.0f, g1 = ((c1 >>> 8) & 0xFF) / 255.0f, b1 = (c1 & 0xFF) / 255.0f;
        float a2 = ((c2 >>> 24) & 0xFF) / 255.0f * opacity, r2 = ((c2 >>> 16) & 0xFF) / 255.0f, g2 = ((c2 >>> 8) & 0xFF) / 255.0f, b2 = (c2 & 0xFF) / 255.0f;

        if (useSdfShader) {
            boolean drawn = switch (dir != null ? dir : "bottom") {
                case "right" -> WebSdfShader.drawSdfGradientRect(matrix, minX, minY, maxX, maxY, rTL, rTR, rBR, rBL,
                        r1, g1, b1, a1, r2, g2, b2, a2, r2, g2, b2, a2, r1, g1, b1, a1,
                        zOffset, cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL);
                case "left" -> WebSdfShader.drawSdfGradientRect(matrix, minX, minY, maxX, maxY, rTL, rTR, rBR, rBL,
                        r2, g2, b2, a2, r1, g1, b1, a1, r1, g1, b1, a1, r2, g2, b2, a2,
                        zOffset, cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL);
                case "top" -> WebSdfShader.drawSdfGradientRect(matrix, minX, minY, maxX, maxY, rTL, rTR, rBR, rBL,
                        r2, g2, b2, a2, r2, g2, b2, a2, r1, g1, b1, a1, r1, g1, b1, a1,
                        zOffset, cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL);
                case "diag" -> {
                    float rm = (r1 + r2) * 0.5f, gm = (g1 + g2) * 0.5f, bm = (b1 + b2) * 0.5f, am = (a1 + a2) * 0.5f;
                    yield WebSdfShader.drawSdfGradientRect(matrix, minX, minY, maxX, maxY, rTL, rTR, rBR, rBL,
                            r1, g1, b1, a1, rm, gm, bm, am, r2, g2, b2, a2, rm, gm, bm, am,
                            zOffset, cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL);
                }
                default -> WebSdfShader.drawSdfGradientRect(matrix, minX, minY, maxX, maxY, rTL, rTR, rBR, rBL,
                        r1, g1, b1, a1, r1, g1, b1, a1, r2, g2, b2, a2, r2, g2, b2, a2,
                        zOffset, cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL);
            };
            if (drawn) return;
        }

        float x0 = Math.max(minX, cx0), y0 = Math.max(minY, cy0);
        float x1 = Math.min(maxX, cx1), y1 = Math.min(maxY, cy1);
        if (x0 < x1 && y0 < y1) {
            addGradientQuadRaw(builder, matrix, x0, y0, x1, y1, dir, c1, c2, opacity, zOffset);
        }
    }

    public static void drawRoundedRect(
            VertexConsumer builder, Matrix4f matrix,
            float minX, float minY, float maxX, float maxY,
            float rTL, float rTR, float rBR, float rBL,
            float r, float g, float b, float a, float zOffset,
            float cx0, float cy0, float cx1, float cy1,
            float cRTL, float cRTR, float cRBR, float cRBL,
            boolean useSdfShader
    ) {
        float maxR = Math.max(Math.max(rTL, rTR), Math.max(rBR, rBL));
        if (maxR <= 0) {
            drawClippedQuad(builder, matrix, minX, minY, maxX, maxY, r, g, b, a, zOffset, cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL, useSdfShader);
            return;
        }
        if (useSdfShader && WebSdfShader.drawSdfRect(matrix, minX, minY, maxX, maxY, rTL, rTR, rBR, rBL, r, g, b, a, zOffset, cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL)) {
            return;
        }

        float maxR_Top = Math.max(rTL, rTR), maxR_Bottom = Math.max(rBL, rBR);
        drawClippedQuad(builder, matrix, minX, minY + maxR_Top, maxX, maxY - maxR_Bottom, r, g, b, a, zOffset, cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL, useSdfShader);

        for (int i = 0; i < maxR_Top; i++) {
            float insetL = computeCornerInset(rTL, rTL - i - 0.5f);
            float insetR = computeCornerInset(rTR, rTR - i - 0.5f);
            drawClippedQuad(builder, matrix, minX + insetL, minY + i, maxX - insetR, minY + i + 1, r, g, b, a, zOffset, cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL, useSdfShader);
        }
        for (int i = 0; i < maxR_Bottom; i++) {
            float insetL = computeCornerInset(rBL, rBL - i - 0.5f);
            float insetR = computeCornerInset(rBR, rBR - i - 0.5f);
            drawClippedQuad(builder, matrix, minX + insetL, maxY - i - 1, maxX - insetR, maxY - i, r, g, b, a, zOffset, cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL, useSdfShader);
        }
    }

    public static void drawClippedQuad(
            VertexConsumer builder, Matrix4f matrix,
            float minX, float minY, float maxX, float maxY,
            float rCol, float gCol, float bCol, float aCol, float zOffset,
            float cx0, float cy0, float cx1, float cy1,
            float cRTL, float cRTR, float cRBR, float cRBL,
            boolean useSdfShader
    ) {
        float startY = Math.max(minY, cy0), endY = Math.min(maxY, cy1);
        if (startY >= endY) return;

        if (cRTL <= 0.01f && cRTR <= 0.01f && cRBR <= 0.01f && cRBL <= 0.01f) {
            float drawX0 = Math.max(minX, cx0), drawX1 = Math.min(maxX, cx1);
            if (drawX0 < drawX1) {
                addQuadRaw(builder, matrix, drawX0, startY, drawX1, endY, rCol, gCol, bCol, aCol, zOffset);
            }
            return;
        }

        if (useSdfShader && WebSdfShader.drawSdfRect(matrix, minX, minY, maxX, maxY, 0f, 0f, 0f, 0f, rCol, gCol, bCol, aCol, zOffset, cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL)) {
            return;
        }

        float maxR_Top = Math.max(cRTL, cRTR), maxR_Bottom = Math.max(cRBL, cRBR);

        float topZoneEnd = Math.min(endY, cy0 + maxR_Top);
        for (float y = startY; y < topZoneEnd; y++) {
            float midY = (y + Math.min(y + 1.0f, topZoneEnd)) / 2.0f;
            float insetL = computeCornerInset(cRTL, cy0 + cRTL - midY);
            float insetR = computeCornerInset(cRTR, cy0 + cRTR - midY);
            float drawX0 = Math.max(minX, cx0 + insetL), drawX1 = Math.min(maxX, cx1 - insetR);
            if (drawX0 < drawX1) addQuadRaw(builder, matrix, drawX0, y, drawX1, Math.min(y + 1.0f, topZoneEnd), rCol, gCol, bCol, aCol, zOffset);
        }

        float midZoneStart = Math.max(startY, cy0 + maxR_Top), midZoneEnd = Math.min(endY, cy1 - maxR_Bottom);
        if (midZoneStart < midZoneEnd) {
            float drawX0 = Math.max(minX, cx0), drawX1 = Math.min(maxX, cx1);
            if (drawX0 < drawX1) addQuadRaw(builder, matrix, drawX0, midZoneStart, drawX1, midZoneEnd, rCol, gCol, bCol, aCol, zOffset);
        }

        float bottomZoneStart = Math.max(midZoneStart, Math.max(startY, cy1 - maxR_Bottom));
        for (float y = bottomZoneStart; y < endY; y++) {
            float midY = (y + Math.min(y + 1.0f, endY)) / 2.0f;
            float insetL = computeCornerInset(cRBL, midY - (cy1 - cRBL));
            float insetR = computeCornerInset(cRBR, midY - (cy1 - cRBR));
            float drawX0 = Math.max(minX, cx0 + insetL), drawX1 = Math.min(maxX, cx1 - insetR);
            if (drawX0 < drawX1) addQuadRaw(builder, matrix, drawX0, y, drawX1, Math.min(y + 1.0f, endY), rCol, gCol, bCol, aCol, zOffset);
        }
    }

    public static void drawRoundedTexturedRect(
            VertexConsumer builder, Matrix4f matrix,
            float minX, float minY, float maxX, float maxY,
            float u0, float v0, float u1, float v1,
            float rTL, float rTR, float rBR, float rBL,
            float r, float g, float b, float a, float zOffset,
            int packedLight, int packedOverlay,
            float cx0, float cy0, float cx1, float cy1,
            float cRTL, float cRTR, float cRBR, float cRBL
    ) {
        float maxR = Math.max(Math.max(rTL, rTR), Math.max(rBR, rBL));
        if (maxR <= 0) {
            drawClippedTexturedQuad(builder, matrix, minX, minY, maxX, maxY, u0, v0, u1, v1, r, g, b, a, zOffset, packedLight, packedOverlay, cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL);
            return;
        }

        float maxR_Top = Math.max(rTL, rTR), maxR_Bottom = Math.max(rBL, rBR), totalW = maxX - minX, totalH = maxY - minY;
        float cy0_t = minY + maxR_Top, cy1_t = maxY - maxR_Bottom;
        float cv0 = v0 + ((cy0_t - minY) / totalH) * (v1 - v0), cv1 = v0 + ((cy1_t - minY) / totalH) * (v1 - v0);

        if (cy0_t < cy1_t) drawClippedTexturedQuad(builder, matrix, minX, cy0_t, maxX, cy1_t, u0, cv0, u1, cv1, r, g, b, a, zOffset, packedLight, packedOverlay, cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL);

        for (int i = 0; i < maxR_Top; i++) {
            float insetL = computeCornerInset(rTL, rTL - i - 0.5f);
            float insetR = computeCornerInset(rTR, rTR - i - 0.5f);
            float x0 = minX + insetL, x1 = maxX - insetR;
            if (x0 < x1) {
                float cu0 = u0 + ((x0 - minX) / totalW) * (u1 - u0), cu1 = u0 + ((x1 - minX) / totalW) * (u1 - u0);
                float y0_t = minY + i, y1_t = minY + i + 1;
                drawClippedTexturedQuad(builder, matrix, x0, y0_t, x1, y1_t, cu0, v0 + ((y0_t - minY) / totalH) * (v1 - v0), cu1, v0 + ((y1_t - minY) / totalH) * (v1 - v0), r, g, b, a, zOffset, packedLight, packedOverlay, cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL);
            }
        }
        for (int i = 0; i < maxR_Bottom; i++) {
            float insetL = computeCornerInset(rBL, rBL - i - 0.5f);
            float insetR = computeCornerInset(rBR, rBR - i - 0.5f);
            float x0 = minX + insetL, x1 = maxX - insetR;
            if (x0 < x1) {
                float cu0 = u0 + ((x0 - minX) / totalW) * (u1 - u0), cu1 = u0 + ((x1 - minX) / totalW) * (u1 - u0);
                float y0_b = maxY - i - 1, y1_b = maxY - i;
                drawClippedTexturedQuad(builder, matrix, x0, y0_b, x1, y1_b, cu0, v0 + ((y0_b - minY) / totalH) * (v1 - v0), cu1, v0 + ((y1_b - minY) / totalH) * (v1 - v0), r, g, b, a, zOffset, packedLight, packedOverlay, cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL);
            }
        }
    }

    public static void drawClippedTexturedQuad(
            VertexConsumer builder, Matrix4f matrix,
            float minX, float minY, float maxX, float maxY,
            float u0, float v0, float u1, float v1,
            float rCol, float gCol, float bCol, float aCol, float zOffset,
            int packedLight, int packedOverlay,
            float cx0, float cy0, float cx1, float cy1,
            float cRTL, float cRTR, float cRBR, float cRBL
    ) {
        float startY = Math.max(minY, cy0), endY = Math.min(maxY, cy1);
        if (startY >= endY) return;
        float totalW = maxX - minX, totalH = maxY - minY;
        if (totalW <= 0 || totalH <= 0) return;

        if (cRTL <= 0.01f && cRTR <= 0.01f && cRBR <= 0.01f && cRBL <= 0.01f) {
            float drawX0 = Math.max(minX, cx0), drawX1 = Math.min(maxX, cx1);
            if (drawX0 < drawX1) {
                float cu0 = u0 + ((drawX0 - minX) / totalW) * (u1 - u0);
                float cu1 = u0 + ((drawX1 - minX) / totalW) * (u1 - u0);
                float cv0 = v0 + ((startY - minY) / totalH) * (v1 - v0);
                float cv1 = v0 + ((endY - minY) / totalH) * (v1 - v0);
                addTexturedQuadRaw(builder, matrix, drawX0, startY, drawX1, endY, cu0, cv0, cu1, cv1, rCol, gCol, bCol, aCol, zOffset, packedLight, packedOverlay);
            }
            return;
        }

        float maxR_Top = Math.max(cRTL, cRTR), maxR_Bottom = Math.max(cRBL, cRBR);

        float topZoneEnd = Math.min(endY, cy0 + maxR_Top);
        for (float y = startY; y < topZoneEnd; y++) {
            float midY = (y + Math.min(y + 1.0f, topZoneEnd)) / 2.0f;
            float insetL = computeCornerInset(cRTL, cy0 + cRTL - midY);
            float insetR = computeCornerInset(cRTR, cy0 + cRTR - midY);
            float drawX0 = Math.max(minX, cx0 + insetL), drawX1 = Math.min(maxX, cx1 - insetR);
            if (drawX0 < drawX1) {
                addTexturedQuadRaw(builder, matrix, drawX0, y, drawX1, Math.min(y + 1.0f, topZoneEnd),
                        u0 + ((drawX0 - minX) / totalW) * (u1 - u0), v0 + ((y - minY) / totalH) * (v1 - v0),
                        u0 + ((drawX1 - minX) / totalW) * (u1 - u0), v0 + ((Math.min(y + 1.0f, topZoneEnd) - minY) / totalH) * (v1 - v0),
                        rCol, gCol, bCol, aCol, zOffset, packedLight, packedOverlay);
            }
        }

        float midZoneStart = Math.max(startY, cy0 + maxR_Top), midZoneEnd = Math.min(endY, cy1 - maxR_Bottom);
        if (midZoneStart < midZoneEnd) {
            float drawX0 = Math.max(minX, cx0), drawX1 = Math.min(maxX, cx1);
            if (drawX0 < drawX1) {
                addTexturedQuadRaw(builder, matrix, drawX0, midZoneStart, drawX1, midZoneEnd,
                        u0 + ((drawX0 - minX) / totalW) * (u1 - u0), v0 + ((midZoneStart - minY) / totalH) * (v1 - v0),
                        u0 + ((drawX1 - minX) / totalW) * (u1 - u0), v0 + ((midZoneEnd - minY) / totalH) * (v1 - v0),
                        rCol, gCol, bCol, aCol, zOffset, packedLight, packedOverlay);
            }
        }

        float bottomZoneStart = Math.max(midZoneStart, Math.max(startY, cy1 - maxR_Bottom));
        for (float y = bottomZoneStart; y < endY; y++) {
            float midY = (y + Math.min(y + 1.0f, endY)) / 2.0f;
            float insetL = computeCornerInset(cRBL, midY - (cy1 - cRBL));
            float insetR = computeCornerInset(cRBR, midY - (cy1 - cRBR));
            float drawX0 = Math.max(minX, cx0 + insetL), drawX1 = Math.min(maxX, cx1 - insetR);
            if (drawX0 < drawX1) {
                addTexturedQuadRaw(builder, matrix, drawX0, y, drawX1, Math.min(y + 1.0f, endY),
                        u0 + ((drawX0 - minX) / totalW) * (u1 - u0), v0 + ((y - minY) / totalH) * (v1 - v0),
                        u0 + ((drawX1 - minX) / totalW) * (u1 - u0), v0 + ((Math.min(y + 1.0f, endY) - minY) / totalH) * (v1 - v0),
                        rCol, gCol, bCol, aCol, zOffset, packedLight, packedOverlay);
            }
        }
    }

    public static void drawRingSegment(
            VertexConsumer builder, Matrix4f matrix,
            float cx, float cy, float radius, float thickness,
            float startAngle, float endAngle,
            int color, float opacity, float zOffset
    ) {
        float a = ((color >>> 24) & 0xFF) / 255.0f * opacity;
        if (a <= 0) return;
        float r = ((color >>> 16) & 0xFF) / 255.0f, g = ((color >>> 8) & 0xFF) / 255.0f, b = (color & 0xFF) / 255.0f;
        int segments = 16;
        float angleStep = (endAngle - startAngle) / segments;
        for (int i = 0; i < segments; i++) {
            float theta1 = startAngle + i * angleStep, theta2 = startAngle + (i + 1) * angleStep;
            float cos1 = (float) Math.cos(theta1), sin1 = (float) Math.sin(theta1);
            float cos2 = (float) Math.cos(theta2), sin2 = (float) Math.sin(theta2);
            float x1_out = cx + cos1 * radius, y1_out = cy + sin1 * radius, x1_in = cx + cos1 * (radius - thickness), y1_in = cy + sin1 * (radius - thickness);
            float x2_out = cx + cos2 * radius, y2_out = cy + sin2 * radius, x2_in = cx + cos2 * (radius - thickness), y2_in = cy + sin2 * (radius - thickness);
            builder.addVertex(matrix, x1_out, y1_out, zOffset).setColor(r, g, b, a);
            builder.addVertex(matrix, x2_out, y2_out, zOffset).setColor(r, g, b, a);
            builder.addVertex(matrix, x2_in, y2_in, zOffset).setColor(r, g, b, a);
            builder.addVertex(matrix, x1_in, y1_in, zOffset).setColor(r, g, b, a);
            builder.addVertex(matrix, x1_out, y1_out, zOffset).setColor(r, g, b, a);
            builder.addVertex(matrix, x1_in, y1_in, zOffset).setColor(r, g, b, a);
            builder.addVertex(matrix, x2_in, y2_in, zOffset).setColor(r, g, b, a);
            builder.addVertex(matrix, x2_out, y2_out, zOffset).setColor(r, g, b, a);
        }
    }
}
