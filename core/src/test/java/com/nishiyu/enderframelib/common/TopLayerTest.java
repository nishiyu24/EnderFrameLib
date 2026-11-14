package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Top Layer（dialog.showModal・popover）。 */
public class TopLayerTest {

    private static final int W = 400, H = 300;
    private static final String CSS = "<style>body{margin:0;font:16px/20px monospace} #d,#p{width:100px;height:40px;padding:0;border:0}</style>";

    private static final class Ctx {
        final UiDocumentCompiler compiler = new UiDocumentCompiler();
        final Document doc;

        Ctx(String html) {
            doc = compiler.parseDocument(CSS + html, "", W, H);
        }

        LayoutBox layout() {
            return compiler.layoutDocument(doc, W, H, Map.of(), new ScrollState(), FontResolver.FALLBACK,
                    ((com.nishiyu.enderframelib.core.dom.node.HtmlDocument) doc.root).interactionState());
        }

        HtmlElement el(String id) {
            return (HtmlElement) doc.dom().getElementById(id);
        }
    }

    /** 描かれる箱（{@code display: none} の箱は無いものとして扱う）。 */
    private static LayoutBox find(LayoutBox box, String id) {
        if (id.equals(box.node.attrs.get("id"))) {
            return box.display != null && box.display.outer() == LayoutBox.Display.Outer.NONE ? null : box;
        }
        for (LayoutBox c : box.elementChildren) {
            LayoutBox h = find(c, id);
            if (h != null) return h;
        }
        return null;
    }

    @Test
    public void closedDialogIsNotRendered() {
        Ctx c = new Ctx("<dialog id=d>x</dialog>");
        assertNull(find(c.layout(), "d"));
    }

    @Test
    public void showModalCentersInViewportAndCloseHides() {
        Ctx c = new Ctx("<div style='height:1000px'></div><dialog id=d>x</dialog>");
        c.el("d").showModal();
        LayoutBox d = find(c.layout(), "d");
        assertNotNull(d);
        assertEquals((W - 100) / 2f, d.x, 0.5f);
        assertEquals((H - 40) / 2f, d.y, 0.5f);
        c.el("d").close();
        assertNull(find(c.layout(), "d"));
    }

    @Test
    public void popoverIsHiddenUntilShown() {
        Ctx c = new Ctx("<div id=p popover>hi</div>");
        assertNull(find(c.layout(), "p"));
        c.el("p").showPopover();
        LayoutBox p = find(c.layout(), "p");
        assertNotNull(p);
        assertEquals((W - 100) / 2f, p.x, 0.5f);
        assertTrue(c.el("p").togglePopover() == false);
        assertNull(find(c.layout(), "p"));
    }

    /** 表示中の popover は、祖先のスタッキングコンテキストの外の、あとから来る z-index の高い兄弟より前面に描かれる。 */
    @Test
    public void popoverPaintsAboveLaterHigherZIndexSiblings() {
        Ctx c = new Ctx("<div style='position:relative;z-index:1'><div id=p popover style='background:#f00'>hi</div></div>"
                + "<div id=s style='position:absolute;left:0;top:0;width:50px;height:50px;z-index:5;background:#00f'></div>");
        c.el("p").showPopover();
        LayoutBox root = c.layout();
        var renderer = new com.nishiyu.enderframelib.core.paint.DisplayListBuilder(W, H, c.doc.sheet.keyframes, new ScrollState());
        var items = renderer.render(root);
        int ip = -1, is = -1;
        for (int i = 0; i < items.size(); i++) {
            String id = items.get(i).id();
            if (id.equals("p") || id.startsWith("p_")) ip = i;
            if (id.equals("s") || id.startsWith("s_")) is = i;
        }
        assertTrue(is >= 0 && ip >= 0, "items found");
        assertTrue(ip > is, "popover must paint after the z-index:5 sibling (ip=" + ip + ", is=" + is + ")");
    }
}
