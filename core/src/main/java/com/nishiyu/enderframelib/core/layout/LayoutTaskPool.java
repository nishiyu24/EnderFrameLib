package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.kernel.EngineFlags;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
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
    public static volatile boolean parallelEnabled = EngineFlags.LAYOUT_PARALLEL;

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
        Integer configured = EngineFlags.THREADS;
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

    /** 呼び出し側のスレッドが、並列区間の仕事を自分でも取って実行している最中か（{@link #runShared}）。 */
    private static final ThreadLocal<boolean[]> CALLER_IN_TASK = ThreadLocal.withInitial(() -> new boolean[1]);

    /**
     * 現在のスレッドが並列区間の仕事を実行中か（ネストした再フォークを防ぐ）。プールのワーカーに加えて、
     * 並列区間を始めて自分も仕事を取っている呼び出し側のスレッドも真。
     */
    public static boolean inWorker() {
        Thread t = Thread.currentThread();
        return t instanceof Worker || CALLER_IN_TASK.get()[0];
    }

    /**
     * 1 つの並列区間に加わるスレッドの数（ワーカー＋呼び出し側）。分割数を決める側が使う。直列なら 1。
     * 呼び出し側は待つ間も仕事を取るので、ワーカーが {@code N} 本なら {@code N + 1} 本で回る（B11）。
     */
    public static int participants() {
        int w = workerCount();
        return w <= 1 ? 1 : w + 1;
    }

    /**
     * 1 つの並列区間を、加わるスレッド 1 本あたり何個の仕事に割るか。1 本に 1 個だと、起きるのが遅いワーカーの分を
     * 他のスレッドが拾えず待つ。{@code -Denderframe.split=N} で変えられる（計測用）。
     */
    static final int SPLIT_PER_PARTICIPANT = Math.max(1, EngineFlags.SPLIT_PER_PARTICIPANT);

    /** {@link #forRange} に渡す粒度: {@code n} 件を {@link #SPLIT_PER_PARTICIPANT} × 加わる本数に割る（{@code minGrain} 件以上）。 */
    public static int grainFor(int n, int minGrain) {
        return Math.max(minGrain, n / (participants() * SPLIT_PER_PARTICIPANT));
    }

    /** {@link #forChunks} に渡す区間の数: {@link #grainFor} と同じ割り方で、1 区間 {@code minPerChunk} 件以上。 */
    public static int chunksFor(int n, int minPerChunk) {
        if (participants() <= 1) return 1; // 直列なら割らない（区間ごとの一覧・数えの配列を作らない）
        return Math.max(1, Math.min(participants() * SPLIT_PER_PARTICIPANT, (n + minPerChunk - 1) / minPerChunk));
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
        runShared(p, count, null, task);
    }

    /** 並列区間 1 回分の共有状態。仕事の番号を先着で配り、加わったヘルパーが全員抜けるのを呼び出し側が待つ。 */
    private static final class SharedRun implements Runnable {
        private final int chunks;
        private final Consumer<Runnable> scopeRunner;
        private final IntConsumer chunkBody;
        private final java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
        /** 仕事を取りに入って、まだ抜けていないヘルパーの数。 */
        private final java.util.concurrent.atomic.AtomicInteger active = new java.util.concurrent.atomic.AtomicInteger();
        private final Thread caller;
        private volatile Throwable failure;

        SharedRun(int chunks, Consumer<Runnable> scopeRunner, IntConsumer chunkBody, Thread caller) {
            this.chunks = chunks;
            this.scopeRunner = scopeRunner;
            this.chunkBody = chunkBody;
            this.caller = caller;
        }

        /** ヘルパー（プールのワーカー）側。仕事を取る前に必ず {@link #active} を増やす（呼び出し側の待ちの条件）。 */
        @Override
        public void run() {
            active.incrementAndGet();
            try {
                work();
            } finally {
                if (active.decrementAndGet() == 0) java.util.concurrent.locks.LockSupport.unpark(caller);
            }
        }

        void work() {
            int c;
            while (failure == null && (c = next.getAndIncrement()) < chunks) {
                final int chunk = c;
                try {
                    if (scopeRunner != null) scopeRunner.accept(() -> chunkBody.accept(chunk));
                    else chunkBody.accept(chunk);
                } catch (Throwable t) {
                    if (failure == null) failure = t;
                }
            }
        }

        /**
         * 呼び出し側が自分の取り分を終えた後に、仕事を持っているヘルパーが抜けるまで待つ。ここに来た時点で番号は配り終えている
         * （{@link #next} が {@link #chunks} 以上）。番号を取ったヘルパーは取る前に {@link #active} を増やしているので、
         * 0 を見たら残りの仕事は無い。まだ始まっていないヘルパーは、後で始まっても番号を取れずに抜ける。
         */
        void awaitHelpers() {
            int spins = 0;
            while (active.get() != 0) {
                if (++spins < 2_000) Thread.onSpinWait();
                else java.util.concurrent.locks.LockSupport.parkNanos(this, 50_000L);
            }
        }
    }

    /**
     * {@code chunks} 個の仕事を、プールのワーカーと呼び出し側のスレッドで先着順に分け合う。以前は {@code ForkJoinPool.invoke}
     * で、呼び出し側（ホストの描画スレッド）は区間の間ずっと待つだけだった（B11: ワーカー 4 本の varied-2000 `fontsize` で
     * main の標本の 7 割が待ち）。結果は誰がどの番号を実行しても同じ（番号ごとに独立な仕事だけを渡すこと）。
     */
    private static void runShared(ForkJoinPool p, int chunks, Consumer<Runnable> scopeRunner, IntConsumer chunkBody) {
        PARALLEL_DISPATCHES.incrementAndGet();
        SharedRun run = new SharedRun(chunks, scopeRunner, chunkBody, Thread.currentThread());
        int helpers = Math.min(p.getParallelism(), chunks - 1);
        for (int i = 0; i < helpers; i++) p.execute(run);
        boolean[] flag = CALLER_IN_TASK.get();
        flag[0] = true;
        try {
            run.work();
        } finally {
            flag[0] = false;
        }
        run.awaitHelpers();
        Throwable t = run.failure;
        if (t instanceof RuntimeException re) throw re;
        if (t instanceof Error e) throw e;
        if (t != null) throw new java.util.concurrent.CompletionException(t);
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
        int chunks = (count + grain - 1) / grain;
        runShared(p, chunks, scopeRunner, c -> {
            int start = (int) (((long) c * count) / chunks);
            int end = (int) (((long) (c + 1) * count) / chunks);
            for (int i = start; i < end; i++) task.accept(i);
        });
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
        final int total = chunks;
        runShared(p, total, scopeRunner, c -> {
            int start = (int) (((long) c * itemCount) / total);
            int end = (int) (((long) (c + 1) * itemCount) / total);
            consumer.accept(packSpan(c, start, end));
        });
    }
}
