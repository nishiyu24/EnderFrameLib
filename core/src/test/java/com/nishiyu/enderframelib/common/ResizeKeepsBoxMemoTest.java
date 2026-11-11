package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.core.paint.DisplayListBuilder;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ウィンドウのリサイズで、描画のビルダーを作り直さず箱ごとの記憶を残す（ビューポートの大きさに依らない箱の項目を使い回す）。
 * 使い回した結果は、そのサイズで一から作ったビューの表示リストと完全に一致しなければならない。
 * ビューポート基準の単位（vw / vh）を使う箱は、記憶があっても作り直す。
 */
class ResizeKeepsBoxMemoTest {

    private static final String HTML = "<style>body{margin:0;font-size:14px}"
            + ".card{padding:8px;margin:4px;border:1px solid #888;background:#eef}"
            + ".vw{width:30vw;height:6vh;background:#fcc;border-radius:2vw;box-shadow:0 0 1vw #333}"
            + ".grad{width:200px;height:40px;background:linear-gradient(90deg,#f00 10vw,#00f 40vw)}"
            + ".fixed{width:120px;height:30px;background:#cfc;border-radius:6px}"
            + ".ctr{width:300px;margin:0 auto;background:#eee}"
            + "</style>"
            + "<div class='vw'>viewport sized</div><div class='grad'></div>"
            + "<div class='ctr'><div class='card'>centered card one</div><div class='card'>centered card two</div></div>"
            + "<div class='fixed'>fixed</div><p>plain paragraph with some words to lay out</p>"
            + "<div style='display:flex;gap:6px'><span class='card'>a</span><span class='card'>b</span><span class='card'>c</span></div>";

    private static List<DisplayItem> items(StandaloneView v) {
        v.ui().flushInvalidation();
        return new ArrayList<>(v.ui().renderCache());
    }

    @Test
    void resizedViewMatchesAViewCreatedAtThatSize() {
        int[][] sizes = {{800, 600}, {799, 600}, {800, 600}, {640, 400}, {641, 400}, {1024, 768}, {320, 200}, {800, 600}};
        StandaloneView resized = new StandaloneView(HTML, sizes[0][0], sizes[0][1], FontResolver.FALLBACK);
        items(resized);
        for (int i = 1; i < sizes.length; i++) {
            resized.ui().recomputeLayout(sizes[i][0], sizes[i][1]);
            List<DisplayItem> got = new ArrayList<>(resized.ui().renderCache());
            List<DisplayItem> want = items(new StandaloneView(HTML, sizes[i][0], sizes[i][1], FontResolver.FALLBACK));
            assertEquals(want.size(), got.size(), "項目の数 @" + sizes[i][0] + "x" + sizes[i][1]);
            for (int k = 0; k < want.size(); k++) {
                assertEquals(want.get(k), got.get(k), "項目 " + k + " @" + sizes[i][0] + "x" + sizes[i][1]);
            }
        }
    }

    @Test
    void boxesThatDoNotDependOnTheViewportAreReusedAcrossAResize() {
        StandaloneView v = new StandaloneView(HTML, 800, 600, FontResolver.FALLBACK);
        items(v);
        long exactBefore = DisplayListBuilder.STAT_EXACT.sum();
        v.ui().recomputeLayout(799, 600);
        v.ui().renderCache();
        long reused = DisplayListBuilder.STAT_EXACT.sum() - exactBefore;
        assertTrue(reused > 0, "リサイズでも、動かない・ビューポートに依らない箱の項目を使い回す: " + reused);
    }
}
