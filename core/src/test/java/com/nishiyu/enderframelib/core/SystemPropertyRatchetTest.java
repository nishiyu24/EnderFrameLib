package com.nishiyu.enderframelib.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.common.ShortcutMatrixMain;
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
 * システムプロパティ・環境変数の読み口のラチェット（ENGINE_REARCHITECTURE_PLAN T1 ④。S1 で読み口を {@code EngineFlags} 1 つにする）。
 *
 * <p>main が読むキーを全部 {@code system-properties.txt}（キー | 種類 | 読むファイル | 説明）に載せる。表に無いキー・表にあるのに
 * 読まれないキー・読むファイルの食い違い・キーを文字列リテラルで書かない読み（{@code System.getProperty(name)} など）で失敗する。
 * 種類 {@code SHORTCUT} は近道の組み合わせ検証（{@code :core:shortcutMatrix}）が回すスイッチと一致しなければならない。
 * {@code enderframe.*} を読むファイルの数は {@link #MAX_ENGINE_READER_FILES}（= 1。{@code EngineFlags}）を超えられない。</p>
 */
class SystemPropertyRatchetTest {

    /** {@code enderframe.*} を読むファイルの数の上限（S1 ① で 22 → 1。読むのは {@code core/kernel/EngineFlags.java} だけ）。 */
    static final int MAX_ENGINE_READER_FILES = 1;

    static final List<String> KINDS = List.of(
            "SHORTCUT",    // 近道の切り分け（true で近道を切る）。shortcutMatrix が回す
            "GPU",         // GPU の面の切り替え・調整（GPU が無い CI では効かない）
            "DIAGNOSTIC",  // 計測・検証（既定オフ。テストや計測でだけ立てる）
            "TUNING",      // 数値の調整（スレッド数・キャッシュの大きさ・閾値）
            "COMPAT",      // 従来の挙動へ戻す切り替え（近道ではなく、結果が変わる）
            "PLATFORM");   // OS・利用者の環境（os.name・環境変数など。エンジンの設定ではない）

    private static final String TABLE = "system-properties.txt";
    private static final String HEADER = """
            # SystemPropertyRatchetTest の表（ENGINE_REARCHITECTURE_PLAN T1 ④）: キー | 種類 | 読むファイル（, 区切り） | 説明
            # 環境変数は env:NAME。種類は SHORTCUT / GPU / DIAGNOSTIC / TUNING / COMPAT / PLATFORM。
            # 新しいキーを足すときは S1（EngineFlags への一元化）の後なら EngineFlags だけで読む。""";

    private static final String READERS = "System\\.getProperty|Boolean\\.getBoolean|Integer\\.getInteger|Long\\.getLong|System\\.getenv";
    private static final Pattern LITERAL_READ = Pattern.compile("\\b(" + READERS + ")\\(\\s*\"([^\"]+)\"");
    private static final Pattern ENV_MAP_READ = Pattern.compile("\\bSystem\\.getenv\\(\\s*\\)\\s*\\.\\s*(?:get|getOrDefault)\\(\\s*\"([^\"]+)\"");
    private static final Pattern DYNAMIC_READ = Pattern.compile("\\b(" + READERS + ")\\(\\s*(?![\"\\s)])|\\bSystem\\.getProperties\\(");

    /** 走査の結果: キー → 読むファイル、と、キーがリテラルでない読みの場所。 */
    record Scan(Map<String, Set<String>> keys, List<String> dynamic) {}

    static Scan scan(Path root) throws IOException {
        Map<String, Set<String>> keys = new TreeMap<>();
        List<String> dynamic = new ArrayList<>();
        for (Path p : RatchetSupport.javaFiles(root)) {
            String rel = RatchetSupport.rel(root, p);
            String src = RatchetSupport.stripCommentsAndStrings(Files.readString(p, StandardCharsets.UTF_8), true);
            Matcher m = LITERAL_READ.matcher(src);
            while (m.find()) keys.computeIfAbsent((m.group(1).equals("System.getenv") ? "env:" : "") + m.group(2), k -> new TreeSet<>()).add(rel);
            m = ENV_MAP_READ.matcher(src);
            while (m.find()) keys.computeIfAbsent("env:" + m.group(1), k -> new TreeSet<>()).add(rel);
            m = DYNAMIC_READ.matcher(src);
            while (m.find()) dynamic.add(rel + ":" + (1 + src.substring(0, m.start()).chars().filter(c -> c == '\n').count()));
        }
        return new Scan(keys, dynamic);
    }

    record Entry(String kind, Set<String> files, String note) {}

    private static Map<String, Entry> readTable() throws IOException {
        Map<String, Entry> out = new TreeMap<>();
        for (var e : RatchetSupport.read(RatchetSupport.table(TABLE), 4).entrySet()) {
            String[] v = e.getValue();
            Set<String> files = new TreeSet<>();
            for (String f : v[1].split(",")) if (!f.isBlank()) files.add(f.trim());
            out.put(e.getKey(), new Entry(v[0], files, v[2]));
        }
        return out;
    }

    static List<String> check(Scan scan, Map<String, Entry> table, Set<String> shortcutSwitches) {
        List<String> bad = new ArrayList<>();
        for (String d : scan.dynamic) bad.add("キーを文字列リテラルで書いていない読み（表で追えない）: " + d);
        for (var e : scan.keys.entrySet()) {
            Entry t = table.get(e.getKey());
            if (t == null) bad.add("表に無いキー: " + e.getKey() + " ← " + e.getValue() + "（表に種類と説明を足す。S1 の後は EngineFlags で読む）");
            else if (!t.files.equals(e.getValue())) bad.add("読むファイルが表と違う: " + e.getKey() + " 表 " + t.files + " / 実際 " + e.getValue());
        }
        for (var e : table.entrySet()) {
            if (!scan.keys.containsKey(e.getKey())) bad.add("表にあるが読まれていないキー（表から外す）: " + e.getKey());
            if (!KINDS.contains(e.getValue().kind)) bad.add("種類が不正: " + e.getKey() + " → " + e.getValue().kind + "（" + KINDS + "）");
            if (e.getValue().note.isBlank()) bad.add("説明が空: " + e.getKey());
            boolean isShortcut = e.getValue().kind.equals("SHORTCUT");
            if (isShortcut != shortcutSwitches.contains(e.getKey()))
                bad.add("種類 SHORTCUT と shortcutMatrix のスイッチが食い違う: " + e.getKey()
                        + "（近道を切るスイッチは enderframe.<面>.no<名前> / .off の形にして shortcutMatrix に回させる）");
        }
        return bad;
    }

    static Set<String> engineReaderFiles(Scan scan) {
        Set<String> files = new TreeSet<>();
        scan.keys.forEach((k, v) -> { if (k.startsWith("enderframe.")) files.addAll(v); });
        return files;
    }

    @Test
    void everyPropertyReadIsListed() throws IOException {
        Path root = RatchetSupport.mainRoot();
        Scan scan = scan(root);
        if (RatchetSupport.UPDATE) {
            Map<String, Entry> old = readTable();
            List<String> rows = new ArrayList<>();
            for (var e : scan.keys.entrySet()) {
                Entry o = old.get(e.getKey());
                rows.add(e.getKey() + " | " + (o == null ? "PENDING" : o.kind) + " | " + String.join(", ", e.getValue()) + " | " + (o == null ? "" : o.note));
            }
            RatchetSupport.write(RatchetSupport.table(TABLE), HEADER, rows);
        }
        Set<String> switches = new TreeSet<>(ShortcutMatrixMain.findSwitches(root.getParent().getParent().getParent()));
        List<String> bad = check(scan, readTable(), switches);
        assertTrue(bad.isEmpty(), "システムプロパティの読み口のラチェット（core/src/test/resources/" + TABLE + "）:\n  " + String.join("\n  ", bad));
    }

    @Test
    void engineSettingsAreReadInNoMoreFilesThanBefore() throws IOException {
        Set<String> files = engineReaderFiles(scan(RatchetSupport.mainRoot()));
        assertTrue(files.size() <= MAX_ENGINE_READER_FILES, "enderframe.* を読むファイルが増えた: " + files.size() + " > " + MAX_ENGINE_READER_FILES
                + "（設定は 1 か所で読む〔S1 の EngineFlags〕）\n  " + String.join("\n  ", files));
        assertTrue(files.size() >= MAX_ENGINE_READER_FILES, "減ったので MAX_ENGINE_READER_FILES を " + files.size() + " へ下げる");
    }

    // ------------------------------------------------------------------ 検査器自身の検証

    @Test
    void scannerFindsLiteralEnvAndDynamicReadsButNotComments() throws IOException {
        RatchetSupport.withTempSources(Map.of("a/A.java", String.join("\n",
                "class A {",
                "  static final boolean X = !Boolean.getBoolean(\"enderframe.x.noa\");",
                "  static final int N = Integer.getInteger( \"enderframe.n\", 4);",
                "  static final String H = System.getenv().getOrDefault(\"HOME_DIR\", \"\");",
                "  static final String L = System.getenv(\"LOCAL\");",
                "  static String dyn(String k) { return System.getProperty(k); }",
                "  // Boolean.getBoolean(\"enderframe.commented\")",
                "  /* System.getProperty(\"enderframe.block\") */",
                "  static final String S = \"System.getProperty(\";",
                "}")), dir -> {
            Scan s = scan(dir);
            assertEquals(Set.of("enderframe.x.noa", "enderframe.n", "env:HOME_DIR", "env:LOCAL"), s.keys.keySet());
            assertEquals(List.of("a/A.java:6"), s.dynamic);
            assertEquals(Set.of("a/A.java"), engineReaderFiles(s));
        });
    }

    @Test
    void checkerCatchesUnlistedStaleMovedAndMisclassifiedKeys() {
        Scan scan = new Scan(Map.of(
                "enderframe.a.noa", Set.of("A.java"),       // SHORTCUT として正しい
                "enderframe.b", Set.of("B.java", "C.java"), // 読むファイルが増えた
                "enderframe.new", Set.of("N.java")), List.of("D.java:3")); // 表に無い・動的な読み
        Map<String, Entry> table = Map.of(
                "enderframe.a.noa", new Entry("SHORTCUT", Set.of("A.java"), "x"),
                "enderframe.b", new Entry("TUNING", Set.of("B.java"), "x"),
                "enderframe.gone", new Entry("DIAGNOSTIC", Set.of("G.java"), "x"),   // 読まれていない
                "enderframe.c.nob", new Entry("TUNING", Set.of("A.java"), "x"));     // 近道なのに SHORTCUT でない ＋ 読まれていない
        List<String> bad = check(scan, table, Set.of("enderframe.a.noa", "enderframe.c.nob"));
        assertEquals(6, bad.size(), String.join("\n", bad));
    }
}
