package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.paint.scene.DisplayScene;
import com.nishiyu.enderframelib.core.paint.scene.SceneHit;
import com.nishiyu.enderframelib.core.paint.scene.SceneHitTest;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** 入れ子のスクロール箱でも、不変 {@link DisplayScene} への {@code scrollContainersAt} / {@code hitTest} が線形走査と一致する。 */
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

    private static String ids(List<SceneHit> l) {
        StringBuilder sb = new StringBuilder();
        for (SceneHit r : l) sb.append(r.baseId()).append('@').append(Math.round(r.localX() * 100)).append(',').append(Math.round(r.localY() * 100)).append(';');
        return sb.toString();
    }

    @Test
    void nestedScrollContainersMatchLinearAndPreserveScene() {
        StandaloneView view = new StandaloneView(html(), W, H, FontResolver.FALLBACK);
        try (var backend = new MutationScenario.NoDraw()) {
            long now = 1000;
            view.frame(now, backend, true);
            DisplayScene scene0 = view.ui().scene();
            String sid = backend.sessionId();
            Random rnd = new Random(8);
            for (int step = 0; step < 100; step++) {
                double mx = 20 + rnd.nextInt(200), my = 40 + rnd.nextInt(150);
                view.mouseMove(mx, my, sid);
                view.scroll(0, rnd.nextInt(9) - 4, sid);
                now += 16;
                view.frame(now, backend, true);
                assertSame(scene0, view.ui().scene(), "入れ子スクロールでも DisplayScene を作り直さない step " + step);
                SceneHitTest.Context ctx = view.ui().hitTestContext();
                List<SceneHit> sc1 = SceneHitTest.scrollContainersAt(scene0, ctx, mx, my);
                List<SceneHit> sc2 = SceneHitTest.scrollContainersAt(scene0, ctx, mx, my);
                assertEquals(ids(sc1), ids(sc2));
                for (int i = 0; i < 60; i++) {
                    double x = rnd.nextDouble() * W, y = rnd.nextDouble() * H;
                    SceneHit a = SceneHitTest.hitTest(scene0, ctx, x, y);
                    SceneHit b = SceneHitTest.hitTestLinear(scene0, ctx, x, y);
                    assertEquals(b == null ? "-" : b.baseId(), a == null ? "-" : a.baseId());
                }
            }
        } finally {
            view.close();
        }
    }
}
