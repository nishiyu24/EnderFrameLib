package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.paint.scene.DisplayScene;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

/**
 * {@link UiView#setPaintOffset} / {@link UiView#clearPaintOffset} の更新が {@link DisplayScene} や表示リストを
 * 作り直さずに直接の当たり判定へ反映されることを検証する（M3b-3）。
 */
class PaintOffsetHitTestCacheTest {

    @Test
    void paintOffsetUpdatesHitPositionDirectlyWithoutRebuildingScene() {
        String html = "<style>body{margin:0}#b{position:absolute;left:10px;top:10px;width:50px;height:50px}"
                + "#c{position:absolute;left:10px;top:200px;width:40px;height:40px}</style>"
                + "<div id=\"b\"><div id=\"kb\" style=\"width:50px;height:50px;background:#c33\" onclick=\"x()\"></div></div>"
                + "<div id=\"c\"><div id=\"kc\" style=\"width:40px;height:40px;background:#33c\" onclick=\"x()\"></div></div>";
        StandaloneView view = new StandaloneView(html, 320, 320, FontResolver.FALLBACK);
        try (var backend = new MutationScenario.NoDraw()) {
            long now = 1000;
            view.frame(now, backend, true);
            DisplayScene scene0 = view.ui().scene();
            assertEquals("kb", view.ui().hitTest(20, 20).baseId());

            view.ui().setPaintOffset("b", 100, 0);
            assertSame(scene0, view.ui().scene(), "平行移動だけではシーンを作り直さない");

            for (int i = 1; i <= 5; i++) {
                view.ui().setPaintOffset("b", 100 + i * 10, i * 5);
                float expectX = 100 + i * 10;
                float expectY = i * 5;
                assertEquals(expectX, view.ui().paintOffsets().dx("b"), "外部表が最新の移動量を持つ");
                assertEquals("kb", view.ui().hitTest(10 + expectX + 10, 10 + expectY + 10).baseId(),
                        "最新の移動量位置で直接ヒットする");
                assertNull(view.ui().hitTest(20, 20), "元の位置は移動後に外れる");
            }

            view.ui().setPaintOffset("c", 0, -150);
            assertEquals("kc", view.ui().hitTest(20, 60).baseId());

            view.ui().clearPaintOffset("b");
            assertEquals("kb", view.ui().hitTest(20, 20).baseId(), "解除後は元の位置で当たる");
            view.ui().clearPaintOffset("c");
            assertEquals(0f, view.ui().paintOffsets().dx("b"));
            assertEquals("kc", view.ui().hitTest(20, 210).baseId());
        } finally {
            view.close();
        }
    }
}
