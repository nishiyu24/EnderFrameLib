package com.nishiyu.enderframelib.core.paint.software;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/**
 * 画素を書く命令 {@link Op} を溜め、64×64 のタイルごとに（並列に）命令の順で実行する。
 * 以前は {@code SoftwareRenderBackend} が命令の型・置き場とタイル振り分けを同じクラスに抱えていた。
 * 描画先を読む・切り替える処理、フレームの終わりの前には必ず {@link #flush} を呼ぶこと。
 * 呼び出しは 1 スレッド（実行だけが内部で並列）。
 */
final class TileQueue {
    /** タイル単位の遅延実行を使うか（{@code -Denderframe.soft.notiled=true} で無効＝命令ごとにその場で全範囲を実行）。 */
    static volatile boolean tiled = !Boolean.getBoolean("enderframe.soft.notiled");
    static final int TILE = 64;

    private final ArrayList<Op> pending = new ArrayList<>();
    private final Scratch scratch = new Scratch();
    private final Consumer<Op> recycle;

    /** @param recycle 実行し終えた命令を手放す（掴んでいる参照を捨てて置き場へ戻す）。 */
    TileQueue(Consumer<Op> recycle) {
        this.recycle = recycle;
    }

    boolean isEmpty() { return pending.isEmpty(); }

    int size() { return pending.size(); }

    /** {@code defer} の命令は溜めてタイルで実行。そうでなければ、溜めた分を先に実行してからその場で全範囲を実行（順序を守る）。 */
    void submit(Op op, boolean defer) {
        if (defer && tiled) {
            if (!pending.isEmpty() && pending.get(0).px != op.px) flush();
            pending.add(op);
            return;
        }
        flush();
        op.run(op.x0, op.y0, op.x1, op.y1, scratch);
        recycle.accept(op);
    }

    /** 溜めた命令を捨てる（例外のとき。描画先は不定なので実行も手放しもしない）。 */
    void discard() {
        pending.clear();
    }

    /** 溜めた命令を実行して空にする。 */
    void flush() {
        int n = pending.size();
        if (n == 0) return;
        Op first = pending.get(0);
        int w = first.w, h = first.px.length / w;
        long area = 0;
        for (int i = 0; i < n; i++) {
            Op o = pending.get(i);
            area += (long) (o.x1 - o.x0) * (o.y1 - o.y0);
        }
        if (n == 1 || Runtime.getRuntime().availableProcessors() < 2 || area < Rows.PARALLEL_MIN_PIXELS || !Rows.PARALLEL) {
            for (int i = 0; i < n; i++) {
                Op o = pending.get(i);
                o.run(o.x0, o.y0, o.x1, o.y1, scratch);
            }
            releaseAll();
            return;
        }
        int tilesX = (w + TILE - 1) / TILE, tilesY = (h + TILE - 1) / TILE;
        int[] start = new int[tilesX * tilesY + 1];
        for (int i = 0; i < n; i++) {
            Op o = pending.get(i);
            for (int ty = o.y0 / TILE; ty <= (o.y1 - 1) / TILE; ty++)
                for (int tx = o.x0 / TILE; tx <= (o.x1 - 1) / TILE; tx++) start[ty * tilesX + tx + 1]++;
        }
        for (int t = 0; t < tilesX * tilesY; t++) start[t + 1] += start[t];
        int[] fill = Arrays.copyOf(start, tilesX * tilesY);
        int[] list = new int[start[tilesX * tilesY]];
        for (int i = 0; i < n; i++) { // 命令の順に入れるので、タイルの中でも命令の順
            Op o = pending.get(i);
            for (int ty = o.y0 / TILE; ty <= (o.y1 - 1) / TILE; ty++)
                for (int tx = o.x0 / TILE; tx <= (o.x1 - 1) / TILE; tx++) list[fill[ty * tilesX + tx]++] = i;
        }
        final ArrayList<Op> ops = pending;
        IntStream.range(0, tilesX * tilesY).parallel().forEach(t -> {
            if (start[t] == start[t + 1]) return;
            Scratch s = new Scratch();
            int tx0 = (t % tilesX) * TILE, ty0 = (t / tilesX) * TILE;
            int tx1 = Math.min(w, tx0 + TILE), ty1 = Math.min(h, ty0 + TILE);
            for (int k = start[t]; k < start[t + 1]; k++) {
                Op o = ops.get(list[k]);
                int ax0 = Math.max(o.x0, tx0), ay0 = Math.max(o.y0, ty0), ax1 = Math.min(o.x1, tx1), ay1 = Math.min(o.y1, ty1);
                if (ax1 > ax0 && ay1 > ay0) o.run(ax0, ay0, ax1, ay1, s);
            }
        });
        releaseAll();
    }

    private void releaseAll() {
        for (int i = 0, n = pending.size(); i < n; i++) recycle.accept(pending.get(i));
        pending.clear();
    }
}
