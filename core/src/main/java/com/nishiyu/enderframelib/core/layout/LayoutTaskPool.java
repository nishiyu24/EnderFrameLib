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
 * 並列・直列の結果の等価性検証に使う。ワーカー数は {@code -Denderframe.threads=N} か {@link #setWorkerThreads} で決める。
 * ソフトウェア描画のタイル実行（{@link #forEachIndex}）も同じプールを使う。</p>
 */
public final class LayoutTaskPool {

    /** これ未満の作業数ではフォークのオーバーヘッドを避けて直列実行する。 */
    public static final int PARALLEL_THRESHOLD = 32;

    /** {@code -Denderframe.layout.noparallel=true} で並列実行を無効化する切り替え。 */
    public static volatile boolean parallelEnabled = !Boolean.getBoolean("enderframe.layout.noparallel");

    /** プールのワーカー。入れ子の再フォークを防ぐ判定（{@link #inWorker}）は、プールを作り直しても効くようこの型で見る。 */
    private static final class Worker extends ForkJoinWorkerThread {
        Worker(ForkJoinPool pool) {
            super(pool);
            setName("enderframe-worker-" + getPoolIndex());
            setDaemon(true);
        }
    }

    private static volatile ForkJoinPool pool = createPool(defaultThreads());
    private static final AtomicLong PARALLEL_DISPATCHES = new AtomicLong();

    private LayoutTaskPool() {}

    /**
     * 既定のワーカー数。{@code -Denderframe.threads=N} があればその値（1 以下は直列）、無ければ論理コア数 − 1。
     * ゲームなど他の処理とコアを分け合うホストは、小さい値を指定するか {@link #setWorkerThreads} で実行時に変える。
     */
    public static int defaultThreads() {
        Integer configured = Integer.getInteger("enderframe.threads");
        if (configured != null) return Math.max(1, configured);
        return Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    }

    private static ForkJoinPool createPool(int threads) {
        return new ForkJoinPool(Math.max(1, threads), Worker::new, null, false);
    }

    /**
     * ワーカー数を実行時に変える（レイアウトの並列実行とソフトウェア描画のタイル実行の両方に効く）。1 以下なら直列。
     * 結果（箱の木・表示リスト・画素）はワーカー数に依らない。進行中のフレームが無いときに呼ぶこと。
     */
    public static synchronized void setWorkerThreads(int threads) {
        int n = Math.max(1, threads);
        ForkJoinPool old = pool;
        if (old.getParallelism() == n) return;
        pool = createPool(n);
        old.shutdown();
    }

    /** 推奨されるワーカー並列数。 */
    public static int workerCount() {
        return Math.max(1, pool.getParallelism());
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
        return Thread.currentThread() instanceof Worker;
    }

    /**
     * {@code [0, count)} の各番号を、互いに独立な仕事として並列（ワーカーが 2 本以上で、呼び出し元がワーカーでないとき）または
     * 直列に実行する。件数のしきい値は設けない（呼び出し側が「並列にする価値がある」と判断済みの仕事用。ソフトウェア描画のタイル）。
     */
    public static void forEachIndex(int count, IntConsumer task) {
        if (count <= 0) return;
        ForkJoinPool p = pool;
        if (count == 1 || p.getParallelism() <= 1 || inWorker()) {
            for (int i = 0; i < count; i++) task.accept(i);
            return;
        }
        p.invoke(new RangeTask(0, count, 1, null, task));
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
        ForkJoinPool p = pool;
        if (!parallelEnabled || count < Math.max(PARALLEL_THRESHOLD, grain * 2)
                || p.getParallelism() <= 1 || inWorker()) {
            for (int i = 0; i < count; i++) {
                task.accept(i);
            }
            return;
        }
        PARALLEL_DISPATCHES.incrementAndGet();
        p.invoke(new RangeTask(0, count, grain, scopeRunner, task));
    }

    /**
     * {@code [0, itemCount)} を最大 {@code desiredChunks} 個の連続スパンに分け、並列または直列に実行する。
     * 各スパンは {@link #spanChunk(long)} / {@link #spanStart(long)} / {@link #spanEnd(long)} で受け取る。
     */
    public static void forChunks(int itemCount, int desiredChunks, Consumer<Runnable> scopeRunner, LongConsumer consumer) {
        if (itemCount <= 0) return;
        int chunks = Math.max(1, Math.min(desiredChunks, itemCount));
        ForkJoinPool p = pool;
        if (!parallelEnabled || itemCount < PARALLEL_THRESHOLD || chunks <= 1
                || p.getParallelism() <= 1 || inWorker()) {
            consumer.accept(packSpan(0, 0, itemCount));
            return;
        }
        PARALLEL_DISPATCHES.incrementAndGet();
        p.invoke(new ChunkTask(0, chunks, chunks, itemCount, scopeRunner, consumer));
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
