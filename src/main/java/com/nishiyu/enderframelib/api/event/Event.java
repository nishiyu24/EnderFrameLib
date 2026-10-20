package com.nishiyu.enderframelib.api.event;

import com.nishiyu.enderframelib.api.dom.EventTarget;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * An interface representing an event which takes place in the DOM.
 *
 * <p>Conforms to WHATWG DOM Standard §2.2 Interface Event.</p>
 */
@ApiStatus.AvailableSince("1.0")
public interface Event {

    // Event phase constants
    short NONE = 0;
    short CAPTURING_PHASE = 1;
    short AT_TARGET = 2;
    short BUBBLING_PHASE = 3;

    /**
     * Returns the type of this event (e.g. "click", "submit", "keydown").
     */
    String getType();

    /**
     * Returns the object to which event is dispatched (its target).
     */
    @Nullable
    EventTarget getTarget();

    /**
     * Returns the object whose event listener's callback is currently being run.
     */
    @Nullable
    EventTarget getCurrentTarget();

    /**
     * Returns the event's path (objects on which listeners will be invoked).
     */
    List<EventTarget> composedPath();

    /**
     * Returns the current event phase: {@link #NONE}, {@link #CAPTURING_PHASE}, {@link #AT_TARGET}, or {@link #BUBBLING_PHASE}.
     */
    short getEventPhase();

    /**
     * Prevents further propagation of the current event in the capturing and bubbling phases.
     */
    void stopPropagation();

    /**
     * Prevents further propagation of the current event to other listeners on the current target and subsequent targets.
     */
    void stopImmediatePropagation();

    /**
     * Returns {@code true} if this event bubbles up through the DOM tree, {@code false} otherwise.
     */
    boolean isBubbles();

    /**
     * Returns {@code true} if this event can be canceled with {@link #preventDefault()}, {@code false} otherwise.
     */
    boolean isCancelable();

    /**
     * Cancels the event (if it is cancelable), without stopping further propagation of the event.
     */
    void preventDefault();

    /**
     * Returns {@code true} if {@link #preventDefault()} was called for this event.
     */
    boolean isDefaultPrevented();

    /**
     * Returns {@code true} if this event can bubble across the boundary between the shadow DOM and the regular DOM.
     */
    boolean isComposed();

    /**
     * Returns {@code true} if the event was dispatched by the user agent, or {@code false} if dispatched by script.
     */
    boolean isTrusted();

    /**
     * Returns the time at which the event was created (in milliseconds since epoch or navigation start).
     */
    double getTimeStamp();
}
