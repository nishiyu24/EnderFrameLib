package com.nishiyu.enderframelib.api.event;

import com.nishiyu.enderframelib.api.dom.Element;
import com.nishiyu.enderframelib.api.dom.EventTarget;
import com.nishiyu.enderframelib.api.dom.FormData;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * The event interfaces layered on top of {@link Event}, grouped by specification.
 *
 * <p>Each nested type corresponds to the interface of the same name with an {@code Event} suffix:
 * {@link UI} is {@code UIEvent}, {@link Mouse} is {@code MouseEvent}, and so on.</p>
 */
@ApiStatus.AvailableSince("1.0")
public final class UIEvents {

    private UIEvents() {}

    /** Simple user interface events. W3C UI Events §3 Interface UIEvent. */
    public interface UI extends Event {

        /** Returns event detail (e.g. click count for mouse click events). */
        int getDetail();
    }

    /** Events from a pointing device. W3C UI Events §3 Interface MouseEvent. */
    public interface Mouse extends UI {

        double getScreenX();

        double getScreenY();

        double getClientX();

        double getClientY();

        boolean isCtrlKey();

        boolean isShiftKey();

        boolean isAltKey();

        boolean isMetaKey();

        short getButton();

        short getButtons();

        @Nullable
        EventTarget getRelatedTarget();
    }

    /** Synthetic pointer events. W3C Pointer Events Level 3 §5 Interface PointerEvent. */
    public interface Pointer extends Mouse {

        int getPointerId();

        double getWidth();

        double getHeight();

        float getPressure();

        String getPointerType();

        boolean isPrimary();
    }

    /** Mouse wheel or similar device events. W3C UI Events §3 Interface WheelEvent. */
    public interface Wheel extends Mouse {

        int DOM_DELTA_PIXEL = 0x00;
        int DOM_DELTA_LINE = 0x01;
        int DOM_DELTA_PAGE = 0x02;

        double getDeltaX();

        double getDeltaY();

        double getDeltaZ();

        int getDeltaMode();
    }

    /** Keyboard interactions. W3C UI Events §3 Interface KeyboardEvent. */
    public interface Keyboard extends UI {

        int DOM_KEY_LOCATION_STANDARD = 0x00;
        int DOM_KEY_LOCATION_LEFT = 0x01;
        int DOM_KEY_LOCATION_RIGHT = 0x02;
        int DOM_KEY_LOCATION_NUMPAD = 0x03;

        String getKey();

        String getCode();

        int getLocation();

        boolean isCtrlKey();

        boolean isShiftKey();

        boolean isAltKey();

        boolean isMetaKey();

        boolean isRepeat();

        boolean isComposing();
    }

    /** Focus-related events (focus, blur, focusin, focusout). W3C UI Events §3 Interface FocusEvent. */
    public interface Focus extends UI {

        @Nullable
        EventTarget getRelatedTarget();
    }

    /** Edits to editable content (beforeinput, input). W3C Input Events Level 2 §2 Interface InputEvent. */
    public interface Input extends UI {

        @Nullable
        String getData();

        boolean isComposing();

        String getInputType();
    }

    /** IME composition events. W3C UI Events §3 Interface CompositionEvent. */
    public interface Composition extends UI {

        String getData();
    }

    /** Events initialized by an application for any purpose. WHATWG DOM Standard §2.4 Interface CustomEvent. */
    public interface Custom extends Event {

        @Nullable
        Object getDetail();
    }

    /**
     * Fired at a {@code <form>} when its entry list is being constructed.
     * WHATWG HTML Standard §4.10.21.4 Interface FormDataEvent.
     */
    public interface FormDataCollect extends Event {

        /** Returns the {@link FormData} object containing the form's current entry list. */
        FormData getFormData();
    }

    /** Fired at a {@code <form>} when it is submitted. WHATWG HTML Standard §4.10.21.5 Interface SubmitEvent. */
    public interface Submit extends Event {

        /** Returns the submit button that was invoked to submit the form, or {@code null} if not applicable. */
        @Nullable
        Element getSubmitter();
    }
}
