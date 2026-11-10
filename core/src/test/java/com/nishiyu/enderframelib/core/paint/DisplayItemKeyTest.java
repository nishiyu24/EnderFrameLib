package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.ImageFitMode;
import com.nishiyu.enderframelib.core.paint.model.RenderMeta;
import com.nishiyu.enderframelib.core.css.model.AnimationSpec;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 戦略 T2 / P0-3: 64-bit 整数鍵（DisplayItem.keyOf(nodeHandle, partOrdinal)）の検証。
 * 文字列連結・AtomTable.intern を回避し、0 アロケーションで nodeHandle と partOrdinal を
 * 可逆的にパック・アンパックできることを保証する。
 */
class DisplayItemKeyTest {

    @Test
    void packAndUnpackIsReversible() {
        int[] nodes = {1, 2, 42, 1000, 65535, 1_000_000};
        int[] parts = {0, 1, 2, 15, 255, 65535};
        for (int node : nodes) {
            for (int part : parts) {
                long key = DisplayItem.keyOf(node, part);
                assertNotEquals(0L, key, "有効なノードと部品番号の鍵は非ゼロ");
                assertEquals(node, DisplayItem.nodeHandleOf(key), "nodeHandle が正確に復元される");
                assertEquals(part, DisplayItem.partOrdinalOf(key), "partOrdinal が正確に復元される");
            }
        }
    }

    @Test
    void nonPositiveNodeHandleReturnsZero() {
        assertEquals(0L, DisplayItem.keyOf(0, 0));
        assertEquals(0L, DisplayItem.keyOf(-1, 5));
    }

    @Test
    void explicitKeyBypassesAtomTableFallback() {
        long customKey = DisplayItem.keyOf(1234, 5);
        DisplayItem item = new DisplayItem("dummy_id_never_interned_xyz", "dummy_base",
                ElementType.RECT, 0, 0, 10, 10, "", 0, 0, Map.of(), 0, 1f,
                0f, 0f, 1f, 1f, 0f, 0, AnimationSpec.NONE, ImageFitMode.FILL,
                RenderMeta.EMPTY, customKey);
        assertEquals(customKey, item.key(), "渡された customKey がそのまま保持される");
        assertEquals(1234, DisplayItem.nodeHandleOf(item.key()));
        assertEquals(5, DisplayItem.partOrdinalOf(item.key()));
    }

    @Test
    void zeroKeyFallsBackToAtomTableIntern() {
        DisplayItem item = new DisplayItem("test_atom_key_fallback", "test_atom_key_fallback",
                ElementType.RECT, 0, 0, 10, 10, "", 0, 0, Map.of(), 0, 1f,
                0f, 0f, 1f, 1f, 0f, 0, AnimationSpec.NONE, ImageFitMode.FILL,
                RenderMeta.EMPTY, 0L);
        assertNotEquals(0L, item.key(), "0L 時は AtomTable から導出された非ゼロの鍵を持つ");
        assertEquals(DisplayItem.keyOf("test_atom_key_fallback"), item.key());
    }

    @Test
    void keysDoNotCollideAcrossDifferentNodesAndParts() {
        long k1 = DisplayItem.keyOf(10, 1);
        long k2 = DisplayItem.keyOf(10, 2);
        long k3 = DisplayItem.keyOf(11, 1);
        assertNotEquals(k1, k2);
        assertNotEquals(k1, k3);
        assertNotEquals(k2, k3);
    }

    @Test
    void decorationPartsUsePackedKeysInTheirOwnNamespaceAndNeverCollide() {
        String html = "<div id=\"card\" style=\"width:120px;height:40px;background:#fc0;"
                + "box-shadow:4px 4px 0 #000, 0 0 8px #333, inset 0 0 3px #00f;\">card text</div>"
                + "<p>a <code id=\"c\" style=\"background:#eee;border:1px solid #888;border-radius:4px\">inline code</code> b</p>";
        com.nishiyu.enderframelib.common.StandaloneView view = new com.nishiyu.enderframelib.common.StandaloneView(
                html, 300, 200, com.nishiyu.enderframelib.core.text.model.FontResolver.FALLBACK);
        try {
            java.util.List<DisplayItem> items = view.items();
            java.util.Set<Long> seen = new java.util.HashSet<>();
            int shadows = 0, insets = 0, frags = 0;
            for (DisplayItem it : items) {
                // 鍵は全項目で一意（文字列由来の鍵とも、パック鍵同士とも衝突しない）
                assertTrue(seen.add(it.key()), "鍵の衝突: " + it.id() + " key=" + it.key());
                String id = it.id();
                boolean shadow = id.contains("_shadow_in") ? false : id.contains("_shadow_");
                boolean inset = id.contains("_shadow_in");
                boolean frag = id.contains("_frag_") || (id.contains("_b_round_") && id.matches(".*_b_round_\\d+$") && id.startsWith("c"));
                if (!(shadow || inset || frag)) continue;
                int handle = DisplayItem.nodeHandleOf(it.key());
                int part = DisplayItem.partOrdinalOf(it.key());
                assertTrue(handle > 0, "装飾部品は節ハンドルをパックしている: " + id);
                if (shadow) { shadows++; assertTrue(part >= 0xC000 && part < 0xE000, id + " part=" + part); }
                if (inset) { insets++; assertTrue(part >= 0xE000, id + " part=" + part); }
                if (frag) { frags++; assertTrue(part >= 0x8000 && part < 0xC000, id + " part=" + part); }
            }
            assertTrue(shadows >= 2, "外側の影（ぼかしなし・あり）が出ている: " + shadows);
            assertEquals(1, insets, "内側の影が 1 つ");
            assertTrue(frags >= 1, "インライン断片の背景が出ている: " + frags);
        } finally {
            view.close();
        }
    }

    @Test
    void renderedTextDisplayItemsHavePackedKeysFromNode() {
        String html = "<div id=\"parent\"><span id=\"s1\">hello</span> <span id=\"s2\">world</span></div>";
        com.nishiyu.enderframelib.common.StandaloneView view = new com.nishiyu.enderframelib.common.StandaloneView(
                html, 300, 200, com.nishiyu.enderframelib.core.text.model.FontResolver.FALLBACK);
        try {
            java.util.List<DisplayItem> items = view.items();
            int textCount = 0;
            for (DisplayItem it : items) {
                if (it.type() == ElementType.TEXT) {
                    textCount++;
                    int nodeHandle = DisplayItem.nodeHandleOf(it.key());
                    assertTrue(nodeHandle > 0, "テキスト項目の鍵は nodeHandle をパックしている: " + it.key() + " (" + it.text() + ")");
                }
            }
            assertTrue(textCount >= 2, "2 つ以上のテキスト項目が生成された");
        } finally {
            view.close();
        }
    }

    @Test
    void majorPartOrdinalsAreDistinctAndInReservedNamespace() {
        int[] parts = {
                DisplayItem.PART_NODE,
                DisplayItem.PART_BG,
                DisplayItem.PART_BORDER,
                DisplayItem.PART_INPUT,
                DisplayItem.PART_CHECKBOX,
                DisplayItem.PART_RADIO,
                DisplayItem.PART_SELECT,
                DisplayItem.PART_TEXTAREA,
                DisplayItem.PART_CANVAS,
                DisplayItem.PART_SCENE,
                DisplayItem.PART_MEDIA,
                DisplayItem.PART_PLACEHOLDER,
                DisplayItem.PART_SCROLLBAR_V,
                DisplayItem.PART_SCROLLBAR_H,
                DisplayItem.PART_BGL_BASE
        };
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int p : parts) {
            assertTrue(p >= 0x7000 && p <= 0x7FFF, "主要部品番号は 0x7000〜0x7FFF の予約領域にある: 0x" + Integer.toHexString(p));
            assertTrue(seen.add(p), "部品番号の重複なし: 0x" + Integer.toHexString(p));
        }
    }

    @Test
    void nodeAndBackgroundAndControlItemsUsePackedKeys() {
        String html = "<div id=\"card\" style=\"width:100px;height:50px;background:#336699\">"
                + "<input id=\"in\" type=\"text\" value=\"abc\">"
                + "<input id=\"chk\" type=\"checkbox\" checked>"
                + "<select id=\"sel\"><option value=\"1\">one</option></select>"
                + "</div>";
        com.nishiyu.enderframelib.common.StandaloneView view = new com.nishiyu.enderframelib.common.StandaloneView(
                html, 300, 200, com.nishiyu.enderframelib.core.text.model.FontResolver.FALLBACK);
        try {
            java.util.List<DisplayItem> items = view.items();
            boolean foundNode = false, foundBg = false, foundInput = false, foundChk = false, foundSel = false;
            for (DisplayItem it : items) {
                int handle = DisplayItem.nodeHandleOf(it.key());
                int part = DisplayItem.partOrdinalOf(it.key());
                if (it.type() == ElementType.NODE && "card".equals(it.id())) {
                    foundNode = true;
                    assertTrue(handle > 0, "card NODE item has positive node handle");
                    assertEquals(DisplayItem.PART_NODE, part, "card NODE has PART_NODE");
                } else if ("card_bg".equals(it.id())) {
                    foundBg = true;
                    assertTrue(handle > 0, "card_bg item has positive node handle");
                    assertEquals(DisplayItem.PART_BG, part, "card_bg has PART_BG");
                } else if ("in_input".equals(it.id())) {
                    foundInput = true;
                    assertTrue(handle > 0, "in_input item has positive node handle");
                    assertEquals(DisplayItem.PART_INPUT, part, "in_input has PART_INPUT");
                } else if ("chk_input".equals(it.id())) {
                    foundChk = true;
                    assertTrue(handle > 0, "chk_input item has positive node handle");
                    assertEquals(DisplayItem.PART_CHECKBOX, part, "chk_input has PART_CHECKBOX");
                } else if ("sel_select".equals(it.id())) {
                    foundSel = true;
                    assertTrue(handle > 0, "sel_select item has positive node handle");
                    assertEquals(DisplayItem.PART_SELECT, part, "sel_select has PART_SELECT");
                }
            }
            assertTrue(foundNode, "card NODE item found");
            assertTrue(foundBg, "card_bg item found");
            assertTrue(foundInput, "in_input item found");
            assertTrue(foundChk, "chk_input item found");
            assertTrue(foundSel, "sel_select item found");

            // PaintSceneDriver & HitTestIndex lookup test
            HitTestIndex hitIdx = view.ui().hitTestIndex();
            com.nishiyu.enderframelib.core.dom.node.HtmlNode cardNode = view.root().getNodeById("card");
            assertNotNull(cardNode);
            int cardHandle = cardNode.nodeId();

            // Lookup by handle & part
            DisplayItem cardByHandle = hitIdx.getElementByHandle(cardHandle, DisplayItem.PART_NODE);
            assertNotNull(cardByHandle, "HitTestIndex getElementByHandle finds card NODE");
            assertEquals("card", cardByHandle.id());

            DisplayItem cardBgByHandle = hitIdx.getElementByHandle(cardHandle, DisplayItem.PART_BG);
            assertNotNull(cardBgByHandle, "HitTestIndex getElementByHandle finds card_bg");
            assertEquals("card_bg", cardBgByHandle.id());

            // Lookup by string ID (backward compatibility)
            DisplayItem cardById = hitIdx.getElementById("card");
            assertNotNull(cardById, "HitTestIndex getElementById finds card NODE");
            assertSame(cardByHandle, cardById);

            DisplayItem cardBgById = hitIdx.getElementById("card_bg");
            assertNotNull(cardBgById, "HitTestIndex getElementById finds card_bg");
            assertSame(cardBgByHandle, cardBgById);

            // PaintSceneDriver direct test
            PaintSceneDriver driver = new PaintSceneDriver("test_session");
            driver.prepare(items, 1000, com.nishiyu.enderframelib.core.raster.Matrix2D.IDENTITY);

            DisplayItem driverItemByHandle = driver.item(cardHandle, DisplayItem.PART_BG);
            assertNotNull(driverItemByHandle, "PaintSceneDriver.item(handle, part) finds card_bg");
            assertEquals("card_bg", driverItemByHandle.id());

            DisplayItem driverItemById = driver.itemById("card_bg");
            assertNotNull(driverItemById, "PaintSceneDriver.itemById('card_bg') finds card_bg");
            assertSame(driverItemByHandle, driverItemById);

            int indexByHandle = driver.indexOf(cardHandle, DisplayItem.PART_BG);
            int indexById = driver.indexOf("card_bg");
            assertTrue(indexByHandle >= 0, "indexOf(handle, part) >= 0");
            assertEquals(indexByHandle, indexById, "index by handle and string ID match");
        } finally {
            view.close();
        }
    }

    @Test
    void remainingPartOrdinalsAreDistinctAndInReservedNamespace() {
        int[] parts = {
                DisplayItem.PART_NODE,
                DisplayItem.PART_BG,
                DisplayItem.PART_BORDER,
                DisplayItem.PART_INPUT,
                DisplayItem.PART_CHECKBOX,
                DisplayItem.PART_RADIO,
                DisplayItem.PART_SELECT,
                DisplayItem.PART_TEXTAREA,
                DisplayItem.PART_CANVAS,
                DisplayItem.PART_SCENE,
                DisplayItem.PART_MEDIA,
                DisplayItem.PART_PLACEHOLDER,
                DisplayItem.PART_SCROLLBAR_V,
                DisplayItem.PART_SCROLLBAR_H,
                DisplayItem.PART_SCROLLBAR_TRACK_V,
                DisplayItem.PART_SCROLLBAR_TRACK_H,
                DisplayItem.PART_PROGRESS,
                DisplayItem.PART_CIRCLE_BORDER,
                DisplayItem.PART_BORDER_TOP,
                DisplayItem.PART_BORDER_RIGHT,
                DisplayItem.PART_BORDER_BOTTOM,
                DisplayItem.PART_BORDER_LEFT,
                DisplayItem.PART_GHOST_IMAGE,
                DisplayItem.PART_BGL_FALLBACK,
                DisplayItem.PART_SCROLLBAR_NODE_V,
                DisplayItem.PART_SCROLLBAR_NODE_H,
                DisplayItem.PART_SCROLL_CONTENT,
                DisplayItem.PART_BGL_BASE,
                DisplayItem.PART_BORDER_ROUND_BASE,
                DisplayItem.PART_BORDER_IMAGE_BASE
        };
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int p : parts) {
            assertTrue(p >= 0x7000 && p <= 0x7FFF, "Part ordinal 0x" + Integer.toHexString(p) + " must be in 0x7000..0x7FFF");
            assertTrue(seen.add(p), "Part ordinal 0x" + Integer.toHexString(p) + " must be distinct");
        }
    }

    @Test
    void bordersProgressScrollbarsAndScrollContentUsePackedKeys() {
        String html = "<div id=\"container\" style=\"width:100px;height:60px;overflow:scroll;border:2px solid #ff0000;border-radius:4px;\">"
                + "<progress id=\"prog\" value=\"40\" max=\"100\" style=\"width:80px;height:10px;\"></progress>"
                + "<div id=\"content\" style=\"width:150px;height:120px;\">hello</div>"
                + "</div>";
        com.nishiyu.enderframelib.common.StandaloneView view = new com.nishiyu.enderframelib.common.StandaloneView(
                html, 300, 200, com.nishiyu.enderframelib.core.text.model.FontResolver.FALLBACK);
        try {
            java.util.List<DisplayItem> items = view.items();
            boolean foundProg = false, foundBorder = false, foundSbV = false, foundSbVt = false, foundScrollc = false;
            for (DisplayItem it : items) {
                int handle = DisplayItem.nodeHandleOf(it.key());
                int part = DisplayItem.partOrdinalOf(it.key());
                if ("prog_progress".equals(it.id())) {
                    foundProg = true;
                    assertTrue(handle > 0, "prog_progress has positive node handle");
                    assertEquals(DisplayItem.PART_PROGRESS, part, "prog_progress has PART_PROGRESS");
                } else if ("container_b_round".equals(it.id())) {
                    foundBorder = true;
                    assertTrue(handle > 0, "container_b_round has positive node handle");
                    assertEquals(DisplayItem.PART_BORDER, part, "container_b_round has PART_BORDER");
                } else if (it.id() != null && it.id().endsWith("_sb_v")) {
                    foundSbV = true;
                    assertTrue(handle > 0, "scrollbar thumb has positive node handle");
                    assertEquals(DisplayItem.PART_SCROLLBAR_V, part, "scrollbar thumb has PART_SCROLLBAR_V");
                } else if (it.id() != null && it.id().endsWith("_sb_vt")) {
                    foundSbVt = true;
                    assertTrue(handle > 0, "scrollbar track has positive node handle");
                    assertEquals(DisplayItem.PART_SCROLLBAR_TRACK_V, part, "scrollbar track has PART_SCROLLBAR_TRACK_V");
                } else if (it.id() != null && it.id().endsWith("_scrollc")) {
                    foundScrollc = true;
                    assertTrue(handle > 0, "scrollc node has positive node handle");
                    assertEquals(DisplayItem.PART_SCROLL_CONTENT, part, "scrollc node has PART_SCROLL_CONTENT");
                }
            }
            assertTrue(foundProg, "prog_progress found");
            assertTrue(foundBorder, "container_b_round found");
            assertTrue(foundSbV, "scrollbar thumb found");
            assertTrue(foundSbVt, "scrollbar track found");
            assertTrue(foundScrollc, "scrollc node found");
        } finally {
            view.close();
        }
    }

    @Test
    void parentKeyInRenderHintsMatchesParentNodeKey() {
        String html = "<div id=\"box\" style=\"width:100px;height:50px;background:#112233;\">"
                + "<span id=\"txt\">Sample</span>"
                + "</div>";
        com.nishiyu.enderframelib.common.StandaloneView view = new com.nishiyu.enderframelib.common.StandaloneView(
                html, 300, 200, com.nishiyu.enderframelib.core.text.model.FontResolver.FALLBACK);
        try {
            java.util.List<DisplayItem> items = view.items();
            com.nishiyu.enderframelib.core.dom.node.HtmlNode boxNode = view.root().getNodeById("box");
            assertNotNull(boxNode);
            long expectedBoxNodeKey = DisplayItem.nodeKeyOf(boxNode.nodeId());

            boolean verifiedBg = false;
            for (DisplayItem it : items) {
                if ("box_bg".equals(it.id())) {
                    assertEquals(expectedBoxNodeKey, it.hints().parentKey(), "box_bg hints.parentKey matches box nodeKey");
                    assertEquals("box", it.hints().parentId(), "box_bg hints.parentId matches 'box'");
                    verifiedBg = true;
                }
            }
            assertTrue(verifiedBg, "box_bg parentKey was verified");
        } finally {
            view.close();
        }
    }
}
