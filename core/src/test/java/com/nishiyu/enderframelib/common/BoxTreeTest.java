package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.box.Display;
import com.nishiyu.enderframelib.core.paint.tree.PaintView;
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
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;
import com.nishiyu.enderframelib.core.layout.model.Viewport;
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
        // 匿名ブロックの効き目は位置に出る: ブロックの前後のインラインはそれぞれ自分の行に置かれ、ブロックをまたいで同じ行に並ばない。
        // 描画用の木は匿名ブロックを節にしない（経路を 1 本にした M2c-3 以降。匿名の箱の組み方は BoxTreeCompilerTest が見る）
        PaintView root = layout("""
                <div id="parent" style="line-height: 20px"><span id="a">x</span><div id="block" style="height: 10px"></div><span id="b">y</span></div>
                """);
        PaintView a = findElement(root, "a"), block = findElement(root, "block"), b = findElement(root, "b");
        assertTrue(a.y() + a.h() <= block.y() + 0.01f, "前のインラインはブロックより上の行");
        assertTrue(b.y() >= block.y() + block.h() - 0.01f, "後のインラインはブロックより下の行");
        assertEquals(20f, block.y() - findElement(root, "parent").y(), 0.01f, "前の行（行の高さ 20px）の下にブロック");
    }

    @Test
    public void testWhitespaceOnlyRunsDoNotCreateAnonymousBlocks() {
        PaintView root = layout("<div id=\"parent\"><div id=\"a\"></div> <div id=\"b\"></div></div>");
        PaintView parent = findElement(root, "parent");
        assertEquals(2, parent.paintChildren().size());
        assertFalse(parent.paintChildren().stream().anyMatch(c -> c.isAnonymous));
    }

    @Test
    public void testInlineOnlyContentIsNotWrapped() {
        PaintView root = layout("<p id=\"p\">a <b>b</b> c</p>");
        PaintView p = findElement(root, "p");
        assertFalse(p.paintChildren().stream().anyMatch(c -> c.isAnonymous));
    }

    @Test
    public void testDisplayContentsPromotesChildrenAndHasNoBox() {
        PaintView root = layout("""
                <div id="row" style="display: flex;">
                  <div id="contents" style="display: contents;"><div id="b"></div><div id="c"></div></div>
                </div>
                """);
        PaintView row = findElement(root, "row");
        assertNull(findElement(root, "contents"), "display: contents の要素は箱を作らない（子が親の子に繰り上がる）");
        assertSame(findElement(root, "b"), row.paintChildren().get(0));
        assertSame(findElement(root, "c"), row.paintChildren().get(1));
        assertSame(row, findElement(root, "b").paintParent());
    }

    @Test
    public void testStrayTableCellsGetAnonymousTableAndRow() {
        PaintView root = layout("""
                <div id="wrap"><div id="a" style="display: table-cell;"></div><div id="b" style="display: table-cell;"></div></div>
                """);
        PaintView wrap = findElement(root, "wrap");
        assertEquals(1, wrap.paintChildren().size());
        PaintView table = wrap.paintChildren().getFirst();
        assertTrue(table.isAnonymous);
        assertEquals(Display.Inner.TABLE, table.display().inner());
        PaintView row = table.paintChildren().getFirst();
        assertTrue(row.isAnonymous);
        assertEquals(Display.Inner.TABLE_ROW, row.display().inner());
        assertEquals(List.of(findElement(root, "a"), findElement(root, "b")), row.paintChildren());
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
        PaintView root = layout("""
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
        PaintView root = layout("""
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
        PaintView root = layout("""
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
        assertEquals("x", beforeText(findElement(root, "a")).strip(), "末尾の空白は語の間の空き（語の文字には入らない）");
        assertEquals("y", beforeText(findElement(root, "b")).strip());
        // 作者の名前は大文字小文字を区別する
        assertEquals("Z. ", markerText(findElement(root, "c")));
        assertEquals("1. ", markerText(findElement(root, "d")));
    }

    @Test
    public void testOlReversedCountsDown() {
        PaintView root = layout("""
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
        PaintView root = layout("<ul style=\"list-style-image: url(" + svg + ");\"><li id=\"a\">a</li></ul>"
                + "<ul style=\"list-style-image: url(" + svg + "); list-style-type: square\"><li id=\"b\" style=\"list-style-image: none\">b</li></ul>");
        PaintView marker = marker(findElement(root, "a"));
        assertNotNull(marker);
        // 描画用の木は行の原子を行の文字の後ろに足す（並びは描く順に効かない）ので、画像の子を引いて確かめる
        PaintView image = marker.paintChildren().stream().filter(c -> "img".equals(c.node.tag)).findFirst().orElse(null);
        assertNotNull(image, "マーカーの中の画像");
        assertTrue(marker.paintChildren().stream().noneMatch(c -> c.node.text != null && !c.node.text.isBlank()), "記号の文字は無い");
        assertEquals(svg, image.node.attrs.get("src"));
        assertEquals(12, image.w(), 0.01f);
        assertEquals(10, image.h(), 0.01f);
        assertTrue(marker.w() >= 12, "marker.w()=" + marker.w());
        // list-style-image: none は list-style-type の記号に戻る
        assertEquals("▪ ", markerText(findElement(root, "b")));
    }

    @Test
    public void listStyleImageChangesMatchAFullRebuild() {
        String svg = "data:image/svg+xml,%3Csvg%20xmlns%3D%27http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%27%20width%3D%2712%27%20height%3D%2710%27%2F%3E";
        String big = svg.replace("12", "20");
        String html = "<style>body{margin:0}</style><ul id=u><li id=a>a</li><li id=b>b</li></ul>";
        for (String to : new String[]{"list-style-image:url(" + svg + ")", "list-style-image:url(" + big + ")"}) {
            IncrementalVsFullTest.assertIncrementalMatchesFull(html, v -> { },
                    v -> v.root().ownerDocument.getElementById("u").setAttribute("style", to));
        }
        IncrementalVsFullTest.assertIncrementalMatchesFull(html,
                v -> v.root().ownerDocument.getElementById("u").setAttribute("style", "list-style-image:url(" + svg + ")"),
                v -> v.root().ownerDocument.getElementById("u").setAttribute("style", "list-style-image:none"));
    }

    @Test
    public void testOutsideMarkerSitsLeftOfTheItemWithoutTakingSpace() {
        PaintView root = layout("""
                <ul style="margin: 0; padding-left: 40px;"><li id="li" style="margin: 0; line-height: 20px;">text</li></ul>
                """);
        PaintView li = findElement(root, "li");
        PaintView marker = marker(li);
        assertNotNull(marker);
        assertEquals(li.x(), marker.x() + marker.w(), 0.01f, "マーカーの右端はリスト項目の左端");
        assertEquals(20f, li.h(), 0.01f, "外側のマーカーは高さに寄与しない");
    }

    @Test
    public void testInsideMarkerIsInlineContent() {
        PaintView root = layout("""
                <ul style="list-style-position: inside;"><li id="li">text</li></ul>
                """);
        PaintView li = findElement(root, "li");
        PaintView marker = marker(li);
        assertNotNull(marker);
        assertTrue(marker.x() >= li.x(), "内側のマーカーはリスト項目の中の行に置かれる");
    }

    @Test
    public void testGeneratedContentConcatenatesStringsAttrAndCounters() {
        PaintView root = layout("""
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
        // 末尾の空白は行の中の語の間の空き（語の文字には入らない）
        assertEquals("2", beforeText(findElement(root, "second")).strip());
        assertEquals("2.1", beforeText(findElement(root, "inner")).strip());
    }

    @Test
    public void testPaintOnlyRestyleUpdatesMarkerAndAnonymousBoxes() {
        // :hover の色はマーカー（生成した箱）と、ブロックの横の文字（匿名ブロックに入る文字）まで届く
        try (StandaloneView view = new StandaloneView("""
                <style>
                  body { margin: 0; }
                  li { color: #111111; }
                  li:hover { color: #222222; }
                </style>
                <ul><li id="li">text<div id="d">block</div></li></ul>
                """, VIEWPORT_W, VIEWPORT_H, FontResolver.FALLBACK)) {
            PaintView li = findElement(view.ui().boxRoot(), "li");
            assertEquals("#111111", marker(li).paintChildren().getFirst().style().get(CssProperty.COLOR));
            assertEquals("#111111", textChild(li).style().get(CssProperty.COLOR));

            view.ui().setHoveredElement("li");
            view.ui().flushInvalidation();
            li = findElement(view.ui().boxRoot(), "li");
            assertEquals("#222222", marker(li).paintChildren().getFirst().style().get(CssProperty.COLOR));
            assertEquals("#222222", textChild(li).style().get(CssProperty.COLOR));
            assertEquals("#222222", findElement(li, "d").style().get(CssProperty.COLOR));
        }
    }

    @Test
    public void testFragmentDocumentGetsImpliedBodyWithUaMargin() {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument("<div id=\"a\" style=\"height: 10px;\"></div>", "", VIEWPORT_W, VIEWPORT_H);
        PaintView root = parser.layoutDocument(doc, VIEWPORT_W, VIEWPORT_H, Map.of(), new ScrollState(), FontResolver.FALLBACK);
        // W7 まではパーサが body を補わず、断片だけの文書では ua.css の body { margin: 8px } が効かなかった
        PaintView a = findElement(root, "a");
        assertEquals("body", a.paintParent().node.tag);
        assertEquals(8, a.x(), 0.01f);
        assertEquals(8, a.y(), 0.01f);
        assertEquals(VIEWPORT_W - 16, a.w(), 0.01f);
    }

    @Test
    public void testPseudoElementGoesThroughTheCascade() {
        PaintView root = layout("""
                <style>
                  #t::before { content: "id"; color: #111111 !important; }
                  .t::before { content: "class"; color: #222222; }
                  div::before { content: "type"; padding-left: 4px; padding: 1px; }
                  span { color: #333333; }
                  .t { color: #444444; }
                </style>
                <div class="t" id="t">x</div><div class="u" id="u">y</div>
                """);
        PaintView t = findElement(root, "t");
        // 以前は宣言を詳細度を見ずに重ねて style 属性に焼き込み、合成した span に span のルールも当たっていた
        assertEquals("id", beforeText(t));
        PaintView before = TestBoxes.elements(t).getFirst();
        assertEquals("#111111", before.style().get(CssProperty.COLOR));
        assertEquals(1f, before.pL(), 0.01f, "後に書いたショートハンドが前のロングハンドに勝つ");

        PaintView u = findElement(root, "u");
        assertEquals("type", beforeText(u));
        assertNotEquals("#333333", TestBoxes.elements(u).getFirst().style().get(CssProperty.COLOR),
                "span のルールは擬似要素に当たらない");
    }

    @Test
    public void testNoPseudoBoxWithoutContentOrWhenDisplayNone() {
        PaintView root = layout("""
                <style>
                  #a::before { color: red; }
                  #b::before { content: none; }
                  #c::before { content: "x"; display: none; }
                  #d::after { content: ""; display: block; height: 3px; }
                </style>
                <div id="a"></div><div id="b"></div><div id="c"></div><div id="d"></div>
                """);
        for (String id : new String[]{"a", "b", "c"}) {
            assertTrue(TestBoxes.elements(findElement(root, id)).stream().noneMatch(c -> c.node.isPseudoNode), id);
        }
        PaintView d = findElement(root, "d");
        assertEquals("d_after", TestBoxes.elements(d).getLast().node.getId(), "空文字列の content は箱を作る");
        assertEquals(3f, d.h(), 0.01f);
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
        PaintView root = parser.layoutDocument(doc, VIEWPORT_W, VIEWPORT_H, Map.of(), new ScrollState(), FontResolver.FALLBACK);
        assertEquals(10f, findElement(root, "a").h(), 0.01f);
    }

    @Test
    public void testStyleAndLinkAreCollectedInDocumentOrder() {
        var ctx = new com.nishiyu.enderframelib.core.EngineContext();
        ctx.updateHost(h -> h.withResourceTextResolver(href -> "/* " + href + " */"));
        com.nishiyu.enderframelib.core.EngineContext.runWith(ctx, () -> {
            UiDocumentCompiler parser = new UiDocumentCompiler();
            Document doc = parser.parseDocument("""
                    <style>/* first */</style>
                    <link rel="stylesheet" href="second.css">
                    <link rel="icon" href="icon.png">
                    <div><style>/* third */</style></div>
                    """, "/* external */", VIEWPORT_W, VIEWPORT_H);
            assertEquals("/* external */\n/* first */\n/* second.css */\n/* third */\n", doc.authorCss);
        });
    }

    // ==========================================

    private static PaintView layout(String html) {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument("<style>body { margin: 0; }</style>" + html, "", VIEWPORT_W, VIEWPORT_H);
        return parser.layoutDocument(doc, VIEWPORT_W, VIEWPORT_H, Map.of(), new ScrollState(), FontResolver.FALLBACK);
    }

    /** 要素ツリーから id で探す（display: contents の要素も見つかる）。 */
    private static PaintView findElement(PaintView box, String id) {
        if (id.equals(box.node.attrs.get("id"))) return box;
        for (PaintView child : TestBoxes.elements(box)) {
            PaintView hit = findElement(child, id);
            if (hit != null) return hit;
        }
        return null;
    }

    private static String markerText(PaintView listItem) {
        PaintView m = marker(listItem);
        return m == null || m.paintChildren().isEmpty() ? null : text(m.paintChildren().getFirst());
    }

    private static String beforeText(PaintView element) {
        List<PaintView> pseudo = new ArrayList<>();
        for (PaintView child : TestBoxes.elements(element)) {
            if (child.node.isPseudoNode && child.node.getId().endsWith("_before")) pseudo.add(child);
        }
        assertEquals(1, pseudo.size());
        PaintView content = pseudo.getFirst();
        return content.paintChildren().isEmpty() ? text(content) : text(content.paintChildren().getFirst());
    }

    /** 文字の箱の文字（行に置いた語をつないだもの）。 */
    private static String text(PaintView box) {
        StringBuilder sb = new StringBuilder();
        for (var run : box.runs()) if (run.text() != null) sb.append(run.text());
        return sb.toString();
    }

    /** リスト項目の {@code ::marker} の箱（無ければ null。描画用の木では項目の中身の後ろに置かれる）。 */
    private static PaintView marker(PaintView listItem) {
        for (PaintView c : listItem.paintChildren()) {
            if (c.node != null && c.node.getId() != null && c.node.getId().endsWith("_marker")) return c;
        }
        return null;
    }

    /** 最初の文字の子の箱。 */
    private static PaintView textChild(PaintView box) {
        for (PaintView c : box.paintChildren()) {
            if (c.node != null && c.node.nodeType == com.nishiyu.enderframelib.core.dom.node.NodeType.TEXT) return c;
        }
        return null;
    }
}
