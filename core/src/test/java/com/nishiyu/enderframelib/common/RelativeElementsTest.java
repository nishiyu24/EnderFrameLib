package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ビューの列は親相対のまま公開し、絶対位置は項目・節ごとに解く（M3b-4b-4b で列全体を絶対座標へ直す {@code elements()} と
 * {@code AbsoluteItems} を削除した）。
 */
class RelativeElementsTest {
    private static final String HTML = "<html><body style='margin:0'><div style='margin:20px 30px;padding:10px;background:#123'>"
            + "<div style='margin:5px;padding:7px;background:#456'>inner <b>text</b></div></div></body></html>";

    @Test
    void relativeElementsAreRelativeAndResolvePerItem() {
        try (StandaloneView v = new StandaloneView(HTML, 400, 300, FontResolver.FALLBACK);
             SoftwareRenderBackend sw = new SoftwareRenderBackend(400, 300, 1f)) {
            v.frame(0, sw, true);
            List<DisplayItem> rel = v.ui().relativeElements();
            assertTrue(rel.stream().anyMatch(i -> i.type() == ElementType.NODE && i.hints().relativeOrigin()), "親相対の NODE がある");
            // 一番内側の文字は、親相対では原点付近・絶対では箱の位置（20+10+5+7 だけずれる）
            DisplayItem relText = rel.stream().filter(i -> i.type() == ElementType.TEXT && "inner".equals(i.text().trim())).findFirst().orElseThrow();
            DisplayItem absText = v.ui().toAbsolute(relText);
            assertTrue(absText.x() > relText.x() && absText.y() > relText.y(), "絶対座標は相対より親の原点だけ大きい");
            // 項目ごとの解決は、列全体を平坦にしたもの（テストの補助）と位置が一致する
            List<DisplayItem> flat = TestFlatItems.absolute(rel);
            for (int i = 0; i < rel.size(); i++) {
                DisplayItem a = v.ui().toAbsolute(rel.get(i));
                assertEquals(flat.get(i).x(), a.x(), rel.get(i).id());
                assertEquals(flat.get(i).y(), a.y(), rel.get(i).id());
            }
        }
    }

    /** 画面のスロット・レシピビューアは NODE だけを絶対位置で要る。一覧全体を直さずに、その NODE だけを解ける。 */
    @Test
    void singleNodesResolveByIdAndTheViewHasNoWholeListConversion() {
        try (StandaloneView v = new StandaloneView(HTML, 400, 300, FontResolver.FALLBACK);
             SoftwareRenderBackend sw = new SoftwareRenderBackend(400, 300, 1f)) {
            v.frame(0, sw, true);
            List<DisplayItem> rel = v.ui().relativeElements();
            int checked = 0;
            for (DisplayItem it : rel) {
                if (it.type() != ElementType.NODE) continue;
                assertEquals(v.ui().toAbsolute(it), v.ui().nodeItemAbsolute(it.id()), it.id());
                checked++;
            }
            assertTrue(checked > 3);
            List<String> names = Arrays.stream(UiView.class.getMethods()).map(java.lang.reflect.Method::getName).toList();
            assertFalse(names.contains("elements") || names.contains("absoluteRenderCache"), "列全体を絶対座標へ直す口は戻さない");
        }
    }
}
