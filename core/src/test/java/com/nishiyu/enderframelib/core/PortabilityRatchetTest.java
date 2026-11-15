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

    /** 現状の依存。GLFW の窓・GL バックエンド・AWT の画像復号（L3 の {@code AwtImageDecoder}）。 */
    private static final Set<String> ALLOWED = Set.of(
            "common/StandaloneViewer.java",
            "core/paint/gl/GlRectStream.java", // バックエンドが受けた矩形を GlTreeStore へ流す窓口（L3）
            "core/paint/gl/GlRectRenderer.java", // GlTreeStore のプリミティブを描く GL 実装（L3）
            "core/paint/gl/GlVramDevice.java", // kernel/gpu の PersistentVramBuffer.Device の GL 実装（L3）。kernel 側は GL 非依存のまま
            "core/paint/gl/GlFenceRing.java", // GlVramDevice 群が共有するフェンス（GL 同期オブジェクト）。L3 の GL 実装の一部
            "core/paint/gl/GlDevice.java", // GpuDevice（RHI）の GL 実装（L3）。G1 で LwjglGlRenderBackend の GL 呼び出しを順に引き取る
            "core/paint/gl/GlScene3DRenderer.java", // Scene3DWorld の GL 3.2 Core + TBO インスタンスレンダラ（L5）
            "core/paint/gl/LwjglGlRenderBackend.java",
            "core/raster/awt/AwtImageDecoder.java");

    private static final Pattern FORBIDDEN = Pattern.compile("org[.]lwjgl[.](opengl|glfw)|java[.]awt");

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
     * GLSL ソース（{@code #version}）を持つファイルと、その本数。G2（シェーダーの単一ソース化）で {@code core.kernel.gpu.shader} へ
     * 集めるまで増やさない。X3-1 の {@code Scene3DShaders}（2 本）を加えた時点の数で固定。
     */
    private static final Map<String, Integer> GLSL_SOURCES = Map.of(
            "core/kernel/gpu/GpuAnimation.java", 1,
            "core/kernel/gpu/GpuRectShader.java", 2,
            "core/paint/AlphaMask.java", 1,
            "core/paint/gl/LwjglGlRenderBackend.java", 3,
            "core/paint/gl/Scene3DShaders.java", 2);

    /**
     * {@code core.paint.gl} の外から、その型を import しているファイル（リポジトリ全体の main）。G1 の中立 {@code GpuRenderBackend} と
     * G4 の {@code ServiceLoader} が入るまで増やさない。
     */
    private static final Set<String> GL_PACKAGE_IMPORTERS = Set.of(
            "core/src/main/java/com/nishiyu/enderframelib/common/EngineSurface.java",
            "core/src/main/java/com/nishiyu/enderframelib/common/StandaloneViewer.java",
            "src/main/java/com/nishiyu/enderframelib/client/renderer/CoreGpuSurface.java");

    private static final Pattern VERSION_DIRECTIVE = Pattern.compile("#version");
    private static final Pattern GL_PACKAGE_REF = Pattern.compile("com[.]nishiyu[.]enderframelib[.]core[.]paint[.]gl[.]");

    @Test
    void glslSourcesOnlyShrinkOrMove() throws IOException {
        Path root = Path.of("core/src/main/java/com/nishiyu/enderframelib");
        Map<String, Integer> found = new java.util.TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                int n = (int) VERSION_DIRECTIVE.matcher(Files.readString(p)).results().count();
                if (n > 0) found.put(root.relativize(p).toString().replace(java.io.File.separatorChar, '/'), n);
            }
        }
        assertEquals(new java.util.TreeMap<>(GLSL_SOURCES), found,
                "GLSL を持つファイル・本数が一覧と違う（増やさない。減らした・移したら一覧を更新する。G2 の目標は core.kernel.gpu.shader だけ）");
    }

    @Test
    void glPackageIsImportedFromOutsideOnlyByTheKnownEntryPoints() throws IOException {
        Set<String> found = new TreeSet<>();
        for (String base : new String[]{"core/src/main/java", "src/main/java"}) {
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
