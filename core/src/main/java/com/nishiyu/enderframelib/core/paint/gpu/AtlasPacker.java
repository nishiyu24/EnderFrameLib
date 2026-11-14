package com.nishiyu.enderframelib.core.paint.gpu;

import com.nishiyu.enderframelib.core.text.font.GlyphBitmap;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 字形アトラスの置き場所を決める、描画 API に依存しない棚詰め（シェルフ）の配置器。
 * 実際のテクスチャへの転送（OpenGL の {@code glTexSubImage2D}、Vulkan のステージング転送など）はバックエンドが、
 * {@link #slot} が「新しく置いた」と返したときだけ行う。
 *
 * <p>満ちたら、上限（{@code maxSize}）に達するまでは一辺を倍にして広げる（置いた字形の位置は変わらない。
 * バックエンドはテクスチャを作り直して {@link #forEach} で全部を載せ直す）。上限でも満ちたら
 * {@code beforeReset} を呼んで（溜めた図形を吐かせてから）全部を空にして詰め直す。
 * 字形は描く直前に載せるので、すでに描いた分には響かない。スレッドセーフではない。
 */
public final class AtlasPacker {

    private int size;
    private int maxSize;
    private final Map<GlyphBitmap, int[]> entries = new IdentityHashMap<>();
    private int x, y, rowH;
    private boolean lastNew;
    private boolean lastGrew;
    private int resets;

    /** 広げない固定サイズのアトラス。 */
    public AtlasPacker(int size) {
        this(size, size);
    }

    /** {@code initialSize} から始め、満ちるたびに倍にして {@code maxSize} まで広げる。 */
    public AtlasPacker(int initialSize, int maxSize) {
        this.size = initialSize;
        this.maxSize = Math.max(initialSize, maxSize);
    }

    /** 広げる上限を変える（現在の大きさより小さくはならない）。GL の最大テクスチャ寸法に合わせるために使う。 */
    public void setMaxSize(int maxSize) {
        this.maxSize = Math.max(size, maxSize);
    }

    /** 広げる上限（画素）。 */
    public int maxSize() {
        return maxSize;
    }

    /** アトラスの現在の一辺（画素）。 */
    public int size() {
        return size;
    }

    /** 字形がアトラスに載るか（大きすぎるものは載らない）。 */
    public boolean fits(GlyphBitmap g) {
        return g.width() > 0 && g.height() > 0 && g.width() <= maxSize && g.height() <= maxSize;
    }

    /**
     * 字形の左上 {@code {x, y}} を返す（まだなら置く）。置いたかどうかは直後の {@link #lastPlacementWasNew} で分かる。
     * 返す配列は呼び出し側で書き換えない。
     */
    public int[] slot(GlyphBitmap g, Runnable beforeReset) {
        lastGrew = false;
        int[] slot = entries.get(g);
        if (slot != null) {
            lastNew = false;
            return slot;
        }
        boolean flushed = false;
        // 幅が現在の大きさを超える字形は、置く前に広げる
        while (g.width() > size && size < maxSize) {
            if (!flushed && beforeReset != null) beforeReset.run();
            flushed = true;
            size = Math.min(size * 2, maxSize);
            lastGrew = true;
        }
        if (x + g.width() > size) {
            x = 0;
            y += rowH;
            rowH = 0;
        }
        while (y + g.height() > size) {
            if (!flushed && beforeReset != null) beforeReset.run();
            flushed = true;
            if (size < maxSize) {
                size = Math.min(size * 2, maxSize);
                lastGrew = true;
            } else {
                entries.clear();
                resets++;
                x = y = rowH = 0;
                break;
            }
        }
        slot = new int[]{x, y};
        x += g.width();
        rowH = Math.max(rowH, g.height());
        entries.put(g, slot);
        lastNew = true;
        return slot;
    }

    /** 置いてある字形をすべて捨てた（満杯で作り直した）回数。保持した GPU 記録は、これが変わると字形の位置が無効になる。 */
    public int resets() {
        return resets;
    }

    /** 直前の {@link #slot} が新しく置いたなら true（その字形をテクスチャへ転送する必要がある）。 */
    public boolean lastPlacementWasNew() {
        return lastNew;
    }

    /**
     * 直前の {@link #slot} でアトラスの一辺を広げたなら true。バックエンドはテクスチャを {@link #size} で作り直し、
     * {@link #forEach} の全字形を載せ直す（その前に溜めた図形は {@code beforeReset} で吐かせてある）。
     */
    public boolean lastPlacementGrew() {
        return lastGrew;
    }

    /** 置いてある字形とその左上位置を順に渡す（広げた後の載せ直し用）。 */
    public void forEach(java.util.function.BiConsumer<GlyphBitmap, int[]> action) {
        entries.forEach(action);
    }

    /** 置いた字形の数。 */
    public int count() {
        return entries.size();
    }
}
