package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.css.model.*;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.css.CssParser.MediaEnvironment;
import com.nishiyu.enderframelib.core.css.InvalidationSets.Relation;
import com.nishiyu.enderframelib.core.css.StyleSheet.CascadeOrigin;
import com.nishiyu.enderframelib.core.css.StyleSheet.StyleRule;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.parser.HtmlParser;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.RenderPipeline;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;
import com.nishiyu.enderframelib.core.layout.LayoutContext;
import com.nishiyu.enderframelib.core.layout.model.Viewport;
import com.nishiyu.enderframelib.core.layout.model.FormattingContext;
import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.css.model.Overflow;
import com.nishiyu.enderframelib.core.css.model.TextAlign;
import com.nishiyu.enderframelib.core.css.model.BorderStyle;

/**
 * R4 のカスケードの土台（{@code @import} の {@code layer()} / {@code supports()}、ユーザー起源、
 * 規則の索引、無効化セット、キーワードの型）を固定する。
 */
public class CascadeInfrastructureTest {

    private static final int VIEWPORT = 400;

    // ---- @import

    @Test
    public void testImportIntoALayerLosesToUnlayeredRules() {
        Map<String, String> files = Map.of("lib.css", "div { color: #111111; }");
        StyleSheet sheet = CssParser.parse("@import url(lib.css) layer(lib); div { color: #222222; }",
                MediaEnvironment.of(VIEWPORT, VIEWPORT).withImportResolver(files::get), CascadeOrigin.AUTHOR);
        assertEquals("#222222", colorOf(sheet, "<div id=\"a\">x</div>"));
    }

    @Test
    public void testImportSupportsConditionGatesTheFile() {
        Map<String, String> files = Map.of("grid.css", "div { color: #333333; }", "never.css", "div { color: #444444; }");
        MediaEnvironment env = MediaEnvironment.of(VIEWPORT, VIEWPORT).withImportResolver(files::get);
        StyleSheet sheet = CssParser.parse(
                "@import 'grid.css' supports(display: grid); @import 'never.css' supports(not (display: flex));", env,
                CascadeOrigin.AUTHOR);
        assertEquals("#333333", colorOf(sheet, "<div id=\"a\">x</div>"));
    }

    // ---- 起源

    @Test
    public void testUserOriginSitsBetweenUserAgentAndAuthorAndImportantReverses() {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        StyleSheet normal = parser.buildStyleSheet("div { color: #aaaaaa; }", "div { color: #bbbbbb; }", VIEWPORT, VIEWPORT);
        assertEquals("#bbbbbb", colorOf(normal, "<div id=\"a\">x</div>"));
        StyleSheet important = parser.buildStyleSheet("div { color: #aaaaaa !important; }",
                "div { color: #bbbbbb !important; }", VIEWPORT, VIEWPORT);
        assertEquals("#aaaaaa", colorOf(important, "<div id=\"a\">x</div>"));
    }

    // ---- 規則の索引

    @Test
    public void testRuleIndexOnlyReturnsRulesTheElementCanMatch() {
        StyleSheet sheet = CssParser.parse("""
                #a { color: red; } .b { color: red; } p { color: red; } span.c { color: red; } * { color: red; }
                [title] { color: red; }
                """, VIEWPORT);
        HtmlNode root = HtmlParser.parse("<div id=\"a\" class=\"b\"></div><p id=\"p\"></p>");
        HtmlNode div = root.getElementById("a");
        HtmlNode paragraph = root.getElementById("p");
        List<String> forDiv = selectorsOf(sheet.index().candidates(div));
        List<String> forParagraph = selectorsOf(sheet.index().candidates(paragraph));
        assertTrue(forDiv.containsAll(List.of("#a", ".b", "*", "[title]")));
        assertFalse(forDiv.contains("p"));
        assertFalse(forDiv.contains("span.c"));
        assertTrue(forParagraph.containsAll(List.of("p", "*", "[title]")));
        assertFalse(forParagraph.contains("#a"));
        assertFalse(forParagraph.contains(".b"));
    }

    @Test
    public void testRuleIndexKeepsCascadeOrderWeakestFirst() {
        StyleSheet sheet = CssParser.parse(".x { color: red; } #a { color: blue; } div { color: lime; }", VIEWPORT);
        HtmlNode div = HtmlParser.parse("<div id=\"a\" class=\"x\"></div>").getElementById("a");
        assertEquals(List.of("div", ".x", "#a"), selectorsOf(sheet.index().candidates(div)));
    }

    // ---- 無効化セット

    @Test
    public void testInvalidationSetsNarrowTheAffectedRange() {
        StyleSheet sheet = CssParser.parse("""
                .open .item { color: red; }
                .card:has(.flag) { color: red; }
                .a + .b { color: red; }
                """, VIEWPORT);
        InvalidationSets sets = InvalidationSets.build(sheet.rules);
        assertTrue(sets.forClass("open").contains(Relation.DESCENDANTS));
        assertTrue(sets.forClass("flag").contains(Relation.ANCESTORS));
        assertTrue(sets.forClass("a").contains(Relation.FOLLOWING_SIBLINGS));
        // 読まれていない class は何も汚さない
        assertEquals(Set.of(), sets.forClass("unrelated"));
    }

    @Test
    public void testInvalidationSetsAreEmptyWhenNoRuleReadsMutableState() {
        StyleSheet sheet = CssParser.parse("div { color: red; } p { margin: 1px; }", VIEWPORT);
        assertTrue(InvalidationSets.build(sheet.rules).isEmpty());
    }

    // ---- キーワードの型

    @Test
    public void testKeywordPropertiesAreReadAsEnumsWithRegisteredInitialValue() {
        LayoutBox root = layout("""
                <style>.f { position: relative; box-sizing: border-box; overflow-x: hidden; }</style>
                <div class="f" id="a"></div><div id="b"></div>
                """);
        ComputedStyle styled = find(root, "a").style;
        ComputedStyle plain = find(root, "b").style;
        assertEquals(CssKeywords.Position.RELATIVE, styled.keywordOf("position", CssKeywords.Position.class));
        assertEquals(CssKeywords.BoxSizing.BORDER_BOX, styled.keywordOf("box-sizing", CssKeywords.BoxSizing.class));
        assertEquals(Overflow.HIDDEN, styled.overflowX());
        assertEquals(CssKeywords.Position.STATIC, plain.keywordOf("position", CssKeywords.Position.class));
        assertEquals(Overflow.VISIBLE, plain.overflowY());
    }

    // ---- helpers

    private static List<String> selectorsOf(List<StyleRule> rules) {
        return rules.stream().map(r -> r.selector).collect(Collectors.toList());
    }

    private static String colorOf(StyleSheet sheet, String html) {
        HtmlNode root = HtmlParser.parse(html);
        HtmlNode node = root.getElementById("a");
        assertNotNull(node);
        return StyleResolver.finalizeComputedStyle(
                StyleResolver.resolveDeclarations(node, Map.of(), sheet, SelectorMatcher.STATIC_ATTRIBUTES), sheet, Map.of())
                .get(CssProperty.COLOR);
    }

    private static LayoutBox layout(String html) {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument(html, "", VIEWPORT, VIEWPORT);
        return parser.layoutDocument(doc, VIEWPORT, VIEWPORT, Map.of(), new ScrollState(), FontResolver.FALLBACK);
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