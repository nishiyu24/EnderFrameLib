package com.nishiyu.enderframelib.core.kernel.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** 3 面リングの {@link PersistentVramBuffer}: 書き込みは現在の面だけへ、どの面も最終的に影と同じ内容になる。 */
class VramRingTest {

    /** N 面の偽デバイス。endFrame で面を進め、待った面の記録を残す。 */
    static final class RingDevice implements PersistentVramBuffer.Device {
        final ByteBuffer[] segs;
        final int n;
        int cur;
        final java.util.List<Integer> awaited = new java.util.ArrayList<>();
        int subDataCalls;

        RingDevice(int n) { this.n = n; this.segs = new ByteBuffer[n]; }

        @Override public ByteBuffer mapPersistent(long bytes) {
            for (int i = 0; i < n; i++) segs[i] = ByteBuffer.allocateDirect((int) bytes).order(ByteOrder.nativeOrder());
            return segs[0];
        }
        @Override public void subData(ByteBuffer src, int offset, int length) { subDataCalls++; }
        @Override public int segmentCount() { return n; }
        @Override public ByteBuffer persistentSegment(int i) { return segs[i]; }
        @Override public int currentSegment() { return cur; }
        @Override public void awaitSegment(int i) { awaited.add(i); }
        void endFrame() { cur = (cur + 1) % n; }
    }

    private static int intAt(RingDevice d, int seg, int byteIndex) { return d.segs[seg].getInt(byteIndex); }

    @Test
    void writesStayInTheShadowUntilFlushAndOnlyTheCurrentSegmentReceivesThem() {
        RingDevice dev = new RingDevice(3);
        PersistentVramBuffer b = new PersistentVramBuffer(64, 16, dev);
        assertTrue(b.isPersistent());
        assertEquals(3, b.segments());
        int s = b.allocate();
        b.putInt(s, 8, 0xBEEF);
        assertEquals(0, intAt(dev, 0, s * 64 + 8), "flush するまで GPU のメモリへは触らない");
        assertEquals(0xBEEF, b.getInt(s, 8), "影からは読める");
        assertTrue(b.isDirty());
        assertEquals(0, b.flush(), "リングへの書き込みは転送（subData）として数えない");
        assertEquals(0xBEEF, intAt(dev, 0, s * 64 + 8));
        assertEquals(0, intAt(dev, 1, s * 64 + 8), "他の面はまだ");
        assertEquals(0, intAt(dev, 2, s * 64 + 8));
        assertEquals(0, dev.subDataCalls);
        assertEquals(64, b.ringCopiedBytes(), "汚れた 1 スロット分だけ写す");
        assertFalse(b.isDirty());
    }

    @Test
    void everySegmentCatchesUpWithChangesMadeWhileItWasNotCurrent() {
        RingDevice dev = new RingDevice(3);
        PersistentVramBuffer b = new PersistentVramBuffer(64, 16, dev);
        int a = b.allocate(), c = b.allocate();
        b.putInt(a, 0, 1);
        b.flush();            // 面 0 へ
        dev.endFrame();
        b.putInt(c, 0, 2);
        b.flush();            // 面 1 へ。a の変更（面 0 の時点）も持ち越されている
        assertEquals(1, intAt(dev, 1, a * 64), "前のフレームの変更も次の面に写る");
        assertEquals(2, intAt(dev, 1, c * 64));
        dev.endFrame();
        b.flush();            // 面 2 へ。a・c どちらも
        assertEquals(1, intAt(dev, 2, a * 64));
        assertEquals(2, intAt(dev, 2, c * 64));
        assertEquals(0, intAt(dev, 0, c * 64), "面 0 はまだ c を知らない（次に現在になったとき写る）");
        dev.endFrame();       // 面 0 に戻る
        b.flush();
        assertEquals(2, intAt(dev, 0, c * 64));
    }

    @Test
    void segmentIsAwaitedBeforeItIsWrittenAndNotWhenNothingChanged() {
        RingDevice dev = new RingDevice(3);
        PersistentVramBuffer b = new PersistentVramBuffer(32, 8, dev);
        int s = b.allocate();
        b.flush();
        assertTrue(dev.awaited.isEmpty(), "変更が無ければ待たない");
        b.putFloat(s, 4, 2.5f);
        b.flush();
        assertEquals(java.util.List.of(0), dev.awaited, "書く面を待ってから写す");
        dev.endFrame();
        b.putFloat(s, 4, 3.5f);
        b.flush();
        assertEquals(java.util.List.of(0, 1), dev.awaited);
    }

    @Test
    void randomWorkloadLeavesEverySegmentEqualToTheShadowAfterAFullCycle() {
        for (int ringSize : new int[]{2, 3, 4}) {
            RingDevice dev = new RingDevice(ringSize);
            int cap = 40, stride = 32;
            PersistentVramBuffer b = new PersistentVramBuffer(stride, cap, dev);
            for (int i = 0; i < cap; i++) b.allocate();
            Random r = new Random(ringSize * 7919L);
            for (int frame = 0; frame < 60; frame++) {
                int edits = r.nextInt(6);
                for (int e = 0; e < edits; e++) {
                    int slot = r.nextInt(cap), off = r.nextInt(stride / 4) * 4;
                    if (r.nextBoolean()) b.putInt(slot, off, r.nextInt());
                    else b.putFloat(slot, off, r.nextFloat());
                }
                b.flush();
                // 現在の面は影と一致する（毎フレーム）
                for (int slot = 0; slot < cap; slot++) {
                    for (int off = 0; off < stride; off += 4) {
                        assertEquals(b.getInt(slot, off), intAt(dev, dev.cur, slot * stride + off),
                                "ring=" + ringSize + " frame=" + frame + " slot=" + slot);
                    }
                }
                dev.endFrame();
            }
        }
    }

    @Test
    void steadyStateWritesCopyOnlyTheChangedRangeNotTheWholeBuffer() {
        RingDevice dev = new RingDevice(3);
        PersistentVramBuffer b = new PersistentVramBuffer(64, 1000, dev);
        for (int i = 0; i < 1000; i++) b.allocate();
        b.putInt(0, 0, 1);
        for (int f = 0; f < 3; f++) { b.flush(); dev.endFrame(); } // 3 面すべてがスロット 0 の変更を受け取る
        long before = b.ringCopiedBytes();
        for (int f = 0; f < 9; f++) { b.putInt(500, 0, f + 10); b.flush(); dev.endFrame(); }
        long perFrame = (b.ringCopiedBytes() - before) / 9;
        assertTrue(perFrame <= 64 * 3, "1 フレームの写しは変更したスロットを含む小さな範囲: " + perFrame);
    }

    @Test
    void singleSegmentDeviceKeepsTheImmediateWriteBehaviour() {
        RingDevice dev = new RingDevice(1);
        PersistentVramBuffer b = new PersistentVramBuffer(64, 4, dev);
        assertEquals(1, b.segments());
        int s = b.allocate();
        b.putInt(s, 0, 9);
        assertEquals(9, intAt(dev, 0, s * 64), "1 面なら従来どおり直接書く");
        assertEquals(0, b.ringCopiedBytes());
    }

    @Test
    void flushDoesNotAllocateInRingMode() {
        RingDevice dev = new RingDevice(3);
        PersistentVramBuffer b = new PersistentVramBuffer(64, 64, dev);
        for (int i = 0; i < 64; i++) b.allocate();
        for (int w = 0; w < 2000; w++) { b.putInt(w & 63, 0, w); b.flush(); dev.endFrame(); dev.awaited.clear(); }
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long id = Thread.currentThread().getId();
        long before = mx.getThreadAllocatedBytes(id);
        for (int w = 0; w < 5000; w++) { b.putInt(w & 63, 0, w + 7); b.flush(); dev.endFrame(); if ((w & 63) == 0) dev.awaited.clear(); }
        long used = mx.getThreadAllocatedBytes(id) - before;
        assertTrue(used < 64 * 1024, "割り当てほぼ 0（awaited 記録用の Integer 以外）: " + used);
    }
}
