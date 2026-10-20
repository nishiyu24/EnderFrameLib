package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.core.GoldenFiles;
import com.nishiyu.enderframelib.core.dom.MarkdownParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MarkdownParserGoldenTest {

    @Test
    public void testGuidebookGolden() {
        // 実際に同梱している guidebook.md の変換結果をまるごと固定する
        String html = MarkdownParser.toHtml(GoldenFiles.readFixture("guidebook.md"));
        GoldenFiles.assertMatches("guidebook.html", html);
    }

    @Test
    public void testGuidebookDocumentWrapping() {
        // 純粋な Markdown 文書は <article class="markdown-body"> で包まれる
        String html = MarkdownParser.processDocumentOrBlocks(GoldenFiles.readFixture("guidebook.md"));
        assertTrue(html.startsWith("<article class=\"markdown-body\">"), html.substring(0, Math.min(80, html.length())));
        assertTrue(html.endsWith("</article>"));
    }

    /** toHtml は末尾に改行を含むため、比較の前に整える */
    private static String md(String markdown) {
        return MarkdownParser.toHtml(markdown).strip();
    }

    @Test
    public void testHeadings() {
        // 見出しにはアンカー用の id が自動付与される
        assertEquals("<h1 id=\"title\">Title</h1>", md("# Title"));
        assertEquals("<h3 id=\"sub\">Sub</h3>", md("### Sub"));
    }

    @Test
    public void testInlineEmphasis() {
        assertEquals("<p><strong>a</strong></p>", md("**a**"));
        assertEquals("<p><em>a</em></p>", md("*a*"));
        assertEquals("<p><del>a</del></p>", md("~~a~~"));
        assertEquals("<p><code>a</code></p>", md("`a`"));
    }

    @Test
    public void testLinkAndImage() {
        assertTrue(md("[x](https://example.com)").contains("<a href=\"https://example.com\""));
        assertTrue(md("![alt](item:minecraft:diamond)").contains("src=\"item:minecraft:diamond\""));
    }

    @Test
    public void testLists() {
        assertEquals("<ul>\n<li>a</li>\n<li>b</li>\n</ul>", md("- a\n- b"));
        assertEquals("<ol>\n<li>a</li>\n<li>b</li>\n</ol>", md("1. a\n2. b"));
    }

    @Test
    public void testFencedCodeBlockIsNotInterpreted() {
        String html = md("```java\nint a = **1**;\n```");
        assertTrue(html.startsWith("<pre"), html);
        assertTrue(html.contains("class=\"language-java\""), html);
        assertTrue(html.contains("**1**"), "コードブロック内の強調は展開されない: " + html);
    }

    @Test
    public void testHorizontalRule() {
        assertEquals("<hr />", md("---"));
    }

    @Test
    public void testEmptyInput() {
        assertEquals("", MarkdownParser.toHtml(""));
        assertEquals("", MarkdownParser.processDocumentOrBlocks(null));
    }
}