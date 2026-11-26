package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.paint.tree.PaintView;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code <template component="…">} ＋ {@code <slot>}：定義の写しをホストの子にし、ライト DOM を slot へ投影する。 */
public class ComponentSlotTest {

    private static final String CARD = """
            <template component="my-card">
              <div class="card"><h3 id="head"><slot name="title">既定の題</slot></h3>
                <div id="body"><slot></slot></div></div>
            </template>
            """;

    private static HtmlNode parse(String html) {
        return new UiDocumentCompiler().parseDocument(html, "", 400, 300).root;
    }

    private static HtmlNode byId(HtmlNode node, String id) {
        if (node.nodeType == NodeType.ELEMENT && id.equals(node.attrs.get("id"))) return node;
        for (HtmlNode c : node.children) {
            HtmlNode hit = byId(c, id);
            if (hit != null) return hit;
        }
        return null;
    }

    private static String text(HtmlNode node) {
        return node.getTextContent().replaceAll("\\s+", " ").trim();
    }

    private static boolean anyTag(HtmlNode node, String tag) {
        if (node.nodeType == NodeType.ELEMENT && tag.equals(node.tag)) return true;
        for (HtmlNode c : node.children) if (anyTag(c, tag)) return true;
        return false;
    }

    @Test
    public void namedAndDefaultSlotsReceiveTheLightDom() {
        HtmlNode root = parse(CARD + "<my-card id=\"h\" class=\"big\"><span slot=\"title\" id=\"t\">題名</span>本文<b id=\"b\">太</b></my-card>");
        HtmlNode host = byId(root, "h");
        assertNotNull(host);
        assertEquals("big", host.attrs.get("class"), "ホストの属性は残る");
        assertEquals("題名", text(byId(root, "head")));
        assertEquals("本文太", text(byId(root, "body")));
        assertFalse(anyTag(root, "slot"), "slot は残らない");
        assertFalse(anyTag(root, "template"), "定義は木から外れる");
    }

    @Test
    public void emptyAssignmentShowsTheFallbackContent() {
        HtmlNode root = parse(CARD + "<my-card id=\"h\">   </my-card>");
        assertEquals("既定の題", text(byId(root, "head")));
        assertEquals("", text(byId(root, "body")));
    }

    @Test
    public void everyInstanceGetsItsOwnCopy() {
        HtmlNode root = parse(CARD + "<my-card><i slot=\"title\">一</i>x</my-card><my-card><i slot=\"title\">二</i>y</my-card>");
        StringBuilder titles = new StringBuilder();
        collectTitles(root, titles);
        assertEquals("一二", titles.toString());
    }

    private static void collectTitles(HtmlNode node, StringBuilder out) {
        if (node.nodeType == NodeType.ELEMENT && "h3".equals(node.tag)) out.append(text(node));
        for (HtmlNode c : node.children) collectTitles(c, out);
    }

    @Test
    public void nestedComponentsAndSlotForwarding() {
        HtmlNode root = parse("""
                <template component="x-wrap"><section id="w"><slot></slot></section></template>
                <template component="x-panel"><x-wrap id="inner"><p id="p"><slot></slot></p></x-wrap></template>
                <x-panel id="o">中身</x-panel>
                """);
        assertEquals("中身", text(byId(root, "p")), "外のライト DOM が内側の slot まで運ばれる");
        assertEquals("section", byId(root, "w").tag);
        assertEquals(byId(root, "inner"), byId(root, "w").parent, "入れ子のホストの下に定義の写しが入る");
        assertFalse(anyTag(root, "slot"));
    }

    @Test
    public void recursiveDefinitionsStop() {
        HtmlNode root = parse("<template component=\"x-loop\"><div id=\"d\"><x-loop></x-loop></div></template><x-loop id=\"o\"></x-loop>");
        assertNotNull(byId(root, "d"), "1 段は展開される");
        assertNotNull(byId(root, "o"));
    }

    @Test
    public void namesWithoutAHyphenAreNotDefinitions() {
        HtmlNode root = parse("<template component=\"card\"><b>x</b></template><card id=\"c\">y</card>");
        assertEquals("y", text(byId(root, "c")), "ハイフンの無い名前は部品にならない");
    }

    @Test
    public void componentLaysOutAndScopesItsStyle() {
        String html = CARD + """
                <style>@scope (my-card) { h3 { color: #123456; } }</style>
                <my-card id="h"><span slot="title">T</span><em id="e">本</em></my-card>
                <h3 id="other">外</h3>
                """;
        UiDocumentCompiler compiler = new UiDocumentCompiler();
        Document doc = compiler.parseDocument(html, "", 400, 300);
        PaintView box = compiler.layoutDocument(doc, 400, 300, Map.of(), new ScrollState(), FontResolver.FALLBACK);
        PaintView head = find(box, "head");
        assertNotNull(head);
        assertEquals("#123456", head.style().get(CssProperty.COLOR), "コンポーネントの中だけに @scope が当たる");
        PaintView other = find(box, "other");
        assertNotNull(other);
        assertTrue(!"#123456".equals(other.style().get(CssProperty.COLOR)), "外の h3 は当たらない");
        PaintView em = find(box, "e");
        assertNotNull(em);
        assertTrue(em.w() > 0 && em.h() > 0, "投影した要素にも寸法がある");
        assertNull(find(box, "missing"));
    }

    private static PaintView find(PaintView box, String id) {
        if (box.node != null && id.equals(box.node.getId())) return box;
        for (PaintView child : TestBoxes.elements(box)) {
            PaintView hit = find(child, id);
            if (hit != null) return hit;
        }
        return null;
    }
}
