package com.nishiyu.enderframelib.core.kernel;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** エンジン再設計 Milestone 3 の番人（機械的ゲート）。 */
class Milestone3GateTest {

    private static final Path MAIN = Path.of("core/src/main/java/com/nishiyu/enderframelib");

    @Test
    void layoutRunsASingleMeasurePass() throws IOException {
        String src = Files.readString(MAIN.resolve("common/RenderPipeline.java"));
        assertTrue(src.contains("LAYOUT_PASSES = 1;"), "measure は 1 パス");
        assertFalse(src.contains("LAYOUT_PASSES = 2"));
    }

    @Test
    void kernelLayoutPackageDoesNotDependOnLayoutBoxOrDom() throws IOException {
        try (var files = Files.list(MAIN.resolve("core/kernel/layout"))) {
            for (Path p : files.toList()) {
                String s = Files.readString(p);
                assertFalse(s.contains("import com.nishiyu.enderframelib.core.layout"), p.toString());
                assertFalse(s.contains("import com.nishiyu.enderframelib.core.dom"), p.toString());
            }
        }
    }

    @Test
    void tableBoxesAreReusableWithoutAShapeExclusion() throws IOException {
        String src = Files.readString(MAIN.resolve("core/layout/LayoutBox.java"));
        assertFalse(src.contains("isReusableShape"), "表を除外する isReusableShape は廃止");
        String pipeline = Files.readString(MAIN.resolve("common/RenderPipeline.java"));
        assertTrue(pipeline.contains("resetEngineOutputs()"), "使い回す箱は表の書き込みを捨てる");
        assertFalse(pipeline.contains("LayoutBox.isTableBox(box) || box.display.isNone()) box = null"));
    }
}
