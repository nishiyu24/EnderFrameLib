package com.nishiyu.enderframelib.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * {@code :core} 配布物の純度のラチェット（ENGINE_REARCHITECTURE_PLAN S5 ③）: Minecraft 側の {@code client} / {@code server} を持たない・
 * {@code core.dom} が 3D / ラスタへ依存しない・使われていないワイルドカード {@code import} を残さない。
 */
class ModulePurityRatchetTest {
    private static final String BASE = "com.nishiyu.enderframelib.";
    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern WILDCARD = Pattern.compile("^import\\s+(" + Pattern.quote(BASE) + "[\\w.]+)\\.\\*;[ \\t]*$", Pattern.MULTILINE);

    @Test
    void coreHasNoMinecraftSideSourceDirectories() {
        Path root = PackageCycleRatchetTest.root();
        for (String dir : List.of("client", "server", "content")) {
            assertTrue(!Files.exists(root.resolve(dir)), ":core に " + dir + "/ がある（Minecraft 側の型はルートの Mod へ置く）");
        }
    }

    @Test
    void domDoesNotDependOnScene3dOrRaster() throws IOException {
        Map<String, Set<String>> g = PackageCycleRatchetTest.graph();
        Set<String> bad = new TreeSet<>();
        for (var e : g.entrySet()) {
            if (!e.getKey().equals("core.dom") && !e.getKey().startsWith("core.dom.")) continue;
            for (String ref : e.getValue()) {
                if (ref.startsWith("core.scene3d") || ref.startsWith("core.raster")) bad.add(e.getKey() + " -> " + ref);
            }
        }
        assertEquals(Set.of(), bad, "core.dom が 3D・ラスタへ依存している（アダプタは common などの上の層へ置く）");
    }

    /** 1 ファイルの、使われていないワイルドカード import（パッケージ名）。同じパッケージの import と、型が 1 つも出てこない import。 */
    static Set<String> unusedWildcards(String src, Map<String, Set<String>> typesByPackage) {
        Set<String> out = new TreeSet<>();
        Matcher pm = PACKAGE.matcher(src);
        String own = pm.find() ? pm.group(1) : "";
        String body = WILDCARD.matcher(src).replaceAll("").replaceAll("(?m)^\\s*import\\s[^;]*;", "");
        Matcher m = WILDCARD.matcher(src);
        while (m.find()) {
            String pkg = m.group(1);
            Set<String> types = typesByPackage.get(pkg);
            if (types == null) continue; // パッケージの出所が分からない（入れ子の型など）は対象外
            boolean used = !pkg.equals(own) && types.stream().anyMatch(t -> Pattern.compile("\\b" + Pattern.quote(t) + "\\b").matcher(body).find());
            if (!used) out.add(pkg);
        }
        return out;
    }

    @Test
    void noUnusedWildcardImports() throws IOException {
        Path root = PackageCycleRatchetTest.root();
        Map<String, Set<String>> types = new HashMap<>();
        List<Path> files = RatchetSupport.javaFiles(root);
        for (Path f : files) {
            String pkg = BASE + RatchetSupport.rel(root, f.getParent()).replace('/', '.');
            String name = f.getFileName().toString();
            types.computeIfAbsent(pkg, k -> new HashSet<>()).add(name.substring(0, name.length() - ".java".length()));
        }
        Set<String> bad = new TreeSet<>();
        for (Path f : files) {
            for (String pkg : unusedWildcards(Files.readString(f).replace("\r\n", "\n"), types)) {
                bad.add(RatchetSupport.rel(root, f) + " -> " + pkg.substring(BASE.length()) + ".*");
            }
        }
        assertEquals(Set.of(), bad, "使われていないワイルドカード import（消すか、使う型を明示する）");
    }

    // ---- 検査器自身の検証 ----

    @Test
    void detectsUnusedSamePackageAndUsedWildcards() {
        Map<String, Set<String>> types = Map.of(
                BASE + "core.text", Set.of("BidiText", "LineBreaker"),
                BASE + "core.css", Set.of("ColorParser"));
        String src = "package " + BASE + "core.text.model;\n"
                + "import " + BASE + "core.text.*;\n"      // 型が出てこない → 未使用
                + "import " + BASE + "core.css.*;\n"       // ColorParser が出てくる → 使用
                + "class A { ColorParser p; }\n";
        assertEquals(Set.of(BASE + "core.text"), unusedWildcards(src, types));
        String same = "package " + BASE + "core.text;\nimport " + BASE + "core.text.*;\nclass B { BidiText t; }\n";
        assertEquals(Set.of(BASE + "core.text"), unusedWildcards(same, types), "自分のパッケージの import は常に不要");
    }
}
