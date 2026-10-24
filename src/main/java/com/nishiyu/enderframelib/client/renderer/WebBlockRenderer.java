package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.core.paint.model.*;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.nishiyu.enderframelib.api.WebScreenBlockEntity;
import com.nishiyu.enderframelib.client.ClientScreenInteractionManager;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.SessionContext;
import com.nishiyu.enderframelib.client.renderer.surface.SurfaceFit;
import com.nishiyu.enderframelib.client.renderer.surface.SurfaceGeometry;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
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

public class WebBlockRenderer<T extends BlockEntity & WebScreenBlockEntity> implements BlockEntityRenderer<T> {

    public WebBlockRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public @NotNull AABB getRenderBoundingBox(T be) {
        // 画面の正面の面だけが描かれる。master（左下）から右へ幅、上へ高さ分の箱に、わずかな余白を足す
        BlockPos pos = be.getBlockPos();
        if (!be.isMaster()) return new AABB(pos);
        Direction facing = be.getFacing();
        if (facing == null || facing.getAxis().isVertical()) facing = Direction.NORTH;
        Direction right = facing.getCounterClockWise();
        BlockPos far = pos.relative(right, be.getScreenWidth() - 1).above(be.getScreenHeight() - 1);
        return new AABB(pos).minmax(new AABB(far)).inflate(0.05);
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

        SessionContext session = ClientWebManager.getSession(sessionId);
        // レイアウトの画面サイズはセッションを読み込んだときの解像度。ブロックの縦×横は、それを貼る面の大きさ
        SurfaceFit fit = SurfaceGeometry.fit(session, be.getScreenWidth(), be.getScreenHeight());
        SurfaceGeometry geometry = fit.content();
        List<DisplayItem> elementsToRender = session != null ? session.elements() : List.of();
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
        float nearZStep = geometry.zStep();
        float zStep = distSqr > 1024.0 ? nearZStep * 12.5f : (distSqr > 256.0 ? nearZStep * 5f : nearZStep);
        float subZOffset = zStep * 0.5f;

        Direction facing = be.getFacing();
        if (facing == null) facing = Direction.NORTH;
        String hoveredId = ClientScreenInteractionManager.getHoveredId(sessionId);

        poseStack.pushPose();
        poseStack.translate(0.5, 0.5, 0.5);
        poseStack.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        poseStack.translate(-0.5, -0.5, 0.501);

        // 面の左上（余白の分だけ内側）を原点にして、仮想ピクセルを下向きに数える
        poseStack.translate(fit.offsetX(), be.getScreenHeight() - fit.offsetY(), 0);
        float scale = geometry.scale();
        poseStack.scale(scale, -scale, scale);

        PaintRequest.builder(elementsToRender, geometry.paintBlocksW(), geometry.paintBlocksH(), poseStack, buffer, sessionId, be.getLevel())
                .light(packedLight, packedOverlay)
                .renderFlags(renderItems, renderText, renderRounded)
                .zOffsets(zStep, subZOffset)
                .flatLayers(true)
                .hoveredId(hoveredId)
                .lod(lodColor, isLodMode)
                .audio(true, false, be.getBlockPos())
                .render();

        poseStack.popPose();
    }
}