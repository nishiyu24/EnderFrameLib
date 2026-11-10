package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 段階 1b ①: 記録する保持層（RECORD）の範囲のうち、描かれないと確実に言える先頭・末尾の区間を走査から外す動きの検証。
 * 外した項目は、描画側の画面外判定（箱の下端 + pad + 高さ &lt; y0 / 上端 − pad − 高さ &gt; y1）に必ず当たること、
 * 当たらない項目は 1 つも外れないこと、範囲の begin / end の呼び出しが保たれることを確かめる。
 */
class PaintSceneDriverRangeCullTest {
    private static final double PAD = 128;
    /** アニメ状態の表はセッション ID ごとに共有されるので、呼び出しごとに別のセッションにする。 */
    private static final java.util.concurrent.atomic.AtomicInteger SEQ = new java.util.concurrent.atomic.AtomicInteger();

    private static String html(int rows, String extraRowStyle) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows; i++) {
            sb.append("<div class=\"row\" id=\"r").append(i).append("\"")
                    .append(i == 200 ? " style=\"" + extraRowStyle + "\"" : "").append(">row ").append(i).append("</div>");
        }
        return "<style>body{margin:0;font-size:13px}.row{height:24px;margin:2px;padding:2px 6px;background:#eef;border:1px solid #99a}"
                + "#outer{position:absolute;left:0;top:0;width:190px;height:230px;overflow-y:auto;border:2px solid #333}</style>"
                + "<div id=\"outer\">" + sb + "</div>";
    }

    private static List<DisplayItem> items(String html) {
        StandaloneView view = new StandaloneView(html, 320, 240, FontResolver.FALLBACK);
        try {
            return List.copyOf(view.items());
        } finally {
            view.close();
        }
    }

    private static final class Rec implements PaintSceneDriver.Painter {
        final List<Integer> drawn = new ArrayList<>();
        final List<String> log = new ArrayList<>();
        double y0, y1;
        boolean endOk = true;
        boolean offerWindow = true;
        int from, to;

        @Override public void syncLayers(List<PaintSceneDriver.Group> want) {}
        @Override public void drawItem(DisplayItem el, int index, float flat) { drawn.add(index); }
        @Override public RetainPlan planRetained(DisplayItem node, int nodeIndex, int f, int t) { from = f; to = t; return RetainPlan.RECORD; }
        @Override public void beginRetained(DisplayItem node, int nodeIndex, int f, int t) { log.add("begin"); }
        @Override public boolean endRetained(DisplayItem node, int nodeIndex) { log.add("end"); return endOk; }
        @Override public double[] cullWindow(DisplayItem node, int nodeIndex) { return offerWindow ? new double[]{y0, y1, PAD} : null; }
    }

    private static boolean offscreen(DisplayItem el, double y0, double y1) {
        double pad = PAD + el.height();
        return el.y() + el.height() + pad < y0 || el.y() - pad > y1;
    }

    private record Result(Rec rec, PaintSceneDriver driver) {}

    private static Result run(List<DisplayItem> items, double y0, double y1, boolean cull, boolean endOk) {
        Rec r = new Rec();
        r.y0 = y0;
        r.y1 = y1;
        r.endOk = endOk;
        PaintSceneDriver d = new PaintSceneDriver("rangecull-" + SEQ.incrementAndGet());
        boolean was = PaintSceneDriver.cullRecordedRanges;
        PaintSceneDriver.cullRecordedRanges = cull;
        try {
            d.run(items, 1000, Matrix2D.IDENTITY, r);
        } finally {
            PaintSceneDriver.cullRecordedRanges = was;
        }
        return new Result(r, d);
    }

    /** 外した項目は全て画面外判定に当たり、判定に当たらない項目は全て残る。 */
    private static void assertExact(List<DisplayItem> items, Result on, Result off, double y0, double y1) {
        Set<Integer> kept = new HashSet<>(on.rec.drawn);
        int dropped = 0;
        for (int i : off.rec.drawn) {
            if (i < off.rec.from || i >= off.rec.to) {
                assertTrue(kept.contains(i), "範囲外の項目は外さない: " + i);
                continue;
            }
            DisplayItem el = items.get(i);
            if (kept.contains(i)) continue;
            dropped++;
            assertNotEquals(ElementType.SCENE, el.type());
            assertTrue(offscreen(el, y0, y1), "画面内の項目を外した: " + i + " y=" + el.y() + " h=" + el.height());
        }
        assertTrue(on.rec.drawn.size() + dropped == off.rec.drawn.size(), "外した以外の項目は同じ順で描かれる");
        List<Integer> expect = new ArrayList<>(off.rec.drawn);
        expect.removeIf(i -> !kept.contains(i));
        assertEquals(expect, on.rec.drawn);
    }

    @Test
    void trailingOffscreenItemsAreSkippedAndBracketsKept() {
        List<DisplayItem> items = items(html(400, ""));
        Result on = run(items, 0, 240, true, true);
        Result off = run(items, 0, 240, false, true);
        assertExact(items, on, off, 0, 240);
        assertTrue(on.driver.rangeCulledItems() > 300, "末尾の画面外が大きく外れる: " + on.driver.rangeCulledItems());
        assertEquals(List.of("begin", "end"), on.rec.log);
        assertEquals(off.rec.log, on.rec.log);
    }

    @Test
    void windowInTheMiddleSkipsBothEnds() {
        List<DisplayItem> items = items(html(400, ""));
        Result on = run(items, 5000, 5240, true, true);
        Result off = run(items, 5000, 5240, false, true);
        assertExact(items, on, off, 5000, 5240);
        int inRange = 0;
        for (int i : on.rec.drawn) if (i >= on.rec.from && i < on.rec.to) inRange++;
        int total = on.rec.to - on.rec.from;
        assertTrue(inRange > 0 && inRange < total / 4, "中ほどだけが残る: " + inRange + " / " + total);
        assertEquals(List.of("begin", "end"), on.rec.log);
    }

    @Test
    void windowBeyondAllContentSkipsTheWholeRangeButStillBrackets() {
        List<DisplayItem> items = items(html(400, ""));
        Result on = run(items, 90000, 90240, true, true);
        Result off = run(items, 90000, 90240, false, true);
        assertExact(items, on, off, 90000, 90240);
        assertEquals(List.of("begin", "end"), on.rec.log, "全部外れても記録の開始と終了は呼ぶ");
        Result above = run(items, -90000, -89760, true, true);
        assertExact(items, above, run(items, -90000, -89760, false, true), -90000, -89760);
        assertEquals(List.of("begin", "end"), above.rec.log);
    }

    @Test
    void windowCoveringEverythingSkipsNothing() {
        List<DisplayItem> items = items(html(60, ""));
        Result on = run(items, -100000, 100000, true, true);
        Result off = run(items, -100000, 100000, false, true);
        assertEquals(off.rec.drawn, on.rec.drawn);
    }

    @Test
    void aTransformedItemInTheRangeDisablesSkipping() {
        List<DisplayItem> items = items(html(400, "transform:translateY(8000px)"));
        Result on = run(items, 0, 240, true, true);
        Result off = run(items, 0, 240, false, true);
        assertEquals(off.rec.drawn, on.rec.drawn, "姿勢が中立でない範囲は全項目を走査する");
        assertEquals(0, on.driver.rangeCulledItems());
    }

    @Test
    void scaledFrameUsesDeviceUnitsForPadAndPosition() {
        List<DisplayItem> items = items(html(400, ""));
        double s = 1.5, y0 = 3000, y1 = 3300;
        Rec r = new Rec();
        r.y0 = y0;
        r.y1 = y1;
        PaintSceneDriver d = new PaintSceneDriver("rangecull-scale-" + SEQ.incrementAndGet());
        d.run(items, 1000, Matrix2D.scale(s, s), r);
        Rec all = new Rec();
        all.offerWindow = false;
        new PaintSceneDriver("rangecull-scale-" + SEQ.incrementAndGet()).run(items, 1000, Matrix2D.scale(s, s), all);
        Set<Integer> kept = new HashSet<>(r.drawn);
        int dropped = 0;
        for (int i : all.drawn) {
            if (kept.contains(i)) continue;
            dropped++;
            DisplayItem el = items.get(i);
            double pad = PAD + el.height();
            double bottom = s * (el.y() + el.height()), top = s * el.y();
            assertTrue(bottom + pad < y0 || top - pad > y1, "デバイス単位で画面内の項目を外した: " + i);
        }
        assertTrue(dropped > 0 && d.rangeCulledItems() > 0);
    }

    @Test
    void staleTransformStateFromAnEarlierDocumentDisablesSkippingUntilItSettles() {
        String session = "rangecull-stale-" + SEQ.incrementAndGet();
        List<DisplayItem> moved = items(html(400, "transform:translateY(8000px)"));
        new PaintSceneDriver(session).run(moved, 1000, Matrix2D.IDENTITY, new Rec());
        // 同じセッションで、変形の無い文書を開き直す: アニメ状態の目標が中立でない項目が残っているので外さない
        List<DisplayItem> plain = items(html(400, ""));
        Rec r = new Rec();
        r.y0 = 0;
        r.y1 = 240;
        PaintSceneDriver d = new PaintSceneDriver(session);
        d.run(plain, 1000, Matrix2D.IDENTITY, r);
        assertEquals(0, d.rangeCulledItems());
        // 全項目を走査したフレームで状態が新しい目標（中立）へ追いつくので、次のフレームからは外せる
        Rec r2 = new Rec();
        r2.y0 = 0;
        r2.y1 = 240;
        d.run(plain, 1000, Matrix2D.IDENTITY, r2);
        assertTrue(d.rangeCulledItems() > 0, "状態が追いついたら外す");
    }

    @Test
    void noWindowOfferedMeansNoSkipping() {
        List<DisplayItem> items = items(html(400, ""));
        Rec r = new Rec();
        r.offerWindow = false;
        new PaintSceneDriver("rangecull-nowin-" + SEQ.incrementAndGet()).run(items, 1000, Matrix2D.IDENTITY, r);
        Result off = run(items, 0, 240, false, true);
        assertEquals(off.rec.drawn, r.drawn);
    }

    @Test
    void failedRecordRedrawsOnlyWhatWasNotSkipped() {
        List<DisplayItem> items = items(html(400, ""));
        Result on = run(items, 0, 240, true, false);
        Result off = run(items, 0, 240, false, false);
        Set<Integer> kept = new HashSet<>(on.rec.drawn);
        for (int i : off.rec.drawn) {
            if (kept.contains(i)) continue;
            DisplayItem el = items.get(i);
            assertTrue(i >= off.rec.from && i < off.rec.to && offscreen(el, 0, 240), "範囲内の画面外だけが落ちる: " + i);
        }
        assertEquals(List.of("begin", "end"), on.rec.log);
        assertTrue(on.rec.drawn.size() < off.rec.drawn.size());
    }
}
