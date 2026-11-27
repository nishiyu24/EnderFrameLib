package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.box.StyleSource;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.fragment.FragmentPipeline;
import com.nishiyu.enderframelib.core.layout.fragment.LayoutFragment;
import com.nishiyu.enderframelib.core.layout.fragment.LayoutSession;
import com.nishiyu.enderframelib.core.paint.SceneDisplayListAdapter;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.scene.DisplayScene;
import com.nishiyu.enderframelib.core.paint.scene.DynamicSceneState;
import com.nishiyu.enderframelib.core.paint.scene.SceneEmitter;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
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
        try (StandaloneView sv = new StandaloneView(DOC, W, H, FontResolver.FALLBACK)) {
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
        try (StandaloneView sv = new StandaloneView(html, W, H, FontResolver.FALLBACK)) {
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

    /**
     * 中身を 1 つの節にまとめないスクロールコンテナ（下の inset の sticky を含む）は、スクロールしたフレームで今のスクロール量で描く。
     * 描画の記憶がコンテナの祖先（文脈が変わらない）を使い回して、中身が古いスクロール量のまま出ていた（G8 の作業で発見。スクロールした
     * コンテナを台帳に節つきの {@code SCROLL} で載せ、祖先を使い回さない）。
     */
    @Test
    void scrollContainerNotMovedAsOneNodeRepaintsUnderAReusedAncestor() {
        String html = "<style>body{margin:0}#sc{width:200px;height:120px;overflow-y:auto}.r{height:20px;background:#9cf;margin-bottom:4px}"
                + "#st{position:sticky;bottom:0;height:10px;background:#f00}</style><div id=w><div id=sc>" + "<div class=r></div>".repeat(12)
                + "<div id=st></div></div></div><p>tail</p>";
        try (StandaloneView sv = new StandaloneView(html, W, H, FontResolver.FALLBACK)) {
            SoftwareRenderBackend live = new SoftwareRenderBackend(W, H, 1f);
            sv.frame(1000, live, true);
            assertTrue(sv.ui().v3.scrollContents().isEmpty(), "前提: 中身を 1 つの節にまとめないコンテナ");
            long now = 1000;
            for (float s : new float[]{30f, 75f, 0f}) {
                sv.ui().scrollState().setScroll(sv.ui().findNode("sc"), 0f, s);
                sv.ui().requestScrollReposition();
                sv.frame(now += 16, live, true);
                assertSame("scroll " + s, sv, live);
            }
        }
    }

    /** {@code :has()} の持ち主（祖先）は、子孫の変化と同じフレームで解き直す（新経路は台帳だけを読むので、祖先も台帳に載ること）。 */
    @Test
    void hasSelectorRestylesTheAncestorInTheSameFrame() {
        String html = "<style>body{margin:0} .card{padding:4px;border:1px solid #888} .card:has(.on){padding:20px} .card:has(p:empty){margin-left:30px}</style>"
                + "<div class=card id=c><span id=s>x</span><p id=p>t</p></div><div id=after>after</div>";
        try (StandaloneView sv = new StandaloneView(html, W, H, FontResolver.FALLBACK)) {
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

    /**
     * 位置指定の層（z-index: auto）は文書順に描く: 包含ブロックの子にした流れの外の箱も、文書で後ろにある位置指定の兄弟より先に描く
     * （voidwave の背景の光が見出しの上に重なった）。
     */
    @Test
    void outOfFlowBoxesPaintInDocumentOrderWithPositionedSiblings() {
        String html = "<style>body{margin:0}</style><div style='position:relative;width:200px;height:100px'>"
                + "<div style='position:absolute;left:0;top:0;width:200px;height:100px;background:#f00'></div>"
                + "<div style='position:relative;width:100px;height:50px;background:#00f'></div>"
                + "<div style='position:absolute;left:150px;top:0;width:50px;height:50px;background:#0f0'></div></div>";
        try (StandaloneView sv = new StandaloneView(html, W, H, FontResolver.FALLBACK)) {
            SoftwareRenderBackend live = new SoftwareRenderBackend(W, H, 1f);
            sv.frame(1000, live, true);
            assertEquals(0xFF0000FF, live.pixmap().get(20, 20), "後ろの relative の兄弟が前の absolute の上");
            assertEquals(0xFF00FF00, live.pixmap().get(170, 20), "後ろの absolute は前の relative の上");
            assertEquals(0xFFFF0000, live.pixmap().get(120, 80), "absolute だけの所");
        }
    }

    /**
     * 描画の記憶が使い回したスクロールコンテナの中身の節は、記憶したときのスクロール量の位置で出る（コンテナを含む親の部分木ごと記憶が
     * 当たったとき）。スクロールの後で別の所が変わって作り直したフレームでも、今のスクロール量との差を節の移動で足すこと（M2c-2。以前は
     * 差を消したまま足さず、中身が先頭へ戻って描かれた）。
     */
    @Test
    void reusedScrollContentKeepsTheCurrentScrollAfterAnUnrelatedChange() {
        String html = "<style>body{margin:0;font:12px sans-serif}#sc{width:200px;height:80px;overflow:auto;border:1px solid #333}"
                + ".r{height:18px;background:#def;margin:2px}</style><div id=w><div id=sc>" + "<div class=r>row</div>".repeat(20) + "</div></div><p id=p>tail</p>";
        try (StandaloneView sv = new StandaloneView(html, W, H, FontResolver.FALLBACK)) {
            SoftwareRenderBackend live = new SoftwareRenderBackend(W, H, 1f);
            sv.frame(1000, live, true);
            sv.ui().scrollState().setScroll(sv.ui().findNode("sc"), 0f, 70f);
            sv.ui().requestScrollReposition();
            sv.frame(1016, live, true);
            assertSame("スクロールの後", sv, live);
            sv.ui().findNode("p").updateTextContent("tail that changed");
            sv.frame(1032, live, true);
            assertSame("関係の無い文字の変更の後", sv, live);
        }
    }

    /**
     * 祖先の状態を読む子孫の規則（{@code .list:hover .item}）は、祖先の状態が変わったフレームで子孫を解き直す（M2c-2。無効化セットの
     * {@code DESCENDANTS} を台帳に {@code STYLE_SUBTREE} として書く。以前は {@code STYLE} だけで、継承値の変わらない子孫を解き直さなかった）。
     */
    @Test
    void ancestorHoverRestylesDescendantsThroughTheLedger() {
        String html = "<style>body{margin:0;font:14px sans-serif}.list{padding:10px;background:#eee}.item{height:20px;color:#000;background:#fff}"
                + ".list:hover .item{background:#c00}</style><div class=list id=l><div class=item>one</div><div class=item>two</div></div>";
        try (StandaloneView sv = new StandaloneView(html, W, H, FontResolver.FALLBACK)) {
            SoftwareRenderBackend live = new SoftwareRenderBackend(W, H, 1f);
            sv.frame(1000, live, true);
            sv.mouseMove(4, 4, "s"); // .list の padding の上（.item の外）
            sv.frame(1016, live, true);
            assertEquals(0xFFCC0000, live.pixmap().get(200, 20), "祖先の :hover で子孫の背景が変わる");
            assertSame("ホバーの後", sv, live);
            sv.mouseMove(W - 2, H - 2, "s");
            sv.frame(1032, live, true);
            assertEquals(0xFFFFFFFF, live.pixmap().get(200, 20), "ホバーが外れたら戻る");
        }
    }

    /**
     * 全体の作り直しの要求（{@code RELAYOUT}。書体の登録の変化など台帳に載らない変化）は、新経路でも前回の木と記憶を捨てて組み直し、
     * 走っているトランジションは途中の値を保つ（M2c-2。以前は新経路が要求を読まずに増分で組み、作り直す実装も遷移を終点へ飛ばした）。
     */
    @Test
    void fullRebuildRequestKeepsRunningTransitions() {
        String html = "<style>body{margin:0}#b{width:100px;height:20px;background:#00f;transition:width 1000ms linear}#b.wide{width:300px}</style><div id=b></div>";
        float[] widths = new float[2];
        for (int run = 0; run < 2; run++) {
            try (StandaloneView sv = new StandaloneView(html, W, H, FontResolver.FALLBACK)) {
                sv.clock(new com.nishiyu.enderframelib.core.kernel.Clock.Manual());
                SoftwareRenderBackend live = new SoftwareRenderBackend(W, H, 1f);
                sv.frame(1000, live, true);
                ((HtmlElement) sv.ui().findNode("b")).setAttribute("class", "wide");
                if (run == 1) sv.ui().markInvalidation(UiView.InvalidationLevel.RELAYOUT); // 遷移が始まるフレームを全体の作り直しにする
                sv.frame(1016, live, true);
                if (run == 1) sv.ui().markInvalidation(UiView.InvalidationLevel.RELAYOUT);
                sv.frame(1516, live, true);
                widths[run] = box(sv.ui().boxRoot(), "b").w();
            }
        }
        assertTrue(widths[0] > 150f && widths[0] < 250f, "増分は遷移の途中: " + widths[0]);
        assertEquals(widths[0], widths[1], 0.01f, "全体の作り直しも同じ途中の値");
    }

    /** 全体の作り直しの要求は、台帳に載らない変化（ここでは通知の無い属性の書き換え）も読む（新経路が要求を無視して増分で組まないこと）。 */
    @Test
    void fullRebuildRequestReadsChangesOutsideTheLedger() {
        try (StandaloneView sv = new StandaloneView("<style>body{margin:0}</style><div id=b style='width:100px;height:10px'></div>", W, H, FontResolver.FALLBACK)) {
            SoftwareRenderBackend live = new SoftwareRenderBackend(W, H, 1f);
            sv.frame(1000, live, true);
            sv.ui().findNode("b").attrs.put("style", "width:50px;height:10px"); // 通知しない書き換え（台帳に載らない）
            sv.ui().markInvalidation(UiView.InvalidationLevel.RESTYLE);
            sv.frame(1016, live, true);
            assertEquals(100f, box(sv.ui().boxRoot(), "b").w(), 0.01f, "増分は台帳に載った変化だけを読む");
            sv.ui().markInvalidation(UiView.InvalidationLevel.RELAYOUT);
            sv.frame(1032, live, true);
            assertEquals(50f, box(sv.ui().boxRoot(), "b").w(), 0.01f, "全体の作り直しは DOM をそのまま読む");
        }
    }

    /**
     * 文書を開いた直後の最初のフレームは静止フレーム（M2c-3）: 開くときの全体の作り直しが、文書を入れたときに溜まった変更
     * （{@code markAllDirty} の台帳の記録）も消化する。消化しないと、次のフレームが同じ変更を読んでもう一度全体を組み直していた
     * （2,000 行の一覧で 1 フレーム 130 ms 余り・保持ヒープ +1.6 MB）。
     */
    @Test
    void firstFrameAfterOpeningIsIdle() {
        StringBuilder rows = new StringBuilder("<ul>");
        for (int i = 0; i < 300; i++) rows.append("<li id=r").append(i).append(">row ").append(i).append("</li>");
        try (StandaloneView sv = new StandaloneView(rows.append("</ul>").toString(), W, H, FontResolver.FALLBACK);
             var be = new MutationScenario.NoDraw()) {
            List<DisplayItem> opened = sv.ui().renderCache();
            long idle = UiView.IDLE_FRAMES.get();
            sv.frame(1000, be, true);
            assertTrue(opened == sv.ui().renderCache(), "開いた直後のフレームで表示リストを作り直した");
            assertEquals(idle + 1, UiView.IDLE_FRAMES.get(), "開いた直後のフレームが静止フレームにならない");
        }
    }

    private static com.nishiyu.enderframelib.core.paint.tree.PaintBox box(com.nishiyu.enderframelib.core.paint.tree.PaintBox b, String id) {
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
        DisplayScene scene = new SceneEmitter(session, doc.sheet.keyframes).emit(f, p.boxRoot(), w, h);
        List<DisplayItem> fresh = new SceneDisplayListAdapter(w, h, doc.sheet.keyframes)
                .emit(scene, DynamicSceneState.of(ui.scrollState(), w, h));
        SoftwareRenderBackend b = new SoftwareRenderBackend(W, H, 1f);
        b.render(fresh, 0);
        Pixmap a = live.pixmap(), z = b.pixmap();
        int diff = 0;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) if (a.get(x, y) != z.get(x, y)) diff++;
        assertTrue(diff == 0, what + ": " + diff + " 画素がゼロから描いた面と違う");
    }
}
