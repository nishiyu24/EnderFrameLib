package com.nishiyu.enderframelib.core.kernel;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 測定のたびに「フロー子のリスト」を作る経路が戻らないための番人（§9.5.17）。 */
class FlowChildrenAllocationGateTest {

    private static final Path LAYOUT = Path.of("core/src/main/java/com/nishiyu/enderframelib/core/layout");

    private static int uses(String file) throws IOException {
        int n = 0;
        for (String line : Files.readString(LAYOUT.resolve(file)).split("\n")) {
            String t = line.trim();
            if (t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")) continue;
            if (t.contains("getFlowChildren(")) n++;
        }
        return n;
    }

    @Test
    void measurementPathsIterateChildrenWithoutBuildingAList() throws IOException {
        assertEquals(0, uses("FlexLayoutEngine.java"), "Flex は children を直接走査する");
        assertEquals(0, uses("GridLayoutEngine.java"), "Grid は children から 1 回だけ集める");
        assertEquals(1, uses("TableLayoutEngine.java"), "残るのは入れ子の列群（<colgroup>）の展開 1 箇所だけ");
        assertEquals(1, uses("LayoutBox.java"), "定義そのもの 1 箇所だけ（min-content も直接走査）");
    }
}
