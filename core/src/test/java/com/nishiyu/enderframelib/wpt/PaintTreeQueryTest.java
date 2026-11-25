package com.nishiyu.enderframelib.wpt;

import com.nishiyu.enderframelib.common.BoxLayoutQuery;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.common.UiFocus;
import com.nishiyu.enderframelib.core.box.StyleSource;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.api.dom.DOMRect;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.LayoutQuery;
import com.nishiyu.enderframelib.core.layout.FragmentProjection;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.PaintBox;
import com.nishiyu.enderframelib.core.layout.fragment.FragmentPipeline;
import com.nishiyu.enderframelib.core.layout.fragment.LayoutFragment;
import com.nishiyu.enderframelib.core.layout.fragment.LayoutSession;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.tree.PaintNode;
import com.nishiyu.enderframelib.core.paint.tree.PaintTreeBuilder;
import com.nishiyu.enderframelib.core.paint.tree.PaintView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 問い合わせ（M2c ③。計画書 §3.4.1 M2c）: CSSOM の寸法（{@link BoxLayoutQuery}）・フォーカスの箱の検索（{@link UiFocus#findBox}）を、
 * 新経路の描画用の木（{@link PaintView}）から答える。射影の箱（M2b の基準）から答えた値と全ての節で一致し、スクロールした中身の位置は
 * 旧経路（{@link StandaloneView}。レイアウトがスクロール量を含む）と一致する。
 */
class PaintTreeQueryTest {

    @Test
    void metricsMatchTheProjectionOnEveryWptDocument() throws Exception {
        Map<String, String> pairs = WptReftestRatchetTest.manifest();
        TreeSet<String> hangs = WptReftestRatchetTest.readList(WptReftestRatchetTest.HANGS);
        TreeSet<String> docs = new TreeSet<>();
        for (var e : pairs.entrySet()) {
            if (hangs.contains(e.getKey())) continue;
            docs.add(e.getKey());
            docs.add(e.getValue());
        }
        List<String> bad = new ArrayList<>();
        int nodes = 0;
        for (String id : docs) {
            int[] count = {0};
            String diff = compare(WptReftestRatchetTest.read(id), count);
            nodes += count[0];
            if (diff != null) bad.add(id + ": " + diff);
        }
        System.out.println("QUERY parity " + (docs.size() - bad.size()) + "/" + docs.size() + " (節 " + nodes + ")");
        for (String b : bad.subList(0, Math.min(8, bad.size()))) System.out.println("QUERY " + b);
        assertEquals(List.of(), bad);
    }

    /** 1 文書の全ての節の寸法・見えている矩形・箱の検索を比べる。一致すれば null。 */
    private static String compare(String html, int[] count) {
        int w = WptReftestRatchetTest.W, h = WptReftestRatchetTest.H;
        Document doc = new UiDocumentCompiler().parseDocument(html, "", w, h);
        InteractionState state = new InteractionState();
        if (doc.root instanceof com.nishiyu.enderframelib.core.dom.node.HtmlDocument hd) hd.setInteractionState(state);
        LayoutSession session = new LayoutSession(WptReftestRatchetTest.FONTS);
        FragmentPipeline pipeline = new FragmentPipeline(new StyleSource(doc.sheet, state, Map.of(), w, h), session);
        LayoutFragment frag = pipeline.frame(doc.root, null, w, h);
        LayoutBox projected = FragmentProjection.project(frag, session);
        PaintNode tree = new PaintTreeBuilder(session).build(frag, pipeline.boxRoot());
        BoxLayoutQuery a = new BoxLayoutQuery(new ScrollState(), () -> { }), b = new BoxLayoutQuery(new ScrollState(), () -> { });
        a.update(projected, doc.sheet, w, h);
        PaintView view = PaintView.root(tree, new ScrollState());
        b.update(view, doc.sheet, w, h);
        List<HtmlNode> all = new ArrayList<>();
        collect(doc.root, all);
        for (HtmlNode n : all) {
            count[0]++;
            LayoutQuery.BoxMetrics ma = a.metrics(n), mb = b.metrics(n);
            if (!java.util.Objects.equals(ma, mb)) return n.key() + " 寸法\n  射影: " + ma + "\n  木:   " + mb;
            DOMRect ra = a.visibleRect(n, null), rb = b.visibleRect(n, null);
            if (!java.util.Objects.equals(ra, rb)) return n.key() + " 見えている矩形 " + ra + " / " + rb;
            PaintBox fa = UiFocus.findBox(projected, n), fb = UiFocus.findBox(view, n);
            if ((fa == null) != (fb == null) || (fa != null && (fa.x() != fb.x() || fa.y() != fb.y() || fa.w() != fb.w()))) {
                return n.key() + " 箱の検索 " + fa + " / " + fb;
            }
        }
        return null;
    }

    /** スクロールした中身（流れの子・包含ブロックがスクロールコンテナの絶対配置）は動き、固定配置は動かない。旧経路と同じ位置。 */
    @Test
    void scrolledContentMatchesTheLegacyPath() {
        String html = "<html><body style='margin:0'><div id=sc style='position:relative;overflow:auto;width:200px;height:100px;"
                + "border:3px solid #888;padding:2px'><div id=in style='height:300px;width:400px;background:#cde'>"
                + "<span id=sp>text</span></div><div id=ab style='position:absolute;left:5px;top:50px;width:20px;height:10px'></div>"
                + "<div id=fx style='position:fixed;left:7px;top:8px;width:20px;height:10px'></div></div>"
                + "<p id=after>after</p></body></html>";
        int w = WptReftestRatchetTest.W, h = WptReftestRatchetTest.H;
        Document doc = new UiDocumentCompiler().parseDocument(html, "", w, h);
        InteractionState state = new InteractionState();
        if (doc.root instanceof com.nishiyu.enderframelib.core.dom.node.HtmlDocument hd) hd.setInteractionState(state);
        LayoutSession session = new LayoutSession(WptReftestRatchetTest.FONTS);
        FragmentPipeline pipeline = new FragmentPipeline(new StyleSource(doc.sheet, state, Map.of(), w, h), session);
        PaintNode tree = new PaintTreeBuilder(session).build(pipeline.frame(doc.root, null, w, h), pipeline.boxRoot());
        ScrollState scroll = new ScrollState();
        HtmlNode sc = doc.root.getNodeById("sc");
        scroll.setScroll(sc, 30, 40);
        BoxLayoutQuery mine = new BoxLayoutQuery(scroll, () -> { });
        mine.update(PaintView.root(tree, scroll), doc.sheet, w, h);

        try (StandaloneView sv = new StandaloneView(html, w, h, WptReftestRatchetTest.FONTS)) {
            sv.frame(1000, NULL_BACKEND, true);
            // 旧経路のビューが文書に差し込んだ問い合わせ（CSSOM の scrollTo と同じ口でスクロールし、読む前に位置を決め直す）
            LayoutQuery legacy = ((com.nishiyu.enderframelib.core.dom.node.HtmlDocument) sv.ui().document().root).layoutQuery();
            legacy.scrollTo(sv.ui().findNode("sc"), 30, 40);
            sv.frame(2000, NULL_BACKEND, true);
            for (String id : new String[]{"sc", "in", "sp", "ab", "fx", "after"}) {
                LayoutQuery.BoxMetrics a = legacy.metrics(sv.ui().findNode(id)), b = mine.metrics(doc.root.getNodeById(id));
                assertNotNull(a, id);
                assertNotNull(b, id);
                assertEquals(a.x(), b.x(), 0.01f, id + " x");
                assertEquals(a.y(), b.y(), 0.01f, id + " y");
                assertEquals(a.w(), b.w(), 0.01f, id + " w");
                assertEquals(a.h(), b.h(), 0.01f, id + " h");
            }
        }
    }

    private static final com.nishiyu.enderframelib.core.paint.RenderBackend NULL_BACKEND = new com.nishiyu.enderframelib.core.paint.RenderBackend() {
        @Override public void render(List<com.nishiyu.enderframelib.core.paint.model.DisplayItem> items, long nowMs) { }
        @Override public com.nishiyu.enderframelib.core.raster.Pixmap pixmap() { throw new UnsupportedOperationException(); }
        @Override public float devicePixelRatio() { return 1f; }
    };

    private static void collect(HtmlNode n, List<HtmlNode> out) {
        out.add(n);
        for (HtmlNode c : n.children) collect(c, out);
    }
}
