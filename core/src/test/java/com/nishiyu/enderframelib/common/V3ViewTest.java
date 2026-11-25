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
