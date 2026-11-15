package com.nishiyu.enderframelib.core.paint.vulkan;

import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuDevice;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuTexture;
import com.nishiyu.enderframelib.core.paint.gpu.GpuSurfaceHost;
import java.util.function.BooleanSupplier;

/**
 * {@link VkDevice}（および任意で {@link VkSwapchain}）を {@link GpuSurfaceHost} へ適合させるホスト実装。
 * ヘッドレス時はスワップチェーンを作らずオフスクリーン描画・GPU タイムスタンプ計測のみを行い、
 * {@code VkSurfaceKHR} が渡された場合は {@link #present} でウィンドウへ転送する。
 */
public final class VkSurfaceHost implements GpuSurfaceHost {
    /** 任意のウィンドウシステム連携（GLFW 依存をホスト側へ持ち込まないためのコールバック）。 */
    public record WindowCallbacks(
            BooleanSupplier shouldClose,
            Runnable pollEvents,
            long windowHandle,
            Runnable onClose) {
        public static final WindowCallbacks NONE = new WindowCallbacks(() -> false, () -> {}, 0L, () -> {});
    }

    private final VkDevice device;
    private final VkSwapchain swapchain;
    private final WindowCallbacks callbacks;
    private boolean timingEnabled;
    private boolean closed;

    /** ヘッドレス用ホストを構築する。 */
    public VkSurfaceHost(boolean validation) {
        this.device = VkDevice.createHeadless(validation);
        this.swapchain = null;
        this.callbacks = WindowCallbacks.NONE;
    }

    /** 既存 {@link VkContext} 上でヘッドレスまたはスワップチェーン付きホストを構築する。 */
    public VkSurfaceHost(VkContext ctx, boolean ownsContext, long surface, int widthPx, int heightPx,
                         WindowCallbacks callbacks) {
        this.device = new VkDevice(ctx, ownsContext);
        this.swapchain = surface != 0L ? new VkSwapchain(device, surface, widthPx, heightPx) : null;
        this.callbacks = callbacks != null ? callbacks : WindowCallbacks.NONE;
    }

    public VkDevice vkDevice() {
        return device;
    }

    public VkSwapchain swapchain() {
        return swapchain;
    }

    public int validationErrorCount() {
        return device.validationErrorCount();
    }

    @Override
    public GpuDevice device() {
        return device;
    }

    @Override
    public boolean enableGpuTiming() {
        timingEnabled = true;
        device.setTimingEnabled(true);
        return true;
    }

    @Override
    public double lastGpuMillis() {
        return device.lastGpuMillis();
    }

    @Override
    public void beginTiming() {
        if (timingEnabled) device.setTimingEnabled(true);
    }

    @Override
    public void endTiming() {
        // Vulkan 側タイムスタンプは VkDevice.submitFrame()/flushFrame() で取得・計算される
    }

    @Override
    public void waitForRender() {
        device.waitIdle();
    }

    @Override
    public void finish() {
        device.waitIdle();
    }

    @Override
    public void present(GpuTexture colorTarget, int width, int height) {
        if (swapchain != null && colorTarget != null) {
            swapchain.present(colorTarget, width, height);
        }
    }

    @Override
    public boolean shouldClose() {
        return callbacks.shouldClose().getAsBoolean();
    }

    @Override
    public void pollEvents() {
        callbacks.pollEvents().run();
    }

    @Override
    public long windowHandle() {
        return callbacks.windowHandle();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (swapchain != null) swapchain.close();
        device.close();
        callbacks.onClose().run();
    }
}
