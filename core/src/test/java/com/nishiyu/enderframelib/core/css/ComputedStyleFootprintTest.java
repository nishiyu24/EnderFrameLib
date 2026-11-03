package com.nishiyu.enderframelib.core.css;

import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ComputedStyle} の参照キャッシュのメモリ・割り当てと、並行して使っても壊れないこと。 */
class ComputedStyleFootprintTest {

    private static Map<String, String> declarations() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("display", "block");
        m.put("width", "120px");
        m.put("height", "2em");
        m.put("margin-top", "4px");
        m.put("padding-left", "8px");
        m.put("color", "#336699");
        m.put("background-color", "rgba(10,20,30,0.5)");
        m.put("opacity", "0.75");
        m.put("z-index", "3");
        m.put("position", "relative");
        m.put("overflow-x", "hidden");
        m.put("float", "none");
        return m;
    }

    /** 1 つのスタイルへ、描画・レイアウトが典型的に行う型付きアクセスをする。 */
    private static float touch(ComputedStyle s) {
        float acc = 0;
        acc += s.length("width", 500, 800, 600);
        acc += s.length("height", 500, 800, 600);
        acc += s.length("margin-top", 500, 800, 600);
        acc += s.length("padding-left", 500, 800, 600);
        acc += s.color("color", 0);
        acc += s.color("background-color", 0);
        acc += s.number("opacity", 1f);
        acc += s.number("z-index", 0f);
        acc += s.keywordOf("position", CssKeywords.Position.class).ordinal();
        acc += s.keywordOf("float", CssKeywords.FloatSide.class).ordinal();
        acc += s.overflowX().ordinal();
        acc += s.paintMemo("k", k -> "v").length();
        return acc;
    }

    private static long allocatedBytes() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean())
                .getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    @Test
    void perStyleAllocationStaysSmall() {
        Map<String, String> decl = declarations();
        ComputedStyle warm = new ComputedStyle(decl, 16f, 16f);
        for (int i = 0; i < 2000; i++) touch(new ComputedStyle(warm.asMap(), 16f, 16f));
        int n = 2000;
        float sink = 0;
        long before = allocatedBytes();
        for (int i = 0; i < n; i++) sink += touch(new ComputedStyle(warm.asMap(), 16f, 16f));
        long perStyle = (allocatedBytes() - before) / n;
        System.out.println("[computed-style footprint] bytes per style (copy + 12 typed accesses) = " + perStyle + " (" + sink + ")");
        assertTrue(perStyle < 4500, "スタイル 1 つあたりの割り当てが大きすぎる: " + perStyle + " bytes");
    }

    @Test
    void typedAccessIsFastWhenCached() {
        ComputedStyle s = new ComputedStyle(declarations(), 16f, 16f);
        float sink = touch(s);
        for (int i = 0; i < 200_000; i++) sink += touch(s); // 暖機
        long t0 = System.nanoTime();
        int reps = 2_000_000;
        for (int i = 0; i < reps; i++) sink += touch(s);
        long ns = System.nanoTime() - t0;
        System.out.println("[computed-style access] " + (ns / (double) reps) + " ns per 12 cached typed accesses (" + sink + ")");
        assertTrue(ns / (double) reps < 3000, "キャッシュ済みの型付き参照が遅い: " + ns / (double) reps + " ns / 12 accesses");
    }

    /** 複数のスレッドが同じスタイルを同時に引いても、値が壊れない（ロックなしで安全な公開）。 */
    @Test
    void concurrentReadersSeeConsistentValues() throws Exception {
        for (int round = 0; round < 50; round++) {
            ComputedStyle s = new ComputedStyle(declarations(), 16f, 16f);
            float expected = touch(new ComputedStyle(declarations(), 16f, 16f));
            int threads = 8;
            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicInteger bad = new java.util.concurrent.atomic.AtomicInteger();
            Thread[] ts = new Thread[threads];
            for (int t = 0; t < threads; t++) {
                ts[t] = new Thread(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        return;
                    }
                    for (int i = 0; i < 200; i++) if (touch(s) != expected) bad.incrementAndGet();
                });
                ts[t].start();
            }
            go.countDown();
            for (Thread t : ts) t.join();
            assertEquals(0, bad.get(), "round " + round);
        }
    }

    @Test
    void paintMemoComputesOnceForManyDistinctKeysAndStaysCorrect() {
        ComputedStyle s = new ComputedStyle(declarations(), 16f, 16f);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        for (int pass = 0; pass < 3; pass++) {
            for (int k = 0; k < 500; k++) {
                int key = k;
                Integer v = s.paintMemo(key, kk -> {
                    calls.incrementAndGet();
                    return key * 2;
                });
                assertEquals(key * 2, v);
            }
        }
        assertEquals(500, calls.get(), "同じ鍵は 1 回しか計算しない（多数の鍵でも）");
    }
}
