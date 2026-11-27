package com.nishiyu.enderframelib.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.PaintOffsets;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * 1 枚の面を描く指定。{@link SurfaceRenderer} だけが作り、{@link MinecraftHostSurface} が読む。
 * ホストは {@link SurfaceStyle} を通して設定する。
 *
 * @param elements     描く項目（親相対のまま）
 * @param sceneSource  項目を組んだシーン（保持層の判定に使う。無ければ null）
 * @param screenWidth  面の幅（ブロック数。テクスチャは {@code × RESOLUTION} の CSS px）
 * @param hoveredId    ツールチップを出す要素（無ければ空）
 */
record PaintRequest(
        List<DisplayItem> elements,
        PaintOffsets paintOffsets,
        com.nishiyu.enderframelib.core.paint.SceneSource sceneSource,
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
    PaintRequest {
        paintOffsets = paintOffsets == null ? PaintOffsets.EMPTY : paintOffsets.snapshot();
    }
}
