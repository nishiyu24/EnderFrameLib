package com.nishiyu.enderframelib.wpt;

import com.nishiyu.enderframelib.wpt.WptHarness.WptCase;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WPT 採用ケースマニフェスト（wpt/manifest.json）の整合性テスト。
 *
 * <p>manifest は「どのケースを、どの固定 SHA で、ブラウザ側ではどう振る舞うか」を記録する。
 * ブラウザと一致しないサブテストは {@code wpt/known-divergences.txt} の台帳にフェーズ付きで載せる。
 */
public class WptManifestTest {

    private static final String MANIFEST_RESOURCE = "/wpt/manifest.json";
    private static final Pattern PHASE = Pattern.compile("\\bR(?:10|[1-9])\\b");

    /** manifest.json の 1 ケース分。 */
    private record CaseEntry(
            String id,
            String wptRevision,
            String status,
            String browserExpectation
    ) {}

    @Test
    public void testManifestLoadsAndHasValidPinnedRevision() {
        String json = readManifest();
        assertNotNull(json, "manifest.json must exist in test resources");
        assertFalse(json.isBlank(), "manifest.json must not be blank");

        String pinned = extractStringProperty(json, "pinnedRevision");
        assertEquals(WptHarness.PINNED_SHA, pinned, "manifest pinned revision must match WptHarness.PINNED_SHA");
    }

    @Test
    public void testAllAdoptedTestMethodsAreTrackedInManifest() {
        Map<String, CaseEntry> manifestCases = extractCases(readManifest());
        assertFalse(manifestCases.isEmpty(), "manifest must declare at least one adopted case");

        Set<String> portedPaths = adoptedPaths();
        for (String path : portedPaths) {
            assertTrue(manifestCases.containsKey(path),
                    "Adopted WPT test path '" + path + "' must be listed in manifest.json");
        }

        // 逆方向: manifest にだけ残った「採用していないケース」も落とす
        for (String path : manifestCases.keySet()) {
            assertTrue(portedPaths.contains(path),
                    "manifest.json の '" + path + "' に対応する @WptCase テストが無い（削除し忘れ）");
        }
    }

    @Test
    public void testEveryCaseRecordsBrowserExpectation() {
        Map<String, CaseEntry> cases = extractCases(readManifest());
        assertFalse(cases.isEmpty(), "manifest must declare at least one adopted case");

        for (CaseEntry entry : cases.values()) {
            assertEquals(WptHarness.PINNED_SHA, entry.wptRevision(),
                    entry.id() + " の wptRevision が固定 SHA と違う");
            assertNotNull(entry.browserExpectation(),
                    entry.id() + " に browserExpectation が無い（ブラウザ側の期待値を記録すること）");
            assertTrue(entry.browserExpectation().equals("pass") || entry.browserExpectation().equals("fail"),
                    entry.id() + " の browserExpectation は pass / fail のどちらか: " + entry.browserExpectation());
        }
    }

    @Test
    public void testKnownDivergencesAreTrackedAndNotStale() {
        Map<String, CaseEntry> cases = extractCases(readManifest());
        Map<String, String> ledger = WptHarness.knownDivergences();
        Map<String, Integer> rowsPerCase = new LinkedHashMap<>();

        for (var row : ledger.entrySet()) {
            String key = row.getKey();
            int hash = key.indexOf('#');
            String path = key.substring(0, hash);
            String subtest = key.substring(hash + 1);
            assertFalse(subtest.isBlank(), "台帳のサブテスト名が空: " + key);

            CaseEntry entry = cases.get(path);
            assertNotNull(entry, "台帳の " + key + " が manifest.json のケースを指していない");
            assertTrue(PHASE.matcher(row.getValue()).find(),
                    "台帳の " + key + " にフェーズ（R1〜R10）が無い: " + row.getValue());
            rowsPerCase.merge(path, 1, Integer::sum);
        }

        for (CaseEntry entry : cases.values()) {
            int rows = rowsPerCase.getOrDefault(entry.id(), 0);
            if ("pass".equals(entry.browserExpectation())) {
                assertEquals(0, rows,
                        entry.id() + " はブラウザで pass なので台帳の行を置けない（台帳が古い）");
            } else {
                assertTrue(rows > 0,
                        entry.id() + " はブラウザで fail なので known-divergences.txt に理由付きの行が必要");
            }
        }
    }

    /** 移植クラス（dom/・uievents/・css/css-syntax/）の @WptCase パスを集める。 */
    private static Set<String> adoptedPaths() {
        Set<String> paths = new HashSet<>();
        for (Class<?> portedClass : List.of(WptDomBehaviorTest.class, WptUiEventsBehaviorTest.class,
                WptCssSyntaxBehaviorTest.class)) {
            for (Method method : portedClass.getDeclaredMethods()) {
                WptCase wptCase = method.getAnnotation(WptCase.class);
                if (wptCase != null) {
                    assertTrue(paths.add(wptCase.path()),
                            "同じ WPT パスが複数のテストメソッドにある: " + wptCase.path());
                    assertEquals(WptHarness.PINNED_SHA, wptCase.sha(),
                            "Test method " + method.getName() + " must use pinned SHA");
                }
            }
        }
        return paths;
    }

    private static String readManifest() {
        try (InputStream is = WptManifestTest.class.getResourceAsStream(MANIFEST_RESOURCE)) {
            if (is == null) throw new IllegalStateException("Missing resource: " + MANIFEST_RESOURCE);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read manifest", e);
        }
    }

    private static String extractStringProperty(String json, String property) {
        Pattern pattern = Pattern.compile("\"" + property + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) return matcher.group(1);
        return null;
    }

    /**
     * cases 配列の各オブジェクトを読む。ケースは入れ子のオブジェクトを持たないので、
     * 依存を増やさずに {@code {...}} 単位で切る。
     */
    private static Map<String, CaseEntry> extractCases(String json) {
        int casesKey = json.indexOf("\"cases\"");
        if (casesKey < 0) throw new IllegalStateException("manifest.json に cases が無い");
        int arrayStart = json.indexOf('[', casesKey);
        int arrayEnd = json.lastIndexOf(']');
        if (arrayStart < 0 || arrayEnd < arrayStart) {
            throw new IllegalStateException("manifest.json の cases 配列が壊れている");
        }
        String array = json.substring(arrayStart + 1, arrayEnd);

        Map<String, CaseEntry> cases = new LinkedHashMap<>();
        Matcher matcher = Pattern.compile("\\{([^{}]*)}").matcher(array);
        while (matcher.find()) {
            String body = matcher.group(1);
            String id = extractStringProperty(body, "id");
            if (id == null) throw new IllegalStateException("manifest のケースに id が無い: " + body);
            CaseEntry entry = new CaseEntry(
                    id,
                    extractStringProperty(body, "wptRevision"),
                    extractStringProperty(body, "status"),
                    extractStringProperty(body, "browserExpectation"));
            if (cases.put(id, entry) != null) {
                throw new IllegalStateException("manifest.json に同じ id が 2 回ある: " + id);
            }
        }
        return cases;
    }

    /** 使っていない import を避けるための小さな検証（manifest の status も既知の値だけにする）。 */
    @Test
    public void testEveryCaseHasKnownStatus() {
        List<String> invalid = new ArrayList<>();
        for (CaseEntry entry : extractCases(readManifest()).values()) {
            if (entry.status() == null || !Set.of("supported", "unsupported").contains(entry.status())) {
                invalid.add(entry.id() + " → " + entry.status());
            }
        }
        assertTrue(invalid.isEmpty(), "manifest の status が不正: " + invalid);
    }
}
