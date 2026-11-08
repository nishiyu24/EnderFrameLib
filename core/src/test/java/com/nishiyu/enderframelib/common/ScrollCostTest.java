package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 実際のスクロール更新（{@code view.scroll} → {@code view.frame}）の 1 回あたりの CPU 時間とヒープ確保を測る。
 * スクロールが「位置に焼き込まない更新」で済んでいるか（{@link UiView#STAT_SCROLL_TRANSLATED}）の確認も兼ねる。
 */
class ScrollCostTest {
    private static final int W = 320, H = 240;

    private static String html() {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 200; i++) rows.append("<div class=\"row\" id=\"r").append(i).append("\">row ").append(i).append(" some text</div>");
        return "<style>body{margin:0;font-size:13px}.row{height:26px;margin:2px;padding:2px 6px;background:#eef;border:1px solid #99a}"
                + "#outer{position:absolute;left:0;top:0;width:300px;height:230px;overflow-y:auto;border:2px solid #333;border-radius:8px}</style>"
                + "<div id=\"outer\">" + rows + "</div>";
    }

    @Test
    void measureScrollUpdate() {
        StandaloneView view = new StandaloneView(html(), W, H, FontResolver.FALLBACK);
        try (var backend = new MutationScenario.NoDraw()) {
            long now = 1000;
            view.frame(now, backend, true);
            String sid = backend.sessionId();
            view.mouseMove(100, 100, sid);
            int warm = 400, n = 2000;
            var mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            long tid = Thread.currentThread().getId();
            long translatedBefore = 0;
            for (int i = 0; i < warm + n; i++) {
                if (i == warm) translatedBefore = UiView.STAT_SCROLL_TRANSLATED.sum();
                if (i == warm) { System.gc(); }
                long t0 = i == warm ? System.nanoTime() : 0;
                if (i == warm) { cpuStart = mx.getThreadCpuTime(tid); allocStart = mx.getThreadAllocatedBytes(tid); wall = t0; }
                view.scroll(0, (i & 1) == 0 ? -1 : 1, sid);
                now += 16;
                view.frame(now, backend, true);
            }
            double cpuMs = (mx.getThreadCpuTime(tid) - cpuStart) / 1e6 / n;
            double kb = (mx.getThreadAllocatedBytes(tid) - allocStart) / 1024.0 / n;
            long translated = UiView.STAT_SCROLL_TRANSLATED.sum() - translatedBefore;
            assertEquals(n, translated, "スクロールが節の移動で済んでいない（作り直しに落ちている）");
            assertTrue(kb < 400, "スクロール 1 回のヒープ確保が大きい: " + kb + " KB（表示リストの複製・索引の作り直しの再発）");
            System.out.println("SCROLL-COST per scroll: cpu " + String.format("%.3f", cpuMs) + " ms, alloc " + String.format("%.1f", kb)
                    + " KB, translated " + translated + "/" + n + ", fallback " + UiView.STAT_SCROLL_FALLBACK.sum());
        } finally {
            view.close();
        }
    }

    /** 診断: スクロールの更新中に別スレッドでスタックを標本化し、どこに時間がかかるかを出す。 */
    @Test
    @EnabledIfEnvironmentVariable(named = "EFL_PROFILE", matches = ".+")
    void sampleWhereScrollTimeGoes() throws Exception {
        StandaloneView view = new StandaloneView(html(), W, H, FontResolver.FALLBACK);
        try (var backend = new MutationScenario.NoDraw()) {
            long[] nowBox = {1000};
            view.frame(nowBox[0], backend, true);
            String sid = backend.sessionId();
            view.mouseMove(100, 100, sid);
            for (int i = 0; i < 600; i++) { view.scroll(0, (i & 1) == 0 ? -1 : 1, sid); nowBox[0] += 16; view.frame(nowBox[0], backend, true); }
            Thread worker = Thread.currentThread();
            java.util.Map<String, Integer> self = new java.util.HashMap<>(), incl = new java.util.HashMap<>();
            int[] samples = {0};
            boolean[] stop = {false};
            Thread sampler = new Thread(() -> {
                while (!stop[0]) {
                    StackTraceElement[] st = worker.getStackTrace();
                    if (st.length > 0) {
                        samples[0]++;
                        String first = null;
                        java.util.Set<String> seen = new java.util.HashSet<>();
                        for (StackTraceElement e : st) {
                            if (!e.getClassName().startsWith("com.nishiyu")) continue;
                            String k = e.getClassName().substring(e.getClassName().lastIndexOf('.') + 1) + "." + e.getMethodName();
                            if (first == null) first = k;
                            if (seen.add(k)) incl.merge(k, 1, Integer::sum);
                        }
                        if (first != null) self.merge(first, 1, Integer::sum);
                    }
                    try { Thread.sleep(0, 200_000); } catch (InterruptedException ex) { return; }
                }
            });
            sampler.setDaemon(true);
            sampler.start();
            for (int i = 0; i < 6000; i++) { view.scroll(0, (i & 1) == 0 ? -1 : 1, sid); nowBox[0] += 16; view.frame(nowBox[0], backend, true); }
            stop[0] = true;
            sampler.join();
            StringBuilder sb = new StringBuilder("SCROLL-PROFILE samples=" + samples[0] + "\n  self:");
            self.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(12)
                    .forEach(e -> sb.append(String.format("\n    %5.1f%% %s", 100.0 * e.getValue() / samples[0], e.getKey())));
            sb.append("\n  inclusive:");
            incl.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(22)
                    .forEach(e -> sb.append(String.format("\n    %5.1f%% %s", 100.0 * e.getValue() / samples[0], e.getKey())));
            System.out.println(sb);
        } finally {
            view.close();
        }
    }

    /** 診断: 同じ標本化を GL バックエンドで。 */
    @Test
    @EnabledIfEnvironmentVariable(named = "EFL_PROFILE", matches = ".+")
    void sampleWhereScrollTimeGoesWithTheGlBackend() throws Exception {
        try (var probe = new com.nishiyu.enderframelib.core.paint.gl.LwjglGlRenderBackend(8, 8)) {
        } catch (Throwable t) {
            org.junit.jupiter.api.Assumptions.abort("OpenGL を使えない: " + t);
        }
        StandaloneView view = new StandaloneView(html(), W, H, FontResolver.FALLBACK);
        try (var backend = new com.nishiyu.enderframelib.core.paint.gl.LwjglGlRenderBackend(W, H)) {
            long[] nowBox = {1000};
            view.frame(nowBox[0], backend, true);
            String sid = backend.sessionId();
            view.mouseMove(100, 100, sid);
            for (int i = 0; i < 600; i++) { view.scroll(0, (i & 1) == 0 ? -1 : 1, sid); nowBox[0] += 16; view.frame(nowBox[0], backend, true); }
            Thread worker = Thread.currentThread();
            java.util.Map<String, Integer> self = new java.util.HashMap<>(), incl = new java.util.HashMap<>();
            int[] samples = {0};
            boolean[] stop = {false};
            Thread sampler = new Thread(() -> {
                while (!stop[0]) {
                    StackTraceElement[] st = worker.getStackTrace();
                    if (st.length > 0) {
                        samples[0]++;
                        String first = null;
                        java.util.Set<String> seen = new java.util.HashSet<>();
                        for (StackTraceElement e : st) {
                            if (!e.getClassName().startsWith("com.nishiyu")) continue;
                            String k = e.getClassName().substring(e.getClassName().lastIndexOf('.') + 1) + "." + e.getMethodName();
                            if (first == null) first = k;
                            if (seen.add(k)) incl.merge(k, 1, Integer::sum);
                        }
                        if (first != null) self.merge(first, 1, Integer::sum);
                    }
                    try { Thread.sleep(0, 200_000); } catch (InterruptedException ex) { return; }
                }
            });
            sampler.setDaemon(true);
            sampler.start();
            for (int i = 0; i < 6000; i++) { view.scroll(0, (i & 1) == 0 ? -1 : 1, sid); nowBox[0] += 16; view.frame(nowBox[0], backend, true); }
            stop[0] = true;
            sampler.join();
            StringBuilder sb = new StringBuilder("SCROLL-PROFILE-GL samples=" + samples[0] + "\n  self:");
            self.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(12)
                    .forEach(e -> sb.append(String.format("\n    %5.1f%% %s", 100.0 * e.getValue() / samples[0], e.getKey())));
            sb.append("\n  inclusive:");
            incl.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(22)
                    .forEach(e -> sb.append(String.format("\n    %5.1f%% %s", 100.0 * e.getValue() / samples[0], e.getKey())));
            System.out.println(sb);
        } finally {
            view.close();
        }
    }

    /** 診断: 本物の GL バックエンド（新経路 / 従来経路）でのスクロール 1 回の CPU 時間（フレームの描画まで含む）。 */
    @Test
    void measureScrollWithTheGlBackend() {
        try (var probe = new com.nishiyu.enderframelib.core.paint.gl.LwjglGlRenderBackend(8, 8)) {
            // GL を使える環境だけ
        } catch (Throwable t) {
            org.junit.jupiter.api.Assumptions.abort("OpenGL を使えない: " + t);
        }
        StringBuilder out = new StringBuilder("SCROLL-GL");
        double[] cpuMs = new double[2];
        for (boolean trees : new boolean[]{false, true}) {
            boolean saved = com.nishiyu.enderframelib.core.paint.gl.LwjglGlRenderBackend.rectTrees;
            com.nishiyu.enderframelib.core.paint.gl.LwjglGlRenderBackend.rectTrees = trees;
            StandaloneView view = new StandaloneView(html(), W, H, FontResolver.FALLBACK);
            try (var backend = new com.nishiyu.enderframelib.core.paint.gl.LwjglGlRenderBackend(W, H)) {
                long now = 1000;
                view.frame(now, backend, true);
                String sid = backend.sessionId();
                view.mouseMove(100, 100, sid);
                var mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
                long tid = Thread.currentThread().getId();
                int warm = 300, n = 600;
                long cpu0 = 0, alloc0 = 0;
                for (int i = 0; i < warm + n; i++) {
                    if (i == warm) { cpu0 = mx.getThreadCpuTime(tid); alloc0 = mx.getThreadAllocatedBytes(tid); }
                    view.scroll(0, (i & 1) == 0 ? -1 : 1, sid);
                    now += 16;
                    view.frame(now, backend, true);
                }
                cpuMs[trees ? 1 : 0] = (mx.getThreadCpuTime(tid) - cpu0) / 1e6 / n;
                out.append(String.format(" | trees=%b: cpu %.3f ms, alloc %.1f KB per scroll+frame", trees,
                        cpuMs[trees ? 1 : 0], (mx.getThreadAllocatedBytes(tid) - alloc0) / 1024.0 / n));
            } finally {
                view.close();
                com.nishiyu.enderframelib.core.paint.gl.LwjglGlRenderBackend.rectTrees = saved;
            }
        }
        System.out.println(out);
        // 永続マップの GPU メモリ（ライトコンバイン）を読むと、フレームあたり数 ms 悪化する（変更検出は CPU 側の影で行う）。再発の検出
        assertTrue(cpuMs[1] < cpuMs[0] * 3 + 2.0, "新経路の CPU が従来経路に比べて大きく悪化している: 従来 " + cpuMs[0] + " ms / 新 " + cpuMs[1] + " ms");
    }

    /** 診断: 画面の中に収まるスクロールコンテナで、スクロール層の保持あり / なしの CPU 時間とヒープ（フレームの描画まで）。 */
    @Test
    void measureScrollWithRetainedLayers() {
        try (var probe = new com.nishiyu.enderframelib.core.paint.gl.LwjglGlRenderBackend(8, 8)) {
        } catch (Throwable t) {
            org.junit.jupiter.api.Assumptions.abort("OpenGL を使えない: " + t);
        }
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 200; i++) rows.append("<div class=\"row\" id=\"r").append(i).append("\">row ").append(i).append(" some text</div>");
        String html = "<style>body{margin:0;font-size:13px;background:#123}.row{height:26px;margin:2px;padding:2px 6px;background:#eef;border:1px solid #99a}"
                + "#outer{position:absolute;left:100px;top:150px;width:300px;height:230px;overflow-y:auto;border:2px solid #333;border-radius:8px}</style>"
                + "<div id=\"outer\">" + rows + "</div>";
        StringBuilder out = new StringBuilder("SCROLL-RETAIN");
        double[] cpuMs = new double[2];
        for (int mode = 0; mode < 2; mode++) {
            boolean retain = mode == 1;
            com.nishiyu.enderframelib.core.paint.PaintSceneDriver.retainScrollLayers = retain;
            com.nishiyu.enderframelib.core.paint.gl.LwjglGlRenderBackend.retainedScroll = true;
            StandaloneView view = new StandaloneView(html, 520, 520, FontResolver.FALLBACK);
            try (var backend = new com.nishiyu.enderframelib.core.paint.gl.LwjglGlRenderBackend(520, 520)) {
                long now = 1000;
                view.frame(now, backend, true);
                String sid = backend.sessionId();
                view.mouseMove(200, 250, sid);
                var mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
                long tid = Thread.currentThread().getId();
                int warm = 300, n = 600;
                long cpu0 = 0, alloc0 = 0;
                for (int i = 0; i < warm + n; i++) {
                    if (i == warm) { cpu0 = mx.getThreadCpuTime(tid); alloc0 = mx.getThreadAllocatedBytes(tid); }
                    view.scroll(0, (i & 1) == 0 ? -0.1 : 0.1, sid); // 3 px ずつ往復
                    now += 16;
                    view.frame(now, backend, true);
                }
                cpuMs[mode] = (mx.getThreadCpuTime(tid) - cpu0) / 1e6 / n;
                if (retain) {
                    assertTrue(backend.scrollLayersSkipped() > backend.scrollLayersRecorded() * 10, "保持した層が使われていない（毎フレーム記録し直している）: skipped "
                            + backend.scrollLayersSkipped() + " recorded " + backend.scrollLayersRecorded());
                }
                out.append(String.format(" | retain=%b: cpu %.3f ms, alloc %.1f KB per scroll+frame (skipped %d, recorded %d)", retain, cpuMs[mode],
                        (mx.getThreadAllocatedBytes(tid) - alloc0) / 1024.0 / n, backend.scrollLayersSkipped(), backend.scrollLayersRecorded()));
            } finally {
                view.close();
                com.nishiyu.enderframelib.core.paint.PaintSceneDriver.retainScrollLayers = true;
            }
        }
        System.out.println(out);
        assertTrue(cpuMs[1] < cpuMs[0], "保持した層のほうが遅い: " + cpuMs[1] + " ms / 保持なし " + cpuMs[0] + " ms");
    }

    /** 診断: 保持した層を使うスクロールで、どこに時間がかかるかをスタックの標本化で出す。 */
    @Test
    @EnabledIfEnvironmentVariable(named = "EFL_PROFILE", matches = ".+")
    void sampleWhereRetainedScrollTimeGoes() throws Exception {
        try (var probe = new com.nishiyu.enderframelib.core.paint.gl.LwjglGlRenderBackend(8, 8)) {
        } catch (Throwable t) {
            org.junit.jupiter.api.Assumptions.abort("OpenGL を使えない: " + t);
        }
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 200; i++) rows.append("<div class=\"row\" id=\"r").append(i).append("\">row ").append(i).append(" some text</div>");
        String html = "<style>body{margin:0;font-size:13px;background:#123}.row{height:26px;margin:2px;padding:2px 6px;background:#eef;border:1px solid #99a}"
                + "#outer{position:absolute;left:100px;top:150px;width:300px;height:230px;overflow-y:auto;border:2px solid #333;border-radius:8px}</style>"
                + "<div id=\"outer\">" + rows + "</div>";
        StandaloneView view = new StandaloneView(html, 520, 520, FontResolver.FALLBACK);
        try (var backend = new com.nishiyu.enderframelib.core.paint.gl.LwjglGlRenderBackend(520, 520)) {
            long[] nowBox = {1000};
            view.frame(nowBox[0], backend, true);
            String sid = backend.sessionId();
            view.mouseMove(200, 250, sid);
            for (int i = 0; i < 600; i++) { view.scroll(0, (i & 1) == 0 ? -0.1 : 0.1, sid); nowBox[0] += 16; view.frame(nowBox[0], backend, true); }
            Thread worker = Thread.currentThread();
            java.util.Map<String, Integer> self = new java.util.HashMap<>(), incl = new java.util.HashMap<>();
            int[] samples = {0};
            boolean[] stop = {false};
            Thread sampler = new Thread(() -> {
                while (!stop[0]) {
                    StackTraceElement[] st = worker.getStackTrace();
                    if (st.length > 0) {
                        samples[0]++;
                        String first = null;
                        java.util.Set<String> seen = new java.util.HashSet<>();
                        for (StackTraceElement e : st) {
                            if (!e.getClassName().startsWith("com.nishiyu")) continue;
                            String k = e.getClassName().substring(e.getClassName().lastIndexOf('.') + 1) + "." + e.getMethodName();
                            if (first == null) first = k;
                            if (seen.add(k)) incl.merge(k, 1, Integer::sum);
                        }
                        if (first != null) self.merge(first, 1, Integer::sum);
                    }
                    try { Thread.sleep(0, 200_000); } catch (InterruptedException ex) { return; }
                }
            });
            sampler.setDaemon(true);
            sampler.start();
            for (int i = 0; i < 6000; i++) { view.scroll(0, (i & 1) == 0 ? -0.1 : 0.1, sid); nowBox[0] += 16; view.frame(nowBox[0], backend, true); }
            stop[0] = true;
            sampler.join();
            StringBuilder sb = new StringBuilder("SCROLL-PROFILE-RETAINED samples=" + samples[0] + "\n  self:");
            self.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(12)
                    .forEach(e -> sb.append(String.format("\n    %5.1f%% %s", 100.0 * e.getValue() / samples[0], e.getKey())));
            sb.append("\n  inclusive:");
            incl.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(24)
                    .forEach(e -> sb.append(String.format("\n    %5.1f%% %s", 100.0 * e.getValue() / samples[0], e.getKey())));
            System.out.println(sb);
        } finally {
            view.close();
        }
    }

    private static long cpuStart, allocStart, wall;
}
