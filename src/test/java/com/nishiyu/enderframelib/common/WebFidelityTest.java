package com.nishiyu.enderframelib.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * ブラウザ実測との差分テスト。
 *
 * 忠実度を目視判断から外すのが目的。コーパス（preview/fidelity/cases）を
 * エンジンに通した結果と、同じコーパスをブラウザで測った期待値
 * （src/test/resources/fidelity/expected）を突き合わせる。
 *
 * 期待値と違ってよいのは {@code known-divergences.txt} に載っている差だけで、
 * 台帳に無い差が出たら回帰として落ちる。逆に台帳に載っているのに一致した差は
 * 「台帳が古い」として落ちる —— これが各フェーズの完了条件になる。
 *
 * 期待値の作り直し手順は CONTRIBUTING.md を参照。
 */
public class WebFidelityTest {

    private static final Path EXPECTED_DIR = Path.of("src/test/resources/fidelity/expected");
    private static final Path LEDGER = Path.of("src/test/resources/fidelity/known-divergences.txt");
    private static final Path FIDELITY_DOC = Path.of("FIDELITY.md");

    @Test
    public void testEngineMatchesBrowserExceptKnownDivergences() {
        Map<String, String> ledger = readLedger();
        Set<String> unused = new LinkedHashSet<>(ledger.keySet());
        List<String> regressions = new ArrayList<>();
        List<String> staleLedgerRows = new ArrayList<>();

        for (String caseName : FidelityDumper.caseNames()) {
            Map<String, FidelityDumper.Snapshot> actual = FidelityDumper.dump(caseName);
            Map<String, FidelityDumper.Snapshot> expected = FidelityDumper.parseDump(readExpected(caseName));

            for (var entry : expected.entrySet()) {
                String id = entry.getKey();
                FidelityDumper.Snapshot want = entry.getValue();
                FidelityDumper.Snapshot got = actual.get(id);
                if (got == null) {
                    regressions.add(caseName + "#" + id + ": 要素がレイアウト結果に無い");
                    continue;
                }
                // 幾何と描画順の比較 (x, y, w, h, z)
                for (String field : FidelityDumper.GEOMETRY_FIELDS) {
                    checkNumericField(caseName, id, field, want.field(field), got.field(field),
                            ledger, unused, regressions, staleLedgerRows);
                }
                // 期待値が拡張形式（計算値やスクロール情報を持つ）の場合、スクロール量と計算値も比較
                if (!want.computed().isEmpty()) {
                    for (String field : new String[]{"scrollWidth", "scrollHeight", "clientWidth", "clientHeight"}) {
                        checkNumericField(caseName, id, field, want.field(field), got.field(field),
                                ledger, unused, regressions, staleLedgerRows);
                    }
                    for (var compEntry : want.computed().entrySet()) {
                        String prop = compEntry.getKey();
                        String wantVal = compEntry.getValue();
                        String gotVal = got.computedValue(prop);
                        String key = caseName + "#" + id + "." + prop;
                        boolean known = ledger.containsKey(key);
                        boolean matches = (gotVal != null && gotVal.equals(wantVal));
                        if (!matches) {
                            if (known) unused.remove(key);
                            else regressions.add(key + ": ブラウザ " + wantVal + " / エンジン " + gotVal);
                        } else if (known) {
                            unused.remove(key);
                            staleLedgerRows.add(key + ": 一致するようになった（ブラウザ "
                                    + wantVal + " / エンジン " + gotVal + "）");
                        }
                    }
                }
            }
        }

        if (!unused.isEmpty()) {
            staleLedgerRows.add("期待値に対応する比較が無い台帳の行: " + unused);
        }

        if (!regressions.isEmpty() || !staleLedgerRows.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            if (!regressions.isEmpty()) {
                sb.append("台帳に無い差異（回帰）:\n");
                regressions.forEach(r -> sb.append("  ").append(r).append('\n'));
                sb.append("意図した差なら ").append(LEDGER).append(" と FIDELITY.md に追記する。\n");
            }
            if (!staleLedgerRows.isEmpty()) {
                sb.append("台帳が古い（差が解消済み）:\n");
                staleLedgerRows.forEach(r -> sb.append("  ").append(r).append('\n'));
                sb.append("該当行を ").append(LEDGER).append(" と FIDELITY.md から削除する。\n");
            }
            fail(sb.toString());
        }
    }

    private static void checkNumericField(String caseName, String id, String field,
                                          float wantVal, float gotVal,
                                          Map<String, String> ledger, Set<String> unused,
                                          List<String> regressions, List<String> staleLedgerRows) {
        String key = caseName + "#" + id + "." + field;
        float diff = Math.abs(gotVal - wantVal);
        boolean known = ledger.containsKey(key);
        String browser = FidelityDumper.number(wantVal);
        String engine = FidelityDumper.number(gotVal);
        if (diff > FidelityDumper.tolerance(field) + 1e-3f) {
            if (known) unused.remove(key);
            else regressions.add(key + ": ブラウザ " + browser + " / エンジン " + engine);
        } else if (known) {
            unused.remove(key);
            staleLedgerRows.add(key + ": 一致するようになった（ブラウザ "
                    + browser + " / エンジン " + engine + "）");
        }
    }

    /** 台帳の各項目が FIDELITY.md にも書かれていること（軸 4 の 3 点セットのうち 2 点）。 */
    @Test
    public void testEveryLedgerRowIsDocumented() throws IOException {
        String doc = Files.readString(FIDELITY_DOC, StandardCharsets.UTF_8);
        for (String key : readLedger().keySet()) {
            assertTrue(doc.contains(key), key + " が FIDELITY.md に書かれていない");
        }
    }

    @Test
    public void testEveryCaseHasExpectedValues() {
        for (String caseName : FidelityDumper.caseNames()) {
            assertTrue(Files.exists(EXPECTED_DIR.resolve(caseName + ".txt")),
                    caseName + " の期待値ファイルが無い");
            assertTrue(!FidelityDumper.caseHtml(caseName).isBlank(), caseName + ".html が空");
        }
    }

    /**
     * ケースが宣言した計算値の列が、期待値にも入っていて実際に比較されることを確かめる。
     *
     * <p>宣言しただけで両側に列が無いと、比較ループが空回りして「測っているつもり」になる。
     * R0 で opacity / transform / color の列が使われているかを確認したときの番人。
     */
    @Test
    public void testDeclaredComputedPropertiesAreCompared() {
        int compared = 0;
        for (String caseName : FidelityDumper.caseNames()) {
            List<String> declared = FidelityDumper.declaredProperties(FidelityDumper.caseHtml(caseName));
            if (declared.isEmpty()) continue;

            Map<String, FidelityDumper.Snapshot> expected = FidelityDumper.parseDump(readExpected(caseName));
            for (String property : declared) {
                boolean present = expected.values().stream()
                        .anyMatch(snapshot -> snapshot.computed().containsKey(property));
                assertTrue(present, caseName + ": 宣言した計算値 " + property
                        + " が期待値に 1 つも無い（列が比較されない）");
                compared++;
            }
        }
        assertTrue(compared > 0, "計算値の列を宣言したケースがコーパスに 1 つも無い");
    }

    /**
     * 現状のエンジン出力を build/fidelity に書き出す補助。
     * {@code ./gradlew test --tests '*WebFidelityTest' -Dfidelity.dump=true} で動かす。
     * 期待値の代わりに使ってはいけない（期待値はブラウザ側が出所）。
     */
    @Test
    public void dumpCurrentEngineGeometry() throws IOException {
        if (!Boolean.getBoolean("fidelity.dump")) return;
        Path outDir = Path.of("build/fidelity");
        Files.createDirectories(outDir);
        for (String caseName : FidelityDumper.caseNames()) {
            String text = FidelityDumper.format(FidelityDumper.dump(caseName));
            Files.writeString(outDir.resolve(caseName + ".txt"), text, StandardCharsets.UTF_8);
        }
    }

    @Test
    public void exportFidelityDumpHtml() throws IOException {
        if (!Boolean.getBoolean("fidelity.export")) return;
        Path out = Path.of("build/fidelity-dump.html");
        Files.createDirectories(out.getParent());
        Files.writeString(out, WebDevKitExporter.buildFidelityDumpHtml(), StandardCharsets.UTF_8);
        System.out.println("Exported fidelity-dump.html to: " + out.toAbsolutePath());
    }

    /** 台帳: {@code case#id.field} → 理由。 */
    private static Map<String, String> readLedger() {
        Map<String, String> rows = new LinkedHashMap<>();
        String text;
        try {
            text = Files.readString(LEDGER, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int sp = trimmed.indexOf(' ');
            if (sp < 0) throw new IllegalArgumentException("台帳の行に理由が無い: " + trimmed);
            rows.put(trimmed.substring(0, sp), trimmed.substring(sp + 1).trim());
        }
        return rows;
    }

    private static String readExpected(String caseName) {
        try (InputStream is = WebFidelityTest.class.getResourceAsStream("/fidelity/expected/" + caseName + ".txt")) {
            if (is == null) throw new IllegalStateException(caseName + " の期待値が無い");
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
