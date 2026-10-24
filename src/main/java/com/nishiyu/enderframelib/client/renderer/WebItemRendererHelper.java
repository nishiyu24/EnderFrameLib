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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

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
        // GUI（ホットバー・インベントリのスロット）にも描く。入力は受けないのでホバーや音は抜き、アニメーションだけ動かす
        if (spec.handShift()) applyFirstPersonHandShift(stack, displayContext, poseStack);
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

        SessionContext session = SurfaceRenderer.open(sessionId);
        // レイアウトの画面サイズはセッションを読み込んだときの解像度。渡された解像度は、セッションがまだ無いときだけ使う
        int layoutW = session != null ? session.rootW() : virtualWidth;
        int layoutH = session != null ? session.rootH() : virtualHeight;
        if (session == null || session.elements().isEmpty()) return;

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
        SurfaceStyle style = SurfaceStyle.item(packedLight, packedOverlay)
                .withAudio(new SurfaceStyle.Audio(playAudio, true, null));
        // GUI（ホットバー・インベントリのスロット）は入力を受けないのでホバーを抜く
        if (displayContext == ItemDisplayContext.GUI) style = style.withHovered("");
        SurfaceRenderer.draw(sessionId, geometry, style, poseStack, buffer);

        poseStack.popPose();
    }

    /** 開いた地図に重ねる面の登録。{@code predicate} に合う地図に {@code spec} の面を描く。先に登録した方が優先。 */
    private record MapOverlay(Predicate<ItemStack> predicate, ItemSurfaceSpec spec) {}

    private static final List<MapOverlay> MAP_OVERLAYS = new CopyOnWriteArrayList<>();

    public static void registerMapOverlay(Predicate<ItemStack> predicate, ItemSurfaceSpec spec) {
        MAP_OVERLAYS.add(new MapOverlay(predicate, spec));
    }

    /**
     * 一人称で開いた地図の上に、登録された面を重ねる。{@code ItemInHandRenderer.renderMap} の末尾から呼ばれる。
     * その時点の座標系は地図の 1px が 1 単位（左上が原点、x 右・y 下、0..128）で、手前は -z。
     * 面の置き方は {@link ItemSurfaceSpec} のとおり（地図の幅を 1 としたアイテム空間で解釈する）。
     */
    public static void renderWebOnMap(ItemStack stack, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        if (MAP_OVERLAYS.isEmpty()) return;
        ItemSurfaceSpec spec = null;
        for (MapOverlay overlay : MAP_OVERLAYS) {
            if (overlay.predicate().test(stack)) { spec = overlay.spec(); break; }
        }
        if (spec == null) return;

        String sessionId = ClientScreenInteractionManager.getSessionIdFromItem(stack);
        if (sessionId == null || sessionId.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (!ClientScreenInteractionManager.isItemInRange(mc, stack)) return;

        SessionContext session = SurfaceRenderer.open(sessionId);
        if (session == null || session.rootW() <= 0 || session.rootH() <= 0) return;
        List<DisplayItem> elements = session.elements();
        if (elements == null || elements.isEmpty()) return;

        int layoutW = session.rootW();
        int layoutH = session.rootH();
        float aspect = (float) layoutH / layoutW;
        float physicalWidth = spec.widthBlocks();
        float[] origin = spec.origin(ItemDisplayContext.NONE, aspect);
        float scale = physicalWidth / layoutW;
        SurfaceGeometry geometry = SurfaceGeometry.ofVirtual(layoutW, layoutH, physicalWidth);

        poseStack.pushPose();
        poseStack.scale(128f, 128f, 128f);
        // アイテム空間は y が上向き・手前が +z。地図は y が下向き・手前が -z なので、上端の y と z を読み替える
        poseStack.translate(origin[0], 1f - origin[1], -(0.02f / 128f) - spec.zOffset() / 128f);
        if (spec.yRotDegrees() != 0f) poseStack.mulPose(Axis.YP.rotationDegrees(spec.yRotDegrees()));
        poseStack.scale(scale, scale, -scale);

        SurfaceRenderer.draw(sessionId, geometry, SurfaceStyle.overlay(packedLight), poseStack, buffer);

        poseStack.popPose();
    }
}
