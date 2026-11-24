package com.nishiyu.enderframelib.core.css;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** M1: {@link CssLength} の px 解決が旧 {@code CompiledLength#resolve} と一致し、キーワード・式が正しく分類されること。 */
class CssLengthTest {
    private static final float PCT = 240f, FONT = 18f, ROOT = 16f, VW = 800f, VH = 600f;

    private static final List<String> SAMPLES = List.of("0", "12px", "-3.5px", "50%", "2em", "1.5rem", "10vw", "20vh", "5vmin",
            "5vmax", "3ch", "1ex", "1in", "2cm", "12pt", "calc(100% - 20px)", "calc(2em + 10px)", "min(50%, 100px)",
            "clamp(10px, 5vw, 200px)", "auto", "none", "normal", "min-content", "max-content", "fit-content", "medium", "thin",
            "thick", "garbage", "", "  7px  ", "AUTO");

    @Test
    void resolvesLikeTheOldCompiledLength() {
        for (String s : SAMPLES) {
            CssExprs.Builder b = new CssExprs.Builder();
            long v = CssLength.parse(s, b);
            CssExprs ex = b.build();
            float expected = LengthResolver.compile(s).resolve(PCT, VW, VH, FONT, ROOT);
            assertEquals(expected, CssLength.resolve(v, ex, PCT, FONT, ROOT, VW, VH), 0f, "'" + s + "'");
        }
    }

    @Test
    void classifiesKeywordsLengthsAndExpressions() {
        CssExprs.Builder b = new CssExprs.Builder();
        assertTrue(CssLength.isAuto(CssLength.parse("auto", b)));
        assertTrue(CssLength.isAuto(CssLength.parse(" AUTO ", b)));
        assertTrue(CssLength.isKeyword(CssLength.parse("none", b), CssLength.KW_NONE));
        assertTrue(CssLength.isKeyword(CssLength.parse("min-content", b), CssLength.KW_MIN_CONTENT));
        assertTrue(CssLength.isKeyword(CssLength.parse("garbage", b), CssLength.KW_OTHER));
        assertTrue(CssLength.isPercent(CssLength.parse("50%", b)));
        long px = CssLength.parse("12px", b);
        assertTrue(CssLength.isPixels(px));
        assertEquals(12f, CssLength.pixels(px));
        assertEquals(px, CssLength.px(12f));
        assertFalse(CssLength.isAuto(px));
        assertTrue(CssLength.isCalc(CssLength.parse("calc(1px + 2px)", b)));
        assertEquals(1, b.build().size());
    }

    @Test
    void exprTablesCompareByValueAndEmptyIsShared() {
        CssExprs.Builder none = new CssExprs.Builder();
        CssLength.parse("10px", none);
        assertSame(CssExprs.EMPTY, none.build());

        CssExprs.Builder a = new CssExprs.Builder(), c = new CssExprs.Builder(), d = new CssExprs.Builder();
        CssLength.parse("calc(1px + 2px)", a);
        CssLength.parse("calc(1px + 2px)", c);
        CssLength.parse("calc(1px + 3px)", d);
        assertEquals(a.build(), c.build());
        assertEquals(a.build().hashCode(), c.build().hashCode());
        assertNotEquals(a.build(), d.build());
        assertNotEquals(a.build(), CssExprs.EMPTY);
    }

    @Test
    void calcProgramStaysRemovedAndKernelStyleStaysPure() throws Exception {
        java.nio.file.Path main = java.nio.file.Path.of("core/src/main/java/com/nishiyu/enderframelib/core");
        assertFalse(java.nio.file.Files.exists(main.resolve("kernel/style/CalcProgram.java")), "数式の評価器は LengthResolver だけ");
        assertFalse(java.nio.file.Files.readString(main.resolve("css/StyleFacts.java")).contains("CompiledLength[]"));
        assertFalse(java.nio.file.Files.readString(main.resolve("css/ComputedStyle.java")).contains("CompiledLength[]"));
        try (var files = java.nio.file.Files.walk(main)) {
            for (java.nio.file.Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                assertFalse(java.nio.file.Files.readString(p).contains("CalcProgram"), p + " が CalcProgram を参照している");
            }
        }
        assertFalse(java.nio.file.Files.readString(main.resolve("kernel/style/PackedCssValue.java")).contains("core.css"),
                "kernel.style は css を知らない（パッケージ循環を作らない）");
    }
}
