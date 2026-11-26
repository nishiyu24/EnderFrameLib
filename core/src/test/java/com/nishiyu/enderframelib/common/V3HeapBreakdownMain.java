package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.MemoryAndLatencyGateAccess;
import com.nishiyu.enderframelib.core.paint.RenderBackend;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.text.model.FontResolver;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.management.ObjectName;

/**
 * M2c-1（計画書 §3.2）: ビューの保持ヒープを段ごとに分ける診断。ゲート（{@code MemoryAndLatencyGate}）と同じ文書・同じフレームを回してから、
 * 出力に近い段から順に参照を外し、そのたびに GC 後の使用量の減りを段の大きさとして出す（後で外す段は、先に外した段と共有していない分だけが
 * 数えられる）。最後にクラスごとの生存オブジェクトの上位（ビューの構築前との差）を出す。
 *
 * <pre>./gradlew :core:heapBreakdown [-Pheap.doc=varied-list-2000] [-Pheap.path=V3|LEGACY] [-Pheap.scenario=open|hover]</pre>
 */
public final class V3HeapBreakdownMain {
    private V3HeapBreakdownMain() { }

    private static final class NullBackend implements RenderBackend {
        @Override public void render(List<DisplayItem> items, long nowMs) { }
        @Override public Pixmap pixmap() { throw new UnsupportedOperationException(); }
        @Override public float devicePixelRatio() { return 1f; }
    }

    public static void main(String[] args) throws Exception {
        String docs = System.getProperty("heap.doc", "varied-list-2000,large-list-2000");
        EnginePath path = EnginePath.valueOf(System.getProperty("heap.path", "V3"));
        String scenario = System.getProperty("heap.scenario", "open");
        try (StandaloneView warm = new StandaloneView(MemoryAndLatencyGateAccess.largeList(50), 800, 600, FontResolver.FALLBACK, path)) {
            warm.frame(1000, new NullBackend(), true);
        }
        for (String doc : docs.split(",")) run(doc.trim(), path, scenario);
    }

    private static void run(String doc, EnginePath path, String scenario) throws Exception {
        Map<String, Long> histBefore = histogram();
        long base = used();
        String html = MemoryAndLatencyGateAccess.loadHtml(doc);
        StandaloneView view = new StandaloneView(html, 800, 600, FontResolver.FALLBACK, path);
        NullBackend backend = new NullBackend();
        long[] now = {1000};
        view.frame(now[0], backend, true);
        if (scenario.equals("open")) {
            for (int i = 0; i < 4; i++) view.frame(now[0] += 16, backend, true);
        } else {
            MemoryAndLatencyGateAccess.steps(view, doc, scenario, 100, now, backend);
        }
        html = null;
        long total = used();
        Map<String, Long> histFull = histogram();

        UiView ui = view.ui();
        Map<String, Long> stages = new LinkedHashMap<>();
        long prev = total;
        // 1. 表示リストとその索引（ビューと窓が持つ出力）
        set(view, "items", List.of());
        set(view, "lastFrame", null);
        for (String f : new String[]{"renderCache", "hitTestIndex", "hitTestIndexSource", "byIdSource", "byIdIndex", "absoluteSource",
                "absoluteResult", "scrollBase", "dynamicIdsBase", "dynamicIdsCache"}) {
            set(ui, f, defaultFor(ui, f));
        }
        prev = record(stages, "表示リストと索引（ビュー・窓が持つ分）", prev);
        Object query = get(ui, "boxLayoutQuery");
        if (query != null) {
            set(query, "root", null);
            set(query, "index", null);
        }
        prev = record(stages, "CSSOM の問い合わせが持つ箱の索引", prev);
        if (path == EnginePath.V3) {
            V3Frame v3 = ui.v3;
            set(v3, "painter", null);
            prev = record(stages, "TreePainter の記憶（記憶が持つ表示リスト・Fragment を含む）", prev);
            set(v3, "root", null);
            Object tree = get(v3, "tree");
            set(tree, "root", null);
            prev = record(stages, "描画用の木（PaintNode）", prev);
            Object pipeline = get(v3, "pipeline");
            Object layout = get(pipeline, "layout");
            for (Field f : layout.getClass().getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                f.setAccessible(true);
                if (f.getType().getSimpleName().equals("LayoutSession")) continue;
                f.set(layout, null);
            }
            prev = record(stages, "断片木とレイアウトの記憶（LayoutFragment・FlowChunk・LayoutCache）", prev);
            set(pipeline, "boxRoot", null);
            set(get(pipeline, "compiler"), "root", null);
            prev = record(stages, "箱の木（BoxNode・BoxChunk と Style）", prev);
            set(v3, "pipeline", null);
            set(v3, "tree", null);
            prev = record(stages, "新経路の残り（解き方・セッション）", prev);
        } else {
            set(ui, "layoutRoot", null);
            prev = record(stages, "旧経路の LayoutBox の木（記憶・Fragment を含む）", prev);
        }
        view.close();
        view = null;
        ui = null;
        prev = record(stages, "DOM・ビュー・その他", prev);

        System.out.printf(Locale.ROOT, "%n== %s:%s path=%s  保持ヒープ全体 %.2f MB ==%n", doc, scenario, path, mb(total - base));
        for (var e : stages.entrySet()) System.out.printf(Locale.ROOT, "  %-60s %8.2f MB%n", e.getKey(), mb(e.getValue()));
        System.out.printf(Locale.ROOT, "  %-60s %8.2f MB%n", "（測りきれない差: 共有・GC の揺れ）", mb(prev - base));
        System.out.println("  -- 生存オブジェクトの上位（構築前との差。クラス: MB / 個数） --");
        printTop(histBefore, histFull, 30);
        System.out.println("  -- ビューを閉じた後にも残るもの（構築前との差） --");
        printTop(histBefore, histogram(), 12);
    }

    private static void printTop(Map<String, Long> before, Map<String, Long> after, int limit) {
        List<Map.Entry<String, Long>> diff = new ArrayList<>();
        for (var e : after.entrySet()) {
            long d = e.getValue() - before.getOrDefault(e.getKey(), 0L);
            if (d > 0) diff.add(Map.entry(e.getKey(), d));
        }
        diff.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        for (int i = 0; i < Math.min(limit, diff.size()); i++) {
            var e = diff.get(i);
            System.out.printf(Locale.ROOT, "  %-80s %8.2f MB  %s%n", e.getKey(), mb(e.getValue()), COUNTS.getOrDefault(e.getKey(), ""));
        }
    }

    private static Object defaultFor(Object o, String name) throws Exception {
        Field f = field(o.getClass(), name);
        if (f == null) return null;
        Class<?> t = f.getType();
        if (t == List.class) return List.of();
        if (t == Map.class) return Map.of();
        return null;
    }

    private static long record(Map<String, Long> stages, String name, long prev) {
        long now = used();
        stages.put(name, Math.max(0, prev - now));
        return now;
    }

    private static Field field(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) { /* 親を見る */ }
        }
        return null;
    }

    private static void set(Object o, String name, Object value) throws Exception {
        Field f = field(o.getClass(), name);
        if (f == null) {
            System.out.println("  (欄が無い: " + o.getClass().getSimpleName() + "." + name + ")");
            return;
        }
        f.set(o, value);
    }

    private static Object get(Object o, String name) throws Exception {
        Field f = field(o.getClass(), name);
        if (f == null) throw new IllegalStateException(o.getClass() + "." + name);
        return f.get(o);
    }

    private static final Map<String, String> COUNTS = new LinkedHashMap<>();

    /** クラス名 → バイト数（{@code GC.class_histogram}。生存オブジェクトだけ）。 */
    private static Map<String, Long> histogram() throws Exception {
        String out = (String) ManagementFactory.getPlatformMBeanServer().invoke(new ObjectName("com.sun.management:type=DiagnosticCommand"),
                "gcClassHistogram", new Object[]{null}, new String[]{String[].class.getName()});
        Map<String, Long> m = new LinkedHashMap<>();
        for (String line : out.split("\n")) {
            String[] p = line.trim().split("\\s+");
            if (p.length < 4 || !p[0].endsWith(":")) continue;
            try {
                m.merge(p[3], Long.parseLong(p[2]), Long::sum);
                COUNTS.put(p[3], p[1]);
            } catch (NumberFormatException ignored) { /* 見出し */ }
        }
        return m;
    }

    private static long used() {
        for (int i = 0; i < 5; i++) {
            System.gc();
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        Runtime r = Runtime.getRuntime();
        return r.totalMemory() - r.freeMemory();
    }

    private static double mb(long bytes) {
        return bytes / 1048576.0;
    }
}
