package com.nishiyu.enderframelib.core.paint.model;

/**
 * VRAM の予算。文字の置き場（字形のテクスチャ。{@code AtlasFootprint}）とレイヤー合成の FBO プールが取り合う
 * 領域の配分を決める。
 *
 * <ul>
 *   <li>全体の上限 {@link #TOTAL_CEILING}（この機能が使ってよい VRAM）から、文字の置き場の予約を引いたものが FBO の予算。
 *       置き場の予約は実際の使用量と {@link #ATLAS_RESERVE} の大きいほう（置き場は描画中に増えるので、空でも最低限を残す）。</li>
 *   <li>FBO の予算は画面の大きさのレイヤー {@link #MIN_LAYERS}〜{@link #MAX_LAYERS} 枚分に収める
 *       （入れ子の効果が数段重なっても回り、巨大な画面でも青天井にならない）。</li>
 *   <li>置き場の使用量が {@link #ATLAS_CEILING} を超えたら、置き場の側が古いものを捨てる目安にする
 *       （{@link #atlasOverCeiling}）。FBO が先に削られることはない。</li>
 * </ul>
 * FBO は効果があるときだけ確保するので、効果の無い文書は予算を一切使わない。
 */
public final class VramBudget {

    private VramBudget() {}

    /** 全体の上限（バイト）。 */
    public static final long TOTAL_CEILING = 192L << 20;
    /** 文字の置き場の最低限の予約（バイト）。 */
    public static final long ATLAS_RESERVE = 32L << 20;
    /** 文字の置き場がこれを超えたら古い置き場を捨てる（バイト）。 */
    public static final long ATLAS_CEILING = 64L << 20;
    public static final int MIN_LAYERS = 4;
    public static final int MAX_LAYERS = 16;

    /** 1 枚のレイヤーのバイト数。色 RGBA8 に深度 24 + ステンシル 8（クリップ・マスク用）を足したもの。 */
    public static long layerBytes(int width, int height) {
        if (width <= 0 || height <= 0) return 0;
        return (long) width * height * 8;
    }

    /** FBO プールが使ってよいバイト数。 */
    public static long fboBudgetBytes(int width, int height, long atlasBytes) {
        long layer = layerBytes(width, height);
        long available = TOTAL_CEILING - Math.max(atlasBytes, ATLAS_RESERVE);
        long min = layer * MIN_LAYERS;
        long max = layer * MAX_LAYERS;
        return Math.max(min, Math.min(max, available));
    }

    /** その予算で同時に持てるレイヤーの枚数（効果の入れ子の深さの上限になる）。 */
    public static int maxLayers(int width, int height, long atlasBytes) {
        long layer = layerBytes(width, height);
        if (layer == 0) return 0;
        return (int) Math.min(MAX_LAYERS, fboBudgetBytes(width, height, atlasBytes) / layer);
    }

    public static boolean atlasOverCeiling(long atlasBytes) {
        return atlasBytes > ATLAS_CEILING;
    }
}
