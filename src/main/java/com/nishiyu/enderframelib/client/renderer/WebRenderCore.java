package com.nishiyu.enderframelib.client.renderer;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.core.model.UIElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class WebRenderCore {

    public static final float RESOLUTION = 32.0f;
    public static final Map<String, Integer> inputScrollOffsets = new ConcurrentHashMap<>();
    public static final Map<String, Integer> textareaCursorIndex = new ConcurrentHashMap<>();
    public static final Map<String, Integer> textareaScrollRow = new ConcurrentHashMap<>();
    public static String activeInputId = "";
    public static String activePressedId = "";

    private static ResourceLocation resolvePlayerSkin(String target) {
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

    private static float snapTextScaleFor2D(Matrix4f pose, float rawTextScale, boolean is2D) {
        if (!is2D) return rawTextScale;
        float matScale = Math.abs(pose.m00());
        if (matScale <= 0.0001f) return rawTextScale;

        double guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        float physicalFontScale = (float) (rawTextScale * matScale * guiScale);

        if (physicalFontScale < 1.0f) {
            return (float) (1.0 / (matScale * guiScale));
        }

        float snappedPhys = Math.round(physicalFontScale * 2.0f) / 2.0f;
        if (Math.abs(physicalFontScale - snappedPhys) < 0.18f) {
            return (float) (snappedPhys / (matScale * guiScale));
        }
        return rawTextScale;
    }

    private static float snapCoordFor2D(float coord, float matScale, boolean is2D) {
        if (!is2D || matScale <= 0.0001f) return coord;
        double guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        double step = matScale * guiScale;
        return (float) (Math.round(coord * step) / step);
    }

    public static String highlightCodeLine(String raw, String syntax) {
        if (raw == null || raw.isEmpty()) return "";
        if (!"lua".equalsIgnoreCase(syntax)) return raw;
        String trimmed = raw.trim();
        if (trimmed.startsWith("--")) return "§8" + raw + "§r";

        int commentIdx = raw.indexOf("--");
        String codePart = commentIdx >= 0 ? raw.substring(0, commentIdx) : raw;
        String commentPart = commentIdx >= 0 ? "§8" + raw.substring(commentIdx) + "§r" : "";

        codePart = codePart.replaceAll("\\b(local|function|return|if|then|else|elseif|end|for|in|do|while|repeat|until|break|and|or|not)\\b", "§d$1§r");
        codePart = codePart.replaceAll("\\b(true|false|nil)\\b", "§6$1§r");
        codePart = codePart.replaceAll("\\b(turtle|system|net|ui|math|string|table|os|print|sleep)\\b", "§b$1§r");
        return codePart + commentPart;
    }

    private static void applyTransforms(PoseStack poseStack, UIElement el, String hoveredId, String sessionId, Map<String, UIElement> elementMap, float[] cumulativeOpacity) {
        if (el == null) return;
        if (el.events() != null && el.events().containsKey("mc-parent")) {
            String parentId = el.events().get("mc-parent");
            applyTransforms(poseStack, elementMap.get(parentId), hoveredId, sessionId, elementMap, cumulativeOpacity);
        }

        String baseId = ClientWebManager.getBaseNodeId(el.id());
        boolean isHovered = baseId.equals(hoveredId);
        boolean isActive = !activePressedId.isEmpty() && baseId.equals(ClientWebManager.getBaseNodeId(activePressedId));
        boolean isFocused = !activeInputId.isEmpty() && baseId.equals(ClientWebManager.getBaseNodeId(activeInputId));
        WebAnimator.AnimatedValues anim = WebAnimator.getValues(sessionId, el, isHovered, isActive, isFocused);

        float originX = 0.5f, originY = 0.5f;
        if (el.events() != null && el.events().containsKey("mc-origin")) {
            String[] parts = el.events().get("mc-origin").split(",");
            if (parts.length >= 2) {
                try { originX = Float.parseFloat(parts[0]); originY = Float.parseFloat(parts[1]); } catch (Exception ignored) {}
            }
        }
        float cx = el.x() + el.width() * originX;
        float cy = el.y() + el.height() * originY;

        poseStack.translate(cx + anim.tx(), cy + anim.ty(), 0);
        if (anim.rot() != 0) poseStack.mulPose(Axis.ZP.rotationDegrees(anim.rot()));
        if (anim.sx() != 1.0f || anim.sy() != 1.0f) poseStack.scale(anim.sx(), anim.sy(), 1.0f);
        poseStack.translate(-cx, -cy, 0);

        cumulativeOpacity[0] *= anim.opacity();
    }

    public static void renderElements(
            List<UIElement> uiElements, int screenWidth, int screenHeight,
            PoseStack poseStack, MultiBufferSource buffer, int packedLight, int packedOverlay,
            boolean renderItems, boolean renderText, boolean renderRounded,
            float zStep, float subZOffset, String hoveredId, int lodColor, boolean isLodMode,
            Level level, boolean playAudio, boolean is2DAudio, String sessionId,
            BlockPos audioPos, ICustomRenderer customRenderer, int renderMode
    ) {
        float maxAllowedX = screenWidth * RESOLUTION;
        float maxAllowedY = screenHeight * RESOLUTION;
        Font font = Minecraft.getInstance().font;
        float zOffset = 0.0f;

        if (isLodMode) {
            if ((lodColor >> 24 & 0xFF) > 0) {
                VertexConsumer builder = buffer.getBuffer(RenderType.gui());
                float a = ((lodColor >> 24) & 0xFF) / 255.0f, r = ((lodColor >> 16) & 0xFF) / 255.0f, g = ((lodColor >> 8) & 0xFF) / 255.0f, b = (lodColor & 0xFF) / 255.0f;
                addQuadRaw(builder, poseStack.last().pose(), 0, 0, maxAllowedX, maxAllowedY, r, g, b, a, 0.0f);
            }
            return;
        }

        if (sessionId != null && !sessionId.isEmpty()) WebAnimator.cleanup(sessionId, uiElements);

        Map<String, UIElement> elementMap = new HashMap<>();
        for (UIElement el : uiElements) elementMap.put(el.id(), el);

        UIElement hoveredTooltipEl = null;
        String resolvedTooltipText = null;

        for (UIElement el : uiElements) {
            if ("node".equals(el.type())) continue;

            zOffset += zStep;
            String baseId = ClientWebManager.getBaseNodeId(el.id());
            boolean isHovered = !hoveredId.isEmpty() && baseId.equals(hoveredId);
            boolean isActive = !activePressedId.isEmpty() && baseId.equals(ClientWebManager.getBaseNodeId(activePressedId));
            boolean isFocused = !activeInputId.isEmpty() && baseId.equals(ClientWebManager.getBaseNodeId(activeInputId));

            if (isHovered && resolvedTooltipText == null) {
                if (el.events() != null && el.events().containsKey("mc-title")) {
                    String t = el.events().get("mc-title");
                    if (!"false".equalsIgnoreCase(t) && !t.isEmpty()) {
                        hoveredTooltipEl = el;
                        resolvedTooltipText = t;
                    }
                } else if (el.type().startsWith("img") && el.text() != null && el.text().startsWith("item:")) {
                    try {
                        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(el.text().substring(5)));
                        if (item != Items.AIR) {
                            hoveredTooltipEl = el;
                            resolvedTooltipText = new ItemStack(item).getHoverName().getString();
                        }
                    } catch (Exception ignored) {}
                }
            }

            String animSessionId = (sessionId != null && !sessionId.isEmpty()) ? sessionId : "unknown";
            WebAnimator.AnimatedValues anim = WebAnimator.getValues(animSessionId, el, isHovered, isActive, isFocused);

            float clipMinX = 0, clipMinY = 0, clipMaxX = maxAllowedX, clipMaxY = maxAllowedY;
            float cRTL = 0, cRTR = 0, cRBR = 0, cRBL = 0;
            if (anim.clipData != null) {
                clipMinX = anim.clipData[0]; clipMinY = anim.clipData[1]; clipMaxX = anim.clipData[2]; clipMaxY = anim.clipData[3];
                cRTL = anim.clipData[4]; cRTR = anim.clipData[5]; cRBR = anim.clipData[6]; cRBL = anim.clipData[7];
            }

            float elWorldMaxX = el.x() + el.width(), elWorldMaxY = el.y() + el.height();
            if (elWorldMaxX < clipMinX || el.x() > clipMaxX || elWorldMaxY < clipMinY || el.y() > clipMaxY) continue;

            float rTL = 0, rTR = 0, rBR = 0, rBL = 0;
            if (renderRounded && anim.radiusData != null) {
                float limit = Math.min(el.width(), el.height()) / 2.0f;
                rTL = Math.min(anim.radiusData[0], limit); rTR = Math.min(anim.radiusData[1], limit);
                rBR = Math.min(anim.radiusData[2], limit); rBL = Math.min(anim.radiusData[3], limit);
            }

            poseStack.pushPose();
            try {
                float[] cumulativeOpacity = {1.0f};
                if (el.events() != null && el.events().containsKey("mc-parent")) {
                    String parentId = el.events().get("mc-parent");
                    applyTransforms(poseStack, elementMap.get(parentId), hoveredId, animSessionId, elementMap, cumulativeOpacity);
                }

                float originX = 0.5f, originY = 0.5f;
                if (el.events() != null && el.events().containsKey("mc-origin")) {
                    String[] parts = el.events().get("mc-origin").split(",");
                    if (parts.length >= 2) { try { originX = Float.parseFloat(parts[0]); originY = Float.parseFloat(parts[1]); } catch (Exception ignored) {} }
                }
                float cx = el.x() + el.width() * originX, cy = el.y() + el.height() * originY;

                poseStack.translate(cx + anim.tx(), cy + anim.ty(), 0);
                if (anim.rot() != 0) poseStack.mulPose(Axis.ZP.rotationDegrees(anim.rot()));
                if (anim.sx() != 1.0f || anim.sy() != 1.0f) poseStack.scale(anim.sx(), anim.sy(), 1.0f);
                poseStack.translate(-cx, -cy, 0);

                float finalOpacity = cumulativeOpacity[0] * anim.opacity();
                if (finalOpacity <= 0.005f) continue;

                if (customRenderer != null && customRenderer.render(el, poseStack, buffer, anim, zOffset, subZOffset, renderRounded)) continue;

                if ("circle_border".equals(el.type()) && anim.circleColors != null) {
                    float outerRadius = Math.min(el.width(), el.height()) / 2.0f;
                    int cTop = anim.circleColors[0], cRight = anim.circleColors[1], cBottom = anim.circleColors[2], cLeft = anim.circleColors[3];
                    VertexConsumer builder = buffer.getBuffer(RenderType.gui());
                    Matrix4f matrix = poseStack.last().pose();
                    float cX = el.x() + el.width() / 2.0f, cY = el.y() + el.height() / 2.0f;
                    if (cTop != 0) drawRingSegment(builder, matrix, cX, cY, outerRadius, anim.circleThickness, (float) (-Math.PI * 0.75), (float) (-Math.PI * 0.25), cTop, finalOpacity, zOffset + subZOffset);
                    if (cRight != 0) drawRingSegment(builder, matrix, cX, cY, outerRadius, anim.circleThickness, (float) (-Math.PI * 0.25), (float) (Math.PI * 0.25), cRight, finalOpacity, zOffset + subZOffset);
                    if (cBottom != 0) drawRingSegment(builder, matrix, cX, cY, outerRadius, anim.circleThickness, (float) (Math.PI * 0.25), (float) (Math.PI * 0.75), cBottom, finalOpacity, zOffset + subZOffset);
                    if (cLeft != 0) drawRingSegment(builder, matrix, cX, cY, outerRadius, anim.circleThickness, (float) (Math.PI * 0.75), (float) (Math.PI * 1.25), cLeft, finalOpacity, zOffset + subZOffset);
                    continue;
                }

                if (!"range".equals(el.type())) {
                    String gradDef = (el.events() != null) ? el.events().get("mc-gradient") : null;
                    if (gradDef != null && !isHovered && !isActive) {
                        String[] gParts = gradDef.split(":");
                        if (gParts.length >= 3) {
                            try {
                                String gDir = gParts[0];
                                int c1 = Integer.parseInt(gParts[1]);
                                int c2 = Integer.parseInt(gParts[2]);
                                VertexConsumer builder = buffer.getBuffer(RenderType.gui());
                                float x0 = Math.max(el.x(), clipMinX), y0 = Math.max(el.y(), clipMinY);
                                float x1 = Math.min(el.x() + el.width(), clipMaxX), y1 = Math.min(el.y() + el.height(), clipMaxY);
                                if (x0 < x1 && y0 < y1) {
                                    addGradientQuadRaw(builder, poseStack.last().pose(), x0, y0, x1, y1, gDir, c1, c2, finalOpacity, zOffset);
                                }
                            } catch (Exception ignored) {}
                        }
                    } else {
                        float bgA = ((anim.bgColor() >> 24) & 0xFF) / 255.0f * finalOpacity;
                        if (bgA > 0) {
                            VertexConsumer builder = buffer.getBuffer(RenderType.gui());
                            float r = ((anim.bgColor() >> 16) & 0xFF) / 255.0f, g = ((anim.bgColor() >> 8) & 0xFF) / 255.0f, b = (anim.bgColor() & 0xFF) / 255.0f;
                            if (rTL > 0 || rTR > 0 || rBR > 0 || rBL > 0) drawRoundedRect(builder, poseStack.last().pose(), el.x(), el.y(), el.x() + el.width(), el.y() + el.height(), rTL, rTR, rBR, rBL, r, g, b, bgA, zOffset, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
                            else drawClippedQuad(builder, poseStack.last().pose(), el.x(), el.y(), el.x() + el.width(), el.y() + el.height(), r, g, b, bgA, zOffset, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
                        }
                    }
                }

                // ★追加: <canvas> の動的2Dベクター描画
                if ("canvas".equals(el.type())) {
                    WebCanvasContext ctx = WebCanvasContext.get(animSessionId, baseId);
                    if (ctx != null) {
                        ctx.renderCommands(el, poseStack, buffer, finalOpacity, zOffset + subZOffset, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL, packedLight);
                    }
                    continue;
                }

                // ★追加: <textarea> のマルチライン・コードエディタ描画
                if ("textarea".equals(el.type())) {
                    if (renderText) {
                        renderTextareaElement(el, poseStack, buffer, font, anim, finalOpacity, zOffset + subZOffset, clipMinX, clipMinY, clipMaxX, clipMaxY, is2DAudio, packedLight);
                    }
                    continue;
                }

                if ("progress".equals(el.type())) {
                    float ratio = 0;
                    if (anim.rangeMax > anim.rangeMin) ratio = Math.max(0, Math.min(1, (anim.rangeVal - anim.rangeMin) / (anim.rangeMax - anim.rangeMin)));
                    float fillW = el.width() * ratio;
                    if (fillW > 0.5f) {
                        int pC = anim.color() != 0 ? anim.color() : 0xFF28A745;
                        float prA = ((pC >> 24) & 0xFF) / 255.0f * finalOpacity, prR = ((pC >> 16) & 0xFF) / 255.0f, prG = ((pC >> 8) & 0xFF) / 255.0f, prB = (pC & 0xFF) / 255.0f;
                        VertexConsumer builder = buffer.getBuffer(RenderType.gui());
                        drawRoundedRect(builder, poseStack.last().pose(), el.x(), el.y(), el.x() + fillW, el.y() + el.height(), rTL, ratio > 0.98f ? rTR : 0, ratio > 0.98f ? rBR : 0, rBL, prR, prG, prB, prA, zOffset + subZOffset, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
                    }
                    continue;
                }

                if (el.type().startsWith("img")) {
                    if (renderItems && el.text() != null && !el.text().isEmpty()) {
                        String src = el.text();
                        if (src.startsWith("https://") || src.startsWith("http://")) {
                            WebMediaManager.ImageInfo info = WebMediaManager.getImage(src);
                            if (info != null) {
                                ResourceLocation texLoc = info.textureId();
                                int imgW = info.width(), imgH = info.height();
                                VertexConsumer builder = buffer.getBuffer(RenderType.entityTranslucent(texLoc));
                                float u0 = 0.0f, v0 = 0.0f, u1 = 1.0f, v1 = 1.0f;
                                float drawMinX = el.x(), drawMinY = el.y(), drawMaxX = el.x() + el.width(), drawMaxY = el.y() + el.height();
                                String fit = el.type().contains(":") ? el.type().split(":")[1] : "fill";
                                float boxW = drawMaxX - drawMinX, boxH = drawMaxY - drawMinY;
                                if (imgW > 0 && imgH > 0 && boxW > 0 && boxH > 0) {
                                    float boxAspect = boxW / boxH, imgAspect = (float) imgW / imgH;
                                    if ("contain".equalsIgnoreCase(fit)) {
                                        if (imgAspect > boxAspect) { float diff = (boxH - (boxW / imgAspect)) / 2.0f; drawMinY += diff; drawMaxY -= diff; }
                                        else { float diff = (boxW - (boxH * imgAspect)) / 2.0f; drawMinX += diff; drawMaxX -= diff; }
                                    } else if ("cover".equalsIgnoreCase(fit)) {
                                        if (imgAspect > boxAspect) { float crop = (imgW - (imgH * boxAspect)) / (2.0f * imgW); u0 = crop; u1 = 1.0f - crop; }
                                        else { float crop = (imgH - (imgW / boxAspect)) / (2.0f * imgH); v0 = crop; v1 = 1.0f - crop; }
                                    }
                                }
                                drawRoundedTexturedRect(builder, poseStack.last().pose(), drawMinX, drawMinY, drawMaxX, drawMaxY, u0, v0, u1, v1, rTL, rTR, rBR, rBL, 1f, 1f, 1f, finalOpacity, zOffset + subZOffset, packedLight, packedOverlay, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
                            }
                        } else if (src.startsWith("player:")) {
                            String playerTarget = src.substring(7).trim();
                            ResourceLocation skinLoc = resolvePlayerSkin(playerTarget);
                            VertexConsumer builder = buffer.getBuffer(RenderType.entityTranslucent(skinLoc));
                            drawRoundedTexturedRect(builder, poseStack.last().pose(), el.x(), el.y(), el.x() + el.width(), el.y() + el.height(), 8f / 64f, 8f / 64f, 16f / 64f, 16f / 64f, rTL, rTR, rBR, rBL, 1f, 1f, 1f, finalOpacity, zOffset + subZOffset, packedLight, packedOverlay, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
                            drawRoundedTexturedRect(builder, poseStack.last().pose(), el.x(), el.y(), el.x() + el.width(), el.y() + el.height(), 40f / 64f, 8f / 64f, 48f / 64f, 16f / 64f, rTL, rTR, rBR, rBL, 1f, 1f, 1f, finalOpacity, zOffset + subZOffset + 0.001f, packedLight, packedOverlay, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
                        } else if (src.startsWith("item:")) {
                            String itemId = src.substring(5);
                            try {
                                Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId));
                                if (item != Items.AIR) {
                                    poseStack.pushPose();
                                    try {
                                        poseStack.translate(el.x() + (el.width() / 2.0f), el.y() + (el.height() / 2.0f), zOffset + subZOffset);
                                        poseStack.scale(el.width(), -el.height(), 1.0f);
                                        poseStack.mulPose(Axis.YP.rotationDegrees(180.0f));
                                        Minecraft.getInstance().getItemRenderer().renderStatic(new ItemStack(item), net.minecraft.world.item.ItemDisplayContext.FIXED, packedLight, packedOverlay, poseStack, buffer, level, 0);
                                    } finally { poseStack.popPose(); }
                                }
                            } catch (Exception ignored) {}
                        } else if (src.startsWith("effect:")) {
                            String effectId = src.substring(7);
                            try {
                                java.util.Optional<net.minecraft.core.Holder.Reference<net.minecraft.world.effect.MobEffect>> effectOpt = BuiltInRegistries.MOB_EFFECT.getHolder(ResourceLocation.parse(effectId));
                                if (effectOpt.isPresent()) {
                                    net.minecraft.client.renderer.texture.TextureAtlasSprite sprite = Minecraft.getInstance().getMobEffectTextures().get(effectOpt.get());
                                    VertexConsumer builder = buffer.getBuffer(RenderType.entityTranslucent(sprite.atlasLocation()));
                                    drawRoundedTexturedRect(builder, poseStack.last().pose(), el.x(), el.y(), el.x() + el.width(), el.y() + el.height(), sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1(), rTL, rTR, rBR, rBL, 1f, 1f, 1f, finalOpacity, zOffset + subZOffset, packedLight, packedOverlay, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
                                }
                            } catch (Exception ignored) {}
                        } else {
                            try {
                                VertexConsumer builder = buffer.getBuffer(RenderType.entityTranslucent(ResourceLocation.parse(src)));
                                drawRoundedTexturedRect(builder, poseStack.last().pose(), el.x(), el.y(), el.x() + el.width(), el.y() + el.height(), 0.0f, 0.0f, 1.0f, 1.0f, rTL, rTR, rBR, rBL, 1f, 1f, 1f, finalOpacity, zOffset + subZOffset, packedLight, packedOverlay, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
                            } catch (Exception ignored) {}
                        }
                    }
                } else if ((el.text() != null && !el.text().isEmpty()) || "input".equals(el.type()) || "select".equals(el.type()) || "range".equals(el.type())) {
                    String textToDraw = el.text() == null ? "" : el.text();
                    float textX = el.x(), textY = el.y();

                    float fontH = el.height();
                    if (el.events() != null && el.events().containsKey("mc-font-h")) {
                        try { fontH = Float.parseFloat(el.events().get("mc-font-h")); } catch (Exception ignored) {}
                    }
                    float textScale = fontH / 12.0f;

                    if (textY > clipMaxY - (el.height() * 0.3f) || textY + el.height() < clipMinY + (el.height() * 0.3f)) continue;

                    Matrix4f currentPose = poseStack.last().pose();
                    float matScale = Math.abs(currentPose.m00());

                    if ("input".equals(el.type()) || "select".equals(el.type())) {
                        float maxAllowedScale = Math.max(0.75f, (el.height() - 4.0f) / 10.0f);
                        textScale = Math.min(textScale, maxAllowedScale);
                        textScale = snapTextScaleFor2D(currentPose, textScale, is2DAudio);
                        textX += 4.0f;
                        textY += (el.height() - (8.0f * textScale)) / 2.0f;
                    } else if ("range".equals(el.type())) {
                        float ratio = 0;
                        if (anim.rangeMax > anim.rangeMin) ratio = Math.max(0, Math.min(1, (anim.rangeVal - anim.rangeMin) / (anim.rangeMax - anim.rangeMin)));
                        VertexConsumer builder = buffer.getBuffer(RenderType.gui());
                        Matrix4f mat = poseStack.last().pose();
                        float trackH = Math.max(4.0f, el.height() * 0.3f), trackY = el.y() + (el.height() - trackH) / 2.0f, trackRad = trackH / 2.0f;
                        float thumbRad = Math.max(10.0f, el.height() * 0.8f) / 2.0f, thumbCY = el.y() + el.height() / 2.0f;
                        float tMinX = el.x() + thumbRad, tMaxX = el.x() + el.width() - thumbRad, tCX = tMinX + ratio * (tMaxX - tMinX);
                        int pC = anim.color() != 0 ? anim.color() : 0xFF007BFF;
                        float prA = ((pC >> 24) & 0xFF) / 255.0f * finalOpacity, prR = ((pC >> 16) & 0xFF) / 255.0f, prG = ((pC >> 8) & 0xFF) / 255.0f, prB = (pC & 0xFF) / 255.0f;
                        if (tCX > el.x()) drawRoundedRect(builder, mat, el.x(), trackY, tCX, trackY + trackH, trackRad, 0, 0, trackRad, prR, prG, prB, prA, zOffset + subZOffset, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
                        int rC = anim.bgColor() != 0 ? anim.bgColor() : 0xFFFFFFFF;
                        float reA = ((rC >> 24) & 0xFF) / 255.0f * finalOpacity, reR = ((rC >> 16) & 0xFF) / 255.0f, reG = ((rC >> 8) & 0xFF) / 255.0f, reB = (rC & 0xFF) / 255.0f;
                        if (tCX < el.x() + el.width()) drawRoundedRect(builder, mat, tCX, trackY, el.x() + el.width(), trackY + trackH, 0, trackRad, trackRad, 0, reR, reG, reB, reA, zOffset + subZOffset, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
                        drawRoundedRect(builder, mat, tCX - thumbRad, thumbCY - thumbRad, tCX + thumbRad, thumbCY + thumbRad, thumbRad, thumbRad, thumbRad, thumbRad, prR, prG, prB, prA, zOffset + subZOffset + 0.002f, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
                        continue;
                    } else if ("toggle".equals(el.type())) {
                        if ("true".equals(textToDraw)) {
                            float a = ((anim.color() >> 24) & 0xFF) / 255.0f * finalOpacity;
                            if (a > 0.0f) {
                                float pad = el.width() * 0.25f;
                                float r = ((anim.color() >> 16) & 0xFF) / 255.0f, g = ((anim.color() >> 8) & 0xFF) / 255.0f, b = (anim.color() & 0xFF) / 255.0f;
                                drawClippedQuad(buffer.getBuffer(RenderType.gui()), poseStack.last().pose(), textX + pad, textY + pad, textX + el.width() - pad, textY + el.height() - pad, r, g, b, a, zOffset + subZOffset, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
                            }
                        }
                        continue;
                    } else {
                        textScale = snapTextScaleFor2D(currentPose, el.height() / 12.0f, is2DAudio);
                    }

                    if (!"input".equals(el.type()) && textToDraw.isEmpty()) continue;

                    if (renderText && textX < maxAllowedX && textY < maxAllowedY) {
                        int newA = (int) (((anim.color() >> 24) & 0xFF) * finalOpacity);
                        if (newA > 0) {
                            boolean useShadow = el.events() != null && "true".equals(el.events().get("text-shadow"));
                            String textDeco = el.events() != null ? el.events().getOrDefault("text-decoration", "none") : "none";

                            poseStack.pushPose();
                            try {
                                if ("input".equals(el.type()) || "select".equals(el.type())) {
                                    float snappedX = snapCoordFor2D(textX, matScale, is2DAudio);
                                    float snappedY = snapCoordFor2D(textY, matScale, is2DAudio);
                                    poseStack.translate(snappedX, snappedY, zOffset + subZOffset);
                                    poseStack.scale(textScale, textScale, 1.0f);
                                    int safeCursorPos = -1;
                                    if ("input".equals(el.type()) && el.id().equals(activeInputId)) {
                                        net.minecraft.client.gui.screens.Screen screen = Minecraft.getInstance().screen;
                                        if (screen != null) {
                                            if (screen.getFocused() instanceof net.minecraft.client.gui.components.EditBox eb) safeCursorPos = eb.getCursorPosition();
                                            else {
                                                for (var child : screen.children()) {
                                                    if (child instanceof net.minecraft.client.gui.components.EditBox eb && eb.isFocused()) { safeCursorPos = eb.getCursorPosition(); break; }
                                                }
                                            }
                                        }
                                    }
                                    int scrollOffset = inputScrollOffsets.getOrDefault(el.id(), 0);
                                    float maxAvailableWidth = (clipMaxX - textX) / textScale;
                                    float availableWidth = Math.max(0, Math.min((el.width() - 8.0f) / textScale, maxAvailableWidth));

                                    if (safeCursorPos >= 0) {
                                        safeCursorPos = Math.min(textToDraw.length(), safeCursorPos);
                                        if (safeCursorPos < scrollOffset) scrollOffset = safeCursorPos;
                                        while (scrollOffset < safeCursorPos && font.width(textToDraw.substring(scrollOffset, safeCursorPos)) > availableWidth) scrollOffset++;
                                        inputScrollOffsets.put(el.id(), scrollOffset);
                                    } else {
                                        if (scrollOffset > textToDraw.length()) scrollOffset = textToDraw.length();
                                    }

                                    String visibleText = font.plainSubstrByWidth(textToDraw.substring(scrollOffset), (int) availableWidth);
                                    if (!visibleText.isEmpty()) font.drawInBatch(visibleText, 0, 0, (newA << 24) | (anim.color() & 0x00FFFFFF), useShadow, poseStack.last().pose(), buffer, Font.DisplayMode.NORMAL, 0, packedLight);

                                    if (safeCursorPos >= 0 && safeCursorPos >= scrollOffset && (safeCursorPos - scrollOffset) <= visibleText.length()) {
                                        if ((System.currentTimeMillis() / 500) % 2 == 0) {
                                            float cursorX = font.width(visibleText.substring(0, safeCursorPos - scrollOffset));
                                            float r = ((anim.color() >> 16) & 0xFF) / 255.0f, g = ((anim.color() >> 8) & 0xFF) / 255.0f, b = (anim.color() & 0xFF) / 255.0f;
                                            addQuadRaw(buffer.getBuffer(RenderType.gui()), poseStack.last().pose(), cursorX, -1.0f, cursorX + 1.5f, 9.0f, r, g, b, newA / 255.0f, 0.01f);
                                        }
                                    }
                                } else {
                                    if (el.id().endsWith("_ph")) {
                                        float phY = textY + (el.height() - (8.0f * textScale)) / 2.0f;
                                        poseStack.translate(snapCoordFor2D(textX, matScale, is2DAudio), snapCoordFor2D(phY, matScale, is2DAudio), zOffset + subZOffset);
                                        poseStack.scale(textScale, textScale, 1.0f);
                                        float availableWidth = Math.max(0, Math.min((el.width() - 8.0f) / textScale, (clipMaxX - textX) / textScale));
                                        String visibleText = font.plainSubstrByWidth(textToDraw, (int) availableWidth);
                                        if (!visibleText.isEmpty()) font.drawInBatch(visibleText, 0, 0, (newA << 24) | (anim.color() & 0x00FFFFFF), useShadow, poseStack.last().pose(), buffer, Font.DisplayMode.NORMAL, 0, packedLight);
                                    } else if (!textToDraw.isEmpty()) {
                                        float availableWidth = Math.max(0, Math.min(el.width() / textScale, (clipMaxX - textX) / textScale));
                                        String visibleText = textToDraw;
                                        if (el.width() > 0 && font.width(textToDraw) > availableWidth * 1.3f + 4) {
                                            int ellipsisW = font.width("...");
                                            visibleText = availableWidth > ellipsisW ? font.plainSubstrByWidth(textToDraw, (int) availableWidth - ellipsisW) + "..." : font.plainSubstrByWidth(textToDraw, (int) availableWidth);
                                        }
                                        float rawTextW = font.width(visibleText);
                                        float alignOffsetX = 0, actualTextWidth = rawTextW * textScale;
                                        if ("center".equals(anim.textAlign)) alignOffsetX = Math.max(0, (el.width() - actualTextWidth) / 2.0f);
                                        else if ("right".equals(anim.textAlign)) alignOffsetX = Math.max(0, el.width() - actualTextWidth);

                                        float drawX = snapCoordFor2D(textX + alignOffsetX, matScale, is2DAudio);
                                        float drawY = snapCoordFor2D(textY + ((el.height() - (8.0f * textScale)) / 2.0f), matScale, is2DAudio);

                                        poseStack.translate(drawX, drawY, zOffset + subZOffset);
                                        poseStack.scale(textScale, textScale, 1.0f);
                                        font.drawInBatch(visibleText, 0, 0, (newA << 24) | (anim.color() & 0x00FFFFFF), useShadow, poseStack.last().pose(), buffer, Font.DisplayMode.NORMAL, 0, packedLight);

                                        if (textDeco.contains("underline") || textDeco.contains("line-through")) {
                                            float r = ((anim.color() >> 16) & 0xFF) / 255.0f, g = ((anim.color() >> 8) & 0xFF) / 255.0f, b = (anim.color() & 0xFF) / 255.0f;
                                            VertexConsumer lineBuilder = buffer.getBuffer(RenderType.gui());
                                            if (textDeco.contains("underline")) {
                                                addQuadRaw(lineBuilder, poseStack.last().pose(), 0, 8.5f, rawTextW, 9.5f, r, g, b, newA / 255.0f, 0.01f);
                                            }
                                            if (textDeco.contains("line-through")) {
                                                addQuadRaw(lineBuilder, poseStack.last().pose(), 0, 4.0f, rawTextW, 5.0f, r, g, b, newA / 255.0f, 0.01f);
                                            }
                                        }
                                    }
                                }
                            } finally { poseStack.popPose(); }
                        }
                    }
                }
            } finally { poseStack.popPose(); }
        }

        if (renderText && hoveredTooltipEl != null && resolvedTooltipText != null && !resolvedTooltipText.isEmpty()) {
            renderTooltipOverlay(poseStack, buffer, font, hoveredTooltipEl, resolvedTooltipText, maxAllowedX, maxAllowedY, zOffset + zStep * 4 + 0.05f, packedLight);
        }
    }

    private static void renderTextareaElement(
            UIElement el, PoseStack poseStack, MultiBufferSource buffer, Font font,
            WebAnimator.AnimatedValues anim, float finalOpacity, float zPos,
            float clipMinX, float clipMinY, float clipMaxX, float clipMaxY, boolean is2D, int packedLight
    ) {
        String fullText = el.text() != null ? el.text() : "";
        String[] lines = fullText.replace("\r\n", "\n").split("\n", -1);

        float fontH = 12.0f;
        if (el.events() != null && el.events().containsKey("mc-font-h")) {
            try { fontH = Float.parseFloat(el.events().get("mc-font-h")); } catch (Exception ignored) {}
        }
        Matrix4f mat = poseStack.last().pose();
        float matScale = Math.abs(mat.m00());
        float textScale = snapTextScaleFor2D(mat, fontH / 12.0f, is2D);
        float lineHeight = Math.max(12.0f * textScale, 11.0f);

        String syntax = el.events() != null ? el.events().getOrDefault("data-syntax", "") : "";
        boolean showLineNums = "lua".equalsIgnoreCase(syntax) || (el.events() != null && "true".equalsIgnoreCase(el.events().get("data-line-numbers")));
        float gutterW = showLineNums ? 28.0f * textScale : 4.0f;

        boolean isFocused = el.id().equals(activeInputId);
        int cursorIdx = Math.clamp(textareaCursorIndex.getOrDefault(el.id(), fullText.length()), 0, fullText.length());

        // カーソルがある行(cursorRow)と列(cursorCol)を算出
        int cursorRow = 0, cursorCol = 0, charAccum = 0;
        for (int i = 0; i < lines.length; i++) {
            int len = lines[i].length();
            if (cursorIdx <= charAccum + len) {
                cursorRow = i;
                cursorCol = cursorIdx - charAccum;
                break;
            }
            charAccum += len + 1;
        }

        int maxVisibleRows = Math.max(1, (int) ((el.height() - 8.0f) / lineHeight));
        int scrollRow = textareaScrollRow.getOrDefault(el.id(), 0);
        if (isFocused) {
            if (cursorRow < scrollRow) scrollRow = cursorRow;
            else if (cursorRow >= scrollRow + maxVisibleRows) scrollRow = cursorRow - maxVisibleRows + 1;
        }
        scrollRow = Math.clamp(scrollRow, 0, Math.max(0, lines.length - maxVisibleRows));
        textareaScrollRow.put(el.id(), scrollRow);

        // ガター背景とアクティブ行ハイライト
        VertexConsumer quadBuilder = buffer.getBuffer(RenderType.gui());
        if (showLineNums) {
            addQuadRaw(quadBuilder, mat, el.x(), el.y(), el.x() + gutterW - 3.0f, el.y() + el.height(), 0.05f, 0.05f, 0.09f, 0.45f * finalOpacity, zPos);
        }

        int newA = (int) (((anim.color() >> 24) & 0xFF) * finalOpacity);
        if (newA <= 0) return;
        int textColor = (newA << 24) | (anim.color() & 0x00FFFFFF);
        int gutterColor = (newA << 24) | 0x585B70;
        float availW = Math.max(10.0f, (el.width() - gutterW - 6.0f) / textScale);

        for (int i = 0; i < maxVisibleRows; i++) {
            int rowIdx = scrollRow + i;
            if (rowIdx >= lines.length) break;

            float rowY = el.y() + 4.0f + i * lineHeight;
            if (rowY + lineHeight < clipMinY || rowY > clipMaxY) continue;

            if (isFocused && rowIdx == cursorRow) {
                addQuadRaw(quadBuilder, mat, el.x() + gutterW - 2.0f, rowY - 1.0f, el.x() + el.width() - 2.0f, rowY + lineHeight - 1.0f, 0.35f, 0.45f, 0.75f, 0.18f * finalOpacity, zPos + 0.001f);
            }

            poseStack.pushPose();
            try {
                float snappedY = snapCoordFor2D(rowY, matScale, is2D);
                if (showLineNums) {
                    poseStack.pushPose();
                    poseStack.translate(snapCoordFor2D(el.x() + 4.0f, matScale, is2D), snappedY, zPos + 0.002f);
                    poseStack.scale(textScale, textScale, 1.0f);
                    font.drawInBatch(String.valueOf(rowIdx + 1), 0, 0, gutterColor, false, poseStack.last().pose(), buffer, Font.DisplayMode.NORMAL, 0, packedLight);
                    poseStack.popPose();
                }

                String rawLine = lines[rowIdx];
                String visibleRaw = font.plainSubstrByWidth(rawLine, (int) availW);
                String colored = highlightCodeLine(visibleRaw, syntax);

                poseStack.translate(snapCoordFor2D(el.x() + gutterW + 2.0f, matScale, is2D), snappedY, zPos + 0.002f);
                poseStack.scale(textScale, textScale, 1.0f);
                if (!colored.isEmpty()) {
                    font.drawInBatch(colored, 0, 0, textColor, false, poseStack.last().pose(), buffer, Font.DisplayMode.NORMAL, 0, packedLight);
                }

                if (isFocused && rowIdx == cursorRow && (System.currentTimeMillis() / 500) % 2 == 0) {
                    int safeCol = Math.clamp(cursorCol, 0, visibleRaw.length());
                    float curX = font.width(visibleRaw.substring(0, safeCol));
                    addQuadRaw(buffer.getBuffer(RenderType.gui()), poseStack.last().pose(), curX, -1.0f, curX + 1.5f, 9.0f, 0.54f, 0.71f, 0.98f, finalOpacity, 0.01f);
                }
            } finally {
                poseStack.popPose();
            }
        }
    }

    private static void renderTooltipOverlay(PoseStack poseStack, MultiBufferSource buffer, Font font, UIElement target, String tooltipText, float maxW, float maxH, float zPos, int packedLight) {
        String[] lines = tooltipText.split("\\\\n|\\n");
        float scale = 1.0f;
        int maxLineW = 0;
        for (String line : lines) {
            maxLineW = Math.max(maxLineW, font.width(line));
        }

        float boxW = maxLineW * scale + 10;
        float boxH = lines.length * 11 * scale + 8;

        float tipX = target.x() + (target.width() - boxW) / 2.0f;
        float tipY = target.y() - boxH - 4.0f;
        if (tipY < 2.0f) tipY = target.y() + target.height() + 4.0f;
        tipX = Math.clamp(tipX, 2.0f, maxW - boxW - 2.0f);
        tipY = Math.clamp(tipY, 2.0f, maxH - boxH - 2.0f);

        poseStack.pushPose();
        try {
            Matrix4f mat = poseStack.last().pose();
            VertexConsumer builder = buffer.getBuffer(RenderType.gui());
            addQuadRaw(builder, mat, tipX - 1, tipY - 1, tipX + boxW + 1, tipY + boxH + 1, 0.31f, 0.0f, 1.0f, 0.7f, zPos);
            addQuadRaw(builder, mat, tipX, tipY, tipX + boxW, tipY + boxH, 0.06f, 0.0f, 0.06f, 0.94f, zPos + 0.002f);

            poseStack.translate(tipX + 5.0f, tipY + 4.0f, zPos + 0.005f);
            poseStack.scale(scale, scale, 1.0f);
            for (int i = 0; i < lines.length; i++) {
                font.drawInBatch(lines[i], 0, i * 11, 0xFFFFFFFF, true, poseStack.last().pose(), buffer, Font.DisplayMode.NORMAL, 0, packedLight);
            }
        } finally {
            poseStack.popPose();
        }
    }

    public static void addGradientQuadRaw(VertexConsumer builder, Matrix4f matrix, float minX, float minY, float maxX, float maxY, String dir, int c1, int c2, float opacity, float zOffset) {
        float a1 = ((c1 >> 24) & 0xFF) / 255.0f * opacity, r1 = ((c1 >> 16) & 0xFF) / 255.0f, g1 = ((c1 >> 8) & 0xFF) / 255.0f, b1 = (c1 & 0xFF) / 255.0f;
        float a2 = ((c2 >> 24) & 0xFF) / 255.0f * opacity, r2 = ((c2 >> 16) & 0xFF) / 255.0f, g2 = ((c2 >> 8) & 0xFF) / 255.0f, b2 = (c2 & 0xFF) / 255.0f;

        switch (dir) {
            case "right" -> {
                builder.addVertex(matrix, minX, maxY, zOffset).setColor(r1, g1, b1, a1);
                builder.addVertex(matrix, maxX, maxY, zOffset).setColor(r2, g2, b2, a2);
                builder.addVertex(matrix, maxX, minY, zOffset).setColor(r2, g2, b2, a2);
                builder.addVertex(matrix, minX, minY, zOffset).setColor(r1, g1, b1, a1);
            }
            case "left" -> {
                builder.addVertex(matrix, minX, maxY, zOffset).setColor(r2, g2, b2, a2);
                builder.addVertex(matrix, maxX, maxY, zOffset).setColor(r1, g1, b1, a1);
                builder.addVertex(matrix, maxX, minY, zOffset).setColor(r1, g1, b1, a1);
                builder.addVertex(matrix, minX, minY, zOffset).setColor(r2, g2, b2, a2);
            }
            case "top" -> {
                builder.addVertex(matrix, minX, maxY, zOffset).setColor(r1, g1, b1, a1);
                builder.addVertex(matrix, maxX, maxY, zOffset).setColor(r1, g1, b1, a1);
                builder.addVertex(matrix, maxX, minY, zOffset).setColor(r2, g2, b2, a2);
                builder.addVertex(matrix, minX, minY, zOffset).setColor(r2, g2, b2, a2);
            }
            case "diag" -> {
                float rm = (r1 + r2) * 0.5f, gm = (g1 + g2) * 0.5f, bm = (b1 + b2) * 0.5f, am = (a1 + a2) * 0.5f;
                builder.addVertex(matrix, minX, maxY, zOffset).setColor(rm, gm, bm, am);
                builder.addVertex(matrix, maxX, maxY, zOffset).setColor(r2, g2, b2, a2);
                builder.addVertex(matrix, maxX, minY, zOffset).setColor(rm, gm, bm, am);
                builder.addVertex(matrix, minX, minY, zOffset).setColor(r1, g1, b1, a1);
            }
            default -> {
                builder.addVertex(matrix, minX, maxY, zOffset).setColor(r2, g2, b2, a2);
                builder.addVertex(matrix, maxX, maxY, zOffset).setColor(r2, g2, b2, a2);
                builder.addVertex(matrix, maxX, minY, zOffset).setColor(r1, g1, b1, a1);
                builder.addVertex(matrix, minX, minY, zOffset).setColor(r1, g1, b1, a1);
            }
        }
    }

    public static void drawRoundedRect(VertexConsumer builder, Matrix4f matrix, float minX, float minY, float maxX, float maxY, float rTL, float rTR, float rBR, float rBL, float r, float g, float b, float a, float zOffset, float cx0, float cy0, float cx1, float cy1, float cRTL, float cRTR, float cRBR, float cRBL) {
        float maxR = Math.max(Math.max(rTL, rTR), Math.max(rBR, rBL));
        if (maxR <= 0) { drawClippedQuad(builder, matrix, minX, minY, maxX, maxY, r, g, b, a, zOffset, cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL); return; }
        float maxR_Top = Math.max(rTL, rTR), maxR_Bottom = Math.max(rBL, rBR);
        drawClippedQuad(builder, matrix, minX, minY + maxR_Top, maxX, maxY - maxR_Bottom, r, g, b, a, zOffset, cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL);

        for (int i = 0; i < maxR_Top; i++) {
            float insetL = 0; if (i < rTL) insetL = rTL - (float) Math.sqrt(Math.max(0, rTL * rTL - Math.pow(Math.max(0, rTL - i - 0.5f), 2)));
            float insetR = 0; if (i < rTR) insetR = rTR - (float) Math.sqrt(Math.max(0, rTR * rTR - Math.pow(Math.max(0, rTR - i - 0.5f), 2)));
            drawClippedQuad(builder, matrix, minX + insetL, minY + i, maxX - insetR, minY + i + 1, r, g, b, a, zOffset, cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL);
        }
        for (int i = 0; i < maxR_Bottom; i++) {
            float insetL = 0; if (i < rBL) insetL = rBL - (float) Math.sqrt(Math.max(0, rBL * rBL - Math.pow(Math.max(0, rBL - i - 0.5f), 2)));
            float insetR = 0; if (i < rBR) insetR = rBR - (float) Math.sqrt(Math.max(0, rBR * rBR - Math.pow(Math.max(0, rBR - i - 0.5f), 2)));
            drawClippedQuad(builder, matrix, minX + insetL, maxY - i - 1, maxX - insetR, maxY - i, r, g, b, a, zOffset, cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL);
        }
    }

    public static void drawClippedQuad(VertexConsumer builder, Matrix4f matrix, float minX, float minY, float maxX, float maxY, float rCol, float gCol, float bCol, float aCol, float zOffset, float cx0, float cy0, float cx1, float cy1, float cRTL, float cRTR, float cRBR, float cRBL) {
        float startY = Math.max(minY, cy0), endY = Math.min(maxY, cy1);
        if (startY >= endY) return;
        float maxR_Top = Math.max(cRTL, cRTR), maxR_Bottom = Math.max(cRBL, cRBR);

        float topZoneEnd = Math.min(endY, cy0 + maxR_Top);
        for (float y = startY; y < topZoneEnd; y++) {
            float midY = (y + Math.min(y + 1.0f, topZoneEnd)) / 2.0f;
            float insetL = 0; if (cy0 + cRTL - midY > 0 && cRTL > 0) insetL = cRTL - (float) Math.sqrt(Math.max(0, cRTL * cRTL - Math.pow(cy0 + cRTL - midY, 2)));
            float insetR = 0; if (cy0 + cRTR - midY > 0 && cRTR > 0) insetR = cRTR - (float) Math.sqrt(Math.max(0, cRTR * cRTR - Math.pow(cy0 + cRTR - midY, 2)));
            float drawX0 = Math.max(minX, cx0 + insetL), drawX1 = Math.min(maxX, cx1 - insetR);
            if (drawX0 < drawX1) addQuadRaw(builder, matrix, drawX0, y, drawX1, Math.min(y + 1.0f, topZoneEnd), rCol, gCol, bCol, aCol, zOffset);
        }

        float midZoneStart = Math.max(startY, cy0 + maxR_Top), midZoneEnd = Math.min(endY, cy1 - maxR_Bottom);
        if (midZoneStart < midZoneEnd) {
            float drawX0 = Math.max(minX, cx0), drawX1 = Math.min(maxX, cx1);
            if (drawX0 < drawX1) addQuadRaw(builder, matrix, drawX0, midZoneStart, drawX1, midZoneEnd, rCol, gCol, bCol, aCol, zOffset);
        }

        float bottomZoneStart = Math.max(midZoneStart, Math.max(startY, cy1 - maxR_Bottom));
        for (float y = bottomZoneStart; y < endY; y++) {
            float midY = (y + Math.min(y + 1.0f, endY)) / 2.0f;
            float insetL = 0; if (midY - (cy1 - cRBL) > 0 && cRBL > 0) insetL = cRBL - (float) Math.sqrt(Math.max(0, cRBL * cRBL - Math.pow(midY - (cy1 - cRBL), 2)));
            float insetR = 0; if (midY - (cy1 - cRBR) > 0 && cRBR > 0) insetR = cRBR - (float) Math.sqrt(Math.max(0, cRBR * cRBR - Math.pow(midY - (cy1 - cRBR), 2)));
            float drawX0 = Math.max(minX, cx0 + insetL), drawX1 = Math.min(maxX, cx1 - insetR);
            if (drawX0 < drawX1) addQuadRaw(builder, matrix, drawX0, y, drawX1, Math.min(y + 1.0f, endY), rCol, gCol, bCol, aCol, zOffset);
        }
    }

    public static void drawRoundedTexturedRect(VertexConsumer builder, Matrix4f matrix, float minX, float minY, float maxX, float maxY, float u0, float v0, float u1, float v1, float rTL, float rTR, float rBR, float rBL, float r, float g, float b, float a, float zOffset, int packedLight, int packedOverlay, float cx0, float cy0, float cx1, float cy1, float cRTL, float cRTR, float cRBR, float cRBL) {
        float maxR = Math.max(Math.max(rTL, rTR), Math.max(rBR, rBL));
        if (maxR <= 0) { drawClippedTexturedQuad(builder, matrix, minX, minY, maxX, maxY, u0, v0, u1, v1, r, g, b, a, zOffset, packedLight, packedOverlay, cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL); return; }

        float maxR_Top = Math.max(rTL, rTR), maxR_Bottom = Math.max(rBL, rBR), totalW = maxX - minX, totalH = maxY - minY;
        float cy0_t = minY + maxR_Top, cy1_t = maxY - maxR_Bottom;
        float cv0 = v0 + ((cy0_t - minY) / totalH) * (v1 - v0), cv1 = v0 + ((cy1_t - minY) / totalH) * (v1 - v0);

        if (cy0_t < cy1_t) drawClippedTexturedQuad(builder, matrix, minX, cy0_t, maxX, cy1_t, u0, cv0, u1, cv1, r, g, b, a, zOffset, packedLight, packedOverlay, cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL);

        for (int i = 0; i < maxR_Top; i++) {
            float insetL = 0; if (i < rTL) insetL = rTL - (float) Math.sqrt(Math.max(0, rTL * rTL - Math.pow(Math.max(0, rTL - i - 0.5f), 2)));
            float insetR = 0; if (i < rTR) insetR = rTR - (float) Math.sqrt(Math.max(0, rTR * rTR - Math.pow(Math.max(0, rTR - i - 0.5f), 2)));
            float x0 = minX + insetL, x1 = maxX - insetR;
            if (x0 < x1) {
                float cu0 = u0 + ((x0 - minX) / totalW) * (u1 - u0), cu1 = u0 + ((x1 - minX) / totalW) * (u1 - u0);
                float y0_t = minY + i, y1_t = minY + i + 1;
                drawClippedTexturedQuad(builder, matrix, x0, y0_t, x1, y1_t, cu0, v0 + ((y0_t - minY) / totalH) * (v1 - v0), cu1, v0 + ((y1_t - minY) / totalH) * (v1 - v0), r, g, b, a, zOffset, packedLight, packedOverlay, cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL);
            }
        }
        for (int i = 0; i < maxR_Bottom; i++) {
            float insetL = 0; if (i < rBL) insetL = rBL - (float) Math.sqrt(Math.max(0, rBL * rBL - Math.pow(Math.max(0, rBL - i - 0.5f), 2)));
            float insetR = 0; if (i < rBR) insetR = rBR - (float) Math.sqrt(Math.max(0, rBR * rBR - Math.pow(Math.max(0, rBR - i - 0.5f), 2)));
            float x0 = minX + insetL, x1 = maxX - insetR;
            if (x0 < x1) {
                float cu0 = u0 + ((x0 - minX) / totalW) * (u1 - u0), cu1 = u0 + ((x1 - minX) / totalW) * (u1 - u0);
                float y0_b = maxY - i - 1, y1_b = maxY - i;
                drawClippedTexturedQuad(builder, matrix, x0, y0_b, x1, y1_b, cu0, v0 + ((y0_b - minY) / totalH) * (v1 - v0), cu1, v0 + ((y1_b - minY) / totalH) * (v1 - v0), r, g, b, a, zOffset, packedLight, packedOverlay, cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL);
            }
        }
    }

    public static void drawClippedTexturedQuad(VertexConsumer builder, Matrix4f matrix, float minX, float minY, float maxX, float maxY, float u0, float v0, float u1, float v1, float rCol, float gCol, float bCol, float aCol, float zOffset, int packedLight, int packedOverlay, float cx0, float cy0, float cx1, float cy1, float cRTL, float cRTR, float cRBR, float cRBL) {
        float startY = Math.max(minY, cy0), endY = Math.min(maxY, cy1);
        if (startY >= endY) return;
        float totalW = maxX - minX, totalH = maxY - minY;
        if (totalW <= 0 || totalH <= 0) return;
        float maxR_Top = Math.max(cRTL, cRTR), maxR_Bottom = Math.max(cRBL, cRBR);

        float topZoneEnd = Math.min(endY, cy0 + maxR_Top);
        for (float y = startY; y < topZoneEnd; y++) {
            float midY = (y + Math.min(y + 1.0f, topZoneEnd)) / 2.0f;
            float insetL = 0; if (cy0 + cRTL - midY > 0 && cRTL > 0) insetL = cRTL - (float) Math.sqrt(Math.max(0, cRTL * cRTL - Math.pow(cy0 + cRTL - midY, 2)));
            float insetR = 0; if (cy0 + cRTR - midY > 0 && cRTR > 0) insetR = cRTR - (float) Math.sqrt(Math.max(0, cRTR * cRTR - Math.pow(cy0 + cRTR - midY, 2)));
            float drawX0 = Math.max(minX, cx0 + insetL), drawX1 = Math.min(maxX, cx1 - insetR);
            if (drawX0 < drawX1) addTexturedQuadRaw(builder, matrix, drawX0, y, drawX1, Math.min(y + 1.0f, topZoneEnd), u0 + ((drawX0 - minX) / totalW) * (u1 - u0), v0 + ((y - minY) / totalH) * (v1 - v0), u0 + ((drawX1 - minX) / totalW) * (u1 - u0), v0 + ((Math.min(y + 1.0f, topZoneEnd) - minY) / totalH) * (v1 - v0), rCol, gCol, bCol, aCol, zOffset, packedLight, packedOverlay);
        }

        float midZoneStart = Math.max(startY, cy0 + maxR_Top), midZoneEnd = Math.min(endY, cy1 - maxR_Bottom);
        if (midZoneStart < midZoneEnd) {
            float drawX0 = Math.max(minX, cx0), drawX1 = Math.min(maxX, cx1);
            if (drawX0 < drawX1) addTexturedQuadRaw(builder, matrix, drawX0, midZoneStart, drawX1, midZoneEnd, u0 + ((drawX0 - minX) / totalW) * (u1 - u0), v0 + ((midZoneStart - minY) / totalH) * (v1 - v0), u0 + ((drawX1 - minX) / totalW) * (u1 - u0), v0 + ((midZoneEnd - minY) / totalH) * (v1 - v0), rCol, gCol, bCol, aCol, zOffset, packedLight, packedOverlay);
        }

        float bottomZoneStart = Math.max(midZoneStart, Math.max(startY, cy1 - maxR_Bottom));
        for (float y = bottomZoneStart; y < endY; y++) {
            float midY = (y + Math.min(y + 1.0f, endY)) / 2.0f;
            float insetL = 0; if (midY - (cy1 - cRBL) > 0 && cRBL > 0) insetL = cRBL - (float) Math.sqrt(Math.max(0, cRBL * cRBL - Math.pow(midY - (cy1 - cRBL), 2)));
            float insetR = 0; if (midY - (cy1 - cRBR) > 0 && cRBR > 0) insetR = cRBR - (float) Math.sqrt(Math.max(0, cRBR * cRBR - Math.pow(midY - (cy1 - cRBR), 2)));
            float drawX0 = Math.max(minX, cx0 + insetL), drawX1 = Math.min(maxX, cx1 - insetR);
            if (drawX0 < drawX1) addTexturedQuadRaw(builder, matrix, drawX0, y, drawX1, Math.min(y + 1.0f, endY), u0 + ((drawX0 - minX) / totalW) * (u1 - u0), v0 + ((y - minY) / totalH) * (v1 - v0), u0 + ((drawX1 - minX) / totalW) * (u1 - u0), v0 + ((Math.min(y + 1.0f, endY) - minY) / totalH) * (v1 - v0), rCol, gCol, bCol, aCol, zOffset, packedLight, packedOverlay);
        }
    }

    public static void addQuadRaw(VertexConsumer builder, Matrix4f matrix, float minX, float minY, float maxX, float maxY, float r, float g, float b, float a, float zOffset) {
        builder.addVertex(matrix, minX, maxY, zOffset).setColor(r, g, b, a);
        builder.addVertex(matrix, maxX, maxY, zOffset).setColor(r, g, b, a);
        builder.addVertex(matrix, maxX, minY, zOffset).setColor(r, g, b, a);
        builder.addVertex(matrix, minX, minY, zOffset).setColor(r, g, b, a);
    }

    public static void addTexturedQuadRaw(VertexConsumer builder, Matrix4f matrix, float minX, float minY, float maxX, float maxY, float u0, float v0, float u1, float v1, float r, float g, float b, float a, float zOffset, int packedLight, int packedOverlay) {
        builder.addVertex(matrix, minX, maxY, zOffset).setColor(r, g, b, a).setUv(u0, v1).setOverlay(packedOverlay).setLight(packedLight).setNormal(0, 0, 1);
        builder.addVertex(matrix, maxX, maxY, zOffset).setColor(r, g, b, a).setUv(u1, v1).setOverlay(packedOverlay).setLight(packedLight).setNormal(0, 0, 1);
        builder.addVertex(matrix, maxX, minY, zOffset).setColor(r, g, b, a).setUv(u1, v0).setOverlay(packedOverlay).setLight(packedLight).setNormal(0, 0, 1);
        builder.addVertex(matrix, minX, minY, zOffset).setColor(r, g, b, a).setUv(u0, v0).setOverlay(packedOverlay).setLight(packedLight).setNormal(0, 0, 1);
    }

    public static void drawRingSegment(VertexConsumer builder, Matrix4f matrix, float cx, float cy, float radius, float thickness, float startAngle, float endAngle, int color, float opacity, float zOffset) {
        float a = ((color >> 24) & 0xFF) / 255.0f * opacity;
        if (a <= 0) return;
        float r = ((color >> 16) & 0xFF) / 255.0f, g = ((color >> 8) & 0xFF) / 255.0f, b = (color & 0xFF) / 255.0f;
        int segments = 16;
        float angleStep = (endAngle - startAngle) / segments;
        for (int i = 0; i < segments; i++) {
            float theta1 = startAngle + i * angleStep, theta2 = startAngle + (i + 1) * angleStep;
            float cos1 = (float) Math.cos(theta1), sin1 = (float) Math.sin(theta1);
            float cos2 = (float) Math.cos(theta2), sin2 = (float) Math.sin(theta2);
            float x1_out = cx + cos1 * radius, y1_out = cy + sin1 * radius, x1_in = cx + cos1 * (radius - thickness), y1_in = cy + sin1 * (radius - thickness);
            float x2_out = cx + cos2 * radius, y2_out = cy + sin2 * radius, x2_in = cx + cos2 * (radius - thickness), y2_in = cy + sin2 * (radius - thickness);
            builder.addVertex(matrix, x1_out, y1_out, zOffset).setColor(r, g, b, a); builder.addVertex(matrix, x2_out, y2_out, zOffset).setColor(r, g, b, a);
            builder.addVertex(matrix, x2_in, y2_in, zOffset).setColor(r, g, b, a); builder.addVertex(matrix, x1_in, y1_in, zOffset).setColor(r, g, b, a);
            builder.addVertex(matrix, x1_out, y1_out, zOffset).setColor(r, g, b, a); builder.addVertex(matrix, x1_in, y1_in, zOffset).setColor(r, g, b, a);
            builder.addVertex(matrix, x2_in, y2_in, zOffset).setColor(r, g, b, a); builder.addVertex(matrix, x2_out, y2_out, zOffset).setColor(r, g, b, a);
        }
    }

    public interface ICustomRenderer {
        boolean render(UIElement el, PoseStack poseStack, MultiBufferSource buffer, WebAnimator.AnimatedValues anim, float zOffset, float subZOffset, boolean renderRounded);
    }
}