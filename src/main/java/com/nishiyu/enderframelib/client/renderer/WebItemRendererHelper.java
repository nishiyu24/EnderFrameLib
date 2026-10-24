package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.core.paint.model.*;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.nishiyu.enderframelib.client.ClientScreenInteractionManager;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.SessionContext;
import com.nishiyu.enderframelib.client.renderer.surface.ItemSurfaceSpec;
import com.nishiyu.enderframelib.client.renderer.surface.SurfaceGeometry;
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
     * {@link ItemSurfaceSpec} どおりに面を置いて描画する（一人称の見やすさ補正を含む）。
     * BEWLR の {@code renderByItem} からはこれを呼ぶだけでよい。
     */
    public static void renderSurface(
            ItemStack stack, ItemDisplayContext displayContext,
            PoseStack poseStack, MultiBufferSource buffer, int packedLight, int packedOverlay,
            ItemSurfaceSpec spec
    ) {
        // ホットバーやインベントリの 1 マスにまで Web 画面を毎フレーム描くと重い。スロットには描かない
        if (displayContext == ItemDisplayContext.GUI) return;
        applyFirstPersonHandShift(stack, displayContext, poseStack);
        // 画面の大きさはセッションを読み込んだときの解像度。仕様の解像度は、セッションがまだ無いときの代わり
        String sessionId = ClientScreenInteractionManager.getSessionIdFromItem(stack);
        SessionContext session = sessionId != null ? ClientWebManager.getSession(sessionId) : null;
        float aspect = session != null && session.rootW() > 0
                ? (float) session.rootH() / session.rootW()
                : (float) spec.virtualHeight() / spec.virtualWidth();
        float[] origin = spec.origin(displayContext, aspect);
        renderWebOnItem(stack, displayContext, poseStack, buffer, packedLight, packedOverlay,
                spec.virtualWidth(), spec.virtualHeight(),
                origin[0], origin[1], origin[2],
                spec.yRotDegrees(), spec.physicalWidth(displayContext));
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
        // レイアウトの画面サイズはセッションを読み込んだときの解像度。渡された解像度は、セッションがまだ無いときだけ使う
        int layoutW = session != null ? session.rootW() : virtualWidth;
        int layoutH = session != null ? session.rootH() : virtualHeight;

        List<DisplayItem> elements = session != null ? session.elements() : List.of();
        if (elements == null || elements.isEmpty()) return;

        poseStack.pushPose();
        poseStack.translate(screenOriginX, screenOriginY, screenOriginZ);
        if (yRotDegrees != 0f) {
            poseStack.mulPose(Axis.YP.rotationDegrees(yRotDegrees));
        }

        float scale = physicalWidthInBlocks / (float) layoutW;
        poseStack.scale(scale, -scale, scale);

        boolean playAudio = (displayContext == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND ||
                displayContext == ItemDisplayContext.FIRST_PERSON_LEFT_HAND ||
                displayContext == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND ||
                displayContext == ItemDisplayContext.THIRD_PERSON_LEFT_HAND);

        SurfaceGeometry geometry = SurfaceGeometry.ofVirtual(layoutW, layoutH, physicalWidthInBlocks);

        PaintRequest.builder(elements, geometry.paintBlocksW(), geometry.paintBlocksH(), poseStack, buffer, sessionId, mc.level)
                .light(packedLight, packedOverlay)
                .renderFlags(true, true, true)
                .zOffsets(geometry.zStep(), geometry.zStep() * 0.5f)
                .flatLayers(true)
                .hoveredId(ClientScreenInteractionManager.getHoveredId(sessionId))
                .audio(playAudio, true, null)
                .screenSpaceText(false)
                .render();

        poseStack.popPose();
    }
}