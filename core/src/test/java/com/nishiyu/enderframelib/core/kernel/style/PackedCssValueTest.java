package com.nishiyu.enderframelib.core.kernel.style;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PackedCssValueTest {

    private static float r(long v, float pct, float em, float rem, float vw, float vh) {
        return PackedCssValue.resolveLength(v, pct, em, rem, vw, vh);
    }

    @Test
    void lengthsRoundTripEveryUnit() {
        long v = PackedCssValue.length(12.5f, PackedCssValue.U_PX);
        assertEquals(PackedCssValue.K_LENGTH, PackedCssValue.kind(v));
        assertEquals(PackedCssValue.U_PX, PackedCssValue.unit(v));
        assertEquals(12.5f, PackedCssValue.floatPayload(v));
        assertEquals(100f, r(PackedCssValue.length(50, PackedCssValue.U_PERCENT), 200, 16, 16, 0, 0));
        assertEquals(32f, r(PackedCssValue.length(2, PackedCssValue.U_EM), 0, 16, 10, 0, 0));
        assertEquals(20f, r(PackedCssValue.length(2, PackedCssValue.U_REM), 0, 16, 10, 0, 0));
        assertEquals(10f, r(PackedCssValue.length(10, PackedCssValue.U_VW), 0, 0, 0, 100, 0));
        assertEquals(30f, r(PackedCssValue.length(10, PackedCssValue.U_VH), 0, 0, 0, 0, 300));
        assertEquals(20f, r(PackedCssValue.length(10, PackedCssValue.U_VMIN), 0, 0, 0, 200, 800));
        assertEquals(80f, r(PackedCssValue.length(10, PackedCssValue.U_VMAX), 0, 0, 0, 200, 800));
        assertEquals(-4f, PackedCssValue.floatPayload(PackedCssValue.length(-4, PackedCssValue.U_PX)));
    }

    @Test
    void numbersColorsKeywordsAndCalcIndex() {
        long n = PackedCssValue.number(0.5f);
        assertEquals(PackedCssValue.K_NUMBER, PackedCssValue.kind(n));
        assertEquals(0.5f, PackedCssValue.floatPayload(n));
        long c = PackedCssValue.color(0xFFFF8800);
        assertEquals(PackedCssValue.K_COLOR, PackedCssValue.kind(c));
        assertEquals(0xFFFF8800, PackedCssValue.colorArgb(c));
        long k = PackedCssValue.keyword(77);
        assertEquals(PackedCssValue.K_KEYWORD, PackedCssValue.kind(k));
        assertEquals(77, PackedCssValue.payload(k));
        long calc = PackedCssValue.calc(5);
        assertEquals(PackedCssValue.K_CALC, PackedCssValue.kind(calc));
        assertEquals(5, PackedCssValue.payload(calc));
    }

    @Test
    void importantFlagAndFieldsDoNotCollide() {
        long v = PackedCssValue.withImportant(PackedCssValue.length(8, PackedCssValue.U_PX));
        assertTrue(PackedCssValue.isImportant(v));
        assertEquals(8f, PackedCssValue.floatPayload(v));
        assertFalse(PackedCssValue.isImportant(PackedCssValue.length(8, PackedCssValue.U_PX)));
        long p = PackedCssValue.pack(0xAB, 0xCD, 0xBEEF, 0xDEADBEEF);
        assertEquals(0xAB, PackedCssValue.kind(p));
        assertEquals(0xCD, PackedCssValue.unit(p));
        assertEquals(0xBEEF, PackedCssValue.flags(p));
        assertEquals(0xDEADBEEF, PackedCssValue.payload(p));
    }

    @Test
    void zeroPixelsIsNotNone() {
        assertNotEquals(PackedCssValue.NONE, PackedCssValue.length(0f, PackedCssValue.U_PX));
        assertEquals(PackedCssValue.K_NONE, PackedCssValue.kind(PackedCssValue.NONE));
    }
}
