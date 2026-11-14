package com.nishiyu.enderframelib.api.ui;

import com.nishiyu.enderframelib.api.dom.Element;
import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.nishiyu.enderframelib.api.ui.UI.*;
import static org.junit.jupiter.api.Assertions.*;

public class ReactUiTest {

    private HtmlDocument doc;
    private Element root;

    @BeforeEach
    void setUp() {
        doc = new HtmlDocument();
        root = (Element) doc.createElement("div");
        root.setId("app-root");
        doc.appendChild((com.nishiyu.enderframelib.core.dom.node.HtmlNode) root);
    }

    @Test
    void testBasicElementCreationAndMount() {
        var vdom = div(cls("card", "featured"), id("c1"),
                header(h1("Title")),
                p(cls("content"), "Hello, world!"),
                button(type("button"), "Click")
        );

        mount(root, vdom);

        Element card = byId(root, "c1");
        assertNotNull(card);
        assertEquals("card featured", card.getClassName());
        assertEquals("div", card.getTagName().toLowerCase());

        Element title = $("h1", card);
        assertNotNull(title);
        assertEquals("Title", title.getTextContent());

        Element p = $("p.content", card);
        assertNotNull(p);
        assertEquals("Hello, world!", p.getTextContent());

        Element btn = $("button", card);
        assertNotNull(btn);
        assertEquals("button", btn.getAttribute("type"));
        assertEquals("Click", btn.getTextContent());
    }

    @Test
    void testRefCapture() {
        Ref<Element> inputRef = Ref.create();
        Ref<Element> spanRef = Ref.create();

        var form = div(
                input(ref(inputRef), type("text"), placeholder("Enter name")),
                span(ref(spanRef), cls("badge"), "OK")
        );

        assertFalse(inputRef.isPresent());
        mount(root, form);

        assertTrue(inputRef.isPresent());
        assertTrue(spanRef.isPresent());
        assertEquals("input", inputRef.get().getTagName().toLowerCase());
        assertEquals("Enter name", inputRef.get().getAttribute("placeholder"));
        assertEquals("span", spanRef.get().getTagName().toLowerCase());
        assertEquals("OK", spanRef.get().getTextContent());
    }

    @Test
    void testStateAndBindText() {
        var count = State.of(42);
        Ref<Element> countRef = Ref.create();

        var view = div(
                span(ref(countRef), count)
        );

        mount(root, view);
        assertEquals("42", countRef.get().getTextContent());

        count.set(100);
        assertEquals("100", countRef.get().getTextContent());

        count.update(c -> c + 5);
        assertEquals("105", countRef.get().getTextContent());
    }

    @Test
    void testStateOnChange() {
        var state = State.of(1);
        AtomicInteger runs = new AtomicInteger();

        var listener = state.onChange(v -> runs.incrementAndGet());
        assertEquals(0, runs.get());

        state.set(2);
        assertEquals(1, runs.get());

        state.set(2); // Same value, should not fire
        assertEquals(1, runs.get());

        state.set(3);
        assertEquals(2, runs.get());

        state.removeListener(listener);
        state.set(4);
        assertEquals(2, runs.get());
    }

    @Test
    void testClassList() {
        Ref<Element> badgeRef = Ref.create();
        mount(root, span(ref(badgeRef), cls("badge")));

        Element badge = badgeRef.get();
        assertEquals("badge", badge.getClassName());

        classList(badge).toggle("low", true);
        assertEquals("badge low", badge.getClassName());

        classList(badge).toggle("low", false);
        assertEquals("badge", badge.getClassName());

        classList(badge).add("active", "pulse");
        assertTrue(classList(badge).contains("active"));
        assertTrue(classList(badge).contains("pulse"));

        classList(badge).remove("pulse");
        assertFalse(classList(badge).contains("pulse"));
        assertTrue(classList(badge).contains("active"));
    }

    @Test
    void testQuerySelector() {
        mount(root,
                div(id("header-id"), cls("header"),
                        span(id("clock-id"), "12:00:00")
                )
        );

        Element header = $("#header-id", root);
        assertNotNull(header);
        assertEquals("header", header.getClassName());

        Element clock = $("#clock-id", root);
        assertNotNull(clock);
        assertEquals("12:00:00", clock.getTextContent());

        Element clockByClass = $("span", header);
        assertNotNull(clockByClass);
        assertEquals("clock-id", clockByClass.getId());
    }

    @Test
    void testRenderReplacesChildren() {
        mount(root, p("Initial"));
        assertEquals(1, root.getChildElementCount());
        assertEquals("Initial", root.getFirstElementChild().getTextContent());

        render(root, h1("Replaced"), p("Second"));
        assertEquals(2, root.getChildElementCount());
        assertEquals("Replaced", root.getFirstElementChild().getTextContent());
    }

    @Test
    void testListUnpacking() {
        List<String> items = List.of("Apple", "Banana", "Cherry");

        var listEl = ul(
                items.stream().map(it -> li(it)).toList()
        );

        mount(root, listEl);

        Element ul = $("ul", root);
        assertNotNull(ul);
        assertEquals(3, ul.getChildElementCount());
        assertEquals("Apple", ul.getFirstElementChild().getTextContent());
    }

    @Test
    void testStateMapWhenAndDerive() {
        var price = State.of(10);
        var qty = State.of(2);
        var total = State.derive(() -> price.get() * qty.get(), price, qty);
        var label = total.map(t -> "$" + t);
        var expensive = total.map(t -> t >= 50);

        Ref<Element> priceRef = Ref.create();
        Ref<Element> badgeRef = Ref.create();

        mount(root, div(
                span(ref(priceRef), label),
                span(ref(badgeRef), className("badge", when("high", expensive)), expensive.when("VIP", "STD"))
        ));

        assertEquals("$20", priceRef.get().getTextContent());
        assertEquals("badge", badgeRef.get().getClassName());
        assertEquals("STD", badgeRef.get().getTextContent());

        price.set(30);
        assertEquals("$60", priceRef.get().getTextContent());
        assertTrue(classList(badgeRef.get()).contains("high"));
        assertEquals("VIP", badgeRef.get().getTextContent());

        qty.set(1);
        assertEquals("$30", priceRef.get().getTextContent());
        assertFalse(classList(badgeRef.get()).contains("high"));
        assertEquals("STD", badgeRef.get().getTextContent());
    }

    @Test
    void testReactiveStyleShowAndClassName() {
        var size = State.of(80);
        var visible = State.of(true);
        var flashed = State.of(false);

        Ref<Element> boxRef = Ref.create();
        mount(root, div(
                ref(boxRef),
                className("card", when("flash", flashed)),
                show(visible),
                style(java.util.Map.of(
                        "width", size.map(s -> s + "px"),
                        "borderRadius", "12px"
                ))
        ));

        Element box = boxRef.get();
        assertEquals("card", box.getClassName());
        assertEquals("80px", box.getStyle().getPropertyValue("width"));
        assertEquals("12px", box.getStyle().getPropertyValue("border-radius"));
        assertEquals("", box.getStyle().getPropertyValue("display"));

        flashed.set(true);
        visible.set(false);
        size.set(120);

        assertTrue(classList(box).contains("flash"));
        assertEquals("none", box.getStyle().getPropertyValue("display"));
        assertEquals("120px", box.getStyle().getPropertyValue("width"));

        visible.set(true);
        assertEquals("", box.getStyle().getPropertyValue("display"));
    }

    @Test
    void testFragmentConditionalAndMapHelpers() {
        var showExtra = State.of(false);

        render(root,
                fragment(
                        h1("Items"),
                        ul(map(3, i -> li("Row " + i))),
                        when(true, p("Always")),
                        when(false, p("Never"))
                )
        );

        assertEquals(3, root.getChildElementCount());
        assertEquals(3, $("ul", root).getChildElementCount());
        assertEquals("Always", $("p", root).getTextContent());

        mount(root, span(hidden(showExtra), "Extra"));
        assertEquals(4, root.getChildElementCount());
        assertEquals("", $("span", root).getStyle().getPropertyValue("display"));

        showExtra.set(true);
        assertEquals("none", $("span", root).getStyle().getPropertyValue("display"));

        render(root);
        assertEquals(0, root.getChildElementCount());
    }

    @Test
    void testWatchEachAndEventDispatch() {
        var state = State.of("initial");
        var items = State.of(List.of("A", "B"));
        List<String> observed = new ArrayList<>();

        render(root,
                div(
                        button(id("trigger"), onClick(e -> {
                            observed.add("clicked");
                            state.set("updated");
                            items.set(List.of("A", "B", "C"));
                        })),
                        watch(state, s -> span(className("watched"), s)),
                        div(id("chips"), each(items, item -> span(className("chip"), item)))
                )
        );

        assertEquals("initial", $(".watched", root).getTextContent());
        assertEquals(2, $$(root, ".chip").size());

        $("#trigger", root).dispatchEvent(new com.nishiyu.enderframelib.core.event.CoreEvent("click", true, true));

        assertEquals(List.of("clicked"), observed);
        assertEquals("updated", $(".watched", root).getTextContent());
        assertEquals(3, $$(root, ".chip").size());
    }
}
