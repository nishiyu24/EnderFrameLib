package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 増分で作った表示リストが、同じ面・同じ時刻で全面の再レイアウトを強いた結果と一致すること（ENGINE_REARCHITECTURE_PLAN A6b）。
 * 乱数の変更列（{@link MutationScenario}）の毎手で比べる。float や位置指定の子の増減を局所パッチで扱うと、兄弟の行の折り返し・描画順が
 * ずれる不具合（layouts 文書の種 2 / 6 / 7 / 8）の回帰テスト。
 */
class IncrementalVsFullTest {

    @AfterEach
    void clear() {
        MutationScenario.STEP_HOOK = null;
    }

    private static List<String> sigs(StandaloneView v) {
        List<String> out = new ArrayList<>();
        for (var it : v.items()) out.add(MutationScenario.sig(it));
        return out;
    }

    /** 毎手、増分の結果と全面の結果を比べて、食い違った手の説明を返す（無ければ空）。{@code strictOrder = false} は並び順を無視する。 */
    private static List<String> mismatches(String name, String html, String[] classes, String[] styles, int seeds, int steps, long seedMul, boolean strictOrder) {
        List<String> bad = new ArrayList<>();
        for (long seed = 1; seed <= seeds; seed++) {
            final long sd = seed;
            boolean[] failed = {false};
            MutationScenario.STEP_HOOK = (step, view) -> {
                if (failed[0]) return;
                List<String> live = sigs(view);
                view.ui().markInvalidation(UiView.InvalidationLevel.RELAYOUT);
                try (var be = new MutationScenario.NoDraw()) {
                    var f = StandaloneView.class.getDeclaredField("lastNow"); // 同じ時刻（時間が進むとトランジションが進んでノイズになる）
                    f.setAccessible(true);
                    view.frame(f.getLong(view), be, true);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
                List<String> full = sigs(view);
                List<String> a = new ArrayList<>(live), b = new ArrayList<>(full);
                if (!strictOrder) { Collections.sort(a); Collections.sort(b); }
                if (!a.equals(b)) {
                    failed[0] = true;
                    String detail = "";
                    for (int i = 0; i < Math.min(a.size(), b.size()); i++) if (!a.get(i).equals(b.get(i))) { detail = "（" + a.get(i) + " ≠ " + b.get(i) + "）"; break; }
                    bad.add(name + "/" + sd + " 手 " + step + detail);
                }
            };
            MutationScenario.run(html, classes, styles, seedMul * seed + (seedMul > 1 ? 1 : 0), steps, false);
        }
        return bad;
    }

    @Test
    void layoutsDocumentMatchesFullRelayoutEveryStep() {
        assertEquals(List.of(), mismatches("layouts", ShortcutMatrixMain.LAYOUTS, ShortcutMatrixMain.LAYOUT_CLASSES, ShortcutMatrixMain.LAYOUT_STYLES, 8, 45, 1, true));
    }

    @Test
    void cardsDocumentMatchesFullRelayoutEveryStep() {
        assertEquals(List.of(), mismatches("cards", ShortcutMatrixMain.CARDS, ShortcutMatrixMain.CARD_CLASSES, ShortcutMatrixMain.CARD_STYLES, 8, 45, 1, true));
    }

    /** nested（flex の入れ子）。ブロックの子を足したとき、新しい子の項目がブロック層の末尾（flex アイテムの手前）に入ること。 */
    @Test
    void nestedDocumentMatchesFullRelayoutEveryStep() {
        assertEquals(List.of(), mismatches("nested", ShortcutMatrixMain.NESTED, ShortcutMatrixMain.NESTED_CLASSES, ShortcutMatrixMain.NESTED_STYLES, 8, 60, 5, true));
    }

    /**
     * 外寸が変わらない子の変化を親で吸収する近道（cutoff）を切ったときも、増分の結果が全面と一致すること（A6b ②）。
     */
    @Test
    void layoutsDocumentWithoutCutoffMatchesFullRelayout() {
        boolean saved = com.nishiyu.enderframelib.core.layout.LayoutBox.cutoffEnabled;
        com.nishiyu.enderframelib.core.layout.LayoutBox.cutoffEnabled = false;
        try {
            assertEquals(List.of(), mismatches("layouts", ShortcutMatrixMain.LAYOUTS, ShortcutMatrixMain.LAYOUT_CLASSES, ShortcutMatrixMain.LAYOUT_STYLES, 8, 45, 1, true));
        } finally {
            com.nishiyu.enderframelib.core.layout.LayoutBox.cutoffEnabled = saved;
        }
    }

    /**
     * 祖先の高さが変わる（子パッチの後のリサイズなど）とき、上下の枠線の太さが負にならないこと（A6b ⑥）。
     * 以前は枠線の RECT の高さに高さの変化量を足して、1px の線が -20 になった（nested・種 4 の手 40）。全面の結果と比べるだけでは
     * 見えない（全面へ強いた時点で直る）ので、項目の大きさを直接調べる。
     */
    @Test
    void borderLinesNeverGetNegativeSizeAfterAncestorResize() {
        List<String> bad = new ArrayList<>();
        for (long seed = 1; seed <= 8; seed++) {
            final long sd = seed;
            MutationScenario.STEP_HOOK = (step, view) -> {
                for (var it : view.items()) if (it.width() < 0 || it.height() < 0) bad.add("nested/" + sd + " 手 " + step + " " + it.id());
            };
            MutationScenario.run(ShortcutMatrixMain.NESTED, ShortcutMatrixMain.NESTED_CLASSES, ShortcutMatrixMain.NESTED_STYLES, 5 * seed + 1, 60, false);
        }
        assertEquals(List.of(), bad.size() > 8 ? bad.subList(0, 8) : bad);
    }

    /**
     * body の背景がキャンバスへ渡る文書で、子孫の :hover が body の背景を変えるとき、ホバーを外したフレームで先頭の bg_root の色が
     * 戻ること（A6b ④。以前は 1 フレーム古い色が残った。bg_root は箱のスライスの外にあり、局所パッチが合わせていなかった）。
     */
    @Test
    void canvasBackgroundFollowsBodyHoverStyleInTheSameFrame() {
        String html = "<style>body{margin:0}body{background:#eef}body:hover{background:#fdd}.r{height:30px}</style><div class=\"r\" id=\"r1\">row</div>";
        try (StandaloneView v = new StandaloneView(html, 320, 240, com.nishiyu.enderframelib.core.text.model.FontResolver.FALLBACK);
             var be = new MutationScenario.NoDraw()) {
            long now = 1000;
            v.frame(now, be, true);
            java.util.function.Supplier<Integer> bg = () -> v.items().stream().filter(i -> i.id().equals("bg_root")).map(i -> i.bgColor()).findFirst().orElse(0);
            int normal = bg.get();
            v.mouseMove(10, 10, be.sessionId());
            v.frame(now += 50, be, true);
            int hovered = bg.get();
            v.mouseMove(10, 235, be.sessionId()); // 行の外（body の余白）へ出ると、body 自身のホバーも外れる文書にするため下の空きへ
            v.mouseMove(330, 100, be.sessionId());
            v.frame(now += 50, be, true);
            assertEquals(true, hovered != normal, "ホバーで背景が変わる前提");
            assertEquals(normal, (int) bg.get());
        }
    }

    private static java.util.Map<String, Object> clipsOf(StandaloneView v, String prefix) {
        java.util.Map<String, Object> out = new java.util.TreeMap<>();
        for (var it : v.items()) if (it.id().startsWith(prefix)) out.put(it.id(), it.meta() == null ? null : it.meta().clipRect());
        return out;
    }

    /**
     * 記憶の無い箱（表のセルは記憶しない。記憶を切った構成ではすべての箱）を :hover の変化で描き直すとき、スクロールする箱の clip が
     * 落ちないこと（A6b ④）。以前は描き直しの clip を祖先の記憶から探し、祖先「自身の」clip（スクロールする箱の外側＝null）を使ったので、
     * 箱の下端をまたぐセルがスクロールする箱の外へはみ出して描かれた（scrolllist・nomemo・種 1 の手 21）。
     */
    @Test
    void repaintedBoxWithoutMemoKeepsScrollContainerClip() {
        String html = "<style>body{margin:0}#sc{margin:4px 8px;width:200px;height:100px;overflow-y:auto}"
                + "table{border-collapse:separate}td{height:40px;width:150px;background:#eef}td:hover{background:#fdd}</style>"
                + "<div id=\"sc\"><table>" + "<tr><td id=\"c0\">0</td></tr><tr><td id=\"c1\">1</td></tr><tr><td id=\"c2\">2</td></tr>"
                + "<tr><td id=\"c3\">3</td></tr></table></div>";
        try (StandaloneView v = new StandaloneView(html, 320, 240, com.nishiyu.enderframelib.core.text.model.FontResolver.FALLBACK);
             var be = new MutationScenario.NoDraw()) {
            long now = 1000;
            v.frame(now, be, true);
            var before = clipsOf(v, "c2");
            assertEquals(true, before.values().stream().allMatch(java.util.Objects::nonNull), "下端をまたぐセルはスクロールする箱で切られる前提: " + before);
            v.mouseMove(50, 95, be.sessionId()); // c2（下端をまたぐ）の上
            v.frame(now += 50, be, true);
            v.mouseMove(300, 230, be.sessionId()); // 外へ
            v.frame(now += 50, be, true);
            var incremental = clipsOf(v, "c2");
            v.ui().markInvalidation(UiView.InvalidationLevel.RELAYOUT);
            v.frame(now, be, true);
            assertEquals(clipsOf(v, "c2"), incremental);
            assertEquals(before, incremental);
        }
    }

    private static List<Integer> scrollListPixels(long seed, boolean scrollNodes) {
        boolean saved = com.nishiyu.enderframelib.core.paint.DisplayListBuilder.scrollContentNodes;
        com.nishiyu.enderframelib.core.paint.DisplayListBuilder.scrollContentNodes = scrollNodes;
        try {
            MutationScenario.run(ShortcutMatrixMain.scrollList(), ShortcutMatrixMain.LIST_CLASSES, ShortcutMatrixMain.LIST_STYLES, seed, 45, true, true);
            return new ArrayList<>(MutationScenario.PIXELS);
        } finally {
            com.nishiyu.enderframelib.core.paint.DisplayListBuilder.scrollContentNodes = saved;
        }
    }

    /**
     * スクロールする箱の中身を 1 つの節にまとめて平行移動する既定と、毎回作り直す経路（{@code noscrollnode}）の画素が毎手一致すること
     * （A6b ⑤）。以前は 3 つの原因で食い違った: (a) 減速の途中のスクロール量が小数で、既定は小数の平行移動（縁がぼける）、作り直しは
     * 整数へ丸めた位置 (b) つまみも小数の平行移動で、角の縁の画素が違った (c) 窓付きの作り直しで、インラインの入れ子の span の箱の
     * x / y が (0, 0) のまま窓の中と判定され、親（画面外の行）が省かれたのにその項目だけが出て、箱の上端に描かれた（種 7 の手 33）。
     */
    @Test
    void scrollListPixelsMatchWithAndWithoutScrollContentNode() {
        List<String> bad = new ArrayList<>();
        for (long seed = 1; seed <= 8; seed++) {
            List<Integer> withNode = scrollListPixels(seed, true), rebuilt = scrollListPixels(seed, false);
            for (int i = 0; i < Math.min(withNode.size(), rebuilt.size()); i++) {
                if (!withNode.get(i).equals(rebuilt.get(i))) { bad.add("scrolllist/" + seed + " 手 " + i); break; }
            }
        }
        assertEquals(List.of(), bad);
    }

    /** ホイールで動き始めたスクロールは、減速の途中も止まった後も整数の px にいる（A6b ⑤。小数だと上の 2 つの経路の画素が違う）。 */
    @Test
    void smoothScrollStaysOnWholePixels() {
        try (StandaloneView v = new StandaloneView(ShortcutMatrixMain.scrollList(), 320, 240, com.nishiyu.enderframelib.core.text.model.FontResolver.FALLBACK);
             var be = new MutationScenario.NoDraw()) {
            long now = 1000;
            v.frame(now, be, true);
            var sc = v.root().ownerDocument.getElementById("sc");
            v.mouseMove(100, 100, be.sessionId());
            List<Float> seen = new ArrayList<>();
            for (double notch : new double[] {-1, -3, 2}) {
                v.scroll(0, notch, be.sessionId());
                for (int i = 0; i < 40; i++) {
                    v.frame(now += 7 + (i % 3) * 5, be, true); // フレームの間隔を揺らす（減速の割合が毎回違う）
                    seen.add(v.ui().scrollState().getScrollY(sc));
                }
            }
            assertEquals(true, seen.stream().anyMatch(y -> y > 0), "スクロールした前提: " + seen);
            assertEquals(List.of(), seen.stream().filter(y -> y != Math.rint(y)).toList());
            assertEquals(80f, seen.get(seen.size() - 1), "最後は止まっている（-1 と -3 で 160px、+2 で 80px 戻る）");
        }
    }

    private static List<Integer> pixelsWithDamage(boolean damage, long seed) throws ReflectiveOperationException {
        var f = com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend.class.getDeclaredField("damageEnabled");
        f.setAccessible(true);
        boolean saved = f.getBoolean(null);
        f.setBoolean(null, damage);
        try {
            MutationScenario.run(ShortcutMatrixMain.CARDS, ShortcutMatrixMain.CARD_CLASSES, ShortcutMatrixMain.CARD_STYLES, seed, 45, true);
            return new ArrayList<>(MutationScenario.PIXELS);
        } finally {
            f.setBoolean(null, saved);
        }
    }

    /**
     * 差分描画（変わった範囲だけを塗り直す）の画素が、毎フレーム全面で描いた画素と一致すること（A6b ③④）。以前は、項目の増減のフレームで
     * 遷移中の項目が「止まっている」と記録され、次のフレームで遷移の途中の色が残った（cards・種 6 の手 22 と 34）。
     */
    @Test
    void damageRepaintMatchesFullRepaintEveryStep() throws Exception {
        for (long seed : new long[] {6, 4, 8}) {
            assertEquals(pixelsWithDamage(false, seed), pixelsWithDamage(true, seed), "cards/" + seed + "（手ごとの画素の写し）");
        }
    }

    /**
     * 項目の座標が絶対のとき（親相対を切る。GL など）の局所パッチ（後続の箱の座標の反映）で、箱の高さ・幅が負にならず、
     * 全面の結果と一致すること（A6b ⑤。以前は新しい高さを「下端 - 原点」と「上端」の二重引きで求め、枠線の高さが -150 になった）。
     */
    @Test
    void absoluteCoordinatesPatchMatchesFullRelayoutAndNeverGoesNegative() {
        String key = "enderframe.paint.noparentrelative";
        String saved = System.getProperty(key);
        System.setProperty(key, "true");
        try {
            List<String> bad = new ArrayList<>();
            for (long seed = 1; seed <= 8; seed++) {
                final long sd = seed;
                MutationScenario.STEP_HOOK = (step, view) -> {
                    for (var it : view.items()) if (it.width() < 0 || it.height() < 0) bad.add("nested/" + sd + " 手 " + step + " " + it.id());
                };
                MutationScenario.run(ShortcutMatrixMain.NESTED, ShortcutMatrixMain.NESTED_CLASSES, ShortcutMatrixMain.NESTED_STYLES, 5 * seed + 1, 60, false);
            }
            assertEquals(List.of(), bad.size() > 8 ? bad.subList(0, 8) : bad);
        } finally {
            if (saved == null) System.clearProperty(key); else System.setProperty(key, saved);
        }
    }
}
