package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.dom.HtmlParser;
import com.nishiyu.enderframelib.core.text.FontMetrics;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * インラインの「測定した高さ」と「実際に配置した高さ」が一致することの番人。
 *
 * 以前は折り返しロジックが測定用と配置用に別々に書かれていたため、
 * 片方だけ直すと中身がはみ出す／余白が余るというずれが起きていた。
 * 現在は行分割の実装が 1 つなので、両者は必ず一致する。
 */
public class InlineLayoutConsistencyTest {

    private static final Viewport VIEWPORT = new Viewport(1920, 1080);

    private static LayoutContext newContext() {
        return new LayoutContext(VIEWPORT, src -> new int[]{32, 32}, new ScrollState(), FontMetrics.FALLBACK);
    }

    private static ComputedStyle style(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return new ComputedStyle(map);
    }

    /** 折り返しが起きる幅で、親の高さが全行分になっていること。 */
    @Test
    public void testWrappedTextHeightMatchesPlacedLines() {
        HtmlNode root = HtmlParser.parse("<p>alpha beta gamma delta epsilon zeta eta theta</p>");
        HtmlNode p = root.children.get(0);
        HtmlNode text = p.children.get(0);

        LayoutBox pBox = new LayoutBox(p, style("display", "block", "width", "120px"));
        LayoutBox textBox = new LayoutBox(text, style("display", "inline"));
        pBox.children.add(textBox);

        LayoutContext ctx = newContext();
        pBox.measure(400, 600, new FormattingContext("block", "stretch", false), ctx);
        pBox.layout(0, 0, 400, 600, ctx);

        assertTrue(textBox.inlineFragments.size() > 1, "折り返しが起きていない");

        int lowestFragmentBottom = 0;
        for (LayoutBox.InlineFragment f : textBox.inlineFragments) {
            lowestFragmentBottom = Math.max(lowestFragmentBottom, f.y + f.h);
        }
        assertTrue(lowestFragmentBottom <= pBox.y + pBox.h,
                "配置した行が測定した高さを超えている: 行の下端=" + lowestFragmentBottom
                        + " 親の下端=" + (pBox.y + pBox.h));

        // 行数 × 行高 が親の高さに収まっていること（行高は 16px * 1.375 = 22px）
        int lineCount = countDistinctLines(textBox);
        assertEquals(lineCount * 22, pBox.h, "親の高さが行数と一致しない");
    }

    /** 連続した br が、測定でも配置でも同じ行数分の高さを占めること。 */
    @Test
    public void testConsecutiveLineBreaksOccupyHeightInBothPasses() {
        HtmlNode root = HtmlParser.parse("<div>a<br><br>b</div>");
        HtmlNode div = root.children.get(0);

        LayoutBox divBox = new LayoutBox(div, style("display", "block", "width", "200px"));
        for (HtmlNode child : div.children) {
            divBox.children.add(new LayoutBox(child, style("display", "inline")));
        }

        LayoutContext ctx = newContext();
        divBox.measure(400, 600, new FormattingContext("block", "stretch", false), ctx);
        divBox.layout(0, 0, 400, 600, ctx);

        // a / 空行 / b の 3 行分（22px * 3）
        assertEquals(66, divBox.h);

        LayoutBox lastText = divBox.children.getLast();
        assertEquals(1, lastText.inlineFragments.size());
        // 最後の "b" は 3 行目に置かれる
        assertEquals(divBox.y + 44, lastText.inlineFragments.getFirst().y);
    }

    /**
     * shrink-to-fit（幅が内容で決まる）場合でも、測定した高さと配置した行数が一致すること。
     * 幅が未確定なまま測るこの経路が、以前は測定と配置でずれやすい箇所だった。
     */
    @Test
    public void testShrinkToFitHeightMatchesPlacedLines() {
        HtmlNode root = HtmlParser.parse("<span>one two three</span>");
        HtmlNode span = root.children.get(0);

        LayoutBox spanBox = new LayoutBox(span, style("display", "inline-block"));
        LayoutBox textBox = new LayoutBox(span.children.get(0), style("display", "inline"));
        spanBox.children.add(textBox);

        LayoutContext ctx = newContext();
        spanBox.measure(0, 600, new FormattingContext("block", "stretch", true), ctx);
        spanBox.layout(0, 0, 0, 600, ctx);

        int lineCount = countDistinctLines(textBox);
        assertTrue(lineCount >= 1, "断片が置かれていない");
        assertEquals(lineCount * 22, spanBox.h, "測定した高さと配置した行数が一致しない");
    }

    private static int countDistinctLines(LayoutBox box) {
        return (int) box.inlineFragments.stream().map(f -> f.y).distinct().count();
    }
}
