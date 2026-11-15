package com.nishiyu.enderframelib.core.paint.vulkan;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.SoftwareRendering;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRSurface;

/**
 * {@link VkSwapchain} の実サーフェス（非表示 GLFW 窓 + {@code VK_KHR_swapchain}）結合検証。
 * 描画 → 提示 → リサイズ再生成 → クリーン解放を通して検証レイヤエラー 0 件を確認する。
 */
class VkSwapchainTest {
    @Test
    void surfaceCreationRenderResizePresentAndDestroyAreValidationClean() {
        Assumptions.assumeTrue(GLFW.glfwInit(), "GLFW init 不可");
        long window = MemoryUtil.NULL;
        VkContext ctx = null;
        long surface = 0L;
        try {
            Assumptions.assumeTrue(GLFWVulkan.glfwVulkanSupported(), "GLFW Vulkan 非対応");
            PointerBuffer reqExts = GLFWVulkan.glfwGetRequiredInstanceExtensions();
            Assumptions.assumeTrue(reqExts != null && reqExts.remaining() > 0, "GLFW Vulkan surface 拡張なし");
            List<String> exts = new ArrayList<>();
            for (int i = 0; i < reqExts.remaining(); i++) {
                exts.add(MemoryUtil.memUTF8(reqExts.get(i)));
            }

            GLFW.glfwDefaultWindowHints();
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
            window = GLFW.glfwCreateWindow(320, 240, "VkSwapchainTest", MemoryUtil.NULL, MemoryUtil.NULL);
            Assumptions.assumeTrue(window != MemoryUtil.NULL, "GLFW_NO_API 窓生成不可");

            try {
                ctx = VkContext.createForSurface(true, exts);
            } catch (Throwable t) {
                Assumptions.abort("VkContext.createForSurface 不可: " + t);
                return;
            }

            try (MemoryStack stack = MemoryStack.stackPush()) {
                LongBuffer pSurf = stack.mallocLong(1);
                int err = GLFWVulkan.glfwCreateWindowSurface(ctx.instance, window, null, pSurf);
                assertEquals(0, err, "glfwCreateWindowSurface failed: " + err);
                surface = pSurf.get(0);
            }
            Assumptions.assumeTrue(ctx.supportsPresent(surface), "サーフェス提示非対応");

            final long winHandle = window;
            var callbacks = new VkSurfaceHost.WindowCallbacks(
                    () -> GLFW.glfwWindowShouldClose(winHandle),
                    GLFW::glfwPollEvents,
                    winHandle,
                    () -> {});

            String html = "<html><body style='margin:0;background:#1e293b'>"
                    + "<div style='width:200px;height:100px;margin:20px;border-radius:12px;background:#38bdf8'>Hello Vulkan Swapchain</div>"
                    + "</body></html>";

            try (VkRenderBackend vk = VkRenderBackend.forSurface(ctx, false, surface, 320, 240, 1f, callbacks)) {
                assertNotNull(vk.swapchain());
                assertTrue(vk.swapchain().imageCount() >= 2);
                assertEquals(320, vk.swapchain().width());
                assertEquals(240, vk.swapchain().height());

                SoftwareRendering.renderHtml(html, 320, 240, 1000L, vk);
                vk.present();

                GLFW.glfwSetWindowSize(window, 400, 300);
                GLFW.glfwPollEvents();
                vk.resize(400, 300);
                SoftwareRendering.renderHtml(html, 400, 300, 1016L, vk);
                vk.present();

                assertEquals(0, vk.validationErrorCount(), "Validation errors: " + vk.validationMessages());
                assertTrue(vk.validationMessages().isEmpty(), "Validation messages: " + vk.validationMessages());
            }

            assertEquals(0, ctx.validationErrorCount(), "Validation errors after close: " + ctx.validationMessages());
        } finally {
            if (ctx != null && surface != 0L) {
                KHRSurface.vkDestroySurfaceKHR(ctx.instance, surface, null);
            }
            if (ctx != null) ctx.close();
            if (window != MemoryUtil.NULL) GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }
}
