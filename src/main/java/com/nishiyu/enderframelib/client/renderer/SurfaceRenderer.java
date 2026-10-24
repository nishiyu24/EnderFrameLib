package com.nishiyu.enderframelib.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nishiyu.enderframelib.client.ClientScreenInteractionManager;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.SessionContext;
import com.nishiyu.enderframelib.client.renderer.surface.SurfaceGeometry;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * セッションを面に描く唯一の入口。画面・コンテナ画面・HUD・ビルボード・ブロック・ホログラム・アイテム・地図の
 * どのホストも、姿勢（{@code PoseStack}）を作ってここを呼ぶだけで、同期の確認・空の判定・ホバーの取得・
 * 描画パスの組み立ては 1 か所にある。置き場所の違いは姿勢と大きさ、描き方の違いは {@link SurfaceStyle}。
 */
public final class SurfaceRenderer {

    private SurfaceRenderer() {}

    /** セッションの同期を確かめて返す。まだ無ければ {@code null}（そのうち届く）。大きさを決めるために先に呼ぶ。 */
    public static @Nullable SessionContext open(String sessionId) {
        ClientScreenInteractionManager.ensureSynced(sessionId);
        return ClientWebManager.getSession(sessionId);
    }

    /**
     * 面の大きさ（{@link SurfaceGeometry}）に合わせて、セッションの要素を描く。{@code poseStack} は、
     * 面の左上を原点に仮想ピクセル 1 つが 1 単位になっていること。
     *
     * @return 何かを描いたか
     */
    public static boolean draw(String sessionId, SurfaceGeometry geometry, SurfaceStyle style,
                               PoseStack poseStack, MultiBufferSource buffer) {
        SessionContext session = ClientWebManager.getSession(sessionId);
        List<DisplayItem> elements = session != null ? session.elements() : List.of();
        float step = switch (style.depth()) {
            case SurfaceStyle.Depth.Geometry g -> geometry.zStep() * g.scale();
            case SurfaceStyle.Depth.Fixed f -> f.step();
        };
        float sub = style.depth() instanceof SurfaceStyle.Depth.Fixed f ? f.sub() : step * 0.5f;
        return paint(sessionId, elements, geometry.paintBlocksW(), geometry.paintBlocksH(), step, sub, style, poseStack, buffer);
    }

    /**
     * 大きさをブロック数で与えて描く（画面・HUD・ビルボードのように、仮想解像度ではなく画面空間で描く面）。
     * {@code elements} は呼び出し側で絞り込んだ一覧でもよい。
     */
    public static boolean draw(String sessionId, List<DisplayItem> elements, int widthBlocks, int heightBlocks,
                               SurfaceStyle style, PoseStack poseStack, MultiBufferSource buffer) {
        if (!(style.depth() instanceof SurfaceStyle.Depth.Fixed f)) {
            throw new IllegalArgumentException("a surface without geometry needs a fixed depth");
        }
        return paint(sessionId, elements, widthBlocks, heightBlocks, f.step(), f.sub(), style, poseStack, buffer);
    }

    private static boolean paint(String sessionId, List<DisplayItem> elements, int widthBlocks, int heightBlocks,
                                 float step, float sub, SurfaceStyle style, PoseStack poseStack, MultiBufferSource buffer) {
        boolean empty = elements == null || elements.isEmpty();
        // 遠距離の塗りだけは、要素が無くても描く
        if (empty && style.lodColor() == 0) return false;
        String hovered = style.hovered() != null ? style.hovered() : ClientScreenInteractionManager.getHoveredId(sessionId);
        SurfaceStyle.Detail detail = style.detail();
        SurfaceStyle.Audio audio = style.audio();
        new PaintRequest(empty ? List.of() : elements, widthBlocks, heightBlocks, poseStack, buffer,
                style.light(), style.overlay(), detail.items(), detail.text(), detail.rounded(),
                step, sub, hovered != null ? hovered : "", style.lodColor(), style.lodActive(),
                Minecraft.getInstance().level, audio.play(), audio.twoD(), sessionId, audio.pos(),
                style.flatLayers(), style.screenSpaceText()).render();
        return true;
    }
}
