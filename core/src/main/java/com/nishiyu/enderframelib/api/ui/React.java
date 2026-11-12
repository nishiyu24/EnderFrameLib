package com.nishiyu.enderframelib.api.ui;

import org.jetbrains.annotations.ApiStatus;

/**
 * React-styled DSL facade for EnderFrameLib.
 *
 * <p>Enables modern, declarative UI authoring in pure Java inspired by React, Mithril, and JSX.
 * Typical usage is via static import:</p>
 *
 * <pre>{@code
 * import static com.nishiyu.enderframelib.api.ui.React.*;
 *
 * var count = useState(0);
 * var card = div(cls("card"),
 *     header(h1("Title")),
 *     mainTag(
 *         p("Current count: ", count),
 *         button(onClick(e -> count.update(c -> c + 1)), "Increment")
 *     )
 * );
 * mount(container, card);
 * }</pre>
 */
@ApiStatus.AvailableSince("1.0")
public final class React extends UI {
    private React() {}
}
