package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.dom.HtmlParser;
import com.nishiyu.enderframelib.core.layout.LayoutContext.FormattingContext;
import com.nishiyu.enderframelib.core.layout.LayoutContext.ScrollState;
import com.nishiyu.enderframelib.core.layout.LayoutContext.Viewport;
import com.nishiyu.enderframelib.core.text.FontFace;
import com.nishiyu.enderframelib.core.text.FontResolver;
import com.nishiyu.enderframelib.core.text.FontStyle;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /**
     * 既定の 1 行の高さ。
     * 以前は {@code 16 * 1.375 = 22} の固定値だったが、{@code line-height: normal} は
     * 書体の ascent + descent + 行間から決まる（F2）。値を書き写すとどちらが正しいか
     * 分からなくなるので、書体から引く。W2 で行高を丸めなくなったので、値も丸めない（18.4px）。
     */
    private static final float LINE_H =
            FontFace.FALLBACK.normalLineHeight(new FontStyle(16.0f, false, false, 0f));

    /** 幾何は float（W2）。行高を足し合わせた値どうしを比べるための許容誤差。 */
    private static final float EPS = 0.001f;

    private static LayoutContext newContext() {
        return new LayoutContext(VIEWPORT, src -> new int[]{32, 32}, new ScrollState(), FontResolver.FALLBACK);
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

        float lowestFragmentBottom = 0;
        for (LayoutBox.InlineFragment f : textBox.inlineFragments) {
            lowestFragmentBottom = Math.max(lowestFragmentBottom, f.y + f.h);
        }
        assertTrue(lowestFragmentBottom <= pBox.y + pBox.h,
                "配置した行が測定した高さを超えている: 行の下端=" + lowestFragmentBottom
                        + " 親の下端=" + (pBox.y + pBox.h));

        int lineCount = countDistinctLines(textBox);
        assertEquals(lineCount * LINE_H, pBox.h, EPS, "親の高さが行数と一致しない");
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

        // a / 空行 / b の 3 行分
        assertEquals(3 * LINE_H, divBox.h, EPS);

        LayoutBox lastText = divBox.children.getLast();
        assertEquals(1, lastText.inlineFragments.size());
        // 最後の "b" は 3 行目に置かれる
        assertEquals(divBox.y + 2 * LINE_H, lastText.inlineFragments.getFirst().y, EPS);
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
        assertEquals(lineCount * LINE_H, spanBox.h, EPS, "測定した高さと配置した行数が一致しない");
    }

    /**
     * display が明示されていない #text / span / b / br でもインライン整形コンテキストで配置されること。
     * StyleResolver を通した実戦スタイル（ua.css に display: inline 指定がない状態）を再現する。
     */
    @Test
    public void testInlineElementsWithoutExplicitDisplayPropertyAreLaidOutInline() {
        HtmlNode root = HtmlParser.parse("<div>Hello <b>bold</b> and <span>inline</span><br>next line</div>");
        HtmlNode div = root.children.get(0);

        LayoutBox divBox = new LayoutBox(div, style("display", "block", "width", "300px"));
        for (HtmlNode child : div.children) {
            LayoutBox childBox = new LayoutBox(child, style());
            for (HtmlNode grandChild : child.children) {
                childBox.children.add(new LayoutBox(grandChild, style()));
            }
            divBox.children.add(childBox);
        }

        LayoutContext ctx = newContext();
        divBox.measure(400, 600, new FormattingContext("block", "stretch", false), ctx);
        divBox.layout(0, 0, 400, 600, ctx);

        // 1行目: "Hello " + "bold" + " and " + "inline"、2行目: "next line" の 2 行
        assertEquals(2 * LINE_H, divBox.h, EPS, "インライン要素が横並びにならずブロック積みになっている");
        LayoutBox firstText = divBox.children.get(0);
        LayoutBox lastText = divBox.children.getLast();
        assertFalse(firstText.inlineFragments.isEmpty());
        assertFalse(lastText.inlineFragments.isEmpty());
        assertEquals(0, firstText.inlineFragments.getFirst().y);
        assertEquals(LINE_H, lastText.inlineFragments.getFirst().y, EPS);
    }

    /**
     * 同じ行に乗った断片のベースラインが一致し、x が重ならずに進むこと。
     *
     * F4（フラグメントツリーとインライン整形の作り直し）はこの経路を大きく触る。
     * 行数と親への収まりだけでは「各断片がどこに置かれたか」が守られないので、
     * 座標そのものを固定しておく。
     */
    @Test
    public void testFragmentsOnOneLineShareABaselineAndAdvanceInOrder() {
        HtmlNode root = HtmlParser.parse("<div>Hello <b>bold</b> tail</div>");
        HtmlNode div = root.children.get(0);

        LayoutBox divBox = new LayoutBox(div, style("display", "block", "width", "400px"));
        for (HtmlNode child : div.children) {
            LayoutBox childBox = new LayoutBox(child, style());
            for (HtmlNode grandChild : child.children) {
                childBox.children.add(new LayoutBox(grandChild, style()));
            }
            divBox.children.add(childBox);
        }

        LayoutContext ctx = newContext();
        divBox.measure(400, 600, new FormattingContext("block", "stretch", false), ctx);
        divBox.layout(0, 0, 400, 600, ctx);

        // 幅 400px なら 1 行に収まる
        assertEquals(LINE_H, divBox.h, EPS, "1 行に収まっていない");

        List<LayoutBox.InlineFragment> fragments = new ArrayList<>();
        collectTextFragments(divBox, fragments);
        assertTrue(fragments.size() >= 3, "断片が足りない: " + fragments.size());
        fragments.sort(Comparator.comparingDouble(f -> f.x));

        float expectedBaseline = fragments.getFirst().y + fragments.getFirst().baseline;
        float previousRight = -Float.MAX_VALUE;
        for (LayoutBox.InlineFragment f : fragments) {
            assertEquals(expectedBaseline, f.y + f.baseline, 0.001f,
                    "同じ行の断片のベースラインが揃っていない: " + f.text);
            assertTrue(f.x >= previousRight, "断片が前の断片に重なっている: " + f.text);
            previousRight = f.x + f.w;
        }
        assertTrue(previousRight <= divBox.x + divBox.w, "行が親の幅を超えている: " + previousRight);
    }

    @Test
    public void testWhitespaceBetweenInlineSpansIsPreserved() {
        com.nishiyu.enderframelib.common.UIParser parser = new com.nishiyu.enderframelib.common.UIParser();
        com.nishiyu.enderframelib.common.UIParser.Document doc =
                parser.parseDocument("<div><span>a</span> <span>b</span></div>", "", 400, 200);
        LayoutBox root = parser.layoutDocument(doc, 400, 200, Map.of(), new ScrollState(), FontResolver.FALLBACK);

        List<LayoutBox.InlineFragment> frags = new ArrayList<>();
        collectTextFragments(root, frags);
        frags.sort(Comparator.comparingDouble(f -> f.x));

        LayoutBox.InlineFragment fragA = frags.stream().filter(f -> "a".equals(f.text)).findFirst().orElseThrow();
        LayoutBox.InlineFragment fragB = frags.stream().filter(f -> "b".equals(f.text)).findFirst().orElseThrow();
        assertTrue(fragB.x > fragA.x + fragA.w, "a と b の間に空白の幅が確保されていない: fragA=" + fragA + ", fragB=" + fragB);
    }

    private static void collectTextFragments(LayoutBox box, List<LayoutBox.InlineFragment> out) {
        for (LayoutBox.InlineFragment f : box.inlineFragments) {
            if (f.isText()) out.add(f);
        }
        for (LayoutBox child : box.children) collectTextFragments(child, out);
    }

    private static int countDistinctLines(LayoutBox box) {
        return (int) box.inlineFragments.stream().map(f -> f.y).distinct().count();
    }
}
