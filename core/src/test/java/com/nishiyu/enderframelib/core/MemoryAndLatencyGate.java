package com.nishiyu.enderframelib.core;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.common.UiView;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.HtmlText;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.paint.RenderBackend;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * Engine v3 メモリ・レイテンシ回帰ゲート（計画書 §3.1 Phase M0）。
 *
 * <p>内部カウンタ（{@code memo*}）を一切参照せず、外から観測できる「保持ヒープ（MB）」と「交互 A/B 実行時間（ms）」のみを監視する。</p>
 * <ul>
 *   <li>{@code ./gradlew :core:memoryAndLatencyGate}: 全再計算・差分シナリオのヒープ保持量と交互 A/B 実行時間を検査する。</li>
 *   <li>{@code ./gradlew :core:memoryAndLatencyGate -Pgate.update}: 現在の実測値で基準値ファイルを更新する。</li>
 *   <li>{@code ./gradlew :core:memoryAndLatencyGate -Pgate.base=HEAD~1}: 指定コミットと作業ツリーの交互 A/B 比較を行う。</li>
 * </ul>
 */
public final class MemoryAndLatencyGate {
    private MemoryAndLatencyGate() { }

    static final Path BASELINE = Path.of("core/src/test/resources/memory-latency-baseline.txt");
    static final int VIEWPORT_W = 800;
    static final int VIEWPORT_H = 600;
    private static final int AB_ROUNDS = 3;
    private static final double DEFAULT_HEAP_SLACK_PCT = 0.25;
    private static final double DEFAULT_HEAP_SLACK_MB = 2.0;
    private static final double DEFAULT_AB_MAX_REGRESSION_PCT = 10.0;
    private static final List<ZipFile> HELD_JARS = new ArrayList<>();

    /** 全再計算・差分のヒープ保持シナリオ: {doc, scenario}。 */
    static final String[][] HEAP_RUNS = {
            {"showcase", "open"},
            {"voidwave.html", "open"},
            {"large-list-2000", "open"},
            {"varied-list-2000", "open"},
            {"large-list-2000", "child"},
            {"varied-list-2000", "hover"},
            {"showcase", "text"},
    };

    /** 増分 vs 全再計算の交互 A/B シナリオ: {doc, scenario, warmup, frames, maxIncToFullRatio}。 */
    static final String[][] AB_INC_VS_FULL_RUNS = {
            {"showcase", "hover", "200", "300", "0.90"},
            {"showcase", "text", "200", "300", "0.98"},
            {"large-list-2000", "child", "100", "200", "1.05"},
            {"varied-list-2000", "hover", "100", "200", "0.60"},
    };

    /** 単独の基準時間計測シナリオ（git base 指定時は交互 A/B 対象）: {doc, scenario, warmup, frames}。 */
    static final String[][] LATENCY_RUNS = {
            {"showcase", "open", "60", "100"},
            {"showcase", "resizeexact", "150", "300"},
            {"voidwave.html", "open", "40", "60"},
            {"varied-list-2000", "fontsize", "40", "80"},
            {"large-list-8000", "child", "40", "80"},
    };

    record BaselineEntry(double valueMbOrMs, double maxAllowed) {}

    private static final class NullBackend implements RenderBackend {
        @Override public void render(List<DisplayItem> items, long nowMs) { }
        @Override public Pixmap pixmap() { throw new UnsupportedOperationException(); }
        @Override public float devicePixelRatio() { return 1f; }
    }

    // ---- ワークロード生成（Workloads と同一構造） ----

    static String loadHtml(String doc) throws IOException {
        return switch (doc) {
            case "showcase" -> readFixture("showcase.html");
            case "voidwave.html" -> readFixture("voidwave.html");
            default -> {
                if (doc.startsWith("large-list-")) yield largeList(Integer.parseInt(doc.substring("large-list-".length())));
                if (doc.startsWith("varied-list-")) yield variedList(Integer.parseInt(doc.substring("varied-list-".length())));
                throw new IllegalArgumentException("未知の文書: " + doc);
            }
        };
    }

    private static String readFixture(String name) throws IOException {
        Path p = Path.of("mod/src/main/resources/data/enderframelib/enderframe_ui", name);
        return Files.readString(p, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    static String largeList(int count) {
        StringBuilder sb = new StringBuilder(count * 80 + 256);
        sb.append("<html><head><style>body { margin: 8px; } ul { list-style: none; padding: 0; } li { padding: 4px; border-bottom: 1px solid #ccc; }</style></head><body>");
        sb.append("<div id=\"scroller\" style=\"height: 400px; overflow-y: auto;\"><ul>");
        for (int i = 1; i <= count; i++) {
            sb.append("<li id=\"item-").append(i).append("\">Row item #").append(i).append(" with description text</li>");
        }
        return sb.append("</ul></div></body></html>").toString();
    }

    private static final String[] PALETTE = {"#fff", "#eef", "#fee", "#efe", "#ffe", "#eff", "#fef", "#dde", "#edd", "#ded", "#eed"};
    private static final String[] BORDER = {"#ccc", "#99c", "#c99", "#9c9", "#cc9", "#9cc", "#c9c", "#888"};
    private static final String LOREM = "alpha beta gamma delta epsilon zeta eta theta iota kappa ";

    static String variedList(int count) {
        StringBuilder sb = new StringBuilder(count * 240 + 256);
        sb.append("<html><head><style>body { margin: 8px; } ul { list-style: none; padding: 0; } li { border-bottom: 1px solid #ccc; } li:hover { color: #c00 !important; }</style></head><body>");
        sb.append("<div id=\"scroller\" style=\"height: 400px; overflow-y: auto;\"><ul>");
        for (int i = 1; i <= count; i++) {
            int pad = 2 + (i * 7) % 9, mb = i % 5, bl = (i * 3) % 5, fs = 80 + (i * 5) % 6 * 10;
            sb.append("<li id=\"item-").append(i).append("\" style=\"padding:").append(pad).append("px ").append(pad + (i % 4)).append("px;margin-bottom:")
                    .append(mb).append("px;background:").append(PALETTE[(i * 3) % PALETTE.length]).append(";border-left:").append(bl).append("px solid ")
                    .append(BORDER[i % BORDER.length]).append(";border-bottom:").append(1 + i % 3).append("px solid ").append(BORDER[(i * 5) % BORDER.length])
                    .append(";font-size:").append(fs).append("%").append(i % 6 == 0 ? ";color:#224" : "").append("\">");
            sb.append("Row item #").append(i).append(" ").append(LOREM.repeat(1 + (i * 13) % 6));
            if (i % 3 == 0) sb.append("<span style=\"background:").append(PALETTE[i % PALETTE.length]).append(";padding:1px ").append(2 + i % 5).append("px;border-radius:").append(i % 4).append("px\">tag").append(i % 7).append("</span>");
            if (i % 4 == 0) sb.append(" <b>bold ").append(i % 10).append("</b>");
            if (i % 7 == 0) sb.append("<div style=\"margin-top:").append(i % 5).append("px;padding-left:").append(4 + i % 11).append("px;color:#666;font-size:").append(70 + i % 4 * 10).append("%\">sub ").append(i).append(" ").append(LOREM.repeat(1 + i % 3)).append("</div>");
            sb.append("</li>");
        }
        return sb.append("</ul></div></body></html>").toString();
    }

    // ---- 子 JVM 計測（ヒープ保持量 & 実行時間） ----

    private static void holdClasspathJars() {
        for (String e : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (!e.endsWith(".jar")) continue;
            try { HELD_JARS.add(new ZipFile(e)); } catch (IOException ignored) { /* 読めない JAR は無視 */ }
        }
    }

    private static long usedBytesAfterGc() {
        for (int i = 0; i < 4; i++) {
            System.gc();
            try { Thread.sleep(60); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        Runtime r = Runtime.getRuntime();
        return r.totalMemory() - r.freeMemory();
    }

    static final class ScenarioTarget {
        HtmlText textTarget;
        HtmlNode bodyTarget;
        HtmlNode parent;
        HtmlNode anchor;
        HtmlNode added;
        String[] hoverIds = {"", ""};

        static ScenarioTarget find(StandaloneView v, String doc) {
            ScenarioTarget t = new ScenarioTarget();
            boolean isList = doc.startsWith("large-list-") || doc.startsWith("varied-list-");
            if (isList) {
                HtmlNode row = v.root().getNodeById("item-250");
                t.parent = row.parent;
                t.anchor = row;
                if (!row.children.isEmpty() && row.children.get(0) instanceof HtmlText tx) t.textTarget = tx;
                t.hoverIds = new String[]{v.root().getNodeById("item-100").key(), v.root().getNodeById("item-101").key()};
            } else {
                HtmlNode btn = firstTag(v.root(), "button");
                if (btn == null) {
                    HtmlNode bd = firstTag(v.root(), "body");
                    for (HtmlNode c : bd.children) if (c.nodeType == NodeType.ELEMENT) { btn = c; break; }
                    if (btn == null) btn = bd;
                }
                t.parent = btn.parent;
                t.anchor = btn;
                t.textTarget = firstNonBlankText(firstTag(v.root(), "body"));
                HtmlNode b2 = secondTag(v.root(), "button", btn);
                t.hoverIds = new String[]{btn.key(), (b2 != null ? b2 : btn).key()};
            }
            t.bodyTarget = firstTag(v.root(), "body");
            return t;
        }

        private static HtmlNode firstTag(HtmlNode n, String tag) {
            if (tag.equals(n.tag)) return n;
            for (HtmlNode c : n.children) {
                HtmlNode r = firstTag(c, tag);
                if (r != null) return r;
            }
            return null;
        }

        private static HtmlNode secondTag(HtmlNode n, String tag, HtmlNode first) {
            boolean[] seen = {false};
            return findAfter(n, tag, first, seen);
        }

        private static HtmlNode findAfter(HtmlNode n, String tag, HtmlNode first, boolean[] seen) {
            if (n == first) seen[0] = true;
            else if (seen[0] && tag.equals(n.tag)) return n;
            for (HtmlNode c : n.children) {
                HtmlNode r = findAfter(c, tag, first, seen);
                if (r != null) return r;
            }
            return null;
        }

        private static HtmlText firstNonBlankText(HtmlNode n) {
            if (n == null) return null;
            for (HtmlNode c : n.children) {
                if (c instanceof HtmlText tx && !tx.getData().isBlank()) return tx;
                HtmlText r = firstNonBlankText(c);
                if (r != null) return r;
            }
            return null;
        }
    }

    static boolean step(String scenario, StandaloneView[] view, RenderBackend backend,
                        ScenarioTarget t, long[] now, int i, String html, boolean forceFull) {
        StandaloneView v = view[0];
        switch (scenario) {
            case "hover" -> v.ui().setHoveredElement(t.hoverIds[i & 1]);
            case "text" -> t.textTarget.setData((i & 1) == 0 ? "Row item A text " + (i & 7) : "Row item B text " + (i & 7));
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
            case "resizeexact" -> v.ui().recomputeLayout(VIEWPORT_W - (i & 1), VIEWPORT_H);
            case "open" -> {
                view[0].close();
                view[0] = new StandaloneView(html, VIEWPORT_W, VIEWPORT_H, FontResolver.FALLBACK);
                return view[0].frame(now[0] += 16, backend, true);
            }
            default -> throw new IllegalArgumentException("未知のシナリオ: " + scenario);
        }
        if (forceFull) v.ui().markInvalidation(UiView.InvalidationLevel.RELAYOUT);
        return v.frame(now[0] += 16, backend, false);
    }

    private static void runChildHeap(String doc, String scenario) throws IOException {
        holdClasspathJars();
        try (StandaloneView warm = new StandaloneView(largeList(50), VIEWPORT_W, VIEWPORT_H, FontResolver.FALLBACK)) {
            warm.frame(1000, new NullBackend(), true);
        }
        long before = usedBytesAfterGc();
        String html = loadHtml(doc);
        StandaloneView[] view = {new StandaloneView(html, VIEWPORT_W, VIEWPORT_H, FontResolver.FALLBACK)};
        NullBackend backend = new NullBackend();
        long[] now = {1000};
        view[0].frame(now[0], backend, true);
        if (!scenario.equals("open")) {
            ScenarioTarget t = ScenarioTarget.find(view[0], doc);
            for (int i = 0; i < 100; i++) step(scenario, view, backend, t, now, i, html, false);
        } else {
            for (int i = 0; i < 4; i++) view[0].frame(now[0] += 16, backend, true);
        }
        long after = usedBytesAfterGc();
        double mb = Math.max(0.0, (after - before) / 1048576.0);
        if (view[0].root() == null) throw new IllegalStateException("root null");
        view[0].close();
        System.out.printf(Locale.ROOT, "HEAP scenario=%s:%s mb=%.3f%n", doc, scenario, mb);
    }

    private static void runChildLatency(String doc, String scenario, String mode, int warmup, int frames) throws IOException {
        String html = loadHtml(doc);
        boolean forceFull = mode.equals("full");
        NullBackend backend = new NullBackend();
        StandaloneView[] view = {new StandaloneView(html, VIEWPORT_W, VIEWPORT_H, FontResolver.FALLBACK)};
        long[] now = {1000};
        view[0].frame(now[0], backend, true);
        ScenarioTarget t = ScenarioTarget.find(view[0], doc);
        double[] ms = new double[frames];
        for (int i = -warmup; i < frames; i++) {
            long t0 = System.nanoTime();
            step(scenario, view, backend, t, now, i, html, forceFull);
            long t1 = System.nanoTime();
            if (i >= 0) ms[i] = (t1 - t0) / 1e6;
        }
        view[0].close();
        double sum = 0;
        for (double v : ms) sum += v;
        Arrays.sort(ms);
        System.out.printf(Locale.ROOT, "LATENCY scenario=%s:%s mode=%s meanMs=%.4f p50Ms=%.4f%n",
                doc, scenario, mode, sum / frames, ms[frames / 2]);
    }

    // ---- 統計・判定ヘルパ（単体テストからも利用） ----

    static double median(double[] values) {
        double[] s = values.clone();
        Arrays.sort(s);
        int n = s.length;
        return (n & 1) == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2.0;
    }

    static List<String> checkHeap(Map<String, Double> measuredMb, Map<String, BaselineEntry> baseline) {
        List<String> failures = new ArrayList<>();
        for (var e : measuredMb.entrySet()) {
            BaselineEntry b = baseline.get(e.getKey());
            if (b == null) {
                failures.add(e.getKey() + ": ヒープ基準値が無い（-Pgate.update で追加）");
                continue;
            }
            if (e.getValue() > b.maxAllowed()) {
                failures.add(String.format(Locale.ROOT, "%s: 保持ヒープ超過 %.2f MB > 上限 %.2f MB（基準 %.2f MB）",
                        e.getKey(), e.getValue(), b.maxAllowed(), b.valueMbOrMs()));
            }
        }
        return failures;
    }

    static List<String> checkAbComparison(String label, double[] baseRounds, double[] curRounds, double maxRatio) {
        double baseMed = median(baseRounds);
        double curMed = median(curRounds);
        double ratio = baseMed > 0 ? curMed / baseMed : 1.0;
        List<String> failures = new ArrayList<>();
        if (ratio > maxRatio) {
            failures.add(String.format(Locale.ROOT, "%s: 交互 A/B 比 %.3fx > 上限 %.3fx (base=%.4f ms, cur=%.4f ms)",
                    label, ratio, maxRatio, baseMed, curMed));
        }
        return failures;
    }

    static Map<String, BaselineEntry> readBaseline(Path file) throws IOException {
        Map<String, BaselineEntry> out = new LinkedHashMap<>();
        if (!Files.exists(file)) return out;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] p = line.split("\\|");
            if (p.length < 3) continue;
            out.put(p[0].trim(), new BaselineEntry(Double.parseDouble(p[1].trim()), Double.parseDouble(p[2].trim())));
        }
        return out;
    }

    // ---- 親プロセスによる子 JVM 起動 ----

    private static double spawnHeap(String cp, String doc, String scenario) throws IOException, InterruptedException {
        List<String> cmd = List.of(javaBin(), "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
                "-Denderframe.threads=1", "-Xmx1g", "-cp", cp, MemoryAndLatencyGate.class.getName(),
                "child", "heap", doc, scenario);
        String line = runChildAndFind(cmd, "HEAP ");
        for (String tok : line.split("\\s+")) {
            if (tok.startsWith("mb=")) return Double.parseDouble(tok.substring(3));
        }
        throw new IllegalStateException("HEAP 出力不正: " + line);
    }

    private static double spawnLatency(String cp, String doc, String scenario, String mode, int warmup, int frames)
            throws IOException, InterruptedException {
        List<String> cmd = List.of(javaBin(), "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
                "-Denderframe.threads=1", "-Xmx1g", "-cp", cp, MemoryAndLatencyGate.class.getName(),
                "child", "latency", doc, scenario, mode, String.valueOf(warmup), String.valueOf(frames));
        String line = runChildAndFind(cmd, "LATENCY ");
        for (String tok : line.split("\\s+")) {
            if (tok.startsWith("meanMs=")) return Double.parseDouble(tok.substring(7));
        }
        throw new IllegalStateException("LATENCY 出力不正: " + line);
    }

    private static String javaBin() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static String runChildAndFind(List<String> cmd, String prefix) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String found = null;
        StringBuilder tail = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                if (line.startsWith(prefix)) found = line;
                else if (tail.length() < 4000) tail.append(line).append('\n');
            }
        }
        int exit = p.waitFor();
        if (exit != 0 || found == null) {
            throw new IllegalStateException("子 JVM 失敗 (exit " + exit + "): " + cmd + "\n" + tail);
        }
        return found;
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("child")) {
            if (args[1].equals("heap")) runChildHeap(args[2], args[3]);
            else if (args[1].equals("latency")) runChildLatency(args[2], args[3], args[4],
                    Integer.parseInt(args[5]), Integer.parseInt(args[6]));
            else throw new IllegalArgumentException("未知の子モード: " + Arrays.toString(args));
            return;
        }

        boolean update = Boolean.getBoolean("gate.update");
        String only = System.getProperty("gate.only", "").trim();
        String baseRef = System.getProperty("gate.base", "").trim();
        String cp = System.getProperty("java.class.path");

        Map<String, Double> heapMeasured = new LinkedHashMap<>();
        for (String[] r : HEAP_RUNS) {
            String key = "heap:" + r[0] + ":" + r[1];
            if (!only.isEmpty() && !key.contains(only)) continue;
            double mb = spawnHeap(cp, r[0], r[1]);
            heapMeasured.put(key, mb);
            System.out.printf(Locale.ROOT, "GATE %-34s %.3f MB%n", key, mb);
        }

        Map<String, double[]> abBase = new LinkedHashMap<>();
        Map<String, double[]> abCur = new LinkedHashMap<>();
        Map<String, Double> abMaxRatio = new LinkedHashMap<>();
        for (String[] r : AB_INC_VS_FULL_RUNS) {
            String key = "ab-inc-vs-full:" + r[0] + ":" + r[1];
            if (!only.isEmpty() && !key.contains(only)) continue;
            int warm = Integer.parseInt(r[2]), frames = Integer.parseInt(r[3]);
            double[] fullRounds = new double[AB_ROUNDS], incRounds = new double[AB_ROUNDS];
            for (int round = 0; round < AB_ROUNDS; round++) {
                if ((round & 1) == 0) {
                    fullRounds[round] = spawnLatency(cp, r[0], r[1], "full", warm, frames);
                    incRounds[round] = spawnLatency(cp, r[0], r[1], "inc", warm, frames);
                } else {
                    incRounds[round] = spawnLatency(cp, r[0], r[1], "inc", warm, frames);
                    fullRounds[round] = spawnLatency(cp, r[0], r[1], "full", warm, frames);
                }
            }
            abBase.put(key, fullRounds);
            abCur.put(key, incRounds);
            abMaxRatio.put(key, Double.parseDouble(r[4]));
            System.out.printf(Locale.ROOT, "GATE %-34s inc=%.4f ms  full=%.4f ms (ratio=%.3fx)%n",
                    key, median(incRounds), median(fullRounds), median(incRounds) / median(fullRounds));
        }

        Map<String, Double> latMeasured = new LinkedHashMap<>();
        for (String[] r : LATENCY_RUNS) {
            String key = "latency:" + r[0] + ":" + r[1];
            if (!only.isEmpty() && !key.contains(only)) continue;
            int warm = Integer.parseInt(r[2]), frames = Integer.parseInt(r[3]);
            double[] rounds = new double[AB_ROUNDS];
            for (int round = 0; round < AB_ROUNDS; round++) {
                rounds[round] = spawnLatency(cp, r[0], r[1], "inc", warm, frames);
            }
            double med = median(rounds);
            latMeasured.put(key, med);
            System.out.printf(Locale.ROOT, "GATE %-34s median=%.4f ms%n", key, med);
        }

        if (!baseRef.isEmpty()) {
            runGitBaseAb(cp, baseRef, only, abCur);
        }

        if (update) {
            if (!only.isEmpty()) throw new IllegalArgumentException("gate.only と gate.update は併用不可");
            StringBuilder sb = new StringBuilder();
            sb.append("# MemoryAndLatencyGate 基準値（キー | 基準値 | 上限）。./gradlew :core:memoryAndLatencyGate -Pgate.update で更新。\n");
            sb.append("# 内部カウンタ（memo*）不使用。ヒープは MB、交互 A/B は inc/full 比または ms。\n");
            for (var e : heapMeasured.entrySet()) {
                double ceil = Math.max(e.getValue() * (1.0 + DEFAULT_HEAP_SLACK_PCT) + DEFAULT_HEAP_SLACK_MB, e.getValue() + 2.0);
                sb.append(String.format(Locale.ROOT, "%s | %.3f | %.3f%n", e.getKey(), e.getValue(), ceil));
            }
            for (var e : abCur.entrySet()) {
                double ratio = median(e.getValue()) / median(abBase.get(e.getKey()));
                sb.append(String.format(Locale.ROOT, "%s | %.4f | %.4f%n", e.getKey(), ratio, abMaxRatio.get(e.getKey())));
            }
            for (var e : latMeasured.entrySet()) {
                sb.append(String.format(Locale.ROOT, "%s | %.4f | %.4f%n", e.getKey(), e.getValue(), e.getValue() * 2.0 + 3.0));
            }
            Files.writeString(BASELINE, sb.toString(), StandardCharsets.UTF_8);
            System.out.println("MEMORY_AND_LATENCY_GATE baseline updated: " + BASELINE);
            return;
        }

        Map<String, BaselineEntry> baseline = readBaseline(BASELINE);
        List<String> failures = new ArrayList<>(checkHeap(heapMeasured, baseline));
        for (var e : abCur.entrySet()) {
            BaselineEntry b = baseline.get(e.getKey());
            double limit = b != null ? b.maxAllowed() : abMaxRatio.get(e.getKey());
            failures.addAll(checkAbComparison(e.getKey(), abBase.get(e.getKey()), e.getValue(), limit));
        }
        if (Boolean.getBoolean("gate.enforceTiming")) {
            for (var e : latMeasured.entrySet()) {
                BaselineEntry b = baseline.get(e.getKey());
                if (b != null && e.getValue() > b.maxAllowed()) {
                    failures.add(String.format(Locale.ROOT, "%s: レイテンシ超過 %.4f ms > 上限 %.4f ms",
                            e.getKey(), e.getValue(), b.maxAllowed()));
                }
            }
        }
        if (failures.isEmpty()) {
            System.out.println("MEMORY_AND_LATENCY_GATE OK");
            return;
        }
        System.out.println("MEMORY_AND_LATENCY_GATE FAILED (" + failures.size() + ")");
        for (String f : failures) System.out.println("  " + f);
        System.exit(1);
    }

    private static void runGitBaseAb(String curCp, String baseRef, String only, Map<String, double[]> curCache)
            throws IOException, InterruptedException {
        Path worktree = Files.createTempDirectory("efl-gate-ab-");
        Files.deleteIfExists(worktree);
        try {
            runChecked(List.of("git", "worktree", "add", "--detach", worktree.toString(), baseRef), Path.of("."));
            String gradlew = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "gradlew.bat" : "./gradlew";
            runChecked(List.of(worktree.resolve(gradlew).toString(), ":core:testClasses", ":core:profileClasspath", "-q"), worktree);
            String baseCp = String.join(File.pathSeparator,
                    Files.readAllLines(worktree.resolve("core/build/profile-classpath.txt"), StandardCharsets.UTF_8))
                    + File.pathSeparator + worktree.resolve("core/build/classes/java/test");
            double maxRatio = 1.0 + DEFAULT_AB_MAX_REGRESSION_PCT / 100.0;
            for (String[] r : LATENCY_RUNS) {
                String key = "git-ab:" + r[0] + ":" + r[1];
                if (!only.isEmpty() && !key.contains(only)) continue;
                int warm = Integer.parseInt(r[2]), frames = Integer.parseInt(r[3]);
                double[] baseRounds = new double[AB_ROUNDS], curRounds = new double[AB_ROUNDS];
                for (int round = 0; round < AB_ROUNDS; round++) {
                    if ((round & 1) == 0) {
                        baseRounds[round] = spawnLatency(baseCp, r[0], r[1], "inc", warm, frames);
                        curRounds[round] = spawnLatency(curCp, r[0], r[1], "inc", warm, frames);
                    } else {
                        curRounds[round] = spawnLatency(curCp, r[0], r[1], "inc", warm, frames);
                        baseRounds[round] = spawnLatency(baseCp, r[0], r[1], "inc", warm, frames);
                    }
                }
                List<String> bad = checkAbComparison(key, baseRounds, curRounds, maxRatio);
                System.out.printf(Locale.ROOT, "GIT-AB %-32s base=%.4f ms  cur=%.4f ms%n",
                        key, median(baseRounds), median(curRounds));
                if (!bad.isEmpty()) throw new IllegalStateException(String.join("\n", bad));
            }
        } finally {
            try { runChecked(List.of("git", "worktree", "remove", "--force", worktree.toString()), Path.of(".")); }
            catch (Exception ignored) { /* ベストエフォート削除 */ }
        }
    }

    private static void runChecked(List<String> cmd, Path dir) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new IllegalStateException("コマンド失敗: " + cmd + "\n" + out);
    }
}
