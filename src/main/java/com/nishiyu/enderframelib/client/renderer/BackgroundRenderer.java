package com.nishiyu.enderframelib.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;
import static com.nishiyu.enderframelib.client.renderer.DisplayItemDrawer.drawClippedQuad;
import static com.nishiyu.enderframelib.client.renderer.DisplayItemDrawer.drawRingSegment;
import static com.nishiyu.enderframelib.client.renderer.DisplayItemDrawer.drawRoundedRect;
import static com.nishiyu.enderframelib.client.renderer.DisplayItemDrawer.useSdfShader;

/** 要素の背景・枠線・影を描く（{@link DisplayItemDrawer} の部品）。 */
public final class BackgroundRenderer {
    private BackgroundRenderer() {}

    static boolean renderCircleBorderElement(DisplayItem el, RenderMeta meta, CssAnimator.AnimatedValues anim,
                                                     PoseStack poseStack, MultiBufferSource buffer, float finalOpacity, float zPos,
                                                     float clipMinX, float clipMinY, float clipMaxX, float clipMaxY,
                                                     float cRTL, float cRTR, float cRBR, float cRBL) {
        CircleBorderSpec cb = meta != null ? meta.circleBorder() : null;
        if (cb == null && anim.circleColors == null) return false;

        float outerRadius = Math.min(el.width(), el.height()) / 2.0f;
        int cTop = cb != null ? cb.colorTop() : anim.circleColors[0];
        int cRight = cb != null ? cb.colorRight() : anim.circleColors[1];
        int cBottom = cb != null ? cb.colorBottom() : anim.circleColors[2];
        int cLeft = cb != null ? cb.colorLeft() : anim.circleColors[3];
        float thick = cb != null ? cb.thickness() : anim.circleThickness;

        VertexConsumer builder = buffer.getBuffer(RenderType.gui());
        Matrix4f matrix = poseStack.last().pose();
        // 4 辺が同じ色なら、角丸の枠線（半径 = 外半径）として SDF の輪で描く。段のない滑らかな円になる
        if (useSdfShader && cTop == cRight && cRight == cBottom && cBottom == cLeft) {
            if (cTop == 0) return true;
            RoundedRectRasterizer.drawRoundedBorder(builder, matrix,
                    el.x(), el.y(), el.x() + el.width(), el.y() + el.height(),
                    outerRadius, outerRadius, outerRadius, outerRadius,
                    new float[]{thick, thick, thick, thick}, new int[]{cTop, cRight, cBottom, cLeft}, finalOpacity, zPos,
                    clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL, true);
            return true;
        }
        float cX = el.x() + el.width() / 2.0f;
        float cY = el.y() + el.height() / 2.0f;

        if (cTop != 0) drawRingSegment(builder, matrix, cX, cY, outerRadius, thick, (float) (-Math.PI * 0.75), (float) (-Math.PI * 0.25), cTop, finalOpacity, zPos);
        if (cRight != 0) drawRingSegment(builder, matrix, cX, cY, outerRadius, thick, (float) (-Math.PI * 0.25), (float) (Math.PI * 0.25), cRight, finalOpacity, zPos);
        if (cBottom != 0) drawRingSegment(builder, matrix, cX, cY, outerRadius, thick, (float) (Math.PI * 0.25), (float) (Math.PI * 0.75), cBottom, finalOpacity, zPos);
        if (cLeft != 0) drawRingSegment(builder, matrix, cX, cY, outerRadius, thick, (float) (Math.PI * 0.75), (float) (Math.PI * 1.25), cLeft, finalOpacity, zPos);
        return true;
    }

    /** 角丸の枠線は枠の領域だけを塗る（内側は背景の要素が受け持つ）。 */
    static void renderRoundedBorderElement(DisplayItem el, RenderMeta meta, PoseStack poseStack, MultiBufferSource buffer,
                                                   float finalOpacity, float zOffset, float rTL, float rTR, float rBR, float rBL,
                                                   float clipMinX, float clipMinY, float clipMaxX, float clipMaxY,
                                                   float cRTL, float cRTR, float cRBR, float cRBL) {
        RoundedBorderSpec spec = meta != null ? meta.roundedBorder() : null;
        if (spec == null) return;
        int[] colors = {spec.colorTop(), spec.colorRight(), spec.colorBottom(), spec.colorLeft()};
        VertexConsumer builder = buffer.getBuffer(RenderType.gui());
        RoundedRectRasterizer.drawRoundedBorder(builder, poseStack.last().pose(),
                el.x(), el.y(), el.x() + el.width(), el.y() + el.height(), rTL, rTR, rBR, rBL,
                spec.widths(), colors, finalOpacity, zOffset,
                clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL, useSdfShader);
    }

    static void renderElementBackground(DisplayItem el, CssAnimator.AnimatedValues anim,
                                                PoseStack poseStack, MultiBufferSource buffer,
                                                float finalOpacity, float zOffset, float rTL, float rTR, float rBR, float rBL,
                                                float clipMinX, float clipMinY, float clipMaxX, float clipMaxY,
                                                float cRTL, float cRTR, float cRBR, float cRBL) {
        RenderHints hints = el.hints();
        if (hints.shadowBlur() > 0) {
            renderBlurredShadow(el, anim, poseStack, finalOpacity, zOffset, hints.shadowBlur(), rTL, rTR, rBR, rBL,
                    clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
            return;
        }
        if (hints.shaderGradient() != null) {
            renderShaderGradient(el, hints.shaderGradient(), poseStack, finalOpacity, zOffset,
                    clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
            return;
        }
        float bgA = ((anim.bgColor() >>> 24) & 0xFF) / 255.0f * finalOpacity;
        if (bgA > 0) {
            VertexConsumer builder = buffer.getBuffer(RenderType.gui());
            float r = ((anim.bgColor() >>> 16) & 0xFF) / 255.0f;
            float g = ((anim.bgColor() >>> 8) & 0xFF) / 255.0f;
            float b = (anim.bgColor() & 0xFF) / 255.0f;
            if (rTL > 0 || rTR > 0 || rBR > 0 || rBL > 0) {
                drawRoundedRect(builder, poseStack.last().pose(), el.x(), el.y(), el.x() + el.width(), el.y() + el.height(),
                        rTL, rTR, rBR, rBL, r, g, b, bgA, zOffset, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
            } else {
                drawClippedQuad(builder, poseStack.last().pose(), el.x(), el.y(), el.x() + el.width(), el.y() + el.height(),
                        r, g, b, bgA, zOffset, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
            }
        }
    }

    /** {@link ShaderGradient} の 1 区間を 1 枚で描く。 */
    static void renderShaderGradient(DisplayItem el, ShaderGradient g, PoseStack poseStack, float finalOpacity, float zOffset,
                                             float clipMinX, float clipMinY, float clipMaxX, float clipMaxY,
                                             float cRTL, float cRTR, float cRBR, float cRBL) {
        float[] gradA = g.gradA(), gradB = g.gradB();
        int c0 = g.color0(), c1 = g.color1();
        float a0 = ((c0 >>> 24) & 0xFF) / 255.0f * finalOpacity, a1 = ((c1 >>> 24) & 0xFF) / 255.0f * finalOpacity;
        if (a0 <= 0 && a1 <= 0) return;
        WebSdfShader.drawSdfShaderGradient(poseStack.last().pose(),
                el.x(), el.y(), el.x() + el.width(), el.y() + el.height(),
                ((c0 >>> 16) & 0xFF) / 255.0f, ((c0 >>> 8) & 0xFF) / 255.0f, (c0 & 0xFF) / 255.0f, a0,
                ((c1 >>> 16) & 0xFF) / 255.0f, ((c1 >>> 8) & 0xFF) / 255.0f, (c1 & 0xFF) / 255.0f, a1,
                gradA, gradB, g.repeatOrigin(), g.repeatPeriod(), zOffset, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
    }

    /**
     * ぼかした影を 1 枚で描く。要素の矩形は「ぼかしが消える所まで」の広さ（{@link #SHADOW_REACH} × ぼかし半径だけ形の外側）で、
     * 影の形はそこから同じだけ内側。減衰はシェーダーがガウスぼかしに近い形で計算する（以前は層の重ね塗りで、段が見えた）。
     */
    static void renderBlurredShadow(DisplayItem el, CssAnimator.AnimatedValues anim, PoseStack poseStack,
                                            float finalOpacity, float zOffset, float blur,
                                            float rTL, float rTR, float rBR, float rBL,
                                            float clipMinX, float clipMinY, float clipMaxX, float clipMaxY,
                                            float cRTL, float cRTR, float cRBR, float cRBL) {
        float a = ((anim.bgColor() >>> 24) & 0xFF) / 255.0f * finalOpacity;
        if (a <= 0) return;
        float r = ((anim.bgColor() >>> 16) & 0xFF) / 255.0f;
        float g = ((anim.bgColor() >>> 8) & 0xFF) / 255.0f;
        float b = (anim.bgColor() & 0xFF) / 255.0f;
        float reach = SHADOW_REACH * blur;
        float qx0 = el.x(), qy0 = el.y(), qx1 = el.x() + el.width(), qy1 = el.y() + el.height();
        float sx0 = qx0 + reach, sy0 = qy0 + reach, sx1 = qx1 - reach, sy1 = qy1 - reach;
        if (sx1 <= sx0 || sy1 <= sy0) return;
        // 形の角丸は、要素の矩形に合わせて切り詰められた半径ではなく、形そのものの大きさで切り詰める
        float limit = Math.min(sx1 - sx0, sy1 - sy0) / 2.0f;
        WebSdfShader.drawSdfShadow(poseStack.last().pose(), qx0, qy0, qx1, qy1, sx0, sy0, sx1, sy1,
                Math.min(rTL, limit), Math.min(rTR, limit), Math.min(rBR, limit), Math.min(rBL, limit),
                r, g, b, a, zOffset, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL, blur);
    }

    /** 影のぼかしが消える所までの広さ（ぼかし半径の倍数。標準偏差 = 半径 / 2 の 3 倍）。 */
    public static final float SHADOW_REACH = 1.5f;
}
