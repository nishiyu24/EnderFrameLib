package com.nishiyu.enderframelib.core.paint.gl;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;

/**
 * B9: ホイールの宛先（スクロールする箱）を探すとき、補間状態が無い祖先ごとに {@code AnimatedValues} を確保しない。
 * 以前は showcase のスクロールで毎フレーム約 2.5 KB（その 100% がこの確保）だった。
 */
class ScrollAllocationTest {

    private static long allocated() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()).getThreadAllocatedBytes(Thread.currentThread().threadId());
    }

    @Test
    void wheelTargetSearchDoesNotAllocatePerAncestor() throws Exception {
        try (StandaloneView v = new StandaloneView(GpuBaselineTest.doc("showcase"), 800, 600, FontResolver.FALLBACK)) {
            v.ui().flushInvalidation();
            v.mouseMove(50, 50, "s");
            for (int i = 0; i < 600; i++) { v.scroll(0, (i & 1) == 0 ? 1 : -1, "s"); v.ui().flushInvalidation(); }
            int frames = 400;
            long before = allocated();
            for (int i = 0; i < frames; i++) { v.scroll(0, (i & 1) == 0 ? 1 : -1, "s"); v.ui().flushInvalidation(); }
            long perFrame = (allocated() - before) / frames;
            assertTrue(perFrame < 1_800, "スクロール 1 フレームの確保が多い: " + perFrame + " B/frame");
        }
    }
}
