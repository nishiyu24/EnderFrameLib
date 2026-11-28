package com.nishiyu.enderframelib.client.renderer;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.math.Axis;
import com.nishiyu.enderframelib.core.paint.PreparedFrame;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.RenderMeta;
import com.nishiyu.enderframelib.core.paint.model.TextureRef;
import com.nishiyu.enderframelib.core.paint.resource.HostTextures;
import com.nishiyu.enderframelib.core.scene3d.Scene3DTags;
import com.nishiyu.enderframelib.core.text.model.BitmapFontFace;
import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Minecraft だけが描ける中身（アイテム・エンティティ・プレイヤーの頭・{@code <mc-scene>}・ビットマップ書体の文字）を、
 * 面を描く前に Minecraft の描画で項目ごとのテクスチャへ描き、コアへ {@link HostTextures} として渡す（M3a）。
 *
 * <p>コアは項目の背景・枠線・角丸・クリップ・不透明度と重なりの順を自分で描き、中身の矩形にこのテクスチャを貼る。
 * テクスチャは Minecraft の GL コンテキストで描き、フェンス（{@link #prepare} の戻り値）でコアの共有コンテキストに待たせる。
 *
 * <p>テクスチャは中身の鍵（種類・指定・大きさ・色）ごとに 1 枚で、同じ鍵の項目どうしは共有する。動くもの（エンティティ・
 * {@code <mc-scene>}）は描くたびに描き直し、動かないものは初めの 1 回だけ描く。しばらく使わない鍵は捨てる。描画スレッドでだけ使う。
 */
final class MinecraftHostTextures implements HostTextures {

    static final MinecraftHostTextures INSTANCE = new MinecraftHostTextures();

    /** 1 辺の画素の上限（GPU のメモリの上限）。 */
    private static final int MAX_SIDE = 1024;
    /** ビットマップ書体の文字を描く倍率（面の画素に対して）。 */
    private static final float TEXT_SUPERSAMPLE = 2f;
    /** この回数の {@link #prepare} で使わなかった鍵は捨てる。 */
    private static final int IDLE_PREPARES = 600;

    private record Key(String kind, String spec, int w, int h, int color) {}

    private static final class Slot {
        TextureTarget target;
        long lastUsed;
        boolean drawn;
    }

    private final Map<Key, Slot> slots = new HashMap<>();
    private final Map<DisplayItem, TextureRef> frame = new IdentityHashMap<>();
    private final Map<String, Entity> previewEntities = new HashMap<>();
    private MultiBufferSource.BufferSource buffers;
    private long prepares;

    private MinecraftHostTextures() {}

    @Override
    public TextureRef texture(DisplayItem item, int deviceW, int deviceH) {
        return frame.get(item);
    }

    /** 中身の種類。Minecraft が描くものでなければ null。 */
    static String kindOf(DisplayItem el) {
        ElementType t = el.type();
        if (t == ElementType.SCENE) {
            String kind = el.events() != null ? el.events().get(Scene3DTags.KIND_ATTR) : null;
            return Scene3DTags.KIND_3D.equals(kind) ? null : "scene"; // <scene-3d> はコアが描く
        }
        String src = el.text();
        if (t == ElementType.IMAGE) {
            if (src == null) return null;
            if (src.startsWith("item:")) return "item";
            if (src.startsWith("entity:")) return "entity";
            if (src.startsWith("player:")) return "player";
            return null;
        }
        // 入力欄・選択欄の文字は欄の内側の余白とキャレットの計算（TextFieldOverlay）がコアの輪郭の書体を前提にするので、コアに任せる
        if (src == null || src.isEmpty() || t == ElementType.INPUT || t == ElementType.SELECT) return null;
        ElementType.RasterRoute route = t.rasterRoute();
        if (route != ElementType.RasterRoute.TEXT_ALWAYS && route != ElementType.RasterRoute.TEXT_IF_ANY) return null;
        return isBitmapText(el) ? "text" : null;
    }

    private static boolean isBitmapText(DisplayItem el) {
        String family = el.hints().fontFamily();
        if (family == null && el.events() != null) family = el.events().get("font-family");
        FontFace face = WebFontManager.McFontResolver.INSTANCE.resolve(family);
        return face instanceof BitmapFontFace;
    }

    /**
     * 面の項目のうち Minecraft が描く中身をテクスチャへ描く。描画スレッドで、コアが描くと決めたフレームの描画の前に呼ぶ
     * （描かないフレームは呼ばない。テクスチャは中身の鍵で引くので、前のフレームの項目を覚えない）。
     * 呼ぶ側の描きかけの頂点は先に吐き出しておくこと（ここで描画先を切り替える）。戻り値はコアの共有コンテキストに待たせるフェンス（0 なら無し）。
     */
    long prepare(PreparedFrame renderFrame, String sessionId, float dpr, boolean worldLighting) {
        frame.clear();
        prepares++;
        List<DisplayItem> todo = null;
        java.util.function.Predicate<DisplayItem> skip = renderFrame.frame().excluded(); // 描かない節の中身は用意しない
        for (DisplayItem el : renderFrame.drawList()) {
            if (el.width() <= 0 || el.height() <= 0 || kindOf(el) == null || (skip != null && skip.test(el))) continue;
            if (todo == null) todo = new java.util.ArrayList<>();
            todo.add(el);
        }
        if (todo == null) {
            evictIdle();
            return 0L;
        }
        GlState saved = GlState.save();
        try {
            for (DisplayItem el : todo) {
                String kind = kindOf(el);
                boolean dynamic = kind.equals("entity") || kind.equals("scene");
                // 書体の字形は 1 画素より細い画素で描かれうる（最近傍で縮めると列・行が落ちる）ので、文字は 2 倍で描いて線形で縮める
                boolean text = kind.equals("text");
                float scale = text ? dpr * TEXT_SUPERSAMPLE : dpr;
                int pw = Math.min(MAX_SIDE, Math.max(1, (int) Math.ceil(el.width() * scale)));
                int ph = Math.min(MAX_SIDE, Math.max(1, (int) Math.ceil(el.height() * scale)));
                String spec = kind.equals("scene") ? sessionId + "|" + el.baseId() : kind.equals("text") ? textSpec(el) : el.text();
                Key key = new Key(kind, spec, pw, ph, kind.equals("text") ? el.color() : 0);
                Slot slot = slots.computeIfAbsent(key, k -> new Slot());
                slot.lastUsed = prepares;
                if (slot.target == null) {
                    slot.target = new TextureTarget(pw, ph, true, Minecraft.ON_OSX);
                    if (text) slot.target.setFilterMode(GL11.GL_LINEAR);
                }
                if (!slot.drawn || dynamic) {
                    draw(slot.target, el, kind, sessionId);
                    slot.drawn = true;
                }
                frame.put(el, new TextureRef(slot.target.getColorTextureId(), pw, ph, 0f, 0f, 1f, 1f, true));
            }
        } finally {
            saved.restore(worldLighting);
        }
        evictIdle();
        long fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        GL11.glFlush();
        return fence;
    }

    private static String textSpec(DisplayItem el) {
        RenderMeta meta = el.meta();
        String family = el.hints().fontFamily();
        if (family == null && el.events() != null) family = el.events().get("font-family");
        return el.text() + "|" + family + "|" + (meta != null ? meta.fontHeight() + "/" + meta.baseline() : "-")
                + "|" + (el.events() != null ? el.events().get("font-weight") + el.events().get("text-shadow")
                + el.events().get("text-decoration") : "");
    }

    /** 1 つの中身を {@code target} へ描く。座標は項目の左上を原点にした CSS px。 */
    private void draw(TextureTarget target, DisplayItem el, String kind, String sessionId) {
        target.setClearColor(0f, 0f, 0f, 0f);
        target.clear(Minecraft.ON_OSX);
        target.bindWrite(true);
        float w = el.width(), h = el.height();
        float far = net.neoforged.neoforge.client.ClientHooks.getGuiFarPlane();
        RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0f, w, h, 0f, 1000f, far), VertexSorting.ORTHOGRAPHIC_Z);
        Matrix4fStack mv = RenderSystem.getModelViewStack();
        mv.pushMatrix();
        try {
            mv.translation(0f, 0f, 10000f - far);
            RenderSystem.applyModelViewMatrix();
            Lighting.setupFor3DItems();
            GuiGraphics g = new GuiGraphics(Minecraft.getInstance(), buffers());
            switch (kind) {
                case "item" -> drawItem(g, el.text().substring(5).trim(), w, h);
                case "entity" -> drawEntity(g, el.text().substring(7).trim(), w, h);
                case "player" -> drawPlayer(g, el.text().substring(7).trim(), w, h);
                case "scene" -> WebSceneContext.getOrCreate(sessionId, el.baseId()).render(el, g.pose(), g.bufferSource(), w, h);
                default -> drawText(g, el, w, h);
            }
            g.flush();
        } finally {
            mv.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    /**
     * 中身を描くための頂点の置き場。光沢（エンチャント）のように 2 つの描き方へ同時に頂点を積むものがあるので、
     * Minecraft の {@code RenderBuffers} と同じく、それらの描き方には専用の置き場を持たせる（共有の 1 つだけだと
     * 片方を積んでいる最中に閉じられて "Not building!" で落ちる）。世界の描画の置き場とは分けておき、描きかけの世界の頂点を
     * このテクスチャへ混ぜない。
     */
    private MultiBufferSource.BufferSource buffers() {
        if (buffers != null) return buffers;
        java.util.SequencedMap<RenderType, ByteBufferBuilder> fixed = new java.util.LinkedHashMap<>();
        for (RenderType type : new RenderType[]{Sheets.solidBlockSheet(), Sheets.cutoutBlockSheet(), Sheets.bannerSheet(),
                Sheets.translucentCullBlockSheet(), Sheets.shieldSheet(), Sheets.bedSheet(), Sheets.shulkerBoxSheet(),
                Sheets.signSheet(), Sheets.hangingSignSheet(), Sheets.chestSheet(), RenderType.armorEntityGlint(),
                RenderType.glint(), RenderType.glintTranslucent(), RenderType.entityGlint(), RenderType.entityGlintDirect()}) {
            fixed.putIfAbsent(type, new ByteBufferBuilder(type.bufferSize()));
        }
        buffers = MultiBufferSource.immediateWithBuffers(fixed, new ByteBufferBuilder(1 << 18));
        return buffers;
    }

    /** {@code item:<id>[*<個数>]}。GUI のアイテムの描き方（インベントリと同じ見た目）を箱の大きさへ拡げる。 */
    private static void drawItem(GuiGraphics g, String spec, float w, float h) {
        int count = 1;
        int star = spec.indexOf('*');
        if (star >= 0) {
            try {
                count = Math.max(1, Integer.parseInt(spec.substring(star + 1).trim()));
            } catch (NumberFormatException ignored) {
            }
            spec = spec.substring(0, star).trim();
        }
        ResourceLocation id = ResourceLocation.tryParse(spec);
        if (id == null) return;
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item == Items.AIR) return;
        ItemStack stack = new ItemStack(item, count);
        g.pose().pushPose();
        g.pose().scale(w / 16f, h / 16f, 1f);
        g.renderFakeItem(stack, 0, 0);
        g.renderItemDecorations(Minecraft.getInstance().font, stack, 0, 0);
        g.pose().popPose();
    }

    /** {@code entity:<型>}。足元を箱の下寄りに置き、ゆっくり回す（従来の描き方と同じ構図）。 */
    private void drawEntity(GuiGraphics g, String spec, float w, float h) {
        Entity entity = previewEntity(spec);
        if (entity == null) return;
        float bbH = Math.max(0.8f, entity.getBbHeight());
        float scale = Math.min(w, h) * 0.72f / bbH;
        PoseStack ps = g.pose();
        ps.pushPose();
        ps.translate(w * 0.5f, h * 0.88f, 100f);
        ps.scale(scale, -scale, scale);
        ps.mulPose(Axis.XP.rotationDegrees(-15f));
        ps.mulPose(Axis.YP.rotationDegrees((Util.getMillis() % 12000L) / 12000f * 360f));
        Lighting.setupForEntityInInventory();
        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        dispatcher.setRenderShadow(false);
        try {
            if (entity instanceof LivingEntity le) {
                float body = le.yBodyRot, head = le.yHeadRot, yRot = le.getYRot();
                le.yBodyRot = 180f;
                le.yHeadRot = 180f;
                le.setYRot(180f);
                try {
                    RenderSystem.runAsFancy(() -> dispatcher.render(le, 0, 0, 0, 0f, 1f, ps, g.bufferSource(), 0xF000F0));
                } finally {
                    le.yBodyRot = body;
                    le.yHeadRot = head;
                    le.setYRot(yRot);
                }
            } else {
                RenderSystem.runAsFancy(() -> dispatcher.render(entity, 0, 0, 0, 0f, 1f, ps, g.bufferSource(), 0xF000F0));
            }
            g.flush();
        } catch (RuntimeException ignored) {
            // 描けないエンティティ（描画器の無い型など）は何も描かない
        } finally {
            dispatcher.setRenderShadow(true);
            ps.popPose();
            Lighting.setupFor3DItems();
        }
    }

    private Entity previewEntity(String spec) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        if (spec.isEmpty() || "@self".equalsIgnoreCase(spec)) return mc.player;
        Entity cached = previewEntities.get(spec);
        if (cached != null && cached.level() == mc.level) return cached;
        Entity created = null;
        ResourceLocation id = ResourceLocation.tryParse(spec);
        if (id != null && BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
            created = BuiltInRegistries.ENTITY_TYPE.get(id).create(mc.level);
        }
        if (created != null) previewEntities.put(spec, created);
        return created;
    }

    /** {@code player:<名前|UUID|@self>}。顔と帽子の 2 層を箱いっぱいに描く。 */
    private static void drawPlayer(GuiGraphics g, String target, float w, float h) {
        ResourceLocation skin = playerSkin(target);
        g.pose().pushPose();
        g.pose().scale(w / 8f, h / 8f, 1f);
        PlayerFaceRenderer.draw(g, skin, 0, 0, 8);
        g.pose().popPose();
    }

    /** {@code @self}・名前・UUID のプレイヤーの皮膚。接続中のプレイヤーに無ければ皮膚の取得を頼む（届くまでは既定の皮膚）。 */
    static ResourceLocation playerSkin(String target) {
        Minecraft mc = Minecraft.getInstance();
        if ((target == null || target.isEmpty() || "@self".equalsIgnoreCase(target)) && mc.player != null) {
            return mc.player.getSkin().texture();
        }
        if (mc.getConnection() != null) {
            for (net.minecraft.client.multiplayer.PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
                if (info.getProfile().getName().equalsIgnoreCase(target) || info.getProfile().getId().toString().equalsIgnoreCase(target)) {
                    return info.getSkin().texture();
                }
            }
        }
        java.util.UUID uuid;
        try {
            uuid = java.util.UUID.fromString(target);
        } catch (IllegalArgumentException e) {
            uuid = java.util.UUID.nameUUIDFromBytes(("OfflinePlayer:" + target).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return mc.getSkinManager().getInsecureSkin(new com.mojang.authlib.GameProfile(uuid, target)).texture();
    }

    /** ビットマップ書体（Minecraft の書体）の文字。測定（{@link BitmapFontFace}）と同じ倍率・ベースラインで描く。 */
    private static void drawText(GuiGraphics g, DisplayItem el, float w, float h) {
        int color = el.color();
        if ((color >>> 24) < 4) return; // Minecraft の Font はアルファ 4 未満を不透明にして描く
        String family = el.hints().fontFamily();
        if (family == null && el.events() != null) family = el.events().get("font-family");
        Font font = WebFontManager.McFontResolver.fontFor(family);
        if (font == null) return;
        RenderMeta meta = el.meta();
        float fontH = meta != null && meta.fontHeight() > 0 ? meta.fontHeight() : h;
        float textScale = fontH / BitmapFontFace.MC_UNITS_PER_EM;
        float ascent = BitmapFontFace.MC_ASCENT * textScale, descent = BitmapFontFace.MC_DESCENT * textScale;
        boolean centers = el.isPart("_ph") || meta == null || meta.baseline() <= 0;
        float baseline = centers ? (h + ascent - descent) / 2f : meta.baseline();
        Map<String, String> ev = el.events() != null ? el.events() : Map.of();
        boolean bold = LayoutMetrics.isBoldWeight(ev.get("font-weight"));
        String text = el.text().replace(' ', ' '); // &nbsp; は測定と同じく空白で描く
        if (bold && !text.startsWith("§l")) text = "§l" + text;
        String deco = ev.getOrDefault("text-decoration", "none");
        g.pose().pushPose();
        g.pose().translate(0f, baseline - ascent, 0f);
        g.pose().scale(textScale, textScale, 1f);
        g.drawString(font, text, 0f, 0f, color, "true".equals(ev.get("text-shadow")));
        int lineW = (int) Math.ceil(w / textScale);
        if (deco.contains("underline")) g.fill(0, 8, lineW, 9, color);
        if (deco.contains("line-through")) g.fill(0, 4, lineW, 5, color);
        g.pose().popPose();
    }

    private void evictIdle() {
        Iterator<Slot> it = slots.values().iterator();
        while (it.hasNext()) {
            Slot s = it.next();
            if (prepares - s.lastUsed > IDLE_PREPARES) {
                if (s.target != null) s.target.destroyBuffers();
                it.remove();
            }
        }
    }

    /** 全部のテクスチャを捨てる（資源の再読み込み・ワールドを離れるとき）。 */
    void clear() {
        for (Slot s : slots.values()) {
            if (s.target != null) s.target.destroyBuffers();
        }
        slots.clear();
        frame.clear();
        previewEntities.clear();
    }

    /** 描く前の GL の状態（描画先・視野・射影・霧・シザー）。描いた後に戻す。 */
    private record GlState(int drawFbo, int readFbo, int[] viewport, boolean scissor, float fogStart, float fogEnd,
                           float[] fogColor, com.mojang.blaze3d.shaders.FogShape fogShape) {
        static GlState save() {
            int[] vp = new int[4];
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, vp);
            boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            GlState s = new GlState(GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING), GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING),
                    vp, scissor, RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd(),
                    RenderSystem.getShaderFogColor().clone(), RenderSystem.getShaderFogShape());
            RenderSystem.backupProjectionMatrix();
            if (scissor) GlStateManager._disableScissorTest();
            FogRenderer.setupNoFog();
            return s;
        }

        void restore(boolean worldLighting) {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo);
            GlStateManager._viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            if (scissor) GlStateManager._enableScissorTest();
            RenderSystem.restoreProjectionMatrix();
            RenderSystem.setShaderFogStart(fogStart);
            RenderSystem.setShaderFogEnd(fogEnd);
            RenderSystem.setShaderFogColor(fogColor[0], fogColor[1], fogColor[2], fogColor[3]);
            RenderSystem.setShaderFogShape(fogShape);
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            Minecraft mc = Minecraft.getInstance();
            if (!worldLighting) Lighting.setupFor3DItems();
            else if (mc.level != null && mc.level.effects().constantAmbientLight()) Lighting.setupNetherLevel();
            else Lighting.setupLevel();
        }
    }
}
