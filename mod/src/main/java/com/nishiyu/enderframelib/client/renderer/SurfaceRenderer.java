package com.nishiyu.enderframelib.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nishiyu.enderframelib.client.ClientScreenInteractionManager;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.SessionContext;
import com.nishiyu.enderframelib.client.renderer.surface.SurfaceGeometry;
import com.nishiyu.enderframelib.core.diag.PipelineStage;
import com.nishiyu.enderframelib.core.diag.StageProfiler;
import com.nishiyu.enderframelib.core.paint.RenderFrame;
import net.minecraft.Util;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.List;

/**
 * セッションを面に描く唯一の入口。画面・コンテナ画面・HUD・ビルボード・ブロック・ホログラム・アイテム・地図の
 * どのホストも、姿勢（{@code PoseStack}）を作ってここを呼ぶだけで、同期の確認・空の判定・ホバーの取得・
 * 描画の指定の組み立ては 1 か所にある。描くのはビューのフレームの値（{@code UiView#renderFrame}。M3b-4c-2b-1 で表示項目の列を受け取る口を削除）。置き場所の違いは姿勢と大きさ、描き方の違いは {@link SurfaceStyle}。
 * 描くのはコアの描画（{@link MinecraftHostSurface}）だけ（M3a で MOD の旧レンダラを撤去した）。
 */
public final class SurfaceRenderer {

    private SurfaceRenderer() {}

    /** セッションの同期を確かめて返す。まだ無ければ {@code null}（そのうち届く）。大きさを決めるために先に呼ぶ。 */
    public static @Nullable SessionContext open(String sessionId) {
        ClientScreenInteractionManager.ensureSynced(sessionId);
        return ClientWebManager.getSession(sessionId);
    }

    /**
     * 面の大きさ（{@link SurfaceGeometry}）に合わせて、セッションのフレームを描く。{@code poseStack} は、
     * 面の左上を原点に仮想ピクセル 1 つが 1 単位になっていること。
     *
     * @return 何かを描いたか
     */
    public static boolean draw(String sessionId, SurfaceGeometry geometry, SurfaceStyle style,
                               PoseStack poseStack, MultiBufferSource buffer) {
        return draw(sessionId, geometry.paintBlocksW(), geometry.paintBlocksH(), style, poseStack, buffer);
    }

    /**
     * 大きさをブロック数で与えて、セッションのフレームを描く（画面・HUD・ビルボードのように、仮想解像度ではなく画面空間で描く面）。
     * 一部を省くときは列を絞らず {@link SurfaceStyle#exclude} で節を除く（フレームの値はシーンを持ったまま。M3b-4c-2b-1）。
     */
    public static boolean draw(String sessionId, int widthBlocks, int heightBlocks,
                               SurfaceStyle style, PoseStack poseStack, MultiBufferSource buffer) {
        if (style.lodActive()) return drawLod(style.lodColor(), widthBlocks, heightBlocks, poseStack, buffer);
        SessionContext session = ClientWebManager.getSession(sessionId);
        if (session == null) return false;
        RenderFrame frame = session.renderFrame(List.of(), style.exclude(), Util.getMillis());
        return paint(sessionId, frame, widthBlocks, heightBlocks, style, poseStack, buffer);
    }

    /**
     * セッションの外で組んだフレームの値 {@code frame}（描く時刻はここで入れる）を描く。エンティティの上の板のように、1 回だけ組んだ文書の
     * 一部を描く面が使う。
     */
    public static boolean draw(String sessionId, RenderFrame frame, int widthBlocks, int heightBlocks,
                               SurfaceStyle style, PoseStack poseStack, MultiBufferSource buffer) {
        if (style.lodActive()) return drawLod(style.lodColor(), widthBlocks, heightBlocks, poseStack, buffer);
        if (frame == null) return false;
        return paint(sessionId, frame.at(Util.getMillis()), widthBlocks, heightBlocks, style, poseStack, buffer);
    }

    private static boolean paint(String sessionId, RenderFrame frame, int widthBlocks, int heightBlocks, SurfaceStyle style,
                                 PoseStack poseStack, MultiBufferSource buffer) {
        if (frame.isEmpty()) return false;
        String hovered = style.hovered() != null ? style.hovered() : ClientScreenInteractionManager.getHoveredId(sessionId);
        SurfaceStyle.Audio audio = style.audio();
        PaintRequest request = new PaintRequest(frame, widthBlocks, heightBlocks, poseStack, buffer,
                style.light(), hovered != null ? hovered : "", sessionId, audio.play(), audio.twoD(), audio.pos(),
                style.screenSpaceText());
        long tSurface = StageProfiler.begin(PipelineStage.SURFACE);
        try {
            return MinecraftHostSurface.draw(request);
        } finally {
            StageProfiler.end(PipelineStage.SURFACE, tSurface);
        }
    }

    /** 遠距離の面: 全体を 1 色で塗る（アルファ 0 なら何も描かない）。 */
    private static boolean drawLod(int argb, int widthBlocks, int heightBlocks, PoseStack poseStack, MultiBufferSource buffer) {
        if ((argb >>> 24) == 0) return false;
        float w = widthBlocks * SurfaceGeometry.RESOLUTION, h = heightBlocks * SurfaceGeometry.RESOLUTION;
        Matrix4f m = poseStack.last().pose();
        VertexConsumer b = buffer.getBuffer(RenderType.gui());
        b.addVertex(m, 0, 0, 0).setColor(argb);
        b.addVertex(m, 0, h, 0).setColor(argb);
        b.addVertex(m, w, h, 0).setColor(argb);
        b.addVertex(m, w, 0, 0).setColor(argb);
        return true;
    }
}
