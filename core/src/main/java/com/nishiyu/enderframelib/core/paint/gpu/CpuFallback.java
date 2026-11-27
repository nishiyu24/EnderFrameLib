package com.nishiyu.enderframelib.core.paint.gpu;

import com.nishiyu.enderframelib.core.paint.port.GpuSink;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.PaintOffsets;
import com.nishiyu.enderframelib.core.paint.resource.PaintResources;
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

    /** {@code items} を描画先を塗り直さずに重ねる。{@code source}（null 可）は列を組んだシーン（保持層の判定に使う）。 */
    void renderOnto(List<DisplayItem> items, PaintOffsets offsets, com.nishiyu.enderframelib.core.paint.SceneSource source, long nowMs,
                    GpuSink sink);

    /** 前に描いたフレームから描き直す範囲が空でないか（{@code RenderBackend#needsRepaint}）。true なら続けて同じ入力で {@link #renderOnto}。 */
    boolean needsRepaint(List<DisplayItem> items, PaintOffsets offsets, com.nishiyu.enderframelib.core.paint.SceneSource source, long nowMs);

    int[] dirtyBounds();

    int[] pixels();

    void clearDirty();

    boolean isAllocated();
}
