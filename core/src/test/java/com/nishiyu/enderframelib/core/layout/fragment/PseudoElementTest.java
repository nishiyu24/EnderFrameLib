package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxKind;
import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2b-1 の疑似要素（計画書 §3.4.2-5）: {@code ::before} / {@code ::after} は DOM に節を作らず、{@link BoxNode#pseudo} の箱として
 * {@link BoxNode#flow} の前後に入る（{@link BoxNode#children} は DOM の子と 1 対 1 のまま）。{@code content} の評価・カウンタ・
 * 増分での使い回しと、全再計算との一致を確かめる。
 */
class PseudoElementTest {

    private static SpikeView view(String css, String body) {
        return new SpikeView("<html><head><style>body{margin:0}" + css + "</style></head><body>" + body + "</body></html>", 600, 400);
    }

    private static BoxNode box(SpikeView v, String id) {
        return find(v.boxRoot, v.byId(id));
    }

    private static BoxNode find(BoxNode b, HtmlNode n) {
        if (b.node == n && b.pseudo == BoxNode.Pseudo.NONE) return b;
        for (int i = 0; i < b.children.size(); i++) {
            BoxNode r = find(b.children.get(i), n);
            if (r != null) return r;
        }
        return null;
    }

    private static String text(BoxNode pseudo) {
        return pseudo.children.get(0).text;
    }

    @Test
    void beforeAndAfterSitAroundTheDomChildrenInFlowOnly() {
        SpikeView v = view("p::before{content:'<'} p::after{content:'>'}", "<p id=p>mid</p>");
        v.frame();
        BoxNode p = box(v, "p");
        assertEquals(1, p.children.size(), "children は DOM の子だけ");
        assertEquals(3, p.flow.size(), "flow は ::before・子・::after");
        assertEquals(BoxNode.Pseudo.BEFORE, p.flow.get(0).pseudo);
        assertEquals(BoxNode.Pseudo.AFTER, p.flow.get(2).pseudo);
        assertEquals("<", text(p.pseudoChild(BoxNode.Pseudo.BEFORE)));
        assertEquals(">", text(p.pseudoChild(BoxNode.Pseudo.AFTER)));
        LayoutFragment f = InlineTextTest.frag(v.layout.root(), "p");
        LineFragment.Piece[] pieces = f.lines[0].pieces();
        assertEquals(List.of("<", "mid", ">"), List.of(pieces[0].string(), pieces[1].string(), pieces[2].string()));
    }

    @Test
    void noPseudoMeansFlowIsChildren() {
        SpikeView v = view("", "<p id=p>mid</p>");
        v.frame();
        BoxNode p = box(v, "p");
        assertSame(p.children, p.flow, "疑似要素が無ければ追加の確保なし");
    }

    @Test
    void contentEvaluatesStringsAndAttr() {
        SpikeView v = view("a::after{content:' (' attr(href) ')'} q::before{content:open-quote}", "<a id=a href=x.html>link</a><q id=q>q</q>");
        v.frame();
        assertEquals(" (x.html)", text(box(v, "a").pseudoChild(BoxNode.Pseudo.AFTER)));
        assertEquals("“", text(box(v, "q").pseudoChild(BoxNode.Pseudo.BEFORE)), "open-quote は深さ 0 の開きの引用符（QuotesTest）");
    }

    @Test
    void noneNormalAndReplacedElementsMakeNoPseudo() {
        SpikeView v = view("p::before{content:none} div::before{content:normal} img::before{content:'x'} span::before{content:'s';display:none}",
                "<p id=p>a</p><div id=d>b</div><img id=i><span id=s>c</span>");
        v.frame();
        for (String id : new String[]{"p", "d", "i", "s"}) assertNull(box(v, id).pseudoChild(BoxNode.Pseudo.BEFORE), id);
    }

    @Test
    void blockPseudoIsABlockBox() {
        SpikeView v = view("div::before{content:'head';display:block;height:20px}", "<div id=d>body</div>");
        LayoutFragment root = v.frame();
        BoxNode before = box(v, "d").pseudoChild(BoxNode.Pseudo.BEFORE);
        assertEquals(BoxKind.BLOCK, before.kind);
        LayoutFragment d = InlineTextTest.frag(root, "d");
        assertEquals(2, d.flow.size(), "::before のブロックと、文字の匿名の塊");
        assertEquals(20f, d.flow.chunks[0].frags[0].height, 0.01f);
    }

    @Test
    void untouchedOwnerKeepsThePseudoBoxReference() {
        SpikeView v = view("li::before{content:'* '}", "<ul id=u><li id=a>a</li><li id=b>b</li></ul>");
        v.frame();
        BoxNode before = box(v, "b").pseudoChild(BoxNode.Pseudo.BEFORE);
        ((HtmlElement) v.byId("a")).setAttribute("style", "color:red");
        v.frame();
        assertSame(before, box(v, "b").pseudoChild(BoxNode.Pseudo.BEFORE), "触れていない持ち主の疑似要素は前回の参照");
        ((HtmlElement) v.byId("b")).setAttribute("style", "padding-left:3px");
        v.frame();
        assertSame(before, box(v, "b").pseudoChild(BoxNode.Pseudo.BEFORE), "持ち主を解き直しても中身が同じなら前回の参照");
    }

    @Test
    void attrChangeUpdatesContentIncrementally() {
        SpikeView v = view("span::after{content:attr(data-n)}", "<p><span id=s data-n=1>x</span></p>");
        v.frame();
        ((HtmlElement) v.byId("s")).setAttribute("data-n", "22");
        List<String> inc = SpikeView.dump(v.frame());
        assertEquals("22", text(box(v, "s").pseudoChild(BoxNode.Pseudo.AFTER)));
        assertEquals(SpikeView.dump(v.scratch()), inc);
    }

    @Test
    void countersFollowDocumentOrderAndRebuildFully() {
        SpikeView v = view("body{counter-reset:sec} h2{counter-increment:sec} h2::before{content:counter(sec) '. '}"
                + " ol{counter-reset:item} li{display:block;counter-increment:item} li::before{content:counters(item, '.') ' '}",
                "<h2 id=h1>A</h2><h2 id=h2>B</h2><ol><li id=l1>x<ol><li id=l11>y</li></ol></li><li id=l2>z</li></ol>");
        v.frame();
        assertEquals("1. ", text(box(v, "h1").pseudoChild(BoxNode.Pseudo.BEFORE)));
        assertEquals("2. ", text(box(v, "h2").pseudoChild(BoxNode.Pseudo.BEFORE)));
        assertEquals("1 ", text(box(v, "l1").pseudoChild(BoxNode.Pseudo.BEFORE)));
        assertEquals("1.1 ", text(box(v, "l11").pseudoChild(BoxNode.Pseudo.BEFORE)));
        assertEquals("2 ", text(box(v, "l2").pseudoChild(BoxNode.Pseudo.BEFORE)));
        // 先頭に h2 を足すと後ろの番号が全部ずれる（非局所）: カウンタを使う文書は全体を作り直す
        HtmlElement h = new HtmlElement("h2");
        h.updateTextContent("new");
        v.byId("h1").parent.insertBefore(h, v.byId("h1"));
        List<String> inc = SpikeView.dump(v.frame());
        assertTrue(v.compiler.stats().full());
        assertEquals("3. ", text(box(v, "h2").pseudoChild(BoxNode.Pseudo.BEFORE)));
        assertEquals(SpikeView.dump(v.scratch()), inc);
    }

    @Test
    void implicitListItemCounterOfTheUaSheetDoesNotForceFullRebuilds() {
        SpikeView v = view("", "<ol id=o><li id=a>a</li><li>b</li></ol>");
        v.frame();
        ((HtmlElement) v.byId("a")).setAttribute("style", "color:red");
        v.frame();
        assertFalse(v.compiler.stats().full(), "UA の counter-reset: list-item だけでは全体を作り直さない");
    }

    @Test
    void pseudoFlexItemsAreItems() {
        SpikeView v = view(".f{display:flex;justify-content:space-between;width:300px} .f::before{content:'b'} .f::after{content:'a'}",
                "<div id=f class=f>x<div>y</div></div>");
        LayoutFragment f = InlineTextTest.frag(v.frame(), "f");
        List<String> kinds = new ArrayList<>();
        f.flow.forEach((c, x, y) -> kinds.add(c.box.pseudo + "@" + Math.round(x)));
        assertEquals(4, kinds.size(), kinds.toString());
        assertTrue(kinds.get(0).startsWith("BEFORE@0"), kinds.toString());
        assertTrue(kinds.get(3).startsWith("AFTER@"), kinds.toString());
        assertNotNull(f.flow.chunks[0].frags[3].box);
    }
}
