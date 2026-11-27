package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.SceneWalkAccess;
import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.paint.PaintSceneDriver.Group;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.PaintOffsets;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

/**
 * 保持層を中身の節に結ぶ（M3b-4b-3b。計画書 §3.4「M3b-4b の設計」）: 走査器は、中身を 1 つの節にまとめたスクロールコンテナの中身の範囲を
 * 自分の項目から求め、中身の同一性の印としてコンテナの層の子の並び（{@code SceneLayer#children}）を描画側へ渡す。印はスクロールだけの
 * フレームで変わらず（保持した層を重ねられる）、中身が変わると変わる。保持の答え（重ねる・記録する・記録に失敗して描き直す）に依らず、
 * 描く項目は保持しない走査と同じになる。
 */
class SceneWalkerRetainTest {

    /** 重なりの文脈を作るスクロールコンテナ（中身の項目が描画順で連続する）。 */
    private static final String DOC = doc("position:relative;z-index:0;");

    /** {@code containerStyle} を足したスクロールコンテナと、その前後の箱の文書。 */
    private static String doc(String containerStyle) {
        StringBuilder html = new StringBuilder("<style>body{margin:0}.r{height:30px}</style><div id=head style='height:30px;background:#999'>head</div>"
                + "<div id=s style='" + containerStyle + "overflow:auto;width:200px;height:120px'>");
        for (int i = 0; i < 10; i++) {
            html.append("<div class=r id=r").append(i).append(" style='background:#").append(i % 2 == 0 ? "c33" : "33c").append("'>row ")
                    .append(i).append("</div>");
        }
        return html.append("</div><div id=tail style='height:40px;background:#9c9'>tail</div>").toString();
    }

    /** 保持の問い合わせと、描いた項目・保持の呼び出しを記録する描画側。答えは {@code answer}（印と範囲から決める）。 */
    private static final class Rec implements Painter {
        final SceneWalker walker;
        final BiFunction<Object, int[], RetainPlan> answer;
        final List<String> calls = new ArrayList<>();
        final List<String> drawn = new ArrayList<>();
        Object token;
        int from = -1, to = -1, node = -1;
        /** 問い合わせた時点の、中身の節の子孫の位置（親の鎖から数え直したもの）。 */
        final List<Integer> descendants = new ArrayList<>();
        boolean endResult = true;

        Rec(SceneWalker walker, BiFunction<Object, int[], RetainPlan> answer) {
            this.walker = walker;
            this.answer = answer;
        }

        @Override public void syncLayers(List<Group> want) { }

        @Override
        public void drawItem(DisplayItem el, int index, float flat) {
            drawn.add(el.id() + "@" + index);
        }

        @Override
        public RetainPlan planRetained(DisplayItem n, int nodeIndex, int f, int t, Object tok) {
            token = tok;
            from = f;
            to = t;
            node = nodeIndex;
            descendants.clear();
            for (int j = 0; j < 100_000; j++) {
                int p = walker.parentIndex(j);
                if (p < 0 && j > t + 64) break;
                for (int guard = 0; p >= 0 && guard < 128; guard++, p = walker.parentIndex(p)) {
                    if (p == nodeIndex) { descendants.add(j); break; }
                }
            }
            RetainPlan r = answer.apply(tok, new int[]{f, t});
            calls.add("plan " + r);
            return r;
        }

        @Override public void drawRetained(DisplayItem n, int nodeIndex, int f, int t) { calls.add("draw " + f + "-" + t); }
        @Override public void beginRetained(DisplayItem n, int nodeIndex, int f, int t) { calls.add("begin " + f + " at " + drawn.size()); }

        @Override
        public boolean endRetained(DisplayItem n, int nodeIndex) {
            calls.add("end at " + drawn.size() + " " + endResult);
            return endResult;
        }
    }

    private static Rec walk(StandaloneView v, SceneWalker walker, long now, BiFunction<Object, int[], RetainPlan> answer) {
        Rec r = new Rec(walker, answer);
        SceneWalkAccess.walk(v, walker, PaintOffsets.EMPTY, now, Matrix2D.IDENTITY, r);
        return r;
    }

    @Test
    void rangeIsTheContentOfTheScrollContainerAndTheTokenSurvivesScrolling() {
        try (SoftwareRenderBackend backend = new SoftwareRenderBackend(300, 300);
             StandaloneView v = new StandaloneView(DOC, 300, 300, FontResolver.FALLBACK)) {
            SceneWalker walker = new SceneWalker(backend.sessionId());
            v.frame(1000L, backend, true);
            Rec first = walk(v, walker, 1000L, (t, r) -> RetainPlan.RECORD);
            assertNotNull(first.token, "中身を 1 つの節にまとめたコンテナの保持を問わない");
            List<Integer> range = new ArrayList<>();
            for (int i = first.from; i < first.to; i++) range.add(i);
            assertEquals(first.descendants, range, "範囲が中身の節の子孫と一致しない");
            assertTrue(first.to - first.from >= 20, "中身の項目が少ない: " + (first.to - first.from));
            // 記録: 範囲の先頭で始め、範囲の後で終える
            assertEquals(List.of("plan RECORD", "begin " + first.from + " at " + countBefore(first, first.from),
                    "end at " + countBefore(first, first.to) + " true"), first.calls);

            // スクロールだけ: 印は同じ（保持した層を重ねられる）
            v.mouseMove(50, 60, backend.sessionId());
            v.scroll(0, -2, backend.sessionId());
            v.frame(1500L, backend, true);
            assertTrue(v.ui().dynamicState().scroll.snapshot().values().stream().anyMatch(xy -> xy[1] > 0f), "前提: 中身がスクロールした");
            Object[] seen = {null};
            Rec scrolled = walk(v, walker, 1500L, (t, r) -> { seen[0] = t; return t == first.token ? RetainPlan.SKIP : RetainPlan.RECORD; });
            assertSame(first.token, seen[0], "スクロールだけで中身の印が変わった（スクロール量を中身に焼き込んだ）");
            assertEquals(List.of("plan SKIP", "draw " + scrolled.from + "-" + scrolled.to), scrolled.calls);
            for (String d : scrolled.drawn) {
                int at = Integer.parseInt(d.substring(d.lastIndexOf('@') + 1));
                assertFalse(at >= scrolled.from && at < scrolled.to, "重ねた範囲の項目を描いた: " + d);
            }

            // 中身が変わると印が変わる
            v.root().getElementById("r3").setAttribute("style", "background:#ff0");
            v.frame(2000L, backend, true);
            Rec changed = walk(v, walker, 2000L, (t, r) -> RetainPlan.NONE);
            assertNotSame(first.token, changed.token, "中身が変わったのに印が同じ");
            // 中身の外が変わっても印は変わらない
            Object before = changed.token;
            v.root().getElementById("tail").setAttribute("style", "height:40px;background:#000");
            v.frame(2500L, backend, true);
            Rec outside = walk(v, walker, 2500L, (t, r) -> RetainPlan.NONE);
            assertSame(before, outside.token, "中身の外の変化で印が変わった");
        }
    }

    @Test
    void everyAnswerDrawsTheSameItemsAsAWalkWithoutRetaining() {
        try (SoftwareRenderBackend backend = new SoftwareRenderBackend(300, 300);
             StandaloneView v = new StandaloneView(DOC, 300, 300, FontResolver.FALLBACK)) {
            SceneWalker walker = new SceneWalker(backend.sessionId());
            v.frame(1000L, backend, true);
            Rec plain = walk(v, walker, 1000L, (t, r) -> RetainPlan.NONE);
            Rec recorded = walk(v, walker, 1000L, (t, r) -> RetainPlan.RECORD);
            assertEquals(plain.drawn, recorded.drawn, "記録しても描く項目は同じ");
            Rec failing = new Rec(walker, (t, r) -> RetainPlan.RECORD);
            failing.endResult = false;
            SceneWalkAccess.walk(v, walker, PaintOffsets.EMPTY, 1000L, Matrix2D.IDENTITY, failing);
            // 記録に失敗したら範囲をふつうに描き直す: 範囲の項目は 2 回（層へ・描き直し）、それ以外は 1 回
            List<String> expected = new ArrayList<>();
            int from = failing.from, to = failing.to;
            for (String d : plain.drawn) {
                expected.add(d);
                int at = Integer.parseInt(d.substring(d.lastIndexOf('@') + 1));
                if (at == lastDrawnIn(plain, from, to)) for (String e : plain.drawn) if (inRange(e, from, to)) expected.add(e);
            }
            assertEquals(expected, failing.drawn, "記録に失敗した範囲を描き直していない");
            Rec skipped = walk(v, walker, 1000L, (t, r) -> RetainPlan.SKIP);
            List<String> outside = new ArrayList<>();
            for (String d : plain.drawn) if (!inRange(d, skipped.from, skipped.to)) outside.add(d);
            assertEquals(outside, skipped.drawn, "重ねたとき、範囲の外の項目は同じに描く");
        }
    }

    /**
     * 重なりの文脈を作らないコンテナの中身の文字は、祖先のインラインの段で後ろの箱（{@code #tail} の背景）の後に描かれる（付録 E）。
     * 中身の項目が描画順で連続しないので、保持には問わない（重ねると後ろの箱との重なりの順が変わる）。
     */
    @Test
    void containerWhoseContentIsInterleavedWithOtherBoxesIsNotOffered() {
        try (SoftwareRenderBackend backend = new SoftwareRenderBackend(300, 300);
             StandaloneView v = new StandaloneView(doc(""), 300, 300, FontResolver.FALLBACK)) {
            SceneWalker walker = new SceneWalker(backend.sessionId());
            v.frame(1000L, backend, true);
            Rec r = walk(v, walker, 1000L, (t, x) -> RetainPlan.SKIP);
            assertTrue(r.calls.isEmpty(), "中身が連続しないコンテナを保持に問うた: " + r.calls);
        }
    }

    @Test
    void containerUnderATranslucentAncestorIsNotOffered() {
        String html = "<style>body{margin:0}</style><div style='opacity:.5'><div style='overflow:auto;width:200px;height:60px'>"
                + "<div style='height:200px;background:#c33'>x</div></div></div>";
        try (SoftwareRenderBackend backend = new SoftwareRenderBackend(300, 200);
             StandaloneView v = new StandaloneView(html, 300, 200, FontResolver.FALLBACK)) {
            SceneWalker walker = new SceneWalker(backend.sessionId());
            v.frame(1000L, backend, true);
            Rec r = walk(v, walker, 1000L, (t, x) -> RetainPlan.SKIP);
            assertTrue(r.calls.isEmpty(), "不透明度のグループの中のコンテナを保持に問うた: " + r.calls);
            assertFalse(r.drawn.isEmpty());
        }
    }

    private static boolean inRange(String drawn, int from, int to) {
        int at = Integer.parseInt(drawn.substring(drawn.lastIndexOf('@') + 1));
        return at >= from && at < to;
    }

    private static int lastDrawnIn(Rec plain, int from, int to) {
        int last = -1;
        for (String d : plain.drawn) if (inRange(d, from, to)) last = Integer.parseInt(d.substring(d.lastIndexOf('@') + 1));
        return last;
    }

    /** 位置 {@code index} より前に描いた項目の数。 */
    private static int countBefore(Rec r, int index) {
        int n = 0;
        for (String d : r.drawn) if (Integer.parseInt(d.substring(d.lastIndexOf('@') + 1)) < index) n++;
        return n;
    }
}
