package com.nishiyu.enderframelib.client;

import net.minecraft.world.phys.Vec3;

/**
 * ホログラム 1 枚の設定。ブロックを持たず、発光で描く。
 *
 * @param sessionId    映すセッションの ID
 * @param center       画面の中心（ワールド座標）
 * @param widthBlocks  幅（ブロック数。小数可）
 * @param heightBlocks 高さ（ブロック数。小数可）
 * @param yawDegrees   Y 軸回りの向き。0 は南向き（+Z 側に正面）。ビルボードのときは無視される
 * @param billboard    真なら、常にカメラの方を向く（Y 軸回りのみ）
 * @param maxDistance  この距離（ブロック）より遠くでは描かない
 * @param interactive  真なら、照準を合わせてクリック・ホバーできる（既定は表示専用）
 */
public record Hologram(String sessionId, Vec3 center, float widthBlocks, float heightBlocks,
                       float yawDegrees, boolean billboard, double maxDistance, boolean interactive) {

    /** 南向き・32 ブロックまで。 */
    public static Hologram at(String sessionId, Vec3 center, float widthBlocks, float heightBlocks) {
        return new Hologram(sessionId, center, widthBlocks, heightBlocks, 0f, false, 32.0, false);
    }

    public Hologram asBillboard() {
        return new Hologram(sessionId, center, widthBlocks, heightBlocks, yawDegrees, true, maxDistance, interactive);
    }

    public Hologram withYaw(float yawDegrees) {
        return new Hologram(sessionId, center, widthBlocks, heightBlocks, yawDegrees, false, maxDistance, interactive);
    }

    public Hologram withMaxDistance(double maxDistance) {
        return new Hologram(sessionId, center, widthBlocks, heightBlocks, yawDegrees, billboard, maxDistance, interactive);
    }

    /** 照準を合わせてクリック・ホバーできるようにする（空中のメニュー）。ブロックより手前にあるときだけ当たる。 */
    public Hologram asInteractive() {
        return new Hologram(sessionId, center, widthBlocks, heightBlocks, yawDegrees, billboard, maxDistance, true);
    }
}
