package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.common.TestBoxes;
import com.nishiyu.enderframelib.core.box.StyleSource;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.SceneDisplayListAdapter;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.scene.DisplayScene;
import com.nishiyu.enderframelib.core.paint.scene.DynamicSceneState;
import com.nishiyu.enderframelib.core.paint.scene.SceneChunk;
import com.nishiyu.enderframelib.core.paint.scene.SceneEmitter;
import com.nishiyu.enderframelib.core.paint.scene.SceneLayer;
import com.nishiyu.enderframelib.core.paint.scene.SceneRun;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 描画シーンの増分構築（M3b-2。{@link SceneEmitter}）: 前回の節とチャンクを使い回したシーンが、同じ断片から作り直したシーン・
 * ゼロから組み直した文書のシーンと同じで（描画だけの変化の最新のスタイルを含む）、変わっていない部分木は {@code (LayoutFragment ==,
 * BoxNode ==)}、チャンクは {@code FlowChunk ==} で前回のまま使い回されること。
 */
class SceneEmitterIncrementalTest {

    @Test
    void hoverRebuildsOnlyTheSpine() {
        SpikeView v = new SpikeView(SpikeView.variedList(200), 640, 480);
        SceneEmitter emitter = new SceneEmitter(v.layout.session(), v.doc.sheet.keyframes);
        DisplayScene s0 = emitter.emit(v.frame(), v.boxRoot, v.vw, v.vh);
        int total = count(s0.root);
        v.state.setHoveredNode(v.byId("item-50"));
        DisplayScene s1 = emitter.emit(v.frame(), v.boxRoot, v.vw, v.vh);
        assertNotSame(s0.root, s1.root);
        assertTrue(emitter.builtLayers() < 40, "作り直した節 " + emitter.builtLayers() + " / " + total);
        assertTrue(emitter.reusedLayers() > 0 || emitter.reusedChunks() > 0);
        SceneLayer li = find(s1.root, v.byId("item-50"));
        assertEquals(0xFFCC0000, li.style.typed().inheritedPaint.color, "最新の木の色");
        assertSame(find(s0.root, v.byId("item-120")), find(s1.root, v.byId("item-120")), "触れていない項目は前回の節");
        assertSameDump(v, s1);
    }

    @Test
    void hoverKeepsTheOpsOfUntouchedLayers() {
        SpikeView v = new SpikeView(SpikeView.variedList(60), 640, 480);
        SceneEmitter emitter = new SceneEmitter(v.layout.session(), v.doc.sheet.keyframes);
        emitter.opsSource(new com.nishiyu.enderframelib.core.paint.LayerOps(v.vw, v.vh, v.doc.sheet.keyframes));
        DisplayScene s0 = emitter.emit(v.frame(), v.boxRoot, v.vw, v.vh);
        SceneLayer untouched = find(s0.root, v.byId("item-40"));
        SceneLayer hovered = find(s0.root, v.byId("item-20"));
        assertTrue(untouched.ops().length > 0 && hovered.ops().length > 0, "層は自分の描画命令を持つ（M3b-4a）");
        v.state.setHoveredNode(v.byId("item-20"));
        DisplayScene s1 = emitter.emit(v.frame(), v.boxRoot, v.vw, v.vh);
        assertSame(untouched.ops(), find(s1.root, v.byId("item-40")).ops(), "作り直さなかった層は命令をそのまま持つ（記憶は層の鍵だけ）");
        assertNotSame(hovered.ops(), find(s1.root, v.byId("item-20")).ops(), "作り直した層は命令も作り直す");
    }

    @Test
    void insertingAtTheTopReusesTheShiftedItemsAndChunks() {
        SpikeView v = new SpikeView(SpikeView.largeList(300), 640, 480);
        SceneEmitter emitter = new SceneEmitter(v.layout.session(), v.doc.sheet.keyframes);
        DisplayScene s0 = emitter.emit(v.frame(), v.boxRoot, v.vw, v.vh);
        int total = count(s0.root);
        SceneLayer item200 = find(s0.root, v.byId("item-200"));
        HtmlElement e = new HtmlElement("li");
        e.updateTextContent("new first row");
        v.byId("list").insertBefore(e, v.byId("item-1"));
        DisplayScene s1 = emitter.emit(v.frame(), v.boxRoot, v.vw, v.vh);
        assertSame(item200, find(s1.root, v.byId("item-200")), "下へずれただけの項目は前回の節（位置はチャンク・親が持つ）");
        assertTrue(emitter.reusedChunks() >= 8, "FlowChunk == で使い回したチャンク数 " + emitter.reusedChunks());
        assertTrue(emitter.builtLayers() < total / 10, "作り直した節 " + emitter.builtLayers() + " / " + total);
        assertSameDump(v, s1);
    }

    @Test
    void randomPaintAndLayoutMutationsMatchARebuild() {
        for (String kind : new String[]{"flex", "text", "tables", "writing"}) {
            String html = switch (kind) {
                case "text" -> SpikeView.inlineText(14).replace("</style>", " .pb::before{content:'[' attr(id) ']';color:#900}</style>");
                case "tables" -> "<html><head><style>td{border:1px solid #888;padding:2px} table{border-collapse:collapse}</style></head><body>"
                        + "<table id=t><tr id=r1><td id=c1>a</td><td id=c2>bb</td></tr><tr id=r2><td id=c3>ccc</td><td id=c4>d</td></tr></table>"
                        + "<div id=d1 style='display:table'><span id=s1 style='display:table-cell'>x</span><span id=s2>y</span></div>"
                        + "<p id=p1>tail <b id=b1>bold</b> text</p></body></html>";
                case "writing" -> SpikeView.nestedFlex(3, 3, true).replace("</style>", " .v{writing-mode:vertical-rl}</style>");
                default -> SpikeView.nestedFlex(4, 3, true);
            };
            SpikeView v = new SpikeView(html, 640, 480);
            SceneEmitter emitter = new SceneEmitter(v.layout.session(), v.doc.sheet.keyframes);
            emitter.emit(v.frame(), v.boxRoot, v.vw, v.vh);
            Random rnd = new Random(kind.hashCode() * 31L + 7);
            for (int step = 0; step < 60; step++) {
                List<HtmlNode> elems = new ArrayList<>();
                collect(v.doc.root, elems);
                HtmlElement target = (HtmlElement) elems.get(rnd.nextInt(elems.size()));
                String op;
                if (rnd.nextInt(3) > 0) {
                    op = PAINT[rnd.nextInt(PAINT.length)];
                    String old = target.getAttribute("style");
                    target.setAttribute("style", (old == null || old.isEmpty() ? "" : old + ";") + op);
                } else if (kind.equals("writing") && rnd.nextBoolean()) {
                    op = "class v";
                    target.setAttribute("class", rnd.nextBoolean() ? "v" : "");
                } else {
                    op = IncrementalOracleTest.mutate(v, rnd, step);
                }
                DisplayScene scene = emitter.emit(v.frame(), v.boxRoot, v.vw, v.vh);
                try {
                    assertSameDump(v, scene);
                } catch (AssertionError err) {
                    throw new AssertionError(kind + " step " + step + " (" + op + " on " + target.tag + ")\n" + err.getMessage(), err);
                }
            }
        }
    }

    /** 増分シーンから出した表示リスト = ゼロから組んだシーンの表示リスト。描画だけ・寸法・属性だけの変化を混ぜる。 */
    @Test
    void adapterOutputMatchesAFreshScene() {
        for (String kind : new String[]{"varied", "flex", "text"}) {
            String html = switch (kind) {
                case "varied" -> SpikeView.variedList(80).replace("</style>", " .sc{height:60px;overflow:auto}</style>");
                case "text" -> SpikeView.inlineText(14).replace("</style>", " .pb::before{content:'[' attr(id) ']';color:#900}</style>");
                default -> SpikeView.nestedFlex(4, 3, true);
            };
            SpikeView v = new SpikeView(html, 640, 480);
            SceneEmitter emitter = new SceneEmitter(v.layout.session(), v.doc.sheet.keyframes);
            SceneDisplayListAdapter adapter = new SceneDisplayListAdapter(v.vw, v.vh, v.doc.sheet.keyframes);
            DynamicSceneState dyn = DynamicSceneState.of(null, v.vw, v.vh);
            adapter.emit(emitter.emit(v.frame(), v.boxRoot, v.vw, v.vh), dyn);
            Random rnd = new Random(kind.hashCode() * 17L + 3);
            for (int step = 0; step < 60; step++) {
                List<HtmlNode> elems = new ArrayList<>();
                collect(v.doc.root, elems);
                HtmlElement target = (HtmlElement) elems.get(rnd.nextInt(elems.size()));
                String op;
                int r = rnd.nextInt(5);
                if (r <= 1) {
                    op = PAINT[rnd.nextInt(PAINT.length)];
                    String old = target.getAttribute("style");
                    target.setAttribute("style", (old == null || old.isEmpty() ? "" : old + ";") + op);
                } else if (r == 2) {
                    op = "hover";
                    v.state.setHoveredNode(target);
                } else if (r == 3) {
                    op = "attr";
                    target.setAttribute(rnd.nextBoolean() ? "title" : "data-pan-x", Integer.toString(rnd.nextInt(30)));
                } else {
                    op = IncrementalOracleTest.mutate(v, rnd, step);
                }
                DisplayScene scene = emitter.emit(v.frame(), v.boxRoot, v.vw, v.vh);
                List<DisplayItem> inc = adapter.emit(scene, dyn);
                DisplayScene freshScene = new SceneEmitter(v.layout.session(), v.doc.sheet.keyframes)
                        .emit(v.layout.root(), v.boxRoot, v.vw, v.vh);
                List<DisplayItem> fresh = new SceneDisplayListAdapter(v.vw, v.vh, v.doc.sheet.keyframes).emit(freshScene, dyn);
                for (int i = 0; i < Math.min(inc.size(), fresh.size()); i++) {
                    if (!inc.get(i).equals(fresh.get(i))) {
                        throw new AssertionError(kind + " step " + step + " (" + op + " on " + target.tag + ") #" + i
                                + "\n  新しく: " + fresh.get(i) + "\n  増分:   " + inc.get(i));
                    }
                }
                assertEquals(fresh.size(), inc.size(), kind + " step " + step + " (" + op + ") 項目の数");
            }
        }
    }

    @Test
    void hoverRebuildsOnlyTheHoveredBranch() {
        SpikeView v = new SpikeView(SpikeView.variedList(200), 640, 480);
        SceneEmitter emitter = new SceneEmitter(v.layout.session(), v.doc.sheet.keyframes);
        emitter.emit(v.frame(), v.boxRoot, v.vw, v.vh);
        int all = emitter.builtLayers();
        v.state.setHoveredNode(v.byId("item-50"));
        emitter.emit(v.frame(), v.boxRoot, v.vw, v.vh);
        assertTrue(emitter.builtLayers() < 15, "作り直した節 " + emitter.builtLayers() + " / " + all);
        assertTrue(emitter.reusedLayers() + emitter.reusedChunks() > 5,
                "使い回した節/チャンク " + emitter.reusedLayers() + " / " + emitter.reusedChunks());
    }

    private static final String CONTROLS_DOC = "<html><head><style>li:hover{color:#c00} .sc{height:60px;overflow:auto;border:1px solid #444}"
            + " td{border:1px solid #888}</style></head><body><table id=t><tr><td>a</td><td>b</td></tr><tr><td>c</td><td>d</td></tr></table>"
            + "<form id=f><input id=in value=abc><select id=sel><option>x</option><option>y</option></select><textarea id=ta>t</textarea>"
            + "<button id=btn>b</button></form><div class=sc id=sc>" + rows("div", "r", 30) + "</div><ul id=list>" + rows("li", "li", 40) + "</ul></body></html>";

    private static String rows(String tag, String id, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= n; i++) sb.append('<').append(tag).append(" id=").append(id).append(i).append(">row ").append(i).append("</").append(tag).append('>');
        return sb.toString();
    }

    /** 表・フォームの部品・スクロールコンテナもそのまま再利用され、表示リストがゼロからの組み立てと一致する。 */
    @Test
    void controlsTablesAndScrollContainersAreReused() {
        SpikeView v = new SpikeView(CONTROLS_DOC, 640, 480);
        SceneEmitter emitter = new SceneEmitter(v.layout.session(), v.doc.sheet.keyframes);
        ScrollState scroll = new ScrollState();
        SceneDisplayListAdapter adapter = new SceneDisplayListAdapter(v.vw, v.vh, v.doc.sheet.keyframes);
        DynamicSceneState dyn = DynamicSceneState.of(scroll, v.vw, v.vh);
        adapter.emit(emitter.emit(v.frame(), v.boxRoot, v.vw, v.vh), dyn);
        int all = emitter.builtLayers();
        assertEquals(1, adapter.scrollContents().size(), "中身を 1 つの節にまとめたスクロールコンテナ");
        v.state.setHoveredNode(v.byId("li20"));
        DisplayScene scene = emitter.emit(v.frame(), v.boxRoot, v.vw, v.vh);
        List<DisplayItem> inc = adapter.emit(scene, dyn);
        assertTrue(emitter.builtLayers() < 10, "作り直した節 " + emitter.builtLayers() + " / " + all);
        assertEquals(new SceneDisplayListAdapter(v.vw, v.vh, v.doc.sheet.keyframes).emit(scene, dyn), inc);
        assertEquals(1, adapter.scrollContents().size(), "使い回したスクロールコンテナの中身の節の記録");
    }

    /** スクロールの位置は {@link DynamicSceneState} で渡すので、{@link DisplayScene} 自体は一切作り直さない。 */
    @Test
    void scrollReusesEntireDisplaySceneWithoutRebuilding() {
        SpikeView v = new SpikeView(CONTROLS_DOC, 640, 480);
        SceneEmitter emitter = new SceneEmitter(v.layout.session(), v.doc.sheet.keyframes);
        ScrollState scroll = new ScrollState();
        SceneDisplayListAdapter adapter = new SceneDisplayListAdapter(v.vw, v.vh, v.doc.sheet.keyframes);
        DisplayScene initial = emitter.emit(v.frame(), v.boxRoot, v.vw, v.vh);
        for (float y : new float[]{20f, 45f, 45f, 0f}) {
            scroll.setScroll(v.byId("sc"), 0f, y);
            DynamicSceneState dyn = DynamicSceneState.of(scroll, v.vw, v.vh);
            List<DisplayItem> inc = adapter.emit(initial, dyn);
            DisplayScene freshScene = new SceneEmitter(v.layout.session(), v.doc.sheet.keyframes)
                    .emit(v.frame(), v.boxRoot, v.vw, v.vh);
            SceneDisplayListAdapter fresh = new SceneDisplayListAdapter(v.vw, v.vh, v.doc.sheet.keyframes);
            assertEquals(fresh.emit(freshScene, dyn), inc, "scrollY=" + y);
            assertEquals(fresh.scrollContents(), adapter.scrollContents(), "scrollY=" + y + " の中身の節の記録");
        }
    }

    private static final String[] PAINT = {"color:#a00", "background:#0a0", "opacity:.4", "outline:2px solid red", "border-color:blue",
            "text-decoration:underline", "z-index:2", "box-shadow:1px 1px 2px #000", ""};

    private static void assertSameDump(SpikeView v, DisplayScene inc) {
        List<String> a = dump(inc.root, v.vw, v.vh);
        DisplayScene sameScene = new SceneEmitter(v.layout.session(), v.doc.sheet.keyframes).emit(v.layout.root(), v.boxRoot, v.vw, v.vh);
        List<String> same = dump(sameScene.root, v.vw, v.vh);
        FragmentPipeline fresh = new FragmentPipeline(new StyleSource(v.doc.sheet, v.state, null, v.vw, v.vh),
                new LayoutSession(FontResolver.FALLBACK));
        LayoutFragment f = fresh.frame(v.doc.root, null, v.vw, v.vh);
        DisplayScene scratchScene = new SceneEmitter(fresh.layout().session(), v.doc.sheet.keyframes)
                .emit(f, fresh.boxRoot(), v.vw, v.vh);
        List<String> scratch = dump(scratchScene.root, v.vw, v.vh);
        firstDiff("同じ断片から作り直したシーン", same, a);
        firstDiff("ゼロから組み直した文書のシーン", scratch, a);
    }

    private static void firstDiff(String what, List<String> want, List<String> got) {
        for (int i = 0; i < Math.min(want.size(), got.size()); i++) {
            if (!want.get(i).equals(got.get(i))) {
                throw new AssertionError(what + " と違う #" + i + "\n  期待: " + want.get(i) + "\n  増分: " + got.get(i));
            }
        }
        assertEquals(want.size(), got.size(), what + " と節の数が違う");
    }

    private static List<String> dump(SceneLayer root, int vw, int vh) {
        List<String> out = new ArrayList<>();
        dump(TestBoxes.root(root, DynamicSceneState.of(null, vw, vh)), 0, out);
        return out;
    }

    private static void dump(TestBoxes.Box b, int depth, List<String> out) {
        out.add("  ".repeat(depth) + b.node().key() + " @" + f(b.x()) + "," + f(b.y()) + " " + f(b.w()) + "x" + f(b.h())
                + " b" + f(b.bT()) + "/" + f(b.bL()) + " p" + f(b.pT()) + "/" + f(b.pL()) + " sc" + f(b.scrollWidth()) + "x" + f(b.scrollHeight())
                + " t" + b.displayTraits() + " style=" + b.style().typed().hashCode() + " eq=" + b.style().typed().toCssMap());
        for (SceneRun r : b.runs()) {
            out.add("  ".repeat(depth + 1) + "| " + f(r.x()) + "," + f(r.y()) + " " + f(r.w()) + "x" + f(r.h()) + (r.text() == null ? "" : " " + r.text()));
        }
        for (TestBoxes.Box c : b.paintChildren()) dump(c, depth + 1, out);
    }

    private static String f(float v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    private static int count(SceneLayer n) {
        int c = 1;
        for (SceneChunk ch : n.children.chunks) {
            for (SceneLayer l : ch.layers) c += count(l);
        }
        return c;
    }

    private static SceneLayer find(SceneLayer n, HtmlNode node) {
        if (n.node == node) return n;
        for (SceneChunk ch : n.children.chunks) {
            for (SceneLayer l : ch.layers) {
                SceneLayer r = find(l, node);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static void collect(HtmlNode n, List<HtmlNode> out) {
        if (n instanceof HtmlElement && n.parent != null && !"html".equals(n.tag) && !"head".equals(n.tag)) out.add(n);
        for (HtmlNode c : n.children) {
            if (!"head".equals(c.tag) && !"style".equals(c.tag)) collect(c, out);
        }
    }
}
