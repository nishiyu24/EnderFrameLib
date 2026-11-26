package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.paint.tree.PaintView;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Anchor Positioning（anchor-name / position-anchor / anchor()）。 */
public class AnchorPositioningTest {

    private static final int W = 400, H = 300;
    private static final String CSS = "<style>body{margin:0} #a{position:absolute;left:100px;top:50px;width:80px;height:30px;anchor-name:--a}"
            + " #t{position:absolute;width:40px;height:20px}</style>";

    private static PaintView layout(String html) {
        UiDocumentCompiler c = new UiDocumentCompiler();
        Document doc = c.parseDocument(CSS + html, "", W, H);
        return c.layoutDocument(doc, W, H, Map.of(), new ScrollState(), FontResolver.FALLBACK);
    }

    private static PaintView find(PaintView box, String id) {
        if (id.equals(box.node.attrs.get("id"))) return box;
        for (PaintView c : TestBoxes.elements(box)) {
            PaintView h = find(c, id);
            if (h != null) return h;
        }
        return null;
    }

    @Test
    public void belowAnchorWithPositionAnchor() {
        PaintView root = layout("<div id=a></div><div id=t style='position-anchor:--a;top:anchor(bottom);left:anchor(left)'></div>");
        PaintView t = find(root, "t");
        assertEquals(100, t.x(), 0.5f);
        assertEquals(80, t.y(), 0.5f);
    }

    @Test
    public void explicitNameRightOfAnchorAndCentered() {
        PaintView root = layout("<div id=a></div><div id=t style='top:anchor(--a center);left:anchor(--a right)'></div>");
        PaintView t = find(root, "t");
        assertEquals(180, t.x(), 0.5f);
        assertEquals(65, t.y(), 0.5f);
    }

    @Test
    public void rightAndBottomInsetsAlignEdgesToTheAnchor() {
        // right: anchor(right) は右端をアンカーの右端へ、bottom: anchor(top) は下端をアンカーの上端へ
        PaintView root = layout("<div id=a></div><div id=t style='position-anchor:--a;bottom:anchor(top);right:anchor(right)'></div>");
        PaintView t = find(root, "t");
        assertEquals(180 - 40, t.x(), 0.5f);
        assertEquals(50 - 20, t.y(), 0.5f);
    }

    @Test
    public void popoverIsAnchoredToItsInvoker() {
        UiDocumentCompiler c = new UiDocumentCompiler();
        Document doc = c.parseDocument(CSS
                + "<button id=b popovertarget=t style='position:absolute;left:10px;top:200px;width:60px;height:24px'>b</button>"
                + "<div id=t popover style='top:anchor(bottom);left:anchor(left);margin:0;width:40px;height:20px'></div>", "", W, H);
        ((HtmlElement) doc.dom().getElementById("t")).showPopover();
        PaintView r = c.layoutDocument(doc, W, H, Map.of(), new ScrollState(), FontResolver.FALLBACK,
                (com.nishiyu.enderframelib.core.dom.InteractionState) ((HtmlDocument) doc.root).interactionState());
        PaintView t = find(r, "t");
        assertEquals(10, t.x(), 0.5f);
        assertEquals(224, t.y(), 0.5f);
    }

    @Test
    public void anchorSizeMatchesTheAnchorWidth() {
        PaintView root = layout("<div id=a></div><div id=t style='position-anchor:--a;top:anchor(bottom);left:anchor(left);width:anchor-size(width);height:anchor-size(--a height)'><div id=c style='height:100%'></div></div>");
        PaintView t = find(root, "t");
        assertEquals(80, t.w(), 0.5f);
        assertEquals(30, t.h(), 0.5f);
        assertEquals(80, find(root, "c").w(), 0.5f);
        assertEquals(30, find(root, "c").h(), 0.5f);
    }

    @Test
    public void positionAreaPlacesBesideTheAnchor() {
        PaintView root = layout("<div id=a></div><div id=t style='position-anchor:--a;position-area:bottom center'></div>");
        PaintView t = find(root, "t");
        assertEquals(120, t.x(), 0.5f);
        assertEquals(80, t.y(), 0.5f);
        PaintView r2 = layout("<div id=a></div><div id=t style='position-anchor:--a;position-area:left'></div>");
        PaintView t2 = find(r2, "t");
        assertEquals(60, t2.x(), 0.5f);
        assertEquals(55, t2.y(), 0.5f);
    }

    @Test
    public void positionAreaSpanAndExplicitInsetPrecedence() {
        PaintView span = layout("<div id=a></div><div id=t style='position-anchor:--a;position-area:bottom span-right'></div>");
        PaintView ts = find(span, "t");
        assertEquals(100, ts.x(), 0.5f);
        assertEquals(80, ts.y(), 0.5f);

        PaintView spanLeft = layout("<div id=a></div><div id=t style='position-anchor:--a;position-area:top span-left'></div>");
        PaintView tl = find(spanLeft, "t");
        assertEquals(140, tl.x(), 0.5f);
        assertEquals(30, tl.y(), 0.5f);

        PaintView explicit = layout("<div id=a></div><div id=t style='position-anchor:--a;left:10px;position-area:bottom right'></div>");
        PaintView te = find(explicit, "t");
        assertEquals(10, te.x(), 0.5f);
        assertEquals(80, te.y(), 0.5f);
    }

    @Test
    @Disabled("G8 position-try の flip-block で入れ替えた余白を描画用の木が読まない（位置は正しい）。計画書 §3.2「新経路の穴（M2c-3 の移植で見えた）」")
    public void positionTryFlipBlockAvoidsBottomOverflow() {
        PaintView root = layout(
                "<style>#a{left:100px;top:265px;width:80px;height:25px}</style>"
                + "<div id=a></div>"
                + "<div id=t style='position-anchor:--a;height:30px;margin-top:4px;top:anchor(bottom);left:anchor(left);position-try-fallbacks:flip-block'></div>");
        PaintView t = find(root, "t");
        assertEquals(100, t.x(), 0.5f);
        assertEquals(265 - 4 - 30, t.y(), 0.5f);
        assertEquals(0, t.mT(), 0.5f);
        assertEquals(4, t.mB(), 0.5f);
    }

    @Test
    public void positionTryFlipInlineAndCombinedTactics() {
        PaintView root = layout(
                "<style>#a{left:360px;top:270px;width:30px;height:20px}</style>"
                + "<div id=a></div>"
                + "<div id=t style='position-anchor:--a;width:60px;height:30px;top:anchor(bottom);left:anchor(right);"
                + "position-try-fallbacks:flip-block, flip-inline, flip-block flip-inline'></div>");
        PaintView t = find(root, "t");
        assertEquals(360 - 60, t.x(), 0.5f);
        assertEquals(270 - 30, t.y(), 0.5f);
    }

    @Test
    public void positionTryAreaFallbacksAndFlipStart() {
        // position-area の直接列挙: left が収まれば left を選び、左端にも収まらなければ次の top を選ぶ
        PaintView areaLeft = layout(
                "<style>#a{left:100px;top:265px;width:80px;height:25px}</style>"
                + "<div id=a></div>"
                + "<div id=t style='position-anchor:--a;position-area:bottom;position-try-fallbacks:left, top'></div>");
        PaintView tl = find(areaLeft, "t");
        assertEquals(100 - 40, tl.x(), 0.5f);
        assertEquals(267.5f, tl.y(), 0.5f);

        PaintView areaTop = layout(
                "<style>#a{left:20px;top:265px;width:80px;height:25px}</style>"
                + "<div id=a></div>"
                + "<div id=t style='position-anchor:--a;position-area:bottom;position-try-fallbacks:left, top'></div>");
        PaintView ta = find(areaTop, "t");
        assertEquals(20 + (80 - 40) / 2f, ta.x(), 0.5f);
        assertEquals(265 - 20, ta.y(), 0.5f);

        // flip-start: bottom left (60x20) -> top right (20x60)
        PaintView flipStart = layout(
                "<style>#a{left:10px;top:270px;width:80px;height:25px}</style>"
                + "<div id=a></div>"
                + "<div id=t style='position-anchor:--a;width:60px;height:20px;position-area:bottom left;position-try-fallbacks:flip-start'></div>");
        PaintView ts = find(flipStart, "t");
        assertEquals(90, ts.x(), 0.5f);
        assertEquals(270 - 60, ts.y(), 0.5f);
        assertEquals(20, ts.w(), 0.5f);
        assertEquals(60, ts.h(), 0.5f);
    }

    @Test
    public void atPositionTryRuleWithShorthandAndInvalidFallbackSkipped() {
        PaintView root = layout(
                "<style>"
                + "@position-try --bad { top: 290px; left: 0; }"
                + "@position-try --good { inset: auto; bottom: anchor(top); right: anchor(right); width: 70px; margin-bottom: 5px; width: 70px !important; background-color: red; }"
                + "#a{left:100px;top:265px;width:80px;height:25px}"
                + "</style>"
                + "<div id=a></div>"
                + "<div id=t style='position-anchor:--a;top:anchor(bottom);left:anchor(left);position-try-fallbacks:--undefined, --bad, --good'>"
                + "<div id=c style='width:100%;height:10px'></div>"
                + "</div>");
        PaintView t = find(root, "t");
        assertEquals(180 - 70, t.x(), 0.5f);
        assertEquals(265 - 20 - 5, t.y(), 0.5f);
        assertEquals(70, t.w(), 0.5f);
        assertEquals(70, find(root, "c").w(), 0.5f);
        assertNotEquals("red", t.style().get("background-color"));
    }

    @Test
    public void positionTryOrderMostHeightAndShorthand() {
        String html = "<style>#a{left:100px;top:200px;width:80px;height:20px}</style><div id=a></div>";
        // normal は基本位置（下: 空き 80px）が収まるのでそのまま
        PaintView normal = layout(html
                + "<div id=t style='position-anchor:--a;height:40px;top:anchor(bottom);left:anchor(left);position-try:normal flip-block'></div>");
        assertEquals(220, find(normal, "t").y(), 0.5f);

        // most-height は両方収まっても空きが大きい上（200px > 80px）を選ぶ
        PaintView mostH = layout(html
                + "<div id=t style='position-anchor:--a;height:40px;top:anchor(bottom);left:anchor(left);position-try:most-height flip-block'></div>");
        assertEquals(200 - 40, find(mostH, "t").y(), 0.5f);

        // most-block-size も横書きでは同じ
        PaintView mostB = layout(html
                + "<div id=t style='position-anchor:--a;height:40px;top:anchor(bottom);left:anchor(left);position-try:most-block-size flip-block'></div>");
        assertEquals(200 - 40, find(mostB, "t").y(), 0.5f);
    }

    @Test
    public void positionTryRevertsToBaseWhenNoneFit() {
        PaintView root = layout(
                "<style>#a{left:100px;top:140px;width:80px;height:20px}</style>"
                + "<div id=a></div>"
                + "<div id=t style='position-anchor:--a;height:200px;top:anchor(bottom);left:anchor(left);position-try-fallbacks:flip-block'></div>");
        PaintView t = find(root, "t");
        assertEquals(160, t.y(), 0.5f);
    }

    @Test
    public void bothAnchorInsetsStretchAutoSize() {
        PaintView root = layout(
                "<div id=a></div>"
                + "<div id=t style='position:absolute;left:anchor(--a left);right:anchor(--a right);top:anchor(--a bottom);height:20px;width:auto'>"
                + "<div id=c style='width:100%;height:100%'></div></div>");
        PaintView t = find(root, "t");
        assertEquals(100, t.x(), 0.5f);
        assertEquals(80, t.y(), 0.5f);
        assertEquals(80, t.w(), 0.5f);
        assertEquals(80, find(root, "c").w(), 0.5f);
    }

    @Test
    @Disabled("G8 スクロール量が新経路のレイアウトの入力でないので、スクロールで position-try の候補を選び直さない。計画書 §3.2「新経路の穴（M2c-3 の移植で見えた）」")
    public void positionTryFlipsOnScrollReposition() {
        UiDocumentCompiler c = new UiDocumentCompiler();
        Document doc = c.parseDocument(
                "<style>body{margin:0}"
                + " #sc{width:400px;height:200px;overflow-y:auto}"
                + " #spacer{height:150px}"
                + " #a{width:80px;height:30px;anchor-name:--a}"
                + " #t{position:fixed;width:60px;height:40px;position-anchor:--a;top:anchor(bottom);left:anchor(left);position-try-fallbacks:flip-block}"
                + "</style>"
                + "<div id=sc><div id=spacer></div><div id=a></div><div style='height:300px'></div></div>"
                + "<div id=t></div>", "", W, H);
        ScrollState scroll = new ScrollState();
        PaintView root = c.layoutDocument(doc, W, H, Map.of(), scroll, FontResolver.FALLBACK);
        // スクロール前: #a は y=150..180、#t は下 (y=180..220) で 300px 以内に収まる
        assertEquals(180, find(root, "t").y(), 0.5f);

        // スクロールして #a が下端付近に動いた場合（実際には下スクロールで #a は上へ行くので、#spacer を 250px にして初期状態では下にはみ出し、スクロール後に下に収まる逆向きで検証）
        Document doc2 = c.parseDocument(
                "<style>body{margin:0}"
                + " #sc{width:400px;height:300px;overflow-y:auto}"
                + " #spacer{height:250px}"
                + " #a{width:80px;height:30px;anchor-name:--a}"
                + " #t{position:fixed;width:60px;height:40px;position-anchor:--a;top:anchor(bottom);left:anchor(left);position-try-fallbacks:flip-block}"
                + "</style>"
                + "<div id=sc><div id=spacer></div><div id=a></div><div style='height:300px'></div></div>"
                + "<div id=t></div>", "", W, H);
        ScrollState scroll2 = new ScrollState();
        PaintView root2 = c.layoutDocument(doc2, W, H, Map.of(), scroll2, FontResolver.FALLBACK);
        // 初期: #a は y=250..280 → 下 (280..320) ははみ出すため上 (250 - 40 = 210) に反転
        assertEquals(210, find(root2, "t").y(), 0.5f);
        // スクロール後: #a は y=150..180 → 下 (180..220) が収まるので下へ戻る
        scroll2.setScroll(find(root2, "sc").node, 0, 100);
        root2 = c.layoutDocument(doc2, W, H, Map.of(), scroll2, FontResolver.FALLBACK);
        assertEquals(180, find(root2, "t").y(), 0.5f);
    }

    @Test
    public void atPositionTryCombinedWithTryTacticAndMostWidthOrder() {
        PaintView combined = layout(
                "<style>"
                + "@position-try --left-edge { left: anchor(right); top: anchor(top); }"
                + "#a{left:340px;top:50px;width:40px;height:30px}"
                + "</style>"
                + "<div id=a></div>"
                + "<div id=t style='position-anchor:--a;width:60px;height:20px;top:anchor(bottom);left:anchor(right);"
                + "position-try-fallbacks:--left-edge flip-inline'></div>");
        PaintView tc = find(combined, "t");
        // --left-edge は left: anchor(right) (380) で右にはみ出すが、flip-inline 併用で right: anchor(left) → 340 - 60 = 280
        assertEquals(280, tc.x(), 0.5f);
        assertEquals(50, tc.y(), 0.5f);

        // most-width + position-area: left (100px) vs right (220px) → 両方収まっても広い right を選ぶ
        PaintView mostW = layout(
                "<style>#a{left:100px;top:50px;width:80px;height:30px}</style>"
                + "<div id=a></div>"
                + "<div id=t style='position-anchor:--a;position-area:left;position-try:most-width left, right'></div>");
        PaintView tm = find(mostW, "t");
        assertEquals(180, tm.x(), 0.5f);
    }

    @Test
    public void flipStartSwapsAutoAndDefiniteDimensionsAndRespectsRelativeContainer() {
        PaintView root = layout(
                "<div style='position:relative;left:50px;top:40px;width:200px;height:150px'>"
                + "  <div id=a style='position:absolute;left:10px;top:100px;width:60px;height:20px;anchor-name:--rel'></div>"
                + "  <div id=t style='position:absolute;position-anchor:--rel;width:25px;height:auto;"
                + "    top:anchor(bottom);left:anchor(left);position-try-fallbacks:flip-start'><div style='height:40px'>x</div></div>"
                + "</div>");
        PaintView t = find(root, "t");
        // 相対コンテナ (50, 40, 200x150) 内で、元は top: anchor(bottom) が下 (120 + 40 > 150) にはみ出す
        // flip-start で left: anchor(right) (70 → 50+70=120), top: anchor(top) (100 → 40+100=140), height: 25px, width: auto (< 25)
        assertEquals(50 + 70, t.x(), 0.5f);
        assertEquals(40 + 100, t.y(), 0.5f);
        assertEquals(25, t.h(), 0.5f);
        assertTrue(t.w() > 0 && t.w() < 25, "width: auto は内容 x の固有幅になる: " + t.w());
    }
}
