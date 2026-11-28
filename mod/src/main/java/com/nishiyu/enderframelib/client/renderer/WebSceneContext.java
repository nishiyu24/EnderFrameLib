package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.core.paint.model.*;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.SessionContext;
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

    public static WebSceneContext getOrCreate(String sessionId, String sceneId) {
        SessionContext session = ClientWebManager.getOrCreateSession(sessionId);
        return session.scenes().computeIfAbsent(stripSceneSuffix(sceneId), k -> new WebSceneContext());
    }

    public static WebSceneContext get(String sessionId, String sceneId) {
        if (sessionId == null || sceneId == null) return null;
        SessionContext session = ClientWebManager.getSession(sessionId);
        return session != null ? session.scenes().get(stripSceneSuffix(sceneId)) : null;
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

    /**
     * 箱（左上が原点の {@code w × h} CSS px）へ、ブロックの構造を描く。描く先は面の項目のテクスチャ
     * （{@link MinecraftHostTextures}）で、箱の外は描かれない。不透明度と角丸はコアが貼るときに掛ける。
     */
    void render(Map<String, String> events, PoseStack poseStack, MultiBufferSource buffer, float w, float h) {
        syncFromHtmlAttributes(events);
        if (blocks.isEmpty()) return;

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

        float sizeX = (maxX - minX + 1), sizeY = (maxY - minY + 1), sizeZ = (maxZ - minZ + 1);
        float maxSpan = Math.max(2.0f, Math.max(sizeX, Math.max(sizeY, sizeZ)));
        float centerX = minX + sizeX * 0.5f, centerY = minY + sizeY * 0.5f, centerZ = minZ + sizeZ * 0.5f;
        this.panX = Math.clamp(this.panX, -w * 0.42f, w * 0.42f);
        this.panY = Math.clamp(this.panY, -h * 0.42f, h * 0.42f);
        float baseScale = (Math.min(w, h) * 0.52f / maxSpan) * this.zoom;
        float currentYaw = this.rotationY;
        if (this.autoRotate) currentYaw += (net.minecraft.Util.getMillis() % 36000L) / 1000.0f * autoRotateSpeed;

        Minecraft mc = Minecraft.getInstance();
        BlockRenderDispatcher blockRenderer = mc.getBlockRenderer();
        Font font = mc.font;
        poseStack.pushPose();
        try {
            poseStack.translate(w * 0.5f + this.panX, h * 0.5f + this.panY, 100f);
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
                    blockRenderer.renderSingleBlock(state, poseStack, buffer, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                            ModelData.EMPTY, null);
                } catch (Exception ignored) {
                    // 描けないブロック（特殊なモデルなど）は飛ばす
                }
                poseStack.popPose();
            }

            if (!highlights.isEmpty()) {
                VertexConsumer lineBuilder = buffer.getBuffer(RenderType.gui());
                for (Map.Entry<BlockPos, Integer> hl : highlights.entrySet()) {
                    if (hl.getKey().getY() > maxVisibleY) continue;
                    drawHighlightBox(poseStack, lineBuilder, hl.getKey(), hl.getValue(), 1f);
                }
            }

            for (Map.Entry<BlockPos, String> lbl : labels.entrySet()) {
                BlockPos pos = lbl.getKey();
                String text = lbl.getValue();
                if (pos.getY() > maxVisibleY || text == null || text.isEmpty()) continue;
                poseStack.pushPose();
                poseStack.translate(pos.getX() + 0.5f, pos.getY() + 1.25f, pos.getZ() + 0.5f);
                poseStack.mulPose(Axis.YP.rotationDegrees(-currentYaw));
                poseStack.mulPose(Axis.XP.rotationDegrees(-this.rotationX));
                poseStack.scale(0.025f, -0.025f, 0.025f);
                font.drawInBatch(text, -font.width(text) / 2.0f, 0, 0xFFFFFFFF, true, poseStack.last().pose(), buffer,
                        Font.DisplayMode.NORMAL, 0x88000000, LightTexture.FULL_BRIGHT);
                poseStack.popPose();
            }
            if (buffer instanceof MultiBufferSource.BufferSource bs) bs.endBatch();
        } finally {
            poseStack.popPose();
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