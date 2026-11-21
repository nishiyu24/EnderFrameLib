package com.nishiyu.enderframelib.core.paint.gpu;

/**
 * ホストが所有する GPU デバイス（A14）。描画 API ごとのネイティブハンドルを、API 中立の 1 つの型で運ぶ。
 * GL はコンテキスト共有元のウィンドウ、Vulkan は instance・physicalDevice・device とグラフィックスキュー。
 * エンジンはこれを破棄しない。
 */
public record HostDevice(GpuApiKind api, long window, long instance, long physicalDevice, long device,
                         int queueFamily, long queue) {

    public static HostDevice openGl(long window) {
        return new HostDevice(GpuApiKind.GL, window, 0, 0, 0, 0, 0);
    }

    public static HostDevice vulkan(long instance, long physicalDevice, long device, int queueFamily, long queue) {
        return new HostDevice(GpuApiKind.VULKAN, 0, instance, physicalDevice, device, queueFamily, queue);
    }
}
