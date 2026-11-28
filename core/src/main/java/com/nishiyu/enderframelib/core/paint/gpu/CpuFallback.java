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

    /** 準備したフレームを描画先を塗り直さずに重ねる（M3b-4c-2a）。 */
    void renderOnto(com.nishiyu.enderframelib.core.paint.PreparedFrame frame, GpuSink sink);

    /** 描く前の準備（前に描いたフレームから描き直す範囲。{@code RenderBackend#prepare}）。描くなら返した値を {@link #renderOnto} へ。 */
    com.nishiyu.enderframelib.core.paint.PreparedFrame prepare(com.nishiyu.enderframelib.core.paint.RenderFrame frame);

    int[] dirtyBounds();

    int[] pixels();

    void clearDirty();

    boolean isAllocated();
}
