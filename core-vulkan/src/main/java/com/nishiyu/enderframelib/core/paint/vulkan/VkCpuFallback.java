package com.nishiyu.enderframelib.core.paint.vulkan;

import com.nishiyu.enderframelib.core.paint.gpu.CpuFallback;
import com.nishiyu.enderframelib.core.paint.port.GpuSink;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
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

    @Override
    public void renderOnto(List<DisplayItem> items, long nowMs, GpuSink sink) {
        raster.renderOnto(items, nowMs, sink);
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
