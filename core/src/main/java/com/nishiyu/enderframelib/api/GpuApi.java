package com.nishiyu.enderframelib.api;

import org.jetbrains.annotations.ApiStatus;

/** GPU 描画で使うグラフィックス API の選び方（{@link EnderEngine.Builder#gpu}）。実装は別モジュール（{@code core-gl} / {@code core-vulkan}）が提供する。 */
@ApiStatus.AvailableSince("1.0")
public enum GpuApi {
    /** 見つかった実装のうち、優先度の高いものから試し、作れた最初のもの（GL → Vulkan）。 */
    AUTO,
    /** OpenGL 3.2 Core（{@code core-gl}）。 */
    GL,
    /** Vulkan 1.1（{@code core-vulkan}）。 */
    VULKAN
}
