package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.SceneWalkAccess;
import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.paint.SceneWalker.Group;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
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

    /**
     * 保持の問い合わせと、描いた項目・保持の呼び出しを記録する描画側。答えは {@code answer}（印と中身の節の ID から決める）。
     * 描いた項目は、中身の節の子孫（項目の文脈から中身の節を引ける）なら {@code |c} を付けて記録する。
     */
    private static final class Rec implements Painter {
        final BiFunction<Object, String, RetainPlan> answer;
        final List<String> calls = new ArrayList<>();
        final List<String> drawn = new ArrayList<>();
        Object token;
        String contentId, ownerId;
        com.nishiyu.enderframelib.core.paint.model.ClipRect clip;
        boolean retainable;
        boolean endResult = true;

        Rec(BiFunction<Object, String, RetainPlan> answer) {
            this.answer = answer;
        }

        @Override public void syncLayers(List<Group> want) { }

        @Override
        public void drawItem(DisplayItem el, LayerContext.Item item, float flat) {
            drawn.add(el.id() + (contentId != null && item.owner(contentId) != null ? "|c" : ""));
        }

        @Override
        public RetainPlan planRetained(LayerContext.Content content) {
            token = content.token();
            contentId = content.id();
            ownerId = content.ownerId();
            clip = content.clip();
            retainable = content.retainable();
            assertNotNull(content.resolved(), "中身の節の姿勢");
            assertNotNull(content.owner(ownerId), "コンテナは中身の節の祖先");
            RetainPlan r = answer.apply(token, contentId);
            calls.add("plan " + r);
            return r;
        }

        @Override public void drawRetained(LayerContext.Content c) { calls.add("draw " + c.id()); }
        @Override public void beginRetained(LayerContext.Content c) { calls.add("begin at " + drawn.size()); }

        @Override
        public boolean endRetained(LayerContext.Content c) {
            calls.add("end at " + drawn.size() + " " + endResult);
            return endResult;
        }

        /** 中身の節の子孫として描いた項目の位置（描いた順）。 */
        List<Integer> contentAt() {
            List<Integer> at = new ArrayList<>();
            for (int i = 0; i < drawn.size(); i++) if (drawn.get(i).endsWith("|c")) at.add(i);
            return at;
        }
    }

    private static Rec walk(StandaloneView v, SceneWalker walker, long now, BiFunction<Object, String, RetainPlan> answer) {
        Rec r = new Rec(answer);
        SceneWalkAccess.walk(v, walker, now, Matrix2D.IDENTITY, r);
        return r;
    }

    @Test
    void rangeIsTheContentOfTheScrollContainerAndTheTokenSurvivesScrolling() {
        try (SoftwareRenderBackend backend = new SoftwareRenderBackend(300, 300);
             StandaloneView v = new StandaloneView(DOC, 300, 300, FontResolver.FALLBACK)) {
            SceneWalker walker = new SceneWalker(backend.sessionId());
            v.frame(1000L, backend, true);
            Rec plain = walk(v, walker, 1000L, (t, id) -> RetainPlan.NONE);
            Rec first = walk(v, walker, 1000L, (t, id) -> RetainPlan.RECORD);
            assertNotNull(first.token, "中身を 1 つの節にまとめたコンテナの保持を問わない");
            List<Integer> content = plain.contentAt();
            assertTrue(content.size() >= 20, "中身の項目が少ない: " + content.size());
            int from = content.get(0), to = content.get(content.size() - 1) + 1;
            assertEquals(to - from, content.size(), "中身の項目が描画順で連続しない: " + plain.drawn);
            // 記録: 中身の先頭の前で始め、末尾の後で終える（記録しても描く項目は同じ）
            assertEquals(plain.drawn, first.drawn);
            assertEquals(List.of("plan RECORD", "begin at " + from, "end at " + to + " true"), first.calls);

            // スクロールだけ: 印は同じ（保持した層を重ねられる）
            v.mouseMove(50, 60, backend.sessionId());
            v.scroll(0, -2, backend.sessionId());
            v.frame(1500L, backend, true);
            assertTrue(v.ui().dynamicState().scroll.snapshot().values().stream().anyMatch(xy -> xy[1] > 0f), "前提: 中身がスクロールした");
            Object[] seen = {null};
            Rec scrolled = walk(v, walker, 1500L, (t, id) -> { seen[0] = t; return t == first.token ? RetainPlan.SKIP : RetainPlan.RECORD; });
            assertSame(first.token, seen[0], "スクロールだけで中身の印が変わった（スクロール量を中身に焼き込んだ）");
            assertEquals(List.of("plan SKIP", "draw " + first.contentId), scrolled.calls);
            assertTrue(scrolled.contentAt().isEmpty(), "重ねた中身の項目を描いた: " + scrolled.drawn);
            assertFalse(scrolled.drawn.isEmpty(), "中身の外の項目は描く");

            // 中身が変わると印が変わる
            v.root().getElementById("r3").setAttribute("style", "background:#ff0");
            v.frame(2000L, backend, true);
            Rec changed = walk(v, walker, 2000L, (t, id) -> RetainPlan.NONE);
            assertNotSame(first.token, changed.token, "中身が変わったのに印が同じ");
            // 中身の外が変わっても印は変わらない
            Object before = changed.token;
            v.root().getElementById("tail").setAttribute("style", "height:40px;background:#000");
            v.frame(2500L, backend, true);
            Rec outside = walk(v, walker, 2500L, (t, id) -> RetainPlan.NONE);
            assertSame(before, outside.token, "中身の外の変化で印が変わった");
        }
    }

    @Test
    void everyAnswerDrawsTheSameItemsAsAWalkWithoutRetaining() {
        try (SoftwareRenderBackend backend = new SoftwareRenderBackend(300, 300);
             StandaloneView v = new StandaloneView(DOC, 300, 300, FontResolver.FALLBACK)) {
            SceneWalker walker = new SceneWalker(backend.sessionId());
            v.frame(1000L, backend, true);
            Rec plain = walk(v, walker, 1000L, (t, id) -> RetainPlan.NONE);
            Rec recorded = walk(v, walker, 1000L, (t, id) -> RetainPlan.RECORD);
            assertEquals(plain.drawn, recorded.drawn, "記録しても描く項目は同じ");
            Rec failing = new Rec((t, id) -> RetainPlan.RECORD);
            failing.endResult = false;
            SceneWalkAccess.walk(v, walker, 1000L, Matrix2D.IDENTITY, failing);
            // 記録に失敗したら中身をふつうに描き直す: 中身の項目は 2 回（層へ・描き直し）、それ以外は 1 回
            List<Integer> content = plain.contentAt();
            int last = content.get(content.size() - 1);
            List<String> expected = new ArrayList<>();
            for (int i = 0; i < plain.drawn.size(); i++) {
                expected.add(plain.drawn.get(i));
                if (i == last) for (int c : content) expected.add(plain.drawn.get(c));
            }
            assertEquals(expected, failing.drawn, "記録に失敗した中身を描き直していない");
            Rec skipped = walk(v, walker, 1000L, (t, id) -> RetainPlan.SKIP);
            List<String> outside = new ArrayList<>();
            for (String d : plain.drawn) if (!d.endsWith("|c")) outside.add(d);
            assertEquals(outside, skipped.drawn, "重ねたとき、中身の外の項目は同じに描く");
        }
    }

    /**
     * 中身の文脈（M3b-4c-2b-3b-2b-4a）: コンテナの ID・コンテナの切り取りを持つ中身のクリップ・中身が層に向くか。描画側は列の範囲を
     * 読まずにこれだけで保持層を作る。中身に動くアニメーションがあると層に向かない。
     */
    @Test
    void contentContextCarriesTheContainerClipAndWhetherTheContentIsRetainable() {
        try (SoftwareRenderBackend backend = new SoftwareRenderBackend(300, 300);
             StandaloneView v = new StandaloneView(DOC, 300, 300, FontResolver.FALLBACK)) {
            SceneWalker walker = new SceneWalker(backend.sessionId());
            v.frame(1000L, backend, true);
            Rec r = walk(v, walker, 1000L, (t, id) -> RetainPlan.NONE);
            assertTrue(r.contentId.startsWith(r.ownerId) && !r.contentId.equals(r.ownerId), r.contentId + " / " + r.ownerId);
            assertNotNull(r.clip, "コンテナの切り取りを持つクリップ");
            boolean owned = r.ownerId.equals(r.clip.ownerId())
                    || r.clip.chain().stream().anyMatch(p -> r.ownerId.equals(p.ownerId()));
            assertTrue(owned, "クリップにコンテナが持ち主の部品が無い");
            assertTrue(r.retainable, "静止した中身は層に向く");
        }
        String animated = DOC.replace("<div class=r id=r4 style='",
                "<style>@keyframes p{from{opacity:1}to{opacity:.2}}</style><div class=r id=r4 style='animation:p 1s infinite;");
        assertNotEquals(DOC, animated, "前提: 行にアニメーションを足した");
        try (SoftwareRenderBackend backend = new SoftwareRenderBackend(300, 300);
             StandaloneView v = new StandaloneView(animated, 300, 300, FontResolver.FALLBACK)) {
            SceneWalker walker = new SceneWalker(backend.sessionId());
            v.frame(1000L, backend, true);
            Rec r = walk(v, walker, 1000L, (t, id) -> RetainPlan.NONE);
            assertNotNull(r.token, "前提: 中身を保持に問う");
            assertFalse(r.retainable, "動くアニメーションのある中身を層に向くと答えた");
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
            Rec r = walk(v, walker, 1000L, (t, id) -> RetainPlan.SKIP);
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
            Rec r = walk(v, walker, 1000L, (t, id) -> RetainPlan.SKIP);
            assertTrue(r.calls.isEmpty(), "不透明度のグループの中のコンテナを保持に問うた: " + r.calls);
            assertFalse(r.drawn.isEmpty());
        }
    }
}
