package com.nishiyu.enderframelib.client.renderer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.nishiyu.enderframelib.EnderFrameLib;
import com.nishiyu.enderframelib.core.diag.PipelineStage;
import com.nishiyu.enderframelib.core.diag.StageProfiler;
import com.nishiyu.enderframelib.core.paint.StaticFrameGate;
import com.nishiyu.enderframelib.core.paint.gl.LwjglGlRenderBackend;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minecraft に依存しない描画コア（{@link LwjglGlRenderBackend}）で面を描き、結果のテクスチャを 1 枚の板として貼る。
 *
 * <p>コアのバックエンドは Minecraft の GL コンテキストとテクスチャを共有する見えないコンテキストで動く
 * （{@link LwjglGlRenderBackend#sharedWith}）。描いた結果は共有テクスチャなので、読み戻さずにそのまま貼れる。
 * 呼び出しの前後で Minecraft の GL コンテキストと状態は元に戻る。これにより、スタンドアロンのアプリと
 * 同じ描画コードが Minecraft の画面・ブロック・ホログラムでも動く。
 *
 * <p>試験的な機能で、既定では無効（{@link #enabled}）。コアが描けない内容（{@code <canvas>}・3D シーン・
 * エンティティ／アイテム／流体／プレイヤーの頭などの画像・ビットマップ書体）を含む面は {@link #tryDraw} が {@code false} を返し、
 * 従来の描画経路がそのまま描く。例外が出たら以後その面はコアを使わない。
 */
public final class CoreGpuSurface {

    /** {@code EnderFrameBuiltInConfig.useCoreBackend} の反映先。 */
    static volatile boolean enabled = false;

    private static final int MAX_SURFACES = 8;
    private static final int MAX_TEXTURE_SIDE = 4096;

    private record Key(String sessionId, int w, int h, int dprMilli) {}

    private static final Map<Key, LwjglGlRenderBackend> BACKENDS = new LinkedHashMap<>(16, 0.75f, true);
    private static final java.util.Set<String> BROKEN = new java.util.HashSet<>();
    /** GPU のタイマークエリを張り済みのバックエンド（閉じたものは {@link #clear}・{@link #dropSession} で外す）。 */
    private static final java.util.Set<LwjglGlRenderBackend> GPU_TIMED =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /**
     * バックエンドごとの「直前に描いた内容」の判定（core の {@link StaticFrameGate}。項目は不変で、変わるときは別の
     * インスタンスになるので、同じ項目が並んでいてアニメーションも動いていなければ描き直しても同じ絵になる）。
     */
    private static final Map<LwjglGlRenderBackend, StaticFrameGate> PAINTED =
            new java.util.IdentityHashMap<>();

    /** 面の再描画を飛ばせるか。既定は有効。切り分け用に {@code -Denderframe.core.noskip=true} で止められる。 */
    private static final boolean SKIP_STATIC = !Boolean.getBoolean("enderframe.core.noskip");

    private CoreGpuSurface() {}

    /** 設定の反映側から有効・無効を切り替える。無効にしたら共有テクスチャを持つバックエンドを閉じる。 */
    public static void setEnabled(boolean value) {
        if (enabled && !value) clear();
        enabled = value;
    }

    /** 共有テクスチャを持つバックエンドをすべて閉じる（リソースの再読み込み・ワールドを離れるとき）。 */
    public static void release() {
        clear();
    }

    /**
     * コアで描けるなら描いて {@code true}。描けない（無効・内容が対象外・失敗）なら何もせず {@code false}。
     */
    static boolean tryDraw(PaintRequest pass) {
        if (!enabled || !RenderSystem.isOnRenderThread()) return false;
        String session = pass.sessionId() == null ? "" : pass.sessionId();
        if (BROKEN.contains(session)) return false;
        if (!pass.renderItems() || !pass.renderText() || !pass.renderRounded() || pass.isLodMode()) return false;
        List<DisplayItem> items = pass.elements();
        if (items == null || items.isEmpty() || !drawable(items)) return false;
        // 面の大きさはブロック数。項目の座標は RESOLUTION 倍の px（従来の経路が maxAllowedX = screenWidth * RESOLUTION と同じ）
        int w = Math.round(pass.screenWidth() * DisplayItemDrawer.RESOLUTION);
        int h = Math.round(pass.screenHeight() * DisplayItemDrawer.RESOLUTION);
        if (w <= 0 || h <= 0) return false;

        try {
            float dpr = pass.screenSpaceText() ? (float) Minecraft.getInstance().getWindow().getGuiScale() : 2f;
            dpr = Math.max(1f, Math.min(dpr, (float) MAX_TEXTURE_SIDE / Math.max(w, h)));
            LwjglGlRenderBackend backend = backendFor(new Key(session, w, h, Math.round(dpr * 1000)), w, h, dpr);
            backend.fonts(WebFontManager.McFontResolver.INSTANCE);
            McMediaImages.INSTANCE.begin(pass);
            backend.images(McMediaImages.INSTANCE);
            // 計測が有効なら GPU のタイマークエリも張る（面ごとに 1 度。無効の間は GL を呼ばない）
            if (StageProfiler.enabled() && GPU_TIMED.add(backend)) backend.enableGpuTiming();
            StaticFrameGate gate = PAINTED.computeIfAbsent(backend, b -> new StaticFrameGate(SKIP_STATIC));
            int fontGen = WebFontManager.McFontResolver.generation();
            if (gate.canSkip(items, fontGen)) {
                // 変化もアニメも無い: 前回描いたテクスチャをそのまま貼る（GPU の描画も CPU 側の組み立ても無し）
                StageProfiler.end(PipelineStage.SURFACE_CORE_SKIPPED, StageProfiler.begin(PipelineStage.SURFACE_CORE_SKIPPED));
            } else {
                long tRender = StageProfiler.begin(PipelineStage.SURFACE_CORE_RENDER);
                try {
                    long now = net.minecraft.Util.getMillis();
                    backend.render(items, now);
                    gate.painted(items, fontGen, backend.hasActiveAnimation(items, now));
                } finally {
                    StageProfiler.end(PipelineStage.SURFACE_CORE_RENDER, tRender);
                }
            }
            flushPending(pass.buffer());
            long tBlit = StageProfiler.begin(PipelineStage.SURFACE_CORE_BLIT);
            try {
                drawTexture(backend, pass.poseStack().last().pose(), w, h);
            } finally {
                StageProfiler.end(PipelineStage.SURFACE_CORE_BLIT, tBlit);
            }
            drawTooltip(pass, items, w, h);
            return true;
        } catch (Throwable t) {
            BROKEN.add(session);
            EnderFrameLib.LOGGER.warn("[EnderFrameLib] core GPU backend failed for session '{}'; falling back to the legacy renderer", session, t);
            dropSession(session);
            return false;
        }
    }

    /**
     * コアが描ける内容か。対象外が 1 つでもあれば従来の経路に任せる。
     *
     * <p>{@code :hover} などの見た目は、節の状態が変わったときにスタイル解決の側が項目へ反映する（描画は項目を描くだけ）ので、
     * ホバー中の要素も対象に含める。{@code transition} の補間はコアの {@code CssAnimator} が進める。
     */
    static boolean drawable(List<DisplayItem> items) {
        for (DisplayItem it : items) {
            switch (it.type()) {
                case SCENE -> {
                    return false;
                }
                case IMAGE, VIDEO, CANVAS -> {
                    if (!McMediaImages.supports(it)) return false;
                }
                default -> {
                }
            }
        }
        return true;
    }

    /** ホバー中の要素の {@code title} をツールチップとして板の上に重ねる（コアは描かない。従来の経路と同じ見た目・位置）。 */
    private static void drawTooltip(PaintRequest pass, List<DisplayItem> items, int w, int h) {
        String hovered = pass.hoveredId();
        if (hovered == null || hovered.isEmpty()) return;
        DisplayItem target = null;
        String text = null;
        for (DisplayItem it : items) {
            if (!hovered.equals(it.baseId())) continue;
            text = DisplayItemDrawer.extractTooltipText(it);
            if (text != null) {
                target = it;
                break;
            }
        }
        if (target == null || text.isEmpty()) return;
        net.minecraft.client.gui.Font font = WebFontManager.getDefaultFont();
        if (font == null) font = Minecraft.getInstance().font;
        float z = DisplayItemDrawer.ZLayers.ELEMENT_Z_STEP * 4 + 0.05f;
        DisplayItemDrawer.renderTooltipOverlay(pass.poseStack(), pass.buffer(), font, target, text,
                w, h, z, pass.packedLight());
        flushPending(pass.buffer());
    }

    private static LwjglGlRenderBackend backendFor(Key key, int w, int h, float dpr) {
        LwjglGlRenderBackend b = BACKENDS.get(key);
        if (b != null) return b;
        // 同じセッションの別の大きさは古いので捨てる
        dropSession(key.sessionId());
        long window = Minecraft.getInstance().getWindow().getWindow();
        b = LwjglGlRenderBackend.sharedWith(window, w, h, dpr);
        BACKENDS.put(key, b);
        Iterator<Map.Entry<Key, LwjglGlRenderBackend>> it = BACKENDS.entrySet().iterator();
        while (BACKENDS.size() > MAX_SURFACES && it.hasNext()) {
            Map.Entry<Key, LwjglGlRenderBackend> eldest = it.next();
            if (eldest.getValue() == b) break;
            it.remove();
            GPU_TIMED.remove(eldest.getValue());
            eldest.getValue().close();
        }
        return b;
    }

    private static void dropSession(String session) {
        Iterator<Map.Entry<Key, LwjglGlRenderBackend>> it = BACKENDS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Key, LwjglGlRenderBackend> e = it.next();
            if (e.getKey().sessionId().equals(session)) {
                it.remove();
                GPU_TIMED.remove(e.getValue());
                PAINTED.remove(e.getValue());
                try {
                    e.getValue().close();
                } catch (Throwable ignored) {
                    // 後始末の失敗は握りつぶす
                }
            }
        }
    }

    /** 全部閉じる（リソースの再読み込み・ワールドを抜けるとき）。 */
    static void clear() {
        for (LwjglGlRenderBackend b : BACKENDS.values()) {
            try {
                b.close();
            } catch (Throwable ignored) {
                // 後始末の失敗は握りつぶす
            }
        }
        BACKENDS.clear();
        GPU_TIMED.clear();
        PAINTED.clear();
        BROKEN.clear();
        McMediaImages.INSTANCE.clear();
    }

    /** 描きかけの頂点を先に吐き出し、この板との前後関係を保つ。 */
    private static void flushPending(MultiBufferSource buffer) {
        if (buffer instanceof MultiBufferSource.BufferSource bs) bs.endBatch();
        WebSdfShader.endPass();
    }

    /** 共有テクスチャ（事前乗算・下端が先頭）を、面の左上を原点とする {@code w × h} の板に貼る。 */
    private static void drawTexture(LwjglGlRenderBackend backend, Matrix4f pose, int w, int h) {
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        backend.waitForRender(); // CPU を止めず、GPU 側で描画完了を待つ
        RenderSystem.setShaderTexture(0, backend.colorTexture());
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.disableCull();
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        // テクスチャは下端が先頭なので、上の辺ほど v が大きい
        builder.addVertex(pose, 0f, h, 0f).setUv(0f, 0f).setColor(-1);
        builder.addVertex(pose, w, h, 0f).setUv(1f, 0f).setColor(-1);
        builder.addVertex(pose, w, 0f, 0f).setUv(1f, 1f).setColor(-1);
        builder.addVertex(pose, 0f, 0f, 0f).setUv(0f, 1f).setColor(-1);
        MeshData mesh = builder.build();
        if (mesh != null) BufferUploader.drawWithShader(mesh);
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableCull();
    }
}
