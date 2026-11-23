package com.nishiyu.enderframelib.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 回帰カタログ（{@code regression-catalog.txt}。ENGINE_REARCHITECTURE_PLAN T3 ①）の検査。
 * ①載っているテスト（{@code クラス#メソッド}）がテストのソースに注釈つきで実在し、{@code gradle:タスク} が登録されている
 * ②計画の §6 で〔不具合〕の印を付けた項目はすべてカタログに載っている ③カタログの §6-N は計画に実在する。
 * テストの名前を変えた・消したときと、§6 に不具合を書いたのに守るテストを載せ忘れたときに赤になる。
 */
class RegressionCatalogTest {

    static final String BUG_MARK = "〔不具合〕";
    private static final Pattern SOURCE = Pattern.compile("§6-\\d+[a-z]?|[A-Z][A-Z0-9]*(?:-\\d+)?[a-z]?|[0-9a-f]{7,40}");
    private static final Pattern TEST_REF = Pattern.compile("(\\w+)#(\\w+)|gradle:(\\w+)");
    private static final Pattern TEST_ANNOTATION = Pattern.compile("@(?:Test|ParameterizedTest|RepeatedTest|TestFactory)\\b");
    private static final Pattern VOID_METHOD = Pattern.compile("\\bvoid\\s+(\\w+)\\s*\\(");
    private static final Pattern ARCHIVED_ITEM = Pattern.compile("^§6-(\\d+[a-z]?)\\. (.*)$");
    private static final Pattern SECTION6_ITEM = Pattern.compile("^(\\d+[a-z]?)\\. (.*)$");

    record Entry(int line, List<String> sources, List<String> tests, String description) { }

    static Path root() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("settings.gradle"))) p = p.getParent();
        assertNotNull(p, "リポジトリの根（settings.gradle）が見つからない");
        return p;
    }

    static List<Entry> catalog() throws IOException {
        List<Entry> out = new ArrayList<>();
        List<String> lines = Files.readAllLines(root().resolve("core/src/test/resources/regression-catalog.txt"), StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.isBlank() || l.startsWith("#")) continue;
            String[] f = l.split("\\s*\\|\\s*", 3);
            assertEquals(3, f.length, "行 " + (i + 1) + ": 形式は「元 | テスト | 説明」: " + l);
            List<String> sources = List.of(f[0].trim().split("\\s*,\\s*"));
            List<String> tests = List.of(f[1].trim().split("\\s*,\\s*"));
            for (String s : sources) assertTrue(SOURCE.matcher(s).matches(), "行 " + (i + 1) + ": 元の形式（§6-N・計画の ID・コミット）: " + s);
            for (String t : tests) assertTrue(TEST_REF.matcher(t).matches(), "行 " + (i + 1) + ": テストの形式（クラス#メソッド・gradle:タスク）: " + t);
            assertTrue(!f[2].isBlank(), "行 " + (i + 1) + ": 説明が空");
            out.add(new Entry(i + 1, sources, tests, f[2].trim()));
        }
        return out;
    }

    /** テストのソース（全モジュールの src/test/java）をクラスの単純名で引く。 */
    static Map<String, List<Path>> testSources() throws IOException {
        Map<String, List<Path>> out = new HashMap<>();
        try (Stream<Path> dirs = Files.list(root())) {
            List<Path> roots = new ArrayList<>(List.of(root().resolve("src/test/java")));
            dirs.filter(Files::isDirectory).map(d -> d.resolve("src/test/java")).forEach(roots::add);
            for (Path r : roots) {
                if (!Files.isDirectory(r)) continue;
                try (Stream<Path> s = Files.walk(r)) {
                    s.filter(p -> p.toString().endsWith(".java")).forEach(p -> out.computeIfAbsent(p.getFileName().toString().replace(".java", ""), k -> new ArrayList<>()).add(p));
                }
            }
        }
        return out;
    }

    /** {@code source} に、テストの注釈（@Test・@ParameterizedTest・@RepeatedTest・@TestFactory）のついたメソッド {@code method} があるか。 */
    static boolean declaresTest(String source, String method) {
        Matcher a = TEST_ANNOTATION.matcher(source);
        Matcher v = VOID_METHOD.matcher(source);
        while (a.find()) {
            if (v.find(a.end()) && v.group(1).equals(method)) return true; // 注釈の直後の最初のメソッド
        }
        return false;
    }

    static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void everyListedTestExists() throws IOException {
        Map<String, List<Path>> sources = testSources();
        String gradle = Files.readString(root().resolve("core/build.gradle"), StandardCharsets.UTF_8);
        List<String> missing = new ArrayList<>();
        List<Entry> entries = catalog();
        for (Entry e : entries) {
            for (String t : e.tests()) {
                Matcher m = TEST_REF.matcher(t);
                assertTrue(m.matches());
                if (m.group(3) != null) {
                    if (!gradle.contains("tasks.register('" + m.group(3) + "'")) missing.add("行 " + e.line() + ": gradle のタスクが無い: " + t);
                    continue;
                }
                List<Path> files = sources.get(m.group(1));
                if (files == null) { missing.add("行 " + e.line() + ": テストのクラスが無い: " + t); continue; }
                if (files.stream().map(RegressionCatalogTest::read).noneMatch(s -> declaresTest(s, m.group(2)))) missing.add("行 " + e.line() + ": テストのメソッドが無い: " + t);
            }
        }
        assertTrue(entries.size() >= 40, "カタログが読めている: " + entries.size());
        assertEquals(List.of(), missing);
    }

    /** 計画の §6 の項目（番号 → 本文。本編と、補足資料の付録 3 の両方）。 */
    static Map<String, String> section6() throws IOException {
        Map<String, String> out = new HashMap<>();
        boolean in = false;
        for (String l : Files.readAllLines(root().resolve("core/ENGINE_REARCHITECTURE_PLAN.md"), StandardCharsets.UTF_8)) {
            if (l.startsWith("## ")) in = l.startsWith("## 6.");
            if (!in) continue;
            Matcher m = SECTION6_ITEM.matcher(l);
            if (m.matches()) assertEquals(null, out.put(m.group(1), m.group(2)), "§6 の番号が重複している（2 つ目には枝番 b を付ける）: " + m.group(1));
        }
        // 古い知見（付録 3）は補足資料へ移してある。そちらでは「§6-N. 本文」と書く
        boolean inAppendix = false;
        for (String l : Files.readAllLines(root().resolve("core/ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md"), StandardCharsets.UTF_8)) {
            if (l.startsWith("## ")) inAppendix = l.startsWith("## 付録 3");
            if (!inAppendix) continue;
            Matcher m = ARCHIVED_ITEM.matcher(l);
            if (m.matches()) assertEquals(null, out.put(m.group(1), m.group(2)), "§6 の番号が本編と付録 3 で重複している: " + m.group(1));
        }
        return out;
    }

    @Test
    void everyBugInSection6IsCatalogued() throws IOException {
        Map<String, String> items = section6();
        Set<String> marked = new TreeSet<>();
        for (var e : items.entrySet()) if (e.getValue().startsWith(BUG_MARK)) marked.add("§6-" + e.getKey());
        assertTrue(marked.size() >= 25, "§6 の〔不具合〕の印が読めている: " + marked);
        Set<String> catalogued = new LinkedHashSet<>();
        for (Entry e : catalog()) catalogued.addAll(e.sources());
        List<String> missing = marked.stream().filter(s -> !catalogued.contains(s)).toList();
        assertEquals(List.of(), missing, "§6 で〔不具合〕の印の付いた項目に、守るテストの行が無い（regression-catalog.txt へ足す）");
    }

    @Test
    void catalogReferencesExistingSection6Items() throws IOException {
        Map<String, String> items = section6();
        assertTrue(items.size() >= 100, "§6 が読めている: " + items.size());
        List<String> unknown = new ArrayList<>();
        for (Entry e : catalog()) for (String s : e.sources()) if (s.startsWith("§6-") && !items.containsKey(s.substring(3))) unknown.add("行 " + e.line() + ": " + s);
        assertEquals(List.of(), unknown);
    }

    /** 検査そのものが効くこと: 注釈の無いメソッド・別のメソッドは「無い」と判定する。 */
    @Test
    void detectorSeesTheDifference() {
        String src = "class X {\n  @Test\n  void a() {}\n  void b() {}\n  @ParameterizedTest\n  @ValueSource(ints = {1})\n  void c(int i) {}\n}";
        assertTrue(declaresTest(src, "a"));
        assertTrue(!declaresTest(src, "b"));
        assertTrue(declaresTest(src, "c"));
        assertTrue(!declaresTest(src, "d"));
        assertTrue(SOURCE.matcher("§6-53b").matches() && SOURCE.matcher("X3-2").matches() && SOURCE.matcher("aa2cf77").matches());
        assertTrue(!SOURCE.matcher("a6b-cutoff").matches());
    }
}
