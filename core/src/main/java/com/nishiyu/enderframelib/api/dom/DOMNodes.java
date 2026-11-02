package com.nishiyu.enderframelib.api.dom;

import com.nishiyu.enderframelib.api.dom.DOMCollections.HTMLCollection;
import com.nishiyu.enderframelib.api.dom.DOMCollections.NodeList;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** The leaf and fragment node interfaces of the DOM Standard, grouped in one place. */
@ApiStatus.AvailableSince("1.0")
public final class DOMNodes {

    private DOMNodes() {}

    /**
     * An interface representing a text node in a DOM tree.
     *
     * <p>Conforms to WHATWG DOM Standard §4.7 Text.</p>
     */
    @ApiStatus.AvailableSince("1.0")
    public interface Text extends Node {

        /**
         * Returns the textual data contained in this node.
         */
        String getData();

        /**
         * Sets the textual data contained in this node.
         */
        void setData(String data);

        /**
         * Returns the number of characters in the text data.
         */
        int getLength();

        /**
         * Breaks the Text node into two nodes at the specified offset, keeping both nodes in the tree as siblings.
         */
        Text splitText(int offset);
    }

    /**
     * An interface representing textual notations within markup, used as comments.
     *
     * <p>Conforms to WHATWG DOM Standard §4.8 Comment.</p>
     */
    @ApiStatus.AvailableSince("1.0")
    public interface Comment extends Node {

        /**
         * Returns the textual data contained in this comment.
         */
        String getData();

        /**
         * Sets the textual data contained in this comment.
         */
        void setData(String data);

        /**
         * Returns the length of the comment string.
         */
        int getLength();
    }

    /**
     * A minimal document object that has no parent.
     *
     * <p>Conforms to WHATWG DOM Standard §4.6 DocumentFragment and ParentNode mixin.</p>
     */
    @ApiStatus.AvailableSince("1.0")
    public interface DocumentFragment extends Node {

        /**
         * Returns a live {@link HTMLCollection} containing all child elements.
         */
        HTMLCollection getChildren();

        /**
         * Returns the first child that is an Element, or {@code null} if there is none.
         */
        @Nullable
        Element getFirstElementChild();

        /**
         * Returns the last child that is an Element, or {@code null} if there is none.
         */
        @Nullable
        Element getLastElementChild();

        /**
         * Returns the number of child elements.
         */
        int getChildElementCount();

        /**
         * Inserts a set of Node objects before the first child of the fragment.
         */
        void prepend(Node... nodes);

        /**
         * Inserts a set of Node objects after the last child of the fragment.
         */
        void append(Node... nodes);

        /**
         * Replaces the existing children of the fragment with a specified new set of children.
         */
        void replaceChildren(Node... nodes);

        /**
         * Returns the first element that matches the specified selector string within the fragment.
         */
        @Nullable
        Element querySelector(String selectors);

        /**
         * Returns a static {@link NodeList} representing a list of elements that match the selector.
         */
        NodeList querySelectorAll(String selectors);

        /**
         * Returns an {@link Element} object representing the element whose id property matches the specified string.
         */
        @Nullable
        Element getElementById(String elementId);
    }
}
