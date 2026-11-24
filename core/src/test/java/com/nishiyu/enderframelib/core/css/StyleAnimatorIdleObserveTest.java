package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** B9: 動くものが無い観測は状態を残さず、その後に本当の変化が来ればトランジションが始まる。 */
class StyleAnimatorIdleObserveTest {

    private static ComputedStyle style(String color) {
        return new ComputedStyle(Map.of("transition", "width 200ms linear", "width", color));
    }

    @Test
    void idleObservesLeaveNothingRunning() {
        StyleAnimator a = new StyleAnimator();
        HtmlElement n = new HtmlElement("button");
        ComputedStyle s = style("10px");
        for (int i = 0; i < 50; i++) a.observe(n, s, s, Style.DEFAULT, Map.of(), 1000L + i);
        assertFalse(a.isRunning(1100L));
        assertTrue(a.advance(1100L).isEmpty());
    }

    @Test
    void realChangeAfterIdleStartsTransitionAndFinishes() {
        StyleAnimator a = new StyleAnimator();
        HtmlElement n = new HtmlElement("button");
        ComputedStyle red = style("10px"), blue = style("90px");
        a.observe(n, red, red, Style.DEFAULT, Map.of(), 1000L);
        a.observe(n, red, blue, Style.DEFAULT, Map.of(), 1010L);
        assertTrue(a.isRunning(1100L));
        assertNotNull(a.sample(n, 1100L));
        // 別の要素の観測が（再利用される空の状態を借りて）動いている要素に混ざらない
        HtmlElement other = new HtmlElement("button");
        a.observe(other, red, red, Style.DEFAULT, Map.of(), 1100L);
        Map<String, String> os = a.sample(other, 1100L);
        assertTrue(os == null || os.isEmpty(), "idle element must not inherit the running transition");
        a.advance(5000L);
        assertFalse(a.isRunning(5000L));
    }
}
