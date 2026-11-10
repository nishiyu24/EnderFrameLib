package com.nishiyu.enderframelib.core;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.RenderPipeline;
import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** レイアウトの切り分けフラグと診断の途中経過が、プロセス全体の static から文脈・スレッド単位へ移ったことの確認。 */
class EngineContextPipelineFlagsTest {

    private static final String HTML = "<div style=\"width:100px\"><p>a</p><p>b</p></div>";

    private static String statsOnFreshThread(EngineContext ctx) throws Exception {
        var out = new String[1];
        Thread t = new Thread(() -> EngineContext.runWith(ctx, () -> {
            StandaloneView v = new StandaloneView(HTML, 200, 100, FontResolver.FALLBACK);
            v.ui().flushInvalidation();
            out[0] = RenderPipeline.lastBuildStats();
        }));
        t.start();
        t.join();
        return out[0];
    }

    @Test
    void flagDefaultsMatchTheOldStatics() {
        EngineContext c = new EngineContext();
        assertTrue(c.incrementalLayout);
        assertTrue(c.pruneCleanSubtrees);
        assertTrue(c.localRelayout);
        assertTrue(c.inPlaceRestyleRelayout);
        assertFalse(c.trackAllocations);
    }

    @Test
    void flagsAreScopedToTheCurrentThread() {
        EngineContext c = new EngineContext();
        c.localRelayout = false;
        EngineContext.runWith(c, () -> assertFalse(EngineContext.current().localRelayout));
        assertTrue(EngineContext.current().localRelayout, "global は変わらない");
    }

    @Test
    void buildStatsAreKeptPerThread() throws Exception {
        String a = statsOnFreshThread(new EngineContext());
        assertTrue(a.startsWith("boxes="), a);
        // 別スレッドの構築はこのスレッドの値を書き換えない
        String mine = RenderPipeline.lastBuildStats();
        statsOnFreshThread(new EngineContext());
        assertEquals(mine, RenderPipeline.lastBuildStats());
    }

    @Test
    void allocationTrackingFlagComesFromTheContext() throws Exception {
        EngineContext c = new EngineContext();
        c.trackAllocations = true;
        String tracked = statsOnFreshThread(c);
        assertTrue(tracked.contains("allocKB["), tracked);
        assertFalse(statsOnFreshThread(new EngineContext()).contains("allocKB["));
    }

    @Test
    void disablingIncrementalLayoutInOneContextDoesNotAffectAnother() throws Exception {
        EngineContext off = new EngineContext();
        off.incrementalLayout = false;
        // どちらも同じ結果（切り分けフラグは結果を変えない）
        assertEquals(statsOnFreshThread(new EngineContext()).split(" ")[0], statsOnFreshThread(off).split(" ")[0]);
    }

    @Test
    void oldStaticsAreGoneFromRenderPipeline() throws IOException {
        String s = Files.readString(Path.of("core/src/main/java/com/nishiyu/enderframelib/common/RenderPipeline.java"));
        for (String bad : List.of("static volatile boolean incrementalLayout", "static volatile boolean pruneCleanSubtrees",
                "static volatile boolean trackAllocations", "static volatile boolean localRelayout",
                "static volatile boolean inPlaceRestyleRelayout", "static volatile String lastBuildStats",
                "static volatile boolean lastRelayoutWasLocal", "static long allocResolve", "private static int statBoxes")) {
            assertFalse(s.contains(bad), "RenderPipeline に " + bad + " が復活している（EngineContext / PipelineStats へ）");
        }
    }
}
