package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.CssKeywords;
import com.nishiyu.enderframelib.core.layout.model.FormattingContext;
import com.nishiyu.enderframelib.core.layout.model.FormattingContext.Display;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 整形コンテキストの親の種類・揃えが、文字列でなく列挙で判定できること。 */
class FormattingContextKindTest {

    @Test
    void flexDirectionsMapToTheirOwnDisplayAndKeepRowColumnSemantics() {
        for (CssKeywords.FlexDirection d : CssKeywords.FlexDirection.values()) {
            Display disp = Display.valueOf(d.name());
            FormattingContext c = new FormattingContext(disp, true, false);
            assertEquals(d.isRow(), c.isFlexRow(), d.name());
            assertEquals(!d.isRow(), c.isFlexColumn(), d.name());
            assertFalse(c.isBlock());
            assertFalse(c.isGrid());
        }
    }

    @Test
    void nonFlexDisplaysAreNeitherRowNorColumn() {
        for (Display d : new Display[]{Display.BLOCK, Display.GRID, Display.LEGEND}) {
            FormattingContext c = new FormattingContext(d, false, true);
            assertFalse(c.isFlexRow(), d.name());
            assertFalse(c.isFlexColumn(), d.name());
            assertEquals(d == Display.BLOCK, c.isBlock(), d.name());
            assertEquals(d == Display.GRID, c.isGrid(), d.name());
            assertTrue(c.isShrink());
        }
    }

    @Test
    void blockConstantIsStretchedNonShrinking() {
        assertEquals(Display.BLOCK, FormattingContext.BLOCK.parentDisplay());
        assertTrue(FormattingContext.BLOCK.stretch());
        assertFalse(FormattingContext.BLOCK.isShrink());
    }
}
