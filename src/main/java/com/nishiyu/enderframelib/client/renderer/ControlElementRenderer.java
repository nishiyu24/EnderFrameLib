package com.nishiyu.enderframelib.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;
import static com.nishiyu.enderframelib.client.renderer.DisplayItemDrawer.drawClippedQuad;
import static com.nishiyu.enderframelib.client.renderer.DisplayItemDrawer.drawRoundedRect;
import static com.nishiyu.enderframelib.client.renderer.DisplayItemDrawer.useSdfShader;

/** 進捗バー・スライダー・チェックボックス・ラジオボタンを描く（{@link DisplayItemDrawer} の部品）。 */
public final class ControlElementRenderer {
    private ControlElementRenderer() {}

    static void renderProgressElement(DisplayItem el, CssAnimator.AnimatedValues anim, PoseStack poseStack, MultiBufferSource buffer,
                                              float finalOpacity, float zPos, float rTL, float rTR, float rBR, float rBL,
                                              float clipMinX, float clipMinY, float clipMaxX, float clipMaxY,
                                              float cRTL, float cRTR, float cRBR, float cRBL) {
        float ratio = anim.rangeMax > anim.rangeMin ? Math.clamp((anim.rangeVal - anim.rangeMin) / (anim.rangeMax - anim.rangeMin), 0f, 1f) : 0f;
        float fillW = el.width() * ratio;
        if (fillW > 0.5f) {
            int pC = anim.color() != 0 ? anim.color() : 0xFF28A745;
            float prA = ((pC >>> 24) & 0xFF) / 255.0f * finalOpacity;
            float prR = ((pC >>> 16) & 0xFF) / 255.0f;
            float prG = ((pC >>> 8) & 0xFF) / 255.0f;
            float prB = (pC & 0xFF) / 255.0f;
            VertexConsumer builder = buffer.getBuffer(RenderType.gui());
            drawRoundedRect(builder, poseStack.last().pose(), el.x(), el.y(), el.x() + fillW, el.y() + el.height(),
                    rTL, ratio > 0.98f ? rTR : 0, ratio > 0.98f ? rBR : 0, rBL,
                    prR, prG, prB, prA, zPos, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
        }
    }

    static void renderRangeElement(DisplayItem el, CssAnimator.AnimatedValues anim, PoseStack poseStack, MultiBufferSource buffer,
                                           float finalOpacity, float zPos, float zUnit,
                                           float clipMinX, float clipMinY, float clipMaxX, float clipMaxY,
                                           float cRTL, float cRTR, float cRBR, float cRBL) {
        float rVal = anim.rangeVal, rMin = anim.rangeMin, rMax = anim.rangeMax;
        if (el.text() != null && el.text().contains(":")) {
            String[] sp = el.text().split(":");
            if (sp.length >= 3) {
                try {
                    rVal = Float.parseFloat(sp[0]);
                    rMin = Float.parseFloat(sp[1]);
                    rMax = Float.parseFloat(sp[2]);
                } catch (NumberFormatException ignored) {}
            }
        }
        float ratio = rMax > rMin ? Math.clamp((rVal - rMin) / (rMax - rMin), 0f, 1f) : 0f;
        VertexConsumer builder = buffer.getBuffer(RenderType.gui());
        Matrix4f mat = poseStack.last().pose();

        // Chromium の NativeThemeBase::PaintSliderTrack / PaintSliderThumb と同じ形（DisplayListBuilder#drawNativeRange）。
        // 溝は箱の幅いっぱい・高さ 8px で縦の中央、つまみは直径 16px で中心が両端から 8px の間を動く
        float cy = el.y() + el.height() / 2.0f;
        float trackTop = cy - RANGE_TRACK_HEIGHT / 2, trackBottom = cy + RANGE_TRACK_HEIGHT / 2;
        float trackRad = RANGE_TRACK_HEIGHT / 2;
        float thumbRad = RANGE_THUMB_SIZE / 2;
        float left = el.x(), right = el.x() + el.width();
        float tCX = left + thumbRad + ratio * Math.max(0, el.width() - RANGE_THUMB_SIZE);

        int pC = anim.color();
        float prA = ((pC >>> 24) & 0xFF) / 255.0f * finalOpacity;
        float prR = ((pC >>> 16) & 0xFF) / 255.0f;
        float prG = ((pC >>> 8) & 0xFF) / 255.0f;
        float prB = (pC & 0xFF) / 255.0f;

        int fill = anim.bgColor();
        if (((fill >>> 24) & 0xFF) != 0) {
            // Chromium は溝を塗り、値までを強調色で塗り、半透明の 1px の枠を溝全体に重ねる。枠を重ねた色を先に合成し、
            // 「枠を重ねた色の角丸」と「1px 内側の地の色の角丸」の 2 枚ずつで描く（どれも SDF で描き、四角形の一括描画と
            // 深度で競合しない。以前は枠を一括描画の四角形で描いて、暗い溝で枠が消えていた）
            RenderMeta meta = el.meta();
            int edge = meta != null && meta.roundedBorder() != null ? meta.roundedBorder().colorTop() : 0;
            int accent = anim.color() | 0xFF000000;
            // 同じ深さに重ねるとちらつく（Z ファイティング）ので、描く順に少しずつ手前へ置く
            fillRoundedRect(builder, mat, left, trackTop, right, trackBottom, trackRad, trackRad, over(edge, fill), finalOpacity, zPos,
                    clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
            fillRoundedRect(builder, mat, left + 1, trackTop + 1, right - 1, trackBottom - 1, trackRad - 1, trackRad - 1, fill, finalOpacity, zPos + zUnit,
                    clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
            if (tCX > left) {
                float r = Math.min(trackRad, (tCX - left) / 2);
                fillRoundedRect(builder, mat, left, trackTop, tCX, trackBottom, r, 0, over(edge, accent), finalOpacity, zPos + 2 * zUnit,
                        clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
                if (tCX > left + 1) {
                    float ri = Math.max(0, Math.min(trackRad - 1, (tCX - left - 1) / 2));
                    fillRoundedRect(builder, mat, left + 1, trackTop + 1, tCX, trackBottom - 1, ri, 0, accent, finalOpacity, zPos + 3 * zUnit,
                            clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
                }
            }
        }
        drawRoundedRect(builder, mat, tCX - thumbRad, cy - thumbRad, tCX + thumbRad, cy + thumbRad,
                thumbRad, thumbRad, thumbRad, thumbRad, prR, prG, prB, prA, zPos + 4 * zUnit,
                clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
    }

    /** 左の角（左上・左下）を {@code rLeft}、右の角を {@code rRight} にした角丸矩形を {@code argb} で塗る。 */
    static void fillRoundedRect(VertexConsumer builder, Matrix4f mat, float x0, float y0, float x1, float y1,
                                        float rLeft, float rRight, int argb, float opacity, float zPos,
                                        float clipMinX, float clipMinY, float clipMaxX, float clipMaxY,
                                        float cRTL, float cRTR, float cRBR, float cRBL) {
        drawRoundedRect(builder, mat, x0, y0, x1, y1, rLeft, rRight, rRight, rLeft,
                ((argb >>> 16) & 0xFF) / 255.0f, ((argb >>> 8) & 0xFF) / 255.0f, (argb & 0xFF) / 255.0f,
                ((argb >>> 24) & 0xFF) / 255.0f * opacity, zPos, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
    }

    /** 半透明の {@code top} を不透明な {@code bottom} に重ねた色（sRGB のまま合成する。ブラウザの合成と同じ）。 */
    static int over(int top, int bottom) {
        float a = ((top >>> 24) & 0xFF) / 255f;
        int out = bottom & 0xFF000000;
        for (int shift = 0; shift <= 16; shift += 8) {
            float t = (top >>> shift) & 0xFF, b = (bottom >>> shift) & 0xFF;
            out |= Math.round(t * a + b * (1 - a)) << shift;
        }
        return out;
    }

    /** レンジの溝の高さとつまみの直径（Chromium の既定、CSS px）。 */
    public static final float RANGE_TRACK_HEIGHT = 8f, RANGE_THUMB_SIZE = 16f;

    /**
     * レンジの横の位置（要素の左端から）を値の割合（0〜1）に直す。つまみの中心が指す位置になるよう、
     * 両端のつまみの半径の分を除いた幅で割る（Chrome と同じく、押した所につまみの中心が来る）。
     */
    public static float rangeRatio(double localX, float width) {
        float travel = Math.max(1f, width - RANGE_THUMB_SIZE);
        return Math.clamp((float) ((localX - RANGE_THUMB_SIZE / 2) / travel), 0f, 1f);
    }

    static void renderToggleElement(DisplayItem el, CssAnimator.AnimatedValues anim, PoseStack poseStack, MultiBufferSource buffer,
                                            float finalOpacity, float zPos,
                                            float clipMinX, float clipMinY, float clipMaxX, float clipMaxY,
                                            float cRTL, float cRTR, float cRBR, float cRBL) {
        boolean mixed = el.type() == ElementType.CHECKBOX && "mixed".equals(el.text());
        if (!"true".equals(el.text()) && !mixed) return;
        float a = ((anim.color() >>> 24) & 0xFF) / 255.0f * finalOpacity;
        if (a <= 0.0f) return;

        float pad = el.width() * 0.25f;
        float r = ((anim.color() >>> 16) & 0xFF) / 255.0f;
        float g = ((anim.color() >>> 8) & 0xFF) / 255.0f;
        float b = (anim.color() & 0xFF) / 255.0f;
        if (el.type() == ElementType.RADIO) {
            // 点は箱の直径の 60% の円（Chromium は箱の矩形を 20% ずつ内側に縮めた楕円を塗る。26px の Chrome を撮った実測）
            pad = el.width() * 0.2f;
            float dot = (el.width() - 2 * pad) / 2f;
            drawRoundedRect(buffer.getBuffer(RenderType.gui()), poseStack.last().pose(),
                    el.x() + pad, el.y() + el.height() / 2f - dot, el.x() + el.width() - pad, el.y() + el.height() / 2f + dot,
                    dot, dot, dot, dot, r, g, b, a, zPos, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
            return;
        }
        if (el.type() == ElementType.CHECKBOX) {
            renderCheckboxMark(el, mixed, r, g, b, a, poseStack, buffer, zPos, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
            return;
        }
        drawClippedQuad(buffer.getBuffer(RenderType.gui()), poseStack.last().pose(),
                el.x() + pad, el.y() + pad, el.x() + el.width() - pad, el.y() + el.height() - pad,
                r, g, b, a, zPos, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
    }

    /**
     * チェックボックスの印（Chromium の NativeThemeBase::PaintCheckbox と同じ形）。
     * <ul>
     *   <li>選択: (0.2w, 0.5h) から右下へ (0.2w, 0.2h) 進み、(0.8w, 0.2h) へ上がる折れ線。太さ 0.16h</li>
     *   <li>未確定（indeterminate）: 左右 20%・上下 40% 内側の横棒</li>
     * </ul>
     * 折れ目は 2 本の線分をそれぞれ太さの半分だけ延ばして重ね、角が欠けないようにする。
     */
    static void renderCheckboxMark(DisplayItem el, boolean mixed, float r, float g, float b, float a,
                                           PoseStack poseStack, MultiBufferSource buffer, float zPos,
                                           float clipMinX, float clipMinY, float clipMaxX, float clipMaxY,
                                           float cRTL, float cRTR, float cRBR, float cRBL) {
        float x = el.x(), y = el.y(), w = el.width(), h = el.height();
        VertexConsumer builder = buffer.getBuffer(RenderType.gui());
        Matrix4f mat = poseStack.last().pose();
        if (mixed) {
            float radius = Math.min(2f, h * 0.1f);
            drawRoundedRect(builder, mat, x + w * 0.2f, y + h * 0.4f, x + w * 0.8f, y + h * 0.6f,
                    radius, radius, radius, radius, r, g, b, a, zPos, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
            return;
        }
        float stroke = h * 0.16f;
        float x0 = x + w * 0.2f, y0 = y + h * 0.5f;
        float x1 = x0 + w * 0.2f, y1 = y0 + h * 0.2f;
        float x2 = x + w * 0.8f, y2 = y + h * 0.2f;
        float lenA = (float) Math.hypot(x1 - x0, y1 - y0), lenB = (float) Math.hypot(x2 - x1, y2 - y1);
        float ext = stroke / 2;
        RoundedRectRasterizer.drawSegment(builder, mat, x0, y0, x1 + (x1 - x0) / lenA * ext, y1 + (y1 - y0) / lenA * ext, stroke,
                r, g, b, a, zPos, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL, useSdfShader);
        RoundedRectRasterizer.drawSegment(builder, mat, x1 - (x2 - x1) / lenB * ext, y1 - (y2 - y1) / lenB * ext, x2, y2, stroke,
                r, g, b, a, zPos, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL, useSdfShader);
    }
}
