package com.nishiyu.enderframelib.api.ui;

import com.nishiyu.enderframelib.api.dom.Element;
import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.nishiyu.enderframelib.api.ui.React.*;
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
    void testUseRef() {
        Ref<Element> inputRef = useRef();
        Ref<Element> spanRef = useRef();

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
    void testUseStateAndBindText() {
        var count = useState(42);
        Ref<Element> countRef = useRef();

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
    void testUseEffect() {
        var state = useState(1);
        AtomicInteger runs = new AtomicInteger();

        useEffect(() -> {
            runs.incrementAndGet();
        }, state);

        assertEquals(1, runs.get());

        state.set(2);
        assertEquals(2, runs.get());

        state.set(2); // Same value, should not fire
        assertEquals(2, runs.get());

        state.set(3);
        assertEquals(3, runs.get());
    }

    @Test
    void testClassList() {
        Ref<Element> badgeRef = useRef();
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
}
