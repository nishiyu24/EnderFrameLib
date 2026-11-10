package com.nishiyu.enderframelib.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 表を含む文書での差分レイアウト（{@link EngineContext#incrementalLayout}）が、毎回すべてを作り直して測り直した結果と
 * まったく同じ表示リストになること。表の種類（分離・collapse・fixed・入れ子・キャプション・colspan）を含む文書に、
 * class・style（display の切り替えを含む）・文字・子の増減・リサイズを乱数で混ぜて 1 手ずつ比べる。
 *
 * <p>表の中の箱は前回から使い回さない（表のエンジンが中の箱へ直接書き込むため）。使い回しを試したところ、行・セルの
 * display の切り替えや入れ子の表で全再構築とずれ、しかも測り直しの回数もほとんど減らなかったので採らなかった。
 * このテストは、その判断（表の中は作り直す）を変えようとしたときの安全網になる。</p>
 */
public class TableIncrementalLayoutTest {

    private static final String HTML = """
            <style>
              body { margin: 0; font-size: 13px }
              table { border: 1px solid #444; margin: 4px }
              td, th { border: 1px solid #888; padding: 3px }
              .col { border-collapse: collapse }
              .sep { border-collapse: separate; border-spacing: 4px }
              .fixed { table-layout: fixed; width: 220px }
              .wide { width: 90px }
              .r { background: #fdd }
              .blk { display: block }
              .flex { display: flex }
              .big { font-size: 20px }
              .va { vertical-align: bottom; height: 40px }
              caption { caption-side: bottom }
            </style>
            <table id="t1"><caption id="c1">cap</caption>
              <tr id="h1"><th id="th1">a</th><th id="th2">b</th><th id="th3">c</th></tr>
              <tr id="r1"><td id="d11">one</td><td id="d12">two words</td><td id="d13">3</td></tr>
              <tr id="r2"><td id="d21" colspan="2">span two columns</td><td id="d23">x</td></tr>
              <tr id="r3"><td id="d31">long long text that wraps in narrow columns</td><td id="d32">y</td><td id="d33">z</td></tr>
            </table>
            <table id="t2" class="col fixed"><tr id="s1"><td id="e11">a</td><td id="e12">bb</td></tr><tr id="s2"><td id="e21">ccc</td><td id="e22">d</td></tr></table>
            <div id="wrap"><table id="t3" class="sep"><tbody id="tb"><tr id="u1"><td id="f11">p</td><td id="f12" class="va">q</td></tr></tbody></table></div>
            <table id="t4"><tr id="n1"><td id="g11">outer <table id="t5"><tr id="n2"><td id="g21">inner</td><td id="g22">cell</td></tr></table></td><td id="g12">side</td></tr></table>
            <p id="after">after the tables</p>
            """;

    private static final String[] CLASSES = {"", "col", "sep", "fixed", "col fixed", "wide", "r", "blk", "flex", "big", "va", "r big", "wide va", "sep wide"};
    private static final String[] STYLES = {"", "display:table-cell", "display:block", "display:table", "display:table-row", "width:60px",
            "padding:10px", "border:3px solid red", "height:50px", "display:none", "text-align:right"};

    @AfterEach
    public void restore() {
        com.nishiyu.enderframelib.core.EngineContext.global().incrementalLayout = true;
    }

    private static List<List<String>> run(boolean incremental, long seed) {
        com.nishiyu.enderframelib.core.EngineContext.global().incrementalLayout = incremental;
        return MutationScenario.run(HTML, CLASSES, STYLES, seed, 80);
    }

    private static void assertSame(List<List<String>> expected, List<List<String>> actual, String label, long seed) {
        assertEquals(expected.size(), actual.size());
        for (int step = 0; step < expected.size(); step++) {
            assertEquals(expected.get(step).size(), actual.get(step).size(), label + " seed " + seed + " step " + step + ": 項目の数");
            for (int k = 0; k < expected.get(step).size(); k++) {
                assertEquals(expected.get(step).get(k), actual.get(step).get(k), label + " seed " + seed + " step " + step + " 項目 " + k);
            }
        }
    }

    @Test
    public void incrementalLayoutMatchesAFullRebuildOnTables() {
        for (long seed = 1; seed <= 16; seed++) {
            assertSame(run(false, seed), run(true, seed), "差分レイアウト", seed);
        }
    }

    @Test
    public void scenarioActuallyChangesTheDisplayList() {
        long distinct = run(true, 5).stream().map(List::toString).distinct().count();
        assertTrue(distinct > 30, "手順が表示リストをほとんど変えていない: " + distinct);
    }
}
