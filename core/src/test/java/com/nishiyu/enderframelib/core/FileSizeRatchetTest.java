package com.nishiyu.enderframelib.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * ファイルの行数のラチェット（ENGINE_REARCHITECTURE_PLAN T1 ①。A8 の分割の進み具合を数で固定する）。
 *
 * <ul>
 *   <li>{@value #TRACKED_OVER} 行を超えるファイルは全部 {@code file-size-ceilings.txt} に載せ、その値を上限にする。</li>
 *   <li>表に無いファイルは {@value #UNLISTED_MAX} 行まで（新しいファイルを大きく育てない）。</li>
 *   <li>表の値より {@value #SLACK} 行を超えて減ったら、表も下げる（ラチェットを締める）。{@value #TRACKED_OVER} 行以下になったら表から外す。</li>
 * </ul>
 * 行数は物理行（空行・注釈を含む）。書き直しは {@code -Dratchet.update=true}。
 */
class FileSizeRatchetTest {

    static final int TRACKED_OVER = 800;
    static final int UNLISTED_MAX = 1500;
    static final int SLACK = 50;
    private static final String TABLE = "file-size-ceilings.txt";
    private static final String HEADER = """
            # FileSizeRatchetTest の表（ENGINE_REARCHITECTURE_PLAN T1 ①）: core/src/main/java/com/nishiyu/enderframelib からの相対パス | 行数の上限
            # 800 行超のファイルは全部載せる。増やさない。分けて減ったら下げる（-Dratchet.update=true で現在値へ書き直す）。""";

    /** 相対パス → 物理行数（{@code minLines} を超えるものだけ）。 */
    static Map<String, Integer> measure(Path root, int minLines) throws IOException {
        Map<String, Integer> out = new TreeMap<>();
        for (Path p : RatchetSupport.javaFiles(root)) {
            int n = Files.readAllLines(p, StandardCharsets.UTF_8).size();
            if (n > minLines) out.put(RatchetSupport.rel(root, p), n);
        }
        return out;
    }

    /** 実測と表を比べて、違反を文で返す（空なら合格）。 */
    static List<String> check(Map<String, Integer> actual, Map<String, Integer> ceilings) {
        List<String> bad = new ArrayList<>();
        for (var e : actual.entrySet()) {
            Integer cap = ceilings.get(e.getKey());
            int n = e.getValue();
            if (cap == null) {
                if (n > UNLISTED_MAX) bad.add("表に無いファイルが " + UNLISTED_MAX + " 行を超えた: " + e.getKey() + " " + n + " 行（責務で分ける）");
                else if (n > TRACKED_OVER) bad.add(TRACKED_OVER + " 行を超えたファイルを表に載せる: " + e.getKey() + " " + n + " 行");
            } else if (n > cap) {
                bad.add("上限を超えた: " + e.getKey() + " " + n + " > " + cap + " 行（増やさない。足すなら同じだけ別の責務を外へ出す）");
            } else if (n < cap - SLACK) {
                bad.add("減ったので表を下げる: " + e.getKey() + " " + cap + " → " + n + " 行" + (n <= TRACKED_OVER ? "（" + TRACKED_OVER + " 行以下なので表から外す）" : ""));
            }
        }
        for (var e : ceilings.entrySet()) {
            Integer n = actual.get(e.getKey());
            if (n == null) bad.add("表にあるが " + TRACKED_OVER + " 行以下か、無くなったファイル（表から外す）: " + e.getKey());
        }
        return bad;
    }

    private static Map<String, Integer> readCeilings() throws IOException {
        Map<String, Integer> out = new TreeMap<>();
        for (var e : RatchetSupport.read(RatchetSupport.table(TABLE), 2).entrySet()) out.put(e.getKey(), Integer.parseInt(e.getValue()[0]));
        return out;
    }

    @Test
    void fileSizesOnlyShrink() throws IOException {
        Map<String, Integer> actual = measure(RatchetSupport.mainRoot(), 0);
        Map<String, Integer> tracked = new TreeMap<>();
        actual.forEach((k, v) -> { if (v > TRACKED_OVER) tracked.put(k, v); });
        if (RatchetSupport.UPDATE) {
            List<String> rows = new ArrayList<>();
            tracked.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).forEach(e -> rows.add(e.getKey() + " | " + e.getValue()));
            RatchetSupport.write(RatchetSupport.table(TABLE), HEADER, rows);
        }
        List<String> bad = check(tracked, readCeilings());
        // 表に無い小さいファイルは tracked に入らないので、UNLISTED_MAX の検査は tracked で足りる
        assertTrue(bad.isEmpty(), "ファイルの行数のラチェット（core/src/test/resources/" + TABLE + "）:\n  " + String.join("\n  ", bad));
    }

    /** 検査器自身の検証: 増加・表に無い大きいファイル・減った分の締め忘れ・消えたファイルを拾い、範囲内は通す。 */
    @Test
    void checkerCatchesEveryKindOfDrift() {
        Map<String, Integer> caps = Map.of("A.java", 1000, "B.java", 2000, "Gone.java", 900);
        Map<String, Integer> actual = new TreeMap<>(Map.of(
                "A.java", 1001,             // 超えた
                "B.java", 1900,             // 50 行を超えて減った
                "C.java", 1600,             // 表に無いのに 1,500 超
                "D.java", 900));            // 表に無いのに 800 超
        List<String> bad = check(actual, caps);
        assertEquals(5, bad.size(), String.join("\n", bad));
        assertTrue(check(Map.of("A.java", 980), Map.of("A.java", 1000)).isEmpty(), "余裕の範囲の減少は通す");
    }

    @Test
    void measureCountsPhysicalLines() throws IOException {
        RatchetSupport.withTempSources(Map.of("p/Big.java", "class Big {\n\n// c\n}\n".repeat(300), "p/Small.java", "class S {}\n"), dir -> {
            Map<String, Integer> m = measure(dir, 800);
            assertEquals(Map.of("p/Big.java", 1200), m);
        });
    }
}
