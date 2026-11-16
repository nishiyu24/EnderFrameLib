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
        boolean prev = EngineContext.current().parentRelativeDisplayItems;
        EngineContext.current().parentRelativeDisplayItems = true;
        try (StandaloneView v = new StandaloneView(HTML, 400, 300, FontResolver.FALLBACK);
             SoftwareRenderBackend sw = new SoftwareRenderBackend(400, 300, 1f)) {
            v.frame(0, sw, true);
            List<DisplayItem> rel = v.ui().relativeElements();
            List<DisplayItem> abs = v.ui().elements();
            assertTrue(rel.stream().anyMatch(i -> i.type() == ElementType.NODE && i.hints().relativeOrigin()), "親相対の NODE がある");
            assertTrue(abs.stream().noneMatch(i -> i.hints().relativeOrigin()), "絶対座標のリストに相対の印は無い");
            assertEquals(AbsoluteItems.of(rel), abs);
            // 一番内側の文字は、親相対では原点付近・絶対では箱の位置（20+10+5+7 だけずれる）
            DisplayItem relText = rel.stream().filter(i -> i.type() == ElementType.TEXT && "inner ".equals(i.text())).findFirst().orElseThrow();
            DisplayItem absText = abs.stream().filter(i -> i.id().equals(relText.id())).findFirst().orElseThrow();
            assertTrue(absText.x() > relText.x() && absText.y() > relText.y(), "絶対座標は相対より親の原点だけ大きい");
        } finally {
            EngineContext.current().parentRelativeDisplayItems = prev;
        }
    }
}
