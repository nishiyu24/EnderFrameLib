package com.nishiyu.enderframelib.core.dom;

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
    public void testMalformedNumericReferencesArePreserved() {
        // 桁が無い・閉じていない・範囲外のものは参照として扱わない
        assertEquals("&#;", HtmlTokenizer.decodeHtmlEntities("&#;"));
        assertEquals("&#65", HtmlTokenizer.decodeHtmlEntities("&#65"));
        assertEquals("&#99999999;", HtmlTokenizer.decodeHtmlEntities("&#99999999;"));
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
