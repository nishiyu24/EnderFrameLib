package com.nishiyu.enderframelib.core.paint.scene;

import com.nishiyu.enderframelib.api.dom.DOMRect;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.ElementInspector;
import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.common.UiFocus;
import com.nishiyu.enderframelib.common.Keys;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SceneQuery}・{@link com.nishiyu.enderframelib.common.BoxLayoutQuery}・{@link UiFocus}・{@link ElementInspector} の単体テスト（M3b-3）。
 */
class SceneQueryTest {
    private static final int W = 400, H = 300;

    private static final class Compiled {
        final Document doc;
        final DisplayScene scene;

        Compiled(String html) {
            UiDocumentCompiler c = new UiDocumentCompiler();
            this.doc = c.parseDocument("<style>body{margin:0}</style>" + html, "", W, H);
            InteractionState ist = doc.root instanceof HtmlDocument hd && hd.interactionState() instanceof InteractionState s ? s : new InteractionState();
            this.scene = c.layoutDocument(doc, W, H, Map.of(), new ScrollState(), FontResolver.FALLBACK, ist);
        }

        HtmlElement el(String id) {
            return (HtmlElement) doc.root.getElementById(id);
        }
    }

    @Test
    void locateLocateByIdLocateByNodeIdAndIndexElements() {
        Compiled c = new Compiled(
                "<div id='outer' style='padding:10px;border:2px solid #000;margin:5px;width:200px'>"
                        + "  <div id='inner' style='width:80px;height:30px'>hello</div>"
                        + "</div>");
        DynamicSceneState dyn = DynamicSceneState.of(null, W, H);
        HtmlElement inner = c.el("inner");

        SceneLocation byNode = SceneQuery.locate(c.scene, dyn, inner);
        SceneLocation byId = SceneQuery.locateById(c.scene, dyn, "inner");
        SceneLocation byNodeId = SceneQuery.locateByNodeId(c.scene, dyn, inner.nodeId());
        Map<HtmlNode, SceneLocation> indexed = SceneQuery.indexElements(c.scene, dyn);

        assertNotNull(byNode);
        assertEquals(byNode, byId);
        assertEquals(byNode, byNodeId);
        assertEquals(byNode, indexed.get(inner));
        assertEquals(17f, byNode.x(), 0.1f); // outer margin(5) + border(2) + padding(10)
        assertEquals(17f, byNode.y(), 0.1f); // outer margin(5) + border(2) + padding(10)
        assertEquals(80f, byNode.w(), 0.1f);
        assertEquals(30f, byNode.h(), 0.1f);
    }

    @Test
    void scrollOffsetUpdatesLocatedWithoutRebuildingScene() {
        Compiled c = new Compiled(
                "<div id='sc' style='width:200px;height:100px;overflow-y:auto'>"
                        + "  <div style='height:80px'></div>"
                        + "  <div id='target' style='height:40px'>x</div>"
                        + "  <div style='height:200px'></div>"
                        + "</div>");
        ScrollState scroll = new ScrollState();
        SceneLocation before = SceneQuery.locateById(c.scene, DynamicSceneState.of(scroll, W, H), "target");
        assertNotNull(before);
        assertEquals(80f, before.y(), 0.1f);

        scroll.setScroll(c.el("sc"), 0f, 50f);
        SceneLocation after = SceneQuery.locateById(c.scene, DynamicSceneState.of(scroll, W, H), "target");
        assertNotNull(after);
        assertEquals(30f, after.y(), 0.1f);
        assertSame(before.layer(), after.layer(), "DisplayScene の節はそのまま再利用される");
    }

    @Test
    void childrenAndRenderedLegend() {
        Compiled c = new Compiled(
                "<fieldset id='fs' style='border:2px solid #000;padding:8px'>"
                        + "  <legend id='leg'>Caption</legend>"
                        + "  <div id='a' style='height:20px'>A</div>"
                        + "  <div id='b' style='height:20px'>B</div>"
                        + "</fieldset>");
        DynamicSceneState dyn = DynamicSceneState.of(null, W, H);
        SceneLocation fs = SceneQuery.locateById(c.scene, dyn, "fs");
        assertNotNull(fs);

        SceneLocation legend = SceneQuery.renderedLegend(fs, dyn);
        assertNotNull(legend);
        assertEquals("leg", legend.node().key());

        List<SceneLocation> kids = SceneQuery.children(fs, dyn);
        List<String> ids = kids.stream().map(k -> k.node() != null ? k.node().key() : "").toList();
        assertTrue(ids.contains("leg"));
        assertTrue(ids.contains("a"));
        assertTrue(ids.contains("b"));
    }

    @Test
    void boxLayoutQueryScrollsWithoutRebuildingScene() {
        String html = "<style>body{margin:0}#sc{width:200px;height:100px;overflow-y:auto}#sp{height:120px}#item{height:40px}</style>"
                + "<div id='sc'><div id='sp'></div><div id='item'></div><div style='height:200px'></div></div>";
        try (StandaloneView v = new StandaloneView(html, W, H, FontResolver.FALLBACK);
             var be = new SoftwareRenderBackend(W, H, 1f)) {
            v.frame(1000, be, true);
            DisplayScene sceneBefore = v.ui().scene();
            assertNotNull(sceneBefore);

            HtmlElement item = (HtmlElement) v.root().getElementById("item");
            DOMRect r0 = item.getBoundingClientRect();
            assertEquals(120.0, r0.top(), 0.5);

            HtmlElement sc = (HtmlElement) v.root().getElementById("sc");
            sc.setScrollTop(60.0f);
            DOMRect r1 = item.getBoundingClientRect();
            assertEquals(60.0, r1.top(), 0.5, "スクロールだけで getBoundingClientRect が更新される");
            assertSame(sceneBefore, v.ui().scene(), "スクロールだけの問い合わせでは DisplayScene を作り直さない");
        }
    }

    @Test
    void uiFocusTabOrderAndSpatialNavigation() {
        String html = "<style>body{margin:0}button{position:absolute;width:60px;height:30px}</style>"
                + "<button id='b2' tabindex='2' style='left:10px;top:10px'>2</button>"
                + "<button id='b1' tabindex='1' style='left:120px;top:10px'>1</button>"
                + "<button id='b0' style='left:10px;top:80px'>0</button>"
                + "<button id='skip' tabindex='-1' style='left:120px;top:80px'>-1</button>"
                + "<button id='dis' disabled style='left:200px;top:80px'>x</button>";
        try (StandaloneView v = new StandaloneView(html, W, H, FontResolver.FALLBACK);
             var be = new SoftwareRenderBackend(W, H, 1f)) {
            v.frame(1000, be, true);
            List<UiFocus.Candidate> order = UiFocus.tabOrder(v.ui());
            assertEquals(List.of("b1", "b2", "b0"),
                    order.stream().map(c -> c.node().key()).toList());

            HtmlNode b1 = v.ui().findNode("b1");
            HtmlNode b2 = v.ui().findNode("b2");
            HtmlNode b0 = v.ui().findNode("b0");
            assertSame(b2, UiFocus.next(v.ui(), b1, 1));
            assertSame(b0, UiFocus.next(v.ui(), b2, 1));
            assertSame(b1, UiFocus.next(v.ui(), b0, 1));
            assertSame(b0, UiFocus.next(v.ui(), b1, -1));

            assertSame(b1, UiFocus.spatial(v.ui(), b2, Keys.RIGHT));
            assertSame(b2, UiFocus.spatial(v.ui(), b1, Keys.LEFT));
            assertSame(b0, UiFocus.spatial(v.ui(), b2, Keys.DOWN));
            assertSame(b2, UiFocus.spatial(v.ui(), b0, Keys.UP));
        }
    }

    @Test
    void elementInspectorInspectAndPick() {
        String html = "<style>body{margin:0}"
                + "#card{position:absolute;left:20px;top:30px;width:100px;height:50px;"
                + "padding:6px;border:2px solid #112233;margin:4px;background-color:#abcdef}</style>"
                + "<div id='card' class='box primary'>Hi</div>";
        try (StandaloneView v = new StandaloneView(html, W, H, FontResolver.FALLBACK);
             var be = new SoftwareRenderBackend(W, H, 1f)) {
            v.frame(1000, be, true);
            HtmlNode card = v.ui().findNode("card");
            ElementInspector.Info info = ElementInspector.inspect(v.ui(), card);
            assertNotNull(info);
            assertEquals("div#card.box.primary", info.selector());
            assertEquals(24f, info.x(), 0.1f); // left 20 + margin 4
            assertEquals(34f, info.y(), 0.1f); // top 30 + margin 4
            assertEquals(4f, info.margin()[0], 0.1f);
            assertEquals(2f, info.border()[0], 0.1f);
            assertEquals(6f, info.padding()[0], 0.1f);
            assertEquals("Hi", info.text());

            HtmlNode picked = ElementInspector.pick(v.ui(), 50, 50);
            assertSame(card, picked);
            assertFalse(ElementInspector.overlay(info, W, H).isEmpty());
        }
    }
}
