package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.api.IWebScreenBlockEntity;
import com.nishiyu.enderframelib.client.gui.InvisibleInputScreen;
import com.nishiyu.enderframelib.client.renderer.WebRenderCore;
import com.nishiyu.enderframelib.core.dom.DomUtils;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.model.UIElement;
import com.nishiyu.enderframelib.network.c2s.WebActionC2SPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
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
import org.lwjgl.glfw.GLFW;

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
    public static boolean mutateDomOnHover = false;

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
            ClientWebManager.addSessionClearedListener(ClientScreenInteractionManager::clearSessionState);
        } catch (Throwable ignored) {}
    }

    public static void clearSessionState(String sessionId) {
        if (sessionId == null) return;
        states.remove(sessionId);
        ROUTER_DISTANCE_CACHE.remove(sessionId);
        if (sessionId.equals(lastHoveredSession)) {
            lastHoveredSession = "";
        }
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        if (mc.screen != null) {
            wasMouseDown = false;
            return;
        }

        long window = mc.getWindow().getWindow();
        boolean glfwLeft = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean glfwRight = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        boolean isMouseDown = glfwLeft || glfwRight || mc.options.keyUse.isDown() || mc.options.keyAttack.isDown();
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

                    double blockU = switch (facing) {
                        case NORTH -> 1.0 - hitX;
                        case SOUTH -> hitX;
                        case WEST -> hitZ;
                        case EAST -> 1.0 - hitZ;
                        default -> 0.0;
                    };
                    double blockV = 1.0 - hitY;

                    IWebScreenBlockEntity master = screen.isMaster() ? screen :
                            (screen.getScreenMasterPos() != null && mc.level.getBlockEntity(screen.getScreenMasterPos()) instanceof IWebScreenBlockEntity m ? m : screen);

                    String sessionId = master.getSessionId();
                    lastHoveredSession = sessionId;
                    BlockPos masterPos = master.getScreenMasterPos() != null ? master.getScreenMasterPos() : pos;

                    Direction screenRight = facing.getCounterClockWise();
                    int logicalX = switch (screenRight) {
                        case WEST -> masterPos.getX() - pos.getX();
                        case EAST -> pos.getX() - masterPos.getX();
                        case NORTH -> masterPos.getZ() - pos.getZ();
                        case SOUTH -> pos.getZ() - masterPos.getZ();
                        default -> 0;
                    };
                    int logicalY = pos.getY() - masterPos.getY();

                    double pixelX = (logicalX + blockU) * WebRenderCore.RESOLUTION;
                    double pixelY = ((master.getScreenHeight() - 1 - logicalY) + blockV) * WebRenderCore.RESOLUTION;

                    if (isMouseJustPressed) {
                        UIElement clickedEl = handleClick(sessionId, pixelX, pixelY);
                        // ★ input と textarea をクリックした際に確実に InvisibleInputScreen を開く
                        if (clickedEl != null && ("input".equals(clickedEl.type()) || "textarea".equals(clickedEl.type()))) {
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
        if (clickedElement != null && ("input".equals(clickedElement.type()) || "textarea".equals(clickedElement.type()))) {
            // ★ InvisibleInputScreen を開き、内部で EditBox が生成される
            Minecraft.getInstance().setScreen(new InvisibleInputScreen(sessionId, clickedElement, pos));
        }
    }

    public static void playElementSound(UIElement el) {
        if (el == null) return;
        Map<String, String> events = el.events();
        String soundId = events != null ? events.get("data-sound") : null;
        if ("none".equalsIgnoreCase(soundId)) return;

        float pitch = 1.0f;
        if (events != null && events.containsKey("data-sound-pitch")) {
            try { pitch = Float.parseFloat(events.get("data-sound-pitch")); } catch (NumberFormatException ignored) {}
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

    private static boolean checkDistanceInfo(Minecraft mc, RouterDistanceInfo info) {
        if (info == null) return true;
        if (info.maxDistSqr() == Double.MAX_VALUE) return true;
        boolean isSameDim = mc.level.dimension().location().toString().equals(info.dimension());
        if (!isSameDim) return false;
        return mc.player.blockPosition().distSqr(BlockPos.of(info.posLong())) <= info.maxDistSqr();
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
        return checkDistanceInfo(mc, info);
    }

    public static boolean isItemInRange(Minecraft mc, ItemStack stack) {
        return itemRangeValidator != null ? itemRangeValidator.test(mc, stack) : defaultIsItemInRange(mc, stack);
    }

    public static boolean defaultIsItemInRange(Minecraft mc, ItemStack stack) {
        if (mc.player == null || mc.level == null) return false;
        CustomData customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        CompoundTag tag = customData.copyTag();
        if (!tag.contains("RouterPos")) return true;

        double maxDist = tag.getDouble("RouterRange");
        RouterDistanceInfo info = new RouterDistanceInfo(
                tag.getLong("RouterPos"),
                tag.getString("RouterDim"),
                maxDist == Double.MAX_VALUE ? Double.MAX_VALUE : maxDist * maxDist
        );
        return checkDistanceInfo(mc, info);
    }

    public static void clearDistanceCache(String screenKey) {
        ROUTER_DISTANCE_CACHE.remove(screenKey);
    }

    private static SessionState getState(String sessionId) {
        return states.computeIfAbsent(sessionId, k -> new SessionState());
    }

    private static void triggerLocalEvent(String sessionId, String elementId, String eventName, String actionName, String value) {
        if (sessionId == null || sessionId.isEmpty()) return;
        boolean hasCustomAction = actionName != null && !actionName.isEmpty() && !"auto".equals(actionName);
        boolean isFormSyncEvent = ("oninput".equalsIgnoreCase(eventName) || "onchange".equalsIgnoreCase(eventName))
                && elementId != null && !elementId.isEmpty();

        if (hasCustomAction && clientEventCallback != null) {
            clientEventCallback.onEvent(sessionId, elementId, eventName, actionName, value);
        }

        if ((hasCustomAction || isFormSyncEvent) && (forwardEventsToServer || ClientWebManager.isServerManagedSession(sessionId))) {
            CompoundTag tag = new CompoundTag();
            tag.putString("ElementId", elementId != null ? elementId : "");
            tag.putString("EventName", eventName != null ? eventName : "");
            tag.putString("ActionName", actionName != null ? actionName : "");
            tag.putString("Value", value != null ? value : "");
            PacketDistributor.sendToServer(new WebActionC2SPacket(sessionId, "ui_event", tag));
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
        String safeVal = newValue != null ? newValue : "";
        String lastValue = state.lastBindValues.getOrDefault(key, "");

        if (!safeVal.equals(lastValue)) {
            state.lastBindValues.put(key, safeVal);
            triggerLocalEvent(sessionId, ClientWebManager.getBaseNodeId(el.id()), "onchange", getEventAction(el.events(), "onchange"), safeVal);
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

    public static void syncServerValueOverride(String sessionId, String elementId, String newValue) {
        if (sessionId == null || elementId == null) return;
        String baseId = ClientWebManager.getBaseNodeId(elementId);
        SessionState state = getState(sessionId);
        if (state.localInputCache.containsKey(baseId)) {
            state.localInputCache.put(baseId, newValue != null ? newValue : "");
        }
    }

    public static void applyLocalOverrides(String sessionId) {
        SessionState state = getState(sessionId);
        for (Map.Entry<String, String> entry : state.localInputCache.entrySet()) {
            ClientWebManager.updateElementTextLocally(sessionId, entry.getKey(), entry.getValue());
        }
        if (!state.draggingId.isEmpty() && !state.lastRangeText.isEmpty()) {
            ClientWebManager.updateElementTextLocally(sessionId, state.draggingId, state.lastRangeText);
        }
        if (System.currentTimeMillis() - state.lastSubmitTime < 1500 && !state.lastSubmittedId.isEmpty()) {
            ClientWebManager.updateElementTextLocally(sessionId, state.lastSubmittedId, state.lastSubmittedText);
        }
        if (mutateDomOnHover && !state.hoveredId.isEmpty()) {
            ClientWebManager.updateNodeAttributeLocally(sessionId, state.hoveredId, "hover", "true", false);
        }
        String activeId = WebRenderCore.activeInputId;
        if (activeId != null && !activeId.isEmpty() && !activeId.endsWith("_textarea")) {
            EditBox eb = WebRenderCore.findActiveEditBox();
            if (eb != null) {
                // ★ EditBox に入力されたテキストを、DOMとUI要素の表示テキストにリアルタイム同期する
                updateInputLocally(sessionId, activeId, eb.getValue());
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
                    if (mutateDomOnHover) {
                        ClientWebManager.updateNodeAttributeLocally(sessionId, state.hoveredId, "hover", null, true);
                    }
                }
                triggerLocalEvent(sessionId, currentHitId, "onmouseenter", getEventAction(events, "onmouseenter"), coordStr);
                if (mutateDomOnHover) {
                    ClientWebManager.updateNodeAttributeLocally(sessionId, currentHitId, "hover", "true", false);
                }
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
                if (mutateDomOnHover) {
                    ClientWebManager.updateNodeAttributeLocally(sessionId, state.hoveredId, "hover", null, true);
                }
                state.hoveredId = "";
            }
            return "";
        }
    }

    private static void cycleSelectOption(String sessionId, String baseId) {
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
        int currentIdx = values.indexOf(current);
        int nextIdx = (currentIdx + 1) % values.size();
        String nextVal = values.get(nextIdx);

        submitInput(sessionId, baseId, nextVal);
        ClientWebManager.updateNodeAttributeLocally(sessionId, baseId, "value", nextVal, false);
        ClientWebManager.processRenderQueue();
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

            if ("radio".equals(clickedEl.type())) {
                HtmlNode target = DomUtils.findNodeById(ClientWebManager.getVirtualRoot(sessionId), baseId);
                if (target != null) {
                    DomUtils.selectRadioButton(ClientWebManager.getVirtualRoot(sessionId), target);
                    triggerLocalEvent(sessionId, baseId, "onchange", getEventAction(events, "onchange"), "true");
                    ClientWebManager.processRenderQueue();
                }
            } else if ("toggle".equals(clickedEl.type()) || "checkbox".equals(clickedEl.type())) {
                ClientWebManager.toggleNodeAttributeLocally(sessionId, baseId, "checked");
                HtmlNode node = DomUtils.findNodeById(ClientWebManager.getVirtualRoot(sessionId), baseId);
                String checkedStr = Boolean.toString(node != null && node.attrs.containsKey("checked"));
                triggerLocalEvent(sessionId, baseId, "onchange", getEventAction(events, "onchange"), checkedStr);
                ClientWebManager.processRenderQueue();
            } else if ("select".equals(clickedEl.type()) && Minecraft.getInstance().screen == null) {
                // ★ select の場合、クリックするたびに値と表示が切り替わる
                cycleSelectOption(sessionId, baseId);
            }

            if (hasEvent(events, "ondrag") || "range".equals(clickedEl.type())) {
                state.draggingId = baseId;
                state.lastSentX = hit.localX();
                state.lastSentY = hit.localY();

                if ("range".equals(clickedEl.type())) {
                    state.lastRatio = Mth.clamp((float) (hit.localX() / Math.max(1.0, clickedEl.width())), 0.0f, 1.0f);
                    state.lastRangeText = generateRangeText(clickedEl.text(), state.lastRatio);
                    state.lastRangeAction = getEventAction(events, "oninput");
                    state.lastRangeChangeAction = getEventAction(events, "onchange");
                    updateInputLocally(sessionId, state.draggingId, state.lastRangeText);
                    triggerLocalEvent(sessionId, state.draggingId, "oninput", state.lastRangeAction, state.lastRangeText.split(":")[0]);
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
                float ratio = Mth.clamp((float) (dragHit.localX() / Math.max(1.0, draggedEl.width())), 0.0f, 1.0f);
                if (Math.abs(state.lastRatio - ratio) > 0.002f) {
                    state.lastRatio = ratio;
                    state.lastRangeText = generateRangeText(draggedEl.text(), ratio);
                    updateInputLocally(sessionId, state.draggingId, state.lastRangeText);
                    if (now - state.lastEventTime > 40) {
                        triggerLocalEvent(sessionId, state.draggingId, "oninput", state.lastRangeAction, state.lastRangeText.split(":")[0]);
                        state.lastEventTime = now;
                    }
                }
            } else if (now - state.lastEventTime > 40) {
                triggerLocalEvent(sessionId, state.draggingId, "ondrag", getEventAction(events, "ondrag"), String.format(Locale.US, "%.1f,%.1f", dragHit.localX(), dragHit.localY()));
                state.lastEventTime = now;
            }
        }
        return true;
    }

    public static void handleRelease(String sessionId) {
        WebRenderCore.activePressedId = "";
        SessionState state = getState(sessionId);
        if (!state.draggingId.isEmpty()) {
            if (state.lastRatio >= 0 && !state.lastRangeText.isEmpty()) {
                String valOnly = state.lastRangeText.split(":")[0];
                triggerLocalEvent(sessionId, state.draggingId, "oninput", state.lastRangeAction, valOnly);
                triggerLocalEvent(sessionId, state.draggingId, "onchange", state.lastRangeChangeAction, valOnly);
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
        if (elementId == null || elementId.isEmpty()) return;
        String baseId = ClientWebManager.getBaseNodeId(elementId);
        String safeText = text != null ? text : "";
        SessionState state = getState(sessionId);
        state.localInputCache.put(baseId, safeText);
        ClientWebManager.updateElementTextLocally(sessionId, baseId, safeText);
    }

    public static void submitInput(String sessionId, String elementId, String text) {
        if (elementId == null || elementId.isEmpty()) return;
        String baseId = ClientWebManager.getBaseNodeId(elementId);
        String safeText = text != null ? text : "";
        updateInputLocally(sessionId, baseId, safeText);
        UIElement el = ClientWebManager.getElementById(sessionId, baseId);
        Map<String, String> events = el != null && el.events() != null ? el.events() : Map.of();
        triggerLocalEvent(sessionId, baseId, "oninput", getEventAction(events, "oninput"), safeText);
        triggerLocalEvent(sessionId, baseId, "onchange", getEventAction(events, "onchange"), safeText);
        SessionState state = getState(sessionId);
        state.lastSubmittedId = baseId;
        state.lastSubmittedText = safeText;
        state.lastSubmitTime = System.currentTimeMillis();
    }

    private static String generateRangeText(String oldText, float ratio) {
        float min = 0, max = 100;
        String[] parts = (oldText != null ? oldText : "").split(":");
        if (parts.length >= 3) {
            try {
                min = Float.parseFloat(parts[1]);
                max = Float.parseFloat(parts[2]);
            } catch (NumberFormatException ignored) {}
        }
        int newVal = Math.round(min + (max - min) * ratio);
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
        final Map<String, String> lastBindValues = new ConcurrentHashMap<>();
        final Map<String, String> localInputCache = new ConcurrentHashMap<>();
    }
}