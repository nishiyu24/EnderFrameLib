package com.nishiyu.enderframelib.core.paint.gl;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.paint.software.SoftwareScene3DRasterizer;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.scene3d.MaterialDesc;
import com.nishiyu.enderframelib.core.scene3d.MeshHandle;
import com.nishiyu.enderframelib.core.scene3d.Scene3DWorld;
import com.nishiyu.enderframelib.core.scene3d.Texture3D;
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

    /** Bloom 有効のシーン: 発光する球と、鏡面のハイライトが 1 を超える金属球。 */
    private static Scene3DWorld bloomScene() {
        Scene3DWorld w = scene();
        MeshHandle lamp = w.geometry().createSphere(0.35f, 24, 32);
        MaterialDesc glow = w.materials().register(0xFF202020, 0f, 0.6f, 0xFFB040, 3f, null, false, false);
        int n = w.arena().allocateNode();
        w.arena().setPosition(n, -1.2f, 0.8f, 0.8f);
        w.arena().setMesh(n, lamp.meshId());
        w.arena().setMaterial(n, glow.materialId());
        w.bloom(true, 0.8f, 1.0f, 10f);
        return w;
    }

    @Test
    void bloomAgreesAcrossSoftwareGlAndVulkan() {
        int size = 160;
        Pixmap sw = SoftwareScene3DRasterizer.render(bloomScene(), size, size);
        Pixmap plain = SoftwareScene3DRasterizer.render(scene(), size, size);
        // ブルームは効いている: 発光球の周りの背景が、ブルーム無しより明るい
        long gain = 0;
        for (int i = 0; i < size * size; i++) {
            gain += Math.max(0, ((sw.pixels()[i] >> 16) & 0xFF) - ((plain.pixels()[i] >> 16) & 0xFF));
        }
        assertTrue(gain > 2000, "ブルームが効いていない: " + gain);

        int farGl = -1, farVk = -1;
        try (LwjglGlRenderBackend b = new LwjglGlRenderBackend(size, size, 1f)) {
            Pixmap gl = b.renderScene3DPixmap(bloomScene(), size, size);
            farGl = far(gl, sw, size);
        } catch (Throwable t) {
            System.out.println("[bloom parity] GL unavailable: " + t);
        }
        try (com.nishiyu.enderframelib.core.paint.vulkan.VkRenderBackend b =
                     new com.nishiyu.enderframelib.core.paint.vulkan.VkRenderBackend(size, size, 1f)) {
            Pixmap vk = b.renderScene3DPixmap(bloomScene(), size, size);
            farVk = far(vk, sw, size);
        } catch (Throwable t) {
            System.out.println("[bloom parity] Vulkan unavailable: " + t);
        }
        System.out.printf("[bloom parity] pixels differing by >4: gl=%d vulkan=%d of %d (bloom gain=%d)%n", farGl, farVk, size * size, gain);
        Assumptions.assumeTrue(farGl >= 0 || farVk >= 0, "GPU unavailable");
        if (farGl >= 0) assertTrue(farGl < size * size * 0.01, "GL とソフトウェア: " + farGl);
        if (farVk >= 0) assertTrue(farVk < size * size * 0.01, "Vulkan とソフトウェア: " + farVk);
    }

    private static int far(Pixmap a, Pixmap b, int size) {
        int far = 0;
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) if (maxDiff(a.get(x, y), b.get(x, y)) > 4) far++;
        return far;
    }

    /** 追加ライト（ポイント 2 灯 + スポット 1 灯）付きのシーン。 */
    private static Scene3DWorld multiLightScene() {
        Scene3DWorld w = scene();
        w.light().setColor(1f, 0.98f, 0.95f, 0.4f);
        w.light().addPoint(-2f, 1.5f, 2f, 1f, 0.3f, 0.2f, 6f, 7f);
        w.light().addPoint(2.2f, -1.2f, 2.5f, 0.2f, 0.5f, 1f, 5f, 6f);
        w.light().addSpot(0f, 3f, 3f, 0f, -0.8f, -0.6f, 1f, 1f, 0.8f, 14f, 9f, 12f, 28f);
        return w;
    }

    @Test
    void extraLightsAgreeAcrossSoftwareGlAndVulkan() {
        int size = 160;
        Pixmap sw = SoftwareScene3DRasterizer.render(multiLightScene(), size, size);
        Pixmap base = SoftwareScene3DRasterizer.render(scene(), size, size);
        int changed = 0;
        for (int i = 0; i < size * size; i++) if (maxDiff(sw.pixels()[i], base.pixels()[i]) > 8) changed++;
        assertTrue(changed > 1500, "追加ライトが絵を変えている: " + changed);

        int farGl = -1, farVk = -1;
        try (LwjglGlRenderBackend b = new LwjglGlRenderBackend(size, size, 1f)) {
            farGl = far(b.renderScene3DPixmap(multiLightScene(), size, size), sw, size);
        } catch (Throwable t) {
            System.out.println("[light parity] GL unavailable: " + t);
        }
        try (com.nishiyu.enderframelib.core.paint.vulkan.VkRenderBackend b =
                     new com.nishiyu.enderframelib.core.paint.vulkan.VkRenderBackend(size, size, 1f)) {
            farVk = far(b.renderScene3DPixmap(multiLightScene(), size, size), sw, size);
        } catch (Throwable t) {
            System.out.println("[light parity] Vulkan unavailable: " + t);
        }
        System.out.printf("[light parity] pixels differing by >4: gl=%d vulkan=%d of %d (changed vs single light=%d)%n",
                farGl, farVk, size * size, changed);
        Assumptions.assumeTrue(farGl >= 0 || farVk >= 0, "GPU unavailable");
        if (farGl >= 0) assertTrue(farGl < size * size * 0.01, "GL とソフトウェア: " + farGl);
        if (farVk >= 0) assertTrue(farVk < size * size * 0.01, "Vulkan とソフトウェア: " + farVk);
    }

    /** ベースカラー画像（8×8 の市松に色の傾斜）つきの立方体と、繰り返しの球、画像なしの金属球、ポイントライト。 */
    private static Scene3DWorld texturedScene() {
        Scene3DWorld w = new Scene3DWorld(1);
        w.clearColorArgb(0xFF182230);
        w.camera().lookAt(0, 0, 4.2f, 0, 0, 0, 0, 1, 0);
        w.camera().setPerspective((float) Math.toRadians(45), 1f, 0.1f, 50f);
        int[] px = new int[8 * 8];
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                boolean on = ((x ^ y) & 1) == 0;
                int r = on ? 230 : 40, g = 30 + x * 28, b = 30 + y * 28;
                px[y * 8 + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        Texture3D checker = new Texture3D(8, 8, px);
        MeshHandle cube = w.geometry().createBox(1.6f, 1.6f, 1.6f);
        MaterialDesc a = w.materials().register(0xFFFFFFFF, 0.1f, 0.5f, 0, 0f, null, false, false, checker, false);
        int c = w.arena().allocateNode();
        w.arena().setRotationEulerRadians(c, 0.35f, 0.65f, 0.15f);
        w.arena().setMesh(c, cube.meshId());
        w.arena().setMaterial(c, a.materialId());
        MeshHandle sphere = w.geometry().createSphere(0.6f, 16, 24);
        MaterialDesc b = w.materials().register(0xFFFFE0C0, 0f, 0.6f, 0, 0f, null, false, false, checker, true);
        int s = w.arena().allocateNode();
        w.arena().setPosition(s, 1.4f, -0.9f, 0.6f);
        w.arena().setMesh(s, sphere.meshId());
        w.arena().setMaterial(s, b.materialId());
        MaterialDesc plain = w.materials().register(0xFF8899AA, 0.8f, 0.3f, 0, 0f, null, false, false);
        int p = w.arena().allocateNode();
        w.arena().setPosition(p, -1.5f, -0.9f, 0.5f);
        w.arena().setMesh(p, sphere.meshId());
        w.arena().setMaterial(p, plain.materialId());
        w.light().addPoint(-2f, 2f, 2.5f, 1f, 0.8f, 0.6f, 5f, 8f);
        return w;
    }

    @Test
    void texturedMaterialsAgreeAcrossSoftwareGlAndVulkan() {
        int size = 160;
        Pixmap sw = SoftwareScene3DRasterizer.render(texturedScene(), size, size);
        // 画像が効いている: 市松の明暗が絵に出ている（ベタ塗りなら赤成分がどちらかに偏る）
        int bright = 0;
        for (int px : sw.pixels()) if (((px >> 16) & 0xFF) > 150) bright++;
        assertTrue(bright > 300, "市松の明るい升が出ている: " + bright);

        int farGl = -1, farVk = -1;
        try (LwjglGlRenderBackend b = new LwjglGlRenderBackend(size, size, 1f)) {
            farGl = far(b.renderScene3DPixmap(texturedScene(), size, size), sw, size);
        } catch (Throwable t) {
            System.out.println("[texture parity] GL unavailable: " + t);
        }
        try (com.nishiyu.enderframelib.core.paint.vulkan.VkRenderBackend b =
                     new com.nishiyu.enderframelib.core.paint.vulkan.VkRenderBackend(size, size, 1f)) {
            farVk = far(b.renderScene3DPixmap(texturedScene(), size, size), sw, size);
        } catch (Throwable t) {
            System.out.println("[texture parity] Vulkan unavailable: " + t);
        }
        System.out.printf("[texture parity] pixels differing by >4: gl=%d vulkan=%d of %d (bright=%d)%n",
                farGl, farVk, size * size, bright);
        Assumptions.assumeTrue(farGl >= 0 || farVk >= 0, "GPU unavailable");
        if (farGl >= 0) assertTrue(farGl < size * size * 0.01, "GL とソフトウェア: " + farGl);
        if (farVk >= 0) assertTrue(farVk < size * size * 0.01, "Vulkan とソフトウェア: " + farVk);
    }
}
