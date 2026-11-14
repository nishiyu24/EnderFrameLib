package com.nishiyu.enderframelib.core.kernel.style;

import com.nishiyu.enderframelib.core.kernel.LayoutUnit;

/**
 * CSS の宣言値を 64-bit の {@code long} 1 個で表す（文字列を持ち回らない）。
 *
 * <pre>
 *  63      56 55      48 47            32 31                       0
 * +----------+----------+----------------+--------------------------+
 * | Kind(8b) | Unit(8b) | Flags/Extra16b | Payload (float bits /    |
 * |          |          |                |  ARGB / atom / calc idx) |
 * +----------+----------+----------------+--------------------------+
 * </pre>
 *
 * <p>Flags/Extra の最下位ビットは {@code !important}。CALC のときはペイロードが
 * 呼び出し側の式表（{@code css.PackedLengths}）の添字で、この型は式の中身を知らない（数式の評価器は本流の
 * {@code LengthResolver} 1 つだけ）。値オブジェクトを作らないので、比較は {@code ==} 1 回。</p>
 */
public final class PackedCssValue {
    private PackedCssValue() {}

    public static final int K_NONE = 0, K_LENGTH = 1, K_NUMBER = 2, K_COLOR = 3, K_KEYWORD = 4, K_CALC = 5;
    public static final int U_NONE = 0, U_PX = 1, U_PERCENT = 2, U_EM = 3, U_REM = 4, U_VW = 5, U_VH = 6, U_VMIN = 7, U_VMAX = 8;
    public static final int FLAG_IMPORTANT = 1;

    /** 値が無い（宣言なし）。{@code long} の 0。 */
    public static final long NONE = 0L;

    public static long pack(int kind, int unit, int flags, int payload) {
        return ((long) (kind & 0xFF) << 56) | ((long) (unit & 0xFF) << 48) | ((long) (flags & 0xFFFF) << 32)
                | (payload & 0xFFFFFFFFL);
    }

    public static int kind(long v) { return (int) (v >>> 56); }
    public static int unit(long v) { return (int) ((v >>> 48) & 0xFF); }
    public static int flags(long v) { return (int) ((v >>> 32) & 0xFFFF); }
    public static int payload(long v) { return (int) v; }
    public static boolean isImportant(long v) { return (flags(v) & FLAG_IMPORTANT) != 0; }
    public static long withImportant(long v) { return v | ((long) FLAG_IMPORTANT << 32); }

    public static long length(float value, int unit) { return pack(K_LENGTH, unit, 0, Float.floatToRawIntBits(value)); }
    public static long number(float value) { return pack(K_NUMBER, U_NONE, 0, Float.floatToRawIntBits(value)); }
    public static long color(int argb) { return pack(K_COLOR, U_NONE, 0, argb); }
    public static long keyword(int atom) { return pack(K_KEYWORD, U_NONE, 0, atom); }
    public static long calc(int programIndex) { return pack(K_CALC, U_NONE, 0, programIndex); }

    public static float floatPayload(long v) { return Float.intBitsToFloat((int) v); }
    public static int colorArgb(long v) { return (int) v; }

    /** 長さを px に解く（K_LENGTH / K_NUMBER 用）。{@code vw} / {@code vh} はビューポートの幅・高さ（px）。K_CALC は呼び出し側が解く。 */
    public static float resolveLength(long v, float percentBase, float fontSize, float rootFontSize, float vw, float vh) {
        float f = floatPayload(v);
        return switch (unit(v)) {
            case U_PERCENT -> f * percentBase / 100f;
            case U_EM -> LayoutUnit.snap(f * fontSize);
            case U_REM -> LayoutUnit.snap(f * rootFontSize);
            case U_VW -> f * vw / 100f;
            case U_VH -> f * vh / 100f;
            case U_VMIN -> Math.min(vw, vh) * f / 100f;
            case U_VMAX -> Math.max(vw, vh) * f / 100f;
            default -> f;
        };
    }
}
