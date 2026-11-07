package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.paint.DisplayListBuilder;
import com.nishiyu.enderframelib.core.paint.UIHitResult;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * スクロールだけの更新で表示リストを作り直さず、スクロールコンテナの中身の節を動かして済ませても
 * （{@link DisplayListBuilder#scrollContentNodes}）、毎回作り直したときと同じ見た目・同じヒットテストになること。
 *
 * <p>見た目は「項目の位置 + 祖先のスクロール節の移動量」（実際の描画位置）で比べる（作り直しのほうは位置に焼き込まれている）。
 * 四捨五入の違いで 1 画素までずれうる。画面に入っている項目は、節で動かした側にも必ずあること（作り足りないと端が欠ける）。
 * 縦・横のスクロール・入れ子・つまみ・sticky と固定配置を含むコンテナ（従来の作り直しに落ちる）・アニメーション（再スタイルで
 * 見た目が変わるフレームは作り直しに落ちる）・リサイズ・文字の書き換えを乱数で混ぜる。</p>
 */
public class ScrollTranslateTest {

    private static final int W = 320, H = 240;

    private static String html() {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 40; i++) rows.append("<div class=\"row\" id=\"r").append(i).append("\">row ").append(i).append(" some text</div>");
        StringBuilder inner = new StringBuilder();
        for (int i = 0; i < 14; i++) inner.append("<div class=\"row\" id=\"i").append(i).append("\">inner ").append(i).append("</div>");
        StringBuilder side = new StringBuilder();
        for (int i = 0; i < 20; i++) side.append("<div class=\"row\" id=\"s").append(i).append("\">side ").append(i).append("</div>");
        StringBuilder stick = new StringBuilder();
        for (int i = 0; i < 20; i++) stick.append("<div class=\"row\" id=\"k").append(i).append("\">stick ").append(i).append("</div>");
        return "<style>"
                + "body{margin:0;font-size:13px}"
                + ".row{height:26px;margin:2px;padding:2px 6px;background:#eef;border:1px solid #99a}"
                + ".hot{background:#fdd}"
                + ".m{animation:morph 1s infinite alternate}"
                + "@keyframes morph{from{border-radius:2px}to{border-radius:14px}}"
                + "#outer{position:absolute;left:0;top:0;width:190px;height:230px;overflow-y:auto;border:2px solid #333;border-radius:8px;"
                + "box-shadow:0 0 6px #000;background:linear-gradient(#fff,#cde)}"
                + "#rel{position:relative}"
                + ".abs{position:absolute;top:10px;right:10px;width:30px;height:18px;background:#d33}"
                + "#inner{height:90px;overflow:auto;background:#efe}"
                + ".wide{width:420px;height:16px;background:#9c9}"
                + "#side{position:absolute;left:200px;top:0;width:110px;height:110px;overflow:scroll}"
                + "#stick{position:absolute;left:200px;top:120px;width:110px;height:100px;overflow:auto}"
                + ".hdr{position:sticky;top:0;background:#fc6}"
                + "</style>"
                + "<div id=\"outer\"><div id=\"rel\"><div class=\"abs\" id=\"a1\"></div>" + rows + "</div>"
                + "<div id=\"inner\">" + inner + "<div class=\"wide\" id=\"wide\"></div></div></div>"
                + "<div id=\"side\">" + side + "</div>"
                + "<div id=\"stick\"><div class=\"hdr\" id=\"hdr\">header</div>" + stick + "</div>";
    }

    private static boolean synthetic(String id) {
        return id.endsWith("_scrollc") || id.endsWith("_scrollv") || id.endsWith("_scrollh");
    }

    /** 1 手ごとの記録: id → 実際の描画位置と、位置以外の中身。 */
    private record Frame(Map<String, float[]> pos, Map<String, String> body, Map<String, DisplayItem> items, List<String> hits, int[][] probes,
                         Map<String, String> missing) {}

    private static Frame snapshot(StandaloneView view, int[][] probes) {
        Map<String, DisplayItem> byId = new HashMap<>();
        List<DisplayItem> current = view.ui().renderCache(); // ヒットテストが読むのと同じリスト
        for (DisplayItem it : current) byId.put(it.id(), it);
        Map<String, float[]> pos = new HashMap<>();
        Map<String, String> body = new HashMap<>();
        Map<String, String> missing = new HashMap<>();
        for (DisplayItem it : current) {
            if (synthetic(it.id())) continue;
            // テキストノードの NODE 項目は位置を持たない（常に 0,0 に出る。文字は親の断片が持つ）ので、比べない
            if (it.type() == ElementType.NODE && (it.id().startsWith("~t") || it.id().endsWith("_txt"))) continue;
            float ex = it.x(), ey = it.y();
            String parent = it.hints().parentId();
            for (int guard = 0; parent != null && guard < 64; guard++) {
                DisplayItem p = byId.get(parent);
                if (p == null) { // 祖先の項目が無い: 画面の外で省かれたか、最上位（body など。項目を出さない）か
                    missing.put(it.id(), parent);
                    break;
                }
                if (synthetic(p.id())) {
                    ex += p.translateX();
                    ey += p.translateY();
                }
                parent = p.hints().parentId();
            }
            pos.put(it.id(), new float[]{ex, ey, it.width(), it.height()});
            body.put(it.id(), it.type() + "|" + it.text() + "|" + it.color() + "|" + it.bgColor() + "|" + it.radius() + "|"
                    + it.opacity() + "|" + it.events());
        }
        List<String> hits = new ArrayList<>();
        for (int[] p : probes) {
            UIHitResult top = view.ui().hitTest(p[0], p[1]);
            hits.add(top == null ? "-" : top.element().id());
        }
        return new Frame(pos, body, byId, hits, probes, missing);
    }

    private static List<Frame> run(boolean nodes, long seed, int steps) {
        DisplayListBuilder.scrollContentNodes = nodes;
        Random rnd = new Random(seed);
        int[][] probes = new int[8][];
        for (int i = 0; i < probes.length; i++) probes[i] = new int[]{rnd.nextInt(W), rnd.nextInt(H)};
        StandaloneView view = new StandaloneView(html(), W, H, FontResolver.FALLBACK);
        try (var backend = new MutationScenario.NoDraw()) {
            List<Frame> out = new ArrayList<>();
            long now = 1000;
            view.frame(now, backend, true);
            String sid = backend.sessionId();
            for (int step = 0; step < steps; step++) {
                switch (rnd.nextInt(10)) {
                    case 0, 1, 2 -> {
                        view.mouseMove(rnd.nextInt(W), rnd.nextInt(H), sid);
                        view.scroll(0, rnd.nextInt(7) - 3, sid);
                    }
                    case 3 -> {
                        view.mouseMove(rnd.nextInt(W), rnd.nextInt(H), sid);
                        view.scroll(rnd.nextInt(5) - 2, 0, sid);
                    }
                    case 4 -> {
                        HtmlNode n = view.root().getNodeById("r" + rnd.nextInt(40));
                        ((HtmlElement) n).setAttribute("class", new String[]{"row", "row hot", "row m", "row hot m"}[rnd.nextInt(4)]);
                    }
                    case 5 -> view.mouseMove(rnd.nextInt(W), rnd.nextInt(H), sid);
                    case 6 -> {
                        HtmlNode n = view.root().getNodeById("r" + rnd.nextInt(40));
                        n.setTextContent("changed " + rnd.nextInt(1000) + (rnd.nextBoolean() ? " with some extra words to wrap" : ""));
                    }
                    case 7 -> {
                        int[][] sizes = {{W, H}, {280, 240}, {360, 260}};
                        int[] size = sizes[rnd.nextInt(sizes.length)];
                        view.resize(size[0], size[1]);
                    }
                    default -> { } // 時間だけ（減速の途中）
                }
                // 減速は経過時間で進む。数フレーム回して、減速の途中の状態も比べる
                for (int f = 0, n = 1 + rnd.nextInt(3); f < n; f++) {
                    now += 16 + rnd.nextInt(40);
                    view.frame(now, backend, true);
                    for (int spin = 0; spin < 2000 && (view.ui().rootW() != view.width() || view.ui().rootH() != view.height()); spin++) {
                        try {
                            Thread.sleep(1);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        view.frame(now, backend, true);
                    }
                    out.add(snapshot(view, probes));
                }
            }
            return out;
        } finally {
            view.close();
        }
    }

    /** 点が、矩形 {@code r = {x, y, w, h}} の縁から 1 画素以内か。 */
    private static boolean nearEdge(int[] p, float[] r) {
        if (r == null) return false;
        boolean outer = p[0] >= r[0] - 1 && p[0] <= r[0] + r[2] + 1 && p[1] >= r[1] - 1 && p[1] <= r[1] + r[3] + 1;
        boolean inner = p[0] > r[0] + 1 && p[0] < r[0] + r[2] - 1 && p[1] > r[1] + 1 && p[1] < r[1] + r[3] - 1;
        return outer && !inner;
    }

    @AfterEach
    public void restore() {
        DisplayListBuilder.scrollContentNodes = true;
    }

    private static void assertSame(Frame expected, Frame actual, String where, java.util.Set<String> allIds) {
        // 画面に入っている項目は、節で動かした側にも必ずあること
        for (var e : expected.pos().entrySet()) {
            float[] p = e.getValue();
            boolean visible = p[0] + p[2] > 0 && p[0] < W && p[1] + p[3] > 0 && p[1] < H;
            if (visible && expected.items().get(e.getKey()).type() != ElementType.NODE) {
                assertTrue(actual.pos().containsKey(e.getKey()), where + ": 画面にある項目が無い: " + e.getKey());
            }
        }
        for (var e : expected.pos().entrySet()) {
            float[] a = actual.pos().get(e.getKey());
            if (a == null) continue; // 画面の外で、どちらかだけが省いた項目
            // 持ち主が画面の外で省かれた項目（スクロールバーの軌道など）は、連鎖が途切れて位置が決まらない
            String m1 = expected.missing().get(e.getKey()), m2 = actual.missing().get(e.getKey());
            if ((m1 != null && allIds.contains(m1)) || (m2 != null && allIds.contains(m2))) continue;
            float[] x = e.getValue();
            assertEquals(x[0], a[0], 1.0f, where + ": x " + e.getKey());
            assertEquals(x[1], a[1], 1.0f, where + ": y " + e.getKey());
            assertEquals(x[2], a[2], 1.0f, where + ": 幅 " + e.getKey());
            assertEquals(x[3], a[3], 1.0f, where + ": 高さ " + e.getKey());
            assertEquals(expected.body().get(e.getKey()), actual.body().get(e.getKey()), where + ": 中身 " + e.getKey());
        }
        for (int i = 0; i < expected.hits().size(); i++) {
            if (!expected.hits().get(i).equals(actual.hits().get(i))) {
                String e = expected.hits().get(i), a = actual.hits().get(i);
                // 作り直しは座標を整数に丸め、節の移動は小数のまま動かす。項目の縁の 1 画素以内の違いは許す
                if (nearEdge(expected.probes()[i], expected.pos().get(e)) || nearEdge(expected.probes()[i], actual.pos().get(a))
                        || nearEdge(expected.probes()[i], actual.pos().get(e)) || nearEdge(expected.probes()[i], expected.pos().get(a))) continue;
                throw new AssertionError(where + ": ヒットテスト 点(" + expected.probes()[i][0] + "," + expected.probes()[i][1] + ") 期待 " + e + " "
                        + java.util.Arrays.toString(expected.pos().get(e)) + " / 実際 " + a + " " + java.util.Arrays.toString(actual.pos().get(a))
                        + " | 節の側の " + e + " " + java.util.Arrays.toString(actual.pos().get(e))
                        + " | 期待側の項目 " + expected.items().get(e) + " | 実際側の項目 " + actual.items().get(a)
                        + " | 親 " + (expected.items().get(e) == null ? null : expected.items().get(e).hints().parentId()));
            }
        }
    }

    @Test
    public void movingTheContentNodeMatchesRebuildingEveryTime() {
        for (long seed = 1; seed <= 10; seed++) {
            List<Frame> rebuilt = run(false, seed, 45);
            List<Frame> moved = run(true, seed, 45);
            assertEquals(rebuilt.size(), moved.size());
            java.util.Set<String> allIds = new java.util.HashSet<>();
            for (Frame f : rebuilt) allIds.addAll(f.items().keySet());
            for (Frame f : moved) allIds.addAll(f.items().keySet());
            for (int i = 0; i < rebuilt.size(); i++) assertSame(rebuilt.get(i), moved.get(i), "seed " + seed + " frame " + i, allIds);
        }
    }

    @Test
    public void scrollingReallyUsesTheNodeMove() {
        // 高速経路が実際に使われること（使われないなら、安全でも無意味）
        long before = UiView.STAT_SCROLL_TRANSLATED.sum();
        run(true, 3, 45);
        assertTrue(UiView.STAT_SCROLL_TRANSLATED.sum() - before > 5, "スクロールが節の移動で済んでいない");
    }

    @Test
    public void containersWithStickyOrFixedFallBackToRebuilding() {
        // sticky を含むコンテナは節にまとめない（中身が一様に動かない）
        StandaloneView view = new StandaloneView(html(), W, H, FontResolver.FALLBACK);
        try {
            List<String> ids = new ArrayList<>();
            for (DisplayItem it : view.items()) if (synthetic(it.id())) ids.add(it.id());
            assertTrue(ids.contains("outer_scrollc"), "通常のコンテナは節にまとめる: " + ids);
            assertTrue(!ids.contains("stick_scrollc"), "sticky を含むコンテナは節にまとめない: " + ids);
        } finally {
            view.close();
        }
    }
}
