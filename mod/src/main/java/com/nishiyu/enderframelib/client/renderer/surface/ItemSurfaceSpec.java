package com.nishiyu.enderframelib.client.renderer.surface;

import net.minecraft.world.item.ItemDisplayContext;

/**
 * 手持ちアイテムの面（タブレットなど）の形と置き方。{@code WebItemRendererHelper} がこれだけで描く。
 * アイテム空間は 0..1（モデルの display の変換が掛かった後）。
 *
 * @param virtualWidth           仮想解像度の幅（px）
 * @param virtualHeight          仮想解像度の高さ（px）
 * @param widthBlocks            面の物理的な幅（アイテム空間の単位）。高さは仮想解像度の比で決まる
 * @param firstPersonWidthBlocks 一人称のときの最大の幅。視界に溢れないよう、{@code widthBlocks} を超えない範囲でこの幅に収める
 * @param anchor                 面の基準点
 * @param yRotDegrees            面の Y 回転（度）
 * @param handShift              一人称の見やすさ補正（見下ろすと上へ寄せる・両手持ちでずらす）を掛けるか。
 *                               紙などの親モデルの上に重ねる使い方では位置を親の {@code display} に任せるので false
 * @param left                   {@link Anchor#ABSOLUTE} のときの面の左端（アイテム空間、左が 0）
 * @param top                    {@link Anchor#ABSOLUTE} のときの面の上端（アイテム空間、下が 0・上が 1）
 * @param zOffset                面の前後位置の補正（アイテム空間。正で手前）。親モデルの面に重ねるときに浮かせる量
 */
public record ItemSurfaceSpec(int virtualWidth, int virtualHeight, float widthBlocks, float firstPersonWidthBlocks,
                              Anchor anchor, float yRotDegrees, boolean handShift,
                              float left, float top, float zOffset) {

    /** 一人称の補正なし。位置は親モデルの {@code display} に任せる。 */
    public ItemSurfaceSpec(int virtualWidth, int virtualHeight, float widthBlocks, float firstPersonWidthBlocks,
                           Anchor anchor, float yRotDegrees) {
        this(virtualWidth, virtualHeight, widthBlocks, firstPersonWidthBlocks, anchor, yRotDegrees, false, 0f, 0f, 0f);
    }

    /**
     * テクスチャ上の矩形に面を貼る。座標はテクスチャのピクセル（左上が原点、y は下向き）。
     * 面はアイテム（や地図）の動きにそのまま連動する。幅を矩形の幅に合わせ、高さは仮想解像度の比で決まる。
     *
     * @param virtualWidth  仮想解像度の幅（px）
     * @param virtualHeight 仮想解像度の高さ（px）
     * @param textureWidth  対象のテクスチャの幅（px）。アイテムは 16、開いた地図は 128 など
     * @param textureHeight 対象のテクスチャの高さ（px）
     * @param x             矩形の左端（テクスチャ px）
     * @param y             矩形の上端（テクスチャ px）
     * @param width         矩形の幅（テクスチャ px）
     */
    public static ItemSurfaceSpec onTexture(int virtualWidth, int virtualHeight, int textureWidth, int textureHeight,
                                            float x, float y, float width) {
        float w = width / textureWidth;
        return new ItemSurfaceSpec(virtualWidth, virtualHeight, w, w, Anchor.ABSOLUTE, 0f, false,
                x / textureWidth, 1f - y / textureHeight, 0f);
    }

    /** 一人称の見やすさ補正を掛ける版。 */
    public ItemSurfaceSpec withHandShift() {
        return new ItemSurfaceSpec(virtualWidth, virtualHeight, widthBlocks, firstPersonWidthBlocks, anchor, yRotDegrees, true,
                left, top, zOffset);
    }

    /** 前後位置を補正した版。 */
    public ItemSurfaceSpec withZOffset(float zOffset) {
        return new ItemSurfaceSpec(virtualWidth, virtualHeight, widthBlocks, firstPersonWidthBlocks, anchor, yRotDegrees, handShift,
                left, top, zOffset);
    }

    /** 面をアイテム空間のどこに置くか。 */
    public enum Anchor {
        /** 面の中心をアイテム空間の中心（0.5, 0.5）に置く。 */
        CENTER,
        /** 面の左下をアイテム空間の左下（0, 0）に置く。 */
        BOTTOM_LEFT,
        /** 面の左上を {@code left}, {@code top} に置く。{@link #onTexture} が使う。 */
        ABSOLUTE
    }

    /** 中心に置き、一人称でも同じ幅で描く。 */
    public static ItemSurfaceSpec centered(int virtualWidth, int virtualHeight, float widthBlocks) {
        return new ItemSurfaceSpec(virtualWidth, virtualHeight, widthBlocks, widthBlocks, Anchor.CENTER, 0f);
    }

    /** このコンテキストで描く物理的な幅。 */
    public float physicalWidth(ItemDisplayContext context) {
        boolean firstPerson = context == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                || context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND;
        return firstPerson ? Math.min(widthBlocks, firstPersonWidthBlocks) : widthBlocks;
    }

    /** 幅に仮想解像度の縦横比を掛けた、物理的な高さ。 */
    public float physicalHeight(ItemDisplayContext context) {
        return physicalHeight(context, (float) virtualHeight / virtualWidth);
    }

    /** 幅に {@code aspect}（高さ / 幅）を掛けた、物理的な高さ。セッションを読み込んだときの解像度の縦横比を渡す。 */
    public float physicalHeight(ItemDisplayContext context, float aspect) {
        return physicalWidth(context) * aspect;
    }

    /** 面の左上の角（仮想座標の原点）のアイテム空間での位置。{@code {x, y, z}}。 */
    public float[] origin(ItemDisplayContext context) {
        return origin(context, (float) virtualHeight / virtualWidth);
    }

    public float[] origin(ItemDisplayContext context, float aspect) {
        float w = physicalWidth(context);
        float h = physicalHeight(context, aspect);
        return switch (anchor) {
            case CENTER -> new float[]{(1f - w) / 2f, (1f + h) / 2f, 0.5f + zOffset};
            case BOTTOM_LEFT -> new float[]{0f, h, 0.5f + zOffset};
            case ABSOLUTE -> new float[]{left, top, 0.5f + zOffset};
        };
    }
}
