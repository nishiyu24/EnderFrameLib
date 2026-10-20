package com.nishiyu.enderframelib.api.event;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * A signal object that allows you to communicate with a DOM request and abort it if required.
 *
 * <p>Conforms to WHATWG DOM Standard §2.8 Interface AbortSignal.</p>
 */
@ApiStatus.AvailableSince("1.0")
public interface AbortSignal {

    /**
     * Returns {@code true} if this signal has been aborted, {@code false} otherwise.
     */
    boolean isAborted();

    /**
     * Returns the reason why this signal was aborted, or {@code null} if not aborted.
     */
    @Nullable
    Object getReason();

    /**
     * Throws the abort reason if this signal has been aborted.
     */
    void throwIfAborted();

    /**
     * Adds an event listener for when the signal is aborted.
     */
    void addEventListener(String type, EventListener listener);

    /**
     * Removes an event listener.
     */
    void removeEventListener(String type, EventListener listener);

    /**
     * A controller object that allows you to abort one or more DOM requests as and when desired.
     *
     * <p>Conforms to WHATWG DOM Standard §2.8 Interface AbortController.</p>
     */
    interface Controller {

        /** Returns the {@link AbortSignal} object associated with this controller. */
        AbortSignal getSignal();

        /** Sets this controller's signal's aborted flag to {@code true}. */
        void abort();

        /** Sets this controller's signal's aborted flag to {@code true} with a reason. */
        void abort(@Nullable Object reason);
    }
}
