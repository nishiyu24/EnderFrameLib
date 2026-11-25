package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2b-5: アンカー位置指定（{@link AnchorPlacement}・{@link AnchorTry}・{@link Anchors}。CSS Anchor Positioning 1）。旧経路の
 * {@code AnchorPositioningTest} と同じ文書・同じ期待値（スクロールに追従する fixed の選び直しは描画の側なので除く）。
 */
class AnchorPlacementTest {
    private static final float EPS = 0.5f;
    private static final String CSS = "<style>body{margin:0} #a{position:absolute;left:100px;top:50px;width:80px;height:30px;anchor-name:--a}"
            + " #t{position:absolute;width:40px;height:20px}</style>";

    private static LayoutFragment layout(String html) {
        return new SpikeView("<html><head>" + CSS + "</head><body>" + html + "</body></html>", 400, 300).frame();
    }

    private static float[] at(LayoutFragment r, String id) {
        float[] b = SpikeView.find(r, id);
        assertNotNull(b, id);
        return b;
    }

    @Test
    void belowAnchorWithPositionAnchor() {
        float[] t = at(layout("<div id=a></div><div id=t style='position-anchor:--a;top:anchor(bottom);left:anchor(left)'></div>"), "t");
        assertEquals(100, t[0], EPS);
        assertEquals(80, t[1], EPS);
    }

    @Test
    void explicitNameRightOfAnchorAndCentered() {
        float[] t = at(layout("<div id=a></div><div id=t style='top:anchor(--a center);left:anchor(--a right)'></div>"), "t");
        assertEquals(180, t[0], EPS);
        assertEquals(65, t[1], EPS);
    }

    @Test
    void rightAndBottomInsetsAlignEdgesToTheAnchor() {
        float[] t = at(layout("<div id=a></div><div id=t style='position-anchor:--a;bottom:anchor(top);right:anchor(right)'></div>"), "t");
        assertEquals(180 - 40, t[0], EPS);
        assertEquals(50 - 20, t[1], EPS);
    }

    /** ポップオーバーの暗黙のアンカーは呼び出し元（{@code popovertarget}）。 */
    @Test
    void popoverIsAnchoredToItsInvoker() {
        SpikeView v = new SpikeView("<html><head>" + CSS + "</head><body>"
                + "<button id=b popovertarget=t style='position:absolute;left:10px;top:200px;width:60px;height:24px'>b</button>"
                + "<div id=t popover style='top:anchor(bottom);left:anchor(left);margin:0;width:40px;height:20px'></div></body></html>", 400, 300);
        ((HtmlElement) v.byId("t")).showPopover();
        float[] t = at(v.frame(), "t");
        assertEquals(10, t[0], EPS);
        assertEquals(224, t[1], EPS);
    }

    @Test
    void anchorSizeMatchesTheAnchorWidth() {
        LayoutFragment r = layout("<div id=a></div><div id=t style='position-anchor:--a;top:anchor(bottom);left:anchor(left);"
                + "width:anchor-size(width);height:anchor-size(--a height)'><div id=c style='height:100%'></div></div>");
        assertEquals(80, at(r, "t")[2], EPS);
        assertEquals(30, at(r, "t")[3], EPS);
        assertEquals(80, at(r, "c")[2], EPS);
        assertEquals(30, at(r, "c")[3], EPS);
    }

    @Test
    void positionAreaPlacesBesideTheAnchor() {
        float[] t = at(layout("<div id=a></div><div id=t style='position-anchor:--a;position-area:bottom center'></div>"), "t");
        assertEquals(120, t[0], EPS);
        assertEquals(80, t[1], EPS);
        float[] t2 = at(layout("<div id=a></div><div id=t style='position-anchor:--a;position-area:left'></div>"), "t");
        assertEquals(60, t2[0], EPS);
        assertEquals(55, t2[1], EPS);
    }

    @Test
    void positionAreaSpanAndExplicitInsetPrecedence() {
        float[] ts = at(layout("<div id=a></div><div id=t style='position-anchor:--a;position-area:bottom span-right'></div>"), "t");
        assertEquals(100, ts[0], EPS);
        assertEquals(80, ts[1], EPS);
        float[] tl = at(layout("<div id=a></div><div id=t style='position-anchor:--a;position-area:top span-left'></div>"), "t");
        assertEquals(140, tl[0], EPS);
        assertEquals(30, tl[1], EPS);
        float[] te = at(layout("<div id=a></div><div id=t style='position-anchor:--a;left:10px;position-area:bottom right'></div>"), "t");
        assertEquals(10, te[0], EPS);
        assertEquals(80, te[1], EPS);
    }

    @Test
    void positionTryFlipBlockAvoidsBottomOverflow() {
        float[] t = at(layout("<style>#a{left:100px;top:265px;width:80px;height:25px}</style><div id=a></div>"
                + "<div id=t style='position-anchor:--a;height:30px;margin-top:4px;top:anchor(bottom);left:anchor(left);position-try-fallbacks:flip-block'></div>"), "t");
        assertEquals(100, t[0], EPS);
        assertEquals(265 - 4 - 30, t[1], EPS);
    }

    @Test
    void positionTryFlipInlineAndCombinedTactics() {
        float[] t = at(layout("<style>#a{left:360px;top:270px;width:30px;height:20px}</style><div id=a></div>"
                + "<div id=t style='position-anchor:--a;width:60px;height:30px;top:anchor(bottom);left:anchor(right);"
                + "position-try-fallbacks:flip-block, flip-inline, flip-block flip-inline'></div>"), "t");
        assertEquals(360 - 60, t[0], EPS);
        assertEquals(270 - 30, t[1], EPS);
    }

    @Test
    void positionTryAreaFallbacksAndFlipStart() {
        float[] tl = at(layout("<style>#a{left:100px;top:265px;width:80px;height:25px}</style><div id=a></div>"
                + "<div id=t style='position-anchor:--a;position-area:bottom;position-try-fallbacks:left, top'></div>"), "t");
        assertEquals(100 - 40, tl[0], EPS);
        assertEquals(267.5f, tl[1], EPS);
        float[] ta = at(layout("<style>#a{left:20px;top:265px;width:80px;height:25px}</style><div id=a></div>"
                + "<div id=t style='position-anchor:--a;position-area:bottom;position-try-fallbacks:left, top'></div>"), "t");
        assertEquals(20 + (80 - 40) / 2f, ta[0], EPS);
        assertEquals(265 - 20, ta[1], EPS);
        float[] ts = at(layout("<style>#a{left:10px;top:270px;width:80px;height:25px}</style><div id=a></div>"
                + "<div id=t style='position-anchor:--a;width:60px;height:20px;position-area:bottom left;position-try-fallbacks:flip-start'></div>"), "t");
        assertEquals(90, ts[0], EPS);
        assertEquals(270 - 60, ts[1], EPS);
        assertEquals(20, ts[2], EPS);
        assertEquals(60, ts[3], EPS);
    }

    @Test
    void atPositionTryRuleWithShorthandAndInvalidFallbackSkipped() {
        LayoutFragment r = layout("<style>@position-try --bad { top: 290px; left: 0; }"
                + "@position-try --good { inset: auto; bottom: anchor(top); right: anchor(right); width: 70px; margin-bottom: 5px; width: 70px !important; background-color: red; }"
                + "#a{left:100px;top:265px;width:80px;height:25px}</style><div id=a></div>"
                + "<div id=t style='position-anchor:--a;top:anchor(bottom);left:anchor(left);position-try-fallbacks:--undefined, --bad, --good'>"
                + "<div id=c style='width:100%;height:10px'></div></div>");
        float[] t = at(r, "t");
        assertEquals(180 - 70, t[0], EPS);
        assertEquals(265 - 20 - 5, t[1], EPS);
        assertEquals(70, t[2], EPS);
        assertEquals(70, at(r, "c")[2], EPS);
    }

    @Test
    void positionTryOrderMostHeightAndShorthand() {
        String html = "<style>#a{left:100px;top:200px;width:80px;height:20px}</style><div id=a></div>";
        assertEquals(220, at(layout(html + "<div id=t style='position-anchor:--a;height:40px;top:anchor(bottom);left:anchor(left);"
                + "position-try:normal flip-block'></div>"), "t")[1], EPS);
        assertEquals(160, at(layout(html + "<div id=t style='position-anchor:--a;height:40px;top:anchor(bottom);left:anchor(left);"
                + "position-try:most-height flip-block'></div>"), "t")[1], EPS);
        assertEquals(160, at(layout(html + "<div id=t style='position-anchor:--a;height:40px;top:anchor(bottom);left:anchor(left);"
                + "position-try:most-block-size flip-block'></div>"), "t")[1], EPS);
    }

    @Test
    void positionTryRevertsToBaseWhenNoneFit() {
        float[] t = at(layout("<style>#a{left:100px;top:140px;width:80px;height:20px}</style><div id=a></div>"
                + "<div id=t style='position-anchor:--a;height:200px;top:anchor(bottom);left:anchor(left);position-try-fallbacks:flip-block'></div>"), "t");
        assertEquals(160, t[1], EPS);
    }

    @Test
    void bothAnchorInsetsStretchAutoSize() {
        LayoutFragment r = layout("<div id=a></div><div id=t style='position:absolute;left:anchor(--a left);right:anchor(--a right);"
                + "top:anchor(--a bottom);height:20px;width:auto'><div id=c style='width:100%;height:100%'></div></div>");
        float[] t = at(r, "t");
        assertEquals(100, t[0], EPS);
        assertEquals(80, t[1], EPS);
        assertEquals(80, t[2], EPS);
        assertEquals(80, at(r, "c")[2], EPS);
    }

    @Test
    void atPositionTryCombinedWithTryTacticAndMostWidthOrder() {
        float[] tc = at(layout("<style>@position-try --left-edge { left: anchor(right); top: anchor(top); }"
                + "#a{left:340px;top:50px;width:40px;height:30px}</style><div id=a></div>"
                + "<div id=t style='position-anchor:--a;width:60px;height:20px;top:anchor(bottom);left:anchor(right);"
                + "position-try-fallbacks:--left-edge flip-inline'></div>"), "t");
        assertEquals(280, tc[0], EPS);
        assertEquals(50, tc[1], EPS);
        float[] tm = at(layout("<style>#a{left:100px;top:50px;width:80px;height:30px}</style><div id=a></div>"
                + "<div id=t style='position-anchor:--a;position-area:left;position-try:most-width left, right'></div>"), "t");
        assertEquals(180, tm[0], EPS);
    }

    /**
     * アンカーが動けば（位置指定のアンカーの inset・流れの中のアンカーの前の中身）、配置した箱も追従する。包含ブロックは中身が変われば
     * 整形し直すので、増分の結果は全再計算と一致する。
     */
    @Test
    void anchoredBoxFollowsAMovedAnchorIncrementally() {
        SpikeView v = new SpikeView("<html><head>" + CSS + "</head><body><div id=a></div>"
                + "<div style='position:relative;width:300px'><p id=lead style='margin:0;height:10px'></p><span id=f style='anchor-name:--flow'>f</span>"
                + "<div id=u style='position:absolute;width:5px;height:5px;top:anchor(--flow bottom);left:anchor(--flow right)'></div></div>"
                + "<div id=t style='position-anchor:--a;top:anchor(bottom);left:anchor(left)'></div></body></html>", 400, 300);
        LayoutFragment first = v.frame();
        assertEquals(80, at(first, "t")[1], EPS);
        float uy = at(first, "u")[1];
        ((HtmlElement) v.byId("a")).setAttribute("style", "top:90px");
        ((HtmlElement) v.byId("lead")).setAttribute("style", "margin:0;height:25px");
        LayoutFragment inc = v.frame();
        assertEquals(120, at(inc, "t")[1], EPS);
        assertEquals(uy + 15, at(inc, "u")[1], EPS);
        assertEquals(SpikeView.dump(v.scratch()), SpikeView.dump(inc));
    }

    @Test
    void flipStartSwapsAutoAndDefiniteDimensionsAndRespectsRelativeContainer() {
        float[] t = at(layout("<div style='position:relative;left:50px;top:40px;width:200px;height:150px'>"
                + "  <div id=a style='position:absolute;left:10px;top:100px;width:60px;height:20px;anchor-name:--rel'></div>"
                + "  <div id=t style='position:absolute;position-anchor:--rel;width:25px;height:auto;"
                + "    top:anchor(bottom);left:anchor(left);position-try-fallbacks:flip-start'><div style='height:40px'>x</div></div>"
                + "</div>"), "t");
        assertEquals(50 + 70, t[0], EPS);
        assertEquals(40 + 100, t[1], EPS);
        assertEquals(25, t[3], EPS);
        assertTrue(t[2] > 0 && t[2] < 25, "width: auto は内容 x の固有幅になる: " + t[2]);
    }
}
