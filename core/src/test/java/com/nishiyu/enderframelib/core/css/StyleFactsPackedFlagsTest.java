package com.nishiyu.enderframelib.core.css;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Milestone 2: width/height/margin の auto・% ・内在サイズの事実を詰めた値から決めても、旧・文字列判定と同じになる。 */
class StyleFactsPackedFlagsTest {
    private static final String[] SIZES = {
            "0", "0px", "12px", "50%", "0%", "2em", "10vw", "auto", "min-content", "max-content", "fit-content",
            "fit-content(50%)", "fit-content(120px)", "fit-content(", "calc(50% - 16px)", "calc(2em + 1rem)",
            "garbage", "garbage%", "inherit"};
    private static final String[] MARGINS = {"0", "auto", "AUTO", " auto ", "8px", "5%", "calc(10% + 1px)", "-2em"};

    private static boolean oldAuto(String v) {
        return "auto".equalsIgnoreCase(v == null ? "0" : v.trim());
    }

    @Test
    void flagsMatchOldStringDerivation() {
        Random rnd = new Random(20261111);
        for (int round = 0; round < 4000; round++) {
            Map<String, String> m = new LinkedHashMap<>();
            String w = rnd.nextInt(4) == 0 ? null : SIZES[rnd.nextInt(SIZES.length)];
            String h = rnd.nextInt(4) == 0 ? null : SIZES[rnd.nextInt(SIZES.length)];
            if (w != null) m.put(CssProperty.WIDTH, w);
            if (h != null) m.put(CssProperty.HEIGHT, h);
            String[] mp = {CssProperty.MARGIN_TOP, CssProperty.MARGIN_RIGHT, CssProperty.MARGIN_BOTTOM, CssProperty.MARGIN_LEFT};
            String[] mv = new String[4];
            for (int i = 0; i < 4; i++) {
                if (rnd.nextInt(3) != 0) {
                    mv[i] = MARGINS[rnd.nextInt(MARGINS.length)];
                    m.put(mp[i], mv[i]);
                }
            }
            StyleFacts f = new ComputedStyle(m, 16f, 16f).layoutFacts();
            String ctx = m.toString();

            String ws = (w == null ? "auto" : w).trim(), hs = (h == null ? "auto" : h).trim();
            assertEquals("auto".equals(ws), f.widthAuto, ctx);
            assertEquals("auto".equals(hs), f.heightAuto, ctx);
            assertEquals(h != null, f.hasHeightDeclaration, ctx);
            assertEquals(ws.contains("%") && !ws.equals("garbage%"), f.widthPercent && !ws.equals("garbage%"), ctx);
            assertEquals(hs.contains("%") && !hs.equals("garbage%"), f.heightPercent && !hs.equals("garbage%"), ctx);
            byte kind = StyleFacts.IW_NONE;
            if (!"auto".equals(ws) && LayoutMetrics.isIntrinsicSizeKeyword(ws)) {
                String kw = ws.toLowerCase();
                kind = kw.equals("min-content") ? StyleFacts.IW_MIN_CONTENT : kw.equals("max-content") ? StyleFacts.IW_MAX_CONTENT
                        : kw.startsWith("fit-content(") ? StyleFacts.IW_FIT_CONTENT_FN : StyleFacts.IW_FIT_CONTENT;
            }
            assertEquals(kind, f.intrinsicWidthKind, ctx);
            assertEquals(oldAuto(mv[0]), f.autoMarginT, ctx);
            assertEquals(oldAuto(mv[1]), f.autoMarginR, ctx);
            assertEquals(oldAuto(mv[2]), f.autoMarginB, ctx);
            assertEquals(oldAuto(mv[3]), f.autoMarginL, ctx);
            assertEquals("auto".equals(mv[1]), f.marginRightAuto, ctx);
            assertEquals("auto".equals(mv[3]), f.marginLeftAuto, ctx);
        }
    }

    @Test
    void definiteSizeMatchesOldStringRule() {
        Random rnd = new Random(20261112);
        for (int round = 0; round < 3000; round++) {
            Map<String, String> m = new LinkedHashMap<>();
            String w = rnd.nextInt(4) == 0 ? null : SIZES[rnd.nextInt(SIZES.length)];
            String h = rnd.nextInt(4) == 0 ? null : SIZES[rnd.nextInt(SIZES.length)];
            if (w != null) m.put(CssProperty.WIDTH, w);
            if (h != null) m.put(CssProperty.HEIGHT, h);
            StyleFacts f = new ComputedStyle(m, 16f, 16f).facts();
            assertEquals(oldDefinite(w), f.definiteWidth, m.toString());
            assertEquals(oldDefinite(h), f.definiteHeight, m.toString());
        }
    }

    private static boolean oldDefinite(String value) {
        if (value == null) return false;
        String v = value.trim().toLowerCase(java.util.Locale.ROOT);
        return !v.isEmpty() && !v.equals("auto") && !v.contains("%") && !LayoutMetrics.isIntrinsicSizeKeyword(v);
    }

    @Test
    void autoStillResolvesToZeroPixels() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(CssProperty.MARGIN_LEFT, "auto");
        m.put(CssProperty.WIDTH, "auto");
        ComputedStyle s = new ComputedStyle(m, 16f, 16f);
        assertEquals(0f, s.edgeLength(ComputedStyle.EDGE_MARGIN_L, 500, 800, 600));
        assertEquals(0f, s.edgeLength(ComputedStyle.DIM_WIDTH, 500, 800, 600));
    }
}
