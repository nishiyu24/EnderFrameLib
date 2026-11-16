package com.nishiyu.enderframelib.api.scene3d;

import org.jetbrains.annotations.ApiStatus;

/**
 * 3D のレイが当たった点。{@code nodeId} は {@link Scene3DController} が返したノード番号（文書の {@code <mesh-3d>} も番号を持つ）、
 * {@code distance} はレイの始点から当たりまでの長さ（ワールド単位）、{@code (x, y, z)} は当たった点（ワールド座標）、
 * {@code (baryU, baryV)} は当たった三角形の重心座標（頂点 1・頂点 2 の重み）。
 */
@ApiStatus.AvailableSince("1.0")
public record Hit3DResult(int nodeId, int entityTag, double distance, double x, double y, double z, double baryU, double baryV) {
}
