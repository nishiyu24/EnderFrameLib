package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * M2b-5: コンテナ問い合わせの 2 段（{@link FragmentPipeline}・{@code ContainerScope}。CSS Conditional 5 §2）。整形した後で容器の内容
 * ボックスの寸法を測り、前と違えば容器の部分木を解き直して整形し直す。期待値は手で求めた数。
 */
class ContainerQueryTest {
    private static final float EPS = 0.02f;

    private static SpikeView view(String css, String body) {
        return new SpikeView("<html><head><style>body{margin:0}" + css + "</style></head><body>" + body + "</body></html>", 800, 600);
    }

    private static float[] at(LayoutFragment r, String id) {
        float[] b = SpikeView.find(r, id);
        assertNotNull(b, id);
        return b;
    }

    /** 寸法の問い合わせは最も近い寸法の容器（内容ボックスの幅。padding を除く）で評価する。 */
    @Test
    void sizeQueryUsesTheNearestContainerContentWidth() {
        String css = ".c{container-type:inline-size;padding:0 10px} .x{width:10px;height:5px} @container (min-width: 200px){ .x{width:50px} }";
        LayoutFragment wide = view(css, "<div class=c style='width:220px'><div id=x class=x></div></div>").frame();
        assertEquals(50, at(wide, "x")[2], EPS);
        LayoutFragment narrow = view(css, "<div class=c style='width:190px'><div id=x class=x></div></div>").frame();
        assertEquals(10, at(narrow, "x")[2], EPS); // width は内容ボックスの幅（padding は外）
    }

    /** 名前付きの問い合わせは名前が合う祖先の容器（最も近い容器が名前を持たなくても）。cqw は最も近い寸法の容器の幅の 1%。 */
    @Test
    void namedQueryAndContainerUnits() {
        String css = ".a{container:outer / inline-size;width:500px} .b{container-type:inline-size;width:120px} .x{height:5px;width:10px}"
                + " @container outer (min-width: 400px){ .x{width:50cqw} }";
        LayoutFragment r = view(css, "<div class=a><div class=b><div id=x class=x></div></div></div>").frame();
        assertEquals(60, at(r, "x")[2], EPS); // 名前は outer（500）で真、cqw は最も近い b（120）の 50%
    }

    /** 擬似要素にとっては持ち主の要素も容器（{@code ::before} の問い合わせは持ち主の幅）。{@code style()} の問い合わせは祖先の算出値。 */
    @Test
    void pseudoElementsAndStyleQueries() {
        String css = ".c{container-type:inline-size;width:300px} .c::before{content:'';display:block;height:4px;width:10px}"
                + " @container (min-width: 250px){ .c::before{width:70px} }"
                + " .t{--theme:dark} .y{width:10px;height:5px} @container style(--theme: dark){ .y{width:30px} }";
        LayoutFragment r = view(css, "<div id=c class=c></div><div class=t><div id=y class=y></div></div>").frame();
        assertEquals(30, at(r, "y")[2], EPS);
        LayoutFragment f = InlineTextTest.frag(r, "c");
        LayoutFragment before = f.flow.chunks[0].frags[0];
        assertEquals(70, before.width, EPS);
    }

    /**
     * 容器の名前だけが変わる・容器でなくなる（寸法は測り直しても変わらない）と、子孫の問い合わせの相手が変わる。容器の種類・名前は
     * 継承されないので、容器の部分木を解き直して届ける。増分の結果は全再計算と一致する。
     */
    @Test
    void renamingOrDroppingAContainerRestylesItsSubtree() {
        String css = ".o{container:outer / inline-size;width:500px} .i{container-type:inline-size;width:150px} .k{container-name:side}"
                + " .x{width:10px;height:5px} @container side (min-width: 100px){ .x{width:30px} } @container (min-width: 300px){ .x{height:7px} }";
        SpikeView v = view(css, "<div class=o><div id=i class=i><div><div><div id=x class=x></div></div></div></div></div>");
        assertEquals(10, at(v.frame(), "x")[2], EPS);
        ((HtmlElement) v.byId("i")).setAttribute("class", "i k"); // 名前だけを足す（寸法は 150 のまま）
        LayoutFragment inc = v.frame();
        assertEquals(30, at(inc, "x")[2], EPS);
        assertEquals(SpikeView.dump(v.scratch()), SpikeView.dump(inc));
        ((HtmlElement) v.byId("i")).setAttribute("class", ""); // 容器でなくなる: 名前の無い問い合わせの相手は外の o（500）
        inc = v.frame();
        assertEquals(10, at(inc, "x")[2], EPS);
        assertEquals(7, at(inc, "x")[3], EPS);
        assertEquals(SpikeView.dump(v.scratch()), SpikeView.dump(inc));
    }

    /** 容器の幅が中身でなく親で決まり、親の幅を変えると問い合わせの結果が変わる。増分の結果は全再計算と一致する。 */
    @Test
    void containerResizeRestylesTheSubtreeIncrementally() {
        String css = ".c{container-type:inline-size} .x{width:10px;height:5px} @container (min-width: 300px){ .x{width:40px} .x span{display:block;height:9px} }";
        SpikeView v = view(css, "<div id=p style='width:400px'><div class=c><div><div id=x class=x><span>s</span></div></div></div></div><p id=q>q</p>");
        assertEquals(40, at(v.frame(), "x")[2], EPS);
        ((HtmlElement) v.byId("p")).setAttribute("style", "width:250px");
        LayoutFragment inc = v.frame();
        assertEquals(10, at(inc, "x")[2], EPS);
        assertEquals(SpikeView.dump(v.scratch()), SpikeView.dump(inc));
        ((HtmlElement) v.byId("p")).setAttribute("style", "width:350px");
        inc = v.frame();
        assertEquals(40, at(inc, "x")[2], EPS);
        assertEquals(SpikeView.dump(v.scratch()), SpikeView.dump(inc));
    }
}
