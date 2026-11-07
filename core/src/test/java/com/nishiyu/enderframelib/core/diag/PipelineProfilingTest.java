package com.nishiyu.enderframelib.core.diag;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.paint.ProfilingRenderBackend;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** 計測を有効にしても描画結果が変わらないこと、パイプラインの各段が実際に記録されること。 */
class PipelineProfilingTest {

    private static final String HTML = "<html><head><style>li{height:20px;background:#eee;border-radius:4px}"
            + " li.hot{color:#f00}</style></head><body style='margin:0'><ul>"
            + "<li id='a'>one</li><li>two</li><li>three</li></ul></body></html>";

    @BeforeEach
    @AfterEach
    void clean() {
        StageProfiler.enable(false);
        StageProfiler.trackAllocations(false);
        StageProfiler.reset();
    }

    private static int[] renderPixels(boolean profiled) {
        StageProfiler.enable(profiled);
        StandaloneView view = new StandaloneView(HTML, 160, 120);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(160, 120, 1f);
             ProfilingRenderBackend wrapped = new ProfilingRenderBackend(cpu)) {
            view.frame(1000, wrapped, true);
            return Arrays.copyOf(wrapped.pixmap().pixels(), 160 * 120);
        }
    }

    @Test
    void profilingDoesNotChangePixels() {
        int[] off = renderPixels(false);
        StageProfiler.reset();
        int[] on = renderPixels(true);
        assertArrayEquals(off, on);
    }

    @Test
    void profilingDoesNotChangeDisplayList() {
        StageProfiler.enable(false);
        List<DisplayItem> off = new StandaloneView(HTML, 160, 120).items();
        StageProfiler.enable(true);
        List<DisplayItem> on = new StandaloneView(HTML, 160, 120).items();
        assertEquals(off, on);
    }

    @Test
    void everyPipelineStageIsRecorded() {
        StageProfiler.enable(true);
        StandaloneView view = new StandaloneView(HTML, 160, 120);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(160, 120, 1f);
             ProfilingRenderBackend wrapped = new ProfilingRenderBackend(cpu)) {
            view.frame(1000, wrapped, true);
            view.root().getElementById("a").getClassList().add("hot");
            view.frame(1016, wrapped, false);
        }
        Set<PipelineStage> seen = StageProfiler.snapshot().stream().map(StageProfiler.StageStats::stage).collect(Collectors.toSet());
        for (PipelineStage s : List.of(PipelineStage.STYLE_BUILD, PipelineStage.BOX_TREE, PipelineStage.LAYOUT_MEASURE, PipelineStage.LAYOUT_PLACE,
                PipelineStage.DISPLAY_LIST, PipelineStage.PAINT)) {
            assertTrue(seen.contains(s), "記録されていない段: " + s + " / 記録: " + seen);
        }
        assertFalse(seen.contains(PipelineStage.GPU), "GPU 版を使っていないので GPU は空");
        StageProfiler.snapshot().forEach(s -> assertTrue(s.totalMs() >= 0 && s.maxMs() >= s.p50Ms()));
    }

    @Test
    void profilingBackendDelegatesEverything() {
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(32, 24, 2f);
             ProfilingRenderBackend wrapped = new ProfilingRenderBackend(cpu)) {
            assertSame(cpu, wrapped.delegate());
            assertEquals(2f, wrapped.devicePixelRatio());
            assertEquals(cpu.sessionId(), wrapped.sessionId());
            assertSame(cpu.pixmap(), wrapped.pixmap());
        }
    }
}
