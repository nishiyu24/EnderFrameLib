package com.nishiyu.enderframelib.core.event;

import com.nishiyu.enderframelib.api.event.UIEvents;
import com.nishiyu.enderframelib.api.dom.Element;
import com.nishiyu.enderframelib.api.dom.EventTarget;
import com.nishiyu.enderframelib.api.event.Event;
import com.nishiyu.enderframelib.api.dom.FormData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.nishiyu.enderframelib.api.dom.Node;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;

/**
 * WHATWG DOM Standard §2.2 Interface Event の具象実装。
 */
public class CoreEvent implements Event {

    private final String type;
    private final boolean bubbles;
    private final boolean cancelable;
    private boolean composed;
    private final double timeStamp;

    private EventTarget target;
    private EventTarget currentTarget;
    private short eventPhase = NONE;
    private boolean defaultPrevented = false;
    private boolean isTrusted = false;

    private boolean stopPropagation = false;
    private boolean stopImmediatePropagation = false;
    private boolean inPassiveListener = false;
    private List<EventTarget> composedPath = Collections.emptyList();

    public CoreEvent(String type, boolean bubbles, boolean cancelable, boolean composed) {
        this.type = type != null ? type : "";
        this.bubbles = bubbles;
        this.cancelable = cancelable;
        this.composed = composed;
        this.timeStamp = System.currentTimeMillis();
    }

    public CoreEvent(String type, boolean bubbles, boolean cancelable) {
        this(type, bubbles, cancelable, false);
    }

    public CoreEvent(String type) {
        this(type, false, false, false);
    }

    @Override
    public String getType() {
        return type;
    }

    @Nullable
    @Override
    public EventTarget getTarget() {
        return target;
    }

    public void setTarget(EventTarget target) {
        this.target = target;
    }

    @Nullable
    @Override
    public EventTarget getCurrentTarget() {
        return currentTarget;
    }

    public void setCurrentTarget(EventTarget currentTarget) {
        this.currentTarget = currentTarget;
    }

    @Override
    public List<EventTarget> composedPath() {
        return Collections.unmodifiableList(composedPath);
    }

    public void setComposedPath(List<EventTarget> path) {
        this.composedPath = path != null ? new ArrayList<>(path) : Collections.emptyList();
    }

    @Override
    public short getEventPhase() {
        return eventPhase;
    }

    public void setEventPhase(short eventPhase) {
        this.eventPhase = eventPhase;
    }

    @Override
    public void stopPropagation() {
        this.stopPropagation = true;
    }

    public boolean isPropagationStopped() {
        return stopPropagation;
    }

    @Override
    public void stopImmediatePropagation() {
        this.stopPropagation = true;
        this.stopImmediatePropagation = true;
    }

    public boolean isImmediatePropagationStopped() {
        return stopImmediatePropagation;
    }

    @Override
    public boolean isBubbles() {
        return bubbles;
    }

    @Override
    public boolean isCancelable() {
        return cancelable;
    }

    @Override
    public void preventDefault() {
        if (cancelable && !inPassiveListener) {
            this.defaultPrevented = true;
        }
    }

    @Override
    public boolean isDefaultPrevented() {
        return defaultPrevented;
    }

    @Override
    public boolean isComposed() {
        return composed;
    }

    @Override
    public boolean isTrusted() {
        return isTrusted;
    }

    /** ユーザーエージェントが生成したイベントとして印を付ける。UI Events では mouseenter / mouseleave 以外は composed。 */
    public void markTrusted(boolean composed) {
        this.isTrusted = true;
        this.composed = composed;
    }

    public void setTrusted(boolean trusted) {
        this.isTrusted = trusted;
    }

    @Override
    public double getTimeStamp() {
        return timeStamp;
    }

    public boolean isInPassiveListener() {
        return inPassiveListener;
    }

    public void setInPassiveListener(boolean inPassiveListener) {
        this.inPassiveListener = inPassiveListener;
    }

    // ==========================================
    // UI Events 実装クラス群
    // ==========================================

    public static class CoreMouseEvent extends CoreUIEvent implements UIEvents.Mouse {
        private final double screenX;
        private final double screenY;
        private final double clientX;
        private final double clientY;
        private final boolean ctrlKey;
        private final boolean shiftKey;
        private final boolean altKey;
        private final boolean metaKey;
        private final short button;
        private final short buttons;
        private final EventTarget relatedTarget;

        public CoreMouseEvent(String type, boolean bubbles, boolean cancelable, int detail,
                              double screenX, double screenY, double clientX, double clientY,
                              boolean ctrlKey, boolean shiftKey, boolean altKey, boolean metaKey,
                              short button, short buttons, @Nullable EventTarget relatedTarget) {
            super(type, bubbles, cancelable, detail);
            this.screenX = screenX;
            this.screenY = screenY;
            this.clientX = clientX;
            this.clientY = clientY;
            this.ctrlKey = ctrlKey;
            this.shiftKey = shiftKey;
            this.altKey = altKey;
            this.metaKey = metaKey;
            this.button = button;
            this.buttons = buttons;
            this.relatedTarget = relatedTarget;
        }

        @Override public double getScreenX() { return screenX; }
        @Override public double getScreenY() { return screenY; }
        @Override public double getClientX() { return clientX; }
        @Override public double getClientY() { return clientY; }
        @Override public boolean isCtrlKey() { return ctrlKey; }
        @Override public boolean isShiftKey() { return shiftKey; }
        @Override public boolean isAltKey() { return altKey; }
        @Override public boolean isMetaKey() { return metaKey; }
        @Override public short getButton() { return button; }
        @Override public short getButtons() { return buttons; }
        @Nullable @Override public EventTarget getRelatedTarget() { return relatedTarget; }
    }

    public static class CorePointerEvent extends CoreMouseEvent implements UIEvents.Pointer {
        private final int pointerId;
        private final double width;
        private final double height;
        private final float pressure;
        private final String pointerType;
        private final boolean primary;

        public CorePointerEvent(String type, boolean bubbles, boolean cancelable, int detail,
                                double screenX, double screenY, double clientX, double clientY,
                                boolean ctrlKey, boolean shiftKey, boolean altKey, boolean metaKey,
                                short button, short buttons, @Nullable EventTarget relatedTarget,
                                int pointerId, double width, double height, float pressure,
                                String pointerType, boolean primary) {
            super(type, bubbles, cancelable, detail, screenX, screenY, clientX, clientY,
                    ctrlKey, shiftKey, altKey, metaKey, button, buttons, relatedTarget);
            this.pointerId = pointerId;
            this.width = width;
            this.height = height;
            this.pressure = pressure;
            this.pointerType = pointerType != null ? pointerType : "mouse";
            this.primary = primary;
        }

        @Override public int getPointerId() { return pointerId; }
        @Override public double getWidth() { return width; }
        @Override public double getHeight() { return height; }
        @Override public float getPressure() { return pressure; }
        @Override public String getPointerType() { return pointerType; }
        @Override public boolean isPrimary() { return primary; }
    }

    public static class CoreWheelEvent extends CoreMouseEvent implements UIEvents.Wheel {
        private final double deltaX;
        private final double deltaY;
        private final double deltaZ;
        private final int deltaMode;

        public CoreWheelEvent(String type, boolean bubbles, boolean cancelable, int detail,
                              double screenX, double screenY, double clientX, double clientY,
                              boolean ctrlKey, boolean shiftKey, boolean altKey, boolean metaKey,
                              short button, short buttons, @Nullable EventTarget relatedTarget,
                              double deltaX, double deltaY, double deltaZ, int deltaMode) {
            super(type, bubbles, cancelable, detail, screenX, screenY, clientX, clientY,
                    ctrlKey, shiftKey, altKey, metaKey, button, buttons, relatedTarget);
            this.deltaX = deltaX;
            this.deltaY = deltaY;
            this.deltaZ = deltaZ;
            this.deltaMode = deltaMode;
        }

        @Override public double getDeltaX() { return deltaX; }
        @Override public double getDeltaY() { return deltaY; }
        @Override public double getDeltaZ() { return deltaZ; }
        @Override public int getDeltaMode() { return deltaMode; }
    }

    public static class CoreKeyboardEvent extends CoreUIEvent implements UIEvents.Keyboard {
        private final String key;
        private final String code;
        private final int location;
        private final boolean ctrlKey;
        private final boolean shiftKey;
        private final boolean altKey;
        private final boolean metaKey;
        private final boolean repeat;
        private final boolean composing;

        public CoreKeyboardEvent(String type, boolean bubbles, boolean cancelable,
                                 String key, String code, int location,
                                 boolean ctrlKey, boolean shiftKey, boolean altKey, boolean metaKey,
                                 boolean repeat, boolean composing) {
            super(type, bubbles, cancelable, 0);
            this.key = key != null ? key : "";
            this.code = code != null ? code : "";
            this.location = location;
            this.ctrlKey = ctrlKey;
            this.shiftKey = shiftKey;
            this.altKey = altKey;
            this.metaKey = metaKey;
            this.repeat = repeat;
            this.composing = composing;
        }

        @Override public String getKey() { return key; }
        @Override public String getCode() { return code; }
        @Override public int getLocation() { return location; }
        @Override public boolean isCtrlKey() { return ctrlKey; }
        @Override public boolean isShiftKey() { return shiftKey; }
        @Override public boolean isAltKey() { return altKey; }
        @Override public boolean isMetaKey() { return metaKey; }
        @Override public boolean isRepeat() { return repeat; }
        @Override public boolean isComposing() { return composing; }
    }

    public static class CoreFocusEvent extends CoreUIEvent implements UIEvents.Focus {
        private final EventTarget relatedTarget;

        public CoreFocusEvent(String type, boolean bubbles, boolean cancelable, @Nullable EventTarget relatedTarget) {
            super(type, bubbles, cancelable, 0);
            this.relatedTarget = relatedTarget;
        }

        @Nullable
        @Override
        public EventTarget getRelatedTarget() {
            return relatedTarget;
        }
    }

    public static class CoreInputEvent extends CoreUIEvent implements UIEvents.Input {
        private final String data;
        private final boolean composing;
        private final String inputType;

        public CoreInputEvent(String type, boolean bubbles, boolean cancelable,
                              @Nullable String data, boolean composing, String inputType) {
            super(type, bubbles, cancelable, 0);
            this.data = data;
            this.composing = composing;
            this.inputType = inputType != null ? inputType : "";
        }

        @Nullable @Override public String getData() { return data; }
        @Override public boolean isComposing() { return composing; }
        @Override public String getInputType() { return inputType; }
    }

    public static class CoreCompositionEvent extends CoreUIEvent implements UIEvents.Composition {
        private final String data;

        public CoreCompositionEvent(String type, boolean bubbles, boolean cancelable, String data) {
            super(type, bubbles, cancelable, 0);
            this.data = data != null ? data : "";
        }

        @Override
        public String getData() {
            return data;
        }
    }

    public static class CoreCustomEvent extends CoreEvent implements UIEvents.Custom {
        private final Object detail;

        public CoreCustomEvent(String type, boolean bubbles, boolean cancelable, @Nullable Object detail) {
            super(type, bubbles, cancelable);
            this.detail = detail;
        }

        @Nullable
        @Override
        public Object getDetail() {
            return detail;
        }
    }

    public static class CoreFormDataEvent extends CoreEvent implements UIEvents.FormDataCollect {
        private final FormData formData;

        public CoreFormDataEvent(FormData formData) {
            super("formdata", true, false);
            this.formData = formData != null ? formData : new FormData();
        }

        @Override
        public FormData getFormData() {
            return formData;
        }
    }

    public static class CoreSubmitEvent extends CoreEvent implements UIEvents.Submit {
        private final Element submitter;

        public CoreSubmitEvent(@Nullable Element submitter) {
            super("submit", true, true);
            this.submitter = submitter;
        }

        @Nullable
        @Override
        public Element getSubmitter() {
            return submitter;
        }
    }

    // ==========================================
    // イベント生成ファクトリ（旧 UiEvents / DomEvent の統合）
    // ==========================================

    public static UIEvents.Pointer pointer(String type, double clientX, double clientY, int button, int buttons,
                                           boolean ctrlKey, boolean shiftKey, boolean altKey, boolean metaKey,
                                           @Nullable EventTarget relatedTarget) {
        boolean bubbles = !"pointerenter".equals(type) && !"pointerleave".equals(type);
        boolean cancelable = !"pointerenter".equals(type) && !"pointerleave".equals(type) && !"pointercancel".equals(type);
        CorePointerEvent ev = new CorePointerEvent(
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
        CoreMouseEvent ev = new CoreMouseEvent(
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
        CoreMouseEvent ev = new CoreMouseEvent(
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
        CoreWheelEvent ev = new CoreWheelEvent(
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
        CoreKeyboardEvent ev = new CoreKeyboardEvent(
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
        CoreFocusEvent ev = new CoreFocusEvent(type, bubbles, false, relatedTarget);
        ev.markTrusted(true);
        return ev;
    }

    public static UIEvents.Input input(String type, @Nullable String data, boolean composing, String inputType) {
        boolean cancelable = "beforeinput".equals(type);
        CoreInputEvent ev = new CoreInputEvent(
                type, true, cancelable, data, composing, inputType
        );
        ev.markTrusted(true);
        return ev;
    }

    public static UIEvents.Mouse click(int button) {
        return click(0, 0, button);
    }

    public static UIEvents.Mouse click(double clientX, double clientY, int button) {
        CoreMouseEvent ev = new CoreMouseEvent(
                "click", true, true, 1,
                clientX, clientY, clientX, clientY,
                false, false, false, false,
                (short) button, (short) 0, null
        );
        ev.markTrusted(true);
        return ev;
    }

    public static UIEvents.Input input(String value) {
        return input("input", value, false, "insertText");
    }

    public static CoreEvent change() {
        CoreEvent ev = new CoreEvent("change", true, false);
        ev.markTrusted(true);
        return ev;
    }

    public static CoreSubmitEvent submit() {
        return submit(null);
    }

    public static CoreSubmitEvent submit(@Nullable Element submitter) {
        CoreSubmitEvent ev = new CoreSubmitEvent(submitter);
        ev.markTrusted(true);
        return ev;
    }

    public static CoreEvent reset() {
        CoreEvent ev = new CoreEvent("reset", true, true);
        ev.markTrusted(true);
        return ev;
    }
}
