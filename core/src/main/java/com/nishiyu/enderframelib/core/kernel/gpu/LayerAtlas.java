package com.nishiyu.enderframelib.core.kernel.gpu;

/**
 * レイヤー（効果のある箱）の描画先を、共有の 1 枚（既定 2048×2048）のサブ矩形として貸す配置器。フル画面 FBO の代わり。
 * 棚詰め（シェルフ）で、同じ高さの棚に横へ並べ、返却された矩形は棚の空き区間として再利用し、隣り合う空きは併合する。
 * GL を知らない（描画先テクスチャの作成は呼び出し側）。矩形は 1 画素の余白を含めて確保し、隣のレイヤーへ滲まない。
 * 棚・区間は固定容量の配列なので割り当ては 0。置けなければ -1（呼び出し側は専用 FBO か直描画へ）。
 *
 * <p>矩形はハンドルで引く: {@link #x}, {@link #y}, {@link #w}, {@link #h}（余白を除いた使える範囲）。</p>
 */
public final class LayerAtlas {
    public static final int GUTTER = 1;
    private static final int MAX_SHELVES = 256;
    private static final int MAX_SPANS = 4096;

    private final int size;
    private final int[] shelfY = new int[MAX_SHELVES], shelfH = new int[MAX_SHELVES];
    private int shelves, nextY;
    private final int[] spanShelf = new int[MAX_SPANS], spanX = new int[MAX_SPANS], spanW = new int[MAX_SPANS], spanH = new int[MAX_SPANS];
    private final boolean[] spanFree = new boolean[MAX_SPANS];
    private final boolean[] spanLive = new boolean[MAX_SPANS];
    private long usedPixels;

    public LayerAtlas(int size) {
        if (size <= 0) throw new IllegalArgumentException("size");
        this.size = size;
    }

    public int size() { return size; }
    /** 貸し出し中の面積（余白込み）。 */
    public long usedPixels() { return usedPixels; }

    /** {@code w×h}（使える範囲）を借りる。置けなければ -1。 */
    public int acquire(int w, int h) {
        if (w <= 0 || h <= 0) return -1;
        long lw = (long) w + 2 * GUTTER, lh = (long) h + 2 * GUTTER;
        if (lw > size || lh > size) return -1;
        int bw = (int) lw, bh = (int) lh;
        int best = -1;
        long bestWaste = Long.MAX_VALUE;
        for (int s = 0; s < MAX_SPANS; s++) { // 高さが足りる棚のうち、無駄の最も少ない空き区間
            if (!spanLive[s] || !spanFree[s] || spanW[s] < bw) continue;
            int sh = shelfH[spanShelf[s]];
            if (sh < bh) continue;
            long waste = (long) (sh - bh) * 8192 + (spanW[s] - bw);
            if (waste < bestWaste) { bestWaste = waste; best = s; }
        }
        if (best >= 0) return take(best, bw, bh);
        if (nextY + bh > size || shelves >= MAX_SHELVES) return -1;
        int sp = newSpan();
        if (sp < 0) return -1;
        int sh = shelves++;
        shelfY[sh] = nextY; shelfH[sh] = bh; nextY += bh;
        spanShelf[sp] = sh; spanX[sp] = 0; spanW[sp] = size; spanFree[sp] = true; spanLive[sp] = true;
        return take(sp, bw, bh);
    }

    private int take(int sp, int bw, int bh) {
        if (spanW[sp] > bw) { // 残りを空き区間として分ける
            int rest = newSpan();
            if (rest >= 0) {
                spanShelf[rest] = spanShelf[sp]; spanX[rest] = spanX[sp] + bw; spanW[rest] = spanW[sp] - bw;
                spanFree[rest] = true; spanLive[rest] = true;
                spanW[sp] = bw;
            }
        }
        spanFree[sp] = false;
        spanH[sp] = bh;
        usedPixels += (long) spanW[sp] * shelfH[spanShelf[sp]];
        return sp;
    }

    private int newSpan() {
        for (int s = 0; s < MAX_SPANS; s++) if (!spanLive[s]) return s;
        return -1;
    }

    /** 返す。同じ棚で隣り合う空きと併合し、最後の棚が丸ごと空きなら畳む。 */
    public void release(int handle) {
        if (handle < 0 || handle >= MAX_SPANS || !spanLive[handle] || spanFree[handle]) throw new IllegalStateException("handle " + handle);
        usedPixels -= (long) spanW[handle] * shelfH[spanShelf[handle]];
        spanFree[handle] = true;
        boolean merged;
        do {
            merged = false;
            for (int o = 0; o < MAX_SPANS; o++) {
                if (o == handle || !spanLive[o] || !spanFree[o] || spanShelf[o] != spanShelf[handle]) continue;
                if (spanX[o] + spanW[o] == spanX[handle]) { spanW[o] += spanW[handle]; spanLive[handle] = false; handle = o; merged = true; break; }
                if (spanX[handle] + spanW[handle] == spanX[o]) { spanW[handle] += spanW[o]; spanLive[o] = false; merged = true; break; }
            }
        } while (merged);
        // 末尾の棚から順に、丸ごと空きなら畳む
        while (shelves > 0) {
            int sh = shelves - 1, whole = -1;
            for (int s = 0; s < MAX_SPANS; s++) if (spanLive[s] && spanShelf[s] == sh) { whole = s; break; }
            if (whole < 0 || !spanFree[whole] || spanW[whole] != size) break;
            spanLive[whole] = false;
            nextY = shelfY[sh];
            shelves--;
        }
    }

    public int x(int handle) { return spanX[handle] + GUTTER; }
    public int y(int handle) { return shelfY[spanShelf[handle]] + GUTTER; }
    public int w(int handle) { return spanW[handle] - 2 * GUTTER; }
    public int h(int handle) { return spanH[handle] - 2 * GUTTER; }
}
