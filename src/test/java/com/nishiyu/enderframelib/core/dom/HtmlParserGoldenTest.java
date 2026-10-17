package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.core.GoldenFiles;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HtmlParserGoldenTest {

    /** パーサが自動採番した id="gen_N" / id="txt_N" を取り除いて比較する */
    private static String stripAutoIds(String html) {
        return html.replaceAll(" id=\"(gen|txt)_[0-9]+\"", "");
    }

    private static String outerOfChildren(HtmlNode root) {
        StringBuilder sb = new StringBuilder();
        for (HtmlNode child : root.children) {
            sb.append(DomUtils.getOuterHTML(child));
        }
        return sb.toString();
    }

    private void assertHtmlOuter(String input, String expectedOuter) {
        assertEquals(expectedOuter, stripAutoIds(outerOfChildren(HtmlParser.parse(input))));
    }

    @Test
    public void testVoidElements() {
        // br, img, input などが正しく " />" で閉じられること
        assertHtmlOuter(
                "<input type=\"text\"><br><img src=\"a.png\">",
                "<input type=\"text\" /><br /><img src=\"a.png\" />"
        );
    }

    @Test
    public void testRawTextElements() {
        // script の中身はエンティティ復号されず、そのまま保持される
        assertHtmlOuter(
                "<script>let a = 1 < 2;</script>",
                "<script>let a = 1 < 2;</script>"
        );
        assertHtmlOuter(
                "<style>.a::after { content: \"&amp;\"; }</style>",
                "<style>.a::after { content: \"&amp;\"; }</style>"
        );
    }

    @Test
    public void testEntitiesAreDecodedOutsideRawText() {
        HtmlNode root = HtmlParser.parse("<p>a &amp; b &lt;c&gt;</p>");
        assertEquals("a & b <c>", root.children.get(0).getTextContent());
    }

    @Test
    public void testNestedElementsAndText() {
        assertHtmlOuter(
                "<div id=\"main\" class=\"container\"><p>Hello <b>World</b>!</p></div>",
                "<div id=\"main\" class=\"container\"><p>Hello <b>World</b>!</p></div>"
        );
    }

    @Test
    public void testAttributeOrderIsPreserved() {
        // attrs が LinkedHashMap になり、getOuterHTML がシリアライズとして安定すること
        assertHtmlOuter(
                "<div data-z=\"1\" data-a=\"2\" data-m=\"3\"></div>",
                "<div data-z=\"1\" data-a=\"2\" data-m=\"3\"></div>"
        );
    }

    @Test
    public void testIdsAreStableAcrossReparse() {
        // NodeId がプロセスグローバルの単調増加ではなく、パース毎にリセットされること
        String html = "<div><span>a</span><span>b</span></div>";
        assertEquals(outerOfChildren(HtmlParser.parse(html)), outerOfChildren(HtmlParser.parse(html)));
    }

    @Test
    public void testExplicitIdWins() {
        HtmlNode root = HtmlParser.parse("<div id=\"mine\"><span></span></div>");
        HtmlNode div = root.children.get(0);
        assertEquals("mine", div.getId());
        // 明示 id が無い子には自動採番される
        assertTrue(div.children.get(0).getId().startsWith("gen_"));
        assertNotEquals("mine", div.children.get(0).getId());
    }

    @Test
    public void testClassListIsDerivedFromAttrs() {
        HtmlNode root = HtmlParser.parse("<div class=\"a b\"></div>");
        HtmlNode div = root.children.get(0);
        assertEquals(2, div.getClasses().size());
        assertTrue(div.hasClass("a"));

        div.addClass("c");
        assertEquals("a b c", div.attrs.get("class"));
        div.removeClass("a");
        assertEquals("b c", div.attrs.get("class"));
    }

    @Test
    public void testShowcaseRoundTripGolden() {
        // 同梱の showcase.html をパースし直したシリアライズ結果をまるごと固定する
        HtmlNode root = HtmlParser.parse(GoldenFiles.readFixture("showcase.html"));
        GoldenFiles.assertMatches("showcase-roundtrip.html", outerOfChildren(root));
    }

    @Test
    public void testHudTestRoundTripIsIdempotent() {
        // 1 回目のシリアライズ結果を再パースしても同じ結果になること（往復の安定性）
        String once = outerOfChildren(HtmlParser.parse(GoldenFiles.readFixture("hud_test.html")));
        String twice = outerOfChildren(HtmlParser.parse(once));
        assertEquals(once, twice);
    }

    @Test
    public void testMismatchedCloseTagIsIgnored() {
        // 対応する開始タグが無い終了タグは無視され、ツリーは壊れない
        assertHtmlOuter("<div>a</span>b</div>", "<div>ab</div>");
    }

    @Test
    public void testUnclosedTagsAreClosedAtEndOfDocument() {
        assertHtmlOuter("<div><p>text", "<div><p>text</p></div>");
    }

    @Test
    public void testOverlappingTagsCloseTheStackUpToTheMatch() {
        // <b><i></b></i> のような交差は、一致する開始タグまでスタックを巻き戻す
        assertHtmlOuter("<b><i>x</b></i>", "<b><i>x</i></b>");
    }

    @Test
    public void testCommentsAndDoctypeAreSkipped() {
        assertHtmlOuter("<!DOCTYPE html><!-- note --><p>x</p>", "<p>x</p>");
    }
}
