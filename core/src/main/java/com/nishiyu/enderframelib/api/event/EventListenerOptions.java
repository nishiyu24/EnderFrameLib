package com.nishiyu.enderframelib.api.event;

import org.jetbrains.annotations.ApiStatus;

/**
 * Options that may be supplied to {@code removeEventListener} and related methods.
 *
 * <p>Conforms to WHATWG DOM Standard §2.7 Dictionary EventListenerOptions.</p>
 */
@ApiStatus.AvailableSince("1.0")
public class EventListenerOptions {

    private final boolean capture;

    public EventListenerOptions(boolean capture) {
        this.capture = capture;
    }

    public static EventListenerOptions of(boolean capture) {
        return new EventListenerOptions(capture);
    }

    /**
     * Returns whether the listener will be dispatched in the capture phase.
     */
    public boolean isCapture() {
        return capture;
    }
}
