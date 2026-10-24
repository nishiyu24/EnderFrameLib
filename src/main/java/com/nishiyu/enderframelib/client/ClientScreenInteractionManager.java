package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.core.paint.model.*;

import net.neoforged.neoforge.client.event.InputEvent;
import java.util.concurrent.atomic.AtomicInteger;
import com.nishiyu.enderframelib.EnderFrameLib;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.client.gui.InvisibleInputScreen;
import com.nishiyu.enderframelib.api.event.Event;
import com.nishiyu.enderframelib.core.dom.EventForwardDeclaration;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.network.UiActionC2SPacket;
import com.nishiyu.enderframelib.network.WebActionC2SPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * ワールド内（ブロック面・アイテム・HUD）の入力ホスト。
 *
 * <p>ホスト固有の仕事は「ブロック面のどこを指しているか」を Web 座標へ直すことと、マウスの押下・解放を
 * tick に合わせて 1 回だけ配送することだけ。イベントの合成はすべて {@link SessionInput} が行う
 * （STRUCTURE_PLAN S4）。ここに残る static メソッドは、既存の呼び出し側とテストのための互換の窓口。</p>
 */
public class ClientScreenInteractionManager {

    @FunctionalInterface
    public interface IClientEventCallback {
        boolean onEvent(String sessionId, String elementId, String eventName, String actionName, String value);
    }

    private static volatile IClientEventCallback clientEventCallback = null;
    private static volatile Consumer<WebActionC2SPacket> serverActionSender = PacketDistributor::sendToServer;
    private static volatile Consumer<UiActionC2SPacket> uiActionSender = PacketDistributor::sendToServer;
    private static volatile boolean enableDefaultUiSounds = true;
    private static volatile boolean mutateDomOnHover = false;
    private static volatile Function<ItemStack, String> itemSessionIdExtractor = ClientScreenInteractionManager::defaultGetSessionIdFromItem;
    private static volatile BiPredicate<Minecraft, ItemStack> itemRangeValidator = ClientScreenInteractionManager::defaultIsItemInRange;

    public static void setClientEventCallback(IClientEventCallback callback) { clientEventCallback = callback; }
    static IClientEventCallback clientEventCallback() { return clientEventCallback; }
    public static void removeClientEventCallback(IClientEventCallback callback) { if (clientEventCallback == callback) clientEventCallback = null; }
    public static void setServerActionSender(Consumer<WebActionC2SPacket> sender) { serverActionSender = sender != null ? sender : PacketDistributor::sendToServer; }
    public static void sendToServer(WebActionC2SPacket packet) { serverActionSender.accept(packet); }
    public static void setUiActionSender(Consumer<UiActionC2SPacket> sender) { uiActionSender = sender != null ? sender : PacketDistributor::sendToServer; }
    public static void sendToServer(UiActionC2SPacket packet) { uiActionSender.accept(packet); }
    /**
     * ボタンクリックなどの対話時に Minecraft の既定 UI 効果音（クリック音など）を再生するかどうかを設定する。
     *
     * @param enable 既定の効果音を有効にする場合は {@code true}
     */
    public static void setEnableDefaultUiSounds(boolean enable) { enableDefaultUiSounds = enable; }

    /**
     * ボタンクリックなどの対話時に Minecraft の既定 UI 効果音（クリック音など）を再生するかどうかを取得する。
     *
     * @return 既定の効果音が有効な場合は {@code true}
     */
    public static boolean isEnableDefaultUiSounds() { return enableDefaultUiSounds; }
    public static void setMutateDomOnHover(boolean mutate) { mutateDomOnHover = mutate; }
    public static boolean isMutateDomOnHover() { return mutateDomOnHover; }

    /**
     * 対象アイテム（{@link ItemStack}）から Web セッション ID を抽出する関数を登録する。
     * <p>
     * 独自コンポーネントや NBT、カスタムタグなどからセッション ID を読み取る下流 Mod 向けの差し込み口。
     * {@code null} を渡すと既定の抽出器に戻る。
     *
     * @param extractor アイテムスタックからセッション ID 文字列を抽出する関数
     */
    public static void setItemSessionIdExtractor(Function<ItemStack, String> extractor) { itemSessionIdExtractor = extractor != null ? extractor : ClientScreenInteractionManager::defaultGetSessionIdFromItem; }
    public static void setItemRangeValidator(BiPredicate<Minecraft, ItemStack> validator) { itemRangeValidator = validator != null ? validator : ClientScreenInteractionManager::defaultIsItemInRange; }

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

    /** 直前のマウス操作で GUI を閉じたとき、同じ操作がワールド側の入力として二重に数えられないようにする。 */
    private static volatile boolean swallowNextMouseEvent = false;

    public static void swallowNextMouseEvent() { swallowNextMouseEvent = true; }

    public static void onMouseButton(InputEvent.MouseButton.Post event) {
        if (swallowNextMouseEvent) {
            swallowNextMouseEvent = false;
            resetMouseLatches();
            return;
        }
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

        SurfaceHitResolver.SurfaceHit hit = SurfaceHitResolver.fromCrosshair(mc);
        if (hit != null) {
            String sessionId = hit.sessionId();
            lastHoveredSession = sessionId;

            if (isMouseJustPressed) {
                DisplayItem clickedEl = handleClick(sessionId, hit.pixelX(), hit.pixelY(), pressedButton);
                if (clickedEl != null && (clickedEl.type() == ElementType.INPUT || clickedEl.type() == ElementType.TEXTAREA)) {
                    SessionContext session = ClientWebManager.getOrCreateSession(sessionId);
                    session.activateInput(clickedEl.id());
                    openInputScreen(sessionId, clickedEl, hit.masterPos());
                } else {
                    SessionContext session = ClientWebManager.getSession(sessionId);
                    if (session != null) session.setActiveInputId("");
                }
            } else if (isMouseDown) {
                handleDrag(sessionId, hit.pixelX(), hit.pixelY());
            }
            handleHover(sessionId, hit.pixelX(), hit.pixelY());
            return;
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

    /** ホバー中の節の ID。ホバーの持ち主は {@link com.nishiyu.enderframelib.core.dom.InteractionState}。 */
    public static String getHoveredId(String sessionId) {
        SessionContext session = ClientWebManager.getSession(sessionId);
        return session != null ? session.input().hoveredId() : "";
    }

    public static void setDraggingId(String sessionId, String draggingId) {
        ClientWebManager.getOrCreateSession(sessionId).input().setDraggingId(draggingId);
    }

    public static void forwardDeclared(String sessionId, HtmlNode node, Event event, EventForwardDeclaration declaration) {
        ClientWebManager.getOrCreateSession(sessionId).input().forwardDeclared(node, event, declaration);
    }

    public static void runInlineAction(String sessionId, HtmlNode node, Event event, String action) {
        ClientWebManager.getOrCreateSession(sessionId).input().runInlineAction(node, event, action);
    }

    public static void ensureSynced(String sessionId) {
        SessionContext session = ClientWebManager.getOrCreateSession(sessionId);
        if (session.document() == null && session.shouldRequestSync()) {
            sendToServer(new WebActionC2SPacket(sessionId, "request_sync", new CompoundTag()));
        }
    }

    public static void syncServerValueOverride(String sessionId, String elementId, String newValue) {
        if (sessionId == null || elementId == null) return;
        ClientWebManager.getOrCreateSession(sessionId).input().syncServerValueOverride(elementId, newValue);
    }

    public static void applyLocalOverrides(String sessionId) {
        ClientWebManager.getOrCreateSession(sessionId).input().applyLocalOverrides();
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
        return ClientWebManager.getOrCreateSession(sessionId).input().hover(pixelX, pixelY);
    }

    private static void cycleSelectOption(String sessionId, String baseId) {
        SessionContext session = ClientWebManager.getSession(sessionId);
        HtmlNode selectNode = session != null ? session.findNode(baseId) : null;
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
        SessionContext updated = ClientWebManager.getSession(sessionId);
        if (updated != null) updated.updateNodeAttributeLocally(baseId, "value", nextVal, false);
    }

    public static DisplayItem handleClick(String sessionId, double pixelX, double pixelY) {
        return handleClick(sessionId, pixelX, pixelY, 0);
    }

    public static void dispatchPress(String sessionId, HtmlNode target, double x, double y, int minecraftButton) {
        ClientWebManager.getOrCreateSession(sessionId).input().dispatchPress(target, x, y, minecraftButton);
    }

    public static DisplayItem handleClick(String sessionId, double pixelX, double pixelY, int button) {
        SessionContext session = ClientWebManager.getOrCreateSession(sessionId);
        SessionInput.PressResult result = session.input().press(pixelX, pixelY, button, SessionInput.Host.NONE);
        if (result == null) return null;
        if (!result.consumed() && result.element() != null && result.element().type() == ElementType.SELECT
                && Minecraft.getInstance().screen == null) {
            cycleSelectOption(sessionId, ClientWebManager.getBaseNodeId(result.element().id()));
        }
        return result.element();
    }

    public static void beginDrag(String sessionId, double pixelX, double pixelY) {
        SessionContext session = ClientWebManager.getSession(sessionId);
        if (session == null) return;
        session.input().beginDrag(pixelX, pixelY);
    }

    public static boolean handleDrag(String sessionId, double pixelX, double pixelY) {
        return ClientWebManager.getOrCreateSession(sessionId).input().drag(pixelX, pixelY);
    }

    public static void handleRelease(String sessionId) {
        SessionContext.InputSessionState state = ClientWebManager.getOrCreateSession(sessionId).inputState();
        handleRelease(sessionId, state.hoverX, state.hoverY, state.pressedButton, true);
    }

    public static void handleRelease(String sessionId, double pixelX, double pixelY, int minecraftButton, boolean synthesizeClick) {
        ClientWebManager.getOrCreateSession(sessionId).input()
                .release(pixelX, pixelY, minecraftButton, synthesizeClick, SessionInput.ClickSink.NONE);
    }

    public static boolean handleScroll(String sessionId, double pixelX, double pixelY, double deltaX, double deltaY) {
        SessionContext session = ClientWebManager.getSession(sessionId);
        if (session == null) return false;
        return session.input().scroll(pixelX, pixelY, deltaX, deltaY, false, false, false, SessionInput.Host.NONE);
    }

    public static void updateInputLocally(String sessionId, String elementId, String text) {
        ClientWebManager.getOrCreateSession(sessionId).input().updateInputLocally(elementId, text);
    }

    public static boolean beginTextEdit(String sessionId, String elementId, String data, String inputType) {
        SessionContext session = ClientWebManager.getSession(sessionId);
        if (session == null) return true;
        return session.input().beginTextEdit(elementId, data, inputType);
    }

    public static void commitTextEdit(String sessionId, String elementId, String newValue, String data, String inputType) {
        SessionContext session = ClientWebManager.getSession(sessionId);
        if (session == null) return;
        session.input().commitTextEdit(elementId, newValue, data, inputType);
    }

    public static void submitInput(String sessionId, String elementId, String text) {
        if (elementId == null || elementId.isEmpty()) return;
        ClientWebManager.getOrCreateSession(sessionId).input().submitInput(elementId, text);
    }
}
