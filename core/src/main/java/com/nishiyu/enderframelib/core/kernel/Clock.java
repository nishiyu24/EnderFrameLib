package com.nishiyu.enderframelib.core.kernel;

/**
 * コアが時刻を読む唯一の入口（設計書 {@code DESIGN_INCREMENTAL_KERNEL.md} §2 K5・計画書 K0 ①）。
 *
 * <p>コアは {@code System.nanoTime}・{@code currentTimeMillis} を直接読まない。実時計を読むのはこのファイルの {@link #SYSTEM} だけで、
 * 時刻に依る振る舞い〔リサイズの間引き・タイマー・アニメーション・診断の所要時間〕は注入された {@code Clock} を通す。
 * テストは {@link Manual} を注入し、実時間を待たず（{@code Thread.sleep} 無しで）決定的に進める。
 * 増加は {@code IncrementalStateRatchetTest} が赤にする。</p>
 */
public interface Clock {
    /** 単調な時刻（ns）。差だけに意味がある。 */
    long nanos();

    /** 壁時計の時刻（epoch ms）。 */
    long millis();

    /** 実時計。コアで {@code System.nanoTime}・{@code currentTimeMillis} を読む唯一の場所。 */
    Clock SYSTEM = new Clock() {
        @Override public long nanos() { return System.nanoTime(); }
        @Override public long millis() { return System.currentTimeMillis(); }
    };

    /** 手動で進める時計（テスト用）。{@link #advanceNanos} しない限り止まったまま。スレッド安全。 */
    final class Manual implements Clock {
        private final java.util.concurrent.atomic.AtomicLong nanos;

        public Manual() { this(0L); }

        public Manual(long startNanos) { this.nanos = new java.util.concurrent.atomic.AtomicLong(startNanos); }

        @Override public long nanos() { return nanos.get(); }

        @Override public long millis() { return nanos.get() / 1_000_000L; }

        public Manual advanceNanos(long delta) { nanos.addAndGet(delta); return this; }

        public Manual advanceMillis(long delta) { return advanceNanos(delta * 1_000_000L); }
    }
}
