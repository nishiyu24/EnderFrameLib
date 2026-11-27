package com.nishiyu.enderframelib.core.paint.scene;

import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.AlphaMask;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SceneHitTest} と {@link SceneHitGeometry} の単体テスト（M3b-3）。
 */
class SceneHitTestTest {
    private static final int W = 400, H = 300;

    private static final class Compiled {
        final Document doc;
        final DisplayScene scene;

        Compiled(String html, int w, int h) {
            UiDocumentCompiler c = new UiDocumentCompiler();
            this.doc = c.parseDocument("<style>body{margin:0}</style>" + html, "", w, h);
            InteractionState ist = doc.root instanceof HtmlDocument hd && hd.interactionState() instanceof InteractionState s ? s : new InteractionState();
            this.scene = c.layoutDocument(doc, w, h, Map.of(), new ScrollState(), FontResolver.FALLBACK, ist);
        }

        HtmlElement el(String id) {
            return (HtmlElement) doc.root.getElementById(id);
        }
    }

    private static String hitKey(SceneHit h) {
        if (h == null) return null;
        return h.isScrollbar() ? h.baseId() + h.scrollbarPart() : h.baseId();
    }

    @Test
    void chunkCullingMatchesLinearScanAcrossRandomScenes() {
        long[] seeds = {11L, 29L, 47L};
        for (long seed : seeds) {
            Random rnd = new Random(seed);
            StringBuilder sb = new StringBuilder("<div id='sc' style='width:380px;height:280px;overflow-y:auto'>");
            for (int i = 0; i < 300; i++) {
                String extra = "";
                if (i % 37 == 5) extra += ";position:sticky;top:4px;z-index:2;background:#0a0";
                else if (i % 19 == 3) extra += ";transform:translateX(" + (rnd.nextInt(40) - 20) + "px) scale(1.05)";
                else if (i % 23 == 7) extra += ";pointer-events:none";
                else if (i % 13 == 1) extra += ";position:relative;z-index:" + (rnd.nextInt(5) - 1);
                int h = 14 + rnd.nextInt(26);
                sb.append("<div id='e").append(i).append("' style='height:").append(h)
                        .append("px;margin-bottom:2px;background:#336").append(extra).append("'></div>");
            }
            sb.append("</div>");
            Compiled c = new Compiled(sb.toString(), W, H);
            ScrollState scroll = new ScrollState();
            scroll.setScroll(c.el("sc"), 0f, 300f + rnd.nextInt(1500));
            SceneHitTest.Context ctx = SceneHitTest.Context.of(DynamicSceneState.of(scroll, W, H));

            int hits = 0;
            for (int p = 0; p < 2000; p++) {
                double x = rnd.nextDouble() * W;
                double y = rnd.nextDouble() * H;
                SceneHit culled = SceneHitTest.hitTest(c.scene, ctx, x, y);
                SceneHit linear = SceneHitTest.hitTestLinear(c.scene, ctx, x, y);
                assertEquals(hitKey(linear), hitKey(culled),
                        "seed=" + seed + " p=" + p + " (" + x + "," + y + ")");
                if (culled != null) hits++;
            }
            assertTrue(hits > 200, "十分に要素に当たる: hits=" + hits);
        }
    }

    @Test
    void reverseStackingOrderAndTopLayer() {
        UiDocumentCompiler c = new UiDocumentCompiler();
        String html = "<style>body{margin:0}"
                + "#neg{position:absolute;left:0;top:0;z-index:-1;width:100px;height:100px;background:#111}"
                + "#blk{width:95px;height:95px;background:#222}"
                + "#flt{float:left;width:80px;height:80px;margin-top:-95px;background:#333}"
                + "#inlWrap{clear:both;margin-top:-80px;pointer-events:none}"
                + "#inl{display:inline-block;width:65px;height:65px;pointer-events:auto;background:#444}"
                + "#pos0{position:absolute;left:0;top:0;z-index:0;width:45px;height:45px;background:#555}"
                + "#pos2{position:absolute;left:0;top:0;z-index:2;width:30px;height:30px;background:#666}"
                + "#clip{position:relative;width:10px;height:10px;overflow:hidden}"
                + "#dlg{position:fixed;left:0;top:0;width:90px;height:90px;margin:0;padding:0;border:0;background:#777}"
                + "</style>"
                + "<div style='position:relative;z-index:0'>"
                + "<div id='neg'></div><div id='blk'></div>"
                + "<div id='flt'></div><div id='inlWrap'><span id='inl'></span></div>"
                + "<div id='pos0'></div><div id='pos2'></div>"
                + "</div>"
                + "<div id='clip'><dialog id='dlg'></dialog></div>";
        Document doc = c.parseDocument(html, "", W, H);
        InteractionState ist = (InteractionState) ((HtmlDocument) doc.root).interactionState();
        SceneHitTest.Context ctx = SceneHitTest.Context.of(DynamicSceneState.of(null, W, H));

        DisplayScene beforeDialog = c.layoutDocument(doc, W, H, Map.of(), new ScrollState(), FontResolver.FALLBACK, ist);
        assertEquals("pos2", hitKey(SceneHitTest.hitTest(beforeDialog, ctx, 15, 15)));
        assertEquals("pos0", hitKey(SceneHitTest.hitTest(beforeDialog, ctx, 38, 20)));
        assertEquals("inl", hitKey(SceneHitTest.hitTest(beforeDialog, ctx, 55, 20)));
        assertEquals("flt", hitKey(SceneHitTest.hitTest(beforeDialog, ctx, 72, 20)));
        assertEquals("blk", hitKey(SceneHitTest.hitTest(beforeDialog, ctx, 88, 20)));
        assertEquals("neg", hitKey(SceneHitTest.hitTest(beforeDialog, ctx, 98, 20)));

        // open modal <dialog> escapes overflow:hidden clip and beats z-index: 2
        ((HtmlElement) doc.root.getElementById("dlg")).showModal();
        DisplayScene withDialog = c.layoutDocument(doc, W, H, Map.of(), new ScrollState(), FontResolver.FALLBACK, ist);
        assertEquals("dlg", hitKey(SceneHitTest.hitTest(withDialog, ctx, 15, 15)));
        assertEquals("dlg", hitKey(SceneHitTest.hitTest(withDialog, ctx, 75, 75)),
                "Top Layer は親の overflow:hidden(10x10) でクリップされない");
    }

    @Test
    void monotonicYChunkCullingSkipsNonOverlappingChunks() {
        int rows = 8000;
        StringBuilder sb = new StringBuilder("<div id='list' style='width:200px'>");
        for (int i = 0; i < rows; i++) {
            sb.append("<div id='r").append(i).append("' style='height:10px;background:#223'></div>");
        }
        sb.append("</div>");
        Compiled c = new Compiled(sb.toString(), W, rows * 10);
        SceneHitTest.Context ctx = SceneHitTest.Context.of(DynamicSceneState.of(null, W, rows * 10));

        int[] visited = new int[1];
        SceneHit hit = SceneHitTest.hitTest(c.scene, ctx, 50, 6500 * 10 + 5, visited);
        assertNotNull(hit);
        assertEquals("r6500", hit.baseId());
        assertTrue(visited[0] > 0 && visited[0] <= 12, "チャンク訪問数が対数オーダー: visited=" + visited[0]);

        // 3000 要素でも一致して高速
        SceneHit linear = SceneHitTest.hitTestLinear(c.scene, ctx, 50, 3000 * 10 + 5);
        assertEquals("r3000", hitKey(linear));
    }

    @Test
    void scrolledStickyElementRecoveredOutOfOriginalChunk() {
        StringBuilder sb = new StringBuilder("<div id='sc' style='width:200px;height:200px;overflow-y:auto'>");
        sb.append("<div id='sticky' style='position:sticky;top:0;height:30px;background:#0a0'></div>");
        for (int i = 0; i < 120; i++) {
            sb.append("<div id='row").append(i).append("' style='height:30px;background:#225'></div>");
        }
        sb.append("</div>");
        Compiled c = new Compiled(sb.toString(), W, H);
        SceneLayer scLayer = SceneQuery.locateById(c.scene, DynamicSceneState.of(null, W, H), "sc").layer();
        assertTrue(scLayer.children.chunks.length > 1, "複数チャンクに分割されている");
        assertTrue(scLayer.children.unculledChunks.length > 0, "sticky チャンクが unculledChunks に記録されている");

        ScrollState scroll = new ScrollState();
        scroll.setScroll(c.el("sc"), 0f, 2000f);
        SceneHitTest.Context ctx = SceneHitTest.Context.of(DynamicSceneState.of(scroll, W, H));
        SceneHit culled = SceneHitTest.hitTest(c.scene, ctx, 50, 15);
        SceneHit linear = SceneHitTest.hitTestLinear(c.scene, ctx, 50, 15);
        assertNotNull(culled);
        assertEquals("sticky", culled.baseId());
        assertEquals(hitKey(linear), hitKey(culled));
    }

    @Test
    void nestedScrollTransformAndClip() {
        Compiled c = new Compiled(
                "<div id='outer' style='width:220px;height:160px;overflow:hidden;background:#111'>"
                        + "  <div id='mid' style='width:300px;height:300px;transform:translate(20px, 10px) scale(2);transform-origin:0 0'>"
                        + "    <div id='inner' style='width:120px;height:60px;overflow:hidden'>"
                        + "      <div style='height:40px'></div>"
                        + "      <div id='target' style='width:200px;height:30px;background:#090'></div>"
                        + "      <div style='height:100px'></div>"
                        + "    </div>"
                        + "  </div>"
                        + "</div>", W, H);
        ScrollState scroll = new ScrollState();
        scroll.setScroll(c.el("inner"), 0f, 20f); // target is now at local y = 20..50 inside inner
        SceneHitTest.Context ctx = SceneHitTest.Context.of(DynamicSceneState.of(scroll, W, H));
        // mid scales 2x from (20, 10): target spans screen x = 20..420, y = 50..110 unless clipped
        assertEquals("target", hitKey(SceneHitTest.hitTest(c.scene, ctx, 80, 70)));
        // outside outer 220px clip: target would cover x=240, y=80 if not clipped
        assertNotEquals("target", hitKey(SceneHitTest.hitTest(c.scene, ctx, 240, 80)));
        // below inner 60px clip (screen y = 10 + 120 = 130) inside mid (300x300): hits mid, not target
        assertEquals("mid", hitKey(SceneHitTest.hitTest(c.scene, ctx, 80, 145)));
        // inside outer before mid's translate(20px, 10px): hits outer
        assertEquals("outer", hitKey(SceneHitTest.hitTest(c.scene, ctx, 5, 5)));
    }

    @Test
    void pointerEventsNoneAndVisibilityHidden() {
        Compiled c = new Compiled(
                "<button id='btn' style='position:absolute;left:10px;top:10px;width:100px;height:40px'></button>"
                        + "<div id='pe' style='position:absolute;left:10px;top:10px;width:60px;height:40px;background:#f00;pointer-events:none'></div>"
                        + "<div id='hid' style='position:absolute;left:10px;top:60px;width:100px;height:40px;background:#0f0;visibility:hidden'>"
                        + "  <div id='vis' style='width:30px;height:30px;background:#00f;visibility:visible'></div>"
                        + "</div>"
                        + "<button id='under' style='position:absolute;left:50px;top:60px;width:60px;height:40px'></button>", W, H);
        SceneHitTest.Context ctx = SceneHitTest.Context.of(DynamicSceneState.of(null, W, H));
        assertEquals("btn", hitKey(SceneHitTest.hitTest(c.scene, ctx, 30, 30)));
        assertEquals("vis", hitKey(SceneHitTest.hitTest(c.scene, ctx, 20, 75)));
        assertEquals("under", hitKey(SceneHitTest.hitTest(c.scene, ctx, 75, 75)));
    }

    @Test
    void scrollbarsAndScrollbarWidthNone() {
        Compiled c = new Compiled(
                "<div id='sc' style='position:absolute;left:0;top:0;width:100px;height:100px;overflow:scroll'>"
                        + "  <div style='width:400px;height:400px'></div>"
                        + "</div>"
                        + "<div id='none' style='position:absolute;left:120px;top:0;width:100px;height:100px;overflow:scroll;scrollbar-width:none;background:#123'>"
                        + "  <div style='width:400px;height:400px'></div>"
                        + "</div>", W, H);
        SceneHitTest.Context ctx = SceneHitTest.Context.of(DynamicSceneState.of(null, W, H));
        assertEquals("sc_sb_v", hitKey(SceneHitTest.hitTest(c.scene, ctx, 95, 10)));
        assertEquals("sc_sb_vt", hitKey(SceneHitTest.hitTest(c.scene, ctx, 95, 80)));
        assertEquals("sc_sb_h", hitKey(SceneHitTest.hitTest(c.scene, ctx, 10, 95)));
        assertEquals("sc_sb_ht", hitKey(SceneHitTest.hitTest(c.scene, ctx, 80, 95)));

        SceneHit noneHit = SceneHitTest.hitTest(c.scene, ctx, 120 + 95, 10);
        assertNotNull(noneHit);
        assertFalse(noneHit.isScrollbar(), "scrollbar-width:none ではスクロールバーが当たらない");
    }

    @Test
    void imgDataHitAlphaRespectsObjectFitContain() {
        // 左半分透明・右半分不透明な 100x100 画像を作成
        Pixmap pm = new Pixmap(100, 100);
        for (int y = 0; y < 100; y++) {
            for (int x = 50; x < 100; x++) pm.set(x, y, 0xFF00FF00);
        }
        Compiled c = new Compiled(
                "<img id='im' src='mem://shape.png' data-hit-alpha='128' "
                        + "style='position:absolute;left:0;top:0;width:200px;height:100px;object-fit:contain'>",
                W, H);
        SceneHitTest.AlphaHitTest alpha = (layer, src, threshold, lx, ly, bw, bh, fit, objPos, fontPx) ->
                AlphaMask.of(pm, threshold).hitsRect(lx, ly, bw, bh, 100f, 100f, fit, objPos, fontPx);
        SceneHitTest.Context ctx = SceneHitTest.Context.of(DynamicSceneState.of(null, W, H), null, alpha, null, true);

        // object-fit:contain により 100x100 が (50,0)-(150,100) に中央配置される
        assertNull(SceneHitTest.hitTest(c.scene, ctx, 20, 50), "レターボックス余白は透明扱い");
        assertNull(SceneHitTest.hitTest(c.scene, ctx, 75, 50), "画像左半分は透明画素なので透過");
        assertEquals("im", hitKey(SceneHitTest.hitTest(c.scene, ctx, 125, 50)), "画像右半分は不透明画素で当たる");
    }

    @Test
    void acceptFilterBlocksInertOutsideModal() {
        Compiled c = new Compiled(
                "<button id='under' style='position:absolute;left:10px;top:10px;width:80px;height:40px'></button>"
                        + "<div id='inertOverlay' style='position:absolute;left:10px;top:10px;width:80px;height:40px;background:#fff'></div>",
                W, H);
        SceneHitTest.Context ctx = SceneHitTest.Context.of(DynamicSceneState.of(null, W, H),
                null, null, l -> l.node == null || !"inertOverlay".equals(l.node.key()), true);
        assertEquals("under", hitKey(SceneHitTest.hitTest(c.scene, ctx, 40, 25)));
    }

    @Test
    void userPaintOffsetDoesNotDoubleShiftSticky() {
        String html = "<style>body{margin:0}"
                + "#sc{width:240px;height:200px;overflow-y:auto}"
                + "#sticky{position:sticky;top:0;height:30px;background:#0a0}"
                + "#drag{position:relative;left:10px;top:20px;width:80px;height:30px;background:#06c}"
                + "#fill{height:2000px}</style>"
                + "<div id='sc'>"
                + "  <div id='sticky'></div>"
                + "  <div id='drag'></div>"
                + "  <div id='fill'></div>"
                + "</div>";
        try (StandaloneView v = new StandaloneView(html, W, H, FontResolver.FALLBACK);
             var be = new SoftwareRenderBackend(W, H, 1f)) {
            v.frame(1000, be, true);
            v.ui().scrollState().setScroll(v.ui().findNode("sc"), 0f, 400f);
            v.ui().requestScrollReposition();
            v.frame(1016, be, true); // スクロール平行移動を実行し sticky の paintOffsets を埋める
            v.ui().setPaintOffset("drag", 60f, 350f);

            // drag: 元 y=50 + 350(paintOffset) - 400(scroll) = 0..30, x=10+60=70..150
            SceneHit dragHit = v.ui().hitTest(100, 15);
            assertNotNull(dragHit);
            assertEquals("drag", dragHit.baseId());

            // sticky: scroll=400 でも viewport top y=0..30 に貼り付く（paintOffsets で二重加算されない）
            SceneHit stickyHit = v.ui().hitTest(20, 15);
            assertNotNull(stickyHit);
            assertEquals("sticky", stickyHit.baseId());
        }
    }

    @Test
    void transformOverrideOverridesStaticTransform() {
        Compiled c = new Compiled(
                "<div id='box' style='position:absolute;left:0;top:0;width:50px;height:50px;background:#f00;transform:translateX(10px)'></div>",
                W, H);
        SceneHitTest.TransformOverride override = (layer, out6) -> {
            if (layer.node != null && "box".equals(layer.node.key())) {
                out6[0] = 150f;
                out6[1] = 20f;
                out6[2] = 1f;
                out6[3] = 1f;
                out6[4] = 0f;
                out6[5] = 0f;
                return true;
            }
            return false;
        };
        SceneHitTest.Context ctx = SceneHitTest.Context.of(DynamicSceneState.of(null, W, H), override, null, null, true);
        assertNull(SceneHitTest.hitTest(c.scene, ctx, 35, 25), "静的位置 (10..60, 0..50) には当たらない");
        assertEquals("box", hitKey(SceneHitTest.hitTest(c.scene, ctx, 175, 45)), "上書き後の位置 (150..200, 20..70) に当たる");
    }

    @Test
    void overflowHiddenDoesNotSwallowScrollTarget() {
        Compiled c = new Compiled(
                "<div id='outer' style='position:absolute;left:0;top:0;width:220px;height:140px;overflow-y:auto'>"
                        + "  <div id='clipped' style='width:200px;height:100px;overflow:hidden'>"
                        + "    <div style='height:500px'></div>"
                        + "  </div>"
                        + "  <div style='height:400px'></div>"
                        + "</div>", W, H);
        SceneHitTest.Context ctx = SceneHitTest.Context.of(DynamicSceneState.of(null, W, H));
        List<SceneHit> containers = SceneHitTest.scrollContainersAt(c.scene, ctx, 50, 50);
        assertFalse(containers.isEmpty());
        for (SceneHit h : containers) {
            assertNotEquals("clipped", h.baseId(), "overflow:hidden の箱はホイール宛先にならない");
        }
        assertEquals("outer", containers.getFirst().baseId());
    }

    @Test
    void scrollContainersAtPreservesPointerAcrossTransformedZPass() {
        Compiled c = new Compiled(
                "<div id='scroller' style='position:absolute;left:0;top:0;width:240px;height:180px;overflow-y:auto'>"
                        + "  <div id='badge' style='position:relative;z-index:10;width:80px;height:30px;transform:translate(200px,200px)'></div>"
                        + "  <div style='height:600px'></div>"
                        + "</div>", W, H);
        SceneHitTest.TransformOverride override = (layer, out6) -> {
            if (layer.node != null && "badge".equals(layer.node.key())) {
                out6[0] = 500f; out6[1] = 500f; out6[2] = 1f; out6[3] = 1f; out6[4] = 0f; out6[5] = 0f;
                return true;
            }
            return false;
        };
        SceneHitTest.Context ctx = SceneHitTest.Context.of(DynamicSceneState.of(null, W, H), override, null, null, true);
        List<SceneHit> containers = SceneHitTest.scrollContainersAt(c.scene, ctx, 60, 60);
        assertFalse(containers.isEmpty(), "Z パスの逆変換で共有座標が壊れず scroller が返る");
        assertEquals("scroller", containers.getFirst().baseId());
    }

    @Test
    void fieldsetLegendShiftExcludesTopBand() {
        Compiled c = new Compiled(
                "<fieldset id='fs' style='position:absolute;left:10px;top:20px;width:200px;height:100px;border:2px solid #444;background:#222;padding:0;margin:0'>"
                        + "  <legend id='lg' style='height:20px;margin:0;padding:0 4px;background:#555'>Title</legend>"
                        + "</fieldset>", W, H);
        SceneHitTest.Context ctx = SceneHitTest.Context.of(DynamicSceneState.of(null, W, H));
        // legend.h=20, bT=2 -> shift=(20-2)/2=9px: y=20..29 の legend 外（右端 x=180）は fieldset の箱の外
        assertNull(SceneHitTest.hitTest(c.scene, ctx, 180, 23), "legend の上半分による上端シフトより上は fieldset に当たらない");
        assertEquals("fs", hitKey(SceneHitTest.hitTest(c.scene, ctx, 180, 40)), "シフト後（y>=29）は fieldset に当たる");
    }

    @Test
    void textRunsExtendBoundsForCulling() {
        Compiled c = new Compiled(
                "<div id='box' style='width:220px;padding:12px 24px;font:14px sans-serif'>padded selectable label text</div>",
                W, H);
        SceneHitTest.Context ctx = SceneHitTest.Context.of(DynamicSceneState.of(null, W, H));
        for (int y = 4; y < 44; y += 4) {
            for (int x = 10; x < 260; x += 6) {
                SceneHit culled = SceneHitTest.hitTest(c.scene, ctx, x, y);
                SceneHit linear = SceneHitTest.hitTestLinear(c.scene, ctx, x, y);
                assertEquals(hitKey(linear), hitKey(culled), "点(" + x + "," + y + ") で TextRun カリングが線形走査と一致");
            }
        }
    }
}
