package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.core.paint.model.*;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.nishiyu.enderframelib.client.ClientScreenInteractionManager;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.SessionContext;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 手持ちアイテム（タブレットやポータブルスクリーン等）の表面にWeb画面を描画するための汎用ヘルパー
 */
public class WebItemRendererHelper {

    /**
     * 一人称視点で下を向いた際の見やすさ補正と両手持ち時のZオフセットを適用する
     */
    public static void applyFirstPersonHandShift(ItemStack stack, ItemDisplayContext displayContext, PoseStack poseStack) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && (displayContext == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND || displayContext == ItemDisplayContext.FIRST_PERSON_LEFT_HAND)) {
            float pitch = mc.player.getXRot();
            if (Float.isNaN(pitch)) pitch = 0.0f;

            float lookDownAmount = Mth.clamp(pitch / 90.0f, 0.0f, 1.0f);
            float yShift = lookDownAmount * 0.3f;
            float zShift = 0.0f;

            ItemStack mainHand = mc.player.getMainHandItem();
            ItemStack offHand = mc.player.getOffhandItem();

            boolean holdingBoth = mainHand.getItem() == stack.getItem() && offHand.getItem() == stack.getItem();

            if (holdingBoth) {
                if (displayContext == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND) zShift = 0.08f;
                else zShift = -0.08f;
            }
            poseStack.translate(0, yShift, zShift);
        }
    }

    /**
     * アイテムの指定位置・サイズにWebセッションのUIを描画する
     */
    public static void renderWebOnItem(
            ItemStack stack, ItemDisplayContext displayContext,
            PoseStack poseStack, MultiBufferSource buffer, int packedLight, int packedOverlay,
            int virtualWidth, int virtualHeight,
            float screenOriginX, float screenOriginY, float screenOriginZ,
            float yRotDegrees, float physicalWidthInBlocks
    ) {
        Minecraft mc = Minecraft.getInstance();
        if (!ClientScreenInteractionManager.isItemInRange(mc, stack)) return;

        String sessionId = ClientScreenInteractionManager.getSessionIdFromItem(stack);
        if (sessionId == null || sessionId.isEmpty()) return;

        ClientScreenInteractionManager.ensureSynced(sessionId);

        SessionContext session = ClientWebManager.getSession(sessionId);
        if (session != null && (session.rootW() != virtualWidth || session.rootH() != virtualHeight)) {
            session.recomputeLayout(virtualWidth, virtualHeight);
        }

        List<DisplayItem> elements = session != null ? session.elements() : List.of();
        if (elements == null || elements.isEmpty()) return;

        poseStack.pushPose();
        poseStack.translate(screenOriginX, screenOriginY, screenOriginZ);
        if (yRotDegrees != 0f) {
            poseStack.mulPose(Axis.YP.rotationDegrees(yRotDegrees));
        }

        float scale = physicalWidthInBlocks / (float) virtualWidth;
        poseStack.scale(scale, -scale, scale);

        boolean playAudio = (displayContext == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND ||
                displayContext == ItemDisplayContext.FIRST_PERSON_LEFT_HAND ||
                displayContext == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND ||
                displayContext == ItemDisplayContext.THIRD_PERSON_LEFT_HAND);

        int logicWidthBlocks = (int) Math.ceil(virtualWidth / DisplayItemDrawer.RESOLUTION);
        int logicHeightBlocks = (int) Math.ceil(virtualHeight / DisplayItemDrawer.RESOLUTION);

        PaintRequest.builder(elements, logicWidthBlocks, logicHeightBlocks, poseStack, buffer, sessionId, mc.level)
                .light(packedLight, packedOverlay)
                .renderFlags(true, true, true)
                .zOffsets(0.02f, 0.01f)
                .hoveredId(ClientScreenInteractionManager.getHoveredId(sessionId))
                .audio(playAudio, true, null)
                .render();

        poseStack.popPose();
    }
}