package com.nishiyu.enderframelib.api.dom;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** The ordered collection interfaces of the DOM Standard, grouped in one place. */
@ApiStatus.AvailableSince("1.0")
public final class DOMCollections {

    private DOMCollections() {}

    /**
     * An ordered collection of {@link Element} objects, conforming to the DOM Standard.
     */
    @ApiStatus.AvailableSince("1.0")
    public interface HTMLCollection extends Iterable<Element> {

        /**
         * Returns the number of elements in the collection.
         */
        int getLength();

        /**
         * Returns the element at the specified index in the collection, or {@code null} if index is out of bounds.
         */
        @Nullable
        Element item(int index);

        /**
         * Returns the first element with the specified ID or name attribute, or {@code null} if no match is found.
         */
        @Nullable
        Element namedItem(String name);
    }

    /**
     * An ordered collection of {@link Node} elements, conforming to the DOM Standard.
     */
    @ApiStatus.AvailableSince("1.0")
    public interface NodeList extends Iterable<Node> {

        /**
         * Returns the number of nodes in the collection.
         */
        int getLength();

        /**
         * Returns the node at the specified index in the collection, or {@code null} if index is out of bounds.
         */
        @Nullable
        Node item(int index);
    }

    /**
     * A set of space-separated tokens, such as class names, conforming to the DOM Standard.
     */
    @ApiStatus.AvailableSince("1.0")
    public interface DOMTokenList extends Iterable<String> {

        /**
         * Returns the number of tokens in the list.
         */
        int getLength();

        /**
         * Returns the token at the given index, or {@code null} if index is out of bounds.
         */
        @Nullable
        String item(int index);

        /**
         * Returns {@code true} if the list contains the given token.
         */
        boolean contains(String token);

        /**
         * Adds the specified tokens to the list, ignoring duplicates.
         */
        void add(String... tokens);

        /**
         * Removes the specified tokens from the list.
         */
        void remove(String... tokens);

        /**
         * Toggles the presence of token in the list.
         *
         * @return {@code true} if the token is present after toggle, {@code false} otherwise.
         */
        boolean toggle(String token);

        /**
         * If force is {@code true}, adds token; if {@code false}, removes token.
         *
         * @return {@code true} if the token is present after toggle, {@code false} otherwise.
         */
        boolean toggle(String token, boolean force);

        /**
         * Replaces an existing token with a new token.
         *
         * @return {@code true} if token was replaced, {@code false} otherwise.
         */
        boolean replace(String oldToken, String newToken);

        /**
         * Returns the serialized value of the token list.
         */
        String getValue();

        /**
         * Sets the serialized value of the token list.
         */
        void setValue(String value);
    }
}
