package com.nishiyu.enderframelib.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nishiyu.enderframelib.client.ClientScreenInteractionManager;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.SessionContext;
import com.nishiyu.enderframelib.client.renderer.surface.SurfaceGeometry;
import com.nishiyu.enderframelib.core.diag.PipelineStage;
import com.nishiyu.enderframelib.core.diag.StageProfiler;
import com.nishiyu.enderframelib.core.paint.SceneSource;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.PaintOffsets;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.List;

/**
 * セッションを面に描く唯一の入口。画面・コンテナ画面・HUD・ビルボード・ブロック・ホログラム・アイテム・地図の
 * どのホストも、姿勢（{@code PoseStack}）を作ってここを呼ぶだけで、同期の確認・空の判定・ホバーの取得・
 * 描画の指定の組み立ては 1 か所にある。置き場所の違いは姿勢と大きさ、描き方の違いは {@link SurfaceStyle}。
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
     * 面の大きさ（{@link SurfaceGeometry}）に合わせて、セッションの要素を描く。{@code poseStack} は、
     * 面の左上を原点に仮想ピクセル 1 つが 1 単位になっていること。
     *
     * @return 何かを描いたか
     */
    public static boolean draw(String sessionId, SurfaceGeometry geometry, SurfaceStyle style,
                               PoseStack poseStack, MultiBufferSource buffer) {
        SessionContext session = ClientWebManager.getSession(sessionId);
        List<DisplayItem> elements = session != null ? session.relativeElements() : List.of();
        PaintOffsets offsets = session != null ? session.paintOffsets() : PaintOffsets.EMPTY;
        return paint(sessionId, elements, sourceOf(session, elements), offsets, geometry.paintBlocksW(), geometry.paintBlocksH(), style,
                poseStack, buffer);
    }

    /**
     * 大きさをブロック数で与えて描く（画面・HUD・ビルボードのように、仮想解像度ではなく画面空間で描く面）。
     * {@code elements} は呼び出し側で絞り込んだ一覧でもよい。
     */
    public static boolean draw(String sessionId, List<DisplayItem> elements, int widthBlocks, int heightBlocks,
                               SurfaceStyle style, PoseStack poseStack, MultiBufferSource buffer) {
        SessionContext session = ClientWebManager.getSession(sessionId);
        PaintOffsets offsets = session != null ? session.paintOffsets() : PaintOffsets.EMPTY;
        return paint(sessionId, elements, sourceOf(session, elements), offsets, widthBlocks, heightBlocks, style, poseStack, buffer);
    }

    /**
     * セッションの外で組んだ列（{@code source} はその列を組んだシーン。{@link SceneSource#items} が {@code elements}）を描く。
     * エンティティの上の板のように、1 回だけ組んだ文書の一部を描く面が使う。
     */
    public static boolean draw(String sessionId, List<DisplayItem> elements, SceneSource source, int widthBlocks, int heightBlocks,
                               SurfaceStyle style, PoseStack poseStack, MultiBufferSource buffer) {
        return paint(sessionId, elements, source, PaintOffsets.EMPTY, widthBlocks, heightBlocks, style, poseStack, buffer);
    }

    /**
     * 列 {@code elements} を組んだシーン。セッションの直近の列（で始まる列）でなければ null（呼び出し側で絞り込んだ一覧には、前のフレームと
     * シーンの違いで比べられるシーンが無い。描画器は毎回全部を描く）。
     */
    private static SceneSource sourceOf(SessionContext session, List<DisplayItem> elements) {
        SceneSource source = session != null ? session.paintSource() : null;
        return source != null && source.baseSizeIn(elements) > 0 ? source : null;
    }

    private static boolean paint(String sessionId, List<DisplayItem> elements, SceneSource source, PaintOffsets paintOffsets,
                                 int widthBlocks, int heightBlocks, SurfaceStyle style, PoseStack poseStack, MultiBufferSource buffer) {
        if (style.lodActive()) return drawLod(style.lodColor(), widthBlocks, heightBlocks, poseStack, buffer);
        if (elements == null || elements.isEmpty()) return false;
        String hovered = style.hovered() != null ? style.hovered() : ClientScreenInteractionManager.getHoveredId(sessionId);
        SurfaceStyle.Audio audio = style.audio();
        PaintRequest request = new PaintRequest(elements, paintOffsets, source,
                widthBlocks, heightBlocks, poseStack, buffer,
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
