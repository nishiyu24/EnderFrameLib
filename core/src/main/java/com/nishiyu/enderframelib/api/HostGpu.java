package com.nishiyu.enderframelib.api;

import org.jetbrains.annotations.ApiStatus;

/**
 * ホストが既に持っている GPU デバイス（{@link EnderEngine.Builder#hostDevice(HostGpu)}）。エンジンは自前のデバイスを作らず、
 * これの上に描いて、結果を {@link WebViewSurface#texture()} でコピーなしに返す。ハンドルは描画 API のネイティブ値（{@code long}）で、
 * ホストが所有し続ける（エンジンは破棄しない。面を閉じた後も使える）。
 *
 * <p>共通の約束: ①エンジンの描画（{@link WebViewSurface#frame}・{@code close}）は、ホストの描画スレッドから呼ぶ。②結果のテクスチャは、
 * 次の {@code frame} までホストが読んでよい。</p>
 */
@ApiStatus.AvailableSince("1.0")
public sealed interface HostGpu permits HostGpu.OpenGl, HostGpu.Vulkan {

    /** このホストデバイスが属する描画 API（{@link GpuApi#GL} か {@link GpuApi#VULKAN}）。 */
    GpuApi api();

    /** OpenGL: ホストの GLFW ウィンドウ（{@code long}）。そのコンテキストと共有して描く。 */
    static HostGpu openGl(long glfwWindow) {
        return new OpenGl(glfwWindow);
    }

    /**
     * Vulkan: ホストの {@code VkInstance}・{@code VkPhysicalDevice}・{@code VkDevice} とグラフィックスキュー。
     * Vulkan 1.1 以上で、グラフィックス対応のキューファミリを指すこと。エンジンは追加の拡張・機能を要求しない。
     * キューはホストと共有するので、エンジンの描画中にホストが同じキューへ送らないこと（外部同期）。
     */
    static HostGpu vulkan(long instance, long physicalDevice, long device, int queueFamily, long queue) {
        return new Vulkan(instance, physicalDevice, device, queueFamily, queue);
    }

    /** {@link #openGl} の実体。 */
    record OpenGl(long window) implements HostGpu {
        public OpenGl {
            if (window == 0) throw new IllegalArgumentException("window handle must be non-zero");
        }

        @Override public GpuApi api() { return GpuApi.GL; }
    }

    /** {@link #vulkan} の実体。 */
    record Vulkan(long instance, long physicalDevice, long device, int queueFamily, long queue) implements HostGpu {
        public Vulkan {
            if (instance == 0) throw new IllegalArgumentException("instance handle must be non-zero");
            if (physicalDevice == 0) throw new IllegalArgumentException("physicalDevice handle must be non-zero");
            if (device == 0) throw new IllegalArgumentException("device handle must be non-zero");
            if (queue == 0) throw new IllegalArgumentException("queue handle must be non-zero");
            if (queueFamily < 0) throw new IllegalArgumentException("queueFamily must be >= 0");
        }

        @Override public GpuApi api() { return GpuApi.VULKAN; }
    }
}
