package com.nishiyu.enderframelib.client;

import net.neoforged.neoforge.client.event.InputEvent;
import java.util.concurrent.atomic.AtomicInteger;
import com.nishiyu.enderframelib.EnderFrameLib;
import com.nishiyu.enderframelib.core.layout.DisplayItem.ElementType;
import com.nishiyu.enderframelib.api.WebScreenBlockEntity;
import com.nishiyu.enderframelib.client.gui.InvisibleInputScreen;
import com.nishiyu.enderframelib.client.gui.InvisibleInputScreen.WebTextInputHost;
import com.nishiyu.enderframelib.client.renderer.DisplayItemDrawer;
import com.nishiyu.enderframelib.api.event.Event;
import com.nishiyu.enderframelib.core.dom.EventForwardDeclaration;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.event.CoreEvent;
import com.nishiyu.enderframelib.core.event.CoreEvent.DomEvent;
import com.nishiyu.enderframelib.core.event.StandardDefaultActions;
import com.nishiyu.enderframelib.core.event.UiEvents;
import com.nishiyu.enderframelib.core.layout.DisplayItem;
import com.nishiyu.enderframelib.network.EnderFrameNetwork.WebActionC2SPacket;
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
import org.lwjgl.glfw.GLFW;

import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.ArrayList;
import java.util.List;

public class ClientScreenInteractionManager {

    @FunctionalInterface
    public interface IClientEventCallback {
        boolean onEvent(String sessionId, String elementId, String eventName, String actionName, String value);
    }

    private static volatile IClientEventCallback clientEventCallback = null;
    private static volatile Consumer<WebActionC2SPacket> serverActionSender = PacketDistributor::sendToServer;
    private static volatile boolean enableDefaultUiSounds = true;
    private static volatile boolean mutateDomOnHover = false;
    private static volatile Function<ItemStack, String> itemSessionIdExtractor = ClientScreenInteractionManager::defaultGetSessionIdFromItem;
    private static volatile BiPredicate<Minecraft, ItemStack> itemRangeValidator = ClientScreenInteractionManager::defaultIsItemInRange;

    public static void setClientEventCallback(IClientEventCallback callback) { clientEventCallback = callback; }
    public static void removeClientEventCallback(IClientEventCallback callback) { if (clientEventCallback == callback) clientEventCallback = null; }
    public static void setServerActionSender(Consumer<WebActionC2SPacket> sender) { serverActionSender = sender != null ? sender : PacketDistributor::sendToServer; }
    public static void sendToServer(WebActionC2SPacket packet) { serverActionSender.accept(packet); }
    public static void setEnableDefaultUiSounds(boolean enable) { enableDefaultUiSounds = enable; }
    public static boolean isEnableDefaultUiSounds() { return enableDefaultUiSounds; }
    public static void setMutateDomOnHover(boolean mutate) { mutateDomOnHover = mutate; }
    public static boolean isMutateDomOnHover() { return mutateDomOnHover; }
    public static void setItemSessionIdExtractor(Function<ItemStack, String> extractor) { itemSessionIdExtractor = extractor != null ? extractor : ClientScreenInteractionManager::defaultGetSessionIdFromItem; }
    public static void setItemRangeValidator(BiPredicate<Minecraft, ItemStack> validator) { itemRangeValidator = validator != null ? validator : ClientScreenInteractionManager::defaultIsItemInRange; }

    private static final long MOVE_EVENT_INTERVAL_MS = 50;
    private static final long DRAG_EVENT_INTERVAL_MS = 40;
    private static final int NO_PENDING_PRESS = -1;
    private static String lastHoveredSession = "";

    public record RouterDistanceInfo(long posLong, String dimension, double maxDistSqr) {}

    private static final AtomicInteger pendingPressButton = new AtomicInteger(NO_PENDING_PRESS);
    private static volatile boolean pendingRelease = false;
    private static volatile boolean leftHeld = false;
    private static volatile boolean rightHeld = false;

    static {
        try {
            NeoForge.EVENT_BUS.addListener(ClientScreenInteractionManager::onClientTick);
            NeoForge.EVENT_BUS.addListener(ClientScreenInteractionManager::onMouseButton);
        } catch (Throwable t) {
            EnderFrameLib.LOGGER.error("Failed to register ClientScreenInteractionManager listeners; in-world screen interaction will not work", t);
        }
    }

    public static void onMouseButton(InputEvent.MouseButton.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return;
        int button = event.getButton();
        boolean isLeft = button == GLFW.GLFW_MOUSE_BUTTON_LEFT;
        boolean isRight = button == GLFW.GLFW_MOUSE_BUTTON_RIGHT;
        if (!isLeft && !isRight) return;
        if (event.getAction() == GLFW.GLFW_PRESS) {
            if (isLeft) leftHeld = true; else rightHeld = true;
            pendingPressButton.compareAndSet(NO_PENDING_PRESS, isLeft ? 0 : 1);
        } else if (event.getAction() == GLFW.GLFW_RELEASE) {
            if (isLeft) leftHeld = false; else rightHeld = false;
            pendingRelease = true;
        }
    }

    private static void resetMouseLatches() {
        pendingPressButton.set(NO_PENDING_PRESS);
        pendingRelease = false;
        leftHeld = false;
        rightHeld = false;
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        ClientScheduler.onClientTick();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        if (mc.screen != null) {
            resetMouseLatches();
            return;
        }

        // セッションが破棄された後に、最後にホバーしていた ID を引きずらない
        if (!lastHoveredSession.isEmpty() && ClientWebManager.getSession(lastHoveredSession) == null) {
            lastHoveredSession = "";
            resetMouseLatches();
        }

        int pressedButton = pendingPressButton.getAndSet(NO_PENDING_PRESS);
        boolean isMouseJustPressed = pressedButton != NO_PENDING_PRESS;
        boolean isMouseJustReleased = pendingRelease;
        pendingRelease = false;
        boolean isMouseDown = leftHeld || rightHeld || mc.options.keyAttack.isDown() || mc.options.keyUse.isDown();

        if (isMouseJustReleased && !lastHoveredSession.isEmpty()) {
            handleRelease(lastHoveredSession);
        }

        if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK) {
            BlockHitResult hitResult = (BlockHitResult) mc.hitResult;
            BlockPos pos = hitResult.getBlockPos();

            if (mc.level.getBlockEntity(pos) instanceof WebScreenBlockEntity screen) {
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

                    WebScreenBlockEntity master = screen.isMaster() ? screen :
                            (screen.getScreenMasterPos() != null && mc.level.getBlockEntity(screen.getScreenMasterPos()) instanceof WebScreenBlockEntity m ? m : screen);

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

                    double pixelX = (logicalX + blockU) * DisplayItemDrawer.RESOLUTION;
                    double pixelY = ((master.getScreenHeight() - 1 - logicalY) + blockV) * DisplayItemDrawer.RESOLUTION;

                    if (isMouseJustPressed) {
                        DisplayItem clickedEl = handleClick(sessionId, pixelX, pixelY, pressedButton);
                        if (clickedEl != null && (clickedEl.type() == ElementType.INPUT || clickedEl.type() == ElementType.TEXTAREA)) {
                            ClientWebManager.setActiveInputId(sessionId, clickedEl.id());
                            openInputScreen(sessionId, clickedEl, masterPos);
                        } else {
                            ClientWebManager.setActiveInputId(sessionId, "");
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

    public static void openInputScreen(String sessionId, DisplayItem clickedElement, BlockPos pos) {
        if (clickedElement != null && (clickedElement.type() == ElementType.INPUT || clickedElement.type() == ElementType.TEXTAREA)) {
            Minecraft.getInstance().setScreen(new InvisibleInputScreen(sessionId, clickedElement, pos));
        }
    }

    public static void playElementSound(DisplayItem el) {
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
            boolean isInteractive = el.type() == ElementType.BUTTON || el.type() == ElementType.TOGGLE || el.type() == ElementType.SELECT ||
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

    private static SessionContext.InputSessionState getState(String sessionId) {
        return ClientWebManager.getOrCreateSession(sessionId).inputState();
    }

    /** ホバー中の節の ID。ホバーの持ち主は {@link com.nishiyu.enderframelib.core.dom.InteractionState}。 */
    private static String getHoveredNodeId(String sessionId) {
        SessionContext session = ClientWebManager.getSession(sessionId);
        HtmlNode hovered = session != null ? session.interactionState().hoveredNode() : null;
        return hovered != null ? hovered.getId() : "";
    }

    public static void setDraggingId(String sessionId, String draggingId) {
        getState(sessionId).draggingId = (draggingId != null) ? draggingId : "";
    }

    public static void forwardDeclared(String sessionId, HtmlNode node, Event event, EventForwardDeclaration declaration) {
        ClientDomEventDispatcher.forwardDeclared(sessionId, node, event, declaration);
    }

    public static void runInlineAction(String sessionId, HtmlNode node, Event event, String action) {
        ClientDomEventDispatcher.runInlineAction(sessionId, node, event, action, clientEventCallback);
    }

    private static boolean hasEvent(Map<String, String> events, String eventName) {
        if (events == null || events.isEmpty()) return false;
        for (String key : events.keySet()) {
            if (key.equalsIgnoreCase(eventName)) return true;
        }
        return false;
    }

    private static void dispatchFormEvent(HtmlNode control, Event event) {
        control.dispatchEvent(event);
    }

    public static void ensureSynced(String sessionId) {
        if (!ClientWebManager.hasDocument(sessionId) && ClientWebManager.shouldRequestSync(sessionId)) {
            sendToServer(new WebActionC2SPacket(sessionId, "request_sync", new CompoundTag()));
        }
    }

    public static String getHoveredId(String sessionId) {
        return getHoveredNodeId(sessionId);
    }

    public static void syncServerValueOverride(String sessionId, String elementId, String newValue) {
        if (sessionId == null || elementId == null) return;
        String baseId = ClientWebManager.getBaseNodeId(elementId);
        SessionContext.InputSessionState state = getState(sessionId);
        if (state.localInputCache.containsKey(baseId)) {
            state.localInputCache.put(baseId, newValue != null ? newValue : "");
        }
    }

    public static void applyLocalOverrides(String sessionId) {
        SessionContext.InputSessionState state = getState(sessionId);
        for (Map.Entry<String, String> entry : state.localInputCache.entrySet()) {
            ClientWebManager.updateElementTextLocally(sessionId, entry.getKey(), entry.getValue());
        }
        if (!state.draggingId.isEmpty() && !state.lastRangeText.isEmpty()) {
            ClientWebManager.updateElementTextLocally(sessionId, state.draggingId, state.lastRangeText);
        }
        if (System.currentTimeMillis() - state.lastSubmitTime < 1500 && !state.lastSubmittedId.isEmpty()) {
            ClientWebManager.updateElementTextLocally(sessionId, state.lastSubmittedId, state.lastSubmittedText);
        }
        String hoveredId = getHoveredNodeId(sessionId);
        if (mutateDomOnHover && !hoveredId.isEmpty()) {
            ClientWebManager.updateNodeAttributeLocally(sessionId, hoveredId, "hover", "true", false);
        }
        String activeId = ClientWebManager.getActiveInputId(sessionId);
        if (activeId != null && !activeId.isEmpty() && !activeId.endsWith("_textarea")) {
            String activeVal = WebTextInputHost.getActiveText(sessionId);
            if (activeVal != null) {
                updateInputLocally(sessionId, activeId, activeVal);
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
        SessionContext.InputSessionState state = getState(sessionId);
        ClientWebManager.UIHitResult hit = ClientWebManager.getHitElement(sessionId, pixelX, pixelY);
        state.hoverX = pixelX;
        state.hoverY = pixelY;

        String currentHitId = hit != null ? ClientWebManager.getBaseNodeId(hit.element().id()) : "";
        double localX = hit != null ? hit.localX() : 0;
        double localY = hit != null ? hit.localY() : 0;

        HtmlNode previous = ClientWebManager.getSession(sessionId) != null
                ? ClientWebManager.getSession(sessionId).interactionState().hoveredNode() : null;
        HtmlNode next = hit != null ? ClientWebManager.findInteractiveNode(sessionId, currentHitId) : null;
        boolean enteredNewTarget = previous != next;
        if (enteredNewTarget) {
            ClientDomEventDispatcher.dispatchHoverTransition(previous, next, localX, localY);
            if (mutateDomOnHover) {
                if (previous != null && !previous.getId().isEmpty()) {
                    ClientWebManager.updateNodeAttributeLocally(sessionId, previous.getId(), "hover", null, true);
                }
                if (next != null && !next.getId().isEmpty()) {
                    ClientWebManager.updateNodeAttributeLocally(sessionId, next.getId(), "hover", "true", false);
                }
            }
            ClientWebManager.setHoveredElement(sessionId, next != null ? next.getId() : "");
        }

        long now = System.currentTimeMillis();
        if (next != null && (enteredNewTarget || now - state.lastEventTime > MOVE_EVENT_INTERVAL_MS)) {
            next.dispatchEvent(UiEvents.pointer("pointermove", localX, localY, 0, 0, false, false, false, false, null));
            next.dispatchEvent(UiEvents.mouse("mousemove", localX, localY, 0, 0, false, false, false, false, null));
            state.lastEventTime = now;
        }
        return currentHitId;
    }

    private static void cycleSelectOption(String sessionId, String baseId) {
        HtmlNode selectNode = ClientWebManager.findNode(sessionId, baseId);
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
    }

    public static DisplayItem handleClick(String sessionId, double pixelX, double pixelY) {
        return handleClick(sessionId, pixelX, pixelY, 0);
    }

    public static void dispatchPress(String sessionId, HtmlNode target, double x, double y, int minecraftButton) {
        SessionContext.InputSessionState state = getState(sessionId);
        state.pressedButton = minecraftButton;
        ClientDomEventDispatcher.dispatchPress(target, x, y, minecraftButton);
    }

    public static DisplayItem handleClick(String sessionId, double pixelX, double pixelY, int button) {
        SessionContext.InputSessionState state = getState(sessionId);
        ClientWebManager.UIHitResult hit = ClientWebManager.getHitElement(sessionId, pixelX, pixelY);
        if (hit == null) {
            ClientWebManager.setActivePressedId(sessionId, "");
            return null;
        }

        DisplayItem clickedEl = hit.element();
        String elementId = clickedEl.id();

        if (elementId.endsWith("_sb_vt") || elementId.endsWith("_sb_ht")) {
            ClientWebManager.handleScrollbarClick(sessionId, clickedEl, pixelX, pixelY);
            return clickedEl;
        } else if (elementId.endsWith("_sb_v") || elementId.endsWith("_sb_h")) {
            state.draggingId = elementId;
            state.lastSentX = hit.localX();
            state.lastSentY = hit.localY();
            return clickedEl;
        }

        String baseId = ClientWebManager.getBaseNodeId(elementId);
        ClientWebManager.setActivePressedId(sessionId, baseId);
        playElementSound(clickedEl);

        HtmlNode pressed = ClientWebManager.findInteractiveNode(sessionId, baseId);
        if (pressed != null) {
            dispatchPress(sessionId, pressed, hit.localX(), hit.localY(), button);
        }

        if (clickedEl.type() == ElementType.SELECT && Minecraft.getInstance().screen == null) {
            cycleSelectOption(sessionId, baseId);
        }

        startDragInteraction(sessionId, hit, pressed);
        return clickedEl;
    }

    public static void beginDrag(String sessionId, double pixelX, double pixelY) {
        ClientWebManager.UIHitResult hit = ClientWebManager.getHitElement(sessionId, pixelX, pixelY);
        if (hit == null) return;
        HtmlNode pressed = ClientWebManager.findInteractiveNode(sessionId, ClientWebManager.getBaseNodeId(hit.element().id()));
        startDragInteraction(sessionId, hit, pressed);
    }

    private static void startDragInteraction(String sessionId, ClientWebManager.UIHitResult hit, HtmlNode pressed) {
        DisplayItem element = hit.element();
        Map<String, String> events = element.events() != null ? element.events() : Map.of();
        if (!hasEvent(events, "ondrag") && element.type() != ElementType.RANGE) return;

        SessionContext.InputSessionState state = getState(sessionId);
        state.draggingId = ClientWebManager.getBaseNodeId(element.id());
        state.lastSentX = hit.localX();
        state.lastSentY = hit.localY();

        if (element.type() == ElementType.RANGE) {
            state.lastRatio = Mth.clamp((float) (hit.localX() / Math.max(1.0, element.width())), 0.0f, 1.0f);
            state.lastRangeText = generateRangeText(element.text(), state.lastRatio);
            updateInputLocally(sessionId, state.draggingId, state.lastRangeText);
            dispatchRangeInput(sessionId, state.draggingId, rangeValue(state.lastRangeText));
        } else if (pressed != null) {
            pressed.dispatchEvent(UiEvents.mouse("dragstart", hit.localX(), hit.localY(), 0, 1, false, false, false, false, null));
        }
    }

    private static void dispatchRangeInput(String sessionId, String baseId, String value) {
        HtmlNode slider = ClientWebManager.findNode(sessionId, baseId);
        if (slider == null) return;
        slider.attrs.put("value", value);
        dispatchFormEvent(slider, new CoreEvent.CoreUIEvent("input", true, false, 0));
    }

    private static String rangeValue(String rangeText) {
        return rangeText.split(":")[0];
    }

    private static void dispatchClick(String sessionId, HtmlNode target, double x, double y, int minecraftButton) {
        int button = ClientDomEventDispatcher.domButton(minecraftButton);
        if (button == 0) {
            DomEvent click = DomEvent.click(x, y, 0);
            target.dispatchEvent(click, event -> StandardDefaultActions.execute(event, ClientDomEventDispatcher.createClickDefaults(sessionId, x, y, null, minecraftButton, null, null,
                    (t) -> dispatchClick(sessionId, t, x, y, 0))));
            ClientDomEventDispatcher.dispatchDoubleClickIfRepeated(sessionId, target, x, y);
            return;
        }
        if (button == 2) {
            target.dispatchEvent(UiEvents.mouse("contextmenu", x, y, button, 0, false, false, false, false, null));
        }
        target.dispatchEvent(UiEvents.mouse("auxclick", x, y, button, 0, false, false, false, false, null));
    }

    public static boolean handleDrag(String sessionId, double pixelX, double pixelY) {
        SessionContext.InputSessionState state = getState(sessionId);
        if (state.draggingId.isEmpty()) return false;
        if (ClientWebManager.handleScrollbarDrag(sessionId, state.draggingId, pixelX, pixelY)) {
            return true;
        }
        ClientWebManager.UIHitResult dragHit = ClientWebManager.getLocalCoords(sessionId, state.draggingId, pixelX, pixelY);
        long now = System.currentTimeMillis();

        if (dragHit != null) {
            DisplayItem draggedEl = dragHit.element();
            state.lastSentX = dragHit.localX();
            state.lastSentY = dragHit.localY();

            if (draggedEl.type() == ElementType.RANGE) {
                float ratio = Mth.clamp((float) (dragHit.localX() / Math.max(1.0, draggedEl.width())), 0.0f, 1.0f);
                if (Math.abs(state.lastRatio - ratio) > 0.002f) {
                    state.lastRatio = ratio;
                    state.lastRangeText = generateRangeText(draggedEl.text(), ratio);
                    updateInputLocally(sessionId, state.draggingId, state.lastRangeText);
                    if (now - state.lastEventTime > DRAG_EVENT_INTERVAL_MS) {
                        dispatchRangeInput(sessionId, state.draggingId, rangeValue(state.lastRangeText));
                        state.lastEventTime = now;
                    }
                }
            } else if (now - state.lastEventTime > DRAG_EVENT_INTERVAL_MS) {
                HtmlNode dragged = ClientWebManager.findInteractiveNode(sessionId, state.draggingId);
                if (dragged != null) {
                    dragged.dispatchEvent(UiEvents.mouse("drag", dragHit.localX(), dragHit.localY(), 0, 1, false, false, false, false, null));
                }
                state.lastEventTime = now;
            }
        }
        return true;
    }

    public static void handleRelease(String sessionId) {
        SessionContext.InputSessionState state = getState(sessionId);
        handleRelease(sessionId, state.hoverX, state.hoverY, state.pressedButton, true);
    }

    public static void handleRelease(String sessionId, double pixelX, double pixelY, int minecraftButton, boolean synthesizeClick) {
        SessionContext.InputSessionState state = getState(sessionId);
        // 押下中の節は InteractionState が持つ。解除する前に読み、クリック合成に使う。
        HtmlNode pressed = ClientWebManager.getSession(sessionId) != null
                ? ClientWebManager.getSession(sessionId).interactionState().activeNode() : null;
        ClientWebManager.setActivePressedId(sessionId, "");

        ClientWebManager.UIHitResult hit = ClientWebManager.getHitElement(sessionId, pixelX, pixelY);
        HtmlNode released = hit != null
                ? ClientWebManager.findInteractiveNode(sessionId, ClientWebManager.getBaseNodeId(hit.element().id()))
                : null;
        int button = ClientDomEventDispatcher.domButton(minecraftButton);
        if (released != null) {
            released.dispatchEvent(UiEvents.pointer("pointerup", hit.localX(), hit.localY(), button, 0, false, false, false, false, null));
            released.dispatchEvent(UiEvents.mouse("mouseup", hit.localX(), hit.localY(), button, 0, false, false, false, false, null));
        }

        finishDrag(sessionId, state);

        if (synthesizeClick && pressed != null && released != null) {
            HtmlNode target = ClientDomEventDispatcher.nearestCommonAncestor(pressed, released);
            if (target != null) {
                dispatchClick(sessionId, target, hit.localX(), hit.localY(), minecraftButton);
            }
        }
    }

    private static void finishDrag(String sessionId, SessionContext.InputSessionState state) {
        if (state.draggingId.isEmpty()) return;

        HtmlNode dragged = ClientWebManager.findInteractiveNode(sessionId, state.draggingId);
        if (state.lastRatio >= 0 && !state.lastRangeText.isEmpty()) {
            String value = rangeValue(state.lastRangeText);
            if (dragged != null) {
                dispatchRangeInput(sessionId, state.draggingId, value);
                dispatchFormEvent(dragged, new CoreEvent.CoreUIEvent("change", true, false, 0));
            }
            state.lastSubmittedId = state.draggingId;
            state.lastSubmittedText = state.lastRangeText;
            state.lastSubmitTime = System.currentTimeMillis();
        }
        if (dragged != null) {
            dragged.dispatchEvent(UiEvents.mouse("dragend", state.lastSentX, state.lastSentY, 0, 0, false, false, false, false, null));
        }
        state.draggingId = "";
        state.lastRatio = -1.0f;
        state.lastRangeText = "";
    }

    public static boolean handleScroll(String sessionId, double pixelX, double pixelY, double deltaX, double deltaY) {
        ClientWebManager.UIHitResult hit = ClientWebManager.getHitElement(sessionId, pixelX, pixelY);
        if (hit != null) {
            String baseId = ClientWebManager.getBaseNodeId(hit.element().id());
            HtmlNode domNode = ClientWebManager.findInteractiveNode(sessionId, baseId);
            if (domNode != null) {
                var wheelEv = UiEvents.wheel(hit.localX(), hit.localY(), deltaX, deltaY, false, false, false, false);
                domNode.dispatchEvent(wheelEv);
                if (wheelEv.isDefaultPrevented()) {
                    return true;
                }
            }
        }
        return ClientWebManager.handleScrollLocally(sessionId, pixelX, pixelY, deltaX, deltaY);
    }

    public static void updateInputLocally(String sessionId, String elementId, String text) {
        if (elementId == null || elementId.isEmpty()) return;
        String baseId = ClientWebManager.getBaseNodeId(elementId);
        String safeText = text != null ? text : "";
        SessionContext.InputSessionState state = getState(sessionId);
        state.localInputCache.put(baseId, safeText);
        ClientWebManager.updateElementTextLocally(sessionId, baseId, safeText);
    }

    public static boolean beginTextEdit(String sessionId, String elementId, String data, String inputType) {
        HtmlNode control = ClientWebManager.findNode(sessionId, ClientWebManager.getBaseNodeId(elementId));
        if (control == null) return true;
        var beforeInput = UiEvents.input("beforeinput", data, false, inputType);
        control.dispatchEvent(beforeInput);
        return !beforeInput.isDefaultPrevented();
    }

    public static void commitTextEdit(String sessionId, String elementId, String newValue, String data, String inputType) {
        HtmlNode control = ClientWebManager.findNode(sessionId, ClientWebManager.getBaseNodeId(elementId));
        String safeValue = newValue != null ? newValue : "";
        if (control == null || safeValue.equals(control.attrs.getOrDefault("value", ""))) return;
        control.attrs.put("value", safeValue);
        dispatchFormEvent(control, UiEvents.input("input", data, false, inputType));
    }

    public static void submitInput(String sessionId, String elementId, String text) {
        if (elementId == null || elementId.isEmpty()) return;
        String baseId = ClientWebManager.getBaseNodeId(elementId);
        String safeText = text != null ? text : "";
        updateInputLocally(sessionId, baseId, safeText);

        HtmlNode control = ClientWebManager.findNode(sessionId, baseId);
        if (control != null) {
            boolean valueChanged = !safeText.equals(control.attrs.getOrDefault("value", ""));
            control.attrs.put("value", safeText);
            if (!ClientDomEventDispatcher.isCheckable(control)) {
                if (valueChanged) {
                    dispatchFormEvent(control, UiEvents.input("input", safeText, false, "insertText"));
                }
                dispatchFormEvent(control, new CoreEvent.CoreUIEvent("change", true, false, 0));
            }
        }
        SessionContext.InputSessionState state = getState(sessionId);
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

}