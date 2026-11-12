package com.nishiyu.enderframelib.api.ui;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/**
 * Functional component interface, accepting props and returning a {@link VNode}.
 *
 * @param <P> the component props type
 */
@FunctionalInterface
@ApiStatus.AvailableSince("1.0")
public interface Component<P> {

    /**
     * Renders this component into a virtual node tree.
     *
     * @param props the component properties
     * @return the virtual node tree
     */
    @NotNull
    VNode render(P props);
}
