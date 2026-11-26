package com.nishiyu.enderframelib.core;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.paint.RenderBackend;

import java.io.IOException;

/** {@link MemoryAndLatencyGate} の文書とシナリオを他のパッケージの診断（{@code V3HeapBreakdownMain}）から使う口。 */
public final class MemoryAndLatencyGateAccess {
    private MemoryAndLatencyGateAccess() { }

    public static String loadHtml(String doc) throws IOException {
        return MemoryAndLatencyGate.loadHtml(doc);
    }

    public static String largeList(int count) {
        return MemoryAndLatencyGate.largeList(count);
    }

    /** ゲートの子 JVM と同じ手順で {@code scenario} を {@code count} 回進める（open 以外）。 */
    public static void steps(StandaloneView view, String doc, String scenario, int count, long[] now, RenderBackend backend) {
        MemoryAndLatencyGate.ScenarioTarget t = MemoryAndLatencyGate.ScenarioTarget.find(view, doc);
        StandaloneView[] holder = {view};
        for (int i = 0; i < count; i++) MemoryAndLatencyGate.step(scenario, holder, backend, t, now, i, null, false);
    }
}
