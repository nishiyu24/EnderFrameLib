package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.paint.ImmutableItemList;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.PaintOffsets;
import com.nishiyu.enderframelib.core.paint.scene.DisplayScene;
import com.nishiyu.enderframelib.core.paint.scene.SceneHit;
import com.nishiyu.enderframelib.core.paint.scene.SceneHitTest;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * スクロールのたびに {@link DisplayScene} と表示リストを作り直さず（移動量は外部表 {@link PaintOffsets} だけに持つ）、
 * {@link SceneHitTest} が同一不変シーンのまま線形走査と全点で一致することを確かめる。
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
    void sceneRemainsImmutableAndCulledHitTestMatchesLinearAcrossScrolls() {
        StandaloneView view = new StandaloneView(html(), W, H, FontResolver.FALLBACK);
        try (var backend = new MutationScenario.NoDraw()) {
            long now = 1000;
            view.frame(now, backend, true);
            DisplayScene initialScene = view.ui().scene();
            String sid = backend.sessionId();
            Random rnd = new Random(5);
            for (int step = 0; step < 120; step++) {
                view.mouseMove(rnd.nextBoolean() ? 60 : 250, rnd.nextInt(H), sid);
                view.scroll(0, rnd.nextInt(7) - 3, sid);
                now += 16;
                view.frame(now, backend, true);
                assertSame(initialScene, view.ui().scene(), "スクロール中に DisplayScene を作り直さない step " + step);
                SceneHitTest.Context ctx = view.ui().hitTestContext();
                for (int i = 0; i < 160; i++) {
                    double x = rnd.nextDouble() * W, y = rnd.nextDouble() * H;
                    SceneHit a = SceneHitTest.hitTest(initialScene, ctx, x, y);
                    SceneHit b = SceneHitTest.hitTestLinear(initialScene, ctx, x, y);
                    assertEquals(b == null ? "-" : b.baseId(), a == null ? "-" : a.baseId(), "step " + step + " 点(" + x + "," + y + ")");
                    assertEquals(b == null ? null : b.scrollbarPart(), a == null ? null : a.scrollbarPart());
                    if (a != null) {
                        assertEquals(b.localX(), a.localX(), 1e-6);
                        assertEquals(b.localY(), a.localY(), 1e-6);
                    }
                }
            }
            assertTrue(view.ui().relativeElements() instanceof ImmutableItemList, "スクロール中の表示リストは移動量だけを載せたスナップショット");
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
            List<DisplayItem> before = view.ui().relativeElements();
            PaintOffsets beforeOff = view.ui().paintOffsets().snapshot();
            int verBefore = view.ui().paintOffsets().version();
            view.scroll(0, -2, sid);
            view.frame(1016, backend, true);
            List<DisplayItem> after = view.ui().relativeElements();
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
