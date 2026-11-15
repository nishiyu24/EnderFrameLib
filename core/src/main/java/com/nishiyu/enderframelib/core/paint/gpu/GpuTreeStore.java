package com.nishiyu.enderframelib.core.paint.gpu;

import com.nishiyu.enderframelib.core.kernel.gpu.PropertyTrees;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuBuffer;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuDevice;
import java.util.ArrayList;
import java.util.List;

/**
 * 4 つの Property Tree とスクロール表・曲線表・描画順（計 7 バッファ）を RHI の {@link GpuBuffer} 群として保持する。
 * {@link PropertyTrees} を CPU 側の書き込み口にし、{@link #flush()} で変更範囲だけを {@link GpuBuffer#markDirty} で通知する
 * （永続マップ対応環境では転送は不要、非対応環境でも {@link GpuDevice} 側で差分だけが送られる）。
 */
public final class GpuTreeStore implements AutoCloseable {
    public final PropertyTrees trees;
    public final GpuBuffer transforms, clips, effects, primitives, scrolls, curves, order;
    private final List<GpuBuffer> all = new ArrayList<>(7);
    private final GpuBuffer[] recordBindings;
    private final boolean persistent;

    public GpuTreeStore(GpuDevice device, int transformCap, int clipCap, int effectCap, int primitiveCap) {
        this.persistent = device.caps().persistentMap();
        this.trees = new PropertyTrees(transformCap, clipCap, effectCap, primitiveCap, bytes -> {
            GpuBuffer buf = device.createBuffer(new GpuDevice.BufferDesc(bytes, GpuDevice.BufferUsage.RECORD));
            all.add(buf);
            return buf;
        });
        this.transforms = all.get(0);
        this.clips = all.get(1);
        this.effects = all.get(2);
        this.primitives = all.get(3);
        this.scrolls = all.get(4);
        this.curves = all.get(5);
        this.order = all.get(6);
        this.recordBindings = new GpuBuffer[]{primitives, order, transforms, scrolls, curves, effects, clips};
    }

    public boolean isPersistent() {
        return persistent;
    }

    /** バインド順（{@code ShaderPrograms.RECTS} 宣言順）の 7 本の記録列配列。 */
    public GpuBuffer[] recordBindings() {
        return recordBindings;
    }

    /** CPU の書き込み範囲を RHI バッファへ通知する。 */
    public int flush() {
        return trees.flush();
    }

    @Override
    public void close() {
        for (GpuBuffer b : all) {
            b.close();
        }
        all.clear();
    }
}
