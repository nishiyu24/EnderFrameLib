package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.client.Hologram;
import com.nishiyu.enderframelib.client.SessionContext;
import com.nishiyu.enderframelib.client.SurfaceHit;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 照準とホログラムの面の交点が、描画の変換の逆でセッションのピクセルに直ること。 */
public class HologramPickTest {

    private static SessionContext session() {
        SessionContext s = new SessionContext("holo");
        s.setRootW(640);
        s.setRootH(360);
        return s;
    }

    private static final Hologram HOLO = Hologram.at("holo", new Vec3(0, 64, 0), 2f, 1.125f).asInteractive();

    @Test
    void centerAndOffsetMapToPixels() {
        SurfaceHit center = HologramRenderer.pick(HOLO, new Vec3(0, 64, 5), new Vec3(0, 0, -1), 0f, session(), 6);
        assertNotNull(center);
        assertEquals(320, center.pixelX(), 1e-6);
        assertEquals(180, center.pixelY(), 1e-6);
        assertEquals(5, center.distance(), 1e-6);
        assertNull(center.masterPos());

        // 正面から見て右へ 0.5、上へ 0.28125 ブロック
        SurfaceHit offset = HologramRenderer.pick(HOLO, new Vec3(0.5, 64.28125, 5), new Vec3(0, 0, -1), 0f, session(), 6);
        assertEquals(480, offset.pixelX(), 1e-6);
        assertEquals(90, offset.pixelY(), 1e-6);
    }

    @Test
    void backSideOutsideAndOutOfReachMiss() {
        assertNull(HologramRenderer.pick(HOLO, new Vec3(0, 64, -5), new Vec3(0, 0, 1), 0f, session(), 6), "裏側");
        assertNull(HologramRenderer.pick(HOLO, new Vec3(1.5, 64, 5), new Vec3(0, 0, -1), 0f, session(), 6), "面の外");
        assertNull(HologramRenderer.pick(HOLO, new Vec3(0, 64, 9), new Vec3(0, 0, -1), 0f, session(), 6), "届かない");
    }

    @Test
    void yawRotatesTheFace() {
        // yaw 90 度: 描画は Y 軸回りに -90 度回すので、正面は -X 側を向く。-X 側から +X 向きに見て中心に当たる
        SurfaceHit hit = HologramRenderer.pick(HOLO.withYaw(90f).asInteractive(), new Vec3(-5, 64, 0), new Vec3(1, 0, 0), 0f, session(), 6);
        assertNotNull(hit);
        assertEquals(320, hit.pixelX(), 1e-6);
        assertNull(HologramRenderer.pick(HOLO.withYaw(90f).asInteractive(), new Vec3(0, 64, 5), new Vec3(0, 0, -1), 0f, session(), 6));
    }
}
