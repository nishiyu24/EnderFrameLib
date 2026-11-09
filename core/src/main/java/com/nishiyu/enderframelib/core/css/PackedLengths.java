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

    /** {@code auto} を詰めた値（キーワード種別。{@link #resolve} は 0 px に解くので、旧 {@code ZERO} と同じ寸法になる）。 */
    static final long AUTO = PackedCssValue.keyword(1);

    static boolean isAuto(long v) {
        return v == AUTO;
    }

    /** 詰めた値が「0 px の長さ」か（{@code 0} のほか、解釈できない値・{@code fit-content(..)} などが潰れた形）。 */
    static boolean isZeroPx(long v) {
        return PackedCssValue.kind(v) == PackedCssValue.K_LENGTH && PackedCssValue.unit(v) == PackedCssValue.U_PX
                && PackedCssValue.floatPayload(v) == 0f;
    }

    static boolean isExpression(long v) {
        return PackedCssValue.kind(v) == PackedCssValue.K_CALC;
    }

    /** 詰めた長さが % を含むか。単位が % のもの、または式の原文に % を含むもの（宣言なしは false）。 */
    static boolean hasPercent(long v) {
        return switch (PackedCssValue.kind(v)) {
            case PackedCssValue.K_LENGTH -> PackedCssValue.unit(v) == PackedCssValue.U_PERCENT;
            case PackedCssValue.K_CALC -> {
                String raw = exprs[PackedCssValue.payload(v)].raw();
                yield raw != null && raw.indexOf('%') >= 0;
            }
            default -> false;
        };
    }

    /** {@link LengthResolver.CompiledLength#resolve} と同じ結果（宣言なしは 0）。 */
    static float resolve(long v, float maxPixels, float rootW, float rootH, float elementFontSize, float rootFontSize) {
        return switch (PackedCssValue.kind(v)) {
            case PackedCssValue.K_NONE, PackedCssValue.K_KEYWORD -> 0f;
            case PackedCssValue.K_CALC ->
                    exprs[PackedCssValue.payload(v)].resolve(maxPixels, rootW, rootH, elementFontSize, rootFontSize);
            default -> PackedCssValue.resolveLength(v, maxPixels, elementFontSize, rootFontSize, rootW, rootH);
        };
    }
}
