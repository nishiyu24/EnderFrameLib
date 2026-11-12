package com.nishiyu.enderframelib.api.ui;

import com.nishiyu.enderframelib.api.dom.Document;
import com.nishiyu.enderframelib.api.dom.Element;
import com.nishiyu.enderframelib.api.dom.Node;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * A virtual node representing a declarative element, text fragment, or subtree in the UI DSL.
 */
@ApiStatus.AvailableSince("1.0")
public interface VNode {

    /**
     * Instantiates this virtual node into a real DOM {@link Node} belonging to the given {@link Document}.
     *
     * @param doc the owner document
     * @return the created DOM node, or {@code null} if empty
     */
    @Nullable
    Node mount(Document doc);

    /**
     * Instantiates and appends this virtual node into the specified parent {@link Node}.
     *
     * @param parent the parent node (typically an {@link Element} or {@link Document})
     * @return the mounted child {@link Node}, or {@code null} if empty
     */
    @Nullable
    default Node mountInto(Node parent) {
        Document doc = parent instanceof Document d ? d : parent.getOwnerDocument();
        if (doc == null) {
            throw new IllegalStateException("Parent node has no owner document");
        }
        Node child = mount(doc);
        if (child != null) {
            parent.appendChild(child);
        }
        return child;
    }
}
