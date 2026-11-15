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

    /** クラス名 → {個数, バイト数}（gcClassHistogram。GC 後の生きているオブジェクト）。 */
    private static java.util.Map<String, long[]> histogramMap() {
        java.util.Map<String, long[]> m = new java.util.HashMap<>();
        try {
            Object out = java.lang.management.ManagementFactory.getPlatformMBeanServer().invoke(
                    new javax.management.ObjectName("com.sun.management:type=DiagnosticCommand"), "gcClassHistogram",
                    new Object[]{null}, new String[]{String[].class.getName()});
            for (String line : out.toString().split("\\R")) {
                String[] t = line.trim().split("\\s+");
                if (t.length >= 4 && t[0].endsWith(":")) m.put(t[3], new long[]{Long.parseLong(t[1]), Long.parseLong(t[2])});
            }
        } catch (Exception e) {
            System.out.println("HISTO failed " + e);
        }
        return m;
    }

    /** 文書を開く前後のヒストグラムの差のうち、増えたバイト数の多いクラス（保持の内訳。B1④: 土台に載った JAR の中央ディレクトリは差に出ない）。 */
    private static void printGrowth(String doc, java.util.Map<String, long[]> before, java.util.Map<String, long[]> after) {
        java.util.List<java.util.Map.Entry<String, Long>> rows = new java.util.ArrayList<>();
        long total = 0;
        for (var e : after.entrySet()) {
            long[] b = before.get(e.getKey());
            long d = e.getValue()[1] - (b == null ? 0 : b[1]);
            total += d;
            if (d > 0) rows.add(java.util.Map.entry(e.getKey(), d));
        }
        rows.sort((x, y) -> Long.compare(y.getValue(), x.getValue()));
        System.out.printf("MEM %-18s histogramGrowth total=%.1f MB; top:", doc, total / 1048576.0);
        for (int i = 0; i < Math.min(5, rows.size()); i++) System.out.printf(" %s=%.2fMB", rows.get(i).getKey(), rows.get(i).getValue() / 1048576.0);
        System.out.println();
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

    private static final java.util.List<java.util.zip.ZipFile> HELD_JARS = new java.util.ArrayList<>();

    /**
     * クラスパスの JAR を全部開いたままにする（B1④）。JAR を開くと中央ディレクトリ（{@code ZipFile.Source.cen} の {@code byte[]}）が
     * ヒープに載る。これは起動方法（Gradle のクラスパス）の都合で、文書を開いたときに初めて開かれる JAR があると、
     * エンジンの保持量に誤って数えられる（Y4: large-2000 で約 1.8 MB）。土台の側へ寄せて、文書ごとの差分から外す。
     */
    private static int holdClasspathJars() {
        int n = 0;
        for (String e : System.getProperty("java.class.path", "").split(java.io.File.pathSeparator)) {
            if (!e.endsWith(".jar")) continue;
            try {
                HELD_JARS.add(new java.util.zip.ZipFile(e));
                n++;
            } catch (java.io.IOException ex) {
                // 読めない JAR は無視
            }
        }
        return n;
    }

    public static void main(String[] args) {
        int jars = Boolean.getBoolean("mem.nohold") ? 0 : holdClasspathJars();
        System.out.println("MEM held classpath jars=" + jars + " (中央ディレクトリを土台へ寄せる。-Dmem.nohold=true で従来の測り方)");
        // 文書に依らない土台（フォント・キャッシュ）を先に温める
        StandaloneView warm = new StandaloneView(Workloads.html("large-list-50"), 800, 600, FontResolver.FALLBACK);
        warm.frame(1000, new NullBackend(), true);
        System.out.printf("MEM baseline(after warm-up view, GC) used=%.1f MB%n", used() / 1048576.0);
        for (String doc : args) {
            long before = used();
            java.util.Map<String, long[]> histoBefore = histogramMap();
            double privBefore = privateMb();
            String html = Workloads.html(doc);
            StandaloneView v = new StandaloneView(html, Workloads.VIEWPORT_W, Workloads.VIEWPORT_H, FontResolver.FALLBACK);
            NullBackend b = new NullBackend();
            for (int i = 0; i < 5; i++) v.frame(1000 + i * 16, b, true);
            long after = used();
            printGrowth(doc, histoBefore, histogramMap());
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
