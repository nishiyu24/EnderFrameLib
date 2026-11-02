package com.nishiyu.enderframelib.core.paint.model;

import com.nishiyu.enderframelib.core.paint.BlendMode;
import com.nishiyu.enderframelib.core.paint.ClipShape;
import com.nishiyu.enderframelib.core.paint.FilterOp;

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
 * @param span          錨を含めて、このレイヤーに入る項目の数。描画順でスタッキングコンテキストの木は連続するので、
 *                      錨の位置から {@code span} 個がレイヤーの中身（マスクの項目を含む）
 * @param opacity       算出値の {@code opacity}（アニメーション・トランジション中は描画側が錨の値で置き換える）
 * @param filters       {@code filter} の操作の列（色の行列は畳み済み）
 * @param blend         {@code mix-blend-mode}
 * @param isolate       中の子孫の {@code mix-blend-mode} をこの箱の中に閉じ込める必要がある（{@code isolation: isolate}、
 *                      またはスタッキングコンテキストが {@code mix-blend-mode} の子孫を含む）。必要ならそれだけでレイヤーに描く
 * @param clip          {@code clip-path} の図形（箱の座標）。なければ null
 * @param maskFrom      錨からこの個数目以降の項目が、マスク画像（{@code mask-image} のレイヤー）を描く項目。マスクが無ければ -1。
 *                      描画側はこれらを、箱の中身ではなくマスク用の描画先に描く
 * @param maskLuminance {@code mask-mode: luminance}（マスクの輝度で透かす。既定はアルファ）
 * @param backdrop      {@code backdrop-filter} の操作の列
 * @param backdropShape {@code backdrop-filter} を貼る形（箱の角丸の矩形）。{@code backdrop} が空なら null
 */
public record LayerSpec(int span, float opacity, List<FilterOp> filters, BlendMode blend, boolean isolate,
                        ClipShape clip, int maskFrom, boolean maskLuminance,
                        List<FilterOp> backdrop, ClipShape backdropShape) {
    public LayerSpec {
        filters = filters == null ? List.of() : List.copyOf(filters);
        backdrop = backdrop == null ? List.of() : List.copyOf(backdrop);
        if (blend == null) blend = BlendMode.NORMAL;
    }

    /** 不透明度・フィルター・混合だけ（クリップ・マスク・背景のフィルターなし）。 */
    public LayerSpec(int span, float opacity, List<FilterOp> filters, BlendMode blend, boolean isolate) {
        this(span, opacity, filters, blend, isolate, null, -1, false, List.of(), null);
    }

    public LayerSpec withSpan(int newSpan) {
        return new LayerSpec(newSpan, opacity, filters, blend, isolate, clip, maskFrom, maskLuminance, backdrop, backdropShape);
    }

    public LayerSpec withMaskFrom(int newMaskFrom) {
        return new LayerSpec(span, opacity, filters, blend, isolate, clip, newMaskFrom, maskLuminance, backdrop, backdropShape);
    }

    /** マスク画像を持つか（{@link #maskFrom} が決まったあと。決まる前は {@code maskLuminance} とは別に {@link #wantsMask}）。 */
    public boolean masked() {
        return maskFrom >= 0;
    }

    /** ビルダーが作った時点で、マスクのレイヤーを後から足す箱か（{@code maskFrom} は項目を出し終えてから決まる）。 */
    public boolean wantsMask() {
        return maskFrom == WANTS_MASK;
    }

    /** {@link #maskFrom} の仮の値（「マスクを描く。位置はまだ」）。 */
    public static final int WANTS_MASK = Integer.MAX_VALUE;

    public boolean hasBackdrop() {
        return !backdrop.isEmpty() && backdropShape != null;
    }

    /** 錨の不透明度 {@code animatedOpacity} のとき、レイヤーに描く意味があるか（無いなら直に描く）。 */
    public boolean needsLayer(float animatedOpacity) {
        return animatedOpacity < 0.999f || !filters.isEmpty() || blend != BlendMode.NORMAL || isolate || clip != null || masked();
    }
}
