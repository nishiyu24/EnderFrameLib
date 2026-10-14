package com.nishiyu.enderframelib.client.renderer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.nishiyu.enderframelib.client.ClientScreenInteractionManager;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.common.UIParser;
import com.nishiyu.enderframelib.core.css.CssParser;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.model.UIElement;
import com.nishiyu.enderframelib.network.c2s.SubscribeC2SPacket;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ByteTag;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WebHudRenderer {

    private static final float VIRTUAL_HEIGHT = 720.0f;
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{([a-zA-Z0-9_.-]+)}}");

    /**
     * 現在HUDとして表示すべきセッションIDを返すリゾルバ。
     * デフォルトでは頭装備に有効なセッションIDがあり、かつ通信範囲内であればそのセッションIDを返す。
     */
    public static Function<Minecraft, String> activeHudSessionResolver = WebHudRenderer::defaultResolveActiveHudSession;

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

    private static final Map<String, HudTemplateConfig> hudConfigs = new ConcurrentHashMap<>();
    private static TrackerTemplateConfig currentTrackerConfig = null;
    private static String currentTrackerSessionId = null;
    private static HtmlNode trackerTemplateNode = null;

    private static final Map<UUID, TrackerCache> trackerCaches = new HashMap<>();
    private static final Map<Integer, JsonObject> serverSyncedTrackerData = new ConcurrentHashMap<>();
    private static JsonObject latestTargetData = null;
    private static boolean pendingSubscriptionSync = false;

    private static int lastSentWidth = -1;
    private static int lastSentHeight = -1;

    static {
        try {
            NeoForge.EVENT_BUS.addListener(WebHudRenderer::onRenderGui);
            NeoForge.EVENT_BUS.addListener(WebHudRenderer::onRenderLevelStage);
        } catch (Throwable ignored) {}
    }

    public static String defaultResolveActiveHudSession(Minecraft mc) {
        if (mc.player == null || mc.options.hideGui) return null;
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
        hudConfigs.put(sessionId, config);
        requestSubscriptionSync();
    }

    public static void setTrackerTemplate(String sessionId, HtmlNode templateNode, double radius, double yOffset, String targetType, List<String> requireNbt) {
        TrackerTemplateConfig config = new TrackerTemplateConfig();
        config.radius = radius;
        config.yOffset = yOffset;
        config.targetType = targetType;
        config.requireNbt.addAll(requireNbt);

        trackerTemplateNode = templateNode;
        currentTrackerSessionId = sessionId;
        currentTrackerConfig = config;
        trackerCaches.clear();
        requestSubscriptionSync();
    }

    public static void clearSession(String sessionId) {
        boolean changed = false;
        if (hudConfigs.remove(sessionId) != null) changed = true;
        if (sessionId.equals(currentTrackerSessionId)) {
            trackerTemplateNode = null;
            currentTrackerSessionId = null;
            currentTrackerConfig = null;
            trackerCaches.clear();
            changed = true;
        }
        if (changed) requestSubscriptionSync();
    }

    private static void executeSubscriptionSync() {
        boolean needsHud = !hudConfigs.isEmpty();
        boolean needsTracker = currentTrackerConfig != null && trackerTemplateNode != null;
        double radius = needsTracker ? currentTrackerConfig.radius : 24.0;
        String type = needsTracker ? currentTrackerConfig.targetType : "living";

        List<String> hudNbtPaths = new ArrayList<>();
        for (HudTemplateConfig cfg : hudConfigs.values()) {
            hudNbtPaths.addAll(cfg.requireNbt);
        }

        List<String> trackerNbtPaths = new ArrayList<>();
        if (needsTracker) {
            trackerNbtPaths.addAll(currentTrackerConfig.requireNbt);
        }

        PacketDistributor.sendToServer(new SubscribeC2SPacket("", needsHud, needsTracker, radius, type, hudNbtPaths, trackerNbtPaths));
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
        if (!hudConfigs.containsKey(screenKey)) return;

        HudTemplateConfig config = hudConfigs.get(screenKey);
        HtmlNode vRoot = ClientWebManager.getVirtualRoot(screenKey);

        if (vRoot != null && config.templateRoot != null) {
            HtmlNode containerNode = vRoot.getElementById(config.containerId);
            if (containerNode != null) {
                Map<String, String> dataMap = extractHudData(mc.hitResult);

                if (dataMap.isEmpty()) {
                    containerNode.attrs.put("style", containerNode.attrs.getOrDefault("style", "") + "; display: none;");
                } else {
                    containerNode.attrs.put("style", containerNode.attrs.getOrDefault("style", "").replace("display: none;", "") + "; display: " + config.origDisplay + ";");

                    HtmlNode clonedTemplate = config.templateRoot.cloneNode();
                    applyTemplateData(clonedTemplate, dataMap);

                    containerNode.children.clear();
                    containerNode.children.addAll(clonedTemplate.children);
                    for (HtmlNode child : containerNode.children) child.parent = containerNode;
                }
                ClientWebManager.requestRender(screenKey);
            }
        }
    }

    private static void renderHudElements(PoseStack poseStack, Minecraft mc, String screenKey) {
        List<UIElement> elements = ClientWebManager.getElements(screenKey);
        if (elements.isEmpty()) return;

        List<UIElement> hudElements = new ArrayList<>();
        for (UIElement el : elements) {
            if (el.events() != null && el.events().containsKey("data-mount-entity")) continue;
            hudElements.add(el);
        }
        if (hudElements.isEmpty()) return;

        poseStack.pushPose();
        poseStack.translate(0, 0, 0);

        int screenHeight = mc.getWindow().getGuiScaledHeight();
        float screenScale = (float) screenHeight / VIRTUAL_HEIGHT;
        poseStack.scale(screenScale, screenScale, 1.0f);

        var bufferSource = mc.renderBuffers().bufferSource();
        int logicWidth = (int) Math.ceil((float) lastSentWidth / WebRenderCore.RESOLUTION);
        int logicHeight = (int) Math.ceil((float) lastSentHeight / WebRenderCore.RESOLUTION);

        WebRenderCore.renderElements(
                hudElements, logicWidth, logicHeight,
                poseStack, bufferSource,
                0xF000F0, OverlayTexture.NO_OVERLAY,
                true, true, true,
                0.0f, 0.0f, ClientScreenInteractionManager.getHoveredId(screenKey),
                0x00000000, false,
                mc.level, false, true, screenKey,
                null, null, 0
        );

        bufferSource.endBatch();
        poseStack.popPose();
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;

        Minecraft mc = Minecraft.getInstance();
        String screenKey = activeHudSessionResolver != null ? activeHudSessionResolver.apply(mc) : null;
        if (screenKey == null || screenKey.isEmpty()) return;

        if (trackerTemplateNode != null && screenKey.equals(currentTrackerSessionId) && currentTrackerConfig != null) {
            renderTrackersInWorld(event.getPoseStack(), mc, screenKey);
        }
    }

    private static void renderTrackersInWorld(PoseStack poseStack, Minecraft mc, String screenKey) {
        Camera camera = mc.gameRenderer.getMainCamera();
        var bufferSource = mc.renderBuffers().bufferSource();
        boolean renderedAny = false;

        AABB box = mc.player.getBoundingBox().inflate(currentTrackerConfig.radius);
        Class<? extends LivingEntity> targetClass = getTargetClass(currentTrackerConfig.targetType);

        for (LivingEntity le : mc.level.getEntitiesOfClass(targetClass, box)) {
            if (le == mc.player) continue;
            if (processAndRenderSingleTracker(poseStack, bufferSource, camera, mc, le, screenKey)) {
                renderedAny = true;
            }
        }

        if (renderedAny) {
            bufferSource.endBatch();
        }
    }

    private static boolean processAndRenderSingleTracker(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource, Camera camera, Minecraft mc, LivingEntity le, String screenKey) {
        UUID uuid = le.getUUID();
        Map<String, String> dataMap = new HashMap<>();

        dataMap.put("name", le.getDisplayName().getString());
        dataMap.put("hp", String.valueOf((int) le.getHealth()));
        dataMap.put("max_hp", String.valueOf((int) le.getMaxHealth()));
        dataMap.put("hp_percent", String.valueOf(Math.round((le.getHealth() / le.getMaxHealth()) * 100)));
        dataMap.put("armor", String.valueOf(le.getArmorValue()));

        JsonArray effectsArray = new JsonArray();
        for (MobEffectInstance effect : le.getActiveEffects()) {
            JsonObject obj = new JsonObject();
            ResourceLocation id = BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value());
            if (id != null) {
                obj.addProperty("id", id.toString());
                obj.addProperty("amplifier", effect.getAmplifier());
                obj.addProperty("duration", effect.getDuration());
                effectsArray.add(obj);
            }
        }
        if (effectsArray.size() > 0) {
            dataMap.put("active_effects", effectsArray.toString());
            dataMap.put("nbt.active_effects", effectsArray.toString());
        }

        JsonObject syncedData = serverSyncedTrackerData.get(le.getId());
        if (syncedData != null) {
            Map<String, String> flatData = new HashMap<>();
            flattenJsonToMap(syncedData, "", flatData);

            for (String path : currentTrackerConfig.requireNbt) {
                String searchKey1 = "nbt." + path;
                String searchKey2 = path;
                String rawValue = flatData.containsKey(searchKey1) ? flatData.get(searchKey1) :
                        flatData.containsKey(searchKey2) ? flatData.get(searchKey2) : null;

                if (rawValue != null) {
                    if ((rawValue.startsWith("[") || rawValue.startsWith("{")) && !rawValue.startsWith("{\"")) {
                        try {
                            CompoundTag dummy = TagParser.parseTag("{data:" + rawValue + "}");
                            Tag parsedTag = dummy.get("data");
                            JsonElement jsonElem = toJsonElement(parsedTag);
                            if (jsonElem != null) rawValue = jsonElem.toString();
                        } catch (Exception ignored) {}
                    }
                    dataMap.put("nbt." + path, rawValue);
                }
            }
        }

        TrackerCache cache = trackerCaches.get(uuid);
        boolean needsUpdate = (cache == null) || cache.isChanged(dataMap);

        if (needsUpdate) {
            cache = new TrackerCache();
            cache.data.putAll(dataMap);

            HtmlNode instanceNode = trackerTemplateNode.cloneNode();
            applyTemplateData(instanceNode, dataMap);

            String style = instanceNode.attrs.getOrDefault("style", "");
            style = style.replaceAll("(?i)display\\s*:\\s*none\\s*;?", "");
            instanceNode.attrs.put("style", style + "; display: flex;");

            UIParser.Document doc = ClientWebManager.getDocument(screenKey);
            if (doc != null) {
                Map<String, String> rootStyle = new HashMap<>();
                rootStyle.put("display", "inline-block");

                LayoutBox layoutBox = new LayoutBox(instanceNode, CssParser.computeNodeStyle(instanceNode, rootStyle, doc.sheet, null), null);
                buildLayoutTreeRecursively(instanceNode, layoutBox, doc.sheet);

                layoutBox.computeSize(1000, 1000, false, false, "flex-start", true, 1000, 1000);
                layoutBox.layout(0, 0, 1000, 1000, 1000, 1000);

                cache.width = layoutBox.w;
                cache.height = layoutBox.h;

                UIRenderer renderer = new UIRenderer(layoutBox.w, layoutBox.h, doc.sheet.keyframes);
                cache.elements = renderer.render(layoutBox);
            }
            trackerCaches.put(uuid, cache);
        }

        if (cache.elements != null && !cache.elements.isEmpty()) {
            double x = le.getX();
            double y = le.getY() + le.getBbHeight() + currentTrackerConfig.yOffset;
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

            WebRenderCore.renderElements(
                    cache.elements, cache.width, cache.height,
                    poseStack, bufferSource,
                    0xF000F0, OverlayTexture.NO_OVERLAY,
                    true, true, true,
                    -0.01f, -0.005f, ClientScreenInteractionManager.getHoveredId(screenKey),
                    0x00000000, false,
                    mc.level, false, true, screenKey,
                    null, null, 0
            );
            poseStack.popPose();
            return true;
        }
        return false;
    }

    private static Class<? extends LivingEntity> getTargetClass(String targetType) {
        if ("player".equalsIgnoreCase(targetType)) return Player.class;
        else if ("monster".equalsIgnoreCase(targetType)) return Monster.class;
        else if ("animal".equalsIgnoreCase(targetType)) return Animal.class;
        return LivingEntity.class;
    }

    private static Map<String, String> extractHudData(HitResult hitResult) {
        Map<String, String> map = new HashMap<>();
        if (hitResult == null || hitResult.getType() == HitResult.Type.MISS) return map;

        Minecraft mc = Minecraft.getInstance();
        if (hitResult.getType() == HitResult.Type.ENTITY) {
            EntityHitResult ehr = (EntityHitResult) hitResult;
            if (ehr.getEntity() instanceof LivingEntity le) {
                map.put("name", le.getDisplayName().getString());
                map.put("hp", String.valueOf((int) le.getHealth()));
                map.put("max_hp", String.valueOf((int) le.getMaxHealth()));
                map.put("armor", String.valueOf(le.getArmorValue()));
                map.put("is_on_fire", String.valueOf(le.isOnFire()));
                map.put("type", "entity");
                map.put("is_entity", "true");

                JsonArray effectsArray = new JsonArray();
                for (MobEffectInstance effect : le.getActiveEffects()) {
                    JsonObject obj = new JsonObject();
                    ResourceLocation id = BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value());
                    if (id != null) {
                        obj.addProperty("id", id.toString());
                        obj.addProperty("amplifier", effect.getAmplifier());
                        obj.addProperty("duration", effect.getDuration());
                        effectsArray.add(obj);
                    }
                }
                map.put("active_effects", effectsArray.toString());
                map.put("nbt.active_effects", effectsArray.toString());
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
            JsonObject obj = element.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                JsonElement child = entry.getValue();
                String newPrefix = prefix + entry.getKey();
                if (child.isJsonPrimitive()) map.put(newPrefix, child.getAsString());
                else {
                    map.put(newPrefix, child.toString());
                    flattenJsonToMap(child, newPrefix + ".", map);
                }
            }
        } else if (element.isJsonArray()) {
            int i = 0;
            for (JsonElement child : element.getAsJsonArray()) {
                String newPrefix = prefix + i;
                if (child.isJsonPrimitive()) map.put(newPrefix, child.getAsString());
                else {
                    map.put(newPrefix, child.toString());
                    flattenJsonToMap(child, newPrefix + ".", map);
                }
                i++;
            }
        }
    }

    public static void applyTemplateData(HtmlNode node, Map<String, String> data) {
        if (node.text != null && node.text.contains("{{")) node.text = replacePlaceholders(node.text, data);
        for (String key : new ArrayList<>(node.attrs.keySet())) {
            String val = node.attrs.get(key);
            if (val != null && val.contains("{{")) node.attrs.put(key, replacePlaceholders(val, data));
        }
        if (node.attrs.containsKey("id")) node.id = node.attrs.get("id");

        if (node.attrs.containsKey("data-show")) {
            String showVal = node.attrs.get("data-show").trim();
            boolean hide = showVal.isEmpty() || showVal.equalsIgnoreCase("false") || showVal.equals("0") || showVal.contains("{{");
            if (hide) node.attrs.put("style", node.attrs.getOrDefault("style", "") + "; display: none;");
            node.attrs.remove("data-show");
        }

        if (node.attrs.containsKey("data-list")) {
            String jsonStr = node.attrs.get("data-list");
            node.attrs.remove("data-list");

            List<HtmlNode> templateChildren = new ArrayList<>();
            for (HtmlNode c : node.children) {
                if ("template".equalsIgnoreCase(c.tag)) templateChildren.addAll(c.children);
                else templateChildren.add(c);
            }
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
                            for (HtmlNode tmplChild : templateChildren) {
                                HtmlNode clonedChild = tmplChild.cloneNode();
                                applyTemplateData(clonedChild, localData);
                                clonedChild.parent = node;
                                node.children.add(clonedChild);
                            }
                        }
                    } else if (parsedJson.isJsonArray()) {
                        int idx = 0;
                        for (JsonElement elem : parsedJson.getAsJsonArray()) {
                            Map<String, String> localData = new HashMap<>(data);
                            localData.put("_key", String.valueOf(idx++));
                            if (elem.isJsonPrimitive()) localData.put("_value", elem.getAsString());
                            else if (elem.isJsonObject()) {
                                JsonObject obj = elem.getAsJsonObject();
                                for (Map.Entry<String, JsonElement> objEntry : obj.entrySet()) {
                                    if (objEntry.getValue().isJsonPrimitive()) localData.put(objEntry.getKey(), objEntry.getValue().getAsString());
                                }
                                localData.put("_value", elem.toString());
                            } else continue;
                            for (HtmlNode tmplChild : templateChildren) {
                                HtmlNode clonedChild = tmplChild.cloneNode();
                                applyTemplateData(clonedChild, localData);
                                clonedChild.parent = node;
                                node.children.add(clonedChild);
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
            return;
        }

        for (HtmlNode child : node.children) applyTemplateData(child, data);
    }

    private static String replacePlaceholders(String text, Map<String, String> data) {
        Matcher m = PLACEHOLDER_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String replacement = data.getOrDefault(key, "");
            if (!data.containsKey(key)) replacement = "{{" + key + "}}";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static void buildLayoutTreeRecursively(HtmlNode node, LayoutBox box, CssParser.StyleSheet sheet) {
        for (HtmlNode child : node.children) {
            if (child.tag.equals("#text") && (child.text == null || child.text.trim().isEmpty())) continue;
            Map<String, String> childStyle = CssParser.computeNodeStyle(child, box.style, sheet, null);
            LayoutBox childBox = new LayoutBox(child, childStyle, null);
            box.children.add(childBox);
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
                        JsonObject entityData = trackerDataObj.getAsJsonObject(entityIdStr);
                        serverSyncedTrackerData.put(entityId, entityData);
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
        if (v == null) return null;
        if (v instanceof String s) return new JsonPrimitive(s);
        if (v instanceof Number n) return new JsonPrimitive(n);
        if (v instanceof Boolean b) return new JsonPrimitive(b);
        if (v instanceof ResourceLocation rl) return new JsonPrimitive(rl.toString());

        if (v instanceof CompoundTag tag) {
            JsonObject obj = new JsonObject();
            for (String key : tag.getAllKeys()) {
                JsonElement child = toJsonElement(tag.get(key));
                if (child != null) obj.add(key, child);
            }
            return obj;
        }
        if (v instanceof ListTag listTag) {
            JsonArray arr = new JsonArray();
            for (Tag child : listTag) {
                JsonElement element = toJsonElement(child);
                if (element != null) arr.add(element);
            }
            return arr;
        }
        if (v instanceof StringTag strTag) return new JsonPrimitive(strTag.getAsString());
        if (v instanceof NumericTag numTag) return new JsonPrimitive(numTag.getAsNumber());
        if (v instanceof ByteTag boolTag) return new JsonPrimitive(boolTag.getAsByte() != 0);

        if (v instanceof Map<?, ?> m) {
            JsonObject obj = new JsonObject();
            for (Map.Entry<?, ?> entry : m.entrySet()) {
                if (entry.getKey() instanceof String strKey) {
                    JsonElement child = toJsonElement(entry.getValue());
                    if (child != null) obj.add(strKey, child);
                }
            }
            return obj;
        }
        if (v instanceof Iterable<?> list) {
            JsonArray arr = new JsonArray();
            for (Object item : list) {
                JsonElement child = toJsonElement(item);
                if (child != null) arr.add(child);
            }
            return arr;
        }
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

    private static JsonObject mapToJson(Map<String, Object> map) {
        JsonElement el = toJsonElement(map);
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
    }

    private static class TrackerCache {
        Map<String, String> data = new HashMap<>();
        List<UIElement> elements;
        int width, height;
        public boolean isChanged(Map<String, String> newData) { return !data.equals(newData); }
    }
}