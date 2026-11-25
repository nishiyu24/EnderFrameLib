package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontStyle;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2b-1: リスト項目の {@code ::marker}（{@code MarkerBoxes}・{@link OutsideMarker}）と閉じた {@code details}。
 * 外側のマーカーは項目の border-box の左外（右の余白の端が項目の左端）に最初の行のベースラインで揃え、内側のマーカーは行の先頭の原子。
 * 番号は兄弟の順に依るので、子の並びが変わったら数え直し、増分の結果が最初から作った結果と同じであること。
 * 期待値は同じ書体（ヘッドレスの {@link FontFace#FALLBACK}）の {@code advance} から求める。
 */
class ListMarkerTest {
    private static final float EPS = 0.02f;
    private static final String HEAD = "<html><head><style>body{margin:0;font-size:16px;line-height:20px} ul,ol{margin:0}</style></head><body>";

    private static SpikeView view(String body) {
        return new SpikeView(HEAD + body + "</body></html>", 800, 600);
    }

    /** {@code id} の項目の外側のマーカーの置いた断片（無ければ null）。 */
    private static PlacedFragment outside(LayoutFragment root, String id) {
        LayoutFragment li = InlineTextTest.frag(root, id);
        assertNotNull(li, id);
        if (li.oof == null) return null;
        for (PlacedFragment p : li.oof) if (p.frag().box.pseudo == BoxNode.Pseudo.MARKER) return p;
        return null;
    }

    private static String text(LayoutFragment marker) {
        StringBuilder sb = new StringBuilder();
        for (LineFragment l : marker.lines) for (LineFragment.Piece p : l.pieces()) sb.append(p.string());
        return sb.toString();
    }

    private static List<String> markers(LayoutFragment root, String... ids) {
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            PlacedFragment p = outside(root, id);
            out.add(p == null ? null : text(p.frag()));
        }
        return out;
    }

    @Test
    void outsideMarkerSitsLeftOfTheItemOnTheFirstBaseline() {
        LayoutFragment r = view("<ul style='padding-left:40px'><li id=a>first line</li></ul>").frame();
        PlacedFragment m = outside(r, "a");
        assertNotNull(m);
        FontStyle fs = InlineTextTest.frag(r, "a").box.style.facts.fontStyle;
        assertEquals("• ", text(m.frag()));
        assertEquals(FontFace.FALLBACK.advance("• ", fs), m.frag().width, EPS);
        assertEquals(-m.frag().width, m.x(), EPS); // 右の端が項目の左端
        assertEquals(0, m.y(), EPS); // 同じ書体・行の高さなのでベースラインが揃うと上端も揃う
        assertEquals(20, InlineTextTest.frag(r, "a").height, EPS); // マーカーは項目の高さに寄与しない
    }

    @Test
    void insideMarkerIsTheFirstAtomOfTheLine() {
        LayoutFragment r = view("<ul style='padding-left:0;list-style-position:inside'><li id=a>x</li></ul>").frame();
        LayoutFragment li = InlineTextTest.frag(r, "a");
        assertNull(outside(r, "a"));
        FontStyle fs = li.box.style.facts.fontStyle;
        LineFragment l = li.lines[0];
        assertEquals(1, l.atoms().length);
        assertEquals(0, l.atoms()[0].x(), EPS);
        assertEquals(FontFace.FALLBACK.advance("• ", fs), l.pieces()[0].x(), EPS); // 文字はマーカーの後ろ
    }

    @Test
    void orderedListNumbersFollowStartReversedAndValue() {
        LayoutFragment r = view("<ol><li id=a>a<li id=b>b<li id=c value=10>c<li id=d>d</ol>"
                + "<ol start=5 reversed><li id=e>e<li id=f>f</ol><ol reversed><li id=g>g<li id=h>h<li id=i>i</ol>").frame();
        assertEquals(List.of("1. ", "2. ", "10. ", "11. "), markers(r, "a", "b", "c", "d"));
        assertEquals(List.of("5. ", "4. "), markers(r, "e", "f"));
        assertEquals(List.of("3. ", "2. ", "1. "), markers(r, "g", "h", "i"));
    }

    @Test
    void insertingAnItemRenumbersTheFollowingItemsLikeAFreshBuild() {
        StringBuilder sb = new StringBuilder("<ol id=l>");
        for (int i = 0; i < 80; i++) sb.append("<li id=i").append(i).append(">item ").append(i).append("</li>");
        SpikeView v = view(sb.append("</ol>").toString());
        v.frame();
        HtmlNode list = v.byId("l");
        HtmlElement e = new HtmlElement("li");
        e.updateTextContent("new");
        list.insertBefore(e, v.byId("i3"));
        LayoutFragment inc = v.frame();
        assertEquals(List.of("3. ", "5. ", "6. ", "81. "), markers(inc, "i2", "i3", "i4", "i79")); // 新しい項目が 4
        assertEquals(SpikeView.dump(v.scratch()), SpikeView.dump(inc));
        list.removeChild(v.byId("i0"));
        ((HtmlElement) list).setAttribute("start", "7");
        inc = v.frame();
        assertEquals(List.of("8. ", "86. "), markers(inc, "i2", "i79"));
        assertEquals(SpikeView.dump(v.scratch()), SpikeView.dump(inc));
    }

    @Test
    void closedDetailsShowsOnlyTheSummaryWithADisclosureMarker() {
        SpikeView v = view("<details id=d><summary id=s>sum</summary><div id=c style='height:30px'>body</div></details>");
        LayoutFragment r = v.frame();
        assertNull(InlineTextTest.frag(r, "c"));
        assertEquals(20, InlineTextTest.frag(r, "d").height, EPS);
        LineFragment l = InlineTextTest.frag(r, "s").lines[0];
        assertEquals(1, l.atoms().length); // 内側の ▸ の原子
        ((HtmlElement) v.byId("d")).setAttribute("open", "");
        r = v.frame();
        assertNotNull(InlineTextTest.frag(r, "c"));
        assertEquals(50, InlineTextTest.frag(r, "d").height, EPS);
        assertEquals(SpikeView.dump(v.scratch()), SpikeView.dump(r));
        assertTrue(text(InlineTextTest.frag(r, "s").lines[0].atoms()[0].frag()).startsWith("▾"));
    }
}
