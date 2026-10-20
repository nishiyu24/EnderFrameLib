package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.api.dom.Element;
import com.nishiyu.enderframelib.api.dom.DOMCollections.HTMLCollection;
import com.nishiyu.enderframelib.api.dom.Node;
import com.nishiyu.enderframelib.api.dom.DOMCollections.NodeList;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Concrete implementations of {@link HTMLCollection} and {@link NodeList}.
 */
public final class HtmlCollections {

    private HtmlCollections() {}

    /**
     * An immutable snapshot NodeList wrapping a fixed list of nodes.
     */
    public static final class StaticNodeList implements NodeList {
        private final List<Node> nodes;

        public StaticNodeList(List<? extends Node> nodes) {
            this.nodes = nodes == null || nodes.isEmpty() ? Collections.emptyList() : new ArrayList<>(nodes);
        }

        @Override
        public int getLength() {
            return nodes.size();
        }

        @Nullable
        @Override
        public Node item(int index) {
            if (index < 0 || index >= nodes.size()) return null;
            return nodes.get(index);
        }

        @Override
        public Iterator<Node> iterator() {
            return nodes.iterator();
        }
    }

    /**
     * A live NodeList reflecting a supplier of child nodes.
     */
    public static final class LiveChildNodeList implements NodeList {
        private final Supplier<List<? extends Node>> supplier;

        public LiveChildNodeList(Supplier<List<? extends Node>> supplier) {
            this.supplier = supplier;
        }

        @Override
        public int getLength() {
            List<? extends Node> list = supplier.get();
            return list != null ? list.size() : 0;
        }

        @Nullable
        @Override
        public Node item(int index) {
            List<? extends Node> list = supplier.get();
            if (list == null || index < 0 || index >= list.size()) return null;
            return list.get(index);
        }

        @Override
        public Iterator<Node> iterator() {
            List<? extends Node> list = supplier.get();
            if (list == null) return Collections.emptyIterator();
            return new ArrayList<Node>(list).iterator();
        }
    }

    /**
     * A live HTMLCollection reflecting elements filtered from a node or tree.
     */
    public static final class LiveHTMLCollection implements HTMLCollection {
        private final Supplier<List<Element>> elementSupplier;

        public LiveHTMLCollection(Supplier<List<Element>> elementSupplier) {
            this.elementSupplier = elementSupplier;
        }

        @Override
        public int getLength() {
            return elementSupplier.get().size();
        }

        @Nullable
        @Override
        public Element item(int index) {
            List<Element> list = elementSupplier.get();
            if (index < 0 || index >= list.size()) return null;
            return list.get(index);
        }

        @Nullable
        @Override
        public Element namedItem(String name) {
            if (name == null || name.isEmpty()) return null;
            for (Element el : elementSupplier.get()) {
                if (name.equals(el.getId()) || name.equals(el.getAttribute("name"))) {
                    return el;
                }
            }
            return null;
        }

        @Override
        public Iterator<Element> iterator() {
            return elementSupplier.get().iterator();
        }
    }

    /**
     * A live HTMLCollection filtering child elements of a specific parent node.
     */
    public static LiveHTMLCollection forChildren(HtmlNode parent) {
        return new LiveHTMLCollection(() -> {
            List<Element> elements = new ArrayList<>();
            for (HtmlNode child : parent.children) {
                if (child instanceof Element el) {
                    elements.add(el);
                }
            }
            return elements;
        });
    }

    /**
     * A live HTMLCollection matching elements by predicate across descendants.
     */
    public static LiveHTMLCollection forDescendants(HtmlNode root, Predicate<Element> predicate) {
        return new LiveHTMLCollection(() -> {
            List<Element> result = new ArrayList<>();
            collectDescendants(root, predicate, result);
            return result;
        });
    }

    private static void collectDescendants(HtmlNode current, Predicate<Element> predicate, List<Element> out) {
        for (HtmlNode child : current.children) {
            if (child instanceof Element el) {
                if (predicate.test(el)) {
                    out.add(el);
                }
            }
            collectDescendants(child, predicate, out);
        }
    }
}
