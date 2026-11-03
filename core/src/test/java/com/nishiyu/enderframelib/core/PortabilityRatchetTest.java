package com.nishiyu.enderframelib.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    /** 現状の依存（課題 E 未着手）。{@code common/} は GLFW のキーコード、{@code core/} は AWT の画像型。 */
    private static final Set<String> ALLOWED = Set.of(
            "common/StandaloneViewer.java",
            "core/paint/gl/LwjglGlRenderBackend.java",
            "core/paint/software/ImageProvider.java",
            "core/raster/GifAnimation.java");

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
}
