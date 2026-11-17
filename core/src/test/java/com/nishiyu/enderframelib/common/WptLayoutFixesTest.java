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
        assertEquals(100, find(r, "t").w, 0.01f);
        assertEquals(100, find(r, "f").w, 0.01f);
        assertEquals(50, find(r, "c").h, 0.01f);
    }

    @Test
    void alignmentIsUnsafeByDefaultAndSafeFallsBackToStart() {
        LayoutBox r = layout("<div style='display:flex;justify-content:center;width:100px'><div id=a style='flex:none;width:140px;height:5px'></div></div>"
                + "<div style='display:flex;justify-content:safe center;width:100px'><div id=b style='flex:none;width:140px;height:5px'></div></div>");
        assertEquals(-20, find(r, "a").x, 0.01f);
        assertEquals(0, find(r, "b").x, 0.01f);
    }

    @Test
    void emptyClearingBoxGetsClearance() {
        LayoutBox r = layout("<style>#d::after{content:'';clear:both;display:block}</style>"
                + "<div id=d><span style='float:left;width:50px;height:30px'></span></div>");
        assertEquals(30, find(r, "d").h, 0.01f);
    }

    @Test
    void leadingByteOrderMarkIsNotText() {
        LayoutBox r = layout("﻿<div id=a style='height:5px'></div>");
        assertEquals(0, find(r, "a").y, 0.01f);
    }

    @Test
    void absoluteBoxUsesGridAreaAsContainingBlock() {
        LayoutBox r = layout("<div style='position:relative;display:grid;grid-template-columns:50px 50px;grid-template-rows:50px 50px;width:100px'>"
                + "<div id=a style='position:absolute;grid-area:2/2/3/3;left:0;top:0;width:10px;height:10px'></div>"
                + "<div id=b style='position:absolute;grid-area:2/2/3/3;align-self:center;justify-self:end;width:10px;height:10px'></div></div>");
        assertEquals(50, find(r, "a").x, 0.01f);
        assertEquals(50, find(r, "a").y, 0.01f);
        assertEquals(90, find(r, "b").x, 0.01f);
        assertEquals(70, find(r, "b").y, 0.01f);
    }

    @Test
    void rtlGridPlacesColumnsFromTheRight() {
        LayoutBox r = layout("<div style='display:grid;direction:rtl;grid-template-columns:50px 50px;width:200px'><div id=a style='height:5px'></div></div>");
        assertEquals(150, find(r, "a").x, 0.01f);
    }

    @Test
    void nestedAbsoluteBoxResolvesHeightFromInsets() {
        LayoutBox r = layout("<div style='position:relative;width:200px;height:200px'><div><div id=a style='position:absolute;top:0;bottom:100px;left:0;right:100px'></div></div></div>");
        assertEquals(100, find(r, "a").w, 0.01f);
        assertEquals(100, find(r, "a").h, 0.01f);
    }

    @Test
    void percentageGapsResolveAgainstTheContainer() {
        LayoutBox r = layout("<div id=g style='display:grid;width:200px;height:220px;gap:10% 10%;grid-template-columns:50px 50px'><div></div><div></div></div>");
        assertEquals(20, find(r, "g").columnGap, 0.01f);
        assertEquals(22, find(r, "g").rowGap, 0.01f);
    }

    @Test
    void logicalPropertiesMapToPhysicalSides() {
        LayoutBox r = layout("<div id=a style='inline-size:120px;block-size:30px;margin-inline-end:20px'></div>"
                + "<div style='direction:rtl'><div id=b style='width:10px;height:1px;margin-inline-end:20px'></div></div>");
        assertEquals(120, find(r, "a").w, 0.01f);
        assertEquals(30, find(r, "a").h, 0.01f);
        assertEquals(20, find(r, "a").mR, 0.01f);
        assertEquals(20, find(r, "b").mL, 0.01f);
        assertEquals(0, find(r, "b").mR, 0.01f);
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
        assertEquals(find(b, "s").w, find(a, "s").w, 0.01f);
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
