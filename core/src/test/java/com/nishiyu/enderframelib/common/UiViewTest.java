package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.paint.UIHitResult;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Minecraft なし（フック未上書き）の {@link UiView} だけで、文書・状態・無効化が動くことの確認。 */
class UiViewTest {

    private static final String HTML = "<html><head><style>"
            + "#a{position:absolute;left:10px;top:10px;width:100px;height:50px;background:#f00}"
            + "#a:hover{background:#00f}"
            + "#box{position:absolute;left:0;top:100px;width:200px;height:60px;overflow:auto}"
            + "#tall{height:400px}"
            + "</style></head><body><div id=\"a\"></div><div id=\"box\"><div id=\"tall\"></div></div></body></html>";

    private static UiView load() {
        UiView view = new UiView("t");
        view.loadLocalHtml(HTML, 320, 240);
        return view;
    }

    private static int[] colors(UiView view) {
        return view.renderCache().stream().mapToInt(i -> i.bgColor() ^ i.color()).toArray();
    }

    @Test
    void loadsDocumentAndBuildsDisplayList() {
        try (UiView view = load()) {
            assertNotNull(view.document());
            assertNotNull(view.layoutRoot());
            assertEquals(320, view.rootW());
            assertEquals(240, view.rootH());
            assertFalse(view.elements().isEmpty());
            assertNotNull(view.elementById("a"));
            assertNotNull(view.findNode("a"));
        }
    }

    @Test
    void hoverRestylesThroughTheInvalidationQueue() {
        try (UiView view = load()) {
            int[] beforeColors = colors(view);
            view.setHoveredElement("a");
            assertEquals(UiView.InvalidationLevel.RESTYLE, view.invalidationLevel());
            view.flushInvalidation();
            assertEquals(UiView.InvalidationLevel.NONE, view.invalidationLevel());
            assertFalse(java.util.Arrays.equals(beforeColors, colors(view)), "hover must change a painted color");
            assertEquals("a", view.interactionState().hoveredNode().getId());
        }
    }

    @Test
    void hitTestFindsTheElementUnderThePoint() {
        try (UiView view = load()) {
            UIHitResult hit = view.hitTest(20, 20);
            assertNotNull(hit);
            assertEquals("a", hit.element().baseId());
        }
    }

    @Test
    void wheelScrollsAScrollContainerAndRepositions() {
        try (UiView view = load()) {
            HtmlNode box = view.findNode("box");
            assertEquals(0f, view.scrollState().getScrollY(box));
            assertTrue(view.handleScrollLocally(10, 110, 0, -1));
            assertTrue(view.scrollState().getScrollY(box) > 0f);
            assertEquals(UiView.InvalidationLevel.REPOSITION, view.invalidationLevel());
            view.flushInvalidation();
            assertEquals(UiView.InvalidationLevel.NONE, view.invalidationLevel());
        }
    }

    @Test
    void resizeRelayoutsToTheNewViewport() {
        try (UiView view = load()) {
            view.recomputeLayout(400, 300);
            assertEquals(400, view.rootW());
            assertEquals(300, view.rootH());
        }
    }

    @Test
    void domChangesRequestRelayoutAndHooksAreCalled() {
        List<String> calls = new ArrayList<>();
        try (UiView view = new UiView("hooks") {
            @Override
            protected void invalidated() {
                calls.add("invalidated");
            }

            @Override
            protected void beforeElements() {
                calls.add("beforeElements");
            }

            @Override
            protected void applyOverrides() {
                calls.add("applyOverrides");
            }
        }) {
            view.loadLocalHtml(HTML, 320, 240);
            assertTrue(calls.contains("applyOverrides"));
            calls.clear();

            view.updateNodeStyleLocally("a", "width", "150px");
            assertTrue(calls.contains("invalidated"));
            view.flushInvalidation();
            assertEquals(150f, view.elementById("a").width(), 0.5f);

            view.elements();
            assertTrue(calls.contains("beforeElements"));
        }
    }

    @Test
    void closeDropsDocumentAndState() {
        UiView view = load();
        view.inputScrollOffsets().put("x", 1);
        view.close();
        assertNull(view.document());
        assertNull(view.layoutRoot());
        assertTrue(view.renderCache().isEmpty());
        assertTrue(view.inputScrollOffsets().isEmpty());
    }
}
