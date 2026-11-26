package com.nishiyu.enderframelib.core.paint.gpu;

import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuDevice;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuTexture;

/**
 * コンテキスト切替・ウィンドウ表示・共有フェンス・GPU タイマークエリなど、ウィンドウシステム／ドライバに紐づく処理の抽象。
 * GL/GLFW 実装は {@code core.paint.gl.GlContext} に閉じ込め、{@link GpuRenderBackend} はウィンドウも GL も持たない。
 */
public interface GpuSurfaceHost extends AutoCloseable {
    GpuDevice device();

    default void enter() {}

    default void exit() {}

    default boolean enableGpuTiming() { return false; }

    default double lastGpuMillis() { return -1; }

    default void beginTiming() {}

    default void endTiming() {}

    default void publishFrame() {}

    default void waitForRender() {}

    /** ホストのコンテキストが置いたフェンス（GL なら {@code GLsync}）を、このコンテキストの以後の命令に待たせる。対応しない実装は何もしない。 */
    default void waitForHost(long fence) {}

    default void finish() {}

    default void present(GpuTexture colorTarget, int width, int height) {}

    default boolean shouldClose() { return false; }

    default void pollEvents() {}

    default long windowHandle() { return 0L; }

    @Override
    default void close() {}
}
