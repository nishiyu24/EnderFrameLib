package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.css.Selector.ElementState;
import com.nishiyu.enderframelib.core.css.SelectorMatcher.SelectorEngine;
import com.nishiyu.enderframelib.core.css.StyleSheet.StyleRule;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.dom.HtmlParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    public void testRootMatchesOnlyTheDocumentElement() {
        HtmlNode root = HtmlParser.parseDocument("<div id=\"a\"></div>");
        List<HtmlNode> found = SelectorEngine.querySelectorAll(root, ":root");
        // 以前はパーサの合成ノード（html_root）にも一致し、:root の宣言が 2 つの箱に掛かっていた
        assertEquals(1, found.size());
        assertEquals("html", found.getFirst().tag);
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

    // ---- W10: An+B・構造擬似クラス・無効なセレクタ

    @Test
    public void testNthChildWithGeneralAnPlusB() {
        HtmlNode root = doc("<ul>" + "<li id=\"l1\"></li><li id=\"l2\"></li><li id=\"l3\"></li><li id=\"l4\"></li>"
                + "<li id=\"l5\"></li><li id=\"l6\"></li><li id=\"l7\"></li></ul>");
        // 以前は 3n などを「指定なし」にしていたので全要素に一致していた
        assertEquals("l3,l6", ids(root, "li:nth-child(3n)"));
        assertEquals("l1,l5", ids(root, "li:nth-child(4n+1)"));
        assertEquals("l1,l2,l3", ids(root, "li:nth-child(-n+3)"));
        assertEquals("l1,l3,l5,l7", ids(root, "li:nth-child(2n + 1)"));
        assertEquals("l2,l4,l6", ids(root, "li:nth-child(even)"));
        assertEquals("l6", ids(root, "li:nth-last-child(2)"));
        assertEquals("l5,l6,l7", ids(root, "li:nth-last-child(-n+3)"));
        assertEquals("", ids(root, "li:nth-child(0)"));
        assertEquals("l1,l2,l3,l4,l5,l6,l7", ids(root, "li:nth-child(n)"));
    }

    @Test
    public void testOfTypeAndOnlyPseudoClasses() {
        HtmlNode root = doc("<div id=\"box\"><span id=\"s1\"></span><p id=\"p1\"></p><span id=\"s2\"></span>"
                + "<em id=\"e1\"></em><span id=\"s3\"></span></div><section><i id=\"only\"></i></section>");
        assertEquals("s1", ids(root, "span:first-of-type"));
        assertEquals("s3", ids(root, "span:last-of-type"));
        assertEquals("s2", ids(root, "span:nth-of-type(2)"));
        assertEquals("s2", ids(root, "span:nth-last-of-type(2)"));
        assertEquals("p1,e1,only", ids(root, "#box > :only-of-type, section > :only-of-type"));
        assertEquals("only", ids(root, "section > :only-child"));
    }

    @Test
    public void testUnknownOrMalformedSelectorsMatchNothing() {
        HtmlNode root = doc("<div id=\"a\"><p id=\"b\"></p></div>");
        // 以前は未知の擬似クラスを読み飛ばしたので、どれも全要素（div と p）に一致していた
        for (String sel : List.of("div:made-up(p)", "p:unknown", "p:nth-of-type(3n of .x)", "p:nth-child(foo)",
                "p:first-child(2)", "p::nonsense", "> p", "div >", "div > + p", "p..x", "div[", "[=x]", "#", "p:not()")) {
            assertEquals("", ids(root, sel), sel);
            assertFalse(SelectorParser.parse(sel).isValid(), sel);
        }
        assertTrue(SelectorParser.parse("p::-webkit-scrollbar").isValid(), "ベンダ接頭辞の擬似要素は無効にしない");
    }

    @Test
    public void testUnrenderedPseudoElementsDoNotStyleTheElement() {
        HtmlNode root = doc("<input id=\"i\" placeholder=\"x\"><ul><li id=\"l\"></li></ul>");
        // 以前は ::placeholder / ::marker の宣言が input / li 自身に掛かっていた
        assertEquals("", ids(root, "input::placeholder"));
        assertEquals("", ids(root, "li::marker"));
        assertTrue(SelectorParser.parse("input::placeholder").isValid());
    }

    @Test
    public void testInvalidSelectorDropsTheWholeRule() {
        StyleSheet sheet = CssParser.parse("p, div:unknown { color: red; } p, ::selection { color: blue; } "
                + "h1 { color: green; }", 800, StyleSheet.CascadeOrigin.AUTHOR);
        // 1 つ目の規則は丸ごと捨てる（p にも掛けない）。::selection は正しいセレクタなので 2 つ目は残る
        assertEquals(List.of("p", "::selection", "h1"), sheet.rules.stream().map(r -> r.selector).toList());

        StyleSheet supports = CssParser.parse("@supports selector(:made-up(a)) { .x { color: red; } } "
                + "@supports selector(:is(a, b)) { .y { color: red; } }", 800, StyleSheet.CascadeOrigin.AUTHOR);
        assertEquals(List.of(".y"), supports.rules.stream().map(r -> r.selector).toList());
    }

    @Test
    public void testIsAndWhereMatchAndCountSpecificity() {
        HtmlNode root = doc("<div class=\"a\"><span id=\"s1\"></span></div><div id=\"b\"><span id=\"s2\"></span></div>"
                + "<div><span id=\"s3\"></span></div>");
        assertEquals("s1,s2", ids(root, ":is(.a, #b) span"));
        assertEquals("s1,s2", ids(root, ":where(.a, #b) > span"));
        assertEquals("s1", ids(root, "span:is(.a *)"));
        // 寛容なリスト: 無効な引数だけを捨てる
        assertEquals("s1", ids(root, ":is(.a, :unknown) span"));
        assertEquals("", ids(root, ":is(:unknown) span"));

        assertSpecificity(1, 0, 1, ":is(.a, #b) span");
        assertSpecificity(0, 0, 1, ":where(.a, #b) span");
        // :not() にリストを渡すと引数の最大の詳細度（以前は全引数の和）
        assertSpecificity(1, 0, 1, "span:not(.a, #b)");
    }

    @Test
    public void testNotTakesComplexSelectors() {
        HtmlNode root = doc("<div class=\"x\"><p id=\"in\"></p></div><p id=\"out\"></p>");
        assertEquals("out", ids(root, "p:not(.x p)"));
        assertEquals("out", ids(root, "p:not(.x > p)"));
        assertEquals("in", ids(root, "p:is(.x > p)"));
    }

    @Test
    public void testSeveralConditionsOnTheSameAttributeAllApply() {
        HtmlNode root = doc("<p id=\"ab\" class=\"aa bb\"></p><p id=\"a\" class=\"aa\"></p><p id=\"b\" class=\"bb\"></p>");
        // 以前は同じ属性の 2 つ目の条件が 1 つ目を上書きし、[class*="b"] だけで照合していた
        assertEquals("ab", ids(root, "[class*=\"a\"][class*=\"b\"]"));
        assertEquals("ab", ids(root, "[class~=\"aa\"][class~=\"bb\"]"));
    }

    @Test
    public void testAttributeCaseFlagAndEmptyValues() {
        HtmlNode root = doc("<input id=\"t\" type=\"TEXT\"><a id=\"l\" href=\"x\" title=\"\"></a>");
        assertEquals("", ids(root, "[type=\"text\"]"));
        assertEquals("t", ids(root, "[type=\"text\" i]"));
        // 空の値への ^= / $= / *= は何にも一致しない
        assertEquals("", ids(root, "[title^=\"\"]"));
        assertEquals("l", ids(root, "[title=\"\"]"));
    }

    @Test
    public void testFormAndLinkPseudoClasses() {
        HtmlNode root = doc("<input id=\"on\"><input id=\"off\" disabled><input id=\"req\" required><div id=\"d\"></div>"
                + "<a id=\"link\" href=\"#\"></a><a id=\"anchor\"></a>");
        assertEquals("on,req", ids(root, ":enabled"));
        assertEquals("off", ids(root, ":disabled"));
        assertEquals("req", ids(root, ":required"));
        assertEquals("on,off", ids(root, ":optional"));
        assertEquals("link", ids(root, ":link"));
        assertEquals("link", ids(root, ":any-link"));
        assertEquals("", ids(root, ":visited"));
        assertEquals(2, CssParser.parse("a:link, a:visited { color: red; }", 800, StyleSheet.CascadeOrigin.AUTHOR)
                .rules.size(), "a:link, a:visited の規則は捨てない");
        assertEquals("link", ids(root, "a:link, a:visited"));
    }

    @Test
    public void testStateInsideIsAndNotDrivesRestyle() {
        StyleSheet sibling = CssParser.parse(":is(input:checked + label) span { color: red; }", 800,
                StyleSheet.CascadeOrigin.AUTHOR);
        assertTrue(StyleResolver.hasSiblingStateRules(sibling), ":is() の中の兄弟結合子も見る");

        HtmlNode root = doc("<div id=\"c\" class=\"card\"></div><div id=\"o\"></div><input id=\"i\">");
        StyleSheet hover = CssParser.parse(".card:is(:hover) { color: red; }", 800, StyleSheet.CascadeOrigin.AUTHOR);
        assertTrue(StyleResolver.hasInteractiveRules(SelectorEngine.querySelector(root, "#c"), hover));
        assertFalse(StyleResolver.hasInteractiveRules(SelectorEngine.querySelector(root, "#o"), hover));

        // :enabled は実行時の DISABLED に従う
        HtmlNode input = SelectorEngine.querySelector(root, "#i");
        Selector enabled = SelectorParser.parse("input:enabled");
        assertTrue(enabled.matches(input, n -> ElementState.NONE));
        assertFalse(enabled.matches(input, n -> ElementState.of(ElementState.DISABLED)));
        assertTrue((enabled.stateMask() & ElementState.DISABLED) != 0, "DISABLED の変化で再スタイルされる");
    }

    @Test
    public void testEscapedIdentifiers() {
        HtmlNode root = doc("<div id=\"x\" class=\"sm:hidden\"></div><div id=\"y\" class=\"sm\"></div>"
                + "<div id=\"123\"></div>");
        // 以前はバックスラッシュで識別子が切れて .sm として照合していた
        assertEquals("x", ids(root, ".sm\\:hidden"));
        assertEquals("123", ids(root, "#\\31 23"));
    }

    @Test
    public void testNoMatchReturnsNull() {
        HtmlNode root = doc("<div id=\"a\"></div>");
        assertNull(SelectorEngine.querySelector(root, ".nope"));
        assertEquals(0, SelectorEngine.querySelectorAll(root, ".nope").size());
    }

    private static void assertSpecificity(int id, int clazz, int type, String selector) {
        StyleSheet.Specificity s = SelectorParser.parse(selector).specificity();
        assertEquals(List.of(id, clazz, type), List.of(s.id(), s.clazz(), s.type()), selector);
    }
}
