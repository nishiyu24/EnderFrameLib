package com.nishiyu.enderframelib.api;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.api.dom.Document;
import com.nishiyu.enderframelib.api.dom.Element;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** WebDocument と標準 DOM（api.dom）の統合: 公開 API だけで文書を読み・書き・購読できる。 */
class WebDocumentDomTest {

    private static final String PAGE =
            "<html><body style=\"margin:0\">"
                    + "<div id=\"box\" class=\"c\" style=\"width:40px;height:30px;background:#ff0000\"></div>"
                    + "<button id=\"btn\" style=\"display:block;width:60px;height:20px\">go</button>"
                    + "</body></html>";

    private static int red(int argb) { return (argb >> 16) & 0xFF; }
    private static int green(int argb) { return (argb >> 8) & 0xFF; }
    private static int blue(int argb) { return argb & 0xFF; }

    private static WebViewSurface surface(String html) {
        return EnderEngine.html(html).size(100, 80).clearColor(0xFFFFFFFF).build();
    }

    private static int pixel(WebViewSurface s, int x, int y) {
        return s.pixels()[y * s.pixelWidth() + x];
    }

    @Test
    void domIsReachableFromTheDocumentAndTheSurface() {
        try (WebViewSurface s = surface(PAGE)) {
            Document d = s.document().dom();
            assertNotNull(d);
            assertSame(d, s.dom(), "surface.dom() は document().dom() の近道");
            Element box = d.getElementById("box");
            assertNotNull(box);
            assertEquals("DIV", box.getTagName().toUpperCase());
            assertEquals("c", box.getClassName());
            assertNotNull(d.getBody());
            assertEquals(box, d.querySelector("#box"));
            assertEquals(2, d.getBody().getChildElementCount());
        }
    }

    @Test
    void convenienceMethodsAgreeWithTheDom() {
        try (WebViewSurface s = surface(PAGE)) {
            WebDocument w = s.document();
            Element box = w.dom().getElementById("box");
            assertEquals(w.hasElement("box"), box != null);
            assertEquals(box.getTagName().toLowerCase(), w.tagOf("box"));
            assertEquals(box.getAttribute("class"), w.attributeOf("box", "class"));
            assertFalse(w.hasElement("nope"));
            assertNull(w.dom().getElementById("nope"));
        }
    }

    @Test
    void styleChangesThroughTheDomAreRenderedOnTheNextFrame() {
        try (WebViewSurface s = surface(PAGE)) {
            s.frame(0, true);
            assertEquals(255, red(pixel(s, 10, 10)));
            s.dom().getElementById("box").getStyle().setProperty("background", "#0000ff");
            assertTrue(s.frame(16, false), "DOM の変更は次のフレームで描かれる");
            int px = pixel(s, 10, 10);
            assertEquals(255, blue(px));
            assertEquals(0, red(px));
        }
    }

    @Test
    void attributeAndInnerHtmlChangesRelayout() {
        try (WebViewSurface s = surface(PAGE)) {
            s.frame(0, true);
            Element box = s.dom().getElementById("box");
            box.setAttribute("style", "width:80px;height:30px;background:#00ff00");
            s.frame(16, false);
            assertEquals(255, green(pixel(s, 70, 10)), "幅が広がった分も緑");
            s.dom().getBody().setInnerHTML("<div id=\"fresh\" style=\"width:20px;height:20px;background:#ff0000\"></div>");
            s.frame(32, false);
            assertTrue(s.document().hasElement("fresh"));
            assertFalse(s.document().hasElement("box"));
            assertEquals(255, red(pixel(s, 5, 5)));
        }
    }

    @Test
    void createdElementsCanBeAppended() {
        try (WebViewSurface s = surface("<html><body style=\"margin:0\"></body></html>")) {
            Document d = s.dom();
            Element e = d.createElement("div");
            e.setId("made");
            e.setAttribute("style", "width:30px;height:30px;background:#ff0000");
            d.getBody().append(e);
            s.frame(0, true);
            assertTrue(s.document().hasElement("made"));
            assertEquals(255, red(pixel(s, 10, 10)));
        }
    }

    @Test
    void domEventListenersReceiveSurfaceInput() {
        List<String> clicks = new ArrayList<>();
        try (WebViewSurface s = surface(PAGE)) {
            s.dom().getElementById("btn").addEventListener("click", ev -> clicks.add(ev.getType()));
            s.frame(0, true);
            s.mouseMove(10, 40);
            s.frame(16, false);
            s.mouseButton(true);
            s.mouseButton(false);
            s.frame(32, false);
            assertEquals(List.of("click"), clicks);
        }
    }

    @Test
    void domMatchesSelectorsAndWalksTheTree() {
        try (WebViewSurface s = surface(PAGE)) {
            Element btn = s.dom().querySelector("button");
            assertNotNull(btn);
            assertTrue(btn.matches("#btn"));
            assertEquals("BODY", btn.closest("body").getTagName().toUpperCase());
            assertEquals(2, s.dom().querySelectorAll("body > *").getLength());
            assertEquals("btn", s.dom().getElementById("box").getNextElementSibling().getId());
        }
    }
}
