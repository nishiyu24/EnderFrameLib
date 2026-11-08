package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 毎回新しい InteractionState で全要素を再スタイルする（PipelineBench.restyle と同じ）。showcase.html を使う。 */
class FullRestyleCostTest {
    private static final int W = 800, H = 600;

    @Test
    void measureFullRestyle() throws Exception {
        Path html = Path.of("../src/main/resources/data/enderframelib/enderframe_ui/showcase.html");
        if (!Files.exists(html)) html = Path.of("src/main/resources/data/enderframelib/enderframe_ui/showcase.html");
        String src = Files.readString(html).replace("\r\n", "\n");
        boolean wasProfiling = com.nishiyu.enderframelib.core.diag.StageProfiler.enabled();
        com.nishiyu.enderframelib.core.diag.StageProfiler.enable(false);
        try {
            UiDocumentCompiler parser = new UiDocumentCompiler();
            Document doc = parser.parseDocument(src, "", W, H);
            LayoutBox root = parser.layoutDocument(doc, W, H, Map.of(), new ScrollState(), FontResolver.FALLBACK, new InteractionState());
            var mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            long tid = Thread.currentThread().getId();
            int warm = 300, n = 600;
            long cpu0 = 0, a0 = 0;
            for (int i = 0; i < warm + n; i++) {
                if (i == warm) { cpu0 = mx.getThreadCpuTime(tid); a0 = mx.getThreadAllocatedBytes(tid); }
                parser.restyleLayoutTree(root, doc, W, H, Map.of(), new InteractionState());
            }
            System.out.println("RESTYLE-COST per full restyle: cpu " + String.format("%.3f", (mx.getThreadCpuTime(tid) - cpu0) / 1e6 / n)
                    + " ms, alloc " + (mx.getThreadAllocatedBytes(tid) - a0) / n / 1024 + " KB");
        } finally {
            com.nishiyu.enderframelib.core.diag.StageProfiler.enable(wasProfiling);
        }
    }
}
