package com.nishiyu.enderframelib.core.paint.software;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 段階 1b: 保持層への記録中の画面外判定（{@link SoftwareRenderBackend#outsideBoxFor}）の境界を固定する。 */
class RecordingCullBoundsTest {
    private static boolean out(double top, double bottom, double left, double right, boolean sideways) {
        // 800x600 の層、項目の高さ 20 → 余白 128 + 20
        return SoftwareRenderBackend.outsideBoxFor(top, bottom, left, right, 148, sideways, 0, 0, 800, 600);
    }

    @Test
    void itemsFarBelowOrAboveAreCulled() {
        assertTrue(out(5000, 5020, 10, 100, false));
        assertTrue(out(-5000, -4980, 10, 100, false));
    }

    @Test
    void itemsInsideOrWithinPaddingAreKept() {
        assertFalse(out(100, 120, 10, 100, false));
        assertFalse(out(700, 720, 10, 100, false), "余白内（文字・影のはみ出し）は残す");
        assertFalse(out(-100, -80, 10, 100, false));
    }

    @Test
    void sidewaysOverflowingTextIsNeverCulledHorizontally() {
        assertTrue(out(100, 120, 5000, 5100, false));
        assertFalse(out(100, 120, 5000, 5100, true), "文字は横へあふれうる");
    }
}
