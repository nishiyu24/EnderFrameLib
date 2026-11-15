package com.nishiyu.enderframelib.core.paint.vulkan;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.LoadOp;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.TextureDesc;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuCaps;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuDevice;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuDeviceContract;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuFrame;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuPass;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuTexture;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * G1 契約 {@link GpuDeviceContract} の {@link VkDevice} 検証。
 * すべての契約テストで {@code VK_LAYER_KHRONOS_validation} のエラー／警告が 0 件であることも同時に保証する。
 */
class VkDeviceContractTest extends GpuDeviceContract {
    @Override
    protected GpuDevice open() {
        try {
            return VkDevice.createHeadless(true);
        } catch (Throwable t) {
            Assumptions.abort("Vulkan 1.1 を使えない: " + t);
            return null;
        }
    }

    @Override
    protected void closeDevice(GpuDevice d) {
        VkDevice vk = (VkDevice) d;
        List<String> msgs = vk.context().validationMessages();
        int errs = vk.validationErrorCount();
        vk.close();
        assertEquals(0, errs, "Vulkan validation errors: " + msgs);
        assertTrue(msgs.isEmpty(), "Vulkan validation messages: " + msgs);
    }

    @Test
    void capsReflectVulkanTopLeftZeroToOneAndValidationAndTimestamps() {
        VkDevice vk = (VkDevice) open();
        try {
            GpuCaps caps = vk.caps();
            assertFalse(caps.bottomUpFramebuffer(), "Vulkan フレームバッファは左上原点");
            assertTrue(caps.zeroToOneDepth(), "Vulkan 深度は [0, 1]");
            assertTrue(caps.persistentMap(), "Vulkan は 3 フレーム永続マップリングに対応");
            assertTrue(caps.maxTextureSize() >= 4096);

            vk.setTimingEnabled(true);
            try (GpuTexture rt = vk.createTexture(TextureDesc.rgba8(32, 32, true))) {
                GpuFrame f = vk.beginFrame();
                GpuPass p = f.beginPass(rt, LoadOp.CLEAR, 0xFF112233, null);
                p.end();
                f.end();
                vk.waitIdle();
            }
            if (caps.timestampQuery()) {
                assertTrue(vk.lastGpuMillis() >= 0.0, "timestampQuery 有効時は >= 0 の GPU 時間を返す");
            }
        } finally {
            closeDevice(vk);
        }
    }
}
