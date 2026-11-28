package com.nishiyu.enderframelib.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nishiyu.enderframelib.core.paint.RenderFrame;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;

/**
 * 1 枚の面を描く指定。{@link SurfaceRenderer} だけが作り、{@link MinecraftHostSurface} が読む。
 * ホストは {@link SurfaceStyle} を通して設定する。
 *
 * @param frame        描くフレームの値（ビューの {@code UiView#renderFrame}〔平行移動は不変の写し〕、または 1 回だけ組んだ文書の一部。
 *                     描く時刻まで入れてある。キャレットの重ね描きは {@link MinecraftHostSurface} が足す）
 * @param screenWidth  面の幅（ブロック数。テクスチャは {@code × RESOLUTION} の CSS px）
 * @param hoveredId    ツールチップを出す要素（無ければ空）
 */
record PaintRequest(
        RenderFrame frame,
        int screenWidth,
        int screenHeight,
        PoseStack poseStack,
        MultiBufferSource buffer,
        int packedLight,
        String hoveredId,
        String sessionId,
        boolean playAudio,
        boolean is2DAudio,
        BlockPos audioPos,
        boolean screenSpaceText
) {
}
