package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 近道の切り分けスイッチの数のラチェットと、組み合わせ検証（{@link ShortcutMatrixMain}、A6）の部品の検証。
 */
class ShortcutSwitchRatchetTest {

    /** 対象のスイッチ（GPU 面を除く）の数の上限。K3 で noshift を外し 29、K5 で STALE_TEXT_PATCH・TEXT_PATCH・ATTR_PATCH・COMBINED_TEXT_PATCH を外し 27 へ下げた（TEXT_PATCH・ATTR_PATCH は元から対象外）。 */
    private static final int MAX_SWITCHES = 27;

    private static Path mainSources() {
        Path p = Path.of("core/src/main/java");
        return Files.isDirectory(p) ? p : Path.of("src/main/java");
    }

    @Test
    void switchCountDoesNotGrow() throws IOException {
        List<String> found = ShortcutMatrixMain.findSwitches(mainSources());
        assertTrue(found.size() <= MAX_SWITCHES, "近道の切り分けスイッチが増えた: " + found.size() + " > " + MAX_SWITCHES + "\n" + found
                + "\n（足すなら先に ./gradlew :core:shortcutMatrix で組み合わせの一致を確かめ、不要な古いスイッチを消して上限を保つ）");
        assertTrue(found.size() >= 25, "検出器が壊れている（スイッチが少なすぎる）: " + found);
    }

    @Test
    void pairwiseRowsCoverEveryOnOffCombinationOfEveryPair() {
        int n = 37;
        List<boolean[]> rows = ShortcutMatrixMain.pairwise(n);
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                boolean[] seen = new boolean[4];
                for (boolean[] r : rows) seen[(r[i] ? 2 : 0) + (r[j] ? 1 : 0)] = true;
                for (int k = 0; k < 4; k++) assertTrue(seen[k], "組 (" + i + "," + j + ") の " + k + " が通らない");
            }
        }
        assertTrue(rows.size() <= 20, "構成が多すぎる: " + rows.size());
        assertEquals(rows.size(), ShortcutMatrixMain.pairwise(n).size(), "同じ入力なら同じ配列（決定的）");
    }

    @Test
    void detectorFindsSwitchesInSyntheticSources() throws IOException {
        Path dir = Files.createTempDirectory("switches");
        try {
            Files.writeString(dir.resolve("A.java"), "class A {\n"
                    + " static final boolean X = !Boolean.getBoolean(\"enderframe.layout.noalpha\");\n"
                    + " static final boolean Y = Boolean.getBoolean( \"enderframe.sheetcache.off\" );\n"
                    + " static final boolean G = Boolean.getBoolean(\"enderframe.gl.noreplay\");\n"      // GPU 面: 対象外
                    + " static final boolean V = Boolean.getBoolean(\"enderframe.verify.memo\");\n" // 近道ではない
                    + "}\n");
            assertEquals(List.of("enderframe.layout.noalpha", "enderframe.sheetcache.off"), ShortcutMatrixMain.findSwitches(dir));
        } finally {
            Files.deleteIfExists(dir.resolve("A.java"));
            Files.deleteIfExists(dir);
        }
    }

    /** シナリオが検査として効くこと: 手順（乱数の種）が違えば署名が違い、同じなら一致する。 */
    @Test
    void scenarioSignaturesDependOnTheSeedAndAreRepeatable() {
        String html = "<style>.a{width:80px}.b{width:160px}</style><div id='r'><div id='x' class='a'>one</div><div id='y' class='b'>two</div></div>";
        String[] classes = {"a", "b", ""};
        String[] styles = {"", "color:red", "padding:6px"};
        var s1 = MutationScenario.run(html, classes, styles, 1, 20, false, true);
        var s1again = MutationScenario.run(html, classes, styles, 1, 20, false, true);
        var s2 = MutationScenario.run(html, classes, styles, 2, 20, false, true);
        assertEquals(s1, s1again);
        assertNotEquals(s1, s2);
    }
}
