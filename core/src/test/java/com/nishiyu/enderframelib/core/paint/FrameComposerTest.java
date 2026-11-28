package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.common.UiView;
import com.nishiyu.enderframelib.core.paint.model.ScrollContentInfo;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 描画器の側の列の組み立て（M3b-4c-2b-2b。{@link FrameComposer}）: ビューは列を持たず、描画器が準備でフレームのシーンから組む。
 *
 * <ul>
 *   <li>シーンの根が同じで、中身を 1 つの節にまとめたコンテナだけがスクロールしたら組み直さず、中身の節の平行移動を足す。</li>
 *   <li>シーンが変わると組み直す。</li>
 *   <li>描いた画素は、同じ状態を新しい描画器で全面に描いた画素と同じ。</li>
 * </ul>
 */
class FrameComposerTest {
    private static final int W = 200, H = 160;

    private static String html() {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 30; i++) rows.append("<div style='height:20px;background:#").append(i % 2 == 0 ? "48c" : "c84").append("'>r").append(i).append("</div>");
        StringBuilder tall = new StringBuilder();
        for (int i = 0; i < 20; i++) tall.append("<p style='margin:0;height:20px'>p").append(i).append("</p>");
        return "<style>body{margin:0}#box{position:absolute;left:10px;top:10px;width:120px;height:100px;overflow-y:auto}#h{background:#eee}#h:hover{background:#f00}</style>"
                + "<div id='box'>" + rows + "</div><div style='height:120px'></div><div id='h'>hover</div>" + tall;
    }

    @Test
    void scrollingAOneNodeContainerReusesTheListAndTranslatesItsContent() {
        try (StandaloneView v = new StandaloneView(html(), W, H, FontResolver.FALLBACK)) {
            UiView ui = v.ui();
            FrameComposer c = new FrameComposer();
            FrameComposer.Composed a = c.compose(ui.renderFrame(List.of(), null, 0));
            assertEquals(1, c.builds());
            assertFalse(a.items().isEmpty());
            assertEquals(1, a.contents().size(), "中身を 1 つの節にまとめたスクロールコンテナ");
            ScrollContentInfo box = a.contents().get(0);

            v.scroll(0, -3, "composer"); // 点はコンテナの外なので、どこもスクロールしない
            ui.handleScrollLocally(40, 40, 0, -3); // コンテナの上でスクロールする
            ui.flushInvalidation();
            FrameComposer.Composed b = c.compose(ui.renderFrame(List.of(), null, 16));
            assertEquals(1, c.builds(), "まとめたコンテナのスクロールだけなら組み直さない");
            assertSame(a.items(), b.items(), "同じ列");
            assertTrue(b.offsets().dy(box.contentId()) < 0f, "中身の節をスクロール量だけ上へ動かす: " + b.offsets().dy(box.contentId()));
            assertEquals(0f, a.offsets().dy(box.contentId()));

            ui.setHoveredElement("h"); // シーンが変わる
            ui.flushInvalidation();
            FrameComposer.Composed d = c.compose(ui.renderFrame(List.of(), null, 32));
            assertEquals(2, c.builds(), "シーンが変われば組み直す");
            assertNotEquals(System.identityHashCode(a.items()), System.identityHashCode(d.items()));
        }
    }

    @Test
    void scrolledFramesMatchAFreshBackend() {
        try (StandaloneView v = new StandaloneView(html(), W, H, FontResolver.FALLBACK);
             SoftwareRenderBackend b = new SoftwareRenderBackend(W, H, 1f)) {
            long t = 1000;
            v.frame(t, b, true);
            v.ui().handleScrollLocally(40, 40, 0, -3); // コンテナ
            v.frame(t += 16, b, true);
            v.ui().handleScrollLocally(180, 150, 0, -4); // 文書
            v.frame(t += 16, b, true);
            try (SoftwareRenderBackend fresh = new SoftwareRenderBackend(W, H, 1f, b.sessionId())) {
                fresh.render(v.ui().renderFrame(List.of(), null, t));
                assertTrue(java.util.Arrays.equals(fresh.pixmap().pixels(), b.pixmap().pixels()), "差分で描いた画素 = 新しい描画器の全面");
            }
        }
    }
}
