package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.paint.HitTestIndex;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

/**
 * 動きうる節の id 集合のキャッシュ: 描画上の移動量の鍵が増減したときだけ集合と索引を作り直し、値の更新だけなら索引を使い回す（patch）。
 * 古い集合を返さない（鍵の追加・解除が必ず索引の動きうる節に反映される）。
 */
class PaintOffsetHitTestCacheTest {

    @Test
    void dynamicSetTracksPaintOffsetKeysAndIsReusedForValueOnlyUpdates() {
        String html = "<style>body{margin:0}#b{position:absolute;left:10px;top:10px;width:50px;height:50px}"
                + "#c{position:absolute;left:10px;top:200px;width:40px;height:40px}</style>"
                + "<div id=\"b\"><div id=\"kb\" style=\"width:50px;height:50px;background:#c33\" onclick=\"x()\"></div></div>"
                + "<div id=\"c\"><div id=\"kc\" style=\"width:40px;height:40px;background:#33c\" onclick=\"x()\"></div></div>";
        StandaloneView view = new StandaloneView(html, 320, 320, FontResolver.FALLBACK);
        try (var backend = new MutationScenario.NoDraw()) {
            long now = 1000;
            view.frame(now, backend, true);
            HitTestIndex i0 = view.ui().hitTestIndex();
            assertFalse(i0.dynamicIds().contains("b"));

            view.ui().setPaintOffset("b", 100, 0);                       // 鍵が増える
            HitTestIndex i1 = view.ui().hitTestIndex();
            assertTrue(i1.dynamicIds().contains("b"), "追加した鍵が動きうる節に入る");
            assertNotSame(i0, i1);

            for (int i = 1; i <= 5; i++) {                               // 値だけの更新
                view.ui().setPaintOffset("b", 100 + i * 10, i * 5);
                HitTestIndex idx = view.ui().hitTestIndex();
                assertSame(i1, idx, "値だけの更新では索引を作り直さない（patch）: " + i);
                assertTrue(idx.dynamicIds().contains("b"));
                float expectX = 100 + i * 10;
                boolean moved = view.ui().renderCache().stream().anyMatch(it -> it.id().equals("b") && it.translateX() == expectX);
                assertTrue(moved, "表示リストの b が最新の移動量を持つ");
            }

            view.ui().setPaintOffset("c", 0, -150);                      // 2 つ目の鍵
            HitTestIndex i2 = view.ui().hitTestIndex();
            assertTrue(i2.dynamicIds().containsAll(java.util.Set.of("b", "c")));

            view.ui().clearPaintOffset("b");                             // 鍵が減る: 古い集合を返さない
            HitTestIndex i3 = view.ui().hitTestIndex();
            assertTrue(i3.dynamicIds().contains("c"));
            view.ui().clearPaintOffset("c");
            HitTestIndex i4 = view.ui().hitTestIndex();
            assertTrue(i4.dynamicIds().containsAll(java.util.Set.of("b", "c")), "動きうる節の集合は上位集合のままでよい（索引は使い回せる）");
            assertEquals(0f, view.ui().renderCache().stream().filter(it -> it.id().equals("b")).findFirst().orElseThrow().translateX());
        } finally {
            view.close();
        }
    }
}
