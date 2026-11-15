package com.nishiyu.enderframelib.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 他の描画 API（Vulkan 等）への流用性を守る「ラチェット」。
 * OpenGL / GLFW / AWT へ直接依存してよいファイルは下の一覧だけで、一覧は増やしてはならない。
 * 依存を取り除いたら、そのファイルを一覧から外すこと（残っているとこのテストが失敗する）。
 * 目標: {@code LwjglGlRenderBackend} 以外の全てが一覧から消える（OPTIMIZATION_PROPOSALS.md §6）。
 */
class PortabilityRatchetTest {

    /**
     * 現状の依存。OpenGL・GLFW・Vulkan は G4 で {@code :core-gl}・{@code :core-vulkan}・{@code :core-glfw} へ出したので、
     * {@code :core} に残るのは AWT の画像復号（L3 の {@code AwtImageDecoder}）だけ（G6 で純 Java の復号器へ）。
     */
    private static final Set<String> ALLOWED = Set.of(
            "core/raster/awt/AwtImageDecoder.java");

    private static final Pattern FORBIDDEN = Pattern.compile("org[.]lwjgl[.](opengl|vulkan|glfw)|java[.]awt");

    /** G4: {@code :core} の main（OpenGL・GLFW・Vulkan の import 0）。AWT は {@link #platformDependenciesOnlyShrink} が見る。 */
    @Test
    void coreHasNoGraphicsApiImports() throws IOException {
        Path root = Path.of("core/src/main/java");
        Pattern api = Pattern.compile("org[.]lwjgl[.](opengl|vulkan|glfw)");
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                if (api.matcher(Files.readString(p)).find()) found.add(root.relativize(p).toString().replace(java.io.File.separatorChar, '/'));
            }
        }
        assertEquals(Set.of(), found, ":core は OpenGL・GLFW・Vulkan を import しない（GPU 実装は :core-gl / :core-vulkan、窓は :core-glfw）");
    }

    @Test
    void platformDependenciesOnlyShrink() throws IOException {
        Path root = Path.of("core/src/main/java/com/nishiyu/enderframelib");
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                if (FORBIDDEN.matcher(Files.readString(p)).find()) {
                    found.add(root.relativize(p).toString().replace(java.io.File.separatorChar, '/'));
                }
            }
        }
        Set<String> added = new TreeSet<>(found);
        added.removeAll(ALLOWED);
        Set<String> removed = new TreeSet<>(ALLOWED);
        removed.removeAll(found);
        assertEquals(Set.of(), added, "新たに GL / GLFW / AWT へ依存したファイル（L0/L1 に持ち込まない。§6.1）");
        assertEquals(Set.of(), removed, "依存がなくなったファイルを ALLOWED から外すこと（ラチェットを締める）");
    }

    // ------------------------------------------------------------------ G0: GLSL とパッケージ境界のラチェット（ENGINE_REARCHITECTURE_PLAN §3）

    /**
     * GLSL のシェーダー本体（{@code void main()}）を持つファイルと、その本数。G2（シェーダーの単一ソース化）で
     * {@code core.kernel.gpu.shader.ShaderSources} へ集め終えた。ここ以外へ GLSL を書き足してはならない。
     * 版の行（{@code #version}）は {@code Glsl}（API ごとの前置き）だけが持つ。
     */
    private static final Map<String, Integer> GLSL_SOURCES = Map.of(
            "core/kernel/gpu/shader/ShaderSources.java", 8);

    /** 版の行（GLSL の {@code #version N}）を書いてよいファイル（行頭または文字列の先頭。Javadoc の言及は数えない）。 */
    private static final Set<String> GLSL_VERSION_FILES = Set.of("core/kernel/gpu/shader/Glsl.java");

    /**
     * {@code core.paint.gl} の外から、その型を import しているファイル（リポジトリ全体の main。{@code :core-gl} 自身
     * は除く）。{@code :core} は G4 の {@code ServiceLoader}（{@code GpuBackendProvider}）で見つけるので 1 つも持たない。残るのは、
     * GL を直接使う窓 {@code StandaloneViewer}（{@code :core-glfw}。{@code :core-gl} に依存する）と、ホスト（Minecraft）の
     * {@code CoreGpuSurface}（G5 で一本化するまで）。
     */
    private static final Set<String> GL_PACKAGE_IMPORTERS = Set.of(
            "core-glfw/src/main/java/com/nishiyu/enderframelib/common/StandaloneViewer.java",
            "src/main/java/com/nishiyu/enderframelib/client/renderer/CoreGpuSurface.java");

    private static final Pattern MAIN_FUNCTION = Pattern.compile("void\\s+main\\s*\\(\\s*\\)");
    private static final Pattern VERSION_LINE = Pattern.compile("(?m)(^[ \\t]*|\")#version[ \\t]+\\d+");
    private static final Pattern GL_PACKAGE_REF = Pattern.compile("com[.]nishiyu[.]enderframelib[.]core[.]paint[.]gl[.]");

    @Test
    void glslSourcesOnlyShrinkOrMove() throws IOException {
        Path root = Path.of("core/src/main/java/com/nishiyu/enderframelib");
        Map<String, Integer> found = new java.util.TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                int n = (int) MAIN_FUNCTION.matcher(Files.readString(p)).results().count();
                if (n > 0) found.put(root.relativize(p).toString().replace(java.io.File.separatorChar, '/'), n);
            }
        }
        assertEquals(new java.util.TreeMap<>(GLSL_SOURCES), found,
                "GLSL を持つファイル・本数が一覧と違う（シェーダーは core.kernel.gpu.shader.ShaderSources だけに書く）");
    }

    @Test
    void glslVersionLinesLiveOnlyInThePreamble() throws IOException {
        Path root = Path.of("core/src/main/java/com/nishiyu/enderframelib");
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                if (VERSION_LINE.matcher(Files.readString(p)).find()) found.add(root.relativize(p).toString().replace(java.io.File.separatorChar, '/'));
            }
        }
        assertEquals(new TreeSet<>(GLSL_VERSION_FILES), found, "GLSL の版の行は Glsl（前置き）だけが持つ");
    }

    @Test
    void glPackageIsImportedFromOutsideOnlyByTheKnownEntryPoints() throws IOException {
        Set<String> found = new TreeSet<>();
        for (String base : new String[]{"core/src/main/java", "core-vulkan/src/main/java", "core-glfw/src/main/java", "src/main/java"}) {
            Path root = Path.of(base);
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> files = Files.walk(root)) {
                for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                    String rel = p.toString().replace(java.io.File.separatorChar, '/');
                    if (rel.contains("/core/paint/gl/")) continue; // パッケージの内側
                    if (GL_PACKAGE_REF.matcher(Files.readString(p)).find()) found.add(rel);
                }
            }
        }
        assertEquals(new TreeSet<>(GL_PACKAGE_IMPORTERS), found,
                "core.paint.gl を外から参照するファイルが一覧と違う（増やさない。外したら一覧から外す）");
    }
}
