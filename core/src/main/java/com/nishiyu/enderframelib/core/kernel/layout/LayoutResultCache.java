package com.nishiyu.enderframelib.core.kernel.layout;

/**
 * 1 つの箱が持つ「制約 → 結果」の 4 スロット。用途ごとに別のスロットを持つので、同じ子が min-content・max-content・
 * Flex/Grid の仮測定・最終測定の別条件で測られても、互いの結果を上書きしない（単一スロットだと往復のたびに作り直す）。
 *
 * @param <K> 制約の型（{@code equals} で比べられること）
 */
public final class LayoutResultCache<K> {
    public static final int MIN_CONTENT = 0;
    public static final int MAX_CONTENT = 1;
    public static final int TENTATIVE = 2;
    public static final int FINAL = 3;
    public static final int SLOTS = 4;

    /** 不変の測定結果。 */
    public record LayoutResult(float w, float h, float contentW, float contentH, float baseline) {}

    private final Object[] keys = new Object[SLOTS];
    private final LayoutResult[] results = new LayoutResult[SLOTS];

    /** 制約が同じなら結果、違う・無いなら null。 */
    public LayoutResult get(int slot, K constraint) {
        Object k = keys[slot];
        return k != null && k.equals(constraint) ? results[slot] : null;
    }

    public void put(int slot, K constraint, LayoutResult result) {
        keys[slot] = constraint;
        results[slot] = result;
    }

    /** 中身が変わった。全スロットを捨てる。 */
    public void invalidateAll() {
        for (int i = 0; i < SLOTS; i++) invalidate(i);
    }

    public void invalidate(int slot) {
        keys[slot] = null;
        results[slot] = null;
    }
}
