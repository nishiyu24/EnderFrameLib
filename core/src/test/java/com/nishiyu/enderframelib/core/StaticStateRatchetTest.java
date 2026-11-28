package com.nishiyu.enderframelib.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 並列実行（B2 のワーカー）で壊れうる static な可変状態の棚卸しと、その数のラチェット（ENGINE_REARCHITECTURE_PLAN A5）。
 *
 * <p>{@code core.*} の static 欄を 3 種類に分ける。</p>
 * <ol>
 *   <li><b>型で安全</b>: {@code volatile}・{@code ThreadLocal}・{@code Concurrent*}・{@code Atomic*}・{@code LongAdder}・{@code CopyOnWrite*}・
 *       {@code synchronizedMap}。数だけ数えて {@link #MAX_SAFE_BY_TYPE} を超えたら失敗（増やすときは設計を見直してから上げる）。</li>
 *   <li><b>不変の定数</b>（{@code static final} の基本型・{@code List.of} など・空配列・{@code {...}} の配列リテラルで初期化した定数表）: 対象外。</li>
 *   <li><b>それ以外</b>（{@code volatile} でない非 final の欄、{@code final} だが中身を書き換えられる配列・コレクション）:
 *       全件を {@code static-state-classification.txt} に「理由つきの分類」で載せる。載っていない欄が増えたら失敗、
 *       載っているのに無くなった欄があっても失敗（表を現状に保つ）。分類 {@code PENDING}（要対処）は 0 でなければならない。</li>
 * </ol>
 * 分類: {@code IMMUTABLE_TABLE}（静的初期化で埋めた後は読むだけ）・{@code LOCKED}（{@code synchronized} / ロック越しにだけ触る）・
 * {@code RACY_CACHE}（1 スロットの記憶。中身は不変の値で、競合しても取り違えない）・{@code DIAGNOSTIC}（計測用。厳密である必要がない）・
 * {@code PENDING}（要対処）。
 */
class StaticStateRatchetTest {

    /**
     * 型で安全な static 欄の数の上限（減ったら下げる）。増やすなら、本当にグローバルな状態が要るかを先に考える。
     * 158（2026-11-17 の実測値）→ 160（2026-11-18 B11: {@code LayoutTaskPool.CALLER_IN_TASK}〔並列区間で仕事を取っている
     * 呼び出し側の印。スレッドごとの状態〕・{@code DisplayListBuilder.STAT_PREPARED_SUBTREES}〔診断〕）
     * → 165（2026-11-18 B12: 診断の数え上げ 3 つ〔{@code DisplayListBuilder.STAT_STACKING_PATCH_HITS}・
     * {@code PaintSceneDriver.STAT_MOVE_PATCHES}・{@code SoftwareRenderBackend.STAT_SHARED_CLIPS}〕と、テストが切り替える近道の
     * 切り分けの印 2 つ〔{@code DisplayListBuilder.STACKING_PATCH_ENABLED}・{@code SoftwareRenderBackend.CLIP_SHARE}。起動時のスイッチは
     * 近道のスイッチ数のラチェットのため足さない〕）
     * → 166（2026-11-18 B12 続き: {@code PaintOrder.STICKY_IN_SCROLL_NODE}〔sticky を含むスクロールコンテナを節にまとめるかの、テストが切り替える印〕）
     * → 60（2026-11-29 M5-5: 上限を実数に締めた。旧 {@code TextShaper} の {@code ThreadLocal}・
     * 書体の照会用の鍵・{@code BidiText} の作業用の配列を消した後の値にした）。
     */
    private static final int MAX_SAFE_BY_TYPE = 60;

    private static final Pattern STATIC_FIELD = Pattern.compile("^\\s*(?:(?:private|public|protected)\\s+)?static\\s+(.*)$");
    private static final Pattern TYPE_DECL = Pattern.compile("^(final\\s+|abstract\\s+|sealed\\s+)?(class|interface|enum|record)\\b.*");
    private static final Pattern SAFE_TYPE = Pattern.compile("\\b(volatile|ThreadLocal|ConcurrentHashMap|ConcurrentLinkedQueue|ConcurrentLinkedDeque|ConcurrentMap"
            + "|AtomicInteger|AtomicLong|AtomicReference|AtomicBoolean|AtomicIntegerArray|AtomicLongArray|LongAdder|CopyOnWriteArrayList|CopyOnWriteArraySet)\\b");
    private static final Pattern SAFE_INIT = Pattern.compile("synchronizedMap|synchronizedList|synchronizedSet|ConcurrentHashMap|ConcurrentLinkedQueue|ThreadLocal|CopyOnWrite");
    private static final Pattern MUTABLE_TYPE = Pattern.compile("\\b(Map|HashMap|LinkedHashMap|TreeMap|WeakHashMap|IdentityHashMap|List|ArrayList|LinkedList|Set|HashSet|LinkedHashSet|TreeSet"
            + "|Deque|ArrayDeque|Queue|StringBuilder|ByteBuffer|BitSet|ClassValue|Object)\\b");
    private static final Pattern IMMUTABLE_INIT = Pattern.compile("(List|Set|Map)\\.(of|copyOf|ofEntries)\\(|unmodifiable|Arrays\\.asList|new\\s+[\\w.<>]+\\[\\s*0\\s*\\]|new Object\\(\\)"
            + "|=\\s*\\{|new\\s+[\\w.<>]+(\\[\\s*\\])+\\s*\\{");
    private static final List<String> CATEGORIES = List.of("IMMUTABLE_TABLE", "LOCKED", "RACY_CACHE", "DIAGNOSTIC", "PENDING");

    private static Path coreRoot() {
        Path root = Path.of("core/src/main/java/com/nishiyu/enderframelib/core");
        if (!Files.isDirectory(root)) root = Path.of("src/main/java/com/nishiyu/enderframelib/core");
        return root;
    }

    /** 走査の結果: 分類が要る欄（鍵 → 行）と、型で安全な欄の数。 */
    static final class Scan {
        final Map<String, String> needsClass = new TreeMap<>();
        int safeByType;
    }

    static Scan scan(Path root) throws IOException {
        Scan out = new Scan();
        List<Path> files;
        try (Stream<Path> s = Files.walk(root)) {
            files = s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        for (Path p : files) {
            String rel = root.relativize(p).toString().replace('\\', '/');
            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i);
                String t = l.trim();
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) continue;
                Matcher m = STATIC_FIELD.matcher(l);
                if (!m.matches()) continue;
                String head = m.group(1);
                int eq = head.indexOf('=');
                String decl = (eq >= 0 ? head.substring(0, eq) : head);
                int semi = decl.indexOf(';');
                if (semi >= 0) decl = decl.substring(0, semi);
                decl = decl.trim();
                if (decl.isEmpty() || decl.startsWith("{") || decl.contains("(") || TYPE_DECL.matcher(decl).matches() || decl.startsWith("void ")) continue;
                StringBuilder init = new StringBuilder(head);
                for (int j = i; j < Math.min(lines.size() - 1, i + 6) && lines.get(j).indexOf(';') < 0; j++) init.append(' ').append(lines.get(j + 1).trim());
                boolean fin = Pattern.compile("\\bfinal\\b").matcher(decl).find();
                boolean array = Pattern.compile("\\w\\s*\\[\\]\\s+\\w").matcher(decl).find();
                String[] words = decl.split("\\s+");
                String name = words[words.length - 1];
                if (SAFE_TYPE.matcher(decl).find() || SAFE_INIT.matcher(init).find()) {
                    out.safeByType++;
                    continue;
                }
                if (fin) {
                    if (!array && !MUTABLE_TYPE.matcher(decl).find()) continue;
                    if (IMMUTABLE_INIT.matcher(init).find()) continue;
                }
                out.needsClass.put(rel + "#" + name, t.length() > 120 ? t.substring(0, 120) : t);
            }
        }
        return out;
    }

    private static Map<String, String[]> readTable() throws IOException {
        Map<String, String[]> table = new LinkedHashMap<>();
        try (InputStream in = StaticStateRatchetTest.class.getResourceAsStream("/static-state-classification.txt")) {
            assertTrue(in != null, "static-state-classification.txt がテストのリソースに無い");
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] f = line.split("\\|", 3);
                assertEquals(3, f.length, "「鍵 | 分類 | 理由」の 3 欄が要る: " + line);
                table.put(f[0].trim(), new String[] {f[1].trim(), f[2].trim()});
            }
        }
        return table;
    }

    @Test
    void everyUnsafeLookingStaticFieldIsClassified() throws IOException {
        Scan scan = scan(coreRoot());
        Map<String, String[]> table = readTable();
        List<String> unclassified = new ArrayList<>();
        for (var e : scan.needsClass.entrySet()) if (!table.containsKey(e.getKey())) unclassified.add(e.getKey() + "    // " + e.getValue());
        List<String> stale = new ArrayList<>();
        for (String k : table.keySet()) if (!scan.needsClass.containsKey(k)) stale.add(k);
        assertTrue(unclassified.isEmpty(), "分類の無い static 欄（static-state-classification.txt に「鍵 | 分類 | 理由」を足す。"
                + "並列実行で安全にできるなら volatile / Concurrent* / ThreadLocal にする）:\n  " + String.join("\n  ", unclassified));
        assertTrue(stale.isEmpty(), "表にあるが無くなった static 欄（表から消す）:\n  " + String.join("\n  ", stale));
    }

    @Test
    void classificationsAreValidAndNothingIsPending() throws IOException {
        List<String> bad = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        for (var e : readTable().entrySet()) {
            String cat = e.getValue()[0];
            if (!CATEGORIES.contains(cat)) bad.add(e.getKey() + " → " + cat);
            if (cat.equals("PENDING")) pending.add(e.getKey());
            if (e.getValue()[1].isEmpty()) bad.add(e.getKey() + " → 理由が空");
        }
        assertTrue(bad.isEmpty(), "分類が不正: " + bad);
        assertTrue(pending.isEmpty(), "要対処（PENDING）が残っている: " + pending);
    }

    @Test
    void staticStateSafeByTypeCountDoesNotGrow() throws IOException {
        int n = scan(coreRoot()).safeByType;
        assertTrue(n <= MAX_SAFE_BY_TYPE, "型で安全な static 欄が増えた: " + n + " > " + MAX_SAFE_BY_TYPE
                + "（グローバルな状態を足す前に、面ごとの文脈〔A13〕へ持てないかを考える。必要なら上限を上げる）");
    }

    /** 検査器自身の検証: 取りこぼしと取りすぎを防ぐ。 */
    @Test
    void scannerClassifiesSyntheticSources() throws IOException {
        Path dir = Files.createTempDirectory("static-state");
        try {
            Files.writeString(dir.resolve("T.java"), String.join("\n",
                    "class T {",
                    "  static int counter;",                                              // 要分類
                    "  private static final java.util.Map<String,String> M = new java.util.HashMap<>();", // 要分類
                    "  static final int[] TABLE = new int[8];",                           // 要分類
                    "  static final int[] EMPTY = new int[0];",                           // 対象外
                    "  static final int K = 3;",                                          // 対象外
                    "  static final java.util.List<String> L = java.util.List.of(\"a\");", // 対象外
                    "  static volatile boolean flag;",                                    // 型で安全
                    "  static final ThreadLocal<int[]> TL = ThreadLocal.withInitial(() -> new int[1]);", // 型で安全
                    "  static final java.util.Map<String,String> C = java.util.Collections.synchronizedMap(new java.util.HashMap<>());", // 型で安全
                    "  // static int commented;",
                    "  static void m() {}",
                    "  static class Inner {}",
                    "}"), StandardCharsets.UTF_8);
            Scan s = scan(dir);
            assertEquals(List.of("T.java#M", "T.java#TABLE", "T.java#counter"), new ArrayList<>(s.needsClass.keySet()));
            assertEquals(3, s.safeByType);
        } finally {
            try (Stream<Path> w = Files.walk(dir)) {
                w.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }
}
