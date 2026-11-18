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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 黙って握りつぶす例外のラチェット（ENGINE_REARCHITECTURE_PLAN T1 ⑤）。
 *
 * <p>本体が空（文もコメントも無い）の {@code catch} で、{@code Exception}・{@code Throwable}・{@code RuntimeException}・{@code Error}
 * のどれかを受けるものを数える。不具合が症状を残さずに消える形なので、ファイルごとの数を {@code swallowed-exceptions.txt} で
 * 固定する（増やさない。減ったら下げる）。握りつぶすのが正しいときは、本体に理由のコメントを書けば数えない。</p>
 */
class SwallowedExceptionRatchetTest {

    private static final String TABLE = "swallowed-exceptions.txt";
    private static final String HEADER = """
            # SwallowedExceptionRatchetTest の表（ENGINE_REARCHITECTURE_PLAN T1 ⑤）: ソースの相対パス | 本体が空の広い catch の数
            # 増やさない。直したら下げる（-Dratchet.update=true で現在値へ書き直す）。握りつぶしてよいなら本体に理由のコメントを書く。""";

    private static final Pattern EMPTY_CATCH = Pattern.compile(
            "catch\\s*\\(\\s*(?:final\\s+)?([\\w.]+(?:\\s*\\|\\s*[\\w.]+)*)\\s+\\w+\\s*\\)\\s*\\{\\s*\\}");
    private static final Pattern BROAD = Pattern.compile("(?:^|[|\\s.])(Exception|Throwable|RuntimeException|Error)\\s*(?:$|\\|)");

    /** ファイル → 本体が空の広い catch の数（0 のファイルは載せない）。 */
    static Map<String, Integer> scan(Path root) throws IOException {
        Map<String, Integer> out = new TreeMap<>();
        for (Path p : RatchetSupport.javaFiles(root)) {
            String raw = Files.readString(p, StandardCharsets.UTF_8);
            String code = RatchetSupport.stripCommentsAndStrings(raw, false); // 位置は raw と同じ
            Matcher m = EMPTY_CATCH.matcher(raw);
            int n = 0;
            while (m.find()) {
                if (!code.startsWith("catch", m.start())) continue;          // コメント・文字列の中
                if (BROAD.matcher(m.group(1).trim()).find()) n++;
            }
            if (n > 0) out.put(RatchetSupport.rel(root, p), n);
        }
        return out;
    }

    static List<String> check(Map<String, Integer> actual, Map<String, Integer> caps) {
        List<String> bad = new ArrayList<>();
        for (var e : actual.entrySet()) {
            int cap = caps.getOrDefault(e.getKey(), 0);
            if (e.getValue() > cap) bad.add(e.getKey() + ": 本体が空の広い catch が増えた " + e.getValue() + " > " + cap
                    + "（必要な例外だけを受ける・診断へ報告する・握りつぶすなら本体に理由のコメント）");
        }
        for (var e : caps.entrySet()) {
            int n = actual.getOrDefault(e.getKey(), 0);
            if (n < e.getValue()) bad.add(e.getKey() + ": 減ったので表を下げる " + e.getValue() + " → " + n + (n == 0 ? "（表から外す）" : ""));
        }
        return bad;
    }

    @Test
    void swallowedExceptionsOnlyShrink() throws IOException {
        Map<String, Integer> actual = scan(RatchetSupport.mainRoot());
        if (RatchetSupport.UPDATE) {
            List<String> rows = new ArrayList<>();
            actual.forEach((k, v) -> rows.add(k + " | " + v));
            RatchetSupport.write(RatchetSupport.table(TABLE), HEADER, rows);
        }
        Map<String, Integer> caps = new TreeMap<>();
        RatchetSupport.read(RatchetSupport.table(TABLE), 2).forEach((k, v) -> caps.put(k, Integer.parseInt(v[0])));
        List<String> bad = check(actual, caps);
        assertTrue(bad.isEmpty(), "握りつぶす例外のラチェット（core/src/test/resources/" + TABLE + "）:\n  " + String.join("\n  ", bad));
    }

    // ------------------------------------------------------------------ 検査器自身の検証

    @Test
    void scannerCountsOnlyEmptyBroadCatches() throws IOException {
        RatchetSupport.withTempSources(Map.of("A.java", String.join("\n",
                "class A {",
                "  void a() {",
                "    try { x(); } catch (Exception e) {}",                         // 数える
                "    try { x(); } catch (final Throwable t) {\n    }",            // 数える（改行だけ）
                "    try { x(); } catch (IOException | RuntimeException e) { }",   // 数える（多重の片方が広い）
                "    try { x(); } catch (java.lang.Exception e) {}",               // 数える（完全修飾名）
                "    try { x(); } catch (Exception e) { /* 理由: 任意の拡張 */ }",   // 理由つき: 数えない
                "    try { x(); } catch (Exception e) { log(e); }",                // 本体あり: 数えない
                "    try { x(); } catch (NumberFormatException e) {}",             // 狭い型: 数えない
                "    try { x(); } catch (MyError e) {}",                           // 名前に Error を含むだけ: 数えない
                "    // try { x(); } catch (Exception e) {}",                      // コメントの中: 数えない
                "    String s = \"catch (Exception e) {}\";",                      // 文字列の中: 数えない
                "  }",
                "}")), dir -> assertEquals(Map.of("A.java", 4), scan(dir)));
    }

    @Test
    void checkerCatchesGrowthAndShrink() {
        List<String> bad = check(Map.of("A.java", 3, "B.java", 1), Map.of("A.java", 2, "C.java", 1));
        assertEquals(3, bad.size(), String.join("\n", bad)); // A 増・B 表に無い・C 減
        assertTrue(check(Map.of("A.java", 2), Map.of("A.java", 2)).isEmpty());
    }
}
