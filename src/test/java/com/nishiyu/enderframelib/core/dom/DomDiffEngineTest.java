package com.nishiyu.enderframelib.core.dom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DomDiffEngineTest {

    /** 子要素を "tag#id" の並びで表した文字列。ツリー形状の比較用 */
    private static String shape(HtmlNode parent) {
        StringBuilder sb = new StringBuilder();
        for (HtmlNode c : parent.children) {
            if (!sb.isEmpty()) sb.append(",");
            sb.append(c.tag).append("#").append(c.getId());
        }
        return sb.toString();
    }

    private static HtmlNode el(String tag, String id) {
        HtmlNode n = new HtmlNode(tag);
        n.setId(id);
        return n;
    }

    private static HtmlNode tree(String rootId, HtmlNode... children) {
        HtmlNode root = el("div", rootId);
        for (HtmlNode c : children) root.appendChild(c);
        return root;
    }

    @Test
    public void testNoChangeReportsNoPatch() {
        HtmlNode real = tree("root", el("span", "a"), el("span", "b"));
        HtmlNode virtual = tree("root", el("span", "a"), el("span", "b"));

        assertFalse(DomDiffEngine.diffAndPatch(real, virtual));
        assertEquals("span#a,span#b", shape(real));
    }

    @Test
    public void testAppend() {
        HtmlNode real = tree("root", el("span", "a"), el("span", "b"));
        HtmlNode keptA = real.children.get(0);
        HtmlNode virtual = tree("root", el("span", "a"), el("span", "b"), el("span", "c"));

        assertTrue(DomDiffEngine.diffAndPatch(real, virtual));
        assertEquals("span#a,span#b,span#c", shape(real));
        assertSame(keptA, real.children.get(0), "既存ノードは再利用される");
    }

    @Test
    public void testTruncateClearsParentPointer() {
        HtmlNode real = tree("root", el("span", "a"), el("span", "b"), el("span", "c"));
        HtmlNode removed = real.children.get(2);
        HtmlNode virtual = tree("root", el("span", "a"), el("span", "b"));

        assertTrue(DomDiffEngine.diffAndPatch(real, virtual));
        assertEquals("span#a,span#b", shape(real));
        assertNull(removed.parent, "C-4: 削除されたノードの parent はクリアされる");
    }

    @Test
    public void testMiddleInsertReusesTrailingNodeByKey() {
        HtmlNode real = tree("root", el("span", "a"), el("span", "c"));
        HtmlNode keptC = real.children.get(1);
        HtmlNode virtual = tree("root", el("span", "a"), el("span", "b"), el("span", "c"));

        assertTrue(DomDiffEngine.diffAndPatch(real, virtual));
        assertEquals("span#a,span#b,span#c", shape(real));
        assertSame(keptC, real.children.get(2), "キー照合により c は作り直されず移動する");
    }

    @Test
    public void testMiddleDeleteReusesTrailingNodeByKey() {
        HtmlNode real = tree("root", el("span", "a"), el("span", "b"), el("span", "c"));
        HtmlNode keptC = real.children.get(2);
        HtmlNode virtual = tree("root", el("span", "a"), el("span", "c"));

        assertTrue(DomDiffEngine.diffAndPatch(real, virtual));
        assertEquals("span#a,span#c", shape(real));
        assertSame(keptC, real.children.get(1), "キー照合により c は作り直されず前に詰められる");
    }

    @Test
    public void testReorderKeepsNodeIdentity() {
        HtmlNode real = tree("root", el("span", "a"), el("span", "b"), el("span", "c"));
        HtmlNode keptA = real.children.get(0);
        HtmlNode keptC = real.children.get(2);
        HtmlNode virtual = tree("root", el("span", "c"), el("span", "b"), el("span", "a"));

        assertTrue(DomDiffEngine.diffAndPatch(real, virtual));
        assertEquals("span#c,span#b,span#a", shape(real));
        assertSame(keptC, real.children.get(0));
        assertSame(keptA, real.children.get(2));
    }

    @Test
    public void testTagChangeReplacesNode() {
        HtmlNode real = tree("root", el("div", "x"));
        HtmlNode old = real.children.get(0);
        HtmlNode virtual = tree("root", el("span", "x"));

        assertTrue(DomDiffEngine.diffAndPatch(real, virtual));
        assertEquals("span#x", shape(real));
        assertEquals(1, real.children.size(), "置き換えであって重複ではない");
        org.junit.jupiter.api.Assertions.assertNotSame(old, real.children.get(0));
    }

    @Test
    public void testAttributesAreAddedUpdatedAndRemoved() {
        HtmlNode real = tree("root", el("span", "a"));
        real.children.get(0).attrs.put("class", "old");
        real.children.get(0).attrs.put("data-gone", "1");

        HtmlNode virtual = tree("root", el("span", "a"));
        virtual.children.get(0).attrs.put("class", "new");
        virtual.children.get(0).attrs.put("data-added", "2");

        assertTrue(DomDiffEngine.diffAndPatch(real, virtual));

        HtmlNode patched = real.children.get(0);
        assertEquals("new", patched.attrs.get("class"));
        assertEquals("2", patched.attrs.get("data-added"));
        assertFalse(patched.attrs.containsKey("data-gone"), "仮想側に無い属性は削除される");
    }

    @Test
    public void testTextContentIsPatched() {
        HtmlNode real = el("p", "p1");
        HtmlNode realText = HtmlNode.createTextNode("before");
        realText.setId("t1");
        real.appendChild(realText);

        HtmlNode virtual = el("p", "p1");
        HtmlNode virtualText = HtmlNode.createTextNode("after");
        virtualText.setId("t1");
        virtual.appendChild(virtualText);

        assertTrue(DomDiffEngine.diffAndPatch(real, virtual));
        assertEquals("after", real.getTextContent());
        assertSame(realText, real.children.get(0), "テキストノードは再利用される");
    }

    @Test
    public void testNestedChildrenArePatchedRecursively() {
        HtmlNode real = tree("root", el("ul", "list"));
        real.children.get(0).appendChild(el("li", "i1"));

        HtmlNode virtual = tree("root", el("ul", "list"));
        virtual.children.get(0).appendChild(el("li", "i1"));
        virtual.children.get(0).appendChild(el("li", "i2"));

        assertTrue(DomDiffEngine.diffAndPatch(real, virtual));
        assertEquals("li#i1,li#i2", shape(real.children.get(0)));
    }

    @Test
    public void testNullArgumentsAreIgnored() {
        assertFalse(DomDiffEngine.diffAndPatch(null, el("div", "a")));
        assertFalse(DomDiffEngine.diffAndPatch(el("div", "a"), null));
    }
}
