package com.nishiyu.enderframelib.client.renderer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.nishiyu.enderframelib.EnderFrameLib;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.renderer.surface.SurfaceGeometry;
import com.nishiyu.enderframelib.core.paint.PreparedFrame;
import com.nishiyu.enderframelib.core.paint.RenderFrame;
import com.nishiyu.enderframelib.core.diag.PipelineStage;
import com.nishiyu.enderframelib.core.diag.StageProfiler;
import com.nishiyu.enderframelib.core.paint.gl.LwjglGlRenderBackend;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL32;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minecraft の中の面を描く唯一の描画: Minecraft に依存しない描画コア（{@link LwjglGlRenderBackend}）で面を描き、
 * 結果のテクスチャを 1 枚の板として貼る（M3a で MOD の旧レンダラと既定の切り替え・フォールバックを撤去した）。
 *
 * <p>コアのバックエンドは Minecraft の GL コンテキストとテクスチャを共有する見えないコンテキストで動く
 * （{@link LwjglGlRenderBackend#sharedWith}）。描いた結果は共有テクスチャなので、読み戻さずにそのまま貼れる。
 * 呼び出しの前後で Minecraft の GL コンテキストと状態は元に戻る。Minecraft だけが描ける中身（アイテム・エンティティ・
 * プレイヤーの頭・{@code <mc-scene>}・ビットマップ書体）は {@link MinecraftHostTextures} が先にテクスチャへ描き、
 * コアが項目の中身として重なりの順どおりに貼る。画像・動画・キャンバスの画素は {@link MinecraftMediaBridge}、
 * 書体は {@code WebFontManager.McFontResolver} が渡す。
 *
 * <p>描けなかった（GL の失敗など）フレームは何も描かず、原因をセッションごとに 1 度だけ記録する。
 */
public final class MinecraftHostSurface {

    /**
     * 使っていない面（{@link #IDLE_MS} 以上描いていない）のバックエンドは、この数まで残す（また見たときに作り直さない）。
     * 描いている最中の面はこの数に関係なく残す。同時に見える面がこれより多いとき、毎フレーム閉じて作り直すと
     * 共有コンテキストとテクスチャの生成・全面の描き直しが続く。
     */
    static final int KEEP_IDLE = 8;
    /** 描いている面でも、これを超えたら古い順に閉じる（GPU メモリの上限）。 */
    static final int HARD_MAX = 32;
    /** この時間描いていない面を「使っていない」とみなす。 */
    static final long IDLE_MS = 2000;
    private static final int MAX_TEXTURE_SIDE = 4096;

    private record Key(String sessionId, int w, int h, int dprMilli) {}

    private static final Map<Key, LwjglGlRenderBackend> BACKENDS = new LinkedHashMap<>(16, 0.75f, true);
    /** 面ごとの最後に描いた時刻（{@code Util.getMillis}）。{@link #BACKENDS} と同じ鍵を持つ。 */
    private static final Map<Key, Long> LAST_USED = new java.util.HashMap<>();
    /** 描けなかったことを記録済みのセッション（ログを埋めない）。 */
    private static final java.util.Set<String> REPORTED = new java.util.HashSet<>();
    /** GPU のタイマークエリを張り済みのバックエンド（閉じたものは {@link #clear}・{@link #dropSession} で外す）。 */
    private static final java.util.Set<LwjglGlRenderBackend> GPU_TIMED =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /**
     * 面の再描画を飛ばせるか。既定は有効（描き直す範囲が空ならテクスチャを使い回す。判定はコアの描画器の
     * 準備 {@link LwjglGlRenderBackend#prepare}: 前のフレームとのシーンの違い・補間・外の事情・重ね描き。M3b-4b-4b・4c-2a）。
     * 切り分け用に {@code -Denderframe.core.noskip=true} で止められる。
     */
    private static final boolean SKIP_STATIC = !Boolean.getBoolean("enderframe.core.noskip");
    /** 面を最後に描いたときの書体の登録の世代。変わったら全部の面を描き直す（字形はシーンに現れない）。 */
    private static int paintedFontGeneration = Integer.MIN_VALUE;

    private MinecraftHostSurface() {}

    /** 共有テクスチャを持つバックエンドと、Minecraft が描いた中身をすべて捨てる（資源の再読み込み・ワールドを離れるとき）。 */
    public static void release() {
        clear();
    }

    /** 面を描く。描いたら {@code true}。項目が無い・描画スレッドでない・描けなかったなら何も描かず {@code false}。 */
    static boolean draw(PaintRequest pass) {
        if (!RenderSystem.isOnRenderThread()) return false;
        String session = pass.sessionId() == null ? "" : pass.sessionId();
        List<DisplayItem> base = pass.elements();
        if (base == null || base.isEmpty()) return false;
        // 面の大きさはブロック数。項目の座標は RESOLUTION 倍の px
        int w = Math.round(pass.screenWidth() * SurfaceGeometry.RESOLUTION);
        int h = Math.round(pass.screenHeight() * SurfaceGeometry.RESOLUTION);
        if (w <= 0 || h <= 0) return false;

        try {
            float dpr = Math.max(1f, Math.min(devicePixelRatio(pass), (float) MAX_TEXTURE_SIDE / Math.max(w, h)));
            LwjglGlRenderBackend backend = backendFor(new Key(session, w, h, Math.round(dpr * 1000)), w, h, dpr);
            backend.fonts(WebFontManager.McFontResolver.INSTANCE);
            MinecraftMediaBridge.INSTANCE.begin(pass);
            backend.images(MinecraftMediaBridge.INSTANCE);
            backend.hostTextures(MinecraftHostTextures.INSTANCE);
            // 計測が有効なら GPU のタイマークエリも張る（面ごとに 1 度。無効の間は GL を呼ばない）
            if (StageProfiler.enabled() && GPU_TIMED.add(backend)) backend.enableGpuTiming();
            List<DisplayItem> caret = SurfaceEditOverlay.caretItems(ClientWebManager.getSession(session), session, base);
            syncFontGeneration();
            flushPending(pass.buffer());
            long now = net.minecraft.Util.getMillis();
            RenderFrame frame = new RenderFrame(base, caret, pass.paintOffsets(), pass.sceneSource(), now);
            List<DisplayItem> items = frame.drawList();
            PreparedFrame prepared = backend.prepare(frame);
            if (SKIP_STATIC && !prepared.needsRepaint()) {
                // 描き直す範囲が空: 前回描いたテクスチャをそのまま貼る（Minecraft が描く中身の用意も GPU の描画も無し）
                StageProfiler.end(PipelineStage.SURFACE_CORE_SKIPPED, StageProfiler.begin(PipelineStage.SURFACE_CORE_SKIPPED));
            } else {
                render(backend, prepared, pass, session, dpr);
            }
            long tBlit = StageProfiler.begin(PipelineStage.SURFACE_CORE_BLIT);
            try {
                drawTexture(backend, pass.poseStack().last().pose(), w, h);
            } finally {
                StageProfiler.end(PipelineStage.SURFACE_CORE_BLIT, tBlit);
            }
            drawTooltip(pass, items, w, h);
            REPORTED.remove(session);
            return true;
        } catch (Throwable t) {
            if (REPORTED.add(session)) {
                EnderFrameLib.LOGGER.error("[EnderFrameLib] failed to draw surface '{}' with the core renderer", session, t);
            }
            dropSession(session);
            return false;
        }
    }

    /**
     * 面の 1 px が画面の何画素か。画面に平行な面（GUI・HUD）は姿勢の倍率 × GUI の倍率で、テクスチャを画面と 1 対 1 で貼る
     * （GUI の倍率だけを使うと、1 CSS px が画面の 1 画素の画面では 3 倍などで描いてから縮めて貼ることになり、
     * 最近傍で縮めたときにビットマップ書体の細い画素の列が落ちていた）。ワールドの面は見る距離が決まらないので 2 倍に固定する。
     */
    static float devicePixelRatio(PaintRequest pass) {
        if (!pass.screenSpaceText()) return 2f;
        Matrix4f pose = pass.poseStack().last().pose();
        float scale = (float) Math.hypot(pose.m00(), pose.m01());
        return (float) (scale * Minecraft.getInstance().getWindow().getGuiScale());
    }

    /** 書体の登録が変わったら、全部の面の前の画素を捨てる（字形の違いはシーンの違いに現れない）。 */
    private static void syncFontGeneration() {
        int gen = WebFontManager.McFontResolver.generation();
        if (gen == paintedFontGeneration) return;
        paintedFontGeneration = gen;
        for (LwjglGlRenderBackend b : BACKENDS.values()) b.invalidate();
    }

    /**
     * Minecraft が描く中身を用意してから、準備したフレームをコアで描く。動く中身（エンティティ・
     * {@code <mc-scene>}）は画像・場面の項目で、描画器が外の事情で変わる項目として毎フレーム描き直す。
     */
    private static void render(LwjglGlRenderBackend backend, PreparedFrame prepared, PaintRequest pass, String session, float dpr) {
        long tRender = StageProfiler.begin(PipelineStage.SURFACE_CORE_RENDER);
        long fence = 0L;
        try {
            fence = MinecraftHostTextures.INSTANCE.prepare(prepared.frame().drawList(), session, dpr, !pass.screenSpaceText());
            if (fence != 0L) backend.waitForHost(fence);
            backend.render(prepared);
        } finally {
            if (fence != 0L) GL32.glDeleteSync(fence);
            StageProfiler.end(PipelineStage.SURFACE_CORE_RENDER, tRender);
        }
    }

    /** ホバー中の要素の {@code title} をツールチップとして板の上に重ねる（ホストの飾り。コアは描かない）。 */
    private static void drawTooltip(PaintRequest pass, List<DisplayItem> items, int w, int h) {
        String hovered = pass.hoveredId();
        if (hovered == null || hovered.isEmpty()) return;
        DisplayItem target = null;
        String text = null;
        for (DisplayItem it : items) {
            if (!hovered.equals(it.baseId())) continue;
            text = SurfaceTooltip.textOf(it);
            if (text != null) {
                target = it;
                break;
            }
        }
        if (target == null || text.isEmpty()) return;
        net.minecraft.client.gui.Font font = WebFontManager.getDefaultFont();
        if (font == null) font = Minecraft.getInstance().font;
        target = DisplayItem.resolveAbsolute(items, target, pass.paintOffsets()); // 項目は親相対・スクロール 0 で組んだ位置
        SurfaceTooltip.draw(pass.poseStack(), pass.buffer(), font, target, text, w, h, 0.2f, pass.packedLight());
        flushPending(pass.buffer());
    }

    private static LwjglGlRenderBackend backendFor(Key key, int w, int h, float dpr) {
        long now = net.minecraft.Util.getMillis();
        LwjglGlRenderBackend b = BACKENDS.get(key);
        if (b != null) {
            LAST_USED.put(key, now);
            return b;
        }
        // 同じセッションの別の大きさは古いので捨てる
        dropSession(key.sessionId());
        long window = Minecraft.getInstance().getWindow().getWindow();
        b = LwjglGlRenderBackend.sharedWith(window, w, h, dpr);
        BACKENDS.put(key, b);
        LAST_USED.put(key, now);
        for (Key old : evictions(BACKENDS.keySet(), k -> LAST_USED.getOrDefault(k, 0L), now, key)) {
            LwjglGlRenderBackend gone = BACKENDS.remove(old);
            LAST_USED.remove(old);
            if (gone == null) continue;
            GPU_TIMED.remove(gone);
            closeQuietly(gone);
        }
        return b;
    }

    /**
     * 新しい面を作った後に閉じる面（{@code lruOrder} は古く使った順）。使っていない面を古い順に {@link #KEEP_IDLE} 個まで減らし、
     * それでも {@link #HARD_MAX} を超えるときだけ、描いている面も古い順に閉じる。{@code keep}（今作った面）は閉じない。
     */
    static <K> java.util.List<K> evictions(java.util.Collection<K> lruOrder, java.util.function.ToLongFunction<K> lastUsed,
                                           long now, K keep) {
        java.util.List<K> out = new java.util.ArrayList<>();
        int size = lruOrder.size();
        for (K k : lruOrder) {
            if (size <= KEEP_IDLE) break;
            if (k.equals(keep) || now - lastUsed.applyAsLong(k) < IDLE_MS) continue;
            out.add(k);
            size--;
        }
        for (K k : lruOrder) {
            if (size <= HARD_MAX) break;
            if (k.equals(keep) || out.contains(k)) continue;
            out.add(k);
            size--;
        }
        return out;
    }

    private static void dropSession(String session) {
        Iterator<Map.Entry<Key, LwjglGlRenderBackend>> it = BACKENDS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Key, LwjglGlRenderBackend> e = it.next();
            if (e.getKey().sessionId().equals(session)) {
                it.remove();
                LAST_USED.remove(e.getKey());
                GPU_TIMED.remove(e.getValue());
                closeQuietly(e.getValue());
            }
        }
    }

    private static void closeQuietly(LwjglGlRenderBackend b) {
        try {
            b.close();
        } catch (Throwable ignored) {
            // 後始末の失敗は握りつぶす
        }
    }

    static void clear() {
        for (LwjglGlRenderBackend b : BACKENDS.values()) closeQuietly(b);
        BACKENDS.clear();
        LAST_USED.clear();
        GPU_TIMED.clear();
        REPORTED.clear();
        MinecraftMediaBridge.INSTANCE.clear();
        if (RenderSystem.isOnRenderThread()) MinecraftHostTextures.INSTANCE.clear();
    }

    /** 描きかけの頂点を先に吐き出し、この板との前後関係を保つ。 */
    private static void flushPending(MultiBufferSource buffer) {
        if (buffer instanceof MultiBufferSource.BufferSource bs) bs.endBatch();
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
