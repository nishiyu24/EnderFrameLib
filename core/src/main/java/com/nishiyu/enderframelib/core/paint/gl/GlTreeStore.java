package com.nishiyu.enderframelib.core.paint.gl;

import com.nishiyu.enderframelib.core.kernel.gpu.PropertyTrees;
import java.util.ArrayList;
import java.util.List;

/**
 * 4 つの Property Tree とスクロール表・曲線表・描画順（計 7 バッファ）を、GPU 側のバッファ 6 本として持つ。
 * {@link PropertyTrees} をそのまま CPU の書き込み口にし、{@link #flush()} が汚れた範囲だけを GPU へ送る
 * （永続マップなら送らない）。フレームの形は {@code beginFrame → 書き込み → flush → 描画 → endFrame}。
 */
public final class GlTreeStore implements AutoCloseable {
    public final PropertyTrees trees;
    public final GlVramDevice transforms, clips, effects, primitives, scrolls, curves, order;
    private final List<GlVramDevice> all = new ArrayList<>(6);

    public GlTreeStore(int transformCap, int clipCap, int effectCap, int primitiveCap) {
        this(transformCap, clipCap, effectCap, primitiveCap, true);
    }

    public GlTreeStore(int transformCap, int clipCap, int effectCap, int primitiveCap, boolean allowPersistent) {
        this.trees = new PropertyTrees(transformCap, clipCap, effectCap, primitiveCap, () -> {
            GlVramDevice d = new GlVramDevice(allowPersistent);
            all.add(d);
            return d;
        });
        this.transforms = all.get(0);
        this.clips = all.get(1);
        this.effects = all.get(2);
        this.primitives = all.get(3);
        this.scrolls = all.get(4);
        this.curves = all.get(5);
        this.order = all.get(6);
    }

    public boolean isPersistent() { return transforms.isPersistent(); }

    public void beginFrame() { for (GlVramDevice d : all) d.beginFrame(); }

    /** CPU の書き込みを GPU へ反映する。送ったバイト数（永続マップでは 0）。 */
    public int flush() { return trees.flush(); }

    public void endFrame() { for (GlVramDevice d : all) d.endFrame(); }

    @Override
    public void close() { for (GlVramDevice d : all) d.close(); }
}
