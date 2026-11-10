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
import org.junit.jupiter.api.Test;

/**
 * CPU バックエンドにおけるスクロール Blit 再利用（{@link CpuRaster#scrollBlit}）および
 * 子要素増減（{@code CHILD_PATCH}）時の差分描画ピクセル等価性・スキップ効率検証。
 */
class CpuScrollBlitPixelTest {
    private static final int W = 400, H = 320;

    private static String scrollListHtml(int rowCount) {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < rowCount; i++) {
            rows.append("<div class=\"row\" id=\"row-").append(i).append("\">")
                    .append("Item #").append(i)
                    .append(" <span class=\"tag\">tag").append(i % 5).append("</span>")
                    .append(" description text for scrolling verification</div>");
        }
        return "<style>"
                + "body { margin:0; background:#1e1e24; font-size:13px; color:#f0f0f5; }"
                + "#header { height:36px; background:#2a2b36; border-bottom:2px solid #44475a; padding:6px 12px; box-sizing:border-box; font-weight:bold; }"
                + "#container { position:absolute; left:16px; top:48px; width:368px; height:240px; overflow-y:auto; border:1px solid #6272a4; border-radius:6px; background:#282a36; }"
                + ".row { height:26px; padding:3px 8px; margin:2px; background:#44475a; border-radius:3px; box-sizing:border-box; white-space:nowrap; overflow:hidden; }"
                + ".tag { background:#6272a4; color:#50fa7b; padding:1px 4px; border-radius:2px; font-size:11px; margin-left:6px; }"
                + "</style>"
                + "<div id=\"header\">Scroll Blit Test Header</div>"
                + "<div id=\"container\">" + rows + "</div>";
    }

    private record RunResult(List<int[]> frames, long partial, long full, long aborts, long skipped) {}

    private static RunResult runScroll(boolean damage, int rowCount, int scrollSteps) {
        boolean saved = SoftwareRenderBackend.damageEnabled;
        SoftwareRenderBackend.damageEnabled = damage;
        StandaloneView view = new StandaloneView(scrollListHtml(rowCount), W, H, FontResolver.FALLBACK);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(W, H, 1f)) {
            List<int[]> frames = new ArrayList<>();
            long now = 1000;
            view.frame(now, cpu, true);

            // コンテナの中心位置へマウス移動
            view.mouseMove(200, 160, cpu.sessionId());

            for (int step = 0; step < scrollSteps; step++) {
                now += 16;
                int deltaY = (step % 20 < 10) ? -2 : 2;
                view.scroll(0, deltaY, cpu.sessionId());
                view.frame(now, cpu, true);
                frames.add(cpu.pixmap().pixels().clone());
            }

            return new RunResult(frames, cpu.partialFrames(), cpu.fullFrames(), cpu.damageAborts(), cpu.skippedItems());
        } finally {
            view.close();
            SoftwareRenderBackend.damageEnabled = saved;
        }
    }

    @Test
    void continuousScrollingMatchesFullRepaintAndSkipsUnexposedItems() {
        int rows = 60;
        int steps = 40;
        RunResult expected = runScroll(false, rows, steps);
        RunResult actual = runScroll(true, rows, steps);

        assertEquals(expected.frames().size(), actual.frames().size());
        for (int i = 0; i < expected.frames().size(); i++) {
            int[] exp = expected.frames().get(i);
            int[] act = actual.frames().get(i);
            int diffCount = 0;
            for (int p = 0; p < exp.length; p++) {
                if (exp[p] != act[p]) {
                    diffCount++;
                    if (diffCount <= 5) {
                        int y = p / W, x = p % W;
                        System.out.printf("DIFF frame %d at (%d, %d): exp=0x%08X act=0x%08X%n", i, x, y, exp[p], act[p]);
                    }
                }
            }
            if (diffCount > 0) {
                System.out.println("Frame " + i + " total diffs: " + diffCount);
            }
            assertArrayEquals(expected.frames().get(i), actual.frames().get(i), "スクロール フレーム " + i);
        }

        // 差分描画が有効に動作していること
        assertTrue(actual.partial() > 0, "差分描画フレームが存在すること");
        assertEquals(0, actual.aborts(), "見積もり外れ abort が 0 であること");

        // scrollBlit により、露出帯以外の多数の項目がスキップされたことを検証
        assertTrue(actual.skipped() > 100, "多数の項目がスキップされていること (skipped=" + actual.skipped() + ")");
    }

    private static RunResult runChildMutations(boolean damage) {
        boolean saved = SoftwareRenderBackend.damageEnabled;
        SoftwareRenderBackend.damageEnabled = damage;
        StandaloneView view = new StandaloneView(scrollListHtml(20), W, H, FontResolver.FALLBACK);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(W, H, 1f)) {
            List<int[]> frames = new ArrayList<>();
            long now = 1000;
            view.frame(now, cpu, true);

            HtmlNode container = view.root().getNodeById("container");
            List<HtmlNode> dynamicNodes = new ArrayList<>();

            for (int step = 0; step < 30; step++) {
                now += 16;
                if (step % 3 != 2 || dynamicNodes.isEmpty()) {
                    // 行の挿入
                    HtmlElement newRow = (HtmlElement) view.root().ownerDocument.createElement("div");
                    newRow.setAttribute("id", "dyn-" + step);
                    newRow.setAttribute("class", "row");
                    newRow.setTextContent("Dynamic Row " + step);
                    if (container != null) {
                        container.appendChild(newRow);
                        dynamicNodes.add(newRow);
                    }
                } else {
                    // 行の削除
                    HtmlNode removed = dynamicNodes.remove(dynamicNodes.size() - 1);
                    if (container != null && removed.parent != null) {
                        container.removeChild(removed);
                    }
                }
                view.frame(now, cpu, true);
                frames.add(cpu.pixmap().pixels().clone());
            }

            return new RunResult(frames, cpu.partialFrames(), cpu.fullFrames(), cpu.damageAborts(), cpu.skippedItems());
        } finally {
            view.close();
            SoftwareRenderBackend.damageEnabled = saved;
        }
    }

    @Test
    void childInsertAndRemoveMatchesFullRepaintWithPartialFrames() {
        RunResult expected = runChildMutations(false);
        RunResult actual = runChildMutations(true);

        assertEquals(expected.frames().size(), actual.frames().size());
        for (int i = 0; i < expected.frames().size(); i++) {
            assertArrayEquals(expected.frames().get(i), actual.frames().get(i), "要素増減 フレーム " + i);
        }

        // 要素の挿入・削除でも差分描画（Tile Dirty Bitset）が動作していること
        assertTrue(actual.partial() > 0, "要素増減で差分描画が成立していること (partial=" + actual.partial() + ")");
        assertEquals(0, actual.aborts(), "見積もり外れ abort が 0 であること");
    }
}
