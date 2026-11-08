package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.api.event.Event;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Minecraft なしの {@link UiView} + {@link UiInput} だけで、ポインタ・クリック・スクロール・キーが動くことの確認。 */
class UiInputTest {

    private static final String HTML = "<html><head><style>"
            + "#a{position:absolute;left:10px;top:10px;width:100px;height:50px;background:#f00}"
            + "#a:hover{background:#00f}"
            + "#cb{position:absolute;left:150px;top:10px;width:20px;height:20px}"
            + "#box{position:absolute;left:0;top:100px;width:200px;height:60px;overflow:auto}"
            + "#tall{height:400px}"
            + "</style></head><body>"
            + "<div id=\"a\" onclick=\"go\"></div><input id=\"cb\" type=\"checkbox\">"
            + "<div id=\"box\"><div id=\"tall\"></div></div></body></html>";

    private static UiView view() {
        UiView v = new UiView("in");
        v.loadLocalHtml(HTML, 320, 240);
        return v;
    }

    private static boolean checked(UiView v, HtmlNode n) {
        var st = v.interactionState().get(n);
        return st != null && Boolean.TRUE.equals(st.tri(com.nishiyu.enderframelib.core.css.model.ElementState.CHECKED));
    }

    @Test
    void hoverSetsInteractionStateAndRestyles() {
        try (UiView v = view()) {
            UiInput in = new UiInput(v);
            assertEquals("a", in.hover(20, 20));
            assertEquals("a", in.hoveredId());
            assertEquals("a", v.interactionState().hoveredNode().getId());
            assertEquals(UiView.InvalidationLevel.RESTYLE, v.invalidationLevel());
            assertEquals("", in.hover(300, 230));
            assertEquals("", in.hoveredId());
        }
    }

    @Test
    void clickRunsTheInlineActionThroughTheHook() {
        List<String> actions = new ArrayList<>();
        try (UiView v = new UiView("in") {
            @Override
            protected void runInlineAction(HtmlNode node, Event event, String action) {
                actions.add(node.getId() + ":" + event.getType() + ":" + action);
            }
        }) {
            v.loadLocalHtml(HTML, 320, 240);
            UiInput in = new UiInput(v);
            in.press(20, 20, 0, UiInput.Host.NONE);
            assertEquals("a", v.activePressedId());
            in.release(20, 20, 0, true, UiInput.ClickSink.NONE);
            assertEquals("", v.activePressedId());
            assertTrue(actions.contains("a:click:go"), actions.toString());
        }
    }

    @Test
    void clickingACheckboxTogglesIt() {
        try (UiView v = view()) {
            UiInput in = new UiInput(v);
            HtmlNode cb = v.findNode("cb");
            assertFalse(checked(v, cb));
            in.press(160, 20, 0, UiInput.Host.NONE);
            in.release(160, 20, 0, true, UiInput.ClickSink.NONE);
            assertTrue(checked(v, cb));
        }
    }

    @Test
    void wheelScrollsThroughTheInput() {
        try (UiView v = view()) {
            UiInput in = new UiInput(v);
            HtmlNode box = v.findNode("box");
            in.scroll(10, 110, 0, -1, false, false, false, UiInput.Host.NONE);
            assertTrue(v.scrollState().getScrollY(box) > 0f);
        }
    }

    @Test
    void mouseEventsReachTheDocumentListeners() {
        try (UiView v = view()) {
            UiInput in = new UiInput(v);
            List<String> seen = new ArrayList<>();
            HtmlNode a = v.findNode("a");
            for (String type : new String[]{"mousedown", "mouseup", "click", "pointermove"}) {
                a.addEventListener(type, e -> seen.add(type));
            }
            in.hover(20, 20);
            in.press(20, 20, 0, UiInput.Host.NONE);
            in.release(20, 20, 0, true, UiInput.ClickSink.NONE);
            assertTrue(seen.containsAll(List.of("mousedown", "mouseup", "click")), seen.toString());
        }
    }

    @Test
    void rangeRatioAndKeyNames() {
        assertEquals(0f, UiInput.rangeRatio(8, 129), 1e-6);
        assertEquals(1f, UiInput.rangeRatio(121, 129), 1e-6);
        assertEquals(0.5f, UiInput.rangeRatio(64.5, 129), 1e-6);
        assertEquals("Enter", UiInput.glfwKeyToDomKey(GLFW.GLFW_KEY_ENTER));
        assertEquals("ArrowLeft", UiInput.glfwKeyToDomCode(GLFW.GLFW_KEY_LEFT));
        assertEquals("deleteWordBackward",
                UiInput.editingInputType(GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_MOD_CONTROL, false));
    }

    @Test
    void closeClearsInputState() {
        UiView v = view();
        UiInput in = new UiInput(v);
        in.press(20, 20, 0, UiInput.Host.NONE);
        assertNotNull(v.inputState().pressedElement);
        v.close();
        assertNull(v.inputState().pressedElement);
    }
}
