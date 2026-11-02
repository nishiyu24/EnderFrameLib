package com.nishiyu.enderframelib.api.dom;

import com.nishiyu.enderframelib.api.event.AddEventListenerOptions;
import com.nishiyu.enderframelib.api.event.Event;
import com.nishiyu.enderframelib.api.event.EventListener;
import com.nishiyu.enderframelib.api.event.EventListenerOptions;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * An interface implemented by objects that can receive events and may have listeners for them.
 *
 * <p>Conforms to WHATWG DOM Standard §2.7 Interface EventTarget.</p>
 */
@ApiStatus.AvailableSince("1.0")
public interface EventTarget {

    /**
     * Appends an event listener for events whose type attribute value is {@code type}.
     */
    default void addEventListener(String type, EventListener listener) {
        addEventListener(type, listener, new AddEventListenerOptions(false, false, false, null));
    }

    /**
     * Appends an event listener for events whose type attribute value is {@code type}.
     *
     * @param useCapture if {@code true}, listener is invoked in capturing phase
     */
    default void addEventListener(String type, EventListener listener, boolean useCapture) {
        addEventListener(type, listener, new AddEventListenerOptions(useCapture, false, false, null));
    }

    /**
     * Appends an event listener for events whose type attribute value is {@code type} with the specified options.
     */
    void addEventListener(String type, EventListener listener, AddEventListenerOptions options);

    /**
     * Removes the event listener in target's event listener list with the same type and listener callback.
     */
    default void removeEventListener(String type, EventListener listener) {
        removeEventListener(type, listener, new EventListenerOptions(false));
    }

    /**
     * Removes the event listener in target's event listener list with the same type, listener, and capture option.
     */
    default void removeEventListener(String type, EventListener listener, boolean useCapture) {
        removeEventListener(type, listener, new EventListenerOptions(useCapture));
    }

    /**
     * Removes the event listener in target's event listener list with the same type, listener, and options.
     */
    void removeEventListener(String type, EventListener listener, EventListenerOptions options);

    /**
     * Dispatches a synthetic event to target and returns true if either event's cancelable attribute value is false
     * or its preventDefault() method was not invoked, and false otherwise.
     */
    boolean dispatchEvent(Event event);
}
