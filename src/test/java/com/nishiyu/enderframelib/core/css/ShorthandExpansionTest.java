package com.nishiyu.enderframelib.core.css;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // ---- W10

    @Test
    public void testFlexKeywordsAndInvalidValues() {
        // 全域キーワードの経路を通らずに展開しても initial は 0 1 auto（以前は 1 1 initial）
        assertEquals(Map.of("flex-grow", "0", "flex-shrink", "1", "flex-basis", "auto"),
                ShorthandExpanders.flex("initial"));
        ComputedStyle viaCascade = resolve("flex", "initial");
        assertEquals("0", viaCascade.get(CssProperty.FLEX_GROW));
        assertEquals("auto", viaCascade.get(CssProperty.FLEX_BASIS));

        ComputedStyle zeros = resolve("flex", "0 0 0");
        assertEquals("0", zeros.get(CssProperty.FLEX_SHRINK));
        assertEquals("0", zeros.get(CssProperty.FLEX_BASIS));
        ComputedStyle basisFirst = resolve("flex", "10px 2");
        assertEquals("2", basisFirst.get(CssProperty.FLEX_GROW));
        assertEquals("10px", basisFirst.get(CssProperty.FLEX_BASIS));

        // 以前は知らない語を basis に入れていた
        assertTrue(ShorthandExpanders.flex("foo").isEmpty());
        assertTrue(ShorthandExpanders.flex("1 2 3 4").isEmpty());
        assertTrue(ShorthandExpanders.flex("1 10px 2").isEmpty(), "grow と shrink の間に basis は書けない");
    }

    @Test
    public void testOmittedSubpropertiesResetToInitialValues() {
        ComputedStyle flow = resolve("flex-flow", "wrap");
        assertEquals("row", flow.get(CssProperty.FLEX_DIRECTION));
        assertEquals("wrap", flow.get(CssProperty.FLEX_WRAP));

        ComputedStyle outline = resolve("outline", "none");
        assertEquals("none", outline.get("outline-style"));
        assertEquals("0", outline.get("outline-width"));
        assertTrue(outline.containsKey("outline-color"));
    }

    @Test
    public void testOmittedInheritedSubpropertiesDoNotKeepTheParentValue() {
        // 継承するロングハンドは親の値が種まきされている。ショートハンドが書かれなかったものは初期値に戻る
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("list-style-type", "square");
        raw.put("list-style", "inside");
        ComputedStyle s = StyleResolver.finalizeComputedStyle(raw, null, null);
        assertEquals("inside", s.get("list-style-position"));
    }

    @Test
    public void testBorderRadiusExpandsToCorners() {
        ComputedStyle three = resolve("border-radius", "1px 2px 3px");
        assertEquals("1px", three.get(CssProperty.BORDER_TOP_LEFT_RADIUS));
        assertEquals("2px", three.get(CssProperty.BORDER_TOP_RIGHT_RADIUS));
        assertEquals("3px", three.get(CssProperty.BORDER_BOTTOM_RIGHT_RADIUS));
        assertEquals("2px", three.get(CssProperty.BORDER_BOTTOM_LEFT_RADIUS));

        ComputedStyle elliptic = resolve("border-radius", "10px 5px / 20px");
        assertEquals("10px 20px", elliptic.get(CssProperty.BORDER_TOP_LEFT_RADIUS));
        assertEquals("5px 20px", elliptic.get(CssProperty.BORDER_TOP_RIGHT_RADIUS));

        // 角のロングハンドは後に書けばショートハンドに勝つ
        ComputedStyle override = resolve("border-radius", "8px", "border-top-left-radius", "0");
        assertEquals("0", override.get(CssProperty.BORDER_TOP_LEFT_RADIUS));
        assertEquals("8px", override.get(CssProperty.BORDER_BOTTOM_RIGHT_RADIUS));

        assertTrue(ShorthandExpanders.borderRadius("-1px").isEmpty());
        assertTrue(ShorthandExpanders.borderRadius("1px 2px 3px 4px 5px").isEmpty());
        assertTrue(ShorthandExpanders.borderRadius("1px / 2px / 3px").isEmpty());
    }

    @Test
    public void testCornerRadiiResolveFontRelativeAndPercentages() {
        ComputedStyle em = StyleResolver.finalizeComputedStyle(
                new LinkedHashMap<>(Map.of("font-size", "10px", "border-radius", "1em 0.5rem")), null, null);
        float[] r = LengthResolver.cornerRadii(em, 100, 40, 320, 200);
        assertEquals(10f, r[0], 0.001f);
        assertEquals(8f, r[1], 0.001f);

        // % は横が幅・縦が高さ基準。円弧で描くので小さい方（長方形の 50% は高さの半分）
        ComputedStyle pct = resolve("border-radius", "50%");
        float[] p = LengthResolver.cornerRadii(pct, 100, 40, 320, 200);
        assertEquals(20f, p[0], 0.001f);

        ComputedStyle longhandOnly = resolve("border-bottom-left-radius", "6px");
        float[] l = LengthResolver.cornerRadii(longhandOnly, 100, 40, 320, 200);
        assertEquals(0f, l[0], 0.001f);
        assertEquals(6f, l[3], 0.001f);
    }

    private static ComputedStyle resolve(String... keyValues) {
        Map<String, String> raw = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            raw.put(keyValues[i], keyValues[i + 1]);
        }
        return StyleResolver.finalizeComputedStyle(raw, null, null);
    }
}
