package com.nishiyu.enderframelib.core.paint.gl;

import com.nishiyu.enderframelib.core.paint.gpu.CpuFallback;
import com.nishiyu.enderframelib.core.paint.port.GpuSink;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.software.PaintResources;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import java.util.List;

/**
 * GL 描画器が使う「CPU で描く保険の層」の窓口。
 * <p>
 * {@link LwjglGlRenderBackend} が {@link SoftwareRenderBackend} という具体型を知らずに済むよう、
 * 実際に必要とする 5 操作（描画・汚れ範囲・画素・汚れ消去・確保済みか）だけを出す。
 * CPU ラスタの内部（{@code Pixmap} 配置・タイル・命令）は GL 側から見えない。
 */
public final class CpuLayerFallback implements CpuFallback {
    private final SoftwareRenderBackend raster;

    public CpuLayerFallback(int cssW, int cssH, float dpr, String sessionId, PaintResources resources) {
        this.raster = new SoftwareRenderBackend(cssW, cssH, dpr, sessionId, resources);
    }

    /** 表示リストを走査し、GPU が受け取らなかった図形だけを CPU の層へ描く（受け取れたものは {@code sink} へ送る）。 */
    @Override
    public void renderOnto(List<DisplayItem> items, long nowMs, GpuSink sink) {
        raster.renderOnto(items, nowMs, sink);
    }

    /** CPU が塗った範囲の外接矩形 {x0,y0,x1,y1}。何も塗っていなければ null。 */
    @Override
    public int[] dirtyBounds() {
        return raster.dirtyBounds();
    }

    /** CPU 層の画素（画面サイズ）。{@link #dirtyBounds()} が null でないときだけ読む。 */
    @Override
    public int[] pixels() {
        return raster.pixmap().pixels();
    }

    @Override
    public void clearDirty() {
        raster.clearDirty();
    }

    /** 画面サイズの画素を確保済みか。図形が全部 GPU に受け取られている間は false。 */
    @Override
    public boolean isAllocated() {
        return raster.isRootAllocated();
    }
}
