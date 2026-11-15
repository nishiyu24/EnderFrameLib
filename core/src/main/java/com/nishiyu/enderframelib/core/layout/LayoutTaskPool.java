package com.nishiyu.enderframelib.core.layout;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

/**
 * 独立した兄弟部分木（再スタイル・測定・配置・変更マーク・後片付け）を並列実行するための共通プール。
 *
 * <p>{@code -Denderframe.layout.noparallel=true} または {@link #parallelEnabled} で直列実行へ切り替えられ、
 * 並列・直列の結果の等価性検証に使う。</p>
 */
public final class LayoutTaskPool {

    /** これ未満の作業数ではフォークのオーバーヘッドを避けて直列実行する。 */
    public static final int PARALLEL_THRESHOLD = 32;

    /** {@code -Denderframe.layout.noparallel=true} で並列実行を無効化する切り替え。 */
    public static volatile boolean parallelEnabled = !Boolean.getBoolean("enderframe.layout.noparallel");

    private static final ForkJoinPool POOL = createPool();
    private static final AtomicLong PARALLEL_DISPATCHES = new AtomicLong();

    private LayoutTaskPool() {}

    private static ForkJoinPool createPool() {
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        return new ForkJoinPool(threads, ForkJoinPool.defaultForkJoinWorkerThreadFactory, null, false);
    }

    /** 推奨されるワーカー並列数。 */
    public static int workerCount() {
        return Math.max(1, POOL.getParallelism());
    }

    /** 並列プールへディスパッチした累計回数（テスト・計測用）。 */
    public static long parallelDispatchCount() {
        return PARALLEL_DISPATCHES.get();
    }

    public static void resetParallelDispatchCount() {
        PARALLEL_DISPATCHES.set(0);
    }

    /** 現在のスレッドがすでにレイアウト並列プール内のワーカーか（ネストした再フォークを防ぐ）。 */
    public static boolean inWorker() {
        return Thread.currentThread() instanceof ForkJoinWorkerThread wt && wt.getPool() == POOL;
    }

    /** チャンク呼び出しに渡す 64bit スパン値からチャンク番号を取り出す。 */
    public static int spanChunk(long span) {
        return (int) (span >>> 48);
    }

    /** チャンク呼び出しに渡す 64bit スパン値から開始インデックス（含む）を取り出す。 */
    public static int spanStart(long span) {
        return (int) ((span >>> 24) & 0xFF_FFFFL);
    }

    /** チャンク呼び出しに渡す 64bit スパン値から終了インデックス（含まない）を取り出す。 */
    public static int spanEnd(long span) {
        return (int) (span & 0xFF_FFFFL);
    }

    private static long packSpan(int chunk, int start, int end) {
        return (((long) chunk) << 48) | (((long) start & 0xFF_FFFFL) << 24) | ((long) end & 0xFF_FFFFL);
    }

    /**
     * {@code [0, count)} を並列または直列に処理する。
     *
     * @param count       要素数
     * @param minGrain    1タスクにまとめる最小要素数
     * @param scopeRunner 各リーフ実行を包むスコープ（不要なら {@code null}）
     * @param task        各インデックスに対する処理
     */
    public static void forRange(int count, int minGrain, Consumer<Runnable> scopeRunner, IntConsumer task) {
        if (count <= 0) return;
        int grain = Math.max(1, minGrain);
        if (!parallelEnabled || count < Math.max(PARALLEL_THRESHOLD, grain * 2)
                || POOL.getParallelism() <= 1 || inWorker()) {
            for (int i = 0; i < count; i++) {
                task.accept(i);
            }
            return;
        }
        PARALLEL_DISPATCHES.incrementAndGet();
        POOL.invoke(new RangeTask(0, count, grain, scopeRunner, task));
    }

    /**
     * {@code [0, itemCount)} を最大 {@code desiredChunks} 個の連続スパンに分け、並列または直列に実行する。
     * 各スパンは {@link #spanChunk(long)} / {@link #spanStart(long)} / {@link #spanEnd(long)} で受け取る。
     */
    public static void forChunks(int itemCount, int desiredChunks, Consumer<Runnable> scopeRunner, LongConsumer consumer) {
        if (itemCount <= 0) return;
        int chunks = Math.max(1, Math.min(desiredChunks, itemCount));
        if (!parallelEnabled || itemCount < PARALLEL_THRESHOLD || chunks <= 1
                || POOL.getParallelism() <= 1 || inWorker()) {
            consumer.accept(packSpan(0, 0, itemCount));
            return;
        }
        PARALLEL_DISPATCHES.incrementAndGet();
        POOL.invoke(new ChunkTask(0, chunks, chunks, itemCount, scopeRunner, consumer));
    }

    private static final class RangeTask extends RecursiveAction {
        private final int start;
        private final int end;
        private final int grain;
        private final Consumer<Runnable> scopeRunner;
        private final IntConsumer task;

        RangeTask(int start, int end, int grain, Consumer<Runnable> scopeRunner, IntConsumer task) {
            this.start = start;
            this.end = end;
            this.grain = grain;
            this.scopeRunner = scopeRunner;
            this.task = task;
        }

        @Override
        protected void compute() {
            int len = end - start;
            if (len <= grain) {
                Runnable leaf = () -> {
                    for (int i = start; i < end; i++) {
                        task.accept(i);
                    }
                };
                if (scopeRunner != null) scopeRunner.accept(leaf);
                else leaf.run();
                return;
            }
            int mid = start + (len >>> 1);
            RangeTask left = new RangeTask(start, mid, grain, scopeRunner, task);
            RangeTask right = new RangeTask(mid, end, grain, scopeRunner, task);
            left.fork();
            right.compute();
            left.join();
        }
    }

    private static final class ChunkTask extends RecursiveAction {
        private final int firstChunk;
        private final int lastChunk;
        private final int totalChunks;
        private final int totalItems;
        private final Consumer<Runnable> scopeRunner;
        private final LongConsumer consumer;

        ChunkTask(int firstChunk, int lastChunk, int totalChunks, int totalItems,
                  Consumer<Runnable> scopeRunner, LongConsumer consumer) {
            this.firstChunk = firstChunk;
            this.lastChunk = lastChunk;
            this.totalChunks = totalChunks;
            this.totalItems = totalItems;
            this.scopeRunner = scopeRunner;
            this.consumer = consumer;
        }

        @Override
        protected void compute() {
            if (lastChunk - firstChunk <= 1) {
                int c = firstChunk;
                int start = (int) (((long) c * totalItems) / totalChunks);
                int end = (int) (((long) (c + 1) * totalItems) / totalChunks);
                Runnable leaf = () -> consumer.accept(packSpan(c, start, end));
                if (scopeRunner != null) scopeRunner.accept(leaf);
                else leaf.run();
                return;
            }
            int mid = (firstChunk + lastChunk) >>> 1;
            ChunkTask left = new ChunkTask(firstChunk, mid, totalChunks, totalItems, scopeRunner, consumer);
            ChunkTask right = new ChunkTask(mid, lastChunk, totalChunks, totalItems, scopeRunner, consumer);
            left.fork();
            right.compute();
            left.join();
        }
    }
}
