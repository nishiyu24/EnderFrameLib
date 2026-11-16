package com.nishiyu.enderframelib.core.layout;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** B9: 再スタイルのたびに同じ {@code display} 文字列から {@link LayoutBox.Display} を作り直さない（同じ値は同じ実体）。 */
class DisplayParseCacheTest {

    @Test
    void sameStringGivesSameInstance() {
        for (String v : new String[]{"flex", "inline-flex", "grid", "table-cell", "list-item", "block flow-root", " FLEX "}) {
            LayoutBox.Display a = LayoutBox.Display.parse(v);
            assertNotNull(a, v);
            assertSame(a, LayoutBox.Display.parse(v), v);
        }
    }

    @Test
    void cachedValuesEqualFreshParse() {
        assertEquals(new LayoutBox.Display(LayoutBox.Display.Outer.BLOCK, LayoutBox.Display.Inner.FLEX, false),
                LayoutBox.Display.parse("flex"));
        assertEquals(new LayoutBox.Display(LayoutBox.Display.Outer.INLINE, LayoutBox.Display.Inner.FLOW_ROOT, false),
                LayoutBox.Display.parse("inline-block"));
        assertEquals(new LayoutBox.Display(LayoutBox.Display.Outer.BLOCK, LayoutBox.Display.Inner.FLOW_ROOT, false),
                LayoutBox.Display.parse("block flow-root"));
    }

    @Test
    void unparseableStaysNullEvenWhenCached() {
        assertNull(LayoutBox.Display.parse("bogus-value"));
        assertNull(LayoutBox.Display.parse("bogus-value"));
        assertNull(LayoutBox.Display.parse(null));
        assertNull(LayoutBox.Display.parse("list-item flex"));
    }
}
