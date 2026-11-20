package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.core.css.model.AnimationSpec;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.ImageFitMode;
import com.nishiyu.enderframelib.core.paint.model.RenderHints;
import com.nishiyu.enderframelib.core.paint.model.RenderMeta;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 箱の項目の検証（C0 ②）の正規形: パラメータ化された値は比べず、箱の入力で決まる値の違いは赤にする。 */
class BoxMemoVerifierTest {
    private static RenderHints hints(String parentId, long parentKey, float skew) {
        return new RenderHints(parentId, parentKey, null, false, 0f, 0f, 0f, null, null, false, 0, null, null, null, skew, false, null);
    }

    private static DisplayItem item(String id, int x, int y, int opacityPct, RenderHints h) {
        return new DisplayItem(id, id, ElementType.RECT, x, y, 10, 10, "", 0, 0, Map.of(), 0, opacityPct / 100f,
                0, 0, 1, 1, 0, 0, AnimationSpec.NONE, ImageFitMode.FILL, RenderMeta.EMPTY.withHints(h));
    }

    private static final RenderHints H = hints("p", 7L, 0f);

    @Test
    void parameterizedCellValuesAndFloatNoiseAreIgnored() {
        List<DisplayItem> a = List.of(item("a", 1, 2, 100, H));
        // 位置・不透明度（cell）は、スクロール・補間・差分パッチでその場で書き換わる
        assertNull(BoxMemoVerifier.firstDifference(a, List.of(item("a", 50, 60, 40, H))));
        // 浮動小数の 1 ULP の揺れ
        assertNull(BoxMemoVerifier.firstDifference(List.of(item("a", 1, 2, 100, hints("p", 7L, 0f))),
                List.of(item("a", 1, 2, 100, hints("p", 7L, 0.0004f)))));
    }

    @Test
    void differencesInTheBoxInputsAreCaught() {
        List<DisplayItem> a = List.of(item("a", 1, 2, 100, H));
        assertNotNull(BoxMemoVerifier.firstDifference(a, List.of(item("b", 1, 2, 100, H))), "id");
        assertNotNull(BoxMemoVerifier.firstDifference(a, List.of(item("a", 1, 2, 100, hints("q", 7L, 0f)))), "parentId");
        assertNotNull(BoxMemoVerifier.firstDifference(a, List.of(item("a", 1, 2, 100, hints("p", 8L, 0f)))), "parentKey");
        assertNotNull(BoxMemoVerifier.firstDifference(a, List.of(item("a", 1, 2, 100, hints("p", 7L, 0.5f)))), "skew（粗い違い）");
        String count = BoxMemoVerifier.firstDifference(a, List.of());
        assertTrue(count != null && count.contains("個数"), "個数: " + count);
    }
}
