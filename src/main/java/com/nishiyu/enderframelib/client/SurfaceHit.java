package com.nishiyu.enderframelib.client;

import net.minecraft.core.BlockPos;

/**
 * 照準（またはカーソル）が指す、世界内の面の点。
 *
 * @param sessionId 面のセッション
 * @param pixelX    セッションのピクセル座標
 * @param pixelY    セッションのピクセル座標
 * @param masterPos 画面の master ブロック（左下）の位置。ブロックの無いホログラムでは {@code null}
 * @param distance  視点から当たった点までの距離（ブロック）。照準の先にあるブロックとの前後の比較に使う
 */
public record SurfaceHit(String sessionId, double pixelX, double pixelY, BlockPos masterPos, double distance) {
}
