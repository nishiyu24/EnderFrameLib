package com.nishiyu.enderframelib.core.css;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.kernel.style.PackedCssValue;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** 縁の長さを {@link PackedCssValue} で持っても、旧 {@code CompiledLength.resolve} とビット単位で同じ結果になる。 */
class PackedLengthsTest {
    private static final String[] SAMPLES = {
            "0", "12px", "-4.5px", "50%", "-10%", "2em", "1.5rem", "10vw", "7vh", "3vmin", "9vmax", "2svw", "4dvh",
            "1ch", "2ex", "1cap", "1lh", "1rlh", "1in", "10pt", "2cm", "auto", "garbage", "12qq",
            "calc(50% - 16px)", "calc(2em + 1rem)", "min(10px, 3%)", "max(1vw, 5px)", "clamp(5px, 10%, 40px)",
            "calc(100vw - min(40px, 10%))", "round(up, 13px, 5px)", "calc(1px / 0)", "calc(10px * 2px)",
            "calc(100% / 3)", "calc(-50% + 1vmin)"};

    @Test
    void packedResolveEqualsCompiledResolveBitForBit() {
        Random rnd = new Random(20261109);
        for (String s : SAMPLES) {
            LengthResolver.CompiledLength c = LengthResolver.compile(s);
            long v = PackedLengths.pack(c);
            assertNotEquals(PackedCssValue.NONE, v, s);
            for (int i = 0; i < 200; i++) {
                float pct = rnd.nextInt(2000) - 200 + rnd.nextFloat(), rw = 1 + rnd.nextInt(3000), rh = 1 + rnd.nextInt(2000);
                float fs = 1 + rnd.nextInt(60), rfs = 1 + rnd.nextInt(40);
                assertEquals(Float.floatToIntBits(c.resolve(pct, rw, rh, fs, rfs)),
                        Float.floatToIntBits(PackedLengths.resolve(v, pct, rw, rh, fs, rfs)), s);
            }
        }
    }

    @Test
    void undeclaredIsNoneAndResolvesToZero() {
        assertEquals(PackedCssValue.NONE, PackedLengths.pack(null));
        assertEquals(0f, PackedLengths.resolve(PackedCssValue.NONE, 100, 100, 100, 16, 16));
    }

    @Test
    void sameExpressionSharesOneTableSlot() {
        LengthResolver.CompiledLength c = LengthResolver.compile("calc(10% + 3px)");
        long a = PackedLengths.pack(c);
        long b = PackedLengths.pack(c);
        assertEquals(a, b);
        assertNotNull(c);
        assertEquals(PackedCssValue.K_CALC, PackedCssValue.kind(a));
    }

    @Test
    void expressionTableGrowsPastItsInitialCapacity() {
        java.util.List<LengthResolver.CompiledLength> keep = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String s = "calc(" + i + "px + 1%)";
            LengthResolver.CompiledLength c = LengthResolver.compile(s);
            keep.add(c);
            long v = PackedLengths.pack(c);
            assertEquals(i + 10f, PackedLengths.resolve(v, 1000, 0, 0, 16, 16), 0f, s);
        }
        assertEquals(100, keep.size());
    }

    @Test
    void edgeLengthMatchesTheGenericLengthPathForExpressionsAndViewportUnits() {
        Map<String, String> p = new HashMap<>();
        p.put("margin-top", "5%");
        p.put("margin-right", "2em");
        p.put("margin-bottom", "3vmin");
        p.put("margin-left", "calc(1% + 4px)");
        p.put("padding-top", "7px");
        p.put("padding-left", "clamp(1px, 2vmax, 9px)");
        p.put("row-gap", "10px");
        p.put("column-gap", "2%");
        ComputedStyle st = new ComputedStyle(p, 16f, 16f);
        String[] names = {"margin-top", "margin-right", "margin-bottom", "margin-left", "padding-top", "padding-right",
                "padding-bottom", "padding-left", "border-top-width", "border-right-width", "border-bottom-width",
                "border-left-width", "row-gap", "column-gap"};
        float[][] envs = {{400, 1920, 1080}, {123, 800, 600}};
        for (float[] e : envs) {
            for (int i = 0; i < names.length; i++) {
                assertEquals(Float.floatToIntBits(st.length(names[i], e[0], e[1], e[2])),
                        Float.floatToIntBits(st.edgeLength(i, e[0], e[1], e[2])), names[i]);
            }
        }
    }

    @Test
    void resolveAllocatesNothing() {
        long v = PackedLengths.pack(LengthResolver.compile("2em"));
        float sink = 0;
        for (int i = 0; i < 100_000; i++) sink += PackedLengths.resolve(v, i, 100, 100, 16, 16);
        var mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().getId();
        long before = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 100_000; i++) sink += PackedLengths.resolve(v, i, 100, 100, 16, 16);
        long used = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(sink != 0);
        assertTrue(used < 4096, "確保 " + used + " B");
    }

    /** 長時間アニメーション: 毎フレーム別の {@code calc()} が生まれても、式表は生きている式の数でしか増えない。 */
    @Test
    void animatedCalcDoesNotGrowTheExpressionTableWithoutBound() {
        int before = PackedLengths.exprCapacityUsedForTest();
        float sink = 0;
        for (int frame = 0; frame < 100_000; frame++) {
            Map<String, String> p = new HashMap<>();
            p.put("margin-left", "calc(" + (frame * 0.01f) + "% + " + (frame % 97) + "px)");
            ComputedStyle st = new ComputedStyle(p, 16f, 16f);
            sink += st.edgeLength(ComputedStyle.EDGE_MARGIN_L, 1000, 800, 600);
            if (frame % 2000 == 0) System.gc();
        }
        System.gc();
        assertTrue(sink > 0);
        int grown = PackedLengths.exprCapacityUsedForTest() - before;
        assertTrue(grown < 20_000, "式表の添字が " + grown + " 個増えた（10 万フレームで無制限に増えている）");
    }

    @Test
    void releasedSlotsAreReusedAndLiveExpressionsKeepTheirOwnSlot() {
        LengthResolver.CompiledLength keep = LengthResolver.compile("calc(7% + 1px)");
        long kept = PackedLengths.pack(keep);
        for (int round = 0; round < 5; round++) {
            for (int i = 0; i < 500; i++) PackedLengths.pack(LengthResolver.compile("calc(" + round + "px + " + i + "%)"));
            System.gc();
        }
        LengthResolver.CompiledLength probe = LengthResolver.compile("calc(1px + 1px)");
        PackedLengths.pack(probe); // 回収分を空きへ戻す
        assertTrue(PackedLengths.liveExprCountForTest() < 2500, "回収されず " + PackedLengths.liveExprCountForTest() + " 個残る");
        assertEquals(keep.resolve(1000, 0, 0, 16, 16), PackedLengths.resolve(kept, 1000, 0, 0, 16, 16), 0f);
        assertEquals(2f, PackedLengths.resolve(PackedLengths.pack(probe), 1000, 0, 0, 16, 16), 0f);
    }

    /** 番人: 縁は {@code long[]} で持つ。{@code CompiledLength[]} と二つ目の数式評価器（{@code CalcProgram}）が戻らない。 */
    @Test
    void edgesStayPackedAndCalcProgramStaysRemoved() throws Exception {
        Path main = Path.of("core/src/main/java/com/nishiyu/enderframelib/core");
        String facts = Files.readString(main.resolve("css/StyleFacts.java"));
        assertTrue(Files.readString(main.resolve("css/ComputedStyle.java")).contains("volatile long[] packedEdges;"));
        assertFalse(facts.contains("CompiledLength[]"));
        assertFalse(Files.readString(main.resolve("css/ComputedStyle.java")).contains("CompiledLength[]"));
        assertFalse(Files.exists(main.resolve("kernel/style/CalcProgram.java")), "数式の評価器は LengthResolver だけ");
        try (var files = Files.walk(main)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                assertFalse(Files.readString(p).contains("CalcProgram"), p + " が CalcProgram を参照している");
            }
        }
        assertFalse(Files.readString(main.resolve("kernel/style/PackedCssValue.java")).contains("core.css"),
                "kernel.style は css を知らない（パッケージ循環を作らない）");
    }
}
