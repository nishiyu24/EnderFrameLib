package com.nishiyu.enderframelib.core.kernel.style;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.kernel.memory.AtomTable;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;

class PackedCssValueTest {

    private static final AtomTable ATOMS = new AtomTable();

    private static long parse(String s, CalcProgram.Table t) {
        return PackedCssValue.parse(s, ATOMS, t);
    }

    @Test
    void lengthsRoundTripEveryUnit() {
        long v = parse("12.5px", null);
        assertEquals(PackedCssValue.K_LENGTH, PackedCssValue.kind(v));
        assertEquals(PackedCssValue.U_PX, PackedCssValue.unit(v));
        assertEquals(12.5f, PackedCssValue.floatPayload(v));
        assertEquals(100f, PackedCssValue.resolveLength(parse("50%", null), 200, 16, 16, 0, 0));
        assertEquals(32f, PackedCssValue.resolveLength(parse("2em", null), 0, 16, 10, 0, 0));
        assertEquals(20f, PackedCssValue.resolveLength(parse("2rem", null), 0, 16, 10, 0, 0));
        assertEquals(10f, PackedCssValue.resolveLength(parse("10vw", null), 0, 0, 0, 100, 0));
        assertEquals(30f, PackedCssValue.resolveLength(parse("10vh", null), 0, 0, 0, 0, 300));
        assertEquals(-4f, PackedCssValue.floatPayload(parse("-4px", null)));
    }

    @Test
    void numbersColorsKeywords() {
        long n = parse("0.5", null);
        assertEquals(PackedCssValue.K_NUMBER, PackedCssValue.kind(n));
        assertEquals(0.5f, PackedCssValue.floatPayload(n));
        long c = parse("#ff8800", null);
        assertEquals(PackedCssValue.K_COLOR, PackedCssValue.kind(c));
        assertEquals(0xFFFF8800, PackedCssValue.colorArgb(c));
        long k = parse("flex-start", null);
        assertEquals(PackedCssValue.K_KEYWORD, PackedCssValue.kind(k));
        assertEquals("flex-start", ATOMS.nameOf(PackedCssValue.payload(k)));
        assertEquals(k, parse("FLEX-START", null), "キーワードは大小無視で同じ atom");
    }

    @Test
    void importantFlagAndFieldsDoNotCollide() {
        long v = parse("8px !important", null);
        assertTrue(PackedCssValue.isImportant(v));
        assertEquals(8f, PackedCssValue.floatPayload(v));
        assertFalse(PackedCssValue.isImportant(parse("8px", null)));
        long p = PackedCssValue.pack(0xAB, 0xCD, 0xBEEF, 0xDEADBEEF);
        assertEquals(0xAB, PackedCssValue.kind(p));
        assertEquals(0xCD, PackedCssValue.unit(p));
        assertEquals(0xBEEF, PackedCssValue.flags(p));
        assertEquals(0xDEADBEEF, PackedCssValue.payload(p));
    }

    @Test
    void garbageIsNone() {
        assertEquals(PackedCssValue.NONE, parse("12qq", null));
        assertEquals(PackedCssValue.NONE, parse("", null));
        assertEquals(PackedCssValue.NONE, PackedCssValue.parse(null, ATOMS, null));
        assertEquals(PackedCssValue.NONE, parse("calc(1px +", new CalcProgram.Table()));
        assertEquals(PackedCssValue.NONE, parse("calc(1px)", null), "表が無ければ数式は詰めない");
    }

    private static float calc(String expr, float pct, float em, float rem, float vw, float vh) {
        CalcProgram p = CalcProgram.compile(expr);
        assertNotNull(p, expr);
        return p.eval(new float[p.stackSize()], pct, em, rem, vw, vh);
    }

    @Test
    void calcEvaluatesLikeTheSpec() {
        assertEquals(84f, calc("calc(50% - 16px)", 200, 16, 16, 0, 0));
        assertEquals(14f, calc("calc(2px + 3px * 4)", 0, 16, 16, 0, 0), "積が先");
        assertEquals(20f, calc("calc((2px + 3px) * 4)", 0, 16, 16, 0, 0));
        assertEquals(7.5f, calc("calc(15px / 2)", 0, 16, 16, 0, 0));
        assertEquals(48f, calc("calc(2em + 1rem)", 0, 16, 16, 0, 0));
        assertEquals(3f, calc("min(10px, 3px, 8px)", 0, 16, 16, 0, 0));
        assertEquals(10f, calc("max(10px, 3px, 8px)", 0, 16, 16, 0, 0));
        assertEquals(5f, calc("clamp(5px, 1px, 9px)", 0, 16, 16, 0, 0));
        assertEquals(9f, calc("clamp(5px, 20px, 9px)", 0, 16, 16, 0, 0));
        assertEquals(7f, calc("clamp(5px, 7px, 9px)", 0, 16, 16, 0, 0));
        assertEquals(60f, calc("calc(100vw - min(40px, 10%))", 400, 16, 16, 100, 0), 0.0001f);
        assertEquals(-3f, calc("calc(-3px)", 0, 16, 16, 0, 0));
        assertEquals(3f, calc("calc(1px+2px)".replace("+", " + "), 0, 16, 16, 0, 0));
    }

    @Test
    void calcRejectsBrokenInput() {
        for (String bad : new String[]{"calc(", "calc(1px 2px)", "calc(1px +)", "calc(1foo)", "foo(1px)", "calc()",
                "calc(1px))", "min(1px,)"}) {
            assertNull(CalcProgram.compile(bad), bad);
        }
    }

    @Test
    void constantFoldingFlag() {
        assertTrue(CalcProgram.compile("calc(2px * 3)").isConstant());
        assertFalse(CalcProgram.compile("calc(50% - 1px)").isConstant());
    }

    @Test
    void calcViaPackedValueUsesTable() {
        CalcProgram.Table t = new CalcProgram.Table();
        long v = parse("calc(100% - 20px)", t);
        assertEquals(PackedCssValue.K_CALC, PackedCssValue.kind(v));
        StyleStructs.CalcResolver r = new StyleStructs.CalcResolver(t);
        assertEquals(180f, StyleStructs.resolve(v, 200, 16, 16, 0, 0, r));
        assertTrue(Float.isNaN(StyleStructs.resolve(v, 200, 16, 16, 0, 0, null)));
    }

    @Test
    void calcEvaluationAllocatesNothing() {
        CalcProgram p = CalcProgram.compile("calc(clamp(8px, 10%, 40px) + 2em * 3 - min(1px, 2px))");
        float[] stack = new float[p.stackSize()];
        float sink = 0;
        for (int i = 0; i < 200_000; i++) sink += p.eval(stack, i, 16, 16, 100, 100);
        var mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().getId();
        long before = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 100_000; i++) sink += p.eval(stack, i, 16, 16, 100, 100);
        long used = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(sink != 0);
        assertTrue(used < 4096, "calc の評価がヒープを確保している: " + used + " B");
    }
}
