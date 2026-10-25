package com.nishiyu.enderframelib.client.renderer;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.SessionContext;
import com.nishiyu.enderframelib.core.paint.model.Corners;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;
import static com.nishiyu.enderframelib.client.renderer.BackgroundRenderer.renderCircleBorderElement;
import static com.nishiyu.enderframelib.client.renderer.BackgroundRenderer.renderElementBackground;
import static com.nishiyu.enderframelib.client.renderer.BackgroundRenderer.renderRoundedBorderElement;
import static com.nishiyu.enderframelib.client.renderer.ControlElementRenderer.renderProgressElement;
import static com.nishiyu.enderframelib.client.renderer.ControlElementRenderer.renderRangeElement;
import static com.nishiyu.enderframelib.client.renderer.ControlElementRenderer.renderToggleElement;
import static com.nishiyu.enderframelib.client.renderer.MediaElementRenderer.renderImageOrMediaElement;
import static com.nishiyu.enderframelib.client.renderer.TextElementRenderer.renderTextOrInputElement;
import static com.nishiyu.enderframelib.client.renderer.TextElementRenderer.renderTextareaElement;
import static com.nishiyu.enderframelib.client.renderer.TextElementRenderer.resolveElementFont;

public class DisplayItemDrawer {
    public static final float RESOLUTION = 32.0f;

    public static boolean useSdfShader = true;

    static final Pattern TOOLTIP_SPLIT_PATTERN = Pattern.compile("\\\\n|\\n");

    // 以前ここに snapTextScaleFor2D があり、フォントの倍率を物理ピクセルの 0.5 刻みに
    // 丸めていた。倍率を変えると送り幅も変わるため、測定が決めた折り返し位置・省略位置と
    // 描画が食い違う原因になっていた。スナップするのは位置だけ（snapCoordFor2D）。

    static float snapCoordFor2D(float coord, float matScale, boolean is2D) {
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

    static boolean enableClipScissorFor2D(Matrix4f poseMat, MultiBufferSource buffer, float clipMinX, float clipMinY, float clipMaxX, float clipMaxY, float maxAllowedX, float maxAllowedY, boolean is2D) {
        if (!is2D) return false;
        if (clipMinX <= 0.5f && clipMinY <= 0.5f && clipMaxX >= maxAllowedX - 0.5f && clipMaxY >= maxAllowedY - 0.5f) {
            return false;
        }
        return enableScissorRect(poseMat, buffer, clipMinX, clipMinY, clipMaxX, clipMaxY);
    }

    /** 祖先の変形を掛け終えた姿勢と、祖先の不透明度の積（1 フレームの間だけ使い回す）。 */
    private record AncestorTransform(Matrix4f pose, Matrix3f normal, float opacity) {}

    /**
     * {@code id} の要素まで（その要素を含む）祖先の変形を掛けた姿勢を返す。結果は {@code memo} に覚え、
     * 同じ祖先を持つ兄弟・子孫では掛け直さない（以前は描画要素ごとに祖先の連鎖を根から辿り直していた）。
     * 呼ぶときの {@code poseStack} の先頭はフレームの基準の姿勢であること。要素が無ければ null。
     */
    static AncestorTransform resolveAncestorTransform(PoseStack poseStack, String id, String hoveredId, String sessionId,
                                                              Map<String, DisplayItem> elementMap,
                                                              Map<String, AncestorTransform> memo) {
        AncestorTransform cached = memo.get(id);
        if (cached != null) return cached;
        DisplayItem el = elementMap.get(id);
        if (el == null) return null;

        poseStack.pushPose();
        try {
            float[] cumulativeOpacity = {1.0f};
            String parentId = el.hints().parentId();
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

    static void applyOwnTransform(PoseStack poseStack, DisplayItem el, String hoveredId, String sessionId, float[] cumulativeOpacity) {
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
            boolean hasParentPose = el.hints().parentId() != null;
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
                String parentId = el.hints().parentId();
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
                    renderElementBackground(el, anim, poseStack, buffer, finalOpacity, zOffset,
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

    static final int Z_LEVEL_CELL = 4;

    static final int Z_LEVEL_CACHE_MAX = 16;

    static final Map<List<DisplayItem>, int[]> Z_LEVEL_CACHE = new IdentityHashMap<>();

    /**
     * 要素ごとの奥行きの段（0 始まり）。前に描いた要素と重なるなら、その最上段の 1 つ上に置く。重ならない要素は
     * 同じ段になるので、要素が千を超えても段は入れ子の深さ程度に収まる（奥行きを要素ごとに積むと、
     * 斜めから見た面が立体に見える）。格子で近似するので、重なりが無くても段が上がることはあるが、下がることはない。
     * 要素のリストは再描画まで同じインスタンスなので、そのインスタンスで結果を覚えておく。
     */
    static int[] zLevelsFor(List<DisplayItem> elements, int screenWidth, int screenHeight) {
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

    static int clamp(int v, int count) {
        return Math.max(0, Math.min(count - 1, v));
    }

    static String extractTooltipText(DisplayItem el) {
        if (el.hints().title() != null) {
            String t = el.hints().title();
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

    static void renderTooltipOverlay(PoseStack poseStack, MultiBufferSource buffer, Font font, DisplayItem target, String tooltipText, float maxW, float maxH, float zPos, int packedLight) {
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
