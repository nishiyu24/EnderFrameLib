package com.nishiyu.enderframelib.core.css;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Milestone 2: 寸法・縁の「% を含むか」を文字列でなく詰めた値で判定しても、旧 {@code indexOf('%')} と同じ結果になる。 */
class StyleFactsPercentTest {
    private static final String[] VALUES = {
            "0", "12px", "50%", "-10%", "2em", "1.5rem", "10vw", "7vh", "auto", "none", "min-content", "fit-content",
            "calc(50% - 16px)", "calc(2em + 1rem)", "min(10px, 3%)", "max(1vw, 5px)", "clamp(5px, 10%, 40px)",
            "calc(100vw - min(40px, 10%))"};
    private static final String[] PROPS = {
            CssProperty.WIDTH, CssProperty.HEIGHT, CssProperty.MIN_WIDTH, CssProperty.MAX_WIDTH, CssProperty.MIN_HEIGHT,
            CssProperty.MAX_HEIGHT, CssProperty.LEFT, CssProperty.RIGHT, CssProperty.TOP, CssProperty.BOTTOM,
            CssProperty.FLEX_BASIS, CssProperty.MARGIN_TOP, CssProperty.MARGIN_RIGHT, CssProperty.MARGIN_BOTTOM,
            CssProperty.MARGIN_LEFT, CssProperty.PADDING_TOP, CssProperty.PADDING_RIGHT, CssProperty.PADDING_BOTTOM,
            CssProperty.PADDING_LEFT, CssProperty.ROW_GAP, CssProperty.COLUMN_GAP};
    private static final int[] IDX = {
            ComputedStyle.DIM_WIDTH, ComputedStyle.DIM_HEIGHT, ComputedStyle.DIM_MIN_WIDTH, ComputedStyle.DIM_MAX_WIDTH,
            ComputedStyle.DIM_MIN_HEIGHT, ComputedStyle.DIM_MAX_HEIGHT, ComputedStyle.DIM_LEFT, ComputedStyle.DIM_RIGHT,
            ComputedStyle.DIM_TOP, ComputedStyle.DIM_BOTTOM, ComputedStyle.DIM_FLEX_BASIS,
            ComputedStyle.EDGE_MARGIN_T, ComputedStyle.EDGE_MARGIN_R, ComputedStyle.EDGE_MARGIN_B, ComputedStyle.EDGE_MARGIN_L,
            ComputedStyle.EDGE_PADDING_T, ComputedStyle.EDGE_PADDING_R, ComputedStyle.EDGE_PADDING_B, ComputedStyle.EDGE_PADDING_L,
            ComputedStyle.EDGE_ROW_GAP, ComputedStyle.EDGE_COLUMN_GAP};

    @Test
    void percentDetectionMatchesStringScan() {
        assertEquals(PROPS.length, IDX.length);
        Random rnd = new Random(20261110);
        for (int round = 0; round < 3000; round++) {
            Map<String, String> m = new LinkedHashMap<>();
            for (String p : PROPS) if (rnd.nextInt(3) != 0) m.put(p, VALUES[rnd.nextInt(VALUES.length)]);
            ComputedStyle s = new ComputedStyle(m, 16f, 16f);
            for (int i = 0; i < PROPS.length; i++) {
                String v = m.get(PROPS[i]);
                assertEquals(v != null && v.indexOf('%') >= 0, s.edgeHasPercent(IDX[i]), PROPS[i] + ": " + v);
            }
        }
    }

    @Test
    void horizontalFixedFollowsPercentInAnyInsetOrSpacing() {
        for (String p : new String[]{CssProperty.MIN_WIDTH, CssProperty.MAX_WIDTH, CssProperty.LEFT, CssProperty.RIGHT,
                CssProperty.TOP, CssProperty.BOTTOM, CssProperty.MARGIN_TOP, CssProperty.PADDING_LEFT}) {
            Map<String, String> fixed = new LinkedHashMap<>();
            fixed.put(CssProperty.WIDTH, "100px");
            fixed.put(p, "4px");
            assertTrue(new ComputedStyle(fixed, 16f, 16f).layoutFacts().horizontalFixed, p + " px");
            Map<String, String> pct = new LinkedHashMap<>(fixed);
            pct.put(p, "calc(50% - 2px)");
            assertFalse(new ComputedStyle(pct, 16f, 16f).layoutFacts().horizontalFixed, p + " calc%");
        }
    }

    @Test
    void styleFactsKeepsNoStringPercentScan() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of(
                "core/src/main/java/com/nishiyu/enderframelib/core/css/StyleFacts.java"));
        assertFalse(src.contains("noPercent"), "StyleFacts が % の文字列検索を復活させている");
    }
}
