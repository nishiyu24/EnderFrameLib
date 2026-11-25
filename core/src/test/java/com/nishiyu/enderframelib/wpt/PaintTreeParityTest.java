package com.nishiyu.enderframelib.wpt;

import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.box.StyleSource;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.layout.FragmentProjection;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.PaintBox;
import com.nishiyu.enderframelib.core.layout.PaintRun;
import com.nishiyu.enderframelib.core.layout.fragment.FragmentPipeline;
import com.nishiyu.enderframelib.core.layout.fragment.LayoutFragment;
import com.nishiyu.enderframelib.core.layout.fragment.LayoutSession;
import com.nishiyu.enderframelib.core.paint.tree.PaintNode;
import com.nishiyu.enderframelib.core.paint.tree.PaintTreeBuilder;
import com.nishiyu.enderframelib.core.paint.tree.PaintView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 描画用の木（M2c ②-2。{@link PaintTreeBuilder}）が、M2b の一時アダプタ（{@link FragmentProjection}。新経路の WPT 170/260 と旧経路との
 * 画素一致の基準）と同じ箱を描く入力になるか。WPT の 260 組の両側の文書で、2 つの木を同じ {@link PaintBox} として辿り、節の鍵・
 * 絶対位置・寸法・縁・行の断片・表示の種類・木の形を比べる。
 */
class PaintTreeParityTest {

    @Test
    void paintTreeMatchesTheProjectionOnEveryWptDocument() throws Exception {
        Map<String, String> pairs = WptReftestRatchetTest.manifest();
        TreeSet<String> hangs = WptReftestRatchetTest.readList(WptReftestRatchetTest.HANGS);
        TreeSet<String> docs = new TreeSet<>();
        for (var e : pairs.entrySet()) {
            if (hangs.contains(e.getKey())) continue;
            docs.add(e.getKey());
            docs.add(e.getValue());
        }
        List<String> bad = new ArrayList<>();
        for (String id : docs) {
            String diff = compare(WptReftestRatchetTest.read(id));
            if (diff != null) bad.add(id + ": " + diff);
        }
        System.out.println("PAINT-TREE parity " + (docs.size() - bad.size()) + "/" + docs.size());
        assertEquals(List.of(), bad);
    }

    /** 同梱の実文書（{@code GeometrySweepTest.REAL_DOCS} と同じ 9 本）。 */
    @Test
    void paintTreeMatchesTheProjectionOnRealDocuments() throws Exception {
        List<String> bad = new ArrayList<>();
        for (String name : List.of("showcase.html", "voidwave.html", "surface_test.html", "animation_test.html", "media_test.html",
                "hud_test.html", "item_block_test.html", "machine_ui.html", "guidebook.md")) {
            java.nio.file.Path p = java.nio.file.Path.of("src/main/resources/data/enderframelib/enderframe_ui").resolve(name);
            if (!java.nio.file.Files.exists(p)) p = java.nio.file.Path.of("src/test/resources/benchmark").resolve(name);
            String text = java.nio.file.Files.readString(p, java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n");
            String diff = compare(name.endsWith(".md") ? com.nishiyu.enderframelib.core.dom.parser.MarkdownParser.toHtml(text) : text);
            if (diff != null) bad.add(name + ": " + diff);
        }
        assertEquals(List.of(), bad);
    }

    /** 1 文書: 新経路で組み、射影と描画用の木を比べる。一致すれば null、違えば最初の違い。 */
    static String compare(String html) {
        int w = WptReftestRatchetTest.W, h = WptReftestRatchetTest.H;
        Document doc = new UiDocumentCompiler().parseDocument(html, "", w, h);
        InteractionState state = new InteractionState();
        if (doc.root instanceof com.nishiyu.enderframelib.core.dom.node.HtmlDocument hd) hd.setInteractionState(state);
        LayoutSession session = new LayoutSession(WptReftestRatchetTest.FONTS);
        FragmentPipeline pipeline = new FragmentPipeline(new StyleSource(doc.sheet, state, Map.of(), w, h), session);
        LayoutFragment frag = pipeline.frame(doc.root, null, w, h);
        LayoutBox projected = FragmentProjection.project(frag, session);
        PaintNode tree = new PaintTreeBuilder(session).build(frag, pipeline.boxRoot());
        List<String> a = new ArrayList<>(), b = new ArrayList<>();
        dump(projected, 0, a);
        dump(PaintView.root(tree), 0, b);
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            if (!a.get(i).equals(b.get(i))) return "#" + i + "\n  射影: " + a.get(i) + "\n  木:   " + b.get(i);
        }
        return a.size() == b.size() ? null : "節の数 " + a.size() + " / " + b.size();
    }

    /** {@link PaintBox} として読める欄を並べる（2 つの実装に共通の物差し）。 */
    static void dump(PaintBox b, int depth, List<String> out) {
        StringBuilder s = new StringBuilder("  ".repeat(depth));
        PaintBox o = b.originParent(), legend = b.renderedLegend();
        s.append(b.node().key()).append(b.anonymous() ? " anon" : "").append(b.isVerticalWrapper() ? " vw" : "")
                .append(" @").append(f(b.x())).append(',').append(f(b.y())).append(' ').append(f(b.w())).append('x').append(f(b.h()))
                .append(" b").append(f(b.bT())).append('/').append(f(b.bR())).append('/').append(f(b.bB())).append('/').append(f(b.bL()))
                .append(" p").append(f(b.pT())).append('/').append(f(b.pR())).append('/').append(f(b.pB())).append('/').append(f(b.pL()))
                .append(" mB").append(f(b.mB())).append(" sc").append(f(b.scrollWidth())).append('x').append(f(b.scrollHeight()))
                .append(" lh").append(f(b.lineHeightPx())).append(" t").append(b.displayTraits())
                .append(" cb").append(Arrays.toString(b.collapsedBorders()))
                .append(" o=").append(o == null ? "-" : o.node().key()).append(" legend=").append(legend == null ? "-" : legend.node().key())
                .append(" style=").append(Integer.toHexString(b.style().typed().hashCode()));
        out.add(s.toString());
        for (PaintRun r : b.runs()) {
            out.add("  ".repeat(depth + 1) + "| " + f(r.x()) + "," + f(r.y()) + " " + f(r.w()) + "x" + f(r.h()) + " b" + f(r.baseline())
                    + (r.startEdge() ? "<" : "") + (r.endEdge() ? ">" : "") + (r.text() == null ? "" : " '" + r.text() + "'"));
        }
        for (PaintBox c : b.paintChildren()) dump(c, depth + 1, out);
    }

    private static String f(float v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }
}
