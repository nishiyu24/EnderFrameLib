package com.nishiyu.enderframelib.api;

import org.jetbrains.annotations.ApiStatus;

/** {@link WebViewSurface} の生存期間の通知。必要なものだけ上書きする。 */
@ApiStatus.AvailableSince("1.0")
public interface SurfaceLifecycleListener {

    /** 文書を（再）読み込みした直後（最初の読み込みは {@code addLifecycleListener} の前に終わっているので来ない）。 */
    default void onLoaded() {}

    /** {@link WebViewSurface#frame} が 1 回終わった。{@code drew} は描いたか。 */
    default void onFrame(long nowMs, boolean drew) {}

    /** 大きさが変わった（CSS px）。 */
    default void onResized(int width, int height) {}

    /** {@link WebViewSurface#close} が呼ばれた（資源を解放する直前）。 */
    default void onClosed() {}
}
