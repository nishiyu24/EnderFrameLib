package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.dom.HtmlParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 結合子・属性セレクタ・疑似クラスの照合（フェーズ1の特性テスト） */
public class SelectorMatchingTest {

    private static HtmlNode doc(String html) {
        return HtmlParser.parse(html);
    }

    /** マッチしたノードの id を "," 連結で返す */
    private static String ids(HtmlNode root, String selector) {
        List<HtmlNode> found = SelectorEngine.querySelectorAll(root, selector);
        return found.stream().map(HtmlNode::getId).filter(id -> !id.startsWith("gen_") && !id.startsWith("txt_"))
                .collect(Collectors.joining(","));
    }

    @Test
    public void testTypeIdAndClassSelectors() {
        HtmlNode root = doc("<div id=\"a\" class=\"x y\"></div><span id=\"b\" class=\"x\"></span>");
        assertEquals("a,b", ids(root, ".x"));
        assertEquals("a", ids(root, ".x.y"));
        assertEquals("a", ids(root, "#a"));
        assertEquals("b", ids(root, "span"));
        assertEquals("a,b", ids(root, "*").replace("html_root,", ""));
    }

    @Test
    public void testDescendantCombinator() {
        HtmlNode root = doc("<div id=\"o\"><section><span id=\"deep\"></span></section></div><span id=\"out\"></span>");
        assertEquals("deep", ids(root, "#o span"));
    }

    @Test
    public void testChildCombinator() {
        HtmlNode root = doc("<div id=\"o\"><span id=\"direct\"></span><section><span id=\"deep\"></span></section></div>");
        assertEquals("direct", ids(root, "#o > span"));
    }

    @Test
    public void testAdjacentAndGeneralSiblingCombinators() {
        HtmlNode root = doc("<p id=\"p1\"></p><span id=\"s1\"></span><span id=\"s2\"></span>");
        assertEquals("s1", ids(root, "p + span"));
        assertEquals("s1,s2", ids(root, "p ~ span"));
    }

    @Test
    public void testAttributeSelectors() {
        HtmlNode root = doc(
                "<a id=\"a1\" href=\"https://example.com/x.png\" data-role=\"nav\"></a>" +
                "<a id=\"a2\" href=\"/local/y.jpg\"></a>");

        assertEquals("a1", ids(root, "[data-role]"));
        assertEquals("a1", ids(root, "[data-role=\"nav\"]"));
        assertEquals("a1", ids(root, "[href^=\"https\"]"));
    }

    @Test
    public void testSuffixAndContainsAttributeSelectors() {
        HtmlNode root = doc(
                "<a id=\"a1\" href=\"https://example.com/x.png\"></a>" +
                "<a id=\"a2\" href=\"/local/y.jpg\"></a>");

        assertEquals("a1", ids(root, "[href$=\".png\"]"));
        assertEquals("a1,a2", ids(root, "[href*=\"/\"]"));
    }

    @Test
    public void testWordAndDashAttributeSelectors() {
        HtmlNode root = doc(
                "<p id=\"p1\" data-tags=\"alpha beta\" lang=\"en-US\"></p>" +
                "<p id=\"p2\" data-tags=\"betagamma\" lang=\"fr\"></p>");

        assertEquals("p1", ids(root, "[data-tags~=\"beta\"]"));
        assertEquals("p1", ids(root, "[lang|=\"en\"]"));
    }

    @Test
    public void testStructuralPseudoClasses() {
        HtmlNode root = doc("<ul><li id=\"l1\"></li><li id=\"l2\"></li><li id=\"l3\"></li><li id=\"l4\"></li></ul>");
        assertEquals("l1", ids(root, "li:first-child"));
        assertEquals("l4", ids(root, "li:last-child"));
        assertEquals("l2", ids(root, "li:nth-child(2)"));
        assertEquals("l1,l3", ids(root, "li:nth-child(odd)"));
        assertEquals("l2,l4", ids(root, "li:nth-child(even)"));
    }

    @Test
    public void testEmptyPseudoClass() {
        HtmlNode root = doc("<div id=\"e\"></div><div id=\"f\">text</div>");
        assertEquals("e", ids(root, "div:empty"));
    }

    @Test
    public void testCheckedAndDisabledUseStaticAttributes() {
        HtmlNode root = doc("<input id=\"i1\" type=\"checkbox\" checked><input id=\"i2\" type=\"checkbox\" disabled>");
        assertEquals("i1", ids(root, "input:checked"));
        assertEquals("i2", ids(root, "input:disabled"));
    }

    @Test
    public void testNotPseudoClass() {
        HtmlNode root = doc("<div id=\"a\" class=\"skip\"></div><div id=\"b\"></div>");
        assertEquals("b", ids(root, "div:not(.skip)"));
    }

    @Test
    public void testInteractivePseudoClassesAreFlaggedNotMatchedStatically() {
        // 対話状態は HtmlNode から切り離されたため、:hover などは StyleRule のフラグとして扱われる
        StyleRule hover = new StyleRule("button:hover");
        StyleRule active = new StyleRule("button:active");
        StyleRule focus = new StyleRule("button:focus");

        org.junit.jupiter.api.Assertions.assertTrue(hover.isHover);
        org.junit.jupiter.api.Assertions.assertTrue(active.isActive);
        org.junit.jupiter.api.Assertions.assertTrue(focus.isFocus);

        // 素の button には（状態に関係なく）セレクタ本体として一致する
        HtmlNode root = doc("<button id=\"btn\"></button>");
        assertNotNull(SelectorEngine.querySelector(root, "button"));
    }

    @Test
    public void testPseudoElementsAreSplitOffTheSelector() {
        StyleRule before = new StyleRule(".tip::before");
        org.junit.jupiter.api.Assertions.assertTrue(before.isBefore);
        assertEquals(".tip", before.selector);
    }

    @Test
    public void testNoMatchReturnsNull() {
        HtmlNode root = doc("<div id=\"a\"></div>");
        assertNull(SelectorEngine.querySelector(root, ".nope"));
        assertEquals(0, SelectorEngine.querySelectorAll(root, ".nope").size());
    }
}
