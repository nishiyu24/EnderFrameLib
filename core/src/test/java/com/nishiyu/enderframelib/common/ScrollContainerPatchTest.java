package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.paint.HitTestIndex;
import com.nishiyu.enderframelib.core.paint.UIHitResult;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** 入れ子のスクロール箱でも、patch した索引の {@code scrollContainersAt} / {@code hitTest} が新しく作った索引と一致する。 */
class ScrollContainerPatchTest {
    private static final int W = 320, H = 240;

    private static String html() {
        StringBuilder inner = new StringBuilder();
        for (int i = 0; i < 40; i++) inner.append("<div class=\"row\" id=\"i").append(i).append("\" onclick=\"x()\">inner ").append(i).append("</div>");
        StringBuilder outer = new StringBuilder("<div id=\"head\" style=\"height:30px\">h</div><div id=\"nested\">").append(inner).append("</div>");
        for (int i = 0; i < 40; i++) outer.append("<div class=\"row\" id=\"o").append(i).append("\" onclick=\"x()\">outer ").append(i).append("</div>");
        return "<style>body{margin:0;font-size:13px}.row{height:24px;margin:2px;padding:2px 6px;background:#eef}"
                + "#outer{position:absolute;left:0;top:0;width:250px;height:230px;overflow-y:auto}"
                + "#nested{height:100px;overflow-y:auto;border:1px solid #333}</style><div id=\"outer\">" + outer + "</div>";
    }

    private static String ids(List<UIHitResult> l) {
        StringBuilder sb = new StringBuilder();
        for (UIHitResult r : l) sb.append(r.element().id()).append('@').append(Math.round(r.localX() * 100)).append(',').append(Math.round(r.localY() * 100)).append(';');
        return sb.toString();
    }

    @Test
    void patchedIndexMatchesAFreshOneForNestedScrollContainers() {
        StandaloneView view = new StandaloneView(html(), W, H, FontResolver.FALLBACK);
        try (var backend = new MutationScenario.NoDraw()) {
            long now = 1000;
            view.frame(now, backend, true);
            String sid = backend.sessionId();
            Random rnd = new Random(8);
            HitTestIndex previous = null;
            int reused = 0;
            for (int step = 0; step < 100; step++) {
                double mx = 20 + rnd.nextInt(200), my = 40 + rnd.nextInt(150);
                view.mouseMove(mx, my, sid);
                view.scroll(0, rnd.nextInt(9) - 4, sid);
                now += 16;
                view.frame(now, backend, true);
                HitTestIndex idx = view.ui().hitTestIndex();
                if (step > 5 && idx == previous) reused++;
                previous = idx;
                HitTestIndex fresh = new HitTestIndex(view.ui().renderCache(), idx.dynamicIds());
                for (int i = 0; i < 60; i++) {
                    double x = rnd.nextDouble() * W, y = rnd.nextDouble() * H;
                    assertEquals(ids(fresh.scrollContainersAt(sid, x, y)), ids(idx.scrollContainersAt(sid, x, y)), "step " + step + " (" + x + "," + y + ")");
                    UIHitResult a = idx.hitTest(sid, x, y), b = fresh.hitTest(sid, x, y);
                    assertEquals(b == null ? "-" : b.element().id(), a == null ? "-" : a.element().id());
                }
            }
            assertTrue(reused > 50, "索引が使い回されている: " + reused);
        } finally {
            view.close();
        }
    }
}
