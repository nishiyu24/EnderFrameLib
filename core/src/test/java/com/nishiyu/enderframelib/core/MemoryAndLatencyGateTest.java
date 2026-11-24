package com.nishiyu.enderframelib.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.common.UiView;
import com.nishiyu.enderframelib.core.MemoryAndLatencyGate.BaselineEntry;
import com.nishiyu.enderframelib.core.MemoryAndLatencyGate.ScenarioTarget;
import com.nishiyu.enderframelib.core.paint.RenderBackend;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@link MemoryAndLatencyGate} の単体検証・保持ヒープ検査・交互 A/B レイテンシ検査（計画書 §3.1 Phase M0）。
 */
class MemoryAndLatencyGateTest {

    private static final class NoOpBackend implements RenderBackend {
        @Override public void render(List<DisplayItem> items, long nowMs) { }
        @Override public Pixmap pixmap() { throw new UnsupportedOperationException(); }
        @Override public float devicePixelRatio() { return 1f; }
    }

    @Test
    void baselineFileExistsAndCoversAllScenarios() throws IOException {
        Path baselinePath = MemoryAndLatencyGate.BASELINE;
        assertTrue(Files.isRegularFile(baselinePath), "基準値ファイルが無い: " + baselinePath);
        Map<String, BaselineEntry> entries = MemoryAndLatencyGate.readBaseline(baselinePath);
        for (String[] r : MemoryAndLatencyGate.HEAP_RUNS) {
            String key = "heap:" + r[0] + ":" + r[1];
            assertTrue(entries.containsKey(key), "ヒープ基準値が不足: " + key);
            assertTrue(entries.get(key).maxAllowed() > 0.0, "上限が正でない: " + key);
        }
        for (String[] r : MemoryAndLatencyGate.AB_INC_VS_FULL_RUNS) {
            String key = "ab-inc-vs-full:" + r[0] + ":" + r[1];
            assertTrue(entries.containsKey(key), "交互 A/B 基準値が不足: " + key);
        }
        for (String[] r : MemoryAndLatencyGate.LATENCY_RUNS) {
            String key = "latency:" + r[0] + ":" + r[1];
            assertTrue(entries.containsKey(key), "レイテンシ基準値が不足: " + key);
        }
    }

    @Test
    void gateDetectsHeapAndLatencyRegressions() {
        Map<String, BaselineEntry> base = Map.of(
                "heap:showcase:open", new BaselineEntry(3.5, 6.0),
                "heap:varied-list-2000:open", new BaselineEntry(38.0, 50.0));
        assertTrue(MemoryAndLatencyGate.checkHeap(Map.of("heap:showcase:open", 4.0), base).isEmpty());
        List<String> heapFail = MemoryAndLatencyGate.checkHeap(Map.of("heap:varied-list-2000:open", 55.0), base);
        assertEquals(1, heapFail.size(), heapFail.toString());

        assertEquals(2.0, MemoryAndLatencyGate.median(new double[]{3.0, 1.0, 2.0}), 1e-6);
        assertEquals(2.5, MemoryAndLatencyGate.median(new double[]{1.0, 4.0, 2.0, 3.0}), 1e-6);

        assertTrue(MemoryAndLatencyGate.checkAbComparison("ok", new double[]{10, 11, 10}, new double[]{10.5, 10.2, 10.1}, 1.10).isEmpty());
        List<String> abFail = MemoryAndLatencyGate.checkAbComparison("slow", new double[]{10, 10, 10}, new double[]{13, 12.5, 14}, 1.10);
        assertEquals(1, abFail.size(), abFail.toString());
    }

    @Test
    void retainedHeapInFreshChildJvmStaysWithinBaseline() throws Exception {
        String cp = System.getProperty("java.class.path");
        Map<String, BaselineEntry> baseline = MemoryAndLatencyGate.readBaseline(MemoryAndLatencyGate.BASELINE);
        for (String[] pair : new String[][]{{"showcase", "open"}, {"large-list-2000", "open"}}) {
            String key = "heap:" + pair[0] + ":" + pair[1];
            Process p = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Denderframe.threads=1", "-Xmx1g",
                    "-cp", cp, MemoryAndLatencyGate.class.getName(), "child", "heap", pair[0], pair[1])
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            assertEquals(0, p.waitFor(), out);
            double mb = -1;
            for (String line : out.split("\\R")) {
                if (line.startsWith("HEAP ")) {
                    for (String tok : line.split("\\s+")) {
                        if (tok.startsWith("mb=")) mb = Double.parseDouble(tok.substring(3));
                    }
                }
            }
            assertTrue(mb >= 0, "HEAP 出力が見つからない:\n" + out);
            BaselineEntry cap = baseline.get(key);
            assertTrue(mb <= cap.maxAllowed(), key + " 保持ヒープ超過: " + mb + " > " + cap.maxAllowed());
        }
    }

    @Test
    @Tag("timing")
    void alternatingAbIncrementalBeatsFullRebuild() throws IOException {
        String html = MemoryAndLatencyGate.largeList(500);
        NoOpBackend backend = new NoOpBackend();
        try (StandaloneView v = new StandaloneView(html, MemoryAndLatencyGate.VIEWPORT_W, MemoryAndLatencyGate.VIEWPORT_H, FontResolver.FALLBACK)) {
            long[] now = {1000};
            v.frame(now[0], backend, true);
            ScenarioTarget t = ScenarioTarget.find(v, "large-list-500");
            StandaloneView[] box = {v};
            for (int i = 0; i < 60; i++) {
                MemoryAndLatencyGate.step("text", box, backend, t, now, i, html, (i & 1) == 0);
            }
            double[] incRounds = new double[3], fullRounds = new double[3];
            for (int r = 0; r < 3; r++) {
                if ((r & 1) == 0) {
                    fullRounds[r] = measureMeanMs("text", box, backend, t, now, html, true);
                    incRounds[r] = measureMeanMs("text", box, backend, t, now, html, false);
                } else {
                    incRounds[r] = measureMeanMs("text", box, backend, t, now, html, false);
                    fullRounds[r] = measureMeanMs("text", box, backend, t, now, html, true);
                }
            }
            double incMedianMs = MemoryAndLatencyGate.median(incRounds);
            double fullMedianMs = MemoryAndLatencyGate.median(fullRounds);
            assertFalse(Double.isNaN(incMedianMs));
            assertTrue(incMedianMs < fullMedianMs,
                    "交互 A/B で増分が全再計算より遅い: inc=" + incMedianMs + " ms, full=" + fullMedianMs + " ms");
        }
    }

    private static double measureMeanMs(String scenario, StandaloneView[] view, RenderBackend backend,
                                        ScenarioTarget t, long[] now, String html, boolean full) {
        int frames = 60;
        long totalNs = 0;
        for (int i = 0; i < frames; i++) {
            long t0 = System.nanoTime();
            MemoryAndLatencyGate.step(scenario, view, backend, t, now, i, html, full);
            totalNs += System.nanoTime() - t0;
            if (full) view[0].ui().markInvalidation(UiView.InvalidationLevel.RELAYOUT);
        }
        return totalNs / 1e6 / frames;
    }
}
