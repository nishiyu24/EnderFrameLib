package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.paint.HitTestIndex;
import com.nishiyu.enderframelib.core.paint.ImmutableItemList;
import com.nishiyu.enderframelib.core.paint.UIHitResult;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.PaintOffsets;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * スクロールのたびに表示項目を作り直さず（移動量は外部表 {@link PaintOffsets} だけに持つ）、ヒットテストの索引も作り直さず使い回すことが、
 * 「その時点で新しく作った索引」と同じ結果を返し、かつ実際に使い回されていることを確かめる。
 */
class ScrollIndexReuseTest {
    private static final int W = 320, H = 240;

    private static String html() {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 80; i++) rows.append("<div class=\"row\" id=\"r").append(i).append("\" onclick=\"x()\">row ").append(i).append("</div>");
        StringBuilder side = new StringBuilder();
        for (int i = 0; i < 30; i++) side.append("<div class=\"row\" id=\"s").append(i).append("\" onclick=\"x()\">side ").append(i).append("</div>");
        return "<style>body{margin:0;font-size:13px}.row{height:24px;margin:2px;padding:2px 6px;background:#eef;border:1px solid #99a}"
                + "#outer{position:absolute;left:0;top:0;width:190px;height:230px;overflow-y:auto;border:2px solid #333}"
                + "#side{position:absolute;left:200px;top:0;width:110px;height:110px;overflow:scroll}</style>"
                + "<div id=\"outer\">" + rows + "</div><div id=\"side\">" + side + "</div>";
    }

    @Test
    void reusedIndexGivesTheSameAnswersAsAFreshOneAndIsReallyReused() {
        StandaloneView view = new StandaloneView(html(), W, H, FontResolver.FALLBACK);
        try (var backend = new MutationScenario.NoDraw()) {
            long now = 1000;
            view.frame(now, backend, true);
            String sid = backend.sessionId();
            Random rnd = new Random(5);
            HitTestIndex previous = null;
            int reused = 0, steps = 120;
            for (int step = 0; step < steps; step++) {
                view.mouseMove(rnd.nextBoolean() ? 60 : 250, rnd.nextInt(H), sid);
                view.scroll(0, rnd.nextInt(7) - 3, sid);
                now += 16;
                view.frame(now, backend, true);
                HitTestIndex idx = view.ui().hitTestIndex();
                if (step > 10 && idx == previous) reused++; // 直前のステップと同じ索引（基底の作り直しがあれば、そのステップだけ新しくなる）
                previous = idx;
                List<DisplayItem> items = view.ui().renderCache();
                // 同じ時点で新しく作った索引（動きうる節は同じ集合で扱う）と、全点で同じ答えになる
                HitTestIndex fresh = new HitTestIndex(items, idx.dynamicIds(), view.ui().paintOffsets());
                for (int i = 0; i < 160; i++) {
                    double x = rnd.nextDouble() * W, y = rnd.nextDouble() * H;
                    UIHitResult a = idx.hitTest(sid, x, y), b = fresh.hitTest(sid, x, y);
                    assertEquals(b == null ? "-" : b.element().id(), a == null ? "-" : a.element().id(), "step " + step + " 点(" + x + "," + y + ")");
                    if (a != null) {
                        assertEquals(b.localX(), a.localX(), 1e-6);
                        assertEquals(b.localY(), a.localY(), 1e-6);
                    }
                }
            }
            assertTrue(reused > (steps - 11) * 0.8, "索引が使い回されていない: " + reused + "/" + (steps - 11));
            assertTrue(view.ui().renderCache() instanceof ImmutableItemList, "スクロール中の表示リストは移動量だけを載せたスナップショット");
        } finally {
            view.close();
        }
    }

    @Test
    void scrollKeepsImmutableItemsAndUpdatesOnlyExternalOffsets() {
        StandaloneView view = new StandaloneView(html(), W, H, FontResolver.FALLBACK);
        try (var backend = new MutationScenario.NoDraw()) {
            view.frame(1000, backend, true);
            String sid = backend.sessionId();
            view.mouseMove(60, 100, sid);
            List<DisplayItem> before = view.ui().renderCache();
            PaintOffsets beforeOff = view.ui().paintOffsets().snapshot();
            int verBefore = view.ui().paintOffsets().version();
            view.scroll(0, -2, sid);
            view.frame(1016, backend, true);
            List<DisplayItem> after = view.ui().renderCache();
            PaintOffsets afterOff = view.ui().paintOffsets();
            assertSame(before, after, "スクロールだけで不変表示リストを作り直してはならない");
            assertTrue(afterOff.version() > verBefore, "移動量表の版が進む");
            int changed = 0;
            for (int i = 0; i < before.size(); i++) {
                DisplayItem it = after.get(i);
                assertSame(before.get(i), it, "項目も同一インスタンス: " + it.id());
                if (it.type() == ElementType.NODE
                        && (beforeOff.dx(it) != afterOff.dx(it) || beforeOff.dy(it) != afterOff.dy(it))) {
                    changed++;
                    assertTrue(it.id().endsWith("_scrollc") || it.id().endsWith("_scrollv"),
                            "動いたのはスクロールの節だけのはず: " + it.id());
                }
            }
            assertTrue(changed >= 1 && changed <= 4, "動くのは少数: " + changed);
        } finally {
            view.close();
        }
    }
}
