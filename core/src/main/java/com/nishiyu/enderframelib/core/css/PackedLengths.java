package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.kernel.style.PackedCssValue;

/**
 * 分解済みの長さ（{@link LengthResolver.CompiledLength}）を 64-bit の {@link PackedCssValue} に詰める・解く窓口。
 * 単位つきの長さは値そのものを {@code long} 1 個に持ち（オブジェクトも参照もない）、{@code calc()} 等の式だけは
 * 本流の評価器（{@link LengthResolver}）が要るので、式表の添字を K_CALC として持つ。
 */
final class PackedLengths {
    private PackedLengths() {}

    /**
     * 式表。式は弱参照で持ち、持ち主（{@code CssValue} が {@link LengthResolver.CompiledLength} を強参照で保持し、
     * それを{@code ComputedStyle} が保持する）が消えれば添字ごと回収して再利用する。長時間のアニメーションで
     * 補間の {@code calc()} が毎フレーム生まれても、生きている式の数でしか増えない。
     * 読みは volatile の配列のスナップショット、書きは同期（式は稀で、詰めるのは初回だけ）。
     */
    private static final class Slot extends java.lang.ref.WeakReference<LengthResolver.CompiledLength> {
        final int index;
        final String raw;

        Slot(LengthResolver.CompiledLength c, int index, java.lang.ref.ReferenceQueue<LengthResolver.CompiledLength> q) {
            super(c, q);
            this.index = index;
            this.raw = c.raw();
        }
    }

    private static volatile Slot[] slots = new Slot[8];
    private static int slotCount;
    private static int[] freeList = new int[8];
    private static int freeCount;
    private static final java.util.HashMap<String, Slot> EXPR_INDEX = new java.util.HashMap<>();
    private static final java.lang.ref.ReferenceQueue<LengthResolver.CompiledLength> QUEUE =
            new java.lang.ref.ReferenceQueue<>();

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
        drain();
        Slot hit = EXPR_INDEX.get(c.raw());
        // 同じ式でも別の実体なら共有しない（先の実体が先に消えると、後の持ち主の添字が宙に浮くため）
        if (hit != null && hit.get() == c) return hit.index;
        int idx;
        if (freeCount > 0) {
            idx = freeList[--freeCount];
        } else {
            Slot[] a = slots;
            if (slotCount == a.length) {
                a = java.util.Arrays.copyOf(a, a.length * 2);
                slots = a;
            }
            idx = slotCount++;
        }
        Slot s = new Slot(c, idx, QUEUE);
        slots[idx] = s;
        EXPR_INDEX.put(c.raw(), s);
        return idx;
    }

    /** 回収された式の添字を空きへ戻す（{@link #register} の同期の中で呼ぶ）。 */
    private static void drain() {
        Object r;
        while ((r = QUEUE.poll()) != null) {
            Slot s = (Slot) r;
            if (slots[s.index] != s) continue;
            slots[s.index] = null;
            EXPR_INDEX.remove(s.raw, s);
            if (freeCount == freeList.length) freeList = java.util.Arrays.copyOf(freeList, freeList.length * 2);
            freeList[freeCount++] = s.index;
        }
    }

    private static LengthResolver.CompiledLength expr(long v) {
        Slot s = slots[PackedCssValue.payload(v)];
        LengthResolver.CompiledLength c = s == null ? null : s.get();
        if (c == null) throw new IllegalStateException("式の持ち主が先に消えた: 添字 " + PackedCssValue.payload(v));
        return c;
    }

    /** テスト用: 使用中（添字が割り当て済み）の式の数。回収待ちは {@link #register} が次に空きへ戻す。 */
    static synchronized int liveExprCountForTest() {
        drain();
        return slotCount - freeCount;
    }

    /** テスト用: 式表が確保した添字の総数（高水位）。 */
    static synchronized int exprCapacityUsedForTest() {
        return slotCount;
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
                String raw = expr(v).raw();
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
                    expr(v).resolve(maxPixels, rootW, rootH, elementFontSize, rootFontSize);
            default -> PackedCssValue.resolveLength(v, maxPixels, elementFontSize, rootFontSize, rootW, rootH);
        };
    }
}
