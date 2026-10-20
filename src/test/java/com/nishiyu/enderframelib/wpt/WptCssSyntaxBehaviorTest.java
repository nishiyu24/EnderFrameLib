package com.nishiyu.enderframelib.wpt;

import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.css.ColorParser;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssParser;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.css.SelectorMatcher.SelectorEngine;
import com.nishiyu.enderframelib.core.css.StyleSheet;
import com.nishiyu.enderframelib.core.css.StyleSheet.StyleRule;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.dom.HtmlParser;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutContext.ScrollState;
import com.nishiyu.enderframelib.core.layout.TextShaper.FontFace.FontResolver;
import com.nishiyu.enderframelib.wpt.WptHarness.WptCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.nishiyu.enderframelib.wpt.WptHarness.assertEqualsWpt;
import static com.nishiyu.enderframelib.wpt.WptHarness.assertFalseWpt;
import static com.nishiyu.enderframelib.wpt.WptHarness.assertNotEqualsWpt;
import static com.nishiyu.enderframelib.wpt.WptHarness.assertTrueWpt;
import static com.nishiyu.enderframelib.wpt.WptHarness.test;

/**
 * WPT の {@code css/css-syntax/} から採用したケースの移植。ブラウザの CSSOM（{@code cssRules} /
 * {@code getComputedStyle}）の代わりに、エンジンの {@link StyleSheet} の見え方と計算値を読む。
 * CSSOM の書き戻し（{@code selectorText} / {@code style.zIndex} への代入）を見るケースは R5 の範囲なので採らない。
 */
public class WptCssSyntaxBehaviorTest extends WptBehaviorTest {

    private static final int VIEWPORT = 400;

    @Test
    @DisplayName("css/css-syntax/cdc-vs-ident-tokens.html")
    @WptCase(path = "css/css-syntax/cdc-vs-ident-tokens.html",
            description = "A CDC token before an ident-like selector is dropped, not swallowed into the ident")
    public void testCdcIsNotParsedAsAnIdent() {
        StyleSheet sheet = CssParser.parse("\n-->\n\n--foo { color: blue; }\n", VIEWPORT);
        test("CDC-token is properly emitted, and not parsed as an ident.", () -> {
            assertEqualsWpt(List.of("--foo"), selectors(sheet), "selectors");
        });
    }

    @Test
    @DisplayName("css/css-syntax/charset-is-not-a-rule.html")
    @WptCase(path = "css/css-syntax/charset-is-not-a-rule.html",
            description = "@charset is not a rule and is dropped")
    public void testCharsetIsNotARule() {
        StyleSheet sheet = CssParser.parse("@charset \"utf-8\";\n@charset \"utf-8\";\nfoo { color: blue; }\n@charset \"utf-8\";",
                VIEWPORT);
        test("@charset isn't a valid rule and shouldn't show up in the OM", () -> {
            assertEqualsWpt(1, sheet.rules.size(), "rule count");
        });
    }

    @Test
    @DisplayName("css/css-syntax/custom-property-rule-ambiguity.html")
    @WptCase(path = "css/css-syntax/custom-property-rule-ambiguity.html",
            description = "A top-level construct that looks like a custom property declaration is dropped; nested, a malformed one is skipped")
    public void testRulesThatLookLikeCustomPropertyDeclarations() {
        test("Rule that looks like a custom property declaration is ignored", () -> {
            StyleSheet sheet = CssParser.parse(".a { color: red } --x:hover { color: red } .b { color: red }", VIEWPORT);
            assertEqualsWpt(List.of(".a", ".b"), selectors(sheet), "selectors");
        });
        test("Rule that looks like an invalid custom property declaration is ignored", () -> {
            StyleSheet sheet = CssParser.parse(".a { color: red } --x:hover { ] } .b { color: red }", VIEWPORT);
            assertEqualsWpt(List.of(".a", ".b"), selectors(sheet), "selectors");
        });
        test("Nested rule that looks like an invalid custom property declaration", () -> {
            StyleSheet sheet = CssParser.parse("div { .a { color: red } --x:hover { ] }\n .b { color: red } }", VIEWPORT);
            // 壊れた宣言は次の ; か } まで食べる。そのあとの .b を別の見出しとして読み直してはいけない。
            assertFalseWpt(selectors(sheet).stream().anyMatch(s -> s.contains(".b")), "no nested .b rule: " + selectors(sheet));
            assertTrueWpt(selectors(sheet).stream().anyMatch(s -> s.contains(".a")), "nested .a rule: " + selectors(sheet));
        });
    }

    @Test
    @DisplayName("css/css-syntax/invalid-nested-rules.html")
    @WptCase(path = "css/css-syntax/invalid-nested-rules.html",
            description = "Parsing continues after the block of an invalid nested rule")
    public void testParsingContinuesAfterAnInvalidNestedRule() {
        StyleSheet sheet = CssParser.parse(".a { .b <::::invalid::::> { color: red } & .c { color: red } }", VIEWPORT);
        test("Continues parsing after block on invalid rule error", () -> {
            assertEqualsWpt(List.of("& .c"), selectors(sheet), "selectors");
        });
    }

    @Test
    @DisplayName("css/css-syntax/trailing-braces.html")
    @WptCase(path = "css/css-syntax/trailing-braces.html",
            description = "A declaration followed by a brace block is invalid")
    public void testTrailingBracesInvalidateTheDeclaration() {
        ComputedStyle style = styleOf("""
                <style>#target1 { color:green; color:red{}; color:red {}; }</style>
                <div id="target1">Green</div>
                """, "target1");
        test("Trailing braces are not valid", () -> {
            assertEqualsWpt(0xFF008000, ColorParser.parseColor(style.get(CssProperty.COLOR)), "color");
        });
    }

    @Test
    @DisplayName("css/css-syntax/at-rule-in-declaration-list.html")
    @WptCase(path = "css/css-syntax/at-rule-in-declaration-list.html",
            description = "An unknown at-rule inside a style rule is skipped and the declarations around it survive")
    public void testAtRuleInsideAStyleRule() {
        test("Allow @-rule with block inside style rule", () -> {
            assertEqualsWpt(0xFF008000, ColorParser.parseColor(styleOf(
                    "<style>div { @at {} color: green; }</style><div id=\"t\"></div>", "t").get(CssProperty.COLOR)), "color");
        });
        test("Allow @-rule with semi-colon inside style rule", () -> {
            assertEqualsWpt(0xFF008000, ColorParser.parseColor(styleOf(
                    "<style>div { @at at; color: green; }</style><div id=\"t\"></div>", "t").get(CssProperty.COLOR)), "color");
        });
    }

    @Test
    @DisplayName("css/css-syntax/declarations-trim-whitespace.html")
    @WptCase(path = "css/css-syntax/declarations-trim-whitespace.html",
            description = "Custom property values are trimmed of leading and trailing whitespace and !important")
    public void testDeclarationsTrimWhitespace() {
        ComputedStyle style = styleOf("""
                <style>
                #foo {
                  --foo-1:bar;
                  --foo-2: bar;
                  --foo-3:bar ;
                  --foo-4: bar ;
                  --foo-5: bar !important;
                  --foo-6: bar !important ;
                  --foo-7:bar!important;
                  --foo-8:bar!important ;
                  --foo-9:bar
                }
                </style>
                <p id="foo">foo</p>
                """, "foo");
        for (int i = 1; i <= 9; i++) {
            String name = "--foo-" + i;
            test(name, () -> assertEqualsWpt("bar", style.customProperties().get(name), name));
        }
    }

    @Test
    @DisplayName("css/css-syntax/ident-three-code-points.html")
    @WptCase(path = "css/css-syntax/ident-three-code-points.html",
            description = "Whether a hash token is an id selector depends on the first three code points")
    public void testIdentStartIsDecidedByTheFirstThreeCodePoints() {
        LayoutBox root = layout("""
                <style>
                  main div { background-color:red; }
                  div[name=one], div[name=two] { background: green; }
                  #1 { background-color:red; }
                  #-2 { background-color:red; }
                  #--3 { background-color:green; }
                  #---4 { background-color:green; }
                  #a { background-color:green; }
                  #-b { background-color:green; }
                  #--c { background-color:green; }
                  #---d { background-color:green; }
                </style>
                <main>
                  <div id="1" name="one">test1</div><div id="-2" name="two">test2</div>
                  <div id="--3" name="three">test3</div><div id="---4" name="four">test4</div>
                  <div id="a" name="five">test A</div><div id="-b" name="six">test B</div>
                  <div id="--c" name="seven">test C</div><div id="---d" name="eight">test D</div>
                </main>
                """);
        for (String id : List.of("1", "-2", "--3", "---4", "a", "-b", "--c", "---d")) {
            test("id " + id + " should be green", () -> {
                ComputedStyle style = find(root, id).style;
                assertEqualsWpt(0xFF008000, ColorParser.parseColor(style.get(CssProperty.BACKGROUND_COLOR)), id);
            });
        }
    }

    @Test
    @DisplayName("css/css-syntax/unclosed-constructs.html")
    @WptCase(path = "css/css-syntax/unclosed-constructs.html",
            description = "Unclosed blocks at EOF are valid in selector grammars")
    public void testUnclosedConstructsAreValidSelectors() {
        for (String selector : List.of("[foo]", "[foo", ":nth-child(1)", ":nth-child(1")) {
            test("\"" + selector + "\" is a valid selector", () ->
                    assertFalseWpt(SelectorEngine.getOrParse(selector).isEmpty(), selector));
        }
    }

    @Test
    @DisplayName("css/css-syntax/whitespace.html")
    @WptCase(path = "css/css-syntax/whitespace.html",
            description = "CSS whitespace is exactly U+0009, U+000A, U+000C, U+000D and U+0020")
    public void testCssWhitespaceIsTheFiveAsciiCharacters() {
        HtmlNode root = HtmlParser.parse("<div class=\"a\"><b id=\"inner\"></b></div><div id=\"foo\"></div>");
        List<HtmlNode> withSpace = SelectorEngine.querySelectorAll(root, ".a b");
        assertEqualsWpt(1, withSpace.size(), "control");
        for (int codePoint : new int[]{0x9, 0xa, 0xc, 0xd, 0x20}) {
            test("U+" + Integer.toHexString(codePoint) + " is CSS whitespace", () -> {
                String selector = ".a" + new String(Character.toChars(codePoint)) + "b";
                assertEqualsWpt(withSpace, SelectorEngine.querySelectorAll(root, selector), selector);
            });
        }
        int[] notWhitespace = {0xb, 0x85, 0xa0, 0x1680, 0x2000, 0x2003, 0x200a, 0x2029, 0x202f, 0x205f, 0x3000,
                0x180e, 0x200b, 0x200c, 0x200d, 0x2060, 0xfeff};
        for (int codePoint : notWhitespace) {
            test("U+" + Integer.toHexString(codePoint) + " is *not* CSS whitespace", () -> {
                String selector = ".a" + new String(Character.toChars(codePoint)) + "b";
                assertNotEqualsWpt(withSpace, SelectorEngine.querySelectorAll(root, selector), selector);
            });
        }
    }

    @Test
    @DisplayName("css/css-syntax/escaped-eof.html")
    @WptCase(path = "css/css-syntax/escaped-eof.html",
            description = "A backslash at EOF becomes U+FFFD in ident, dimension and url tokens and is ignored in a string")
    public void testEscapedEofInTokens() {
        test("Escaped EOF turns into a U+FFFD in an ident token.", () ->
                assertEqualsWpt("foo\uFFFD", customValue("foo { --foo:foo\\"), "ident"));
        test("Escaped EOF turns into a U+FFFD in a dimension token.", () ->
                assertEqualsWpt("1foo\uFFFD", customValue("foo { --foo:1foo\\"), "dimension"));
        test("Escaped EOF turns into a U+FFFD in a url token.", () ->
                assertEqualsWpt("url(foo\uFFFD)", customValue("foo { --foo:url(foo\\"), "url"));
        test("Escaped EOF in a string is ignored.", () ->
                assertEqualsWpt("\"foo\"", customValue("foo { --foo:\"foo\\"), "string"));
    }

    @Test
    @DisplayName("css/css-syntax/input-preprocessing.html")
    @WptCase(path = "css/css-syntax/input-preprocessing.html",
            description = "NULL and surrogate code points become U+FFFD before tokenization")
    public void testInputPreprocessingReplacesNullAndSurrogates() {
        test("NULL becomes FFFD in a custom property value", () ->
                assertEqualsWpt("f\uFFFDoo", customValue("foo { --foo:f\u0000oo }"), "NULL"));
        test("a lone surrogate becomes FFFD in a custom property value", () ->
                assertEqualsWpt("f\uFFFDoo", customValue("foo { --foo:f\ud800oo }"), "surrogate"));
    }

    // ---- helpers

    private static List<String> selectors(StyleSheet sheet) {
        return sheet.rules.stream().map(rule -> rule.selector).collect(Collectors.toList());
    }

    private static String customValue(String css) {
        StyleRule rule = CssParser.parse(css, VIEWPORT).rules.getFirst();
        return rule.properties.get("--foo");
    }

    private static LayoutBox layout(String html) {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        UiDocumentCompiler.Document doc = parser.parseDocument(html, "", VIEWPORT, VIEWPORT);
        return parser.layoutDocument(doc, VIEWPORT, VIEWPORT, Map.of(), new ScrollState(), FontResolver.FALLBACK);
    }

    private static ComputedStyle styleOf(String html, String id) {
        return find(layout(html), id).style;
    }

    private static LayoutBox find(LayoutBox box, String id) {
        if (box.node != null && id.equals(box.node.getId())) return box;
        for (LayoutBox child : box.elementChildren) {
            LayoutBox hit = find(child, id);
            if (hit != null) return hit;
        }
        return null;
    }
}