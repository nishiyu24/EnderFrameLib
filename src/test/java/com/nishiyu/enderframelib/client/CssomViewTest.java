package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.api.dom.DOMException;
import com.nishiyu.enderframelib.api.dom.DOMRect;
import com.nishiyu.enderframelib.api.dom.Element;
import com.nishiyu.enderframelib.api.dom.IntersectionObserver;
import com.nishiyu.enderframelib.api.dom.ResizeObserver;
import com.nishiyu.enderframelib.common.BoxLayoutQuery;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.RenderPipeline;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CSSOM View（getBoundingClientRect・offset* / client* / scroll*・scrollTo・各 Observer・getComputedStyle・matchMedia）。 */
public class CssomViewTest {

    private static final int W = 640;
    private static final int H = 480;

    /** セッションと同じ配線: レイアウト後に読み口を更新し、スクロールは位置の決め直しで反映する。 */
    private static final class Page {
        final UiDocumentCompiler compiler = new UiDocumentCompiler();
        final Document doc;
        final HtmlDocument live;
        final InteractionState state = new InteractionState();
        final ScrollState scroll = new ScrollState();
        final BoxLayoutQuery query;
        LayoutBox root;

        Page(String html) {
            doc = compiler.parseDocument(html, "", W, H);
            live = (HtmlDocument) doc.root;
            live.setInteractionState(state);
            query = new BoxLayoutQuery(scroll, this::reposition);
            live.setLayoutQuery(query);
            layout();
        }

        void layout() {
            root = compiler.layoutDocument(doc, W, H, Map.of(), scroll, FontResolver.FALLBACK, state, root);
            query.update(root, doc.sheet, W, H);
            live.notifyLayoutObservers();
        }

        void reposition() {
            RenderPipeline.repositionLayout(root, W, H, scroll, FontResolver.FALLBACK);
            live.notifyLayoutObservers();
        }

        Element el(String id) {
            return (Element) live.getElementById(id);
        }
    }

    private static final String BOX_HTML = """
            <html><head><style>
              body { margin: 0; }
              #a { width: 100px; height: 50px; padding: 5px; border: 2px solid #000; margin: 10px; }
              #p { position: relative; left: 20px; top: 30px; width: 300px; height: 200px; border: 3px solid #000; }
              #c { position: absolute; left: 7px; top: 9px; width: 10px; height: 10px; }
            </style></head><body>
              <div id="a"></div>
              <div id="p"><div id="c"></div></div>
            </body></html>
            """;

    @Test
    public void boxGeometry() {
        Page page = new Page(BOX_HTML);
        Element a = page.el("a");
        assertEquals(new DOMRect(10, 10, 114, 64), a.getBoundingClientRect());
        assertEquals(List.of(a.getBoundingClientRect()), a.getClientRects());
        assertEquals(114, a.getOffsetWidth());
        assertEquals(64, a.getOffsetHeight());
        assertEquals(110, a.getClientWidth());
        assertEquals(60, a.getClientHeight());
        assertEquals(2, a.getClientLeft());
        assertEquals(2, a.getClientTop());
        assertEquals(110, a.getScrollWidth());
        assertEquals(60, a.getScrollHeight());
    }

    @Test
    public void offsetParentIsNearestPositionedAncestor() {
        Page page = new Page(BOX_HTML);
        Element c = page.el("c");
        Element p = page.el("p");
        assertSame(p, c.getOffsetParent());
        assertEquals(7, c.getOffsetLeft());
        assertEquals(9, c.getOffsetTop());
        assertNull(page.el("a").getOffsetParent());
        assertEquals(10, page.el("a").getOffsetLeft());
    }

    @Test
    public void unlaidOutElementsReadZero() {
        Page page = new Page("<html><body><div id=\"n\" style=\"display:none\">x</div></body></html>");
        Element n = page.el("n");
        assertEquals(DOMRect.EMPTY, n.getBoundingClientRect());
        assertTrue(n.getClientRects().isEmpty());
        assertEquals(0, n.getOffsetWidth());
        assertEquals(0, n.getScrollHeight());
    }

    private static final String SCROLL_HTML = """
            <html><head><style>
              body { margin: 0; }
              #s { width: 200px; height: 100px; overflow: auto; }
              .row { height: 30px; }
            </style></head><body><div id="s">
              <div class="row" id="r0"></div><div class="row" id="r1"></div><div class="row" id="r2"></div>
              <div class="row" id="r3"></div><div class="row" id="r4"></div><div class="row" id="r5"></div>
              <div class="row" id="r6"></div><div class="row" id="r7"></div><div class="row" id="r8"></div>
              <div class="row" id="r9"></div>
            </div></body></html>
            """;

    @Test
    public void scrollingMovesContentAndClamps() {
        Page page = new Page(SCROLL_HTML);
        Element s = page.el("s");
        assertEquals(300, s.getScrollHeight());
        assertEquals(100, s.getClientHeight());
        assertEquals(0f, s.getScrollTop());
        float rowY = page.el("r2").getBoundingClientRect().y();

        s.setScrollTop(45.5f);
        assertEquals(45.5f, s.getScrollTop(), "小数のスクロール位置を保つ");
        assertEquals(rowY - 45.5f, page.el("r2").getBoundingClientRect().y(), 0.001f);

        s.setScrollTop(9999);
        assertEquals(200f, s.getScrollTop(), "最大値に丸める");
        s.scrollTo(0, -5);
        assertEquals(0f, s.getScrollTop(), "負は 0");
        s.scrollBy(0, 60);
        assertEquals(60f, s.getScrollTop());
        s.setScrollLeft(50);
        assertEquals(0f, s.getScrollLeft(), "横に溢れない要素は動かない");
    }

    @Test
    public void nonScrollableElementIgnoresScrollTo() {
        Page page = new Page(BOX_HTML);
        Element a = page.el("a");
        a.setScrollTop(10);
        assertEquals(0f, a.getScrollTop());
    }

    @Test
    public void scrollIntoViewBringsRowToTop() {
        Page page = new Page(SCROLL_HTML);
        page.el("r7").scrollIntoView();
        assertEquals(200f, page.el("s").getScrollTop(), "max に丸められる（210 は範囲外）");
        page.el("s").setScrollTop(0);
        page.el("r3").scrollIntoView();
        assertEquals(90f, page.el("s").getScrollTop());
        assertEquals(page.el("s").getBoundingClientRect().y(), page.el("r3").getBoundingClientRect().y(), 0.001f);
    }

    @Test
    public void intersectionObserverFollowsScrolling() {
        Page page = new Page(SCROLL_HTML);
        List<IntersectionObserver.Entry> seen = new ArrayList<>();
        IntersectionObserver observer = new IntersectionObserver(seen::addAll);
        observer.observe(page.el("r0"));
        observer.observe(page.el("r9"));
        page.reposition();
        assertEquals(2, seen.size(), "最初の評価で両方が通知される");
        assertTrue(seen.stream().anyMatch(e -> e.target() == page.el("r0") && e.isIntersecting()));
        assertTrue(seen.stream().anyMatch(e -> e.target() == page.el("r9") && !e.isIntersecting()));

        seen.clear();
        page.el("s").setScrollTop(9999);
        assertEquals(2, seen.size(), "r0 は外へ、r9 は中へ");
        assertTrue(seen.stream().anyMatch(e -> e.target() == page.el("r9") && e.isIntersecting() && e.intersectionRatio() == 1f));
        assertTrue(seen.stream().anyMatch(e -> e.target() == page.el("r0") && !e.isIntersecting()));

        seen.clear();
        page.reposition();
        assertTrue(seen.isEmpty(), "変化が無ければ通知しない");
        observer.disconnect();
        page.el("s").setScrollTop(0);
        assertTrue(seen.isEmpty());
    }

    @Test
    public void resizeObserverReportsContentBox() {
        Page page = new Page(BOX_HTML);
        List<ResizeObserver.Entry> seen = new ArrayList<>();
        ResizeObserver observer = new ResizeObserver(seen::addAll);
        observer.observe(page.el("a"));
        page.layout();
        assertEquals(1, seen.size());
        assertEquals(new DOMRect(5, 5, 100, 50), seen.get(0).contentRect());

        seen.clear();
        page.layout();
        assertTrue(seen.isEmpty(), "大きさが同じなら通知しない");

        page.el("a").setAttribute("style", "width: 140px");
        page.layout();
        assertEquals(1, seen.size());
        assertEquals(140f, seen.get(0).contentRect().width());
        observer.disconnect();
    }

    @Test
    public void computedStyleIsReadOnlyAndUsesUsedValues() {
        Page page = new Page(BOX_HTML);
        var style = page.live.getComputedStyle(page.el("a"));
        assertEquals("100px", style.getPropertyValue("width"));
        assertEquals("50px", style.getPropertyValue("height"));
        assertEquals("5px", style.getPropertyValue("padding-top"));
        assertEquals("10px", style.getPropertyValue("margin-left"));
        assertThrows(DOMException.class, () -> style.setProperty("width", "1px"));
        assertEquals("", style.getPropertyValue("no-such-property"));
    }

    @Test
    public void viewportAndMediaQueries() {
        Page page = new Page(BOX_HTML);
        assertEquals(W, page.live.getInnerWidth());
        assertEquals(H, page.live.getInnerHeight());
        assertTrue(page.live.matchMedia("(min-width: 500px)").matches());
        assertFalse(page.live.matchMedia("(max-width: 300px)").matches());
        assertEquals("(min-width: 500px)", page.live.matchMedia("(min-width: 500px)").media());
    }

    @Test
    public void documentWithoutLayoutReadsZero() {
        UiDocumentCompiler compiler = new UiDocumentCompiler();
        Document doc = compiler.parseDocument(BOX_HTML, "", W, H);
        Element a = (Element) ((HtmlDocument) doc.root).getElementById("a");
        assertEquals(0, a.getOffsetWidth());
        assertEquals(DOMRect.EMPTY, a.getBoundingClientRect());
        assertEquals(0, ((HtmlDocument) doc.root).getInnerWidth());
    }
}
