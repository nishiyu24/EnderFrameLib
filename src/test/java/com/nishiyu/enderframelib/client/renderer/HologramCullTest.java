package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.client.Hologram;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ホログラムは視錐台の外（背後・画面外）では描かない。判定に使う箱は、どの向きに回っても面を含む。 */
class HologramCullTest {

    /** (0, 64, 0) から -Z を向くカメラの視錐台。 */
    private static Frustum frustum() {
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.05f, 512f);
        Frustum f = new Frustum(new Matrix4f(), projection);
        f.prepare(0, 64, 0);
        return f;
    }

    @Test
    void hologramsBehindOrBesideTheCameraAreCulled() {
        Frustum f = frustum();
        assertTrue(f.isVisible(HologramRenderer.bounds(Hologram.at("a", new Vec3(0, 64, -10), 2f, 1.125f))), "正面");
        assertFalse(f.isVisible(HologramRenderer.bounds(Hologram.at("a", new Vec3(0, 64, 10), 2f, 1.125f))), "背後");
        assertFalse(f.isVisible(HologramRenderer.bounds(Hologram.at("a", new Vec3(60, 64, -5), 2f, 1.125f))), "真横の画面外");
        // 中心（距離 5 で左へ 7）は画面外でも、右の端（左へ 5）が入っていれば描く。水平の半画角は約 51 度
        assertTrue(f.isVisible(HologramRenderer.bounds(Hologram.at("a", new Vec3(-7, 64, -5), 4f, 1f))), "端が入る大きな面");
    }

    @Test
    void boundsContainTheFaceAtAnyYaw() {
        Hologram base = Hologram.at("a", new Vec3(3, 70, -4), 2f, 1f);
        for (float yaw = 0; yaw < 360; yaw += 15) {
            Hologram h = base.withYaw(yaw);
            AABB box = HologramRenderer.bounds(h);
            double t = Math.toRadians(-yaw);
            // 面の右の辺の向き（局所の +X を Y 軸回りに回したもの）
            double ex = Math.cos(t), ez = -Math.sin(t);
            for (int sx = -1; sx <= 1; sx += 2) {
                for (int sy = -1; sy <= 1; sy += 2) {
                    Vec3 corner = h.center().add(sx * ex * h.widthBlocks() / 2, sy * h.heightBlocks() / 2.0, sx * ez * h.widthBlocks() / 2);
                    assertTrue(box.contains(corner), "yaw=" + yaw + " の角 " + corner);
                }
            }
        }
    }
}
