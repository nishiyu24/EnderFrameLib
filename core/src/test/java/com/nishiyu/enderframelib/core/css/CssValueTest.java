package com.nishiyu.enderframelib.core.css;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.css.model.Overflow;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Milestone 2: 算出スタイルが持つ値は、文字列ごとに 1 つの正規の値オブジェクト（型付きの結果を値に覚える）。読み出す値は文字列のときと変わらない。 */
class CssValueTest {

    @Test
    void sameTextIsTheSameObjectWhileAlive() {
        CssValue a = CssValue.of(new String("10px"));
        CssValue b = CssValue.of(new String("10px"));
        assertSame(a, b);
        assertNotSame(a, CssValue.of("11px"));
        assertNull(CssValue.of(null));
        assertEquals("10px", a.text);
        assertEquals("", CssValue.of("").text);
    }

    @Test
    void stylesShareValueObjectsAndCompareByIdentity() {
        Map<String, String> m = new HashMap<>();
        m.put("width", new String("12px"));
        m.put("height", new String("12px"));
        ComputedStyle a = ComputedStyle.of(m, 16f, 16f);
        ComputedStyle b = ComputedStyle.of(new HashMap<>(m), 16f, 16f);
        assertTrue(ComputedStyle.sameDeclarations(a, b));
        assertEquals("12px", a.get(CssProperty.Id.WIDTH));
        assertEquals(a.asMap(), b.asMap());
        Map<String, String> m2 = new HashMap<>(m);
        m2.put("height", "13px");
        assertFalse(ComputedStyle.sameDeclarations(a, ComputedStyle.of(m2, 16f, 16f)));
    }

    @Test
    void typedResultsEqualTheStringParsers() {
        Random rnd = new Random(20261119);
        String[] lengths = {"10px", "2em", "50%", "0", "auto", "calc(1px + 2em)", "10vw", "-3px", "garbage", "", " 5px "};
        String[] colors = {"#336699", "red", "rgb(1, 2, 3)", "transparent", "nonsense", "#fff", "rgba(0,0,0,.5)"};
        String[] numbers = {"0.5", "1", "-2", " 7 ", "auto", "NaN", "1e3", ""};
        for (int i = 0; i < 500; i++) {
            String l = lengths[rnd.nextInt(lengths.length)], c = colors[rnd.nextInt(colors.length)], n = numbers[rnd.nextInt(numbers.length)];
            ComputedStyle s = ComputedStyle.of(Map.of("width", l, "color", c, "opacity", n), 16f, 16f);
            assertEquals(LengthResolver.compile(l).resolve(100f, 800f, 600f, 16f, 16f), s.length(CssProperty.Id.WIDTH, 100f, 800f, 600f), 0f, l);
            assertEquals(ColorParser.parseColor(c), s.color(CssProperty.Id.COLOR, 0), c);
            float expected;
            try {
                expected = Float.parseFloat(n.trim());
            } catch (NumberFormatException e) {
                expected = 42f;
            }
            assertEquals(expected, s.number(CssProperty.Id.OPACITY, 42f), 0f, n);
        }
    }

    @Test
    void keywordMemoGivesTheRightAnswerForEachRequestedType() {
        ComputedStyle s = ComputedStyle.of(Map.of("overflow-x", "hidden", "display", "none", "float", "none"), 16f, 16f);
        for (int i = 0; i < 3; i++) {
            assertEquals(Overflow.HIDDEN, s.keywordOf(CssProperty.Id.OVERFLOW_X, Overflow.class));
            assertEquals(CssKeywords.FloatSide.NONE, s.keywordOf(CssProperty.Id.FLOAT, CssKeywords.FloatSide.class));
            // 同じ文字列（none）を別の列挙型で続けて読んでも、型ごとの正しい結果
            assertEquals(Overflow.VISIBLE, s.keywordOf(CssProperty.Id.OVERFLOW_Y, Overflow.class), "宣言なしは初期値");
        }
        CssValue none = CssValue.of("none");
        assertEquals(CssKeywords.FloatSide.NONE, none.keyword(CssKeywords.FloatSide.class));
        assertNull(none.keyword(Overflow.class));
        assertEquals(CssKeywords.FloatSide.NONE, none.keyword(CssKeywords.FloatSide.class));
    }

    @Test
    void flagsAreCachedAndMatchTheirDefinitions() {
        assertTrue(CssValue.of("10vw").usesRelativeUnit());
        assertFalse(CssValue.of("10px").usesRelativeUnit());
        assertFalse(CssValue.of("block").mayContainDimension());
        assertTrue(CssValue.of("calc(1em + 2px)").mayContainDimension());
        assertTrue(CssValue.of("1px solid CurrentColor").mentionsCurrentColor());
        assertFalse(CssValue.of("1px solid red").mentionsCurrentColor());
        assertTrue(CssValue.of("var(--x)").containsVar());
        assertEquals("inherit", String.valueOf(CssValue.of(" Inherit ").wide()).toLowerCase());
        assertNull(CssValue.of("red").wide());
        assertEquals("  AbC ".trim().toLowerCase(), CssValue.of("  AbC ").lowered());
    }

    @Test
    void unusedValuesAreReclaimedSoTheTableDoesNotGrowForever() throws Exception {
        int before = CssValue.tableSizeForTest();
        for (int i = 0; i < 20000; i++) CssValue.of("unique-value-" + i);
        // 参照を保持していないので、回収されて表が片付く（GC の都合なので、何度か促して十分減ったことだけ確かめる）
        int after = Integer.MAX_VALUE;
        for (int attempt = 0; attempt < 20 && after > before + 10000; attempt++) {
            System.gc();
            Thread.sleep(50);
            CssValue.of("poke-" + attempt); // 回収された項目の片付けは作成のたびに進む
            after = CssValue.tableSizeForTest();
        }
        assertTrue(after < before + 20000, "表が際限なく増えない: before=" + before + " after=" + after);
    }
}
