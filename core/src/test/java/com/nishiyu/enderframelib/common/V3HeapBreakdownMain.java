package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.MemoryAndLatencyGateAccess;
import com.nishiyu.enderframelib.core.paint.RenderBackend;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.text.model.FontResolver;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
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
 * <pre>./gradlew :core:heapBreakdown [-Pheap.doc=varied-list-2000] [-Pheap.scenario=open|hover] [-Pheap.detail=true]</pre>
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
        String scenario = System.getProperty("heap.scenario", "open");
        try (StandaloneView warm = new StandaloneView(MemoryAndLatencyGateAccess.largeList(50), 800, 600, FontResolver.FALLBACK)) {
            warm.frame(1000, new NullBackend(), true);
        }
        for (String doc : docs.split(",")) run(doc.trim(), scenario);
    }

    private static void run(String doc, String scenario) throws Exception {
        Map<String, Long> histBefore = histogram();
        long base = used();
        String html = MemoryAndLatencyGateAccess.loadHtml(doc);
        StandaloneView view = new StandaloneView(html, 800, 600, FontResolver.FALLBACK);
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
        Map<String, String> countsFull = new LinkedHashMap<>(COUNTS); // 後の段ごとの調べが COUNTS を書き換えるので、全体の個数を先に写す

        if (DETAIL) opCensus(view.ui().v3.scene());
        Map<String, Long> stages = new LinkedHashMap<>();
        lastHist = DETAIL ? histogram() : null;
        STAGE_TOP.clear();
        long prev = releaseStages(view, stages, total); // 段を外す局所変数（ビューへの逆参照を持つ）はこの呼び出しの中で消える
        view.close();
        view = null;
        prev = record(stages, "DOM・ビュー・その他", prev);

        System.out.printf(Locale.ROOT, "%n== %s:%s  保持ヒープ全体 %.2f MB ==%n", doc, scenario, mb(total - base));
        for (var e : stages.entrySet()) {
            System.out.printf(Locale.ROOT, "  %-60s %8.2f MB%n", e.getKey(), mb(e.getValue()));
            if (STAGE_TOP.containsKey(e.getKey())) System.out.print(STAGE_TOP.get(e.getKey()));
        }
        System.out.printf(Locale.ROOT, "  %-60s %8.2f MB%n", "（測りきれない差: 共有・GC の揺れ）", mb(prev - base));
        if (prev - base > (total - base) / 4) {
            System.out.println("  !! 測りきれない差が全体の 1/4 を超えた: 段の外し漏れ（シーン・列・箱を持つ新しい欄を releaseStages に足していないか）");
        }
        System.out.println("  -- 生存オブジェクトの上位（構築前との差。クラス: MB / 個数） --");
        printTop(histBefore, histFull, 30, countsFull);
        System.out.println("  -- ビューを閉じた後にも残るもの（構築前との差） --");
        Map<String, Long> histAfter = histogram();
        printTop(histBefore, histAfter, 12, COUNTS);
    }

    /**
     * 出力に近い段から参照を外して、段ごとの減りを {@code stages} に記録する。外す欄の名前は反射で引くので、ビュー・窓に出力を持つ欄を足したら
     * ここにも足す（足し忘れると、その段と後の段が 0 になり全部が「測りきれない差」へ落ちる）。
     */
    private static long releaseStages(StandaloneView view, Map<String, Long> stages, long prev) throws Exception {
        UiView ui = view.ui();
        // 1. 表示リストとその索引（ビューと窓が持つ出力）。cachedSource は描画器へ渡すシーンの束（シーン・列・動的状態。M3b-4b-4a）、
        //    cachedDynRoot は動的状態を作った根の層の覚え
        set(view, "items", List.of());
        set(view, "lastFrame", null);
        for (String f : new String[]{"renderCache", "byIdSource", "byIdIndex", "scrollBase", "cachedDynState", "cachedDynRoot", "cachedSource"}) {
            set(ui, f, defaultFor(ui, f));
        }
        Object hits = get(ui, "hitSupport"); // 当たり判定の文脈（M3b-3）が前回の動的状態を持つ
        if (hits != null) {
            set(hits, "lastDyn", null);
            set(hits, "cachedCtx", null);
        }
        prev = record(stages, "表示リストと索引（ビュー・窓が持つ分）", prev);
        Object query = get(ui, "boxLayoutQuery");
        if (query != null) {
            set(query, "scene", null);
            set(query, "dynamic", null);
            set(query, "index", null);
        }
        prev = record(stages, "CSSOM の問い合わせが持つ箱の索引", prev);
        V3Frame v3 = ui.v3;
        set(v3, "adapter", null);
        prev = record(stages, "表示リストアダプタ（一時アダプタ・記憶なし）", prev);
        set(v3, "scene", null);
        Object emitter = get(v3, "emitter");
        set(emitter, "prevScene", null);
        prev = record(stages, "描画シーン（DisplayScene / SceneLayer / SceneChunk）", prev);
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
        set(v3, "emitter", null);
        return record(stages, "新経路の残り（解き方・セッション）", prev);
    }

    private static void printTop(Map<String, Long> before, Map<String, Long> after, int limit, Map<String, String> counts) {
        List<Map.Entry<String, Long>> diff = new ArrayList<>();
        for (var e : after.entrySet()) {
            long d = e.getValue() - before.getOrDefault(e.getKey(), 0L);
            if (d > 0) diff.add(Map.entry(e.getKey(), d));
        }
        diff.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        for (int i = 0; i < Math.min(limit, diff.size()); i++) {
            var e = diff.get(i);
            System.out.printf(Locale.ROOT, "  %-80s %8.2f MB  %s%n", e.getKey(), mb(e.getValue()), counts.getOrDefault(e.getKey(), ""));
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
        if (DETAIL) {
            try {
                Map<String, Long> h = histogram();
                if (lastHist != null) STAGE_TOP.put(name, topDrops(lastHist, h, 14));
                lastHist = h;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return now;
    }

    /**
     * 層の描画命令の中身の調べ（M3b-4c-0。型付きの小さい命令にしたときの見込みの根拠）: 命令の種類ごとの数と、命令が持つ欄のうち型付きの命令で
     * 要らなくなるもの（文字列 ID・{@code events}・{@code RenderMeta}・{@code RenderHints}・祖先のクリップ）と、残るもの（枠線・角丸・影・
     * グラデーション）の数。
     */
    private static void opCensus(com.nishiyu.enderframelib.core.paint.scene.DisplayScene scene) {
        if (scene == null) return;
        Map<String, int[]> byType = new java.util.TreeMap<>();
        java.util.IdentityHashMap<Object, Boolean> metas = new java.util.IdentityHashMap<>(), hints = new java.util.IdentityHashMap<>(),
                clips = new java.util.IdentityHashMap<>(), ids = new java.util.IdentityHashMap<>(), texts = new java.util.IdentityHashMap<>();
        int ops = 0, withEvents = 0, withAnim = 0, withRadii = 0, withBorder = 0, withShadow = 0, withGradient = 0, withLayer = 0, layers = 0;
        long idChars = 0;
        ArrayDeque<com.nishiyu.enderframelib.core.paint.scene.SceneLayer> stack = new ArrayDeque<>();
        stack.push(scene.root);
        while (!stack.isEmpty()) {
            var l = stack.pop();
            layers++;
            for (var op : l.ops()) {
                if (!(op instanceof com.nishiyu.enderframelib.core.paint.scene.DrawOp.Item it)) continue;
                ops++;
                byType.computeIfAbsent(String.valueOf(it.type()), k -> new int[1])[0]++;
                if (it.events() != null && !it.events().isEmpty()) withEvents++;
                if (it.animDef() != null) withAnim++;
                for (String s : new String[]{it.id(), it.baseNodeId()}) {
                    if (s != null && ids.put(s, Boolean.TRUE) == null) idChars += s.length();
                }
                if (it.text() != null) texts.put(it.text(), Boolean.TRUE);
                var m = it.meta();
                if (m == null) continue;
                metas.put(m, Boolean.TRUE);
                if (m.clipRect() != null) clips.put(m.clipRect(), Boolean.TRUE);
                if (m.borderRadii() != null) withRadii++;
                if (m.roundedBorder() != null || m.circleBorder() != null) withBorder++;
                var h = m.hints();
                if (h != null && h != com.nishiyu.enderframelib.core.paint.model.RenderHints.NONE) {
                    hints.put(h, Boolean.TRUE);
                    if (h.shadowShape() != null || h.shadowBlur() != 0f) withShadow++;
                    if (h.shaderGradient() != null) withGradient++;
                    if (h.layer() != null) withLayer++;
                }
            }
            for (var c : l.children.chunks) for (var k : c.layers) stack.push(k);
        }
        System.out.printf(Locale.ROOT, "%n  -- 層の描画命令の調べ: 層 %d・命令 %d（種類ごと %s）%n", layers, ops,
                byType.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()[0]).collect(java.util.stream.Collectors.joining(", ")));
        System.out.printf(Locale.ROOT, "     型付きで要らない: 文字列 ID %d 個（%d 字）・events %d・animDef %d・RenderMeta %d 個・RenderHints %d 個・ClipRect %d 個%n",
                ids.size(), idChars, withEvents, withAnim, metas.size(), hints.size(), clips.size());
        System.out.printf(Locale.ROOT, "     型付きでも残す: 角丸 %d・枠線の形 %d・影 %d・グラデーション %d・層の指定 %d・文字列の中身 %d 個%n",
                withRadii, withBorder, withShadow, withGradient, withLayer, texts.size());
    }

    /** {@code -Pheap.detail=true}: 段ごとに減ったクラスの上位も出す（段の中身を分ける。M3b-4c-0 で命令を型付きにする見込みを出すのに使った）。 */
    private static final boolean DETAIL = Boolean.getBoolean("heap.detail");
    private static Map<String, Long> lastHist;
    private static final Map<String, String> STAGE_TOP = new LinkedHashMap<>();

    private static String topDrops(Map<String, Long> before, Map<String, Long> after, int limit) {
        List<Map.Entry<String, Long>> d = new ArrayList<>();
        for (var e : before.entrySet()) {
            long x = e.getValue() - after.getOrDefault(e.getKey(), 0L);
            if (x > 0) d.add(Map.entry(e.getKey(), x));
        }
        d.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(limit, d.size()); i++) {
            String k = d.get(i).getKey();
            sb.append(String.format(Locale.ROOT, "      %-76s %7.2f MB%n", k.substring(k.lastIndexOf('.') + 1), mb(d.get(i).getValue())));
        }
        return sb.toString();
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
