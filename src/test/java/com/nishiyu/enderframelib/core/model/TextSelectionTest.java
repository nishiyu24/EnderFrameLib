package com.nishiyu.enderframelib.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TextSelectionTest {

    @Test
    @DisplayName("空の選択モデルの検証")
    void testEmptySelection() {
        TextSelection sel = new TextSelection();
        assertTrue(sel.isEmpty());
        assertEquals("", sel.selectedText());
    }

    @Test
    @DisplayName("ドラッグによるテキスト選択と逆方向選択の検証")
    void testSelectionWithinNode() {
        TextSelection sel = new TextSelection();
        String text = "Hello World, EnderFrameLib!";

        // 順方向 (6 -> 11: "World")
        sel.startSelection("txt1", 6);
        sel.updateWithinNode("txt1", 11, text);
        assertFalse(sel.isEmpty());
        assertEquals("World", sel.selectedText());
        assertEquals(6, sel.startOffset());
        assertEquals(11, sel.endOffset());

        // 逆方向 (11 -> 6: "World")
        sel.startSelection("txt1", 11);
        sel.updateWithinNode("txt1", 6, text);
        assertFalse(sel.isEmpty());
        assertEquals("World", sel.selectedText());
        assertEquals(6, sel.startOffset());
        assertEquals(11, sel.endOffset());
    }

    @Test
    @DisplayName("全選択（selectAll）の検証")
    void testSelectAll() {
        TextSelection sel = new TextSelection();
        String text = "Quick brown fox";

        sel.selectAll("txt1", text);
        assertFalse(sel.isEmpty());
        assertEquals("Quick brown fox", sel.selectedText());
        assertEquals(0, sel.startOffset());
        assertEquals(15, sel.endOffset());

        sel.clear();
        assertTrue(sel.isEmpty());
        assertEquals("", sel.selectedText());
    }
}
