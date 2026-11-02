package com.nishiyu.enderframelib.api.event;

import org.jetbrains.annotations.ApiStatus;

/**
 * An object that can handle an event dispatched by an {@link com.nishiyu.enderframelib.api.dom.EventTarget}.
 *
 * <p>Conforms to WHATWG DOM Standard §2.6 Callback interface EventListener.</p>
 */
@FunctionalInterface
@ApiStatus.AvailableSince("1.0")
public interface EventListener {

    /**
     * Invoked whenever an event of the type for which the EventListener was registered occurs.
     *
     * @param event the {@link Event} being handled
     */
    void handleEvent(Event event);
}
