package com.nishiyu.enderframelib.core.paint.software;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.paint.PaintSceneDriver;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 表示リストの差分更新（{@link PaintSceneDriver#patchDiffIndex}）と、項目ごとのクリップの記憶・GPU 用クリップ列の記憶・字形の送り位置の記憶が、
 * 毎回作り直す場合と画素まで同じ絵を描くこと。文字・スタイル・クリップ（overflow の箱の大きさ）・ホバーの変化の列で、全フレームの全画素を比べる。
 */
class DiffPatchPixelTest {
    private static final int W = 360, H = 300;

    @AfterEach
    void restore() {
        PaintSceneDriver.patchDiffIndex = true;
    }

    private static String html() {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 30; i++) rows.append("<div class=\"row\" id=\"r").append(i).append("\">row <span id=\"t").append(i).append("\">").append(i).append("</span> long trailing text for the clipping tests</div>");
        return "<style>body{margin:0;font-size:13px;background:#123}.row{height:22px;margin:2px;padding:2px 6px;background:#eef;color:#223;border:1px solid #99a;border-radius:4px;overflow:hidden;white-space:nowrap}"
                + ".row:hover{background:#fed}.dim{opacity:.5}"
                + "#outer{position:absolute;left:10px;top:10px;width:200px;height:260px;overflow-y:auto;border:2px solid #333;border-radius:10px}"
                + "#clip{position:absolute;left:230px;top:10px;width:110px;height:110px;overflow:hidden;border-radius:30px;background:#cde}"
                + "#clip div{width:200px;height:200px;background:#a33;margin:4px}</style>"
                + "<div id=\"outer\">" + rows + "</div><div id=\"clip\"><div>wide content that is clipped by the rounded box</div></div>";
    }

    private static List<int[]> run(boolean diffPatch, long seed, int steps) {
        PaintSceneDriver.patchDiffIndex = diffPatch;
        List<int[]> out = new ArrayList<>();
        StandaloneView view = new StandaloneView(html(), W, H, FontResolver.FALLBACK);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(W, H, 1f)) {
            Random rnd = new Random(seed);
            long now = 1000;
            view.frame(now, cpu, true);
            for (int step = 0; step < steps; step++) {
                switch (rnd.nextInt(6)) {
                    case 0 -> {
                        HtmlNode t = view.root().getNodeById("t" + rnd.nextInt(30));
                        if (t != null) t.updateTextContent("n" + rnd.nextInt(10000));
                    }
                    case 1 -> {
                        HtmlNode r = view.root().getNodeById("r" + rnd.nextInt(30));
                        if (r instanceof HtmlElement e) e.setAttribute("class", "row dim".equals(e.attrs.get("class")) ? "row" : "row dim");
                    }
                    case 2 -> view.ui().setHoveredElement(view.root().getNodeById("r" + rnd.nextInt(30)).key());
                    case 3 -> {
                        // クリップの形が変わる（項目の並びは同じ）
                        HtmlNode c = view.root().getNodeById("clip");
                        if (c instanceof HtmlElement e) {
                            e.setAttribute("style", "width:" + (60 + rnd.nextInt(100)) + "px;height:" + (60 + rnd.nextInt(100)) + "px;border-radius:" + rnd.nextInt(50) + "px");
                        }
                    }
                    case 4 -> {
                        // 1 行のクリップの形だけが変わる（他の行は動かない＝少数の項目の差分。子の字のクリップが変わる）
                        HtmlNode r = view.root().getNodeById("r" + rnd.nextInt(30));
                        if (r instanceof HtmlElement e) e.setAttribute("style", "border-radius:" + rnd.nextInt(12) + "px;width:" + (40 + rnd.nextInt(120)) + "px");
                    }
                    default -> {
                        HtmlNode o = view.root().getNodeById("outer");
                        if (o instanceof HtmlElement e) e.setAttribute("style", "height:" + (180 + rnd.nextInt(100)) + "px");
                    }
                }
                now += 16;
                view.frame(now, cpu, true);
                out.add(cpu.pixmap().pixels().clone());
            }
        } finally {
            view.close();
        }
        return out;
    }

    @Test
    void diffPatchedFramesAreIdenticalToRebuiltFrames() {
        for (long seed = 1; seed <= 3; seed++) {
            List<int[]> expected = run(false, seed, 60);
            List<int[]> actual = run(true, seed, 60);
            assertTrue(expected.size() == actual.size() && expected.size() == 60);
            for (int i = 0; i < expected.size(); i++) assertArrayEquals(expected.get(i), actual.get(i), "seed " + seed + " フレーム " + i);
        }
    }
}
