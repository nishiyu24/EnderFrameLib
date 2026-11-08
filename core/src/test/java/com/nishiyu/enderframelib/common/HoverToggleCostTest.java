package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.lang.management.ManagementFactory;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** :hover を往復させたときの再スタイル 1 回あたりの時間とヒープ確保（状態表を引く経路）。 */
class HoverToggleCostTest {
    private static final int W = 800, H = 600;

    @Test
    void measureHoverToggle() {
        // 他のテストが段別プロファイラを有効のまま残すと、測定自体が確保する。この測定の間だけ切る
        boolean wasProfiling = com.nishiyu.enderframelib.core.diag.StageProfiler.enabled();
        com.nishiyu.enderframelib.core.diag.StageProfiler.enable(false);
        try {
            measure();
        } finally {
            com.nishiyu.enderframelib.core.diag.StageProfiler.enable(wasProfiling);
        }
    }

    private void measure() {
        StringBuilder sb = new StringBuilder("<style>body{margin:0}.b{padding:4px;background:#ccc}.b:hover{background:#eee}</style><div>");
        for (int i = 0; i < 40; i++) sb.append("<div class=\"card\"><span>label ").append(i).append("</span><button class=\"b\" id=\"b").append(i).append("\">go</button></div>");
        sb.append("</div>");
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument(sb.toString(), "", W, H);
        InteractionState state = new InteractionState();
        LayoutBox root = parser.layoutDocument(doc, W, H, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);
        HtmlNode target = doc.root.getElementById("b3");
        assertNotNull(target);
        var mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().getId();
        int warm = 6000, n = 5000;
        boolean on = false;
        double ms = Double.MAX_VALUE, bytes = Double.MAX_VALUE;
        // 全体実行では JIT の状態が揺れるので、3 回測って最小を採る
        for (int round = 0; round < 3; round++) {
            long cpu0 = 0, a0 = 0;
            for (int i = 0; i < warm + n; i++) {
                if (i == warm) { cpu0 = mx.getThreadCpuTime(tid); a0 = mx.getThreadAllocatedBytes(tid); }
                on = !on;
                state.setHoveredNode(on ? target : null);
                parser.restyleLayoutTree(root, doc, W, H, Map.of(), state);
            }
            ms = Math.min(ms, (mx.getThreadCpuTime(tid) - cpu0) / 1e6 / n);
            bytes = Math.min(bytes, (mx.getThreadAllocatedBytes(tid) - a0) / (double) n);
        }
        assertTrue(bytes < 2000, "hover 往復 1 回のヒープ確保が増えた（着手前は約 5.9 KB、現在は約 0.9 KB）: " + bytes + " B");
        long b0 = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 2000; i++) { on = !on; state.setHoveredNode(on ? target : null); parser.restyleLayoutTree(root, doc, W, H, Map.of(), state); }
        long b1 = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 2000; i++) { on = !on; state.setHoveredNode(on ? target : null); }
        long b2 = mx.getThreadAllocatedBytes(tid);
        System.out.println("HOVER-SPLIT total " + (b1 - b0) / 2000 + " B, setHoveredNode alone " + (b2 - b1) / 2000 + " B");
        System.out.println("HOVER-COST per toggle: cpu " + String.format("%.4f", ms) + " ms, alloc " + String.format("%.0f", bytes) + " B");
    }
}
