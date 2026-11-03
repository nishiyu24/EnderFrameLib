package com.nishiyu.enderframelib.core.paint.gpu;

import com.nishiyu.enderframelib.core.text.font.GlyphBitmap;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 字形アトラスの置き場所を決める、描画 API に依存しない棚詰め（シェルフ）の配置器。
 * 実際のテクスチャへの転送（OpenGL の {@code glTexSubImage2D}、Vulkan のステージング転送など）はバックエンドが、
 * {@link #slot} が「新しく置いた」と返したときだけ行う。
 *
 * <p>満ちたら {@code beforeReset} を呼んで（溜めた図形を吐かせてから）全部を空にして詰め直す。
 * 字形は描く直前に載せるので、すでに描いた分には響かない。スレッドセーフではない。
 */
public final class AtlasPacker {

    private final int size;
    private final Map<GlyphBitmap, int[]> entries = new IdentityHashMap<>();
    private int x, y, rowH;
    private boolean lastNew;

    public AtlasPacker(int size) {
        this.size = size;
    }

    /** アトラスの一辺（画素）。 */
    public int size() {
        return size;
    }

    /** 字形がアトラスに載るか（大きすぎるものは載らない）。 */
    public boolean fits(GlyphBitmap g) {
        return g.width() > 0 && g.height() > 0 && g.width() <= size && g.height() <= size;
    }

    /**
     * 字形の左上 {@code {x, y}} を返す（まだなら置く）。置いたかどうかは直後の {@link #lastPlacementWasNew} で分かる。
     * 返す配列は呼び出し側で書き換えない。
     */
    public int[] slot(GlyphBitmap g, Runnable beforeReset) {
        int[] slot = entries.get(g);
        if (slot != null) {
            lastNew = false;
            return slot;
        }
        if (x + g.width() > size) {
            x = 0;
            y += rowH;
            rowH = 0;
        }
        if (y + g.height() > size) {
            if (beforeReset != null) beforeReset.run();
            entries.clear();
            x = y = rowH = 0;
        }
        slot = new int[]{x, y};
        x += g.width();
        rowH = Math.max(rowH, g.height());
        entries.put(g, slot);
        lastNew = true;
        return slot;
    }

    /** 直前の {@link #slot} が新しく置いたなら true（その字形をテクスチャへ転送する必要がある）。 */
    public boolean lastPlacementWasNew() {
        return lastNew;
    }

    /** 置いた字形の数。 */
    public int count() {
        return entries.size();
    }
}
