package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 30 個の項目のうち 1 つの文字だけを変えたとき、測り直す箱の数（M3 の入出力分離の動機を測る）。
 * ブロックは変更の経路だけを測り直すが、flex / grid は項目を「仮の測定 → 確定幅で測り直し」の 2 つの制約で測るため、
 * 単一スロットの結果が互いを追い出し、中身が同じ項目まで毎回測り直す。
 */
class RemeasureCostTest {

    private static String html(String display, String extra) {
        StringBuilder b = new StringBuilder("<div style='display:" + display + ";flex-wrap:wrap;width:400px;gap:4px;" + extra + "'>");
        for (int i = 0; i < 30; i++) {
            b.append("<div style='padding:2px;" + (extra.contains("column") ? "flex:1 1 0;" : "") + "'><div><span id='t").append(i).append("'>item ").append(i)
                    .append("</span></div><div>x</div></div>");
        }
        return b.append("</div>").toString();
    }

    private static long remeasured(String display) {
        return remeasured(display, "", false);
    }

    private static long remeasured(String display, String extra, boolean grow) {
        StandaloneView v = new StandaloneView(html(display, extra), 480, 600, FontResolver.FALLBACK);
        v.ui().flushInvalidation();
        HtmlNode r = v.root().getNodeById("t3");
        for (int i = 0; i < 3; i++) {
            r.updateTextContent("w" + i);
            v.ui().flushInvalidation();
        }
        long before = LayoutBox.measurementsComputed();
        r.updateTextContent(grow ? "q9 word ".repeat(30) : "q9");
        v.ui().flushInvalidation();
        return LayoutBox.measurementsComputed() - before;
    }

    @Test
    void blockRemeasuresOnlyTheChangedPath() {
        long n = remeasured("block");
        assertTrue(n < 20, "block: " + n);
    }

    /** 数値の記録用（現状 flex 約 13・grid 約 15・column 約 29（無効時 386））。上限は退行の番人で、入出力分離で下げていく。 */
    @Test
    void flexAndGridCostIsRecorded() {
        long flex = remeasured("flex");
        long grid = remeasured("grid");
        // 項目が flex-grow で伸びる列方向（確定の測り直しが起きる）。文字を長くして項目の高さを変える
        long column = remeasured("flex", "flex-direction:column;height:300px;align-items:flex-start", true);
        System.out.println("REMEASURE flex=" + flex + " grid=" + grid + " column=" + column);
        assertTrue(column <= 60, "column: " + column);
        assertTrue(flex <= 40, "flex: " + flex);
        assertTrue(grid <= 40, "grid: " + grid);
    }
}
