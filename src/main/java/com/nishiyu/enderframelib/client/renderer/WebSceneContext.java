package com.nishiyu.enderframelib.client.renderer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.nishiyu.enderframelib.core.model.UIElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WebSceneContext {

    private static final Map<String, Map<String, WebSceneContext>> SESSION_SCENES = new ConcurrentHashMap<>();

    public static WebSceneContext getOrCreate(String sessionId, String sceneId) {
        String safeSessionId = (sessionId != null && !sessionId.isEmpty()) ? sessionId : "unknown";
        String baseId = stripSceneSuffix(sceneId);
        return SESSION_SCENES
                .computeIfAbsent(safeSessionId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(baseId, k -> new WebSceneContext());
    }

    public static WebSceneContext get(String sessionId, String sceneId) {
        if (sessionId == null || sceneId == null) return null;
        Map<String, WebSceneContext> map = SESSION_SCENES.get(sessionId);
        return map != null ? map.get(stripSceneSuffix(sceneId)) : null;
    }

    public static void clearSession(String sessionId) {
        if (sessionId != null) {
            SESSION_SCENES.remove(sessionId);
        }
    }

    private static String stripSceneSuffix(String id) {
        return (id != null && id.endsWith("_scene")) ? id.substring(0, id.length() - 6) : id;
    }

    private final Map<BlockPos, BlockState> blocks = new ConcurrentHashMap<>();
    private final Map<BlockPos, Integer> highlights = new ConcurrentHashMap<>();
    private final Map<BlockPos, String> labels = new ConcurrentHashMap<>();

    private float rotationX = 25.0f;
    private float rotationY = -45.0f;
    private float zoom = 1.0f;
    private float panX = 0.0f;
    private float panY = 0.0f;
    private int maxVisibleY = Integer.MAX_VALUE;
    private boolean autoRotate = false;
    private final float autoRotateSpeed = 20.0f;
    private String lastParsedSpec = "";

    public WebSceneContext clear() {
        blocks.clear();
        highlights.clear();
        labels.clear();
        lastParsedSpec = "";
        return this;
    }

    public WebSceneContext setBlock(int x, int y, int z, BlockState state) {
        BlockPos pos = new BlockPos(x, y, z);
        if (state == null || state.isAir()) blocks.remove(pos);
        else blocks.put(pos, state);
        return this;
    }

    public WebSceneContext setBlock(int x, int y, int z, String blockId) {
        try {
            String cleanId = blockId.trim().toLowerCase(Locale.ROOT);
            Block b = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(cleanId));
            BlockPos pos = new BlockPos(x, y, z);
            if (b != Blocks.AIR) {
                blocks.put(pos, b.defaultBlockState());
            } else {
                blocks.remove(pos);
            }
        } catch (Exception ignored) {}
        return this;
    }

    public WebSceneContext fillBlocks(int x1, int y1, int z1, int x2, int y2, int z2, BlockState state) {
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    setBlock(x, y, z, state);
                }
            }
        }
        return this;
    }

    public WebSceneContext highlightBlock(int x, int y, int z, int argbColor) {
        highlights.put(new BlockPos(x, y, z), argbColor);
        return this;
    }

    public WebSceneContext clearHighlights() {
        highlights.clear();
        return this;
    }

    public WebSceneContext setBlockLabel(int x, int y, int z, String labelText) {
        BlockPos pos = new BlockPos(x, y, z);
        if (labelText == null || labelText.isEmpty()) labels.remove(pos);
        else labels.put(pos, labelText);
        return this;
    }

    public WebSceneContext setMaxVisibleY(int maxY) {
        this.maxVisibleY = maxY;
        return this;
    }

    public int getMaxVisibleY() {
        return maxVisibleY;
    }

    public WebSceneContext setRotation(float pitch, float yaw) {
        this.rotationX = Math.clamp(pitch, -85.0f, 85.0f);
        this.rotationY = yaw;
        return this;
    }

    public WebSceneContext addRotation(float deltaPitch, float deltaYaw) {
        this.rotationX = Math.clamp(this.rotationX + deltaPitch, -85.0f, 85.0f);
        this.rotationY = (this.rotationY + deltaYaw) % 360.0f;
        return this;
    }

    public WebSceneContext setZoom(float zoom) {
        this.zoom = Math.clamp(zoom, 0.35f, 3.0f);
        return this;
    }

    public WebSceneContext addZoom(float deltaZoom) {
        this.zoom = Math.clamp(this.zoom + deltaZoom, 0.35f, 3.0f);
        return this;
    }

    public WebSceneContext addPan(float dx, float dy) {
        this.panX = Math.clamp(this.panX + dx, -80.0f, 80.0f);
        this.panY = Math.clamp(this.panY + dy, -60.0f, 60.0f);
        return this;
    }

    public WebSceneContext resetCamera() {
        this.rotationX = 25.0f;
        this.rotationY = -45.0f;
        this.zoom = 1.0f;
        this.panX = 0.0f;
        this.panY = 0.0f;
        return this;
    }

    public WebSceneContext setAutoRotate(boolean enable) {
        this.autoRotate = enable;
        return this;
    }

    public void syncFromHtmlAttributes(Map<String, String> events) {
        if (events == null) return;
        if (events.containsKey("data-slice-y")) {
            try {
                this.maxVisibleY = Integer.parseInt(events.get("data-slice-y").trim());
            } catch (NumberFormatException ignored) {
                this.maxVisibleY = Integer.MAX_VALUE;
            }
        }
        if (events.containsKey("data-auto-rotate")) {
            this.autoRotate = "true".equalsIgnoreCase(events.get("data-auto-rotate").trim());
        }
        String spec = events.get("data-blocks");
        if (spec != null && !spec.isEmpty() && !spec.equals(lastParsedSpec)) {
            lastParsedSpec = spec;
            blocks.clear();
            for (String entry : spec.split(";")) {
                entry = entry.trim();
                if (entry.isEmpty()) continue;
                int colon = entry.indexOf(':');
                if (colon > 0) {
                    String[] coords = entry.substring(0, colon).split(",");
                    String blockId = entry.substring(colon + 1).trim();
                    if (coords.length >= 3) {
                        try {
                            int x = Integer.parseInt(coords[0].trim());
                            int y = Integer.parseInt(coords[1].trim());
                            int z = Integer.parseInt(coords[2].trim());
                            setBlock(x, y, z, blockId);
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
        }
    }

    public void renderScene(UIElement el, PoseStack poseStack, MultiBufferSource buffer, float finalOpacity, float zOffset) {
        syncFromHtmlAttributes(el.events());
        if (blocks.isEmpty() || finalOpacity <= 0.01f) return;

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        int visibleCount = 0;

        for (BlockPos pos : blocks.keySet()) {
            if (pos.getY() > maxVisibleY) continue;
            minX = Math.min(minX, pos.getX()); maxX = Math.max(maxX, pos.getX());
            minY = Math.min(minY, pos.getY()); maxY = Math.max(maxY, pos.getY());
            minZ = Math.min(minZ, pos.getZ()); maxZ = Math.max(maxZ, pos.getZ());
            visibleCount++;
        }
        if (visibleCount == 0) return;

        float sizeX = (maxX - minX + 1);
        float sizeY = (maxY - minY + 1);
        float sizeZ = (maxZ - minZ + 1);
        float maxSpan = Math.max(2.0f, Math.max(sizeX, Math.max(sizeY, sizeZ)));

        float centerX = minX + sizeX * 0.5f;
        float centerY = minY + sizeY * 0.5f;
        float centerZ = minZ + sizeZ * 0.5f;

        float maxPanX = el.width() * 0.42f;
        float maxPanY = el.height() * 0.42f;
        this.panX = Math.clamp(this.panX, -maxPanX, maxPanX);
        this.panY = Math.clamp(this.panY, -maxPanY, maxPanY);

        float baseScale = (Math.min(el.width(), el.height()) * 0.52f / maxSpan) * this.zoom;
        float screenCX = el.x() + el.width() * 0.5f + this.panX;
        float screenCY = el.y() + el.height() * 0.5f + this.panY;

        float currentYaw = this.rotationY;
        if (this.autoRotate) {
            currentYaw += (System.currentTimeMillis() % 36000L) / 1000.0f * autoRotateSpeed;
        }

        Minecraft mc = Minecraft.getInstance();
        BlockRenderDispatcher blockRenderer = mc.getBlockRenderer();
        Font font = mc.font;

        // WebRenderCore.enableScissorRect でこれまでのバッチをフラッシュし、<mc-scene> の矩形で OpenGL Scissor を有効化
        boolean scissorEnabled = WebRenderCore.enableScissorRect(
                poseStack.last().pose(), buffer,
                el.x(), el.y(), el.x() + el.width(), el.y() + el.height()
        );

        poseStack.pushPose();
        try {
            poseStack.translate(screenCX, screenCY, zOffset + ZLayers.SCENE_PREVIEW_Z);
            poseStack.scale(baseScale, -baseScale, baseScale);
            poseStack.mulPose(Axis.XP.rotationDegrees(this.rotationX));
            poseStack.mulPose(Axis.YP.rotationDegrees(currentYaw));
            poseStack.translate(-centerX, -centerY, -centerZ);

            for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
                BlockPos pos = entry.getKey();
                if (pos.getY() > maxVisibleY) continue;
                BlockState state = entry.getValue();
                if (state.getRenderShape() == RenderShape.INVISIBLE) continue;

                poseStack.pushPose();
                poseStack.translate(pos.getX(), pos.getY(), pos.getZ());
                try {
                    blockRenderer.renderSingleBlock(
                            state, poseStack, buffer,
                            LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                            ModelData.EMPTY, null
                    );
                } catch (Exception ignored) {}
                poseStack.popPose();
            }

            if (!highlights.isEmpty()) {
                VertexConsumer lineBuilder = buffer.getBuffer(RenderType.gui());
                for (Map.Entry<BlockPos, Integer> hl : highlights.entrySet()) {
                    BlockPos pos = hl.getKey();
                    if (pos.getY() > maxVisibleY) continue;
                    drawHighlightBox(poseStack, lineBuilder, pos, hl.getValue(), finalOpacity);
                }
            }

            if (!labels.isEmpty()) {
                for (Map.Entry<BlockPos, String> lbl : labels.entrySet()) {
                    BlockPos pos = lbl.getKey();
                    if (pos.getY() > maxVisibleY) continue;
                    String text = lbl.getValue();
                    if (text == null || text.isEmpty()) continue;

                    poseStack.pushPose();
                    poseStack.translate(pos.getX() + 0.5f, pos.getY() + 1.25f, pos.getZ() + 0.5f);
                    poseStack.mulPose(Axis.YP.rotationDegrees(-currentYaw));
                    poseStack.mulPose(Axis.XP.rotationDegrees(-this.rotationX));
                    float textScale = 0.025f;
                    poseStack.scale(textScale, -textScale, textScale);
                    int tw = font.width(text);
                    font.drawInBatch(text, -tw / 2.0f, 0, 0xFFFFFFFF, true, poseStack.last().pose(), buffer, Font.DisplayMode.NORMAL, 0x88000000, LightTexture.FULL_BRIGHT);
                    poseStack.popPose();
                }
            }

            if (buffer instanceof MultiBufferSource.BufferSource bs) {
                bs.endBatch();
            }
        } finally {
            poseStack.popPose();
            if (scissorEnabled) {
                RenderSystem.disableScissor();
            }
        }
    }

    private static void drawHighlightBox(PoseStack poseStack, VertexConsumer builder, BlockPos pos, int color, float opacity) {
        float a = ((color >>> 24) & 0xFF) / 255.0f * opacity;
        if (a <= 0.01f) a = 0.45f * opacity;
        float r = ((color >>> 16) & 0xFF) / 255.0f;
        float g = ((color >>> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;

        float minX = pos.getX() - 0.02f, minY = pos.getY() - 0.02f, minZ = pos.getZ() - 0.02f;
        float maxX = pos.getX() + 1.02f, maxY = pos.getY() + 1.02f, maxZ = pos.getZ() + 1.02f;
        Matrix4f m = poseStack.last().pose();

        builder.addVertex(m, minX, maxY, minZ).setColor(r, g, b, a);
        builder.addVertex(m, minX, maxY, maxZ).setColor(r, g, b, a);
        builder.addVertex(m, maxX, maxY, maxZ).setColor(r, g, b, a);
        builder.addVertex(m, maxX, maxY, minZ).setColor(r, g, b, a);

        builder.addVertex(m, minX, minY, maxZ).setColor(r, g, b, a);
        builder.addVertex(m, minX, maxY, maxZ).setColor(r, g, b, a);
        builder.addVertex(m, maxX, maxY, maxZ).setColor(r, g, b, a);
        builder.addVertex(m, maxX, minY, maxZ).setColor(r, g, b, a);

        builder.addVertex(m, maxX, minY, minZ).setColor(r, g, b, a);
        builder.addVertex(m, maxX, maxY, minZ).setColor(r, g, b, a);
        builder.addVertex(m, minX, maxY, minZ).setColor(r, g, b, a);
        builder.addVertex(m, minX, minY, minZ).setColor(r, g, b, a);
    }
}