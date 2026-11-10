package com.nishiyu.enderframelib.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 再スタイルの走査が、汚れていない部分木を飛ばしても（{@link EngineContext#pruneCleanSubtrees}）、全ての箱をたどったときと
 * まったく同じ表示リストになること。クラス・style・文字・子の増減・マウスの移動（:hover と祖先を読む規則）・
 * トランジション・描画系の @keyframes・リサイズ（vw と @media）・時間の経過を、乱数で混ぜて 1 手ずつ比べる。
 */
public class RestylePruneTest {

    private static final String HTML = """
            <style>
              :root { --c: #369 }
              body { margin: 0; font-size: 14px }
              .card { padding: 6px; margin: 3px; border: 1px solid #888; background: #eef; transition: background-color .3s, width .3s }
              .card:hover { background: #fde }
              .list:hover .item { color: #c00 }
              .item + .item { margin-top: 2px }
              .item:nth-child(odd) { background: #ddd }
              .a { width: 120px }
              .b { width: 50vw; color: #080 }
              .hot { background: #fdd; border-radius: 12px }
              .card:has(.hot) { outline: 2px solid #f00 }
              .t { width: 100px; transition: width .5s }
              .t.wide { width: 200px }
              @keyframes morph { from { border-radius: 2px } to { border-radius: 30px } }
              .m { animation: morph 1s infinite alternate }
              .v { color: var(--c) }
              .big { font-size: 20px }
              @media (max-width: 300px) { .card { padding: 2px } }
            </style>
            <div class="list" id="l1">
              <div class="card item" id="i1">one <span class="v">v1</span></div>
              <div class="card item a" id="i2">two <b>bold</b></div>
              <div class="card item" id="i3"><div class="t">t</div></div>
              <div class="card item m" id="i4">four</div>
            </div>
            <div class="list" id="l2">
              <div class="card item" id="j1"><span id="s1">x</span> <span class="v">y</span></div>
              <div class="card item" id="j2"><div class="card"><span>deep</span></div></div>
            </div>
            <details id="d"><summary>sum</summary><p>body text</p></details>
            """;

    private static final String[] CLASSES = {"card item", "card item a", "card item b", "card item hot", "card item m", "card item hot m",
            "card item big", "t", "t wide", "v", "", "big v"};
    private static final String[] STYLES = {"", "width:80px", "color:red", "padding:10px", "border-radius:20px", "font-size:18px",
            "display:none", "opacity:.5", "margin-left:7px"};

    @AfterEach
    public void restore() {
        com.nishiyu.enderframelib.core.EngineContext.global().pruneCleanSubtrees = true;
    }

    @Test
    public void skippingCleanSubtreesGivesTheSameDisplayListAsWalkingEverything() {
        for (long seed = 1; seed <= 14; seed++) {
            com.nishiyu.enderframelib.core.EngineContext.global().pruneCleanSubtrees = true;
            List<List<String>> pruned = MutationScenario.run(HTML, CLASSES, STYLES, seed, 70);
            com.nishiyu.enderframelib.core.EngineContext.global().pruneCleanSubtrees = false;
            List<List<String>> full = MutationScenario.run(HTML, CLASSES, STYLES, seed, 70);
            assertEquals(full.size(), pruned.size());
            for (int step = 0; step < full.size(); step++) {
                assertEquals(full.get(step).size(), pruned.get(step).size(), "seed " + seed + " step " + step + ": 項目の数");
                for (int k = 0; k < full.get(step).size(); k++) {
                    assertEquals(full.get(step).get(k), pruned.get(step).get(k), "seed " + seed + " step " + step + " 項目 " + k);
                }
            }
        }
    }

    @Test
    public void theScenarioActuallyChangesTheDisplayList() {
        // 手順が何も変えない（比べる意味が無い）ことを防ぐ
        List<List<String>> steps = MutationScenario.run(HTML, CLASSES, STYLES, 3, 70);
        long distinct = steps.stream().map(List::toString).distinct().count();
        assertTrue(distinct > 30, "手順が表示リストをほとんど変えていない: " + distinct);
    }

    @Test
    public void realShowcasePageGivesTheSameResultWithAndWithoutPruning() throws Exception {
        java.nio.file.Path page = java.nio.file.Path.of("demo/resources/showcase.html");
        if (!java.nio.file.Files.exists(page)) page = java.nio.file.Path.of("core/demo/resources/showcase.html");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.exists(page), "showcase.html が見つからない");
        String html = java.nio.file.Files.readString(page);
        String[] classes = {"", "px", "px flash", "px pulse", "row", "card", "stat", "hot", "fill", "bar", "stripe"};
        String[] styles = {"", "transform:scaleX(.5)", "opacity:.5", "width:120px", "color:red", "display:none", "padding:12px"};
        for (long seed = 1; seed <= 3; seed++) {
            com.nishiyu.enderframelib.core.EngineContext.global().pruneCleanSubtrees = true;
            List<List<String>> pruned = MutationScenario.run(html, classes, styles, seed, 40, false);
            com.nishiyu.enderframelib.core.EngineContext.global().pruneCleanSubtrees = false;
            List<List<String>> full = MutationScenario.run(html, classes, styles, seed, 40, false);
            assertEquals(full.size(), pruned.size());
            for (int step = 0; step < full.size(); step++) {
                assertEquals(full.get(step).size(), pruned.get(step).size(), "showcase seed " + seed + " step " + step + ": 項目の数");
                for (int k = 0; k < full.get(step).size(); k++) {
                    assertEquals(full.get(step).get(k), pruned.get(step).get(k), "showcase seed " + seed + " step " + step + " 項目 " + k);
                }
            }
        }
    }
}
