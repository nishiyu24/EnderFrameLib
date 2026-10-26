package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.client.renderer.DisplayItemDrawer.ZLayers;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.SessionContext;
import com.nishiyu.enderframelib.client.gui.TextareaEditor.TextEditState;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.raster.RasterImageStore;
import com.nishiyu.enderframelib.core.paint.model.ImageFitMode;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import org.joml.Matrix4f;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;
import static com.nishiyu.enderframelib.client.renderer.DisplayItemDrawer.drawClippedTexturedQuad;
import static com.nishiyu.enderframelib.client.renderer.DisplayItemDrawer.drawRoundedTexturedRect;

/** 画像・動画・アイテム・液体・エンティティを描く（{@link DisplayItemDrawer} の部品）。 */
public final class MediaElementRenderer {
    private MediaElementRenderer() {}

    // キャレット位置と入力欄のスクロールは TextEditState が唯一の出所。
    // 以前ここに同名の static マップが 3 本あり、編集側の書き込みが描画に届いていなかった。
    static final Map<String, Entity> ENTITY_PREVIEW_CACHE = new ConcurrentHashMap<>();

    // フォーカス / 押下状態は SessionContext が所有する（ClientWebManager 経由で参照）。
    // かつてここに public static String で置かれていたため、GUI 画面とブロック画面が
    // フォーカスを共有してしまっていた。

    static ResourceLocation resolvePlayerSkin(String target) {
        Minecraft mc = Minecraft.getInstance();
        if (target == null || target.isEmpty() || "@self".equalsIgnoreCase(target)) {
            if (mc.player != null) return mc.player.getSkin().texture();
        }
        if (mc.getConnection() != null) {
            for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
                if (info.getProfile().getName().equalsIgnoreCase(target) || info.getProfile().getId().toString().equalsIgnoreCase(target)) {
                    return info.getSkin().texture();
                }
            }
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(target);
        } catch (IllegalArgumentException e) {
            uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + target).getBytes(StandardCharsets.UTF_8));
        }
        return mc.getSkinManager().getInsecureSkin(new GameProfile(uuid, target)).texture();
    }

    static Entity resolvePreviewEntity(String target, Level level) {
        Minecraft mc = Minecraft.getInstance();
        if (level == null) level = mc.level;
        if (level == null) return null;
        if (target == null || target.isEmpty() || "@self".equalsIgnoreCase(target)) {
            return mc.player;
        }
        final Level finalLevel = level;
        return ENTITY_PREVIEW_CACHE.computeIfAbsent(target, key -> {
            try {
                EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(key));
                return type.create(finalLevel);
            } catch (Exception e) {
                return null;
            }
        });
    }

    public static void clearEntityPreviewCache() {
        ENTITY_PREVIEW_CACHE.clear();
    }

    static void renderImageOrMediaElement(
            DisplayItem el, RenderMeta meta, PoseStack poseStack, MultiBufferSource buffer, Font font,
            Level level, String animSessionId, BlockPos audioPos, boolean playAudio, boolean is2DAudio,
            boolean renderItems, boolean renderText, float finalOpacity, float zPos, int packedLight, int packedOverlay,
            float rTL, float rTR, float rBR, float rBL,
            float clipMinX, float clipMinY, float clipMaxX, float clipMaxY,
            float cRTL, float cRTR, float cRBR, float cRBL
    ) {
        if (el.type() == ElementType.VIDEO) {
            if (el.text() != null && !el.text().isEmpty()) {
                WebMediaManager.VideoTexture vTex = WebMediaManager.getVideoTexture(el.text());
                if (vTex != null) {
                    if (is2DAudio) vTex.keepAlive2D(animSessionId);
                    else if (playAudio) vTex.keepAlive(audioPos, animSessionId);
                    vTex.updateFrame();
                    if (vTex.isInitialized) {
                        VertexConsumer builder = buffer.getBuffer(RenderType.entityTranslucent(vTex.textureLocation));
                        drawRoundedTexturedRect(builder, poseStack.last().pose(), el.x(), el.y(), el.x() + el.width(), el.y() + el.height(),
                                0f, 0f, 1f, 1f, rTL, rTR, rBR, rBL, 1f, 1f, 1f, finalOpacity, zPos, packedLight, packedOverlay,
                                clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
                    }
                }
            }
            return;
        }

        if (!renderItems || el.text() == null || el.text().isEmpty()) return;
        String src = el.text();

        if (RasterImageStore.isRasterSrc(src)) {
            // SVG をソフトウェアで描いた画素（RasterPainting が置いたもの）
            String key = RasterPainting.keyOf(src);
            RasterImageStore.Entry entry = RasterImageStore.get(key);
            int[] css = RasterPainting.cssSizeOf(src);
            if (entry != null && css != null) {
                WebMediaManager.ImageInfo info = RasterTextures.textureFor(key, entry.pixmap(), entry.version(), css[0], css[1]);
                renderExternalWebImage(el, info, poseStack, buffer, finalOpacity, zPos, packedLight, packedOverlay,
                        rTL, rTR, rBR, rBL, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
            }
        } else if (src.startsWith("https://") || src.startsWith("http://")) {
            WebMediaManager.ImageInfo info = WebMediaManager.getImage(src);
            if (info != null) {
                renderExternalWebImage(el, info, poseStack, buffer, finalOpacity, zPos, packedLight, packedOverlay,
                        rTL, rTR, rBR, rBL, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
            }
        } else if (src.startsWith("fluid:")) {
            renderFluidElement(el, src.substring(6).trim(), poseStack, buffer, rTL, rTR, rBR, rBL, finalOpacity, zPos,
                    packedLight, packedOverlay, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
        } else if (src.startsWith("entity:")) {
            renderEntityElement(el, src.substring(7).trim(), poseStack, buffer, level, zPos, packedLight);
        } else if (src.startsWith("player:")) {
            ResourceLocation skinLoc = resolvePlayerSkin(src.substring(7).trim());
            VertexConsumer builder = buffer.getBuffer(RenderType.entityTranslucent(skinLoc));
            drawRoundedTexturedRect(builder, poseStack.last().pose(), el.x(), el.y(), el.x() + el.width(), el.y() + el.height(),
                    8f / 64f, 8f / 64f, 16f / 64f, 16f / 64f, rTL, rTR, rBR, rBL, 1f, 1f, 1f, finalOpacity, zPos,
                    packedLight, packedOverlay, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
            drawRoundedTexturedRect(builder, poseStack.last().pose(), el.x(), el.y(), el.x() + el.width(), el.y() + el.height(),
                    40f / 64f, 8f / 64f, 48f / 64f, 16f / 64f, rTL, rTR, rBR, rBL, 1f, 1f, 1f, finalOpacity, zPos + 0.0002f,
                    packedLight, packedOverlay, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
        } else if (src.startsWith("item:")) {
            renderItemElement(el, meta, src.substring(5).trim(), poseStack, buffer, font, level, renderText, zPos, packedLight, packedOverlay);
        } else if (src.startsWith("effect:")) {
            try {
                Optional<Holder.Reference<MobEffect>> effectOpt = BuiltInRegistries.MOB_EFFECT.getHolder(ResourceLocation.parse(src.substring(7)));
                if (effectOpt.isPresent()) {
                    TextureAtlasSprite sprite = Minecraft.getInstance().getMobEffectTextures().get(effectOpt.get());
                    VertexConsumer builder = buffer.getBuffer(RenderType.entityTranslucent(sprite.atlasLocation()));
                    drawRoundedTexturedRect(builder, poseStack.last().pose(), el.x(), el.y(), el.x() + el.width(), el.y() + el.height(),
                            sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1(), rTL, rTR, rBR, rBL,
                            1f, 1f, 1f, finalOpacity, zPos, packedLight, packedOverlay,
                            clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
                }
            } catch (Exception ignored) {}
        } else {
            try {
                VertexConsumer builder = buffer.getBuffer(RenderType.entityTranslucent(ResourceLocation.parse(src)));
                drawRoundedTexturedRect(builder, poseStack.last().pose(), el.x(), el.y(), el.x() + el.width(), el.y() + el.height(),
                        0.0f, 0.0f, 1.0f, 1.0f, rTL, rTR, rBR, rBL, 1f, 1f, 1f, finalOpacity, zPos, packedLight, packedOverlay,
                        clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
            } catch (Exception ignored) {}
        }
    }

    static void renderExternalWebImage(
            DisplayItem el, WebMediaManager.ImageInfo info, PoseStack poseStack, MultiBufferSource buffer,
            float finalOpacity, float zPos, int packedLight, int packedOverlay,
            float rTL, float rTR, float rBR, float rBL,
            float clipMinX, float clipMinY, float clipMaxX, float clipMaxY,
            float cRTL, float cRTR, float cRBR, float cRBL
    ) {
        VertexConsumer builder = buffer.getBuffer(RenderType.entityTranslucent(info.textureId()));
        int imgW = info.width(), imgH = info.height();
        ImageFitMode fit = el.fitMode() != null ? el.fitMode() : ImageFitMode.FILL;
        float boxW = el.width(), boxH = el.height();
        if (boxW <= 0 || boxH <= 0) return;

        // object-fit / object-position: 中身の矩形を決め、箱の外にはみ出す分はテクスチャ座標で切る
        ObjectFit.Placement pl = ObjectFit.place(fit, el.events() == null ? null : el.events().get("object-position"),
                imgW, imgH, boxW, boxH, 16f);
        float vx0 = Math.max(0f, pl.x()), vy0 = Math.max(0f, pl.y());
        float vx1 = Math.min(boxW, pl.x() + pl.w()), vy1 = Math.min(boxH, pl.y() + pl.h());
        if (!(vx1 > vx0) || !(vy1 > vy0) || !(pl.w() > 0) || !(pl.h() > 0)) return;
        float u0 = (vx0 - pl.x()) / pl.w(), u1 = (vx1 - pl.x()) / pl.w();
        float v0 = (vy0 - pl.y()) / pl.h(), v1 = (vy1 - pl.y()) / pl.h();
        float drawMinX = el.x() + vx0, drawMinY = el.y() + vy0, drawMaxX = el.x() + vx1, drawMaxY = el.y() + vy1;
        drawRoundedTexturedRect(builder, poseStack.last().pose(), drawMinX, drawMinY, drawMaxX, drawMaxY,
                u0, v0, u1, v1, rTL, rTR, rBR, rBL, 1f, 1f, 1f, finalOpacity, zPos, packedLight, packedOverlay,
                clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
    }

    static void renderItemElement(DisplayItem el, RenderMeta meta, String rawSpec,
                                          PoseStack poseStack, MultiBufferSource buffer, Font font,
                                          Level level, boolean renderText, float zPos, int packedLight, int packedOverlay) {
        int count = meta != null ? meta.itemCount() : 1;
        if (rawSpec.contains("*")) {
            String[] sp = rawSpec.split("\\*", 2);
            rawSpec = sp[0].trim();
            try { count = Integer.parseInt(sp[1].trim()); } catch (NumberFormatException ignored) {}
        }
        try {
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(rawSpec));
            if (item != Items.AIR) {
                poseStack.pushPose();
                try {
                    poseStack.translate(el.x() + (el.width() / 2.0f), el.y() + (el.height() / 2.0f), zPos + 0.02f);
                    poseStack.scale(el.width(), -el.height(), 1.0f);
                    poseStack.mulPose(Axis.YP.rotationDegrees(180.0f));
                    Minecraft.getInstance().getItemRenderer().renderStatic(new ItemStack(item), ItemDisplayContext.FIXED, packedLight, packedOverlay, poseStack, buffer, level, 0);
                } finally {
                    poseStack.popPose();
                }

                if (count > 1 && renderText) {
                    String countStr = String.valueOf(count);
                    float cScale = Math.max(0.6f, el.width() / 18.0f);
                    poseStack.pushPose();
                    poseStack.translate(el.x() + el.width() - font.width(countStr) * cScale, el.y() + el.height() - 7.0f * cScale, zPos + 0.05f);
                    poseStack.scale(cScale, cScale, 1.0f);
                    font.drawInBatch(countStr, 0, 0, 0xFFFFFFFF, true, poseStack.last().pose(), buffer, Font.DisplayMode.NORMAL, 0, packedLight);
                    poseStack.popPose();
                }
            }
        } catch (Exception ignored) {}
    }

    static void renderFluidElement(
            DisplayItem el, String fluidId, PoseStack poseStack, MultiBufferSource buffer,
            float rTL, float rTR, float rBR, float rBL, float finalOpacity, float zPos,
            int packedLight, int packedOverlay,
            float clipMinX, float clipMinY, float clipMaxX, float clipMaxY,
            float cRTL, float cRTR, float cRBR, float cRBL
    ) {
        try {
            Fluid fluid = BuiltInRegistries.FLUID.get(ResourceLocation.parse(fluidId));
            if (fluid == Fluids.EMPTY) return;

            IClientFluidTypeExtensions ext = IClientFluidTypeExtensions.of(fluid);
            ResourceLocation stillTex = ext.getStillTexture();
            if (stillTex == null) return;

            TextureAtlasSprite sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(stillTex);
            int tint = ext.getTintColor();
            float r = ((tint >>> 16) & 0xFF) / 255.0f;
            float g = ((tint >>> 8) & 0xFF) / 255.0f;
            float b = (tint & 0xFF) / 255.0f;
            float a = (((tint >>> 24) & 0xFF) / 255.0f) * finalOpacity;
            if (a <= 0.001f) a = finalOpacity;

            VertexConsumer builder = buffer.getBuffer(RenderType.entityTranslucent(InventoryMenu.BLOCK_ATLAS));
            Matrix4f mat = poseStack.last().pose();

            if (rTL > 0 || rTR > 0 || rBR > 0 || rBL > 0) {
                drawRoundedTexturedRect(builder, mat, el.x(), el.y(), el.x() + el.width(), el.y() + el.height(),
                        sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1(),
                        rTL, rTR, rBR, rBL, r, g, b, a, zPos, packedLight, packedOverlay,
                        clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
            } else {
                int tileSize = 16;
                for (int ty = 0; ty < el.height(); ty += tileSize) {
                    int th = Math.min(tileSize, el.height() - ty);
                    float vRatio = (float) th / tileSize;
                    float v1 = sprite.getV0() + (sprite.getV1() - sprite.getV0()) * vRatio;
                    for (int tx = 0; tx < el.width(); tx += tileSize) {
                        int tw = Math.min(tileSize, el.width() - tx);
                        float uRatio = (float) tw / tileSize;
                        float u1 = sprite.getU0() + (sprite.getU1() - sprite.getU0()) * uRatio;
                        drawClippedTexturedQuad(builder, mat,
                                el.x() + tx, el.y() + ty, el.x() + tx + tw, el.y() + ty + th,
                                sprite.getU0(), sprite.getV0(), u1, v1,
                                r, g, b, a, zPos, packedLight, packedOverlay,
                                clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    static void renderEntityElement(
            DisplayItem el, String entitySpec, PoseStack poseStack, MultiBufferSource buffer,
            Level level, float zPos, int packedLight
    ) {
        Entity entity = resolvePreviewEntity(entitySpec, level);
        if (entity == null) return;

        poseStack.pushPose();
        try {
            float bbH = Math.max(0.8f, entity.getBbHeight());
            float scale = Math.min(el.width(), el.height()) * 0.72f / bbH;
            float cx = el.x() + el.width() * 0.5f;
            float bottomY = el.y() + el.height() * 0.88f;

            poseStack.translate(cx, bottomY, zPos + ZLayers.ENTITY_PREVIEW_Z);
            poseStack.scale(scale, -scale, scale);
            poseStack.mulPose(Axis.XP.rotationDegrees(-15.0f));

            float rotY = (System.currentTimeMillis() % 12000L) / 12000.0f * 360.0f;
            poseStack.mulPose(Axis.YP.rotationDegrees(rotY));

            EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
            dispatcher.setRenderShadow(false);
            if (entity instanceof LivingEntity le) {
                float origYBody = le.yBodyRot;
                float origYHead = le.yHeadRot;
                float origYRot = le.getYRot();
                le.yBodyRot = 180.0f;
                le.setYRot(180.0f);
                le.yHeadRot = 180.0f;
                dispatcher.render(le, 0.0, 0.0, 0.0, 0.0f, 1.0f, poseStack, buffer, packedLight);
                le.yBodyRot = origYBody;
                le.setYRot(origYRot);
                le.yHeadRot = origYHead;
            } else {
                dispatcher.render(entity, 0.0, 0.0, 0.0, 0.0f, 1.0f, poseStack, buffer, packedLight);
            }
            dispatcher.setRenderShadow(true);
        } catch (Exception ignored) {
        } finally {
            poseStack.popPose();
        }
    }
}
