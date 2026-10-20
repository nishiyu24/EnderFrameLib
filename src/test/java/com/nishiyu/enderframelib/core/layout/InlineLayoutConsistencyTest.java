package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.layout.TextShaper.FontFace;
import com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutContext;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.dom.HtmlParser;
import com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutContext.FormattingContext;
import com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutContext.ScrollState;
import com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutContext.Viewport;
import com.nishiyu.enderframelib.core.layout.TextShaper.FontFace.FontResolver;
import com.nishiyu.enderframelib.core.layout.TextShaper.FontFace.FontStyle;
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
        com.nishiyu.enderframelib.common.UiDocumentCompiler parser = new com.nishiyu.enderframelib.common.UiDocumentCompiler();
        com.nishiyu.enderframelib.common.UiDocumentCompiler.Document doc =
                parser.parseDocument("<div><span>a</span> <span>b</span></div>", "", 400, 200);
        LayoutBox root = parser.layoutDocument(doc, 400, 200, Map.of(), new ScrollState(), FontResolver.FALLBACK);

        List<LayoutBox.InlineFragment> frags = new ArrayList<>();
        collectTextFragments(root, frags);
        frags.sort(Comparator.comparingDouble(f -> f.x));

        LayoutBox.InlineFragment fragA = frags.stream().filter(f -> "a".equals(f.text)).findFirst().orElseThrow();
        LayoutBox.InlineFragment fragB = frags.stream().filter(f -> "b".equals(f.text)).findFirst().orElseThrow();
        assertTrue(fragB.x > fragA.x + fragA.w, "a と b の間に空白の幅が確保されていない: fragA=" + fragA + ", fragB=" + fragB);
    }

    /**
     * 複数語のインラインボックスは 1 行に 1 つの断片で、左右の装飾は両端に 1 回だけ付く（W11）。
     * 以前は語ごとに装飾つきの断片ができ、左右の padding が語の数だけ足されていた。
     */
    @Test
    public void testMultiWordInlineBoxIsOneFragmentWithEdgesOnce() {
        LayoutBox root = layoutDocument("<div style=\"width: 400px;\">x<span id=\"s\" style=\"padding: 0 4px; border-left: 2px solid; margin-left: 3px;\">alpha beta gamma</span>y</div>");
        LayoutBox span = findById(root, "s");
        List<LayoutBox.InlineFragment> own = ownFragments(span);
        assertEquals(1, own.size(), "1 行のインラインボックスが語ごとに分かれている");

        List<LayoutBox.InlineFragment> words = new ArrayList<>();
        collectTextFragments(span, words);
        words.sort(Comparator.comparingDouble(f -> f.x));
        LayoutBox.InlineFragment box = own.getFirst();
        // 開始端: margin の後ろに border box が始まり、最初の語は border + padding の後ろ
        assertEquals(box.x + 2 + 4, words.getFirst().x, EPS);
        // 終了端: 最後の語の後ろに padding が 1 回
        LayoutBox.InlineFragment last = words.getLast();
        assertEquals(last.x + last.w + 4, box.x + box.w, EPS);
        // 語の間に装飾が挟まっていない
        for (int i = 1; i < words.size(); i++) {
            assertEquals(words.get(i - 1).x + words.get(i - 1).w, words.get(i).x, EPS);
        }
        // 箱の矩形は断片そのもの
        assertEquals(box.x, span.x, EPS);
        assertEquals(box.w, span.w, EPS);
    }

    /** 行をまたぐインラインボックスは、最初の断片だけが開始端を、最後の断片だけが終了端を持つ。 */
    @Test
    public void testWrappedInlineBoxSlicesItsEdges() {
        LayoutBox root = layoutDocument("<div style=\"width: 60px;\"><span id=\"s\" style=\"padding: 0 5px; border: 1px solid;\">alpha beta gamma delta</span></div>");
        LayoutBox span = findById(root, "s");
        List<LayoutBox.InlineFragment> own = ownFragments(span);
        assertTrue(own.size() > 1, "折り返しが起きていない");
        own.sort(Comparator.comparingDouble(f -> f.y));
        for (int i = 0; i < own.size(); i++) {
            assertEquals(i == 0, own.get(i).startEdge, "開始端を持つ断片が違う: " + i);
            assertEquals(i == own.size() - 1, own.get(i).endEdge, "終了端を持つ断片が違う: " + i);
        }
        // 2 行目以降は行の左端から始まり、開始端の装飾を持たない
        LayoutBox.InlineFragment second = own.get(1);
        List<LayoutBox.InlineFragment> words = new ArrayList<>();
        collectTextFragments(span, words);
        float secondLineFirstWord = words.stream().filter(f -> Math.abs(f.y - words.getFirst().y) > EPS)
                .map(f -> f.x).min(Float::compare).orElseThrow();
        assertEquals(second.x, secondLineFirstWord, EPS);
        assertEquals(own.getFirst().x + 1 + 5, words.getFirst().x, EPS);
    }

    /** インラインボックスの上下の padding と border は行の高さに入らない（CSS 2.2 §10.8.1）。 */
    @Test
    public void testInlineBoxVerticalDecorationDoesNotGrowTheLine() {
        LayoutBox root = layoutDocument("<div id=\"d\" style=\"width: 400px;\">a <span id=\"c\" style=\"padding: 6px 2px; border: 3px solid;\">b</span> c</div>");
        LayoutBox div = findById(root, "d");
        assertEquals(LINE_H, div.h, EPS, "インラインの上下の装飾が行を押し広げている");
        // 断片は内容領域の上下に padding と border を足した高さ
        LayoutBox.InlineFragment code = ownFragments(findById(root, "c")).getFirst();
        FontStyle font = new FontStyle(16.0f, false, false, 0f);
        float content = FontFace.FALLBACK.ascent(font) + FontFace.FALLBACK.descent(font);
        assertEquals(content + 2 * (6 + 3), code.h, EPS);
    }

    /** 中身を置く前に折り返したインラインボックスは、開始端ごと次の行へ移る。 */
    @Test
    public void testInlineBoxStartingAtTheWrapPointMovesItsStartEdge() {
        float word = FontFace.FALLBACK.advance("aaaa", new FontStyle(16.0f, false, false, 0f));
        float width = word + 10;
        LayoutBox root = layoutDocument("<div style=\"width: " + width + "px;\">aaaa <span id=\"s\" style=\"padding-left: 20px;\">bbbb</span></div>");
        List<LayoutBox.InlineFragment> own = ownFragments(findById(root, "s"));
        assertEquals(1, own.size(), "開始端だけの空の断片が前の行に残っている");
        assertTrue(own.getFirst().startEdge);
        assertEquals(0, own.getFirst().x, EPS);
    }

    private static LayoutBox layoutDocument(String html) {
        com.nishiyu.enderframelib.common.UiDocumentCompiler parser = new com.nishiyu.enderframelib.common.UiDocumentCompiler();
        com.nishiyu.enderframelib.common.UiDocumentCompiler.Document doc =
                parser.parseDocument("<style>body { margin: 0; }</style>" + html, "", 400, 200);
        return parser.layoutDocument(doc, 400, 200, Map.of(), new ScrollState(), FontResolver.FALLBACK);
    }

    private static LayoutBox findById(LayoutBox box, String id) {
        if (id.equals(box.node.attrs.get("id"))) return box;
        for (LayoutBox child : box.elementChildren) {
            LayoutBox hit = findById(child, id);
            if (hit != null) return hit;
        }
        return null;
    }

    /** インラインボックス自身の断片（中の文字の断片を除く）。 */
    private static List<LayoutBox.InlineFragment> ownFragments(LayoutBox box) {
        return new ArrayList<>(box.inlineFragments.stream().filter(f -> !f.isText() && f.box == box).toList());
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