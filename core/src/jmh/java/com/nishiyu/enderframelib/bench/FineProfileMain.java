package com.nishiyu.enderframelib.bench;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.common.UiView;
import com.nishiyu.enderframelib.core.diag.StageProfiler;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.HtmlText;
import com.nishiyu.enderframelib.core.paint.RenderBackend;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.text.model.FontResolver;

import java.util.Arrays;
import java.util.List;

/**
 * 実フレームの経路（{@link StandaloneView#frame}）を、シナリオ別に回して、フレーム全体の分布・段別の時間・確保量を出す。
 *
 * <pre>
 * ./gradlew :core:profile -Pprofile.main=com.nishiyu.enderframelib.bench.FineProfileMain \
 *     -Pprofile.args="&lt;scenario&gt; &lt;doc&gt; &lt;frames&gt; &lt;null|cpu|gl|vk&gt;" [-Pprofile.jfr=out.jfr]
 * </pre>
 * シナリオ: idle / hover / scroll / text / style（1 要素の style 属性）/ inherited（body の継承プロパティ）/
 * child（子の挿入と削除）/ relayout（全体の再レイアウト要求）/ resize / open（文書を開く: 解析 + 初回フレーム）。
 * バックエンド: {@code null} は描画しない（パイプラインだけ）、{@code cpu} は CPU ラスタまで含める、
 * {@code gl} / {@code vk} はそれぞれの GPU バックエンド。
 */
public final class FineProfileMain {
    private FineProfileMain() { }

    /**
     * {@code -Dprofile.font=<family>}（Gradle では {@code -Pprofile.font=Arial}）: OS の実書体で測る。文書の全要素へ同じ
     * {@code font-family} を強制し（{@link Workloads#withFont}。Chrome 側の {@code trace.js} も {@code BENCH_FONT} で同じ規則を足す）、
     * 文字の測定と描画をアウトライン書体の経路に通す。指定が無ければ従来どおり {@link FontResolver#FALLBACK}（文字幅の表だけの簡易書体）。
     */
    private static final String FONT = System.getProperty("profile.font");
    private static final FontResolver FONTS = FONT == null ? FontResolver.FALLBACK : com.nishiyu.enderframelib.core.text.font.SystemFonts.system();

    /** 描かないバックエンド（パイプラインのコストだけを見る）。 */
    private static final class NullBackend implements RenderBackend {
        @Override public void render(List<DisplayItem> items, long nowMs) { }
        @Override public Pixmap pixmap() { throw new UnsupportedOperationException(); }
        @Override public float devicePixelRatio() { return 1f; }
    }

    private static long D_STAT(Class<?> c, String name) {
        try {
            return ((java.util.concurrent.atomic.LongAdder) c.getField(name).get(null)).sum();
        } catch (ReflectiveOperationException e) {
            return -1;
        }
    }

    /**
     * {@code -Dprofile.disable=DisplayListBuilder.STACKING_PATCH_ENABLED,PaintOrder.STICKY_IN_SCROLL_NODE}: テストだけが切り替える
     * static な近道の印（起動時のスイッチを持たないもの）を false にしてから走らせる。回帰ゲート（{@link CostGateMain}）が、
     * 近道を 1 つ切ると実文書の行が赤になることを確かめるために使う（エンジン側に起動時のスイッチを足さない。近道のスイッチ数のラチェット）。
     * クラス名は {@code com.nishiyu.enderframelib.core.} からの相対（{@code paint.DisplayListBuilder}）か、単純名なら {@code core.paint} の下。
     */
    static void applyDisabledFlags(String spec) {
        if (spec == null || spec.isBlank()) return;
        for (String entry : spec.split(",")) {
            String e = entry.trim();
            int dot = e.lastIndexOf('.');
            if (dot <= 0) throw new IllegalArgumentException("profile.disable の書式は Class.FIELD: " + e);
            String cls = e.substring(0, dot), field = e.substring(dot + 1);
            String fqn = cls.startsWith("com.") ? cls : cls.contains(".") ? "com.nishiyu.enderframelib.core." + cls
                    : "com.nishiyu.enderframelib.core.paint." + cls;
            try {
                java.lang.reflect.Field f = Class.forName(fqn).getDeclaredField(field);
                f.setAccessible(true);
                f.setBoolean(null, false);
                System.out.println("DISABLED " + fqn + "." + field);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalArgumentException("profile.disable: 切り替えられない印 " + e, ex);
            }
        }
    }

    public static void main(String[] args) {
        applyDisabledFlags(System.getProperty("profile.disable"));
        String scenario = args.length > 0 ? args[0] : "idle";
        String doc = args.length > 1 ? args[1] : Workloads.SHOWCASE;
        int frames = args.length > 2 ? Integer.parseInt(args[2]) : 2000;
        String backendName = args.length > 3 ? args[3] : "null";
        boolean cpu = backendName.equals("cpu");
        int warmup = scenario.equals("open") ? 300 : 600;
        String html = Workloads.html(doc);
        if (FONT != null) {
            html = Workloads.withFont(html, FONT);
            System.out.println("FONT " + FONT + " face=" + FONTS.resolve(FONT).getClass().getSimpleName());
        }
        System.out.println("THREADS workers=" + com.nishiyu.enderframelib.core.layout.LayoutTaskPool.workerCount());
        if ((scenario.equals("hover") || scenario.equals("scrollhover")) && Workloads.isList(doc)) html = html.replace("</style>", " li:hover { background: #eef; }</style>");

        try (RenderBackend raw = switch (backendName) {
            case "cpu" -> { var b = new SoftwareRenderBackend(Workloads.VIEWPORT_W, Workloads.VIEWPORT_H, 1f); yield FONT == null ? b : b.fonts(FONTS); }
            case "gl" -> { var b = new com.nishiyu.enderframelib.core.paint.gl.LwjglGlRenderBackend(Workloads.VIEWPORT_W, Workloads.VIEWPORT_H); yield FONT == null ? b : b.fonts(FONTS); }
            case "vk" -> { var b = new com.nishiyu.enderframelib.core.paint.vulkan.VkRenderBackend(Workloads.VIEWPORT_W, Workloads.VIEWPORT_H); yield FONT == null ? b : b.fonts(FONTS); }
            default -> new NullBackend();
        }) {
            // 描画バックエンド呼び出し（CPU 側の描画・GL 送出・索引走査を含む）を PAINT 段として計るため包む。種別判定は raw で行う
            RenderBackend backend = new com.nishiyu.enderframelib.core.paint.ProfilingRenderBackend(raw);
            StandaloneView[] view = {newView(html)};
            long[] now = {1000};
            view[0].frame(now[0], backend, true);
            Target t = Target.find(view[0], doc);
            long[] memo0 = memoSnapshot(); // 開く分（初回の組み立て）を除くため、計測の前に取っておく

            com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
            long tid = Thread.currentThread().getId();
            // -Dprofile.sample=true: 別スレッドから main のスタックを 50µs ごとに採って、上位を出す（JFR が短いフレームを取りこぼすため）
            final java.util.concurrent.atomic.AtomicBoolean sampling = new java.util.concurrent.atomic.AtomicBoolean(false);
            final java.util.Map<String, int[]> topHits = new java.util.concurrent.ConcurrentHashMap<>(), inclHits = new java.util.concurrent.ConcurrentHashMap<>();
            final int[] sampleTotal = {0};
            Thread mainThread = Thread.currentThread();
            Thread sampler = null;
            if (Boolean.getBoolean("profile.sample")) {
                sampler = new Thread(() -> {
                    while (!Thread.currentThread().isInterrupted()) {
                        if (sampling.get()) {
                            StackTraceElement[] st = mainThread.getStackTrace();
                            if (st.length > 0) {
                                sampleTotal[0]++;
                                topHits.computeIfAbsent(st[0].getClassName() + "." + st[0].getMethodName(), k -> new int[1])[0]++;
                                java.util.Set<String> seen = new java.util.HashSet<>();
                                for (StackTraceElement e : st) {
                                    String k = e.getClassName() + "." + e.getMethodName();
                                    if (seen.add(k)) inclHits.computeIfAbsent(k, kk -> new int[1])[0]++;
                                }
                            }
                        }
                        java.util.concurrent.locks.LockSupport.parkNanos(50_000);
                    }
                }, "sampler");
                sampler.setDaemon(true);
                sampler.start();
            }
            double[] ms = new double[frames];
            long allocTotal = 0;
            int rendered = 0;

            com.nishiyu.enderframelib.core.paint.gpu.GpuRenderBackend gpuBackend = switch (raw) {
                case com.nishiyu.enderframelib.core.paint.gl.LwjglGlRenderBackend g0 -> g0.gpuBackend();
                case com.nishiyu.enderframelib.core.paint.vulkan.VkRenderBackend v0 -> v0.gpuBackend();
                default -> null;
            };
            long replayed0 = 0, flushes0 = 0, draws0 = 0;
            for (int i = -warmup; i < frames; i++) {
                if (i == 0) {
                    if (gpuBackend != null) { // G0/G3: 計測区間（ウォームアップ後）の GPU 被覆率
                        com.nishiyu.enderframelib.core.paint.port.GpuCoverage.reset();
                        com.nishiyu.enderframelib.core.paint.port.GpuCoverage.enable(true);
                        replayed0 = gpuBackend.rectTreeReplayedShapes();
                        flushes0 = gpuBackend.cpuOverlayFlushCount();
                        draws0 = gpuBackend.drawCallCount();
                    }
                    StageProfiler.reset();
                    com.nishiyu.enderframelib.core.layout.LayoutParallelProbe.reset();
                    StageProfiler.trackAllocations(Boolean.getBoolean("profile.stagealloc"));
                    StageProfiler.enable(true);
                }
                long a0 = mx.getThreadAllocatedBytes(tid);
                long t0 = System.nanoTime();
                if (i >= 0) sampling.set(true);
                boolean drew = step(scenario, view, backend, t, now, i, html);
                sampling.set(false);
                long t1 = System.nanoTime();
                if (i >= 0) {
                    ms[i] = (t1 - t0) / 1e6;
                    allocTotal += mx.getThreadAllocatedBytes(tid) - a0;
                    if (drew) rendered++;
                }
            }
            if (sampler != null) {
                sampler.interrupt();
                System.out.println("SAMPLES " + sampleTotal[0]);
                topHits.entrySet().stream().sorted((a, b) -> b.getValue()[0] - a.getValue()[0]).limit(16).forEach(e -> System.out.println("  TOP " + e.getValue()[0] + " " + e.getKey()));
                inclHits.entrySet().stream().filter(e -> e.getKey().contains("nishiyu")).sorted((a, b) -> b.getValue()[0] - a.getValue()[0]).limit(45).forEach(e -> System.out.println("  INC " + e.getValue()[0] + " " + e.getKey()));
            }
            StageProfiler.enable(false);
            if (com.nishiyu.enderframelib.core.layout.LayoutParallelProbe.enabled()) {
                System.out.printf("PARALLELPROBE independent share of measure=%.3f (boxes/frame=%.1f, total=%.2f ms/frame) place=%.3f (boxes/frame=%.1f, total=%.2f ms/frame)%n",
                        com.nishiyu.enderframelib.core.layout.LayoutParallelProbe.independentShare(0), com.nishiyu.enderframelib.core.layout.LayoutParallelProbe.independentBoxes(0) / (double) frames,
                        com.nishiyu.enderframelib.core.layout.LayoutParallelProbe.totalNs(0) / 1e6 / frames,
                        com.nishiyu.enderframelib.core.layout.LayoutParallelProbe.independentShare(1), com.nishiyu.enderframelib.core.layout.LayoutParallelProbe.independentBoxes(1) / (double) frames,
                        com.nishiyu.enderframelib.core.layout.LayoutParallelProbe.totalNs(1) / 1e6 / frames);
                System.out.printf("PARALLELPROBE restyle share=%.3f (boxes/frame=%.1f, total=%.2f ms/frame)%n",
                        com.nishiyu.enderframelib.core.layout.LayoutParallelProbe.independentShare(2),
                        com.nishiyu.enderframelib.core.layout.LayoutParallelProbe.independentBoxes(2) / (double) frames,
                        com.nishiyu.enderframelib.core.layout.LayoutParallelProbe.totalNs(2) / 1e6 / frames);
            }
            if (gpuBackend != null) {
                com.nishiyu.enderframelib.core.paint.port.GpuCoverage.enable(false);
                long replayed = gpuBackend.rectTreeReplayedShapes() - replayed0;
                // GPU 被覆率 = (受理 + 再生で再利用した図形) ÷ 全図形。CPU 層へ落ちた図形は種別ヒストグラムで多い順に出す
                System.out.printf("GPUCOVERAGE %.4f accepted=%d replayed=%d declined=%d declinedByKind: %s%n",
                        com.nishiyu.enderframelib.core.paint.port.GpuCoverage.coverage(replayed),
                        com.nishiyu.enderframelib.core.paint.port.GpuCoverage.totalAccepted(), replayed,
                        com.nishiyu.enderframelib.core.paint.port.GpuCoverage.totalDeclined(),
                        com.nishiyu.enderframelib.core.paint.port.GpuCoverage.declinedHistogram());
                System.out.printf("GPUFRAME backend=%s per-frame drawCalls=%.2f cpuOverlayFlushes=%.2f%n",
                        backendName, (gpuBackend.drawCallCount() - draws0) / (double) frames,
                        (gpuBackend.cpuOverlayFlushCount() - flushes0) / (double) frames);
            }

            double[] sorted = ms.clone();
            Arrays.sort(sorted);
            double sum = 0;
            for (double v : ms) sum += v;
            System.out.printf("RESULT scenario=%s doc=%s backend=%s frames=%d drawn=%d mean=%.4f p50=%.4f p95=%.4f p99=%.4f max=%.4f ms alloc=%.1f KB/frame%n",
                    scenario, doc, backendName, frames, rendered, sum / frames, sorted[frames / 2],
                    sorted[(int) (frames * 0.95)], sorted[(int) (frames * 0.99)], sorted[frames - 1], allocTotal / 1024.0 / frames);
            {
                // Chrome 側（bench.html の bench()）と同じ統計: 200 フレーム平均を 15 組取った中央値（GC・JIT の外れ値は組の平均に埋もれて除かれる）
                int n = 200, groups = Math.min(15, frames / n);
                if (groups >= 3) {
                    double[] g = new double[groups];
                    for (int k = 0; k < groups; k++) {
                        double s = 0;
                        for (int j = 0; j < n; j++) s += ms[k * n + j];
                        g[k] = s / n;
                    }
                    Arrays.sort(g);
                    System.out.printf("CHROMESTAT median-of-%d-batch-means(200)=%.4f min=%.4f max=%.4f ms%n", groups, g[groups / 2], g[0], g[groups - 1]);
                }
            }
            System.out.println("DOM elements=" + countElements(view[0].root()));
            // A1: 回帰ゲート（CostGateMain）が読む、時間に依らない量だけの行。確保量は main スレッドの 1 フレームあたり（バイト）、
            // ほかは経路の回数（ウォームアップ込みの累計。フレーム数が同じなら決定的）。項目を足すときは末尾へ（基準値ファイルは名前で引く）
            System.out.println("COST allocBytes=" + (allocTotal / frames)
                    + " drawn=" + rendered
                    + " elements=" + countElements(view[0].root())
                    + " items=" + view[0].ui().renderCache().size()
                    + " textPatchHits=" + UiView.TEXT_PATCH_HITS.get() + " textPatchFallbacks=" + UiView.TEXT_PATCH_FALLBACKS.get()
                    + " childPatchHits=" + UiView.CHILD_PATCH_HITS.get() + " childPatchFallbacks=" + UiView.CHILD_PATCH_FALLBACKS.get()
                    + " restyleStructural=" + UiView.RESTYLE_STRUCTURAL.get()
                    + " relayoutFull=" + UiView.RESTYLE_RELAYOUT_FULL.get()
                    + " relayoutInplace=" + UiView.RESTYLE_RELAYOUT_INPLACE.get()
                    + " reposition=" + UiView.RESTYLE_REPOSITION.get()
                    + " repaintPatch=" + UiView.RESTYLE_REPAINT_PATCH.get()
                    + " repaintFull=" + UiView.RESTYLE_REPAINT_FULL.get()
                    + " layoutsShifted=" + com.nishiyu.enderframelib.core.layout.LayoutBox.layoutsShifted()
                    + " layoutsSkipped=" + com.nishiyu.enderframelib.core.layout.LayoutBox.layoutsSkipped()
                    + memoCostKeys(memoSnapshot(), memo0));
            System.out.println("ITEMS " + view[0].ui().renderCache().size() + " textPatch hit/fallback=" + UiView.TEXT_PATCH_HITS.get() + "/" + UiView.TEXT_PATCH_FALLBACKS.get() + " childPatch hit/fallback=" + UiView.CHILD_PATCH_HITS.get() + "/" + UiView.CHILD_PATCH_FALLBACKS.get());
            if (gpuBackend != null) System.out.println(backendName.toUpperCase() + " shapes=" + gpuBackend.rectTreeShapes() + " glyphs=" + gpuBackend.gpuGlyphCount() + " rejects(pose,grid,corner,capacity/opacity,clip)=" + java.util.Arrays.toString(gpuBackend.rectTreeRejects()) + " drawCalls=" + gpuBackend.rectTreeDrawCalls() + " replayed items/shapes per frame=" + String.format("%.1f/%.1f", gpuBackend.rectTreeReplayedItems() / (double) (frames + warmup), gpuBackend.rectTreeReplayedShapes() / (double) (frames + warmup)) + " changedWrites/frame=" + String.format("%.1f", gpuBackend.rectTreeChangedWrites() / (double) (frames + warmup)) + " per-frame frames=" + (frames + warmup));
            {
                long n = frames + warmup;
                // 配置の近道の効き具合（プロセス累計 / フレーム）: shifted = 部分木の平行移動、skipped = 入力が同じで飛ばした
                System.out.printf("LAYOUTSKIP per-frame shifted=%.1f skipped=%.1f%n",
                        com.nishiyu.enderframelib.core.layout.LayoutBox.layoutsShifted() / (double) n,
                        com.nishiyu.enderframelib.core.layout.LayoutBox.layoutsSkipped() / (double) n);
            }
            {
                // C 系の KPI（記憶の効き具合）: 段ごとの 1 フレームあたりの呼び出し・使い回し・再計算（開いた後のウォームアップ＋計測の差分 / フレーム）
                double n = frames + warmup;
                long[] d = memoSnapshot();
                for (int k = 0; k < d.length; k++) d[k] -= memo0[k];
                System.out.printf("MEMO per-frame style(visited=%.1f recomputed=%.1f changed=%.1f) measure(calls=%.1f computed=%.1f) place(calls=%.1f placed=%.1f) fragment(hits=%.1f misses=%.1f) items=%d%n",
                        d[0] / n, d[1] / n, d[2] / n, d[3] / n, d[4] / n, d[5] / n, d[6] / n, d[7] / n, d[8] / n, view[0].ui().renderCache().size());
            }
            System.out.println("FRAGMENT memo hits=" +com.nishiyu.enderframelib.core.paint.DisplayListBuilder.fragmentMemoHits() + " ownReused=" + com.nishiyu.enderframelib.core.paint.DisplayListBuilder.fragmentOwnReused()
                    + " misses=" + com.nishiyu.enderframelib.core.paint.DisplayListBuilder.fragmentMemoMisses());
            System.out.println("RANGECULL planned=" + com.nishiyu.enderframelib.core.paint.PaintSceneDriver.RANGE_PLANNED.get()
                    + " ineligible=" + com.nishiyu.enderframelib.core.paint.PaintSceneDriver.RANGE_INELIGIBLE.get()
                    + " culledItems=" + com.nishiyu.enderframelib.core.paint.PaintSceneDriver.RANGE_CULLED_TOTAL.get());
            if (raw instanceof SoftwareRenderBackend sw) {
                try {
                    java.lang.reflect.Method p = SoftwareRenderBackend.class.getDeclaredMethod("partialFrames");
                    java.lang.reflect.Method f = SoftwareRenderBackend.class.getDeclaredMethod("fullFrames");
                    java.lang.reflect.Method a = SoftwareRenderBackend.class.getDeclaredMethod("damageAborts");
                    java.lang.reflect.Method s = SoftwareRenderBackend.class.getDeclaredMethod("skippedItems");
                    p.setAccessible(true); f.setAccessible(true); a.setAccessible(true); s.setAccessible(true);
                    java.lang.reflect.Method w = SoftwareRenderBackend.class.getDeclaredMethod("fullReasons");
                    w.setAccessible(true);
                    System.out.println("DAMAGE-WHY " + java.util.Arrays.toString((long[]) w.invoke(sw)));
                    System.out.println("DAMAGE partial=" + p.invoke(sw) + " full=" + f.invoke(sw) + " aborts=" + a.invoke(sw) + " skipped=" + s.invoke(sw));
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
            System.out.print(StageProfiler.report());
            double stageTotal = 0;
            for (StageProfiler.StageStats st : StageProfiler.snapshot()) {
                // GPU 段は数フレーム遅れて届くタイマークエリで CPU フレーム時間と重複するため合算しない
                if (st.stage() != com.nishiyu.enderframelib.core.diag.PipelineStage.GPU) stageTotal += st.totalMs();
            }
            double meanMs = sum / frames;
            double accounted = stageTotal / frames;
            System.out.printf("ACCOUNTED stages=%.4f ms/frame frame=%.4f ms explained=%.1f%% unaccounted=%.4f ms%n",
                    accounted, meanMs, 100.0 * accounted / meanMs, meanMs - accounted);
            System.out.println("PATCH restyle:"
                    + " | restyle: structural=" + UiView.RESTYLE_STRUCTURAL.get()
                    + " relayoutFull=" + UiView.RESTYLE_RELAYOUT_FULL.get()
                    + " relayoutInplace=" + UiView.RESTYLE_RELAYOUT_INPLACE.get()
                    + " reposition=" + UiView.RESTYLE_REPOSITION.get()
                    + " repaintPatch=" + UiView.RESTYLE_REPAINT_PATCH.get()
                    + " repaintFull=" + UiView.RESTYLE_REPAINT_FULL.get()
                    + " (ウォームアップ込み累計)");
            StringBuilder cant = new StringBuilder("CANTPATCH");
            cant.append(" noBuilder=").append(UiView.SLICE_NO_BUILDER.get()).append(" multiPlan=").append(UiView.SLICE_MULTI_PLAN.get());

            System.out.println(cant);
        }
    }

    private static int countElements(HtmlNode n) {
        int c = n instanceof HtmlElement ? 1 : 0;
        for (HtmlNode ch : n.children) c += countElements(ch);
        return c;
    }

    /** 記憶の KPI のカウンタ（累計）: style 訪問・再計算・変化 / 測定 呼び出し・計算 / 配置 呼び出し・配置し直し / 断片 hit・miss。 */
    private static long[] memoSnapshot() {
        long pc = com.nishiyu.enderframelib.core.kernel.Kernel.stats.placeCalls();
        long placed = pc - com.nishiyu.enderframelib.core.layout.LayoutBox.layoutsSkipped() - com.nishiyu.enderframelib.core.layout.LayoutBox.layoutsShifted();
        return new long[]{
                com.nishiyu.enderframelib.core.kernel.Kernel.stats.restyleVisited(), com.nishiyu.enderframelib.core.kernel.Kernel.stats.restyleRecomputed(),
                com.nishiyu.enderframelib.core.kernel.Kernel.stats.restyleChanged(),
                com.nishiyu.enderframelib.core.kernel.Kernel.stats.measureCalls(), com.nishiyu.enderframelib.core.layout.LayoutBox.measurementsComputed(),
                pc, placed,
                com.nishiyu.enderframelib.core.paint.DisplayListBuilder.fragmentMemoHits(), com.nishiyu.enderframelib.core.paint.DisplayListBuilder.fragmentMemoMisses()};
    }

    /** {@code COST} 行の末尾に足す、回帰ゲートが完全一致で比べる記憶の個数（再計算した数。開く分を除く）。 */
    private static String memoCostKeys(long[] now, long[] base) {
        return " memoStyleRecomputed=" + (now[1] - base[1]) + " memoMeasureComputed=" + (now[4] - base[4])
                + " memoPlaced=" + (now[6] - base[6]) + " memoFragMisses=" + (now[8] - base[8]);
    }

    private static StandaloneView newView(String html) {
        return new StandaloneView(html, Workloads.VIEWPORT_W, Workloads.VIEWPORT_H, FONTS);
    }

    /** 1 フレームぶんの変化を起こして、1 フレーム進める。描いたら true。 */
    private static boolean step(String scenario, StandaloneView[] view, RenderBackend backend, Target t, long[] now, int i,
                                String html) {
        StandaloneView v = view[0];
        switch (scenario) {
            case "idle" -> { }
            case "hover" -> {
                v.ui().setHoveredElement(t.hoverIds[i & 1]);
            }
            case "scrollhover" -> {
                // スクロールを 1 度起こして保持層を作らせたあと、画面外の項目の :hover を往復する（記録フレームの走査コスト）
                if (i == -600) v.mouseMove(t.scrollPoint[0], t.scrollPoint[1], backend.sessionId());
                if (i == -599 || i == -598) v.scroll(0, -1, backend.sessionId()); // 負が下方向（先頭では上へは動かない）
                if (i >= -597) v.ui().setHoveredElement(t.hoverIds[i & 1]);
            }
            case "scroll" -> {
                if (i == -600) v.mouseMove(t.scrollPoint[0], t.scrollPoint[1], backend.sessionId());
                v.scroll(0, (i & 1) == 0 ? 1 : -1, backend.sessionId());
            }
            case "textbtn" -> t.buttonText.setData((i & 1) == 0 ? "Buy now A" : "Buy now B");
            case "text" -> t.textTarget.setData((i & 1) == 0 ? "Row item A with text " + (i & 7) : "Row item B with text " + (i & 7));
            case "style" -> ((HtmlElement) t.styleTarget).setAttribute("style", (i & 1) == 0 ? "color:#112233" : "color:#223344");
            case "inherited" -> ((HtmlElement) t.bodyTarget).setAttribute("style", (i & 1) == 0 ? "color:#112233" : "color:#223344");
            case "fontsize" -> ((HtmlElement) t.bodyTarget).setAttribute("style", (i & 1) == 0 ? "font-size:16px" : "font-size:15px");
            case "child" -> {
                if ((i & 1) == 0) {
                    HtmlNode added = new HtmlElement("li");
                    added.updateTextContent("inserted " + i);
                    t.parent.insertBefore(added, t.anchor);
                    t.added = added;
                } else if (t.added != null) {
                    t.parent.removeChild(t.added);
                    t.added = null;
                }
            }
            // 切り分け用: child と同じだが、挿入する行の高さを整数（20px + 下枠 1px）に固定する。後続の行の移動量が整数になり、
            // 平行移動の近道（整数の移動だけ許す）が効くかどうかで、child の place の原因が「小数の移動量」かを確かめる
            case "childfixed" -> {
                if ((i & 1) == 0) {
                    HtmlElement added = new HtmlElement("li");
                    added.setAttribute("style", "height:20px;overflow:hidden");
                    added.updateTextContent("inserted " + i);
                    t.parent.insertBefore(added, t.anchor);
                    t.added = added;
                } else if (t.added != null) {
                    t.parent.removeChild(t.added);
                    t.added = null;
                }
            }
            case "relayout" -> v.ui().markInvalidation(UiView.InvalidationLevel.RELAYOUT);
            case "resize" -> v.ui().recomputeLayout(Workloads.VIEWPORT_W - (i & 1), Workloads.VIEWPORT_H);
            // 切り分け用: 幅を 2px ずつ変える。中央寄せの箱の移動量が 0.5px → 1px（整数）になり、表示項目の平行移動の再利用が効くかを確かめる
            case "resize2" -> v.ui().recomputeLayout(Workloads.VIEWPORT_W - 2 * (i & 1), Workloads.VIEWPORT_H);
            // ホストが実際に通る経路（StandaloneView.resize → requestResize → flushInvalidation の resize 分岐）。
            // recomputeLayout の直呼びと違い、表示項目の厳密な平行移動（exactTranslation）が有効になる
            case "resizeframe" -> v.resize(Workloads.VIEWPORT_W - (i & 1), Workloads.VIEWPORT_H);
            // 旧: 厳密な平行移動つきの resize 分岐の再現。平行移動の再利用を削除した（C1m）ので recomputeLayout と同じ。costGate の行名のために残す
            case "resizeexact" -> v.ui().recomputeLayout(Workloads.VIEWPORT_W - (i & 1), Workloads.VIEWPORT_H);
            case "open" -> {
                view[0] = newView(html);
                return view[0].frame(now[0] += 16, backend, true);
            }
            default -> throw new IllegalArgumentException("未知のシナリオ: " + scenario);
        }
        return v.frame(now[0] += 16, backend, false);
    }

    /** シナリオが触る要素。 */
    private static final class Target {
        HtmlText textTarget, buttonText;
        HtmlNode styleTarget, bodyTarget, parent, anchor, added;
        double[][] hover = {{10, 10}, {20, 20}};
        double[] scrollPoint = {50, 50};
        String[] hoverIds = {"", ""};

        static Target find(StandaloneView v, String doc) {
            Target t = new Target();
            boolean list = Workloads.isList(doc);
            HtmlNode row = list ? v.root().getNodeById("item-250") : null;
            if (list) {
                t.styleTarget = v.root().getNodeById("item-100");
                t.parent = row.parent;
                t.anchor = row;
                if (row.children.get(0) instanceof HtmlText tx) t.textTarget = tx;
            } else {
                HtmlNode button = firstOf(v.root(), "button");
                if (button == null) { // ボタンの無い文書（実文書の被覆率の測定用）: body の最初の子の要素で代える
                    HtmlNode bd = firstOf(v.root(), "body");
                    for (HtmlNode c : bd.children) if (c.nodeType == com.nishiyu.enderframelib.core.dom.node.NodeType.ELEMENT) { button = c; break; }
                    if (button == null) button = bd;
                }
                t.styleTarget = button;
                t.parent = button.parent;
                t.anchor = button;
                HtmlNode body = firstOf(v.root(), "body");
                t.textTarget = firstTextUnder(body, false);
                t.buttonText = firstTextUnder(button, true);
            }
            t.bodyTarget = firstOf(v.root(), "body");
            if (list) {
                t.hoverIds = new String[]{v.root().getNodeById("item-100").key(), v.root().getNodeById("item-101").key()};
            } else {
                HtmlNode b2 = secondOf(v.root(), "button");
                t.hoverIds = new String[]{t.styleTarget.key(), (b2 != null ? b2 : t.styleTarget).key()};
                if (b2 == null && doc.endsWith(".html")) {
                    // 実文書で button が 2 つ無いとき: class に btn を持つ要素を 2 つ使う（Chrome 側の trace.js と同じ決め方）
                    java.util.List<HtmlNode> btns = new java.util.ArrayList<>();
                    collectBtnClass(v.root(), btns);
                    if (btns.size() >= 2) t.hoverIds = new String[]{btns.get(0).key(), btns.get(1).key()};
                }
            }
            // ホバーの往復: :hover の規則が掛かる（interactiveState の）要素を 2 つ探して、その中心を行き来する
            int found = 0;
            for (DisplayItem it : v.ui().renderCache()) {
                if (it.meta() != null && it.meta().interactiveState() && it.width() > 4 && it.height() > 4 && found < 2) {
                    t.hover[found++] = new double[]{it.x() + it.width() / 2.0, it.y() + it.height() / 2.0};
                }
            }
            if (found < 2) t.hover = new double[][]{{40, 40}, {300, 300}};
            return t;
        }

        private static void collectBtnClass(HtmlNode n, java.util.List<HtmlNode> out) {
            if (out.size() >= 2) return;
            if (n instanceof HtmlElement && n.hasClass("btn")) out.add(n);
            for (HtmlNode c : n.children) collectBtnClass(c, out);
        }

        private static HtmlNode firstOf(HtmlNode n, String tag) {
            if (tag.equals(n.tag)) return n;
            for (HtmlNode c : n.children) {
                HtmlNode r = firstOf(c, tag);
                if (r != null) return r;
            }
            return null;
        }

        private static HtmlNode secondOf(HtmlNode n, String tag) {
            HtmlNode first = firstOf(n, tag);
            return first == null ? null : after(n, tag, first, new boolean[]{false});
        }

        private static HtmlNode after(HtmlNode n, String tag, HtmlNode first, boolean[] seen) {
            if (n == first) seen[0] = true;
            else if (seen[0] && tag.equals(n.tag)) return n;
            for (HtmlNode c : n.children) {
                HtmlNode r = after(c, tag, first, seen);
                if (r != null) return r;
            }
            return null;
        }

        /** body の下で最初の、空白だけでない文字。{@code insideButtonOnly} なら button の中の文字、でなければ button / label の外の文字。 */
        private static HtmlText firstTextUnder(HtmlNode n, boolean insideButtonOnly) {
            for (HtmlNode c : n.children) {
                if (c instanceof HtmlText tx && !tx.getData().isBlank()) {
                    boolean btn = n.tag != null && (n.tag.equals("button") || n.tag.equals("label"));
                    if (insideButtonOnly == btn || (insideButtonOnly && n.tag != null && n.tag.equals("button"))) return tx;
                    continue;
                }
                HtmlText r = firstTextUnder(c, insideButtonOnly);
                if (r != null) return r;
            }
            return null;
        }

        private static HtmlText firstText(HtmlNode n) {
            for (HtmlNode c : n.children) {
                if (c instanceof HtmlText tx && !tx.getData().isBlank()) return tx;
                HtmlText r = firstText(c);
                if (r != null) return r;
            }
            return null;
        }
    }
}
