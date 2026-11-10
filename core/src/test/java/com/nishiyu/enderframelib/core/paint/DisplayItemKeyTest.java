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
}
