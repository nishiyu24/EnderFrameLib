package com.nishiyu.enderframelib.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * {@link DisplayItemDrawer#renderElements} に渡す描画パスのパラメータ。{@link SurfaceRenderer} だけが作る。
 * ホストは {@link SurfaceStyle} を通して設定する。
 */
record PaintRequest(
        List<DisplayItem> elements,
        int screenWidth,
        int screenHeight,
        PoseStack poseStack,
        MultiBufferSource buffer,
        int packedLight,
        int packedOverlay,
        boolean renderItems,
        boolean renderText,
        boolean renderRounded,
        float zStep,
        float subZOffset,
        String hoveredId,
        int lodColor,
        boolean isLodMode,
        Level level,
        boolean playAudio,
        boolean is2DAudio,
        String sessionId,
        BlockPos audioPos,
        boolean flatLayers,
        boolean screenSpaceText
) {
    void render() {
        DisplayItemDrawer.renderElements(this);
    }
}
