package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 計測用: スタイルの使い回しを切った冷たい構築 1 回あたりの CPU と確保量（カスケード・算出の作業表の A/B 用）。 */
class CascadeColdCostTest {
    @AfterEach
    void restore() {
        StyleResolver.ShareCache.enabled = true;
    }

    private static long allocated() {
        return ((com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean())
                .getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    @Test
    void measureColdBuild() {
        StyleResolver.ShareCache.enabled = false;
        StringBuilder html = new StringBuilder("<style>:root{--g:6px} body{font-size:14px;color:#222} .r{height:18px;margin-bottom:var(--g);padding:2px 4px;border:1px solid #ccc}"
                + " .r b{color:red;font-size:1.2em} .r:nth-child(odd){background:#eee}</style>");
        for (int i = 0; i < 300; i++) html.append("<div class=r><span>a").append(i).append("</span> <b>x</b> <i>y</i></div>");
        String doc = html.toString();
        for (int i = 0; i < 60; i++) new StandaloneView(doc, 400, 400, FontResolver.FALLBACK).ui().flushInvalidation();
        int runs = 40;
        long[] times = new long[runs];
        long alloc = 0;
        for (int i = 0; i < runs; i++) {
            long a0 = allocated(), t0 = System.nanoTime();
            new StandaloneView(doc, 400, 400, FontResolver.FALLBACK).ui().flushInvalidation();
            times[i] = System.nanoTime() - t0;
            alloc += allocated() - a0;
        }
        java.util.Arrays.sort(times);
        long cpu = times[runs / 2];
        System.out.printf("    COLD-BUILD per build: median %.3f ms, min %.3f ms, alloc %d KB%n", cpu / 1e6, times[0] / 1e6, alloc / runs / 1024);
        assertTrue(cpu > 0);
    }
}
