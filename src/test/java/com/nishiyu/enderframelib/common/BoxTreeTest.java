package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.LayoutBox.Display;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
 * 軸 6「DOM と箱は別物」（W3）の検証。
 *
 * 矩形に出る振る舞い（匿名ブロック・{@code display: contents}・匿名表・BFC）は忠実度コーパスが
 * ブラウザと突き合わせている。ここでは箱の組み方そのものと、矩形に出ない振る舞い
 * （{@code ::marker} の文字列・CSS カウンタ・生成内容の評価）を固定する。
 */
public class BoxTreeTest {

    private static final int VIEWPORT_W = 320;
    private static final int VIEWPORT_H = 200;

    @Test
    public void testInlineRunsNextToBlocksAreWrappedInAnonymousBlocks() {
        LayoutBox root = layout("""
                <div id="parent"><span id="a">x</span><div id="block"></div><span id="b">y</span></div>
                """);
        LayoutBox parent = findElement(root, "parent");
        assertEquals(3, parent.children.size());
        assertTrue(parent.children.get(0).isAnonymous);
        assertSame(findElement(root, "a"), parent.children.get(0).children.getFirst());
        assertSame(findElement(root, "block"), parent.children.get(1));
        assertTrue(parent.children.get(2).isAnonymous);
        // 匿名ブロックは別々の id を持つ（再レイアウトしても同じ id になる）
        assertFalse(parent.children.get(0).node.getId().equals(parent.children.get(2).node.getId()));
    }

    @Test
    public void testWhitespaceOnlyRunsDoNotCreateAnonymousBlocks() {
        LayoutBox root = layout("<div id=\"parent\"><div id=\"a\"></div> <div id=\"b\"></div></div>");
        LayoutBox parent = findElement(root, "parent");
        assertEquals(2, parent.children.size());
        assertFalse(parent.children.stream().anyMatch(c -> c.isAnonymous));
    }

    @Test
    public void testInlineOnlyContentIsNotWrapped() {
        LayoutBox root = layout("<p id=\"p\">a <b>b</b> c</p>");
        LayoutBox p = findElement(root, "p");
        assertFalse(p.children.stream().anyMatch(c -> c.isAnonymous));
    }

    @Test
    public void testDisplayContentsPromotesChildrenAndHasNoBox() {
        LayoutBox root = layout("""
                <div id="row" style="display: flex;">
                  <div id="contents" style="display: contents;"><div id="b"></div><div id="c"></div></div>
                </div>
                """);
        LayoutBox row = findElement(root, "row");
        LayoutBox contents = findElement(root, "contents");
        assertNotNull(contents, "要素ツリーには残る（再スタイルと継承はこちらを辿る）");
        assertFalse(row.children.contains(contents));
        assertSame(findElement(root, "b"), row.children.get(0));
        assertSame(findElement(root, "c"), row.children.get(1));
        assertSame(row, findElement(root, "b").parent);
    }

    @Test
    public void testStrayTableCellsGetAnonymousTableAndRow() {
        LayoutBox root = layout("""
                <div id="wrap"><div id="a" style="display: table-cell;"></div><div id="b" style="display: table-cell;"></div></div>
                """);
        LayoutBox wrap = findElement(root, "wrap");
        assertEquals(1, wrap.children.size());
        LayoutBox table = wrap.children.getFirst();
        assertTrue(table.isAnonymous);
        assertEquals(Display.Inner.TABLE, table.display.inner());
        LayoutBox row = table.children.getFirst();
        assertTrue(row.isAnonymous);
        assertEquals(Display.Inner.TABLE_ROW, row.display.inner());
        assertEquals(List.of(findElement(root, "a"), findElement(root, "b")), row.children);
    }

    @Test
    public void testDisplayParsesTwoKeywordSyntax() {
        assertEquals(Display.parse("inline-block"), Display.parse("inline flow-root"));
        assertEquals(Display.parse("flex"), Display.parse("block flex"));
        assertTrue(Display.parse("list-item").listItem());
        assertTrue(Display.parse("inline list-item").isInlineLevel());
        assertNull(Display.parse("list-item flex"), "list-item は flow / flow-root とだけ組める");
        assertNull(Display.parse("bogus"));
    }

    @Test
    public void testListItemsGetMarkersFromListStyleType() {
        LayoutBox root = layout("""
                <ul><li id="u1">a</li><li id="u2">b</li></ul>
                <ol><li id="o1">a</li><li id="o2">b</li></ol>
                <ul style="list-style-type: none;"><li id="none">a</li></ul>
                <ul><li><ul><li id="nested">a</li></ul></li></ul>
                """);
        assertEquals("• ", markerText(findElement(root, "u1")));
        assertEquals("1. ", markerText(findElement(root, "o1")));
        assertEquals("2. ", markerText(findElement(root, "o2")));
        assertNull(markerText(findElement(root, "none")));
        assertEquals("◦ ", markerText(findElement(root, "nested")));
    }

    @Test
    public void testOlStartAndLiValue() {
        LayoutBox root = layout("""
                <ol start="3"><li id="a">a</li><li id="b">b</li><li id="c" value="10">c</li><li id="d">d</li></ol>
                <ol style="list-style-type: lower-roman;"><li>a</li><li>b</li><li>c</li><li id="roman">d</li></ol>
                """);
        assertEquals("3. ", markerText(findElement(root, "a")));
        assertEquals("4. ", markerText(findElement(root, "b")));
        assertEquals("10. ", markerText(findElement(root, "c")));
        assertEquals("11. ", markerText(findElement(root, "d")));
        assertEquals("iv. ", markerText(findElement(root, "roman")));
    }

    @Test
    public void testCounterStyleDrivesMarkersAndCounterFunction() {
        LayoutBox root = layout("""
                <style>
                  @counter-style parens { system: cyclic; symbols: "x" "y"; prefix: "("; suffix: ")"; }
                  @counter-style Foo { system: cyclic; symbols: "Z"; }
                  ol.parens { list-style-type: parens; }
                  ol.upper { list-style-type: Foo; }
                  ol.lower { list-style-type: foo; }
                  .parens li::before { content: counter(list-item, parens) " "; }
                </style>
                <ol class="parens"><li id="a">a</li><li id="b">b</li></ol>
                <ol class="upper"><li id="c">c</li></ol>
                <ol class="lower"><li id="d">d</li></ol>
                """);
        // マーカーは prefix / suffix を含み、counter() は含まない
        assertEquals("(x)", markerText(findElement(root, "a")));
        assertEquals("(y)", markerText(findElement(root, "b")));
        assertEquals("x ", beforeText(findElement(root, "a")));
        assertEquals("y ", beforeText(findElement(root, "b")));
        // 作者の名前は大文字小文字を区別する
        assertEquals("Z. ", markerText(findElement(root, "c")));
        assertEquals("1. ", markerText(findElement(root, "d")));
    }

    @Test
    public void testOlReversedCountsDown() {
        LayoutBox root = layout("""
                <ol reversed><li id="a">a</li><li id="b">b</li><li id="c">c</li></ol>
                <ol reversed start="5"><li id="d">d</li><li id="e">e</li><li id="f" value="9">f</li><li id="g">g</li></ol>
                <ol reversed><li id="outer">x<ol><li id="inner">y</li></ol></li><li id="outer2">z</li></ol>
                """);
        assertEquals("3. ", markerText(findElement(root, "a")));
        assertEquals("2. ", markerText(findElement(root, "b")));
        assertEquals("1. ", markerText(findElement(root, "c")));
        assertEquals("5. ", markerText(findElement(root, "d")));
        assertEquals("4. ", markerText(findElement(root, "e")));
        assertEquals("9. ", markerText(findElement(root, "f")));
        assertEquals("8. ", markerText(findElement(root, "g")));
        // 入れ子の逆順でない ol は普通に数え、外側は 2 項目から数える
        assertEquals("2. ", markerText(findElement(root, "outer")));
        assertEquals("1. ", markerText(findElement(root, "inner")));
        assertEquals("1. ", markerText(findElement(root, "outer2")));
    }

    @Test
    public void testListStyleImageReplacesTheMarkerText() {
        String svg = "data:image/svg+xml,%3Csvg%20xmlns%3D%27http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%27%20width%3D%2712%27%20height%3D%2710%27%2F%3E";
        LayoutBox root = layout("<ul style=\"list-style-image: url(" + svg + ");\"><li id=\"a\">a</li></ul>"
                + "<ul style=\"list-style-image: url(" + svg + "); list-style-type: square\"><li id=\"b\" style=\"list-style-image: none\">b</li></ul>");
        LayoutBox marker = findElement(root, "a").children.getFirst();
        assertTrue(marker.node.getId().endsWith("_marker"));
        LayoutBox image = marker.children.getFirst();
        assertEquals("img", image.node.tag);
        assertEquals(svg, image.node.attrs.get("src"));
        assertEquals(12, image.w, 0.01f);
        assertEquals(10, image.h, 0.01f);
        assertTrue(marker.w >= 12, "marker.w=" + marker.w);
        // list-style-image: none は list-style-type の記号に戻る
        assertEquals("▪ ", markerText(findElement(root, "b")));
    }

    @Test
    public void testOutsideMarkerSitsLeftOfTheItemWithoutTakingSpace() {
        LayoutBox root = layout("""
                <ul style="margin: 0; padding-left: 40px;"><li id="li" style="margin: 0; line-height: 20px;">text</li></ul>
                """);
        LayoutBox li = findElement(root, "li");
        LayoutBox marker = li.children.getFirst();
        assertTrue(marker.isOutsideMarker);
        assertEquals(li.x, marker.x + marker.w, 0.01f, "マーカーの右端はリスト項目の左端");
        assertEquals(20f, li.h, 0.01f, "外側のマーカーは高さに寄与しない");
        assertFalse(li.getFlowChildren().contains(marker));
    }

    @Test
    public void testInsideMarkerIsInlineContent() {
        LayoutBox root = layout("""
                <ul style="list-style-position: inside;"><li id="li">text</li></ul>
                """);
        LayoutBox marker = findElement(root, "li").children.getFirst();
        assertFalse(marker.isOutsideMarker);
        assertTrue(findElement(root, "li").getFlowChildren().contains(marker));
    }

    @Test
    public void testGeneratedContentConcatenatesStringsAttrAndCounters() {
        LayoutBox root = layout("""
                <style>
                  .t::before { content: "(" attr(title) ")"; }
                  ol.n { counter-reset: s; list-style-type: none; }
                  ol.n > li { counter-increment: s; }
                  ol.n > li::before { content: counters(s, ".") " "; }
                </style>
                <div id="t" class="t" title="hi"></div>
                <ol class="n"><li>a</li><li id="second">b<ol class="n"><li id="inner">c</li></ol></li></ol>
                """);
        assertEquals("(hi)", beforeText(findElement(root, "t")));
        assertEquals("2 ", beforeText(findElement(root, "second")));
        assertEquals("2.1 ", beforeText(findElement(root, "inner")));
    }

    @Test
    public void testPaintOnlyRestyleUpdatesMarkerAndAnonymousBoxes() {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument("""
                <style>
                  body { margin: 0; }
                  li { color: #111111; }
                  li:hover { color: #222222; }
                </style>
                <ul><li id="li">text<div>block</div></li></ul>
                """, "", VIEWPORT_W, VIEWPORT_H);
        InteractionState state = new InteractionState();
        LayoutBox root = parser.layoutDocument(doc, VIEWPORT_W, VIEWPORT_H, Map.of(), new ScrollState(),
                FontResolver.FALLBACK, state);
        LayoutBox li = findElement(root, "li");
        LayoutBox marker = li.children.getFirst();
        LayoutBox anonymous = li.children.get(1);
        assertTrue(anonymous.isAnonymous);
        assertEquals("#111111", marker.children.getFirst().style.get(CssProperty.COLOR));

        state.setHoveredNode(doc.root.getElementById("li"));
        RenderPipeline.RestyleResult res = parser.restyleLayoutTree(root, doc, VIEWPORT_W, VIEWPORT_H, Map.of(), state);
        assertFalse(res.layoutRequired(), "色だけの変化なのでボックスツリーは組み直されない");
        assertEquals("#222222", marker.children.getFirst().style.get(CssProperty.COLOR));
        assertEquals("#222222", anonymous.style.get(CssProperty.COLOR));
    }

    @Test
    public void testFragmentDocumentGetsImpliedBodyWithUaMargin() {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument("<div id=\"a\" style=\"height: 10px;\"></div>", "", VIEWPORT_W, VIEWPORT_H);
        LayoutBox root = parser.layoutDocument(doc, VIEWPORT_W, VIEWPORT_H, Map.of(), new ScrollState(), FontResolver.FALLBACK);
        // W7 まではパーサが body を補わず、断片だけの文書では ua.css の body { margin: 8px } が効かなかった
        LayoutBox a = findElement(root, "a");
        assertEquals("body", a.parent.node.tag);
        assertEquals(8, a.x, 0.01f);
        assertEquals(8, a.y, 0.01f);
        assertEquals(VIEWPORT_W - 16, a.w, 0.01f);
    }

    @Test
    public void testPseudoElementGoesThroughTheCascade() {
        LayoutBox root = layout("""
                <style>
                  #t::before { content: "id"; color: #111111 !important; }
                  .t::before { content: "class"; color: #222222; }
                  div::before { content: "type"; padding-left: 4px; padding: 1px; }
                  span { color: #333333; }
                  .t { color: #444444; }
                </style>
                <div class="t" id="t">x</div><div class="u" id="u">y</div>
                """);
        LayoutBox t = findElement(root, "t");
        // 以前は宣言を詳細度を見ずに重ねて style 属性に焼き込み、合成した span に span のルールも当たっていた
        assertEquals("id", beforeText(t));
        LayoutBox before = t.elementChildren.getFirst();
        assertEquals("#111111", before.style.get(CssProperty.COLOR));
        assertEquals(1f, before.pL, 0.01f, "後に書いたショートハンドが前のロングハンドに勝つ");

        LayoutBox u = findElement(root, "u");
        assertEquals("type", beforeText(u));
        assertNotEquals("#333333", u.elementChildren.getFirst().style.get(CssProperty.COLOR),
                "span のルールは擬似要素に当たらない");
    }

    @Test
    public void testNoPseudoBoxWithoutContentOrWhenDisplayNone() {
        LayoutBox root = layout("""
                <style>
                  #a::before { color: red; }
                  #b::before { content: none; }
                  #c::before { content: "x"; display: none; }
                  #d::after { content: ""; display: block; height: 3px; }
                </style>
                <div id="a"></div><div id="b"></div><div id="c"></div><div id="d"></div>
                """);
        for (String id : new String[]{"a", "b", "c"}) {
            assertTrue(findElement(root, id).elementChildren.stream().noneMatch(c -> c.node.isPseudoNode), id);
        }
        LayoutBox d = findElement(root, "d");
        assertEquals("d_after", d.elementChildren.getLast().node.getId(), "空文字列の content は箱を作る");
        assertEquals(3f, d.h, 0.01f);
    }

    @Test
    public void testStyleInCommentsAndRawTextIsNotAuthorCss() {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument("""
                <!-- <style>#a { height: 50px; }</style> -->
                <textarea id="ta"><style>#a { height: 60px; }</style></textarea>
                <pre id="pre">&lt;style&gt;#a { height: 70px; }&lt;/style&gt;</pre>
                <template><style>#a { height: 80px; }</style></template>
                <div id="a" style="height: 10px;"></div>
                """, "", VIEWPORT_W, VIEWPORT_H);
        // 以前は解析の前に正規表現で抜き出していたので、コメントや textarea の中まで作者 CSS になっていた
        assertFalse(doc.authorCss.contains("px"), doc.authorCss);
        assertEquals("<style>#a { height: 60px; }</style>", doc.root.getElementById("ta").getTextContent());
        assertTrue(doc.root.getElementById("pre").getTextContent().contains("<style>"));
        LayoutBox root = parser.layoutDocument(doc, VIEWPORT_W, VIEWPORT_H, Map.of(), new ScrollState(), FontResolver.FALLBACK);
        assertEquals(10f, findElement(root, "a").h, 0.01f);
    }

    @Test
    public void testStyleAndLinkAreCollectedInDocumentOrder() {
        var previous = UiDocumentCompiler.resourceTextResolver;
        UiDocumentCompiler.resourceTextResolver = href -> "/* " + href + " */";
        try {
            UiDocumentCompiler parser = new UiDocumentCompiler();
            Document doc = parser.parseDocument("""
                    <style>/* first */</style>
                    <link rel="stylesheet" href="second.css">
                    <link rel="icon" href="icon.png">
                    <div><style>/* third */</style></div>
                    """, "/* external */", VIEWPORT_W, VIEWPORT_H);
            assertEquals("/* external */\n/* first */\n/* second.css */\n/* third */\n", doc.authorCss);
        } finally {
            UiDocumentCompiler.resourceTextResolver = previous;
        }
    }

    // ==========================================

    private static LayoutBox layout(String html) {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument("<style>body { margin: 0; }</style>" + html, "", VIEWPORT_W, VIEWPORT_H);
        return parser.layoutDocument(doc, VIEWPORT_W, VIEWPORT_H, Map.of(), new ScrollState(), FontResolver.FALLBACK);
    }

    /** 要素ツリーから id で探す（display: contents の要素も見つかる）。 */
    private static LayoutBox findElement(LayoutBox box, String id) {
        if (id.equals(box.node.attrs.get("id"))) return box;
        for (LayoutBox child : box.elementChildren) {
            LayoutBox hit = findElement(child, id);
            if (hit != null) return hit;
        }
        return null;
    }

    private static String markerText(LayoutBox listItem) {
        if (listItem.children.isEmpty()) return null;
        LayoutBox first = listItem.children.getFirst();
        if (!first.node.getId().endsWith("_marker")) return null;
        return first.children.getFirst().displayText;
    }

    private static String beforeText(LayoutBox element) {
        List<LayoutBox> pseudo = new ArrayList<>();
        for (LayoutBox child : element.elementChildren) {
            if (child.node.isPseudoNode && child.node.getId().endsWith("_before")) pseudo.add(child);
        }
        assertEquals(1, pseudo.size());
        return pseudo.getFirst().elementChildren.getFirst().displayText;
    }
}