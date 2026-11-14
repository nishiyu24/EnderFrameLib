package com.nishiyu.enderframelib.api.ui;

import com.nishiyu.enderframelib.api.dom.DOMCollections.DOMTokenList;
import com.nishiyu.enderframelib.api.dom.DOMCollections.NodeList;
import com.nishiyu.enderframelib.api.dom.Document;
import com.nishiyu.enderframelib.api.dom.Element;
import com.nishiyu.enderframelib.api.dom.EventTarget;
import com.nishiyu.enderframelib.api.dom.Node;
import com.nishiyu.enderframelib.api.event.Event;
import com.nishiyu.enderframelib.api.event.EventListener;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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

    /**
     * Scheduler bridge enabling JS-style {@link #setInterval}, {@link #setTimeout},
     * and {@link #requestAnimationFrame} inside {@link #withContext}.
     */
    public interface Scheduler {
        int setInterval(Runnable task, long intervalMs);
        int setTimeout(Runnable task, long delayMs);
        boolean clearInterval(int id);
        boolean clearTimeout(int id);
        int requestAnimationFrame(Consumer<Double> callback);
        boolean cancelAnimationFrame(int id);
    }

    /**
     * JavaScript-style console logger ({@code console.log(...)}).
     */
    public static final class Console {
        private Console() {}

        public void log(Object... args) { System.out.println(formatArgs(args)); }
        public void info(Object... args) { System.out.println(formatArgs(args)); }
        public void warn(Object... args) { System.err.println("[WARN] " + formatArgs(args)); }
        public void error(Object... args) { System.err.println("[ERROR] " + formatArgs(args)); }

        private static String formatArgs(Object... args) {
            if (args == null || args.length == 0) return "";
            if (args.length == 1) return String.valueOf(args[0]);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(' ');
                sb.append(args[i]);
            }
            return sb.toString();
        }
    }

    /** Global JS-style console instance ({@code console.log(...)}). */
    public static final Console console = new Console();

    private record UiContext(@Nullable Node root, @Nullable Scheduler scheduler) {}

    private static final ThreadLocal<UiContext> CONTEXT = new ThreadLocal<>();
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * Executes {@code app} with {@code root} and {@code scheduler} bound as the active JS/React context,
     * enabling context-free {@code $(selector)}, {@code createRoot(selector)}, {@code setInterval}, etc.
     */
    public static void withContext(@Nullable Node root, @Nullable Scheduler scheduler, @NotNull Runnable app) {
        Objects.requireNonNull(app, "app");
        UiContext previous = CONTEXT.get();
        CONTEXT.set(new UiContext(root, scheduler));
        try {
            app.run();
        } finally {
            if (previous != null) {
                CONTEXT.set(previous);
            } else {
                CONTEXT.remove();
            }
        }
    }

    /** Returns the active context's root node, or {@code null} if outside {@link #withContext}. */
    @Nullable
    public static Node rootNode() {
        UiContext ctx = CONTEXT.get();
        return ctx != null ? ctx.root() : null;
    }

    /** Returns the active context's {@link Document}, or {@code null} if unavailable. */
    @Nullable
    public static Document document() {
        Node root = rootNode();
        if (root instanceof Document doc) return doc;
        return root != null ? root.getOwnerDocument() : null;
    }

    private static Node requireRoot() {
        Node root = rootNode();
        if (root == null) {
            throw new IllegalStateException("No active UI context; pass root explicitly or run inside withContext/mountApp");
        }
        return root;
    }

    private static Scheduler requireScheduler() {
        UiContext ctx = CONTEXT.get();
        if (ctx == null || ctx.scheduler() == null) {
            throw new IllegalStateException("No active Scheduler in UI context; pass timers or run inside withContext/mountApp");
        }
        return ctx.scheduler();
    }

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

    /** React-style alias for {@link #fragment(Object...)}. */
    public static VFragment Fragment(Object... items) {
        return fragment(items);
    }

    /** Renders a {@link Component} with the given props. */
    public static <P> VNode el(Component<P> component, P props) {
        return component.render(props);
    }

    /** Renders a zero-prop functional component. */
    public static VNode el(Supplier<VNode> component) {
        return component.get();
    }

    public static VText text(Object value) {
        return new VText(value);
    }

    /**
     * JSX-like conditional rendering: {@code when(isOpen, div(...))} (equivalent to {@code {isOpen && <div/>}}).
     */
    @Nullable
    public static <T> T when(boolean condition, T value) {
        return condition ? value : null;
    }

    /**
     * JSX-like ternary rendering: {@code when(isOk, "OK", "ERR")}.
     */
    public static <T> T when(boolean condition, T whenTrue, T whenFalse) {
        return condition ? whenTrue : whenFalse;
    }

    /**
     * Reactive ternary state: updates whenever {@code condition} changes.
     */
    public static <T> State<T> when(State<Boolean> condition, T whenTrue, T whenFalse) {
        return condition.when(whenTrue, whenFalse);
    }

    /**
     * Reactive conditional CSS class prop: toggles {@code className} on the element's {@code classList}
     * whenever {@code condition} changes.
     */
    public static Prop when(String className, State<Boolean> condition) {
        return el -> {
            if (condition == null || className == null || className.isBlank()) return;
            if (Boolean.TRUE.equals(condition.get())) {
                addClassTokens(el.getClassList(), className);
            }
            condition.onChange(active -> toggleClassTokens(el.getClassList(), className, Boolean.TRUE.equals(active)));
        };
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
                    addClassTokens(el.getClassList(), entry.getKey());
                }
            }
        };
    }

    public static Prop className(Map<String, Boolean> classes) {
        return cls(classes);
    }

    /**
     * Composes static strings, reactive {@link State}s, conditional {@link Prop}s (e.g. {@link #when(String, State)}),
     * and class maps (including {@code State<Boolean>} values).
     */
    public static Prop cls(Object... parts) {
        return el -> {
            if (parts == null) return;
            for (Object part : parts) {
                applyClassPart(el, part);
            }
        };
    }

    public static Prop className(Object... parts) {
        return cls(parts);
    }

    /** React/clsx-style shorthand for {@link #cls(Object...)}. */
    public static Prop cx(Object... parts) {
        return cls(parts);
    }

    private static void applyClassPart(Element el, Object part) {
        if (part == null || part instanceof Boolean) return;
        if (part instanceof String s) {
            if (!s.isBlank()) {
                addClassTokens(el.getClassList(), s);
            }
        } else if (part instanceof Prop prop) {
            prop.apply(el);
        } else if (part instanceof State<?> state) {
            final String[] prev = {null};
            Runnable sync = () -> {
                DOMTokenList list = el.getClassList();
                if (prev[0] != null && !prev[0].isBlank()) {
                    removeClassTokens(list, prev[0]);
                }
                Object val = state.get();
                String next = val != null ? String.valueOf(val) : "";
                if (!next.isBlank()) {
                    addClassTokens(list, next);
                }
                prev[0] = next;
            };
            sync.run();
            state.onChange(v -> sync.run());
        } else if (part instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) continue;
                String token = String.valueOf(entry.getKey());
                Object v = entry.getValue();
                if (v instanceof State<?> state) {
                    if (Boolean.TRUE.equals(state.get())) {
                        addClassTokens(el.getClassList(), token);
                    }
                    state.onChange(next -> toggleClassTokens(el.getClassList(), token, Boolean.TRUE.equals(next)));
                } else if (Boolean.TRUE.equals(v)) {
                    addClassTokens(el.getClassList(), token);
                }
            }
        } else if (part instanceof Iterable<?> iter) {
            for (Object item : iter) applyClassPart(el, item);
        } else if (part instanceof Object[] arr) {
            for (Object item : arr) applyClassPart(el, item);
        }
    }

    private static void addClassTokens(DOMTokenList list, String raw) {
        if (raw == null || raw.isBlank()) return;
        for (String token : WHITESPACE.split(raw.trim())) {
            if (!token.isEmpty()) list.add(token);
        }
    }

    private static void removeClassTokens(DOMTokenList list, String raw) {
        if (raw == null || raw.isBlank()) return;
        for (String token : WHITESPACE.split(raw.trim())) {
            if (!token.isEmpty()) list.remove(token);
        }
    }

    private static void toggleClassTokens(DOMTokenList list, String raw, boolean force) {
        if (raw == null || raw.isBlank()) return;
        for (String token : WHITESPACE.split(raw.trim())) {
            if (!token.isEmpty()) list.toggle(token, force);
        }
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

    /** Reactively binds the full {@code style} attribute string. */
    public static Prop style(State<String> inlineCss) {
        return el -> {
            if (inlineCss == null) return;
            String init = inlineCss.get();
            if (init != null && !init.isEmpty()) {
                el.setAttribute("style", init);
            }
            inlineCss.onChange(val -> {
                if (val == null || val.isEmpty()) {
                    el.removeAttribute("style");
                } else {
                    el.setAttribute("style", val);
                }
            });
        };
    }

    /**
     * Sets a single CSS property, supporting both kebab-case ({@code "animation-delay"})
     * and React-style camelCase ({@code "animationDelay"}).
     */
    public static Prop style(String property, String value) {
        return el -> {
            if (property != null && value != null) {
                el.getStyle().setProperty(toCssProperty(property), value);
            }
        };
    }

    /**
     * Reactively binds a CSS property to a {@link State} (camelCase or kebab-case).
     */
    public static Prop style(String property, State<?> value) {
        return el -> {
            if (property == null || value == null) return;
            String cssProp = toCssProperty(property);
            applyStyleValue(el, cssProp, value.get());
            value.onChange(v -> applyStyleValue(el, cssProp, v));
        };
    }

    /** Sets a CSS property from a supplier (camelCase or kebab-case). */
    public static Prop style(String property, Supplier<?> value) {
        return el -> {
            if (property != null && value != null) {
                applyStyleValue(el, toCssProperty(property), value.get());
            }
        };
    }

    /**
     * Applies a React-style property map (supports camelCase keys and {@link State} values).
     */
    public static Prop style(Map<String, ?> properties) {
        return el -> {
            if (properties == null) return;
            for (Map.Entry<String, ?> entry : properties.entrySet()) {
                String key = entry.getKey();
                if (key == null) continue;
                String cssProp = toCssProperty(key);
                Object v = entry.getValue();
                if (v instanceof State<?> state) {
                    applyStyleValue(el, cssProp, state.get());
                    state.onChange(next -> applyStyleValue(el, cssProp, next));
                } else if (v instanceof Supplier<?> sup) {
                    applyStyleValue(el, cssProp, sup.get());
                } else {
                    applyStyleValue(el, cssProp, v);
                }
            }
        };
    }

    /**
     * Inline style object shorthand from alternating {@code property, value} pairs:
     * {@code sx("width", sizePx, "height", sizePx, "borderRadius", "12px")}.
     */
    public static Prop sx(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (keyValues != null) {
            for (int i = 0; i + 1 < keyValues.length; i += 2) {
                map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
            }
        }
        return style(map);
    }

    /** Imperatively updates an existing element's styles using a React-style property map. */
    public static void setStyle(@Nullable Element el, @Nullable Map<String, ?> properties) {
        if (el == null || properties == null) return;
        for (Map.Entry<String, ?> entry : properties.entrySet()) {
            String key = entry.getKey();
            if (key == null) continue;
            Object v = entry.getValue();
            Object resolved = v instanceof State<?> s ? s.get() : v instanceof Supplier<?> sup ? sup.get() : v;
            applyStyleValue(el, toCssProperty(key), resolved);
        }
    }

    private static void applyStyleValue(Element el, String cssProp, @Nullable Object val) {
        if (val == null) {
            el.getStyle().removeProperty(cssProp);
        } else {
            String s = String.valueOf(val);
            if (s.isBlank()) {
                el.getStyle().removeProperty(cssProp);
            } else {
                el.getStyle().setProperty(cssProp, s);
            }
        }
    }

    private static String toCssProperty(String prop) {
        if (prop.startsWith("--")) return prop;
        boolean hasUpper = false;
        for (int i = 0; i < prop.length(); i++) {
            char c = prop.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                hasUpper = true;
                break;
            }
        }
        if (!hasUpper) return prop;
        StringBuilder sb = new StringBuilder(prop.length() + 4);
        for (int i = 0; i < prop.length(); i++) {
            char c = prop.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                sb.append('-').append((char) (c + 32));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Reactively shows/hides an element by toggling {@code display: none}. */
    public static Prop show(State<Boolean> visible) {
        return el -> {
            if (visible == null) return;
            if (!Boolean.TRUE.equals(visible.get())) {
                el.getStyle().setProperty("display", "none");
            }
            visible.onChange(v -> {
                if (Boolean.TRUE.equals(v)) {
                    el.getStyle().removeProperty("display");
                } else {
                    el.getStyle().setProperty("display", "none");
                }
            });
        };
    }

    /** Reactively hides/shows an element by toggling {@code display: none}. */
    public static Prop hidden(State<Boolean> hidden) {
        return el -> {
            if (hidden == null) return;
            if (Boolean.TRUE.equals(hidden.get())) {
                el.getStyle().setProperty("display", "none");
            }
            hidden.onChange(v -> {
                if (Boolean.TRUE.equals(v)) {
                    el.getStyle().setProperty("display", "none");
                } else {
                    el.getStyle().removeProperty("display");
                }
            });
        };
    }

    public static Prop attr(String name, Object value) {
        return el -> applyAttrValue(el, name, value);
    }

    /** Reactively binds an attribute to a {@link State}. */
    public static Prop attr(String name, State<?> value) {
        return el -> {
            if (value == null) return;
            applyAttrValue(el, name, value.get());
            value.onChange(v -> applyAttrValue(el, name, v));
        };
    }

    private static void applyAttrValue(Element el, String name, @Nullable Object value) {
        if (value == null) {
            el.removeAttribute(name);
        } else if (value instanceof Boolean b) {
            el.toggleAttribute(name, b);
        } else {
            el.setAttribute(name, String.valueOf(value));
        }
    }

    public static Prop placeholder(String text) { return attr("placeholder", text); }
    public static Prop type(String type) { return attr("type", type); }
    public static Prop value(Object val) { return attr("value", val); }
    public static Prop value(State<?> val) { return attr("value", val); }
    public static Prop name(String name) { return attr("name", name); }
    public static Prop href(String url) { return attr("href", url); }
    public static Prop src(String url) { return attr("src", url); }
    public static Prop disabled(boolean disabled) { return attr("disabled", disabled); }
    public static Prop disabled(State<Boolean> disabled) { return attr("disabled", disabled); }
    public static Prop checked(boolean checked) { return attr("checked", checked); }
    public static Prop checked(State<Boolean> checked) { return attr("checked", checked); }
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

    /** Prop listener receiving {@code e.target.value} directly as a non-null String on {@code input}. */
    public static Prop onInputValue(Consumer<String> onValue) {
        return on("input", e -> { if (onValue != null) onValue.accept(targetValue(e)); });
    }

    /** Prop listener receiving {@code e.target.value} directly as a non-null String on {@code change}. */
    public static Prop onChangeValue(Consumer<String> onValue) {
        return on("change", e -> { if (onValue != null) onValue.accept(targetValue(e)); });
    }

    /** Extracts {@code e.target.value} safely, returning {@code ""} if missing. */
    @NotNull
    public static String targetValue(@Nullable Event event) {
        return targetValue(event, "");
    }

    /** Extracts {@code e.target.value} safely, returning {@code fallback} if missing. */
    @NotNull
    public static String targetValue(@Nullable Event event, String fallback) {
        if (event != null && event.getTarget() instanceof Element el) {
            String val = el.getAttribute("value");
            if (val != null) return val;
        }
        return fallback != null ? fallback : "";
    }

    /** Extracts {@code e.target.checked} safely. */
    public static boolean targetChecked(@Nullable Event event) {
        return event != null && event.getTarget() instanceof Element el && el.hasAttribute("checked");
    }

    public static void on(@Nullable EventTarget target, String eventType, EventListener listener) {
        if (target != null && listener != null) target.addEventListener(eventType, listener);
    }

    public static void onClick(@Nullable EventTarget target, EventListener listener) { on(target, "click", listener); }
    public static void onInput(@Nullable EventTarget target, EventListener listener) { on(target, "input", listener); }
    public static void onChange(@Nullable EventTarget target, EventListener listener) { on(target, "change", listener); }

    public static void onInputValue(@Nullable EventTarget target, Consumer<String> onValue) {
        on(target, "input", e -> { if (onValue != null) onValue.accept(targetValue(e)); });
    }

    public static void onChangeValue(@Nullable EventTarget target, Consumer<String> onValue) {
        on(target, "change", e -> { if (onValue != null) onValue.accept(targetValue(e)); });
    }

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

    /**
     * Executes an effect that returns an optional cleanup {@link Runnable}, invoking the previous
     * cleanup before each re-execution when dependencies change.
     */
    public static void useEffect(Supplier<Runnable> effectWithCleanup, State<?>... deps) {
        if (effectWithCleanup == null) return;
        final Runnable[] cleanup = { effectWithCleanup.get() };
        if (deps != null) {
            for (State<?> dep : deps) {
                if (dep != null) {
                    dep.onChange(v -> {
                        if (cleanup[0] != null) {
                            cleanup[0].run();
                        }
                        cleanup[0] = effectWithCleanup.get();
                    });
                }
            }
        }
    }

    /**
     * Computes a derived reactive {@link State} from {@code compute} and updates it whenever
     * any dependency in {@code deps} changes.
     */
    public static <T> State<T> useMemo(Supplier<T> compute, State<?>... deps) {
        Objects.requireNonNull(compute, "compute");
        State<T> memo = new State<>(compute.get());
        if (deps != null) {
            for (State<?> dep : deps) {
                if (dep != null) {
                    dep.onChange(v -> memo.set(compute.get()));
                }
            }
        }
        return memo;
    }

    /** React parity helper returning {@code callback}. */
    public static <T> T useCallback(T callback, State<?>... deps) {
        return callback;
    }

    // ------------------------------------------------------------------------
    // JS Timers & Collection Mapping
    // ------------------------------------------------------------------------

    public static int setInterval(Runnable task, long intervalMs) {
        return requireScheduler().setInterval(task, intervalMs);
    }

    public static int setTimeout(Runnable task, long delayMs) {
        return requireScheduler().setTimeout(task, delayMs);
    }

    public static boolean clearInterval(int id) {
        return requireScheduler().clearInterval(id);
    }

    public static boolean clearTimeout(int id) {
        return requireScheduler().clearTimeout(id);
    }

    public static int requestAnimationFrame(Consumer<Double> callback) {
        return requireScheduler().requestAnimationFrame(callback);
    }

    public static boolean cancelAnimationFrame(int id) {
        return requireScheduler().cancelAnimationFrame(id);
    }

    /** Maps indices {@code 0 .. count - 1} to a list, mirroring JS {@code Array.from({length: count}, (_, i) => ...)}. */
    public static <R> List<R> map(int count, IntFunction<R> mapper) {
        return map(0, count, mapper);
    }

    /** Maps indices {@code startInclusive .. endExclusive - 1} to a list. */
    public static <R> List<R> map(int startInclusive, int endExclusive, IntFunction<R> mapper) {
        int len = Math.max(0, endExclusive - startInclusive);
        List<R> out = new ArrayList<>(len);
        for (int i = startInclusive; i < endExclusive; i++) {
            out.add(mapper.apply(i));
        }
        return out;
    }

    /** Maps a collection to a list, mirroring JS {@code array.map(item => ...)}. */
    public static <T, R> List<R> map(Collection<T> items, Function<? super T, ? extends R> mapper) {
        if (items == null || items.isEmpty()) return List.of();
        List<R> out = new ArrayList<>(items.size());
        for (T item : items) {
            out.add(mapper.apply(item));
        }
        return out;
    }

    /** Maps an array to a list, mirroring JS {@code array.map(item => ...)}. */
    public static <T, R> List<R> map(T[] items, Function<? super T, ? extends R> mapper) {
        if (items == null || items.length == 0) return List.of();
        List<R> out = new ArrayList<>(items.length);
        for (T item : items) {
            out.add(mapper.apply(item));
        }
        return out;
    }

    // ------------------------------------------------------------------------
    // DOM Query & Helpers
    // ------------------------------------------------------------------------

    /**
     * Context-aware query selector (uses the root node from {@link #withContext}).
     */
    @Nullable
    public static Element $(String selector) {
        return $(selector, requireRoot());
    }

    /**
     * Context-aware querySelectorAll returning a {@link List} of matching {@link Element}s.
     */
    @NotNull
    public static List<Element> $$(String selector) {
        return $$(selector, requireRoot());
    }

    /**
     * Returns all elements matching {@code selector} within {@code root}.
     */
    @NotNull
    public static List<Element> $$(String selector, Node root) {
        if (root == null || selector == null || selector.isBlank()) return List.of();
        NodeList nl = null;
        if (root instanceof Element el) {
            nl = el.querySelectorAll(selector);
        } else if (root instanceof Document doc) {
            nl = doc.querySelectorAll(selector);
        } else {
            Document doc = root.getOwnerDocument();
            if (doc != null) {
                nl = doc.querySelectorAll(selector);
            }
        }
        if (nl == null || nl.getLength() == 0) return List.of();
        List<Element> out = new ArrayList<>(nl.getLength());
        for (int i = 0; i < nl.getLength(); i++) {
            if (nl.item(i) instanceof Element el) {
                out.add(el);
            }
        }
        return out;
    }

    /**
     * Context-aware getElementById (uses the root node from {@link #withContext}).
     */
    @NotNull
    public static Element byId(String id) {
        return byId(requireRoot(), id);
    }

    /**
     * Creates a React 18-style root handle for {@code container}.
     */
    @NotNull
    public static ReactRoot createRoot(@NotNull Node container) {
        return new ReactRoot(container);
    }

    /**
     * Creates a React 18-style root handle for the element matching {@code selector} in the active context.
     */
    @NotNull
    public static ReactRoot createRoot(@NotNull String selector) {
        return createRoot(selector, requireRoot());
    }

    /**
     * Creates a React 18-style root handle for the element matching {@code selector} within {@code root}.
     */
    @NotNull
    public static ReactRoot createRoot(@NotNull String selector, @NotNull Node root) {
        Element el = $(selector, root);
        if (el == null) {
            throw new IllegalStateException("createRoot target not found: " + selector);
        }
        return new ReactRoot(el);
    }

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
        node.updateTextContent(String.valueOf(state.get()));
        state.onChange(val -> node.updateTextContent(val != null ? String.valueOf(val) : ""));
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
        } else if (item instanceof Stream<?> stream) {
            stream.forEach(child -> mountIntoContainer(doc, container, child));
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
        } else if (item instanceof Stream<?> stream) {
            stream.forEach(child -> unpack(child, element));
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
        } else if (item instanceof Stream<?> stream) {
            stream.forEach(child -> unpackFragment(child, frag));
        } else {
            frag.addChild(new VText(item));
        }
    }
}
