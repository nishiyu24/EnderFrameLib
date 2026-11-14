package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
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

    private ImmutableItemList(DisplayItem[][] chunks, int size) {
        this.chunks = chunks;
        this.size = size;
    }

    /** {@code src} の要素を持つ不変リストを返す（{@code prev} が同じサイズなら未変更ブロックを共有する）。 */
    public static ImmutableItemList from(ImmutableItemList prev, List<DisplayItem> src) {
        int n = src.size();
        if (prev != null && prev.size == n) {
            return patch(prev, src);
        }
        int numChunks = (n + CHUNK_MASK) >>> CHUNK_SHIFT;
        DisplayItem[][] chunks = new DisplayItem[numChunks][];
        for (int c = 0; c < numChunks; c++) {
            int base = c << CHUNK_SHIFT;
            int len = Math.min(CHUNK_SIZE, n - base);
            DisplayItem[] block = new DisplayItem[len];
            for (int j = 0; j < len; j++) {
                block[j] = src.get(base + j);
            }
            chunks[c] = block;
        }
        return new ImmutableItemList(chunks, n);
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
                if (ob[j] != cur) {
                    if (nb == null) nb = Arrays.copyOf(ob, len);
                    nb[j] = cur;
                }
            }
            if (nb != null) {
                if (nextChunks == null) nextChunks = oldChunks.clone();
                nextChunks[c] = nb;
            }
        }
        return nextChunks == null ? prev : new ImmutableItemList(nextChunks, n);
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
