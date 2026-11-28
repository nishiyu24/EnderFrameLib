package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.SceneWalkAccess;
import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.paint.SceneWalker.Group;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.PaintOffsets;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * 部分の描き直しの範囲（M3b-4b-3b。計画書 §3.4「M3b-4b の設計」）の確かめ: {@link SceneDamage} がシーンの違いとスクロール量から、
 * {@link SceneWalker} が補間・外の事情で変わる項目から求めた範囲が、<b>前後のフレームを全面で描いた画素の違いを全て覆う</b>こと
 * （表示項目の列も旧経路も見ない、外から見た正しさ）と、小さな変化で範囲が小さいこと。
 */
class SceneDamageTest {

    /** 何もしない描画側（走査器に範囲を足させるだけ）。 */
    private static final Painter NOOP = new Painter() {
        @Override public void syncLayers(List<Group> want) { }
        @Override public void drawItem(DisplayItem el, int index, float flat) { }
    };

    /** 前のフレームの画素と、今のフレームの画素・範囲を比べ、範囲の外で変わった画素の数を返す（{@code where} に最初の数個）。 */
    static int uncovered(int[] before, int[] after, int w, int h, SceneDamage.Rects damage, StringBuilder where) {
        if (damage.full()) return 0;
        int miss = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if (before[i] != after[i] && !damage.covers(x + 0.5f, y + 0.5f)) {
                    if (miss++ < 6 && where != null) where.append(" (").append(x).append(',').append(y).append(')');
                }
            }
        }
        return miss;
    }

    /** 範囲が覆う面積の割合（矩形の和の外接ではなく、画素で数える）。 */
    static double coverage(SceneDamage.Rects damage, int w, int h) {
        if (damage.full()) return 1.0;
        int hit = 0;
        for (int y = 0; y < h; y += 2) for (int x = 0; x < w; x += 2) if (damage.covers(x + 0.5f, y + 0.5f)) hit++;
        return hit / (double) ((w + 1) / 2 * ((h + 1) / 2));
    }

    // ---- 実文書の全フレーム ----

    @TestFactory
    Stream<DynamicTest> damageCoversEveryChangedPixelOfTheRealDocuments() {
        return RealDocumentFramesTest.DOCS.stream().map(doc -> DynamicTest.dynamicTest(doc, () -> coversRealDocument(doc)));
    }

    private static void coversRealDocument(String doc) throws Exception {
        int w = RealDocumentFramesTest.W, h = RealDocumentFramesTest.H;
        try (Timed backend = new Timed(w, h)) {
            SceneDamage damage = new SceneDamage();
            SceneWalker walker = new SceneWalker(backend.sessionId());
            int[][] prev = {null};
            int[] frames = {0}, partial = {0};
            double[] area = {0};
            StringBuilder report = new StringBuilder();
            java.util.Map<String, Integer> reasons = new java.util.TreeMap<>();
            RealDocumentFramesTest.scriptWithView(doc, backend, false, true, (v, f) -> {
                SceneDamage.Rects r = SceneWalkAccess.damage(v, damage, walker, backend.now, NOOP);
                int[] px = f.pixels();
                if (prev[0] != null) {
                    StringBuilder where = new StringBuilder();
                    int miss = uncovered(prev[0], px, w, h, r, where);
                    if (miss > 0) report.append("\n  ").append(f.name()).append(": 範囲の外で ").append(miss).append(" 画素が変わった").append(where);
                    frames[0]++;
                    if (r.full()) reasons.merge(r.fullReason(), 1, Integer::sum);
                    if (!r.full()) {
                        partial[0]++;
                        area[0] += coverage(r, w, h);
                    }
                }
                prev[0] = px;
            });
            System.out.printf("[scene-damage] %s frames=%d partial=%d meanArea=%.3f full=%s%n", doc, frames[0], partial[0],
                    partial[0] > 0 ? area[0] / partial[0] : 1.0, reasons);
            if (!report.isEmpty()) fail(doc + ":" + report);
            assertTrue(frames[0] >= 8, doc + ": 比べたフレームが少ない（" + frames[0] + "）");
        }
    }

    /** CPU で全面を描き、描いた時刻を覚える描画器（手順の時刻を走査器の補間に渡す）。 */
    private static final class Timed implements RenderBackend {
        final SoftwareRenderBackend real;
        long now;

        Timed(int w, int h) { real = new SoftwareRenderBackend(w, h).partialRepaint(false); }

        // 毎回全面で描く（時刻だけを覚える）
        @Override public void render(com.nishiyu.enderframelib.core.paint.PreparedFrame frame) {
            com.nishiyu.enderframelib.core.paint.RenderFrame f = frame.frame();
            real.render(f);
            now = f.nowMs();
        }
        @Override public Pixmap pixmap() { return real.pixmap(); }
        @Override public float devicePixelRatio() { return 1f; }
        @Override public String sessionId() { return real.sessionId(); }
        @Override public void close() { real.close(); }
    }

    // ---- 小さな文書 ----

    /** {@code html} を描き、{@code change} の後をもう 1 度描いて、変わった画素が範囲に入ることと範囲の割合を返す。 */
    private static double check(String html, BiConsumer<StandaloneView, String> change) {
        return check(html, change, new SceneDamage());
    }

    private static double check(String html, BiConsumer<StandaloneView, String> change, SceneDamage damage) {
        int w = 400, h = 300;
        try (SoftwareRenderBackend backend = new SoftwareRenderBackend(w, h);
             StandaloneView v = new StandaloneView(html, w, h, FontResolver.FALLBACK)) {
            SceneWalker walker = new SceneWalker(backend.sessionId());
            v.frame(1000L, backend, true);
            assertTrue(SceneWalkAccess.damage(v, damage, walker, 1000L, NOOP).full(), "初回は全面");
            int[] before = backend.pixmap().pixels().clone();
            change.accept(v, backend.sessionId());
            v.frame(5000L, backend, true);
            SceneDamage.Rects r = SceneWalkAccess.damage(v, damage, walker, 5000L, NOOP);
            int[] after = backend.pixmap().pixels();
            StringBuilder where = new StringBuilder();
            int miss = uncovered(before, after, w, h, r, where);
            assertEquals(0, miss, "範囲の外で画素が変わった:" + where);
            boolean changed = false;
            for (int i = 0; i < before.length && !changed; i++) changed = before[i] != after[i];
            assertTrue(changed, "前提: 絵が変わった");
            return coverage(r, w, h);
        }
    }

    private static final String LIST = "<style>body{margin:0;font:14px sans-serif}.r{height:24px;margin:2px;background:#ddd}</style>"
            + "<div id=l><div class=r id=a>A</div><div class=r id=b>B</div><div class=r id=c>C</div><div class=r id=d>D</div>"
            + "<div class=r id=e>E</div><div class=r id=f>F</div></div><p id=t>tail text</p>";

    @Test
    void colourChangeOfOneRowDamagesOnlyThatRow() {
        double area = check(LIST, (v, s) -> v.root().getElementById("c").setAttribute("style", "background:#c33"));
        assertTrue(area < 0.15, "1 行の色の変化で範囲が広い: " + area);
    }

    /**
     * M3b-4c-1a: 長いリストの 1 行の変化は、前と同じ位置の同じチャンク（{@code SceneEmitter} が使い回した 32 子単位）へ下りずに飛ばし、
     * 変わった行のチャンクだけを対にする。範囲は変わった画素を覆う（飛ばしたチャンクに変化が無いことは画素で確かめる）。
     */
    @Test
    void unchangedChunksOfALongListAreSkippedWithoutVisitingTheirRows() {
        StringBuilder html = new StringBuilder("<style>body{margin:0;font:10px sans-serif}.r{height:3px;margin:0;background:#ddd}</style><div id=l>");
        for (int i = 0; i < 320; i++) html.append("<div class=r id=r").append(i).append("></div>");
        html.append("</div>");
        SceneDamage damage = new SceneDamage();
        double area = check(html.toString(), (v, s) -> v.root().getElementById("r40").setAttribute("style", "background:#c33"), damage);
        assertTrue(area < 0.05, "1 行の色の変化で範囲が広い: " + area);
        assertTrue(damage.skippedChunks() >= 8, "変わらないチャンクを飛ばしていない: " + damage.skippedChunks());
        assertTrue(damage.visitedPairs() < 80, "変わらない行まで対にしてたどった: " + damage.visitedPairs());
    }

    /** 前の行が消えてチャンクの位置がずれたら、同じチャンクでも飛ばさない（後ろの行が上へ動いた範囲を覆う）。 */
    @Test
    void shiftedChunksAreNotSkipped() {
        StringBuilder html = new StringBuilder("<style>body{margin:0;font:10px sans-serif}.r{height:3px;margin:0}</style><div id=l>");
        for (int i = 0; i < 200; i++) html.append("<div class=r id=r").append(i).append(" style='background:#").append(i % 2 == 0 ? "c33" : "33c").append("'></div>");
        html.append("</div>");
        check(html.toString(), (v, s) -> v.root().getElementById("r3").remove());
    }

    /**
     * 先頭に行を足すと、後ろのチャンクは同じ実体（{@code ==}）のまま下へずれて使い回される（{@code SceneEmitter} はチャンクの位置だけを
     * 変える）。位置の変わったチャンクは飛ばさない（飛ばすと、下へずれた行の跡が描き直されない）。
     */
    @Test
    void chunksMovedByAnInsertedRowAreNotSkipped() {
        StringBuilder html = new StringBuilder("<style>body{margin:0;font:10px sans-serif}.r{height:3px;margin:0}</style><div id=l>");
        for (int i = 0; i < 200; i++) html.append("<div class=r id=r").append(i).append(" style='background:#").append(i % 2 == 0 ? "c33" : "33c").append("'></div>");
        html.append("</div>");
        check(html.toString(), (v, s) -> {
            var row = new com.nishiyu.enderframelib.core.dom.node.HtmlElement("div");
            row.setAttribute("class", "r");
            row.setAttribute("style", "background:#3c3;height:5px");
            v.root().getElementById("l").insertBefore(row, v.root().getElementById("r0"));
        });
    }

    @Test
    void removedRowDamagesTheRowsThatMovedUp() {
        double area = check(LIST, (v, s) -> v.root().getElementById("b").remove());
        assertTrue(area < 0.8, "範囲が広い: " + area);
    }

    /** 消えた箱の跡（後ろの箱が動かないので、動いた箱の範囲には入らない）。 */
    @Test
    void removedAbsoluteBoxIsCovered() {
        String html = "<style>body{margin:0}</style><div style='height:200px;background:#eee'></div>"
                + "<div id=p style='position:absolute;left:150px;top:120px;width:60px;height:50px;background:#c33'></div>";
        double area = check(html, (v, s) -> v.root().getElementById("p").remove());
        assertTrue(area < 0.2, "範囲が広い: " + area);
    }

    /** 行の高さより大きい文字（字形が項目の箱の外へはみ出す）を変えても覆う（文字の大きさの余白）。 */
    @Test
    void textOverflowingItsLineIsCovered() {
        String html = "<style>body{margin:0}</style><p id=t style='margin:80px 40px;font-size:40px;line-height:4px'>Big Wg</p>";
        check(html, (v, s) -> v.root().getElementById("t").setTextContent("Jog Qy"));
    }

    @Test
    void textChangeIsCovered() {
        check(LIST, (v, s) -> v.root().getElementById("t").setTextContent("a much longer tail text than before"));
    }

    @Test
    void removedOffsetShadowIsCovered() {
        String html = "<style>body{margin:0}</style><div id=s style='margin:40px;width:60px;height:40px;background:#33c;"
                + "box-shadow:30px 25px 6px #000'></div><div style='height:20px;background:#eee'></div>";
        double area = check(html, (v, s) -> v.root().getElementById("s").setAttribute("style", "margin:40px;width:60px;height:40px;background:#33c"));
        assertTrue(area < 0.3, "範囲が広い: " + area);
    }

    @Test
    void overlappingSiblingsSwappingPaintOrderAreCovered() {
        String html = "<style>body{margin:0}.p{position:absolute;width:80px;height:80px}</style>"
                + "<div id=x class=p style='left:20px;top:20px;background:#c33'></div>"
                + "<div id=y class=p style='left:60px;top:60px;background:#3c3'></div>";
        check(html, (v, s) -> {
            var x = v.root().getElementById("x");
            var parent = x.getParentNode();
            parent.removeChild(x);
            parent.appendChild(x);
        });
    }

    @Test
    void rotatedBoxChangeIsCovered() {
        String html = "<style>body{margin:0}</style><div id=r style='margin:60px;width:120px;height:20px;background:#c33;"
                + "transform:rotate(30deg)'></div>";
        check(html, (v, s) -> v.root().getElementById("r").setAttribute("style",
                "margin:60px;width:120px;height:20px;background:#3c3;transform:rotate(60deg)"));
    }

    @Test
    void scrollOfAnInnerContainerDamagesOnlyTheContainer() {
        StringBuilder html = new StringBuilder("<style>body{margin:0}.r{height:30px}</style><div id=head style='height:40px;background:#ccc'></div>"
                + "<div id=s style='overflow:auto;width:200px;height:120px'>");
        for (int i = 0; i < 12; i++) html.append("<div class=r style='background:#").append(i % 2 == 0 ? "c33" : "33c").append("'>").append(i).append("</div>");
        html.append("</div><div style='height:60px;background:#9c9'></div>");
        double area = check(html.toString(), (v, s) -> {
            v.mouseMove(50, 90, s);
            v.scroll(0, -2, s);
        });
        assertTrue(area < 0.3, "内側のスクロールで範囲が広い: " + area);
    }
}
