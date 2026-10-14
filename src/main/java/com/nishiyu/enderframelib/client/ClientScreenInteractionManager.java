package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.api.IWebScreenBlockEntity;
import com.nishiyu.enderframelib.client.gui.InvisibleInputScreen;
import com.nishiyu.enderframelib.client.renderer.WebRenderCore;
import com.nishiyu.enderframelib.core.dom.DomUtils;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.model.UIElement;
import com.nishiyu.enderframelib.network.c2s.WebActionC2SPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.Function;

public class ClientScreenInteractionManager {

    @FunctionalInterface
    public interface IClientEventCallback {
        void onEvent(String sessionId, String elementId, String eventName, String actionName, String value);
    }

    public static IClientEventCallback clientEventCallback = null;
    public static boolean forwardEventsToServer = false;
    public static boolean enableDefaultUiSounds = true;

    public static Function<ItemStack, String> itemSessionIdExtractor = ClientScreenInteractionManager::defaultGetSessionIdFromItem;
    public static BiPredicate<Minecraft, ItemStack> itemRangeValidator = ClientScreenInteractionManager::defaultIsItemInRange;

    private static final Map<String, SessionState> states = new ConcurrentHashMap<>();
    private static boolean wasMouseDown = false;
    private static String lastHoveredSession = "";

    private static final Map<String, RouterDistanceInfo> ROUTER_DISTANCE_CACHE = new ConcurrentHashMap<>();
    public record RouterDistanceInfo(long posLong, String dimension, double maxDistSqr) {}

    static {
        try {
            NeoForge.EVENT_BUS.addListener(ClientScreenInteractionManager::onClientTick);
        } catch (Throwable ignored) {}
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        boolean isMouseDown = mc.options.keyUse.isDown() || mc.options.keyAttack.isDown();
        boolean isMouseJustPressed = isMouseDown && !wasMouseDown;
        boolean isMouseJustReleased = !isMouseDown && wasMouseDown;
        wasMouseDown = isMouseDown;

        if (isMouseJustReleased && !lastHoveredSession.isEmpty()) {
            handleRelease(lastHoveredSession);
        }

        if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK) {
            BlockHitResult hitResult = (BlockHitResult) mc.hitResult;
            BlockPos pos = hitResult.getBlockPos();

            if (mc.level.getBlockEntity(pos) instanceof IWebScreenBlockEntity screen) {
                Direction facing = screen.getFacing();

                if (facing != null && hitResult.getDirection() == facing) {
                    double hitX = hitResult.getLocation().x - pos.getX();
                    double hitY = hitResult.getLocation().y - pos.getY();
                    double hitZ = hitResult.getLocation().z - pos.getZ();

                    double blockU = 0, blockV = 1.0 - hitY;
                    switch (facing) {
                        case NORTH -> blockU = 1.0 - hitX;
                        case SOUTH -> blockU = hitX;
                        case WEST -> blockU = hitZ;
                        case EAST -> blockU = 1.0 - hitZ;
                    }

                    IWebScreenBlockEntity master = screen.isMaster() ? screen :
                            (screen.getScreenMasterPos() != null && mc.level.getBlockEntity(screen.getScreenMasterPos()) instanceof IWebScreenBlockEntity m ? m : screen);

                    String sessionId = master.getSessionId();
                    lastHoveredSession = sessionId;
                    BlockPos masterPos = master.getScreenMasterPos() != null ? master.getScreenMasterPos() : pos;

                    Direction screenRight = facing.getCounterClockWise();
                    int logicalX = 0;
                    if (screenRight == Direction.WEST) logicalX = masterPos.getX() - pos.getX();
                    else if (screenRight == Direction.EAST) logicalX = pos.getX() - masterPos.getX();
                    else if (screenRight == Direction.NORTH) logicalX = masterPos.getZ() - pos.getZ();
                    else if (screenRight == Direction.SOUTH) logicalX = pos.getZ() - masterPos.getZ();
                    int logicalY = pos.getY() - masterPos.getY();

                    double pixelX = (logicalX + blockU) * WebRenderCore.RESOLUTION;
                    double pixelY = ((master.getScreenHeight() - 1 - logicalY) + blockV) * WebRenderCore.RESOLUTION;

                    if (isMouseJustPressed) {
                        UIElement clickedEl = handleClick(sessionId, pixelX, pixelY);
                        if (clickedEl != null && "input".equals(clickedEl.type())) {
                            WebRenderCore.activeInputId = clickedEl.id();
                            openInputScreen(sessionId, clickedEl, masterPos);
                        } else {
                            WebRenderCore.activeInputId = "";
                        }
                    } else if (isMouseDown) {
                        handleDrag(sessionId, pixelX, pixelY);
                    }

                    handleHover(sessionId, pixelX, pixelY);
                    return;
                }
            }
        }

        if (!lastHoveredSession.isEmpty()) {
            handleHover(lastHoveredSession, -1, -1);
            lastHoveredSession = "";
        }
    }

    public static void openInputScreen(String sessionId, UIElement clickedElement, BlockPos pos) {
        if (clickedElement != null && "input".equals(clickedElement.type())) {
            Minecraft.getInstance().setScreen(new InvisibleInputScreen(sessionId, clickedElement, pos));
        }
    }

    private static void playElementSound(UIElement el) {
        Map<String, String> events = el.events();
        String soundId = events != null ? events.get("data-sound") : null;
        if ("none".equalsIgnoreCase(soundId)) return;

        float pitch = 1.0f;
        if (events != null && events.containsKey("data-sound-pitch")) {
            try { pitch = Float.parseFloat(events.get("data-sound-pitch")); } catch (Exception ignored) {}
        }

        if (soundId != null && !soundId.isEmpty()) {
            try {
                SoundEvent se = BuiltInRegistries.SOUND_EVENT.get(ResourceLocation.parse(soundId));
                if (se != null) {
                    Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(se, pitch));
                    return;
                }
            } catch (Exception ignored) {}
        }

        if (enableDefaultUiSounds) {
            boolean isInteractive = "button".equals(el.type()) || "toggle".equals(el.type()) || "select".equals(el.type()) ||
                    (events != null && events.containsKey("onclick"));
            if (isInteractive) {
                Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, pitch));
            }
        }
    }

    public static boolean isWithinRange(String screenKey, CompoundTag tag, Minecraft mc) {
        if (mc.player == null || mc.level == null) return false;

        RouterDistanceInfo info = ROUTER_DISTANCE_CACHE.computeIfAbsent(screenKey, k -> {
            if (!tag.contains("RouterPos")) return null;
            double maxDist = tag.getDouble("RouterRange");
            return new RouterDistanceInfo(
                    tag.getLong("RouterPos"),
                    tag.getString("RouterDim"),
                    maxDist == Double.MAX_VALUE ? Double.MAX_VALUE : maxDist * maxDist
            );
        });

        if (info == null) return true;

        boolean isSameDim = mc.level.dimension().location().toString().equals(info.dimension());
        if (!isSameDim) return info.maxDistSqr() == Double.MAX_VALUE;
        if (info.maxDistSqr() == Double.MAX_VALUE) return true;

        BlockPos pos = BlockPos.of(info.posLong());
        return mc.player.blockPosition().distSqr(pos) <= info.maxDistSqr();
    }

    public static boolean isItemInRange(Minecraft mc, ItemStack stack) {
        return itemRangeValidator != null ? itemRangeValidator.test(mc, stack) : defaultIsItemInRange(mc, stack);
    }

    public static boolean defaultIsItemInRange(Minecraft mc, ItemStack stack) {
        if (mc.player == null || mc.level == null) return false;
        CustomData customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        CompoundTag tag = customData.copyTag();
        if (!tag.contains("RouterPos")) return true;

        BlockPos routerPos = BlockPos.of(tag.getLong("RouterPos"));
        String routerDim = tag.getString("RouterDim");
        double maxDist = tag.getDouble("RouterRange");
        boolean isSameDim = mc.level.dimension().location().toString().equals(routerDim);

        if (!isSameDim && maxDist != Double.MAX_VALUE) return false;
        return !isSameDim || maxDist == Double.MAX_VALUE || !(mc.player.blockPosition().distSqr(routerPos) > (maxDist * maxDist));
    }

    public static void clearDistanceCache(String screenKey) {
        ROUTER_DISTANCE_CACHE.remove(screenKey);
    }

    private static SessionState getState(String sessionId) {
        return states.computeIfAbsent(sessionId, k -> new SessionState());
    }

    private static void triggerLocalEvent(String sessionId, String elementId, String eventName, String actionName, String value) {
        if (sessionId == null || sessionId.isEmpty()) return;
        if (actionName != null && !actionName.isEmpty() && !"auto".equals(actionName)) {
            if (clientEventCallback != null) {
                clientEventCallback.onEvent(sessionId, elementId, eventName, actionName, value);
            }
            if (forwardEventsToServer) {
                CompoundTag tag = new CompoundTag();
                tag.putString("ElementId", elementId != null ? elementId : "");
                tag.putString("EventName", eventName != null ? eventName : "");
                tag.putString("ActionName", actionName);
                tag.putString("Value", value != null ? value : "");
                PacketDistributor.sendToServer(new WebActionC2SPacket(sessionId, "ui_event", tag));
            }
        }
    }

    private static String getEventAction(Map<String, String> events, String eventName) {
        if (events == null || events.isEmpty()) return "";
        for (Map.Entry<String, String> entry : events.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(eventName)) return entry.getValue();
        }
        return "";
    }

    private static boolean hasEvent(Map<String, String> events, String eventName) {
        if (events == null || events.isEmpty()) return false;
        for (String key : events.keySet()) {
            if (key.equalsIgnoreCase(eventName)) return true;
        }
        return false;
    }

    public static void checkAndSendChange(String sessionId, UIElement el, String newValue) {
        if (el.events() == null || !el.events().containsKey("onchange")) return;

        SessionState state = getState(sessionId);
        String key = "bind_" + el.id();

        if (newValue == null) newValue = "";
        String lastValue = state.lastBindValues.getOrDefault(key, "");

        if (!newValue.equals(lastValue)) {
            state.lastBindValues.put(key, newValue);
            triggerLocalEvent(sessionId, ClientWebManager.getBaseNodeId(el.id()), "onchange", getEventAction(el.events(), "onchange"), newValue);
        }
    }

    public static void ensureSynced(String sessionId) {
        if (!ClientWebManager.hasDocument(sessionId) && ClientWebManager.shouldRequestSync(sessionId)) {
            PacketDistributor.sendToServer(new WebActionC2SPacket(sessionId, "request_sync", new CompoundTag()));
        }
    }

    public static String getHoveredId(String sessionId) {
        SessionState state = states.get(sessionId);
        return state != null ? state.hoveredId : "";
    }

    public static void applyLocalOverrides(String sessionId) {
        SessionState state = getState(sessionId);
        for (Map.Entry<String, String> entry : state.localInputCache.entrySet()) {
            ClientWebManager.updateElementTextLocally(sessionId, entry.getKey(), entry.getValue());
        }
        if (!state.draggingId.isEmpty() && !state.lastRangeText.isEmpty()) {
            ClientWebManager.updateElementTextLocally(sessionId, state.draggingId, state.lastRangeText);
        }
        if (System.currentTimeMillis() - state.lastSubmitTime < 1500) {
            if (!state.lastSubmittedId.isEmpty() && !state.lastSubmittedText.isEmpty()) {
                ClientWebManager.updateElementTextLocally(sessionId, state.lastSubmittedId, state.lastSubmittedText);
            }
        }
        if (!state.hoveredId.isEmpty()) {
            ClientWebManager.updateNodeAttributeLocally(sessionId, state.hoveredId, "hover", "true", false);
        }
        String activeId = WebRenderCore.activeInputId;
        if (activeId != null && !activeId.isEmpty()) {
            net.minecraft.client.gui.screens.Screen screen = Minecraft.getInstance().screen;
            if (screen != null) {
                String currentText = null;
                for (net.minecraft.client.gui.components.events.GuiEventListener child : screen.children()) {
                    if (child instanceof net.minecraft.client.gui.components.EditBox eb && eb.isFocused()) {
                        currentText = eb.getValue();
                        break;
                    }
                }
                if (currentText != null) {
                    updateInputLocally(sessionId, activeId, currentText);
                }
            }
        }
    }

    public static String getSessionIdFromItem(ItemStack stack) {
        return itemSessionIdExtractor != null ? itemSessionIdExtractor.apply(stack) : defaultGetSessionIdFromItem(stack);
    }

    public static String defaultGetSessionIdFromItem(ItemStack stack) {
        CustomData customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        CompoundTag tag = customData.copyTag();
        if (tag.contains("SessionId")) return tag.getString("SessionId");
        if (tag.contains("IPAddress")) {
            String ip = tag.getString("IPAddress");
            String networkId = tag.contains("NetworkId") ? tag.getUUID("NetworkId").toString() : "global";
            return networkId + ":" + ip;
        }
        return null;
    }

    public static String handleHover(String sessionId, double pixelX, double pixelY) {
        SessionState state = getState(sessionId);
        ClientWebManager.UIHitResult hit = ClientWebManager.getHitElement(sessionId, pixelX, pixelY);
        long now = System.currentTimeMillis();

        if (hit != null) {
            UIElement hitElement = hit.element();
            String currentHitId = ClientWebManager.getBaseNodeId(hitElement.id());
            Map<String, String> events = hitElement.events() != null ? hitElement.events() : Map.of();
            String coordStr = String.format(Locale.US, "%.1f,%.1f", hit.localX(), hit.localY());

            if (!currentHitId.equals(state.hoveredId)) {
                if (!state.hoveredId.isEmpty()) {
                    triggerLocalEvent(sessionId, state.hoveredId, "onmouseleave", "auto", "");
                    ClientWebManager.updateNodeAttributeLocally(sessionId, state.hoveredId, "hover", null, true);
                }
                triggerLocalEvent(sessionId, currentHitId, "onmouseenter", getEventAction(events, "onmouseenter"), coordStr);
                ClientWebManager.updateNodeAttributeLocally(sessionId, currentHitId, "hover", "true", false);
                state.hoveredId = currentHitId;
            }
            if (hasEvent(events, "onmousemove") && (now - state.lastEventTime > 50)) {
                triggerLocalEvent(sessionId, currentHitId, "onmousemove", getEventAction(events, "onmousemove"), coordStr);
                state.lastEventTime = now;
            }
            return currentHitId;
        } else {
            if (!state.hoveredId.isEmpty()) {
                triggerLocalEvent(sessionId, state.hoveredId, "onmouseleave", "auto", "");
                ClientWebManager.updateNodeAttributeLocally(sessionId, state.hoveredId, "hover", null, true);
                state.hoveredId = "";
            }
            return "";
        }
    }

    private static void cycleSelectOption(String sessionId, String baseId, Map<String, String> events) {
        HtmlNode selectNode = DomUtils.findNodeById(ClientWebManager.getVirtualRoot(sessionId), baseId);
        if (selectNode == null) return;

        List<String> values = new ArrayList<>();
        for (HtmlNode child : selectNode.children) {
            if ("option".equalsIgnoreCase(child.tag)) {
                values.add(child.attrs.getOrDefault("value", child.getTextContent().trim()));
            }
        }
        if (values.isEmpty()) return;

        String current = selectNode.attrs.getOrDefault("value", values.getFirst());
        int nextIdx = (values.indexOf(current) + 1) % values.size();
        String nextVal = values.get(nextIdx);

        ClientWebManager.updateNodeAttributeLocally(sessionId, baseId, "value", nextVal, false);
        triggerLocalEvent(sessionId, baseId, "onchange", getEventAction(events, "onchange"), nextVal);
    }

    public static UIElement handleClick(String sessionId, double pixelX, double pixelY) {
        SessionState state = getState(sessionId);
        ClientWebManager.UIHitResult hit = ClientWebManager.getHitElement(sessionId, pixelX, pixelY);

        if (hit != null) {
            UIElement clickedEl = hit.element();
            String baseId = ClientWebManager.getBaseNodeId(clickedEl.id());
            WebRenderCore.activePressedId = baseId;
            playElementSound(clickedEl);

            Map<String, String> events = clickedEl.events() != null ? clickedEl.events() : Map.of();
            String cordStr = String.format(Locale.US, "%.1f,%.1f", hit.localX(), hit.localY());
            String clickPayload = baseId + "|" + cordStr;

            triggerLocalEvent(sessionId, baseId, "onclick", getEventAction(events, "onclick"), clickPayload);

            if ("toggle".equals(clickedEl.type()) || "checkbox".equals(clickedEl.type()) || "radio".equals(clickedEl.type())) {
                ClientWebManager.toggleNodeAttributeLocally(sessionId, baseId, "checked");
            } else if ("select".equals(clickedEl.type())) {
                cycleSelectOption(sessionId, baseId, events);
            }

            if (hasEvent(events, "ondrag") || "range".equals(clickedEl.type())) {
                state.draggingId = baseId;
                state.lastSentX = hit.localX();
                state.lastSentY = hit.localY();

                if ("range".equals(clickedEl.type())) {
                    state.lastRatio = Mth.clamp((float) (hit.localX() / clickedEl.width()), 0.0f, 1.0f);
                    state.lastRangeText = generateRangeText(clickedEl.text(), state.lastRatio);
                    state.lastRangeAction = getEventAction(events, "oninput");
                    state.lastRangeChangeAction = getEventAction(events, "onchange");
                    updateInputLocally(sessionId, state.draggingId, state.lastRangeText);
                    triggerLocalEvent(sessionId, state.draggingId, "oninput", state.lastRangeAction, state.lastRangeText);
                } else {
                    triggerLocalEvent(sessionId, state.draggingId, "ondragstart", getEventAction(events, "ondragstart"), cordStr);
                }
            }
            return clickedEl;
        } else {
            WebRenderCore.activePressedId = "";
            triggerLocalEvent(sessionId, "", "onclick", "", String.format(Locale.US, "%.5f,%.5f", pixelX / WebRenderCore.RESOLUTION, pixelY / WebRenderCore.RESOLUTION));
            return null;
        }
    }

    public static boolean handleDrag(String sessionId, double pixelX, double pixelY) {
        SessionState state = getState(sessionId);
        if (state.draggingId.isEmpty()) return false;
        ClientWebManager.UIHitResult dragHit = ClientWebManager.getLocalCoords(sessionId, state.draggingId, pixelX, pixelY);
        long now = System.currentTimeMillis();

        if (dragHit != null) {
            UIElement draggedEl = dragHit.element();
            state.lastSentX = dragHit.localX();
            state.lastSentY = dragHit.localY();
            Map<String, String> events = draggedEl.events() != null ? draggedEl.events() : Map.of();

            if ("range".equals(draggedEl.type())) {
                float ratio = Mth.clamp((float) (dragHit.localX() / draggedEl.width()), 0.0f, 1.0f);
                if (Math.abs(state.lastRatio - ratio) > 0.005f) {
                    state.lastRatio = ratio;
                    state.lastRangeText = generateRangeText(draggedEl.text(), ratio);
                    updateInputLocally(sessionId, state.draggingId, state.lastRangeText);
                    if (now - state.lastEventTime > 50) {
                        triggerLocalEvent(sessionId, state.draggingId, "oninput", state.lastRangeAction, state.lastRangeText);
                        state.lastEventTime = now;
                    }
                }
            } else {
                if (now - state.lastEventTime > 50) {
                    triggerLocalEvent(sessionId, state.draggingId, "ondrag", getEventAction(events, "ondrag"), String.format(Locale.US, "%.1f,%.1f", dragHit.localX(), dragHit.localY()));
                    state.lastEventTime = now;
                }
            }
        }
        return true;
    }

    public static void handleRelease(String sessionId) {
        WebRenderCore.activePressedId = "";
        SessionState state = getState(sessionId);
        if (!state.draggingId.isEmpty()) {
            if (state.lastRatio >= 0 && !state.lastRangeText.isEmpty()) {
                triggerLocalEvent(sessionId, state.draggingId, "oninput", state.lastRangeAction, state.lastRangeText);
                triggerLocalEvent(sessionId, state.draggingId, "onchange", state.lastRangeChangeAction, state.lastRangeText);
                state.lastSubmittedId = state.draggingId;
                state.lastSubmittedText = state.lastRangeText;
                state.lastSubmitTime = System.currentTimeMillis();
            }
            triggerLocalEvent(sessionId, state.draggingId, "ondragend", "auto", String.format(Locale.US, "%.1f,%.1f", state.lastSentX, state.lastSentY));
            state.draggingId = "";
            state.lastRatio = -1.0f;
            state.lastRangeText = "";
            state.lastRangeAction = "";
            state.lastRangeChangeAction = "";
        }
    }

    public static boolean handleScroll(String sessionId, double pixelX, double pixelY, double deltaX, double deltaY) {
        return ClientWebManager.handleScrollLocally(sessionId, pixelX, pixelY, deltaX, deltaY);
    }

    public static void updateInputLocally(String sessionId, String elementId, String text) {
        SessionState state = getState(sessionId);
        String current = state.localInputCache.get(elementId);
        if (java.util.Objects.equals(current, text)) return;
        state.localInputCache.put(elementId, text);
        ClientWebManager.updateElementTextLocally(sessionId, elementId, text);
    }

    public static void submitInput(String sessionId, String elementId, String text) {
        updateInputLocally(sessionId, elementId, text);
        UIElement el = ClientWebManager.getElementById(sessionId, elementId);
        Map<String, String> events = el != null && el.events() != null ? el.events() : Map.of();
        triggerLocalEvent(sessionId, elementId, "oninput", getEventAction(events, "oninput"), text);
        triggerLocalEvent(sessionId, elementId, "onchange", getEventAction(events, "onchange"), text);
        SessionState state = getState(sessionId);
        state.lastSubmittedId = elementId;
        state.lastSubmittedText = text;
        state.lastSubmitTime = System.currentTimeMillis();
    }

    private static String generateRangeText(String oldText, float ratio) {
        float min = 0, max = 100;
        String[] parts = (oldText != null ? oldText : "").split(":");
        if (parts.length >= 3) {
            try {
                min = Float.parseFloat(parts[1]);
                max = Float.parseFloat(parts[2]);
            } catch (Exception ignored) {}
        }
        int newVal = (int) (min + (max - min) * ratio);
        return newVal + ":" + min + ":" + max;
    }

    private static class SessionState {
        String hoveredId = "";
        String draggingId = "";
        float lastRatio = -1.0f;
        String lastRangeText = "";
        String lastRangeAction = "";
        String lastRangeChangeAction = "";
        double lastSentX = -1, lastSentY = -1;
        long lastEventTime = 0;
        String lastSubmittedId = "";
        String lastSubmittedText = "";
        long lastSubmitTime = 0;
        Map<String, String> lastBindValues = new ConcurrentHashMap<>();
        Map<String, String> localInputCache = new ConcurrentHashMap<>();
    }
}