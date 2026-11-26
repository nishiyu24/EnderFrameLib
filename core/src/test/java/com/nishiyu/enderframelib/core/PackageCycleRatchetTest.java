package com.nishiyu.enderframelib.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 同一層の中のパッケージ循環（SCC）と、使われていないワイルドカード {@code import} のラチェット
 * （ENGINE_REARCHITECTURE_PLAN S5 ①）。層間の逆参照は {@link LayerDependencyRatchetTest} が見る。ここは「層が同じでも、
 * パッケージが互いを参照し合って 1 つの塊になる」ことを見張る。
 *
 * <p>表は {@code package-cycles.txt}（1 行 = 1 つの循環の塊。パッケージをコンマで並べる）。塊は <b>広がってはならない</b>
 * （新しい塊・別の塊との併合・パッケージの追加は失敗）。解消したら表を下げる（{@code -Dratchet.update=true}）。目標は空。
 */
class PackageCycleRatchetTest {
    private static final String BASE = "com.nishiyu.enderframelib.";
    private static final String TABLE = "package-cycles.txt";
    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+(static\\s+)?([\\w.]+?)(\\.\\*)?;", Pattern.MULTILINE);
    private static final Pattern FQN = Pattern.compile(Pattern.quote(BASE) + "([a-z]\\w*(?:\\.[a-z]\\w*)*)(?:\\.[A-Z]\\w*)?");

    /** 本体のソース木 {@code com/nishiyu/enderframelib}。 */
    static Path root() {
        Path p = Path.of("core/src/main/java/com/nishiyu/enderframelib");
        return Files.isDirectory(p) ? p : Path.of("src/main/java/com/nishiyu/enderframelib");
    }

    /** 1 ファイルの（自分のパッケージ, 参照先のパッケージ集合）。参照先は {@code BASE} 以下だけ（BASE を除いた形）。 */
    static Map.Entry<String, Set<String>> packageRefs(String src) {
        Matcher pm = PACKAGE.matcher(src);
        if (!pm.find() || !pm.group(1).startsWith(BASE)) return null;
        String own = pm.group(1).substring(BASE.length());
        String body = RatchetSupport.stripCommentsAndStrings(src, true);
        Set<String> refs = new TreeSet<>();
        Matcher im = IMPORT.matcher(body);
        while (im.find()) {
            String name = im.group(2);
            if (!name.startsWith(BASE)) continue;
            String pkg = packageOf(name.substring(BASE.length()), im.group(3) != null, im.group(1) != null);
            if (!pkg.isEmpty()) refs.add(pkg);
        }
        Matcher fm = FQN.matcher(body.replaceAll("(?m)^\\s*(import|package)\\s[^;]*;", ""));
        while (fm.find()) refs.add(fm.group(1));
        refs.remove(own);
        return Map.entry(own, refs);
    }

    /** {@code core.css.ColorParser} / {@code core.css.model} などから、パッケージ部分（大文字始まりの手前まで）。 */
    static String packageOf(String dotted, boolean wildcard, boolean isStatic) {
        StringBuilder sb = new StringBuilder();
        for (String part : dotted.split("[.]")) {
            if (Character.isUpperCase(part.charAt(0))) break;
            if (sb.length() > 0) sb.append('.');
            sb.append(part);
        }
        return sb.toString();
    }

    /** パッケージの依存グラフ（パッケージ → 参照先のパッケージ。本体にあるパッケージだけ）。 */
    static Map<String, Set<String>> graph() throws IOException {
        Path root = root();
        Map<String, Set<String>> g = new TreeMap<>();
        List<Path> files = RatchetSupport.javaFiles(root);
        for (Path f : files) {
            var e = packageRefs(Files.readString(f));
            if (e == null) continue;
            g.computeIfAbsent(e.getKey(), k -> new TreeSet<>()).addAll(e.getValue());
        }
        for (Set<String> refs : g.values()) refs.retainAll(g.keySet());
        return g;
    }

    /** 強連結成分（Tarjan）のうち、2 つ以上のパッケージからなるもの。各塊はパッケージ名の昇順。 */
    static List<TreeSet<String>> cycles(Map<String, Set<String>> g) {
        Map<String, Integer> index = new HashMap<>(), low = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> on = new TreeSet<>();
        List<TreeSet<String>> out = new ArrayList<>();
        int[] counter = {0};
        for (String v : g.keySet()) if (!index.containsKey(v)) strong(v, g, index, low, stack, on, counter, out);
        out.sort((a, b) -> a.first().compareTo(b.first()));
        return out;
    }

    private static void strong(String v, Map<String, Set<String>> g, Map<String, Integer> index, Map<String, Integer> low,
                               Deque<String> stack, Set<String> on, int[] counter, List<TreeSet<String>> out) {
        index.put(v, counter[0]);
        low.put(v, counter[0]++);
        stack.push(v);
        on.add(v);
        for (String w : g.getOrDefault(v, Set.of())) {
            if (!index.containsKey(w)) {
                strong(w, g, index, low, stack, on, counter, out);
                low.put(v, Math.min(low.get(v), low.get(w)));
            } else if (on.contains(w)) {
                low.put(v, Math.min(low.get(v), index.get(w)));
            }
        }
        if (low.get(v).equals(index.get(v))) {
            TreeSet<String> comp = new TreeSet<>();
            String w;
            do {
                w = stack.pop();
                on.remove(w);
                comp.add(w);
            } while (!w.equals(v));
            if (comp.size() > 1) out.add(comp);
        }
    }

    private static List<TreeSet<String>> frozen() throws IOException {
        List<TreeSet<String>> out = new ArrayList<>();
        for (var e : RatchetSupport.read(RatchetSupport.table(TABLE), 1 + 1).entrySet()) {
            // 形式: 先頭のパッケージ | 全パッケージ（コンマ区切り）
            out.add(new TreeSet<>(List.of(e.getValue()[0].split("\\s*,\\s*"))));
        }
        return out;
    }

    private static String describe(TreeSet<String> c) {
        return c.first() + " | " + String.join(", ", c);
    }

    @Test
    void packageCyclesOnlyShrink() throws IOException {
        List<TreeSet<String>> found = cycles(graph());
        if (RatchetSupport.UPDATE) {
            List<String> rows = new ArrayList<>();
            for (TreeSet<String> c : found) rows.add(describe(c));
            RatchetSupport.write(RatchetSupport.table(TABLE), "# PackageCycleRatchetTest の表（ENGINE_REARCHITECTURE_PLAN S5 ①）: 代表のパッケージ | 循環の塊（パッケージをコンマ区切り）\n"
                    + "#   同一層の中でパッケージが互いを参照し合う塊。広げない。解消したら下げる（-Dratchet.update=true）。目標は空。", rows);
            return;
        }
        List<TreeSet<String>> frozen = frozen();
        List<String> problems = new ArrayList<>();
        for (TreeSet<String> c : found) {
            boolean within = frozen.stream().anyMatch(f -> f.containsAll(c));
            if (!within) problems.add("新しい・広がった循環: " + describe(c));
        }
        for (TreeSet<String> f : frozen) {
            if (found.stream().noneMatch(c -> c.equals(f))) problems.add("解消した・縮んだ循環（表を下げる）: " + describe(f));
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    // ---- 検査器自身の検証 ----

    @Test
    void detectsACycleAndIgnoresAcyclicChains() {
        Map<String, Set<String>> g = new LinkedHashMap<>();
        g.put("a", new TreeSet<>(Set.of("b")));
        g.put("b", new TreeSet<>(Set.of("c")));
        g.put("c", new TreeSet<>(Set.of("a")));
        g.put("d", new TreeSet<>(Set.of("a"))); // d は a へ向かうだけ（循環の外）
        List<TreeSet<String>> cs = cycles(g);
        assertEquals(1, cs.size());
        assertEquals(new TreeSet<>(Set.of("a", "b", "c")), cs.get(0));
    }

    @Test
    void parsesImportsWildcardsAndFullyQualifiedNames() {
        String src = "package com.nishiyu.enderframelib.core.css;\n"
                + "import com.nishiyu.enderframelib.core.dom.node.HtmlNode;\n"
                + "import com.nishiyu.enderframelib.core.event.*;\n"
                + "import static com.nishiyu.enderframelib.core.kernel.Shortcut.on;\n"
                + "import java.util.List;\n"
                + "// import com.nishiyu.enderframelib.core.paint.tree.PaintView;\n"
                + "class A { Object o = com.nishiyu.enderframelib.core.paint.model.DisplayItem.class; }";
        var e = packageRefs(src);
        assertEquals("core.css", e.getKey());
        assertEquals(new TreeSet<>(Set.of("core.dom.node", "core.event", "core.kernel", "core.paint.model")), e.getValue());
    }
}
