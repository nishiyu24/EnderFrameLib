package com.nishiyu.enderframelib.api.ui;

import com.nishiyu.enderframelib.api.dom.DOMCollections.DOMTokenList;
import com.nishiyu.enderframelib.api.dom.Document;
import com.nishiyu.enderframelib.api.dom.Element;
import com.nishiyu.enderframelib.api.dom.Node;
import com.nishiyu.enderframelib.api.event.EventListener;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Modern, declarative UI framework for EnderFrameLib inspired by React, Mithril, and JSX.
 *
 * <p>Enables authoring dynamic DOM trees with zero boilerplate using functional components,
 * hyperscript element builders, reactive states ({@link #useState}), references ({@link #useRef}),
 * and standard modern web APIs ({@link #classList}, {@link #$}).</p>
 */
@ApiStatus.AvailableSince("1.0")
public class UI {

    protected UI() {}

    // ------------------------------------------------------------------------
    // Hyperscript Element Factories
    // ------------------------------------------------------------------------

    /**
     * Creates a virtual element with the specified tag name, props, and children.
     */
    public static VElement h(String tag, Object... items) {
        VElement el = new VElement(tag);
        if (items != null) {
            for (Object item : items) {
                unpack(item, el);
            }
        }
        return el;
    }

    public static VElement div(Object... items) { return h("div", items); }
    public static VElement span(Object... items) { return h("span", items); }
    public static VElement p(Object... items) { return h("p", items); }
    public static VElement a(Object... items) { return h("a", items); }
    public static VElement b(Object... items) { return h("b", items); }
    public static VElement strong(Object... items) { return h("strong", items); }
    public static VElement i(Object... items) { return h("i", items); }
    public static VElement em(Object... items) { return h("em", items); }
    public static VElement small(Object... items) { return h("small", items); }
    public static VElement code(Object... items) { return h("code", items); }
    public static VElement pre(Object... items) { return h("pre", items); }
    public static VElement hr(Object... items) { return h("hr", items); }
    public static VElement br(Object... items) { return h("br", items); }

    public static VElement h1(Object... items) { return h("h1", items); }
    public static VElement h2(Object... items) { return h("h2", items); }
    public static VElement h3(Object... items) { return h("h3", items); }
    public static VElement h4(Object... items) { return h("h4", items); }
    public static VElement h5(Object... items) { return h("h5", items); }
    public static VElement h6(Object... items) { return h("h6", items); }

    public static VElement header(Object... items) { return h("header", items); }
    public static VElement footer(Object... items) { return h("footer", items); }
    public static VElement mainTag(Object... items) { return h("main", items); }
    public static VElement aside(Object... items) { return h("aside", items); }
    public static VElement section(Object... items) { return h("section", items); }
    public static VElement article(Object... items) { return h("article", items); }
    public static VElement nav(Object... items) { return h("nav", items); }

    public static VElement ul(Object... items) { return h("ul", items); }
    public static VElement ol(Object... items) { return h("ol", items); }
    public static VElement li(Object... items) { return h("li", items); }

    public static VElement button(Object... items) { return h("button", items); }
    public static VElement input(Object... items) { return h("input", items); }
    public static VElement textarea(Object... items) { return h("textarea", items); }
    public static VElement label(Object... items) { return h("label", items); }
    public static VElement select(Object... items) { return h("select", items); }
    public static VElement option(Object... items) { return h("option", items); }
    public static VElement form(Object... items) { return h("form", items); }

    public static VElement img(Object... items) { return h("img", items); }

    public static VElement table(Object... items) { return h("table", items); }
    public static VElement thead(Object... items) { return h("thead", items); }
    public static VElement tbody(Object... items) { return h("tbody", items); }
    public static VElement tr(Object... items) { return h("tr", items); }
    public static VElement th(Object... items) { return h("th", items); }
    public static VElement td(Object... items) { return h("td", items); }

    public static VFragment fragment(Object... items) {
        VFragment frag = new VFragment();
        if (items != null) {
            for (Object item : items) {
                unpackFragment(item, frag);
            }
        }
        return frag;
    }

    public static VText text(Object value) {
        return new VText(value);
    }

    // ------------------------------------------------------------------------
    // Props & Attributes Helpers
    // ------------------------------------------------------------------------

    /**
     * Sets class names on the element. Multiple class strings are joined by spaces.
     */
    public static Prop cls(String... classNames) {
        return el -> {
            if (classNames == null || classNames.length == 0) return;
            StringBuilder sb = new StringBuilder();
            for (String c : classNames) {
                if (c != null && !c.isBlank()) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(c.trim());
                }
            }
            if (sb.length() > 0) {
                String existing = el.getClassName();
                if (existing != null && !existing.isBlank()) {
                    el.setClassName(existing + " " + sb);
                } else {
                    el.setClassName(sb.toString());
                }
            }
        };
    }

    public static Prop className(String... classNames) {
        return cls(classNames);
    }

    /**
     * Conditionally applies class names based on map boolean values.
     */
    public static Prop cls(Map<String, Boolean> classes) {
        return el -> {
            if (classes == null) return;
            for (Map.Entry<String, Boolean> entry : classes.entrySet()) {
                if (Boolean.TRUE.equals(entry.getValue())) {
                    el.getClassList().add(entry.getKey());
                }
            }
        };
    }

    public static Prop id(String id) {
        return el -> {
            if (id != null) el.setId(id);
        };
    }

    public static Prop style(String inlineCss) {
        return el -> {
            if (inlineCss != null) el.setAttribute("style", inlineCss);
        };
    }

    public static Prop style(String property, String value) {
        return el -> {
            if (property != null && value != null) {
                el.getStyle().setProperty(property, value);
            }
        };
    }

    public static Prop attr(String name, Object value) {
        return el -> {
            if (value == null) {
                el.removeAttribute(name);
            } else if (value instanceof Boolean b) {
                el.toggleAttribute(name, b);
            } else {
                el.setAttribute(name, String.valueOf(value));
            }
        };
    }

    public static Prop placeholder(String text) { return attr("placeholder", text); }
    public static Prop type(String type) { return attr("type", type); }
    public static Prop value(Object val) { return attr("value", val); }
    public static Prop name(String name) { return attr("name", name); }
    public static Prop href(String url) { return attr("href", url); }
    public static Prop src(String url) { return attr("src", url); }
    public static Prop disabled(boolean disabled) { return attr("disabled", disabled); }
    public static Prop checked(boolean checked) { return attr("checked", checked); }
    public static Prop hidden(boolean hidden) { return attr("hidden", hidden); }
    public static Prop data(String key, String val) { return attr("data-" + key, val); }

    @SuppressWarnings("unchecked")
    public static Prop ref(Ref<Element> ref) {
        return el -> {
            if (ref != null) ref.set(el);
        };
    }

    public static Prop ref(Consumer<Element> callback) {
        return el -> {
            if (callback != null) callback.accept(el);
        };
    }

    public static Prop on(String eventType, EventListener listener) {
        return el -> el.addEventListener(eventType, listener);
    }

    public static Prop onClick(EventListener listener) { return on("click", listener); }
    public static Prop onInput(EventListener listener) { return on("input", listener); }
    public static Prop onChange(EventListener listener) { return on("change", listener); }
    public static Prop onKeyDown(EventListener listener) { return on("keydown", listener); }
    public static Prop onKeyUp(EventListener listener) { return on("keyup", listener); }
    public static Prop onMouseEnter(EventListener listener) { return on("mouseenter", listener); }
    public static Prop onMouseLeave(EventListener listener) { return on("mouseleave", listener); }

    // ------------------------------------------------------------------------
    // React Hooks
    // ------------------------------------------------------------------------

    /**
     * Creates a reactive state initialized with the given value.
     */
    public static <T> State<T> useState(T initial) {
        return new State<>(initial);
    }

    /**
     * Creates an empty reference container.
     */
    public static <T> Ref<T> useRef() {
        return new Ref<>();
    }

    /**
     * Creates a reference container initialized with the given value.
     */
    public static <T> Ref<T> useRef(T initial) {
        return new Ref<>(initial);
    }

    /**
     * Executes the given effect immediately, and re-executes whenever any of the given dependencies change.
     */
    public static void useEffect(Runnable effect, State<?>... deps) {
        if (effect == null) return;
        effect.run();
        if (deps != null) {
            for (State<?> dep : deps) {
                if (dep != null) {
                    dep.onChange(v -> effect.run());
                }
            }
        }
    }

    // ------------------------------------------------------------------------
    // DOM Query & Helpers
    // ------------------------------------------------------------------------

    /**
     * Fast query selector looking up the first element matching {@code selector} within {@code root}.
     */
    @Nullable
    public static Element $(String selector, Node root) {
        if (root == null || selector == null || selector.isBlank()) return null;
        if (selector.startsWith("#") && !selector.contains(" ") && !selector.contains(".") && !selector.contains("[")) {
            return findById(root, selector.substring(1));
        }
        if (root instanceof Element el) {
            return el.querySelector(selector);
        }
        if (root instanceof Document doc) {
            return doc.querySelector(selector);
        }
        Document doc = root.getOwnerDocument();
        if (doc != null) {
            return doc.querySelector(selector);
        }
        return null;
    }

    @Nullable
    public static Element $(Node root, String selector) {
        return $(selector, root);
    }

    /**
     * Retrieves an element by its ID, throwing {@link IllegalStateException} if not found.
     */
    @NotNull
    public static Element byId(Node root, String id) {
        Element el = findById(root, id);
        if (el == null) {
            throw new IllegalStateException("#" + id + " not found");
        }
        return el;
    }

    @NotNull
    public static Element byId(String id, Node root) {
        return byId(root, id);
    }

    @Nullable
    private static Element findById(Node root, String id) {
        if (root == null || id == null) return null;
        Document doc = root instanceof Document d ? d : root.getOwnerDocument();
        if (doc != null) {
            Element el = doc.getElementById(id);
            if (el != null) return el;
        }
        if (root instanceof Element el) {
            return el.querySelector("#" + id);
        }
        return null;
    }

    /**
     * Obtains the live {@link DOMTokenList} of class names for an element.
     */
    @NotNull
    public static DOMTokenList classList(Node node) {
        if (node instanceof Element el) {
            return el.getClassList();
        }
        throw new IllegalArgumentException("Node is not an Element: " + node);
    }

    /**
     * Binds a reactive state directly to the text content of a DOM node.
     */
    public static void bindText(Node node, State<?> state) {
        if (node == null) throw new IllegalArgumentException("Cannot bind text: target node is null");
        if (state == null) return;
        node.setTextContent(String.valueOf(state.get()));
        state.onChange(val -> node.setTextContent(val != null ? String.valueOf(val) : ""));
    }

    /**
     * Two-way binds a reactive string state to the value attribute of an input element.
     */
    public static void bindValue(Element input, State<String> state) {
        if (input == null || state == null) return;
        input.setAttribute("value", state.get() != null ? state.get() : "");
        state.onChange(val -> input.setAttribute("value", val != null ? val : ""));
        input.addEventListener("input", e -> {
            String v = input.getAttribute("value");
            state.set(v != null ? v : "");
        });
    }

    // ------------------------------------------------------------------------
    // Mounting & Rendering
    // ------------------------------------------------------------------------

    /**
     * Batch mounts virtual nodes, text, or DOM nodes into the given parent node.
     */
    public static void mount(Node parent, Object... items) {
        if (parent == null || items == null) return;
        Document doc = parent instanceof Document d ? d : parent.getOwnerDocument();
        if (doc == null) {
            throw new IllegalArgumentException("Parent node has no ownerDocument");
        }
        var frag = doc.createDocumentFragment();
        mountIntoContainer(doc, frag, items);
        parent.appendChild(frag);
    }

    /**
     * Clears all children of the parent node and renders the new items into it.
     */
    public static void render(Node parent, Object... items) {
        if (parent == null) return;
        if (parent instanceof Element el) {
            el.replaceChildren();
        } else {
            while (parent.hasChildNodes()) {
                parent.removeChild(parent.getFirstChild());
            }
        }
        mount(parent, items);
    }

    private static void mountIntoContainer(Document doc, Node container, Object item) {
        if (item == null || item instanceof Boolean) return;
        if (item instanceof VNode vnode) {
            Node child = vnode.mount(doc);
            if (child != null) container.appendChild(child);
        } else if (item instanceof Node node) {
            container.appendChild(node);
        } else if (item instanceof Iterable<?> iter) {
            for (Object child : iter) mountIntoContainer(doc, container, child);
        } else if (item instanceof Object[] arr) {
            for (Object child : arr) mountIntoContainer(doc, container, child);
        } else {
            container.appendChild(doc.createTextNode(String.valueOf(item)));
        }
    }

    @SuppressWarnings("unchecked")
    private static void unpack(Object item, VElement element) {
        if (item == null || item instanceof Boolean) return;
        if (item instanceof Prop prop) {
            element.addProp(prop);
        } else if (item instanceof VNode vnode) {
            element.addChild(vnode);
        } else if (item instanceof Ref<?> ref) {
            element.setRef(el -> ((Ref<Element>) ref).set(el));
        } else if (item instanceof Node node) {
            element.addChild(doc -> node);
        } else if (item instanceof Iterable<?> iterable) {
            for (Object child : iterable) {
                unpack(child, element);
            }
        } else if (item instanceof Object[] array) {
            for (Object child : array) {
                unpack(child, element);
            }
        } else {
            element.addChild(new VText(item));
        }
    }

    private static void unpackFragment(Object item, VFragment frag) {
        if (item == null || item instanceof Boolean) return;
        if (item instanceof VNode vnode) {
            frag.addChild(vnode);
        } else if (item instanceof Node node) {
            frag.addChild(doc -> node);
        } else if (item instanceof Iterable<?> iter) {
            for (Object child : iter) unpackFragment(child, frag);
        } else if (item instanceof Object[] arr) {
            for (Object child : arr) unpackFragment(child, frag);
        } else {
            frag.addChild(new VText(item));
        }
    }
}
