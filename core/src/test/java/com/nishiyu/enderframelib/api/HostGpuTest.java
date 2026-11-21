package com.nishiyu.enderframelib.api;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** A14: API 中立のホストデバイス指定 {@link HostGpu}。値の検証と、{@code gpu()} との食い違いの拒否。 */
class HostGpuTest {

    private static final String PAGE = "<body style=\"margin:0;background:#2040a0\"></body>";

    @Test
    void openGlCarriesTheWindowAndReportsGl() {
        HostGpu h = HostGpu.openGl(0x1234L);
        assertEquals(GpuApi.GL, h.api());
        assertEquals(0x1234L, ((HostGpu.OpenGl) h).window());
        assertEquals(HostGpu.openGl(0x1234L), h);
    }

    @Test
    void vulkanCarriesAllHandlesAndReportsVulkan() {
        HostGpu h = HostGpu.vulkan(1, 2, 3, 4, 5);
        assertEquals(GpuApi.VULKAN, h.api());
        var v = (HostGpu.Vulkan) h;
        assertEquals(1, v.instance());
        assertEquals(2, v.physicalDevice());
        assertEquals(3, v.device());
        assertEquals(4, v.queueFamily());
        assertEquals(5, v.queue());
    }

    @Test
    void zeroHandlesAndNegativeQueueFamilyAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> HostGpu.openGl(0));
        assertThrows(IllegalArgumentException.class, () -> HostGpu.vulkan(0, 2, 3, 0, 5));
        assertThrows(IllegalArgumentException.class, () -> HostGpu.vulkan(1, 0, 3, 0, 5));
        assertThrows(IllegalArgumentException.class, () -> HostGpu.vulkan(1, 2, 0, 0, 5));
        assertThrows(IllegalArgumentException.class, () -> HostGpu.vulkan(1, 2, 3, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> HostGpu.vulkan(1, 2, 3, -1, 5));
        assertDoesNotThrow(() -> HostGpu.vulkan(1, 2, 3, 0, 5), "queueFamily 0 は有効");
    }

    @Test
    void hostDeviceRejectsNull() {
        assertThrows(NullPointerException.class, () -> EnderEngine.html(PAGE).hostDevice((HostGpu) null));
    }

    @Test
    @SuppressWarnings("deprecation")
    void deprecatedLongOverloadStillValidates() {
        assertThrows(IllegalArgumentException.class, () -> EnderEngine.html(PAGE).hostDevice(0L));
        assertDoesNotThrow(() -> EnderEngine.html(PAGE).hostDevice(7L));
    }

    @Test
    void gpuApiThatContradictsTheHostIsRefusedInsteadOfFallingBack() {
        // ハンドルは偽物。食い違いはデバイスに触れる前に判定されるので、GPU の無い環境でも検査できる
        var vkHost = HostGpu.vulkan(1, 2, 3, 0, 5);
        var glHost = HostGpu.openGl(9);
        var e1 = assertThrows(IllegalStateException.class, () -> EnderEngine.html(PAGE)
                .target(EnderEngine.Target.GPU).gpu(GpuApi.GL).hostDevice(vkHost).build());
        assertTrue(e1.getMessage().contains("VULKAN") && e1.getMessage().contains("GL"), e1.getMessage());
        assertThrows(IllegalStateException.class, () -> EnderEngine.html(PAGE)
                .target(EnderEngine.Target.GPU).gpu(GpuApi.VULKAN).hostDevice(glHost).build());
        // GPU_OFFSCREEN は GL の別名なので、Vulkan のホストとは両立しない
        assertThrows(IllegalStateException.class, () -> EnderEngine.html(PAGE)
                .target(EnderEngine.Target.GPU_OFFSCREEN).hostDevice(vkHost).build());
    }
}
