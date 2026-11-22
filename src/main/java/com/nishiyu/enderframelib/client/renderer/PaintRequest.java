package com.nishiyu.enderframelib.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.PaintOffsets;
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
        PaintOffsets paintOffsets,
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
    PaintRequest {
        paintOffsets = paintOffsets == null ? PaintOffsets.EMPTY : paintOffsets.snapshot();
    }

    /** 項目の一覧だけを差し替えた複製（親相対のまま GPU 経路へ渡し、描けなければ絶対座標へ直して従来の経路へ渡す）。 */
    PaintRequest withElements(List<DisplayItem> newElements) {
        return new PaintRequest(newElements, paintOffsets, screenWidth, screenHeight, poseStack, buffer, packedLight, packedOverlay,
                renderItems, renderText, renderRounded, zStep, subZOffset, hoveredId, lodColor, isLodMode, level,
                playAudio, is2DAudio, sessionId, audioPos, flatLayers, screenSpaceText);
    }

    void render() {
        DisplayItemDrawer.renderElements(this);
    }
}
