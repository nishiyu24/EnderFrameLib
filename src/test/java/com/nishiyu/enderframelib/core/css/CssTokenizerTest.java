package com.nishiyu.enderframelib.core.css;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 旧 CSS_PATTERN 正規表現が扱えなかったケースを固定する。 */
public class CssTokenizerTest {

    @Test
    public void testNestedBracesAreOneBlock() {
        List<CssTokenizer.Block> blocks =
                CssTokenizer.tokenize("@media (min-width: 100px) { .a { color: red; } .b { color: blue; } } .c { color: green; }");

        assertEquals(2, blocks.size());
        assertTrue(blocks.get(0).isAtRule());
        assertEquals("@media", blocks.get(0).atRuleName());
        assertEquals("(min-width: 100px)", blocks.get(0).atRuleParams());
        // 中身はネストしたブロックのまま保持され、再帰的にパースできる
        assertEquals(2, CssTokenizer.tokenize(blocks.get(0).body()).size());
        assertEquals(".c", blocks.get(1).prelude());
    }

    @Test
    public void testUrlAndStringsDoNotBreakDeclarations() {
        // url() の中のセミコロン・波括弧、文字列中のコロンで分割されないこと
        Map<String, String> decls = CssTokenizer.parseDeclarations(
                "background: url(a;b{c}.png); content: \"a: b; c\"; color: red");

        assertEquals(3, decls.size());
        assertEquals("url(a;b{c}.png)", decls.get("background"));
        assertEquals("\"a: b; c\"", decls.get("content"));
        assertEquals("red", decls.get("color"));
    }

    @Test
    public void testCommentsAreStrippedOutsideStrings() {
        Map<String, String> decls = CssTokenizer.parseDeclarations("color: red; /* background: blue; */ margin: 0");
        assertEquals(2, decls.size());
        assertEquals("red", decls.get("color"));
        assertEquals("0", decls.get("margin"));

        // 文字列リテラルの中はコメントとして扱わない
        assertEquals("\"/* keep */\"", CssTokenizer.parseDeclarations("content: \"/* keep */\"").get("content"));
    }

    @Test
    public void testSelectorListSplitIgnoresCommasInsideParensAndBrackets() {
        assertEquals(List.of("a:not(.x, .y)", "b[title=\",\"]", "c"),
                CssTokenizer.splitSelectorList("a:not(.x, .y), b[title=\",\"], c"));
    }

    @Test
    public void testKeyframesAreParsedWithoutStringFlattening() {
        StyleSheet sheet = CssParser.parse(
                "@keyframes fade { from { opacity: 0; } to { opacity: 1; } } .x { color: red; }", 1920);

        assertEquals(1, sheet.rules.size(), "@keyframes 自体はルールにならない");
        String encoded = sheet.keyframes.get("fade");
        assertTrue(encoded.contains("0`opacity: 0;|"), encoded);
        assertTrue(encoded.contains("100`opacity: 1;|"), encoded);
    }

    @Test
    public void testImportantBeatsHigherSpecificity() {
        StyleSheet sheet = CssParser.parse("#a { color: blue; } .c { color: red !important; }", 1920);

        // 詳細度では #a が強いが、!important を持つ .c が最後（＝最強）に並ぶ
        StyleRule strongest = sheet.rules.get(sheet.rules.size() - 1);
        assertEquals(".c", strongest.selector);
        assertTrue(strongest.specificity.important());
        assertEquals("red", strongest.properties.get("color"), "!important の表記は値から除かれる");
    }

    @Test
    public void testMediaQueryListIsOr() {
        String css = "@media (max-width: 400px), (min-width: 1000px) { .x { color: red; } }";
        assertEquals(1, CssParser.parse(css, 300).rules.size());
        assertEquals(1, CssParser.parse(css, 1200).rules.size());
        assertEquals(0, CssParser.parse(css, 700).rules.size(), "どちらの条件も満たさない幅では落ちる");
    }
}
