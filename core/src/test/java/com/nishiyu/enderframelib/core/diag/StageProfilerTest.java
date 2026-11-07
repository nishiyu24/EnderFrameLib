package com.nishiyu.enderframelib.core.diag;

import com.nishiyu.enderframelib.core.diag.StageProfiler.StageStats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class StageProfilerTest {

    @BeforeEach
    @AfterEach
    void clean() {
        StageProfiler.enable(false);
        StageProfiler.trackAllocations(false);
        StageProfiler.reset();
    }

    private static StageStats stats(PipelineStage s) {
        return StageProfiler.snapshot().stream().filter(x -> x.stage() == s).findFirst().orElse(null);
    }

    @Test
    void disabledRecordsNothingAndBeginReturnsZero() {
        assertEquals(0L, StageProfiler.begin(PipelineStage.DISPLAY_LIST));
        StageProfiler.end(PipelineStage.DISPLAY_LIST, 0L);
        StageProfiler.record(PipelineStage.DISPLAY_LIST, 1_000_000);
        assertTrue(StageProfiler.snapshot().isEmpty());
    }

    @Test
    void beginEndRecordsElapsed() throws Exception {
        StageProfiler.enable(true);
        long t0 = StageProfiler.begin(PipelineStage.STYLE_BUILD);
        assertNotEquals(0L, t0);
        Thread.sleep(5);
        StageProfiler.end(PipelineStage.STYLE_BUILD, t0);
        StageStats s = stats(PipelineStage.STYLE_BUILD);
        assertNotNull(s);
        assertEquals(1, s.count());
        assertTrue(s.lastMs() >= 4.0, "sleep(5) を下回る: " + s.lastMs());
        assertEquals(-1.0, s.allocKb(), "割り当て計測は既定で無効");
    }

    @Test
    void endWithZeroStartIsIgnoredEvenWhenEnabledLater() {
        long t0 = StageProfiler.begin(PipelineStage.BOX_TREE); // 無効の間に開始
        StageProfiler.enable(true);
        StageProfiler.end(PipelineStage.BOX_TREE, t0);          // 0 なので無視（途中から有効にしても壊れた値を入れない）
        assertTrue(StageProfiler.snapshot().isEmpty());
    }

    @Test
    void percentilesMaxAndMean() {
        StageProfiler.enable(true);
        for (int ms = 1; ms <= 100; ms++) StageProfiler.record(PipelineStage.PAINT, ms * 1_000_000L);
        StageStats s = stats(PipelineStage.PAINT);
        assertEquals(100, s.count());
        assertEquals(50.0, s.p50Ms(), 1.0);
        assertEquals(95.0, s.p95Ms(), 1.0);
        assertEquals(100.0, s.maxMs(), 1e-9);
        assertEquals(50.5, s.meanMs(), 1e-9);
        assertEquals(5050.0, s.totalMs(), 1e-6);
        assertEquals(100.0, s.lastMs(), 1e-9);
    }

    @Test
    void ringKeepsOnlyRecentSamplesButCountsAll() {
        StageProfiler.enable(true);
        for (int i = 0; i < 1024; i++) StageProfiler.record(PipelineStage.GPU, 1_000_000_000L); // 古い 1000ms
        for (int i = 0; i < 1024; i++) StageProfiler.record(PipelineStage.GPU, 1_000_000L);     // 新しい 1ms
        StageStats s = stats(PipelineStage.GPU);
        assertEquals(2048, s.count());
        assertEquals(1.0, s.p95Ms(), 1e-9, "輪は新しい標本だけを見る");
        assertEquals(1000.0, s.maxMs(), 1e-9, "最大は全期間");
    }

    @Test
    void negativeNanosAreClampedAndResetClears() {
        StageProfiler.enable(true);
        StageProfiler.record(PipelineStage.REPOSITION, -5);
        assertEquals(0.0, stats(PipelineStage.REPOSITION).lastMs());
        StageProfiler.reset();
        assertTrue(StageProfiler.snapshot().isEmpty());
    }

    @Test
    void allocationTrackingMeasuresBytes() {
        StageProfiler.enable(true);
        StageProfiler.trackAllocations(true);
        long t0 = StageProfiler.begin(PipelineStage.DISPLAY_LIST);
        byte[][] keep = new byte[64][];
        for (int i = 0; i < keep.length; i++) keep[i] = new byte[1024];
        StageProfiler.end(PipelineStage.DISPLAY_LIST, t0);
        assertEquals(1024, keep[63].length);
        double kb = stats(PipelineStage.DISPLAY_LIST).allocKb();
        assertTrue(kb >= 60.0, "64KB 確保したはず: " + kb);
    }

    @Test
    void snapshotIsOrderedByDeclarationAndReportListsStages() {
        StageProfiler.enable(true);
        StageProfiler.record(PipelineStage.PAINT, 1);
        StageProfiler.record(PipelineStage.STYLE_BUILD, 1);
        List<StageStats> all = StageProfiler.snapshot();
        assertEquals(List.of(PipelineStage.STYLE_BUILD, PipelineStage.PAINT), all.stream().map(StageStats::stage).toList());
        String report = StageProfiler.report();
        assertTrue(report.contains("style+build") && report.contains("paint(cpu)"));
    }

    @Test
    void concurrentRecordingLosesNothing() throws Exception {
        StageProfiler.enable(true);
        int threads = 8, per = 5000;
        var pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.execute(() -> {
                for (int i = 0; i < per; i++) StageProfiler.record(PipelineStage.LAYOUT_MEASURE, 10);
                done.countDown();
            });
        }
        done.await();
        pool.shutdown();
        assertEquals((long) threads * per, stats(PipelineStage.LAYOUT_MEASURE).count());
    }
}
