package com.nishiyu.enderframelib.core.kernel;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * エンジン再設計 Milestone 0 の番人: 外部依存ゼロ・JVM フラグ強制ゼロ。
 * 利用側（Minecraft ランチャー等）に特別な起動引数も追加ネイティブも要求しないことを機械的に守る。
 */
class KernelBaselineTest {

    private static final Path CORE = Path.of("core");

    @Test
    void testJvmRunsWithoutKernelFlags() {
        List<String> args = ManagementFactory.getRuntimeMXBean().getInputArguments();
        assertFalse(args.contains("--enable-preview"), args.toString());
        assertTrue(args.stream().noneMatch(a -> a.startsWith("--add-modules") || a.startsWith("--enable-native-access")), args.toString());
        assertFalse(ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent(),
                "テストは incubator モジュール無しで走る（任意プラグインが無くても動く証明）");
    }

    @Test
    void buildFilesDoNotForceFlagsOrNativeExtras() throws IOException {
        for (String file : List.of("build.gradle", "core/build.gradle")) {
            String text = Files.readString(Path.of(file));
            for (String forbidden : List.of("--enable-preview", "--add-modules", "--enable-native-access", "jdk.incubator", "harfbuzz")) {
                assertFalse(text.contains(forbidden), file + " に " + forbidden);
            }
        }
    }

    @Test
    void mainSourcesUseNoPreviewOrIncubatorApis() throws IOException {
        Path root = CORE.resolve("src/main/java");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String text = Files.readString(p);
                assertFalse(text.contains("java.lang.foreign"), p + " が Panama FFM（JDK 21 ではプレビュー）を使っている");
                assertFalse(text.contains("jdk.incubator"), p + " が incubator モジュールを使っている");
                assertFalse(text.contains("org.lwjgl.util.harfbuzz"), p + " が追加ネイティブ（HarfBuzz）を使っている");
            }
        }
    }

    @Test
    void kernelClassesAreNotPreviewClassFiles() throws IOException {
        for (Class<?> c : List.of(com.nishiyu.enderframelib.core.kernel.memory.OffHeapColumns.class,
                com.nishiyu.enderframelib.core.kernel.simd.ScalarSimd.class,
                com.nishiyu.enderframelib.core.text.font.GsubTable.class)) {
            try (InputStream in = c.getResourceAsStream(c.getSimpleName() + ".class")) {
                assertNotNull(in, c.getName());
                byte[] head = in.readNBytes(8);
                int minor = ((head[4] & 0xFF) << 8) | (head[5] & 0xFF);
                assertNotEquals(0xFFFF, minor, c.getName() + " はプレビュー印のクラス（--enable-preview 必須になる）");
            }
        }
    }
}
