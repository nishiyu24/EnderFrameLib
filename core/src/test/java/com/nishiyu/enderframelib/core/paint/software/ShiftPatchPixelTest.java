package com.nishiyu.enderframelib.core.paint.software;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
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
 * 子要素の挿入・削除（CHILD_PATCH）に対する増分インデックスシフト（{@link PaintSceneDriver#patchShiftIndex}）が、
 * 毎回全再構築する場合と画素まで 100% 同じ絵を描くことを検証する画素等価性テスト。
 */
class ShiftPatchPixelTest {
    private static final int W = 360, H = 300;

    @AfterEach
    void restore() {
        PaintSceneDriver.patchShiftIndex = true;
        PaintSceneDriver.patchDiffIndex = true;
    }

    private static String html() {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            rows.append("<div class=\"row\" id=\"r").append(i).append("\">row <span id=\"t").append(i).append("\">").append(i).append("</span> text</div>");
        }
        return "<style>body{margin:0;font-size:13px;background:#123}.row{height:22px;margin:2px;padding:2px 6px;background:#eef;color:#223;border:1px solid #99a;border-radius:4px;overflow:hidden}"
                + ".row:hover{background:#fed}.hot{color:#c00;background:#fdd}"
                + "#outer{position:absolute;left:10px;top:10px;width:200px;height:260px;overflow-y:auto;border:2px solid #333;border-radius:10px}"
                + "#side{position:absolute;left:230px;top:10px;width:110px;height:110px;background:#cde;border-radius:12px;padding:8px}</style>"
                + "<div id=\"outer\">" + rows + "</div><div id=\"side\">side card</div>";
    }

    private static List<int[]> run(boolean shiftPatch, long seed, int steps) {
        PaintSceneDriver.patchShiftIndex = shiftPatch;
        List<int[]> out = new ArrayList<>();
        StandaloneView view = new StandaloneView(html(), W, H, FontResolver.FALLBACK);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(W, H, 1f)) {
            Random rnd = new Random(seed);
            long now = 1000;
            view.frame(now, cpu, true);
            int dynamicId = 1000;

            for (int step = 0; step < steps; step++) {
                HtmlNode outer = view.root().getNodeById("outer");
                HtmlDocument doc = view.root() instanceof HtmlDocument d ? d : null;

                switch (rnd.nextInt(5)) {
                    case 0 -> {
                        // 挿入
                        if (doc != null && outer != null) {
                            HtmlElement newRow = (HtmlElement) doc.createElement("div");
                            newRow.setAttribute("class", "row hot");
                            newRow.setAttribute("id", "dyn_" + (dynamicId++));
                            newRow.appendChild((HtmlNode) doc.createTextNode("item " + dynamicId));
                            int cnt = outer.children.size();
                            if (cnt > 0) {
                                outer.insertBefore(newRow, outer.children.get(rnd.nextInt(cnt)));
                            } else {
                                outer.appendChild(newRow);
                            }
                        }
                    }
                    case 1 -> {
                        // 削除
                        if (outer != null && outer.children.size() > 5) {
                            int at = rnd.nextInt(outer.children.size());
                            outer.removeChild(outer.children.get(at));
                        }
                    }
                    case 2 -> {
                        // テキスト更新
                        HtmlNode t = view.root().getNodeById("t" + rnd.nextInt(20));
                        if (t != null) t.updateTextContent("v" + rnd.nextInt(10000));
                    }
                    case 3 -> {
                        // スタイル更新
                        HtmlNode r = view.root().getNodeById("r" + rnd.nextInt(20));
                        if (r instanceof HtmlElement e) {
                            e.setAttribute("style", "border-radius:" + rnd.nextInt(10) + "px;color:#" + (rnd.nextBoolean() ? "c00" : "223"));
                        }
                    }
                    default -> {
                        // hover
                        HtmlNode r = view.root().getNodeById("r" + rnd.nextInt(20));
                        if (r != null) view.ui().setHoveredElement(r.key());
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
    void shiftPatchedFramesArePixelIdenticalToRebuiltFrames() {
        for (long seed = 1; seed <= 3; seed++) {
            List<int[]> expected = run(false, seed, 40);
            List<int[]> actual = run(true, seed, 40);
            assertTrue(expected.size() == actual.size() && expected.size() == 40);
            for (int i = 0; i < expected.size(); i++) {
                assertArrayEquals(expected.get(i), actual.get(i), "seed " + seed + " フレーム " + i + " の描画ピクセルが完全一致すること");
            }
        }
    }
}
