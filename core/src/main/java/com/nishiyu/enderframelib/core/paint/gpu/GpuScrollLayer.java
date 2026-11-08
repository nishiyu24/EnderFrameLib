package com.nishiyu.enderframelib.core.paint.gpu;

import com.nishiyu.enderframelib.core.raster.Matrix2D;

/**
 * スクロールコンテナの中身を「保持した層」として扱うときの、描画側（GPU）へ渡す情報。
 *
 * @param key    スクロール節の項目 id（保持した層の鍵）
 * @param token  中身の同一性の印。同じ印なら、節の移動量以外は前に描いたときと同じ中身（同じ基底の表示リスト）
 * @param pose   節の姿勢（デバイス画素へ）。移動量の差だけが、保持した層のずらし量になる
 * @param clip   コンテナの切り取り（デバイス画素の角丸矩形）。保持した層は、これで切って重ねる
 * @param bounds {@code clip} のデバイス画素の外接矩形 {@code {x0, y0, x1, y1}}
 */
public record GpuScrollLayer(String key, Object token, Matrix2D pose, GpuClip[] clip, int[] bounds) {}
