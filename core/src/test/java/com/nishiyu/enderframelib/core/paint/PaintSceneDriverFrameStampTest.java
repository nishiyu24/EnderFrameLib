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

/** フレームごとの姿勢の表の消去をフレーム印に替えても、前フレームの姿勢が漏れない。 */
class PaintSceneDriverFrameStampTest {

    private static final class PoseRec implements PaintSceneDriver.Painter {
        final PaintSceneDriver driver;
        final List<String> log = new ArrayList<>();
        PoseRec(PaintSceneDriver d) { driver = d; }
        @Override public void syncLayers(List<PaintSceneDriver.Group> want) {}
        @Override public void drawItem(DisplayItem el, int index, float flat) {
            Matrix2D m = driver.resolveAt(index).pose();
            log.add(index + ":" + driver.resolveAt(index).opacity() + "/" + m.a() + "," + m.d() + "," + m.e() + "," + m.f() + "@" + flat);
        }
    }

    private static List<DisplayItem> items(int rows) {
        StringBuilder sb = new StringBuilder("<style>body{margin:0}.row{height:20px;margin:2px;background:#eef}</style>");
        for (int i = 0; i < rows; i++) sb.append("<div class=\"row\" style=\"opacity:.").append(1 + i % 9).append("\">r").append(i).append("</div>");
        StandaloneView v = new StandaloneView(sb.toString(), 320, 240, FontResolver.FALLBACK);
        try { return List.copyOf(v.items()); } finally { v.close(); }
    }

    @Test
    void reusedDriverMatchesAFreshDriverAcrossRepeatedAndSwappedLists() {
        List<List<DisplayItem>> lists = List.of(items(30), items(7), items(45));
        Matrix2D frame = Matrix2D.scale(1.5, 1.5); // フレーム変換は一定（静止した姿勢の再利用の前提）
        PaintSceneDriver shared = new PaintSceneDriver("stamp-shared");
        Random r = new Random(5);
        for (int step = 0; step < 60; step++) {
            List<DisplayItem> list = lists.get(r.nextInt(lists.size()));
            PoseRec a = new PoseRec(shared);
            shared.run(list, 2000 + step, frame, a);
            PaintSceneDriver fresh = new PaintSceneDriver("stamp-fresh-" + step);
            PoseRec b = new PoseRec(fresh);
            fresh.run(list, 2000 + step, frame, b);
            assertFalse(a.log.isEmpty());
            assertEquals(b.log, a.log, "step " + step);
        }
    }
}
