package com.nishiyu.enderframelib.api.dom;

import com.nishiyu.enderframelib.api.dom.DOMCollections.NodeList;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.function.Function;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * Provides the ability to watch for changes being made to the DOM tree.
 *
 * <p>Conforms to WHATWG DOM Standard §4.3 MutationObserver.</p>
 */
@ApiStatus.AvailableSince("1.0")
public interface MutationObserver {

    /**
     * Configures the MutationObserver to begin receiving notifications through its callback when DOM changes occur.
     */
    void observe(Node target, Init options);

    /**
     * Configures the MutationObserver with default options (childList + subtree).
     */
    default void observe(Node target) {
        observe(target, Init.builder().childList(true).subtree(true).build());
    }

    /**
     * Stops the MutationObserver instance from receiving further notifications until and unless observe() is called again.
     */
    void disconnect();

    /**
     * Empties the MutationObserver instance's record queue and returns what was in there.
     */
    List<Record> takeRecords();

    /**
     * Creates a new {@link MutationObserver} instance with the given callback.
     */
    static MutationObserver create(Callback callback) {
        if (Factory.provider == null) {
            throw new IllegalStateException("MutationObserver provider is not registered in runtime");
        }
        return Factory.provider.apply(callback);
    }

    /**
     * Internal factory holder for implementation injection without violating API boundary.
     */
    @ApiStatus.Internal
    class Factory {
        public static Function<Callback, MutationObserver> provider;
    }

    /**
     * Callback invoked when DOM mutations occur.
     *
     * <p>Conforms to WHATWG DOM Standard §4.3 Callback.</p>
     */
    @FunctionalInterface
    @ApiStatus.AvailableSince("1.0")
    public interface Callback {

        /**
         * Handles the queued DOM mutations.
         *
         * @param mutations list of mutation records
         * @param observer  the observer invoking this callback
         */
        void handleMutations(List<Record> mutations, MutationObserver observer);
    }

    /**
     * Represents an individual DOM mutation observed by a {@link MutationObserver}.
     *
     * <p>Conforms to WHATWG DOM Standard §4.3 Record.</p>
     */
    @ApiStatus.AvailableSince("1.0")
    public interface Record {

        /**
         * Returns "attributes" if it was an attribute mutation, "characterData" if it was a mutation to a CharacterData node,
         * and "childList" if it was a mutation to the tree of nodes.
         */
        String getType();

        /**
         * Returns the node the mutation affected, depending on the {@link #getType()}.
         */
        Node getTarget();

        /**
         * Return the nodes added and removed.
         */
        NodeList getAddedNodes();

        /**
         * Return the nodes removed.
         */
        NodeList getRemovedNodes();

        /**
         * Return the previous and next siblings of the added or removed nodes, or {@code null}.
         */
        @Nullable
        Node getPreviousSibling();

        /**
         * Return the next sibling of the added or removed nodes, or {@code null}.
         */
        @Nullable
        Node getNextSibling();

        /**
         * Returns the local name of the changed attribute, or {@code null}.
         */
        @Nullable
        String getAttributeName();

        /**
         * The return value depends on type. For "attributes", it is the value of the changed attribute before the change.
         * For "characterData", it is the data of the changed node before the change. For "childList", it is {@code null}.
         */
        @Nullable
        String getOldValue();
    }

    /**
     * Options dictionary configuring a {@link MutationObserver}.
     *
     * <p>Conforms to WHATWG DOM Standard §4.3 Init.</p>
     */
    @ApiStatus.AvailableSince("1.0")
    public class Init {

        private boolean childList;
        private boolean attributes;
        private boolean characterData;
        private boolean subtree;
        private boolean attributeOldValue;
        private boolean characterDataOldValue;
        private Set<String> attributeFilter;

        public Init() {}

        public static Builder builder() {
            return new Builder();
        }

        public boolean isChildList() {
            return childList;
        }

        public void setChildList(boolean childList) {
            this.childList = childList;
        }

        public boolean isAttributes() {
            return attributes;
        }

        public void setAttributes(boolean attributes) {
            this.attributes = attributes;
        }

        public boolean isCharacterData() {
            return characterData;
        }

        public void setCharacterData(boolean characterData) {
            this.characterData = characterData;
        }

        public boolean isSubtree() {
            return subtree;
        }

        public void setSubtree(boolean subtree) {
            this.subtree = subtree;
        }

        public boolean isAttributeOldValue() {
            return attributeOldValue;
        }

        public void setAttributeOldValue(boolean attributeOldValue) {
            this.attributeOldValue = attributeOldValue;
        }

        public boolean isCharacterDataOldValue() {
            return characterDataOldValue;
        }

        public void setCharacterDataOldValue(boolean characterDataOldValue) {
            this.characterDataOldValue = characterDataOldValue;
        }

        @Nullable
        public Set<String> getAttributeFilter() {
            return attributeFilter;
        }

        public void setAttributeFilter(@Nullable Set<String> attributeFilter) {
            this.attributeFilter = attributeFilter != null ? new HashSet<>(attributeFilter) : null;
        }

        public static class Builder {
            private final Init init = new Init();

            public Builder childList(boolean childList) {
                init.setChildList(childList);
                return this;
            }

            public Builder attributes(boolean attributes) {
                init.setAttributes(attributes);
                return this;
            }

            public Builder characterData(boolean characterData) {
                init.setCharacterData(characterData);
                return this;
            }

            public Builder subtree(boolean subtree) {
                init.setSubtree(subtree);
                return this;
            }

            public Builder attributeOldValue(boolean attributeOldValue) {
                init.setAttributeOldValue(attributeOldValue);
                return this;
            }

            public Builder characterDataOldValue(boolean characterDataOldValue) {
                init.setCharacterDataOldValue(characterDataOldValue);
                return this;
            }

            public Builder attributeFilter(String... attributes) {
                Set<String> set = new HashSet<>();
                Collections.addAll(set, attributes);
                init.setAttributeFilter(set);
                return this;
            }

            public Init build() {
                return init;
            }
        }
    }
}
