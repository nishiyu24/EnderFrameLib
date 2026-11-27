package com.nishiyu.enderframelib.core.paint.scene;

import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.box.BoxTreeCompiler;
import com.nishiyu.enderframelib.core.box.StyleSource;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.dom.node.ChangeLedger;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.fragment.FragmentLayout;
import com.nishiyu.enderframelib.core.layout.fragment.LayoutFragment;
import com.nishiyu.enderframelib.core.layout.fragment.LayoutSession;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.SceneDisplayListAdapter;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DisplayScene} と {@link SceneEmitter} の契約の検証（M3b-2。計画書 §3.4）:
 * 数値 {@code nodeId} 索引・チャンク境界による二分探索カリング・スクロール外 {@code stickyDescendants} の回収・
 * CSS 2.2 付録 E の重なり順・{@code FlowChunk ==} によるチャンク使い回し・スクロール不変性を確かめる。
 */
class DisplaySceneTest {
    private static final int W = 400, H = 300;

    private static DisplayScene compile(String html, int w, int h) {
        Document doc = new UiDocumentCompiler().parseDocument(html, "", w, h);
        LayoutSession session = new LayoutSession(FontResolver.FALLBACK);
        StyleSource src = new StyleSource(doc.sheet, new InteractionState(), Map.of(), w, h);
        var boxRoot = new BoxTreeCompiler(src).compile(doc.root, null);
        LayoutFragment frag = new FragmentLayout(session).layout(boxRoot.layout, w, h);
        return new SceneEmitter(session, doc.sheet.keyframes).emit(frag, boxRoot, w, h);
    }

    @Test
    void nodeIdIndexMapsRealDomNodesAndExcludesGeneratedBoxes() {
        String html = "<style>#a::before{content:'pre'} #a::after{content:'post'}</style>"
                + "<div id=a><span id=b>text</span></div>";
        Document doc = new UiDocumentCompiler().parseDocument(html, "", W, H);
        LayoutSession session = new LayoutSession(FontResolver.FALLBACK);
        StyleSource src = new StyleSource(doc.sheet, new InteractionState(), Map.of(), W, H);
        var boxRoot = new BoxTreeCompiler(src).compile(doc.root, null);
        LayoutFragment frag = new FragmentLayout(session).layout(boxRoot.layout, W, H);
        DisplayScene scene = new SceneEmitter(session, doc.sheet.keyframes).emit(frag, boxRoot, W, H);

        HtmlNode a = doc.root.getElementById("a");
        HtmlNode b = doc.root.getElementById("b");
        SceneLayer la = scene.layerByNodeId(a.nodeId());
        SceneLayer lb = scene.layerByNodeId(b.nodeId());
        assertNotNull(la);
        assertNotNull(lb);
        assertSame(a, la.node);
        assertSame(b, lb.node);
        assertEquals(a.nodeId(), la.nodeId);
        // 疑似要素の節は nodeId == 0 で索引に入らない
        int pseudoCount = 0;
        for (SceneChunk c : la.children.chunks) {
            for (SceneLayer child : c.layers) {
                if (child.node != null && child.node.isPseudoNode) {
                    assertEquals(0, child.nodeId);
                    pseudoCount++;
                }
            }
        }
        assertTrue(pseudoCount >= 2, "::before / ::after が存在する");
    }

    @Test
    void sceneChunkBoundsAndMonotonicYCullOffscreenChunks() {
        StringBuilder sb = new StringBuilder("<style>body{margin:0}#sc{height:120px;overflow-y:auto}.r{height:20px}</style><div id=sc><ul id=ul style='margin:0;padding:0'>");
        for (int i = 0; i < 8000; i++) sb.append("<li class=r id=r").append(i).append(">row</li>");
        sb.append("</ul></div>");
        DisplayScene scene = compile(sb.toString(), W, H);

        SceneLayer ul = findById(scene.root, "ul");
        assertNotNull(ul);
        assertTrue(ul.children.monotonicY, "8,000 行の一覧のチャンクは Y 単調");
        assertTrue(ul.children.chunks.length >= 200, "32 子単位のチャンク数: " + ul.children.chunks.length);

        List<Integer> visited = new ArrayList<>();
        // スクロール位置 y = 50,000..50,120 付近だけを可視範囲として問い合わせる
        ul.children.forEachVisible(0f, 50_000f, W, 50_120f, (k, chunk, ox, oy) -> visited.add(k));
        assertTrue(visited.size() >= 1 && visited.size() <= 3, "可視チャンク数 " + visited.size());
        // その先頭と末尾のチャンクが本当に可視範囲と交差していること
        int first = visited.getFirst();
        SceneChunk fc = ul.children.chunks[first];
        float cMinY = ul.children.chunkY[first] + fc.minY;
        float cMaxY = ul.children.chunkY[first] + fc.maxY;
        assertTrue(cMaxY >= 50_000f && cMinY <= 50_120f);
    }

    @Test
    void stickyDescendantsRecoveredOutsideVisibleChunks() {
        StringBuilder sb = new StringBuilder("<style>body{margin:0}#sc{width:200px;height:100px;overflow-y:auto}"
                + "#st{position:sticky;top:0;height:16px;background:#fc6}.r{height:20px}</style><div id=sc><div id=wrap>"
                + "<div id=st>head</div>");
        for (int i = 0; i < 300; i++) sb.append("<div class=r>r").append(i).append("</div>");
        sb.append("</div></div>");
        DisplayScene scene = compile(sb.toString(), W, H);

        SceneLayer sc = findById(scene.root, "sc");
        assertNotNull(sc);
        assertTrue(sc.isScrollContainer());
        assertEquals(1, sc.stickyDescendants.length, "スクロールコンテナが子孫の sticky を回収");
        assertEquals("st", sc.stickyDescendants[0].node.getId());
        // wrap 自体はスクロールコンテナでないので stickyDescendants は空
        SceneLayer wrap = findById(scene.root, "wrap");
        assertEquals(0, wrap.stickyDescendants.length);

        // チャンクカリングで先頭チャンクを外した y=2000 でも stickyDescendants から sticky 要素を取り出せる
        ScrollState scroll = new ScrollState();
        scroll.setScroll(sc.node, 0f, 2000f);
        SceneLocation stickyLoc = SceneQuery.locateById(scene, DynamicSceneState.of(scroll, W, H), "st");
        assertNotNull(stickyLoc);
        assertEquals(0f, stickyLoc.y(), 0.01f, "スクロール量 2000 でもビューポート上端に張り付く");
    }

    @Test
    void stackingOrderPassesPreserveCssAppendixE() {
        String html = "<style>body{margin:0}"
                + "#sc{position:relative;width:120px;height:120px;overflow:auto}"
                + "#neg{position:relative;z-index:-1;width:60px;height:60px;background:#f00}"
                + "#blk{width:60px;height:60px;background:#0f0;margin-top:-60px}"
                + "#pos0{position:relative;width:40px;height:40px;background:#00f;margin-top:-60px}"
                + "#posz{position:relative;z-index:2;width:20px;height:20px;background:#ff0;margin-top:-40px}"
                + "</style><div id=sc><div id=neg></div><div id=blk></div><div id=pos0></div><div id=posz></div></div>"
                + "<dialog id=dlg open style='position:fixed;z-index:2147483647;left:80px;top:10px;right:auto;margin:0;width:30px;height:30px;background:#f0f;border:0;padding:0'></dialog>";

        try (StandaloneView sv = new StandaloneView(html, W, H, FontResolver.FALLBACK)) {
            SoftwareRenderBackend be = new SoftwareRenderBackend(W, H, 1f);
            sv.frame(1000, be, true);
            // #sc は z-index: auto なのでスタッキングコンテキストを作らない。#neg (z=-1) は #blk の後ろに描かれる
            assertEquals(0xFFFFFF00, be.pixmap().get(10, 10), "z-index:2 が最前面");
            assertEquals(0xFF0000FF, be.pixmap().get(30, 30), "position:relative (pos0) がブロックの上");
            assertEquals(0xFF00FF00, be.pixmap().get(50, 50), "通常ブロックが z-index:-1 の上");
            assertEquals(0xFFFF00FF, be.pixmap().get(95, 25), "Top Layer が最前面");

            // 伝播ビットの確認
            DisplayScene scene = compile(html, W, H);
            SceneLayer sc = findById(scene.root, "sc");
            assertTrue((sc.propagatedLayers & SceneLayer.LAYER_NEG) != 0, "非スタッキングの親から NEG が伝播");
            assertTrue((sc.propagatedLayers & SceneLayer.LAYER_POSZ) != 0, "POSZ が伝播");
            SceneLayer dlg = findById(scene.root, "dlg");
            assertTrue((dlg.propagatedLayers & SceneLayer.LAYER_HOIST) != 0, "Top Layer は HOIST で伝播");
        }
    }

    @Test
    void flowChunkReusePreservesUnchangedChunksOnTopInsert() {
        StringBuilder sb = new StringBuilder("<style>body{margin:0} li{height:16px}</style><ul id=ul>");
        for (int i = 0; i < 300; i++) sb.append("<li id=li").append(i).append(">row ").append(i).append("</li>");
        sb.append("</ul>");

        Document doc = new UiDocumentCompiler().parseDocument(sb.toString(), "", W, H);
        LayoutSession session = new LayoutSession(FontResolver.FALLBACK);
        StyleSource src = new StyleSource(doc.sheet, new InteractionState(), Map.of(), W, H);
        BoxTreeCompiler compiler = new BoxTreeCompiler(src);
        FragmentLayout layout = new FragmentLayout(session);
        SceneEmitter emitter = new SceneEmitter(session, doc.sheet.keyframes);

        var box0 = compiler.compile(doc.root, null);
        LayoutFragment frag0 = layout.layout(box0.layout, W, H);
        DisplayScene scene0 = emitter.emit(frag0, box0, W, H);
        SceneLayer ul0 = findById(scene0.root, "ul");
        SceneChunk lastChunk0 = ul0.children.chunks[ul0.children.chunks.length - 1];

        // 先頭に 1 件挿入する（台帳経由）
        ChangeLedger ledger = new ChangeLedger();
        HtmlNode ulNode = doc.root.getElementById("ul");
        HtmlElement inserted = new HtmlElement("li");
        inserted.updateTextContent("inserted");
        ulNode.insertBefore(inserted, ulNode.children.getFirst());
        ledger.append(ulNode, com.nishiyu.enderframelib.core.dom.node.ChangeKind.CHILDREN);
        ledger.freeze();

        var box1 = compiler.compile(doc.root, ledger);
        LayoutFragment frag1 = layout.layout(box1.layout, W, H);
        DisplayScene scene1 = emitter.emit(frag1, box1, W, H);
        SceneLayer ul1 = findById(scene1.root, "ul");
        SceneChunk lastChunk1 = ul1.children.chunks[ul1.children.chunks.length - 1];

        assertSame(lastChunk0, lastChunk1, "後ろのチャンクは FlowChunk == で同一インスタンスを使い回す");
        assertTrue(emitter.reusedChunks() >= 8, "使い回したチャンク数: " + emitter.reusedChunks());
    }

    @Test
    void scrollDoesNotRebuildDisplaySceneAndUpdatesDynamicOutput() {
        StringBuilder sb = new StringBuilder("<style>body{margin:0}#sc{width:200px;height:100px;overflow:auto}"
                + ".r{height:30px;background:#0a0}.odd{background:#00f}</style><div id=sc>");
        for (int i = 0; i < 20; i++) {
            sb.append("<div class='r").append(i % 2 == 1 ? " odd" : "").append("'>row</div>");
        }
        sb.append("</div>");

        try (StandaloneView sv = new StandaloneView(sb.toString(), W, H, FontResolver.FALLBACK)) {
            SoftwareRenderBackend be = new SoftwareRenderBackend(W, H, 1f);
            sv.frame(1000, be, true);
            assertEquals(0xFF00AA00, be.pixmap().get(20, 15));

            // 同じ DisplayScene に異なる DynamicSceneState を渡すだけでスクロール結果が変わる
            Document doc = sv.ui().document();
            LayoutSession session = new LayoutSession(FontResolver.FALLBACK);
            StyleSource src = new StyleSource(doc.sheet, new InteractionState(), Map.of(), W, H);
            var boxRoot = new BoxTreeCompiler(src).compile(doc.root, null);
            LayoutFragment frag = new FragmentLayout(session).layout(boxRoot.layout, W, H);
            DisplayScene scene = new SceneEmitter(session, doc.sheet.keyframes).emit(frag, boxRoot, W, H);

            ScrollState s1 = new ScrollState();
            s1.setScroll(doc.root.getElementById("sc"), 0f, 30f);
            List<DisplayItem> items1 = new SceneDisplayListAdapter(W, H, doc.sheet.keyframes)
                    .emit(scene, DynamicSceneState.of(s1, W, H));
            SoftwareRenderBackend be1 = new SoftwareRenderBackend(W, H, 1f);
            be1.render(items1, 1000);
            assertEquals(0xFF0000FF, be1.pixmap().get(20, 15), "30px スクロールで 2 行目 (#00f) が先頭に来る");
        }
    }

    @Test
    void parentRelativePositionsShareSubtreesAcrossSiblingGrowth() {
        String html = "<style>body{margin:0} #a{height:20px} #b{height:40px}</style>"
                + "<div id=a></div><div id=b><span id=inner>hi</span></div>";
        Document doc = new UiDocumentCompiler().parseDocument(html, "", W, H);
        LayoutSession session = new LayoutSession(FontResolver.FALLBACK);
        StyleSource src = new StyleSource(doc.sheet, new InteractionState(), Map.of(), W, H);
        BoxTreeCompiler compiler = new BoxTreeCompiler(src);
        FragmentLayout layout = new FragmentLayout(session);
        SceneEmitter emitter = new SceneEmitter(session, doc.sheet.keyframes);

        var box0 = compiler.compile(doc.root, null);
        DisplayScene s0 = emitter.emit(layout.layout(box0.layout, W, H), box0, W, H);
        SceneLayer b0 = findById(s0.root, "b");
        assertEquals(20f, b0.y, 0.01f);

        HtmlElement a = doc.root.getElementById("a");
        ChangeLedger ledger = new ChangeLedger();
        a.setAttribute("style", "height:50px");
        ledger.append(a, com.nishiyu.enderframelib.core.dom.node.ChangeKind.STYLE);
        ledger.freeze();

        var box1 = compiler.compile(doc.root, ledger);
        DisplayScene s1 = emitter.emit(layout.layout(box1.layout, W, H), box1, W, H);
        SceneLayer b1 = findById(s1.root, "b");
        assertEquals(50f, b1.y, 0.01f);
        assertSame(b0.children, b1.children, "前の兄弟が伸びて動いただけの節は子配列をそのまま共有する");
        assertNull(b0.transform);
    }

    private static SceneLayer findById(SceneLayer layer, String id) {
        if (layer.node != null && id.equals(layer.node.getId())) return layer;
        for (SceneChunk c : layer.children.chunks) {
            for (SceneLayer child : c.layers) {
                SceneLayer hit = findById(child, id);
                if (hit != null) return hit;
            }
        }
        return null;
    }
}
