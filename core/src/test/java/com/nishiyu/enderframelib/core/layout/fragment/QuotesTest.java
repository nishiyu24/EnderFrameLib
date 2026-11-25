package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * M2b-1: 引用符（{@code quotes}・{@code open-quote} / {@code close-quote}。CSS Generated Content 3 §2.1。旧経路は出さない）。
 * UA の {@code q::before} / {@code q::after} は置かない（全ての文書で疑似要素のカスケードを解くことになり、旧経路・新経路とも
 * 確保が数倍に増えた。{@code costGate} で検出）ので、文書が {@code q} の規則を書く。
 */
class QuotesTest {
    /** {@code id} の行の文字片を空白を除いて繋げた文字列（片の間の空白は片に入らない）。 */
    private static String text(SpikeView v, String id) {
        LayoutFragment f = InlineTextTest.frag(v.frame(), id);
        assertNotNull(f, id);
        StringBuilder sb = new StringBuilder();
        for (LineFragment l : f.lines) for (LineFragment.Piece p : l.pieces()) sb.append(p.string());
        return sb.toString().replace(" ", "");
    }

    private static SpikeView view(String css, String body) {
        return new SpikeView("<html><head><style>body{width:600px} q::before{content:open-quote} q::after{content:close-quote}" + css
                + "</style></head><body>" + body + "</body></html>", 800, 600);
    }

    @Test
    void nestedQElementsAlternateTheQuotePairs() {
        assertEquals("“a‘b’c”", text(view("", "<p id=p><q>a <q>b</q> c</q></p>"), "p"));
    }

    @Test
    void quotesPropertyChoosesThePairsAndNoneHidesThem() {
        assertEquals("<<a[b]c>>", text(view("p{quotes:'<<' '>>' '[' ']'}", "<p id=p><q>a <q>b</q> c</q></p>"), "p"));
        assertEquals("ab", text(view("p{quotes:none}", "<p id=p><q>a</q> <q>b</q></p>"), "p"));
        // 組が足りない深さは最後の組
        assertEquals("«a«b»c»", text(view("p{quotes:'«' '»'}", "<p id=p><q>a <q>b</q> c</q></p>"), "p"));
    }

    @Test
    void documentOrderDepthWhenTheDocumentUsesCounters() {
        // カウンタを使う文書は文書順の深さ: 前の兄弟の閉じない open-quote も数える
        String css = "body{counter-reset:x} .o::before{content:open-quote} .c::after{content:close-quote} .k::before{content:counter(x)}";
        assertEquals("“a‘b’”", text(view(css, "<p id=p><span class=o>a</span><span class=o>b</span><span class=c></span><span class=c></span>"
                + "<span class=k></span></p>"), "p").replace("0", ""));
    }

    @Test
    void insertedQuoteMatchesAFreshBuild() {
        SpikeView v = view("", "<p id=p><q id=outer>a</q></p>");
        v.frame();
        HtmlElement inner = new HtmlElement("q");
        inner.updateTextContent("b");
        v.byId("outer").appendChild(inner);
        assertEquals("“a‘b’”", text(v, "p"));
        assertEquals(SpikeView.dump(v.scratch()), SpikeView.dump(v.frame()));
    }
}
