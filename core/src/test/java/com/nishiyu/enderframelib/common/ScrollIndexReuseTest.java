package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.paint.HitTestIndex;
import com.nishiyu.enderframelib.core.paint.ImmutableItemList;
import com.nishiyu.enderframelib.core.paint.ItemDiff;
import com.nishiyu.enderframelib.core.paint.UIHitResult;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * スクロールのたびに項目を作り直さず（移動量は項目のセルへ書く。{@link ImmutableItemList#touched}）、ヒットテストの索引も作り直さず使い回す（{@link HitTestIndex#patch}）ことが、
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
                HitTestIndex fresh = new HitTestIndex(items, idx.dynamicIds());
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
    void scrollWritesOffsetsIntoAFewSameItems() {
        StandaloneView view = new StandaloneView(html(), W, H, FontResolver.FALLBACK);
        try (var backend = new MutationScenario.NoDraw()) {
            view.frame(1000, backend, true);
            String sid = backend.sessionId();
            view.mouseMove(60, 100, sid);
            List<DisplayItem> same = List.copyOf(view.ui().renderCache()); // 同じ項目への参照
            List<DisplayItem> before = new java.util.ArrayList<>();
            for (DisplayItem it : same) before.add(it.frozen()); // 値の控え
            Object token = ItemDiff.contentTokenOf(view.ui().renderCache());
            view.scroll(0, -2, sid);
            view.frame(1016, backend, true);
            List<DisplayItem> after = view.ui().renderCache();
            assertEquals(before.size(), after.size());
            assertSame(token, ItemDiff.contentTokenOf(after), "スクロールだけで中身の印が変わった（保持層などが無効になる）");
            int changed = 0;
            for (int i = 0; i < before.size(); i++) {
                assertSame(same.get(i), after.get(i), "項目は作り直さない: " + after.get(i).id());
                if (before.get(i).translateX() != after.get(i).translateX() || before.get(i).translateY() != after.get(i).translateY()) {
                    changed++;
                    assertTrue(after.get(i).id().endsWith("_scrollc") || after.get(i).id().endsWith("_scrollv"),
                            "動いたのはスクロールの節だけのはず: " + after.get(i).id());
                    assertEquals(before.get(i).key(), after.get(i).key());
                    assertEquals(before.get(i).baseTranslateY(), after.get(i).baseTranslateY(), "基準の移動量は変えない");
                }
            }
            assertTrue(changed >= 1 && changed <= 4, "動くのは少数: " + changed);
            // 動いた位置は時計の印で分かる（スナップショットにブロックの複製や位置の表は載せない）
            var clock = ((ImmutableItemList) after).clock();
            int marked = 0;
            for (int c = 0; c * 32 < after.size(); c++) if (clock.chunkEpoch(c) > 0) marked++;
            assertTrue(marked >= 1, "動いた項目のブロックに時計の印が付いていない");
        } finally {
            view.close();
        }
    }
}
