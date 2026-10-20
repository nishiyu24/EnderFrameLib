package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R1: 対話状態とスクロール位置は節オブジェクトをキーにし、id の引き当ては索引だけで行う。 */
public class NodeKeyedStateTest {

    @Test
    public void getElementByIdReturnsNullForUnknownIdInsteadOfCreatingElement() {
        HtmlNode root = HtmlParser.parse("<div id=\"a\"><span id=\"b\"></span></div>");

        assertSame(root.children.get(0), root.getElementById("a"));
        assertNull(root.getElementById("missing"));
        assertNull(root.getElementById(""));
        assertNull(root.getElementById(null));
    }

    @Test
    public void getElementByIdFollowsTreeMutation() {
        HtmlNode root = HtmlParser.parse("<div id=\"a\"></div>");
        HtmlNode added = HtmlParser.parseFragment("<p id=\"late\"></p>", "div").children.get(0);
        HtmlNode container = root.children.get(0);
        container.appendChild(added);
        assertSame(added, root.getElementById("late"));

        container.removeChild(added);
        assertNull(root.getElementById("late"));
    }

    @Test
    public void interactionStateKeepsSameIdNodesApart() {
        HtmlNode first = HtmlParser.parse("<div id=\"dup\"></div>").children.get(0);
        HtmlNode second = HtmlParser.parse("<div id=\"dup\"></div>").children.get(0);
        InteractionState state = new InteractionState();

        assertTrue(state.setHovered(first, true));

        assertTrue(state.get(first).isHovered);
        assertNull(state.get(second));
        assertNotSame(state.getOrCreate(first), state.getOrCreate(second));
    }

    @Test
    public void scrollStateKeepsSameIdNodesApart() {
        HtmlNode first = HtmlParser.parse("<div id=\"dup\"></div>").children.get(0);
        HtmlNode second = HtmlParser.parse("<div id=\"dup\"></div>").children.get(0);
        LayoutContext.ScrollState scroll = new LayoutContext.ScrollState();

        scroll.setScroll(first, 3, 7);

        assertEquals(3, scroll.getScrollX(first));
        assertEquals(7, scroll.getScrollY(first));
        assertEquals(0, scroll.getScrollX(second));
        assertEquals(0, scroll.getScrollY(second));
    }
}
