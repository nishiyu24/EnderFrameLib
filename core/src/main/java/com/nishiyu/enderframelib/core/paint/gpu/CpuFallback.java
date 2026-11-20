package com.nishiyu.enderframelib.core.paint.gpu;

import com.nishiyu.enderframelib.core.paint.port.GpuSink;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.software.PaintResources;
import java.util.List;

/**
 * GPU バックエンドが受け取れなかった図形だけを CPU 側で描くフォールバック窓口。
 * {@link GpuRenderBackend} は具体実装（{@code SoftwareRenderBackend}）を直接知らず、この契約越しに連携する。
 */
public interface CpuFallback {
    @FunctionalInterface
    interface Factory {
        CpuFallback create(int cssW, int cssH, float dpr, String sessionId, PaintResources resources);
    }

    void renderOnto(List<DisplayItem> items, long nowMs, GpuSink sink);

    int[] dirtyBounds();

    int[] pixels();

    void clearDirty();

    boolean isAllocated();
}
