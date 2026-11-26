package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.box.StyleSource;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.fragment.FragmentPipeline;
import com.nishiyu.enderframelib.core.layout.fragment.LayoutFragment;
import com.nishiyu.enderframelib.core.layout.fragment.LayoutSession;
import com.nishiyu.enderframelib.core.paint.TreePainter;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.tree.PaintBox;
import com.nishiyu.enderframelib.core.paint.tree.PaintNode;
import com.nishiyu.enderframelib.core.paint.tree.PaintTreeBuilder;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * スクロールコンテナの中のアンカーへの追従と、スクロールによる {@code position-try} の選び直し（CSS Anchor Positioning 1 §3.3・§6.5。
 * 計画書 §3.2「G8 の後半の設計」）。スクロール量はレイアウトの入力にせず、追従は描画の平行移動、選び直しはアンカーの再計算点で覚えた
 * スクロール量を取り直して組み直す。
 */
class AnchorScrollFollowTest {
    private static final int W = 400, H = 300;

    /** #a は y = spacer..spacer+30。#t は固定配置で、既定のアンカー #a の下（{@code flip-block} の候補があれば上）に置く。 */
    private static String doc(int spacer, boolean fallbacks) {
        return "<style>body{margin:0}"
                + "#sc{width:400px;height:300px;overflow-y:auto}"
                + "#spacer{height:" + spacer + "px}"
                + "#a{width:80px;height:30px;anchor-name:--a;background:#00f}"
                + "#t{position:fixed;width:60px;height:40px;position-anchor:--a;top:anchor(bottom);left:anchor(left);background:#0a0"
                + (fallbacks ? ";position-try-fallbacks:flip-block" : "") + "}"
                + "</style><div id=sc><div id=spacer></div><div id=a></div><div style='height:600px'></div></div><div id=t></div>";
    }

    /** 候補の無い箱も、スクロールしたアンカーと一緒に動く（スクロールだけの更新で、中身の節を動かして済ませる経路でも）。 */
    @Test
    void fixedBoxFollowsItsScrolledAnchor() {
        try (StandaloneView sv = new StandaloneView(doc(50, false), W, H, FontResolver.FALLBACK)) {
            SoftwareRenderBackend live = new SoftwareRenderBackend(W, H, 1f);
            long now = 1000;
            sv.frame(now, live, true);
            assertEquals(80, y(sv, "t"), 0.01f);
            for (float s : new float[]{30f, 45f, 0f}) {
                scroll(sv, s);
                sv.frame(now += 16, live, true);
                assertEquals(50 - s, y(sv, "a"), 0.01f, "scroll " + s + " のアンカー");
                assertEquals(80 - s, y(sv, "t"), 0.01f, "scroll " + s + " の追従する箱");
                assertSamePixels("scroll " + s, sv, live);
            }
        }
    }

    /**
     * 覚えたスクロール量が状態の役をする: 反転した候補は、スクロールしてもはみ出さない限り保つ（仕様 §6.5 の「はみ出すまで今の候補」）。
     * はみ出したら再計算点で今の量を覚え、基本の候補から選び直す。
     */
    @Test
    void scrollReselectsTheFallbackOnlyWhenTheChosenOneOverflows() {
        try (StandaloneView sv = new StandaloneView(doc(250, true), W, H, FontResolver.FALLBACK)) {
            SoftwareRenderBackend live = new SoftwareRenderBackend(W, H, 1f);
            long now = 1000;
            sv.frame(now, live, true);
            assertEquals(210, y(sv, "t"), 0.01f, "下 (280..320) ははみ出すので上へ反転");
            // 100px: アンカーは 150..180。反転した候補 (110..150) ははみ出さないので保つ（基本の候補 180..220 も収まるが選び直さない）
            float[][] cases = {{100f, 110f}, {220f, 60f}, {0f, 210f}};
            // 220px: 反転した候補は -10 で上へはみ出す → アンカー 30..60 の下 (60..100) へ戻る
            // 0px: 基本の候補は 280..320 で下へはみ出す → また上へ反転 (210)
            for (float[] c : cases) {
                scroll(sv, c[0]);
                sv.frame(now += 16, live, true);
                assertEquals(c[1], y(sv, "t"), 0.01f, "scroll " + c[0]);
                assertSamePixels("scroll " + c[0], sv, live);
            }
        }
    }

    /** 覚えたスクロール量はビューの履歴なので、全体の作り直し（{@code RELAYOUT}）をまたいでも候補が変わらない。 */
    @Test
    void rememberedScrollSurvivesAFullRebuild() {
        try (StandaloneView sv = new StandaloneView(doc(250, true), W, H, FontResolver.FALLBACK);
             var be = new MutationScenario.NoDraw()) {
            long now = 1000;
            sv.frame(now, be, true);
            scroll(sv, 100f);
            sv.frame(now += 16, be, true);
            assertEquals(110, y(sv, "t"), 0.01f);
            List<String> incremental = sv.items().stream().map(MutationScenario::sig).toList();
            sv.ui().markInvalidation(UiView.InvalidationLevel.RELAYOUT);
            sv.frame(now += 16, be, true);
            assertEquals(110, y(sv, "t"), 0.01f, "作り直しても反転した候補のまま");
            assertEquals(incremental, sv.items().stream().map(MutationScenario::sig).toList());
        }
    }

    /** スクロールした後で箱を作り始めた（display: none から戻した）ときは再計算点: 今のスクロール量で候補を選ぶ。 */
    @Test
    void boxGeneratedWhileScrolledChoosesWithTheCurrentScroll() {
        try (StandaloneView sv = new StandaloneView(doc(250, true), W, H, FontResolver.FALLBACK);
             var be = new MutationScenario.NoDraw()) {
            long now = 1000;
            sv.frame(now, be, true);
            HtmlNode t = sv.ui().findNode("t");
            ((com.nishiyu.enderframelib.core.dom.node.HtmlElement) t).setAttribute("style", "display:none");
            sv.frame(now += 16, be, true);
            scroll(sv, 100f);
            sv.frame(now += 16, be, true);
            ((com.nishiyu.enderframelib.core.dom.node.HtmlElement) t).removeAttribute("style");
            sv.frame(now += 16, be, true);
            assertEquals(180, y(sv, "t"), 0.01f, "アンカー 150..180 の下が収まるので基本の候補");
        }
    }

    private static void scroll(StandaloneView sv, float y) {
        sv.ui().scrollState().setScroll(sv.ui().findNode("sc"), 0f, y);
        sv.ui().requestScrollReposition();
    }

    private static float y(StandaloneView sv, String id) {
        PaintBox b = find(sv.ui().boxRoot(), id);
        if (b == null) throw new AssertionError(id + " が無い");
        return b.y();
    }

    private static PaintBox find(PaintBox b, String id) {
        if (b.node() != null && id.equals(b.node().getId())) return b;
        for (PaintBox c : b.paintChildren()) {
            PaintBox f = find(c, id);
            if (f != null) return f;
        }
        return null;
    }

    /** ビューの面が、同じスクロール量・同じ覚えたスクロール量からゼロから組んで描いた面と画素で一致すること。 */
    private static void assertSamePixels(String what, StandaloneView sv, SoftwareRenderBackend live) {
        UiView ui = sv.ui();
        Document doc = ui.document();
        LayoutSession session = new LayoutSession(FontResolver.FALLBACK);
        FragmentPipeline p = new FragmentPipeline(new StyleSource(doc.sheet, ui.interactionState(), ui.env(), W, H), session);
        p.compiler().remembered(ui.v3.remembered());
        LayoutFragment f = p.frame(doc.root, null, W, H);
        PaintNode tree = new PaintTreeBuilder(session).build(f, p.boxRoot());
        List<DisplayItem> fresh = new TreePainter(W, H, doc.sheet.keyframes, ui.scrollState()).paint(tree);
        SoftwareRenderBackend b = new SoftwareRenderBackend(W, H, 1f);
        b.render(fresh, 0);
        Pixmap a = live.pixmap(), z = b.pixmap();
        int diff = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) if (a.get(x, y) != z.get(x, y)) diff++;
        assertTrue(diff == 0, what + ": " + diff + " 画素がゼロから描いた面と違う");
        assertEquals(0xFF00AA00, a.get(30, Math.round(y(sv, "t")) + 20), what + ": 追従する箱が見えた位置に描かれている");
    }
}
