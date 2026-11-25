package com.nishiyu.enderframelib.core.layout.fragment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * M2b-1: {@code aspect-ratio}（CSS Sizing 4 §5.1）。auto の側の大きさを確定した側から比で移す: 高さ auto は幅から、幅 auto は確定した
 * 高さから、{@code min-height} は内在サイズの {@code min-width} へ。{@code box-sizing: border-box} なら比は border-box に掛かる。中身が比の
 * 高さより高ければ伸びる。flex の row の項目は確定した交差軸の大きさから基準の大きさを決める（Flexbox §9.2 3B）。期待値は手で求めた数。
 */
class AspectRatioTest {
    private static final float EPS = 0.02f;

    private static float[] at(String body, String id) {
        float[] r = SpikeView.find(new SpikeView("<html><body style='margin:0;font-size:16px;line-height:20px'>" + body + "</body></html>",
                800, 600).frame(), id);
        assertNotNull(r, id);
        return r;
    }

    @Test
    void autoHeightComesFromTheWidth() {
        assertEquals(100, at("<div id=a style='width:200px;aspect-ratio:2/1'></div>", "a")[3], EPS);
        // content-box: 比は内容の箱に掛かる（内容 200 x 100 ＋ padding 10 x 2）
        assertEquals(120, at("<div id=a style='width:200px;padding:10px;aspect-ratio:2'></div>", "a")[3], EPS);
        // border-box: 比は border-box に掛かる
        assertEquals(100, at("<div id=a style='width:200px;padding:10px;box-sizing:border-box;aspect-ratio:2'></div>", "a")[3], EPS);
    }

    @Test
    void autoWidthComesFromADefiniteHeight() {
        float[] a = at("<div id=a style='height:50px;aspect-ratio:3/1'></div>", "a");
        assertEquals(150, a[2], EPS);
        assertEquals(50, a[3], EPS);
    }

    @Test
    void contentTallerThanTheRatioHeightGrowsTheBox() {
        assertEquals(60, at("<div id=a style='width:100px;aspect-ratio:10'><div style='height:60px'></div></div>", "a")[3], EPS);
    }

    @Test
    void minHeightIsTransferredToTheIntrinsicWidth() {
        // WPT flex-item-transferred-sizes-padding-content-sizing: min-height 90（内容）→ 内在の最小の幅 90 ＋ padding 10
        float[] a = at("<div style='display:flex;flex-direction:column;float:left'><div id=a style='padding:5px;min-height:90px;aspect-ratio:1/1'></div></div>", "a");
        assertEquals(100, a[2], EPS);
        assertEquals(100, a[3], EPS);
    }

    @Test
    void stretchedFlexItemTakesItsWidthFromTheContainerHeight() {
        // WPT aspect-ratio-intrinsic-size-010: inline-flex の高さ 100、項目は stretch で border-box の高さ 80 → 幅 80
        float[] a = at("<div style='display:inline-flex;height:100px'><div id=a style='box-sizing:border-box;padding:3px;border:7px solid;"
                + "margin:10px;aspect-ratio:1/1'></div></div>", "a");
        assertEquals(80, a[2], EPS);
        assertEquals(80, a[3], EPS);
        float[] fixed = at("<div style='display:flex;height:100px;width:500px'><div id=a style='height:40px;aspect-ratio:2'></div></div>", "a");
        assertEquals(80, fixed[2], EPS); // 指定の高さ 40 からの基準の大きさ
    }
}
