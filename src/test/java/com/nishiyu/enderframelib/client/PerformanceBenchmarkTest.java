package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.client.renderer.DisplayListBuilder;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.GoldenFiles;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.dom.parser.MarkdownParser;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.RenderPipeline;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;
import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;
import com.nishiyu.enderframelib.core.layout.LayoutContext;
import com.nishiyu.enderframelib.core.layout.model.Viewport;
import com.nishiyu.enderframelib.core.layout.model.FormattingContext;
import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.css.model.Overflow;
import com.nishiyu.enderframelib.core.css.model.TextAlign;
import com.nishiyu.enderframelib.core.css.model.BorderStyle;

/**
 * 代表文書（showcase.html, guidebook.md, 大きなリスト）を対象とした性能ベンチマークテスト。
 *
 * <p>再スタイル・再レイアウト・ディスプレイリスト生成の時間、およびスクロール 1 段の時間を計測し、
 * R1（ライブ DOM）や R5（インクリメンタル・パイプライン）での前後比較の基準値として記録する。
 *
 * <p>基準値は {@code src/test/resources/benchmark/baseline.txt} にコミットしてある。
 * 測定値が「基準値 × factor + slack」を超えたら回帰として落ちる。環境差で恒久的に
 * 遅い/速い場合は {@code -Dbenchmark.update=true} で基準値を更新する（差分は必ず目視する）。
 */
public class PerformanceBenchmarkTest {

    private static final int VIEWPORT_W = 800;
    private static final int VIEWPORT_H = 600;
    private static final int WARMUP_ITERATIONS = 5;
    private static final int MEASURE_ITERATIONS = 15;

    private static final Path BASELINE_PATH = Path.of("src/test/resources/benchmark/baseline.txt");
    private static final double DEFAULT_FACTOR = 2.0;
    private static final double DEFAULT_SLACK_MS = 3.0;

    public record BenchmarkResult(
            String documentName,
            double restyleMs,
            double relayoutMs,
            double displayListMs,
            double scrollStepMs
    ) {}

    /** baseline.txt の 1 文書分の基準値と、比較に使う許容幅。 */
    public record Baseline(
            Map<String, BenchmarkResult> documents,
            double factor,
            double slackMs
    ) {
        BenchmarkResult document(String name) {
            return documents.get(name);
        }
    }

    @Test
    @DisplayName("Performance Benchmark: showcase.html, guidebook.md, and large list")
    public void runBenchmarks() throws IOException {
        String showcaseHtml = GoldenFiles.readFixture("showcase.html");
        String guidebookHtml = MarkdownParser.toHtml(GoldenFiles.readFixture("guidebook.md"));
        String largeListHtml = generateLargeListHtml(500);

        List<BenchmarkResult> results = new ArrayList<>();
        results.add(measureDocument("showcase.html", showcaseHtml));
        results.add(measureDocument("guidebook.md", guidebookHtml));
        results.add(measureDocument("large-list (500 items)", largeListHtml));

        if (Boolean.getBoolean("benchmark.update")) {
            writeBaseline(results);
            String report = formatReport(results, null);
            System.out.println(report);
            writeReport(report);
            return;
        }

        Baseline baseline = readBaseline();
        List<String> regressions = new ArrayList<>();
        for (BenchmarkResult r : results) {
            BenchmarkResult reference = baseline.document(r.documentName());
            if (reference == null) {
                regressions.add(r.documentName() + ": baseline.txt に基準値が無い");
                continue;
            }
            checkMetric(regressions, baseline, r, "restyle", reference.restyleMs(), r.restyleMs());
            checkMetric(regressions, baseline, r, "relayout", reference.relayoutMs(), r.relayoutMs());
            checkMetric(regressions, baseline, r, "displayList", reference.displayListMs(), r.displayListMs());
            checkMetric(regressions, baseline, r, "scrollStep", reference.scrollStepMs(), r.scrollStepMs());
        }

        // 合否に関わらず、比較できる形のレポートを残す
        String report = formatReport(results, baseline);
        System.out.println(report);
        writeReport(report);

        if (!regressions.isEmpty()) {
            fail("ベンチマークが基準値から後退しています（許容: 基準値 × " + baseline.factor()
                    + " + " + baseline.slackMs() + "ms）:\n  "
                    + String.join("\n  ", regressions)
                    + "\n意図した変更なら `-Dbenchmark.update=true` で "
                    + BASELINE_PATH + " を更新し、差分を目視してコミットする。");
        }
    }

    /** 基準値ファイル自体が読めること・全項目が正の値であることを確かめる。 */
    @Test
    public void testBaselineIsWellFormed() throws IOException {
        Baseline baseline = readBaseline();
        assertTrue(baseline.factor() > 0, "baseline.factor は正の値であること");
        assertTrue(baseline.slackMs() >= 0, "baseline.slackMs は 0 以上であること");
        assertTrue(baseline.documents().size() >= 3,
                "baseline.txt には代表文書（showcase / guidebook / large-list）の基準値が必要");
        baseline.documents().forEach((name, r) -> {
            assertFalse(name.isBlank(), "文書名が空の基準値行がある");
            assertTrue(r.restyleMs() >= 0 && r.relayoutMs() >= 0
                            && r.displayListMs() >= 0 && r.scrollStepMs() >= 0,
                    name + " の基準値に負の値がある");
        });
    }

    private static void checkMetric(List<String> regressions, Baseline baseline, BenchmarkResult r,
                                    String metric, double reference, double actual) {
        double allowed = reference * baseline.factor() + baseline.slackMs();
        if (actual > allowed) {
            regressions.add(String.format(Locale.ROOT,
                    "%s %s: 基準 %.3fms / 実測 %.3fms（許容 %.3fms）",
                    r.documentName(), metric, reference, actual, allowed));
        }
    }

    /** baseline.txt を読む。形式はファイル冒頭のコメントを参照。 */
    static Baseline readBaseline() throws IOException {
        double factor = DEFAULT_FACTOR;
        double slackMs = DEFAULT_SLACK_MS;
        Map<String, BenchmarkResult> documents = new LinkedHashMap<>();

        for (String line : Files.readAllLines(BASELINE_PATH, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            if (trimmed.startsWith("baseline.factor=")) {
                factor = Double.parseDouble(trimmed.substring("baseline.factor=".length()).trim());
                continue;
            }
            if (trimmed.startsWith("baseline.slackMs=")) {
                slackMs = Double.parseDouble(trimmed.substring("baseline.slackMs=".length()).trim());
                continue;
            }
            String[] parts = line.split("\t", -1);
            if (parts.length != 5) {
                throw new IllegalStateException("baseline.txt の列数が 5 ではない: " + line);
            }
            documents.put(parts[0], new BenchmarkResult(
                    parts[0],
                    Double.parseDouble(parts[1].trim()),
                    Double.parseDouble(parts[2].trim()),
                    Double.parseDouble(parts[3].trim()),
                    Double.parseDouble(parts[4].trim())));
        }
        // 共有 CI など一時的に遅い環境では、ファイルを書き換えずに許容幅だけ広げられる
        factor = Double.parseDouble(System.getProperty("benchmark.factor", Double.toString(factor)));
        slackMs = Double.parseDouble(System.getProperty("benchmark.slackMs", Double.toString(slackMs)));
        return new Baseline(documents, factor, slackMs);
    }

    /** {@code -Dbenchmark.update=true} のときだけ呼ばれる。基準値を実測で置き換える。 */
    private static void writeBaseline(List<BenchmarkResult> results) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# EnderFrameLib R0 performance baseline\n");
        sb.append("#\n");
        sb.append("# 形式: <document>\\t<restyleMs>\\t<relayoutMs>\\t<displayListMs>\\t<scrollStepMs>\n");
        sb.append("# 単位はミリ秒。PerformanceBenchmarkTest が同じ文書を測り、この値と比べる。\n");
        sb.append("# 一致判定は「実測 <= 基準値 * factor + slackMs」。既定は factor=2.0 / slackMs=3.0。\n");
        sb.append("#\n");
        sb.append("# マシンが変わって恒久的に遅い/速い場合は、次のコマンドで更新して差分を確認する:\n");
        sb.append("#   ./gradlew test --tests '*PerformanceBenchmarkTest' -Dbenchmark.update=true\n");
        sb.append("# 一時的な環境差は -Dbenchmark.factor=... / -Dbenchmark.slackMs=... で緩められる。\n");
        sb.append("#\n");
        sb.append("# ベンチは「R1 / R5 の前後で同じ文書がどう変わったか」を見るためのもの。\n");
        sb.append("# 500ms のゆるい上限だけでは、2 倍遅くなっても通ってしまうためこのファイルを持つ。\n");
        sb.append("baseline.factor=").append(DEFAULT_FACTOR).append('\n');
        sb.append("baseline.slackMs=").append(DEFAULT_SLACK_MS).append('\n');
        for (BenchmarkResult r : results) {
            sb.append(r.documentName()).append('\t')
                    .append(String.format(Locale.ROOT, "%.1f", r.restyleMs())).append('\t')
                    .append(String.format(Locale.ROOT, "%.1f", r.relayoutMs())).append('\t')
                    .append(String.format(Locale.ROOT, "%.1f", r.displayListMs())).append('\t')
                    .append(String.format(Locale.ROOT, "%.1f", r.scrollStepMs())).append('\n');
        }
        Files.writeString(BASELINE_PATH, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("Updated benchmark baseline: " + BASELINE_PATH.toAbsolutePath());
    }

    private static void writeReport(String report) throws IOException {
        Path outReport = Path.of("build/reports/benchmark-results.txt");
        Files.createDirectories(outReport.getParent());
        Files.writeString(outReport, report, StandardCharsets.UTF_8);
    }

    private BenchmarkResult measureDocument(String name, String html) {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument(html, "", VIEWPORT_W, VIEWPORT_H);
        // スクロール 1 段の対象。この文書にスクロールコンテナ（#scroller）が無ければ、スクロールは何も動かさない
        HtmlNode scroller = doc.root.getElementById("scroller");

        // ウォームアップ
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            InteractionState warmupState = new InteractionState();
            LayoutBox root = parser.layoutDocument(doc, VIEWPORT_W, VIEWPORT_H, Map.of(), new ScrollState(), FontResolver.FALLBACK, warmupState);
            parser.restyleLayoutTree(root, doc, VIEWPORT_W, VIEWPORT_H, Map.of(), new InteractionState());
            parser.runLayoutPasses(root, VIEWPORT_W, VIEWPORT_H, new ScrollState(), FontResolver.FALLBACK);
            DisplayListBuilder renderer = new DisplayListBuilder(VIEWPORT_W, VIEWPORT_H, doc.sheet.keyframes, new ScrollState());
            renderer.render(root);
            ScrollState scroll = new ScrollState();
            scroll.setScroll(scroller, 0, 20);
            parser.runLayoutPasses(root, VIEWPORT_W, VIEWPORT_H, scroll, FontResolver.FALLBACK);
            renderer = new DisplayListBuilder(VIEWPORT_W, VIEWPORT_H, doc.sheet.keyframes, scroll);
            renderer.render(root);
        }

        InteractionState baseState = new InteractionState();
        LayoutBox root = parser.layoutDocument(doc, VIEWPORT_W, VIEWPORT_H, Map.of(), new ScrollState(), FontResolver.FALLBACK, baseState);

        // 計測
        long totalRestyleNs = 0;
        long totalRelayoutNs = 0;
        long totalDisplayListNs = 0;
        long totalScrollNs = 0;

        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            // 1. 再スタイル
            InteractionState iterState = new InteractionState();
            long t0 = System.nanoTime();
            parser.restyleLayoutTree(root, doc, VIEWPORT_W, VIEWPORT_H, Map.of(), iterState);
            long t1 = System.nanoTime();
            totalRestyleNs += (t1 - t0);

            // 2. 再レイアウト
            long t2 = System.nanoTime();
            parser.runLayoutPasses(root, VIEWPORT_W, VIEWPORT_H, new ScrollState(), FontResolver.FALLBACK);
            long t3 = System.nanoTime();
            totalRelayoutNs += (t3 - t2);

            // 3. ディスプレイリスト生成
            long t4 = System.nanoTime();
            DisplayListBuilder renderer = new DisplayListBuilder(VIEWPORT_W, VIEWPORT_H, doc.sheet.keyframes, new ScrollState());
            List<DisplayItem> elements = renderer.render(root);
            long t5 = System.nanoTime();
            totalDisplayListNs += (t5 - t4);
            assertFalse(elements.isEmpty());

            // 4. スクロール 1 段
            ScrollState scroll = new ScrollState();
            scroll.setScroll(scroller, 0, 20);
            long t6 = System.nanoTime();
            parser.runLayoutPasses(root, VIEWPORT_W, VIEWPORT_H, scroll, FontResolver.FALLBACK);
            DisplayListBuilder scrollRenderer = new DisplayListBuilder(VIEWPORT_W, VIEWPORT_H, doc.sheet.keyframes, scroll);
            scrollRenderer.render(root);
            long t7 = System.nanoTime();
            totalScrollNs += (t7 - t6);
        }

        double toMs = 1.0 / (MEASURE_ITERATIONS * 1_000_000.0);
        return new BenchmarkResult(
                name,
                totalRestyleNs * toMs,
                totalRelayoutNs * toMs,
                totalDisplayListNs * toMs,
                totalScrollNs * toMs
        );
    }

    private static String generateLargeListHtml(int count) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><head><style>body { margin: 8px; } ul { list-style: none; padding: 0; } li { padding: 4px; border-bottom: 1px solid #ccc; }</style></head><body>");
        sb.append("<div id=\"scroller\" style=\"height: 400px; overflow-y: auto;\"><ul>");
        for (int i = 1; i <= count; i++) {
            sb.append("<li id=\"item-").append(i).append("\">Row item #").append(i).append(" with description text</li>");
        }
        sb.append("</ul></div></body></html>");
        return sb.toString();
    }

    private static String formatReport(List<BenchmarkResult> results, Baseline baseline) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n========================================================================================\n");
        sb.append("                       EnderFrameLib R0 Performance Baseline\n");
        sb.append("========================================================================================\n");
        if (baseline != null) {
            sb.append(String.format(Locale.ROOT,
                    "許容幅: 基準値 × %.2f + %.1fms%n", baseline.factor(), baseline.slackMs()));
        }
        sb.append(String.format(Locale.ROOT, "%-26s | %11s | %11s | %13s | %12s\n",
                "Document", "Restyle", "Relayout", "DisplayList", "ScrollStep"));
        sb.append("----------------------------------------------------------------------------------------\n");
        for (BenchmarkResult r : results) {
            sb.append(String.format(Locale.ROOT, "%-26s | %9.3f ms | %9.3f ms | %11.3f ms | %10.3f ms",
                    r.documentName(), r.restyleMs(), r.relayoutMs(), r.displayListMs(), r.scrollStepMs()));
            BenchmarkResult reference = baseline != null ? baseline.document(r.documentName()) : null;
            if (reference != null) {
                sb.append(String.format(Locale.ROOT, "  (基準 %.1f / %.1f / %.1f / %.1f)",
                        reference.restyleMs(), reference.relayoutMs(),
                        reference.displayListMs(), reference.scrollStepMs()));
            }
            sb.append('\n');
        }
        sb.append("========================================================================================\n");
        return sb.toString();
    }
}