package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.StyleSource;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.PaintBox;
import com.nishiyu.enderframelib.core.layout.PaintRun;
import com.nishiyu.enderframelib.core.paint.tree.PaintNode;
import com.nishiyu.enderframelib.core.paint.tree.PaintTreeBuilder;
import com.nishiyu.enderframelib.core.paint.tree.PaintView;
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
 * 描画用の木の増分（M2c ②-2。{@link PaintTreeBuilder}）: 前回の節を使い回した木が、同じ断片から作り直した木・ゼロから組み直した
 * 文書の木と同じで（描画だけの変化の最新のスタイルを含む）、変わっていない部分木は前回の節のまま（{@code ==}）であること。
 */
class PaintTreeIncrementalTest {

    @Test
    void hoverRebuildsOnlyTheSpine() {
        SpikeView v = new SpikeView(SpikeView.variedList(200), 640, 480);
        PaintTreeBuilder b = new PaintTreeBuilder(v.layout.session());
        b.build(v.frame(), v.boxRoot);
        int total = count(b.root());
        PaintNode before = b.root();
        v.state.setHoveredNode(v.byId("item-50"));
        b.build(v.frame(), v.boxRoot);
        assertNotSame(before, b.root());
        assertTrue(b.built() < 40, "作り直した節 " + b.built() + " / " + total);
        assertTrue(b.reused() > 0);
        PaintNode li = find(b.root(), v.byId("item-50"));
        assertEquals(0xFFCC0000, li.style.typed().inheritedPaint.color, "最新の木の色");
        assertSame(find(before, v.byId("item-120")), find(b.root(), v.byId("item-120")), "触れていない項目は前回の節");
        assertSameDump(v, b);
    }

    @Test
    void insertingAtTheTopReusesTheShiftedItems() {
        SpikeView v = new SpikeView(SpikeView.largeList(300), 640, 480);
        PaintTreeBuilder b = new PaintTreeBuilder(v.layout.session());
        b.build(v.frame(), v.boxRoot);
        int total = count(b.root());
        PaintNode item200 = find(b.root(), v.byId("item-200"));
        HtmlElement e = new HtmlElement("li");
        e.updateTextContent("new first row");
        v.byId("list").insertBefore(e, v.byId("item-1"));
        b.build(v.frame(), v.boxRoot);
        assertSame(item200, find(b.root(), v.byId("item-200")), "下へずれただけの項目は前回の節（位置は親が持つ）");
        assertTrue(b.built() < total / 10, "作り直した節 " + b.built() + " / " + total);
        assertSameDump(v, b);
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
            PaintTreeBuilder b = new PaintTreeBuilder(v.layout.session());
            b.build(v.frame(), v.boxRoot);
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
                b.build(v.frame(), v.boxRoot);
                try {
                    assertSameDump(v, b);
                } catch (AssertionError err) {
                    throw new AssertionError(kind + " step " + step + " (" + op + " on " + target.tag + ")\n" + err.getMessage(), err);
                }
            }
        }
    }

    /** 新しい描画の記憶（M2c ②-3）: 前回の結果を使った表示リスト = 新しく描いた表示リスト。描画だけ・寸法・属性だけの変化を混ぜる。 */
    @Test
    void painterMemoMatchesAFreshPaint() {
        for (String kind : new String[]{"varied", "flex", "text"}) {
            String html = switch (kind) {
                case "varied" -> SpikeView.variedList(80).replace("</style>", " .sc{height:60px;overflow:auto}</style>");
                case "text" -> SpikeView.inlineText(14).replace("</style>", " .pb::before{content:'[' attr(id) ']';color:#900}</style>");
                default -> SpikeView.nestedFlex(4, 3, true);
            };
            SpikeView v = new SpikeView(html, 640, 480);
            PaintTreeBuilder b = new PaintTreeBuilder(v.layout.session());
            com.nishiyu.enderframelib.core.paint.TreePainter painter = new com.nishiyu.enderframelib.core.paint.TreePainter(v.vw, v.vh,
                    v.doc.sheet.keyframes, null);
            painter.paint(b.build(v.frame(), v.boxRoot), v.ledger());
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
                PaintNode tree = b.build(v.frame(), v.boxRoot);
                List<com.nishiyu.enderframelib.core.paint.model.DisplayItem> inc = painter.paint(tree, v.ledger());
                List<com.nishiyu.enderframelib.core.paint.model.DisplayItem> fresh = new com.nishiyu.enderframelib.core.paint.TreePainter(v.vw,
                        v.vh, v.doc.sheet.keyframes, null).paint(tree);
                for (int i = 0; i < Math.min(inc.size(), fresh.size()); i++) {
                    if (!inc.get(i).equals(fresh.get(i))) {
                        throw new AssertionError(kind + " step " + step + " (" + op + " on " + target.tag + ") #" + i
                                + "\n  新しく: " + fresh.get(i) + "\n  記憶:   " + inc.get(i));
                    }
                }
                assertEquals(fresh.size(), inc.size(), kind + " step " + step + " (" + op + ") 項目の数");
            }
        }
    }

    @Test
    void hoverRepaintsOnlyTheHoveredItem() {
        SpikeView v = new SpikeView(SpikeView.variedList(200), 640, 480);
        PaintTreeBuilder b = new PaintTreeBuilder(v.layout.session());
        com.nishiyu.enderframelib.core.paint.TreePainter painter = new com.nishiyu.enderframelib.core.paint.TreePainter(v.vw, v.vh,
                v.doc.sheet.keyframes, null);
        painter.paint(b.build(v.frame(), v.boxRoot), v.ledger());
        int all = painter.built();
        v.state.setHoveredNode(v.byId("item-50"));
        painter.paint(b.build(v.frame(), v.boxRoot), v.ledger());
        assertTrue(painter.built() < 30, "作り直した箱 " + painter.built() + " / " + all);
        assertTrue(painter.reused() > 100, "使い回した部分木 " + painter.reused());
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

    /**
     * 表・フォームの部品・スクロールコンテナ（スクロールバーと中身の節）も記憶する（記憶してよい箱をタグ名で決める一覧は無い。描画が DOM を
     * 読む所は台帳で、スクロールの位置は文脈で照合する）。hover で作り直すのは触れた箱と祖先だけで、使い回したスクロールコンテナの中身の節の
     * 記録も残る。
     */
    @Test
    void controlsTablesAndScrollContainersAreReused() {
        SpikeView v = new SpikeView(CONTROLS_DOC, 640, 480);
        PaintTreeBuilder b = new PaintTreeBuilder(v.layout.session());
        com.nishiyu.enderframelib.core.layout.model.ScrollState scroll = new com.nishiyu.enderframelib.core.layout.model.ScrollState();
        com.nishiyu.enderframelib.core.paint.TreePainter painter = new com.nishiyu.enderframelib.core.paint.TreePainter(v.vw, v.vh,
                v.doc.sheet.keyframes, scroll);
        painter.paint(b.build(v.frame(), v.boxRoot), v.ledger());
        int all = painter.built();
        assertEquals(1, painter.scrollContents().size(), "中身を 1 つの節にまとめたスクロールコンテナ");
        v.state.setHoveredNode(v.byId("li20"));
        PaintNode tree = b.build(v.frame(), v.boxRoot);
        List<com.nishiyu.enderframelib.core.paint.model.DisplayItem> inc = painter.paint(tree, v.ledger());
        assertTrue(painter.built() < 10, "作り直した箱 " + painter.built() + " / " + all);
        assertEquals(new com.nishiyu.enderframelib.core.paint.TreePainter(v.vw, v.vh, v.doc.sheet.keyframes, scroll).paint(tree), inc);
        assertEquals(1, painter.scrollContents().size(), "使い回したスクロールコンテナの中身の節の記録");
    }

    /** スクロールの位置は台帳を通らずに変わることがあるので、スクロールコンテナの記憶は自分のスクロールの位置を文脈として照合する。 */
    @Test
    void scrollContainerMemoFollowsTheScrollPosition() {
        SpikeView v = new SpikeView(CONTROLS_DOC, 640, 480);
        PaintTreeBuilder b = new PaintTreeBuilder(v.layout.session());
        com.nishiyu.enderframelib.core.layout.model.ScrollState scroll = new com.nishiyu.enderframelib.core.layout.model.ScrollState();
        com.nishiyu.enderframelib.core.paint.TreePainter painter = new com.nishiyu.enderframelib.core.paint.TreePainter(v.vw, v.vh,
                v.doc.sheet.keyframes, scroll);
        painter.paint(b.build(v.frame(), v.boxRoot), v.ledger());
        int step = 0;
        for (float y : new float[]{20f, 45f, 45f, 0f}) {
            scroll.setScroll(v.byId("sc"), 0f, y);
            v.state.setHoveredNode(v.byId("li" + (1 + step++ % 40))); // 台帳にはリストの hover だけが載る
            PaintNode tree = b.build(v.frame(), v.boxRoot);
            List<com.nishiyu.enderframelib.core.paint.model.DisplayItem> inc = painter.paint(tree, v.ledger());
            com.nishiyu.enderframelib.core.paint.TreePainter fresh = new com.nishiyu.enderframelib.core.paint.TreePainter(v.vw, v.vh,
                    v.doc.sheet.keyframes, scroll);
            assertEquals(fresh.paint(tree), inc, "scrollY=" + y);
            assertEquals(fresh.scrollContents(), painter.scrollContents(), "scrollY=" + y + " の中身の節の記録");
        }
    }

    private static final String[] PAINT = {"color:#a00", "background:#0a0", "opacity:.4", "outline:2px solid red", "border-color:blue",
            "text-decoration:underline", "z-index:2", "box-shadow:1px 1px 2px #000", ""};

    /** 増分の木 = 同じ断片から作り直した木 = ゼロから組み直した文書の木。 */
    private static void assertSameDump(SpikeView v, PaintTreeBuilder inc) {
        List<String> a = dump(inc.root());
        List<String> same = dump(new PaintTreeBuilder(v.layout.session()).build(v.layout.root(), v.boxRoot));
        FragmentPipeline fresh = new FragmentPipeline(new StyleSource(v.doc.sheet, v.state, null, v.vw, v.vh),
                new LayoutSession(FontResolver.FALLBACK));
        LayoutFragment f = fresh.frame(v.doc.root, null, v.vw, v.vh);
        List<String> scratch = dump(new PaintTreeBuilder(fresh.layout().session()).build(f, fresh.boxRoot()));
        firstDiff("同じ断片から作り直した木", same, a);
        firstDiff("ゼロから組み直した文書の木", scratch, a);
    }

    private static void firstDiff(String what, List<String> want, List<String> got) {
        for (int i = 0; i < Math.min(want.size(), got.size()); i++) {
            if (!want.get(i).equals(got.get(i))) {
                throw new AssertionError(what + " と違う #" + i + "\n  期待: " + want.get(i) + "\n  増分: " + got.get(i));
            }
        }
        assertEquals(want.size(), got.size(), what + " と節の数が違う");
    }

    private static List<String> dump(PaintNode root) {
        List<String> out = new ArrayList<>();
        dump(PaintView.root(root), 0, out);
        return out;
    }

    private static void dump(PaintBox b, int depth, List<String> out) {
        out.add("  ".repeat(depth) + b.node().key() + " @" + f(b.x()) + "," + f(b.y()) + " " + f(b.w()) + "x" + f(b.h())
                + " b" + f(b.bT()) + "/" + f(b.bL()) + " p" + f(b.pT()) + "/" + f(b.pL()) + " sc" + f(b.scrollWidth()) + "x" + f(b.scrollHeight())
                + " t" + b.displayTraits() + " style=" + b.style().typed().hashCode() + " eq=" + b.style().typed().toCssMap());
        for (PaintRun r : b.runs()) {
            out.add("  ".repeat(depth + 1) + "| " + f(r.x()) + "," + f(r.y()) + " " + f(r.w()) + "x" + f(r.h()) + (r.text() == null ? "" : " " + r.text()));
        }
        for (PaintBox c : b.paintChildren()) dump(c, depth + 1, out);
    }

    private static String f(float v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    private static int count(PaintNode n) {
        int c = 1;
        for (int i = 0; i < n.childCount(); i++) c += count(n.child(i));
        return c;
    }

    private static PaintNode find(PaintNode n, HtmlNode node) {
        if (n.node == node) return n;
        for (int i = 0; i < n.childCount(); i++) {
            PaintNode r = find(n.child(i), node);
            if (r != null) return r;
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
