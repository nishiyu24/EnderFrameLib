package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.core.kernel.Clock;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * K0 ③ フレームのオラクル（{@link FrameOracle}）の検証: ①健全なエンジンでは偽陽性が 0 ②<b>無効化の漏れ</b>の変異を全て殺す
 * ③ファズで偽陽性 0 ④既知の欠陥を 1 件固定する。
 *
 * <p>変異の作り方: 変更を<b>通知なしに</b>入れる（{@code attrsForWrite()} への直接の書き込みは無効化を起こさない）。今の面は古い箱の木のまま、
 * DOM だけが新しくなる ＝ 「レイアウトが走るべきなのに走らなかった」状態そのもの。最初から作った面は新しい DOM を読むので、箱が食い違う。
 * 場面は、過去に不具合になった 5 つの形〔外の包含ブロックの検査・スクロール版・子のスクロール・包含ブロック切り替えの特例・純粋性の素通し〕。
 * 各場面で、<b>通知つきの正しい変更では null</b>（偽陽性なし）、<b>通知なしの変異では差が出る</b>（殺す）ことを確かめる。
 * 保持を<em>戻して</em>欠陥を再現する変異はコードにスイッチを足さないと作れない（運用ルール 8）ので、漏れの型で表す。</p>
 */
class FrameOracleTest {

    @AfterEach
    void clear() {
        MutationScenario.STEP_HOOK = null;
    }

    /** 変更の入れ方。{@code notify} は通常の API（無効化が起きる）、{@code silent} は属性の直書き（無効化の漏れ）。 */
    private record Change(String id, String attr, String value) {
        void apply(StandaloneView v, boolean notify) {
            HtmlElement el = (HtmlElement) v.root().ownerDocument.getElementById(id);
            if (notify) el.setAttribute(attr, value);
            else el.attrsForWrite().put(attr, value);
        }
    }

    private record Scene(String name, String html, Change change, FrameOracle.Scope scope) { }

    private static final String CSS = "<style>body{margin:0}</style>";

    /** 過去に不具合になった 5 つの形。 */
    private static final List<Scene> SCENES = List.of(
            // 外の包含ブロックの検査: abs 子孫の位置は、部分木の外の包含ブロックの幅に依る
            new Scene("外の包含ブロック", CSS + "<div id=o style=\"position:relative;width:200px;height:100px\"><div><div style=\"position:absolute;right:0;bottom:0;width:10px;height:10px\"></div></div></div>",
                    new Change("o", "style", "position:relative;width:300px;height:100px"), FrameOracle.Scope.GEOMETRY),
            // スクロール版: スクロールコンテナの中身が変わると、スクロール範囲が変わる
            new Scene("スクロール版", CSS + "<div id=sc style=\"overflow:auto;height:50px;width:100px\"><div id=in style=\"height:200px\"></div></div>",
                    new Change("in", "style", "height:400px"), FrameOracle.Scope.GEOMETRY_AND_SCROLL_EXTENTS),
            // 子のスクロール: 入れ子のスクロールコンテナの内側の変更が、外のスクロール範囲へ届く
            new Scene("子のスクロール", CSS + "<div style=\"overflow:auto;height:60px;width:120px\"><div style=\"overflow:auto;height:300px;width:100px\"><div id=deep style=\"height:100px\"></div></div></div>",
                    new Change("deep", "style", "height:500px"), FrameOracle.Scope.GEOMETRY_AND_SCROLL_EXTENTS),
            // 包含ブロック切り替え: 祖先に transform が付くと、abs 子孫の包含ブロックが変わる（C9b で特例を足した欠陥の形）
            new Scene("包含ブロック切り替え", CSS + "<div id=t style=\"width:200px;height:50px;margin:20px\"><div style=\"position:absolute;left:0;top:0;width:5px;height:5px\"></div></div>",
                    new Change("t", "style", "width:200px;height:50px;margin:20px;transform:translateX(0)"), FrameOracle.Scope.GEOMETRY),
            // 純粋性の素通し: 外へ何も読まないように見える部分木の中の変更（幅の指定）も、レイアウトへ届く
            new Scene("純粋性の素通し", CSS + "<div><div><p id=p style=\"height:10px\">hi</p><p style=\"height:10px\">there</p></div></div>",
                    new Change("p", "style", "height:10px;width:30px;margin-left:15px"), FrameOracle.Scope.GEOMETRY));

    private static StandaloneView open(Scene s) {
        StandaloneView v = new StandaloneView(s.html(), 320, 240, FontResolver.FALLBACK);
        v.clock(new Clock.Manual());
        return v;
    }

    /** 偽陽性なし: 通知つきの正しい変更のあと、今の面は最初から作った面と一致する（変更の前も）。 */
    @Test
    void healthyEngineMatchesTheFreshSurface() {
        for (Scene s : SCENES) {
            try (StandaloneView v = open(s); var be = new MutationScenario.NoDraw()) {
                v.frame(1000, be, true);
                assertNull(FrameOracle.firstDifference(v, s.scope()), s.name() + ": 変更の前");
                s.change().apply(v, true);
                v.frame(1016, be, true);
                assertNull(FrameOracle.firstDifference(v, s.scope()), s.name() + ": 通知つきの変更のあと");
            }
        }
    }

    /** 殺す: 無効化の漏れ（通知なしの変更）の変異は、全ての場面でオラクルが検出する。 */
    @Test
    void invalidationLeakMutantsAreAllKilled() {
        List<String> survived = new ArrayList<>();
        for (Scene s : SCENES) {
            try (StandaloneView v = open(s); var be = new MutationScenario.NoDraw()) {
                v.frame(1000, be, true);
                s.change().apply(v, false); // 漏れ: DOM だけが変わり、レイアウトは走らない
                v.frame(1016, be, true);
                if (FrameOracle.firstDifference(v, s.scope()) == null) survived.add(s.name());
            }
        }
        assertEquals(List.of(), survived, "オラクルが見逃した変異（無効化の漏れ）");
    }

    /** オラクルは今の面を変えない（箱の木・DOM・表示項目の数が呼ぶ前と同じ）。 */
    @Test
    void oracleDoesNotDisturbTheLiveSurface() {
        Scene s = SCENES.get(0);
        try (StandaloneView v = open(s); var be = new MutationScenario.NoDraw()) {
            v.frame(1000, be, true);
            List<String> before = FrameOracle.boxes(TestBoxes.root(v.ui()), true);
            int items = v.ui().relativeElements().size();
            String dom = v.root().children.toString();
            for (int i = 0; i < 3; i++) assertNull(FrameOracle.firstDifference(v, FrameOracle.Scope.GEOMETRY_AND_SCROLL_EXTENTS));
            assertEquals(before, FrameOracle.boxes(TestBoxes.root(v.ui()), true));
            assertEquals(items, v.ui().relativeElements().size());
            assertEquals(dom, v.root().children.toString());
        }
    }

    /** 変異の殺しは検出器の感度であって偶然でない: 差の説明に、どの箱が・どちらがどの値かが出る。 */
    @Test
    void differenceNamesTheBoxAndBothValues() {
        Scene s = SCENES.get(0);
        try (StandaloneView v = open(s); var be = new MutationScenario.NoDraw()) {
            v.frame(1000, be, true);
            s.change().apply(v, false);
            v.frame(1016, be, true);
            FrameOracle.Difference d = FrameOracle.firstDifference(v);
            assertNotNull(d);
            assertTrue(d.live().contains("200.0") && d.fresh().contains("300.0"), d.toString());
        }
    }

    // ---- ファズ: 偽陽性 0 ----

    /** 毎手の増分の結果が、最初から作った面と一致する（偽陽性 0）。種の数は {@code -Doracle.seeds}（既定 60。完了の判定は 1500）。 */
    @Test
    @Tag("slow")
    void layoutsFuzzHasNoDifferenceFromTheFreshSurface() {
        int seeds = Integer.getInteger("oracle.seeds", 60);
        MutationFuzz.Doc doc = MutationFuzz.DOCS.get("layouts");
        List<String> bad = new ArrayList<>();
        for (long seed = 1; seed <= seeds && bad.size() < 5; seed++) {
            final long sd = seed;
            MutationScenario.STEP_HOOK = (step, view) -> {
                FrameOracle.Difference d = FrameOracle.firstDifference(view);
                if (d != null && bad.size() < 5) bad.add("layouts/" + sd + " 手 " + step + " (" + MutationScenario.lastLog() + ") " + d);
            };
            MutationScenario.run(doc.html(), doc.classes(), doc.styles(), seed, doc.steps(), false);
        }
        assertEquals(List.of(), bad);
    }

    // ---- 回帰: display 変更後のスクロール範囲（§6-182） ----

    /**
     * 回帰（§6-182）: {@code layouts} 種 1・種 3 を含む display 型変更（{@code inline}→{@code grid}/{@code flex} など）後も、
     * 遅延消去待ちの古い {@code inlineFragments} で {@code scrollWidth}/{@code scrollHeight} が壊れず、最初から作った面と一致する。
     */
    @Test
    void scrollExtentMatchesAfterDisplayTypeChange() {
        MutationFuzz.Doc doc = MutationFuzz.DOCS.get("layouts");
        for (long seed : new long[]{1L, 3L}) {
            MutationScenario.STEP_HOOK = (step, view) -> {
                FrameOracle.Difference d = FrameOracle.firstDifference(view, FrameOracle.Scope.GEOMETRY_AND_SCROLL_EXTENTS);
                assertNull(d, "layouts/" + seed + " 手 " + step + " (" + MutationScenario.lastLog() + "): " + d);
            };
            MutationScenario.run(doc.html(), doc.classes(), doc.styles(), seed, doc.steps(), false);
        }
    }
}
