package com.nishiyu.enderframelib.core.paint.gl;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.paint.DisplayListBuilder;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.List;
import org.junit.jupiter.api.Test;

/** B9 M3: hover の塗りの変化は項目を作り直さずセルへ書かれ、それでも描画（差分の損傷範囲）は新規描画と画素一致する。 */
class PaintInPlaceTest {
    private static final String HTML = "<html><head><style>ul{list-style:none;padding:0}li{padding:6px;border-bottom:1px solid #ccc;background:#fff}"
            + "li:hover{background:#cde}</style></head><body><ul>"
            + "<li id=a>one</li><li id=b>two</li><li id=c>three</li><li id=d>four</li></ul></body></html>";

    private static Pixmap fresh(String hoverId) {
        try (StandaloneView v = new StandaloneView(HTML, 400, 300, FontResolver.FALLBACK);
             SoftwareRenderBackend sw = new SoftwareRenderBackend(400, 300, 1f)) {
            v.frame(1000, sw, true);
            if (hoverId != null) v.ui().setHoveredElement(v.root().getNodeById(hoverId).key());
            v.frame(1016, sw, true);
            v.frame(1032, sw, true);
            return sw.pixmap().copy();
        }
    }

    @Test
    void hoverWritesPaintIntoTheSameItemAndPixelsMatchAFreshRender() {
        long before = DisplayListBuilder.STAT_REPAINT_IN_PLACE.sum();
        try (StandaloneView v = new StandaloneView(HTML, 400, 300, FontResolver.FALLBACK);
             SoftwareRenderBackend sw = new SoftwareRenderBackend(400, 300, 1f)) {
            v.frame(1000, sw, true);
            List<DisplayItem> first = List.copyOf(v.items());
            String prevHover = null;
            for (String id : new String[]{"a", "b", "c", null, "d", "a"}) {
                v.ui().setHoveredElement(id == null ? null : v.root().getNodeById(id).key());
                v.frame(1016, sw, true);
                v.frame(1032, sw, true); // 色の遷移（無ければ即時）が落ち着くまで
                v.frame(5000, sw, true);
                Pixmap fresh = fresh(id);
                assertArrayEquals(fresh.pixels(), sw.pixmap().pixels(), "hover=" + id + " の画素が新規描画と違う");
            }
            // 項目の数と並びは変わらず、塗り以外は同じ項目のまま
            List<DisplayItem> last = v.items();
            assertEquals(first.size(), last.size());
        }
        assertTrue(DisplayListBuilder.STAT_REPAINT_IN_PLACE.sum() > before, "塗りの書き込みが一度も使われていない（経路が働いていない）");
    }

    @Test
    void touchedItemIsDetectedByTheNextSnapshotAndNotByAnIdenticalOne() {
        try (StandaloneView v = new StandaloneView(HTML, 400, 300, FontResolver.FALLBACK);
             SoftwareRenderBackend sw = new SoftwareRenderBackend(400, 300, 1f)) {
            v.frame(1000, sw, true);
            var s1 = v.ui().renderCache();
            int epoch1 = ((com.nishiyu.enderframelib.core.paint.ImmutableItemList) s1).epoch();
            v.ui().setHoveredElement(v.root().getNodeById("b").key());
            v.frame(1016, sw, true);
            v.frame(5000, sw, true);
            var s2 = v.ui().renderCache();
            assertNotSame(s1, s2, "書き込みがあったのに同じスナップショットのまま（変化が見えない）");
            assertTrue(((com.nishiyu.enderframelib.core.paint.ImmutableItemList) s2).epoch() > epoch1);
            int changedItems = 0;
            for (int i = 0; i < s2.size(); i++) {
                if (com.nishiyu.enderframelib.core.paint.ItemDiff.changed(s1.get(i), s2.get(i), epoch1)) changedItems++;
            }
            assertTrue(changedItems > 0, "変わった項目が検出されない");
            v.frame(5016, sw, true);
            var s3 = v.ui().renderCache();
            int epoch2 = ((com.nishiyu.enderframelib.core.paint.ImmutableItemList) s2).epoch();
            for (int i = 0; i < s3.size(); i++) {
                assertFalse(com.nishiyu.enderframelib.core.paint.ItemDiff.changed(s2.get(i), s3.get(i), epoch2), "何も変えていないのに変わったと検出された: " + s3.get(i).id());
            }
        }
    }

    /**
     * 塗りをセルへ書いた項目は、別の経路（全面の再構築・箱の記憶の再利用）に混ざっても古い色を出さない。
     * hover → 戻す → 全面の再構築、hover したまま全面の再構築、のどちらも新規描画と画素一致であること。
     */
    @Test
    void fullRebuildAfterInPlacePaintMatchesAFreshRender() {
        for (boolean endHovered : new boolean[]{false, true}) {
            try (StandaloneView v = new StandaloneView(HTML, 400, 300, FontResolver.FALLBACK);
                 SoftwareRenderBackend sw = new SoftwareRenderBackend(400, 300, 1f)) {
                long t = 1000;
                v.frame(t, sw, true);
                for (String id : new String[]{"a", "b", "c"}) {
                    v.ui().setHoveredElement(v.root().getNodeById(id).key());
                    v.frame(t += 16, sw, true);
                }
                if (!endHovered) {
                    v.ui().setHoveredElement(null);
                    v.frame(t += 16, sw, true);
                }
                v.invalidate(); // 全面の再構築（箱の記憶を使う）
                v.frame(t += 500, sw, true);
                v.frame(t += 500, sw, true);
                v.resize(399, 300); // 幅が変わる再構築
                for (int k = 0; k < 6; k++) { v.frame(t += 500, sw, true); try { Thread.sleep(30); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
                v.resize(400, 300);
                for (int k = 0; k < 6; k++) { v.frame(t += 500, sw, true); try { Thread.sleep(30); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
                Pixmap fresh = fresh(endHovered ? "c" : null);
                assertArrayEquals(fresh.pixels(), sw.pixmap().pixels(), "endHovered=" + endHovered + ": 再構築後の画素が新規描画と違う");
            }
        }
    }
}
