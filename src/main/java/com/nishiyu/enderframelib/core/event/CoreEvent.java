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
import com.nishiyu.enderframelib.core.dom.HtmlNode;

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

    public static class CoreUIEvent extends CoreEvent implements UIEvents.UI {
        private final int detail;

        public CoreUIEvent(String type, boolean bubbles, boolean cancelable, int detail) {
            super(type, bubbles, cancelable);
            this.detail = detail;
        }

        @Override
        public int getDetail() {
            return detail;
        }
    }

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

    /**
     * DOM イベントオブジェクト（W3C DOM Event 仕様準拠）。
     *
     * <p>下位互換性のため既存のレコード風アクセサ（type(), bubbles() 等）を維持しつつ、
     * WHATWG DOM Standard {@link com.nishiyu.enderframelib.api.event.Event} および
     * {@link MouseEvent} を実装する。</p>
     */
    public static class DomEvent extends CoreEvent implements UIEvents.Mouse {

        private double screenX;
        private double screenY;
        private double clientX;
        private double clientY;
        private boolean ctrlKey;
        private boolean shiftKey;
        private boolean altKey;
        private boolean metaKey;
        private short button;
        private short buttons;
        private EventTarget relatedTarget;
        private int detailInt;
        private String detail = "";

        public DomEvent(String type, boolean bubbles, boolean cancelable) {
            super(type, bubbles, cancelable);
        }

        public DomEvent(String type, boolean bubbles, boolean cancelable, boolean composed) {
            super(type, bubbles, cancelable, composed);
        }

        public static DomEvent click(int button) {
            return click(0, 0, button);
        }

        public static DomEvent click(double clientX, double clientY, int button) {
            DomEvent ev = new DomEvent("click", true, true);
            ev.clientX = clientX;
            ev.clientY = clientY;
            ev.screenX = clientX;
            ev.screenY = clientY;
            ev.button = (short) button;
            ev.detailInt = 1;
            return ev;
        }

        public static DomEvent input(String value) {
            DomEvent ev = new DomEvent("input", true, false);
            ev.detail = value != null ? value : "";
            return ev;
        }

        public static DomEvent change(String value) {
            DomEvent ev = new DomEvent("change", true, false);
            ev.detail = value != null ? value : "";
            return ev;
        }

        public static DomEvent submit() {
            return new DomEvent("submit", true, true);
        }

        public static DomEvent reset() {
            return new DomEvent("reset", true, true);
        }

        // 既存アクセサ（レコード互換）
        public String type() { return getType(); }
        public boolean bubbles() { return isBubbles(); }
        public boolean cancelable() { return isCancelable(); }

        public HtmlNode target() {
            EventTarget t = getTarget();
            return t instanceof HtmlNode n ? n : null;
        }

        public void setTarget(HtmlNode target) {
            super.setTarget(target);
        }

        public HtmlNode currentTarget() {
            EventTarget ct = getCurrentTarget();
            return ct instanceof HtmlNode n ? n : null;
        }

        public void setCurrentTarget(HtmlNode currentTarget) {
            super.setCurrentTarget(currentTarget);
        }

        public EventPhase eventPhase() {
            return switch (getEventPhase()) {
                case CAPTURING_PHASE -> EventPhase.CAPTURING_PHASE;
                case AT_TARGET -> EventPhase.AT_TARGET;
                case BUBBLING_PHASE -> EventPhase.BUBBLING_PHASE;
                default -> EventPhase.NONE;
            };
        }

        public void setEventPhase(EventPhase phase) {
            if (phase == null) {
                super.setEventPhase(NONE);
                return;
            }
            short p = switch (phase) {
                case CAPTURING_PHASE -> CAPTURING_PHASE;
                case AT_TARGET -> AT_TARGET;
                case BUBBLING_PHASE -> BUBBLING_PHASE;
                default -> NONE;
            };
            super.setEventPhase(p);
        }

        public double clientX() { return clientX; }
        public double clientY() { return clientY; }
        public int button() { return button; }
        public String detail() { return detail; }
        public void setDetail(String detail) { this.detail = detail != null ? detail : ""; }

        // MouseEvent / UIEvent 実装
        @Override public int getDetail() { return detailInt; }
        public void setDetailInt(int detailInt) { this.detailInt = detailInt; }

        @Override public double getScreenX() { return screenX; }
        public void setScreenX(double screenX) { this.screenX = screenX; }

        @Override public double getScreenY() { return screenY; }
        public void setScreenY(double screenY) { this.screenY = screenY; }

        @Override public double getClientX() { return clientX; }
        public void setClientX(double clientX) { this.clientX = clientX; }

        @Override public double getClientY() { return clientY; }
        public void setClientY(double clientY) { this.clientY = clientY; }

        @Override public boolean isCtrlKey() { return ctrlKey; }
        public void setCtrlKey(boolean ctrlKey) { this.ctrlKey = ctrlKey; }

        @Override public boolean isShiftKey() { return shiftKey; }
        public void setShiftKey(boolean shiftKey) { this.shiftKey = shiftKey; }

        @Override public boolean isAltKey() { return altKey; }
        public void setAltKey(boolean altKey) { this.altKey = altKey; }

        @Override public boolean isMetaKey() { return metaKey; }
        public void setMetaKey(boolean metaKey) { this.metaKey = metaKey; }

        @Override public short getButton() { return button; }
        public void setButton(short button) { this.button = button; }

        @Override public short getButtons() { return buttons; }
        public void setButtons(short buttons) { this.buttons = buttons; }

        @Nullable
        @Override
        public EventTarget getRelatedTarget() { return relatedTarget; }
        public void setRelatedTarget(@Nullable EventTarget relatedTarget) { this.relatedTarget = relatedTarget; }

        /**
         * DOM イベントの伝播フェーズ（下位互換 enum）。
         */
        public enum EventPhase {
            NONE,
            CAPTURING_PHASE,
            AT_TARGET,
            BUBBLING_PHASE
        }
    }
}
