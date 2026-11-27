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

    private SceneChunk(FlowChunk source, SceneLayer[] layers, float minX, float minY, float maxX, float maxY,
                       int propagatedLayers, boolean anchoredBelow, boolean propagatesBlend,
                       SceneLayer[] stickyDescendants, int anonChain, boolean hasDynamicBounds) {
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
    }

    public static SceneChunk of(FlowChunk source, SceneLayer[] layers) {
        if (layers == null || layers.length == 0) {
            return new SceneChunk(source, SceneLayer.NO_LAYERS, 0f, 0f, 0f, 0f, 0, false, false, SceneLayer.NO_LAYERS, 0, false);
        }
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        int prop = 0, anon = 0;
        boolean anch = false, blend = false, dyn = false;
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
        return new SceneChunk(source, layers, minX, minY, maxX, maxY, prop, anch, blend, stickies, anon, dyn);
    }

    public int size() {
        return layers.length;
    }
}
