package com.nishiyu.enderframelib.core.paint.gl;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.paint.software.SoftwareScene3DRasterizer;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.scene3d.MaterialDesc;
import com.nishiyu.enderframelib.core.scene3d.MeshHandle;
import com.nishiyu.enderframelib.core.scene3d.Scene3DWorld;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** X3-2: 同じシーンを GL とソフトウェアで描き、画素がほぼ一致する（各チャンネルの差は最大でも数段階。縁と量子化の差だけを許す）。 */
class Scene3DSoftwareParityTest {

    private static Scene3DWorld scene() {
        Scene3DWorld w = new Scene3DWorld(1);
        w.clearColorArgb(0xFF182230);
        w.camera().lookAt(0, 0, 4.2f, 0, 0, 0, 0, 1, 0);
        w.camera().setPerspective((float) Math.toRadians(45), 1f, 0.1f, 50f);
        MeshHandle cube = w.geometry().createBox(1.5f, 1.5f, 1.5f);
        MaterialDesc amber = w.materials().register(0xFFF5A623, 0.1f, 0.45f, 0, 0f, null, false, false);
        int c = w.arena().allocateNode();
        w.arena().setRotationEulerRadians(c, 0.35f, 0.65f, 0.15f);
        w.arena().setMesh(c, cube.meshId());
        w.arena().setMaterial(c, amber.materialId());
        MeshHandle sphere = w.geometry().createSphere(0.5f, 16, 24);
        MaterialDesc steel = w.materials().register(0xFF8899AA, 0.9f, 0.3f, 0, 0f, null, false, false);
        int s = w.arena().allocateNode();
        w.arena().setPosition(s, 1.2f, -0.9f, 0.6f);
        // 非一様スケール + 回転: 法線は逆行列の転置で変換しないと陰影がずれる（InstancedBatcher の法線行列の回帰）
        w.arena().setScale(s, 1.6f, 0.7f, 1f);
        w.arena().setRotationEulerRadians(s, 0.4f, 0f, 0.5f);
        w.arena().setMesh(s, sphere.meshId());
        w.arena().setMaterial(s, steel.materialId());
        return w;
    }

    @Test
    void softwareAndGlAgreeOnTheSameScene() {
        int size = 160;
        Pixmap gl;
        try (LwjglGlRenderBackend backend = new LwjglGlRenderBackend(size, size, 1f)) {
            gl = backend.renderScene3DPixmap(scene(), size, size);
        } catch (Throwable t) {
            Assumptions.abort("GL unavailable: " + t);
            return;
        }
        assertNotNull(gl);
        Pixmap sw = SoftwareScene3DRasterizer.render(scene(), size, size);

        int far = 0, total = 0, sum = 0, max = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int a = gl.get(x, y), b = sw.get(x, y);
                int d = 0;
                for (int sh = 0; sh <= 24; sh += 8) d = Math.max(d, Math.abs(((a >>> sh) & 0xFF) - ((b >>> sh) & 0xFF)));
                total++;
                sum += d;
                max = Math.max(max, d);
                if (d > 2) far++;
            }
        }
        double meanErr = sum / (double) total;
        System.out.printf("[scene3d parity] mean max-channel diff=%.3f max=%d pixels>2: %d of %d%n", meanErr, max, far, total);
        assertTrue(meanErr < 0.5, "平均差 " + meanErr);
        assertTrue(far < total * 0.005, "2 を超える画素が 0.5% 以上: " + far);
        assertTrue(max <= 24, "最大差 " + max);
    }

    @Test
    void vulkanAgreesWithSoftwareAndGl() {
        int size = 160;
        Pixmap vk, gl;
        try (com.nishiyu.enderframelib.core.paint.vulkan.VkRenderBackend backend =
                     new com.nishiyu.enderframelib.core.paint.vulkan.VkRenderBackend(size, size, 1f)) {
            vk = backend.renderScene3DPixmap(scene(), size, size);
        } catch (Throwable t) {
            Assumptions.abort("Vulkan unavailable: " + t);
            return;
        }
        try (LwjglGlRenderBackend backend = new LwjglGlRenderBackend(size, size, 1f)) {
            gl = backend.renderScene3DPixmap(scene(), size, size);
        } catch (Throwable t) {
            Assumptions.abort("GL unavailable: " + t);
            return;
        }
        Pixmap sw = SoftwareScene3DRasterizer.render(scene(), size, size);
        assertNotNull(vk);
        int farGl = 0, farSw = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                farGl += maxDiff(vk.get(x, y), gl.get(x, y)) > 2 ? 1 : 0;
                farSw += maxDiff(vk.get(x, y), sw.get(x, y)) > 2 ? 1 : 0;
            }
        }
        System.out.printf("[scene3d parity] vulkan vs gl >2: %d, vulkan vs software >2: %d of %d%n", farGl, farSw, size * size);
        assertTrue(farGl < size * size * 0.005, "Vulkan と GL: " + farGl);
        assertTrue(farSw < size * size * 0.005, "Vulkan とソフトウェア: " + farSw);
    }

    private static int maxDiff(int a, int b) {
        int d = 0;
        for (int sh = 0; sh <= 24; sh += 8) d = Math.max(d, Math.abs(((a >>> sh) & 0xFF) - ((b >>> sh) & 0xFF)));
        return d;
    }
}
