package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxKind;
import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2b-4 の表（計画書 §3.4.1）: 新経路の {@link TableFlow}（格子 {@link TableStructure}・列幅 {@link TableColumns}・枠線の重ね
 * {@link TableBorders}）と入力側の匿名の表の箱（{@code TableBoxes}）が CSS 2.2 §17 の規則どおりに置くこと。期待値は仕様から手で求めた数
 * （旧経路との比較は {@link LegacyParityTest#tableMatchesLegacy}）。文字を含まない固定の大きさの箱だけで組むので、書体に依らない。
 */
class TableLayoutTest {
    private static final float EPS = 0.02f;
    /** 中身の箱（固定の大きさ）。 */
    private static String box(int w, int h) {
        return "<div style='width:" + w + "px;height:" + h + "px'></div>";
    }

    private static float[] at(String html, String id) {
        float[] r = SpikeView.find(new SpikeView(html, 800, 600).frame(), id);
        assertNotNull(r, id);
        return r;
    }

    private static String doc(String body) {
        return "<html><head><style>td,th{padding:0}</style></head><body style='margin:0'>" + body + "</body></html>";
    }

    /** 自動レイアウト: 列幅は列の最大幅、{@code border-spacing} は列・行の間と外周に入る。 */
    @Test
    void autoLayoutWithBorderSpacing() {
        String html = doc("<div id=t style='display:table;border-spacing:2px 4px'>"
                + "<div style='display:table-row'><div id=a style='display:table-cell'>" + box(50, 10) + "</div>"
                + "<div id=b style='display:table-cell'>" + box(30, 20) + "</div></div>"
                + "<div style='display:table-row'><div id=c style='display:table-cell'>" + box(70, 5) + "</div>"
                + "<div id=d style='display:table-cell'></div></div></div>");
        // 列 0 = max(50, 70) = 70・列 1 = 30。表の幅 = 70 + 30 + 2 × 3 = 106。行 0 の高さ 20・行 1 の高さ 5
        assertEquals(106, at(html, "t")[2], EPS);
        assertEquals(4 + 20 + 4 + 5 + 4, at(html, "t")[3], EPS);
        assertEquals(2, at(html, "a")[0], EPS);
        assertEquals(4, at(html, "a")[1], EPS);
        assertEquals(70, at(html, "a")[2], EPS);
        assertEquals(20, at(html, "a")[3], EPS, "セルは行の高さまで伸びる");
        assertEquals(74, at(html, "b")[0], EPS);
        assertEquals(28, at(html, "c")[1], EPS);
        assertEquals(30, at(html, "d")[2], EPS);
    }

    /** {@code colspan} / {@code rowspan}: 格子の位置を飛ばし、またがるセルの足りない幅は最大幅の比で、高さは最後の行に足す。 */
    @Test
    void spansPlaceCellsOnTheGrid() {
        String html = doc("<table style='border-spacing:0'><tr><td id=a rowspan=2>" + box(20, 60) + "</td><td id=b>" + box(40, 10) + "</td>"
                + "<td id=c>" + box(10, 10) + "</td></tr><tr><td id=d colspan=2>" + box(100, 10) + "</td></tr></table>");
        // 列 1・2 の最大幅 40・10 に、d の足りない 50 を 4:1 で配る → 80・20
        assertEquals(20, at(html, "b")[0], EPS);
        assertEquals(80, at(html, "b")[2], EPS);
        assertEquals(100, at(html, "c")[0], EPS);
        assertEquals(20, at(html, "d")[0], EPS);
        assertEquals(10, at(html, "d")[1], EPS);
        assertEquals(100, at(html, "d")[2], EPS);
        // a は 2 行にまたがり、足りない 60 - 20 = 40 を最後の行に足す
        assertEquals(60, at(html, "a")[3], EPS);
        assertEquals(50, at(html, "d")[3], EPS);
    }

    /** {@code border-collapse}: 隣り合う枠線は太い方の 1 本で、両側が半分ずつ。表の外周は表の枠線と外側のセルの太い方。 */
    @Test
    void collapsedBordersAreShared() {
        String html = doc("<table id=t style='border-collapse:collapse;border:6px solid'><tr>"
                + "<td id=a style='border:2px solid'>" + box(40, 10) + "</td><td id=b style='border:4px solid'>" + box(40, 10) + "</td>"
                + "</tr></table>");
        // 外周: 左 max(6, 2) = 6 → 表 3・セル 3。a と b の間: max(2, 4) = 4 → 2 ずつ。右: max(6, 4) = 6 → 3 ずつ
        // a の幅 = 3 + 40 + 2 = 45、b = 2 + 40 + 3 = 45。表 = 3 + 45 + 45 + 3 = 96
        assertEquals(96, at(html, "t")[2], EPS);
        assertEquals(3, at(html, "a")[0], EPS);
        assertEquals(45, at(html, "a")[2], EPS);
        assertEquals(48, at(html, "b")[0], EPS);
        assertEquals(45, at(html, "b")[2], EPS);
        assertEquals(3 + 3 + 10 + 3 + 3, at(html, "t")[3], EPS);
    }

    /**
     * キャプションは表の内容の上（{@code caption-side: bottom} なら下）で、表の高さの指定は行の部分の下限（キャプションはその外）。
     * 余った高さは行へ配る（旧経路は配らず、行の下に空きを残していた）。
     */
    @Test
    void captionsAndTableHeight() {
        String html = doc("<div id=t style='display:table;height:50px'>"
                + "<div id=top style='display:table-caption;height:10px'></div>"
                + "<div id=bot style='display:table-caption;caption-side:bottom;height:7px'></div>"
                + "<div id=c style='display:table-cell'>" + box(30, 20) + "</div></div>");
        assertEquals(0, at(html, "top")[1], EPS);
        assertEquals(10, at(html, "c")[1], EPS);
        assertEquals(30, at(html, "top")[2], EPS, "キャプションは表の内容の幅");
        assertEquals(50, at(html, "c")[3], EPS, "行は表の高さまで伸びる");
        assertEquals(60, at(html, "bot")[1], EPS);
        assertEquals(50 + 10 + 7, at(html, "t")[3], EPS);
    }

    /** セルの {@code vertical-align}: top / middle / bottom / baseline（中身のずれは断片の内容の原点に表す）。 */
    @Test
    void cellVerticalAlign() {
        String html = doc("<div style='display:table'><div style='display:table-row'>"
                + "<div style='display:table-cell'>" + box(10, 60) + "</div>"
                + "<div style='display:table-cell;vertical-align:middle'><div id=m style='width:10px;height:20px'></div></div>"
                + "<div style='display:table-cell;vertical-align:bottom'><div id=b style='width:10px;height:20px'></div></div>"
                + "<div style='display:table-cell;vertical-align:top'><div id=t style='width:10px;height:20px'></div></div>"
                + "</div></div>");
        assertEquals(20, at(html, "m")[1], EPS);
        assertEquals(40, at(html, "b")[1], EPS);
        assertEquals(0, at(html, "t")[1], EPS);
    }

    /** {@code table-layout: fixed}: 列要素と 1 行目のセルの幅で決め、残りを幅の無い列で等分する（中身に依らない）。 */
    @Test
    void fixedLayout() {
        String html = doc("<table style='table-layout:fixed;width:300px;border-spacing:0'><col style='width:100px'>"
                + "<tr><td id=a>" + box(200, 5) + "</td><td id=b></td><td id=c style='width:50px'></td></tr></table>");
        assertEquals(100, at(html, "a")[2], EPS);
        assertEquals(150, at(html, "b")[2], EPS);
        assertEquals(250, at(html, "c")[0], EPS);
    }

    /** {@code width: N%} の列は表の幅の割合で、残りを他の列が分ける。表は割合の列の最大幅を収める幅まで広がる。 */
    @Test
    void percentColumns() {
        String html = doc("<table id=t style='width:400px;border-spacing:0'><tr><td id=a style='width:25%'></td><td id=b>"
                + box(20, 5) + "</td></tr></table>");
        assertEquals(100, at(html, "a")[2], EPS);
        assertEquals(100, at(html, "b")[0], EPS);
        assertEquals(300, at(html, "b")[2], EPS);
    }

    /** 表の幅は列の最小幅の和より狭くならない（指定の幅・{@code max-width} より優先。§17.5.2）。auto の余白で中央に寄る。 */
    @Test
    void minContentFloorAndAutoMargins() {
        String html = doc("<div id=t style='display:table;width:10px;max-width:10px'><div style='display:table-cell'>" + box(100, 10) + "</div></div>"
                + "<div id=u style='display:table;margin:0 auto'><div style='display:table-cell'>" + box(200, 10) + "</div></div>");
        assertEquals(100, at(html, "t")[2], EPS);
        assertEquals(300, at(html, "u")[0], EPS);
    }

    /**
     * 匿名の表の箱（CSS 2.2 §17.2.1）: 表の外のセルの並びは匿名の行と表で包まれ、表の直下の文字は匿名の行とセルに入る。間の空白は
     * 箱を作らない。DOM の子の並び（{@code children}）は変えない。
     */
    @Test
    void anonymousTableBoxes() {
        String html = doc("<div id=p><div id=a style='display:table-cell'>" + box(40, 10) + "</div>\n  "
                + "<div id=b style='display:table-cell'>" + box(60, 30) + "</div></div>"
                + "<div id=q style='display:table'>" + box(25, 25) + "</div>");
        SpikeView v = new SpikeView(html, 800, 600);
        v.frame();
        assertEquals(40, SpikeView.find(v.layout.root(), "b")[0], EPS, "a と b は同じ匿名の行に並ぶ");
        assertEquals(0, SpikeView.find(v.layout.root(), "b")[1], EPS);
        assertEquals(30, SpikeView.find(v.layout.root(), "a")[3], EPS, "同じ行のセルは行の高さまで伸びる");
        BoxNode p = find(v.boxRoot, "p");
        assertEquals(3, p.children.size(), "DOM の子と 1 対 1（空白の文字を含む）");
        assertEquals(1, p.flow.size());
        BoxNode table = p.flow.get(0);
        assertEquals(BoxKind.TABLE, table.kind);
        assertEquals(BoxNode.Pseudo.ANONYMOUS, table.pseudo);
        assertEquals(BoxKind.TABLE_ROW, table.flow.get(0).kind);
        BoxNode q = find(v.boxRoot, "q");
        assertEquals(BoxKind.TABLE_ROW, q.flow.get(0).kind);
        assertEquals(BoxKind.TABLE_CELL, q.flow.get(0).flow.get(0).kind);
        assertEquals(25, at(html, "q")[2], EPS);
    }

    /** 触れていない表の匿名の箱は、親を作り直しても前回の参照のまま（下流のキャッシュが当たる）。 */
    @Test
    void anonymousBoxesAreReusedAcrossFrames() {
        String html = doc("<div id=p><div id=a style='display:table-cell'>" + box(40, 10) + "</div><div id=x>" + box(5, 5) + "</div></div>");
        SpikeView v = new SpikeView(html, 800, 600);
        v.frame();
        BoxNode anon = find(v.boxRoot, "p").flow.get(0);
        ((HtmlElement) v.byId("x")).setAttribute("style", "padding:3px");
        v.frame();
        BoxNode p = find(v.boxRoot, "p");
        assertSame(anon, p.flow.get(0), "包んだ子が同じ匿名の表は同じ参照");
        assertTrue(p.flow.get(1).node == v.byId("x"));
    }

    /** {@code colspan} の属性だけの変化も箱の作り直しとして届き、格子が変わる（入力側の写し {@code BoxNode.span}）。 */
    @Test
    void spanAttributeChangeRelayouts() {
        String html = doc("<table style='border-spacing:0'><tr><td id=a>" + box(30, 10) + "</td><td id=b>" + box(30, 10) + "</td></tr>"
                + "<tr><td id=c>" + box(10, 10) + "</td><td id=d>" + box(10, 10) + "</td></tr></table>");
        SpikeView v = new SpikeView(html, 800, 600);
        v.frame();
        assertEquals(30, SpikeView.find(v.layout.root(), "d")[0], EPS);
        ((HtmlElement) v.byId("c")).setAttribute("colspan", "2");
        ((HtmlElement) v.byId("d")).setAttribute("style", "display:none");
        LayoutFragment inc = v.frame();
        assertEquals(60, SpikeView.find(inc, "c")[2], EPS);
        assertEquals(SpikeView.dump(v.scratch()), SpikeView.dump(inc));
    }

    /**
     * 行を足しても、列幅の変わらない既存のセルは前回の断片を使い回す（セルの中身の高さで整形した断片は {@link FlowChunk#slots} に残り、
     * 行の高さに伸ばした断片と取り違えない）。
     */
    @Test
    void insertedRowReusesOtherCells() {
        String html = doc("<table style='border-spacing:0'><tbody id=tb><tr><td id=a style='vertical-align:middle'>" + box(30, 10) + "</td>"
                + "<td id=b>" + box(30, 30) + "</td></tr></tbody></table>");
        SpikeView v = new SpikeView(html, 800, 600);
        v.frame();
        v.layout.session().cache().trackPerBox(true);
        HtmlElement tr = new HtmlElement("tr");
        HtmlElement td = new HtmlElement("td");
        td.setAttribute("style", "width:20px");
        tr.appendChild(td);
        v.byId("tb").appendChild(tr);
        LayoutFragment inc = v.frame();
        assertEquals(0, v.layout.session().cache().perBox(find(v.boxRoot, "a"))[0], "伸ばしたセルの元の断片が当たる");
        assertEquals(0, v.layout.session().cache().perBox(find(v.boxRoot, "b"))[0]);
        assertEquals(SpikeView.dump(v.scratch()), SpikeView.dump(inc));
    }

    /** {@code visibility: collapse} の行は高さも前後の間隔も持たない。 */
    @Test
    void collapsedRow() {
        String html = doc("<table style='border-spacing:0 5px'><tr><td>" + box(10, 10) + "</td></tr>"
                + "<tr style='visibility:collapse'><td>" + box(10, 30) + "</td></tr><tr><td id=c>" + box(10, 10) + "</td></tr></table>");
        assertEquals(20, at(html, "c")[1], EPS);
    }

    private static BoxNode find(BoxNode b, String id) {
        if (b.node != null && b.pseudo == BoxNode.Pseudo.NONE && id.equals(b.node.attrs.get("id"))) return b;
        for (int i = 0; i < b.children.size(); i++) {
            BoxNode r = find(b.children.get(i), id);
            if (r != null) return r;
        }
        return null;
    }
}
