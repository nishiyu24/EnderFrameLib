package com.nishiyu.enderframelib.core.layout.fragment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * M2b-1: {@code position: sticky} の静的位置（計画書 §3.4.2-5: レイアウトは静的位置だけを出し、スクロールに応じたずれは描画の側で
 * 解く）。sticky の箱は流れの中の位置に置かれ（{@code top} などはレイアウトの位置を変えない）、絶対配置の子孫の包含ブロックになる。
 */
class StickyPositionTest {
    private static final float EPS = 0.02f;

    private static float[] at(String body, String id) {
        float[] r = SpikeView.find(new SpikeView("<html><body style='margin:0'>" + body + "</body></html>", 800, 600).frame(), id);
        assertNotNull(r, id);
        return r;
    }

    @Test
    void stickyBoxStaysAtItsStaticPosition() {
        String html = "<div style='height:30px'></div><div id=s style='position:sticky;top:100px;left:40px;height:10px'></div>";
        float[] s = at(html, "s");
        assertEquals(0, s[0], EPS);
        assertEquals(30, s[1], EPS);
    }

    @Test
    void stickyBoxContainsAbsoluteDescendants() {
        String html = "<div style='height:30px'></div><div style='position:sticky;top:0;padding-left:20px;height:50px'>"
                + "<div id=a style='position:absolute;left:5px;top:7px;width:10px;height:10px'></div></div>";
        float[] a = at(html, "a");
        assertEquals(5, a[0], EPS); // 包含ブロックは sticky の箱の padding の箱（左端 0）
        assertEquals(30 + 7, a[1], EPS);
    }
}
