package com.nishiyu.enderframelib.demo;

import com.nishiyu.enderframelib.api.dom.Element;
import com.nishiyu.enderframelib.api.ui.State;
import com.nishiyu.enderframelib.api.ui.VNode;
import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.common.StandaloneViewer;
import com.nishiyu.enderframelib.common.UiTimers;

import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import static com.nishiyu.enderframelib.api.ui.React.*;

/**
 * ライブラリだけで動くテストサイト。表は多彩な HTML / CSS アニメーション、裏は Java が DOM を操作する。
 *
 * <p>React / Hyperscript に着想を得たモダンな宣言的 UI DSL ({@link com.nishiyu.enderframelib.api.ui.React})
 * で記述されています。</p>
 *
 * <pre>
 * ./gradlew :core:runShowcase                         窓で表示
 * ./gradlew :core:runShowcase --args="--png out.png --after 3000"   窓なしで PNG
 * </pre>
 *
 * Java 側の仕込み（{@link #wire}）: 時計・カウンタ・進捗バー・ログの追加と削除・表の書き換え・
 * フォームのイベント・1 000 要素のグリッドの波。
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

    // ---- React 関数コンポーネント群 ----

    private static VNode Bar(State<Double> scale) {
        return div(className("bar"),
                div(className("fill"), style("transform", scale.map(s -> "scaleX(" + s + ")")))
        );
    }

    private static VNode LogRow(int seq, String verb, long timestamp) {
        return div(className("row"), "#" + seq + "  " + verb + "  @" + timestamp);
    }

    private static VNode TableRow(int index, State<Integer> value, State<Boolean> hot) {
        return tr(className(when("hot", hot)),
                td(index),
                td("node-" + Integer.toHexString(0x1000 + index * 37)),
                td(value)
        );
    }

    private static VNode GridCell(int r, int c, State<Boolean> flashed) {
        double phase = (c * 0.09 + r * 0.05) % 4.0;
        String phaseStr = String.format(Locale.ROOT, "%.2f", phase);
        boolean pulse = (r * 7 + c * 3) % 11 == 0;
        return div(
                className("px", when(pulse, "pulse"), when("flash", flashed)),
                style("animationDelay", "-" + phaseStr + "s")
        );
    }

    /**
     * 全ティックの土台（1000ms）。DOM を書き換えるたびに全体が再レイアウトされるので、
     * 書き換えを 1 つのティックへ集める。{@code -Dshowcase.off=bars,log} で負荷切り分け可。
     */
    private static final class TickScheduler {
        private static final long BASE_MS = 1000;
        private final List<Runnable> tasks = new ArrayList<>();
        private final List<Integer> everyTicks = new ArrayList<>();
        private final List<String> offNames = Arrays.asList(System.getProperty("showcase.off", "").split(","));
        private int tickNo;
        private boolean started;

        void every(String name, Runnable task, long ms) {
            if (offNames.contains(name)) return;
            if (!started) {
                started = true;
                setInterval(() -> {
                    tickNo++;
                    for (int i = 0; i < tasks.size(); i++) {
                        if (tickNo % everyTicks.get(i) == 0) {
                            tasks.get(i).run();
                        }
                    }
                }, BASE_MS);
            }
            tasks.add(task);
            everyTicks.add((int) Math.max(1, Math.round((double) ms / BASE_MS)));
        }
    }

    /** 文書を読み込んだ直後に呼ばれる。以降は React State / Effect と JS タイマーで DOM を駆動する。 */
    static void wire(StandaloneView view) {
        UiTimers timers = Boolean.getBoolean("showcase.notimers") ? new UiTimers("x") : view.timers();
        var rnd = new Random(42);

        view.mountApp(timers, () -> {
            var scheduler = new TickScheduler();

            // ---- 1. 時計とカウンタ (useState / bindText) ----
            var clock = useState("--:--:--");
            var counter = useState(0);
            bindText($("#clock"), clock);
            bindText($("#counter"), counter);

            var timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss");
            scheduler.every("clock", () -> {
                clock.set(LocalTime.now().format(timeFmt));
                counter.update(c -> c + 1);
            }, 1000);

            // ---- 2. 進捗バー（State の変化で transform transition を駆動） ----
            var barScales = map(6, i -> useState(0.1));
            createRoot("#bars").render(map(barScales, ShowcaseApp::Bar));

            scheduler.every("bars", () -> {
                for (var scale : barScales) {
                    scale.set((8 + rnd.nextInt(92)) / 100.0);
                }
            }, 2000);

            // ---- 3. ログ（追加と削除） ----
            var log = $("#log");
            var seq = useRef(0);
            String[] verbs = {"createElement", "appendChild", "setAttribute", "classList.add", "removeChild", "setTextContent"};
            scheduler.every("log", () -> {
                int nextSeq = seq.get() + 1;
                seq.set(nextSeq);
                mount(log, LogRow(nextSeq, verbs[rnd.nextInt(verbs.length)], System.currentTimeMillis() % 100000));
                while (log.getChildElementCount() > 12) {
                    Element first = log.getFirstElementChild();
                    if (first != null) log.removeChild(first);
                }
            }, 400);

            // ---- 4. 表（17 行を作り、毎秒ランダム行をリアクティブ更新） ----
            record RowState(int index, State<Integer> value, State<Boolean> hot) {}
            var rows = map(1, 18, i -> new RowState(i, useState(0), useState(false)));
            createRoot("#tbl").append(
                    tbody(map(rows, row -> TableRow(row.index(), row.value(), row.hot())))
            );

            scheduler.every("table", () -> {
                for (int i = 0; i < 8; i++) {
                    var row = rows.get(rnd.nextInt(rows.size()));
                    row.value().set(rnd.nextInt(10000));
                    row.hot().set(rnd.nextBoolean());
                }
            }, 400);

            // ---- 5. フォームのイベント (React useState / useEffect + setStyle) ----
            var name = useState("Hello EnderFrameLib world");
            var size = useState(80);
            var color = useState("#f59e0b");
            var spinning = useState(false);
            var echoText = useState("(Java echo appears here)");

            bindText($("#sizeOut"), size);
            bindText($("#echo"), echoText);

            onInputValue($("#name"), name::set);
            onInputValue($("#size"), v -> size.set(parseIntOr(v, 80)));
            onChangeValue($("#color"), color::set);
            onClick($("#spin"), e -> spinning.update(s -> !s));
            onClick($("#go"), e -> echoText.set(
                    "Java received: name=\"" + name.get() + "\" size=" + size.get() + " color=" + color.get()
            ));

            var preview = $("#preview");
            useEffect(() -> setStyle(preview, Map.of(
                    "width", size.get() + "px",
                    "height", size.get() + "px",
                    "background", color.get(),
                    "borderRadius", "12px",
                    "margin", "30px",
                    "animation", Boolean.TRUE.equals(spinning.get()) ? "spinbox 2s linear infinite" : ""
            )), size, color, spinning);

            // ---- 6. ストレス: 1 000 セルの波 ----
            // 波そのものは CSS の @keyframes。Java は位相を一度だけ割り振り、毎ティックはフラッシュ状態のみ切り替える。
            @SuppressWarnings("unchecked")
            State<Boolean>[][] cellFlash = new State[GRID_ROWS][GRID_COLS];
            List<VNode> cellNodes = new ArrayList<>(GRID_ROWS * GRID_COLS);
            for (int r = 0; r < GRID_ROWS; r++) {
                for (int c = 0; c < GRID_COLS; c++) {
                    var flashed = useState(false);
                    cellFlash[r][c] = flashed;
                    cellNodes.add(GridCell(r, c, flashed));
                }
            }
            createRoot("#grid").render(cellNodes);

            $("#stressTitle").setTextContent(GRID_COLS * GRID_ROWS + " elements, animated from Java");
            var activeFlashed = new ArrayList<State<Boolean>>(12);
            scheduler.every("grid", () -> {
                for (var prev : activeFlashed) {
                    prev.set(false);
                }
                activeFlashed.clear();
                for (int i = 0; i < 12; i++) {
                    var next = cellFlash[rnd.nextInt(GRID_ROWS)][rnd.nextInt(GRID_COLS)];
                    next.set(true);
                    activeFlashed.add(next);
                }
            }, TickScheduler.BASE_MS);

            // ---- 7. 夕焼けの縞（Java が 12 本を差し込む） ----
            createRoot("#stripes").render(
                    map(12, i -> div(
                            className("stripe"),
                            style("animationDelay", "-" + (i * 0.25) + "s")
                    ))
            );
        });
    }

    private static int parseIntOr(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
