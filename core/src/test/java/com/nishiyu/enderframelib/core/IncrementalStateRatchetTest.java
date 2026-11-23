package com.nishiyu.enderframelib.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.core.kernel.Kernel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 増分エンジン（記憶・無効化・再利用）の「状態の予算」のラチェット（計画書 運用ルール 8〜10・設計書 {@code DESIGN_INCREMENTAL_KERNEL.md} §2 K1・K4・K5・K6、計画書 K0 ②④）。
 *
 * <p>「近道（フラグ）を足す → 費用を削る → 不具合が出る → フラグや検証を足す」のループを、文書の注意ではなくテストで止める。
 * 次は<b>増やすと赤</b>になる（減らすのは歓迎。減らしたらこの表を現状へ下げる）。</p>
 * <ol>
 *   <li>記憶の状態フラグ（{@code F_* = 1 << n}）の数・箱ごとの記憶のフィールド数（付帯欄）: 新しいフラグ・付帯欄を足さない。足したくなったら、鍵（読み取り集合）・台帳・オラクルの不足なので K 系へ寄せる。</li>
 *   <li>保持ごとの検証クラス（{@code *Verifier}）: 新しい検証クラスを足さない。検証は鍵の汎用の照合とフレームのオラクルへ統合する（K6）。</li>
 *   <li>実時計（{@code System.nanoTime}・{@code currentTimeMillis}）を読むファイル: {@code core/kernel/Clock.java} だけ（K0 ①）。時刻は注入する（K5）。</li>
 *   <li>計器: {@code LongAdder} の計器・段の中の {@code Kernel.stats} の呼び出し箇所を増やさない。1 箱ごとの計器（実体のカウンタ）は禁止（K0 ②・K4）。</li>
 *   <li>テストの実時間の {@code Thread.sleep}: 新しく足さない（K0 ①）。</li>
 * </ol>
 */
class IncrementalStateRatchetTest {

    private static final String RULE = "（計画書 ENGINE_REARCHITECTURE_PLAN.md の運用ルール 8〜10、設計書 DESIGN_INCREMENTAL_KERNEL.md §2・§7 を先に読む。"
            + "フラグ・検証クラス・実時計・計器を足す前に、鍵・台帳・オラクル〔K 系〕で解けないかを検討する。減らしたらこの表を現状へ下げる）";

    /** 記憶の状態フラグ {@code F_* = 1 << n} をファイルごとに数えた現状。新しいファイルが現れたら（0 → 1）失敗。 */
    private static final Map<String, Integer> MAX_FLAGS = Map.of(
            "core/layout/MeasureMemo.java", 17,
            "core/layout/PlacementMemo.java", 2);

    /** 保持ごとの検証クラス（これ以上増やさない。K6 で 1 つへ統合して減らす）。 */
    private static final Set<String> ALLOWED_VERIFIERS = Set.of(
            "core/layout/MeasureVerifier.java",
            "core/paint/OwnItemsVerifier.java",
            "core/paint/PoseVerifier.java");

    /**
     * 実時計（{@code System.nanoTime}・{@code currentTimeMillis}）を読んでよいファイル（K0 ①: 17 → 1）。{@code core/kernel/Clock.java} の {@code Clock.SYSTEM} だけ。
     * 時刻に依る振る舞いは注入された {@code Clock} を通す（テストは {@code Clock.Manual}）。
     */
    private static final Set<String> ALLOWED_WALL_CLOCK = Set.of("core/kernel/Clock.java");

    /** 状態の予算: 箱ごとの記憶の実体のフィールド数（付帯欄を含む。K0 ④）。増やすには同数を消す。 */
    private static final Map<String, Integer> MAX_MEMO_FIELDS = Map.of(
            "core/layout/PlacementMemo.java", 13,
            "core/layout/MeasureMemo.java", 41);

    /** {@code new LongAdder()} の数（static の診断用の計数）をファイルごとに。増やさない。計器は {@code Kernel.stats} の入口へ（K0 ②）。 */
    private static final Map<String, Integer> MAX_STATIC_ADDERS = Map.ofEntries(
            Map.entry("common/UiView.java", 5), Map.entry("core/css/ComputedStyle.java", 4), Map.entry("core/kernel/Kernel.java", 5),
            Map.entry("core/layout/BlockLayoutEngine.java", 1), Map.entry("core/layout/MeasureMemo.java", 3),
            Map.entry("core/layout/PlacementMemo.java", 2),
            Map.entry("core/paint/AbsoluteItems.java", 1), Map.entry("core/paint/DisplayListBuilder.java", 1),
            Map.entry("core/paint/FragmentPaint.java", 3), Map.entry("core/paint/PaintOrder.java", 1),
            Map.entry("core/paint/gpu/RectStream.java", 1), Map.entry("core/paint/port/GpuCoverage.java", 1),
            Map.entry("core/paint/software/SoftwareRenderBackend.java", 1));

    /** 段の中の {@code Kernel.stats.…} の呼び出し箇所（{@code Kernel.java} の外）。増やさない（K0 ②）。 */
    private static final int MAX_KERNEL_STATS_CALL_SITES = 5;

    /** 実体（非 static）の {@code LongAdder}・{@code AtomicLong} を持ってよいファイル。箱ごとの計器は禁止で、ここは箱ごとではない集約だけ。 */
    private static final Set<String> ALLOWED_INSTANCE_COUNTERS = Set.of("core/kernel/Clock.java", "core/kernel/Kernel.java", "core/text/font/AtlasFootprint.java");

    /** テストで実時間を待つ {@code Thread.sleep} を持ってよいファイル（K0 ①: リサイズの間引きを待っていた 3 本を除いた。新しく足さない）。 */
    private static final Set<String> ALLOWED_TEST_SLEEP = Set.of(
            "api/SurfaceLifecycleTest.java", "api/ui/NoHtmlSiteTest.java", "common/LocalUpdateTest.java", "common/ScrollCostTest.java",
            "common/ScrollTranslateTest.java", "core/css/CssValueTest.java", "core/diag/StageProfilerTest.java",
            "core/kernel/memory/ArenaSlotsLeakTest.java", "core/paint/gl/GoldenFrameTest.java", "core/paint/HitTestFrameClockTest.java",
            "core/paint/resource/VideoWindowTest.java", "core/paint/software/ScalingBenchmarkTest.java",
            "core/paint/software/StandaloneViewTest.java", "core/scene3d/GltfBinaryLoaderTest.java",
            "core/text/font/FontLifecycleTest.java", "core/text/font/FontSourcesTest.java", "core/text/P8bRetentionTest.java");

    private static final Pattern FLAG = Pattern.compile("\\bF_[A-Z0-9_]+\\s*=\\s*1\\s*<<\\s*\\d+");
    private static final Pattern WALL_CLOCK = Pattern.compile("System\\.(nanoTime|currentTimeMillis)\\(");
    private static final Pattern MEMO_FIELD = Pattern.compile("(?m)^    private (?!static\\b)(?:final |volatile )*[\\w.<>\\[\\]]+ ([^;(]*?);");
    private static final Pattern STATIC_ADDER = Pattern.compile("new\\s+(?:java\\.util\\.concurrent\\.atomic\\.)?(?:LongAdder|DoubleAdder)\\s*\\(");
    private static final Pattern INSTANCE_COUNTER = Pattern.compile(
            "(?m)^\\s+(?![^\\n]*\\bstatic\\b)(?:private |public |protected |final |volatile )*(?:java\\.util\\.concurrent\\.atomic\\.)?(?:LongAdder|AtomicLong)\\s+\\w+");
    private static final Pattern KERNEL_STATS_CALL = Pattern.compile("Kernel\\.stats\\.");
    private static final Pattern SLEEP = Pattern.compile("Thread\\.sleep\\(|TimeUnit\\.[A-Z]+\\.sleep\\(");

    private static Path root() {
        Path p = Path.of("core/src/main/java/com/nishiyu/enderframelib");
        return Files.isDirectory(p) ? p : Path.of("src/main/java/com/nishiyu/enderframelib");
    }

    private static List<Path> sources() throws IOException {
        return RatchetSupport.javaFiles(root());
    }

    @Test
    void memoStateFlagsOnlyShrink() throws IOException {
        Map<String, Integer> found = new TreeMap<>();
        for (Path p : sources()) {
            Matcher m = FLAG.matcher(Files.readString(p, StandardCharsets.UTF_8));
            int n = 0;
            while (m.find()) n++;
            if (n > 0) found.put(RatchetSupport.rel(root(), p), n);
        }
        assertTrue(found.getOrDefault("core/layout/PlacementMemo.java", 0) >= 2 && found.getOrDefault("core/layout/MeasureMemo.java", 0) >= 10,
                "検出器が壊れている（フラグが見つからない）: " + found);
        List<String> bad = new ArrayList<>();
        for (var e : found.entrySet()) {
            int max = MAX_FLAGS.getOrDefault(e.getKey(), 0);
            if (e.getValue() > max) bad.add(e.getKey() + ": 記憶の状態フラグが増えた " + e.getValue() + " > " + max);
        }
        assertEquals(List.of(), bad, "記憶の状態フラグを足さない " + RULE);
    }

    @Test
    void memoStateFieldsOnlyShrink() throws IOException {
        Map<String, Integer> found = new TreeMap<>();
        for (String rel : MAX_MEMO_FIELDS.keySet()) {
            Matcher m = MEMO_FIELD.matcher(Files.readString(root().resolve(rel), StandardCharsets.UTF_8));
            int n = 0;
            while (m.find()) n += 1 + (int) m.group(1).chars().filter(c -> c == ',').count();
            found.put(rel, n);
        }
        assertTrue(found.get("core/layout/PlacementMemo.java") >= 10 && found.get("core/layout/MeasureMemo.java") >= 30,
                "検出器が壊れている（記憶のフィールドが見つからない）: " + found);
        List<String> bad = new ArrayList<>();
        for (var e : found.entrySet()) {
            int max = MAX_MEMO_FIELDS.get(e.getKey());
            if (e.getValue() > max) bad.add(e.getKey() + ": 箱ごとの記憶のフィールド（付帯欄）が増えた " + e.getValue() + " > " + max);
        }
        assertEquals(List.of(), bad, "箱ごとの記憶に付帯欄を足さない（足すなら同数を消す。鍵〔読み取り集合〕へ寄せる） " + RULE);
    }

    @Test
    void verifierClassesDoNotGrow() throws IOException {
        Set<String> found = new TreeSet<>();
        for (Path p : sources()) if (p.getFileName().toString().endsWith("Verifier.java")) found.add(RatchetSupport.rel(root(), p));
        assertTrue(found.size() >= 3, "検出器が壊れている（検証クラスが見つからない）: " + found);
        Set<String> extra = new TreeSet<>(found);
        extra.removeAll(ALLOWED_VERIFIERS);
        assertEquals(Set.of(), extra, "新しい検証クラスを足さない（検証は K6 で鍵の汎用の照合とオラクルへ統合する） " + RULE);
    }

    @Test
    void wallClockIsNotReadOutsideClock() throws IOException {
        Set<String> found = new TreeSet<>();
        for (Path p : sources()) {
            if (WALL_CLOCK.matcher(Files.readString(p, StandardCharsets.UTF_8)).find()) found.add(RatchetSupport.rel(root(), p));
        }
        assertTrue(found.contains("core/kernel/Clock.java"), "検出器が壊れている（Clock.SYSTEM の実時計の読みが見つからない）: " + found);
        Set<String> extra = new TreeSet<>(found);
        extra.removeAll(ALLOWED_WALL_CLOCK);
        assertEquals(Set.of(), extra, "コアは実時計を直接読まない（注入された Clock を通す。K0 ①・K5） " + RULE);
    }

    @Test
    void staticAddersAreNotAddedOutsideTheKernelEntry() throws IOException {
        Map<String, Integer> found = new TreeMap<>();
        for (Path p : sources()) {
            Matcher m = STATIC_ADDER.matcher(Files.readString(p, StandardCharsets.UTF_8));
            int n = 0;
            while (m.find()) n++;
            if (n > 0) found.put(RatchetSupport.rel(root(), p), n);
        }
        assertEquals(5, found.getOrDefault("core/kernel/Kernel.java", 0), "検出器が壊れている（Kernel の計器が見つからない）: " + found);
        List<String> bad = new ArrayList<>();
        for (var e : found.entrySet()) {
            int max = MAX_STATIC_ADDERS.getOrDefault(e.getKey(), 0);
            if (e.getValue() > max) bad.add(e.getKey() + ": LongAdder の計器が増えた " + e.getValue() + " > " + max + "（計器は Kernel.stats の入口へ）");
        }
        assertEquals(List.of(), bad, "計器を段の中へ散らさない " + RULE);
    }

    @Test
    void noPerBoxCounters() throws IOException {
        Set<String> found = new TreeSet<>();
        for (Path p : sources()) {
            if (INSTANCE_COUNTER.matcher(Files.readString(p, StandardCharsets.UTF_8)).find()) found.add(RatchetSupport.rel(root(), p));
        }
        assertTrue(found.contains("core/kernel/Kernel.java"), "検出器が壊れている（Kernel の計器の実体が見つからない）: " + found);
        Set<String> extra = new TreeSet<>(found);
        extra.removeAll(ALLOWED_INSTANCE_COUNTERS);
        assertEquals(Set.of(), extra, "1 箱ごとの計器（実体の LongAdder・AtomicLong）を足さない。2,000 箱の文書で hover が約 8% 遅くなった（§6-180） " + RULE);
    }

    @Test
    void kernelStatsCallSitesDoNotGrow() throws IOException {
        int n = 0;
        List<String> sites = new ArrayList<>();
        for (Path p : sources()) {
            String rel = RatchetSupport.rel(root(), p);
            if (rel.equals("core/kernel/Kernel.java")) continue;
            Matcher m = KERNEL_STATS_CALL.matcher(Files.readString(p, StandardCharsets.UTF_8));
            while (m.find()) {
                n++;
                sites.add(rel);
            }
        }
        assertTrue(n >= 3, "検出器が壊れている（Kernel.stats の呼び出しが見つからない）: " + sites);
        assertTrue(n <= MAX_KERNEL_STATS_CALL_SITES, "段の中の Kernel.stats の呼び出し箇所が増えた " + n + " > " + MAX_KERNEL_STATS_CALL_SITES + " " + sites + " " + RULE);
    }

    /** 計器は既定でオフ（{@code static final} の分岐が JIT に消される前提）。オフの間は数えない。 */
    @Test
    void kernelStatsCountsNothingWhenOff() {
        if (Kernel.STATS) return; // costGate・profile の子 JVM では立つ。このテストは既定の JVM のためのもの
        Kernel.stats.measureCall();
        Kernel.stats.placeCall();
        Kernel.stats.restyleVisit();
        Kernel.stats.restyleRecompute();
        Kernel.stats.restyleChange();
        assertEquals(0L, Kernel.stats.measureCalls(), "オフの間は数えない");
        assertEquals(0L, Kernel.stats.placeCalls());
        assertEquals(0L, Kernel.stats.restyleVisited());
        assertEquals(0L, Kernel.stats.restyleRecomputed());
        assertEquals(0L, Kernel.stats.restyleChanged());
    }

    /** テストは実時間を待たない: リサイズの間引きを待つ {@code Thread.sleep} を持ち込まない（注入した時計で解く。K0 ①）。 */
    @Test
    void testsDoNotSleepForTheResizeThrottle() throws IOException {
        Path testRoot = Path.of("core/src/test/java/com/nishiyu/enderframelib");
        if (!Files.isDirectory(testRoot)) testRoot = Path.of("src/test/java/com/nishiyu/enderframelib");
        Set<String> found = new TreeSet<>();
        for (Path p : RatchetSupport.javaFiles(testRoot)) {
            if (SLEEP.matcher(Files.readString(p, StandardCharsets.UTF_8)).find()) found.add(RatchetSupport.rel(testRoot, p));
        }
        assertTrue(found.contains("common/ScrollCostTest.java"), "検出器が壊れている（既知の sleep が見つからない）: " + found);
        Set<String> extra = new TreeSet<>(found);
        extra.removeAll(ALLOWED_TEST_SLEEP);
        assertEquals(Set.of(), extra, "テストに実時間の sleep を足さない（Clock.Manual を注入する。ResizeSettle を使う） " + RULE);
    }
}
