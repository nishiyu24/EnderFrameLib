package com.nishiyu.enderframelib.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nishiyu.enderframelib.core.model.UIElement;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * UI描画パスのパラメータを集約するオブジェクトおよびビルダ。
 * 6つの描画ホスト（GUI, ContainerScreen, BlockFace, HUD, Billboard, Item）の前置処理を一本化する。
 */
public record WebPaintPass(
        List<UIElement> elements,
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
        BlockPos audioPos
) {
    public void render() {
        WebRenderCore.renderElements(this);
    }

    public static Builder builder(List<UIElement> elements, int screenWidth, int screenHeight,
                                  PoseStack poseStack, MultiBufferSource buffer,
                                  String sessionId, Level level) {
        return new Builder(elements, screenWidth, screenHeight, poseStack, buffer, sessionId, level);
    }

    public static WebPaintPass forGui(List<UIElement> elements, int width, int height,
                                      PoseStack poseStack, MultiBufferSource buffer,
                                      String sessionId, String hoveredId, Level level,
                                      boolean playAudio) {
        return builder(elements, width, height, poseStack, buffer, sessionId, level)
                .light(0xF000F0, OverlayTexture.NO_OVERLAY)
                .zOffsets(0.05f, 0.02f)
                .hoveredId(hoveredId)
                .audio(playAudio, true, null)
                .build();
    }

    public static WebPaintPass forBillboard(List<UIElement> elements, int width, int height,
                                            PoseStack poseStack, MultiBufferSource buffer,
                                            String sessionId, String hoveredId, Level level) {
        return builder(elements, width, height, poseStack, buffer, sessionId, level)
                .light(0xF000F0, OverlayTexture.NO_OVERLAY)
                .zOffsets(-0.01f, -0.005f)
                .hoveredId(hoveredId)
                .audio(false, true, null)
                .build();
    }

    public static class Builder {
        private final List<UIElement> elements;
        private final int screenWidth;
        private final int screenHeight;
        private final PoseStack poseStack;
        private final MultiBufferSource buffer;
        private final String sessionId;
        private final Level level;

        private int packedLight = 0xF000F0;
        private int packedOverlay = OverlayTexture.NO_OVERLAY;
        private boolean renderItems = true;
        private boolean renderText = true;
        private boolean renderRounded = true;
        private float zStep = 0.05f;
        private float subZOffset = 0.02f;
        private String hoveredId = "";
        private int lodColor = 0;
        private boolean isLodMode = false;
        private boolean playAudio = true;
        private boolean is2DAudio = true;
        private BlockPos audioPos = null;

        public Builder(List<UIElement> elements, int screenWidth, int screenHeight,
                       PoseStack poseStack, MultiBufferSource buffer,
                       String sessionId, Level level) {
            this.elements = elements;
            this.screenWidth = screenWidth;
            this.screenHeight = screenHeight;
            this.poseStack = poseStack;
            this.buffer = buffer;
            this.sessionId = sessionId;
            this.level = level;
        }

        public Builder light(int packedLight, int packedOverlay) {
            this.packedLight = packedLight;
            this.packedOverlay = packedOverlay;
            return this;
        }

        public Builder renderFlags(boolean renderItems, boolean renderText, boolean renderRounded) {
            this.renderItems = renderItems;
            this.renderText = renderText;
            this.renderRounded = renderRounded;
            return this;
        }

        public Builder zOffsets(float zStep, float subZOffset) {
            this.zStep = zStep;
            this.subZOffset = subZOffset;
            return this;
        }

        public Builder hoveredId(String hoveredId) {
            this.hoveredId = (hoveredId != null) ? hoveredId : "";
            return this;
        }

        public Builder lod(int lodColor, boolean isLodMode) {
            this.lodColor = lodColor;
            this.isLodMode = isLodMode;
            return this;
        }

        public Builder audio(boolean playAudio, boolean is2DAudio, BlockPos audioPos) {
            this.playAudio = playAudio;
            this.is2DAudio = is2DAudio;
            this.audioPos = audioPos;
            return this;
        }

        public WebPaintPass build() {
            return new WebPaintPass(
                    elements, screenWidth, screenHeight, poseStack, buffer,
                    packedLight, packedOverlay, renderItems, renderText, renderRounded,
                    zStep, subZOffset, hoveredId, lodColor, isLodMode,
                    level, playAudio, is2DAudio, sessionId, audioPos
            );
        }

        public void render() {
            build().render();
        }
    }
}
