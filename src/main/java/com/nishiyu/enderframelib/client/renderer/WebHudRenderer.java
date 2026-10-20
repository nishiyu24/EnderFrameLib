package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.EnderFrameLib;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.nishiyu.enderframelib.client.ClientLocalEnvProvider;
import com.nishiyu.enderframelib.client.ClientScreenInteractionManager;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.ReactiveStateStore;
import com.nishiyu.enderframelib.client.SessionContext;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.css.StyleSheet;
import com.nishiyu.enderframelib.core.dom.TemplateEngine;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.DisplayItem;
import com.nishiyu.enderframelib.network.EnderFrameNetwork.SubscribeC2SPacket;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3f;

import java.lang.reflect.Array;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class WebHudRenderer {

    private static final float VIRTUAL_HEIGHT = 720.0f;

    public static String customActiveHudSessionId = "";
    public static Function<Minecraft, String> activeHudSessionResolver = WebHudRenderer::defaultResolveActiveHudSession;
    public static java.util.function.Consumer<SubscribeC2SPacket> packetSender = PacketDistributor::sendToServer;

    public static class HudTemplateConfig {
        public String containerId;
        public String origDisplay;
        public HtmlNode templateRoot;
        public List<String> requireNbt = new ArrayList<>();
    }

    public static class TrackerTemplateConfig {
        public double radius = 24.0;
        public double yOffset = 0.8;
        public String targetType = "living";
        public List<String> requireNbt = new ArrayList<>();
    }

    public static class TrackerEntry {
        public final TrackerTemplateConfig config;
        public final HtmlNode templateNode;

        public TrackerEntry(TrackerTemplateConfig config, HtmlNode templateNode) {
            this.config = config;
            this.templateNode = templateNode;
        }
    }

    private static final Map<UUID, TrackerCache> trackerCaches = new ConcurrentHashMap<>();
    private static final Map<Integer, JsonObject> serverSyncedTrackerData = new ConcurrentHashMap<>();
    private static JsonObject latestTargetData = null;
    private static boolean pendingSubscriptionSync = false;

    private static int lastSentWidth = -1;
    private static int lastSentHeight = -1;

    static {
        try {
            NeoForge.EVENT_BUS.addListener(WebHudRenderer::onRenderGui);
            NeoForge.EVENT_BUS.addListener(WebHudRenderer::onRenderLevelStage);
        } catch (Throwable t) {
            EnderFrameLib.LOGGER.error("Failed to register WebHudRenderer render listeners; "
                    + "HUD templates and entity trackers will not be drawn", t);
        }
    }

    public static String defaultResolveActiveHudSession(Minecraft mc) {
        if (mc.player == null || mc.options.hideGui) return null;
        if (customActiveHudSessionId != null && !customActiveHudSessionId.isEmpty()) {
            return customActiveHudSessionId;
        }
        ItemStack headItem = mc.player.getItemBySlot(EquipmentSlot.HEAD);
        if (headItem.isEmpty()) return null;
        String sessionId = ClientScreenInteractionManager.getSessionIdFromItem(headItem);
        if (sessionId == null || sessionId.isEmpty()) return null;
        if (!ClientScreenInteractionManager.isItemInRange(mc, headItem)) return null;
        return sessionId;
    }

    public static void requestSubscriptionSync() {
        pendingSubscriptionSync = true;
    }

    public static void enableHudTemplate(String sessionId, String containerId, String origDisplay, HtmlNode templateRoot, List<String> requireNbt) {
        HudTemplateConfig config = new HudTemplateConfig();
        config.containerId = containerId;
        config.origDisplay = origDisplay;
        config.templateRoot = templateRoot;
        config.requireNbt.addAll(requireNbt);
        SessionContext session = ClientWebManager.getOrCreateSession(sessionId);
        session.setHudConfig(config);
        session.setLastHudData(null);
        requestSubscriptionSync();
    }

    public static void setTrackerTemplate(String sessionId, HtmlNode templateNode, double radius, double yOffset, String targetType, List<String> requireNbt) {
        TrackerTemplateConfig config = new TrackerTemplateConfig();
        config.radius = radius;
        config.yOffset = yOffset;
        config.targetType = targetType;
        config.requireNbt.addAll(requireNbt);

        ClientWebManager.getOrCreateSession(sessionId).setTrackerEntry(new TrackerEntry(config, templateNode));
        requestSubscriptionSync();
    }

    /** セッションの HUD 設定が消えたことを {@link SessionContext#close()} から知らされる。 */
    public static void onSessionStateCleared() {
        boolean anyTracker = false;
        for (SessionContext session : ClientWebManager.sessions()) {
            if (session.trackerEntry() != null) {
                anyTracker = true;
                break;
            }
        }
        if (!anyTracker) {
            trackerCaches.clear();
        }
        requestSubscriptionSync();
    }

    private static void executeSubscriptionSync() {
        boolean needsHud = false;
        boolean needsTracker = false;
        double radius = 24.0;
        String type = "living";

        List<String> trackerNbtPaths = new ArrayList<>();
        List<String> hudNbtPaths = new ArrayList<>();
        for (SessionContext session : ClientWebManager.sessions()) {
            TrackerEntry te = session.trackerEntry();
            if (te != null) {
                needsTracker = true;
                radius = Math.max(radius, te.config.radius);
                type = te.config.targetType;
                trackerNbtPaths.addAll(te.config.requireNbt);
            }
            HudTemplateConfig cfg = session.hudConfig();
            if (cfg != null) {
                needsHud = true;
                hudNbtPaths.addAll(cfg.requireNbt);
            }
        }

        SubscribeC2SPacket packet = new SubscribeC2SPacket("", false, needsHud, needsTracker, radius, type, hudNbtPaths, trackerNbtPaths);
        if (packetSender != null) {
            packetSender.accept(packet);
        } else {
            PacketDistributor.sendToServer(packet);
        }
    }

    public static void onRenderGui(RenderGuiEvent.Post event) {
        ClientWebManager.processRenderQueue();

        if (pendingSubscriptionSync) {
            executeSubscriptionSync();
            pendingSubscriptionSync = false;
        }

        Minecraft mc = Minecraft.getInstance();
        String screenKey = activeHudSessionResolver != null ? activeHudSessionResolver.apply(mc) : null;
        if (screenKey == null || screenKey.isEmpty()) return;

        ClientScreenInteractionManager.ensureSynced(screenKey);

        updateScreenLayout(mc, screenKey);
        processHudTemplates(mc, screenKey);
        renderHudElements(event.getGuiGraphics().pose(), mc, screenKey);
    }

    private static void updateScreenLayout(Minecraft mc, String screenKey) {
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int virtualHeight = (int) VIRTUAL_HEIGHT;
        int virtualWidth = (int) (VIRTUAL_HEIGHT * ((float) screenWidth / screenHeight));

        if (ClientWebManager.getLastRootW(screenKey) != virtualWidth ||
                ClientWebManager.getLastRootH(screenKey) != virtualHeight) {
            ClientWebManager.recomputeLayout(screenKey, virtualWidth, virtualHeight);
        }

        lastSentWidth = virtualWidth;
        lastSentHeight = virtualHeight;
    }

    private static void processHudTemplates(Minecraft mc, String screenKey) {
        SessionContext session = ClientWebManager.getSession(screenKey);
        HudTemplateConfig config = session != null ? session.hudConfig() : null;
        if (config == null || config.templateRoot == null) return;

        HtmlNode vRoot = ClientWebManager.getRootNode(screenKey);
        if (vRoot == null) return;

        HtmlNode containerNode = vRoot.getElementById(config.containerId);
        if (containerNode == null) return;

        Map<String, String> dataMap = extractHudData(mc.hitResult);
        Map<String, String> prevData = session.lastHudData();

        if (prevData != null && prevData.equals(dataMap)) {
            return;
        }
        session.setLastHudData(new HashMap<>(dataMap));

        if (dataMap.isEmpty()) {
            containerNode.setStyleProperty("display", "none");
        } else {
            containerNode.setStyleProperty("display", config.origDisplay);
            HtmlNode clonedTemplate = config.templateRoot.cloneNode();
            applyTemplateData(clonedTemplate, dataMap);

            containerNode.children.clear();
            for (HtmlNode child : clonedTemplate.children) {
                child.parent = containerNode;
                containerNode.children.add(child);
            }
        }
        ClientWebManager.requestRender(screenKey);
    }

    private static void renderHudElements(PoseStack poseStack, Minecraft mc, String screenKey) {
        List<DisplayItem> elements = ClientWebManager.getElements(screenKey);
        if (elements.isEmpty()) return;

        List<DisplayItem> hudElements = new ArrayList<>(elements.size());
        for (DisplayItem el : elements) {
            if (el.events() != null && el.events().containsKey("data-mount-entity")) continue;
            hudElements.add(el);
        }
        if (hudElements.isEmpty()) return;

        poseStack.pushPose();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        float screenScale = (float) screenHeight / VIRTUAL_HEIGHT;
        poseStack.scale(screenScale, screenScale, 1.0f);

        var bufferSource = mc.renderBuffers().bufferSource();
        int logicWidth = (int) Math.ceil((float) lastSentWidth / DisplayItemDrawer.RESOLUTION);
        int logicHeight = (int) Math.ceil((float) lastSentHeight / DisplayItemDrawer.RESOLUTION);

        PaintRequest.builder(hudElements, logicWidth, logicHeight, poseStack, bufferSource, screenKey, mc.level)
                .light(0xF000F0, OverlayTexture.NO_OVERLAY)
                .zOffsets(0.0f, 0.0f)
                .hoveredId(ClientScreenInteractionManager.getHoveredId(screenKey))
                .audio(false, true, null)
                .render();

        bufferSource.endBatch();
        poseStack.popPose();
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;

        Minecraft mc = Minecraft.getInstance();
        String screenKey = activeHudSessionResolver != null ? activeHudSessionResolver.apply(mc) : null;
        if (screenKey == null || screenKey.isEmpty()) return;

        SessionContext session = ClientWebManager.getSession(screenKey);
        TrackerEntry entry = session != null ? session.trackerEntry() : null;
        if (entry != null) {
            renderTrackersInWorld(event.getPoseStack(), mc, screenKey, entry);
        }
    }

    private static void renderTrackersInWorld(PoseStack poseStack, Minecraft mc, String screenKey, TrackerEntry entry) {
        if (mc.player == null || mc.level == null) return;
        Camera camera = mc.gameRenderer.getMainCamera();
        var bufferSource = mc.renderBuffers().bufferSource();
        boolean renderedAny = false;

        AABB box = mc.player.getBoundingBox().inflate(entry.config.radius);
        Class<? extends LivingEntity> targetClass = getTargetClass(entry.config.targetType);
        List<? extends LivingEntity> entities = mc.level.getEntitiesOfClass(targetClass, box);

        Set<UUID> activeUuids = new HashSet<>(entities.size());
        for (LivingEntity le : entities) {
            if (le == mc.player) continue;
            activeUuids.add(le.getUUID());
            if (processAndRenderSingleTracker(poseStack, bufferSource, camera, mc, le, screenKey, entry)) {
                renderedAny = true;
            }
        }

        if (trackerCaches.size() > activeUuids.size() + 10) {
            trackerCaches.keySet().retainAll(activeUuids);
        }

        if (renderedAny) {
            bufferSource.endBatch();
        }
    }

    private static void populateLivingEntityData(LivingEntity le, Map<String, String> dataMap) {
        dataMap.put("name", le.getDisplayName().getString());
        dataMap.put("hp", String.valueOf((int) le.getHealth()));
        dataMap.put("max_hp", String.valueOf((int) le.getMaxHealth()));
        dataMap.put("hp_percent", String.valueOf(Math.round((le.getHealth() / Math.max(1f, le.getMaxHealth())) * 100)));
        dataMap.put("armor", String.valueOf(le.getArmorValue()));

        JsonArray effectsArray = new JsonArray();
        for (MobEffectInstance effect : le.getActiveEffects()) {
            ResourceLocation id = BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value());
            if (id != null) {
                JsonObject obj = new JsonObject();
                obj.addProperty("id", id.toString());
                obj.addProperty("amplifier", effect.getAmplifier());
                obj.addProperty("duration", effect.getDuration());
                effectsArray.add(obj);
            }
        }
        if (!effectsArray.isEmpty()) {
            String json = effectsArray.toString();
            dataMap.put("active_effects", json);
            dataMap.put("nbt.active_effects", json);
        }
    }

    private static void populateSyncedTrackerData(LivingEntity le, Map<String, String> dataMap, TrackerTemplateConfig config) {
        JsonObject syncedData = serverSyncedTrackerData.get(le.getId());
        if (syncedData == null) return;
        Map<String, String> flatData = new HashMap<>();
        flattenJsonToMap(syncedData, "", flatData);

        for (String searchKey2 : config.requireNbt) {
            String searchKey1 = "nbt." + searchKey2;
            String rawValue = flatData.getOrDefault(searchKey1, flatData.get(searchKey2));

            if (rawValue != null) {
                if ((rawValue.startsWith("[") || rawValue.startsWith("{")) && !rawValue.startsWith("{\"")) {
                    try {
                        CompoundTag dummy = TagParser.parseTag("{data:" + rawValue + "}");
                        JsonElement jsonElem = toJsonElement(dummy.get("data"));
                        if (jsonElem != null) rawValue = jsonElem.toString();
                    } catch (Exception ignored) {}
                }
                dataMap.put(searchKey1, rawValue);
            }
        }
    }

    private static void buildTrackerLayout(HtmlNode instanceNode, String screenKey, TrackerCache cache) {
        UiDocumentCompiler.Document doc = ClientWebManager.getDocument(screenKey);
        if (doc == null) return;

        com.nishiyu.enderframelib.core.css.StyleResolver.StyleSet styleSet = com.nishiyu.enderframelib.core.css.StyleResolver.resolveStyleSet(instanceNode, Map.of("display", "inline-block"), doc.sheet);
        Map<String, String> rawStyle = styleSet.resolve(com.nishiyu.enderframelib.core.css.Selector.ElementState.NONE);
        com.nishiyu.enderframelib.core.css.ComputedStyle computed = com.nishiyu.enderframelib.core.css.StyleResolver.finalizeComputedStyle(rawStyle, doc.sheet, null);

        LayoutBox layoutBox = new LayoutBox(instanceNode, computed);
        buildLayoutTreeRecursively(instanceNode, layoutBox, doc.sheet);
        com.nishiyu.enderframelib.core.layout.BoxTreeBuilder.build(layoutBox);

        com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutContext.Viewport viewport = new com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutContext.Viewport(1000, 1000);
        com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutContext.ScrollState scrollState = new com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutContext.ScrollState();
        com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutContext ctx = new com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutContext(
                viewport, UiDocumentCompiler.intrinsicSizeResolver, scrollState, ClientWebManager.CLIENT_FONTS);
        com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutContext.FormattingContext fmtCtx = new com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutContext.FormattingContext("block", "stretch", false);

        layoutBox.measure(1000, 1000, fmtCtx, ctx);
        layoutBox.measure(1000, 1000, fmtCtx, ctx);
        layoutBox.layout(0, 0, 1000, 1000, ctx);

        cache.width = Math.round(layoutBox.w);
        cache.height = Math.round(layoutBox.h);

        DisplayListBuilder renderer = new DisplayListBuilder(cache.width, cache.height, doc.sheet.keyframes, scrollState);
        cache.elements = renderer.render(layoutBox);
    }

    private static boolean renderTrackerBillboard(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource, Camera camera, Minecraft mc, LivingEntity le, String screenKey, TrackerCache cache, TrackerTemplateConfig config) {
        if (cache.elements == null || cache.elements.isEmpty()) return false;

        double x = le.getX();
        double y = le.getY() + le.getBbHeight() + config.yOffset;
        double z = le.getZ();

        Vec3 dir = new Vec3(x - camera.getPosition().x, y - camera.getPosition().y, z - camera.getPosition().z);
        Vector3f viewVecF = camera.getLookVector();
        Vec3 viewVec = new Vec3(viewVecF.x(), viewVecF.y(), viewVecF.z());
        if (dir.dot(viewVec) <= 0) return false;

        poseStack.pushPose();
        poseStack.translate(x - camera.getPosition().x, y - camera.getPosition().y, z - camera.getPosition().z);
        poseStack.mulPose(Axis.YP.rotationDegrees(-camera.getYRot()));
        poseStack.mulPose(Axis.XP.rotationDegrees(camera.getXRot()));

        float scale = 0.008f;
        poseStack.scale(-scale, -scale, scale);
        poseStack.translate(-cache.width / 2.0f, -cache.height, 0);

        PaintRequest.forBillboard(
                cache.elements, cache.width, cache.height,
                poseStack, bufferSource,
                screenKey, ClientScreenInteractionManager.getHoveredId(screenKey),
                mc.level
        ).render();
        poseStack.popPose();
        return true;
    }

    private static boolean processAndRenderSingleTracker(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource, Camera camera, Minecraft mc, LivingEntity le, String screenKey, TrackerEntry entry) {
        UUID uuid = le.getUUID();
        Map<String, String> dataMap = new HashMap<>();
        populateLivingEntityData(le, dataMap);
        populateSyncedTrackerData(le, dataMap, entry.config);

        TrackerCache cache = trackerCaches.get(uuid);
        if (cache == null || cache.isChanged(dataMap)) {
            cache = new TrackerCache();
            cache.data.putAll(dataMap);

            HtmlNode instanceNode = entry.templateNode.cloneNode();
            applyTemplateData(instanceNode, dataMap);
            instanceNode.attrs.put("style", instanceNode.attrs.getOrDefault("style", "") + "; display: flex;");

            buildTrackerLayout(instanceNode, screenKey, cache);
            trackerCaches.put(uuid, cache);
        }

        return renderTrackerBillboard(poseStack, bufferSource, camera, mc, le, screenKey, cache, entry.config);
    }

    private static Class<? extends LivingEntity> getTargetClass(String targetType) {
        if ("player".equalsIgnoreCase(targetType)) return Player.class;
        if ("monster".equalsIgnoreCase(targetType)) return Monster.class;
        if ("animal".equalsIgnoreCase(targetType)) return Animal.class;
        return LivingEntity.class;
    }

    private static Map<String, String> extractHudData(HitResult hitResult) {
        Map<String, String> map = new HashMap<>();
        if (hitResult == null || hitResult.getType() == HitResult.Type.MISS) return map;

        Minecraft mc = Minecraft.getInstance();
        if (hitResult.getType() == HitResult.Type.ENTITY) {
            EntityHitResult ehr = (EntityHitResult) hitResult;
            if (ehr.getEntity() instanceof LivingEntity le) {
                populateLivingEntityData(le, map);
                map.put("is_on_fire", String.valueOf(le.isOnFire()));
                map.put("type", "entity");
                map.put("is_entity", "true");
            }
        } else if (hitResult.getType() == HitResult.Type.BLOCK) {
            BlockHitResult bhr = (BlockHitResult) hitResult;
            if (mc.level != null) {
                BlockState state = mc.level.getBlockState(bhr.getBlockPos());
                map.put("name", state.getBlock().getName().getString());
                map.put("icon", "minecraft:" + BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath());
                map.put("hardness", String.valueOf(state.getDestroySpeed(mc.level, bhr.getBlockPos())));
                map.put("type", "block");
                map.put("is_block", "true");
            }
        }

        if (latestTargetData != null) flattenJsonToMap(latestTargetData, "nbt.", map);
        return map;
    }

    private static void flattenJsonToMap(JsonElement element, String prefix, Map<String, String> map) {
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                JsonElement child = entry.getValue();
                String newPrefix = prefix.isEmpty() ? entry.getKey() : prefix + entry.getKey();
                if (child.isJsonPrimitive()) {
                    map.put(newPrefix, child.getAsString());
                } else {
                    map.put(newPrefix, child.toString());
                    flattenJsonToMap(child, newPrefix + ".", map);
                }
            }
        } else if (element.isJsonArray()) {
            int i = 0;
            for (JsonElement child : element.getAsJsonArray()) {
                String newPrefix = prefix + i;
                if (child.isJsonPrimitive()) {
                    map.put(newPrefix, child.getAsString());
                } else {
                    map.put(newPrefix, child.toString());
                    flattenJsonToMap(child, newPrefix + ".", map);
                }
                i++;
            }
        }
    }

    public static void applyTemplateData(HtmlNode node, Map<String, String> data) {
        ClientLocalEnvProvider.applyI18nAttributes(node);
        Map<String, String> localEnv = ClientLocalEnvProvider.captureCurrentEnv();

        TemplateEngine.applyTemplate(node, text -> TemplateEngine.resolve(text, data, localEnv));

        if (node.attrs.containsKey("data-show")) {
            String showVal = node.attrs.get("data-show").trim();
            Boolean condResult = ReactiveStateStore.evaluateCondition(showVal, data);
            boolean hide = (condResult != null) ? !condResult
                    : (showVal.isEmpty() || "false".equalsIgnoreCase(showVal) || "0".equals(showVal) || showVal.contains("{{"));
            if (hide) node.setStyleProperty("display", "none");
            node.attrs.remove("data-show");
        }

        if (node.attrs.containsKey("data-list")) {
            String jsonStr = node.attrs.remove("data-list");
            List<HtmlNode> templateChildren = ReactiveStateStore.extractTemplateChildren(node);
            node.children.clear();

            if (jsonStr != null && !jsonStr.isEmpty() && !jsonStr.contains("{{")) {
                try {
                    JsonElement parsedJson = JsonParser.parseString(jsonStr);
                    if (parsedJson.isJsonObject()) {
                        for (Map.Entry<String, JsonElement> entry : parsedJson.getAsJsonObject().entrySet()) {
                            if (!entry.getValue().isJsonPrimitive()) continue;
                            Map<String, String> localData = new HashMap<>(data);
                            localData.put("_key", entry.getKey());
                            localData.put("_value", entry.getValue().getAsString());
                            appendClonedTemplateItems(node, templateChildren, localData);
                        }
                    } else if (parsedJson.isJsonArray()) {
                        int idx = 0;
                        for (JsonElement elem : parsedJson.getAsJsonArray()) {
                            Map<String, String> localData = new HashMap<>(data);
                            localData.put("_key", String.valueOf(idx++));
                            if (elem.isJsonPrimitive()) {
                                localData.put("_value", elem.getAsString());
                            } else if (elem.isJsonObject()) {
                                for (Map.Entry<String, JsonElement> objEntry : elem.getAsJsonObject().entrySet()) {
                                    if (objEntry.getValue().isJsonPrimitive()) {
                                        localData.put(objEntry.getKey(), objEntry.getValue().getAsString());
                                    }
                                }
                                localData.put("_value", elem.toString());
                            } else {
                                continue;
                            }
                            appendClonedTemplateItems(node, templateChildren, localData);
                        }
                    }
                } catch (Exception ignored) {}
            }
            return;
        }

        for (HtmlNode child : node.children) {
            applyTemplateData(child, data);
        }
    }

    private static void appendClonedTemplateItems(HtmlNode parent, List<HtmlNode> templateChildren, Map<String, String> localData) {
        for (HtmlNode tmplChild : templateChildren) {
            HtmlNode clonedChild = tmplChild.cloneNode();
            applyTemplateData(clonedChild, localData);
            clonedChild.parent = parent;
            parent.children.add(clonedChild);
        }
    }

    private static void buildLayoutTreeRecursively(HtmlNode node, LayoutBox box, StyleSheet sheet) {
        for (HtmlNode child : node.children) {
            if ("#text".equals(child.tag) && (child.text == null || child.text.trim().isEmpty())) continue;

            com.nishiyu.enderframelib.core.css.StyleResolver.StyleSet styleSet = com.nishiyu.enderframelib.core.css.StyleResolver.resolveStyleSet(child, box.style.asMap(), sheet);
            Map<String, String> rawStyle = styleSet.resolve(com.nishiyu.enderframelib.core.css.Selector.ElementState.NONE);
            com.nishiyu.enderframelib.core.css.ComputedStyle computed = com.nishiyu.enderframelib.core.css.StyleResolver.finalizeComputedStyle(
                    rawStyle, sheet, null, box.style.asMap(), box.style.fontSizePx(),
                    com.nishiyu.enderframelib.core.css.ComputedStyle.DEFAULT_FONT_SIZE, 1000, 1000);

            LayoutBox childBox = new LayoutBox(child, computed);
            box.addElementChild(childBox);
            buildLayoutTreeRecursively(child, childBox, sheet);
        }
    }

    public static void handleBinaryTargetDataSync(Map<String, Object> data) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        try {
            JsonObject rootObj = mapToJson(data);

            if (rootObj.has("tracker_data")) {
                JsonObject trackerDataObj = rootObj.getAsJsonObject("tracker_data");
                serverSyncedTrackerData.clear();
                for (String entityIdStr : trackerDataObj.keySet()) {
                    try {
                        int entityId = Integer.parseInt(entityIdStr);
                        serverSyncedTrackerData.put(entityId, trackerDataObj.getAsJsonObject(entityIdStr));
                    } catch (NumberFormatException ignored) {}
                }
            }

            if (rootObj.has("target") && !rootObj.get("target").isJsonNull()) {
                latestTargetData = rootObj.getAsJsonObject("target");
            } else {
                latestTargetData = null;
            }
        } catch (Exception e) {
            latestTargetData = null;
            serverSyncedTrackerData.clear();
        }
    }

    private static JsonElement toJsonElement(Object v) {
        switch (v) {
            case null -> { return null; }
            case String s -> { return new JsonPrimitive(s); }
            case Number n -> { return new JsonPrimitive(n); }
            case Boolean b -> { return new JsonPrimitive(b); }
            case ResourceLocation rl -> { return new JsonPrimitive(rl.toString()); }
            case CompoundTag tag -> {
                JsonObject obj = new JsonObject();
                for (String key : tag.getAllKeys()) {
                    JsonElement child = toJsonElement(tag.get(key));
                    if (child != null) obj.add(key, child);
                }
                return obj;
            }
            case ListTag listTag -> {
                JsonArray arr = new JsonArray();
                for (Tag child : listTag) {
                    JsonElement element = toJsonElement(child);
                    if (element != null) arr.add(element);
                }
                return arr;
            }
            case StringTag strTag -> { return new JsonPrimitive(strTag.getAsString()); }
            case NumericTag numTag -> { return new JsonPrimitive(numTag.getAsNumber()); }
            case Map<?, ?> m -> {
                JsonObject obj = new JsonObject();
                for (Map.Entry<?, ?> entry : m.entrySet()) {
                    if (entry.getKey() instanceof String strKey) {
                        JsonElement child = toJsonElement(entry.getValue());
                        if (child != null) obj.add(strKey, child);
                    }
                }
                return obj;
            }
            case Iterable<?> list -> {
                JsonArray arr = new JsonArray();
                for (Object item : list) {
                    JsonElement child = toJsonElement(item);
                    if (child != null) arr.add(child);
                }
                return arr;
            }
            default -> {
                if (v.getClass().isArray()) {
                    JsonArray arr = new JsonArray();
                    int length = Array.getLength(v);
                    for (int i = 0; i < length; i++) {
                        JsonElement child = toJsonElement(Array.get(v, i));
                        if (child != null) arr.add(child);
                    }
                    return arr;
                }
                return null;
            }
        }
    }

    private static JsonObject mapToJson(Map<String, Object> map) {
        JsonElement el = toJsonElement(map);
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
    }

    private static class TrackerCache {
        final Map<String, String> data = new HashMap<>();
        List<DisplayItem> elements;
        int width, height;
        public boolean isChanged(Map<String, String> newData) { return !data.equals(newData); }
    }
}