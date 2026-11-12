package com.nishiyu.enderframelib.api.ui;

import com.nishiyu.enderframelib.api.dom.Element;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/**
 * Represents an attribute, style, event listener, or property applied to an {@link Element}.
 */
@FunctionalInterface
@ApiStatus.AvailableSince("1.0")
public interface Prop {

    /**
     * Applies this property configuration to the given mounted {@link Element}.
     *
     * @param element the element to configure
     */
    void apply(@NotNull Element element);
}
