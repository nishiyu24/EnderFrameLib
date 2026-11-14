package com.nishiyu.enderframelib.core.paint.software;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * 差分描画（{@link SoftwareRenderBackend} の Tile Dirty Bitset・部分再描画）が、毎フレーム全面を描き直す場合と
 * 全画素で同じ絵になること。文字・スタイル・ホバー・スクロール（描画上の平行移動）・クリップ・子の増減を混ぜた列と、
 * アニメーション（補間の途中は全面へ戻す）の列を比べる。
 *
 * <p>差分描画の見積もりが外れた場合は全面でやり直すので絵は同じになるが、見積もりの漏れを検出するため
 * 標準の列では {@code damageAborts() == 0} も確かめる。</p>
 */
class PartialRepaintPixelTest {
    private static final int W = 360, H = 300;

    /** 1 回の実行の、フレームごとの画素と診断値。 */
    private record Run(List<int[]> frames, long partial, long full, long aborts, long skipped) {}

    private static String html() {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            rows.append("<div class=\"row\" id=\"r").append(i).append("\">row <span id=\"t").append(i).append("\">")
                    .append(i).append("</span> long trailing text for the clipping tests</div>");
        }
        return "<style>body{margin:0;font-size:13px;background:#123}"
                + ".row{height:22px;margin:2px;padding:2px 6px;background:#eef;color:#223;border:1px solid #99a;border-radius:4px;overflow:hidden;white-space:nowrap}"
                + ".row:hover{background:#fed}.dim{opacity:.5}"
                + "#outer{position:absolute;left:10px;top:10px;width:200px;height:260px;overflow-y:auto;border:2px solid #333;border-radius:10px}"
                + "#clip{position:absolute;left:230px;top:10px;width:110px;height:110px;overflow:hidden;border-radius:30px;background:#cde}"
                + "#clip div{width:200px;height:200px;background:#a33;margin:4px}"
                + "#shadow{position:absolute;left:235px;top:150px;width:80px;height:50px;background:#8c8;border-radius:8px;box-shadow:0 3px 8px #0008}"
                + "</style><div id=\"outer\">" + rows + "</div>"
                + "<div id=\"clip\"><div>wide content that is clipped by the rounded box</div></div>"
                + "<div id=\"shadow\">s</div>";
    }

    /**
     * {@code seed} の手順を {@code steps} 手進める。{@code damage} が false なら毎フレーム全面描画（比較の基準）。
     */
    private static Run run(boolean damage, long seed, int steps) {
        boolean saved = SoftwareRenderBackend.damageEnabled;
        SoftwareRenderBackend.damageEnabled = damage;
        StandaloneView view = new StandaloneView(html(), W, H, FontResolver.FALLBACK);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(W, H, 1f)) {
            List<int[]> frames = new ArrayList<>();
            List<HtmlNode> added = new ArrayList<>();
            Random rnd = new Random(seed);
            long now = 1000;
            view.frame(now, cpu, true);
            for (int step = 0; step < steps; step++) {
                switch (rnd.nextInt(9)) {
                    case 0 -> {
                        HtmlNode t = view.root().getNodeById("t" + rnd.nextInt(30));
                        if (t != null) t.updateTextContent("n" + rnd.nextInt(10000));
                    }
                    case 1 -> {
                        // 不透明度の層（グループ）は差分描画の対象外。同じ行を往復させ、層が無いフレームも作る
                        HtmlNode r = view.root().getNodeById("r0");
                        if (r instanceof HtmlElement e) {
                            e.setAttribute("class", "row dim".equals(e.attrs.get("class")) ? "row" : "row dim");
                        }
                    }
                    case 2 -> {
                        HtmlNode r = view.root().getNodeById("r" + rnd.nextInt(30));
                        if (r != null) view.ui().setHoveredElement(r.key());
                    }
                    case 3 -> {
                        HtmlNode c = view.root().getNodeById("clip");
                        if (c instanceof HtmlElement e) {
                            e.setAttribute("style", "width:" + (60 + rnd.nextInt(100)) + "px;height:"
                                    + (60 + rnd.nextInt(100)) + "px;border-radius:" + rnd.nextInt(50) + "px");
                        }
                    }
                    case 4 -> {
                        HtmlNode r = view.root().getNodeById("r" + rnd.nextInt(30));
                        if (r instanceof HtmlElement e) {
                            e.setAttribute("style", "border-radius:" + rnd.nextInt(12) + "px;width:" + (40 + rnd.nextInt(120)) + "px");
                        }
                    }
                    case 5 -> {
                        // スクロールは描画上の平行移動（OffsetItemList）で、動いた節の子孫だけが塗り直しの対象になる
                        view.mouseMove(60, 120, cpu.sessionId());
                        view.scroll(0, rnd.nextInt(7) - 3, cpu.sessionId());
                    }
                    case 6 -> {
                        HtmlNode r = view.root().getNodeById("r" + rnd.nextInt(30));
                        if (r != null) {
                            HtmlElement add = (HtmlElement) view.root().ownerDocument.createElement(rnd.nextBoolean() ? "div" : "span");
                            add.setAttribute("id", "z" + step); // DOM API で作った要素は id を持たない
                            add.setAttribute("class", "row");
                            add.setTextContent("n" + step);
                            r.appendChild(add);
                            added.add(add);
                        }
                    }
                    case 7 -> {
                        if (!added.isEmpty()) {
                            HtmlNode gone = added.remove(rnd.nextInt(added.size()));
                            if (gone.parent != null) gone.parent.removeChild(gone);
                        }
                    }
                    default -> {
                        HtmlNode o = view.root().getNodeById("outer");
                        if (o instanceof HtmlElement e) e.setAttribute("style", "height:" + (180 + rnd.nextInt(100)) + "px");
                    }
                }
                now += 16;
                view.frame(now, cpu, true);
                frames.add(cpu.pixmap().pixels().clone());
            }
            return new Run(frames, cpu.partialFrames(), cpu.fullFrames(), cpu.damageAborts(), cpu.skippedItems());
        } finally {
            view.close();
            SoftwareRenderBackend.damageEnabled = saved;
        }
    }

    @Test
    void partialFramesMatchFullRepaint() {
        for (long seed = 1; seed <= 3; seed++) {
            Run expected = run(false, seed, 60);
            Run actual = run(true, seed, 60);
            assertEquals(expected.frames().size(), actual.frames().size());
            for (int i = 0; i < expected.frames().size(); i++) {
                assertArrayEquals(expected.frames().get(i), actual.frames().get(i), "seed " + seed + " フレーム " + i);
            }
            assertTrue(actual.partial() > 0, "seed " + seed + ": 差分描画のフレームが無い");
            assertTrue(actual.skipped() > 0, "seed " + seed + ": 描かない項目が無い");
            assertTrue(actual.full() > 0, "seed " + seed + ": 全面へ戻る場面（不透明度の層・子の増減）が無い");
            assertEquals(0, actual.aborts(), "seed " + seed + ": 見積もりが外れて全面でやり直した");
        }
    }

    private static String animationHtml() {
        return "<style>body{margin:0;background:#123;font-size:13px}"
                + "#a{position:absolute;left:20px;top:20px;width:120px;height:80px;background:#345;transition:background-color 400ms linear}"
                + "#a:hover{background:#f80}"
                + "#b{position:absolute;left:20px;top:140px;width:120px;height:80px;background:#567}"
                + "</style><div id=\"a\">a</div><div id=\"b\">b</div>";
    }

    /** ホバーで背景色のトランジションを始め、40 フレーム（800ms）進める。 */
    private static Run animationRun(boolean damage) {
        boolean saved = SoftwareRenderBackend.damageEnabled;
        SoftwareRenderBackend.damageEnabled = damage;
        StandaloneView view = new StandaloneView(animationHtml(), W, H, FontResolver.FALLBACK);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(W, H, 1f)) {
            List<int[]> frames = new ArrayList<>();
            long now = 1000;
            view.frame(now, cpu, true);
            view.ui().setHoveredElement(view.root().getNodeById("a").key());
            for (int i = 0; i < 40; i++) {
                now += 20;
                view.frame(now, cpu, true);
                frames.add(cpu.pixmap().pixels().clone());
            }
            return new Run(frames, cpu.partialFrames(), cpu.fullFrames(), cpu.damageAborts(), cpu.skippedItems());
        } finally {
            view.close();
            SoftwareRenderBackend.damageEnabled = saved;
        }
    }

    @Test
    void colorTransitionFramesArePaintedPartiallyAndMatch() {
        Run expected = animationRun(false);
        Run actual = animationRun(true);
        for (int i = 0; i < expected.frames().size(); i++) {
            assertArrayEquals(expected.frames().get(i), actual.frames().get(i), "アニメーション フレーム " + i);
        }
        // 色だけの遷移は、遷移中も項目単位の差分描画。全面は初回と遷移の開始（項目の差し替え）だけ
        assertTrue(actual.partial() >= 38, "遷移中のフレームが全面描画になっている: partial=" + actual.partial() + " full=" + actual.full());
        assertEquals(0, actual.aborts(), "補間の前後で見積もりが外れた");
    }

    /** {@code #a} へ hover を当て、{@code frames} フレーム進めたあと外す（外す遷移も含む）。 */
    private static Run hoverRun(String html, boolean damage, int frames) {
        boolean saved = SoftwareRenderBackend.damageEnabled;
        SoftwareRenderBackend.damageEnabled = damage;
        StandaloneView view = new StandaloneView(html, W, H, FontResolver.FALLBACK);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(W, H, 1f)) {
            List<int[]> out = new ArrayList<>();
            long now = 1000;
            view.frame(now, cpu, true);
            view.ui().setHoveredElement(view.root().getNodeById("a").key());
            for (int i = 0; i < frames; i++) {
                now += 20;
                view.frame(now, cpu, true);
                out.add(cpu.pixmap().pixels().clone());
            }
            view.ui().setHoveredElement(view.root().getNodeById("b").key());
            for (int i = 0; i < frames; i++) {
                now += 20;
                view.frame(now, cpu, true);
                out.add(cpu.pixmap().pixels().clone());
            }
            return new Run(out, cpu.partialFrames(), cpu.fullFrames(), cpu.damageAborts(), cpu.skippedItems());
        } finally {
            view.close();
            SoftwareRenderBackend.damageEnabled = saved;
        }
    }

    private static void assertSamePixels(Run expected, Run actual, String what) {
        assertEquals(expected.frames().size(), actual.frames().size());
        for (int i = 0; i < expected.frames().size(); i++) {
            assertArrayEquals(expected.frames().get(i), actual.frames().get(i), what + " フレーム " + i);
        }
        assertEquals(0, actual.aborts(), what + ": 見積もりが外れて全面でやり直した");
    }

    @Test
    void transformTransitionFramesArePaintedPartiallyAndMatch() {
        // 変形の遷移: 動いた節の子孫が旧範囲と新範囲で塗り直される。子（文字つき）・影・クリップ内の子も混ぜる
        String html = "<style>body{margin:0;background:#123;font-size:13px}"
                + "#a{position:absolute;left:30px;top:30px;width:120px;height:80px;background:#345;color:#fff;border-radius:10px;"
                + "box-shadow:0 3px 8px #0008;transition:transform 300ms linear}"
                + "#a:hover{transform:translate(40px,20px) rotate(8deg) scale(1.1)}"
                + "#a span{display:block;margin:10px;width:60px;height:20px;background:#a33}"
                + "#c{position:absolute;left:200px;top:30px;width:100px;height:100px;overflow:hidden;background:#cde}"
                + "#c div{width:160px;height:160px;background:#4a4}"
                + "#b{position:absolute;left:20px;top:150px;width:120px;height:60px;background:#567}"
                + "</style><div id=\"a\">text<span>child</span></div><div id=\"c\"><div>clipped</div></div><div id=\"b\">b</div>";
        Run expected = hoverRun(html, false, 25);
        Run actual = hoverRun(html, true, 25);
        assertSamePixels(expected, actual, "変形の遷移");
        assertTrue(actual.partial() >= 40, "変形の遷移中が全面描画になっている: partial=" + actual.partial() + " full=" + actual.full());
    }

    @Test
    void opacityTransitionStillFallsBackToFullRepaintAndMatches() {
        // 不透明度の遷移は層（グループ）を要するので、動いている間は全面描画のまま。絵は同じ
        String html = "<style>body{margin:0;background:#123;font-size:13px}"
                + "#a{position:absolute;left:30px;top:30px;width:120px;height:80px;background:#f80;transition:opacity 300ms linear}"
                + "#a:hover{opacity:.3}"
                + "#b{position:absolute;left:20px;top:150px;width:120px;height:60px;background:#567}"
                + "</style><div id=\"a\">a<span>x</span></div><div id=\"b\">b</div>";
        Run expected = hoverRun(html, false, 25);
        Run actual = hoverRun(html, true, 25);
        assertSamePixels(expected, actual, "不透明度の遷移");
        assertTrue(actual.full() >= 10, "不透明度の遷移中に全面描画へ戻っていない: full=" + actual.full());
        assertTrue(actual.partial() > 0, "遷移が終わった後に差分描画へ戻っていない");
    }

    @Test
    void transitionEndFrameShowsTheFinalColor() {
        // 遷移の最後のフレームで最終色へ確定する（前のフレームで動いていた項目を塗り直す）
        String html = animationHtml();
        Run actual = hoverRun(html, true, 40);
        Run expected = hoverRun(html, false, 40);
        assertSamePixels(expected, actual, "遷移の終端");
        int[] last = actual.frames().get(39); // hover 中の最後のフレーム（遷移は 400ms = 20 フレームで終わっている）
        int px = last[60 * W + 60];
        assertEquals(0xFFFF8800, px, "遷移後の #a は :hover の背景色 #f80");
    }

    private static String keyframeHtml() {
        return "<style>body{margin:0;background:#000}"
                + "#b{position:absolute;left:75px;top:75px;width:50px;height:50px;background:#00ff00;animation:k 2s linear 1}"
                + "@keyframes k{from{transform:skewX(0deg)}to{transform:skewX(45deg)}}"
                + "</style><div id=\"b\"></div>";
    }

    /** 表示リストが変わらないキーフレームだけが進むフレーム。差分描画は全面へ戻らなければならない。 */
    private static Run keyframeRun(boolean damage) {
        boolean saved = SoftwareRenderBackend.damageEnabled;
        SoftwareRenderBackend.damageEnabled = damage;
        StandaloneView view = new StandaloneView(keyframeHtml(), W, H, FontResolver.FALLBACK);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(W, H, 1f)) {
            List<int[]> frames = new ArrayList<>();
            long[] times = {1000, 1500, 2100, 2600, 3200, 3400};
            for (long now : times) {
                view.frame(now, cpu, true);
                frames.add(cpu.pixmap().pixels().clone());
            }
            return new Run(frames, cpu.partialFrames(), cpu.fullFrames(), cpu.damageAborts(), cpu.skippedItems());
        } finally {
            view.close();
            SoftwareRenderBackend.damageEnabled = saved;
        }
    }

    @Test
    void keyframeFramesWithoutListChangesMatchFullRepaint() {
        Run expected = keyframeRun(false);
        Run actual = keyframeRun(true);
        for (int i = 0; i < expected.frames().size(); i++) {
            assertArrayEquals(expected.frames().get(i), actual.frames().get(i), "キーフレーム フレーム " + i);
        }
        assertTrue(actual.full() > 0, "キーフレームの途中で全面描画へ戻っていない");
        assertTrue(actual.partial() > 0, "キーフレームが終わった後に差分描画へ戻っていない");
        assertEquals(0, actual.aborts(), "キーフレームの前後で見積もりが外れた");
    }
}
