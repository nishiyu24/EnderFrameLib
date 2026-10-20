package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.css.SelectorMatcher.SelectorEngine;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.parser.HtmlParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.RenderPipeline;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;
import com.nishiyu.enderframelib.core.layout.LayoutContext;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.layout.model.Viewport;
import com.nishiyu.enderframelib.core.layout.model.FormattingContext;
import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.css.model.Overflow;
import com.nishiyu.enderframelib.core.css.model.TextAlign;
import com.nishiyu.enderframelib.core.css.model.BorderStyle;

/**
 * カスタムタグのマクロ展開が、正規表現による文字列置換から DOM の部分木差し替えに
 * 移っても同じ構造を作ることの確認。
 */
public class CustomTagExpanderTest {

    private static HtmlNode expanded(String html) {
        HtmlNode root = HtmlParser.parse(html);
        CustomTagExpander.expand(root);
        return root;
    }

    private static List<HtmlNode> slots(HtmlNode root) {
        return SelectorEngine.querySelectorAll(root, ".mc-slot");
    }

    @Test
    public void testSlotKeepsIndexClassStyleAndId() {
        HtmlNode root = expanded("<mc-slot id=\"s1\" index=\"7\" class=\"big\" style=\"margin:2px\"></mc-slot>");
        HtmlNode slot = root.children.get(0);

        assertEquals("div", slot.tag);
        assertEquals("s1", slot.getId());
        assertEquals("7", slot.attrs.get("data-slot"));
        assertTrue(slot.hasClass("mc-slot"));
        assertTrue(slot.hasClass("big"));
        assertEquals("margin:2px", slot.attrs.get("style"));
    }

    @Test
    public void testSlotFallsBackToSlotAttributeThenZero() {
        assertEquals("3", expanded("<mc-slot slot=\"3\"></mc-slot>").children.get(0).attrs.get("data-slot"));
        assertEquals("0", expanded("<mc-slot></mc-slot>").children.get(0).attrs.get("data-slot"));
    }

    @Test
    public void testSlotGridProducesRowsAndSequentialIndices() {
        HtmlNode root = expanded("<mc-slot-grid cols=\"3\" rows=\"2\" start=\"10\" gap=\"4\"></mc-slot-grid>");
        HtmlNode container = root.children.get(0);

        assertEquals(2, container.children.size(), "行数が合わない");
        assertTrue(container.attrs.get("style").contains("gap:4px"));

        List<HtmlNode> all = slots(root);
        assertEquals(6, all.size());
        for (int i = 0; i < all.size(); i++) {
            assertEquals(String.valueOf(10 + i), all.get(i).attrs.get("data-slot"));
        }
    }

    @Test
    public void testInventoryHasThirtySixSlotsWithHotbarLast() {
        HtmlNode root = expanded("<mc-inventory start=\"9\"></mc-inventory>");
        List<HtmlNode> all = slots(root);

        assertEquals(36, all.size());
        // メイン領域は start から 27 個、ホットバーは start+27 から 9 個
        assertEquals("9", all.get(0).attrs.get("data-slot"));
        assertEquals("35", all.get(26).attrs.get("data-slot"));
        assertEquals("36", all.get(27).attrs.get("data-slot"));
    }

    @Test
    public void testInventoryHotbarFirstReordersIndices() {
        HtmlNode root = expanded("<mc-inventory start=\"0\" hotbar-first=\"true\"></mc-inventory>");
        List<HtmlNode> all = slots(root);

        assertEquals("9", all.get(0).attrs.get("data-slot"), "メイン領域は start+9 から");
        assertEquals("0", all.get(27).attrs.get("data-slot"), "ホットバーは start から");
    }

    @Test
    public void testBarProducesProgressAndLabel() {
        HtmlNode root = expanded("<mc-bar value=\"30\" max=\"60\" color=\"#ff0000\" label=\"HP\"></mc-bar>");
        HtmlNode bar = root.children.get(0);

        assertTrue(bar.hasClass("mc-bar"));
        HtmlNode progress = bar.children.get(0);
        assertEquals("progress", progress.tag);
        assertEquals("30", progress.attrs.get("value"));
        assertEquals("60", progress.attrs.get("max"));
        assertTrue(progress.attrs.get("style").contains("#ff0000"));

        HtmlNode label = bar.children.get(1);
        assertEquals("span", label.tag);
        assertEquals("HP", label.children.get(0).text);
    }

    @Test
    public void testBarWithoutLabelHasNoSpan() {
        HtmlNode bar = expanded("<mc-bar value=\"1\"></mc-bar>").children.get(0);
        assertEquals(1, bar.children.size());
    }

    /**
     * 正規表現版はカスタムタグが入れ子の中にあると取りこぼしたり、
     * 属性値に含まれる '>' で壊れたりしていた。
     */
    @Test
    public void testNestedCustomTagsAndAngleBracketInAttributeValue() {
        HtmlNode root = expanded(
                "<div><section><mc-slot index=\"1\" style=\"content:'a>b'\"></mc-slot></section></div>");

        List<HtmlNode> all = slots(root);
        assertEquals(1, all.size());
        assertEquals("1", all.get(0).attrs.get("data-slot"));
        assertNotNull(all.get(0).parent);
        assertEquals("section", all.get(0).parent.tag);
    }

    @Test
    public void testExpansionIsStableAcrossReparse() {
        String html = "<div><mc-slot-grid cols=\"2\" rows=\"2\"></mc-slot-grid></div>";
        assertEquals(expanded(html).getOuterHTML(), expanded(html).getOuterHTML(),
                "同じ入力から同じ DOM（ID を含む）が得られない");
    }
}
