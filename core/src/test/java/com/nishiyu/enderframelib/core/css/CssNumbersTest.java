package com.nishiyu.enderframelib.core.css;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Locale;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** 旧実装（String.format + 正規表現）と同じ文字列を返すこと。 */
class CssNumbersTest {

    private static String oldPx(float value) {
        float rounded = Math.round(value);
        if (Math.abs(value - rounded) < 1e-4f) return (long) rounded + "px";
        String s = String.format(Locale.ROOT, "%.4f", value);
        s = s.replaceAll("0+$", "");
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s + "px";
    }

    private static String oldFormat(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e9) return Long.toString((long) v);
        String s = String.format(Locale.ROOT, "%.4f", v);
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '0') end--;
        if (end > 0 && s.charAt(end - 1) == '.') end--;
        s = s.substring(0, end);
        return s.equals("-0") ? "0" : s;
    }

    @Test
    void pxMatchesLegacyOnKnownValues() {
        for (float f : new float[]{0f, 1f, -1f, 0.5f, 12.34567f, 0.0050f, 0.03125f, -0.03125f, 100.00005f, 1e-5f,
                -7.25f, 33.3333f, 1234567.5f, 0.1f, 0.00015f, 99999.9999f}) {
            assertEquals(oldPx(f), CssNumbers.px(f), "px(" + f + ")");
        }
    }

    @Test
    void pxMatchesLegacyOnRandomValues() {
        Random r = new Random(42);
        for (int i = 0; i < 200_000; i++) {
            float f = (r.nextFloat() - 0.5f) * (float) Math.pow(10, r.nextInt(7) - 2);
            assertEquals(oldPx(f), CssNumbers.px(f), "px(" + f + ")");
        }
    }

    @Test
    void trimmed4MatchesLegacyFormat() {
        Random r = new Random(7);
        for (int i = 0; i < 200_000; i++) {
            double d = (r.nextDouble() - 0.5) * Math.pow(10, r.nextInt(8) - 3);
            String expected = oldFormat(d);
            String got = d == Math.rint(d) && Math.abs(d) < 1e9 ? Long.toString((long) d) : CssNumbers.trimmed4(d);
            if (got.equals("-0")) got = "0";
            assertEquals(expected, got, "format(" + d + ")");
        }
    }

    @Test
    void valueComputerAndInterpolatorUseIt() {
        assertEquals("12.5px", ValueComputer.px(12.5f));
        assertEquals("3px", ValueComputer.px(3.00001f));
        assertEquals("-0.5px", ValueComputer.px(-0.5f));
    }

    private static String oldSix(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
        String s = String.format(Locale.ROOT, "%.6f", v);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    @Test
    void sixDigitsMatchesLegacy() {
        Random r = new Random(11);
        for (int i = 0; i < 200_000; i++) {
            double d = (r.nextDouble() - 0.5) * Math.pow(10, r.nextInt(8) - 3);
            if (d == Math.rint(d)) continue;
            assertEquals(oldSix(d), CssNumbers.trimmed(d, 6), "six(" + d + ")");
        }
    }

    @Test
    void colorFormatMatchesLegacy() {
        Random r = new Random(5);
        for (int i = 0; i < 100_000; i++) {
            int argb = r.nextInt();
            if (r.nextInt(3) == 0) argb |= 0xFF000000;
            int a = argb >>> 24, rr = (argb >> 16) & 255, g = (argb >> 8) & 255, b = argb & 255;
            String expected;
            if (a == 255) expected = String.format(Locale.ROOT, "#%02x%02x%02x", rr, g, b);
            else {
                String n = String.format(Locale.ROOT, "%.4f", a / 255.0).replaceAll("0+$", "");
                if (n.endsWith(".")) n = n.substring(0, n.length() - 1);
                expected = "rgba(" + rr + ", " + g + ", " + b + ", " + n + ")";
            }
            assertEquals(expected, ColorParser.format(argb), Integer.toHexString(argb));
        }
    }
}
