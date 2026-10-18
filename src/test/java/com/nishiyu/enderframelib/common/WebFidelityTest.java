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
            Map<String, FidelityDumper.Rect> actual = FidelityDumper.dump(caseName);
            Map<String, FidelityDumper.Rect> expected = FidelityDumper.parseDump(readExpected(caseName));

            for (var entry : expected.entrySet()) {
                String id = entry.getKey();
                FidelityDumper.Rect want = entry.getValue();
                FidelityDumper.Rect got = actual.get(id);
                if (got == null) {
                    regressions.add(caseName + "#" + id + ": 要素がレイアウト結果に無い");
                    continue;
                }
                for (String field : FidelityDumper.FIELDS) {
                    String key = caseName + "#" + id + "." + field;
                    float diff = Math.abs(got.field(field) - want.field(field));
                    boolean known = ledger.containsKey(key);
                    String browser = FidelityDumper.number(want.field(field));
                    String engine = FidelityDumper.number(got.field(field));
                    // float の演算誤差でちょうど 0.5px の差が落ちないよう、わずかに余裕を持たせる
                    if (diff > FidelityDumper.tolerance(field) + 1e-3f) {
                        if (known) unused.remove(key);
                        else regressions.add(key + ": ブラウザ " + browser + " / エンジン " + engine);
                    } else if (known) {
                        unused.remove(key);
                        staleLedgerRows.add(key + ": 一致するようになった（ブラウザ "
                                + browser + " / エンジン " + engine + "）");
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
            System.out.println("[fidelity] " + caseName + "\n" + text);
        }
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
