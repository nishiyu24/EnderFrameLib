package com.nishiyu.enderframelib.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.core.kernel.Shortcut;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * パッチ（前回の出力をその場で書き換える経路）の増加禁止ラチェット（ENGINE_REARCHITECTURE_PLAN C0 ①）。
 * 表は {@code patch-sites.txt}。パッチ系の近道スイッチの一覧・{@code cant(…)} の箇所数・パッチ専用クラスの行数を凍結し、
 * 増えたら赤（C1〜C5 でメモ化へ置き換えて消したら、表の行も消して下げる）。
 */
class PatchSiteRatchetTest {
    private static final String TABLE = "patch-sites.txt";
    /** パッチ系の近道スイッチとみなす名前（表に無い新しいものは赤）。 */
    private static final Pattern PATCH_SWITCH_NAME = Pattern.compile(".*(PATCH|SHIFT|KEEP_PLACEMENTS|CHANGE_STAMPS).*");
    private static final Pattern CANT_CALL = Pattern.compile("\\bcant\\(\\s*\\d+\\s*,\\s*\\d+\\s*\\)");
    private static final String DLB = "core/paint/DisplayListBuilder.java";

    private static Map<String, String[]> table() throws IOException {
        return RatchetSupport.read(RatchetSupport.table(TABLE), 4);
    }

    /** 実測: patch スイッチの名前の集合（Shortcut の定義から）。 */
    static Set<String> actualPatchSwitches() {
        Set<String> out = new LinkedHashSet<>();
        for (Shortcut s : Shortcut.values()) if (PATCH_SWITCH_NAME.matcher(s.name()).matches()) out.add(s.name());
        return out;
    }

    /** {@code cant(<数>, <数>)} の呼び出し箇所数（コメントと文字列を除く。定義 {@code cant(int, int)} は数でないので数えない）。 */
    static int countCantSites(String source) {
        Matcher m = CANT_CALL.matcher(RatchetSupport.stripCommentsAndStrings(source, false));
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    static int lineCount(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).size();
    }

    @Test
    void patchSwitchesAreExactlyTheTabulatedOnes() throws IOException {
        Set<String> tabulated = new LinkedHashSet<>();
        for (String k : table().keySet()) if (k.startsWith("switch:")) tabulated.add(k.substring("switch:".length()));
        Set<String> actual = actualPatchSwitches();
        Set<String> added = new LinkedHashSet<>(actual);
        added.removeAll(tabulated);
        Set<String> stale = new LinkedHashSet<>(tabulated);
        stale.removeAll(actual);
        assertTrue(added.isEmpty(), "表にないパッチ系スイッチが増えた（パッチを足さずにメモ化で書く。§1.2 原則 2）: " + added);
        assertTrue(stale.isEmpty(), "Shortcut から消えたスイッチが表に残っている（行を消して下げる）: " + stale);
    }

    @Test
    void cantSitesDoNotGrow() throws IOException {
        int actual = countCantSites(Files.readString(RatchetSupport.mainRoot().resolve(DLB), StandardCharsets.UTF_8));
        int ceiling = Integer.parseInt(table().get("cant-sites")[0]);
        assertTrue(actual >= 15, "検出器が壊れている（cant の箇所が少なすぎる）: " + actual);
        assertTrue(actual <= ceiling, "DisplayListBuilder の cant(…) が増えた: " + actual + " > " + ceiling + "（パッチの退く条件を足さない）");
        assertEquals(ceiling, actual, "減った: 表の cant-sites を " + actual + " へ下げる（-Dratchet.update=true）");
    }

    @Test
    void patchClassesDoNotGrow() throws IOException {
        List<String> problems = new ArrayList<>();
        for (var e : table().entrySet()) {
            if (!e.getKey().startsWith("lines:")) continue;
            Path f = RatchetSupport.mainRoot().resolve(e.getKey().substring("lines:".length()));
            int ceiling = Integer.parseInt(e.getValue()[0]);
            if (!Files.exists(f)) { problems.add(f + " が無い（削除したなら表の行も消す）"); continue; }
            int actual = lineCount(f);
            if (actual > ceiling) problems.add(e.getKey() + " が増えた: " + actual + " > " + ceiling);
            else if (actual < ceiling) problems.add(e.getKey() + " が減った: 表を " + actual + " へ下げる");
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void everyRowNamesAStageAndAGuardTest() throws IOException {
        for (var e : table().entrySet()) {
            assertTrue(e.getValue()[1].matches("C[0-5]"), e.getKey() + ": 置き換え先の段が C0〜C5 でない: " + e.getValue()[1]);
            assertTrue(!e.getValue()[2].isBlank(), e.getKey() + ": 守るテストが空");
        }
    }

    @Test
    void detectorCountsOnlyRealCallsInSyntheticSource() {
        String src = "class A {\n"
                + "  private static boolean cant(int which, int reason) { return false; }\n"   // 定義: 数でない
                + "  boolean f() { // cant(0, 1) はコメント\n"
                + "    String s = \"cant(0, 2)\";\n"                                         // 文字列
                + "    if (x) return cant(0, 3);\n"
                + "    return cant( 1 , 4 );\n"
                + "  }\n}\n";
        assertEquals(2, countCantSites(src));
        assertTrue(PATCH_SWITCH_NAME.matcher("NEW_PATCH_INDEX").matches());
        assertTrue(!PATCH_SWITCH_NAME.matcher("FLOAT_MEMO").matches());
    }
}
