package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** スクロール層の保持プロトコル（{@link PaintSceneDriver.Painter#planRetained} ほか）の動きを、記録するペインターで確かめる。 */
class PaintSceneDriverRetainTest {

    private static String html(boolean groupAround) {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 30; i++) rows.append("<div class=\"row\" id=\"r").append(i).append("\">row ").append(i).append("</div>");
        return "<style>body{margin:0;font-size:13px}.row{height:24px;margin:2px;padding:2px 6px;background:#eef;border:1px solid #99a}"
                + "#outer{position:absolute;left:0;top:0;width:190px;height:230px;overflow-y:auto;border:2px solid #333;border-radius:8px}"
                + "#wrap{opacity:.5;position:relative;width:320px;height:240px}</style>"
                + (groupAround ? "<div id=\"wrap\">" : "") + "<div id=\"outer\">" + rows + "</div>"
                + "<div style=\"position:absolute;left:220px;top:10px;width:60px;height:40px;background:#c33\"></div>" + (groupAround ? "</div>" : "");
    }

    /** 呼び出しを全部記録するペインター。 */
    private static final class Rec implements PaintSceneDriver.Painter {
        final List<String> log = new ArrayList<>();
        final List<Integer> drawn = new ArrayList<>();
        RetainPlan decision = RetainPlan.NONE;
        boolean endOk = true;
        int from = -1, to = -1, planCalls;

        @Override public void syncLayers(List<PaintSceneDriver.Group> want) {}
        @Override public void drawItem(DisplayItem el, int index, float flat) { drawn.add(index); }
        @Override public RetainPlan planRetained(DisplayItem node, int nodeIndex, int f, int t) {
            planCalls++;
            from = f; to = t;
            log.add("plan:" + node.id());
            return decision;
        }
        @Override public void drawRetained(DisplayItem node, int nodeIndex, int f, int t) { log.add("drawRetained"); }
        @Override public void beginRetained(DisplayItem node, int nodeIndex, int f, int t) { log.add("begin@" + drawn.size()); }
        @Override public boolean endRetained(DisplayItem node, int nodeIndex) { log.add("end@" + drawn.size()); return endOk; }
    }

    private static List<DisplayItem> items(String html) {
        StandaloneView view = new StandaloneView(html, 320, 240, FontResolver.FALLBACK);
        try {
            return List.copyOf(view.items());
        } finally {
            view.close();
        }
    }

    private static Rec run(List<DisplayItem> items, RetainPlan decision, boolean endOk) {
        Rec r = new Rec();
        r.decision = decision;
        r.endOk = endOk;
        new PaintSceneDriver("retain-" + decision + endOk).run(items, 1000, Matrix2D.IDENTITY, r);
        return r;
    }

    @Test
    void rangeIsTheContiguousDescendantsOfTheScrollNode() {
        List<DisplayItem> items = items(html(false));
        Rec r = run(items, RetainPlan.NONE, true);
        assertEquals(1, r.planCalls);
        assertTrue(r.from > 0 && r.to > r.from && r.to <= items.size(), r.from + ".." + r.to);
        // 範囲の中の項目は、すべてスクロール節の子孫
        java.util.Map<String, DisplayItem> byId = new java.util.HashMap<>();
        for (DisplayItem it : items) byId.put(it.id(), it);
        for (int i = r.from; i < r.to; i++) {
            String p = items.get(i).hints().parentId();
            boolean found = false;
            for (int g = 0; p != null && g < 64 && !found; g++) {
                found = p.endsWith("_scrollc");
                DisplayItem pi = byId.get(p);
                p = pi == null ? null : pi.hints().parentId();
            }
            assertTrue(found, "範囲外の項目が混ざっている: " + items.get(i).id());
        }
    }

    @Test
    void noneDrawsEverythingLikeBefore() {
        List<DisplayItem> items = items(html(false));
        Rec none = run(items, RetainPlan.NONE, true);
        PaintSceneDriver.retainScrollLayers = false;
        Rec off;
        try {
            off = run(items, RetainPlan.NONE, true);
        } finally {
            PaintSceneDriver.retainScrollLayers = true;
        }
        assertEquals(off.drawn, none.drawn, "問い合わせの有無で走査は変わらない");
        assertEquals(0, off.planCalls);
    }

    @Test
    void skipOmitsTheRangeAndOnlyTheRange() {
        List<DisplayItem> items = items(html(false));
        Rec all = run(items, RetainPlan.NONE, true);
        Rec skip = run(items, RetainPlan.SKIP, true);
        List<Integer> expect = new ArrayList<>(all.drawn);
        expect.removeIf(i -> i >= all.from && i < all.to);
        assertEquals(expect, skip.drawn);
        assertEquals(List.of("plan:outer_scrollc", "drawRetained"), skip.log);
        assertTrue(all.drawn.stream().anyMatch(i -> i >= all.from && i < all.to), "範囲に描く項目がある");
    }

    @Test
    void recordBracketsTheRangeAndDrawsItNormally() {
        List<DisplayItem> items = items(html(false));
        Rec all = run(items, RetainPlan.NONE, true);
        Rec rec = run(items, RetainPlan.RECORD, true);
        assertEquals(all.drawn, rec.drawn, "記録中も項目は同じ順に描かれる");
        int inRange = (int) all.drawn.stream().filter(i -> i >= all.from && i < all.to).count();
        int before = (int) all.drawn.stream().filter(i -> i < all.from).count();
        assertEquals(List.of("plan:outer_scrollc", "begin@" + before, "end@" + (before + inRange)), rec.log);
    }

    @Test
    void aFailedRecordRedrawsTheRangeNormally() {
        List<DisplayItem> items = items(html(false));
        Rec all = run(items, RetainPlan.NONE, true);
        Rec failed = run(items, RetainPlan.RECORD, false);
        int inRange = (int) all.drawn.stream().filter(i -> i >= all.from && i < all.to).count();
        assertEquals(all.drawn.size() + inRange, failed.drawn.size(), "範囲が 2 回描かれる（記録 + 描き直し）");
        assertEquals(2, failed.log.stream().filter(l -> l.startsWith("begin") || l.startsWith("end")).count(), "描き直しでは begin / end を呼ばない");
        // 描き直しの後、範囲より後ろの項目は 1 回だけ
        List<Integer> after = failed.drawn.stream().filter(i -> i >= all.to).toList();
        assertEquals(all.drawn.stream().filter(i -> i >= all.to).toList(), after);
    }

    @Test
    void scrollNodesUnderAGroupAreNotOffered() {
        List<DisplayItem> items = items(html(true));
        Rec r = run(items, RetainPlan.SKIP, true);
        assertEquals(0, r.planCalls, "祖先に不透明度のグループがあるときは問い合わせない");
    }
}
