package com.nishiyu.enderframelib.client.renderer;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.gui.WebTextInputHost;
import com.nishiyu.enderframelib.core.dom.DomUtils;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.model.ClipRect;
import com.nishiyu.enderframelib.core.model.Corners;
import com.nishiyu.enderframelib.core.model.ElementType;
import com.nishiyu.enderframelib.core.model.ImageFitMode;
import com.nishiyu.enderframelib.core.model.UIElement;
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
import org.joml.Vector4f;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class WebRenderCore {

    public static final float RESOLUTION = 32.0f;
    public static boolean useSdfShader = true;

    // プリコンパイル済み Lua ハイライト用正規表現
    private static final Pattern LUA_KEYWORDS_PATTERN = Pattern.compile("\\b(local|function|return|if|then|else|elseif|end|for|in|do|while|repeat|until|break|and|or|not)\\b");
    private static final Pattern LUA_LITERALS_PATTERN = Pattern.compile("\\b(true|false|nil)\\b");
    private static final Pattern LUA_BUILTINS_PATTERN = Pattern.compile("\\b(turtle|system|net|ui|math|string|table|os|print|sleep)\\b");
    private static final Pattern TOOLTIP_SPLIT_PATTERN = Pattern.compile("\\\\n|\\n");

    public static final Map<String, Integer> inputScrollOffsets = new ConcurrentHashMap<>();
    public static final Map<String, Integer> textareaCursorIndex = new ConcurrentHashMap<>();
    public static final Map<String, Integer> textareaScrollRow = new ConcurrentHashMap<>();
    private static final Map<String, Entity> ENTITY_PREVIEW_CACHE = new ConcurrentHashMap<>();
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

    private static Entity resolvePreviewEntity(String target, Level level) {
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

    public static boolean enableScissorRect(Matrix4f poseMat, MultiBufferSource buffer, float minX, float minY, float maxX, float maxY) {
        try {
            Window window = Minecraft.getInstance().getWindow();
            double guiScale = window.getGuiScale();

            Vector4f p0 = new Vector4f(minX, minY, 0f, 1f).mul(poseMat);
            Vector4f p1 = new Vector4f(maxX, maxY, 0f, 1f).mul(poseMat);

            float minGuiX = Math.min(p0.x, p1.x);
            float maxGuiX = Math.max(p0.x, p1.x);
            float minGuiY = Math.min(p0.y, p1.y);
            float maxGuiY = Math.max(p0.y, p1.y);

            int sx = Math.max(0, (int) Math.floor(minGuiX * guiScale));
            int sy = Math.max(0, (int) Math.floor(window.getHeight() - maxGuiY * guiScale));
            int sw = Math.max(0, (int) Math.ceil((maxGuiX - minGuiX) * guiScale));
            int sh = Math.max(0, (int) Math.ceil((maxGuiY - minGuiY) * guiScale));

            if (sw > 0 && sh > 0) {
                if (buffer instanceof MultiBufferSource.BufferSource bs) {
                    bs.endBatch();
                }
                RenderSystem.enableScissor(sx, sy, sw, sh);
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static boolean enableClipScissorFor2D(Matrix4f poseMat, MultiBufferSource buffer, float clipMinX, float clipMinY, float clipMaxX, float clipMaxY, float maxAllowedX, float maxAllowedY, boolean is2D) {
        if (!is2D) return false;
        if (clipMinX <= 0.5f && clipMinY <= 0.5f && clipMaxX >= maxAllowedX - 0.5f && clipMaxY >= maxAllowedY - 0.5f) {
            return false;
        }
        return enableScissorRect(poseMat, buffer, clipMinX, clipMinY, clipMaxX, clipMaxY);
    }

    public static String highlightCodeLine(String raw, String syntax) {
        if (raw == null || raw.isEmpty()) return "";
        if (!"lua".equalsIgnoreCase(syntax)) return raw;
        if (raw.trim().startsWith("--")) return "\u00a78" + raw + "\u00a7r";

        int commentIdx = raw.indexOf("--");
        String codePart = commentIdx >= 0 ? raw.substring(0, commentIdx) : raw;
        String commentPart = commentIdx >= 0 ? "\u00a78" + raw.substring(commentIdx) + "\u00a7r" : "";

        codePart = LUA_KEYWORDS_PATTERN.matcher(codePart).replaceAll("\u00a7d$1\u00a7r");
        codePart = LUA_LITERALS_PATTERN.matcher(codePart).replaceAll("\u00a76$1\u00a7r");
        codePart = LUA_BUILTINS_PATTERN.matcher(codePart).replaceAll("\u00a7b$1\u00a7r");
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

        UIElement.RenderMeta meta = el.meta();
        float originX = meta != null ? meta.originX() : 0.5f;
        float originY = meta != null ? meta.originY() : 0.5f;
        float cx = el.x() + el.width() * originX;
        float cy = el.y() + el.height() * originY;

        poseStack.translate(cx + anim.tx(), cy + anim.ty(), 0);
        if (anim.rot() != 0) poseStack.mulPose(Axis.ZP.rotationDegrees(anim.rot()));
        if (anim.sx() != 1.0f || anim.sy() != 1.0f) poseStack.scale(anim.sx(), anim.sy(), 1.0f);
        poseStack.translate(-cx, -cy, 0);

        cumulativeOpacity[0] *= anim.opacity();
    }

    public static void renderElements(WebPaintPass pass) {
        List<UIElement> uiElements = pass.elements();
        int screenWidth = pass.screenWidth();
        int screenHeight = pass.screenHeight();
        PoseStack poseStack = pass.poseStack();
        MultiBufferSource buffer = pass.buffer();
        int packedLight = pass.packedLight();
        int packedOverlay = pass.packedOverlay();
        boolean renderItems = pass.renderItems();
        boolean renderText = pass.renderText();
        boolean renderRounded = pass.renderRounded();
        float zStep = pass.zStep();
        float subZOffset = pass.subZOffset();
        String hoveredId = pass.hoveredId();
        int lodColor = pass.lodColor();
        boolean isLodMode = pass.isLodMode();
        Level level = pass.level();
        boolean playAudio = pass.playAudio();
        boolean is2DAudio = pass.is2DAudio();
        String sessionId = pass.sessionId();
        BlockPos audioPos = pass.audioPos();

        float maxAllowedX = screenWidth * RESOLUTION;
        float maxAllowedY = screenHeight * RESOLUTION;
        Font defaultFont = Minecraft.getInstance().font;
        float zOffset = 0.0f;

        float effectiveZStep = (zStep == 0.0f) ? ZLayers.ELEMENT_Z_STEP : zStep;
        float effectiveSubZ = (subZOffset == 0.0f) ? ZLayers.SUB_Z_OFFSET : subZOffset;

        if (isLodMode) {
            if ((lodColor >>> 24) > 0) {
                VertexConsumer builder = buffer.getBuffer(RenderType.gui());
                float a = ((lodColor >>> 24) & 0xFF) / 255.0f;
                float r = ((lodColor >>> 16) & 0xFF) / 255.0f;
                float g = ((lodColor >>> 8) & 0xFF) / 255.0f;
                float b = (lodColor & 0xFF) / 255.0f;
                addQuadRaw(builder, poseStack.last().pose(), 0, 0, maxAllowedX, maxAllowedY, r, g, b, a, 0.0f);
            }
            return;
        }

        if (sessionId != null && !sessionId.isEmpty()) {
            WebAnimator.cleanup(sessionId, uiElements);
        }

        Map<String, UIElement> elementMap = new HashMap<>(uiElements.size());
        for (UIElement el : uiElements) elementMap.put(el.id(), el);

        UIElement hoveredTooltipEl = null;
        String resolvedTooltipText = null;
        String animSessionId = (sessionId != null && !sessionId.isEmpty()) ? sessionId : "unknown";

        for (UIElement el : uiElements) {
            if ("node".equals(el.type())) continue;

            Font font = resolveElementFont(el, defaultFont);
            zOffset += effectiveZStep;

            String baseId = ClientWebManager.getBaseNodeId(el.id());
            boolean isHovered = !hoveredId.isEmpty() && baseId.equals(hoveredId);
            boolean isActive = !activePressedId.isEmpty() && baseId.equals(ClientWebManager.getBaseNodeId(activePressedId));
            boolean isFocused = !activeInputId.isEmpty() && (el.id().equals(activeInputId) || baseId.equals(ClientWebManager.getBaseNodeId(activeInputId)));

            if (isHovered && resolvedTooltipText == null) {
                resolvedTooltipText = extractTooltipText(el);
                if (resolvedTooltipText != null) hoveredTooltipEl = el;
            }

            WebAnimator.AnimatedValues anim = WebAnimator.getValues(animSessionId, el, isHovered, isActive, isFocused);
            UIElement.RenderMeta meta = el.meta();

            float clipMinX = 0, clipMinY = 0, clipMaxX = maxAllowedX, clipMaxY = maxAllowedY;
            float cRTL = 0, cRTR = 0, cRBR = 0, cRBL = 0;
            float[] clipArr = (meta != null && meta.clipRect() != null) ? meta.clipRect().toArray() : anim.clipData;
            if (clipArr != null && clipArr.length >= 8) {
                clipMinX = clipArr[0]; clipMinY = clipArr[1]; clipMaxX = clipArr[2]; clipMaxY = clipArr[3];
                cRTL = clipArr[4]; cRTR = clipArr[5]; cRBR = clipArr[6]; cRBL = clipArr[7];
            }

            if (el.x() + el.width() < clipMinX || el.x() > clipMaxX || el.y() + el.height() < clipMinY || el.y() > clipMaxY) {
                continue;
            }

            float rTL = 0, rTR = 0, rBR = 0, rBL = 0;
            if (renderRounded) {
                float limit = Math.min(el.width(), el.height()) / 2.0f;
                if (meta != null && meta.borderRadii() != null) {
                    Corners br = meta.borderRadii();
                    rTL = Math.min(br.topLeft(), limit); rTR = Math.min(br.topRight(), limit);
                    rBR = Math.min(br.bottomRight(), limit); rBL = Math.min(br.bottomLeft(), limit);
                } else if (anim.radiusData != null) {
                    rTL = Math.min(anim.radiusData[0], limit); rTR = Math.min(anim.radiusData[1], limit);
                    rBR = Math.min(anim.radiusData[2], limit); rBL = Math.min(anim.radiusData[3], limit);
                }
            }

            poseStack.pushPose();
            try {
                float[] cumulativeOpacity = {1.0f};
                if (el.events() != null && el.events().containsKey("mc-parent")) {
                    applyTransforms(poseStack, elementMap.get(el.events().get("mc-parent")), hoveredId, animSessionId, elementMap, cumulativeOpacity);
                }

                float originX = meta != null ? meta.originX() : 0.5f;
                float originY = meta != null ? meta.originY() : 0.5f;
                float cx = el.x() + el.width() * originX;
                float cy = el.y() + el.height() * originY;

                poseStack.translate(cx + anim.tx(), cy + anim.ty(), 0);
                if (anim.rot() != 0) poseStack.mulPose(Axis.ZP.rotationDegrees(anim.rot()));
                if (anim.sx() != 1.0f || anim.sy() != 1.0f) poseStack.scale(anim.sx(), anim.sy(), 1.0f);
                poseStack.translate(-cx, -cy, 0);

                float finalOpacity = cumulativeOpacity[0] * anim.opacity();
                if (finalOpacity <= 0.005f) continue;

                if (el.type() == ElementType.CIRCLE_BORDER) {
                    if (renderCircleBorderElement(el, meta, anim, poseStack, buffer, finalOpacity, zOffset + effectiveSubZ)) {
                        continue;
                    }
                }

                if (el.type() != ElementType.RANGE) {
                    renderElementBackground(el, meta, anim, isHovered, isActive, poseStack, buffer, finalOpacity, zOffset,
                            rTL, rTR, rBR, rBL, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
                }

                ElementType type = el.type();
                float contentZ = zOffset + effectiveSubZ;

                if (type == ElementType.CANVAS) {
                    WebCanvasContext ctx = WebCanvasContext.get(animSessionId, baseId);
                    if (ctx != null) {
                        ctx.renderCommands(el, poseStack, buffer, finalOpacity, contentZ, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL, packedLight);
                    }
                } else if (type == ElementType.SCENE) {
                    WebSceneContext.getOrCreate(animSessionId, baseId).renderScene(el, poseStack, buffer, finalOpacity, contentZ);
                } else if (type == ElementType.TEXTAREA) {
                    if (renderText) {
                        renderTextareaElement(el, poseStack, buffer, font, anim, finalOpacity, contentZ, clipMinX, clipMinY, clipMaxX, clipMaxY, maxAllowedX, maxAllowedY, is2DAudio, packedLight);
                    }
                } else if (type == ElementType.RANGE) {
                    renderRangeElement(el, anim, poseStack, buffer, finalOpacity, contentZ, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
                } else if (type == ElementType.TOGGLE || type == ElementType.CHECKBOX || type == ElementType.RADIO) {
                    renderToggleElement(el, anim, poseStack, buffer, finalOpacity, contentZ, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
                } else if (type == ElementType.IMAGE || type == ElementType.VIDEO) {
                    renderImageOrMediaElement(el, meta, poseStack, buffer, font, level, animSessionId, audioPos, playAudio, is2DAudio, renderItems, renderText,
                            finalOpacity, contentZ, packedLight, packedOverlay, rTL, rTR, rBR, rBL, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
                } else if ((el.text() != null && !el.text().isEmpty()) || type == ElementType.INPUT || type == ElementType.SELECT) {
                    renderTextOrInputElement(el, meta, anim, isFocused, elementMap, animSessionId, poseStack, buffer, font, renderText, is2DAudio,
                            finalOpacity, contentZ, packedLight, clipMinX, clipMinY, clipMaxX, clipMaxY, maxAllowedX, maxAllowedY);
                }
            } finally {
                poseStack.popPose();
            }
        }

        if (renderText && hoveredTooltipEl != null && resolvedTooltipText != null && !resolvedTooltipText.isEmpty()) {
            renderTooltipOverlay(poseStack, buffer, defaultFont, hoveredTooltipEl, resolvedTooltipText, maxAllowedX, maxAllowedY, zOffset + effectiveZStep * 4 + 0.05f, packedLight);
        }

        WebSdfShader.endPass();
    }

    private static Font resolveElementFont(UIElement el, Font defaultFont) {
        if (el.events() != null && el.events().containsKey("font-family")) {
            Font customFont = WebFontManager.resolveFont(el.events().get("font-family"));
            if (customFont != null) return customFont;
        }
        return defaultFont;
    }

    private static String extractTooltipText(UIElement el) {
        if (el.events() != null && el.events().containsKey("mc-title")) {
            String t = el.events().get("mc-title");
            if (!"false".equalsIgnoreCase(t) && !t.isEmpty()) {
                return t;
            }
        } else if (el.type() == ElementType.IMAGE && el.text() != null) {
            if (el.text().startsWith("item:")) {
                try {
                    String rawItem = el.text().substring(5).split("\\*")[0];
                    Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(rawItem));
                    if (item != Items.AIR) {
                        return new ItemStack(item).getHoverName().getString();
                    }
                } catch (Exception ignored) {}
            } else if (el.text().startsWith("fluid:")) {
                try {
                    Fluid fluid = BuiltInRegistries.FLUID.get(ResourceLocation.parse(el.text().substring(6)));
                    if (fluid != Fluids.EMPTY) {
                        return fluid.getFluidType().getDescription().getString();
                    }
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private static boolean renderCircleBorderElement(UIElement el, UIElement.RenderMeta meta, WebAnimator.AnimatedValues anim,
                                                     PoseStack poseStack, MultiBufferSource buffer, float finalOpacity, float zPos) {
        UIElement.CircleBorderSpec cb = meta != null ? meta.circleBorder() : null;
        if (cb == null && anim.circleColors == null) return false;

        float outerRadius = Math.min(el.width(), el.height()) / 2.0f;
        int cTop = cb != null ? cb.colorTop() : anim.circleColors[0];
        int cRight = cb != null ? cb.colorRight() : anim.circleColors[1];
        int cBottom = cb != null ? cb.colorBottom() : anim.circleColors[2];
        int cLeft = cb != null ? cb.colorLeft() : anim.circleColors[3];
        float thick = cb != null ? cb.thickness() : anim.circleThickness;

        VertexConsumer builder = buffer.getBuffer(RenderType.gui());
        Matrix4f matrix = poseStack.last().pose();
        float cX = el.x() + el.width() / 2.0f;
        float cY = el.y() + el.height() / 2.0f;

        if (cTop != 0) drawRingSegment(builder, matrix, cX, cY, outerRadius, thick, (float) (-Math.PI * 0.75), (float) (-Math.PI * 0.25), cTop, finalOpacity, zPos);
        if (cRight != 0) drawRingSegment(builder, matrix, cX, cY, outerRadius, thick, (float) (-Math.PI * 0.25), (float) (Math.PI * 0.25), cRight, finalOpacity, zPos);
        if (cBottom != 0) drawRingSegment(builder, matrix, cX, cY, outerRadius, thick, (float) (Math.PI * 0.25), (float) (Math.PI * 0.75), cBottom, finalOpacity, zPos);
        if (cLeft != 0) drawRingSegment(builder, matrix, cX, cY, outerRadius, thick, (float) (Math.PI * 0.75), (float) (Math.PI * 1.25), cLeft, finalOpacity, zPos);
        return true;
    }

    private static void renderElementBackground(UIElement el, UIElement.RenderMeta meta, WebAnimator.AnimatedValues anim,
                                                boolean isHovered, boolean isActive, PoseStack poseStack, MultiBufferSource buffer,
                                                float finalOpacity, float zOffset, float rTL, float rTR, float rBR, float rBL,
                                                float clipMinX, float clipMinY, float clipMaxX, float clipMaxY,
                                                float cRTL, float cRTR, float cRBR, float cRBL) {
        UIElement.GradientSpec grad = meta != null ? meta.gradient() : null;
        if (grad != null && !isHovered && !isActive) {
            VertexConsumer builder = buffer.getBuffer(RenderType.gui());
            drawGradientRoundedRect(
                    builder, poseStack.last().pose(),
                    el.x(), el.y(), el.x() + el.width(), el.y() + el.height(),
                    rTL, rTR, rBR, rBL,
                    grad.direction(), grad.startColor(), grad.endColor(), finalOpacity, zOffset,
                    clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL
            );
        } else {
            float bgA = ((anim.bgColor() >>> 24) & 0xFF) / 255.0f * finalOpacity;
            if (bgA > 0) {
                VertexConsumer builder = buffer.getBuffer(RenderType.gui());
                float r = ((anim.bgColor() >>> 16) & 0xFF) / 255.0f;
                float g = ((anim.bgColor() >>> 8) & 0xFF) / 255.0f;
                float b = (anim.bgColor() & 0xFF) / 255.0f;
                if (rTL > 0 || rTR > 0 || rBR > 0 || rBL > 0) {
                    drawRoundedRect(builder, poseStack.last().pose(), el.x(), el.y(), el.x() + el.width(), el.y() + el.height(),
                            rTL, rTR, rBR, rBL, r, g, b, bgA, zOffset, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
                } else {
                    drawClippedQuad(builder, poseStack.last().pose(), el.x(), el.y(), el.x() + el.width(), el.y() + el.height(),
                            r, g, b, bgA, zOffset, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
                }
            }
        }
    }

    private static void renderProgressElement(UIElement el, WebAnimator.AnimatedValues anim, PoseStack poseStack, MultiBufferSource buffer,
                                              float finalOpacity, float zPos, float rTL, float rTR, float rBR, float rBL,
                                              float clipMinX, float clipMinY, float clipMaxX, float clipMaxY,
                                              float cRTL, float cRTR, float cRBR, float cRBL) {
        float ratio = anim.rangeMax > anim.rangeMin ? Math.clamp((anim.rangeVal - anim.rangeMin) / (anim.rangeMax - anim.rangeMin), 0f, 1f) : 0f;
        float fillW = el.width() * ratio;
        if (fillW > 0.5f) {
            int pC = anim.color() != 0 ? anim.color() : 0xFF28A745;
            float prA = ((pC >>> 24) & 0xFF) / 255.0f * finalOpacity;
            float prR = ((pC >>> 16) & 0xFF) / 255.0f;
            float prG = ((pC >>> 8) & 0xFF) / 255.0f;
            float prB = (pC & 0xFF) / 255.0f;
            VertexConsumer builder = buffer.getBuffer(RenderType.gui());
            drawRoundedRect(builder, poseStack.last().pose(), el.x(), el.y(), el.x() + fillW, el.y() + el.height(),
                    rTL, ratio > 0.98f ? rTR : 0, ratio > 0.98f ? rBR : 0, rBL,
                    prR, prG, prB, prA, zPos, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
        }
    }

    private static void renderRangeElement(UIElement el, WebAnimator.AnimatedValues anim, PoseStack poseStack, MultiBufferSource buffer,
                                           float finalOpacity, float zPos,
                                           float clipMinX, float clipMinY, float clipMaxX, float clipMaxY,
                                           float cRTL, float cRTR, float cRBR, float cRBL) {
        float rVal = anim.rangeVal, rMin = anim.rangeMin, rMax = anim.rangeMax;
        if (el.text() != null && el.text().contains(":")) {
            String[] sp = el.text().split(":");
            if (sp.length >= 3) {
                try {
                    rVal = Float.parseFloat(sp[0]);
                    rMin = Float.parseFloat(sp[1]);
                    rMax = Float.parseFloat(sp[2]);
                } catch (NumberFormatException ignored) {}
            }
        }
        float ratio = rMax > rMin ? Math.clamp((rVal - rMin) / (rMax - rMin), 0f, 1f) : 0f;
        VertexConsumer builder = buffer.getBuffer(RenderType.gui());
        Matrix4f mat = poseStack.last().pose();

        float trackH = Math.max(4.0f, el.height() * 0.3f);
        float trackY = el.y() + (el.height() - trackH) / 2.0f;
        float trackRad = trackH / 2.0f;
        float thumbRad = Math.max(10.0f, el.height() * 0.8f) / 2.0f;
        float thumbCY = el.y() + el.height() / 2.0f;
        float tMinX = el.x() + thumbRad;
        float tMaxX = el.x() + el.width() - thumbRad;
        float tCX = tMinX + ratio * (tMaxX - tMinX);

        int pC = anim.color() != 0 ? anim.color() : 0xFF007BFF;
        float prA = ((pC >>> 24) & 0xFF) / 255.0f * finalOpacity;
        float prR = ((pC >>> 16) & 0xFF) / 255.0f;
        float prG = ((pC >>> 8) & 0xFF) / 255.0f;
        float prB = (pC & 0xFF) / 255.0f;

        if (tCX > el.x()) {
            drawRoundedRect(builder, mat, el.x(), trackY, tCX, trackY + trackH, trackRad, 0, 0, trackRad,
                    prR, prG, prB, prA, zPos, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
        }

        int rC = anim.bgColor() != 0 ? anim.bgColor() : 0xFFFFFFFF;
        float reA = ((rC >>> 24) & 0xFF) / 255.0f * finalOpacity;
        float reR = ((rC >>> 16) & 0xFF) / 255.0f;
        float reG = ((rC >>> 8) & 0xFF) / 255.0f;
        float reB = (rC & 0xFF) / 255.0f;

        if (tCX < el.x() + el.width()) {
            drawRoundedRect(builder, mat, tCX, trackY, el.x() + el.width(), trackY + trackH, 0, trackRad, trackRad, 0,
                    reR, reG, reB, reA, zPos, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
        }
        drawRoundedRect(builder, mat, tCX - thumbRad, thumbCY - thumbRad, tCX + thumbRad, thumbCY + thumbRad,
                thumbRad, thumbRad, thumbRad, thumbRad, prR, prG, prB, prA, zPos + ZLayers.SUB_Z_OFFSET,
                clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
    }

    private static void renderToggleElement(UIElement el, WebAnimator.AnimatedValues anim, PoseStack poseStack, MultiBufferSource buffer,
                                            float finalOpacity, float zPos,
                                            float clipMinX, float clipMinY, float clipMaxX, float clipMaxY,
                                            float cRTL, float cRTR, float cRBR, float cRBL) {
        if (!"true".equals(el.text())) return;
        float a = ((anim.color() >>> 24) & 0xFF) / 255.0f * finalOpacity;
        if (a <= 0.0f) return;

        float pad = el.width() * 0.25f;
        float r = ((anim.color() >>> 16) & 0xFF) / 255.0f;
        float g = ((anim.color() >>> 8) & 0xFF) / 255.0f;
        float b = (anim.color() & 0xFF) / 255.0f;
        drawClippedQuad(buffer.getBuffer(RenderType.gui()), poseStack.last().pose(),
                el.x() + pad, el.y() + pad, el.x() + el.width() - pad, el.y() + el.height() - pad,
                r, g, b, a, zPos, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
    }

    private static void renderImageOrMediaElement(
            UIElement el, UIElement.RenderMeta meta, PoseStack poseStack, MultiBufferSource buffer, Font font,
            Level level, String animSessionId, BlockPos audioPos, boolean playAudio, boolean is2DAudio,
            boolean renderItems, boolean renderText, float finalOpacity, float zPos, int packedLight, int packedOverlay,
            float rTL, float rTR, float rBR, float rBL,
            float clipMinX, float clipMinY, float clipMaxX, float clipMaxY,
            float cRTL, float cRTR, float cRBR, float cRBL
    ) {
        if ("video".equals(el.type())) {
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

        if (src.startsWith("https://") || src.startsWith("http://")) {
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

    private static void renderExternalWebImage(
            UIElement el, WebMediaManager.ImageInfo info, PoseStack poseStack, MultiBufferSource buffer,
            float finalOpacity, float zPos, int packedLight, int packedOverlay,
            float rTL, float rTR, float rBR, float rBL,
            float clipMinX, float clipMinY, float clipMaxX, float clipMaxY,
            float cRTL, float cRTR, float cRBR, float cRBL
    ) {
        VertexConsumer builder = buffer.getBuffer(RenderType.entityTranslucent(info.textureId()));
        int imgW = info.width(), imgH = info.height();
        float u0 = 0.0f, v0 = 0.0f, u1 = 1.0f, v1 = 1.0f;
        float drawMinX = el.x(), drawMinY = el.y(), drawMaxX = el.x() + el.width(), drawMaxY = el.y() + el.height();
        ImageFitMode fit = el.fitMode() != null ? el.fitMode() : ImageFitMode.FILL;
        float boxW = drawMaxX - drawMinX, boxH = drawMaxY - drawMinY;

        if (imgW > 0 && imgH > 0 && boxW > 0 && boxH > 0) {
            float boxAspect = boxW / boxH, imgAspect = (float) imgW / imgH;
            if (fit == ImageFitMode.CONTAIN) {
                if (imgAspect > boxAspect) {
                    float diff = (boxH - (boxW / imgAspect)) / 2.0f;
                    drawMinY += diff; drawMaxY -= diff;
                } else {
                    float diff = (boxW - (boxH * imgAspect)) / 2.0f;
                    drawMinX += diff; drawMaxX -= diff;
                }
            } else if (fit == ImageFitMode.COVER) {
                if (imgAspect > boxAspect) {
                    float crop = (imgW - (imgH * boxAspect)) / (2.0f * imgW);
                    u0 = crop; u1 = 1.0f - crop;
                } else {
                    float crop = (imgH - (imgW / boxAspect)) / (2.0f * imgH);
                    v0 = crop; v1 = 1.0f - crop;
                }
            }
        }
        drawRoundedTexturedRect(builder, poseStack.last().pose(), drawMinX, drawMinY, drawMaxX, drawMaxY,
                u0, v0, u1, v1, rTL, rTR, rBR, rBL, 1f, 1f, 1f, finalOpacity, zPos, packedLight, packedOverlay,
                clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
    }

    private static void renderItemElement(UIElement el, UIElement.RenderMeta meta, String rawSpec,
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

    private static boolean shouldSkipPlaceholder(UIElement el, Map<String, UIElement> elementMap, String animSessionId) {
        if (!el.id().endsWith("_ph")) return false;
        String inputNodeId = el.id().substring(0, el.id().length() - 3);
        UIElement inputEl = elementMap.get(inputNodeId + "_input");
        if (inputEl != null && inputEl.text() != null && !inputEl.text().isEmpty()) {
            return true;
        }
        if (!activeInputId.isEmpty() && ClientWebManager.getBaseNodeId(activeInputId).equals(inputNodeId)) {
            String activeVal = WebTextInputHost.getActiveText();
            if (activeVal != null && !activeVal.isEmpty()) return true;
        }
        HtmlNode vRoot = ClientWebManager.getVirtualRoot(animSessionId);
        if (vRoot != null) {
            HtmlNode domNode = DomUtils.findNodeById(vRoot, inputNodeId);
            return domNode != null && !domNode.attrs.getOrDefault("value", "").isEmpty();
        }
        return false;
    }

    private static void renderTextOrInputElement(
            UIElement el, UIElement.RenderMeta meta, WebAnimator.AnimatedValues anim, boolean isFocused,
            Map<String, UIElement> elementMap, String animSessionId,
            PoseStack poseStack, MultiBufferSource buffer, Font font, boolean renderText, boolean is2DAudio,
            float finalOpacity, float zPos, int packedLight,
            float clipMinX, float clipMinY, float clipMaxX, float clipMaxY, float maxAllowedX, float maxAllowedY
    ) {
        if (shouldSkipPlaceholder(el, elementMap, animSessionId)) return;

        String textToDraw = el.text() == null ? "" : el.text();
        float textX = el.x(), textY = el.y();

        int safeCursorPos = -1;
        if ("input".equals(el.type()) && isFocused) {
            String activeVal = WebTextInputHost.getActiveText();
            if (activeVal != null) {
                textToDraw = activeVal;
                safeCursorPos = WebTextInputHost.getActiveCursorPosition();
            }
        }

        if (textY >= clipMaxY || textY + el.height() <= clipMinY) return;

        float fontH = meta != null ? meta.fontHeight() : el.height();
        float textScale = fontH / 12.0f;
        Matrix4f currentPose = poseStack.last().pose();
        float matScale = Math.abs(currentPose.m00());

        if ("input".equals(el.type()) || "select".equals(el.type())) {
            float maxAllowedScale = Math.max(0.75f, (el.height() - 4.0f) / 10.0f);
            textScale = Math.min(textScale, maxAllowedScale);
            textScale = snapTextScaleFor2D(currentPose, textScale, is2DAudio);
            textX += 4.0f;
            textY += (el.height() - (8.0f * textScale)) / 2.0f;
        } else {
            textScale = snapTextScaleFor2D(currentPose, el.height() / 12.0f, is2DAudio);
        }

        if (!"input".equals(el.type()) && textToDraw.isEmpty()) return;
        if (!renderText || textX >= maxAllowedX || textY >= maxAllowedY) return;

        int newA = (int) (((anim.color() >>> 24) & 0xFF) * finalOpacity);
        if (newA <= 0) return;

        boolean useShadow = el.events() != null && "true".equals(el.events().get("text-shadow"));
        String textDeco = el.events() != null ? el.events().getOrDefault("text-decoration", "none") : "none";
        int argbColor = (newA << 24) | (anim.color() & 0x00FFFFFF);

        boolean textScissor = enableClipScissorFor2D(currentPose, buffer, clipMinX, clipMinY, clipMaxX, clipMaxY, maxAllowedX, maxAllowedY, is2DAudio);
        poseStack.pushPose();
        try {
            if ("input".equals(el.type()) || "select".equals(el.type())) {
                poseStack.translate(snapCoordFor2D(textX, matScale, is2DAudio), snapCoordFor2D(textY, matScale, is2DAudio), zPos);
                poseStack.scale(textScale, textScale, 1.0f);

                int scrollOffset = inputScrollOffsets.getOrDefault(el.id(), 0);
                float maxAvailableWidth = (clipMaxX - textX) / textScale;
                float availableWidth = Math.max(0, Math.min((el.width() - 8.0f) / textScale, maxAvailableWidth));

                if (safeCursorPos >= 0) {
                    safeCursorPos = Math.min(textToDraw.length(), safeCursorPos);
                    if (safeCursorPos < scrollOffset) scrollOffset = safeCursorPos;
                    while (scrollOffset < safeCursorPos && font.width(textToDraw.substring(scrollOffset, safeCursorPos)) > availableWidth) {
                        scrollOffset++;
                    }
                    inputScrollOffsets.put(el.id(), scrollOffset);
                } else if (scrollOffset > textToDraw.length()) {
                    scrollOffset = textToDraw.length();
                }

                String visibleText = font.plainSubstrByWidth(textToDraw.substring(scrollOffset), (int) availableWidth);
                if (!visibleText.isEmpty()) {
                    font.drawInBatch(visibleText, 0, 0, argbColor, useShadow, poseStack.last().pose(), buffer, Font.DisplayMode.NORMAL, 0, packedLight);
                }

                if (safeCursorPos >= 0 && safeCursorPos >= scrollOffset && (safeCursorPos - scrollOffset) <= visibleText.length()) {
                    if ((System.currentTimeMillis() / 500) % 2 == 0) {
                        float cursorX = font.width(visibleText.substring(0, safeCursorPos - scrollOffset));
                        float r = ((anim.color() >>> 16) & 0xFF) / 255.0f;
                        float g = ((anim.color() >>> 8) & 0xFF) / 255.0f;
                        float b = (anim.color() & 0xFF) / 255.0f;
                        addQuadRaw(buffer.getBuffer(RenderType.gui()), poseStack.last().pose(), cursorX, -1.0f, cursorX + 1.5f, 9.0f, r, g, b, newA / 255.0f, ZLayers.SUB_Z_OFFSET);
                    }
                }
            } else if (el.id().endsWith("_ph")) {
                float phY = textY + (el.height() - (8.0f * textScale)) / 2.0f;
                poseStack.translate(snapCoordFor2D(textX, matScale, is2DAudio), snapCoordFor2D(phY, matScale, is2DAudio), zPos);
                poseStack.scale(textScale, textScale, 1.0f);
                float availableWidth = Math.max(0, Math.min((el.width() - 8.0f) / textScale, (clipMaxX - textX) / textScale));
                String visibleText = font.plainSubstrByWidth(textToDraw, (int) availableWidth);
                if (!visibleText.isEmpty()) {
                    font.drawInBatch(visibleText, 0, 0, argbColor, useShadow, poseStack.last().pose(), buffer, Font.DisplayMode.NORMAL, 0, packedLight);
                }
            } else if (!textToDraw.isEmpty()) {
                float availableWidth = Math.max(0, Math.min(el.width() / textScale, (clipMaxX - textX) / textScale));
                String visibleText = textToDraw;
                if (el.width() > 0 && font.width(textToDraw) > availableWidth * 1.3f + 4) {
                    int ellipsisW = font.width("...");
                    visibleText = availableWidth > ellipsisW
                            ? font.plainSubstrByWidth(textToDraw, (int) availableWidth - ellipsisW) + "..."
                            : font.plainSubstrByWidth(textToDraw, (int) availableWidth);
                }
                float rawTextW = font.width(visibleText);
                float alignOffsetX = 0;
                float actualTextWidth = rawTextW * textScale;
                if ("center".equals(anim.textAlign)) alignOffsetX = Math.max(0, (el.width() - actualTextWidth) / 2.0f);
                else if ("right".equals(anim.textAlign)) alignOffsetX = Math.max(0, el.width() - actualTextWidth);

                float drawX = snapCoordFor2D(textX + alignOffsetX, matScale, is2DAudio);
                float drawY = snapCoordFor2D(textY + ((el.height() - (8.0f * textScale)) / 2.0f), matScale, is2DAudio);

                poseStack.translate(drawX, drawY, zPos);
                poseStack.scale(textScale, textScale, 1.0f);
                font.drawInBatch(visibleText, 0, 0, argbColor, useShadow, poseStack.last().pose(), buffer, Font.DisplayMode.NORMAL, 0, packedLight);

                if (textDeco.contains("underline") || textDeco.contains("line-through")) {
                    float r = ((anim.color() >>> 16) & 0xFF) / 255.0f;
                    float g = ((anim.color() >>> 8) & 0xFF) / 255.0f;
                    float b = (anim.color() & 0xFF) / 255.0f;
                    VertexConsumer lineBuilder = buffer.getBuffer(RenderType.gui());
                    if (textDeco.contains("underline")) {
                        addQuadRaw(lineBuilder, poseStack.last().pose(), 0, 8.5f, rawTextW, 9.5f, r, g, b, newA / 255.0f, ZLayers.SUB_Z_OFFSET);
                    }
                    if (textDeco.contains("line-through")) {
                        addQuadRaw(lineBuilder, poseStack.last().pose(), 0, 4.0f, rawTextW, 5.0f, r, g, b, newA / 255.0f, ZLayers.SUB_Z_OFFSET);
                    }
                }
            }
            if (textScissor && buffer instanceof MultiBufferSource.BufferSource bs) {
                bs.endBatch();
            }
        } finally {
            poseStack.popPose();
            if (textScissor) {
                RenderSystem.disableScissor();
            }
        }
    }

    private static void renderTooltipOverlay(PoseStack poseStack, MultiBufferSource buffer, Font font, UIElement target, String tooltipText, float maxW, float maxH, float zPos, int packedLight) {
        String[] lines = TOOLTIP_SPLIT_PATTERN.split(tooltipText);
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
            addQuadRaw(builder, mat, tipX, tipY, tipX + boxW, tipY + boxH, 0.06f, 0.0f, 0.06f, 0.94f, zPos + ZLayers.ELEMENT_Z_STEP);

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
        RoundedRectRasterizer.addGradientQuadRaw(builder, matrix, minX, minY, maxX, maxY, dir, c1, c2, opacity, zOffset);
    }

    public static void drawGradientRoundedRect(
            VertexConsumer builder, Matrix4f matrix,
            float minX, float minY, float maxX, float maxY,
            float rTL, float rTR, float rBR, float rBL,
            String dir, int c1, int c2, float opacity, float zOffset,
            float cx0, float cy0, float cx1, float cy1,
            float cRTL, float cRTR, float cRBR, float cRBL
    ) {
        RoundedRectRasterizer.drawGradientRoundedRect(builder, matrix, minX, minY, maxX, maxY, rTL, rTR, rBR, rBL, dir, c1, c2, opacity, zOffset, cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL, useSdfShader);
    }

    public static void drawRoundedRect(VertexConsumer builder, Matrix4f matrix, float minX, float minY, float maxX, float maxY, float rTL, float rTR, float rBR, float rBL, float r, float g, float b, float a, float zOffset, float cx0, float cy0, float cx1, float cy1, float cRTL, float cRTR, float cRBR, float cRBL) {
        RoundedRectRasterizer.drawRoundedRect(builder, matrix, minX, minY, maxX, maxY, rTL, rTR, rBR, rBL, r, g, b, a, zOffset, cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL, useSdfShader);
    }

    public static void drawClippedQuad(VertexConsumer builder, Matrix4f matrix, float minX, float minY, float maxX, float maxY, float rCol, float gCol, float bCol, float aCol, float zOffset, float cx0, float cy0, float cx1, float cy1, float cRTL, float cRTR, float cRBR, float cRBL) {
        RoundedRectRasterizer.drawClippedQuad(builder, matrix, minX, minY, maxX, maxY, rCol, gCol, bCol, aCol, zOffset, cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL, useSdfShader);
    }

    public static void drawRoundedTexturedRect(VertexConsumer builder, Matrix4f matrix, float minX, float minY, float maxX, float maxY, float u0, float v0, float u1, float v1, float rTL, float rTR, float rBR, float rBL, float r, float g, float b, float a, float zOffset, int packedLight, int packedOverlay, float cx0, float cy0, float cx1, float cy1, float cRTL, float cRTR, float cRBR, float cRBL) {
        RoundedRectRasterizer.drawRoundedTexturedRect(builder, matrix, minX, minY, maxX, maxY, u0, v0, u1, v1, rTL, rTR, rBR, rBL, r, g, b, a, zOffset, packedLight, packedOverlay, cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL);
    }

    public static void drawClippedTexturedQuad(VertexConsumer builder, Matrix4f matrix, float minX, float minY, float maxX, float maxY, float u0, float v0, float u1, float v1, float rCol, float gCol, float bCol, float aCol, float zOffset, int packedLight, int packedOverlay, float cx0, float cy0, float cx1, float cy1, float cRTL, float cRTR, float cRBR, float cRBL) {
        RoundedRectRasterizer.drawClippedTexturedQuad(builder, matrix, minX, minY, maxX, maxY, u0, v0, u1, v1, rCol, gCol, bCol, aCol, zOffset, packedLight, packedOverlay, cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL);
    }

    public static void addQuadRaw(VertexConsumer builder, Matrix4f matrix, float minX, float minY, float maxX, float maxY, float r, float g, float b, float a, float zOffset) {
        RoundedRectRasterizer.addQuadRaw(builder, matrix, minX, minY, maxX, maxY, r, g, b, a, zOffset);
    }

    public static void addTexturedQuadRaw(VertexConsumer builder, Matrix4f matrix, float minX, float minY, float maxX, float maxY, float u0, float v0, float u1, float v1, float r, float g, float b, float a, float zOffset, int packedLight, int packedOverlay) {
        RoundedRectRasterizer.addTexturedQuadRaw(builder, matrix, minX, minY, maxX, maxY, u0, v0, u1, v1, r, g, b, a, zOffset, packedLight, packedOverlay);
    }

    public static void drawRingSegment(VertexConsumer builder, Matrix4f matrix, float cx, float cy, float radius, float thickness, float startAngle, float endAngle, int color, float opacity, float zOffset) {
        RoundedRectRasterizer.drawRingSegment(builder, matrix, cx, cy, radius, thickness, startAngle, endAngle, color, opacity, zOffset);
    }

    private static void renderFluidElement(
            UIElement el, String fluidId, PoseStack poseStack, MultiBufferSource buffer,
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

    private static void renderEntityElement(
            UIElement el, String entitySpec, PoseStack poseStack, MultiBufferSource buffer,
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

    private static void renderTextareaElement(
            UIElement el, PoseStack poseStack, MultiBufferSource buffer, Font font,
            WebAnimator.AnimatedValues anim, float finalOpacity, float zPos,
            float clipMinX, float clipMinY, float clipMaxX, float clipMaxY,
            float maxAllowedX, float maxAllowedY, boolean is2D, int packedLight
    ) {
        String fullText = el.text() != null ? el.text() : "";
        String[] lines = fullText.replace("\r\n", "\n").split("\n", -1);

        UIElement.RenderMeta meta = el.meta();
        float fontH = meta != null ? meta.fontHeight() : 12.0f;
        Matrix4f mat = poseStack.last().pose();
        float matScale = Math.abs(mat.m00());
        float textScale = snapTextScaleFor2D(mat, fontH / 12.0f, is2D);
        float lineHeight = Math.max(12.0f * textScale, 11.0f);

        String syntax = el.events() != null ? el.events().getOrDefault("data-syntax", "") : "";
        boolean showLineNums = "lua".equalsIgnoreCase(syntax) || (el.events() != null && "true".equalsIgnoreCase(el.events().get("data-line-numbers")));
        float gutterW = showLineNums ? 28.0f * textScale : 4.0f;

        boolean isFocused = el.id().equals(activeInputId);
        int cursorIdx = Math.clamp(textareaCursorIndex.getOrDefault(el.id(), fullText.length()), 0, fullText.length());

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

        float taClipMinX = Math.max(clipMinX, el.x());
        float taClipMinY = Math.max(clipMinY, el.y());
        float taClipMaxX = Math.min(clipMaxX, el.x() + el.width());
        float taClipMaxY = Math.min(clipMaxY, el.y() + el.height());

        boolean taScissor = enableClipScissorFor2D(mat, buffer, taClipMinX, taClipMinY, taClipMaxX, taClipMaxY, maxAllowedX, maxAllowedY, is2D);
        try {
            VertexConsumer quadBuilder = buffer.getBuffer(RenderType.gui());
            if (showLineNums) {
                addQuadRaw(quadBuilder, mat, el.x(), el.y(), el.x() + gutterW - 3.0f, el.y() + el.height(), 0.05f, 0.05f, 0.09f, 0.45f * finalOpacity, zPos);
            }
            if (isFocused && cursorRow >= scrollRow && cursorRow < scrollRow + maxVisibleRows) {
                float activeRowY = el.y() + 4.0f + (cursorRow - scrollRow) * lineHeight;
                if (activeRowY + lineHeight >= taClipMinY && activeRowY <= taClipMaxY) {
                    addQuadRaw(quadBuilder, mat, el.x() + gutterW - 2.0f, activeRowY - 1.0f, el.x() + el.width() - 2.0f, activeRowY + lineHeight - 1.0f, 0.35f, 0.45f, 0.75f, 0.18f * finalOpacity, zPos + 0.0002f);
                }
            }

            int newA = (int) (((anim.color() >>> 24) & 0xFF) * finalOpacity);
            if (newA <= 0) return;
            int textColor = (newA << 24) | (anim.color() & 0x00FFFFFF);
            int gutterColor = (newA << 24) | 0x585B70;
            float availW = Math.max(10.0f, (el.width() - gutterW - 6.0f) / textScale);

            for (int i = 0; i < maxVisibleRows; i++) {
                int rowIdx = scrollRow + i;
                if (rowIdx >= lines.length) break;

                float rowY = el.y() + 4.0f + i * lineHeight;
                if (rowY + lineHeight < taClipMinY || rowY > taClipMaxY) continue;

                poseStack.pushPose();
                try {
                    float snappedY = snapCoordFor2D(rowY, matScale, is2D);
                    if (showLineNums) {
                        poseStack.pushPose();
                        poseStack.translate(snapCoordFor2D(el.x() + 4.0f, matScale, is2D), snappedY, zPos + 0.0004f);
                        poseStack.scale(textScale, textScale, 1.0f);
                        font.drawInBatch(String.valueOf(rowIdx + 1), 0, 0, gutterColor, false, poseStack.last().pose(), buffer, Font.DisplayMode.NORMAL, 0, packedLight);
                        poseStack.popPose();
                    }

                    String rawLine = lines[rowIdx];
                    String visibleRaw = font.plainSubstrByWidth(rawLine, (int) availW);
                    String colored = highlightCodeLine(visibleRaw, syntax);

                    poseStack.translate(snapCoordFor2D(el.x() + gutterW + 2.0f, matScale, is2D), snappedY, zPos + 0.0004f);
                    poseStack.scale(textScale, textScale, 1.0f);
                    if (!colored.isEmpty()) {
                        font.drawInBatch(colored, 0, 0, textColor, false, poseStack.last().pose(), buffer, Font.DisplayMode.NORMAL, 0, packedLight);
                    }

                    if (isFocused && rowIdx == cursorRow && (System.currentTimeMillis() / 500) % 2 == 0) {
                        int safeCol = Math.clamp(cursorCol, 0, visibleRaw.length());
                        float curX = font.width(visibleRaw.substring(0, safeCol));
                        addQuadRaw(buffer.getBuffer(RenderType.gui()), poseStack.last().pose(), curX, -1.0f, curX + 1.5f, 9.0f, 0.54f, 0.71f, 0.98f, finalOpacity, 0.0006f);
                    }
                } finally {
                    poseStack.popPose();
                }
            }
            if (taScissor && buffer instanceof MultiBufferSource.BufferSource bs) {
                bs.endBatch();
            }
        } finally {
            if (taScissor) {
                RenderSystem.disableScissor();
            }
        }
    }
}