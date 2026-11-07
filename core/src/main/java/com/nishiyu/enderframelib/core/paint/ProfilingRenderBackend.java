package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.diag.PipelineStage;
import com.nishiyu.enderframelib.core.diag.StageProfiler;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.raster.Pixmap;

import java.util.List;

/**
 * 任意の {@link RenderBackend} を包み、{@link #render} の所要を {@link PipelineStage#PAINT} に記録する。
 * 描画結果には何も足さない（全部を委譲する）。{@link StageProfiler} が無効なら時計も読まない。
 *
 * <p>GPU 版では {@code render} は命令の発行までで、GPU の実行時間は含まない。
 * GPU 側は {@code LwjglGlRenderBackend#enableGpuTiming} が {@link PipelineStage#GPU} に別に記録する。
 */
public final class ProfilingRenderBackend implements RenderBackend {

    private final RenderBackend delegate;

    public ProfilingRenderBackend(RenderBackend delegate) {
        this.delegate = java.util.Objects.requireNonNull(delegate);
    }

    /** 包んでいる実体。 */
    public RenderBackend delegate() {
        return delegate;
    }

    @Override
    public void render(List<DisplayItem> items, long nowMs) {
        long t0 = StageProfiler.begin(PipelineStage.PAINT);
        try {
            delegate.render(items, nowMs);
        } finally {
            StageProfiler.end(PipelineStage.PAINT, t0);
        }
    }

    @Override
    public Pixmap pixmap() {
        return delegate.pixmap();
    }

    @Override
    public float devicePixelRatio() {
        return delegate.devicePixelRatio();
    }

    @Override
    public String sessionId() {
        return delegate.sessionId();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
