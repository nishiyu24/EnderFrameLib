package com.nishiyu.enderframelib.core.paint.vulkan;

import com.nishiyu.enderframelib.core.paint.gpu.CpuFallback;
import com.nishiyu.enderframelib.core.paint.port.GpuSink;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.PaintOffsets;
import com.nishiyu.enderframelib.core.paint.resource.PaintResources;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import java.util.List;

/**
 * {@link VkRenderBackend} が {@code core.paint.gl} へ依存せず {@link SoftwareRenderBackend} を
 * GPU 保険層として利用するための {@link CpuFallback} アダプタ。
 */
final class VkCpuFallback implements CpuFallback {
    private final SoftwareRenderBackend raster;

    VkCpuFallback(int cssW, int cssH, float dpr, String sessionId, PaintResources resources) {
        this.raster = new SoftwareRenderBackend(cssW, cssH, dpr, sessionId, resources);
    }

    /** 表示リストを走査し、GPU が受け取らなかった図形だけを CPU の層へ描く（受け取れたものは {@code sink} へ送る）。 */
    @Override
    public void renderOnto(List<DisplayItem> items, PaintOffsets offsets, com.nishiyu.enderframelib.core.paint.SceneSource source, long nowMs,
                           GpuSink sink) {
        raster.renderOnto(items, offsets, source, nowMs, sink);
    }

    @Override
    public boolean needsRepaint(List<DisplayItem> items, PaintOffsets offsets, com.nishiyu.enderframelib.core.paint.SceneSource source, long nowMs) {
        return raster.needsRepaint(items, offsets, source, nowMs);
    }

    @Override
    public int[] dirtyBounds() {
        return raster.dirtyBounds();
    }

    @Override
    public int[] pixels() {
        return raster.pixmap().pixels();
    }

    @Override
    public void clearDirty() {
        raster.clearDirty();
    }

    @Override
    public boolean isAllocated() {
        return raster.isRootAllocated();
    }
}
