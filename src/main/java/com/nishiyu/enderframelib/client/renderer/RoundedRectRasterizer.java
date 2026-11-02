package com.nishiyu.enderframelib.client.renderer;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nishiyu.enderframelib.core.paint.BoxDecorations;
import com.nishiyu.enderframelib.core.paint.RoundedRectGeometry;
import org.joml.Matrix4f;

/**
 * 角丸矩形・グラデーション・テクスチャ付き矩形などのラスタライズ幾何ユーティリティ。
 */
public final class RoundedRectRasterizer {

    private RoundedRectRasterizer() {}

    /**
     * 円弧角の内側への食い込み量（インセット）。幾何は core の {@link RoundedRectGeometry} にある。
     */
    public static float computeCornerInset(float radius, float distFromCenter) {
        return RoundedRectGeometry.computeCornerInset(radius, distFromCenter);
    }

    public static void addQuadRaw(VertexConsumer builder, Matrix4f matrix,
                                  float minX, float minY, float maxX, float maxY,
                                  float r, float g, float b, float a, float zOffset) {
        // SDF のバッチ中は、通常の頂点バッファ（あとでまとめて描かれる）ではなく、同じバッチに積む。
        // 別々に描くと、SDF の Quad と矩形の描く順が要素の順から外れ、半透明の重なりが崩れる。
        // クリップは矩形の外側に広げて、縁を切らない（縁の柔らかさはシェーダー側の AA に任せる）
        if (WebSdfShader.isBatching() && WebSdfShader.drawSdfRect(matrix, minX, minY, maxX, maxY, 0f, 0f, 0f, 0f,
                r, g, b, a, zOffset, minX - 1f, minY - 1f, maxX + 1f, maxY + 1f, 0f, 0f, 0f, 0f)) {
            return;
        }
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

    /**
     * 角丸の枠線。枠の領域（外側の角丸矩形 − パディングボックス）だけを、辺ごとの色の矩形の片で塗る
     * （{@link BoxDecorations#roundedBorderSpans}）。片は親のクリップで切る。
     */
    public static void drawRoundedBorder(
            VertexConsumer builder, Matrix4f matrix,
            float minX, float minY, float maxX, float maxY,
            float rTL, float rTR, float rBR, float rBL,
            float[] widths, int[] colors, float opacity, float zOffset,
            float cx0, float cy0, float cx1, float cy1,
            float cRTL, float cRTR, float cRBR, float cRBL,
            boolean useSdfShader
    ) {
        drawRoundedBorder(builder, matrix, minX, minY, maxX, maxY, rTL, rTR, rBR, rBL, widths, colors, opacity, zOffset,
                cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL, useSdfShader, null);
    }

    /** 同上。{@code dash}（{@link WebSdfShader#drawSdfBorder} の破線の記述）があれば、4 辺が同じ色の輪を破線・点線で描く。 */
    public static void drawRoundedBorder(
            VertexConsumer builder, Matrix4f matrix,
            float minX, float minY, float maxX, float maxY,
            float rTL, float rTR, float rBR, float rBL,
            float[] widths, int[] colors, float opacity, float zOffset,
            float cx0, float cy0, float cx1, float cy1,
            float cRTL, float cRTR, float cRBR, float cRBL,
            boolean useSdfShader, float[] dash
    ) {
        // 4 辺が同じ色なら、シェーダーで輪として 1 枚に描く（アンチエイリアスがかかる）。辺ごとに色が違うときだけ、下の帯で描く
        if (useSdfShader) {
            Integer single = null;
            boolean uniform = true;
            for (int side = 0; side < 4 && uniform; side++) {
                if (widths[side] <= 0) continue;
                if (single == null) single = colors[side];
                else if (single != colors[side]) uniform = false;
            }
            if (single == null) return;
            if (uniform) {
                int c = single;
                float a = ((c >>> 24) & 0xFF) / 255.0f * opacity;
                if (a <= 0) return;
                if (WebSdfShader.drawSdfBorder(matrix, minX, minY, maxX, maxY, rTL, rTR, rBR, rBL,
                        widths[0], widths[1], widths[2], widths[3],
                        ((c >>> 16) & 0xFF) / 255.0f, ((c >>> 8) & 0xFF) / 255.0f, (c & 0xFF) / 255.0f, a, zOffset,
                        cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL, dash)) {
                    return;
                }
            }
        }
        for (BoxDecorations.Span span : BoxDecorations.roundedBorderSpans(minX, minY, maxX - minX, maxY - minY,
                new float[]{rTL, rTR, rBR, rBL}, widths)) {
            int c = colors[span.side().ordinal()];
            float a = ((c >>> 24) & 0xFF) / 255.0f * opacity;
            if (a <= 0) continue;
            drawClippedQuad(builder, matrix, span.x(), span.y(), span.x() + span.w(), span.y() + span.h(),
                    ((c >>> 16) & 0xFF) / 255.0f, ((c >>> 8) & 0xFF) / 255.0f, (c & 0xFF) / 255.0f, a, zOffset,
                    cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL, useSdfShader);
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
        RoundedRectGeometry.roundedRectRows(minX, minY, maxX, maxY, rTL, rTR, rBR, rBL,
                (x0, y0, x1, y1) -> drawClippedQuad(builder, matrix, x0, y0, x1, y1, r, g, b, a, zOffset,
                        cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL, useSdfShader));
    }

    public static void drawClippedQuad(
            VertexConsumer builder, Matrix4f matrix,
            float minX, float minY, float maxX, float maxY,
            float rCol, float gCol, float bCol, float aCol, float zOffset,
            float cx0, float cy0, float cx1, float cy1,
            float cRTL, float cRTR, float cRBR, float cRBL,
            boolean useSdfShader
    ) {
        if (Math.max(minY, cy0) >= Math.min(maxY, cy1)) return;
        boolean plainClip = cRTL <= 0.01f && cRTR <= 0.01f && cRBR <= 0.01f && cRBL <= 0.01f;
        if (!(plainClip && !(useSdfShader && WebSdfShader.hasClipTransform()))
                && useSdfShader && WebSdfShader.drawSdfRect(matrix, minX, minY, maxX, maxY, 0f, 0f, 0f, 0f, rCol, gCol, bCol, aCol, zOffset, cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL)) {
            return;
        }
        RoundedRectGeometry.clippedRows(minX, minY, maxX, maxY, cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL,
                (x0, y0, x1, y1) -> addQuadRaw(builder, matrix, x0, y0, x1, y1, rCol, gCol, bCol, aCol, zOffset));
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
        float totalW = maxX - minX, totalH = maxY - minY;
        RoundedRectGeometry.roundedRectRows(minX, minY, maxX, maxY, rTL, rTR, rBR, rBL,
                (x0, y0, x1, y1) -> drawClippedTexturedQuad(builder, matrix, x0, y0, x1, y1,
                        u0 + ((x0 - minX) / totalW) * (u1 - u0), v0 + ((y0 - minY) / totalH) * (v1 - v0),
                        u0 + ((x1 - minX) / totalW) * (u1 - u0), v0 + ((y1 - minY) / totalH) * (v1 - v0),
                        r, g, b, a, zOffset, packedLight, packedOverlay, cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL));
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
        float totalW = maxX - minX, totalH = maxY - minY;
        if (totalW <= 0 || totalH <= 0) return;
        RoundedRectGeometry.clippedRows(minX, minY, maxX, maxY, cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL,
                (x0, y0, x1, y1) -> addTexturedQuadRaw(builder, matrix, x0, y0, x1, y1,
                        u0 + ((x0 - minX) / totalW) * (u1 - u0), v0 + ((y0 - minY) / totalH) * (v1 - v0),
                        u0 + ((x1 - minX) / totalW) * (u1 - u0), v0 + ((y1 - minY) / totalH) * (v1 - v0),
                        rCol, gCol, bCol, aCol, zOffset, packedLight, packedOverlay));
    }


    /**
     * 太さ {@code width} の線分（両端は直角）。シェーダーが使えればアンチエイリアスとクリップ付きで、
     * 使えなければ回転した四角形 1 枚で描く（クリップしない）。
     */
    public static void drawSegment(
            VertexConsumer builder, Matrix4f matrix,
            float ax, float ay, float bx, float by, float width,
            float r, float g, float b, float a, float zOffset,
            float cx0, float cy0, float cx1, float cy1,
            float cRTL, float cRTR, float cRBR, float cRBL,
            boolean useSdfShader
    ) {
        if (useSdfShader && WebSdfShader.drawSdfSegment(matrix, ax, ay, bx, by, width, r, g, b, a, zOffset,
                cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL)) {
            return;
        }
        float len = (float) Math.hypot(bx - ax, by - ay);
        if (len <= 0) return;
        float nx = -(by - ay) / len * width / 2, ny = (bx - ax) / len * width / 2;
        builder.addVertex(matrix, ax + nx, ay + ny, zOffset).setColor(r, g, b, a);
        builder.addVertex(matrix, bx + nx, by + ny, zOffset).setColor(r, g, b, a);
        builder.addVertex(matrix, bx - nx, by - ny, zOffset).setColor(r, g, b, a);
        builder.addVertex(matrix, ax - nx, ay - ny, zOffset).setColor(r, g, b, a);
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