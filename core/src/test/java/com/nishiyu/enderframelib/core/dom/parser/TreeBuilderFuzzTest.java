package com.nishiyu.enderframelib.core.dom.parser;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ツリー構築（{@link TreeBuilder}・{@link ActiveFormatting}・{@link TableModes}）に、書式要素の誤った入れ子・表の中の
 * 置き場違い・template・外来要素を混ぜた乱数のタグの並びを読ませ、どの入力でも木の形の不変条件が保たれること。
 *
 * <p>M5-2 で {@code HtmlParser}（1,214 行）を 1 回の解析の状態・書式要素の一覧・表の挿入モード・文字の整え方に分けた。
 * 分ける前後で同じ入力が同じ木になることは、環境変数 {@code PARSER_FUZZ_DUMP=<file>} で木を書き出して分ける前のコミットと
 * 突き合わせて確かめた（ここに旧版との一致の番人は置かない。計画書 §5 運用ルール 6）。</p>
 */
class TreeBuilderFuzzTest {

    private static final String[] TAGS = {
            "html", "head", "body", "p", "div", "span", "b", "i", "a", "em", "strong", "nobr", "font", "u", "s", "code",
            "table", "tbody", "thead", "tfoot", "tr", "td", "th", "caption", "colgroup", "col",
            "ul", "ol", "li", "dl", "dt", "dd", "h1", "h2", "select", "option", "optgroup", "form", "button",
            "template", "svg", "rect", "math", "ruby", "rb", "rt", "rp", "rtc", "pre", "textarea", "style", "title",
            "br", "hr", "img", "image", "input", "marquee", "object", "applet", "section", "address", "meta", "link"};

    private static final String[] TEXTS = {"x", " ", "\n", "a b", "&amp;", "\t y  z", "\r\nq"};

    private static final int CASES = 600;

    /** 乱数のタグの並び（属性・自己終了・注釈・文字を混ぜる）。 */
    static String soup(Random r) {
        StringBuilder sb = new StringBuilder();
        int n = 4 + r.nextInt(40);
        for (int k = 0; k < n; k++) {
            int kind = r.nextInt(10);
            String tag = TAGS[r.nextInt(TAGS.length)];
            if (kind < 5) {
                sb.append('<').append(tag);
                if (r.nextInt(4) == 0) sb.append(" class=\"c").append(r.nextInt(3)).append('"');
                if (r.nextInt(8) == 0) sb.append(" type=\"hidden\"");
                if (r.nextInt(10) == 0) sb.append(" inert");
                sb.append(r.nextInt(6) == 0 ? "/>" : ">");
            } else if (kind < 8) {
                sb.append("</").append(tag).append('>');
            } else if (kind < 9) {
                sb.append(TEXTS[r.nextInt(TEXTS.length)]);
            } else {
                sb.append("<!--c-->");
            }
        }
        return sb.toString();
    }

    /** 木を 1 行ずつ書き出す（属性は書かれた順、template は中身を content として）。 */
    static void dump(HtmlNode node, String indent, StringBuilder out) {
        if (node.nodeType == NodeType.TEXT) {
            out.append(indent).append('"').append(node.text.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")).append("\"\n");
            return;
        }
        out.append(indent).append('<').append(node.tag);
        for (Map.Entry<String, String> a : node.attrs.entrySet()) out.append(' ').append(a.getKey()).append("=\"").append(a.getValue()).append('"');
        out.append(">\n");
        for (HtmlNode c : node.children) dump(c, indent + "  ", out);
        if (node instanceof HtmlElement el && el.isTemplateElement()) {
            out.append(indent).append("  content\n");
            for (HtmlNode c : el.getContent().children) dump(c, indent + "    ", out);
        }
    }

    /** 親子の結び・位置・同じ節が 2 度現れないこと、template の子は中身にだけあること、表の部品の置き場。 */
    private static void assertWellFormed(HtmlNode node, Set<HtmlNode> seen, boolean inTemplateContentTop, String rootTag, String input) {
        assertTrue(seen.add(node), () -> "同じ節が 2 度現れた: " + input);
        String tag = node.parent == null ? rootTag : node.tag; // 断片の根は文脈の要素の代わり
        for (int i = 0; i < node.children.size(); i++) {
            HtmlNode c = node.children.get(i);
            assertSame(node, c.parent, () -> "親の結びが食い違う: " + input);
            assertEquals(i, c.indexInParent(), () -> "子の位置が食い違う: " + input);
            if (c.nodeType == NodeType.TEXT) {
                assertFalse(c.text.isEmpty(), () -> "空の文字の節: " + input);
                if (i > 0) assertNotEquals(NodeType.TEXT, node.children.get(i - 1).nodeType, () -> "隣り合う文字の節は繋がる: " + input);
                continue;
            }
            if (!inTemplateContentTop && !inForeign(c)) {
                if (tag.equals("table")) {
                    assertFalse(Set.of("tr", "td", "th").contains(c.tag), () -> "表の直下に " + c.tag + ": " + input);
                }
                if (c.tag.equals("td") || c.tag.equals("th")) {
                    assertEquals("tr", tag, () -> "セルの親が tr でない: " + input);
                }
            }
            assertWellFormed(c, seen, false, rootTag, input);
        }
        if (node instanceof HtmlElement el && el.isTemplateElement()) {
            assertTrue(node.children.isEmpty(), () -> "template の子は中身（content）に入る: " + input);
            HtmlNode content = el.getContent();
            for (int i = 0; i < content.children.size(); i++) {
                assertSame(content, content.children.get(i).parent, () -> "中身の親の結び: " + input);
                assertWellFormed(content.children.get(i), seen, true, rootTag, input);
            }
        }
    }

    private static boolean inForeign(HtmlNode n) {
        for (HtmlNode cur = n.parent; cur != null; cur = cur.parent) {
            if (cur.tag.equals("svg") || cur.tag.equals("math")) return true;
        }
        return false;
    }

    private static final String[] CONTEXTS = {"body", "tbody", "tr", "table", "template", "select", "ul", "td"};

    /** 環境変数 {@code PARSER_FUZZ_DUMP} があれば、全ての入力の木をそこへ書き出す（不変条件を確かめる前に。分ける前の版との突き合わせ用）。 */
    private static void dumpAllIfAsked() throws IOException {
        String path = System.getenv("PARSER_FUZZ_DUMP");
        if (path == null) return;
        Random r = new Random(52);
        StringBuilder dumpOut = new StringBuilder();
        for (int k = 0; k < CASES; k++) {
            String html = soup(r);
            String context = CONTEXTS[k % CONTEXTS.length];
            dumpOut.append("#").append(k).append(' ').append(context).append('\n');
            dump(HtmlParser.parseDocument(html), "", dumpOut);
            dumpOut.append("-- fragment\n");
            dump(HtmlParser.parseFragment(html, context), "", dumpOut);
        }
        Files.writeString(Path.of(path), dumpOut.toString(), StandardCharsets.UTF_8);
    }

    @Test
    void randomSoupKeepsTreeInvariants() throws IOException {
        dumpAllIfAsked();
        Random r = new Random(52);
        for (int k = 0; k < CASES; k++) {
            String html = soup(r);
            HtmlNode doc = HtmlParser.parseDocument(html);
            assertInstanceOf(HtmlDocument.class, doc);
            List<HtmlNode> top = doc.children.stream().filter(c -> c.nodeType == NodeType.ELEMENT).toList();
            assertEquals(1, top.size(), () -> "文書の要素は html の 1 つ: " + html);
            HtmlNode htmlEl = top.get(0);
            assertEquals("html", htmlEl.tag);
            List<String> sections = htmlEl.children.stream().filter(c -> c.nodeType == NodeType.ELEMENT).map(c -> c.tag).toList();
            assertEquals(List.of("head", "body"), sections, () -> "html の下は head と body: " + html);
            assertWellFormed(doc, Collections.newSetFromMap(new IdentityHashMap<>()), false, "#document", html);

            String context = CONTEXTS[k % CONTEXTS.length];
            HtmlNode frag = HtmlParser.parseFragment(html, context);
            assertWellFormed(frag, Collections.newSetFromMap(new IdentityHashMap<>()), context.equals("template"), context, html);
        }
    }

    @Test
    void adoptionAgencyAndFosterParentingStillReshapeTheTree() {
        StringBuilder out = new StringBuilder();
        dump(HtmlParser.parse("<b><i>1</b>2</i>"), "", out);
        assertEquals("<root id=\"html_root\">\n  <b>\n    <i>\n      \"1\"\n  <i>\n    \"2\"\n", out.toString(), "誤った入れ子は adoption agency で組み替える");

        out.setLength(0);
        dump(HtmlParser.parse("<table>a<tr><td>1</td></tr></table>"), "", out);
        assertEquals("<root id=\"html_root\">\n  \"a\"\n  <table>\n    <tbody>\n      <tr>\n        <td>\n          \"1\"\n",
                out.toString(), "表の中の文字は表の前へ出し、tr は暗黙の tbody に入れる");

        out.setLength(0);
        dump(HtmlParser.parse("<p><b>a<p>b"), "", out);
        assertEquals("<root id=\"html_root\">\n  <p>\n    <b>\n      \"a\"\n  <p>\n    <b>\n      \"b\"\n", out.toString(),
                "閉じた段落の書式要素は次の段落で開き直す");
    }
}
