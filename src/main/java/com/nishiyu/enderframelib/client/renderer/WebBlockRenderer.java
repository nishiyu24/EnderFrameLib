package com.nishiyu.enderframelib.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.nishiyu.enderframelib.api.IWebScreenBlockEntity;
import com.nishiyu.enderframelib.client.ClientScreenInteractionManager;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.core.model.UIElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class WebBlockRenderer<T extends BlockEntity & IWebScreenBlockEntity> implements BlockEntityRenderer<T> {

    public WebBlockRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public @NotNull AABB getRenderBoundingBox(T be) {
        BlockPos pos = be.getBlockPos();
        return new AABB(pos).inflate(be.getScreenWidth() + 1, be.getScreenHeight() + 1, be.getScreenWidth() + 1);
    }

    @Override
    public boolean shouldRenderOffScreen(@NotNull T be) {
        return true;
    }

    @Override
    public void render(T be, float partialTick, @NotNull PoseStack poseStack, @NotNull MultiBufferSource buffer, int packedLight, int packedOverlay) {
        if (!be.isMaster()) return;

        WebMediaManager.linkScreenToSpeakers(be.getBlockPos(), be.getLinkedSpeakers());

        String sessionId = be.getSessionId();
        ClientScreenInteractionManager.ensureSynced(sessionId);

        List<UIElement> elementsToRender = ClientWebManager.getElements(sessionId);
        int lodColor = be.getLodColor();

        if ((elementsToRender == null || elementsToRender.isEmpty()) && lodColor == 0x00000000) return;

        Minecraft mc = Minecraft.getInstance();
        double camX = mc.gameRenderer.getMainCamera().getPosition().x;
        double camY = mc.gameRenderer.getMainCamera().getPosition().y;
        double camZ = mc.gameRenderer.getMainCamera().getPosition().z;
        double dx = be.getBlockPos().getX() + 0.5 - camX;
        double dy = be.getBlockPos().getY() + 0.5 - camY;
        double dz = be.getBlockPos().getZ() + 0.5 - camZ;
        double distSqr = dx * dx + dy * dy + dz * dz;

        boolean isLodMode = distSqr > 4096.0;
        boolean renderItems = distSqr < 1024.0, renderText = distSqr < 256.0, renderRounded = distSqr < 256.0;
        float zStep = distSqr > 1024.0 ? 0.25f : (distSqr > 256.0 ? 0.1f : 0.02f);
        float subZOffset = zStep * 0.5f;

        Direction facing = be.getFacing();
        if (facing == null) facing = Direction.NORTH;
        String hoveredId = ClientScreenInteractionManager.getHoveredId(sessionId);

        poseStack.pushPose();
        poseStack.translate(0.5, 0.5, 0.5);
        poseStack.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        poseStack.translate(-0.5, -0.5, 0.501);

        float scale = 1.0f / WebRenderCore.RESOLUTION;
        poseStack.scale(scale, -scale, scale);
        poseStack.translate(0, -WebRenderCore.RESOLUTION * be.getScreenHeight(), 0);

        WebPaintPass.builder(elementsToRender, be.getScreenWidth(), be.getScreenHeight(), poseStack, buffer, sessionId, be.getLevel())
                .light(packedLight, packedOverlay)
                .renderFlags(renderItems, renderText, renderRounded)
                .zOffsets(zStep, subZOffset)
                .hoveredId(hoveredId)
                .lod(lodColor, isLodMode)
                .audio(true, false, be.getBlockPos())
                .render();

        poseStack.popPose();
    }
}