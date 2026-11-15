package com.nishiyu.enderframelib.core.paint.gl;

import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuTexture;
import com.nishiyu.enderframelib.core.paint.gpu.Scene3DRenderer;
import com.nishiyu.enderframelib.core.scene3d.MaterialDesc;
import com.nishiyu.enderframelib.core.scene3d.MeshHandle;
import com.nishiyu.enderframelib.core.scene3d.Scene3DWorld;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import static org.junit.jupiter.api.Assertions.*;

class GlScene3DRendererTest {
    private static long window = MemoryUtil.NULL;
    private static String unavailable;

    @BeforeAll
    static void initGl() {
        try {
            if (!GLFW.glfwInit()) {
                unavailable = "glfwInit returned false";
                return;
            }
            GLFW.glfwDefaultWindowHints();
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            window = GLFW.glfwCreateWindow(64, 64, "GlScene3DRendererTest", MemoryUtil.NULL, MemoryUtil.NULL);
            if (window == MemoryUtil.NULL) {
                unavailable = "glfwCreateWindow returned NULL";
                return;
            }
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
        } catch (Throwable t) {
            unavailable = t.toString();
        }
    }

    @AfterAll
    static void shutdownGl() {
        if (window != MemoryUtil.NULL) {
            GLFW.glfwMakeContextCurrent(MemoryUtil.NULL);
            GLFW.glfwDestroyWindow(window);
            window = MemoryUtil.NULL;
        }
        GLFW.glfwTerminate();
    }

    private static void requireGl() {
        Assumptions.assumeTrue(window != MemoryUtil.NULL && unavailable == null, "OpenGL 3.2 unavailable: " + unavailable);
        GLFW.glfwMakeContextCurrent(window);
    }

    @Test
    void rendersOverThousandInstancesInSingleDrawCallWithTboRingAndSubDataFallback() {
        requireGl();
        Scene3DWorld world = new Scene3DWorld(1L).clearColorArgb(0xFF000000);
        MeshHandle quadBox = world.geometry().createBox(0.18f, 0.18f, 0.18f);
        MaterialDesc cyan = world.materials().registerColor(0xFF00DCE8);
        world.camera().lookAt(0, 0, 12.0f, 0, 0, 0, 0, 1, 0);
        world.camera().setPerspective((float) Math.toRadians(60.0), 1.0f, 0.1f, 100.0f);

        for (int r = 0; r < 32; r++) {
            for (int c = 0; c < 32; c++) {
                int n = world.arena().allocateNode();
                float x = (c - 15.5f) * 0.25f;
                float y = (r - 15.5f) * 0.25f;
                world.arena().setPosition(n, x, y, 0.0f);
                world.arena().setMesh(n, quadBox.meshId());
                world.arena().setMaterial(n, cyan.materialId());
            }
        }

        try (GlDevice persistentDev = new GlDevice(true, 3);
             GlDevice subDataDev = new GlDevice(false, 1);
             Scene3DRenderer persistentRenderer = new Scene3DRenderer(persistentDev);
             Scene3DRenderer subDataRenderer = new Scene3DRenderer(subDataDev)) {
            GpuTexture tex1 = persistentRenderer.render(world, 256, 256);
            assertNotNull(tex1);
            assertTrue(persistentDev.nativeHandle(tex1) > 0);
            assertEquals(1, persistentRenderer.lastDrawCalls());
            assertEquals(1024, persistentRenderer.lastInstanceCount());

            GpuTexture tex2 = subDataRenderer.render(world, 256, 256);
            assertNotNull(tex2);
            assertTrue(subDataDev.nativeHandle(tex2) > 0);
            assertEquals(1, subDataRenderer.lastDrawCalls());
            assertEquals(1024, subDataRenderer.lastInstanceCount());

            int[] px1 = persistentRenderer.readTexturePixelsArgb(tex1, 256, 256);
            int[] px2 = subDataRenderer.readTexturePixelsArgb(tex2, 256, 256);
            int nonBlack1 = countBrightPixels(px1);
            int nonBlack2 = countBrightPixels(px2);
            assertTrue(nonBlack1 > 5000, "Expected shaded instanced grid pixels, got " + nonBlack1);
            assertEquals(nonBlack1, nonBlack2, 32, "Persistent ring and subData fallback should match");
        }
    }

    @Test
    void preservesDepthOrderUprightOrientationAndReusesFbo() {
        requireGl();
        Scene3DWorld world = new Scene3DWorld(2L).clearColorArgb(0x00000000);
        MeshHandle box = world.geometry().createBox(1.2f, 0.7f, 0.4f);
        MaterialDesc redTop = world.materials().register(0xFFE62020, 0f, 0.6f, 0, 0f, null, false, true);
        MaterialDesc greenBottom = world.materials().register(0xFF20E640, 0f, 0.6f, 0, 0f, null, false, true);
        MaterialDesc blueBack = world.materials().register(0xFF2040F0, 0f, 0.6f, 0, 0f, null, false, true);

        world.camera().lookAt(0, 0, 4.0f, 0, 0, 0, 0, 1, 0);
        world.camera().setPerspective((float) Math.toRadians(45.0), 1.0f, 0.1f, 50.0f);

        int topNode = world.arena().allocateNode();
        world.arena().setPosition(topNode, 0f, 0.65f, 0f);
        world.arena().setMesh(topNode, box.meshId());
        world.arena().setMaterial(topNode, redTop.materialId());

        int bottomNode = world.arena().allocateNode();
        world.arena().setPosition(bottomNode, 0f, -0.65f, 0f);
        world.arena().setMesh(bottomNode, box.meshId());
        world.arena().setMaterial(bottomNode, greenBottom.materialId());

        // 手前の +Z 位置の青箱と背後のさらに奥の赤箱を重ならせるのではなく、中央奥に青、さらに手前に緑を置く
        int backCenter = world.arena().allocateNode();
        world.arena().setPosition(backCenter, 0f, 0f, -1.0f);
        world.arena().setScale(backCenter, 1.0f, 0.5f, 1.0f);
        world.arena().setMesh(backCenter, box.meshId());
        world.arena().setMaterial(backCenter, blueBack.materialId());

        int frontCenter = world.arena().allocateNode();
        world.arena().setPosition(frontCenter, 0f, 0f, 1.0f);
        world.arena().setScale(frontCenter, 0.5f, 0.5f, 1.0f);
        world.arena().setMesh(frontCenter, box.meshId());
        world.arena().setMaterial(frontCenter, greenBottom.materialId());

        try (GlDevice dev = new GlDevice();
             Scene3DRenderer renderer = new Scene3DRenderer(dev)) {
            GpuTexture tex = renderer.render(world, 128, 128);
            assertEquals(1, renderer.fboReallocations());

            // 同じサイズで複数フレーム描画しても FBO は再生成されない
            renderer.render(world, 128, 128);
            renderer.render(world, 128, 128);
            assertEquals(1, renderer.fboReallocations());

            int[] pixels = renderer.readTexturePixelsArgb(tex, 128, 128);
            // top-down 行配列で y=36 (上部=+Y) が赤、y=92 (下部=-Y) が緑、中央(64,64) は手前の緑が奥の青を遮蔽
            int topPx = pixels[36 * 128 + 64];
            int bottomPx = pixels[92 * 128 + 64];
            int centerPx = pixels[64 * 128 + 64];
            assertRedDominant(topPx, "Top (+Y) must be red");
            assertGreenDominant(bottomPx, "Bottom (-Y) must be green");
            assertGreenDominant(centerPx, "Front green must occlude back blue");

            // サイズ変更時だけ 1 回だけ FBO を作り直す
            renderer.render(world, 160, 120);
            assertEquals(2, renderer.fboReallocations());
            renderer.render(world, 160, 120);
            assertEquals(2, renderer.fboReallocations());
        }
    }

    private static int countBrightPixels(int[] argb) {
        int count = 0;
        for (int c : argb) {
            int r = (c >>> 16) & 0xFF;
            int g = (c >>> 8) & 0xFF;
            int b = c & 0xFF;
            if (r + g + b > 30) count++;
        }
        return count;
    }

    private static void assertRedDominant(int argb, String msg) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        assertTrue(a > 200 && r > g + 80 && r > b + 80, msg + " (got 0x" + Integer.toHexString(argb) + ")");
    }

    private static void assertGreenDominant(int argb, String msg) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        assertTrue(a > 200 && g > r + 80 && g > b + 80, msg + " (got 0x" + Integer.toHexString(argb) + ")");
    }
}
