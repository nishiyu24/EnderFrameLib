package com.nishiyu.enderframelib.core.event;

import com.nishiyu.enderframelib.api.event.UIEvents;
import com.nishiyu.enderframelib.api.dom.EventTarget;
import org.jetbrains.annotations.Nullable;

/**
 * UI Events / Pointer Events の標準生成ファクトリ。
 */
public final class UiEvents {

    private UiEvents() {
    }

    public static UIEvents.Pointer pointer(String type, double clientX, double clientY, int button, int buttons,
                                       boolean ctrlKey, boolean shiftKey, boolean altKey, boolean metaKey,
                                       @Nullable EventTarget relatedTarget) {
        boolean bubbles = !"pointerenter".equals(type) && !"pointerleave".equals(type);
        boolean cancelable = !"pointerenter".equals(type) && !"pointerleave".equals(type) && !"pointercancel".equals(type);
        CoreEvent.CorePointerEvent ev = new CoreEvent.CorePointerEvent(
                type, bubbles, cancelable, 1,
                clientX, clientY, clientX, clientY,
                ctrlKey, shiftKey, altKey, metaKey,
                (short) button, (short) buttons, relatedTarget,
                1, 1.0, 1.0, 0.5f, "mouse", true
        );
        ev.markTrusted(bubbles);
        return ev;
    }

    public static UIEvents.Mouse mouse(String type, double clientX, double clientY, int button, int buttons,
                                   boolean ctrlKey, boolean shiftKey, boolean altKey, boolean metaKey,
                                   @Nullable EventTarget relatedTarget) {
        boolean bubbles = !"mouseenter".equals(type) && !"mouseleave".equals(type);
        boolean cancelable = !"mouseenter".equals(type) && !"mouseleave".equals(type);
        CoreEvent.CoreMouseEvent ev = new CoreEvent.CoreMouseEvent(
                type, bubbles, cancelable, 1,
                clientX, clientY, clientX, clientY,
                ctrlKey, shiftKey, altKey, metaKey,
                (short) button, (short) buttons, relatedTarget
        );
        ev.markTrusted(bubbles);
        return ev;
    }

    /** 2 回目のクリックの後に送る {@code dblclick}（{@code detail} は連続回数 2）。 */
    public static UIEvents.Mouse doubleClick(double clientX, double clientY,
                                         boolean ctrlKey, boolean shiftKey, boolean altKey, boolean metaKey) {
        CoreEvent.CoreMouseEvent ev = new CoreEvent.CoreMouseEvent(
                "dblclick", true, true, 2,
                clientX, clientY, clientX, clientY,
                ctrlKey, shiftKey, altKey, metaKey,
                (short) 0, (short) 0, null
        );
        ev.markTrusted(true);
        return ev;
    }

    public static UIEvents.Wheel wheel(double clientX, double clientY, double deltaX, double deltaY,
                                   boolean ctrlKey, boolean shiftKey, boolean altKey, boolean metaKey) {
        CoreEvent.CoreWheelEvent ev = new CoreEvent.CoreWheelEvent(
                "wheel", true, true, 0,
                clientX, clientY, clientX, clientY,
                ctrlKey, shiftKey, altKey, metaKey,
                (short) 0, (short) 0, null,
                deltaX, deltaY, 0.0, UIEvents.Wheel.DOM_DELTA_PIXEL
        );
        ev.markTrusted(true);
        return ev;
    }

    public static UIEvents.Keyboard keyboard(String type, String key, String code, int location,
                                         boolean ctrlKey, boolean shiftKey, boolean altKey, boolean metaKey,
                                         boolean repeat, boolean composing) {
        CoreEvent.CoreKeyboardEvent ev = new CoreEvent.CoreKeyboardEvent(
                type, true, true,
                key, code, location,
                ctrlKey, shiftKey, altKey, metaKey,
                repeat, composing
        );
        ev.markTrusted(true);
        return ev;
    }

    public static UIEvents.Focus focus(String type, @Nullable EventTarget relatedTarget) {
        boolean bubbles = "focusin".equals(type) || "focusout".equals(type);
        CoreEvent.CoreFocusEvent ev = new CoreEvent.CoreFocusEvent(type, bubbles, false, relatedTarget);
        ev.markTrusted(true);
        return ev;
    }

    public static UIEvents.Input input(String type, @Nullable String data, boolean composing, String inputType) {
        boolean cancelable = "beforeinput".equals(type);
        CoreEvent.CoreInputEvent ev = new CoreEvent.CoreInputEvent(
                type, true, cancelable, data, composing, inputType
        );
        ev.markTrusted(true);
        return ev;
    }
}
