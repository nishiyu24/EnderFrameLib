package com.nishiyu.enderframelib.core.paint.model;


import java.util.List;

/**
 * スタッキングコンテキストの箱を 1 枚のレイヤー（別の描画先）に描いてから合成する指定。錨の項目（{@code NODE}）の
 * {@link RenderHints#layer()} に載る。
 *
 * <p>グループの不透明度・{@code filter}・{@code mix-blend-mode}・{@code clip-path}・{@code mask} は、箱とその子孫を先に 1 枚へ描き、
 * できた画像に掛けてから下へ重ねる（ブラウザと同じ）。子孫を 1 つずつ半透明にして重ねると、重なった部分が透けて見える。
 * {@code backdrop-filter} は箱の後ろの画素を加工して箱の形に貼る（箱自身の描画の前）ので、レイヤーに描く必要は無く、
 * 錨の位置で先に済ませる。
 *
 * <p>レイヤーの中身は錨の子孫の項目（列の階層で引く。M3b-4c-2b-3b-2a で列の範囲〔錨から何個〕を持たなくなった）、マスクの画像を描く項目は
 * 列の階層の印（{@code FrameStructure#isMask}）で分ける。</p>
 *
 * @param opacity       算出値の {@code opacity}（アニメーション・トランジション中は描画側が錨の値で置き換える）
 * @param filters       {@code filter} の操作の列（色の行列は畳み済み）
 * @param blend         {@code mix-blend-mode}
 * @param isolate       中の子孫の {@code mix-blend-mode} をこの箱の中に閉じ込める必要がある（{@code isolation: isolate}、
 *                      またはスタッキングコンテキストが {@code mix-blend-mode} の子孫を含む）。必要ならそれだけでレイヤーに描く
 * @param clip          {@code clip-path} の図形（箱の座標）。なければ null
 * @param mask          マスク画像（{@code mask-image} のレイヤー）: {@link #MASK_NONE}・{@link #MASK_WANTED}（作った時点。マスクの項目を出し終えて
 *                      決まる）・{@link #MASK_DRAWN}（マスクの項目がある。描画側はそれらを箱の中身ではなくマスク用の描画先に描く）
 * @param maskLuminance {@code mask-mode: luminance}（マスクの輝度で透かす。既定はアルファ）
 * @param backdrop      {@code backdrop-filter} の操作の列
 * @param backdropShape {@code backdrop-filter} を貼る形（箱の角丸の矩形）。{@code backdrop} が空なら null
 */
public record LayerSpec(float opacity, List<FilterOp> filters, BlendMode blend, boolean isolate,
                        ClipShape clip, int mask, boolean maskLuminance,
                        List<FilterOp> backdrop, ClipShape backdropShape) {
    public LayerSpec {
        filters = filters == null ? List.of() : List.copyOf(filters);
        backdrop = backdrop == null ? List.of() : List.copyOf(backdrop);
        if (blend == null) blend = BlendMode.NORMAL;
    }

    /** 不透明度・フィルター・混合だけ（クリップ・マスク・背景のフィルターなし）。 */
    public LayerSpec(float opacity, List<FilterOp> filters, BlendMode blend, boolean isolate) {
        this(opacity, filters, blend, isolate, null, MASK_NONE, false, List.of(), null);
    }

    /** マスクの項目を出し終えた後の指定（{@code drawn} ならマスクの項目がある）。 */
    public LayerSpec withMask(boolean drawn) {
        return new LayerSpec(opacity, filters, blend, isolate, clip, drawn ? MASK_DRAWN : MASK_NONE, maskLuminance, backdrop, backdropShape);
    }

    /** 層を要する効果（フィルター・マスク・混合・分離・{@code clip-path}・{@code backdrop-filter}）が 1 つでもあるか。不透明度は数えない。 */
    public boolean hasEffects() {
        return !filters.isEmpty() || masked() || blend != BlendMode.NORMAL || isolate || clip != null || hasBackdrop();
    }

    /** マスク画像の項目を持つか（{@link #withMask} で決まったあと）。 */
    public boolean masked() {
        return mask == MASK_DRAWN;
    }

    /** 作った時点で、マスクのレイヤーを後から足す箱か（項目があるかは出し終えてから決まる）。 */
    public boolean wantsMask() {
        return mask == MASK_WANTED;
    }

    /** {@link #mask} の値。 */
    public static final int MASK_NONE = 0, MASK_WANTED = 1, MASK_DRAWN = 2;

    public boolean hasBackdrop() {
        return !backdrop.isEmpty() && backdropShape != null;
    }

    /** 錨の不透明度 {@code animatedOpacity} のとき、レイヤーに描く意味があるか（無いなら直に描く）。 */
    public boolean needsLayer(float animatedOpacity) {
        return animatedOpacity < 0.999f || !filters.isEmpty() || blend != BlendMode.NORMAL || isolate || clip != null || masked();
    }
}
