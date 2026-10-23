package com.nishiyu.enderframelib.core.text;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
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
        assertEquals(text.length(), sel.endOffset());
    }

    @Test
    @DisplayName("クリア操作（clear）の検証")
    void testClear() {
        TextSelection sel = new TextSelection("txt1", 0, "txt1", 5, "Hello");
        assertFalse(sel.isEmpty());

        sel.clear();
        assertTrue(sel.isEmpty());
        assertEquals("", sel.selectedText());
    }
}
