package com.nishiyu.enderframelib.core.layout.fragment;

import org.junit.jupiter.api.Test;

import java.util.IdentityHashMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * M2b-1: 断片の到達範囲（{@link ScrollableOverflow}。旧 {@code ScrollExtentCalc.updateScrollExtent} の移植）。
 * 結果は {@code {scrollWidth, scrollHeight, 子孫のはみ出しの右端, 下端}}。期待値は固定の大きさの箱から手で求めた数。
 */
class ScrollableOverflowTest {
    private static final float EPS = 0.02f;

    private static float[] of(String body, String id) {
        SpikeView v = new SpikeView("<html><body style='margin:0'>" + body + "</body></html>", 800, 600);
        LayoutFragment f = InlineTextTest.frag(v.frame(), id);
        assertNotNull(f, id);
        return ScrollableOverflow.of(f, new IdentityHashMap<>(), v.layout.session());
    }

    @Test
    void scrollContainerReachesItsTallChildPlusEndPadding() {
        // 内容の幅 100・高さ 50、padding 5。子は 300 x 200 で余白 10: 到達範囲は 5 + 10 + 300 + 10(右の余白) + 5 = 330、
        // 5 + 10 + 200 + 10(下の余白。BFC の根なので突き抜けない) + 5 = 230
        float[] r = of("<div id=s style='overflow:auto;width:100px;height:50px;padding:5px'>"
                + "<div style='width:300px;height:200px;margin:10px'></div></div>", "s");
        assertArrayEquals(new float[]{330, 230, 315, 215}, r, EPS);
    }

    @Test
    void fittingContentKeepsTheBoxSize() {
        float[] r = of("<div id=s style='width:100px;height:50px'><div style='height:20px'></div></div>", "s");
        assertArrayEquals(new float[]{100, 50, 100, 50}, r, EPS);
    }

    @Test
    void absoluteChildrenDoNotReachButNonClippingDescendantsOverflow() {
        // 絶対配置の子は数えない。clip しない子の中のはみ出し（子 50 x 30 の中の 80 x 90）は伝わる。clip する子の中は伝わらない
        float[] r = of("<div id=s style='width:100px;height:50px;position:relative'>"
                + "<div style='position:absolute;left:0;top:0;width:500px;height:500px'></div>"
                + "<div style='width:50px;height:30px'><div style='width:80px;height:90px'></div></div>"
                + "<div style='width:50px;height:10px;overflow:hidden'><div style='width:400px;height:400px'></div></div></div>", "s");
        assertArrayEquals(new float[]{100, 90, 100, 90}, r, EPS);
    }

    @Test
    void escapingBottomMarginOfTheLastChildIsNotCounted() {
        // 高さ auto・下の padding も border も無い箱: 最後の子の下の余白 40 は外へ突き抜けるので数えない（高さ 20 = 子の高さ）
        float[] r = of("<div id=s style='width:100px'><div style='height:20px;margin-bottom:40px'></div></div>", "s");
        assertArrayEquals(new float[]{100, 20, 100, 20}, r, EPS);
    }
}
