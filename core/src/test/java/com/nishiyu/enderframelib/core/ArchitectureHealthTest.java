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
 *   <li>新経路のレイアウト（{@code core/layout/fragment}）は描画だけの群（{@code Style.inheritedPaint}・{@code Style.visual}）を読まない
 *       （M2c。描画だけの変化はレイアウトの入力の木を変えないので、読むと古い値を読む。計画書 §3.4.2-3）。</li>
 *   <li>新経路（{@link #NEW_PATH_SOURCES}）は {@code Shortcut}・{@code EngineFlags}・文脈の切り替え・{@code static volatile}・
 *       {@code ThreadLocal} を読まない（計画書 §5-4。状態は 1 ビューに閉じ、旧経路の切り分けの切り替えは旧経路と一緒に消える）。</li>
 *   <li>旧レイアウト経路の型（{@link #LEGACY_PATH_TYPES}）が全モジュールの本体・テストに戻らない（M2c-3 の完了ゲート）。</li>
 *   <li>描画（{@code core/paint}）は DOM の節の属性・文字・状態の規則の照合を読まない（M3b-1・決定 F。描画が読む値は箱の木が
 *       {@code BoxNode.values} に写す）。</li>
 *   <li>DOM の木の形を写した番号の表（{@link #LEGACY_DOM_ARENA_TYPES}）が戻らない（M5-1）。</li>
 * </ol>
 */
class ArchitectureHealthTest {

    static final int NEW_CLASS_MAX_LINES = 600;
    static final int MAX_OVER_1000_CLASSES = 20;
    static final int MAX_VOLATILE_STATIC = 40;
    static final int MAX_THREAD_LOCAL_STATIC = 14;
    static final int MAX_ATOMIC_STATIC = 18;
    static final int MAX_EPOCH_STATIC = 0;
    static final int MAX_CORE_SCC = 1;
    static final int MAX_MOD_SCC = 3;
    static final int MAX_SHORTCUTS = 10;
    static final int MAX_ENGINE_FLAGS = 18;

    private static final String BASE = "com.nishiyu.enderframelib.";
    private static final String CEILINGS_TABLE = "architecture-health-ceilings.txt";
    private static final List<String> ALL_MODULE_MAIN_ROOTS = List.of(
            "core/src/main/java", "core-gl/src/main/java", "core-vulkan/src/main/java",
            "core-glfw/src/main/java", "mod/src/main/java");
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
            "STICKY_IN_SCROLL_NODE", "CULL_RECORDED_RANGES",
            "CLIP_SHARE", "SOFT_CULL_OFFSCREEN",
            "SOFT_FAST_SOLID", "TEXT_SETUP_REUSE", "SOFT_TILED");

    private static final Set<String> FROZEN_ENGINE_FLAGS = Set.of(
            "BLOCK_PERCENT_FREE", "KERNEL_STATS", "CUTOFF", "DEFINITE_LEAF_REUSE",
            "FIT_REUSE", "FLEX_SKIP_SAME_WIDTH", "FLOAT_MEMO", "FRESH_LAYOUT_PASS_REUSE",
            "LAZY_THROUGH_INELIGIBLE", "LAYOUT_PARALLEL", "RESIZE_SKIP_REBUILD", "SAME_PASS_REUSE",
            "TENTATIVE_REUSE", "VIEWPORT_WIDTH_FREE_REUSE", "WIDTH_INDEPENDENT_REUSE",
            "PARENT_RELATIVE", "SCROLL_CONTENT_NODES",
            "TEXT_SETUP_REUSE", "WINDOWED", "SHEET_CACHE", "SOFT_CULL_OFFSCREEN",
            "SOFT_FAST_SOLID", "SOFT_TILED", "KEEP_NEWLINE_WHITESPACE",
            "WINDOW_MIN_OWNERS", "RASTER_PARALLEL", "SPLIT_PER_PARTICIPANT",
            "TEXT_CACHE", "THREADS", "LAYOUT_PROBE",
            "PROFILE", "PROFILE_ALLOC", "GL_DOWNSAMPLED_BLUR",
            "GL_BOUNDED_LAYERS", "GL_RECT_CAPACITY", "GL_RECT_TREES",
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
        assertTrue(c.volatileFields() >= 20 && c.atomics() >= 10, "検出器が壊れている: " + c);
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

        List<TreeSet<String>> modSccs = packageSccs(root.resolve("mod/src/main/java/com/nishiyu/enderframelib"));
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

    // ---- 6. 新経路のレイアウトは描画だけの群を読まない（M2c） ----

    /** 描画だけの群とその読み手（{@code Style} の補助）。 */
    private static final Pattern PAINT_GROUP_READ = Pattern.compile(
            "\\.(inheritedPaint|visual)\\b|\\b(InheritedPaintStyle|VisualStyle)\\b|\\.(visPx|borderColor|outlineColor|columnRuleColor)\\(");

    static List<String> checkPaintGroupReads(Path dir) throws IOException {
        return checkCodeLines(dir, PAINT_GROUP_READ);
    }

    /** {@code path}（ディレクトリなら配下の .java 全て、ファイルならそれ 1 つ）のコメントでない行のうち {@code p} に当たる行。 */
    static List<String> checkCodeLines(Path path, Pattern p) throws IOException {
        List<String> bad = new ArrayList<>();
        if (!Files.exists(path)) return bad;
        Path base = Files.isDirectory(path) ? path : path.getParent();
        try (var files = Files.walk(path)) {
            for (Path f : files.filter(x -> x.toString().endsWith(".java")).sorted().toList()) {
                List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).trim();
                    if (line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")) continue;
                    if (p.matcher(line).find()) bad.add(base.relativize(f) + ":" + (i + 1) + ": " + line);
                }
            }
        }
        return bad;
    }

    @Test
    void fragmentLayoutDoesNotReadPaintOnlyGroups() throws IOException {
        List<String> bad = checkPaintGroupReads(repoRoot().resolve("core/src/main/java/com/nishiyu/enderframelib/core/layout/fragment"));
        assertEquals(List.of(), bad, "新経路のレイアウトが描画だけの群を読んでいる（M2c）");
    }

    // ---- 7. 新経路はプロセス共有の切り替え・可変状態を読まない（M2c） ----

    /**
     * 新経路のソース（新経路のパッケージと、旧経路と同じパッケージに置いた新経路のクラス。新しく足したクラスはその段のうちにここへ足す）。
     * 描画命令・走査器・描き直す範囲（M3b-4a〜4b）、問い合わせの文脈（M3b-3）、DOM の分割（M5-1〜M5-3a）のクラスは M3b-4c-0 の点検で
     * 入れた。{@code ParserText} は互換の切り替え（{@code EngineFlags.KEEP_NEWLINE_WHITESPACE}。結果を変える従来の挙動への切り替えで、
     * 近道ではない）を読むので入れない。箱 1 つの描画の道具（{@code BoxItemPainter} ほか）は、層の値だけを読むようにした M3b-4c-1a で入れた。
     */
    static final List<String> NEW_PATH_SOURCES = newPathSources();

    private static List<String> newPathSources() {
        String main = "core/src/main/java/com/nishiyu/enderframelib/";
        List<String> out = new ArrayList<>(List.of(
                main + "core/box", main + "core/layout/fragment", main + "core/paint/scene", main + "common/V3Frame.java",
                main + "common/UiHitSupport.java"));
        for (String c : List.of("SceneAdapterBox", "SceneDisplayListAdapter", "SceneAdapterSupport", "LayerOps", "OpList", "OpComposer",
                "SceneWalker", "AnimationStates", "ItemSink", "SceneDamage", "ScrollContentRanges", "SceneSource", "FrameDamage",
                "BoxItemPainter", "BoxDecorationPainter", "BoxDecorations", "ControlPainter", "TextRunPainter", "PaintSupport", "ItemProps",
                "PaintBounds", "BoxAnimation")) {
            out.add(main + "core/paint/" + c + ".java");
        }
        for (String c : List.of("DeclarativeBindings", "InteractionChains", "ReactiveDomBinder", "StateFlags", "StyleInvalidation", "TemplateDeps",
                "node/ElementCssomView", "node/HtmlCSSStyleDeclaration", "node/HtmlDOMTokenList", "node/NodeAttributes", "node/NodeEvents",
                "node/NodeIds", "node/NodeNumbering", "node/NodeState", "node/NodeText", "node/SelectorIndex", "node/TopLayerOps", "node/TreeOps",
                "parser/ActiveFormatting", "parser/BodyMode", "parser/TableModes", "parser/TagSets", "parser/TreeBuilder")) {
            out.add(main + "core/dom/" + c + ".java");
        }
        return List.copyOf(out);
    }

    /**
     * 近道・切り分けの切り替え（{@code Shortcut}・{@code EngineFlags}・文脈の {@code ContextFlags} とその読み口 {@code ctx.flags()}・{@code EngineContext.current().flags()}）・
     * {@code static volatile}・{@code ThreadLocal}。描画命令の {@code flags()}（座標の基準の印）は切り替えではないので数えない。
     */
    private static final Pattern PROCESS_SWITCH_READ = Pattern.compile(
            "\\b(Shortcut|EngineFlags|ContextFlags|ThreadLocal)\\b|\\bstatic\\s+volatile\\b|parentRelativeSource|\\b(ctx|context|current\\(\\))\\.flags\\(\\)");

    @Test
    void newPathDoesNotReadProcessSwitches() throws IOException {
        List<String> bad = new ArrayList<>();
        for (String s : NEW_PATH_SOURCES) {
            Path p = repoRoot().resolve(s);
            assertTrue(Files.exists(p), "新経路のソースが無い: " + s);
            bad.addAll(checkCodeLines(p, PROCESS_SWITCH_READ));
        }
        assertEquals(List.of(), bad, "新経路がプロセス共有の切り替え・可変状態を読んでいる（計画書 §5-4。状態は 1 ビューに閉じる）");
    }

    // ---- 7b. 描画は DOM を読まない（M3b-1・決定 F。計画書 §3.4） ----

    /**
     * DOM の節の属性・文字・状態の規則の照合を読む式（{@code HtmlNode} の欄・メソッド名で見る。表示項目の {@code text()} などと
     * 区別できない {@code .text} の欄は、描画用の木が節の文字を持つので数えない）。
     */
    static final Pattern DOM_VALUE_READ = Pattern.compile(
            "\\.attrs\\b(?!\\s*\\()|\\b(getAttribute|hasAttribute|getAttributeNames|getTextContent|hasEventAttributes|attrsForWrite|getDataset"
                    + "|hasInteractiveRules|interactiveMask)\\s*\\(");

    @Test
    void paintDoesNotReadDomValues() throws IOException {
        List<String> bad = checkCodeLines(repoRoot().resolve("core/src/main/java/com/nishiyu/enderframelib/core/paint"), DOM_VALUE_READ);
        assertEquals(List.of(), bad, "描画が DOM の値を読んでいる（M3b-1・決定 F。描画が読む値は箱の木が BoxNode.values に写す）");
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

        RatchetSupport.withTempSources(Map.of("Flow.java",
                "class Flow {\n  int a(Style s) { return s.inheritedPaint.color; }\n  /** s.visual は描画 */\n"
                        + "  float b(Style s) { return s.box.width + s.visPx(0, 0, 0, 0); }\n  int c(Style s) { return s.inherited.fontWeight; }\n}"),
                dir -> assertEquals(2, checkPaintGroupReads(dir).size(), "描画だけの群を読む行は 2 行"));

        RatchetSupport.withTempSources(Map.of("Painter.java",
                "class Painter {\n  boolean a() { return Shortcut.SCROLL_CONTENT_NODES.on(); }\n  /** Shortcut は読まない */\n"
                        + "  boolean b() { return DisplayListBuilder.parentRelativeSource.getAsBoolean(); }\n"
                        + "  boolean c() { return EngineContext.current().flags().parentRelativeDisplayItems(); }\n  int d() { return 1; }\n"
                        + "  int e(DrawOps.Cursor it) { return it.flags() & DrawOps.BASE_ORIGIN_PARENT; }\n}"),
                dir -> assertEquals(3, checkCodeLines(dir, PROCESS_SWITCH_READ).size(), "切り替えを読む行は 3 行（描画命令の flags() は数えない）"));

        RatchetSupport.withTempSources(Map.of("Paint.java",
                "class Paint {\n  String a(PaintBox b) { return b.node().attrs.get(\"title\"); }\n  /** node.attrs は注釈 */\n"
                        + "  String b(HtmlNode n) { return n.getTextContent(); }\n  Map<String, String> c(ItemProps p) { return p.attrs(); }\n"
                        + "  String d(PaintBox b) { return b.values().attr(\"title\"); }\n"
                        + "  boolean e(HtmlNode n, StyleSheet s) { return StyleResolver.hasInteractiveRules(n, s); }\n}"),
                dir -> assertEquals(3, checkCodeLines(dir, DOM_VALUE_READ).size(), "DOM の値を読む行は 3 行"));
    }
    // ---- 8. 旧レイアウト経路の型が戻らない（M2c-3 の完了ゲート。計画書 §3.2） ----

    /** M2c-3 で消した旧レイアウト経路の型。識別子として現れたら失敗（注釈と文字列は数えない）。 */
    static final List<String> LEGACY_PATH_TYPES = List.of(
            "LayoutBox", "MeasureMemo", "PlacementMemo", "PlacementDeps", "LayoutReads", "MeasureReads", "ReadKind",
            "PaintStamps", "PartialBuild", "ChildWindow", "ChangeReads", "ReusableTable", "FragmentProjection", "EnginePath",
            "RenderPipeline", "RenderTreeBuilder", "InPlaceRelayout", "RepositionPass", "TextChanges", "DisplayListBuilder",
            "FragmentPaint", "PaintOrder", "InkBounds", "InkState", "PaintDescent", "ILayoutBoxRenderer", "BlockLayoutEngine",
            "FlexLayoutEngine", "GridLayoutEngine", "TableLayoutEngine", "VerticalWritingEngine", "HorizontalReturnEngine",
            "LayoutEngine", "LayoutContext", "BoxTreeBuilder", "BoxMeasurer", "LineBuilder", "FlowBuilder", "AnchorPositioning",
            "ScrollExtentCalc", "MulticolLayout", "ContextFlags", "FenwickVirtualList");

    static List<String> checkLegacyTypes(Path base) throws IOException {
        return checkTypeNames(base, LEGACY_PATH_TYPES);
    }

    static List<String> checkTypeNames(Path base, List<String> names) throws IOException {
        Pattern p = Pattern.compile("\\b(" + String.join("|", names) + ")\\b");
        List<String> bad = new ArrayList<>();
        if (!Files.isDirectory(base)) return bad;
        for (Path f : RatchetSupport.javaFiles(base)) {
            Matcher m = p.matcher(RatchetSupport.stripCommentsAndStrings(Files.readString(f, StandardCharsets.UTF_8), false));
            if (m.find()) bad.add(base.relativize(f).toString().replace('\\', '/') + ": " + m.group(1));
        }
        return bad;
    }

    @Test
    void legacyLayoutPathTypesStayGone() throws IOException {
        List<String> bad = new ArrayList<>();
        List<String> roots = new ArrayList<>(ALL_MODULE_MAIN_ROOTS);
        roots.addAll(List.of("core/src/test/java", "core/src/testFixtures/java", "core/src/jmh/java", "core/demo/java", "mod/src/test/java"));
        for (String root : roots) bad.addAll(checkLegacyTypes(repoRoot().resolve(root)));
        assertEquals(List.of(), bad, "旧レイアウト経路の型が戻った（M2c-3 で消した。計画書 §3.2）");
    }

    // ---- 9. MOD の旧レンダラが戻らない（M3a の完了ゲート。計画書 §3.3） ----

    /** M3a で消した MOD の旧レンダラの型と、旧レンダラへ切り替える設定。識別子として現れたら失敗（注釈と文字列は数えない）。 */
    static final List<String> LEGACY_MOD_RENDERER_TYPES = List.of(
            "DisplayItemDrawer", "LayerCompositor", "WebSdfShader", "TextElementRenderer", "OutlineTextRenderer",
            "BackgroundRenderer", "ControlElementRenderer", "MediaElementRenderer", "RoundedRectRasterizer", "RasterTextures",
            "CoreGpuSurface", "McMediaImages", "useCoreBackend", "useSdfShader");

    @Test
    void legacyModRendererStaysGone() throws IOException {
        List<String> bad = new ArrayList<>();
        for (String root : List.of("mod/src/main/java", "mod/src/test/java", "core/src/main/java")) {
            bad.addAll(checkTypeNames(repoRoot().resolve(root), LEGACY_MOD_RENDERER_TYPES));
        }
        assertEquals(List.of(), bad, "MOD の旧レンダラが戻った（M3a で消した。面はコアの描画だけが描く。計画書 §3.3）");
    }

    /** M3b-2 で消した描画用の木と平坦な層別断片の型。識別子として現れたら失敗（注釈と文字列は数えない）。 */
    static final List<String> M3B2_DELETED_TYPES = List.of(
            "PaintTreeBuilder", "PaintNode", "PaintView", "TreeRun", "StickyOffset", "AnchorFollow",
            "TreePainter", "TreeStacking", "FragSeq");

    /** M3b-3 で消した旧問い合わせ型（当たり判定・互換アダプタ・旧抽象木）。識別子として現れたら失敗（注釈と文字列は数えない）。 */
    static final List<String> M3B3_DELETED_TYPES = List.of(
            "HitTestIndex", "PaintBox", "PaintRun", "SceneBox", "UIHitResult");

    /** M3b-4b-4a で走査器（{@code SceneWalker}）と {@code SceneDamage} に置き換えて消した描画の駆動・差分の型。 */
    static final List<String> M3B4B4A_DELETED_TYPES = List.of(
            "PaintSceneDriver", "RetainRanges", "RangeBounds", "DamagePlanner", "ReplayKeys");

    @Test
    void legacyPaintTreeTypesStayGone() throws IOException {
        List<String> bad = new ArrayList<>();
        List<String> roots = new ArrayList<>(ALL_MODULE_MAIN_ROOTS);
        roots.addAll(List.of("core/src/test/java", "core/src/testFixtures/java", "core/src/jmh/java", "core/demo/java", "mod/src/test/java"));
        for (String root : roots) bad.addAll(checkTypeNames(repoRoot().resolve(root), M3B2_DELETED_TYPES));
        assertEquals(List.of(), bad, "M3b-2 で消した描画用の木・TreePainter・FragSeq が戻った（計画書 §3.4）");
    }

    // ---- 10. DOM の木の形を写した番号の表が戻らない（M5-1。計画書 §3.7） ----

    /**
     * M5-1 で消した、DOM の木の形（親・子・兄弟）とタグ・id・Bloom をオフヒープの列に写して子の並びと二重に同期していた型。
     * 番号は {@code NodeIds}（番号と世代だけ）、木の形の正本は子の並び、セレクタの足切りは {@code SelectorIndex}。
     */
    static final List<String> LEGACY_DOM_ARENA_TYPES = List.of("DocumentArena");

    @Test
    void domArenaCopyStaysGone() throws IOException {
        List<String> bad = new ArrayList<>();
        List<String> roots = new ArrayList<>(ALL_MODULE_MAIN_ROOTS);
        roots.addAll(List.of("core/src/test/java", "core/src/testFixtures/java", "core/src/jmh/java", "core/demo/java", "mod/src/test/java"));
        for (String root : roots) bad.addAll(checkTypeNames(repoRoot().resolve(root), LEGACY_DOM_ARENA_TYPES));
        assertEquals(List.of(), bad, "DOM の木を写した番号の表が戻った（M5-1 で消した。計画書 §3.7）");
    }

    @Test
    void legacyQueryTypesStayGone() throws IOException {
        List<String> bad = new ArrayList<>();
        List<String> roots = new ArrayList<>(ALL_MODULE_MAIN_ROOTS);
        roots.addAll(List.of("core/src/test/java", "core/src/testFixtures/java", "core/src/jmh/java", "core/demo/java", "mod/src/test/java"));
        for (String root : roots) bad.addAll(checkTypeNames(repoRoot().resolve(root), M3B3_DELETED_TYPES));
        assertEquals(List.of(), bad, "M3b-3 で消した HitTestIndex / PaintBox / PaintRun / SceneBox / UIHitResult が戻った（計画書 §3.4）");
    }

    @Test
    void legacyPaintDriverTypesStayGone() throws IOException {
        List<String> bad = new ArrayList<>();
        List<String> roots = new ArrayList<>(ALL_MODULE_MAIN_ROOTS);
        roots.addAll(List.of("core/src/test/java", "core/src/testFixtures/java", "core/src/jmh/java", "core/demo/java", "mod/src/test/java"));
        for (String root : roots) bad.addAll(checkTypeNames(repoRoot().resolve(root), M3B4B4A_DELETED_TYPES));
        assertEquals(List.of(), bad, "M3b-4b-4a で消した PaintSceneDriver / RetainRanges / RangeBounds / DamagePlanner / ReplayKeys が戻った（計画書 §3.4）");
    }

    /** M3b-4b-4b で消した、項目の同一性で静止フレーム・変化を見る型と、列全体を絶対座標へ直す本番の型。 */
    static final List<String> M3B4B4B_DELETED_TYPES = List.of("StaticFrameGate", "ItemDiff", "AbsoluteItems");

    @Test
    void legacyStaticFrameTypesStayGone() throws IOException {
        List<String> bad = new ArrayList<>();
        List<String> roots = new ArrayList<>(ALL_MODULE_MAIN_ROOTS);
        roots.addAll(List.of("core/src/test/java", "core/src/testFixtures/java", "core/src/jmh/java", "core/demo/java", "mod/src/test/java"));
        for (String root : roots) bad.addAll(checkTypeNames(repoRoot().resolve(root), M3B4B4B_DELETED_TYPES));
        assertEquals(List.of(), bad, "M3b-4b-4b で消した StaticFrameGate / ItemDiff / AbsoluteItems が戻った（静止の判定は描画器の needsRepaint。計画書 §3.4）");
    }

    /**
     * M3b-4c-1a: 一時アダプタの箱 {@code SceneAdapterBox}（M3b-4c-2 でアダプタごと消す）を読むのは、アダプタ自身と、表示項目を組み立てる
     * 側だけ。残す部品（項目の生成器・{@code LayerOps}・{@code SceneDamage}）は {@code SceneLayer} と明示した置き場所を読む（計画書 §1.4 の 4）。
     */
    static final Set<String> SCENE_ADAPTER_BOX_READERS = Set.of(
            "SceneAdapterBox.java", "SceneAdapterSupport.java", "SceneDisplayListAdapter.java", "OpComposer.java");

    @Test
    void sceneAdapterBoxStaysInsideTheAdapter() throws IOException {
        List<String> bad = new ArrayList<>();
        List<String> roots = new ArrayList<>(ALL_MODULE_MAIN_ROOTS);
        roots.addAll(List.of("core/src/test/java", "core/src/testFixtures/java", "core/src/jmh/java", "core/demo/java", "mod/src/test/java"));
        for (String root : roots) {
            for (String hit : checkTypeNames(repoRoot().resolve(root), List.of("SceneAdapterBox"))) {
                String file = hit.substring(0, hit.indexOf(':'));
                if (!SCENE_ADAPTER_BOX_READERS.contains(file.substring(file.lastIndexOf('/') + 1))) bad.add(root + "/" + hit);
            }
        }
        assertEquals(List.of(), bad, "一時アダプタの SceneAdapterBox をアダプタの外が読んだ（生成器・LayerOps・SceneDamage は SceneLayer と置き場所を読む。計画書 §3.4 M3b-4c-1a）");
    }

    @Test
    void legacyTypeDetectorWorks() throws IOException {
        RatchetSupport.withTempSources(Map.of("Old.java",
                "class Old {\n  LayoutBox b;\n  /** LayoutBox は注釈なので数えない */\n  String s = \"MeasureMemo\";\n  int layoutBoxCount;\n}"),
                dir -> assertEquals(1, checkLegacyTypes(dir).size(), "識別子の LayoutBox だけを数える"));
    }
}
