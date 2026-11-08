package com.nishiyu.enderframelib.core.css;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 静止フレーム（動いているものが無い）の {@link StyleAnimator#advance} は、一覧も反復子も作らない。 */
public class StyleAnimatorIdleTest {

    @Test
    public void idleAdvanceReturnsSharedEmptyAndAllocatesNothing() {
        StyleAnimator animator = new StyleAnimator();
        assertTrue(animator.advance(0).isEmpty());
        assertSame(animator.advance(1), animator.advance(2), "空の結果は共有の不変一覧");
        assertTrue(!animator.isRunning(0));

        ThreadMXBean mx = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().getId();
        for (int i = 0; i < 20_000; i++) animator.advance(i); // JIT を温める
        int n = 100_000;
        long before = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < n; i++) animator.advance(i);
        long perCall = (mx.getThreadAllocatedBytes(tid) - before) / n;
        assertTrue(perCall <= 1, "静止時の advance は割り当て 0: " + perCall + " B/call");
    }
}
