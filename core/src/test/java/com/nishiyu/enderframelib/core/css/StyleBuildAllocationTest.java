package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;

/** 大きな文書をまるごと組み立てる（スタイル解決を含む）ときの割り当て量と時間。表示するだけ。 */
class StyleBuildAllocationTest {

    private static long allocated() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean())
                .getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    @Test
    void reportBuildCost() {
        StringBuilder html = new StringBuilder("<style>body{margin:0;font-size:12px}.row{height:16px;color:#333}.row b{color:red}</style>");
        for (int i = 0; i < 1100; i++) html.append("<div class=row><span>item ").append(i).append("</span> <b>x</b></div>");
        String doc = html.toString();
        for (int i = 0; i < 8; i++) new StandaloneView(doc, 800, 600, FontResolver.FALLBACK);
        int n = 10;
        long a0 = allocated(), t0 = System.nanoTime();
        for (int i = 0; i < n; i++) new StandaloneView(doc, 800, 600, FontResolver.FALLBACK);
        System.out.println("[style-build] 1500 rows (~4500 nodes): " + (allocated() - a0) / n / 1024 + " KB, "
                + (System.nanoTime() - t0) / n / 1_000_000 + " ms per build");
        for (int i = 0; i < 20; i++) com.nishiyu.enderframelib.core.dom.parser.HtmlParser.HtmlTokenizer.tokenize(doc);
        long b0 = allocated();
        for (int i = 0; i < n; i++) com.nishiyu.enderframelib.core.dom.parser.HtmlParser.HtmlTokenizer.tokenize(doc);
        System.out.println("[style-build] tokenize only: " + (allocated() - b0) / n / 1024 + " KB per run (doc " + doc.length() / 1024 + " KB)");
        var mx = ManagementFactory.getThreadMXBean();
        for (int rep = 0; rep < 3; rep++) {
            long c0 = mx.getCurrentThreadCpuTime();
            for (int i = 0; i < 60; i++) new StandaloneView(doc, 800, 600, FontResolver.FALLBACK);
            System.out.println("[style-build] cpu ms per build (mean of 60): " + (mx.getCurrentThreadCpuTime() - c0) / 60 / 1000 / 1000.0);
        }
        com.nishiyu.enderframelib.core.EngineContext.global().trackAllocations = true;
        try {
            for (int i = 0; i < 3; i++) {
                StandaloneView v = new StandaloneView(doc, 800, 600, FontResolver.FALLBACK);
                if (i == 2) System.out.println("[style-build] phases: " + com.nishiyu.enderframelib.common.RenderPipeline.lastBuildStats() + " | " + v.ui().lastBreakdown());
            }
        } finally {
            com.nishiyu.enderframelib.core.EngineContext.global().trackAllocations = false;
        }
        if (System.getenv("ALLOCPROFILE") != null) profile(doc);
        if (System.getenv("CPUPROFILE") != null) cpuProfile(doc);
    }

    /** 実行サンプル（CPU）の自己時間と包含時間。JFR の割り当て標本と違い、こちらは件数がそのまま時間に比例する。 */
    private static void cpuProfile(String doc) {
        try (jdk.jfr.Recording r = new jdk.jfr.Recording()) {
            r.enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(1)).withStackTrace();
            r.start();
            long end = System.nanoTime() + 25_000_000_000L;
            int builds = 0;
            while (System.nanoTime() < end) { new StandaloneView(doc, 800, 600, FontResolver.FALLBACK); builds++; }
            r.stop();
            java.nio.file.Path f = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "cpu.jfr");
            r.dump(f);
            java.util.Map<String, Integer> hashers = new java.util.HashMap<>(), callers = new java.util.HashMap<>(), self = new java.util.HashMap<>(), incl = new java.util.HashMap<>();
            int total = 0;
            for (jdk.jfr.consumer.RecordedEvent e : jdk.jfr.consumer.RecordingFile.readAllEvents(f)) {
                if (e.getStackTrace() == null) continue;
                total++;
                var frames = e.getStackTrace().getFrames();
                var top = frames.get(0).getMethod();
                self.merge(top.getType().getName().replaceAll(".*[.]", "") + "." + top.getName(), 1, Integer::sum);
                if (frames.get(0).getMethod().getName().equals("hashCode") && frames.size() > 2) {
                    var f1 = frames.get(1); var f2 = frames.get(2);
                    hashers.merge(f1.getMethod().getType().getName().replaceAll(".*[.]", "") + "." + f1.getMethod().getName() + ":" + f1.getLineNumber()
                            + " < " + f2.getMethod().getType().getName().replaceAll(".*[.]", "") + "." + f2.getMethod().getName() + ":" + f2.getLineNumber(), 1, Integer::sum);
                }
                boolean viaId = false;
                for (var fr : frames) if (fr.getMethod().getName().equals("hashCode") || fr.getMethod().getType().getName().endsWith("CssPropertyId")) { viaId = true; break; }
                if (viaId) {
                    for (var fr : frames) {
                        String t = fr.getMethod().getType().getName();
                        if (t.startsWith("com.nishiyu") && !t.contains("ComputedStyle") && !t.contains("CssPropertyId")) {
                            callers.merge(t.replaceAll(".*[.]", "") + "." + fr.getMethod().getName() + ":" + fr.getLineNumber(), 1, Integer::sum);
                            break;
                        }
                    }
                }
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (var fr : frames) {
                    String t = fr.getMethod().getType().getName();
                    if (!t.startsWith("com.nishiyu")) continue;
                    String k = t.replaceAll(".*[.]", "") + "." + fr.getMethod().getName();
                    if (seen.add(k)) incl.merge(k, 1, Integer::sum);
                }
            }
            final int tot = total;
            System.out.println("[cpu-profile] builds=" + builds + " samples=" + tot);
            System.out.println("[cpu-profile] INCLUSIVE");
            incl.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(45)
                    .forEach(en -> System.out.println("  " + en.getValue() * 1000 / tot / 10.0 + "% " + en.getKey()));
            System.out.println("[cpu-profile] CALLERS of string-keyed style lookup");
            callers.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(30)
                    .forEach(en -> System.out.println("  " + en.getValue() * 1000 / tot / 10.0 + "% " + en.getKey()));
            System.out.println("[cpu-profile] HASHCODE parents");
            hashers.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(12)
                    .forEach(en -> System.out.println("  " + en.getValue() * 1000 / tot / 10.0 + "% " + en.getKey()));
            System.out.println("[cpu-profile] SELF");
            self.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(25)
                    .forEach(en -> System.out.println("  " + en.getValue() * 1000 / tot / 10.0 + "% " + en.getKey()));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static void profile(String doc) {
        try (jdk.jfr.Recording r = new jdk.jfr.Recording()) {
            r.enable("jdk.ObjectAllocationSample").withStackTrace().with("throttle", "10000/s");
            r.start();
            for (int i = 0; i < 20; i++) new StandaloneView(doc, 800, 600, FontResolver.FALLBACK);
            r.stop();
            java.nio.file.Path f = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "alloc.jfr");
            r.dump(f);
            java.util.Map<String, Long> byFrame = new java.util.HashMap<>(), byType = new java.util.HashMap<>();
            long total = 0;
            for (jdk.jfr.consumer.RecordedEvent e : jdk.jfr.consumer.RecordingFile.readAllEvents(f)) {
                long w = e.getLong("weight");
                total += w;
                byType.merge(e.getClass("objectClass").getName(), w, Long::sum);
                String key = "?";
                if (e.getStackTrace() == null) continue;
                for (jdk.jfr.consumer.RecordedFrame fr : e.getStackTrace().getFrames()) {
                    String t = fr.getMethod().getType().getName();
                    if (t.startsWith("com.nishiyu")) { key = t.substring(t.lastIndexOf('.') + 1) + "." + fr.getMethod().getName(); break; }
                }
                if (key.endsWith("tokenize")) { var fs = e.getStackTrace().getFrames(); key += " <- "; for (int q = 0; q < 4; q++) { var fr = fs.get(q); key += fr.getMethod().getType().getName().replaceAll(".*[.]", "") + "." + fr.getMethod().getName() + ":" + fr.getLineNumber() + " < "; } }
                byFrame.merge(key, w, Long::sum);
            }
            final long tot = total;
            System.out.println("[alloc-by-frame] total=" + tot / 1024 + "KB");
            byFrame.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(25)
                    .forEach(en -> System.out.println("  " + en.getValue() * 100 / tot + "% " + en.getKey()));
            System.out.println("[alloc-by-type]");
            byType.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(12)
                    .forEach(en -> System.out.println("  " + en.getValue() * 100 / tot + "% " + en.getKey()));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
