package com.nishiyu.enderframelib.demo;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.common.StandaloneViewer;
import com.nishiyu.enderframelib.common.UiTimers;
import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;

import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * ライブラリだけで動くテストサイト。表は多彩な HTML / CSS アニメーション、裏は Java が DOM を操作する。
 *
 * <pre>
 * ./gradlew :core:runShowcase                         窓で表示
 * ./gradlew :core:runShowcase --args="--png out.png --after 3000"   窓なしで PNG
 * </pre>
 *
 * Java 側の仕込み（{@link #wire}）: 時計・カウンタ・進捗バー・ログの追加と削除・表の書き換え・
 * フォームのイベント・1 600 要素のグリッドの波。
 */
public final class ShowcaseApp {
    private static final int GRID_COLS = 50, GRID_ROWS = 20; // 1 000 セル

    private ShowcaseApp() {}

    public static void main(String[] args) throws Exception {
        Path html = Path.of("core/demo/resources/showcase.html");
        if (args.length > 0 && args[0].equals("--paint")) {
            paintBench(html);
            return;
        }
        if (args.length > 0 && args[0].equals("--dump")) {
            StandaloneView v = new StandaloneView(java.nio.file.Files.readString(html), 1280, 800);
            wire(v);
            try (var cpu = new com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend(1280, 800, 1f)) {
                v.frame(0, cpu, true);
            }
            for (var it : v.items()) if (it.text() != null && (it.text().contains("node-1025") || it.text().equals("1") || it.text().equals("name"))) System.out.println(it.baseId() + " " + it.type() + " x=" + it.x() + " y=" + it.y() + " w=" + it.width() + " '" + it.text() + "'");
            return;
        }
        if (args.length > 0 && args[0].equals("--bench")) {
            bench(html);
            return;
        }
        if (args.length > 0 && args[0].equals("--scroll")) {
            scrollBench(html);
            System.exit(0);
            return;
        }
        if (args.length > 1 && args[0].equals("--gpupng")) {
            // GPU の経路で 1 枚描いて PNG にする（範囲の限定などの切り替えで、絵が変わらないことを見る）
            StandaloneView v = new StandaloneView(java.nio.file.Files.readString(html), 1280, 800);
            wire(v);
            try (var gl = new com.nishiyu.enderframelib.core.paint.gl.LwjglGlRenderBackend(1280, 800, 1f)) {
                v.frame(0, gl, true);
                v.frame(Long.getLong("gpupng.now", 2500L), gl, true);
                gl.writePng(Path.of(args[1]));
            }
            System.exit(0);
            return;
        }
        if (args.length > 0 && args[0].equals("--render")) {
            renderBench(html);
            System.exit(0);
            return;
        }
        // 先頭が「幅 高さ」ならそれを使う（既定 1280×800）。--png と組み合わせて縦長に撮ると全体を一度に確認できる
        boolean sized = args.length >= 2 && args[0].matches("[0-9]+") && args[1].matches("[0-9]+");
        List<String> a = new ArrayList<>(List.of(html.toString()));
        if (!sized) a.addAll(List.of("1280", "800"));
        a.addAll(List.of(args));
        StandaloneViewer.launch(a.toArray(new String[0]), ShowcaseApp::wire);
    }

    /** 窓なし（見えない GL 窓）で 120 フレーム回し、1 フレームの所要時間を出す。 */
    private static void bench(Path html) throws Exception {
        String src = java.nio.file.Files.readString(html).replace("</style>", System.getProperty("showcase.inject", "") + "</style>");
        StandaloneView view = new StandaloneView(src, 1280, 800);
        wire(view);
        try (var gl = new com.nishiyu.enderframelib.core.paint.gl.LwjglGlRenderBackend(1280, 800, 1f)) {
            int n = Integer.getInteger("showcase.frames", 400), warm = n / 2;
            long[] ms = new long[n - warm];
            int drawn = 0, rebuilt = 0;
            float y0 = 0, y1 = 0;
            long start = System.currentTimeMillis();
            for (int i = 0; i < n; i++) {
                long t0 = System.nanoTime();
                var itemsBefore = view.items();
                if (view.frame(System.currentTimeMillis(), gl, false)) drawn++;
                if (view.items() != itemsBefore) rebuilt++;
                long took = (System.nanoTime() - t0) / 1_000_000;
                if (i >= warm) ms[i - warm] = took;
                if (took > 80 && Boolean.getBoolean("showcase.verbose")) System.out.println("slow frame " + i + ": " + took + "ms " + view.lastTimings());
                if (i == 20) {
                    y0 = galleryY(view);
                    view.mouseMove(600, 400, gl.sessionId());
                }
                if (i == 21) view.scroll(0, -5, gl.sessionId());
                if (i == 100) y1 = galleryY(view);
                if (i == warm) view.scroll(0, -12, gl.sessionId()); // 定常状態でもう一度スクロール
                Thread.sleep(8);
            }
            long[] sorted = ms.clone();
            java.util.Arrays.sort(sorted);
            long sum = 0;
            for (long v : ms) sum += v;
            System.out.println("frame ms (after warm-up, " + ms.length + " frames): median=" + sorted[sorted.length / 2]
                    + " mean=" + sum / ms.length + " p90=" + sorted[sorted.length * 9 / 10] + " max=" + sorted[sorted.length - 1]
                    + " | scroll y " + y0 + " -> " + y1 + " drawn=" + drawn + " | " + view.lastTimings()
                    + " total=" + (System.currentTimeMillis() - start) + "ms rebuiltFrames=" + rebuilt + "/" + n);
        }
    }

    /** 窓と同じ GL 経路で、ホイールを回し続けながらフレームの CPU 時間を計る（スクロールの重さ）。 */
    private static void scrollBench(Path html) throws Exception {
        StandaloneView view = new StandaloneView(java.nio.file.Files.readString(html), 1280, 800);
        wire(view);
        var mx = java.lang.management.ManagementFactory.getThreadMXBean();
        try (var gl = new com.nishiyu.enderframelib.core.paint.gl.LwjglGlRenderBackend(1280, 800, 1f)) {
            for (int i = 0; i < 60; i++) { view.frame(System.currentTimeMillis(), gl, false); Thread.sleep(8); }
            view.mouseMove(600, 400, gl.sessionId());
            int n = Integer.getInteger("scroll.frames", 300);
            double[] cpu = new double[n];
            double[] wall = new double[n];
            double down = -1;
            for (int i = 0; i < n; i++) {
                if (i % 6 == 0) view.scroll(0, (i / 120) % 2 == 0 ? -1 : 1, gl.sessionId()); // ホイールを回し続ける（往復）
                long c0 = mx.getCurrentThreadCpuTime(), w0 = System.nanoTime();
                view.frame(System.currentTimeMillis(), gl, false);
                cpu[i] = (mx.getCurrentThreadCpuTime() - c0) / 1e6;
                wall[i] = (System.nanoTime() - w0) / 1e6;
                Thread.sleep(4);
            }
            // Windows の ThreadMXBean は 15.6ms 刻みなので、中央値ではなく合計から平均を出す
            var D = com.nishiyu.enderframelib.core.paint.DisplayListBuilder.class;
            System.out.println("memo: exact=" + com.nishiyu.enderframelib.core.paint.DisplayListBuilder.STAT_EXACT.sum() + " translated=" + com.nishiyu.enderframelib.core.paint.DisplayListBuilder.STAT_TRANSLATED.sum()
                    + " miss=" + com.nishiyu.enderframelib.core.paint.DisplayListBuilder.STAT_MISS.sum() + " new=" + com.nishiyu.enderframelib.core.paint.DisplayListBuilder.STAT_NEW.sum()
                    + " unmemoizable=" + com.nishiyu.enderframelib.core.paint.DisplayListBuilder.STAT_UNMEMOIZABLE.sum());
            System.out.println("scroll: translated=" + com.nishiyu.enderframelib.common.UiView.STAT_SCROLL_TRANSLATED.sum() + " fallback=" + com.nishiyu.enderframelib.common.UiView.STAT_SCROLL_FALLBACK.sum()
                    + " other=" + com.nishiyu.enderframelib.common.UiView.STAT_REPOSITION_OTHER.sum());
            double cpuMean = java.util.Arrays.stream(cpu).sum() / n, wallMean = java.util.Arrays.stream(wall).sum() / n;
            java.util.Arrays.sort(wall);
            System.out.printf("scroll frames: cpu mean=%.1f | wall mean=%.1f median=%.1f p90=%.1f p99=%.1f max=%.1f ms | %s%n",
                    cpuMean, wallMean, wall[n / 2], wall[n * 9 / 10], wall[n * 99 / 100], wall[n - 1], view.lastTimings());
        }
    }

    /**
     * 描画バックエンドだけを、同じ項目リストに対して時刻を進めながら繰り返し計る（CPU 時間。他のプロセスの負荷に左右されにくい）。
     * 再レイアウトを挟まない「アニメーションだけが動く」フレームの費用に当たる。
     */
    private static void renderBench(Path html) throws Exception {
        String page = java.nio.file.Files.readString(html).replace("</style>", System.getProperty("showcase.inject", "") + "</style>");
        StandaloneView view = new StandaloneView(page, 1280, 800);
        wire(view);
        var mx = java.lang.management.ManagementFactory.getThreadMXBean();
        try (var cpu = new com.nishiyu.enderframelib.core.paint.gl.LwjglGlRenderBackend(1280, 800, 1f)) {
            System.out.println("GL_RENDERER=" + org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER) + " / " + org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VERSION));
            view.frame(0, cpu, true);
            var items = view.items();
            long t = System.currentTimeMillis();
            for (int i = 0; i < 150; i++) cpu.render(items, t + i * 16L);
            int n = Integer.getInteger("render.frames", 300);
            long c0 = mx.getCurrentThreadCpuTime(), w0 = System.nanoTime();
            long wallBlock = System.nanoTime();
            for (int i = 0; i < n; i++) {
                cpu.render(items, t + 3000 + i * 16L);
                if (Boolean.getBoolean("render.sync")) cpu.pixmap(); // GPU の完了まで待つ（読み戻し）: GPU の時間も含めて測る
                if (Boolean.getBoolean("render.trace") && i % 100 == 99) { // 蓄積して遅くなっていないかを見る
                    System.out.printf("  frames %d-%d: %.1f ms/frame wall, heap %d MB%n", i - 99, i, (System.nanoTime() - wallBlock) / 1e6 / 100,
                            (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20);
                    wallBlock = System.nanoTime();
                }
            }
            System.out.printf("render-only: %.2f ms/frame cpu, %.1f ms/frame wall (%d items)%n", (mx.getCurrentThreadCpuTime() - c0) / 1e6 / n, (System.nanoTime() - w0) / 1e6 / n, items.size());
        }
    }

    /** 表示リスト作り（DisplayListBuilder.render）だけを、固定のレイアウトに対して繰り返し計る。 */
    private static void paintBench(Path html) throws Exception {
        StringBuilder cells = new StringBuilder();
        for (int i = 0; i < GRID_ROWS * GRID_COLS; i++) cells.append("<div class=\"px\" style=\"animation-delay:-").append(i % 40 / 10.0).append("s\"></div>");
        String src = java.nio.file.Files.readString(html).replace("<div id=\"grid\"></div>", "<div id=\"grid\">" + cells + "</div>");
        var compiler = new com.nishiyu.enderframelib.common.UiDocumentCompiler();
        var doc = compiler.parseDocument(src, "", 1280, 800);
        var scroll = new com.nishiyu.enderframelib.core.layout.model.ScrollState();
        var fonts = com.nishiyu.enderframelib.core.text.font.SystemFonts.system();
        var root = compiler.layoutDocument(doc, 1280, 800, java.util.Map.of(), scroll, fonts, new com.nishiyu.enderframelib.core.dom.InteractionState());
        int items = 0;
        for (int i = 0; i < 80; i++) new com.nishiyu.enderframelib.core.paint.DisplayListBuilder(1280, 800, doc.sheet.keyframes, scroll).render(root, false);
        int n = 150;
        var mx = java.lang.management.ManagementFactory.getThreadMXBean();
        long t0 = mx.getCurrentThreadCpuTime();
        for (int i = 0; i < n; i++) {
            items = new com.nishiyu.enderframelib.core.paint.DisplayListBuilder(1280, 800, doc.sheet.keyframes, scroll).render(root, false).size();
        }
        System.out.printf("paint full  : %.1f ms/frame cpu (%d items)%n", (mx.getCurrentThreadCpuTime() - t0) / 1e6 / n, items);
        // 窓と同じくビルダーを使い回した再レイアウト後の全件描画（変わらない箱は前回の項目を使う）
        var kept = new com.nishiyu.enderframelib.core.paint.DisplayListBuilder(1280, 800, doc.sheet.keyframes, scroll);
        for (int i = 0; i < 80; i++) kept.render(root, false);
        t0 = mx.getCurrentThreadCpuTime();
        for (int i = 0; i < n; i++) items = kept.render(root, false).size();
        System.out.printf("paint memo  : %.1f ms/frame cpu (%d items)%n", (mx.getCurrentThreadCpuTime() - t0) / 1e6 / n, items);
        // 窓と同じく、全件で描いたビルダーを使い回して、画面外の箱を省く（スクロール）
        var b = new com.nishiyu.enderframelib.core.paint.DisplayListBuilder(1280, 800, doc.sheet.keyframes, scroll);
        b.render(root, false);
        for (int i = 0; i < 80; i++) b.render(root, true);
        t0 = mx.getCurrentThreadCpuTime();
        for (int i = 0; i < n; i++) items = b.render(root, true).size();
        System.out.printf("paint culled: %.1f ms/frame cpu (%d items)%n", (mx.getCurrentThreadCpuTime() - t0) / 1e6 / n, items);
    }

    private static float galleryY(StandaloneView v) {
        for (var it : v.items()) if (it.baseId().equals("gallery")) return it.y();
        return Float.NaN;
    }

    /** 文書を読み込んだ直後に呼ばれる。以降はタイマーとイベントで DOM を書き換える。 */
    static void wire(StandaloneView view) {
        HtmlNode root = view.root();
        HtmlDocument doc = root.ownerDocument;
        TICK_TASKS.clear();
        TICK_EVERY.clear();
        UiTimers timers = view.timers();
        if (Boolean.getBoolean("showcase.notimers")) timers = new UiTimers("x");
        Random rnd = new Random(42);

        // ---- 時計とカウンタ ----
        HtmlNode clock = byId(root, "clock");
        HtmlNode counter = byId(root, "counter");
        int[] ticks = {0};
        every("clock", timers, () -> {
            clock.setTextContent(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
            counter.setTextContent(Integer.toString(++ticks[0]));
        }, 1000);

        // ---- 進捗バー（width の transition を Java が起こす） ----
        HtmlNode bars = byId(root, "bars");
        List<HtmlElement> fills = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            HtmlElement bar = el(doc, "div", "bar");
            HtmlElement fill = el(doc, "div", "fill");
            bar.appendChild(fill);
            bars.appendChild(bar);
            fills.add(fill);
        }
        every("bars", timers, () -> {
            for (HtmlElement f : fills) f.setAttribute("style", "transform:scaleX(" + ((8 + rnd.nextInt(92)) / 100.0) + ")");
        }, 2000);

        // ---- ログ（追加と削除） ----
        HtmlNode log = byId(root, "log");
        int[] seq = {0};
        String[] verbs = {"createElement", "appendChild", "setAttribute", "classList.add", "removeChild", "setTextContent"};
        every("log", timers, () -> {
            HtmlElement row = el(doc, "div", "row");
            row.setTextContent("#" + (++seq[0]) + "  " + verbs[rnd.nextInt(verbs.length)] + "  @" + System.currentTimeMillis() % 100000);
            log.appendChild(row);
            while (log.children.size() > 12) log.removeChild(log.children.get(0));
        }, 400);

        // ---- 表（17 行を作り、毎秒たくさん書き換える） ----
        HtmlNode tbl = byId(root, "tbl");
        HtmlElement body = el(doc, "tbody", null);
        tbl.appendChild(body);
        List<HtmlElement> valueCells = new ArrayList<>();
        List<HtmlElement> rows = new ArrayList<>();
        for (int i = 1; i <= 17; i++) {
            HtmlElement tr = el(doc, "tr", null);
            HtmlElement c0 = el(doc, "td", null), c1 = el(doc, "td", null), c2 = el(doc, "td", null);
            c0.setTextContent(Integer.toString(i));
            c1.setTextContent("node-" + Integer.toHexString(0x1000 + i * 37));
            c2.setTextContent("0");
            tr.appendChild(c0);
            tr.appendChild(c1);
            tr.appendChild(c2);
            body.appendChild(tr);
            valueCells.add(c2);
            rows.add(tr);
        }
        every("table", timers, () -> {
            for (int i = 0; i < 8; i++) {
                int k = rnd.nextInt(valueCells.size());
                valueCells.get(k).setTextContent(Integer.toString(rnd.nextInt(10000)));
                rows.get(k).setAttribute("class", rnd.nextBoolean() ? "hot" : "");
            }
        }, 400);

        // ---- フォームのイベント ----
        HtmlNode name = byId(root, "name");
        HtmlNode echo = byId(root, "echo");
        HtmlNode preview = byId(root, "preview");
        HtmlNode size = byId(root, "size");
        HtmlNode sizeOut = byId(root, "sizeOut");
        HtmlNode color = byId(root, "color");
        HtmlNode spin = byId(root, "spin");
        String base = "border-radius:12px;margin:30px;";
        size.addEventListener("input", e -> {
            String v = size.attrs.getOrDefault("value", "80");
            sizeOut.setTextContent(v);
            preview.setAttribute("style", "width:" + v + "px;height:" + v + "px;background:" + color.attrs.getOrDefault("value", "#f59e0b") + ";" + base);
        });
        color.addEventListener("change", e -> preview.setAttribute("style",
                "width:" + size.attrs.getOrDefault("value", "80") + "px;height:" + size.attrs.getOrDefault("value", "80") + "px;background:"
                        + color.attrs.getOrDefault("value", "#f59e0b") + ";" + base));
        spin.addEventListener("click", e -> {
            String s = preview.attrs.getOrDefault("style", "");
            preview.setAttribute("style", s.contains("animation") ? s.replaceAll("animation:[^;]*;?", "") : s + "animation: spinbox 2s linear infinite;");
        });
        byId(root, "go").addEventListener("click", e -> echo.setTextContent(
                "Java received: name=\"" + name.attrs.getOrDefault("value", "") + "\" size=" + size.attrs.getOrDefault("value", "")
                        + " color=" + color.attrs.getOrDefault("value", "")));

        // ---- ストレス: 1 000 セルの波 ----
        // 波そのものは CSS の @keyframes（色の補間は描画側＝GPU 経路が毎フレーム行う）。Java は位相（animation-delay）を
        // 一度だけ割り振り、毎ティックは数個のセルのクラスを入れ替えるだけにする（DOM の書き換えは全体の再レイアウトを伴うため）
        HtmlNode grid = byId(root, "grid");
        HtmlElement[][] cells = new HtmlElement[GRID_ROWS][GRID_COLS];
        for (int r = 0; r < GRID_ROWS; r++) {
            for (int c = 0; c < GRID_COLS; c++) {
                HtmlElement cell = el(doc, "div", (r * 7 + c * 3) % 11 == 0 ? "px pulse" : "px");
                double phase = (c * 0.09 + r * 0.05) % 4.0;
                cell.setAttribute("style", "animation-delay:-" + String.format(java.util.Locale.ROOT, "%.2f", phase) + "s");
                grid.appendChild(cell);
                cells[r][c] = cell;
            }
        }
        HtmlNode title = byId(root, "stressTitle");
        title.setTextContent(GRID_COLS * GRID_ROWS + " elements, animated from Java");
        HtmlElement[] flashed = new HtmlElement[12];
        every("grid", timers, () -> {
            for (int i = 0; i < flashed.length; i++) {
                if (flashed[i] != null) flashed[i].setAttribute("class", "px");
                flashed[i] = cells[rnd.nextInt(GRID_ROWS)][rnd.nextInt(GRID_COLS)];
                flashed[i].setAttribute("class", "px flash");
            }
        }, BASE_MS);

        // ---- 夕焼けの縞（Java が数十本を差し込む） ----
        HtmlNode stripes = byId(root, "stripes");
        for (int i = 0; i < 12; i++) {
            HtmlElement s = el(doc, "div", "stripe");
            s.setAttribute("style", "animation-delay:-" + (i * 0.25) + "s");
            stripes.appendChild(s);
        }
    }

    /** 全ティックの土台（ms）。DOM を書き換えるたびに全体が再レイアウトされるので、書き換えを 1 つのティックへ集める。 */
    private static final long BASE_MS = 1000;
    private static final List<Runnable> TICK_TASKS = new ArrayList<>();
    private static final List<Integer> TICK_EVERY = new ArrayList<>();
    private static int tickNo;

    /**
     * {@code ms} ごとに {@code r} を走らせる（実際には {@link #BASE_MS} のティックの倍数）。
     * {@code -Dshowcase.off=bars,log} のように名前で止められる（負荷の切り分け用）。
     */
    private static void every(String name, UiTimers timers, Runnable r, long ms) {
        String off = System.getProperty("showcase.off", "");
        if (java.util.Arrays.asList(off.split(",")).contains(name)) return;
        if (TICK_TASKS.isEmpty()) {
            tickNo = 0;
            timers.setInterval(() -> {
                tickNo++;
                for (int i = 0; i < TICK_TASKS.size(); i++) {
                    if (tickNo % TICK_EVERY.get(i) == 0) TICK_TASKS.get(i).run();
                }
            }, BASE_MS);
        }
        TICK_TASKS.add(r);
        TICK_EVERY.add((int) Math.max(1, Math.round((double) ms / BASE_MS)));
    }

    private static HtmlNode byId(HtmlNode root, String id) {
        HtmlNode n = root.getNodeById(id);
        if (n == null) throw new IllegalStateException("#" + id + " not found");
        return n;
    }

    private static int idSeq;

    private static HtmlElement el(HtmlDocument doc, String tag, String cls) {
        HtmlElement e = (HtmlElement) doc.createElement(tag);
        e.setAttribute("id", "j" + (++idSeq)); // 明示の id（自動採番の id に補間状態が結びつくのを避ける）
        if (cls != null) e.setAttribute("class", cls);
        return e;
    }
}
