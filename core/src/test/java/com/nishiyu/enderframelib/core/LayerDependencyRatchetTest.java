package com.nishiyu.enderframelib.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OSI 参照モデル型の 7 層単方向依存（ENGINE_REARCHITECTURE_PLAN §1.3）を機械的に固定するラチェット。
 * 「第 N 層は第 N 層以下の型しか参照してはならない」。{@code import}（ワイルドカード含む）と完全修飾名の直接参照の両方を数える。
 *
 * <p>{@link #KNOWN_UPWARD} は解消待ちの逆方向参照で、<b>増やしてはならない</b>。解消したら一覧から外すこと
 * （残っていると失敗する）。目標は空集合。
 */
class LayerDependencyRatchetTest {

    private static final String BASE = "com.nishiyu.enderframelib.";

    /** 層の割り当て。先頭一致の長い順に評価する。{@code core.kernel.compiler} は CSS/DOM を保持するので L3、SVG は DOM を解析するので L3。 */
    private static final String[][] LAYERS = {
            {"core.kernel.compiler", "3"},
            {"core.raster.svg", "3"},
            {"core.kernel", "1"},
            {"core.diag", "1"},
            {"core.text", "2"},
            {"core.raster", "2"},
            {"core.net", "2"},
            {"core.media", "2"},
            {"core.dom", "3"},
            {"core.css", "3"},
            {"core.event", "3"},
            {"core.layout", "4"},
            {"core.paint", "5"},
    };

    /**
     * 既知の逆方向参照（ファイル → 参照先の型）。解消策は計画書 §1.3。
     * <ul>
     *   <li>L2→L3 {@code CanvasRenderingContext2D → ColorParser}: 色解析を L1/L2 へ</li>
     *   <li>L2→L4/L5 {@code FontFaceRules → LayoutMetrics / DisplayItem}: CSS・Paint 依存メソッドを上位へ</li>
     *   <li>L3→L6 {@code Bindings → EngineContext}: i18n・JSON 解析器を引数で受ける</li>
     *   <li>L4→L5 {@code BoxMeasurer → ImageChoice / ImageSources}: 画像寸法の取得を L2/L4 の窓口へ</li>
     * </ul>
     */
    private static final Set<String> KNOWN_UPWARD = Set.of(
            "core/raster/CanvasRenderingContext2D.java -> core.css.ColorParser",
            "core/text/font/FontFaceRules.java -> core.layout.model.LayoutMetrics",
            "core/text/font/FontFaceRules.java -> core.paint.model.DisplayItem",
            "core/dom/Bindings.java -> core.EngineContext",
            "core/layout/BoxMeasurer.java -> core.paint.ImageChoice",
            "core/layout/BoxMeasurer.java -> core.paint.ImageSources");

    private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+(static\\s+)?(" + Pattern.quote(BASE) + "[\\w.]+?)(\\.\\*)?;", Pattern.MULTILINE);
    private static final Pattern FQN = Pattern.compile(Pattern.quote(BASE) + "([a-z][\\w]*(?:\\.[a-z][\\w]*)*)(?:\\.([A-Z]\\w*))?");
    private static final Pattern COMMENTS = Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);
    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+);", Pattern.MULTILINE);

    /** 層番号（1〜7）。{@code core} 直下は L6（{@code EngineContext}）。core 以外（common / api / client…）は空。 */
    static OptionalInt layerOf(String pkgRelativeToBase) {
        for (String[] e : LAYERS) {
            if (pkgRelativeToBase.equals(e[0]) || pkgRelativeToBase.startsWith(e[0] + ".")) {
                return OptionalInt.of(Integer.parseInt(e[1]));
            }
        }
        if (pkgRelativeToBase.equals("core")) return OptionalInt.of(6);
        return OptionalInt.empty();
    }

    /** {@code core.css.ColorParser} / {@code core.css.model.*} のような名前から、パッケージ部分（大文字始まりの手前まで）を取り出す。 */
    private static String packageOf(String dotted) {
        StringBuilder sb = new StringBuilder();
        for (String part : dotted.split("[.]")) {
            if (Character.isUpperCase(part.charAt(0))) break;
            if (sb.length() > 0) sb.append('.');
            sb.append(part);
        }
        return sb.toString();
    }

    /** 1 ファイルの逆方向参照（"file -> 参照先"）を集める。{@code src} のコメントは除く。 */
    static Set<String> upwardReferences(String file, String src) {
        Set<String> out = new TreeSet<>();
        Matcher pm = PACKAGE.matcher(src);
        if (!pm.find()) return out;
        String ownPkg = pm.group(1).startsWith(BASE) ? pm.group(1).substring(BASE.length()) : pm.group(1);
        OptionalInt own = layerOf(ownPkg);
        if (own.isEmpty()) return out;
        String body = COMMENTS.matcher(src).replaceAll(" ");
        collect(file, own.getAsInt(), body, IMPORT, 2, out);
        Matcher f = FQN.matcher(body.replaceAll("(?m)^\\s*(import|package)\\s[^;]*;", ""));
        while (f.find()) {
            String pkg = f.group(1);
            if (!pkg.startsWith("core")) continue;
            OptionalInt t = layerOf(pkg);
            if (t.isPresent() && t.getAsInt() > own.getAsInt()) {
                out.add(file + " -> " + pkg + (f.group(2) != null ? "." + f.group(2) : ".*"));
            }
        }
        return out;
    }

    private static void collect(String file, int own, String body, Pattern p, int group, Set<String> out) {
        Matcher m = p.matcher(body);
        while (m.find()) {
            String name = m.group(group).substring(BASE.length());
            OptionalInt t = layerOf(packageOf(name));
            if (t.isPresent() && t.getAsInt() > own) {
                out.add(file + " -> " + name + (m.group(3) != null ? ".*" : ""));
            }
        }
    }

    @Test
    void upwardDependenciesOnlyShrink() throws IOException {
        Path root = Path.of("core/src/main/java/com/nishiyu/enderframelib");
        if (!Files.isDirectory(root)) root = Path.of("src/main/java/com/nishiyu/enderframelib");
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(root.resolve("core"))) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                String rel = root.relativize(p).toString().replace(java.io.File.separatorChar, '/');
                found.addAll(upwardReferences(rel, Files.readString(p)));
            }
        }
        Set<String> added = new TreeSet<>(found);
        added.removeAll(KNOWN_UPWARD);
        Set<String> removed = new TreeSet<>(KNOWN_UPWARD);
        removed.removeAll(found);
        assertEquals(Set.of(), added, "新たな逆方向（下位層 → 上位層）参照。依存方向を直すこと（計画書 §1.3）");
        assertEquals(Set.of(), removed, "解消した逆方向参照を KNOWN_UPWARD から外すこと（ラチェットを締める）");
    }

    /** core は外側（common / client / server）へ依存しない。api へは DOM・イベントの契約型（{@code api.dom} / {@code api.event}。L1 扱い）だけが許される。 */
    @Test
    void coreNeverDependsOutward() throws IOException {
        Path root = Path.of("core/src/main/java/com/nishiyu/enderframelib");
        if (!Files.isDirectory(root)) root = Path.of("src/main/java/com/nishiyu/enderframelib");
        Pattern outward = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?" + Pattern.quote(BASE) + "(common|client|server|content)\\.");
        Pattern api = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?" + Pattern.quote(BASE) + "api\\.(?!(?:dom|event)\\.)");
        Set<String> bad = new TreeSet<>();
        try (Stream<Path> files = Files.walk(root.resolve("core"))) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                String src = COMMENTS.matcher(Files.readString(p)).replaceAll(" ");
                if (outward.matcher(src).find() || api.matcher(src).find()) {
                    bad.add(root.relativize(p).toString().replace(java.io.File.separatorChar, '/'));
                }
            }
        }
        assertEquals(Set.of(), bad, "core.* が common / client / server / api（api.dom・api.event を除く）を参照している");
    }

    // ---- 検査器自身の検証（偽陰性で素通りしないことを固定する） ----

    @Test
    void detectsUpwardImport() {
        String src = "package com.nishiyu.enderframelib.core.css;\nimport com.nishiyu.enderframelib.core.layout.LayoutBox;\nclass A {}";
        assertEquals(Set.of("core/css/A.java -> core.layout.LayoutBox"), upwardReferences("core/css/A.java", src));
    }

    @Test
    void detectsWildcardAndFullyQualifiedReferences() {
        String src = "package com.nishiyu.enderframelib.core.layout;\n"
                + "import com.nishiyu.enderframelib.core.paint.*;\n"
                + "class B { Object o = com.nishiyu.enderframelib.core.paint.model.DisplayItem.class; }";
        Set<String> got = upwardReferences("core/layout/B.java", src);
        assertTrue(got.contains("core/layout/B.java -> core.paint.*"), got.toString());
        assertTrue(got.contains("core/layout/B.java -> core.paint.model.DisplayItem"), got.toString());
    }

    @Test
    void ignoresDownwardSameLayerAndComments() {
        String src = "package com.nishiyu.enderframelib.core.layout;\n"
                + "import com.nishiyu.enderframelib.core.css.ComputedStyle;\n"      // 下向き: 可
                + "import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;\n" // 同一層: 可
                + "import com.nishiyu.enderframelib.core.kernel.memory.AtomTable;\n"
                + "/** {@link com.nishiyu.enderframelib.core.paint.model.DisplayItem} */\n" // コメント: 無視
                + "// com.nishiyu.enderframelib.core.paint.ItemProps\nclass C {}";
        assertEquals(Set.of(), upwardReferences("core/layout/C.java", src));
    }

    @Test
    void layerAssignmentFollowsTheReferenceModel() {
        assertEquals(1, layerOf("core.kernel.memory").getAsInt());
        assertEquals(3, layerOf("core.kernel.compiler").getAsInt());
        assertEquals(2, layerOf("core.text.font").getAsInt());
        assertEquals(3, layerOf("core.raster.svg").getAsInt());
        assertEquals(2, layerOf("core.raster").getAsInt());
        assertEquals(3, layerOf("core.css.model").getAsInt());
        assertEquals(4, layerOf("core.layout.model").getAsInt());
        assertEquals(5, layerOf("core.paint.gl").getAsInt());
        assertEquals(6, layerOf("core").getAsInt());
        assertTrue(layerOf("common").isEmpty());
        assertTrue(List.of("core.kernel", "core.text", "core.dom", "core.layout", "core.paint").stream()
                .allMatch(p -> layerOf(p).isPresent()));
    }
}
