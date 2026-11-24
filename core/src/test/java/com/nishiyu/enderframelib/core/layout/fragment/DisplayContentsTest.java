package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * M2b-1 の {@code display: contents}: 箱を作らず、子（と疑似要素）を親の子として並べる。子のスタイルの継承元ではある。
 * 包んだ文書と包まない文書で、中身の位置が同じになることを確かめる。
 */
class DisplayContentsTest {

    private static LayoutFragment frame(String body) {
        return new SpikeView("<html><head><style>body{margin:0}</style></head><body>" + body + "</body></html>", 600, 400).frame();
    }

    private static void assertSamePositions(String wrapped, String plain, String... ids) {
        LayoutFragment a = frame(wrapped), b = frame(plain);
        for (String id : ids) assertArrayEquals(SpikeView.find(b, id), SpikeView.find(a, id), 1e-3f, id);
    }

    @Test
    void blockChildrenJoinTheGrandparentFlow() {
        assertSamePositions("<div style='padding:5px'><p id=a style='margin:4px'>a</p><div style='display:contents;padding:50px;border:9px solid'>"
                        + "<p id=b style='margin:10px'>b</p><p id=c>c</p></div><p id=d>d</p></div>",
                "<div style='padding:5px'><p id=a style='margin:4px'>a</p><p id=b style='margin:10px'>b</p><p id=c>c</p><p id=d>d</p></div>",
                "a", "b", "c", "d");
    }

    @Test
    void wrapperMakesNoFragment() {
        LayoutFragment root = frame("<div id=w style='display:contents'><p id=p>x</p></div>");
        assertNull(SpikeView.find(root, "w"));
    }

    @Test
    void childrenBecomeFlexItems() {
        assertSamePositions("<div style='display:flex;justify-content:space-between;width:300px'><div style='display:contents'>"
                        + "<span id=x>x</span><span id=y>y</span></div><span id=z>z</span></div>",
                "<div style='display:flex;justify-content:space-between;width:300px'><span id=x>x</span><span id=y>y</span><span id=z>z</span></div>",
                "x", "y", "z");
    }

    @Test
    void inlineContentFlowsIntoTheParentLineAndInheritsStyle() {
        LayoutFragment f = InlineTextTest.frag(frame("<p id=p>a <span style='display:contents;font-size:32px;padding:20px'>big</span> b</p>"), "p");
        assertEquals(1, f.lines.length);
        LineFragment.Piece[] pieces = f.lines[0].pieces();
        assertEquals(List.of("a", "big", "b"), List.of(pieces[0].string(), pieces[1].string(), pieces[2].string()));
        assertEquals(32f, pieces[1].text().style.facts.fontStyle.fontSizePx(), 1e-3f, "子は contents の要素から継承する");
        assertEquals(0f, f.lines[0].x(), 1e-3f, "contents の要素の padding は効かない（箱が無い）");
    }

    @Test
    void pseudoElementsOfTheWrapperAreGenerated() {
        LayoutFragment f = InlineTextTest.frag(new SpikeView("<html><head><style>.c{display:contents} .c::before{content:'['} .c::after{content:']'}</style></head>"
                + "<body><p id=p><span class=c>in</span></p></body></html>", 600, 400).frame(), "p");
        LineFragment.Piece[] pieces = f.lines[0].pieces();
        assertEquals(List.of("[", "in", "]"), List.of(pieces[0].string(), pieces[1].string(), pieces[2].string()));
    }

    @Test
    void togglingContentsMatchesAFreshBuild() {
        SpikeView v = new SpikeView("<html><body><div id=w style='padding:9px'><p>a</p>text <b>b</b></div><div>tail</div></body></html>", 600, 400);
        v.frame();
        for (String st : new String[]{"display:contents", "padding:9px", "display:contents;color:red", "display:flex"}) {
            ((HtmlElement) v.byId("w")).setAttribute("style", st);
            List<String> inc = SpikeView.dump(v.frame());
            assertEquals(SpikeView.dump(v.scratch()), inc, st);
        }
    }
}
