package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.EngineContext;
import com.nishiyu.enderframelib.core.paint.AbsoluteItems;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.List;
import org.junit.jupiter.api.Test;

/** MOD 側の描画経路は {@code relativeElements()} を親相対のまま GPU へ渡し、従来の経路へ落ちるときだけ絶対座標へ直す。 */
class RelativeElementsTest {
    private static final String HTML = "<html><body style='margin:0'><div style='margin:20px 30px;padding:10px;background:#123'>"
            + "<div style='margin:5px;padding:7px;background:#456'>inner <b>text</b></div></div></body></html>";

    @Test
    void relativeElementsAreRelativeAndElementsAreTheAbsoluteOfThem() {
        try (StandaloneView v = new StandaloneView(HTML, 400, 300, FontResolver.FALLBACK);
             SoftwareRenderBackend sw = new SoftwareRenderBackend(400, 300, 1f)) {
            v.frame(0, sw, true);
            List<DisplayItem> rel = v.ui().relativeElements();
            List<DisplayItem> abs = v.ui().elements();
            assertTrue(rel.stream().anyMatch(i -> i.type() == ElementType.NODE && i.hints().relativeOrigin()), "親相対の NODE がある");
            assertTrue(abs.stream().noneMatch(i -> i.hints().relativeOrigin()), "絶対座標のリストに相対の印は無い");
            assertEquals(AbsoluteItems.of(rel), abs);
            // 一番内側の文字は、親相対では原点付近・絶対では箱の位置（20+10+5+7 だけずれる）
            DisplayItem relText = rel.stream().filter(i -> i.type() == ElementType.TEXT && "inner".equals(i.text().trim())).findFirst().orElseThrow();
            DisplayItem absText = abs.stream().filter(i -> i.id().equals(relText.id())).findFirst().orElseThrow();
            assertTrue(absText.x() > relText.x() && absText.y() > relText.y(), "絶対座標は相対より親の原点だけ大きい");
        }
    }

    /** 画面のスロット・レシピビューアは NODE だけを絶対位置で要る。一覧全体を直さずに、その NODE だけを解ける。 */
    @Test
    void singleItemsResolveWithoutConvertingTheWholeList() {
        try (StandaloneView v = new StandaloneView(HTML, 400, 300, FontResolver.FALLBACK);
             SoftwareRenderBackend sw = new SoftwareRenderBackend(400, 300, 1f)) {
            v.frame(0, sw, true);
            long before = AbsoluteItems.CONVERSIONS.sum();
            List<DisplayItem> rel = v.ui().relativeElements();
            int checked = 0;
            for (DisplayItem it : rel) {
                if (it.type() != ElementType.NODE) continue;
                DisplayItem viaIndex = v.ui().toAbsolute(it);
                DisplayItem viaId = v.ui().nodeItemAbsolute(it.id());
                assertEquals(viaIndex, viaId, it.id());
                checked++;
            }
            assertTrue(checked > 3);
            assertEquals(before, AbsoluteItems.CONVERSIONS.sum(), "NODE を 1 つずつ解いても、一覧全体の絶対化は起きない");
            List<DisplayItem> abs = v.ui().elements();
            assertEquals(before + 1, AbsoluteItems.CONVERSIONS.sum(), "elements() は 1 回だけ直す");
            for (DisplayItem it : rel) {
                if (it.type() != ElementType.NODE) continue;
                DisplayItem a = abs.stream().filter(x -> x.id().equals(it.id())).findFirst().orElseThrow();
                assertEquals(a.x(), v.ui().nodeItemAbsolute(it.id()).x(), it.id());
                assertEquals(a.y(), v.ui().nodeItemAbsolute(it.id()).y(), it.id());
            }
        }
    }
}
