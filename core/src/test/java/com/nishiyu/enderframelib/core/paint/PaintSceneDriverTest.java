package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.common.SoftwareRendering;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 画素も GL も持たないシーン走査（{@link PaintSceneDriver}）が、描く項目を順に・正しい姿勢と層の鎖で渡すこと。 */
public class PaintSceneDriverTest {

    private record Visit(String id, float flat, List<String> layers, Matrix2D pose, float opacity) {}

    private static final class Recorder implements PaintSceneDriver.Painter {
        final List<Visit> visits = new ArrayList<>();
        final List<List<String>> syncs = new ArrayList<>();
        List<String> current = List.of();
        PaintSceneDriver driver;

        @Override
        public void syncLayers(List<PaintSceneDriver.Group> want) {
            current = want.stream().map(PaintSceneDriver.Group::id).toList();
            syncs.add(current);
        }

        @Override
        public void drawItem(DisplayItem el, int index, float flat) {
            PaintSceneDriver.Resolved r = driver.resolveAt(index);
            visits.add(new Visit(el.id(), flat, current, r.pose(), r.opacity()));
        }
    }

    private static List<DisplayItem> items(String body) {
        return List.copyOf(SoftwareRendering.displayList("<html><body style='margin:0'>" + body + "</body></html>", 200, 100));
    }

    private static Recorder run(PaintSceneDriver d, List<DisplayItem> items, long t, float dpr) {
        Recorder rec = new Recorder();
        rec.driver = d;
        d.run(items, t, Matrix2D.scale(dpr, dpr), rec);
        return rec;
    }

    @Test
    public void visitsEveryDrawableItemInOrderAndNeverTheAnchors() {
        List<DisplayItem> items = items("<div style='width:50px;height:20px;background:#f00'></div>"
                + "<div style='width:50px;height:20px;background:#0f0'></div>");
        Recorder rec = run(new PaintSceneDriver("t-order"), items, 0, 1f);
        List<String> expected = items.stream().filter(i -> i.type() != ElementType.NODE).map(DisplayItem::id).toList();
        assertEquals(expected, rec.visits.stream().map(Visit::id).toList());
        assertTrue(!expected.isEmpty());
        assertEquals(List.of(), rec.syncs.get(rec.syncs.size() - 1), "最後は層を全部閉じる");
    }

    @Test
    public void frameTransformScalesThePose() {
        List<DisplayItem> items = items("<div style='margin-left:10px;width:50px;height:20px;background:#f00'></div>");
        Recorder one = run(new PaintSceneDriver("t-dpr1"), items, 0, 1f);
        Recorder two = run(new PaintSceneDriver("t-dpr2"), items, 0, 2f);
        assertEquals(one.visits.size(), two.visits.size());
        for (int i = 0; i < one.visits.size(); i++) {
            assertEquals(one.visits.get(i).pose().meanScale() * 2, two.visits.get(i).pose().meanScale(), 1e-9);
        }
    }

    @Test
    public void groupWithSeveralPartsOpensALayerAndSinglePartIsFlattened() {
        // 子が 2 つ以上あるグループは層にする。1 つだけなら層にせず、不透明度を項目に掛ける
        List<DisplayItem> multi = items("<div style='opacity:.5;width:60px;height:30px'>"
                + "<div style='width:20px;height:10px;background:#f00'></div><div style='width:20px;height:10px;background:#00f'></div></div>");
        Recorder m = run(new PaintSceneDriver("t-multi"), multi, 0, 1f);
        assertTrue(m.visits.stream().anyMatch(v -> !v.layers().isEmpty()), "層が開く");

        List<DisplayItem> single = items("<div style='opacity:.5;width:60px;height:30px;background:#f00'></div>");
        Recorder s = run(new PaintSceneDriver("t-single"), single, 0, 1f);
        assertTrue(s.visits.stream().allMatch(v -> v.layers().isEmpty()), "層は要らない");
        assertTrue(s.visits.stream().anyMatch(v -> Math.abs(v.flat() - 0.5f) < 1e-4 || v.opacity() < 0.99f), "不透明度は項目へ");
    }

    @Test
    public void animationsAdvanceWithTimeAndTheIndexIsReusedForTheSameImmutableList() {
        List<DisplayItem> items = items("<style>@keyframes mv { from { transform: translateX(0) } to { transform: translateX(100px) } }</style>"
                + "<div style='width:20px;height:20px;background:#f00;animation:mv 1s linear infinite'></div>");
        PaintSceneDriver d = new PaintSceneDriver("t-anim");
        Recorder a = run(d, items, 100_000, 1f);
        long gen = d.generation();
        Recorder b = run(d, items, 100_500, 1f);
        assertEquals(gen, d.generation(), "同じ不変のリストなら索引は作り直さない");
        boolean moved = false;
        for (int i = 0; i < a.visits.size(); i++) moved |= a.visits.get(i).pose().e() != b.visits.get(i).pose().e();
        assertTrue(moved, "時刻で姿勢が変わる");
        run(d, new ArrayList<>(items), 100_500, 1f); // 可変のリストは毎回作り直す
        assertNotEquals(gen, d.generation());
    }

    @Test
    public void emptyListDoesNothing() {
        Recorder rec = run(new PaintSceneDriver("t-empty"), List.of(), 0, 1f);
        assertTrue(rec.visits.isEmpty());
        assertTrue(rec.syncs.isEmpty());
    }
}
