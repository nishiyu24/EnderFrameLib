package com.nishiyu.enderframelib.core.paint.vulkan;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.LoadOp;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.TextureDesc;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.kernel.gpu.GpuSceneSync;
import com.nishiyu.enderframelib.core.kernel.gpu.SimdTileSoftwareRenderer;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuDevice;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuFrame;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuPass;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuTexture;
import com.nishiyu.enderframelib.core.paint.gl.LwjglGlRenderBackend;
import com.nishiyu.enderframelib.core.paint.gpu.GpuTreeStore;
import com.nishiyu.enderframelib.core.paint.gpu.RectRenderer;
import com.nishiyu.enderframelib.core.paint.gpu.Scene3DRenderer;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.scene3d.MaterialDesc;
import com.nishiyu.enderframelib.core.scene3d.MeshHandle;
import com.nishiyu.enderframelib.core.scene3d.Scene3DWorld;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * {@link VkDevice} 上での {@link RectRenderer}（GPU 図形木）および {@link Scene3DRenderer}（3D 回転キューブ＋深度）の検証。
 * {@code VK_LAYER_KHRONOS_validation} エラー 0 件と GL / CPU とのピクセル一致を確認する。
 */
class VkDeviceAndRendererTest {
    private static final int W = 200, H = 150;

    private static VkDevice openVk() {
        try {
            return VkDevice.createHeadless(true);
        } catch (Throwable t) {
            Assumptions.abort("Vulkan 1.1 を使えない: " + t);
            return null;
        }
    }

    private static void closeAndAssertClean(VkDevice vk) {
        List<String> msgs = vk.context().validationMessages();
        int errs = vk.validationErrorCount();
        vk.close();
        assertEquals(0, errs, "Vulkan validation errors: " + msgs);
        assertTrue(msgs.isEmpty(), "Vulkan validation messages: " + msgs);
    }

    private static DisplayItem item(String id, int x, int y, int w, int h, int argb, int radius, float opacity,
                                    float tx, float ty, float sx, float sy, float rot) {
        return new DisplayItem(id, id, ElementType.RECT, x, y, w, h, null, 0, argb, null, radius, opacity,
                tx, ty, sx, sy, rot, 0, null, null, null);
    }

    private static int[] drawRectsVk(VkDevice dev, GpuTreeStore st, RectRenderer rr, GpuTexture target, GpuTexture dummy, int count) {
        st.flush();
        GpuFrame f = dev.beginFrame();
        GpuPass p = f.beginPass(target, LoadOp.CLEAR, 0, null);
        rr.draw(p, st, dummy, dummy, 0, count, 0f, W, H, true);
        p.end();
        ByteBuffer rgba = ByteBuffer.allocateDirect(W * H * 4);
        f.readback(target, new int[]{0, 0, W, H}, rgba);
        f.end();
        int[] out = new int[W * H];
        for (int i = 0; i < out.length; i++) {
            int r = rgba.get(i * 4) & 0xFF;
            int g = rgba.get(i * 4 + 1) & 0xFF;
            int b = rgba.get(i * 4 + 2) & 0xFF;
            int a = rgba.get(i * 4 + 3) & 0xFF;
            out[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        return out;
    }

    private static void assertClose(int[] expect, int[] actual, int tol, String msg) {
        int bad = 0;
        String first = null;
        for (int i = 0; i < expect.length; i++) {
            for (int sh = 0; sh < 32; sh += 8) {
                int a = (expect[i] >>> sh) & 0xFF;
                int b = (actual[i] >>> sh) & 0xFF;
                if (Math.abs(a - b) > tol) {
                    bad++;
                    if (first == null) {
                        first = "pixel(" + (i % W) + "," + (i / W) + ") ch" + sh + " expect=" + a + " actual=" + b;
                    }
                }
            }
        }
        assertEquals(0, bad, msg + ": " + first);
    }

    @Test
    void rectRendererRoundedRectsAndMutationsMatchCpu() {
        VkDevice dev = openVk();
        try (GpuTreeStore st = new GpuTreeStore(dev, 256, 16, 256, 256);
             RectRenderer rr = new RectRenderer(dev);
             GpuTexture target = dev.createTexture(TextureDesc.rgba8(W, H, true));
             GpuTexture dummy = dev.createTexture(TextureDesc.rgba8(1, 1, false))) {
            GpuSceneSync sync = new GpuSceneSync(st.trees);
            Random r = new Random(42);
            List<DisplayItem> items = new ArrayList<>();
            SimdTileSoftwareRenderer cpu = new SimdTileSoftwareRenderer(W, H);
            for (int i = 0; i < 70; i++) {
                int x = r.nextInt(W + 20) - 10;
                int y = r.nextInt(H + 20) - 10;
                int w = 4 + r.nextInt(90);
                int h = 4 + r.nextInt(70);
                int rad = r.nextInt(3) == 0 ? 0 : r.nextInt(Math.min(w, h) / 2 + 1);
                int argb = r.nextInt() | (r.nextInt(3) == 0 ? 0xFF000000 : 0);
                items.add(item("r" + i, x, y, w, h, argb, rad, 1f, 0, 0, 1, 1, 0));
                cpu.fillRoundRect(x, y, w, h, rad, argb, 1f);
            }
            cpu.render();
            int n = sync.sync(items);
            assertEquals(70, n);
            int[] vkPx = drawRectsVk(dev, st, rr, target, dummy, n);
            assertClose(cpu.pixels(), vkPx, 2, "Vulkan RectRenderer vs CPU 70 rounded rects");

            // 更新・並べ替え・削除
            List<DisplayItem> pair = new ArrayList<>(List.of(
                    item("a", 20, 20, 80, 60, 0xFFFF0000, 6, 1f, 0, 0, 1, 1, 0),
                    item("b", 50, 40, 80, 60, 0xFF0000FF, 0, 0.5f, 25, 10, 1, 1, 0)));
            n = sync.sync(pair);
            SimdTileSoftwareRenderer cpu2 = new SimdTileSoftwareRenderer(W, H);
            cpu2.fillRoundRect(20, 20, 80, 60, 6, 0xFFFF0000, 1f);
            cpu2.fillRoundRect(75, 50, 80, 60, 0, 0xFF0000FF, 0.5f);
            cpu2.render();
            assertClose(cpu2.pixels(), drawRectsVk(dev, st, rr, target, dummy, n), 2, "Vulkan RectRenderer 移動・半透明");
        } finally {
            closeAndAssertClean(dev);
        }
    }

    @Test
    void rotatingCubeAndDepthOrderMatchGlBackendPixelWise() {
        VkDevice vkDev = openVk();
        Scene3DWorld world = new Scene3DWorld(77L).clearColorArgb(0xFF182230);
        MeshHandle cube = world.geometry().createBox(1.5f, 1.5f, 1.5f);
        MaterialDesc amber = world.materials().register(0xFFF5A623, 0.1f, 0.45f, 0, 0f, null, false, false);
        int centerCube = world.arena().allocateNode();
        world.arena().setRotationEulerRadians(centerCube, 0.35f, 0.65f, 0.15f);
        world.arena().setMesh(centerCube, cube.meshId());
        world.arena().setMaterial(centerCube, amber.materialId());

        // +Y 赤（上）、-Y 青（下）、z=-1 奥、z=+1 手前緑の遮蔽検証
        MeshHandle bar = world.geometry().createBox(1.2f, 0.45f, 0.2f);
        MaterialDesc redTop = world.materials().register(0xFFEF233C, 0f, 0.5f, 0, 0f, null, false, true);
        MaterialDesc blueBottom = world.materials().register(0xFF2B6CB0, 0f, 0.5f, 0, 0f, null, false, true);
        int topMarker = world.arena().allocateNode();
        world.arena().setPosition(topMarker, 0f, 1.15f, 0.5f);
        world.arena().setMesh(topMarker, bar.meshId());
        world.arena().setMaterial(topMarker, redTop.materialId());
        int bottomMarker = world.arena().allocateNode();
        world.arena().setPosition(bottomMarker, 0f, -1.15f, 0.5f);
        world.arena().setMesh(bottomMarker, bar.meshId());
        world.arena().setMaterial(bottomMarker, blueBottom.materialId());

        world.camera().lookAt(0, 0, 4.2f, 0, 0, 0, 0, 1, 0);
        world.camera().setPerspective((float) Math.toRadians(45.0), 1.0f, 0.1f, 50.0f);

        int size = 160;
        int[] vkPixels;
        try (Scene3DRenderer vk3d = new Scene3DRenderer(vkDev)) {
            GpuTexture tex = vk3d.render(world, size, size);
            assertNotNull(tex);
            assertEquals(3, vk3d.lastInstanceCount());
            assertTrue(vk3d.lastDrawCalls() >= 1);
            vkPixels = vk3d.readTexturePixelsArgb(tex, size, size);
        } finally {
            closeAndAssertClean(vkDev);
        }

        // 上下向き（上=+Y 赤, 下=-Y 青）と回転キューブ本体を確認
        int topPx = vkPixels[24 * size + 80];
        int bottomPx = vkPixels[136 * size + 80];
        assertTrue(((topPx >>> 16) & 0xFF) > (topPx & 0xFF) + 80,
                "Vulkan 3D 上 (+Y) は赤であるべき: 0x" + Integer.toHexString(topPx));
        assertTrue((bottomPx & 0xFF) > ((bottomPx >>> 16) & 0xFF) + 60,
                "Vulkan 3D 下 (-Y) は青であるべき: 0x" + Integer.toHexString(bottomPx));
        assertNotEquals(0xFF182230, vkPixels[80 * size + 80], "回転キューブ中心は背景色ではない");

        // GL 版 Scene3DRenderer とピクセル突合（GL 利用可能時）
        LwjglGlRenderBackend gl = null;
        try {
            gl = new LwjglGlRenderBackend(size, size, 1f);
        } catch (Throwable t) {
            Assumptions.abort("OpenGL 3.2 を使えないため GL<->Vulkan 突合をスキップ: " + t);
        }
        try (LwjglGlRenderBackend glBackend = gl) {
            int[] glPixels = glBackend.renderScene3DPixmap(world, size, size).pixels();
            int maxDiff = 0;
            int diffOver4 = 0;
            for (int i = 0; i < vkPixels.length; i++) {
                int a = glPixels[i], b = vkPixels[i];
                for (int sh = 0; sh < 32; sh += 8) {
                    int d = Math.abs(((a >>> sh) & 0xFF) - ((b >>> sh) & 0xFF));
                    if (d > maxDiff) maxDiff = d;
                }
                if (a != b) {
                    int mr = Math.abs(((a >>> 16) & 0xFF) - ((b >>> 16) & 0xFF));
                    int mg = Math.abs(((a >>> 8) & 0xFF) - ((b >>> 8) & 0xFF));
                    int mb = Math.abs((a & 0xFF) - (b & 0xFF));
                    int ma = Math.abs(((a >>> 24) & 0xFF) - ((b >>> 24) & 0xFF));
                    if (Math.max(Math.max(mr, mg), Math.max(mb, ma)) > 8) diffOver4++;
                }
            }
            System.out.printf("G3 3D rotating cube GL<->Vulkan: maxDiff=%d, pixels(>8)=%d%n", maxDiff, diffOver4);
            assertTrue(maxDiff <= 4 && diffOver4 == 0,
                    "GL と Vulkan の 3D 回転キューブが乖離: pixels(>8)=" + diffOver4 + ", maxDiff=" + maxDiff);
        }
    }
}
