package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.common.SceneWalkAccess;
import com.nishiyu.enderframelib.common.SoftwareRendering;
import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.PaintOffsets;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 走査器（{@link SceneWalker}。M3b-4b-4a で全バックエンドの描画の経路）が、表示項目の列を入力にして、描く項目を順に・正しい姿勢と
 * 層の鎖で渡すこと（旧 {@code PaintSceneDriverTest} を移した）。前のフレームの索引・姿勢は使い回さない。
 */
public class SceneWalkerTest {

    private record Visit(String id, float flat, List<String> layers, Matrix2D pose, float opacity) {}

    private static final class Recorder implements Painter {
        final List<Visit> visits = new ArrayList<>();
        final List<List<String>> syncs = new ArrayList<>();
        List<String> current = List.of();
        SceneWalker walker;

        @Override
        public void syncLayers(List<SceneWalker.Group> want) {
            current = want.stream().map(SceneWalker.Group::id).toList();
            syncs.add(current);
        }

        @Override
        public void drawItem(DisplayItem el, int index, float flat) {
            SceneWalker.Resolved r = walker.resolveAt(index);
            visits.add(new Visit(el.id(), flat, current, r.pose(), r.opacity()));
        }
    }

    private static List<DisplayItem> items(String body) {
        return List.copyOf(SoftwareRendering.displayList("<html><body style='margin:0'>" + body + "</body></html>", 200, 100));
    }

    private static Recorder run(SceneWalker w, List<DisplayItem> items, PaintOffsets offsets, long t, float dpr) {
        Recorder rec = new Recorder();
        rec.walker = w;
        w.run(com.nishiyu.enderframelib.core.paint.RenderFrame.of(items, offsets, t), Matrix2D.scale(dpr, dpr), rec, null);
        return rec;
    }

    private static Recorder run(SceneWalker w, List<DisplayItem> items, long t, float dpr) {
        return run(w, items, PaintOffsets.EMPTY, t, dpr);
    }

    @Test
    public void visitsEveryDrawableItemInOrderAndNeverTheAnchors() {
        List<DisplayItem> items = items("<div style='width:50px;height:20px;background:#f00'></div>"
                + "<div style='width:50px;height:20px;background:#0f0'></div>");
        Recorder rec = run(new SceneWalker("w-order"), items, 0, 1f);
        List<String> expected = items.stream().filter(i -> i.type() != ElementType.NODE).map(DisplayItem::id).toList();
        assertEquals(expected, rec.visits.stream().map(Visit::id).toList());
        assertTrue(!expected.isEmpty());
        assertEquals(List.of(), rec.syncs.get(rec.syncs.size() - 1), "最後は層を全部閉じる");
    }

    @Test
    public void frameTransformScalesThePose() {
        List<DisplayItem> items = items("<div style='margin-left:10px;width:50px;height:20px;background:#f00'></div>");
        Recorder one = run(new SceneWalker("w-dpr1"), items, 0, 1f);
        Recorder two = run(new SceneWalker("w-dpr2"), items, 0, 2f);
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
        Recorder m = run(new SceneWalker("w-multi"), multi, 0, 1f);
        assertTrue(m.visits.stream().anyMatch(v -> !v.layers().isEmpty()), "層が開く");

        List<DisplayItem> single = items("<div style='opacity:.5;width:60px;height:30px;background:#f00'></div>");
        Recorder s = run(new SceneWalker("w-single"), single, 0, 1f);
        assertTrue(s.visits.stream().allMatch(v -> v.layers().isEmpty()), "層は要らない");
        assertTrue(s.visits.stream().anyMatch(v -> Math.abs(v.flat() - 0.5f) < 1e-4 || v.opacity() < 0.99f), "不透明度は項目へ");
    }

    @Test
    public void animationsAdvanceWithTimeAndTheGenerationFollowsTheListIdentity() {
        List<DisplayItem> items = items("<style>@keyframes mv { from { transform: translateX(0) } to { transform: translateX(100px) } }</style>"
                + "<div style='width:20px;height:20px;background:#f00;animation:mv 1s linear infinite'></div>");
        SceneWalker w = new SceneWalker("w-anim");
        Recorder a = run(w, items, 100_000, 1f);
        long gen = w.generation();
        Recorder b = run(w, items, 100_500, 1f);
        assertEquals(gen, w.generation(), "同じ列なら世代は変わらない（項目の位置で引く描画側の覚えが使える）");
        boolean moved = false;
        for (int i = 0; i < a.visits.size(); i++) moved |= a.visits.get(i).pose().e() != b.visits.get(i).pose().e();
        assertTrue(moved, "時刻で姿勢が変わる");
        run(w, new ArrayList<>(items), 100_500, 1f);
        assertNotEquals(gen, w.generation(), "別の列なら世代が進む");
    }

    @Test
    public void userOffsetsTranslateTheNodeAndItsParts() {
        List<DisplayItem> items = items("<div id='box' style='width:50px;height:20px;background:#f00'></div>");
        long parentKey = items.stream().filter(i -> i.type() != ElementType.NODE && i.hints().parentKey() != 0L)
                .reduce((x, y) -> y).orElseThrow().hints().parentKey(); // 最後の部品（箱の背景）の錨
        DisplayItem node = items.stream().filter(i -> i.type() == ElementType.NODE && i.key() == parentKey).findFirst().orElseThrow();
        Recorder base = run(new SceneWalker("w-off0"), items, 0, 1f);
        PaintOffsets off = new PaintOffsets();
        off.set(node.id(), 7f, 3f);
        Recorder moved = run(new SceneWalker("w-off1"), items, off.snapshot(), 0, 1f);
        boolean any = false;
        for (int i = 0; i < base.visits.size(); i++) {
            double dx = moved.visits.get(i).pose().e() - base.visits.get(i).pose().e();
            double dy = moved.visits.get(i).pose().f() - base.visits.get(i).pose().f();
            if (dx != 0 || dy != 0) {
                assertEquals(7.0, dx, 1e-6);
                assertEquals(3.0, dy, 1e-6);
                any = true;
            }
        }
        assertTrue(any, "節の部品が平行移動を受ける");
    }

    /**
     * 高さ 0 の要素は節の錨を出さない。その子は親の鎖でも祖先のフィルターの層に入る（M3b-4b-3a で錨を出さない箱を親の鎖と座標の原点から
     * 素通しにした。{@code ZeroSizeAncestorTest}）。描画の経路と同じ入力（ビューの列・平行移動・シーン）で、子の背景が層の中で描かれること。
     */
    @Test
    public void filterLayerCoversItemsWhoseAnchorIsNotDrawn() {
        String html = "<style>body{margin:0}</style><div id=f style='filter:blur(1px);width:200px;height:60px'>"
                + "<div id=z style='height:0'><div id=c style='width:40px;height:20px;background:#c33'></div></div></div>";
        try (SoftwareRenderBackend backend = new SoftwareRenderBackend(300, 100);
             StandaloneView v = new StandaloneView(html, 300, 100, FontResolver.FALLBACK)) {
            v.frame(1000L, backend, true);
            Recorder rec = new Recorder();
            rec.walker = new SceneWalker(backend.sessionId());
            SceneWalkAccess.walk(v, rec.walker, 1000L, Matrix2D.IDENTITY, rec);
            List<DisplayItem> items = v.ui().renderCache();
            String childBg = items.stream().filter(it -> it.bgColor() == 0xFFCC3333 && it.type() != ElementType.NODE)
                    .findFirst().orElseThrow().id();
            Visit child = rec.visits.stream().filter(x -> childBg.equals(x.id())).findFirst().orElseThrow();
            assertTrue(!child.layers().isEmpty(), "高さ 0 の要素の子の背景がフィルターの層に入っていない: " + rec.visits);
        }
    }

    /**
     * ビューの表示項目の列はスクロール量を焼き込まない（M3b-4b-3a・4b-4a）: 中身を 1 つの節にまとめるスクロールコンテナをスクロールしても、
     * 列の項目の位置は同じで、走査器が描く中身の項目の姿勢だけがスクロール量だけ動く。焼き込む・平行移動の向きを取り違える変異で落ちる。
     */
    @Test
    public void viewListDoesNotDependOnTheScrollOfOneNodeContainers() {
        StringBuilder html = new StringBuilder("<style>body{margin:0}.r{height:40px}</style><div id=s style='overflow:auto;width:200px;height:100px'>");
        for (int i = 0; i < 10; i++) html.append("<div class=r style='background:#").append(i % 2 == 0 ? "c33" : "3c3").append("'>").append(i).append("</div>");
        html.append("</div><div id=x style='width:20px;height:12px;background:#00f'></div>");
        try (SoftwareRenderBackend backend = new SoftwareRenderBackend(300, 200);
             StandaloneView v = new StandaloneView(html.toString(), 300, 200, FontResolver.FALLBACK)) {
            SceneWalker walker = new SceneWalker(backend.sessionId());
            v.frame(1000L, backend, true);
            v.mouseMove(50, 50, backend.sessionId());
            v.frame(1100L, backend, true);
            Recorder top = new Recorder();
            top.walker = walker;
            SceneWalkAccess.walk(v, walker, 1100L, Matrix2D.IDENTITY, top);
            Map<String, String> before = boxes(v.ui().renderCache());
            v.scroll(0, -2, backend.sessionId());
            v.frame(1500L, backend, true);
            float dy = v.ui().dynamicState().scroll.snapshot().values().stream().findFirst().map(xy -> xy[1]).orElse(0f);
            assertTrue(dy > 0f, "前提: 中身がスクロールした");
            assertEquals(before, boxes(v.ui().renderCache()), "スクロールで列の項目の位置が変わった（スクロール量を焼き込んだ）");
            Recorder scrolled = new Recorder();
            scrolled.walker = walker;
            SceneWalkAccess.walk(v, walker, 1500L, Matrix2D.IDENTITY, scrolled);
            assertEquals(top.visits.size(), scrolled.visits.size());
            int moved = 0;
            List<DisplayItem> items = v.ui().renderCache();
            Map<String, DisplayItem> byId = new HashMap<>();
            for (DisplayItem it : items) byId.put(it.id(), it);
            for (int i = 0; i < top.visits.size(); i++) {
                DisplayItem a = byId.get(top.visits.get(i).id());
                if (a == null) continue;
                double d = scrolled.visits.get(i).pose().f() - top.visits.get(i).pose().f();
                if (a.type() == ElementType.TEXT || a.bgColor() == 0xFFCC3333 || a.bgColor() == 0xFF33CC33) {
                    assertEquals(-dy, d, 1e-3, "中身の項目がスクロール量だけ動かない: " + a.id());
                    moved++;
                }
            }
            assertTrue(moved >= 10, "中身の項目が少ない: " + moved);
            // スクロールしたまま列を組み直しても（ほかの箱の変化）、中身の項目はスクロール 0 の位置のまま
            List<DisplayItem> beforeRebuild = v.ui().renderCache();
            v.root().getNodeById("x").setAttribute("style", "width:30px;height:12px;background:#00f");
            v.frame(1600L, backend, true);
            assertTrue(beforeRebuild != v.ui().renderCache(), "前提: 列を組み直した");
            Map<String, String> after = boxes(v.ui().renderCache());
            for (var e : before.entrySet()) if (e.getValue().startsWith("RECT") && after.containsKey(e.getKey()) && !e.getKey().startsWith("x")) {
                assertEquals(e.getValue(), after.get(e.getKey()), "組み直した列にスクロール量が焼き込まれた: " + e.getKey());
            }
        }
    }

    /**
     * 列はスクロール 0 で組むので、画面の上の位置は平行移動を足して求める（{@code DisplayItem#resolveAbsolute(List, DisplayItem, PaintOffsets)}。
     * MOD のツールチップ・面の上のキャレットが使う）。内側のコンテナをスクロールした後の行の背景の位置が、シーンから求めた位置
     * （{@code UiView#locate}）と合うこと。平行移動を足さない変異で落ちる。
     */
    @Test
    public void absolutePositionOfAnItemFollowsTheScrollOffsets() {
        StringBuilder html = new StringBuilder("<style>body{margin:0}.r{height:40px;background:#c33}</style><div id=s style='overflow:auto;width:200px;height:100px'>");
        for (int i = 0; i < 10; i++) html.append("<div class=r id=r").append(i).append(">").append(i).append("</div>");
        html.append("</div>");
        try (SoftwareRenderBackend backend = new SoftwareRenderBackend(300, 200);
             StandaloneView v = new StandaloneView(html.toString(), 300, 200, FontResolver.FALLBACK)) {
            v.frame(1000L, backend, true);
            v.mouseMove(50, 50, backend.sessionId());
            v.scroll(0, -2, backend.sessionId());
            v.frame(1500L, backend, true);
            var node = v.root().getNodeById("r3");
            var loc = v.ui().locate(node);
            List<DisplayItem> items = v.ui().renderCache();
            DisplayItem bg = items.stream().filter(it -> it.type() != ElementType.NODE && it.bgColor() == 0xFFCC3333
                    && DisplayItem.nodeHandleOf(it.key()) == node.nodeId()).findFirst().orElseThrow();
            DisplayItem abs = DisplayItem.resolveAbsolute(items, bg, v.ui().paintOffsets());
            assertTrue(loc.y() != DisplayItem.resolveAbsolute(items, bg).y(), "前提: スクロールで位置が動いた");
            assertEquals(loc.y(), abs.y(), 1.0, "画面の上の位置がシーンと違う");
        }
    }

    /** 列の項目の id → 箱（スクロールで変わらないこと）。 */
    private static Map<String, String> boxes(List<DisplayItem> items) {
        Map<String, String> m = new HashMap<>();
        for (DisplayItem it : items) m.put(it.id(), it.type() + " " + it.x() + "," + it.y() + " " + it.width() + "x" + it.height());
        return m;
    }

    @Test
    public void emptyListDoesNothing() {
        Recorder rec = run(new SceneWalker("w-empty"), List.of(), 0, 1f);
        assertTrue(rec.visits.isEmpty());
        assertTrue(rec.syncs.isEmpty());
    }
}
