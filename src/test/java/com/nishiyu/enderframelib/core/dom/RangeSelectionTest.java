package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.api.dom.DOMException;
import com.nishiyu.enderframelib.api.dom.DOMNodes.DocumentFragment;
import com.nishiyu.enderframelib.api.dom.DOMNodes.Text;
import com.nishiyu.enderframelib.api.dom.Range;
import com.nishiyu.enderframelib.api.dom.Selection;
import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DOM Standard §5 の Range と Selection API。
 *
 * 文書:  body > p#a ["Hello ", b["big"], " world"]  + p#b ["Second"]
 */
public class RangeSelectionTest {

    private HtmlDocument doc;
    private HtmlElement body, a, b, bold;
    private Text hello, big, world, second;

    @BeforeEach
    void build() {
        doc = new HtmlDocument();
        body = (HtmlElement) doc.createElement("body");
        a = (HtmlElement) doc.createElement("p");
        a.setId("a");
        b = (HtmlElement) doc.createElement("p");
        b.setId("b");
        bold = (HtmlElement) doc.createElement("b");
        hello = doc.createTextNode("Hello ");
        big = doc.createTextNode("big");
        world = doc.createTextNode(" world");
        second = doc.createTextNode("Second");
        doc.appendChild(body);
        body.appendChild(a);
        body.appendChild(b);
        a.appendChild(hello);
        a.appendChild(bold);
        bold.appendChild(big);
        a.appendChild(world);
        b.appendChild(second);
    }

    // ---- 境界点

    @Test
    void newRangeIsCollapsedAtTheDocumentStart() {
        Range r = doc.createRange();
        assertSame(doc, r.getStartContainer());
        assertEquals(0, r.getStartOffset());
        assertTrue(r.isCollapsed());
        assertSame(doc, r.getCommonAncestorContainer());
    }

    @Test
    void settersKeepStartBeforeEnd() {
        Range r = doc.createRange();
        r.setStart(hello, 2);
        r.setEnd(world, 3);
        assertEquals(a, r.getCommonAncestorContainer());
        assertFalse(r.isCollapsed());

        // 終点より後ろに始点を置くと、終点も同じ位置へ動く
        r.setStart(second, 1);
        assertSame(second, r.getEndContainer());
        assertEquals(1, r.getEndOffset());
        assertTrue(r.isCollapsed());

        // 始点より前に終点を置くと、始点が動く
        r.setEnd(hello, 1);
        assertSame(hello, r.getStartContainer());
        assertEquals(1, r.getStartOffset());
    }

    @Test
    void offsetsAreValidated() {
        Range r = doc.createRange();
        assertThrows(DOMException.class, () -> r.setStart(hello, 7));
        assertThrows(DOMException.class, () -> r.setEnd(a, 4));
        assertThrows(DOMException.class, () -> r.setStart(hello, -1));
        r.setStart(a, 3); // 子は 3 つ
        assertEquals(3, r.getStartOffset());
    }

    @Test
    void selectNodeAndSelectNodeContents() {
        Range r = doc.createRange();
        r.selectNode(bold);
        assertSame(a, r.getStartContainer());
        assertEquals(1, r.getStartOffset());
        assertEquals(2, r.getEndOffset());
        assertEquals("big", r.toString());

        r.selectNodeContents(a);
        assertEquals(0, r.getStartOffset());
        assertEquals(3, r.getEndOffset());
        assertEquals("Hello big world", r.toString());

        assertThrows(DOMException.class, () -> doc.createRange().selectNode(doc));
    }

    @Test
    void collapseGoesToEitherEnd() {
        Range r = doc.createRange();
        r.setStart(hello, 1);
        r.setEnd(world, 2);
        r.collapse(true);
        assertSame(hello, r.getEndContainer());
        assertEquals(1, r.getEndOffset());
        r.setEnd(world, 2);
        r.collapse(false);
        assertSame(world, r.getStartContainer());
        assertEquals(2, r.getStartOffset());
    }

    @Test
    void compareBoundaryPointsAndPoints() {
        Range r1 = doc.createRange();
        r1.setStart(hello, 1);
        r1.setEnd(world, 3);
        Range r2 = doc.createRange();
        r2.setStart(big, 0);
        r2.setEnd(second, 2);

        assertEquals(-1, r1.compareBoundaryPoints(Range.START_TO_START, r2));
        assertEquals(-1, r1.compareBoundaryPoints(Range.END_TO_END, r2));
        assertEquals(1, r1.compareBoundaryPoints(Range.START_TO_END, r2));
        // END_TO_START: この範囲の始点 (big,0) を相手の終点 (world,3) と比べる
        assertEquals(-1, r2.compareBoundaryPoints(Range.END_TO_START, r1));

        assertEquals(0, r1.comparePoint(big, 1));
        assertEquals(-1, r1.comparePoint(hello, 0));
        assertEquals(1, r1.comparePoint(second, 0));
        assertTrue(r1.isPointInRange(bold, 1));
        assertFalse(r1.isPointInRange(second, 0));
        assertTrue(r1.intersectsNode(bold));
        assertFalse(r1.intersectsNode(b));
    }

    // ---- 内容

    @Test
    void toStringAcrossNodes() {
        Range r = doc.createRange();
        r.setStart(hello, 3);
        r.setEnd(second, 3);
        assertEquals("lo big worldSec", r.toString());

        Range same = doc.createRange();
        same.setStart(hello, 1);
        same.setEnd(hello, 4);
        assertEquals("ell", same.toString());
    }

    @Test
    void cloneContentsLeavesTheDocumentAlone() {
        Range r = doc.createRange();
        r.setStart(hello, 3);
        r.setEnd(world, 3);
        DocumentFragment f = r.cloneContents();
        assertEquals("lo big wo", f.getTextContent());
        assertEquals(3, f.getChildNodes().getLength());
        assertEquals("b", f.getChildNodes().item(1).getNodeName().toLowerCase());
        assertEquals("Hello big world", a.getTextContent(), "複製なので元は変わらない");
    }

    @Test
    void extractContentsMovesNodesAndCollapsesTheRange() {
        Range r = doc.createRange();
        r.setStart(hello, 3);
        r.setEnd(world, 3);
        DocumentFragment f = r.extractContents();
        assertEquals("lo big wo", f.getTextContent());
        assertEquals("Helrld", a.getTextContent());
        assertTrue(r.isCollapsed());
        assertEquals("Hel", hello.getData());
        assertEquals("rld", world.getData());
    }

    @Test
    void deleteContentsWithinOneTextNode() {
        Range r = doc.createRange();
        r.setStart(hello, 1);
        r.setEnd(hello, 5);
        r.deleteContents();
        assertEquals("H ", hello.getData());
        assertTrue(r.isCollapsed());
        assertEquals(1, r.getStartOffset());
    }

    @Test
    void deleteContentsAcrossElements() {
        Range r = doc.createRange();
        r.setStart(big, 1);
        r.setEnd(second, 3);
        r.deleteContents();
        assertEquals("b", big.getData());
        assertEquals("ond", second.getData());
        assertEquals("Hello b", a.getTextContent());
    }

    @Test
    void insertNodeSplitsATextNode() {
        Range r = doc.createRange();
        r.setStart(hello, 3);
        r.setEnd(hello, 3);
        HtmlElement i = (HtmlElement) doc.createElement("i");
        r.insertNode(i);
        assertEquals("Hel", hello.getData());
        assertEquals("i", a.getChildNodes().item(1).getNodeName().toLowerCase());
        assertEquals("lo ", ((Text) a.getChildNodes().item(2)).getData());
        // 折りたたまれた範囲は挿入した節を含むように広がる
        assertSame(i, a.getChildNodes().item(1));
        assertFalse(r.isCollapsed());
    }

    @Test
    void surroundContentsWrapsTheRange() {
        Range r = doc.createRange();
        r.setStart(hello, 0);
        r.setEnd(hello, 5);
        HtmlElement em = (HtmlElement) doc.createElement("em");
        r.surroundContents(em);
        assertSame(a, em.getParentNode());
        assertEquals("Hello", em.getTextContent());
        assertEquals("Hello big world", a.getTextContent());
        assertSame(a, r.getStartContainer());
    }

    @Test
    void cloneRangeIsIndependent() {
        Range r = doc.createRange();
        r.setStart(hello, 1);
        r.setEnd(world, 2);
        Range c = r.cloneRange();
        r.collapse(true);
        assertFalse(c.isCollapsed());
        assertEquals(2, c.getEndOffset());
    }

    // ---- 生きた範囲

    @Test
    void rangeFollowsTextEdits() {
        Range r = doc.createRange();
        r.setStart(hello, 2);
        r.setEnd(hello, 5);
        hello.setData("Hi");
        // 置き換えた文字より後ろの境界は、置き換えの先頭に寄る
        assertEquals(0, r.getStartOffset());
        assertEquals(0, r.getEndOffset());
    }

    @Test
    void rangeFollowsSplitText() {
        Range r = doc.createRange();
        r.setStart(hello, 1);
        r.setEnd(hello, 5);
        Text tail = hello.splitText(3);
        assertSame(hello, r.getStartContainer());
        assertEquals(1, r.getStartOffset());
        assertSame(tail, r.getEndContainer());
        assertEquals(2, r.getEndOffset());
    }

    @Test
    void rangeFollowsInsertAndRemoveOfSiblings() {
        Range r = doc.createRange();
        r.setStart(a, 1);
        r.setEnd(a, 3);
        a.insertBefore(doc.createTextNode(">> "), hello);
        assertEquals(2, r.getStartOffset());
        assertEquals(4, r.getEndOffset());

        a.removeChild(hello);
        assertEquals(1, r.getStartOffset());
        assertEquals(3, r.getEndOffset());
    }

    @Test
    void removingTheNodeThatHoldsABoundaryMovesItToTheParent() {
        Range r = doc.createRange();
        r.setStart(big, 1);
        r.setEnd(big, 2);
        int index = 1;
        a.removeChild(bold);
        assertSame(a, r.getStartContainer());
        assertEquals(index, r.getStartOffset());
        assertSame(a, r.getEndContainer());
        assertEquals(index, r.getEndOffset());
    }

    // ---- Selection

    @Test
    void selectionStartsEmpty() {
        Selection s = doc.getSelection();
        assertSame(s, doc.getSelection(), "文書ごとに 1 つ");
        assertEquals(0, s.getRangeCount());
        assertEquals("None", s.getType());
        assertNull(s.getAnchorNode());
        assertTrue(s.isCollapsed());
        assertEquals("", s.toString());
        assertThrows(DOMException.class, () -> s.getRangeAt(0));
    }

    @Test
    void collapseMakesACaretAndExtendMakesARange() {
        Selection s = doc.getSelection();
        s.collapse(hello, 2);
        assertEquals("Caret", s.getType());
        assertEquals(1, s.getRangeCount());
        assertSame(hello, s.getAnchorNode());
        assertEquals(2, s.getFocusOffset());

        s.extend(world, 3);
        assertEquals("Range", s.getType());
        assertEquals("forward", s.getDirection());
        assertEquals("llo big wo", s.toString());
        // anchor は動かない
        assertSame(hello, s.getAnchorNode());
        assertEquals(2, s.getAnchorOffset());
        assertSame(world, s.getFocusNode());
    }

    @Test
    void backwardSelectionKeepsAnchorAtTheEnd() {
        Selection s = doc.getSelection();
        s.setBaseAndExtent(world, 3, hello, 2);
        assertEquals("backward", s.getDirection());
        assertSame(world, s.getAnchorNode());
        assertEquals(3, s.getAnchorOffset());
        assertSame(hello, s.getFocusNode());
        assertEquals("llo big wo", s.toString());
        Range r = s.getRangeAt(0);
        assertSame(hello, r.getStartContainer());
        assertSame(world, r.getEndContainer());
    }

    @Test
    void selectAllChildrenAndCollapseToEnds() {
        Selection s = doc.getSelection();
        s.selectAllChildren(a);
        assertEquals("Hello big world", s.toString());
        s.collapseToEnd();
        assertEquals("Caret", s.getType());
        assertSame(a, s.getFocusNode());
        assertEquals(3, s.getFocusOffset());
        s.selectAllChildren(b);
        s.collapseToStart();
        assertEquals(0, s.getFocusOffset());
    }

    @Test
    void addRangeOnlyWhenEmptyAndRemoveAllRangesClears() {
        Selection s = doc.getSelection();
        Range r1 = doc.createRange();
        r1.selectNodeContents(a);
        s.addRange(r1);
        assertEquals(1, s.getRangeCount());
        assertSame(r1, s.getRangeAt(0));

        Range r2 = doc.createRange();
        r2.selectNodeContents(b);
        s.addRange(r2); // 範囲を持っているときは無視される（Chrome と同じ）
        assertSame(r1, s.getRangeAt(0));

        s.removeAllRanges();
        assertEquals(0, s.getRangeCount());
        assertEquals("None", s.getType());
    }

    @Test
    void deleteFromDocumentAndContainsNode() {
        Selection s = doc.getSelection();
        s.setBaseAndExtent(hello, 0, world, 6);
        assertTrue(s.containsNode(bold, false));
        assertFalse(s.containsNode(b, false));
        assertTrue(s.containsNode(a, true));
        s.deleteFromDocument();
        assertEquals("", a.getTextContent());
        assertTrue(s.isCollapsed());
    }

    @Test
    void selectionTracksDomMutations() {
        Selection s = doc.getSelection();
        s.setBaseAndExtent(hello, 1, hello, 4);
        hello.splitText(2);
        assertEquals("ell", s.toString(), "分割しても選択範囲の文字列は変わらない");
        a.removeChild(world);
        assertEquals("ell", s.toString());
    }

    @Test
    void selectionChangeEventIsDispatched() {
        List<String> events = new ArrayList<>();
        doc.addEventListener("selectionchange", e -> events.add(e.getType()));
        Selection s = doc.getSelection();
        s.collapse(hello, 1);
        s.extend(hello, 3);
        s.removeAllRanges();
        s.removeAllRanges(); // 変わらないので送らない
        assertEquals(List.of("selectionchange", "selectionchange", "selectionchange"), events);
    }

    @Test
    void nodesOutsideTheDocumentCannotBeSelected() {
        Selection s = doc.getSelection();
        HtmlElement orphan = (HtmlElement) doc.createElement("div");
        Text t = doc.createTextNode("x");
        orphan.appendChild(t);
        s.collapse(t, 0);
        assertEquals(0, s.getRangeCount());
        assertNull(((HtmlNode) orphan).getParentNode());
    }
}
