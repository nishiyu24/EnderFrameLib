package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.core.kernel.Clock;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

/**
 * K0 ①: 時刻は注入された {@link Clock} を通る。リサイズの間引き（直前のレイアウト所要時間の 2 倍まで反映を持ち越す）が、
 * 実時間を待たず手動の時計だけで決定的に再現・解除できること（{@code MeasureReadSetTest} の不安定の原因 §6-178 を構造で断つ）。
 */
class ClockInjectionTest {

    private static final String HTML = "<style>body{margin:0}.a{width:50%;height:20px;background:#ccc}</style><div class=\"a\">x</div>";

    /** 手動の時計は、進めない限り止まっている。 */
    @Test
    void manualClockOnlyMovesWhenAdvanced() {
        Clock.Manual c = new Clock.Manual(5_000_000L);
        assertEquals(5_000_000L, c.nanos());
        assertEquals(5L, c.millis());
        c.advanceMillis(3);
        assertEquals(8_000_000L, c.nanos());
        assertEquals(8L, c.millis());
    }

    /** 既定は実時計（単調に進む）。コアが時刻を読む入口はここだけ。 */
    @Test
    void defaultClockIsTheSystemClock() {
        try (StandaloneView v = new StandaloneView(HTML, 200, 100, FontResolver.FALLBACK)) {
            assertEquals(Clock.SYSTEM, v.ui().clock);
            long a = Clock.SYSTEM.nanos();
            assertTrue(Clock.SYSTEM.nanos() >= a);
            v.clock(null);
            assertEquals(Clock.SYSTEM, v.ui().clock, "null は実時計へ戻す");
        }
    }

    /**
     * 間引きの再現: 直前のレイアウトに 10 ms かかったと時計が言うとき、resize は 20 ms が過ぎるまで持ち越され、過ぎたら反映される。
     * 実時間は 1 ms も待たない。
     */
    @Test
    void resizeThrottleFollowsTheInjectedClock() {
        Clock.Manual clock = new Clock.Manual();
        try (StandaloneView v = new StandaloneView(HTML, 200, 100, FontResolver.FALLBACK);
             var be = new MutationScenario.NoDraw()) {
            v.clock(clock);
            // レイアウトの前後で時計を進めて「重いレイアウト」を作る: 開始時刻・終了時刻を時計から取る
            v.frame(1000, be, true);
            v.ui().lastLayoutCostNanos = 10_000_000L; // 直前のレイアウトは 10 ms
            v.ui().lastLayoutFinishedNanos = clock.nanos();

            v.resize(300, 100);
            clock.advanceMillis(5); // 5 ms < 20 ms: 持ち越し
            v.frame(1016, be, true);
            assertFalse(v.ui().rootW() == 300, "間引きの窓の中では反映されない");

            clock.advanceMillis(20); // 25 ms >= 20 ms: 反映
            v.frame(1032, be, true);
            assertEquals(300, v.ui().rootW(), "窓を過ぎたら反映される");
        }
    }

    /** 止めた時計（所要時間 0）では間引きが起きず、resize は常に 1 フレームで反映される（{@link ResizeSettle}）。 */
    @Test
    void frozenClockNeverThrottlesResize() {
        for (int i = 0; i < 50; i++) {
            try (StandaloneView v = new StandaloneView(HTML, 200, 100, FontResolver.FALLBACK);
                 var be = new MutationScenario.NoDraw()) {
                ResizeSettle.freezeClock(v);
                v.frame(1000, be, true);
                v.resize(260 + i, 100);
                v.frame(1016, be, true);
                assertEquals(260 + i, v.ui().rootW());
                v.resize(180, 100); // 直後の連続した resize も間引かれない
                v.frame(1032, be, true);
                assertEquals(180, v.ui().rootW());
            }
        }
    }

    /** アニメーション・レイアウトの所要時間も、注入した時計から取る（実時計を読まない）。 */
    @Test
    void layoutCostIsMeasuredWithTheInjectedClock() {
        Clock.Manual clock = new Clock.Manual(777L);
        try (StandaloneView v = new StandaloneView(HTML, 200, 100, FontResolver.FALLBACK);
             var be = new MutationScenario.NoDraw()) {
            v.clock(clock);
            v.resize(240, 100);
            v.frame(1000, be, true);
            assertEquals(0L, v.ui().lastLayoutCostNanos, "止めた時計ではレイアウトの所要時間は 0");
            assertEquals(777L, v.ui().lastLayoutFinishedNanos);
        }
    }
}
