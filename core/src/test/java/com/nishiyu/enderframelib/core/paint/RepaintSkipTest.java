package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.PaintOffsets;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 描かないフレームの判定（M3b-4b-4b。{@link RenderBackend#needsRepaint}）: 前に描いたフレームの画素を使い回すホスト（MOD の面）が、
 * 描画器に「描き直す範囲が空か」を描く前に聞き、空なら描かない。旧 {@code StaticFrameGate} の項目の同一性の後追い照合を、シーンの違い
 * （{@link SceneDamage}）と走査器が足すシーンの外の変化（補間・外の事情・重ね描き）に置き換えた。
 *
 * <p>正しさは外から見た画素で確かめる: 描かないと答えたフレームも含めて、ホストが持ち続けた画素が、そのフレームを<b>新しい描画器で全面に
 * 描いた画素</b>と 1 画素も違わないこと（実文書 9 本の全フレームと、変化の種類ごとの小さな文書）。判定が描くと答えすぎていないこと
 * （何も変わらないフレームで描かない）も確かめる。
 */
class RepaintSkipTest {
    private static final int W = 320, H = 240;

    @Test
    void skippedFramesMatchAFreshFullRepaintOnEveryRealDocumentFrame() throws Exception {
        int skippedTotal = 0;
        for (String doc : RealDocumentFramesTest.DOCS) {
            try (SkipTwin twin = new SkipTwin(RealDocumentFramesTest.W, RealDocumentFramesTest.H)) {
                RealDocumentFramesTest.scriptWithView(doc, twin, false, false, (v, f) -> { });
                System.out.printf("[repaint-skip] %s frames=%d skipped=%d%n", doc, twin.frames, twin.skipped);
                if (!twin.report.isEmpty()) fail(doc + ":" + twin.report);
                assertTrue(twin.frames >= 8, doc + ": 比べたフレームが少ない（" + twin.frames + "）");
                skippedTotal += twin.skipped;
            }
        }
        // 前提: 描かないと答えたフレームがある（リサイズの反映を待つ間・落ち着かせる間は入力が変わらない）
        assertTrue(skippedTotal > 0, "描かないと答えたフレームが 1 つも無い（判定が常に描くと答えている）");
    }

    @Test
    void stillFramesAreSkippedAndEveryKindOfChangeIsPainted() {
        String html = "<style>body{margin:0;font:13px sans-serif}#box{width:80px;height:30px;background:#36c}"
                + "#sc{height:60px;overflow-y:auto;background:#eef}#sc p{margin:0;height:20px}</style>"
                + "<div id='box'>box</div><p id='t'>text</p><div id='sc'><p>1</p><p>2</p><p>3</p><p>4</p><p>5</p><p>6</p></div>";
        try (StandaloneView v = new StandaloneView(html, W, H, FontResolver.FALLBACK);
             SkipTwin twin = new SkipTwin(W, H)) {
            long t = 1000;
            assertTrue(twin.step(v, t), "初めのフレームは描く");
            assertFalse(twin.step(v, t += 16), "何も変わらないフレームは描かない");
            assertFalse(twin.step(v, t += 16), "何度でも描かない");

            v.root().getElementById("box").setAttribute("style", "background:#c33");
            assertTrue(twin.step(v, t += 16), "DOM が変わったら描く");
            assertFalse(twin.step(v, t += 16));

            v.mouseMove(40, 100, twin.sessionId());
            v.scroll(0, -1, twin.sessionId());
            boolean painted = false;
            for (int i = 0; i < 100; i++) painted |= twin.step(v, t += 16); // 滑らかなスクロールが止まるまで
            assertTrue(painted, "スクロールしたら描く");
            assertFalse(twin.step(v, t += 16), "スクロールが止まれば描かない");

            v.ui().setPaintOffset("box", 12f, 3f);
            assertTrue(twin.step(v, t += 16), "利用者の平行移動が変わったら描く");
            assertFalse(twin.step(v, t += 16));

            twin.clearColor(0xFF102030);
            assertTrue(twin.step(v, t += 16), "塗りの色が変わったら描く");
            assertFalse(twin.step(v, t += 16));
            assertEquals("", twin.report.toString());
        }
    }

    /** シーンの外から足した重ね描き（キャレット・インスペクタ）: 足したフレーム・残っている間・外したフレームは描き、外し終えたら描かない。 */
    @Test
    void overlaysOutsideTheSceneArePaintedWhileShownAndWhenRemoved() {
        String html = "<style>body{margin:0}#a{width:60px;height:20px;background:#3a3}</style><div id='a'></div><p>text</p>";
        try (StandaloneView v = new StandaloneView(html, W, H, FontResolver.FALLBACK);
             SkipTwin twin = new SkipTwin(W, H)) {
            long t = 1000;
            twin.step(v, t);
            assertFalse(twin.step(v, t += 16));
            List<DisplayItem> base = v.ui().renderCache();
            DisplayItem paint = base.stream().filter(i -> i.type() != ElementType.NODE && i.width() > 0).findFirst().orElseThrow();
            List<DisplayItem> overlay = List.of(paint.withPosition(paint.x() + 90, paint.y() + 90));
            assertTrue(twin.stepOverlays(v, overlay, t += 16), "重ね描きを足したら描く");
            assertTrue(twin.stepOverlays(v, new ArrayList<>(overlay), t += 16), "重ね描きがある間は描く（シーンの外なので違いを比べない）");
            assertTrue(twin.step(v, t += 16), "重ね描きを外したフレームは描く（前の重ね描きの跡を消す）");
            assertFalse(twin.step(v, t += 16), "外し終えたら描かない");
            assertEquals("", twin.report.toString());
        }
    }

    /** 補間: 動いている間と、止まった最初のフレーム（前の時刻に動いていた）は描き、止まり終えたら描かない。 */
    @Test
    void animationsArePaintedUntilTheFirstFrameAfterTheyStop() {
        String html = "<style>body{margin:0}@keyframes m{from{background:#f00}to{background:#00f}}"
                + "#a{width:60px;height:20px;animation:m 200ms linear 1 forwards}</style><div id='a'></div>";
        try (StandaloneView v = new StandaloneView(html, W, H, FontResolver.FALLBACK);
             SkipTwin twin = new SkipTwin(W, H)) {
            long t = 1000;
            assertTrue(twin.step(v, t));
            assertTrue(twin.step(v, t += 50), "動いている間は描く");
            assertTrue(twin.step(v, t += 50));
            assertTrue(twin.step(v, t += 400), "止まった最初のフレームは描く（最後の値）");
            assertFalse(twin.step(v, t += 16), "止まり終えたら描かない");
            assertEquals("", twin.report.toString());
        }
    }

    /** 外の事情（画像の読み込み・動画・キャンバス・3D）で変わる項目がある文書は、毎フレーム描く。 */
    @Test
    void externallyDrivenItemsAreAlwaysPainted() {
        String html = "<style>body{margin:0}</style><img src='nothing-yet.png' width=20 height=20><p>x</p>";
        try (StandaloneView v = new StandaloneView(html, W, H, FontResolver.FALLBACK);
             SkipTwin twin = new SkipTwin(W, H)) {
            long t = 1000;
            for (int i = 0; i < 4; i++) assertTrue(twin.step(v, t += 16), "画像を含む文書は描く（フレーム " + i + "）");
        }
    }

    /**
     * 部品の値（入力欄の値・textarea の中身・ゲージ・スライダー・チェック）は DOM の写し（{@code DomValues}）でシーンに入るので、外の事情として
     * 毎フレーム描き直さなくても、値が変わったフレームだけ部分の描き直しで正しく描ける（M3b-4b-4b で「部品の状態で変わる」項目を、
     * 毎回描き直す種類から外した）。
     */
    @Test
    void widgetValueChangesArePaintedByTheSceneDiffAlone() {
        String html = "<style>body{margin:0;font:13px sans-serif}input,textarea,progress,select{display:block;margin:4px}</style>"
                + "<input id='i' value='first'><textarea id='ta'>one</textarea><progress id='p' max='100' value='20'></progress>"
                + "<input id='r' type='range' min='0' max='10' value='2'><input id='c' type='checkbox'>"
                + "<select id='s'><option>alpha</option><option>beta</option></select>";
        try (StandaloneView v = new StandaloneView(html, W, H, FontResolver.FALLBACK);
             SkipTwin twin = new SkipTwin(W, H)) {
            long t = 1000;
            twin.step(v, t);
            assertFalse(twin.step(v, t += 16), "入力欄があっても、何も変わらなければ描かない");
            String[][] changes = {{"i", "value", "second value"}, {"ta", "value", "two\nthree"}, {"p", "value", "80"},
                    {"r", "value", "9"}, {"c", "checked", ""}};
            for (String[] c : changes) {
                v.root().getElementById(c[0]).setAttribute(c[1], c[2]);
                assertTrue(twin.step(v, t += 16), c[0] + " の " + c[1] + " が変わったら描く");
                assertFalse(twin.step(v, t += 16), c[0] + ": 変わり終えたら描かない");
            }
            assertTrue(twin.partialFrames() > 0, "部分の描き直しで描いたフレームがある");
            assertEquals("", twin.report.toString());
        }
    }

    /**
     * ホストを真似た描画器: 描く前に {@link SoftwareRenderBackend#needsRepaint} を聞き、true のときだけ使い続ける描画器で描く（false なら前の画素を
     * そのまま持つ）。毎回、持っている画素を、同じ入力を新しい描画器（同じ補間の状態の持ち主）で全面に描いた画素と比べる。
     */
    static final class SkipTwin implements RenderBackend {
        final SoftwareRenderBackend persistent;
        final int w, h;
        final StringBuilder report = new StringBuilder();
        int frames, skipped;
        private int clearArgb;
        private boolean lastNeed;

        SkipTwin(int w, int h) {
            this.w = w;
            this.h = h;
            this.persistent = new SoftwareRenderBackend(w, h);
        }

        /** ビューの 1 フレーム（タイマー・滑らかなスクロールを進め、列を組み、ここへ描かせる）。描いたら true。 */
        boolean step(StandaloneView v, long now) {
            v.frame(now, this, true);
            return lastNeed;
        }

        /** ビューの今の列に重ね描き {@code overlays} を添えたフレームを描く。描いたら true。 */
        boolean stepOverlays(StandaloneView v, List<DisplayItem> overlays, long now) {
            return paint(v.ui().renderFrame(overlays, null, now));
        }

        void clearColor(int argb) {
            clearArgb = argb;
            persistent.clearColor(argb);
        }

        long partialFrames() { return persistent.partialFrames(); }

        private boolean paint(com.nishiyu.enderframelib.core.paint.RenderFrame f) {
            long now = f.nowMs();
            com.nishiyu.enderframelib.core.paint.PreparedFrame mine = persistent.prepare(f);
            boolean need = lastNeed = mine.needsRepaint();
            if (need) persistent.render(mine);
            else skipped++;
            int[] got = persistent.pixmap().pixels();
            try (SoftwareRenderBackend fresh = new SoftwareRenderBackend(w, h, 1f, persistent.sessionId())) {
                fresh.clearColor(clearArgb);
                fresh.render(com.nishiyu.enderframelib.core.paint.RenderFrame.of(f.drawList(), f.offsets(), now));
                int[] want = fresh.pixmap().pixels();
                int diff = 0, first = -1;
                for (int i = 0; i < want.length; i++) {
                    if (want[i] != got[i] && diff++ == 0) first = i;
                }
                if (diff > 0) {
                    report.append("\n  frame ").append(frames).append(" t=").append(now).append(need ? " (描いた)" : " (描かなかった)")
                            .append(": ").append(diff).append(" 画素が全面描画と違う（最初は ").append(first % w).append(',')
                            .append(first / w).append('）');
                }
            }
            frames++;
            return need;
        }

        @Override public void render(com.nishiyu.enderframelib.core.paint.PreparedFrame frame) { paint(frame.frame()); }
        @Override public Pixmap pixmap() { return persistent.pixmap(); }
        @Override public float devicePixelRatio() { return 1f; }
        @Override public String sessionId() { return persistent.sessionId(); }
        @Override public void close() { persistent.close(); }
    }
}
