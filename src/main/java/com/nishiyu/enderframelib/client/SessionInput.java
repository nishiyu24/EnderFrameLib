package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.client.renderer.DisplayItemDrawer;
import com.nishiyu.enderframelib.api.event.Event;
import com.nishiyu.enderframelib.api.event.UIEvents;
import com.nishiyu.enderframelib.client.gui.WebTextInputHost;
import com.nishiyu.enderframelib.core.dom.EventForwardDeclaration;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.event.CoreEvent;
import com.nishiyu.enderframelib.core.event.CoreUIEvent;
import com.nishiyu.enderframelib.core.event.StandardDefaultActions;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.network.UiActionC2SPacket;
import com.nishiyu.enderframelib.network.WebActionC2SPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.nishiyu.enderframelib.network.WebSyncS2CPacket;
import com.nishiyu.enderframelib.network.SubscribeC2SPacket;
import com.nishiyu.enderframelib.network.PubSubUpdateS2CPacket;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;
import com.nishiyu.enderframelib.common.Document;

/**
 * 1 セッション分の入力処理。ホスト（GUI 画面・ワールド内ブロック・アイテム・HUD）は
 * 「ホスト座標 → Web 座標」の変換だけを行い、そこから先のヒットテスト・pointer/mouse/click の合成・
 * キーとテキスト編集・既定動作・サーバーへの転送はすべてここを通す（STRUCTURE_PLAN S4）。
 *
 * <p>GUI 固有の判断（ドロップダウンの開閉・テキストエリアのフォーカス・シーンの操作など）は
 * {@link Host} と {@link ClickSink} にだけ残す。ワールド内は {@link Host#NONE} と
 * {@link ClickSink#NONE} を渡す。</p>
 */
public final class SessionInput {

    private static final long DOUBLE_CLICK_INTERVAL_MS = 500;
    private static final long MOVE_EVENT_INTERVAL_MS = 50;
    private static final long DRAG_EVENT_INTERVAL_MS = 40;

    private final SessionContext session;

    /** GUI 画面など、セッションごとのルータへ先に届けるための受け皿。 */
    private ClientScreenInteractionManager.IClientEventCallback localEventCallback;

    /** クリックの配送中だけ有効な、GUI 側のアクション受け皿。 */
    private ClickSink activeClickSink = ClickSink.NONE;
    private DisplayItem activeClickedItem = null;
    private int activeClickButton = 0;

    SessionInput(SessionContext session) {
        this.session = session;
    }

    /** ホスト固有の入力ポリシー。ワールド内は {@link #NONE}。 */
    public interface Host {
        Host NONE = new Host() {};

        /** 共通の押下処理（スクロールバー・press イベント・ドラッグ開始）が終わった後に呼ばれる。 */
        default void pressed(PressResult result) {}

        /** ホイールを DOM に送った後、ホスト固有のスクロール（シーン・テキストエリア）を処理する。 */
        default boolean scrollHandled(SessionContext.UIHitResult hit, double scrollY) {
            return false;
        }
    }

    /**
     * クリックの既定動作から GUI へ戻るための受け皿。{@link #NONE} はワールド内の既定動作
     * （ラベルのクリックを同じ座標へ再配送する）だけを行う。
     */
    public interface ClickSink {
        ClickSink NONE = new ClickSink() {};

        /** {@code on*} 属性のアクション名。GUI の {@code onWebAction} へ渡す。 */
        default void action(String action, DisplayItem clicked, int button) {}

        /** チェックボックス・ラジオの値変化。GUI の {@code onInputChanged} へ渡す。 */
        default void inputChanged(String elementId, String value) {}

        /** 既定動作が別の要素のクリックを要求したとき（label など）。 */
        default void clickDelegate(SessionInput input, HtmlNode target, double x, double y, int minecraftButton) {
            input.dispatchClick(target, x, y, minecraftButton, null, this);
        }
    }

    /** 押下の結果。{@code consumed} はスクロールバーなど、ホスト側の追加処理が不要な操作。 */
    public record PressResult(DisplayItem element, SessionContext.UIHitResult hit, HtmlNode pressedNode,
                              int minecraftButton, boolean consumed) {
    }

    private SessionContext.InputSessionState state() {
        return session.inputState();
    }

    // ------------------------------------------------------------------
    // ポインタ
    // ------------------------------------------------------------------

    public String hover(double pixelX, double pixelY) {
        SessionContext.InputSessionState state = state();
        SessionContext.UIHitResult hit = session.hitTest(pixelX, pixelY);
        state.hoverX = pixelX;
        state.hoverY = pixelY;

        String currentHitId = hit != null ? baseIdOf(hit.element()) : "";
        double localX = hit != null ? hit.localX() : 0;
        double localY = hit != null ? hit.localY() : 0;

        HtmlNode previous = session.interactionState().hoveredNode();
        HtmlNode next = hit != null ? session.findInteractiveNode(currentHitId) : null;
        boolean enteredNewTarget = previous != next;
        if (enteredNewTarget) {
            dispatchHoverTransition(previous, next, localX, localY);
            if (ClientScreenInteractionManager.isMutateDomOnHover()) {
                if (previous != null && !previous.getId().isEmpty()) {
                    session.updateNodeAttributeLocally(previous.getId(), "hover", null, true);
                }
                if (next != null && !next.getId().isEmpty()) {
                    session.updateNodeAttributeLocally(next.getId(), "hover", "true", false);
                }
            }
            session.setHoveredElement(next != null ? next.getId() : "");
        }

        long now = System.currentTimeMillis();
        if (next != null && (enteredNewTarget || now - state.lastEventTime > MOVE_EVENT_INTERVAL_MS)) {
            next.dispatchEvent(CoreEvent.pointer("pointermove", localX, localY, 0, 0, false, false, false, false, null));
            next.dispatchEvent(CoreEvent.mouse("mousemove", localX, localY, 0, 0, false, false, false, false, null));
            state.lastEventTime = now;
        }
        return currentHitId;
    }

    public PressResult press(double pixelX, double pixelY, int minecraftButton, Host host) {
        SessionContext.UIHitResult hit = session.hitTest(pixelX, pixelY);
        if (hit == null) {
            session.setActivePressedId("");
            return null;
        }

        SessionContext.InputSessionState state = state();
        DisplayItem clickedEl = hit.element();
        String elementId = clickedEl.id();

        if (elementId.endsWith("_sb_vt") || elementId.endsWith("_sb_ht")) {
            session.handleScrollbarClick(clickedEl, pixelX, pixelY);
            PressResult result = new PressResult(clickedEl, hit, null, minecraftButton, true);
            if (host != null) host.pressed(result);
            return result;
        } else if (elementId.endsWith("_sb_v") || elementId.endsWith("_sb_h")) {
            state.draggingId = elementId;
            state.lastSentX = hit.localX();
            state.lastSentY = hit.localY();
            PressResult result = new PressResult(clickedEl, hit, null, minecraftButton, true);
            if (host != null) host.pressed(result);
            return result;
        }

        String baseId = baseIdOf(clickedEl);
        session.setActivePressedId(baseId);
        state.pressedElement = clickedEl;
        ClientScreenInteractionManager.playElementSound(clickedEl);

        HtmlNode pressed = session.findInteractiveNode(baseId);
        if (pressed != null) {
            dispatchPress(pressed, hit.localX(), hit.localY(), minecraftButton);
        }

        startDragInteraction(session, hit, pressed);
        PressResult result = new PressResult(clickedEl, hit, pressed, minecraftButton, false);
        if (host != null) host.pressed(result);
        return result;
    }

    public boolean drag(double pixelX, double pixelY) {
        SessionContext.InputSessionState state = state();
        if (state.draggingId.isEmpty()) return false;
        if (session.handleScrollbarDrag(state.draggingId, pixelX, pixelY)) {
            return true;
        }
        SessionContext.UIHitResult dragHit = session.localCoords(state.draggingId, pixelX, pixelY);
        long now = System.currentTimeMillis();

        if (dragHit != null) {
            DisplayItem draggedEl = dragHit.element();
            state.lastSentX = dragHit.localX();
            state.lastSentY = dragHit.localY();

            if (draggedEl.type() == ElementType.RANGE) {
                float ratio = DisplayItemDrawer.rangeRatio(dragHit.localX(), draggedEl.width());
                if (Math.abs(state.lastRatio - ratio) > 0.002f) {
                    state.lastRatio = ratio;
                    state.lastRangeText = generateRangeText(draggedEl.text(), ratio);
                    updateInputLocally(state.draggingId, state.lastRangeText);
                    if (now - state.lastEventTime > DRAG_EVENT_INTERVAL_MS) {
                        dispatchRangeInput(session, state.draggingId, rangeValue(state.lastRangeText));
                        state.lastEventTime = now;
                    }
                }
            } else if (now - state.lastEventTime > DRAG_EVENT_INTERVAL_MS) {
                HtmlNode dragged = session.findInteractiveNode(state.draggingId);
                if (dragged != null) {
                    dragged.dispatchEvent(CoreEvent.mouse("drag", dragHit.localX(), dragHit.localY(), 0, 1, false, false, false, false, null));
                }
                state.lastEventTime = now;
            }
        }
        return true;
    }

    public void release(double pixelX, double pixelY, int minecraftButton) {
        release(pixelX, pixelY, minecraftButton, true, ClickSink.NONE);
    }

    public void release(double pixelX, double pixelY, int minecraftButton, boolean synthesizeClick, ClickSink clickSink) {
        SessionContext.InputSessionState state = state();
        // 押下中の節は InteractionState が持つ。解除する前に読み、クリック合成に使う。
        HtmlNode pressed = session.interactionState().activeNode();
        DisplayItem pressedElement = state.pressedElement;
        session.setActivePressedId("");

        SessionContext.UIHitResult hit = session.hitTest(pixelX, pixelY);
        HtmlNode released = hit != null
                ? session.findInteractiveNode(baseIdOf(hit.element()))
                : null;
        int button = domButton(minecraftButton);
        if (released != null) {
            released.dispatchEvent(CoreEvent.pointer("pointerup", hit.localX(), hit.localY(), button, 0, false, false, false, false, null));
            released.dispatchEvent(CoreEvent.mouse("mouseup", hit.localX(), hit.localY(), button, 0, false, false, false, false, null));
        }

        finishDrag(session, state);

        if (synthesizeClick && pressed != null && released != null) {
            HtmlNode target = nearestCommonAncestor(pressed, released);
            if (target != null) {
                dispatchClick(target, hit.localX(), hit.localY(), minecraftButton, pressedElement, clickSink);
            }
        }
        state.pressedElement = null;
    }

    public void dispatchPress(HtmlNode target, double x, double y, int minecraftButton) {
        if (target == null) return;
        state().pressedButton = minecraftButton;
        int button = domButton(minecraftButton);
        target.dispatchEvent(CoreEvent.pointer("pointerdown", x, y, button, buttonsMask(button), false, false, false, false, null));
        target.dispatchEvent(CoreEvent.mouse("mousedown", x, y, button, buttonsMask(button), false, false, false, false, null));
    }

    /**
     * クリックを 1 か所で合成する。左ボタンは click と dblclick、右・中は contextmenu / auxclick。
     * 呼び出し中だけ {@code clickSink} を文書のインラインアクション受け皿に結び、GUI の
     * {@code onWebAction} にも同じアクション名を届ける。
     */
    public void dispatchClick(HtmlNode target, double x, double y, int minecraftButton,
                              DisplayItem clickedItem, ClickSink clickSink) {
        if (target == null) return;

        ClickSink previousSink = activeClickSink;
        DisplayItem previousItem = activeClickedItem;
        int previousButton = activeClickButton;
        activeClickSink = clickSink != null ? clickSink : ClickSink.NONE;
        activeClickedItem = clickedItem;
        activeClickButton = minecraftButton;
        try {
            int button = domButton(minecraftButton);
            if (button == 0) {
                UIEvents.Mouse click = CoreEvent.click(x, y, 0);
                target.dispatchEvent(click, event -> StandardDefaultActions.execute(
                        event, createClickDefaults(x, y, clickedItem, minecraftButton, activeClickSink)));
                dispatchDoubleClickIfRepeated(target, x, y);
                return;
            }
            if (button == 2) {
                target.dispatchEvent(CoreEvent.mouse("contextmenu", x, y, button, 0, false, false, false, false, null));
            }
            target.dispatchEvent(CoreEvent.mouse("auxclick", x, y, button, 0, false, false, false, false, null));
        } finally {
            activeClickSink = previousSink;
            activeClickedItem = previousItem;
            activeClickButton = previousButton;
        }
    }

    public boolean scroll(double pixelX, double pixelY, double deltaX, double deltaY,
                          boolean ctrl, boolean shift, boolean alt, Host host) {
        SessionContext.UIHitResult hit = session.hitTest(pixelX, pixelY);
        if (hit != null) {
            String baseId = baseIdOf(hit.element());
            HtmlNode domNode = session.findInteractiveNode(baseId);
            if (domNode != null) {
                var wheelEv = CoreEvent.wheel(hit.localX(), hit.localY(), deltaX, deltaY, ctrl, shift, alt, false);
                domNode.dispatchEvent(wheelEv);
                if (wheelEv.isDefaultPrevented()) {
                    return true;
                }
            }
            if (host != null && host.scrollHandled(hit, deltaY)) {
                return true;
            }
        }
        return session.handleScrollLocally(pixelX, pixelY, deltaX, deltaY);
    }

    // ------------------------------------------------------------------
    // キーとテキスト編集
    // ------------------------------------------------------------------

    public UIEvents.Keyboard keyEvent(String type, HtmlNode target, int keyCode,
                                      boolean ctrl, boolean shift, boolean alt, boolean meta) {
        if (target == null) return null;
        UIEvents.Keyboard event = CoreEvent.keyboard(type, glfwKeyToDomKey(keyCode), glfwKeyToDomCode(keyCode),
                0, ctrl, shift, alt, meta, false, false);
        target.dispatchEvent(event);
        return event;
    }

    public boolean beginTextEdit(String elementId, String data, String inputType) {
        String baseId = ClientWebManager.getBaseNodeId(elementId);
        HtmlNode control = session.findNode(baseId);
        if (control == null) return true;
        var beforeInput = CoreEvent.input("beforeinput", data, false, inputType);
        control.dispatchEvent(beforeInput);
        return !beforeInput.isDefaultPrevented();
    }

    public void commitTextEdit(String elementId, String newValue, String data, String inputType) {
        String baseId = ClientWebManager.getBaseNodeId(elementId);
        HtmlNode control = session.findNode(baseId);
        String safeValue = newValue != null ? newValue : "";
        if (control == null) return;
        String prev = state().lastCommittedValues.get(baseId);
        if (prev == null) {
            prev = control.attrs.getOrDefault("value", "");
        }
        if (safeValue.equals(prev)) return;
        state().lastCommittedValues.put(baseId, safeValue);
        control.attrs.put("value", safeValue);
        control.dispatchEvent(CoreEvent.input("input", data, false, inputType));
    }

    public void submitInput(String elementId, String text) {
        if (elementId == null || elementId.isEmpty()) return;
        String baseId = ClientWebManager.getBaseNodeId(elementId);
        String safeText = text != null ? text : "";
        HtmlNode control = session.findNode(baseId);
        String prev = state().lastCommittedValues.get(baseId);
        if (prev == null && control != null) {
            prev = control.attrs.getOrDefault("value", "");
        }
        boolean valueChanged = prev != null ? !safeText.equals(prev) : !safeText.isEmpty();
        updateInputLocally(baseId, safeText);

        if (control != null) {
            state().lastCommittedValues.put(baseId, safeText);
            control.attrs.put("value", safeText);
            if (!isCheckable(control)) {
                if (valueChanged) {
                    control.dispatchEvent(CoreEvent.input("input", safeText, false, "insertText"));
                }
                control.dispatchEvent(new CoreUIEvent("change", true, false, 0));
            }
        }
        SessionContext.InputSessionState state = state();
        state.lastSubmittedId = baseId;
        state.lastSubmittedText = safeText;
        state.lastSubmitTime = System.currentTimeMillis();
    }

    public void setLastCommittedValue(String elementId, String value) {
        String baseId = ClientWebManager.getBaseNodeId(elementId);
        state().lastCommittedValues.put(baseId, value != null ? value : "");
    }

    public void updateInputLocally(String elementId, String text) {
        if (elementId == null || elementId.isEmpty()) return;
        String baseId = ClientWebManager.getBaseNodeId(elementId);
        String safeText = text != null ? text : "";
        state().localInputCache.put(baseId, safeText);
        session.updateElementTextLocally(baseId, safeText);
    }

    public void setDraggingId(String draggingId) {
        state().draggingId = (draggingId != null) ? draggingId : "";
    }

    public void beginDrag(double pixelX, double pixelY) {
        SessionContext.UIHitResult hit = session.hitTest(pixelX, pixelY);
        if (hit == null) return;
        HtmlNode pressed = session.findInteractiveNode(baseIdOf(hit.element()));
        startDragInteraction(session, hit, pressed);
    }

    /** 描画部品の基底ノード ID。テキスト片のような合成 ID でも正しい節へ戻れる。 */
    private static String baseIdOf(DisplayItem element) {
        if (element == null) return "";
        String baseId = element.baseId();
        return baseId != null && !baseId.isEmpty() ? baseId : ClientWebManager.getBaseNodeId(element.id());
    }

    // ------------------------------------------------------------------
    // 文書の外へ出るアクション
    // ------------------------------------------------------------------

    public void runInlineAction(HtmlNode node, Event event, String action) {
        ClickSink sink = activeClickSink;
        if (sink != null && sink != ClickSink.NONE) {
            sink.action(action, activeClickedItem, activeClickButton);
        }
        if (action == null || action.isEmpty()) return;
        triggerLocalEvent(node != null ? node.getId() : "",
                "on" + event.getType().toLowerCase(Locale.ROOT), action, inlinePayload(node, event));
    }

    /**
     * GUI 画面のルータなど、このセッション専用のアクション受け皿を登録する。
     * 画面を閉じるときは {@link #clearLocalEventCallback} で自分のものだけを外す。
     */
    public void setLocalEventCallback(ClientScreenInteractionManager.IClientEventCallback callback) {
        this.localEventCallback = callback;
    }

    public void clearLocalEventCallback(ClientScreenInteractionManager.IClientEventCallback callback) {
        if (this.localEventCallback == callback) this.localEventCallback = null;
    }

    public void forwardDeclared(HtmlNode node, Event event, EventForwardDeclaration declaration) {
        sendUiEvent(node != null ? node.getId() : "", "on" + declaration.eventType(), declaration.action(),
                declaredPayload(node, event, declaration.payload()));
    }

    private void triggerLocalEvent(String elementId, String eventName, String actionName, String value) {
        if (actionName == null || actionName.isEmpty()) return;
        boolean handledLocally = false;
        ClientScreenInteractionManager.IClientEventCallback local = this.localEventCallback;
        if (local != null) {
            handledLocally = local.onEvent(session.sessionId(), elementId, eventName, actionName, value);
        }
        ClientScreenInteractionManager.IClientEventCallback global = ClientScreenInteractionManager.clientEventCallback();
        if (!handledLocally && global != null && global != local) {
            handledLocally = global.onEvent(session.sessionId(), elementId, eventName, actionName, value);
        }
        if (!handledLocally && (actionName.startsWith("server:") || session.isHeadlessState())) {
            sendUiEvent(elementId, eventName, actionName, value);
        }
    }

    private void sendUiEvent(String elementId, String eventName, String actionName, String value) {
        CompoundTag tag = new CompoundTag();
        tag.putString("ElementId", elementId != null ? elementId : "");
        tag.putString("EventName", eventName != null ? eventName : "");
        tag.putString("ActionName", actionName != null ? actionName : "");
        tag.putString("Value", value != null ? value : "");

        if (session.isHeadlessState()) {
            String cleanAction = actionName != null && actionName.startsWith("server:")
                    ? actionName.substring(7)
                    : (actionName != null ? actionName : "");
            ClientScreenInteractionManager.sendToServer(new UiActionC2SPacket(session.sessionId(), cleanAction, tag));
        } else {
            ClientScreenInteractionManager.sendToServer(new WebActionC2SPacket(session.sessionId(), "ui_event", tag));
        }
    }

    private String declaredPayload(HtmlNode declaringNode, Event event, EventForwardDeclaration.Payload payload) {
        HtmlNode target = event.getTarget() instanceof HtmlNode node ? node : declaringNode;
        return switch (payload) {
            case DEFAULT -> inlinePayload(declaringNode, event);
            case VALUE -> controlValue(target);
            case KEY -> event instanceof UIEvents.Keyboard keyboard ? keyboard.getKey() : "";
            case POINT -> pointPayload(event);
            case TARGET -> target.getId();
            case NONE -> "";
        };
    }

    private static String pointPayload(Event event) {
        if (event instanceof UIEvents.Wheel wheel) return formatPoint(wheel.getDeltaX(), wheel.getDeltaY());
        if (event instanceof UIEvents.Mouse mouse) return formatPoint(mouse.getClientX(), mouse.getClientY());
        return "";
    }

    public static String inlinePayload(HtmlNode listener, Event event) {
        HtmlNode target = event.getTarget() instanceof HtmlNode node ? node : listener;
        String type = event.getType().toLowerCase(Locale.ROOT);
        if (event instanceof UIEvents.Wheel wheel) return formatPoint(wheel.getDeltaX(), wheel.getDeltaY());
        if (event instanceof UIEvents.Mouse mouse) {
            return switch (type) {
                case "mouseleave", "pointerleave" -> "";
                case "click", "dblclick", "auxclick", "contextmenu" ->
                        target.getId() + "|" + formatPoint(mouse.getClientX(), mouse.getClientY());
                default -> formatPoint(mouse.getClientX(), mouse.getClientY());
            };
        }
        if (event instanceof UIEvents.Keyboard keyboard) return keyboard.getKey();
        if (type.equals("input") || type.equals("change")) return controlValue(target);
        return "";
    }

    public static String controlValue(HtmlNode control) {
        return isCheckable(control) ? Boolean.toString(control.attrs.containsKey("checked")) : control.attrs.getOrDefault("value", "");
    }

    public static boolean isCheckable(HtmlNode node) {
        if (node == null || !"input".equalsIgnoreCase(node.tag)) return false;
        String type = node.attrs.getOrDefault("type", "");
        return "checkbox".equalsIgnoreCase(type) || "radio".equalsIgnoreCase(type);
    }

    public static String formatPoint(double x, double y) {
        return String.format(Locale.US, "%.1f,%.1f", x, y);
    }

    public static int domButton(int minecraftButton) {
        return switch (minecraftButton) {
            case 1 -> 2;
            case 2 -> 1;
            default -> minecraftButton;
        };
    }

    public static int buttonsMask(int domButton) {
        return switch (domButton) {
            case 0 -> 1;
            case 2 -> 2;
            case 1 -> 4;
            default -> 0;
        };
    }

    public static void dispatchHoverTransition(HtmlNode previous, HtmlNode next, double x, double y) {
        if (previous != null) {
            previous.dispatchEvent(CoreEvent.pointer("pointerout", x, y, 0, 0, false, false, false, false, next));
            previous.dispatchEvent(CoreEvent.mouse("mouseout", x, y, 0, 0, false, false, false, false, next));
            for (HtmlNode left = previous; left != null && !isInclusiveAncestor(left, next); left = left.parent) {
                left.dispatchEvent(CoreEvent.pointer("pointerleave", x, y, 0, 0, false, false, false, false, next));
                left.dispatchEvent(CoreEvent.mouse("mouseleave", x, y, 0, 0, false, false, false, false, next));
            }
        }
        if (next != null) {
            next.dispatchEvent(CoreEvent.pointer("pointerover", x, y, 0, 0, false, false, false, false, previous));
            next.dispatchEvent(CoreEvent.mouse("mouseover", x, y, 0, 0, false, false, false, false, previous));
            List<HtmlNode> entered = new ArrayList<>();
            for (HtmlNode node = next; node != null && !isInclusiveAncestor(node, previous); node = node.parent) {
                entered.addFirst(node);
            }
            for (HtmlNode node : entered) {
                node.dispatchEvent(CoreEvent.pointer("pointerenter", x, y, 0, 0, false, false, false, false, previous));
                node.dispatchEvent(CoreEvent.mouse("mouseenter", x, y, 0, 0, false, false, false, false, previous));
            }
        }
    }

    public static boolean isInclusiveAncestor(HtmlNode ancestor, HtmlNode node) {
        for (HtmlNode current = node; current != null; current = current.parent) {
            if (current == ancestor) return true;
        }
        return false;
    }

    public static HtmlNode nearestCommonAncestor(HtmlNode first, HtmlNode second) {
        for (HtmlNode candidate = first; candidate != null; candidate = candidate.parent) {
            if (isInclusiveAncestor(candidate, second)) return candidate;
        }
        return null;
    }

    private void dispatchDoubleClickIfRepeated(HtmlNode target, double x, double y) {
        SessionContext.InputSessionState state = state();
        long now = System.currentTimeMillis();
        if (state.lastClickNode == target && now - state.lastClickTime <= DOUBLE_CLICK_INTERVAL_MS) {
            state.lastClickNode = null;
            target.dispatchEvent(CoreEvent.doubleClick(x, y, false, false, false, false));
        } else {
            state.lastClickNode = target;
            state.lastClickTime = now;
        }
    }

    private StandardDefaultActions.Context createClickDefaults(double x, double y, DisplayItem clickedItem,
                                                                int button, ClickSink clickSink) {
        return new StandardDefaultActions.Context() {
            @Override
            public void onAttributeChanged(HtmlNode node, String attrName, String attrValue) {
                session.updateNodeAttributeLocally(node.getId(), attrName, attrValue, attrValue == null);
                if ("checked".equalsIgnoreCase(attrName)) {
                    String val = Boolean.toString(attrValue != null);
                    submitInput(node.getId(), val);
                    if (clickSink != null) clickSink.inputChanged(node.getId(), val);
                }
            }

            @Override
            public void dispatchClickTo(HtmlNode target) {
                if (target != null && clickSink != null) {
                    clickSink.clickDelegate(SessionInput.this, target, x, y, button);
                }
            }

            @Override
            public void navigateTo(String href) {
                if (clickSink != null) clickSink.action(href, clickedItem, button);
            }

            @Override
            public void executeFormAction(HtmlNode form, String actionType) {
                String act = form.attrs.get(actionType);
                if (act != null && clickSink != null) clickSink.action(act, clickedItem, button);
            }
        };
    }

    // ------------------------------------------------------------------
    // レンジ・ドラッグ
    // ------------------------------------------------------------------

    private void startDragInteraction(SessionContext session, SessionContext.UIHitResult hit, HtmlNode pressed) {
        DisplayItem element = hit.element();
        Map<String, String> events = element.events() != null ? element.events() : Map.of();
        if (!hasEvent(events, "ondrag") && element.type() != ElementType.RANGE) return;

        SessionContext.InputSessionState state = state();
        state.draggingId = ClientWebManager.getBaseNodeId(element.id());
        state.lastSentX = hit.localX();
        state.lastSentY = hit.localY();

        if (element.type() == ElementType.RANGE) {
            state.lastRatio = DisplayItemDrawer.rangeRatio(hit.localX(), element.width());
            state.lastRangeText = generateRangeText(element.text(), state.lastRatio);
            updateInputLocally(state.draggingId, state.lastRangeText);
            dispatchRangeInput(session, state.draggingId, rangeValue(state.lastRangeText));
        } else if (pressed != null) {
            pressed.dispatchEvent(CoreEvent.mouse("dragstart", hit.localX(), hit.localY(), 0, 1, false, false, false, false, null));
        }
    }

    private static boolean hasEvent(Map<String, String> events, String eventName) {
        if (events == null || events.isEmpty()) return false;
        for (String key : events.keySet()) {
            if (key.equalsIgnoreCase(eventName)) return true;
        }
        return false;
    }

    private static void dispatchRangeInput(SessionContext session, String baseId, String value) {
        HtmlNode slider = session.findNode(baseId);
        if (slider == null) return;
        slider.attrs.put("value", value);
        slider.dispatchEvent(new CoreUIEvent("input", true, false, 0));
    }

    private static String rangeValue(String rangeText) {
        return rangeText.split(":")[0];
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

    private void finishDrag(SessionContext session, SessionContext.InputSessionState state) {
        if (state.draggingId.isEmpty()) return;

        HtmlNode dragged = session.findInteractiveNode(state.draggingId);
        if (state.lastRatio >= 0 && !state.lastRangeText.isEmpty()) {
            String value = rangeValue(state.lastRangeText);
            if (dragged != null) {
                dispatchRangeInput(session, state.draggingId, value);
                dragged.dispatchEvent(new CoreUIEvent("change", true, false, 0));
            }
            state.lastSubmittedId = state.draggingId;
            state.lastSubmittedText = state.lastRangeText;
            state.lastSubmitTime = System.currentTimeMillis();
        }
        if (dragged != null) {
            dragged.dispatchEvent(CoreEvent.mouse("dragend", state.lastSentX, state.lastSentY, 0, 0, false, false, false, false, null));
        }
        state.draggingId = "";
        state.lastRatio = -1.0f;
        state.lastRangeText = "";
    }

    // ------------------------------------------------------------------
    // ローカル上書き・サーバー値の保持
    // ------------------------------------------------------------------

    public void applyLocalOverrides() {
        SessionContext.InputSessionState state = state();
        for (Map.Entry<String, String> entry : state.localInputCache.entrySet()) {
            session.updateElementTextLocally(entry.getKey(), entry.getValue());
        }
        if (!state.draggingId.isEmpty() && !state.lastRangeText.isEmpty()) {
            session.updateElementTextLocally(state.draggingId, state.lastRangeText);
        }
        if (System.currentTimeMillis() - state.lastSubmitTime < 1500 && !state.lastSubmittedId.isEmpty()) {
            session.updateElementTextLocally(state.lastSubmittedId, state.lastSubmittedText);
        }
        String hoveredId = hoveredId();
        if (ClientScreenInteractionManager.isMutateDomOnHover() && !hoveredId.isEmpty()) {
            session.updateNodeAttributeLocally(hoveredId, "hover", "true", false);
        }
        String activeId = session.activeInputId();
        if (activeId != null && !activeId.isEmpty() && !activeId.endsWith("_textarea")) {
            String activeVal = WebTextInputHost.getActiveText(session.sessionId());
            if (activeVal != null) {
                updateInputLocally(activeId, activeVal);
            }
        }
    }

    public void syncServerValueOverride(String elementId, String newValue) {
        if (elementId == null) return;
        String baseId = ClientWebManager.getBaseNodeId(elementId);
        SessionContext.InputSessionState state = state();
        if (state.localInputCache.containsKey(baseId)) {
            state.localInputCache.put(baseId, newValue != null ? newValue : "");
        }
    }

    public String hoveredId() {
        HtmlNode hovered = session.interactionState().hoveredNode();
        return hovered != null ? hovered.getId() : "";
    }

    // ------------------------------------------------------------------
    // キー名
    // ------------------------------------------------------------------

    public static String glfwKeyToDomKey(int keyCode) {
        return switch (keyCode) {
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> "Enter";
            case GLFW.GLFW_KEY_ESCAPE -> "Escape";
            case GLFW.GLFW_KEY_BACKSPACE -> "Backspace";
            case GLFW.GLFW_KEY_TAB -> "Tab";
            case GLFW.GLFW_KEY_SPACE -> " ";
            case GLFW.GLFW_KEY_UP -> "ArrowUp";
            case GLFW.GLFW_KEY_DOWN -> "ArrowDown";
            case GLFW.GLFW_KEY_LEFT -> "ArrowLeft";
            case GLFW.GLFW_KEY_RIGHT -> "ArrowRight";
            case GLFW.GLFW_KEY_PAGE_UP -> "PageUp";
            case GLFW.GLFW_KEY_PAGE_DOWN -> "PageDown";
            case GLFW.GLFW_KEY_HOME -> "Home";
            case GLFW.GLFW_KEY_END -> "End";
            case GLFW.GLFW_KEY_DELETE -> "Delete";
            default -> {
                String name = GLFW.glfwGetKeyName(keyCode, 0);
                yield (name != null && !name.isEmpty()) ? name : "Unidentified";
            }
        };
    }

    public static String glfwKeyToDomCode(int keyCode) {
        return switch (keyCode) {
            case GLFW.GLFW_KEY_ENTER -> "Enter";
            case GLFW.GLFW_KEY_KP_ENTER -> "NumpadEnter";
            case GLFW.GLFW_KEY_ESCAPE -> "Escape";
            case GLFW.GLFW_KEY_BACKSPACE -> "Backspace";
            case GLFW.GLFW_KEY_TAB -> "Tab";
            case GLFW.GLFW_KEY_SPACE -> "Space";
            case GLFW.GLFW_KEY_UP -> "ArrowUp";
            case GLFW.GLFW_KEY_DOWN -> "ArrowDown";
            case GLFW.GLFW_KEY_LEFT -> "ArrowLeft";
            case GLFW.GLFW_KEY_RIGHT -> "ArrowRight";
            case GLFW.GLFW_KEY_PAGE_UP -> "PageUp";
            case GLFW.GLFW_KEY_PAGE_DOWN -> "PageDown";
            case GLFW.GLFW_KEY_HOME -> "Home";
            case GLFW.GLFW_KEY_END -> "End";
            case GLFW.GLFW_KEY_DELETE -> "Delete";
            default -> "Key" + keyCode;
        };
    }

    public static String editingInputType(int keyCode, int modifiers, boolean multiLine) {
        boolean ctrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) return ctrl ? "deleteWordBackward" : "deleteContentBackward";
        if (keyCode == GLFW.GLFW_KEY_DELETE) return ctrl ? "deleteWordForward" : "deleteContentForward";
        if (ctrl && keyCode == GLFW.GLFW_KEY_V) return "insertFromPaste";
        if (ctrl && keyCode == GLFW.GLFW_KEY_X) return "deleteByCut";
        if (multiLine && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) return "insertLineBreak";
        return null;
    }
}
