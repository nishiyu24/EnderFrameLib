package com.nishiyu.enderframelib.core.paint.scene;

import com.nishiyu.enderframelib.core.layout.fragment.FlowChunk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@link SceneChildren} の 32 子単位の不変チャンク（計画書 §3.4）。
 *
 * <p>{@link #source} はこのチャンクを作った {@link FlowChunk}（均質なチャンクでない場合や OOF を差し込んだ場合は null）。
 * 同じ {@code FlowChunk} かつ配下の {@code BoxNode} が最新の木で分岐していなければ、チャンク全体をそのまま（{@code ==}）使い回す。</p>
 */
public final class SceneChunk {
    public static final SceneChunk[] NO_CHUNKS = new SceneChunk[0];

    public final FlowChunk source;
    public final float minX, minY, maxX, maxY;
    public final SceneLayer[] layers;
    public final int propagatedLayers;
    public final boolean anchoredBelow;
    public final boolean propagatesBlend;
    public final SceneLayer[] stickyDescendants;
    public final int anonChain;
    /** 自身または子孫に変形・アニメーション・sticky・アンカー追従・Top Layer が含まれるか（カリング除外判定）。 */
    public final boolean hasDynamicBounds;
    /** 層か子孫が {@code backdrop-filter} を持つか（M3b-4c-2b-3a。シーンを全部たどらずに答える）。 */
    public final boolean backdropBelow;
    /**
     * 親の流れのこのチャンクの子の到達範囲（{@code ScrollableOverflow#chunk}。チャンクの原点から。{@link #source} があり使い回せる値のときだけ。
     * 無ければ null）。同じ {@link #source} でチャンクを使い回す親は、8,000 子の流れでも中を下りずに自分の到達範囲を求める（M3b-4c-2b-3a）。
     */
    public final float[] overflow;

    private SceneChunk(FlowChunk source, SceneLayer[] layers, float minX, float minY, float maxX, float maxY,
                       int propagatedLayers, boolean anchoredBelow, boolean propagatesBlend,
                       SceneLayer[] stickyDescendants, int anonChain, boolean hasDynamicBounds, boolean backdropBelow,
                       float[] overflow) {
        this.source = source;
        this.layers = layers;
        this.minX = minX;
        this.minY = minY;
        this.maxX = maxX;
        this.maxY = maxY;
        this.propagatedLayers = propagatedLayers;
        this.anchoredBelow = anchoredBelow;
        this.propagatesBlend = propagatesBlend;
        this.stickyDescendants = stickyDescendants;
        this.anonChain = anonChain;
        this.hasDynamicBounds = hasDynamicBounds;
        this.backdropBelow = backdropBelow;
        this.overflow = overflow;
    }

    public static SceneChunk of(FlowChunk source, SceneLayer[] layers) {
        return of(source, layers, null);
    }

    /** {@code overflow} は親の流れのこのチャンクの子の到達範囲（{@link #overflow}。{@code source} が null なら持たない）。 */
    public static SceneChunk of(FlowChunk source, SceneLayer[] layers, float[] overflow) {
        float[] ov = source != null ? overflow : null;
        if (layers == null || layers.length == 0) {
            return new SceneChunk(source, SceneLayer.NO_LAYERS, 0f, 0f, 0f, 0f, 0, false, false, SceneLayer.NO_LAYERS, 0, false, false, ov);
        }
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        int prop = 0, anon = 0;
        boolean anch = false, blend = false, dyn = false, backdrop = false;
        SceneLayer[] singleStickies = null;
        List<SceneLayer> multiStickies = null;
        for (SceneLayer l : layers) {
            minX = Math.min(minX, l.x + l.boundsMinX);
            minY = Math.min(minY, l.y + l.boundsMinY);
            maxX = Math.max(maxX, l.x + l.boundsMaxX);
            maxY = Math.max(maxY, l.y + l.boundsMaxY);
            prop |= l.propagatedLayers;
            anch |= l.anchoredBelow();
            blend |= l.propagatesBlend();
            dyn |= l.hasDynamicBounds();
            backdrop |= l.backdropBelow();
            anon += l.anonChain;
            SceneLayer[] st = l.bubbledStickies();
            if (st.length > 0) {
                if (singleStickies == null && multiStickies == null) {
                    singleStickies = st;
                } else {
                    if (multiStickies == null) {
                        multiStickies = new ArrayList<>(Arrays.asList(singleStickies));
                        singleStickies = null;
                    }
                    for (SceneLayer s : st) multiStickies.add(s);
                }
            }
        }
        if (minX > maxX) { minX = minY = maxX = maxY = 0f; }
        SceneLayer[] stickies = multiStickies != null ? multiStickies.toArray(SceneLayer.NO_LAYERS)
                : singleStickies != null ? singleStickies : SceneLayer.NO_LAYERS;
        return new SceneChunk(source, layers, minX, minY, maxX, maxY, prop, anch, blend, stickies, anon, dyn, backdrop, ov);
    }

    public int size() {
        return layers.length;
    }
}
