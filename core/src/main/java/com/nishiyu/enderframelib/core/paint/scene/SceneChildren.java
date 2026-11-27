package com.nishiyu.enderframelib.core.paint.scene;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@link SceneLayer} の子のチャンク列（32 子単位の {@link SceneChunk} と各チャンクの親基準の位置、Y 単調性、可視範囲カリング）。
 */
public final class SceneChildren {
    private static final float[] NO_POS = new float[0];
    private static final int[] NO_INTS = new int[0];
    public static final SceneChildren EMPTY = new SceneChildren(SceneChunk.NO_CHUNKS, NO_POS, NO_POS, true, 0, 0, false, false, 0, false, NO_INTS);

    public final SceneChunk[] chunks;
    /** 各チャンクの原点（親の border-box 左上基準）。 */
    public final float[] chunkX, chunkY;
    /** 各チャンクの包含矩形の minY と maxY がともに非減少で並んでいるか（二分探索カリングが可能）。 */
    public final boolean monotonicY;
    public final int size;
    public final int propagatedLayers;
    public final boolean anchoredBelow;
    public final boolean propagatesBlend;
    public final int anonChain;
    public final boolean hasDynamicBounds;
    /** 動的境界を持つ（範囲カリングをバイパスすべき）チャンク添字の昇順配列。 */
    public final int[] unculledChunks;

    private SceneChildren(SceneChunk[] chunks, float[] chunkX, float[] chunkY, boolean monotonicY, int size,
                          int propagatedLayers, boolean anchoredBelow, boolean propagatesBlend, int anonChain,
                          boolean hasDynamicBounds, int[] unculledChunks) {
        this.chunks = chunks;
        this.chunkX = chunkX;
        this.chunkY = chunkY;
        this.monotonicY = monotonicY;
        this.size = size;
        this.propagatedLayers = propagatedLayers;
        this.anchoredBelow = anchoredBelow;
        this.propagatesBlend = propagatesBlend;
        this.anonChain = anonChain;
        this.hasDynamicBounds = hasDynamicBounds;
        this.unculledChunks = unculledChunks;
    }

    public static SceneChildren of(SceneChunk[] chunks, float[] chunkX, float[] chunkY) {
        if (chunks == null || chunks.length == 0) return EMPTY;
        int total = 0, prop = 0, anon = 0, dynCount = 0;
        boolean anch = false, blend = false, mono = true;
        float prevMinY = Float.NEGATIVE_INFINITY, prevMaxY = Float.NEGATIVE_INFINITY;
        for (int k = 0; k < chunks.length; k++) {
            SceneChunk c = chunks[k];
            total += c.layers.length;
            prop |= c.propagatedLayers;
            anch |= c.anchoredBelow;
            blend |= c.propagatesBlend;
            anon += c.anonChain;
            if (c.hasDynamicBounds) dynCount++;
            if (mono && c.layers.length > 0) {
                float cMinY = chunkY[k] + c.minY;
                float cMaxY = chunkY[k] + c.maxY;
                if (cMinY < prevMinY || cMaxY < prevMaxY) mono = false;
                prevMinY = cMinY;
                prevMaxY = cMaxY;
            }
        }
        if (total == 0) return EMPTY;
        int[] unculled = NO_INTS;
        if (dynCount > 0) {
            unculled = new int[dynCount];
            int idx = 0;
            for (int k = 0; k < chunks.length; k++) {
                if (chunks[k].hasDynamicBounds) unculled[idx++] = k;
            }
        }
        return new SceneChildren(chunks, chunkX, chunkY, mono, total, prop, anch, blend, anon, dynCount > 0, unculled);
    }

    /** 親へ汲み上げる子孫の sticky 要素を集める（通常は 0 確保）。 */
    SceneLayer[] collectStickies() {
        SceneLayer[] single = null;
        List<SceneLayer> multi = null;
        for (SceneChunk c : chunks) {
            SceneLayer[] st = c.stickyDescendants;
            if (st.length == 0) continue;
            if (single == null && multi == null) {
                single = st;
            } else {
                if (multi == null) {
                    multi = new ArrayList<>(Arrays.asList(single));
                    single = null;
                }
                for (SceneLayer s : st) multi.add(s);
            }
        }
        return multi != null ? multi.toArray(SceneLayer.NO_LAYERS) : single != null ? single : SceneLayer.NO_LAYERS;
    }

    /**
     * {@link #monotonicY} が真のとき、下端が {@code viewMinY} 以上の最初のチャンク番号を二分探索（{@code O(log N)}）で返す。
     * 単調でなければ 0。
     */
    public int firstVisibleChunk(float viewMinY) {
        if (!monotonicY || chunks.length == 0) return 0;
        int lo = 0, hi = chunks.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            float maxY = chunkY[mid] + chunks[mid].maxY;
            if (maxY < viewMinY) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    /**
     * {@link #monotonicY} が真のとき、上端が {@code viewMaxY} 以下の最後のチャンク番号を二分探索（{@code O(log N)}）で返す。
     * 単調でなければ末尾の添字。
     */
    public int lastVisibleChunk(float viewMaxY) {
        if (!monotonicY || chunks.length == 0) return chunks.length - 1;
        int lo = 0, hi = chunks.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            float minY = chunkY[mid] + chunks[mid].minY;
            if (minY <= viewMaxY) lo = mid + 1;
            else hi = mid;
        }
        return lo - 1;
    }

    @FunctionalInterface
    public interface ChunkVisitor {
        void visit(int chunkIndex, SceneChunk chunk, float offsetX, float offsetY);
    }

    /** 可視範囲 {@code [viewMinX..viewMaxX, viewMinY..viewMaxY]}（親座標）と交わるチャンクだけを順に訪ねる。 */
    public void forEachVisible(float viewMinX, float viewMinY, float viewMaxX, float viewMaxY, ChunkVisitor visitor) {
        int start = firstVisibleChunk(viewMinY);
        for (int k = start; k < chunks.length; k++) {
            SceneChunk c = chunks[k];
            if (c.layers.length == 0) continue;
            float cx = chunkX[k], cy = chunkY[k];
            float cMinY = cy + c.minY;
            if (monotonicY && cMinY > viewMaxY) break;
            float cMaxY = cy + c.maxY;
            if (cMaxY < viewMinY || cMinY > viewMaxY) continue;
            if (cx + c.maxX < viewMinX || cx + c.minX > viewMaxX) continue;
            visitor.visit(k, c, cx, cy);
        }
    }
}
