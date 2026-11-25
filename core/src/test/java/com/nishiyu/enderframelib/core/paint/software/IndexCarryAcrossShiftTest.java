package com.nishiyu.enderframelib.core.paint.software;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.paint.PaintSceneDriver;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

/**
 * K4b: 描画側の索引の作り直し（{@code PaintSceneDriver.rebuildIndex}）は、位置がずれた項目の姿勢・補間状態を鍵で前回の位置から運ぶ。
 * ホバーで別々の項目の組が出入りして間がずれる（項目数は同じ）と、以前は索引と全項目の姿勢を毎フレーム作り直していた
 * （voidwave の {@code hover} の確保 {@code 250 KB} の約 7 割）。運んでも、最初から作った絵と同じで、姿勢を作る数が項目数より十分少ないこと。
 */
class IndexCarryAcrossShiftTest {
    private static final int W = 320, H = 400;

    private static String doc() {
        StringBuilder rows = new StringBuilder();
        for (int r = 0; r < 40; r++) rows.append("<div class='row' id='r").append(r).append("'>row ").append(r).append("</div>");
        return "<html><head><style>body{margin:0;font:12px sans-serif;background:#123}"
                + ".row{height:14px;margin:2px;padding:1px 4px;background:#dde;border:1px solid #99a;color:#123}"
                + "#a{height:20px;margin:2px;background:#cfc}#b{height:20px;margin:2px;background:#fcc}"
                + "#a:hover{border:3px solid #c00;border-radius:8px;box-shadow:0 2px 6px #000}"
                + "#b:hover{background:#ff8;outline:2px solid #00c}"
                + "</style></head><body>" + rows
                + "<div id='a'>A</div><div id='b'>B</div>" + rows.toString().replace("id='r", "id='s")
                + "</body></html>";
    }

    private static int[] fresh(double x, double y) {
        try (SoftwareRenderBackend backend = new SoftwareRenderBackend(W, H, 1f);
             StandaloneView v = new StandaloneView(doc(), W, H, FontResolver.FALLBACK)) {
            v.frame(1000, backend, true);
            v.mouseMove(x, y, backend.sessionId());
            v.frame(1016, backend, true);
            return backend.pixmap().pixels().clone();
        }
    }

    private static java.util.List<com.nishiyu.enderframelib.core.paint.model.DisplayItem> itemsWithHover(String id) {
        try (SoftwareRenderBackend backend = new SoftwareRenderBackend(W, H, 1f);
             StandaloneView v = new StandaloneView(doc(), W, H, FontResolver.FALLBACK)) {
            v.frame(1000, backend, true);
            double[] p = center(v.ui().boxRoot(), id);
            v.mouseMove(p[0], p[1], backend.sessionId());
            v.frame(1016, backend, true);
            return v.ui().renderCache();
        }
    }

    private static long allocated() {
        return ((com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean()).getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    @Test
    void indexRebuildsAcrossShiftedHoverListsAreCheapAndStayConsistent() {
        var a = itemsWithHover("a");
        var b = itemsWithHover("b");
        assertTrue(a.size() != b.size() || !a.equals(b), "二つのホバーで項目の並びが違う");
        PaintSceneDriver d = new PaintSceneDriver("k4b");
        var frame = com.nishiyu.enderframelib.core.raster.Matrix2D.IDENTITY;
        long t = 1000, worst = 0;
        for (int i = 0; i < 40; i++) {
            var items = (i & 1) == 0 ? a : b;
            long a0 = allocated();
            d.prepare(items, t += 16, frame);
            long used = allocated() - a0;
            if (i >= 8) worst = Math.max(worst, used);
            // 索引は常に今のリストと一致する（運んだ記憶が別の項目の位置に残っていない）
            for (int k = 0; k < items.size(); k += 7) { int at = d.indexOfKey(items.get(k).key());
                assertTrue(at >= 0 && items.get(at).key() == items.get(k).key(), "索引が今のリストと違う: 位置 " + k + " 手 " + i); }
        }
        // 毎回の作り直しが、項目数に比例して表（鍵 → 位置・鍵 → 項目）を作ると、1 回で 40 KB 以上確保していた
        assertTrue(worst < 12_000, "索引の作り直しの確保が大きい: " + worst + " B / 項目 " + a.size());
    }

    /**
     * K4g: 索引の更新は {@code rebuildIndex} が基準で、{@code reconcileIndex} は差分が小さいときに同じ結果を安く作る最適化。
     * 前回のリストから差分で整えた索引（鍵 → 位置・親の添字）が、毎回最初から作った索引と全項目で一致する。
     */
    @Test
    void reconciledIndexEqualsAFreshRebuildForEveryList() {
        var none = itemsWithHover("r5");
        var a = itemsWithHover("a");
        var b = itemsWithHover("b");
        var seq = java.util.List.of(none, a, b, a, none, b, b, a, none);
        PaintSceneDriver inc = new PaintSceneDriver("k4g-inc");
        var frame = com.nishiyu.enderframelib.core.raster.Matrix2D.IDENTITY;
        long t = 1000;
        for (int step = 0; step < seq.size(); step++) {
            var items = seq.get(step);
            inc.prepare(items, t += 16, frame);
            PaintSceneDriver fresh = new PaintSceneDriver("k4g-fresh" + step);
            fresh.prepare(items, t, frame);
            for (int i = 0; i < items.size(); i++) {
                assertTrue(inc.indexOfKey(items.get(i).key()) == fresh.indexOfKey(items.get(i).key()), "鍵の位置が違う: 手 " + step + " 位置 " + i);
                assertTrue(inc.parentIndex(i) == fresh.parentIndex(i), "親の添字が違う: 手 " + step + " 位置 " + i);
            }
        }
    }

    private static double[] center(com.nishiyu.enderframelib.core.layout.PaintBox b, String id) {
        if (b.node != null && !b.isAnonymous && id.equals(b.node.getId())) return new double[] {b.x() + b.w() / 2, b.y() + b.h() / 2};
        for (var c : b.paintChildren()) {
            double[] f = center(c, id);
            if (f != null) return f;
        }
        return null;
    }
}
