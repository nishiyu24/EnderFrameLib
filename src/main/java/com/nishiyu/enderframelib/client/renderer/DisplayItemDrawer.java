package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.client.renderer.WebFontManager.McFontResolver;
import com.nishiyu.enderframelib.core.paint.model.RenderKeys;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.SessionContext;
import com.nishiyu.enderframelib.client.gui.TextareaEditor.TextEditState;
import com.nishiyu.enderframelib.client.gui.WebTextInputHost;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.paint.model.Corners;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.ImageFitMode;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.TextShaper;
import com.nishiyu.enderframelib.core.text.model.BitmapFontFace;
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
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;
import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontStyle;
import com.nishiyu.enderframelib.core.text.font.OutlineFontFace;

public class DisplayItemDrawer {

    public static final float RESOLUTION = 32.0f;
    public static boolean useSdfShader = true;

    // プリコンパイル済み Lua ハイライト用正規表現
    private static final Pattern LUA_KEYWORDS_PATTERN = Pattern.compile("\\b(local|function|return|if|then|else|elseif|end|for|in|do|while|repeat|until|break|and|or|not)\\b");
    private static final Pattern LUA_LITERALS_PATTERN = Pattern.compile("\\b(true|false|nil)\\b");
    private static final Pattern LUA_BUILTINS_PATTERN = Pattern.compile("\\b(turtle|system|net|ui|math|string|table|os|print|sleep)\\b");
    private static final Pattern TOOLTIP_SPLIT_PATTERN = Pattern.compile("\\\\n|\\n");

    // キャレット位置と入力欄のスクロールは TextEditState が唯一の出所。
    // 以前ここに同名の static マップが 3 本あり、編集側の書き込みが描画に届いていなかった。
    private static final Map<String, Entity> ENTITY_PREVIEW_CACHE = new ConcurrentHashMap<>();
    // フォーカス / 押下状態は SessionContext が所有する（ClientWebManager 経由で参照）。
    // かつてここに public static String で置かれていたため、GUI 画面とブロック画面が
    // フォーカスを共有してしまっていた。

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

    // 以前ここに snapTextScaleFor2D があり、フォントの倍率を物理ピクセルの 0.5 刻みに
    // 丸めていた。倍率を変えると送り幅も変わるため、測定が決めた折り返し位置・省略位置と
    // 描画が食い違う原因になっていた。スナップするのは位置だけ（snapCoordFor2D）。

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

    /** 祖先の変形を掛け終えた姿勢と、祖先の不透明度の積（1 フレームの間だけ使い回す）。 */
    private record AncestorTransform(Matrix4f pose, Matrix3f normal, float opacity) {}

    /**
     * {@code id} の要素まで（その要素を含む）祖先の変形を掛けた姿勢を返す。結果は {@code memo} に覚え、
     * 同じ祖先を持つ兄弟・子孫では掛け直さない（以前は描画要素ごとに祖先の連鎖を根から辿り直していた）。
     * 呼ぶときの {@code poseStack} の先頭はフレームの基準の姿勢であること。要素が無ければ null。
     */
    private static AncestorTransform resolveAncestorTransform(PoseStack poseStack, String id, String hoveredId, String sessionId,
                                                              Map<String, DisplayItem> elementMap,
                                                              Map<String, AncestorTransform> memo) {
        AncestorTransform cached = memo.get(id);
        if (cached != null) return cached;
        DisplayItem el = elementMap.get(id);
        if (el == null) return null;

        poseStack.pushPose();
        try {
            float[] cumulativeOpacity = {1.0f};
            String parentId = el.events() != null ? el.events().get(RenderKeys.MC_PARENT) : null;
            if (parentId != null) {
                AncestorTransform parent = resolveAncestorTransform(poseStack, parentId, hoveredId, sessionId, elementMap, memo);
                if (parent != null) {
                    poseStack.last().pose().set(parent.pose());
                    poseStack.last().normal().set(parent.normal());
                    cumulativeOpacity[0] = parent.opacity();
                }
            }
            applyOwnTransform(poseStack, el, hoveredId, sessionId, cumulativeOpacity);
            AncestorTransform result = new AncestorTransform(new Matrix4f(poseStack.last().pose()),
                    new Matrix3f(poseStack.last().normal()), cumulativeOpacity[0]);
            memo.put(id, result);
            return result;
        } finally {
            poseStack.popPose();
        }
    }

    private static void applyOwnTransform(PoseStack poseStack, DisplayItem el, String hoveredId, String sessionId, float[] cumulativeOpacity) {
        String baseId = ClientWebManager.getBaseNodeId(el.id());
        boolean isHovered = baseId.equals(hoveredId);
        SessionContext session = ClientWebManager.getSession(sessionId);
        boolean isActive = session != null && session.isPressedBase(baseId);
        boolean isFocused = session != null && session.isFocusedBase(baseId);
        CssAnimator.AnimatedValues anim = CssAnimator.getValues(sessionId, el, isHovered, isActive, isFocused);

        RenderMeta meta = el.meta();
        float originX = meta != null ? meta.originX() : 0.5f;
        float originY = meta != null ? meta.originY() : 0.5f;
        float cx = el.x() + el.width() * originX;
        float cy = el.y() + el.height() * originY;

        poseStack.translate(cx + anim.tx(), cy + anim.ty(), 0);
        if (anim.rot() != 0) poseStack.mulPose(Axis.ZP.rotationDegrees(anim.rot()));
        if (anim.sx() != 1.0f || anim.sy() != 1.0f) poseStack.scale(anim.sx(), anim.sy(), 1.0f);
        poseStack.translate(-cx, -cy, 0);

        // LIMITATION(制約): opacity はグループとして合成せず、部品ごとに祖先の不透明度を掛けて描く。
        // 半透明の箱の中で重なった子孫が互いに透けて見える（ブラウザは中身を 1 枚に描いてから透かす）。
        // 正しくするには描画先を別のフレームバッファに切り替える必要がある。README の "Not supported" に対応。
        cumulativeOpacity[0] *= anim.opacity();
    }

    public static void renderElements(PaintRequest pass) {
        List<DisplayItem> uiElements = pass.elements();
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
        boolean is2DText = pass.screenSpaceText();
        String sessionId = pass.sessionId();
        BlockPos audioPos = pass.audioPos();

        float maxAllowedX = screenWidth * RESOLUTION;
        float maxAllowedY = screenHeight * RESOLUTION;
        Font defaultFont = WebFontManager.getDefaultFont();
        if (defaultFont == null) defaultFont = Minecraft.getInstance().font;
        float zOffset = 0.0f;
        float topZ = 0.0f;

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
            CssAnimator.cleanup(sessionId, uiElements);
        }

        Map<String, DisplayItem> elementMap = new HashMap<>(uiElements.size());
        for (DisplayItem el : uiElements) elementMap.put(el.id(), el);
        Map<String, AncestorTransform> ancestorTransforms = new HashMap<>();

        DisplayItem hoveredTooltipEl = null;
        String resolvedTooltipText = null;
        String animSessionId = (sessionId != null && !sessionId.isEmpty()) ? sessionId : "unknown";
        SessionContext session = ClientWebManager.getSession(animSessionId);

        // 世界内の面は、SDF の Quad を画面を描き終える（endPass）まで積んで、まとめて描く。
        // GUI などは従来どおり、Quad ごとに描く（バッファの吐き出しも Quad ごと。描く順が要素の順のまま保たれる）
        if (pass.flatLayers()) WebSdfShader.beginPass(poseStack.last().pose());
        int[] zLevels = pass.flatLayers() ? zLevelsFor(uiElements, screenWidth, screenHeight) : null;
        int elementIndex = -1;
        for (DisplayItem el : uiElements) {
            elementIndex++;
            if (el.type() == ElementType.NODE) continue;

            Font font = resolveElementFont(el, defaultFont);
            zOffset = zLevels != null ? zLevels[elementIndex] * effectiveZStep : zOffset + effectiveZStep;
            topZ = Math.max(topZ, zOffset);

            String baseId = ClientWebManager.getBaseNodeId(el.id());
            boolean isHovered = !hoveredId.isEmpty() && baseId.equals(hoveredId);
            boolean isActive = session != null && session.isPressedBase(baseId);
            boolean isFocused = session != null && (session.isActiveInput(el.id()) || session.isFocusedBase(baseId));

            if (isHovered && resolvedTooltipText == null) {
                resolvedTooltipText = extractTooltipText(el);
                if (resolvedTooltipText != null) hoveredTooltipEl = el;
            }

            CssAnimator.AnimatedValues anim = CssAnimator.getValues(animSessionId, el, isHovered, isActive, isFocused);
            RenderMeta meta = el.meta();

            float clipMinX = 0, clipMinY = 0, clipMaxX = maxAllowedX, clipMaxY = maxAllowedY;
            float cRTL = 0, cRTR = 0, cRBR = 0, cRBL = 0;
            // LIMITATION(制約): クリップの矩形は要素自身の変形の後の座標で当てる。クリップする祖先と要素の間に
            // transform があると、祖先の矩形ではなく変形した矩形で切られる（回転した子は回転した矩形で切られる）。
            // 祖先の空間で切るにはステンシルが要る。README の "Not supported" に対応。
            float[] clipArr = (meta != null && meta.clipRect() != null) ? meta.clipRect().toArray() : anim.clipData;
            if (clipArr != null && clipArr.length >= 8) {
                // 祖先のクリップが画面より大きいとき（overflow が画面からはみ出すレイアウト）も、画面の外へは描かない
                clipMinX = Math.max(clipArr[0], 0); clipMinY = Math.max(clipArr[1], 0);
                clipMaxX = Math.min(clipArr[2], maxAllowedX); clipMaxY = Math.min(clipArr[3], maxAllowedY);
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

            // 祖先や要素自身の変形があると、描く座標（要素の空間）とクリップの座標（レイアウトの空間）がずれる。
            // 変形の前の姿勢を覚えておき、変形の後でクリップを要素の空間へ直す
            boolean ownTransform = anim.tx() != 0 || anim.ty() != 0 || anim.rot() != 0 || anim.sx() != 1.0f || anim.sy() != 1.0f;
            boolean hasParentPose = el.events() != null && el.events().containsKey(RenderKeys.MC_PARENT);
            Matrix4f basePose = (ownTransform || hasParentPose) ? new Matrix4f(poseStack.last().pose()) : null;
            // 要素自身の箱で切るクリップ（背景のグラデーション・画像など）は、その要素の変形に付いていく。
            // 動かない祖先のクリップだけを画面に留める
            String clipOwner = meta != null && meta.clipRect() != null ? meta.clipRect().ownerId() : null;
            if (clipOwner != null && basePose != null) {
                AncestorTransform owner = resolveAncestorTransform(poseStack, clipOwner, hoveredId, animSessionId,
                        elementMap, ancestorTransforms);
                if (owner != null) basePose = new Matrix4f(owner.pose());
            }

            poseStack.pushPose();
            try {
                float[] cumulativeOpacity = {1.0f};
                String parentId = el.events() != null ? el.events().get(RenderKeys.MC_PARENT) : null;
                if (parentId != null) {
                    AncestorTransform parent = resolveAncestorTransform(poseStack, parentId, hoveredId, animSessionId,
                            elementMap, ancestorTransforms);
                    if (parent != null) {
                        poseStack.last().pose().set(parent.pose());
                        poseStack.last().normal().set(parent.normal());
                        cumulativeOpacity[0] = parent.opacity();
                    }
                }

                float originX = meta != null ? meta.originX() : 0.5f;
                float originY = meta != null ? meta.originY() : 0.5f;
                float cx = el.x() + el.width() * originX;
                float cy = el.y() + el.height() * originY;

                poseStack.translate(cx + anim.tx(), cy + anim.ty(), 0);
                if (anim.rot() != 0) poseStack.mulPose(Axis.ZP.rotationDegrees(anim.rot()));
                if (anim.sx() != 1.0f || anim.sy() != 1.0f) poseStack.scale(anim.sx(), anim.sy(), 1.0f);
                poseStack.translate(-cx, -cy, 0);

                if (basePose != null) {
                    float[] fixed = clipIntoLocalSpace(basePose, poseStack.last().pose(),
                            clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
                    if (fixed != null) {
                        clipMinX = fixed[0]; clipMinY = fixed[1]; clipMaxX = fixed[2]; clipMaxY = fixed[3];
                        cRTL = fixed[4]; cRTR = fixed[5]; cRBR = fixed[6]; cRBL = fixed[7];
                    }
                }

                float finalOpacity = cumulativeOpacity[0] * anim.opacity();
                if (finalOpacity <= 0.005f) continue;

                if (el.type() == ElementType.CIRCLE_BORDER) {
                    if (renderCircleBorderElement(el, meta, anim, poseStack, buffer, finalOpacity, zOffset + effectiveSubZ,
                            clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL)) {
                        continue;
                    }
                }
                if (el.type() == ElementType.ROUNDED_BORDER) {
                    renderRoundedBorderElement(el, meta, poseStack, buffer, finalOpacity, zOffset,
                            rTL, rTR, rBR, rBL, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
                    continue;
                }

                if (el.type() != ElementType.RANGE) {
                    renderElementBackground(el, meta, anim, poseStack, buffer, finalOpacity, zOffset,
                            rTL, rTR, rBR, rBL, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
                }

                ElementType type = el.type();
                float contentZ = zOffset + effectiveSubZ;

                if (type == ElementType.CANVAS) {
                    Canvas2DContext ctx = Canvas2DContext.get(animSessionId, baseId);
                    if (ctx != null) {
                        ctx.renderCommands(el, poseStack, buffer, finalOpacity, contentZ, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL, packedLight);
                    }
                } else if (type == ElementType.SCENE) {
                    WebSceneContext.getOrCreate(animSessionId, baseId).renderScene(el, poseStack, buffer, finalOpacity, contentZ);
                } else if (type == ElementType.TEXTAREA) {
                    if (renderText) {
                        renderTextareaElement(el, animSessionId, poseStack, buffer, font, anim, finalOpacity, contentZ, clipMinX, clipMinY, clipMaxX, clipMaxY, maxAllowedX, maxAllowedY, is2DText, packedLight);
                    }
                } else if (type == ElementType.RANGE) {
                    renderRangeElement(el, anim, poseStack, buffer, finalOpacity, contentZ, effectiveZStep * 0.08f, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
                } else if (type == ElementType.PROGRESS) {
                    renderProgressElement(el, anim, poseStack, buffer, finalOpacity, contentZ, rTL, rTR, rBR, rBL, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
                } else if (type == ElementType.TOGGLE || type == ElementType.CHECKBOX || type == ElementType.RADIO) {
                    renderToggleElement(el, anim, poseStack, buffer, finalOpacity, contentZ, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
                } else if (type == ElementType.IMAGE || type == ElementType.VIDEO) {
                    renderImageOrMediaElement(el, meta, poseStack, buffer, font, level, animSessionId, audioPos, playAudio, is2DAudio, renderItems, renderText,
                            finalOpacity, contentZ, packedLight, packedOverlay, rTL, rTR, rBR, rBL, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
                } else if ((el.text() != null && !el.text().isEmpty()) || type == ElementType.INPUT || type == ElementType.SELECT) {
                    renderTextOrInputElement(el, meta, anim, isFocused, elementMap, animSessionId, poseStack, buffer, font, renderText, is2DText,
                            finalOpacity, contentZ, packedLight, clipMinX, clipMinY, clipMaxX, clipMaxY, maxAllowedX, maxAllowedY);
                }
            } finally {
                poseStack.popPose();
            }
        }

        if (renderText && hoveredTooltipEl != null && resolvedTooltipText != null && !resolvedTooltipText.isEmpty()) {
            renderTooltipOverlay(poseStack, buffer, defaultFont, hoveredTooltipEl, resolvedTooltipText, maxAllowedX, maxAllowedY, topZ + effectiveZStep * 4 + 0.05f, packedLight);
        }

        WebSdfShader.endPass();
    }

    private static final int Z_LEVEL_CELL = 4;
    private static final int Z_LEVEL_CACHE_MAX = 16;
    private static final Map<List<DisplayItem>, int[]> Z_LEVEL_CACHE = new IdentityHashMap<>();

    /**
     * 要素ごとの奥行きの段（0 始まり）。前に描いた要素と重なるなら、その最上段の 1 つ上に置く。重ならない要素は
     * 同じ段になるので、要素が千を超えても段は入れ子の深さ程度に収まる（奥行きを要素ごとに積むと、
     * 斜めから見た面が立体に見える）。格子で近似するので、重なりが無くても段が上がることはあるが、下がることはない。
     * 要素のリストは再描画まで同じインスタンスなので、そのインスタンスで結果を覚えておく。
     */
    private static int[] zLevelsFor(List<DisplayItem> elements, int screenWidth, int screenHeight) {
        int[] cached = Z_LEVEL_CACHE.get(elements);
        if (cached != null && cached.length == elements.size()) return cached;

        int cols = Math.max(1, (int) Math.ceil(screenWidth * RESOLUTION / Z_LEVEL_CELL));
        int rows = Math.max(1, (int) Math.ceil(screenHeight * RESOLUTION / Z_LEVEL_CELL));
        int[] cellTop = new int[cols * rows];
        int[] levels = new int[elements.size()];
        for (int i = 0; i < levels.length; i++) {
            DisplayItem el = elements.get(i);
            if (el.type() == ElementType.NODE) continue;
            int c0 = clamp(Math.floorDiv(el.x(), Z_LEVEL_CELL), cols);
            // 端は含めない（隣り合うだけの要素を重なりとみなすと、段が横へ連鎖して積み上がり、奥行きが厚くなる）
            int c1 = Math.max(c0, clamp(Math.floorDiv(el.x() + Math.max(1, el.width()) - 1, Z_LEVEL_CELL), cols));
            int r0 = clamp(Math.floorDiv(el.y(), Z_LEVEL_CELL), rows);
            int r1 = Math.max(r0, clamp(Math.floorDiv(el.y() + Math.max(1, el.height()) - 1, Z_LEVEL_CELL), rows));
            int level = 0;
            for (int r = r0; r <= r1; r++) {
                for (int c = c0; c <= c1; c++) level = Math.max(level, cellTop[r * cols + c]);
            }
            level++;
            levels[i] = level;
            for (int r = r0; r <= r1; r++) {
                for (int c = c0; c <= c1; c++) cellTop[r * cols + c] = level;
            }
        }
        if (Z_LEVEL_CACHE.size() >= Z_LEVEL_CACHE_MAX) Z_LEVEL_CACHE.clear();
        Z_LEVEL_CACHE.put(elements, levels);
        return levels;
    }

    private static int clamp(int v, int count) {
        return Math.max(0, Math.min(count - 1, v));
    }

    /** 測定に使った書体と同じものを引く（引き当ては McFontResolver が唯一の出所）。 */
    private static Font resolveElementFont(DisplayItem el, Font defaultFont) {
        if (el.events() != null && el.events().containsKey("font-family")) {
            Font customFont = McFontResolver.fontFor(el.events().get("font-family"));
            if (customFont != null) return customFont;
        }
        return defaultFont;
    }

    private static String extractTooltipText(DisplayItem el) {
        if (el.events() != null && el.events().containsKey(RenderKeys.MC_TITLE)) {
            String t = el.events().get(RenderKeys.MC_TITLE);
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

    private static boolean renderCircleBorderElement(DisplayItem el, RenderMeta meta, CssAnimator.AnimatedValues anim,
                                                     PoseStack poseStack, MultiBufferSource buffer, float finalOpacity, float zPos,
                                                     float clipMinX, float clipMinY, float clipMaxX, float clipMaxY,
                                                     float cRTL, float cRTR, float cRBR, float cRBL) {
        CircleBorderSpec cb = meta != null ? meta.circleBorder() : null;
        if (cb == null && anim.circleColors == null) return false;

        float outerRadius = Math.min(el.width(), el.height()) / 2.0f;
        int cTop = cb != null ? cb.colorTop() : anim.circleColors[0];
        int cRight = cb != null ? cb.colorRight() : anim.circleColors[1];
        int cBottom = cb != null ? cb.colorBottom() : anim.circleColors[2];
        int cLeft = cb != null ? cb.colorLeft() : anim.circleColors[3];
        float thick = cb != null ? cb.thickness() : anim.circleThickness;

        VertexConsumer builder = buffer.getBuffer(RenderType.gui());
        Matrix4f matrix = poseStack.last().pose();
        // 4 辺が同じ色なら、角丸の枠線（半径 = 外半径）として SDF の輪で描く。段のない滑らかな円になる
        if (useSdfShader && cTop == cRight && cRight == cBottom && cBottom == cLeft) {
            if (cTop == 0) return true;
            RoundedRectRasterizer.drawRoundedBorder(builder, matrix,
                    el.x(), el.y(), el.x() + el.width(), el.y() + el.height(),
                    outerRadius, outerRadius, outerRadius, outerRadius,
                    new float[]{thick, thick, thick, thick}, new int[]{cTop, cRight, cBottom, cLeft}, finalOpacity, zPos,
                    clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL, true);
            return true;
        }
        float cX = el.x() + el.width() / 2.0f;
        float cY = el.y() + el.height() / 2.0f;

        if (cTop != 0) drawRingSegment(builder, matrix, cX, cY, outerRadius, thick, (float) (-Math.PI * 0.75), (float) (-Math.PI * 0.25), cTop, finalOpacity, zPos);
        if (cRight != 0) drawRingSegment(builder, matrix, cX, cY, outerRadius, thick, (float) (-Math.PI * 0.25), (float) (Math.PI * 0.25), cRight, finalOpacity, zPos);
        if (cBottom != 0) drawRingSegment(builder, matrix, cX, cY, outerRadius, thick, (float) (Math.PI * 0.25), (float) (Math.PI * 0.75), cBottom, finalOpacity, zPos);
        if (cLeft != 0) drawRingSegment(builder, matrix, cX, cY, outerRadius, thick, (float) (Math.PI * 0.75), (float) (Math.PI * 1.25), cLeft, finalOpacity, zPos);
        return true;
    }

    /** 角丸の枠線は枠の領域だけを塗る（内側は背景の要素が受け持つ）。 */
    private static void renderRoundedBorderElement(DisplayItem el, RenderMeta meta, PoseStack poseStack, MultiBufferSource buffer,
                                                   float finalOpacity, float zOffset, float rTL, float rTR, float rBR, float rBL,
                                                   float clipMinX, float clipMinY, float clipMaxX, float clipMaxY,
                                                   float cRTL, float cRTR, float cRBR, float cRBL) {
        RoundedBorderSpec spec = meta != null ? meta.roundedBorder() : null;
        if (spec == null) return;
        int[] colors = {spec.colorTop(), spec.colorRight(), spec.colorBottom(), spec.colorLeft()};
        VertexConsumer builder = buffer.getBuffer(RenderType.gui());
        RoundedRectRasterizer.drawRoundedBorder(builder, poseStack.last().pose(),
                el.x(), el.y(), el.x() + el.width(), el.y() + el.height(), rTL, rTR, rBR, rBL,
                spec.widths(), colors, finalOpacity, zOffset,
                clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL, useSdfShader);
    }

    private static void renderElementBackground(DisplayItem el, RenderMeta meta, CssAnimator.AnimatedValues anim,
                                                PoseStack poseStack, MultiBufferSource buffer,
                                                float finalOpacity, float zOffset, float rTL, float rTR, float rBR, float rBL,
                                                float clipMinX, float clipMinY, float clipMaxX, float clipMaxY,
                                                float cRTL, float cRTR, float cRBR, float cRBL) {
        String shadowBlur = el.events() != null ? el.events().get(RenderKeys.SHADOW_BLUR) : null;
        if (shadowBlur != null) {
            renderBlurredShadow(el, anim, poseStack, finalOpacity, zOffset, Float.parseFloat(shadowBlur), rTL, rTR, rBR, rBL,
                    clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
            return;
        }
        String shaderGradient = el.events() != null ? el.events().get(RenderKeys.SHADER_GRADIENT) : null;
        if (shaderGradient != null) {
            renderShaderGradient(el, shaderGradient, poseStack, finalOpacity, zOffset,
                    clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
            return;
        }
        GradientSpec grad = meta != null ? meta.gradient() : null;
        // 何が壊れていたか: 以前は isHovered / isActive でグラデーションを無効にしていたため、
        // グラデーション背景の要素にカーソルを乗せると平坦な色に落ちていた。
        // 判定すべきは「状態が背景色を実際に差し替えたか」で、ホバー中かどうかではない。
        boolean backgroundOverriddenByState = anim.bgColor() != el.bgColor();
        if (grad != null && !backgroundOverriddenByState) {
            VertexConsumer builder = buffer.getBuffer(RenderType.gui());
            String edgeMask = el.events() != null ? el.events().get(RenderKeys.EDGE_MASK) : null;
            if (edgeMask != null) WebSdfShader.setEdgeMask(Integer.parseInt(edgeMask));
            try {
                drawGradientRoundedRect(
                        builder, poseStack.last().pose(),
                        el.x(), el.y(), el.x() + el.width(), el.y() + el.height(),
                        rTL, rTR, rBR, rBL,
                        grad, finalOpacity, zOffset,
                        clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL
                );
            } finally {
                if (edgeMask != null) WebSdfShader.setEdgeMask(15);
            }
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

    /** {@code Gradients#shaderGradient} の記述を読んで、2 色のグラデーションを 1 枚で描く。 */
    private static void renderShaderGradient(DisplayItem el, String encoded, PoseStack poseStack, float finalOpacity, float zOffset,
                                             float clipMinX, float clipMinY, float clipMaxX, float clipMaxY,
                                             float cRTL, float cRTR, float cRBR, float cRBL) {
        String[] p = encoded.split(",");
        float[] gradA = new float[4], gradB = new float[4];
        int c0, c1;
        if (p[0].equals("L")) {
            gradA[0] = Float.parseFloat(p[1]);
            gradA[1] = Float.parseFloat(p[2]);
            gradA[2] = Float.parseFloat(p[3]);
            gradA[3] = 1f;
            c0 = Integer.parseInt(p[4]);
            c1 = Integer.parseInt(p[5]);
        } else {
            gradA[0] = Float.parseFloat(p[1]);
            gradA[1] = Float.parseFloat(p[2]);
            gradA[2] = Float.parseFloat(p[3]);
            gradA[3] = 2f;
            gradB[0] = Float.parseFloat(p[4]);
            gradB[1] = Float.parseFloat(p[5]);
            gradB[2] = Float.parseFloat(p[6]);
            c0 = Integer.parseInt(p[7]);
            c1 = Integer.parseInt(p[8]);
        }
        float a0 = ((c0 >>> 24) & 0xFF) / 255.0f * finalOpacity, a1 = ((c1 >>> 24) & 0xFF) / 255.0f * finalOpacity;
        if (a0 <= 0 && a1 <= 0) return;
        WebSdfShader.drawSdfShaderGradient(poseStack.last().pose(),
                el.x(), el.y(), el.x() + el.width(), el.y() + el.height(),
                ((c0 >>> 16) & 0xFF) / 255.0f, ((c0 >>> 8) & 0xFF) / 255.0f, (c0 & 0xFF) / 255.0f, a0,
                ((c1 >>> 16) & 0xFF) / 255.0f, ((c1 >>> 8) & 0xFF) / 255.0f, (c1 & 0xFF) / 255.0f, a1,
                gradA, gradB, zOffset, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
    }

    /**
     * ぼかした影を 1 枚で描く。要素の矩形は「ぼかしが消える所まで」の広さ（{@link #SHADOW_REACH} × ぼかし半径だけ形の外側）で、
     * 影の形はそこから同じだけ内側。減衰はシェーダーがガウスぼかしに近い形で計算する（以前は層の重ね塗りで、段が見えた）。
     */
    private static void renderBlurredShadow(DisplayItem el, CssAnimator.AnimatedValues anim, PoseStack poseStack,
                                            float finalOpacity, float zOffset, float blur,
                                            float rTL, float rTR, float rBR, float rBL,
                                            float clipMinX, float clipMinY, float clipMaxX, float clipMaxY,
                                            float cRTL, float cRTR, float cRBR, float cRBL) {
        float a = ((anim.bgColor() >>> 24) & 0xFF) / 255.0f * finalOpacity;
        if (a <= 0) return;
        float r = ((anim.bgColor() >>> 16) & 0xFF) / 255.0f;
        float g = ((anim.bgColor() >>> 8) & 0xFF) / 255.0f;
        float b = (anim.bgColor() & 0xFF) / 255.0f;
        float reach = SHADOW_REACH * blur;
        float qx0 = el.x(), qy0 = el.y(), qx1 = el.x() + el.width(), qy1 = el.y() + el.height();
        float sx0 = qx0 + reach, sy0 = qy0 + reach, sx1 = qx1 - reach, sy1 = qy1 - reach;
        if (sx1 <= sx0 || sy1 <= sy0) return;
        // 形の角丸は、要素の矩形に合わせて切り詰められた半径ではなく、形そのものの大きさで切り詰める
        float limit = Math.min(sx1 - sx0, sy1 - sy0) / 2.0f;
        WebSdfShader.drawSdfShadow(poseStack.last().pose(), qx0, qy0, qx1, qy1, sx0, sy0, sx1, sy1,
                Math.min(rTL, limit), Math.min(rTR, limit), Math.min(rBR, limit), Math.min(rBL, limit),
                r, g, b, a, zOffset, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL, blur);
    }

    /** 影のぼかしが消える所までの広さ（ぼかし半径の倍数。標準偏差 = 半径 / 2 の 3 倍）。 */
    public static final float SHADOW_REACH = 1.5f;

    /**
     * レイアウトの空間のクリップ矩形を、要素を描く空間（変形の後の姿勢の局所座標）へ直す。{@code base} は変形の前、
     * {@code eff} は変形の後の姿勢。平行移動だけなら矩形をずらす（角丸もそのまま）。回転・拡縮があるときは、
     * 円（正方形で角丸が半分以上）のクリップだけ、中心を移して半径を拡縮する（円は回しても円）。
     * それ以外（回した角丸矩形など）は、直せないのでそのまま（{@code null}）。
     */
    static float[] clipIntoLocalSpace(Matrix4f base, Matrix4f eff,
                                              float x0, float y0, float x1, float y1,
                                              float r0, float r1, float r2, float r3) {
        Matrix4f toLocal = new Matrix4f(eff).invert().mul(base);
        float a = toLocal.m00(), b = toLocal.m10(), c = toLocal.m01(), d = toLocal.m11();
        float tx = toLocal.m30(), ty = toLocal.m31();
        if (!Float.isFinite(a) || !Float.isFinite(d) || !Float.isFinite(tx) || !Float.isFinite(ty)) return null;
        boolean identityLinear = Math.abs(a - 1) < 1e-4f && Math.abs(d - 1) < 1e-4f && Math.abs(b) < 1e-4f && Math.abs(c) < 1e-4f;
        if (identityLinear) {
            if (Math.abs(tx) < 1e-4f && Math.abs(ty) < 1e-4f) return null;
            return new float[]{x0 + tx, y0 + ty, x1 + tx, y1 + ty, r0, r1, r2, r3};
        }
        float w = x1 - x0, h = y1 - y0;
        float half = Math.min(w, h) / 2.0f;
        boolean circle = Math.abs(w - h) < 0.5f && half > 0
                && r0 >= half - 0.5f && r1 >= half - 0.5f && r2 >= half - 0.5f && r3 >= half - 0.5f;
        float scaleX = (float) Math.sqrt(a * a + c * c), scaleY = (float) Math.sqrt(b * b + d * d);
        if (!circle || Math.abs(scaleX - scaleY) > 1e-3f * Math.max(1f, scaleX)) return null;
        float qx = (x0 + x1) / 2.0f, qy = (y0 + y1) / 2.0f;
        float px = a * qx + b * qy + tx, py = c * qx + d * qy + ty;
        float rad = half * scaleX;
        return new float[]{px - rad, py - rad, px + rad, py + rad, rad, rad, rad, rad};
    }

    private static void renderProgressElement(DisplayItem el, CssAnimator.AnimatedValues anim, PoseStack poseStack, MultiBufferSource buffer,
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

    private static void renderRangeElement(DisplayItem el, CssAnimator.AnimatedValues anim, PoseStack poseStack, MultiBufferSource buffer,
                                           float finalOpacity, float zPos, float zUnit,
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

        // Chromium の NativeThemeBase::PaintSliderTrack / PaintSliderThumb と同じ形（DisplayListBuilder#drawNativeRange）。
        // 溝は箱の幅いっぱい・高さ 8px で縦の中央、つまみは直径 16px で中心が両端から 8px の間を動く
        float cy = el.y() + el.height() / 2.0f;
        float trackTop = cy - RANGE_TRACK_HEIGHT / 2, trackBottom = cy + RANGE_TRACK_HEIGHT / 2;
        float trackRad = RANGE_TRACK_HEIGHT / 2;
        float thumbRad = RANGE_THUMB_SIZE / 2;
        float left = el.x(), right = el.x() + el.width();
        float tCX = left + thumbRad + ratio * Math.max(0, el.width() - RANGE_THUMB_SIZE);

        int pC = anim.color();
        float prA = ((pC >>> 24) & 0xFF) / 255.0f * finalOpacity;
        float prR = ((pC >>> 16) & 0xFF) / 255.0f;
        float prG = ((pC >>> 8) & 0xFF) / 255.0f;
        float prB = (pC & 0xFF) / 255.0f;

        int fill = anim.bgColor();
        if (((fill >>> 24) & 0xFF) != 0) {
            // Chromium は溝を塗り、値までを強調色で塗り、半透明の 1px の枠を溝全体に重ねる。枠を重ねた色を先に合成し、
            // 「枠を重ねた色の角丸」と「1px 内側の地の色の角丸」の 2 枚ずつで描く（どれも SDF で描き、四角形の一括描画と
            // 深度で競合しない。以前は枠を一括描画の四角形で描いて、暗い溝で枠が消えていた）
            RenderMeta meta = el.meta();
            int edge = meta != null && meta.roundedBorder() != null ? meta.roundedBorder().colorTop() : 0;
            int accent = anim.color() | 0xFF000000;
            // 同じ深さに重ねるとちらつく（Z ファイティング）ので、描く順に少しずつ手前へ置く
            fillRoundedRect(builder, mat, left, trackTop, right, trackBottom, trackRad, trackRad, over(edge, fill), finalOpacity, zPos,
                    clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
            fillRoundedRect(builder, mat, left + 1, trackTop + 1, right - 1, trackBottom - 1, trackRad - 1, trackRad - 1, fill, finalOpacity, zPos + zUnit,
                    clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
            if (tCX > left) {
                float r = Math.min(trackRad, (tCX - left) / 2);
                fillRoundedRect(builder, mat, left, trackTop, tCX, trackBottom, r, 0, over(edge, accent), finalOpacity, zPos + 2 * zUnit,
                        clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
                if (tCX > left + 1) {
                    float ri = Math.max(0, Math.min(trackRad - 1, (tCX - left - 1) / 2));
                    fillRoundedRect(builder, mat, left + 1, trackTop + 1, tCX, trackBottom - 1, ri, 0, accent, finalOpacity, zPos + 3 * zUnit,
                            clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
                }
            }
        }
        drawRoundedRect(builder, mat, tCX - thumbRad, cy - thumbRad, tCX + thumbRad, cy + thumbRad,
                thumbRad, thumbRad, thumbRad, thumbRad, prR, prG, prB, prA, zPos + 4 * zUnit,
                clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
    }

    /** 左の角（左上・左下）を {@code rLeft}、右の角を {@code rRight} にした角丸矩形を {@code argb} で塗る。 */
    private static void fillRoundedRect(VertexConsumer builder, Matrix4f mat, float x0, float y0, float x1, float y1,
                                        float rLeft, float rRight, int argb, float opacity, float zPos,
                                        float clipMinX, float clipMinY, float clipMaxX, float clipMaxY,
                                        float cRTL, float cRTR, float cRBR, float cRBL) {
        drawRoundedRect(builder, mat, x0, y0, x1, y1, rLeft, rRight, rRight, rLeft,
                ((argb >>> 16) & 0xFF) / 255.0f, ((argb >>> 8) & 0xFF) / 255.0f, (argb & 0xFF) / 255.0f,
                ((argb >>> 24) & 0xFF) / 255.0f * opacity, zPos, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
    }

    /** 半透明の {@code top} を不透明な {@code bottom} に重ねた色（sRGB のまま合成する。ブラウザの合成と同じ）。 */
    static int over(int top, int bottom) {
        float a = ((top >>> 24) & 0xFF) / 255f;
        int out = bottom & 0xFF000000;
        for (int shift = 0; shift <= 16; shift += 8) {
            float t = (top >>> shift) & 0xFF, b = (bottom >>> shift) & 0xFF;
            out |= Math.round(t * a + b * (1 - a)) << shift;
        }
        return out;
    }

    /** レンジの溝の高さとつまみの直径（Chromium の既定、CSS px）。 */
    public static final float RANGE_TRACK_HEIGHT = 8f, RANGE_THUMB_SIZE = 16f;

    /**
     * レンジの横の位置（要素の左端から）を値の割合（0〜1）に直す。つまみの中心が指す位置になるよう、
     * 両端のつまみの半径の分を除いた幅で割る（Chrome と同じく、押した所につまみの中心が来る）。
     */
    public static float rangeRatio(double localX, float width) {
        float travel = Math.max(1f, width - RANGE_THUMB_SIZE);
        return Math.clamp((float) ((localX - RANGE_THUMB_SIZE / 2) / travel), 0f, 1f);
    }

    private static void renderToggleElement(DisplayItem el, CssAnimator.AnimatedValues anim, PoseStack poseStack, MultiBufferSource buffer,
                                            float finalOpacity, float zPos,
                                            float clipMinX, float clipMinY, float clipMaxX, float clipMaxY,
                                            float cRTL, float cRTR, float cRBR, float cRBL) {
        boolean mixed = el.type() == ElementType.CHECKBOX && "mixed".equals(el.text());
        if (!"true".equals(el.text()) && !mixed) return;
        float a = ((anim.color() >>> 24) & 0xFF) / 255.0f * finalOpacity;
        if (a <= 0.0f) return;

        float pad = el.width() * 0.25f;
        float r = ((anim.color() >>> 16) & 0xFF) / 255.0f;
        float g = ((anim.color() >>> 8) & 0xFF) / 255.0f;
        float b = (anim.color() & 0xFF) / 255.0f;
        if (el.type() == ElementType.RADIO) {
            // 点は箱の直径の 60% の円（Chromium は箱の矩形を 20% ずつ内側に縮めた楕円を塗る。26px の Chrome を撮った実測）
            pad = el.width() * 0.2f;
            float dot = (el.width() - 2 * pad) / 2f;
            drawRoundedRect(buffer.getBuffer(RenderType.gui()), poseStack.last().pose(),
                    el.x() + pad, el.y() + el.height() / 2f - dot, el.x() + el.width() - pad, el.y() + el.height() / 2f + dot,
                    dot, dot, dot, dot, r, g, b, a, zPos, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
            return;
        }
        if (el.type() == ElementType.CHECKBOX) {
            renderCheckboxMark(el, mixed, r, g, b, a, poseStack, buffer, zPos, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
            return;
        }
        drawClippedQuad(buffer.getBuffer(RenderType.gui()), poseStack.last().pose(),
                el.x() + pad, el.y() + pad, el.x() + el.width() - pad, el.y() + el.height() - pad,
                r, g, b, a, zPos, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
    }

    /**
     * チェックボックスの印（Chromium の NativeThemeBase::PaintCheckbox と同じ形）。
     * <ul>
     *   <li>選択: (0.2w, 0.5h) から右下へ (0.2w, 0.2h) 進み、(0.8w, 0.2h) へ上がる折れ線。太さ 0.16h</li>
     *   <li>未確定（indeterminate）: 左右 20%・上下 40% 内側の横棒</li>
     * </ul>
     * 折れ目は 2 本の線分をそれぞれ太さの半分だけ延ばして重ね、角が欠けないようにする。
     */
    private static void renderCheckboxMark(DisplayItem el, boolean mixed, float r, float g, float b, float a,
                                           PoseStack poseStack, MultiBufferSource buffer, float zPos,
                                           float clipMinX, float clipMinY, float clipMaxX, float clipMaxY,
                                           float cRTL, float cRTR, float cRBR, float cRBL) {
        float x = el.x(), y = el.y(), w = el.width(), h = el.height();
        VertexConsumer builder = buffer.getBuffer(RenderType.gui());
        Matrix4f mat = poseStack.last().pose();
        if (mixed) {
            float radius = Math.min(2f, h * 0.1f);
            drawRoundedRect(builder, mat, x + w * 0.2f, y + h * 0.4f, x + w * 0.8f, y + h * 0.6f,
                    radius, radius, radius, radius, r, g, b, a, zPos, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL);
            return;
        }
        float stroke = h * 0.16f;
        float x0 = x + w * 0.2f, y0 = y + h * 0.5f;
        float x1 = x0 + w * 0.2f, y1 = y0 + h * 0.2f;
        float x2 = x + w * 0.8f, y2 = y + h * 0.2f;
        float lenA = (float) Math.hypot(x1 - x0, y1 - y0), lenB = (float) Math.hypot(x2 - x1, y2 - y1);
        float ext = stroke / 2;
        RoundedRectRasterizer.drawSegment(builder, mat, x0, y0, x1 + (x1 - x0) / lenA * ext, y1 + (y1 - y0) / lenA * ext, stroke,
                r, g, b, a, zPos, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL, useSdfShader);
        RoundedRectRasterizer.drawSegment(builder, mat, x1 - (x2 - x1) / lenB * ext, y1 - (y2 - y1) / lenB * ext, x2, y2, stroke,
                r, g, b, a, zPos, clipMinX, clipMinY, clipMaxX, clipMaxY, cRTL, cRTR, cRBR, cRBL, useSdfShader);
    }

    private static void renderImageOrMediaElement(
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
            DisplayItem el, WebMediaManager.ImageInfo info, PoseStack poseStack, MultiBufferSource buffer,
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

    private static void renderItemElement(DisplayItem el, RenderMeta meta, String rawSpec,
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

    private static boolean shouldSkipPlaceholder(DisplayItem el, Map<String, DisplayItem> elementMap, String animSessionId) {
        if (!el.id().endsWith("_ph")) return false;
        SessionContext session = ClientWebManager.getSession(animSessionId);
        String inputNodeId = el.id().substring(0, el.id().length() - 3);
        DisplayItem inputEl = elementMap.get(inputNodeId + "_input");
        if (inputEl != null && inputEl.text() != null && !inputEl.text().isEmpty()) {
            return true;
        }
        if (session != null && session.isFocusedBase(inputNodeId)) {
            String activeVal = WebTextInputHost.getActiveText(animSessionId);
            if (activeVal != null && !activeVal.isEmpty()) return true;
        }
        HtmlNode vRoot = session != null ? session.rootNode() : null;
        if (vRoot != null) {
            HtmlNode domNode = vRoot.getNodeById(inputNodeId);
            return domNode != null && !domNode.attrs.getOrDefault("value", "").isEmpty();
        }
        return false;
    }

    private static void renderTextOrInputElement(
            DisplayItem el, RenderMeta meta, CssAnimator.AnimatedValues anim, boolean isFocused,
            Map<String, DisplayItem> elementMap, String animSessionId,
            PoseStack poseStack, MultiBufferSource buffer, Font font, boolean renderText, boolean is2DAudio,
            float finalOpacity, float zPos, int packedLight,
            float clipMinX, float clipMinY, float clipMaxX, float clipMaxY, float maxAllowedX, float maxAllowedY
    ) {
        if (shouldSkipPlaceholder(el, elementMap, animSessionId)) return;

        // &nbsp; は U+00A0 で届く。Minecraft のフォントはこの字形を空白の幅で持たないので、測定（McFontResolver）と同じく空白で描く
        String textToDraw = el.text() == null ? "" : el.text().replace(' ', ' ');
        float textX = el.x(), textY = el.y();

        int safeCursorPos = -1;
        if (el.type() == ElementType.INPUT && isFocused) {
            String activeVal = WebTextInputHost.getActiveText(animSessionId);
            if (activeVal != null) {
                textToDraw = activeVal;
                safeCursorPos = WebTextInputHost.getActiveCursorPosition(animSessionId);
            }
        }

        if (textY >= clipMaxY || textY + el.height() <= clipMinY) return;

        float fontH = meta != null ? meta.fontHeight() : el.height();
        OutlineFontFace outline = WebFontManager.outlineFaceFor(el);
        if (outline != null) {
            renderOutlineTextOrInput(el, meta, anim, outline, textToDraw, safeCursorPos, fontH, animSessionId,
                    poseStack, buffer, renderText, is2DAudio, finalOpacity, zPos, packedLight,
                    clipMinX, clipMinY, clipMaxX, clipMaxY, maxAllowedX, maxAllowedY);
            return;
        }
        // 書体の em（素のサイズ 9px）からの倍率。測定側（BitmapFontFace）と同じ式を使う。
        // 以前は測定が 16/12、描画が /12 という別々の換算で、しかも
        // 要素の高さを font-size の代わりに使っていた。
        float textScale = fontH / BitmapFontFace.MC_UNITS_PER_EM;
        float ascent = BitmapFontFace.MC_ASCENT * textScale;
        float descent = BitmapFontFace.MC_DESCENT * textScale;
        Matrix4f currentPose = poseStack.last().pose();
        float matScale = Math.abs(currentPose.m00());

        // ベースラインはレイアウトが決める。持たない要素（入力欄・プレースホルダ）だけ
        // 内容領域の中央に置く。以前は全要素を「高さ 8px の箱」として中央寄せしていた。
        boolean centersInBox = el.type() == ElementType.INPUT || el.type() == ElementType.SELECT
                || el.id().endsWith("_ph") || meta == null || meta.baseline() <= 0;
        float baseline = centersInBox
                ? (el.height() + ascent - descent) / 2.0f
                : meta.baseline();
        if (el.type() == ElementType.INPUT || el.type() == ElementType.SELECT) {
            textX += 4.0f;
        }
        // Minecraft のフォントは ascent の上端を原点に描くので、ベースラインから戻す
        textY += baseline - ascent;

        if (el.type() != ElementType.INPUT && textToDraw.isEmpty()) return;
        if (!renderText || textX >= maxAllowedX || textY >= maxAllowedY) return;

        int newA = (int) (((anim.color() >>> 24) & 0xFF) * finalOpacity);
        // Minecraft の Font はアルファが 4 未満の色を不透明にして描くので、color: transparent が黒く出ないよう描かない
        if (newA < 4) return;

        boolean useShadow = el.events() != null && "true".equals(el.events().get("text-shadow"));
        String textDeco = el.events() != null ? el.events().getOrDefault("text-decoration", "none") : "none";
        int argbColor = (newA << 24) | (anim.color() & 0x00FFFFFF);

        boolean textScissor = enableClipScissorFor2D(currentPose, buffer, clipMinX, clipMinY, clipMaxX, clipMaxY, maxAllowedX, maxAllowedY, is2DAudio);
        poseStack.pushPose();
        try {
            if (el.type() == ElementType.INPUT || el.type() == ElementType.SELECT) {
                poseStack.translate(snapCoordFor2D(textX, matScale, is2DAudio), snapCoordFor2D(textY, matScale, is2DAudio), zPos);
                poseStack.scale(textScale, textScale, 1.0f);

                int scrollOffset = TextEditState.getInputScrollOffset(animSessionId, el.id());
                float maxAvailableWidth = (clipMaxX - textX) / textScale;
                float availableWidth = Math.max(0, Math.min((el.width() - 8.0f) / textScale, maxAvailableWidth));

                if (safeCursorPos >= 0) {
                    safeCursorPos = Math.min(textToDraw.length(), safeCursorPos);
                    if (safeCursorPos < scrollOffset) scrollOffset = safeCursorPos;
                    while (scrollOffset < safeCursorPos && font.width(textToDraw.substring(scrollOffset, safeCursorPos)) > availableWidth) {
                        scrollOffset++;
                    }
                    TextEditState.setInputScrollOffset(animSessionId, el.id(), scrollOffset);
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
                poseStack.translate(snapCoordFor2D(textX, matScale, is2DAudio), snapCoordFor2D(textY, matScale, is2DAudio), zPos);
                poseStack.scale(textScale, textScale, 1.0f);
                float availableWidth = Math.max(0, Math.min((el.width() - 8.0f) / textScale, (clipMaxX - textX) / textScale));
                String visibleText = font.plainSubstrByWidth(textToDraw, (int) availableWidth);
                if (!visibleText.isEmpty()) {
                    font.drawInBatch(visibleText, 0, 0, argbColor, useShadow, poseStack.last().pose(), buffer, Font.DisplayMode.NORMAL, 0, packedLight);
                }
            } else if (!textToDraw.isEmpty()) {
                // 折り返し・省略・text-align はすべてレイアウト側で確定済み。
                // 以前はここで font.width を使って測り直し、「幅の 1.3 倍 + 4px」という
                // 独自の基準で省略を判定し、中央寄せも自前でやっていたため、
                // レイアウトが予約した行と実際に見える行が一致しなかった。
                boolean isBold = el.events() != null && com.nishiyu.enderframelib.core.layout.model.LayoutMetrics.isBoldWeight(el.events().get("font-weight"));
                String visibleText = isBold && !textToDraw.startsWith("§l") ? "§l" + textToDraw : textToDraw;
                float rawTextW = textScale > 0 ? el.width() / textScale : 0;

                float drawX = snapCoordFor2D(textX, matScale, is2DAudio);
                float drawY = snapCoordFor2D(textY, matScale, is2DAudio);

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

    private static void renderTooltipOverlay(PoseStack poseStack, MultiBufferSource buffer, Font font, DisplayItem target, String tooltipText, float maxW, float maxH, float zPos, int packedLight) {
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
        // 画面よりツールチップが大きいと範囲が逆転する（Math.clamp は例外）。その場合は左上に寄せる
        tipX = Math.max(2.0f, Math.min(tipX, maxW - boxW - 2.0f));
        tipY = Math.max(2.0f, Math.min(tipY, maxH - boxH - 2.0f));

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

    public static void addGradientQuadRaw(VertexConsumer builder, Matrix4f matrix, float minX, float minY, float maxX, float maxY, int cTL, int cTR, int cBR, int cBL, float opacity, float zOffset) {
        RoundedRectRasterizer.addGradientQuadRaw(builder, matrix, minX, minY, maxX, maxY, cTL, cTR, cBR, cBL, opacity, zOffset);
    }

    public static void drawGradientRoundedRect(
            VertexConsumer builder, Matrix4f matrix,
            float minX, float minY, float maxX, float maxY,
            float rTL, float rTR, float rBR, float rBL,
            GradientSpec grad, float opacity, float zOffset,
            float cx0, float cy0, float cx1, float cy1,
            float cRTL, float cRTR, float cRBR, float cRBL
    ) {
        RoundedRectRasterizer.drawGradientRoundedRect(builder, matrix, minX, minY, maxX, maxY, rTL, rTR, rBR, rBL,
                grad.topLeft(), grad.topRight(), grad.bottomRight(), grad.bottomLeft(), opacity, zOffset,
                cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL, useSdfShader);
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

    private static void renderEntityElement(
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

    /** textarea が行番号を出すか。クリック位置の解釈（TextareaEditor）と共有する。 */
    public static boolean showsLineNumbers(DisplayItem el) {
        if (el.events() == null) return false;
        String syntax = el.events().getOrDefault("data-syntax", "");
        return "lua".equalsIgnoreCase(syntax) || "true".equalsIgnoreCase(el.events().get("data-line-numbers"));
    }

    /** textarea の本文が始まる x 位置（行番号ガターの幅）。同じく編集側と共有する。 */
    public static float textareaGutterWidth(DisplayItem el, float textScale) {
        return showsLineNumbers(el) ? 28.0f * textScale : 4.0f;
    }

    private static void renderTextareaElement(
            DisplayItem el, String sessionId, PoseStack poseStack, MultiBufferSource buffer, Font font,
            CssAnimator.AnimatedValues anim, float finalOpacity, float zPos,
            float clipMinX, float clipMinY, float clipMaxX, float clipMaxY,
            float maxAllowedX, float maxAllowedY, boolean is2D, int packedLight
    ) {
        String fullText = el.text() != null ? el.text() : "";
        String[] lines = fullText.replace("\r\n", "\n").split("\n", -1);
        OutlineFontFace outline = WebFontManager.outlineFaceFor(el);

        RenderMeta meta = el.meta();
        RenderMeta safeMeta = meta != null ? meta : RenderMeta.EMPTY;
        float fontH = safeMeta.fontHeight();
        Matrix4f mat = poseStack.last().pose();
        float matScale = Math.abs(mat.m00());
        float textScale = safeMeta.textScale();
        // 行送りは CSS の line-height をレイアウトが解決した値。
        // 以前は max(12 * textScale, 11.0f) という独自の式で、line-height を無視していた。
        float lineHeight = safeMeta.resolvedLineHeight();

        String syntax = el.events() != null ? el.events().getOrDefault("data-syntax", "") : "";
        boolean showLineNums = showsLineNumbers(el);
        float gutterW = textareaGutterWidth(el, textScale);

        boolean isFocused = ClientWebManager.getSession(sessionId) != null
                && ClientWebManager.getSession(sessionId).isActiveInput(el.id());
        int cursorIdx = Math.clamp(TextEditState.getTextareaCursor(sessionId, el.id(), fullText.length()), 0, fullText.length());

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
        int scrollRow = TextEditState.getTextareaScrollRow(sessionId, el.id());
        if (isFocused) {
            if (cursorRow < scrollRow) scrollRow = cursorRow;
            else if (cursorRow >= scrollRow + maxVisibleRows) scrollRow = cursorRow - maxVisibleRows + 1;
        }
        scrollRow = Math.clamp(scrollRow, 0, Math.max(0, lines.length - maxVisibleRows));
        TextEditState.setTextareaScrollRow(sessionId, el.id(), scrollRow);

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
            if (outline != null) {
                drawOutlineTextareaRows(el, outline, lines, syntax, showLineNums, gutterW, lineHeight, scrollRow, maxVisibleRows,
                        isFocused, cursorRow, cursorCol, textColor, gutterColor, finalOpacity, poseStack, buffer, zPos,
                        taClipMinY, taClipMaxY, is2D, packedLight);
                if (taScissor && buffer instanceof MultiBufferSource.BufferSource bs) {
                    bs.endBatch();
                }
                return;
            }
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

    /** ワールド内（ブロックの画面）の文字をラスタライズする倍率。見る距離が決まらないので固定の値にする。 */
    private static final float WORLD_TEXT_SCALE = 3.0f;

    /** 1 CSS px が何デバイスピクセルか。GUI では変換行列の倍率 × 画面の倍率、ワールド内では固定。 */
    private static float deviceScaleFor(Matrix4f pose, boolean is2D) {
        if (!is2D) return WORLD_TEXT_SCALE;
        return (float) (Math.abs(pose.m00()) * Minecraft.getInstance().getWindow().getGuiScale());
    }

    /**
     * システムの書体で文字・入力欄・プレースホルダーを描く。位置と幅は測定（{@link OutlineFontFace}）と同じ値で決める。
     * ベースラインはレイアウトが決めた値、持たない要素（入力欄など）は内容領域の中央に置く。
     */
    private static void renderOutlineTextOrInput(
            DisplayItem el, RenderMeta meta, CssAnimator.AnimatedValues anim, OutlineFontFace face,
            String textToDraw, int cursorPos, float fontH, String animSessionId,
            PoseStack poseStack, MultiBufferSource buffer, boolean renderText, boolean is2D,
            float finalOpacity, float zPos, int packedLight,
            float clipMinX, float clipMinY, float clipMaxX, float clipMaxY, float maxAllowedX, float maxAllowedY
    ) {
        FontStyle style = WebFontManager.fontStyleFor(el, fontH);
        float ascent = face.ascent(style);
        float descent = face.descent(style);
        boolean isField = el.type() == ElementType.INPUT || el.type() == ElementType.SELECT;
        boolean centersInBox = isField || el.id().endsWith("_ph") || meta == null || meta.baseline() <= 0;
        float baseline = el.y() + (centersInBox ? (el.height() + ascent - descent) / 2.0f : meta.baseline());
        float textX = el.x() + (isField ? 4.0f : 0.0f);

        if (el.type() != ElementType.INPUT && textToDraw.isEmpty()) return;
        if (!renderText || textX >= maxAllowedX || baseline - ascent >= maxAllowedY) return;

        int newA = (int) (((anim.color() >>> 24) & 0xFF) * finalOpacity);
        if (newA < 4) return;
        boolean useShadow = el.events() != null && "true".equals(el.events().get("text-shadow"));
        String textDeco = el.events() != null ? el.events().getOrDefault("text-decoration", "none") : "none";
        int argb = (newA << 24) | (anim.color() & 0x00FFFFFF);
        float r = ((anim.color() >>> 16) & 0xFF) / 255.0f;
        float g = ((anim.color() >>> 8) & 0xFF) / 255.0f;
        float b = (anim.color() & 0xFF) / 255.0f;

        Matrix4f outer = poseStack.last().pose();
        float deviceScale = deviceScaleFor(outer, is2D);
        boolean textScissor = enableClipScissorFor2D(outer, buffer, clipMinX, clipMinY, clipMaxX, clipMaxY, maxAllowedX, maxAllowedY, is2D);
        poseStack.pushPose();
        try {
            poseStack.translate(0, 0, zPos);
            Matrix4f pose = poseStack.last().pose();
            if (isField) {
                int scrollOffset = TextEditState.getInputScrollOffset(animSessionId, el.id());
                float available = Math.max(0, Math.min(el.width() - 8.0f, clipMaxX - textX));
                if (cursorPos >= 0) {
                    cursorPos = Math.min(textToDraw.length(), cursorPos);
                    if (cursorPos < scrollOffset) scrollOffset = cursorPos;
                    while (scrollOffset < cursorPos && face.advance(textToDraw.substring(scrollOffset, cursorPos), style) > available) {
                        scrollOffset++;
                    }
                    TextEditState.setInputScrollOffset(animSessionId, el.id(), scrollOffset);
                } else if (scrollOffset > textToDraw.length()) {
                    scrollOffset = textToDraw.length();
                }
                String rest = textToDraw.substring(Math.min(scrollOffset, textToDraw.length()));
                String visible = rest.substring(0, face.fitChars(rest, style, available));
                OutlineTextRenderer.draw(visible, face, style, textX, baseline, argb, useShadow, pose, buffer, deviceScale, is2D, packedLight);
                if (cursorPos >= scrollOffset && cursorPos - scrollOffset <= visible.length()
                        && (System.currentTimeMillis() / 500) % 2 == 0) {
                    float cx = textX + face.advance(visible.substring(0, cursorPos - scrollOffset), style);
                    addQuadRaw(buffer.getBuffer(RenderType.gui()), pose, cx, baseline - ascent, cx + 1.0f, baseline + descent,
                            r, g, b, newA / 255.0f, ZLayers.SUB_Z_OFFSET);
                }
            } else if (el.id().endsWith("_ph")) {
                float available = Math.max(0, Math.min(el.width() - 8.0f, clipMaxX - textX));
                String visible = textToDraw.substring(0, face.fitChars(textToDraw, style, available));
                OutlineTextRenderer.draw(visible, face, style, textX, baseline, argb, useShadow, pose, buffer, deviceScale, is2D, packedLight);
            } else {
                // 折り返し・省略・text-align はすべてレイアウト側で確定済み（描画は測り直さない）
                OutlineTextRenderer.draw(textToDraw, face, style, textX, baseline, argb, useShadow, pose, buffer, deviceScale, is2D, packedLight);
                if (textDeco.contains("underline") || textDeco.contains("line-through")) {
                    var primary = face.primaryFace(style);
                    float size = style.fontSizePx();
                    VertexConsumer lineBuilder = buffer.getBuffer(RenderType.gui());
                    if (textDeco.contains("underline")) {
                        float top = snapCoordFor2D(baseline + primary.underlineOffset(size), Math.abs(outer.m00()), is2D);
                        addQuadRaw(lineBuilder, pose, textX, top, textX + el.width(), top + primary.underlineThickness(size),
                                r, g, b, newA / 255.0f, ZLayers.SUB_Z_OFFSET);
                    }
                    if (textDeco.contains("line-through")) {
                        float thick = primary.strikeoutThickness(size);
                        float top = snapCoordFor2D(baseline - primary.strikeoutPosition(size) - thick / 2, Math.abs(outer.m00()), is2D);
                        addQuadRaw(lineBuilder, pose, textX, top, textX + el.width(), top + thick,
                                r, g, b, newA / 255.0f, ZLayers.SUB_Z_OFFSET);
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

    /** テキストエリアの行をシステムの書体で描く。行の中の文字の位置は half-leading の分け方（{@link TextShaper#leadingAbove}）で決める。 */
    private static void drawOutlineTextareaRows(
            DisplayItem el, OutlineFontFace face, String[] lines, String syntax, boolean showLineNums, float gutterW,
            float lineHeight, int scrollRow, int maxVisibleRows, boolean isFocused, int cursorRow, int cursorCol,
            int textColor, int gutterColor, float finalOpacity, PoseStack poseStack, MultiBufferSource buffer, float zPos,
            float taClipMinY, float taClipMaxY, boolean is2D, int packedLight
    ) {
        RenderMeta meta = el.meta() != null ? el.meta() : RenderMeta.EMPTY;
        FontStyle style = WebFontManager.fontStyleFor(el, meta.fontHeight());
        float ascent = face.ascent(style);
        float descent = face.descent(style);
        float halfLeading = TextShaper.leadingAbove(lineHeight, ascent, descent);
        float availW = Math.max(10.0f, el.width() - gutterW - 6.0f);

        poseStack.pushPose();
        try {
            poseStack.translate(0, 0, zPos + 0.0004f);
            Matrix4f pose = poseStack.last().pose();
            float deviceScale = deviceScaleFor(pose, is2D);
            for (int i = 0; i < maxVisibleRows; i++) {
                int rowIdx = scrollRow + i;
                if (rowIdx >= lines.length) break;
                float rowY = el.y() + 4.0f + i * lineHeight;
                if (rowY + lineHeight < taClipMinY || rowY > taClipMaxY) continue;
                float baseline = rowY + halfLeading + ascent;

                if (showLineNums) {
                    OutlineTextRenderer.draw(String.valueOf(rowIdx + 1), face, style, el.x() + 4.0f, baseline, gutterColor,
                            false, pose, buffer, deviceScale, is2D, packedLight);
                }
                String rawLine = lines[rowIdx];
                String visibleRaw = rawLine.substring(0, face.fitChars(rawLine, style, availW));
                float textX = el.x() + gutterW + 2.0f;
                OutlineTextRenderer.drawFormatted(highlightCodeLine(visibleRaw, syntax), face, style, textX, baseline, textColor,
                        pose, buffer, deviceScale, is2D, packedLight);

                if (isFocused && rowIdx == cursorRow && (System.currentTimeMillis() / 500) % 2 == 0) {
                    int safeCol = Math.clamp(cursorCol, 0, visibleRaw.length());
                    float curX = textX + face.advance(visibleRaw.substring(0, safeCol), style);
                    addQuadRaw(buffer.getBuffer(RenderType.gui()), pose, curX, baseline - ascent, curX + 1.0f, baseline + descent,
                            0.54f, 0.71f, 0.98f, finalOpacity, 0.0002f);
                }
            }
        } finally {
            poseStack.popPose();
        }
    }

    /**
     * クライアントUIおよび描画パイプライン全体で共通利用されるZ深度（レイヤーオフセット）の定数集。
     */
    public static final class ZLayers {

        private ZLayers() {}

        /** 要素階層ごとの基本Zステップ（重なり順制御用） */
        public static final float ELEMENT_Z_STEP = 0.002f;

        /** 要素内の微小サブZオフセット（装飾・下線・キャレットなど） */
        public static final float SUB_Z_OFFSET = 0.0005f;

        /** 3DエンティティプレビューのZオフセット */
        public static final float ENTITY_PREVIEW_Z = 20.0f;

        /** 3DシーンプレビューのZオフセット */
        public static final float SCENE_PREVIEW_Z = 35.0f;

        /** フォーカスインジケータ枠線のオーバーレイZ層 */
        public static final float FOCUS_INDICATOR_OVERLAY_Z = 280.0f;

        /** セレクトドロップダウンのポップアップオーバーレイZ層 */
        public static final float DROPDOWN_POPUP_OVERLAY_Z = 300.0f;

        /** ドラッグ＆ドロッププレビューのオーバーレイZ層 */
        public static final float DRAG_DROP_OVERLAY_Z = 320.0f;

        /** DevTools（インスペクタ）のオーバーレイZ層 */
        public static final float DEVTOOLS_OVERLAY_Z = 350.0f;
    }
}