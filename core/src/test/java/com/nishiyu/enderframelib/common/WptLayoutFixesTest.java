package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** WPT の参照比較（A11）で直したレイアウトの不具合の回帰テスト。 */
class WptLayoutFixesTest {
    private static final int W = 800, H = 600;

    private static LayoutBox layout(String html) {
        UiDocumentCompiler c = new UiDocumentCompiler();
        Document doc = c.parseDocument("<style>body{margin:0}</style>" + html, "", W, H);
        return c.layoutDocument(doc, W, H, Map.of(), new ScrollState(), FontResolver.FALLBACK);
    }

    private static LayoutBox find(LayoutBox box, String id) {
        if (id.equals(box.node.attrs.get("id"))) return box;
        for (LayoutBox c : box.children) {
            LayoutBox h = find(c, id);
            if (h != null) return h;
        }
        return null;
    }

    @Test
    void tableIsNeverNarrowerThanItsContent() {
        LayoutBox r = layout("<div id=t style='display:table;width:10px;max-width:10px;min-width:5px'><div style='width:100px;height:10px'></div></div>"
                + "<div style='display:flex;width:100px'><div id=f style='display:table;width:10px;max-width:10px'><div style='width:100px;height:10px'></div></div></div>"
                + "<div style='display:flex;flex-direction:column;height:0'><div id=c style='display:table;min-height:0;width:10px'><div style='height:50px'></div></div></div>");
        assertEquals(100, find(r, "t").w(), 0.01f);
        assertEquals(100, find(r, "f").w(), 0.01f);
        assertEquals(50, find(r, "c").h(), 0.01f);
    }

    @Test
    void alignmentIsUnsafeByDefaultAndSafeFallsBackToStart() {
        LayoutBox r = layout("<div style='display:flex;justify-content:center;width:100px'><div id=a style='flex:none;width:140px;height:5px'></div></div>"
                + "<div style='display:flex;justify-content:safe center;width:100px'><div id=b style='flex:none;width:140px;height:5px'></div></div>");
        assertEquals(-20, find(r, "a").x(), 0.01f);
        assertEquals(0, find(r, "b").x(), 0.01f);
    }

    @Test
    void emptyClearingBoxGetsClearance() {
        LayoutBox r = layout("<style>#d::after{content:'';clear:both;display:block}</style>"
                + "<div id=d><span style='float:left;width:50px;height:30px'></span></div>");
        assertEquals(30, find(r, "d").h(), 0.01f);
    }

    @Test
    void leadingByteOrderMarkIsNotText() {
        LayoutBox r = layout("﻿<div id=a style='height:5px'></div>");
        assertEquals(0, find(r, "a").y(), 0.01f);
    }

    @Test
    void absoluteBoxUsesGridAreaAsContainingBlock() {
        LayoutBox r = layout("<div style='position:relative;display:grid;grid-template-columns:50px 50px;grid-template-rows:50px 50px;width:100px'>"
                + "<div id=a style='position:absolute;grid-area:2/2/3/3;left:0;top:0;width:10px;height:10px'></div>"
                + "<div id=b style='position:absolute;grid-area:2/2/3/3;align-self:center;justify-self:end;width:10px;height:10px'></div></div>");
        assertEquals(50, find(r, "a").x(), 0.01f);
        assertEquals(50, find(r, "a").y(), 0.01f);
        assertEquals(90, find(r, "b").x(), 0.01f);
        assertEquals(70, find(r, "b").y(), 0.01f);
    }

    @Test
    void rtlGridPlacesColumnsFromTheRight() {
        LayoutBox r = layout("<div style='display:grid;direction:rtl;grid-template-columns:50px 50px;width:200px'><div id=a style='height:5px'></div></div>");
        assertEquals(150, find(r, "a").x(), 0.01f);
    }

    @Test
    void nestedAbsoluteBoxResolvesHeightFromInsets() {
        LayoutBox r = layout("<div style='position:relative;width:200px;height:200px'><div><div id=a style='position:absolute;top:0;bottom:100px;left:0;right:100px'></div></div></div>");
        assertEquals(100, find(r, "a").w(), 0.01f);
        assertEquals(100, find(r, "a").h(), 0.01f);
    }

    @Test
    void percentageGapsResolveAgainstTheContainer() {
        LayoutBox r = layout("<div id=g style='display:grid;width:200px;height:220px;gap:10% 10%;grid-template-columns:50px 50px'><div></div><div></div></div>");
        assertEquals(20, find(r, "g").columnGap(), 0.01f);
        assertEquals(22, find(r, "g").rowGap(), 0.01f);
    }

    @Test
    void logicalPropertiesMapToPhysicalSides() {
        LayoutBox r = layout("<div id=a style='inline-size:120px;block-size:30px;margin-inline-end:20px'></div>"
                + "<div style='direction:rtl'><div id=b style='width:10px;height:1px;margin-inline-end:20px'></div></div>");
        assertEquals(120, find(r, "a").w(), 0.01f);
        assertEquals(30, find(r, "a").h(), 0.01f);
        assertEquals(20, find(r, "a").mR(), 0.01f);
        assertEquals(20, find(r, "b").mL(), 0.01f);
        assertEquals(0, find(r, "b").mR(), 0.01f);
    }

    @Test
    void justifySpreadsWrappedLinesButNotTheLastOne() {
        LayoutBox r = layout("<div style='width:100px;text-align:justify'><span id=s>aa bb cc dd ee ff gg hh ii</span></div>");
        var frags = find(r, "s").children.get(0).inlineFragments;
        // 1 行目の最後の語は右端に揃う。最終行は開始端から
        float firstLineY = frags.get(0).y;
        var lastOfFirst = frags.stream().filter(f -> f.y == firstLineY).reduce((a, b) -> b).orElseThrow();
        assertTrue(lastOfFirst.x > 50, "1 行目の最後の語 '" + lastOfFirst.text.strip() + "' が右へ寄る");
        var last = frags.get(frags.size() - 1);
        assertEquals(frags.stream().filter(f -> f.y == last.y).findFirst().orElseThrow().x, 0, 0.01f);
    }

    @Test
    void bidiControlsAreInvisibleAndZeroWidth() {
        LayoutBox a = layout("<span id=s>ab⁦c⁩d</span>");
        LayoutBox b = layout("<span id=s>abcd</span>");
        assertEquals(find(b, "s").w(), find(a, "s").w(), 0.01f);
    }

    @Test
    void tableHeightExcludesCaption() {
        LayoutBox r = layout("<div id=t style='display:table;height:80px;width:100px'><div style='display:table-caption;height:20px'></div>"
                + "<div style='display:table-cell'>x</div></div>");
        assertEquals(100, find(r, "t").h(), 0.01f);
    }

    @Test
    void percentBasisInIndefiniteColumnUsesContentSize() {
        LayoutBox r = layout("<div style='display:flex;flex-direction:column'><div id=i style='flex:0 0 0%;height:500px'><div style='height:100px'></div></div></div>");
        assertEquals(100, find(r, "i").h(), 0.01f);
    }

    @Test
    void preWrapViaCssKeepsSpacesAndTabStops() {
        LayoutBox r = layout("<div style='white-space:pre;font-family:monospace;tab-size:4'><span id=s>\tX</span></div>"
                + "<div style='white-space:pre-wrap'><span id=p>a   b</span></div>");
        var tab = find(r, "s").children.get(0).inlineFragments;
        var x = tab.get(tab.size() - 1);
        assertEquals("X", x.text);
        assertTrue(x.x > 0, "tab が次の tab 位置まで進む");
        String text = find(r, "p").children.get(0).displayText();
        assertEquals("a   b", text, "white-space: pre-wrap を CSS で指定しても空白が畳まれない");
    }

    @Test
    void breakSpacesKeepsSpacesAtTheStartOfTheNextLine() {
        LayoutBox r = layout("<div style='white-space:break-spaces;width:60px;font-family:monospace'><span id=s>aaaa    b</span></div>");
        var frags = find(r, "s").children.get(0).inlineFragments;
        var b = frags.get(frags.size() - 1);
        assertEquals("b", b.text);
        assertTrue(b.x > 0, "折り返した行の頭の空白が幅を取る");
    }

    @Test
    void matchParentUsesTheParentsDirection() {
        LayoutBox r = layout("<div style='direction:rtl;text-align:start;width:200px'><div style='direction:ltr;text-align:match-parent'><span id=s>x</span></div></div>");
        assertTrue(find(r, "s").children.get(0).inlineFragments.get(0).x > 150, "親の start（rtl）は右");
    }

    @Test
    void explicitZeroWidthWrapsAtEveryOpportunity() {
        LayoutBox r = layout("<div style='width:0'><span id=s>aa bb</span></div>");
        var frags = find(r, "s").children.get(0).inlineFragments;
        assertNotEquals(frags.get(0).y, frags.get(frags.size() - 1).y, 0.01f);
    }

    @Test
    void letterSpacingIsNotAddedAfterTheLastCharacterOfAnElement() {
        LayoutBox a = layout("<div><span id=s style='letter-spacing:10px'>a</span><span id=t>a</span></div>");
        LayoutBox b = layout("<div><span id=s>a</span><span id=t>a</span></div>");
        assertEquals(find(b, "t").x(), find(a, "t").x(), 0.01f);
    }

    @Test
    void rtlFlexRowStartsAtTheRight() {
        LayoutBox r = layout("<div style='display:flex;direction:rtl;width:200px'><div id=a style='width:50px;height:5px'></div><div id=b style='width:50px;height:5px'></div></div>");
        assertEquals(150, find(r, "a").x(), 0.01f);
        assertEquals(100, find(r, "b").x(), 0.01f);
    }

    @Test
    void collapsedFlexItemTakesNoSpace() {
        LayoutBox r = layout("<div style='display:flex;gap:10px'><div style='width:50px;height:5px'></div>"
                + "<div style='width:50px;height:5px;visibility:collapse'></div><div id=c style='width:50px;height:5px'></div></div>");
        assertEquals(60, find(r, "c").x(), 0.01f);
    }

    @Test
    void shrinkToFitBoxesHonourMinAndMaxWidth() {
        LayoutBox r = layout("<div id=a style='float:left;min-width:200px'>x</div>"
                + "<div id=b style='float:left;clear:both;max-width:60px'>aaa bbb ccc ddd eee</div>");
        assertEquals(200, find(r, "a").w(), 0.01f);
        assertEquals(60, find(r, "b").w(), 0.01f);
        assertTrue(find(r, "b").h() > 30, "max-width の幅で折り返す");
    }

    @Test
    void inlineFlexBaselineComesFromItsFirstLine() {
        LayoutBox r = layout("<div style='line-height:20px'>a<div id=f style='display:inline-flex;align-items:baseline'>"
                + "<div style='font-size:10px'>b</div><div style='align-self:stretch;font-size:35px;line-height:35px'>d</div></div></div>");
        Float b = com.nishiyu.enderframelib.core.layout.BlockLayoutEngine.firstBaseline(find(r, "f"));
        assertNotNull(b, "flex 容器のベースラインがある");
        assertTrue(b < find(r, "f").h(), "下端ではなく項目のベースライン");
    }

    @Test
    void spaceAtARunBoundaryCountsTowardsShrinkToFitWidth() {
        LayoutBox a = layout("<div id=a style='float:left'>aa <span>bb</span></div>");
        LayoutBox b = layout("<div id=a style='float:left'>aa bb</div>");
        assertEquals(find(b, "a").w(), find(a, "a").w(), 0.5f);
        assertEquals(find(a, "a").h(), find(b, "a").h(), 0.01f);
    }

    @Test
    void wrapOpportunityBelongsToTheSpacesWhiteSpace() {
        LayoutBox r = layout("<div id=d style='white-space:nowrap;width:30px'><span style='white-space:normal'><span style='white-space:nowrap'>aaaa</span> </span>bbbb</div>");
        assertTrue(find(r, "d").h() > 30, "normal の空白の後で、nowrap の文字でも折り返せる");
    }

    @Test
    void textWrapBalanceEvensOutLines() {
        LayoutBox r = layout("<div style='width:100px;text-wrap:balance'><span id=s>aaa bbb ccc ddd eee</span></div>");
        var frags = find(r, "s").children.get(0).inlineFragments;
        float firstY = frags.get(0).y, lastY = frags.get(frags.size() - 1).y;
        long firstLine = frags.stream().filter(f -> f.y == firstY).count();
        long lastLine = frags.stream().filter(f -> f.y == lastY).count();
        assertTrue(Math.abs(firstLine - lastLine) <= 1, "行の語の数が揃う: " + firstLine + " / " + lastLine);
    }

    @Test
    void textNodesDoNotMatchElementSelectors() {
        LayoutBox r = layout("<style>.c > * { width: 20px; height: 15px; float: left }</style>"
                + "<div class=c id=c style='width:40px'>\n  <div></div><div></div>\n  <div></div><div></div>\n</div>");
        assertEquals(30, find(r, "c").children.stream().filter(k -> k.node.nodeType == com.nishiyu.enderframelib.core.dom.node.NodeType.ELEMENT)
                .mapToDouble(k -> k.y() + k.h()).max().orElse(0), 0.01f);
    }

    @Test
    void newlineBetweenInlineElementsIsASpace() {
        LayoutBox a = layout("<div><span id=x>a</span>\n<span id=y>b</span></div>");
        LayoutBox b = layout("<div><span id=x>a</span> <span id=y>b</span></div>");
        assertEquals(find(b, "y").x(), find(a, "y").x(), 0.01f);
    }

    @Test
    void leadingSpaceOfAWrappedRunDoesNotStartTheLine() {
        LayoutBox r = layout("<div style='width:60px'>aaaa <span>bbbbbbbb</span> cccc</div>");
        LayoutBox text = r.children.get(0).children.get(1).children.get(0).children.get(2);
        var f = text.inlineFragments.get(text.inlineFragments.size() - 1);
        assertEquals("cccc", f.text.strip());
        assertFalse(f.text.startsWith(" "), "折り返した行の頭に空白が残らない: '" + f.text + "'");
        assertEquals(0, f.x, 0.01f);
    }
}
