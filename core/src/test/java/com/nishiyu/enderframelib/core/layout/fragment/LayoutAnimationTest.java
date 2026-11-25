package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * M2b-1: レイアウトに効くアニメーション（計画書 §3.4.2-5）。トランジションと {@code @keyframes} の補間値は {@code StyleSource} が
 * {@code StyleAnimator} で目標値に重ね、動いている節は駆動側が毎フレーム台帳へ {@code STYLE} を足す（{@code SpikeView.frame} が
 * {@code StyleSource.appendAnimating} を呼ぶ）。時刻は {@code InteractionState.setAnimationTime} で止めて進め、実時間を待たない。
 */
class LayoutAnimationTest {
    private static final float EPS = 0.05f;

    private static float width(SpikeView v, long t, String id) {
        v.state.setAnimationTime(t);
        float[] r = SpikeView.find(v.frame(), id);
        assertNotNull(r, id);
        return r[2];
    }

    @Test
    void widthTransitionInterpolatesFrameByFrameAndSettles() {
        SpikeView v = new SpikeView("<html><body style='margin:0'><div id=a style='width:100px;height:10px;transition:width 1000ms linear'>"
                + "</div></body></html>", 800, 600);
        assertEquals(100, width(v, 0, "a"), EPS);
        ((HtmlElement) v.byId("a")).setAttribute("style", "width:200px;height:10px;transition:width 1000ms linear");
        assertEquals(100, width(v, 0, "a"), EPS); // 始まった瞬間は元の値
        assertEquals(150, width(v, 500, "a"), EPS); // 台帳に触れずに時刻だけ進めても、駆動側が STYLE を足して補間する
        assertEquals(175, width(v, 750, "a"), EPS);
        assertEquals(200, width(v, 1000, "a"), EPS);
        assertEquals(200, width(v, 2000, "a"), EPS); // 終わった後は目標値のまま
    }

    @Test
    void keyframesAnimationDrivesLayout() {
        SpikeView v = new SpikeView("<html><head><style>@keyframes grow { from { width: 10px } to { width: 110px } }"
                + " #a { height: 5px; width: 50px; animation: grow 1000ms linear }</style></head><body style='margin:0'><div id=a></div></body></html>",
                800, 600);
        assertEquals(10, width(v, 0, "a"), EPS);
        assertEquals(60, width(v, 500, "a"), EPS);
        assertEquals(50, width(v, 1500, "a"), EPS); // fill-mode none: 終わったら指定の値
    }
}
