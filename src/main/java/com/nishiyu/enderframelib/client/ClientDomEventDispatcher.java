package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.api.event.Event;
import com.nishiyu.enderframelib.api.event.UIEvents;
import com.nishiyu.enderframelib.core.dom.EventForwardDeclaration;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.event.CoreEvent.DomEvent;
import com.nishiyu.enderframelib.core.event.EventDispatcher;
import com.nishiyu.enderframelib.core.event.StandardDefaultActions;
import com.nishiyu.enderframelib.core.event.UiEvents;
import com.nishiyu.enderframelib.core.layout.DisplayItem;
import com.nishiyu.enderframelib.network.EnderFrameNetwork.WebActionC2SPacket;
import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class ClientDomEventDispatcher {

    public interface TriActionCallback {
        void onAction(String action, DisplayItem clickedElement, int mouseButton);
    }

    private static final long DOUBLE_CLICK_INTERVAL_MS = 500;

    public static void triggerLocalEvent(String sessionId, String elementId, String eventName, String actionName, String value, ClientScreenInteractionManager.IClientEventCallback callback) {
        if (sessionId == null || sessionId.isEmpty() || actionName == null || actionName.isEmpty()) return;
        boolean handledLocally = callback != null && callback.onEvent(sessionId, elementId, eventName, actionName, value);
        if (!handledLocally && actionName.startsWith("server:")) {
            sendUiEvent(sessionId, elementId, eventName, actionName, value);
        }
    }

    private static void sendUiEvent(String sessionId, String elementId, String eventName, String actionName, String value) {
        CompoundTag tag = new CompoundTag();
        tag.putString("ElementId", elementId != null ? elementId : "");
        tag.putString("EventName", eventName != null ? eventName : "");
        tag.putString("ActionName", actionName != null ? actionName : "");
        tag.putString("Value", value != null ? value : "");
        ClientScreenInteractionManager.sendToServer(new WebActionC2SPacket(sessionId, "ui_event", tag));
    }

    public static void forwardDeclared(String sessionId, HtmlNode node, Event event, EventForwardDeclaration declaration) {
        if (sessionId == null || sessionId.isEmpty()) return;
        sendUiEvent(sessionId, node.getId(), "on" + declaration.eventType(), declaration.action(),
                declaredPayload(node, event, declaration.payload()));
    }

    public static void runInlineAction(String sessionId, HtmlNode node, Event event, String action, ClientScreenInteractionManager.IClientEventCallback callback) {
        triggerLocalEvent(sessionId, node.getId(), "on" + event.getType().toLowerCase(Locale.ROOT),
                action, inlinePayload(node, event), callback);
    }

    private static String declaredPayload(HtmlNode declaringNode, Event event, EventForwardDeclaration.Payload payload) {
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

    public static void dispatchPress(HtmlNode target, double x, double y, int minecraftButton) {
        int button = domButton(minecraftButton);
        target.dispatchEvent(UiEvents.pointer("pointerdown", x, y, button, buttonsMask(button), false, false, false, false, null));
        target.dispatchEvent(UiEvents.mouse("mousedown", x, y, button, buttonsMask(button), false, false, false, false, null));
    }

    public static void dispatchDoubleClickIfRepeated(String sessionId, HtmlNode target, double x, double y) {
        SessionContext.InputSessionState state = ClientWebManager.getOrCreateSession(sessionId).inputState();
        long now = System.currentTimeMillis();
        if (state.lastClickNode == target && now - state.lastClickTime <= DOUBLE_CLICK_INTERVAL_MS) {
            state.lastClickNode = null;
            target.dispatchEvent(UiEvents.doubleClick(x, y, false, false, false, false));
        } else {
            state.lastClickNode = target;
            state.lastClickTime = now;
        }
    }

    public static void dispatchHoverTransition(HtmlNode previous, HtmlNode next, double x, double y) {
        if (previous != null) {
            previous.dispatchEvent(UiEvents.pointer("pointerout", x, y, 0, 0, false, false, false, false, next));
            previous.dispatchEvent(UiEvents.mouse("mouseout", x, y, 0, 0, false, false, false, false, next));
            for (HtmlNode left = previous; left != null && !isInclusiveAncestor(left, next); left = left.parent) {
                left.dispatchEvent(UiEvents.pointer("pointerleave", x, y, 0, 0, false, false, false, false, next));
                left.dispatchEvent(UiEvents.mouse("mouseleave", x, y, 0, 0, false, false, false, false, next));
            }
        }
        if (next != null) {
            next.dispatchEvent(UiEvents.pointer("pointerover", x, y, 0, 0, false, false, false, false, previous));
            next.dispatchEvent(UiEvents.mouse("mouseover", x, y, 0, 0, false, false, false, false, previous));
            List<HtmlNode> entered = new ArrayList<>();
            for (HtmlNode node = next; node != null && !isInclusiveAncestor(node, previous); node = node.parent) {
                entered.addFirst(node);
            }
            for (HtmlNode node : entered) {
                node.dispatchEvent(UiEvents.pointer("pointerenter", x, y, 0, 0, false, false, false, false, previous));
                node.dispatchEvent(UiEvents.mouse("mouseenter", x, y, 0, 0, false, false, false, false, previous));
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

    public static StandardDefaultActions.Context createClickDefaults(
            String sessionId, double x, double y, DisplayItem clickedItem, int button,
            BiConsumer<String, String> onInputChanged, TriActionCallback onWebAction,
            Consumer<HtmlNode> clickDelegate) {
        return new StandardDefaultActions.Context() {
            @Override
            public void onAttributeChanged(HtmlNode node, String attrName, String attrValue) {
                ClientWebManager.updateNodeAttributeLocally(sessionId, node.getId(), attrName, attrValue, attrValue == null);
                if ("checked".equalsIgnoreCase(attrName)) {
                    String val = Boolean.toString(attrValue != null);
                    ClientScreenInteractionManager.submitInput(sessionId, node.getId(), val);
                    if (onInputChanged != null) onInputChanged.accept(node.getId(), val);
                }
            }

            @Override
            public void dispatchClickTo(HtmlNode target) {
                if (target != null && clickDelegate != null) {
                    clickDelegate.accept(target);
                }
            }

            @Override
            public void navigateTo(String href) {
                if (onWebAction != null) onWebAction.onAction(href, clickedItem, button);
            }

            @Override
            public void executeFormAction(HtmlNode form, String actionType) {
                String act = form.attrs.get(actionType);
                if (act != null && onWebAction != null) onWebAction.onAction(act, clickedItem, button);
            }
        };
    }

    public static boolean dispatchWithActions(String sessionId, HtmlNode target, Event event, DisplayItem clicked, int button,
                                              TriActionCallback onWebAction, ClientScreenInteractionManager.IClientEventCallback callback,
                                              EventDispatcher.DefaultActionHandler defaultActions) {
        boolean[] invoked = new boolean[]{false};
        EventDispatcher.dispatchEvent(target, event, (listener, action) -> {
            invoked[0] = true;
            if (onWebAction != null && clicked != null) {
                onWebAction.onAction(action, clicked, button);
            }
            runInlineAction(sessionId, listener, event, action, callback);
        }, defaultActions);
        return invoked[0];
    }
}