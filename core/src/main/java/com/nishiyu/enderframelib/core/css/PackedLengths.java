package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.kernel.style.PackedCssValue;

/**
 * 分解済みの長さ（{@link LengthResolver.CompiledLength}）を 64-bit の {@link PackedCssValue} に詰める・解く窓口。
 * 単位つきの長さは値そのものを {@code long} 1 個に持ち（オブジェクトも参照もない）、{@code calc()} 等の式だけは
 * 本流の評価器（{@link LengthResolver}）が要るので、式表の添字を K_CALC として持つ。
 */
final class PackedLengths {
    private PackedLengths() {}

    /** 式表。読みは volatile の配列のスナップショット、書きは同期（式は稀で、詰めるのは初回だけ）。 */
    private static volatile LengthResolver.CompiledLength[] exprs = new LengthResolver.CompiledLength[8];
    private static int exprCount;
    private static final java.util.HashMap<String, Integer> EXPR_INDEX = new java.util.HashMap<>();

    /** 宣言なしは {@link PackedCssValue#NONE}。{@link LengthResolver.CompiledLength#ZERO}（auto・不正値）は 0 px。 */
    static long pack(LengthResolver.CompiledLength c) {
        if (c == null) return PackedCssValue.NONE;
        return switch (c.unit()) {
            case PIXELS -> PackedCssValue.length(c.value(), PackedCssValue.U_PX);
            case PERCENT -> PackedCssValue.length(c.value(), PackedCssValue.U_PERCENT);
            case VW -> PackedCssValue.length(c.value(), PackedCssValue.U_VW);
            case VH -> PackedCssValue.length(c.value(), PackedCssValue.U_VH);
            case VMIN -> PackedCssValue.length(c.value(), PackedCssValue.U_VMIN);
            case VMAX -> PackedCssValue.length(c.value(), PackedCssValue.U_VMAX);
            case EM -> PackedCssValue.length(c.value(), PackedCssValue.U_EM);
            case REM -> PackedCssValue.length(c.value(), PackedCssValue.U_REM);
            case NONE -> PackedCssValue.length(0f, PackedCssValue.U_PX);
            case EXPRESSION -> PackedCssValue.calc(register(c));
        };
    }

    private static synchronized int register(LengthResolver.CompiledLength c) {
        Integer hit = EXPR_INDEX.get(c.raw());
        if (hit != null) return hit;
        LengthResolver.CompiledLength[] a = exprs;
        if (exprCount == a.length) a = java.util.Arrays.copyOf(a, a.length * 2);
        a[exprCount] = c;
        exprs = a;
        EXPR_INDEX.put(c.raw(), exprCount);
        return exprCount++;
    }

    /** {@link LengthResolver.CompiledLength#resolve} と同じ結果（宣言なしは 0）。 */
    static float resolve(long v, float maxPixels, float rootW, float rootH, float elementFontSize, float rootFontSize) {
        return switch (PackedCssValue.kind(v)) {
            case PackedCssValue.K_NONE -> 0f;
            case PackedCssValue.K_CALC ->
                    exprs[PackedCssValue.payload(v)].resolve(maxPixels, rootW, rootH, elementFontSize, rootFontSize);
            default -> PackedCssValue.resolveLength(v, maxPixels, elementFontSize, rootFontSize, rootW, rootH);
        };
    }
}
