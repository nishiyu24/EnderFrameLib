package com.nishiyu.enderframelib.core.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * B11: 並列区間を始めた呼び出し側のスレッドも仕事を取る（以前は {@code ForkJoinPool.invoke} で待つだけだった）。
 * どの番号も 1 回だけ実行されること・呼び出し側が実際に加わること・入れ子で再び配らないこと・例外が呼び出し側へ伝わること・
 * 区間の割り方が以前の式と同じことを確かめる。
 */
class LayoutTaskPoolCallerParticipationTest {

    @BeforeEach
    void fourWorkers() {
        LayoutTaskPool.parallelEnabled = true;
        LayoutTaskPool.setWorkerThreads(4);
    }

    @AfterEach
    void restore() {
        LayoutTaskPool.parallelEnabled = true;
        LayoutTaskPool.setWorkerThreads(LayoutTaskPool.defaultThreads());
    }

    /** 少し時間の掛かる仕事（ワーカーが起きる前に呼び出し側が番号を取れる程度）。 */
    private static long spin(int i) {
        long x = i;
        for (int k = 0; k < 20_000; k++) x = x * 6364136223846793005L + 1442695040888963407L;
        return x;
    }

    @Test
    void everyIndexRunsOnceAndTheCallerTakesAShare() {
        int n = 2_000;
        AtomicIntegerArray runs = new AtomicIntegerArray(n);
        Thread caller = Thread.currentThread();
        AtomicInteger onCaller = new AtomicInteger();
        Set<Thread> threads = ConcurrentHashMap.newKeySet();
        long[] sink = new long[1];
        LayoutTaskPool.forRange(n, 4, null, i -> {
            runs.incrementAndGet(i);
            threads.add(Thread.currentThread());
            if (Thread.currentThread() == caller) onCaller.incrementAndGet();
            sink[0] ^= spin(i);
        });
        for (int i = 0; i < n; i++) assertEquals(1, runs.get(i), "index " + i);
        assertTrue(onCaller.get() > 0, "呼び出し側のスレッドも仕事を取る");
        assertTrue(threads.size() >= 2, "ワーカーも加わる: " + threads.size());
        assertFalse(LayoutTaskPool.inWorker(), "区間を抜けたら呼び出し側は仕事中ではない");
    }

    @Test
    void callerCountsAsInsideATaskSoNestedCallsRunInline() {
        Thread caller = Thread.currentThread();
        AtomicInteger nestedOnCaller = new AtomicInteger();
        AtomicInteger callerSawInWorker = new AtomicInteger();
        LayoutTaskPool.resetParallelDispatchCount();
        LayoutTaskPool.forRange(256, 1, null, i -> {
            Thread self = Thread.currentThread();
            assertTrue(LayoutTaskPool.inWorker(), "区間の中は（呼び出し側でも）仕事中");
            if (self == caller) callerSawInWorker.incrementAndGet();
            // 入れ子の区間は配らずに、その場で実行される
            LayoutTaskPool.forRange(64, 1, null, j -> {
                if (Thread.currentThread() != self) throw new AssertionError("入れ子の区間が別のスレッドへ配られた");
                if (self == caller) nestedOnCaller.incrementAndGet();
            });
            spin(i);
        });
        assertEquals(1, LayoutTaskPool.parallelDispatchCount(), "外側の 1 回だけ配る");
        assertTrue(callerSawInWorker.get() > 0);
        assertEquals(callerSawInWorker.get() * 64, nestedOnCaller.get());
    }

    @Test
    void failureReachesTheCallerAndThePoolStaysUsable() {
        IllegalStateException boom = new IllegalStateException("boom");
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> LayoutTaskPool.forRange(1_000, 2, null, i -> {
                    spin(i);
                    if (i == 777) throw boom;
                }));
        assertSame(boom, thrown);
        assertFalse(LayoutTaskPool.inWorker(), "失敗しても呼び出し側の印は外れる");
        AtomicInteger sum = new AtomicInteger();
        LayoutTaskPool.forRange(1_000, 2, null, i -> sum.addAndGet(i));
        assertEquals(999 * 1_000 / 2, sum.get());
    }

    @Test
    void chunkSpansFollowTheSameFormulaAndCoverEveryItemOnce() {
        int items = 1_003, chunks = 7;
        AtomicIntegerArray covered = new AtomicIntegerArray(items);
        AtomicIntegerArray chunkSeen = new AtomicIntegerArray(chunks);
        LayoutTaskPool.forChunks(items, chunks, null, span -> {
            int c = LayoutTaskPool.spanChunk(span);
            int start = LayoutTaskPool.spanStart(span);
            int end = LayoutTaskPool.spanEnd(span);
            assertEquals((int) ((long) c * items / chunks), start, "chunk " + c + " start");
            assertEquals((int) ((long) (c + 1) * items / chunks), end, "chunk " + c + " end");
            chunkSeen.incrementAndGet(c);
            for (int i = start; i < end; i++) covered.incrementAndGet(i);
        });
        for (int c = 0; c < chunks; c++) assertEquals(1, chunkSeen.get(c), "chunk " + c);
        for (int i = 0; i < items; i++) assertEquals(1, covered.get(i), "item " + i);
    }

    @Test
    void scopeRunnerWrapsEveryChunkOnEveryThread() {
        ThreadLocal<Boolean> inScope = ThreadLocal.withInitial(() -> false);
        AtomicInteger outside = new AtomicInteger();
        LayoutTaskPool.forRange(500, 5, r -> {
            inScope.set(true);
            try {
                r.run();
            } finally {
                inScope.set(false);
            }
        }, i -> {
            if (!inScope.get()) outside.incrementAndGet();
        });
        assertEquals(0, outside.get(), "どのスレッドでも、仕事はスコープの中で走る");
    }

    @Test
    void participantsCountTheCallerAndSplitIntoSeveralChunksPerThread() {
        assertEquals(5, LayoutTaskPool.participants());
        assertEquals(5 * LayoutTaskPool.SPLIT_PER_PARTICIPANT, LayoutTaskPool.chunksFor(2_000, 16));
        assertEquals(3, LayoutTaskPool.chunksFor(40, 16), "小さい並びは 1 区間 16 件以上");
        assertEquals(2_000 / (5 * LayoutTaskPool.SPLIT_PER_PARTICIPANT), LayoutTaskPool.grainFor(2_000, 8));
        assertEquals(8, LayoutTaskPool.grainFor(40, 8));
        LayoutTaskPool.setWorkerThreads(1);
        assertEquals(1, LayoutTaskPool.participants(), "直列なら呼び出し側だけ");
    }
}
