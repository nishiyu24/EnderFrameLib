package com.nishiyu.enderframelib.core.kernel;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** エンジン再設計 Milestone 4 の番人（機械的ゲート）。 */
class Milestone4GateTest {

    private static final Path MAIN = Path.of("core/src/main/java/com/nishiyu/enderframelib");

    @Test
    void kernelGpuPackageIsGlFreeAndPureJava() throws IOException {
        try (var files = Files.list(MAIN.resolve("core/kernel/gpu"))) {
            for (Path p : files.toList()) {
                String s = Files.readString(p);
                assertFalse(s.contains("org.lwjgl"), p + ": GL/LWJGL に依存してはならない（Device 越しにだけ触る）");
                assertFalse(s.contains("import net.minecraft"), p.toString());
                assertFalse(s.contains("java.lang.foreign"), p + ": プレビュー API は使わない");
                assertFalse(s.contains("--enable-preview"), p.toString());
            }
        }
    }

    @Test
    void recordStridesAreTheDesignedSizes() {
        assertEquals(64, com.nishiyu.enderframelib.core.kernel.gpu.PropertyTrees.TRANSFORM_STRIDE);
        assertEquals(64, com.nishiyu.enderframelib.core.kernel.gpu.PropertyTrees.CLIP_STRIDE);
        assertEquals(32, com.nishiyu.enderframelib.core.kernel.gpu.PropertyTrees.EFFECT_STRIDE);
        assertEquals(128, com.nishiyu.enderframelib.core.kernel.gpu.PropertyTrees.PRIMITIVE_STRIDE);
    }

    @Test
    void hotPathsHaveNoStringFormatOrRegex() throws IOException {
        try (var files = Files.list(MAIN.resolve("core/kernel/gpu"))) {
            for (Path p : files.toList()) {
                String s = Files.readString(p);
                assertFalse(s.contains("String.format"), p.toString());
                assertFalse(s.contains("java.util.regex"), p.toString());
                assertFalse(s.contains("new HashMap"), p.toString());
            }
        }
    }

    @Test
    void itemKeysIsGoneAndKeysComeFromTheKernelAtomTable() throws IOException {
        assertFalse(Files.exists(MAIN.resolve("core/paint/model/ItemKeys.java")), "ItemKeys は削除済み（鍵は DisplayItem.keyOf = AtomTable）");
        try (var files = Files.walk(MAIN)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                assertFalse(Files.readString(p).contains("ItemKeys."), p + " が ItemKeys を参照している");
            }
        }
        assertTrue(Files.readString(MAIN.resolve("core/paint/model/DisplayItem.java")).contains("AtomTable.shared().intern(id)"));
    }

    @Test
    void simdTileSoftwareRendererStaysTestOnly() {
        // 本流から呼ばれない第二のタイルラスタライザだったので、照合基準としてテスト側へ移した（§9.5.21）。本番へ戻さない
        assertFalse(Files.exists(MAIN.resolve("core/kernel/gpu/SimdTileSoftwareRenderer.java")));
    }
}
