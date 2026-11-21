package com.nishiyu.enderframelib.core.paint.vulkan;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.api.EnderEngine;
import com.nishiyu.enderframelib.api.GpuApi;
import com.nishiyu.enderframelib.api.HostGpu;
import com.nishiyu.enderframelib.api.TextureHandle;
import com.nishiyu.enderframelib.api.TextureKind;
import com.nishiyu.enderframelib.api.WebViewSurface;
import java.util.Arrays;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VK10;

/**
 * A14: ホストが持つ Vulkan デバイスの上に描く。ホスト役は {@link VkContext}（エンジンの外で作った instance・device・queue）。
 * 結果がホストのデバイスのコピーなしのハンドルで返ること、自前デバイスと同じ画素になること、面を閉じてもホストのデバイスが壊れないことを見る。
 */
class VkHostDeviceTest {

    private static final String PAGE = "<body style=\"margin:0;background:#2040a0\">"
            + "<div style=\"width:40px;height:30px;background:#e0a020;border-radius:6px\"></div>"
            + "<p style=\"color:#fff;font:14px sans-serif\">host</p></body>";

    private static VkContext openHost() {
        try {
            return VkContext.createHeadless(true);
        } catch (Throwable t) {
            Assumptions.abort("Vulkan 1.1 を使えない: " + t);
            return null;
        }
    }

    private static HostGpu hostGpu(VkContext c) {
        return HostGpu.vulkan(c.instanceHandle(), c.physicalDeviceHandle(), c.deviceHandle(), c.queueFamily(), c.queueHandle());
    }

    private static int[] capture(EnderEngine.Builder b, int[] size) {
        int[][] out = new int[1][];
        try (WebViewSurface s = b.sink((px, w, h) -> {
            out[0] = Arrays.copyOf(px, w * h);
            size[0] = w;
            size[1] = h;
        }).build()) {
            s.frame(0, true);
        }
        assertNotNull(out[0], "sink が呼ばれていない");
        return out[0];
    }

    @Test
    void surfaceOnTheHostDeviceReturnsAVulkanImageWithoutPickingGl() {
        VkContext host = openHost();
        try {
            // AUTO でも、Vulkan のホストデバイスなら GL を先に試さず Vulkan が選ばれる
            try (WebViewSurface s = EnderEngine.html(PAGE).size(64, 48).devicePixelRatio(2f)
                    .target(EnderEngine.Target.GPU).hostDevice(hostGpu(host)).build()) {
                s.frame(0, true);
                TextureHandle t = s.texture().orElseThrow();
                assertEquals(TextureKind.VULKAN_IMAGE, t.kind());
                assertNotEquals(0L, t.handle());
                assertEquals(128, t.width());
                assertEquals(96, t.height());
            }
            assertEquals(0, host.validationErrorCount(), "検証レイヤ: " + host.validationMessages());
        } finally {
            host.close();
        }
    }

    @Test
    void pixelsMatchTheEnginesOwnDevice() {
        VkContext host = openHost();
        try {
            int[] sa = new int[2], sb = new int[2];
            int[] own = capture(EnderEngine.html(PAGE).size(64, 48).target(EnderEngine.Target.GPU).gpu(GpuApi.VULKAN), sa);
            int[] shared = capture(EnderEngine.html(PAGE).size(64, 48).target(EnderEngine.Target.GPU)
                    .gpu(GpuApi.VULKAN).hostDevice(hostGpu(host)), sb);
            assertArrayEquals(sa, sb);
            assertArrayEquals(own, shared, "ホストのデバイスでも自前のデバイスと同じ画素");
        } finally {
            host.close();
        }
    }

    @Test
    void closingTheSurfaceLeavesTheHostDeviceUsable() {
        VkContext host = openHost();
        try {
            for (int round = 0; round < 3; round++) { // 閉じたあと同じデバイスへ繰り返し作れる（ホストのハンドルが生きている）
                try (WebViewSurface s = EnderEngine.html(PAGE).size(32, 32).target(EnderEngine.Target.GPU)
                        .hostDevice(hostGpu(host)).build()) {
                    s.frame(round, true);
                    assertNotEquals(0L, s.texture().orElseThrow().handle());
                }
                assertEquals(VK10.VK_SUCCESS, VK10.vkDeviceWaitIdle(host.device), "round " + round + ": ホストのデバイスが壊れている");
            }
            assertEquals(0, host.validationErrorCount(), "検証レイヤ: " + host.validationMessages());
        } finally {
            host.close();
        }
    }

    @Test
    void twoSurfacesShareOneHostDevice() {
        VkContext host = openHost();
        try (WebViewSurface a = EnderEngine.html(PAGE).size(32, 32).target(EnderEngine.Target.GPU).hostDevice(hostGpu(host)).build();
             WebViewSurface b = EnderEngine.html(PAGE).size(48, 24).target(EnderEngine.Target.GPU).hostDevice(hostGpu(host)).build()) {
            a.frame(0, true);
            b.frame(0, true);
            TextureHandle ta = a.texture().orElseThrow();
            TextureHandle tb = b.texture().orElseThrow();
            assertNotEquals(ta.handle(), tb.handle(), "面ごとに別のテクスチャ");
            assertEquals(48, tb.width());
        } finally {
            host.close();
        }
    }

    @Test
    void adoptRejectsAQueueFamilyThatDoesNotExist() {
        VkContext host = openHost();
        try {
            assertThrows(IllegalArgumentException.class, () -> VkContext.adopt(host.instanceHandle(),
                    host.physicalDeviceHandle(), host.deviceHandle(), 9999, host.queueHandle()));
        } finally {
            host.close();
        }
    }

    @Test
    void adoptedContextExposesTheHostsHandlesAndCloseKeepsThemAlive() {
        VkContext host = openHost();
        try {
            VkContext c = VkContext.adopt(host.instanceHandle(), host.physicalDeviceHandle(), host.deviceHandle(),
                    host.queueFamily(), host.queueHandle());
            assertEquals(host.instanceHandle(), c.instanceHandle());
            assertEquals(host.deviceHandle(), c.deviceHandle());
            assertEquals(host.queueFamily(), c.queueFamily());
            c.close();
            assertEquals(VK10.VK_SUCCESS, VK10.vkDeviceWaitIdle(host.device), "借りたコンテキストの close はホストのデバイスを壊さない");
        } finally {
            host.close();
        }
    }
}
