package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.model.ChunkClock;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.PaintCell;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.List;
import java.util.RandomAccess;

/**
 * 固定長 32 要素ごとのブロック配列で持つ不変の {@link DisplayItem} リスト。
 * パッチ更新（同じ長さで少数の項目だけが差し替わったとき）では、変わったブロックだけをコピーして
 * 新しい不変リストを作るため、全体の {@code List.copyOf} による O(N) 配列確保を避けられる。
 */
public final class ImmutableItemList extends AbstractList<DisplayItem> implements RandomAccess {
    static final int CHUNK_SHIFT = 5;
    static final int CHUNK_SIZE = 1 << CHUNK_SHIFT;
    static final int CHUNK_MASK = CHUNK_SIZE - 1;

    private final DisplayItem[][] chunks;
    private final int size;
    /** 作った時点の塗りの書き込みの通し番号（{@link PaintCell#currentEpoch()}）。これより後に書かれた項目が「変わった」ものになる。 */
    private final int epoch;
    /**
     * 中身の同一性の印。作り直し・差分パッチで新しくなり、{@link #touched}（描画の上だけの移動量の書き込み）では引き継がれる。
     * スクロール層の保持などが「中身が変わっていない」ことを、スナップショットの同一性ではなくこの印で判定する。
     */
    private final Object token;
    /**
     * ブロックごとの書き込みの通し番号の時計（B9 の M6）。項目のセルが書かれるたびに印が付くので、消費者は書き込みを知るために
     * ブロックを複製してもらう必要が無い。同じ長さの差分パッチと {@link #touched} の間は同じ時計を共有する。
     */
    private final ChunkClock clock;

    private ImmutableItemList(DisplayItem[][] chunks, int size, ChunkClock clock) {
        this(chunks, size, new Object(), clock);
    }

    private ImmutableItemList(DisplayItem[][] chunks, int size, Object token, ChunkClock clock) {
        this.chunks = chunks;
        this.size = size;
        this.token = token;
        this.clock = clock;
        this.epoch = PaintCell.currentEpoch();
    }

    /** {@code src} の要素を持つ不変リストを返す（{@code prev} が同じサイズなら未変更ブロックを共有する）。 */
    public static ImmutableItemList from(ImmutableItemList prev, List<DisplayItem> src) {
        int n = src.size();
        if (prev != null && prev.size == n) {
            return patch(prev, src);
        }
        int numChunks = (n + CHUNK_MASK) >>> CHUNK_SHIFT;
        ChunkClock clock = new ChunkClock(numChunks);
        DisplayItem[][] chunks = new DisplayItem[numChunks][];
        for (int c = 0; c < numChunks; c++) {
            int base = c << CHUNK_SHIFT;
            int len = Math.min(CHUNK_SIZE, n - base);
            DisplayItem[] block = new DisplayItem[len];
            for (int j = 0; j < len; j++) {
                DisplayItem it = src.get(base + j);
                block[j] = it;
                it.attach(clock, base + j);
            }
            chunks[c] = block;
        }
        return new ImmutableItemList(chunks, n, clock);
    }

    /** 配列 {@code src[0..n)} を持つ不変リストを返す（{@code prev} が同じサイズなら未変更ブロックを共有する）。 */
    static ImmutableItemList fromArray(ImmutableItemList prev, DisplayItem[] src, int n) {
        if (prev != null && prev.size == n) {
            return patchArray(prev, src);
        }
        int numChunks = (n + CHUNK_MASK) >>> CHUNK_SHIFT;
        ChunkClock clock = new ChunkClock(numChunks);
        DisplayItem[][] chunks = new DisplayItem[numChunks][];
        for (int c = 0; c < numChunks; c++) {
            int base = c << CHUNK_SHIFT;
            int len = Math.min(CHUNK_SIZE, n - base);
            DisplayItem[] block = new DisplayItem[len];
            for (int j = 0; j < len; j++) {
                DisplayItem it = src[base + j];
                block[j] = it;
                it.attach(clock, base + j);
            }
            chunks[c] = block;
        }
        return new ImmutableItemList(chunks, n, clock);
    }

    private static ImmutableItemList patchArray(ImmutableItemList prev, DisplayItem[] src) {
        int n = prev.size;
        DisplayItem[][] oldChunks = prev.chunks;
        DisplayItem[][] nextChunks = null;
        int numChunks = oldChunks.length;
        for (int c = 0; c < numChunks; c++) {
            DisplayItem[] ob = oldChunks[c];
            int base = c << CHUNK_SHIFT;
            int len = ob.length;
            DisplayItem[] nb = null;
            for (int j = 0; j < len; j++) {
                DisplayItem cur = src[base + j];
                if (ItemDiff.replaced(ob[j], cur)) {
                    if (nb == null) nb = Arrays.copyOf(ob, len);
                    nb[j] = cur;
                    cur.attach(prev.clock, base + j);
                }
            }
            if (nb != null) {
                if (nextChunks == null) nextChunks = oldChunks.clone();
                nextChunks[c] = nb;
            }
        }
        if (nextChunks != null) return new ImmutableItemList(nextChunks, n, prev.clock);
        return prev.clock.maxPaintEpoch() > prev.epoch ? new ImmutableItemList(oldChunks, n, new Object(), prev.clock) : prev;
    }

    private static ImmutableItemList patch(ImmutableItemList prev, List<DisplayItem> src) {
        int n = prev.size;
        DisplayItem[][] oldChunks = prev.chunks;
        DisplayItem[][] nextChunks = null;
        int numChunks = oldChunks.length;
        for (int c = 0; c < numChunks; c++) {
            DisplayItem[] ob = oldChunks[c];
            int base = c << CHUNK_SHIFT;
            int len = ob.length;
            DisplayItem[] nb = null;
            for (int j = 0; j < len; j++) {
                DisplayItem cur = src.get(base + j);
                if (ItemDiff.replaced(ob[j], cur)) {
                    if (nb == null) nb = Arrays.copyOf(ob, len);
                    nb[j] = cur;
                    cur.attach(prev.clock, base + j);
                }
            }
            if (nb != null) {
                if (nextChunks == null) nextChunks = oldChunks.clone();
                nextChunks[c] = nb;
            }
        }
        if (nextChunks != null) return new ImmutableItemList(nextChunks, n, prev.clock);
        // 項目の差し替えは無い。セルへ書かれていれば（時計が進んでいれば）、ブロックを複製せず新しいスナップショットだけを返す
        // （消費者は塗りの変化を、リストの同一性が変わったことと時計で知る）。何も書かれていなければ同じリスト
        return prev.clock.maxPaintEpoch() > prev.epoch ? new ImmutableItemList(oldChunks, n, new Object(), prev.clock) : prev;
    }

    /**
     * 項目の移動量が書き換わったことを表す新しいスナップショット（消費者がリストの同一性で変化に気づく）。項目もブロックも時計も
     * 同じものを指し（どの項目が変わったかは時計の印で分かる）、中身の印は引き継ぐ。
     */
    public ImmutableItemList touched() {
        return new ImmutableItemList(chunks, size, token, clock);
    }

    /** ブロックごとの書き込みの時計。 */
    public ChunkClock clock() {
        return clock;
    }

    /** 中身の同一性の印（{@link #touched} では変わらない）。 */
    public Object token() {
        return token;
    }

    /** 作った時点の塗りの書き込みの通し番号。 */
    public int epoch() {
        return epoch;
    }

    int chunkCount() {
        return chunks.length;
    }

    DisplayItem[] chunkAt(int c) {
        return chunks[c];
    }

    @Override
    public DisplayItem get(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
        return chunks[index >>> CHUNK_SHIFT][index & CHUNK_MASK];
    }

    @Override
    public int size() {
        return size;
    }
}
