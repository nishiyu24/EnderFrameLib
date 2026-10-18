package com.nishiyu.enderframelib.core.css;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * ショートハンドがロングハンドの読み手まで届くことを固定する。
 *
 * 何が壊れていたか: {@code flex} の展開が無かったため {@code flex: 1} が
 * {@code flex-grow} の読み手に届かず、ua.css の {@code th, td { flex: 1 }} が
 * no-op になって表の列幅が決まらなかった。
 */
public class ShorthandExpansionTest {

    @Test
    public void testFlexSingleNumberExpandsToGrowShrinkBasis() {
        ComputedStyle s = resolve("flex", "1");
        assertEquals("1", s.get(CssProperty.FLEX_GROW));
        assertEquals("1", s.get(CssProperty.FLEX_SHRINK));
        assertEquals("0%", s.get(CssProperty.FLEX_BASIS));
    }

    @Test
    public void testFlexKeywordsExpand() {
        ComputedStyle none = resolve("flex", "none");
        assertEquals("0", none.get(CssProperty.FLEX_GROW));
        assertEquals("0", none.get(CssProperty.FLEX_SHRINK));
        assertEquals("auto", none.get(CssProperty.FLEX_BASIS));

        ComputedStyle auto = resolve("flex", "auto");
        assertEquals("1", auto.get(CssProperty.FLEX_GROW));
        assertEquals("1", auto.get(CssProperty.FLEX_SHRINK));
        assertEquals("auto", auto.get(CssProperty.FLEX_BASIS));
    }

    @Test
    public void testFlexThreeValuesAndLengthBasis() {
        ComputedStyle three = resolve("flex", "2 3 40px");
        assertEquals("2", three.get(CssProperty.FLEX_GROW));
        assertEquals("3", three.get(CssProperty.FLEX_SHRINK));
        assertEquals("40px", three.get(CssProperty.FLEX_BASIS));

        // 単一の長さは 1 1 <length>
        ComputedStyle lengthOnly = resolve("flex", "30px");
        assertEquals("1", lengthOnly.get(CssProperty.FLEX_GROW));
        assertEquals("1", lengthOnly.get(CssProperty.FLEX_SHRINK));
        assertEquals("30px", lengthOnly.get(CssProperty.FLEX_BASIS));
    }

    @Test
    public void testLonghandWinsOverShorthand() {
        ComputedStyle s = resolve("flex", "1", "flex-grow", "5");
        assertEquals("5", s.get(CssProperty.FLEX_GROW));
    }

    @Test
    public void testOverflowExpandsToBothAxes() {
        ComputedStyle one = resolve("overflow", "hidden");
        assertEquals("hidden", one.get(CssProperty.OVERFLOW_X));
        assertEquals("hidden", one.get(CssProperty.OVERFLOW_Y));

        ComputedStyle two = resolve("overflow", "hidden auto");
        assertEquals("hidden", two.get(CssProperty.OVERFLOW_X));
        assertEquals("auto", two.get(CssProperty.OVERFLOW_Y));
    }

    @Test
    public void testInsetExpandsToFourEdges() {
        ComputedStyle s = resolve("inset", "4px 8px");
        assertEquals("4px", s.get(CssProperty.TOP));
        assertEquals("8px", s.get(CssProperty.RIGHT));
        assertEquals("4px", s.get(CssProperty.BOTTOM));
        assertEquals("8px", s.get(CssProperty.LEFT));
    }

    @Test
    public void testNoShorthandLeavesLonghandsAbsent() {
        ComputedStyle s = resolve("display", "flex");
        assertNull(s.get(CssProperty.FLEX_GROW));
        assertNull(s.get(CssProperty.OVERFLOW_X));
        assertNull(s.get(CssProperty.TOP));
    }

    private static ComputedStyle resolve(String... keyValues) {
        Map<String, String> raw = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            raw.put(keyValues[i], keyValues[i + 1]);
        }
        return StyleResolver.finalizeComputedStyle(raw, null, null);
    }
}
