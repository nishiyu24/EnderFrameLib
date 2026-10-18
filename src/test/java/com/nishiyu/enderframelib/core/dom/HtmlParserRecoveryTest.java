package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.core.dom.HtmlParser.HtmlTokenizer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 閉じタグ省略と文字参照の扱い（フェーズ4で追加した挙動）。 */
public class HtmlParserRecoveryTest {

    @Test
    public void testListItemsAreSiblingsNotNested() {
        HtmlNode root = HtmlParser.parse("<ul><li>a<li>b<li>c</ul>");
        HtmlNode ul = root.children.get(0);

        assertEquals(3, ul.children.size(), "li が入れ子になっている: " + ul.getOuterHTML());
        for (HtmlNode li : ul.children) {
            assertEquals("li", li.tag);
            // 各項目はテキスト 1 つだけを持つ（次の項目を抱えていない）
            assertEquals(1, li.children.size(), li.getOuterHTML());
        }
    }

    @Test
    public void testParagraphIsClosedByFollowingBlock() {
        HtmlNode root = HtmlParser.parse("<p>first<p>second<div>third</div>");

        assertEquals(3, root.children.size(), root.getOuterHTML());
        assertEquals("p", root.children.get(0).tag);
        assertEquals("p", root.children.get(1).tag);
        assertEquals("div", root.children.get(2).tag);
    }

    @Test
    public void testInlineElementsStillNestInsideParagraph() {
        // ブロック以外の開始タグでは <p> を閉じない
        HtmlNode root = HtmlParser.parse("<p>a<span>b</span></p>");
        assertEquals(1, root.children.size());
        assertEquals(2, root.children.get(0).children.size());
    }

    @Test
    public void testDefinitionListItemsAreSiblings() {
        HtmlNode root = HtmlParser.parse("<dl><dt>term<dd>desc<dt>term2</dl>");
        assertEquals(3, root.children.get(0).children.size());
    }

    @Test
    public void testNumericCharacterReferences() {
        assertEquals("A&B", HtmlTokenizer.decodeHtmlEntities("&#65;&amp;&#66;"));
        assertEquals("'", HtmlTokenizer.decodeHtmlEntities("&#39;"));
        assertEquals("→", HtmlTokenizer.decodeHtmlEntities("&#x2192;"));
        assertEquals("→", HtmlTokenizer.decodeHtmlEntities("&#8594;"));
    }

    @Test
    public void testMalformedNumericReferencesFollowTheSpec() {
        // 桁が無いものは参照ではない。';' の無いものも解き、範囲外とサロゲートと 0 は U+FFFD（HTML §13.2.5.80）。
        // W6 までは ';' の無いものと範囲外を生のまま残していた
        assertEquals("&#;", HtmlTokenizer.decodeHtmlEntities("&#;"));
        assertEquals("A", HtmlTokenizer.decodeHtmlEntities("&#65"));
        assertEquals("�", HtmlTokenizer.decodeHtmlEntities("&#99999999;"));
        assertEquals("�", HtmlTokenizer.decodeHtmlEntities("&#xD800;"));
        // 0x80〜0x9F は Windows-1252 の文字
        assertEquals("™", HtmlTokenizer.decodeHtmlEntities("&#153;"));
    }

    @Test
    public void testNamedReferencesUseTheFullTable() {
        // 以前は 6 つの名前しか知らず、&nbsp; は普通の空白になっていた
        assertEquals("© …  ", HtmlTokenizer.decodeHtmlEntities("&copy; &hellip; &nbsp;"));
        assertEquals("∳", HtmlTokenizer.decodeHtmlEntities("&CounterClockwiseContourIntegral;"));
        // 2 文字に展開される名前
        assertEquals("≂̸", HtmlTokenizer.decodeHtmlEntities("&NotEqualTilde;"));
        // 未知の名前はそのまま
        assertEquals("&nosuch;", HtmlTokenizer.decodeHtmlEntities("&nosuch;"));
    }

    @Test
    public void testInnerHtmlUsesTheTargetAsContext() {
        var previous = DomUtils.innerHtmlParser;
        DomUtils.innerHtmlParser = HtmlParser::parseFragment;
        try {
            HtmlNode root = HtmlParser.parse("<table><tbody id=\"body\"></tbody></table>");
            HtmlNode tbody = root.getElementById("body");
            tbody.setInnerHTML("<tr><td>a</td></tr>");
            // body の文脈で解析すると表の外の <tr> / <td> は捨てられ、"a" だけが残る
            assertEquals("tr", tbody.children.getFirst().tag);
            assertEquals("td", tbody.children.getFirst().children.getFirst().tag);
        } finally {
            DomUtils.innerHtmlParser = previous;
        }
    }

    @Test
    public void testDocumentGetsImpliedHtmlHeadBody() {
        HtmlNode root = HtmlParser.parseDocument("<link rel=\"stylesheet\" href=\"a.css\"><div id=\"x\"></div>");
        assertEquals(1, root.children.size());
        HtmlNode html = root.children.getFirst();
        assertEquals("html", html.tag);
        assertEquals("head", html.children.get(0).tag);
        assertEquals("link", html.children.get(0).children.getFirst().tag);
        assertEquals("body", html.children.get(1).tag);
        assertEquals("x", html.children.get(1).children.getFirst().getId());
    }

    @Test
    public void testEscapedReferenceIsNotDecodedTwice() {
        // "&amp;#39;" は「&#39; という文字列」を意味するので、アポストロフィにしてはいけない
        assertEquals("&#39;", HtmlTokenizer.decodeHtmlEntities("&amp;#39;"));
        assertEquals("&lt;", HtmlTokenizer.decodeHtmlEntities("&amp;lt;"));
    }

    @Test
    public void testNumericReferencesReachTheDom() {
        HtmlNode root = HtmlParser.parse("<p>&#65;&#x42;</p>");
        HtmlNode text = root.children.get(0).children.get(0);
        assertTrue(text.text.contains("AB"), "実際のテキスト: " + text.text);
    }
}
