package com.nishiyu.enderframelib.core.css;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Milestone 2: {@code usesViewportUnits} を、算出の途中（寸法トークンを持つ値だけ走査）で決めて算出スタイルへ渡しても、
 * 値を全部走査し直した結果と同じになる。
 */
class RelativeUnitsFlagTest {
    private static final String[] VALUES = {"10px", "2em", "1.5rem", "50%", "10vw", "3VH", "5vmin", "2svw", "4dvh", "6lvi", "7cqw", "8cqmin",
            "calc(10vw - 4px)", "calc(1em + 2px)", "1px solid red", "0 10vw", "12pt", "auto", "none", "red", "#fff", "block", "100%",
            "min(10vh, 20px)", "var(--x)", "rotate(10deg)", "1s", "garbage", "", "5cqh 3px", "10vwx", "1e3px", "-2vh"};
    private static final String[] PROPS = {"width", "height", "margin-top", "padding-left", "font-size", "line-height", "top", "left",
            "border-top-width", "border-top-style", "gap", "color", "display", "transform", "font-family", "content",
            "--a", "--b", "--c", "made-up-prop", "perspective"};

    @Test
    void flagFromComputeEqualsAFullScan() {
        Random rnd = new Random(20261118);
        int trues = 0, falses = 0;
        for (int round = 0; round < 5000; round++) {
            Map<String, String> src = new LinkedHashMap<>();
            int n = rnd.nextInt(8);
            for (int i = 0; i < n; i++) src.put(PROPS[rnd.nextInt(PROPS.length)], VALUES[rnd.nextInt(VALUES.length)]);
            StyleTable table = StyleTable.copyOf(src);
            PropertyRegistry.expandShorthands(table);
            ValueComputer.compute(table, new ValueComputer.Context(Map.of(), 16f, 16f, 800, 600, rnd.nextBoolean() ? 400f : Float.NaN, 300f, 20f));
            assertTrue(table.relativeUnits >= 0, "算出が決めている");

            StyleTable forScan = StyleTable.copyOf(table); // 複製は未確定に戻る
            assertEquals(-1, forScan.relativeUnits);
            boolean expected = new ComputedStyle(forScan, 16f, 16f).usesViewportUnits();
            boolean actual = ComputedStyle.ofOwned(table, 16f, 16f).usesViewportUnits();
            assertEquals(expected, actual, "round " + round + " src=" + src);
            if (expected) trues++;
            else falses++;
        }
        assertTrue(trues > 300 && falses > 300, "両方の場合が十分にある: " + trues + "/" + falses);
    }

    @Test
    void writesInvalidateTheFlag() {
        StyleTable t = new StyleTable();
        t.put("width", "10vw");
        ValueComputer.compute(t, new ValueComputer.Context(Map.of(), 16f, 16f, 800, 600));
        assertEquals(1, t.relativeUnits);
        t.put("height", "5px");
        assertEquals(-1, t.relativeUnits, "書き込みで未確定へ戻る");
        assertFalse(new ComputedStyle(StyleTable.copyOf(Map.of("height", "5px")), 16f, 16f).usesViewportUnits());
    }

    @Test
    void manyDimensionValuesTakeTheFullScanPath() {
        String[] props = {"width", "height", "min-width", "max-width", "min-height", "max-height", "margin-top", "margin-right",
                "margin-bottom", "margin-left", "padding-top", "padding-right", "padding-bottom", "padding-left", "top", "left",
                "right", "bottom", "row-gap", "column-gap", "flex-basis", "text-indent", "letter-spacing"};
        for (boolean withViewport : new boolean[]{false, true}) {
            StyleTable t = new StyleTable();
            for (String prop : props) t.put(prop, "10px");
            if (withViewport) t.put("letter-spacing", "2vw");
            ValueComputer.compute(t, new ValueComputer.Context(Map.of(), 16f, 16f, 800, 600));
            assertEquals(withViewport ? 1 : 0, t.relativeUnits);
            assertEquals(withViewport, new ComputedStyle(StyleTable.copyOf(t), 16f, 16f).usesViewportUnits());
        }
    }
}
