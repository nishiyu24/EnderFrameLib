package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

/**
 * B1③（§1.4 条件 c-②）: 入力を受けたフレームで表示する（遅延 0 フレーム）。
 * Chrome は入力 → 次の vsync の提示（実測 約 1 フレーム・約 15 ms）だが、`:core` は入力を渡した直後の同じ {@code frame()} 呼び出しで
 * 描き終える。ホバー・スクロール・フォーカスのどれも、1 回目の {@code frame()} で画素に反映され、2 回目は描くものが残らない。
 */
class InputToDisplayLatencyTest {
    private static final String HTML = "<html><head><style>body{margin:0;background:#fff}"
            + "#a{position:absolute;left:0;top:0;width:100px;height:50px;background:#0000ff}#a:hover{background:#ff0000}"
            + "#s{position:absolute;left:0;top:100px;width:200px;height:100px;overflow:auto}"
            + "#s div{height:40px;background:#00aa00;border-bottom:1px solid #000}</style></head><body><div id='a'></div>"
            + "<div id='s'>" + "<div></div>".repeat(20) + "</div></body></html>";

    private static int px(SoftwareRenderBackend b, int x, int y) {
        return b.pixmap().getStraight(x, y) & 0xFFFFFF;
    }

    @Test
    void hoverIsDrawnInTheSameFrameThatReceivedTheInput() {
        SoftwareRenderBackend b = new SoftwareRenderBackend(300, 300, 1f);
        StandaloneView v = new StandaloneView(HTML, 300, 300, FontResolver.FALLBACK);
        assertTrue(v.frame(1000, b, true));
        assertEquals(0x0000FF, px(b, 10, 10), "ホバー前");

        v.mouseMove(10, 10, b.sessionId());
        assertTrue(v.frame(1016, b, false), "入力を渡した直後の frame() が描く（次のフレームへ持ち越さない）");
        assertEquals(0xFF0000, px(b, 10, 10), "同じフレームの画素に :hover が反映されている（遅延 0 フレーム）");

        assertFalse(v.frame(1032, b, false), "反映後は描くものが残らない（遅れて来る更新が無い）");
        v.close();
    }

    @Test
    void hoverOutIsAlsoDrawnInTheSameFrame() {
        SoftwareRenderBackend b = new SoftwareRenderBackend(300, 300, 1f);
        StandaloneView v = new StandaloneView(HTML, 300, 300, FontResolver.FALLBACK);
        v.frame(1000, b, true);
        v.mouseMove(10, 10, b.sessionId());
        v.frame(1016, b, false);
        v.mouseMove(250, 250, b.sessionId());
        assertTrue(v.frame(1032, b, false));
        assertEquals(0x0000FF, px(b, 10, 10), "外へ出たフレームで元の色へ戻る");
        v.close();
    }
}
