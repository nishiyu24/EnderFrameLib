package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.box.StyleSource;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.fragment.FragmentPipeline;
import com.nishiyu.enderframelib.core.layout.fragment.LayoutFragment;
import com.nishiyu.enderframelib.core.layout.fragment.LayoutSession;
import com.nishiyu.enderframelib.core.paint.TreePainter;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.paint.tree.PaintNode;
import com.nishiyu.enderframelib.core.paint.tree.PaintTreeBuilder;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 新経路のビュー（M2c ④。{@link EnginePath#V3}）: 対話（hover・クリック・ホイール・リサイズ・DOM の変更・文字の入力）のあと、ビューが描いた面が、
 * 同じ文書・同じ対話の状態・同じスクロールの状態からゼロから新経路で描いた面と画素で一致すること（スクロールだけの更新は中身の節を
 * 動かすだけなので、表示項目でなく画素で比べる）。
 */
class V3ViewTest {
    private static final int W = 480, H = 360;

    private static final String DOC = "<html><head><style>body{margin:8px;font:14px sans-serif} .card{padding:6px;margin:4px;border:1px solid #888;background:#eef}"
            + " .card:hover{background:#fcc;border-color:#c00} .sc{height:120px;overflow:auto;border:2px solid #444} li{padding:3px}"
            + " li:hover{color:#c00} button:hover{background:#cfc} .abs{position:absolute;left:300px;top:20px;width:80px;height:40px;background:#ccf}</style></head>"
            + "<body><div class=card id=c1>first card with some text</div><div class=card id=c2>second <b>bold</b> card</div>"
            + "<div class=sc id=sc><ul id=list>" + items(40) + "</ul></div><button id=b1>Press</button><input id=in1 value='abc'>"
            + "<div class=abs id=ab>abs</div><p id=p1>tail paragraph text that wraps around the available width of the body element</p></body></html>";

    private static String items(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= n; i++) sb.append("<li id=li").append(i).append(">row ").append(i).append("</li>");
        return sb.toString();
    }

    @Test
    void interactionsMatchAFreshV3Render() {
        try (StandaloneView sv = new StandaloneView(DOC, W, H, FontResolver.FALLBACK, EnginePath.V3)) {
            SoftwareRenderBackend live = new SoftwareRenderBackend(W, H, 1f);
            long now = 1000;
            sv.frame(now, live, true);
            assertSame("初回", sv, live);
            Random rnd = new Random(42);
            for (int step = 0; step < 80; step++) {
                String op;
                switch (rnd.nextInt(7)) {
                    case 0, 1 -> {
                        double x = rnd.nextInt(W), y = rnd.nextInt(H);
                        sv.mouseMove(x, y, "s");
                        op = "move " + x + "," + y;
                    }
                    case 2 -> {
                        sv.mouseMove(60, 180, "s");
                        sv.scroll(0, rnd.nextBoolean() ? -1 : 1, "s");
                        op = "wheel";
                    }
                    case 3 -> {
                        HtmlNode list = sv.ui().findNode("list");
                        HtmlElement li = new HtmlElement("li");
                        li.updateTextContent("added " + step);
                        list.insertBefore(li, rnd.nextBoolean() ? list.children.getFirst() : null);
                        op = "insert";
                    }
                    case 4 -> {
                        HtmlElement c = (HtmlElement) sv.ui().findNode(rnd.nextBoolean() ? "c1" : "c2");
                        c.setAttribute("style", rnd.nextBoolean() ? "padding:" + rnd.nextInt(12) + "px" : "color:#" + (100 + rnd.nextInt(800)));
                        op = "style";
                    }
                    case 5 -> {
                        sv.resize(W - rnd.nextInt(60), H - rnd.nextInt(40));
                        op = "resize";
                    }
                    default -> {
                        sv.mouseMove(30, 20, "s");
                        sv.mouseButton(true, "s");
                        sv.mouseButton(false, "s");
                        op = "click";
                    }
                }
                now += 16;
                sv.frame(now, live, false);
                sv.frame(now + 1, live, false);
                now++;
                assertSame("step " + step + " " + op, sv, live);
            }
        }
    }

    /**
     * {@code position: sticky} はスクロールコンテナの内側の縁で止まり、親の内容の箱の外へは出ない。スクロール量はレイアウトの入力に
     * しないので、貼り付きは描画用の木の見え方（{@code PaintView}）が付け、中身の節を動かす更新とゼロから描いた面が一致する。
     */
    @Test
    void stickyHeaderSticksToTheScrollportAndStopsAtItsParent() {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 12; i++) rows.append("<div style='height:20px'>row ").append(i).append("</div>");
        String html = "<style>body{margin:0}#sc{position:absolute;left:10px;top:30px;width:150px;height:100px;overflow:auto;border:2px solid #333}"
                + ".h{position:sticky;top:0;height:16px;background:#fc6}</style><div id=sc><div id=g1><div class=h id=h1>one</div>" + rows
                + "</div><div id=g2><div class=h id=h2>two</div>" + rows + "</div></div>";
        try (StandaloneView sv = new StandaloneView(html, W, H, FontResolver.FALLBACK, EnginePath.V3)) {
            SoftwareRenderBackend live = new SoftwareRenderBackend(W, H, 1f);
            sv.frame(1000, live, true);
            HtmlNode sc = sv.ui().findNode("sc");
            float[][] cases = {{0f, 32f, 32f + 256f}, {60f, 32f, 32f + 256f - 60f}, {250f, 32f + 256f - 250f - 16f, 32f + 256f - 250f}, {300f, 32f + 256f - 300f - 16f, 32f}};
            long now = 1000;
            for (float[] c : cases) {
                sv.ui().scrollState().setScroll(sc, 0f, c[0]);
                sv.ui().requestScrollReposition();
                now += 16;
                sv.frame(now, live, true);
                assertEquals(c[1], box(sv.ui().boxRoot(), "h1").y(), 0.01f, "scroll " + c[0] + " の 1 つ目の見出し");
                assertEquals(c[2], box(sv.ui().boxRoot(), "h2").y(), 0.01f, "scroll " + c[0] + " の 2 つ目の見出し");
                assertSame("scroll " + c[0], sv, live);
            }
        }
    }

    /** {@code :has()} の持ち主（祖先）は、子孫の変化と同じフレームで解き直す（新経路は台帳だけを読むので、祖先も台帳に載ること）。 */
    @Test
    void hasSelectorRestylesTheAncestorInTheSameFrame() {
        String html = "<style>body{margin:0} .card{padding:4px;border:1px solid #888} .card:has(.on){padding:20px} .card:has(p:empty){margin-left:30px}</style>"
                + "<div class=card id=c><span id=s>x</span><p id=p>t</p></div><div id=after>after</div>";
        try (StandaloneView sv = new StandaloneView(html, W, H, FontResolver.FALLBACK, EnginePath.V3)) {
            SoftwareRenderBackend live = new SoftwareRenderBackend(W, H, 1f);
            long now = 1000;
            sv.frame(now, live, true);
            HtmlElement s = (HtmlElement) sv.ui().findNode("s");
            HtmlNode c = sv.ui().findNode("c"), p = sv.ui().findNode("p");
            List<Runnable> steps = List.of(() -> s.setAttribute("class", "on"), () -> s.removeAttribute("class"), () -> {
                HtmlElement e = new HtmlElement("b");
                e.setAttribute("class", "on");
                c.appendChild(e);
            }, () -> c.removeChild(c.children.getLast()), () -> p.updateTextContent(""), () -> p.updateTextContent("back"));
            for (int i = 0; i < steps.size(); i++) {
                steps.get(i).run();
                now += 16;
                sv.frame(now, live, true);
                assertEquals(null, FrameOracle.firstDifference(sv), "手 " + i);
            }
        }
    }

    private static com.nishiyu.enderframelib.core.layout.PaintBox box(com.nishiyu.enderframelib.core.layout.PaintBox b, String id) {
        if (b.node != null && !b.isAnonymous && id.equals(b.node.getId())) return b;
        for (var c : b.paintChildren()) {
            var f = box(c, id);
            if (f != null) return f;
        }
        return null;
    }

    /** ビューの面（{@code live}）が、同じ状態からゼロから新経路で描いた面と画素で一致すること。 */
    private static void assertSame(String what, StandaloneView sv, SoftwareRenderBackend live) {
        UiView ui = sv.ui();
        int w = ui.rootW(), h = ui.rootH();
        Document doc = ui.document();
        LayoutSession session = new LayoutSession(FontResolver.FALLBACK);
        FragmentPipeline p = new FragmentPipeline(new StyleSource(doc.sheet, ui.interactionState(), ui.env(), w, h), session);
        LayoutFragment f = p.frame(doc.root, null, w, h);
        PaintNode tree = new PaintTreeBuilder(session).build(f, p.boxRoot());
        List<DisplayItem> fresh = new TreePainter(w, h, doc.sheet.keyframes, ui.scrollState()).paint(tree);
        SoftwareRenderBackend b = new SoftwareRenderBackend(W, H, 1f);
        b.render(fresh, 0);
        Pixmap a = live.pixmap(), z = b.pixmap();
        int diff = 0;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) if (a.get(x, y) != z.get(x, y)) diff++;
        assertTrue(diff == 0, what + ": " + diff + " 画素がゼロから描いた面と違う");
    }
}
