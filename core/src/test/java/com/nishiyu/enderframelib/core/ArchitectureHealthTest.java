package com.nishiyu.enderframelib.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.core.kernel.EngineFlags;
import com.nishiyu.enderframelib.core.kernel.Shortcut;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Engine v3 アーキテクチャ健全性ゲート（計画書 §3.1 Phase M0）。
 *
 * <p>次の 5 項目を機械的に検査し、新規の肥大化・グローバル可変状態・パッケージ循環・境界汚染・近道スイッチ追加を阻止する:</p>
 * <ol>
 *   <li>クラス行数上限: 猶予リスト外のファイルは {@value #NEW_CLASS_MAX_LINES} 行以下、1,000 行超クラス数の上限凍結。</li>
 *   <li>グローバル mutable static 数: {@code static volatile}・{@code ThreadLocal}・{@code Atomic*}/{@code LongAdder}・{@code *EPOCH} の上限凍結。</li>
 *   <li>パッケージ循環数（SCC）: {@code :core} と MOD 本体の強連結成分（SCC）の増加・拡大禁止。</li>
 *   <li>{@code :core} 境界純度: Minecraft / NeoForge / 上位層 / プレビュー API への非依存。</li>
 *   <li>既存 {@code Shortcut} / {@code EngineFlags} の上限凍結: 新規追加を禁止し、単調減少のみ許可。</li>
 * </ol>
 */
class ArchitectureHealthTest {

    static final int NEW_CLASS_MAX_LINES = 600;
    static final int MAX_OVER_1000_CLASSES = 26;
    static final int MAX_VOLATILE_STATIC = 49;
    static final int MAX_THREAD_LOCAL_STATIC = 26;
    static final int MAX_ATOMIC_STATIC = 50;
    static final int MAX_EPOCH_STATIC = 5;
    static final int MAX_CORE_SCC = 1;
    static final int MAX_MOD_SCC = 3;
    static final int MAX_SHORTCUTS = 33;
    static final int MAX_ENGINE_FLAGS = 43;

    private static final String BASE = "com.nishiyu.enderframelib.";
    private static final String CEILINGS_TABLE = "architecture-health-ceilings.txt";
    private static final List<String> ALL_MODULE_MAIN_ROOTS = List.of(
            "core/src/main/java", "core-gl/src/main/java", "core-vulkan/src/main/java",
            "core-glfw/src/main/java", "src/main/java");
    private static final List<String> PURE_JAVA_MAIN_ROOTS = List.of(
            "core/src/main/java", "core-gl/src/main/java", "core-vulkan/src/main/java",
            "core-glfw/src/main/java");

    private static final Set<String> FROZEN_SHORTCUTS = Set.of(
            "SHEET_CACHE", "FRESH_LAYOUT_PASS_REUSE", "RESIZE_SKIP_REBUILD",
            "DEFER_SCROLL_LAYOUT", "FLOAT_MEMO",
            "FLEX_SKIP_SAME_WIDTH", "BLOCK_PERCENT_FREE", "DEFINITE_LEAF_REUSE", "TENTATIVE_REUSE",
            "CUTOFF", "SAME_PASS_REUSE", "WIDTH_INDEPENDENT_REUSE", "VIEWPORT_WIDTH_FREE_REUSE",
            "FIT_REUSE", "LAYOUT_PARALLEL", "WINDOWED", "SCROLL_CONTENT_NODES",
            "GL_RECT_TREES", "GL_BOUNDED_LAYERS", "GL_DOWNSAMPLED_BLUR", "GL_RETAINED_SCROLL",
            "GL_REPLAY", "STICKY_IN_SCROLL_NODE", "REUSE_IDLE_POSES", "CULL_RECORDED_RANGES",
            "RETAIN_SCROLL_LAYERS", "SOFT_DAMAGE", "CLIP_SHARE", "SOFT_CULL_OFFSCREEN",
            "SOFT_GL_REPLAY", "SOFT_FAST_SOLID", "TEXT_SETUP_REUSE", "SOFT_TILED");

    private static final Set<String> FROZEN_ENGINE_FLAGS = Set.of(
            "BLOCK_PERCENT_FREE", "KERNEL_STATS", "CUTOFF", "DEFINITE_LEAF_REUSE",
            "FIT_REUSE", "FLEX_SKIP_SAME_WIDTH", "FLOAT_MEMO", "FRESH_LAYOUT_PASS_REUSE",
            "LAZY_THROUGH_INELIGIBLE", "LAYOUT_PARALLEL", "RESIZE_SKIP_REBUILD", "SAME_PASS_REUSE",
            "TENTATIVE_REUSE", "VIEWPORT_WIDTH_FREE_REUSE", "WIDTH_INDEPENDENT_REUSE",
            "CULL_RECORDED_RANGES", "PARENT_RELATIVE", "SOFT_GL_REPLAY", "SCROLL_CONTENT_NODES",
            "TEXT_SETUP_REUSE", "WINDOWED", "SHEET_CACHE", "SOFT_CULL_OFFSCREEN", "SOFT_DAMAGE",
            "SOFT_FAST_SOLID", "SOFT_TILED", "KEEP_NEWLINE_WHITESPACE",
            "CULL_MARGIN", "WINDOW_MIN_OWNERS", "RASTER_PARALLEL", "SPLIT_PER_PARTICIPANT",
            "TEXT_CACHE", "THREADS", "LAYOUT_PROBE",
            "PROFILE", "PROFILE_ALLOC", "SOFT_DAMAGE_DEBUG", "GL_DOWNSAMPLED_BLUR",
            "GL_BOUNDED_LAYERS", "GL_REPLAY", "GL_RECT_CAPACITY", "GL_RECT_TREES",
            "GL_RETAINED_SCROLL");

    private static final List<Set<String>> FROZEN_CORE_SCCS = List.of(Set.of("api.dom", "api.event"));
    private static final List<Set<String>> FROZEN_MOD_SCCS = List.of(
            Set.of("api", "api.state"),
            Set.of("client", "client.gui", "client.gui.desktop", "client.renderer", "client.renderer.surface"),
            Set.of("network", "server", "server.state"));

    private static final Pattern STATIC_FIELD_DECL = Pattern.compile(
            "^\\s*(?:(?:public|private|protected)\\s+)?static\\s+(?!class\\b|interface\\b|enum\\b|record\\b|void\\b)([^(=;]+?)\\s+(\\w+)\\s*(?:=|;)");
    private static final Pattern VOLATILE_MOD = Pattern.compile("\\bvolatile\\b");
    private static final Pattern THREAD_LOCAL_TYPE = Pattern.compile("\\bThreadLocal\\b");
    private static final Pattern ATOMIC_TYPE = Pattern.compile(
            "\\b(AtomicInteger|AtomicLong|AtomicReference|AtomicBoolean|AtomicIntegerArray|AtomicLongArray|LongAdder|DoubleAdder)\\b");
    private static final Pattern PACKAGE_DECL = Pattern.compile("^\\s*package\\s+([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern IMPORT_DECL = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.]+?)(?:\\.\\*)?;", Pattern.MULTILINE);
    private static final Pattern FQN_REF = Pattern.compile(Pattern.quote(BASE) + "([a-z]\\w*(?:\\.[a-z]\\w*)*)(?:\\.[A-Z]\\w*)?");

    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("settings.gradle"))) p = p.getParent();
        assertTrue(p != null, "リポジトリルートが見つからない");
        return p;
    }

    // ---- 1. クラス行数上限 ----

    record LineCheckResult(List<String> violations, int over1000Count) {}

    static LineCheckResult checkLineCounts(Map<String, Integer> actualLines, Map<String, Integer> ceilings) {
        List<String> bad = new ArrayList<>();
        int over1000 = 0;
        for (var e : actualLines.entrySet()) {
            int lines = e.getValue();
            if (lines > 1000) over1000++;
            Integer cap = ceilings.get(e.getKey());
            if (cap == null) {
                if (lines > NEW_CLASS_MAX_LINES) {
                    bad.add("新規・猶予対象外のクラスが " + NEW_CLASS_MAX_LINES + " 行を超過: " + e.getKey() + " (" + lines + " 行)");
                }
            } else if (lines > cap) {
                bad.add("クラス行数が上限を超過: " + e.getKey() + " (" + lines + " > " + cap + " 行)");
            }
        }
        return new LineCheckResult(bad, over1000);
    }

    @Test
    void classLineCountsAreBounded() throws IOException {
        Path root = repoRoot();
        Map<String, Integer> actual = new TreeMap<>();
        for (String moduleRoot : ALL_MODULE_MAIN_ROOTS) {
            Path dir = root.resolve(moduleRoot);
            if (!Files.isDirectory(dir)) continue;
            for (Path f : RatchetSupport.javaFiles(dir)) {
                String rel = RatchetSupport.rel(root, f);
                actual.put(rel, Files.readAllLines(f, StandardCharsets.UTF_8).size());
            }
        }
        assertTrue(actual.size() >= 600, "走査したクラス数が少なすぎる: " + actual.size());

        Map<String, Integer> ceilings = new TreeMap<>();
        RatchetSupport.read(RatchetSupport.table(CEILINGS_TABLE), 2)
                .forEach((k, v) -> ceilings.put(k, Integer.parseInt(v[0])));

        LineCheckResult result = checkLineCounts(actual, ceilings);
        assertTrue(result.violations().isEmpty(),
                "クラス行数上限違反（新規・刷新クラスは " + NEW_CLASS_MAX_LINES + " 行以下、既存は単調減少）:\n  "
                        + String.join("\n  ", result.violations()));
        assertTrue(result.over1000Count() <= MAX_OVER_1000_CLASSES,
                "1,000 行超クラス数が増加: " + result.over1000Count() + " > " + MAX_OVER_1000_CLASSES);
    }

    // ---- 2. グローバル mutable static 数 ----

    record StaticCounts(int volatileFields, int threadLocals, int atomics, int epochs) {}

    static StaticCounts countGlobalMutableStatics(Path dir) throws IOException {
        int vol = 0, tl = 0, atom = 0, epoch = 0;
        for (Path f : RatchetSupport.javaFiles(dir)) {
            String stripped = RatchetSupport.stripCommentsAndStrings(Files.readString(f, StandardCharsets.UTF_8), false);
            for (String line : stripped.split("\\R")) {
                Matcher m = STATIC_FIELD_DECL.matcher(line);
                if (!m.find()) continue;
                String decl = m.group(1);
                String name = m.group(2);
                if (VOLATILE_MOD.matcher(decl).find()) vol++;
                if (THREAD_LOCAL_TYPE.matcher(decl).find()) tl++;
                if (ATOMIC_TYPE.matcher(decl).find()) atom++;
                if (name.endsWith("EPOCH") && !decl.contains("int")) epoch++;
            }
        }
        return new StaticCounts(vol, tl, atom, epoch);
    }

    @Test
    void globalMutableStaticsAreBounded() throws IOException {
        Path coreMain = repoRoot().resolve("core/src/main/java/com/nishiyu/enderframelib");
        StaticCounts c = countGlobalMutableStatics(coreMain);
        assertTrue(c.volatileFields() >= 40 && c.atomics() >= 40, "検出器が壊れている: " + c);
        assertTrue(c.volatileFields() <= MAX_VOLATILE_STATIC,
                "static volatile が増加: " + c.volatileFields() + " > " + MAX_VOLATILE_STATIC);
        assertTrue(c.threadLocals() <= MAX_THREAD_LOCAL_STATIC,
                "static ThreadLocal が増加: " + c.threadLocals() + " > " + MAX_THREAD_LOCAL_STATIC);
        assertTrue(c.atomics() <= MAX_ATOMIC_STATIC,
                "static Atomic/LongAdder が増加: " + c.atomics() + " > " + MAX_ATOMIC_STATIC);
        assertTrue(c.epochs() <= MAX_EPOCH_STATIC,
                "共有 EPOCH カウンタが増加: " + c.epochs() + " > " + MAX_EPOCH_STATIC);
    }

    // ---- 3. パッケージ循環数（SCC） ----

    static List<TreeSet<String>> packageSccs(Path sourceBaseRoot) throws IOException {
        Map<String, Set<String>> g = new TreeMap<>();
        for (Path f : RatchetSupport.javaFiles(sourceBaseRoot)) {
            String src = RatchetSupport.stripCommentsAndStrings(Files.readString(f, StandardCharsets.UTF_8), false);
            Matcher pm = PACKAGE_DECL.matcher(src);
            if (!pm.find() || !pm.group(1).startsWith(BASE)) continue;
            String own = pm.group(1).substring(BASE.length());
            Set<String> refs = g.computeIfAbsent(own, k -> new TreeSet<>());
            Matcher im = IMPORT_DECL.matcher(src);
            while (im.find()) {
                String name = im.group(1);
                if (!name.startsWith(BASE)) continue;
                String pkg = packageOf(name.substring(BASE.length()));
                if (!pkg.isEmpty() && !pkg.equals(own)) refs.add(pkg);
            }
            Matcher fm = FQN_REF.matcher(src.replaceAll("(?m)^\\s*(import|package)\\s[^;]*;", ""));
            while (fm.find()) {
                String pkg = fm.group(1);
                if (!pkg.equals(own)) refs.add(pkg);
            }
        }
        for (Set<String> refs : g.values()) refs.retainAll(g.keySet());
        return tarjanSccs(g);
    }

    private static String packageOf(String dotted) {
        StringBuilder sb = new StringBuilder();
        for (String part : dotted.split("\\.")) {
            if (part.isEmpty() || Character.isUpperCase(part.charAt(0))) break;
            if (sb.length() > 0) sb.append('.');
            sb.append(part);
        }
        return sb.toString();
    }

    private static List<TreeSet<String>> tarjanSccs(Map<String, Set<String>> g) {
        Map<String, Integer> idx = new HashMap<>(), low = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> onStack = new TreeSet<>();
        List<TreeSet<String>> sccs = new ArrayList<>();
        int[] nextIdx = {0};
        for (String v : g.keySet()) {
            if (!idx.containsKey(v)) strongConnect(v, g, idx, low, stack, onStack, nextIdx, sccs);
        }
        sccs.sort((a, b) -> a.first().compareTo(b.first()));
        return sccs;
    }

    private static void strongConnect(String v, Map<String, Set<String>> g, Map<String, Integer> idx,
                                      Map<String, Integer> low, Deque<String> stack, Set<String> onStack,
                                      int[] nextIdx, List<TreeSet<String>> sccs) {
        idx.put(v, nextIdx[0]);
        low.put(v, nextIdx[0]++);
        stack.push(v);
        onStack.add(v);
        for (String w : g.getOrDefault(v, Set.of())) {
            if (!idx.containsKey(w)) {
                strongConnect(w, g, idx, low, stack, onStack, nextIdx, sccs);
                low.put(v, Math.min(low.get(v), low.get(w)));
            } else if (onStack.contains(w)) {
                low.put(v, Math.min(low.get(v), idx.get(w)));
            }
        }
        if (low.get(v).equals(idx.get(v))) {
            TreeSet<String> comp = new TreeSet<>();
            String w;
            do {
                w = stack.pop();
                onStack.remove(w);
                comp.add(w);
            } while (!w.equals(v));
            if (comp.size() > 1) sccs.add(comp);
        }
    }

    static List<String> checkSccs(List<TreeSet<String>> actual, List<Set<String>> frozen, int maxCount) {
        List<String> bad = new ArrayList<>();
        if (actual.size() > maxCount) {
            bad.add("パッケージ循環数（SCC）が増加: " + actual.size() + " > " + maxCount + " " + actual);
        }
        for (TreeSet<String> scc : actual) {
            boolean allowed = frozen.stream().anyMatch(f -> f.containsAll(scc));
            if (!allowed) bad.add("新規・拡大したパッケージ循環（SCC）: " + scc);
        }
        return bad;
    }

    @Test
    void packageCyclesDoNotGrow() throws IOException {
        Path root = repoRoot();
        List<TreeSet<String>> coreSccs = packageSccs(root.resolve("core/src/main/java/com/nishiyu/enderframelib"));
        List<String> coreBad = checkSccs(coreSccs, FROZEN_CORE_SCCS, MAX_CORE_SCC);
        assertTrue(coreBad.isEmpty(), ":core のパッケージ循環違反: " + String.join("; ", coreBad));

        List<TreeSet<String>> modSccs = packageSccs(root.resolve("src/main/java/com/nishiyu/enderframelib"));
        List<String> modBad = checkSccs(modSccs, FROZEN_MOD_SCCS, MAX_MOD_SCC);
        assertTrue(modBad.isEmpty(), "MOD 側のパッケージ循環違反: " + String.join("; ", modBad));
    }

    // ---- 4. :core 境界純度（Minecraft 非依存） ----

    static List<String> checkCorePurity(Path root) throws IOException {
        List<String> violations = new ArrayList<>();
        Path corePkgRoot = root.resolve("core/src/main/java/com/nishiyu/enderframelib");
        for (String forbiddenDir : List.of("client", "server", "content")) {
            if (Files.exists(corePkgRoot.resolve(forbiddenDir))) {
                violations.add(":core に禁止ディレクトリが存在: " + forbiddenDir);
            }
        }
        List<String> forbiddenRefs = List.of(
                "net.minecraft.", "net.neoforged.", "com.mojang.",
                "com.nishiyu.enderframelib.client.", "com.nishiyu.enderframelib.server.",
                "com.nishiyu.enderframelib.network.", "com.nishiyu.enderframelib.gametest.",
                "com.nishiyu.enderframelib.mixin.", "com.nishiyu.enderframelib.content.",
                "java.lang.foreign.");
        for (String moduleRoot : PURE_JAVA_MAIN_ROOTS) {
            Path dir = root.resolve(moduleRoot);
            if (!Files.isDirectory(dir)) continue;
            for (Path f : RatchetSupport.javaFiles(dir)) {
                String code = RatchetSupport.stripCommentsAndStrings(Files.readString(f, StandardCharsets.UTF_8), false);
                String rel = RatchetSupport.rel(root, f);
                for (String ref : forbiddenRefs) {
                    if (code.contains(ref)) violations.add(rel + " -> " + ref);
                }
            }
        }
        Path coreLayerRoot = corePkgRoot.resolve("core");
        Pattern outwardApi = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?com\\.nishiyu\\.enderframelib\\.(?:common\\.|api\\.(?!(?:dom|event)\\.))");
        for (Path f : RatchetSupport.javaFiles(coreLayerRoot)) {
            String code = RatchetSupport.stripCommentsAndStrings(Files.readString(f, StandardCharsets.UTF_8), false);
            if (outwardApi.matcher(code).find()) {
                violations.add(RatchetSupport.rel(root, f) + " -> common/api 逆参照");
            }
        }
        return violations;
    }

    @Test
    void coreModuleBoundaryPurity() throws IOException {
        List<String> violations = checkCorePurity(repoRoot());
        assertEquals(List.of(), violations, ":core 境界純度違反（Minecraft・上位層非依存）");
    }

    // ---- 5. Shortcut / EngineFlags 上限凍結（新規追加禁止・単調減少のみ） ----

    static List<String> checkFrozenSwitches(List<String> actualShortcuts, List<String> actualFlags) {
        List<String> bad = new ArrayList<>();
        if (actualShortcuts.size() > MAX_SHORTCUTS) {
            bad.add("Shortcut 数が増加: " + actualShortcuts.size() + " > " + MAX_SHORTCUTS);
        }
        for (String s : actualShortcuts) {
            if (!FROZEN_SHORTCUTS.contains(s)) {
                bad.add("新規 Shortcut の追加は禁止（M0 凍結）: " + s);
            }
        }
        if (actualFlags.size() > MAX_ENGINE_FLAGS) {
            bad.add("EngineFlags 欄数が増加: " + actualFlags.size() + " > " + MAX_ENGINE_FLAGS);
        }
        for (String f : actualFlags) {
            if (!FROZEN_ENGINE_FLAGS.contains(f)) {
                bad.add("新規 EngineFlags の追加は禁止（M0 凍結）: " + f);
            }
        }
        return bad;
    }

    @Test
    void shortcutAndEngineFlagsAreFrozen() {
        List<String> shortcuts = Arrays.stream(Shortcut.values()).map(Enum::name).toList();
        List<String> flags = Arrays.stream(EngineFlags.class.getDeclaredFields())
                .filter(f -> !f.isSynthetic())
                .map(Field::getName)
                .toList();
        List<String> bad = checkFrozenSwitches(shortcuts, flags);
        assertEquals(List.of(), bad, "Shortcut / EngineFlags 上限凍結違反");
    }

    // ---- 検査器自身の検証 ----

    @Test
    void detectorsCatchSyntheticViolations() throws IOException {
        LineCheckResult lines = checkLineCounts(
                Map.of("core/NewBig.java", 601, "core/Old.java", 701, "core/Ok.java", 600),
                Map.of("core/Old.java", 700));
        assertEquals(2, lines.violations().size(), lines.violations().toString());

        List<String> swBad = checkFrozenSwitches(
                List.of("CUTOFF", "BRAND_NEW_SHORTCUT"),
                List.of("CUTOFF", "NEW_FLAG"));
        assertEquals(2, swBad.size(), swBad.toString());

        List<String> sccBad = checkSccs(
                List.of(new TreeSet<>(Set.of("a", "b")), new TreeSet<>(Set.of("api.dom", "api.event", "extra"))),
                FROZEN_CORE_SCCS, 1);
        assertEquals(3, sccBad.size(), sccBad.toString());

        RatchetSupport.withTempSources(Map.of("Bad.java",
                "class Bad {\n  static volatile int v;\n  static final ThreadLocal<int[]> tl = null;\n  static long SHAPE_EPOCH;\n}"),
                dir -> {
                    StaticCounts sc = countGlobalMutableStatics(dir);
                    assertEquals(1, sc.volatileFields());
                    assertEquals(1, sc.threadLocals());
                    assertEquals(1, sc.epochs());
                });
        assertFalse(checkFrozenSwitches(List.of("CUTOFF"), List.of("CUTOFF")).stream().findAny().isPresent());
    }
}
