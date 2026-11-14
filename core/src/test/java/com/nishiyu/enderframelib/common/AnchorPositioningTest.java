package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Anchor Positioning（anchor-name / position-anchor / anchor()）。 */
public class AnchorPositioningTest {

    private static final int W = 400, H = 300;
    private static final String CSS = "<style>body{margin:0} #a{position:absolute;left:100px;top:50px;width:80px;height:30px;anchor-name:--a}"
            + " #t{position:absolute;width:40px;height:20px}</style>";

    private static LayoutBox layout(String html) {
        UiDocumentCompiler c = new UiDocumentCompiler();
        Document doc = c.parseDocument(CSS + html, "", W, H);
        return c.layoutDocument(doc, W, H, Map.of(), new ScrollState(), FontResolver.FALLBACK);
    }

    private static LayoutBox find(LayoutBox box, String id) {
        if (id.equals(box.node.attrs.get("id"))) return box;
        for (LayoutBox c : box.elementChildren) {
            LayoutBox h = find(c, id);
            if (h != null) return h;
        }
        return null;
    }

    @Test
    public void belowAnchorWithPositionAnchor() {
        LayoutBox root = layout("<div id=a></div><div id=t style='position-anchor:--a;top:anchor(bottom);left:anchor(left)'></div>");
        LayoutBox t = find(root, "t");
        assertEquals(100, t.x, 0.5f);
        assertEquals(80, t.y, 0.5f);
    }

    @Test
    public void explicitNameRightOfAnchorAndCentered() {
        LayoutBox root = layout("<div id=a></div><div id=t style='top:anchor(--a center);left:anchor(--a right)'></div>");
        LayoutBox t = find(root, "t");
        assertEquals(180, t.x, 0.5f);
        assertEquals(65, t.y, 0.5f);
    }

    @Test
    public void rightAndBottomInsetsAlignEdgesToTheAnchor() {
        // right: anchor(right) は右端をアンカーの右端へ、bottom: anchor(top) は下端をアンカーの上端へ
        LayoutBox root = layout("<div id=a></div><div id=t style='position-anchor:--a;bottom:anchor(top);right:anchor(right)'></div>");
        LayoutBox t = find(root, "t");
        assertEquals(180 - 40, t.x, 0.5f);
        assertEquals(50 - 20, t.y, 0.5f);
    }

    @Test
    public void popoverIsAnchoredToItsInvoker() {
        UiDocumentCompiler c = new UiDocumentCompiler();
        Document doc = c.parseDocument(CSS
                + "<button id=b popovertarget=t style='position:absolute;left:10px;top:200px;width:60px;height:24px'>b</button>"
                + "<div id=t popover style='top:anchor(bottom);left:anchor(left);margin:0;width:40px;height:20px'></div>", "", W, H);
        ((HtmlElement) doc.dom().getElementById("t")).showPopover();
        LayoutBox r = c.layoutDocument(doc, W, H, Map.of(), new ScrollState(), FontResolver.FALLBACK,
                ((HtmlDocument) doc.root).interactionState());
        LayoutBox t = find(r, "t");
        assertEquals(10, t.x, 0.5f);
        assertEquals(224, t.y, 0.5f);
    }

    @Test
    public void anchorSizeMatchesTheAnchorWidth() {
        LayoutBox root = layout("<div id=a></div><div id=t style='position-anchor:--a;top:anchor(bottom);left:anchor(left);width:anchor-size(width);height:anchor-size(--a height)'><div id=c style='height:100%'></div></div>");
        LayoutBox t = find(root, "t");
        assertEquals(80, t.w, 0.5f);
        assertEquals(30, t.h, 0.5f);
        assertEquals(80, find(root, "c").w, 0.5f);
        assertEquals(30, find(root, "c").h, 0.5f);
    }

    @Test
    public void positionAreaPlacesBesideTheAnchor() {
        LayoutBox root = layout("<div id=a></div><div id=t style='position-anchor:--a;position-area:bottom center'></div>");
        LayoutBox t = find(root, "t");
        assertEquals(120, t.x, 0.5f);
        assertEquals(80, t.y, 0.5f);
        LayoutBox r2 = layout("<div id=a></div><div id=t style='position-anchor:--a;position-area:left'></div>");
        LayoutBox t2 = find(r2, "t");
        assertEquals(60, t2.x, 0.5f);
        assertEquals(55, t2.y, 0.5f);
    }

    @Test
    public void positionAreaSpanAndExplicitInsetPrecedence() {
        LayoutBox span = layout("<div id=a></div><div id=t style='position-anchor:--a;position-area:bottom span-right'></div>");
        LayoutBox ts = find(span, "t");
        assertEquals(100, ts.x, 0.5f);
        assertEquals(80, ts.y, 0.5f);

        LayoutBox spanLeft = layout("<div id=a></div><div id=t style='position-anchor:--a;position-area:top span-left'></div>");
        LayoutBox tl = find(spanLeft, "t");
        assertEquals(140, tl.x, 0.5f);
        assertEquals(30, tl.y, 0.5f);

        LayoutBox explicit = layout("<div id=a></div><div id=t style='position-anchor:--a;left:10px;position-area:bottom right'></div>");
        LayoutBox te = find(explicit, "t");
        assertEquals(10, te.x, 0.5f);
        assertEquals(80, te.y, 0.5f);
    }
}
