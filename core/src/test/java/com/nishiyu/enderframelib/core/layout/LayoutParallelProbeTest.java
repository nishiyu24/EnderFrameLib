package com.nishiyu.enderframelib.core.layout;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.SoftwareRendering;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

/** B2 の計測器: 兄弟が多い親の子（配り先がある所）だけを「独立な部分木」と数え、少なければ 0。既定は無効。 */
class LayoutParallelProbeTest {
    private static String rows(int n) {
        StringBuilder sb = new StringBuilder("<html><body style='margin:0'><div id='list'>");
        for (int i = 0; i < n; i++) sb.append("<div style='padding:2px;border-bottom:1px solid #ccc'>row ").append(i).append(" with some text</div>");
        return sb.append("</div></body></html>").toString();
    }

    @Test
    void manySiblingsAreCountedAsIndependentUnits() {
        boolean was = LayoutParallelProbe.enabled();
        try {
            LayoutParallelProbe.enable(true);
            LayoutParallelProbe.reset();
            SoftwareRendering.displayList(rows(40), 400, 300, FontResolver.FALLBACK);
            assertTrue(LayoutParallelProbe.independentBoxes(LayoutParallelProbe.MEASURE) >= 40, "行ごとに 1 単位");
            assertTrue(LayoutParallelProbe.independentBoxes(LayoutParallelProbe.PLACE) >= 40);
            double share = LayoutParallelProbe.independentShare(LayoutParallelProbe.MEASURE);
            assertTrue(share > 0.3 && share <= 1.0, "測定の時間の大半は行の中: " + share);
        } finally {
            LayoutParallelProbe.enable(was);
        }
    }

    @Test
    void fewSiblingsHaveNothingToDistribute() {
        boolean was = LayoutParallelProbe.enabled();
        try {
            LayoutParallelProbe.enable(true);
            LayoutParallelProbe.reset();
            SoftwareRendering.displayList(rows(3), 400, 300, FontResolver.FALLBACK);
            assertEquals(0, LayoutParallelProbe.independentBoxes(LayoutParallelProbe.MEASURE));
            assertEquals(0.0, LayoutParallelProbe.independentShare(LayoutParallelProbe.MEASURE));
        } finally {
            LayoutParallelProbe.enable(was);
        }
    }

    @Test
    void floatsAndPositionedBoxesAreNotIndependent() {
        boolean was = LayoutParallelProbe.enabled();
        try {
            LayoutParallelProbe.enable(true);
            LayoutParallelProbe.reset();
            StringBuilder sb = new StringBuilder("<html><body style='margin:0'><div>");
            for (int i = 0; i < 20; i++) sb.append("<div style='float:left;width:10px;height:10px'></div>");
            SoftwareRendering.displayList(sb.append("</div></body></html>").toString(), 400, 300, FontResolver.FALLBACK);
            assertEquals(0, LayoutParallelProbe.independentBoxes(LayoutParallelProbe.MEASURE), "float は兄弟と干渉する");
        } finally {
            LayoutParallelProbe.enable(was);
        }
    }

    @Test
    void disabledByDefaultCountsNothing() {
        if (LayoutParallelProbe.enabled()) return; // -Denderframe.layout.probe=true で走らせたときは対象外
        LayoutParallelProbe.reset();
        SoftwareRendering.displayList(rows(40), 400, 300, FontResolver.FALLBACK);
        assertEquals(0, LayoutParallelProbe.totalNs(LayoutParallelProbe.MEASURE));
    }
}
