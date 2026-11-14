package com.nishiyu.enderframelib.bench;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.paint.RenderBackend;
import com.nishiyu.enderframelib.core.text.model.FontResolver;

/** 文書を開いて 1 フレーム描いたあとの、保持しているヒープの大きさ（GC 後の使用量の差）を測る。 */
public final class MemFootprintMain {
    private MemFootprintMain() { }

    private static final class NullBackend implements RenderBackend {
        @Override public void render(java.util.List<com.nishiyu.enderframelib.core.paint.model.DisplayItem> items, long nowMs) { }
        @Override public com.nishiyu.enderframelib.core.raster.Pixmap pixmap() { throw new UnsupportedOperationException(); }
        @Override public float devicePixelRatio() { return 1f; }
    }

    private static long used() {
        for (int i = 0; i < 4; i++) { System.gc(); try { Thread.sleep(80); } catch (InterruptedException e) { } }
        Runtime r = Runtime.getRuntime();
        return r.totalMemory() - r.freeMemory();
    }

    /** このプロセスの Private バイト（ヒープ + オフヒープ + ネイティブ）。PowerShell で読む。 */
    private static double privateMb() {
        try {
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-Command", "(Get-Process -Id " + ProcessHandle.current().pid() + ").PrivateMemorySize64").start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return Long.parseLong(out) / 1048576.0;
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    /** 保持中のオブジェクトの上位（クラス別のバイト数）。-Dmem.histo=1 で、文書を閉じる前に出す。 */
    private static void histogram(String doc) {
        try {
            Object out = java.lang.management.ManagementFactory.getPlatformMBeanServer().invoke(
                    new javax.management.ObjectName("com.sun.management:type=DiagnosticCommand"), "gcClassHistogram",
                    new Object[]{null}, new String[]{String[].class.getName()});
            String[] lines = out.toString().split("\\R");
            System.out.println("HISTO " + doc);
            for (int i = 0; i < Math.min(lines.length, Integer.getInteger("mem.histo.n", 22)); i++) System.out.println("HISTO " + lines[i]);
        } catch (Exception e) {
            System.out.println("HISTO failed " + e);
        }
    }

    /** 生きているオブジェクトだけのヒープダンプ（-Dmem.dump=ファイル）。保持元の内訳を別ツールで調べる用。 */
    private static void dumpHeap(String file) {
        try {
            java.lang.management.ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class).dumpHeap(file, true);
        } catch (Exception e) {
            System.out.println("DUMP failed " + e);
        }
    }

    /** クラスの private static なキャッシュ（Map）を空にして、そのぶんの保持量を見る。 */
    private static void clearCache(String cls, String field) {
        try {
            java.lang.reflect.Field f = Class.forName(cls).getDeclaredField(field);
            f.setAccessible(true);
            ((java.util.Map<?, ?>) f.get(null)).clear();
        } catch (Exception e) {
            System.out.println("MEM clear failed " + e);
        }
    }

    public static void main(String[] args) {
        // 文書に依らない土台（フォント・キャッシュ）を先に温める
        StandaloneView warm = new StandaloneView(Workloads.html("large-list-50"), 800, 600, FontResolver.FALLBACK);
        warm.frame(1000, new NullBackend(), true);
        System.out.printf("MEM baseline(after warm-up view, GC) used=%.1f MB%n", used() / 1048576.0);
        for (String doc : args) {
            long before = used();
            double privBefore = privateMb();
            String html = Workloads.html(doc);
            StandaloneView v = new StandaloneView(html, Workloads.VIEWPORT_W, Workloads.VIEWPORT_H, FontResolver.FALLBACK);
            NullBackend b = new NullBackend();
            for (int i = 0; i < 5; i++) v.frame(1000 + i * 16, b, true);
            long after = used();
            double privAfter = privateMb();
            System.out.printf("MEM %-18s html=%6.0f KB  heapRetained=%7.1f MB  processPrivateDelta=%7.1f MB%n", doc, html.length() / 1024.0, (after - before) / 1048576.0, privAfter - privBefore);
            System.out.println("MEM share hits=" + com.nishiyu.enderframelib.core.css.ComputedStyle.shareHits() + " misses=" + com.nishiyu.enderframelib.core.css.ComputedStyle.shareMisses());
            if (System.getProperty("mem.clear") != null) {
                clearCache("com.nishiyu.enderframelib.core.css.CssSyntax", "VALUE_CACHE");
                System.out.printf("MEM %-18s after clearing CssSyntax.VALUE_CACHE: heapRetained=%7.1f MB%n", doc, (used() - before) / 1048576.0);
            }
            if (System.getProperty("mem.histo") != null) histogram(doc);
            if (System.getProperty("mem.dump") != null) dumpHeap(System.getProperty("mem.dump"));
            v.close();
        }
    }
}
