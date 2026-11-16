package com.nishiyu.enderframelib.core.scene3d;

/**
 * カメラを注視点のまわりで回す・寄せる（オービット操作。GL・paint・DOM に依存しない純粋な計算）。上方向はワールドの +Y に固定し、
 * 真上・真下で反転しないよう仰角を ±89° に抑える。
 */
public final class CameraOrbit {
    /** 仰角の限界（ラジアン）。 */
    public static final float MAX_PITCH = (float) Math.toRadians(89.0);
    /** 注視点とカメラの距離の下限・上限。 */
    public static final float MIN_DISTANCE = 0.05f, MAX_DISTANCE = 10_000f;

    private CameraOrbit() {}

    /** 注視点を中心に、水平に {@code dYaw}・垂直に {@code dPitch}（ラジアン。正の dYaw でカメラは右（+X 側）へ、正の dPitch で上へ動く）回す。 */
    public static void orbit(CameraSpec cam, float dYaw, float dPitch) {
        float vx = cam.eyeX() - cam.targetX(), vy = cam.eyeY() - cam.targetY(), vz = cam.eyeZ() - cam.targetZ();
        float r = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (r < 1e-6f) return;
        float yaw = (float) Math.atan2(vx, vz) + dYaw;
        float pitch = (float) Math.asin(Math.max(-1f, Math.min(1f, vy / r))) + dPitch;
        pitch = Math.max(-MAX_PITCH, Math.min(MAX_PITCH, pitch));
        float cp = (float) Math.cos(pitch);
        cam.lookAt(cam.targetX() + r * cp * (float) Math.sin(yaw), cam.targetY() + r * (float) Math.sin(pitch),
                cam.targetZ() + r * cp * (float) Math.cos(yaw), cam.targetX(), cam.targetY(), cam.targetZ(), 0f, 1f, 0f);
    }

    /** 注視点との距離を {@code factor} 倍にする（1 未満で近づく）。 */
    public static void zoom(CameraSpec cam, float factor) {
        if (!(factor > 0f) || Float.isInfinite(factor)) return;
        float vx = cam.eyeX() - cam.targetX(), vy = cam.eyeY() - cam.targetY(), vz = cam.eyeZ() - cam.targetZ();
        float r = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (r < 1e-6f) return;
        float nr = Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, r * factor));
        float k = nr / r;
        cam.lookAt(cam.targetX() + vx * k, cam.targetY() + vy * k, cam.targetZ() + vz * k,
                cam.targetX(), cam.targetY(), cam.targetZ(), 0f, 1f, 0f);
    }
}
