package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** {@code run} の作業表（chains / members / plan）の使い回しが、毎回新しいドライバと同じ結果になる。 */
class PaintSceneDriverScratchReuseTest {

    private static final class Rec implements PaintSceneDriver.Painter {
        final List<String> log = new ArrayList<>();
        RetainPlan decision = RetainPlan.NONE;
        @Override public void syncLayers(List<PaintSceneDriver.Group> want) { log.add("sync" + want.size()); }
        @Override public void drawItem(DisplayItem el, int index, float flat) { log.add("d" + index + "@" + flat); }
        @Override public RetainPlan planRetained(DisplayItem n, int i, int f, int t) { return decision; }
        @Override public void drawRetained(DisplayItem n, int i, int f, int t) { log.add("R" + f + "-" + t); }
        @Override public void beginRetained(DisplayItem n, int i, int f, int t) { log.add("B" + f); }
        @Override public boolean endRetained(DisplayItem n, int i) { log.add("E"); return true; }
    }

    private static List<DisplayItem> items(int rows, boolean group) {
        StringBuilder sb = new StringBuilder("<style>body{margin:0;font-size:13px}.row{height:24px;margin:2px;background:#eef}"
                + "#outer{position:absolute;left:0;top:0;width:190px;height:230px;overflow-y:auto}#wrap{opacity:.5;position:relative;width:320px;height:240px}</style>");
        if (group) sb.append("<div id=\"wrap\"><div id=\"a\" style=\"opacity:.7\"><p>x</p><p>y</p></div>");
        sb.append("<div id=\"outer\">");
        for (int i = 0; i < rows; i++) sb.append("<div class=\"row\">row ").append(i).append("</div>");
        sb.append("</div>");
        if (group) sb.append("</div>");
        StandaloneView v = new StandaloneView(sb.toString(), 320, 240, FontResolver.FALLBACK);
        try { return List.copyOf(v.items()); } finally { v.close(); }
    }

    @Test
    void oneDriverReusedAcrossListsOfDifferentSizesMatchesFreshDrivers() {
        RetainPlan[] plans = RetainPlan.values();
        List<List<DisplayItem>> lists = List.of(items(40, false), items(5, false), items(60, true), items(12, true), items(40, false));
        PaintSceneDriver shared = new PaintSceneDriver("scratch-shared");
        Random r = new Random(11);
        for (int step = 0; step < 60; step++) {
            List<DisplayItem> list = lists.get(r.nextInt(lists.size()));
            RetainPlan d = plans[r.nextInt(plans.length)];
            Rec a = new Rec(), b = new Rec();
            a.decision = d; b.decision = d;
            shared.run(list, 1000 + step, Matrix2D.IDENTITY, a);
            new PaintSceneDriver("scratch-fresh-" + step).run(list, 1000 + step, Matrix2D.IDENTITY, b);
            assertEquals(b.log, a.log, "step " + step + " plan " + d);
        }
    }
}
