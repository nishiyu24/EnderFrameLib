package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * B12: スタッキングコンテキストを作る・やめる箱（{@code :hover} で {@code transform} / {@code opacity} が付く）の変更は、
 * 項目が付録 E の別の層へ移るので箱のスライスの差し替えでは表せない。そのときは、前後とも変わらないいちばん近い
 * スタッキングコンテキストの部分木だけを組み直す（{@link DisplayListBuilder} の部分木の組み直し）。
 * 同じ操作列（ホバーの移動・遷移の途中と確定・スクロール）を、組み直しあり／なし（なしは全体の作り直し）で流し、
 * 毎フレームの表示リスト（値）と画素が完全に一致すること、組み直しが実際に使われたことを確かめる。
 */
class StackingSubtreePatchTest {
    private static final int W = 420, H = 300;

    /**
     * {@code .panel}（位置指定 + z-index）と {@code .fade}（opacity）がスタッキングコンテキスト。{@code #b2} はホバーで
     * コンテキストになり、中の絶対配置のバッジが {@code .fade} の層 8 から {@code #b2} の中へ移る。{@code #b3} のいちばん近い
     * コンテキストは文書の根なので組み直せない（全体の作り直しに落ちる）。{@code #p1} は単純な背景の変更（箱のスライスの差し替え）。
     */
    static final String DOC = "<html><head><style>"
            + "body{margin:6px;font:13px sans-serif;color:#223;background:#f4f4f8}"
            + ".panel{position:relative;z-index:1;padding:6px;background:#fff;border:1px solid #bbb;margin-bottom:6px}"
            + ".fade{opacity:.97;padding:4px;background:#eef}"
            + ".btn{display:inline-flex;padding:4px 10px;background:#36c;color:#fff;border-radius:9px;"
            + "transition:transform 120ms,background-color 120ms}"
            + ".btn:hover{transform:translateY(-3px) scale(1.04);background:#c33}"
            + ".rel{position:relative}"
            + ".badge{position:absolute;right:-4px;top:-6px;width:10px;height:10px;background:#f0f;border-radius:5px}"
            + ".plain{padding:4px;background:#ddd;margin:4px 0}"
            + ".plain:hover{background:#9c9}"
            + ".glow:hover{opacity:.6}"
            + ".over{position:relative;margin-top:-10px;background:rgba(255,200,0,.8);height:16px}"
            + "#scroller{height:60px;overflow-y:auto;background:#eef}"
            + "</style></head><body>"
            + "<div class='panel'>"
            + "<div class='fade'><span class='btn' id='b1'>One</span> <span class='btn rel' id='b2'>Two<i class='badge'></i></span> text after</div>"
            + "<div class='plain glow' id='g1'>glow block <span class='rel'>inner rel</span></div>"
            + "<div class='over'>overlapping positioned</div>"
            + "</div>"
            + "<div class='plain' id='p1'>plain outside</div>"
            + "<div><span class='btn' id='b3'>root-level button</span></div>"
            + "<div id='scroller'><p>row 1</p><p>row 2</p><p>row 3</p><p>row 4</p><p>row 5</p></div>"
            + "</body></html>";

    @AfterEach
    void restore() {
        com.nishiyu.enderframelib.core.kernel.Shortcut.STACKING_PATCH.set(true);
        DisplayListBuilder.WINDOW_MIN_OWNERS = Integer.getInteger("enderframe.paint.windowmin", 800);
    }

    /** 1 回の操作列の結果: フレームごとの表示リストの値の控えと画素。 */
    record Run(List<List<DisplayItem>> lists, List<int[]> pixels, long stackHits, long movePatches) { }

    private static double[] center(StandaloneView v, String id) {
        LayoutBox b = find(v.ui().layoutRoot(), id);
        assertNotNull(b, id);
        return new double[]{b.x + b.w / 2.0, b.y + b.h / 2.0};
    }

    private static LayoutBox find(LayoutBox b, String id) {
        if (b.node != null && id.equals(b.node.getId())) return b;
        if (b.children != null) for (LayoutBox c : b.children) {
            LayoutBox f = find(c, id);
            if (f != null) return f;
        }
        return null;
    }

    private static List<DisplayItem> freeze(List<DisplayItem> items) {
        List<DisplayItem> out = new ArrayList<>(items.size());
        for (DisplayItem it : items) out.add(it.frozen());
        return out;
    }

    /** 操作列を流す。{@code rows} 行を足すと窓付きの表示リストになる（{@code windowMin} を小さくする）。 */
    static Run run(boolean stackPatch, com.nishiyu.enderframelib.core.paint.RenderBackend backend, String doc) {
        com.nishiyu.enderframelib.core.kernel.Shortcut.STACKING_PATCH.set(stackPatch);
        long hits0 = DisplayListBuilder.STAT_STACKING_PATCH_HITS.sum();
        long moves0 = PaintSceneDriver.STAT_MOVE_PATCHES.sum();
        List<List<DisplayItem>> lists = new ArrayList<>();
        List<int[]> pixels = new ArrayList<>();
        try (StandaloneView v = new StandaloneView(doc, W, H, FontResolver.FALLBACK)) {
            long t = 1000;
            v.frame(t, backend, true);
            String[] path = {"b1", "b2", "b1", "g1", "p1", "b3", "b2", "p1", "g1", "b1"};
            for (String id : path) {
                double[] c = center(v, id);
                v.mouseMove(c[0], c[1], backend.sessionId());
                for (int i = 0; i < 4; i++) { // 遷移の途中・確定・何も変わらない続きのフレーム
                    v.frame(t += 40, backend, true);
                    lists.add(freeze(v.ui().renderCache()));
                    pixels.add(backend.pixmap().pixels().clone());
                }
            }
            v.mouseMove(-50, -50, backend.sessionId());
            for (int i = 0; i < 4; i++) {
                v.frame(t += 40, backend, true);
                lists.add(freeze(v.ui().renderCache()));
                pixels.add(backend.pixmap().pixels().clone());
            }
        }
        return new Run(lists, pixels, DisplayListBuilder.STAT_STACKING_PATCH_HITS.sum() - hits0,
                PaintSceneDriver.STAT_MOVE_PATCHES.sum() - moves0);
    }

    private static boolean sameValue(DisplayItem a, DisplayItem b) {
        return a.sameExceptPaint(b) && a.color() == b.color() && a.bgColor() == b.bgColor()
                && Float.compare(a.opacity(), b.opacity()) == 0;
    }

    static void assertSameRuns(Run expected, Run actual) {
        assertEquals(expected.lists().size(), actual.lists().size());
        for (int f = 0; f < expected.lists().size(); f++) {
            List<DisplayItem> e = expected.lists().get(f), a = actual.lists().get(f);
            assertEquals(e.size(), a.size(), "フレーム " + f + " の項目数");
            for (int i = 0; i < e.size(); i++) {
                if (!sameValue(e.get(i), a.get(i))) {
                    fail("フレーム " + f + " の項目 " + i + " が全体の作り直しと違う:\n  exp: " + e.get(i) + "\n  act: " + a.get(i));
                }
            }
            assertArrayEquals(expected.pixels().get(f), actual.pixels().get(f), "フレーム " + f + " の画素が全体の作り直しと違う");
        }
    }

    @Test
    void stackingFlipPatchMatchesFullRebuildOnCpu() {
        Run full, patched;
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(W, H, 1f)) {
            full = run(false, cpu, DOC);
        }
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(W, H, 1f)) {
            patched = run(true, cpu, DOC);
        }
        assertEquals(0, full.stackHits(), "切った構成では組み直しを使わない");
        assertTrue(patched.stackHits() > 0, "スタッキングコンテキストの部分木の組み直しが実際に使われた（空の検証ではない）");
        assertSameRuns(full, patched);
    }

    @Test
    void stackingFlipPatchMatchesFullRebuildInWindowedList() {
        // 窓付きの表示リスト（画面外の箱の項目を作らない）でも同じ。行を足して窓の外の箱を作る
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 60; i++) rows.append("<div class='plain'>filler row ").append(i).append("</div>");
        String doc = DOC.replace("</body>", rows + "</body>");
        DisplayListBuilder.WINDOW_MIN_OWNERS = 20;
        Run full, patched;
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(W, H, 1f)) {
            full = run(false, cpu, doc);
        }
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(W, H, 1f)) {
            patched = run(true, cpu, doc);
        }
        assertTrue(patched.stackHits() > 0, "窓付きでも組み直しが使われた");
        assertSameRuns(full, patched);
    }

    @Test
    void stackingFlipPatchMatchesFullRebuildOnGl() {
        com.nishiyu.enderframelib.core.paint.gl.LwjglGlRenderBackend probe;
        try {
            probe = new com.nishiyu.enderframelib.core.paint.gl.LwjglGlRenderBackend(8, 8);
            probe.close();
        } catch (Throwable t) {
            Assumptions.abort("OpenGL を使えない: " + t);
            return;
        }
        Run full, patched;
        try (var gl = new com.nishiyu.enderframelib.core.paint.gl.LwjglGlRenderBackend(W, H)) {
            full = run(false, gl, DOC);
        }
        try (var gl = new com.nishiyu.enderframelib.core.paint.gl.LwjglGlRenderBackend(W, H)) {
            patched = run(true, gl, DOC);
        }
        assertTrue(patched.stackHits() > 0, "GL でも組み直しが使われた");
        assertTrue(patched.movePatches() > 0, "描画側の索引が、並びの入れ替えの差分で整えられた（作り直していない）");
        assertSameRuns(full, patched);
    }
}
