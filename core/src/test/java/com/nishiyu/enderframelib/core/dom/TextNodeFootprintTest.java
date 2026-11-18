package com.nishiyu.enderframelib.core.dom;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.HtmlText;
import org.junit.jupiter.api.Test;

/** A16②: テキストの節は属性の表・子の並びを共有の空のものにし、書くときだけ作る。 */
class TextNodeFootprintTest {

    @Test
    void textNodesShareEmptyTablesUntilWritten() {
        HtmlText a = new HtmlText("x"), b = new HtmlText("y");
        assertSame(a.attrs, b.attrs, "属性を持たない文字の節は空の表を共有する");
        assertSame(a.children, b.children);
        assertTrue(a.children.isEmpty());
        assertNull(a.attrs.remove("id"), "空の表からの削除は何もしない");

        a.setId("t1");
        assertEquals("t1", a.attrs.get("id"));
        assertEquals("t1", a.key());
        assertNotSame(a.attrs, b.attrs, "書いた節だけが自分の表を持つ");
        assertTrue(b.attrs.isEmpty());

        HtmlNode copy = a.cloneNode(true) instanceof HtmlNode n ? n : null;
        assertNotNull(copy);
        assertEquals("t1", copy.attrs.get("id"), "複製は属性を写す");
    }

    @Test
    void elementsStillOwnTheirTablesAndTextChildrenWork() {
        HtmlElement div = new HtmlElement("div");
        div.setAttribute("class", "a");
        div.updateTextContent("hello");
        assertEquals(1, div.children.size());
        assertEquals("hello", div.children.get(0).text);
        assertThrows(Exception.class, () -> div.children.get(0).appendChild(new HtmlText("z")),
                "文字の節は子を持てない");
    }

    @Test
    void shortWhitespaceTextIsShared() {
        assertSame(new HtmlText(new String("\n    ")).text, new HtmlText(new String("\n    ")).text);
        String word = new String("abc");
        assertSame(word, new HtmlText(word).text, "空白以外はそのまま持つ");
    }
}
