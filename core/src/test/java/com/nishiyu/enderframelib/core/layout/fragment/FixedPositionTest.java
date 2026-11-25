package com.nishiyu.enderframelib.core.layout.fragment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@code position: fixed} の包含ブロックはビューポート（新経路では文書の根。スクロール 0 のときに一致）で、位置指定の祖先（relative・
 * absolute）は受け取らない（CSS 2.2 §10.1）。位置指定の祖先が absolute の子孫だけを置き、fixed は根まで渡すこと。
 */
class FixedPositionTest {

    @Test
    void fixedInsidePositionedAncestorsUsesTheViewport() {
        for (String display : new String[]{"block", "flex", "grid", "table"}) {
            SpikeView v = new SpikeView("<html><body style='margin:0'><div id=sc style='position:relative;display:" + display
                    + ";border:3px solid #888;padding:2px;margin:20px'><div id=ab style='position:absolute;left:5px;top:6px;width:10px;height:10px'></div>"
                    + "<div id=fx style='position:fixed;left:7px;top:8px;width:10px;height:10px'></div>"
                    + "<div style='position:absolute;left:30px;top:0'><div id=fx2 style='position:fixed;right:9px;bottom:4px;width:10px;height:10px'></div></div>"
                    + "</div></body></html>", 640, 480);
            LayoutFragment root = v.frame();
            float[] fx = SpikeView.find(root, "fx"), fx2 = SpikeView.find(root, "fx2"), ab = SpikeView.find(root, "ab");
            assertNotNull(fx, display);
            assertEquals(7f, fx[0], 0.01f, display + " fixed の x はビューポート基準");
            assertEquals(8f, fx[1], 0.01f, display + " fixed の y はビューポート基準");
            assertEquals(640f - 9f - 10f, fx2[0], 0.01f, display + " 入れ子の absolute の中の fixed もビューポート基準");
            assertEquals(480f - 4f - 10f, fx2[1], 0.01f, display);
            // absolute は位置指定の祖先（パディングの箱）が受け取る: 余白 20 + 枠線 3 + left / top
            assertEquals(20f + 3f + 5f, ab[0], 0.01f, display + " absolute は位置指定の祖先が包含ブロック");
            assertEquals(20f + 3f + 6f, ab[1], 0.01f, display);
            assertEquals(SpikeView.dump(v.scratch()), SpikeView.dump(root), display);
        }
    }
}
