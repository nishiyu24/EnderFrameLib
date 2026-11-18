package com.nishiyu.enderframelib.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 実時間で合否を決めるテストは {@code @Tag("timing")} を付け、共有ランナーの CI では外す（2026-11-19）。
 * 2 コアの共有ランナーでは同じコードでも速さが実行ごとに揺れ、変更と関係の無い赤が出る。合否を時間に頼らない確認
 * （件数・経路の回数・画素・確保量の costGate）だけを CI の合否にし、速さの回帰は開発機（{@code scripts/ci-linux.ps1} を含む）で見る。
 * <ul>
 *   <li>経過時間の変数（{@code ms}・{@code ...Ms}・{@code ...Ns} など）を大小比較する assert は、{@code @Tag("timing")} のテストの中だけに置く</li>
 *   <li>CI のワークフローでテストを流す Gradle の行は、どれも {@code -Ptest.excludeTags=timing} を渡す</li>
 * </ul>
 */
class TimingTagPolicyTest {

    private static final Path TESTS = Path.of("core/src/test/java");
    private static final Path WORKFLOWS = Path.of(".github/workflows");
    private static final String EXCLUDE = "-Ptest.excludeTags=timing";

    /** 経過時間らしい名前の変数を {@code <} / {@code >} で比べる assert。{@code changedItems} などの「…ms」で終わる語は拾わない。 */
    static final Pattern TIME_ASSERT = Pattern.compile(
            "assert\\w*\\([^;]*?(?:\\b(?:ms|ns|nanos|millis)|\\b\\w*(?:Ms|Ns|Nanos|Millis))\\b(?:\\[\\d+\\])?\\s*[<>]");

    /** 本文の位置 {@code pos} を含むテストメソッドの注釈（直前の {@code @Test} からメソッド名まで）。無ければ null。 */
    static String annotationsOfEnclosingTest(String src, int pos) {
        int at = src.lastIndexOf("@Test", pos);
        if (at < 0) return null;
        int open = src.indexOf('(', src.indexOf("void ", at));
        // 間に別のメソッドの本文が挟まっていれば、assert は @Test の付かないメソッドの中にある
        int body = src.indexOf('{', open);
        if (body < 0 || body > pos) return null;
        int depth = 0;
        for (int i = body; i < pos; i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return null;
        }
        return src.substring(at, open);
    }

    static List<String> untaggedTimeAsserts(String file, String src) {
        List<String> bad = new ArrayList<>();
        Matcher m = TIME_ASSERT.matcher(src);
        while (m.find()) {
            String ann = annotationsOfEnclosingTest(src, m.start());
            if (ann == null || !ann.contains("@Tag(\"timing\")")) {
                int line = 1;
                for (int i = 0; i < m.start(); i++) if (src.charAt(i) == '\n') line++;
                bad.add(file + ":" + line);
            }
        }
        return bad;
    }

    @Test
    void wallClockAssertionsAreTaggedTiming() throws IOException {
        List<String> bad = new ArrayList<>();
        int found = 0;
        try (Stream<Path> files = Files.walk(TESTS)) {
            // このファイル自身は検出の式を試す見本の文字列を持つので除く
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java") && !f.endsWith("TimingTagPolicyTest.java"))::iterator) {
                String src = Files.readString(p, StandardCharsets.UTF_8);
                Matcher m = TIME_ASSERT.matcher(src);
                while (m.find()) found++;
                bad.addAll(untaggedTimeAsserts(TESTS.relativize(p).toString(), src));
            }
        }
        assertTrue(found >= 6, "経過時間の assert を見つけられていない（検出の式が壊れた？）: " + found);
        assertEquals(List.of(), bad, "実時間で合否を決める assert は @Tag(\"timing\") のテストに置く（CI の共有ランナーでは揺れる）");
    }

    @Test
    void detectorSeesTheDifference() {
        String tagged = "class A {\n  @Test\n  @Tag(\"timing\")\n  void a() {\n    long ms = 1;\n    assertTrue(ms < 5, \"x\");\n  }\n}\n";
        String untagged = "class A {\n  @Test\n  void a() {\n    if (true) { }\n    assertTrue(medianMs < 3.0);\n  }\n}\n";
        String notTime = "class A {\n  @Test\n  void a() {\n    assertTrue(changedItems > 0);\n    assertTrue(items.size() < 200);\n  }\n}\n";
        String inHelper = "class A {\n  @Test\n  @Tag(\"timing\")\n  void a() { }\n  void helper() {\n    assertTrue(ms < 5);\n  }\n}\n";
        assertEquals(List.of(), untaggedTimeAsserts("A", tagged));
        assertEquals(List.of("A:5"), untaggedTimeAsserts("A", untagged));
        assertEquals(List.of(), untaggedTimeAsserts("A", notTime));
        assertEquals(List.of("A:6"), untaggedTimeAsserts("A", inHelper), "@Test の付かないメソッドの中の時間の assert は外せない");
    }

    @Test
    void ciWorkflowsExcludeTimingTests() throws IOException {
        int testRuns = 0;
        List<String> bad = new ArrayList<>();
        try (Stream<Path> files = Files.list(WORKFLOWS)) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".yml"))::iterator) {
                List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String l = lines.get(i).trim();
                    if (l.startsWith("#") || !l.contains("./gradlew")) continue;
                    // `-x test` は除外なので数えない。`test` / `:core:test` などのタスクを流す行だけ
                    String runsTests = l.replaceAll("-x\\s+\\S+", "");
                    if (!runsTests.matches(".*(?:\\s|:)test(?:\\s|$).*")) continue;
                    testRuns++;
                    if (!l.contains(EXCLUDE)) bad.add(p.getFileName() + ":" + (i + 1) + " " + l);
                }
            }
        }
        assertTrue(testRuns >= 2, "build.yml と full-tests.yml のテストの行が見つからない: " + testRuns);
        assertEquals(List.of(), bad, "CI でテストを流す行は " + EXCLUDE + " を渡す");
    }
}
