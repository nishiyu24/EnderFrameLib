package com.nishiyu.enderframelib.core.paint.vulkan;

import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuTexture;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;

/**
 * {@code VK_KHR_swapchain} を用いたオンスクリーン提示（G3）。
 * GLFW へ直接依存しないよう {@code VkSurfaceKHR} ハンドルを受け取る設計とし、
 * Portable 層境界を維持する。スワップチェーン画像のフォーマットと現在サイズに応じて
 * オフスクリーン描画結果テクスチャを {@code vkCmdBlitImage} で転送・提示する。
 */
public final class VkSwapchain implements AutoCloseable {
    private static final long FENCE_TIMEOUT_NS = 5_000_000_000L;

    private final VkDevice device;
    private final VkContext ctx;
    private final long surface;

    private long swapchain = VK10.VK_NULL_HANDLE;
    private long[] images = new long[0];
    private long[] renderFinishedSems = new long[0];
    private long imageAvailableSem = VK10.VK_NULL_HANDLE;
    private long presentFence = VK10.VK_NULL_HANDLE;

    private int extentW;
    private int extentH;
    private int imageFormat;
    private int colorSpace;
    private boolean closed;

    public VkSwapchain(VkDevice device, long surface, int width, int height) {
        if (device == null) throw new IllegalArgumentException("device is null");
        if (surface == VK10.VK_NULL_HANDLE) throw new IllegalArgumentException("surface is null");
        this.device = device;
        this.ctx = device.context();
        this.surface = surface;
        if (!ctx.swapchainEnabled) {
            throw new IllegalStateException("VkContext が VK_KHR_swapchain 拡張なしで作成されている");
        }
        if (!ctx.supportsPresent(surface)) {
            throw new IllegalStateException("グラフィックスキューが指定 VkSurfaceKHR の提示に対応していない");
        }
        createSyncObjects();
        recreate(width, height);
    }

    public int width() { return extentW; }
    public int height() { return extentH; }
    public int imageCount() { return images.length; }
    public int imageFormat() { return imageFormat; }

    /**
     * オフスクリーンカラーターゲットをスワップチェーン画像へ転送し提示する。
     * リサイズや {@code VK_ERROR_OUT_OF_DATE_KHR} 発生時は自動再構築してリトライする。
     */
    public void present(GpuTexture colorTarget, int srcW, int srcH) {
        if (closed || !(colorTarget instanceof VkDevice.Texture srcTex)) return;
        if (extentW <= 0 || extentH <= 0) {
            recreate(srcW, srcH);
            if (extentW <= 0 || extentH <= 0) return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer imageIndex = stack.mallocInt(1);
            int acq = KHRSwapchain.vkAcquireNextImageKHR(
                    ctx.device, swapchain, FENCE_TIMEOUT_NS, imageAvailableSem, VK10.VK_NULL_HANDLE, imageIndex);
            if (acq == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR) {
                recreate(srcW, srcH);
                if (extentW <= 0 || extentH <= 0) return;
                acq = KHRSwapchain.vkAcquireNextImageKHR(
                        ctx.device, swapchain, FENCE_TIMEOUT_NS, imageAvailableSem, VK10.VK_NULL_HANDLE, imageIndex);
            }
            if (acq != VK10.VK_SUCCESS && acq != KHRSwapchain.VK_SUBOPTIMAL_KHR) {
                VkContext.vkCheck(acq, "vkAcquireNextImageKHR");
            }
            int idx = imageIndex.get(0);
            long signalSem = renderFinishedSems[idx];

            device.blitTextureToSwapchain(
                    srcTex, images[idx], srcW, srcH, extentW, extentH,
                    imageAvailableSem, signalSem, presentFence);

            VkPresentInfoKHR pi = VkPresentInfoKHR.calloc(stack)
                    .sType(KHRSwapchain.VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
                    .pWaitSemaphores(stack.longs(signalSem))
                    .swapchainCount(1)
                    .pSwapchains(stack.longs(swapchain))
                    .pImageIndices(stack.ints(idx));
            int pres = KHRSwapchain.vkQueuePresentKHR(ctx.graphicsQueue, pi);
            // blit 用コマンドプールが VkDevice 側リングと共有されるため、presentFence 完了を待機して再利用を安全化
            VkContext.vkCheck(VK10.vkWaitForFences(ctx.device, presentFence, true, FENCE_TIMEOUT_NS),
                    "vkWaitForFences(present)");
            if (pres == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR || pres == KHRSwapchain.VK_SUBOPTIMAL_KHR) {
                recreate(srcW, srcH);
            } else if (pres != VK10.VK_SUCCESS) {
                VkContext.vkCheck(pres, "vkQueuePresentKHR");
            }
        }
    }

    /** 指定サイズに合わせてスワップチェーンを再構築する。 */
    public void recreate(int reqWidth, int reqHeight) {
        if (closed) return;
        VK10.vkDeviceWaitIdle(ctx.device);
        destroyRenderFinishedSems();

        long oldSwapchain = swapchain;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.calloc(stack);
            VkContext.vkCheck(
                    KHRSurface.vkGetPhysicalDeviceSurfaceCapabilitiesKHR(ctx.physicalDevice, surface, caps),
                    "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");

            int w;
            int h;
            if (caps.currentExtent().width() != 0xFFFFFFFF) {
                w = caps.currentExtent().width();
                h = caps.currentExtent().height();
            } else {
                w = clamp(reqWidth, caps.minImageExtent().width(), caps.maxImageExtent().width());
                h = clamp(reqHeight, caps.minImageExtent().height(), caps.maxImageExtent().height());
            }
            if (w <= 0 || h <= 0) {
                if (oldSwapchain != VK10.VK_NULL_HANDLE) {
                    KHRSwapchain.vkDestroySwapchainKHR(ctx.device, oldSwapchain, null);
                    swapchain = VK10.VK_NULL_HANDLE;
                }
                extentW = 0;
                extentH = 0;
                images = new long[0];
                return;
            }

            pickSurfaceFormat(stack);
            int presentMode = pickPresentMode(stack);
            int minImages = caps.minImageCount() + 1;
            if (caps.maxImageCount() > 0 && minImages > caps.maxImageCount()) {
                minImages = caps.maxImageCount();
            }
            int usage = VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
            int supportedUsage = caps.supportedUsageFlags();
            if ((supportedUsage & VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT) == 0) {
                usage = VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
            }

            VkSwapchainCreateInfoKHR ci = VkSwapchainCreateInfoKHR.calloc(stack)
                    .sType(KHRSwapchain.VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
                    .surface(surface)
                    .minImageCount(minImages)
                    .imageFormat(imageFormat)
                    .imageColorSpace(colorSpace)
                    .imageExtent(e -> e.width(w).height(h))
                    .imageArrayLayers(1)
                    .imageUsage(usage)
                    .imageSharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
                    .preTransform(caps.currentTransform())
                    .compositeAlpha(pickCompositeAlpha(caps.supportedCompositeAlpha()))
                    .presentMode(presentMode)
                    .clipped(true)
                    .oldSwapchain(oldSwapchain);

            LongBuffer pSwap = stack.mallocLong(1);
            VkContext.vkCheck(KHRSwapchain.vkCreateSwapchainKHR(ctx.device, ci, null, pSwap), "vkCreateSwapchainKHR");
            swapchain = pSwap.get(0);
            extentW = w;
            extentH = h;

            if (oldSwapchain != VK10.VK_NULL_HANDLE) {
                KHRSwapchain.vkDestroySwapchainKHR(ctx.device, oldSwapchain, null);
            }

            IntBuffer count = stack.mallocInt(1);
            VkContext.vkCheck(KHRSwapchain.vkGetSwapchainImagesKHR(ctx.device, swapchain, count, null),
                    "vkGetSwapchainImagesKHR(count)");
            int imgCount = count.get(0);
            LongBuffer pImgs = stack.mallocLong(imgCount);
            VkContext.vkCheck(KHRSwapchain.vkGetSwapchainImagesKHR(ctx.device, swapchain, count, pImgs),
                    "vkGetSwapchainImagesKHR");
            images = new long[imgCount];
            renderFinishedSems = new long[imgCount];
            VkSemaphoreCreateInfo sci = VkSemaphoreCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
            LongBuffer pSem = stack.mallocLong(1);
            for (int i = 0; i < imgCount; i++) {
                images[i] = pImgs.get(i);
                VkContext.vkCheck(VK10.vkCreateSemaphore(ctx.device, sci, null, pSem), "vkCreateSemaphore(renderFinished)");
                renderFinishedSems[i] = pSem.get(0);
            }
        }
    }

    private void createSyncObjects() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreCreateInfo sci = VkSemaphoreCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
            LongBuffer pSem = stack.mallocLong(1);
            VkContext.vkCheck(VK10.vkCreateSemaphore(ctx.device, sci, null, pSem), "vkCreateSemaphore(imageAvailable)");
            imageAvailableSem = pSem.get(0);

            VkFenceCreateInfo fci = VkFenceCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                    .flags(VK10.VK_FENCE_CREATE_SIGNALED_BIT);
            LongBuffer pFence = stack.mallocLong(1);
            VkContext.vkCheck(VK10.vkCreateFence(ctx.device, fci, null, pFence), "vkCreateFence(present)");
            presentFence = pFence.get(0);
        }
    }

    private void pickSurfaceFormat(MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        VkContext.vkCheck(KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(ctx.physicalDevice, surface, count, null),
                "vkGetPhysicalDeviceSurfaceFormatsKHR(count)");
        int n = count.get(0);
        if (n == 0) throw new IllegalStateException("サーフェスフォーマットが 0 件");
        VkSurfaceFormatKHR.Buffer fmts = VkSurfaceFormatKHR.calloc(n, stack);
        VkContext.vkCheck(KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(ctx.physicalDevice, surface, count, fmts),
                "vkGetPhysicalDeviceSurfaceFormatsKHR");
        // オフスクリーンターゲットは線形 RGBA8_UNORM と同じ無変換提示（UNORM）を優先
        for (int i = 0; i < n; i++) {
            int f = fmts.get(i).format();
            int cs = fmts.get(i).colorSpace();
            if ((f == VK10.VK_FORMAT_B8G8R8A8_UNORM || f == VK10.VK_FORMAT_R8G8B8A8_UNORM)
                    && cs == KHRSurface.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                imageFormat = f;
                colorSpace = cs;
                return;
            }
        }
        imageFormat = fmts.get(0).format();
        colorSpace = fmts.get(0).colorSpace();
    }

    private int pickPresentMode(MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        VkContext.vkCheck(KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR(ctx.physicalDevice, surface, count, null),
                "vkGetPhysicalDeviceSurfacePresentModesKHR(count)");
        int n = count.get(0);
        if (n == 0) return KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
        IntBuffer modes = stack.mallocInt(n);
        VkContext.vkCheck(KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR(ctx.physicalDevice, surface, count, modes),
                "vkGetPhysicalDeviceSurfacePresentModesKHR");
        for (int i = 0; i < n; i++) {
            if (modes.get(i) == KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR) {
                return KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR;
            }
        }
        return KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
    }

    private static int pickCompositeAlpha(int mask) {
        int[] order = {
                KHRSurface.VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,
                KHRSurface.VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR,
                KHRSurface.VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR,
                KHRSurface.VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR
        };
        for (int bit : order) {
            if ((mask & bit) != 0) return bit;
        }
        return KHRSurface.VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void destroyRenderFinishedSems() {
        for (long sem : renderFinishedSems) {
            if (sem != VK10.VK_NULL_HANDLE) VK10.vkDestroySemaphore(ctx.device, sem, null);
        }
        renderFinishedSems = new long[0];
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        VK10.vkDeviceWaitIdle(ctx.device);
        destroyRenderFinishedSems();
        if (imageAvailableSem != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroySemaphore(ctx.device, imageAvailableSem, null);
            imageAvailableSem = VK10.VK_NULL_HANDLE;
        }
        if (presentFence != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyFence(ctx.device, presentFence, null);
            presentFence = VK10.VK_NULL_HANDLE;
        }
        if (swapchain != VK10.VK_NULL_HANDLE) {
            KHRSwapchain.vkDestroySwapchainKHR(ctx.device, swapchain, null);
            swapchain = VK10.VK_NULL_HANDLE;
        }
    }
}
