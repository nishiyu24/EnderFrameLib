package com.nishiyu.enderframelib.core.kernel.gpu;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 固定長レコードの配列を持つ、GPU（SSBO）向けのバッファ。1 レコード = 1 スロット（添字 {@code slot}）。
 *
 * <p>書き込み先は 2 通り（{@link Device} が決める）:</p>
 * <ul>
 *   <li><b>永続マップ</b>（{@code GL_ARB_buffer_storage} / {@code GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT}）:
 *       {@link Device#mapPersistent} が返した GPU メモリそのものへ直接書く。{@link #flush()} は何も転送しない。
 *       GPU メモリは書き込み専用（ライトコンバインで、読むと桁違いに遅い）なので、変更の検出と読み出しは CPU 側の影のコピーで行う。</li>
 *   <li><b>フォールバック</b>: オフヒープのステージングバッファへ書き、{@link #flush()} が汚れた範囲だけ
 *       {@link Device#subData}（{@code glBufferSubData}）へ送る。</li>
 * </ul>
 *
 * <p>どちらも書き込みは割り当て 0。スロットの確保・解放も事前確保の {@code int[]} スタックで行う。
 * 同じ値の書き込みは汚れにしない（{@link #putInt}/{@link #putFloat} は変化したときだけ範囲を広げる）。</p>
 *
 * <p>GL 非依存（{@code Device} を実装するのは描画側）。テストは {@link Device} の偽実装で転送量を数える。</p>
 */
public final class PersistentVramBuffer {
    /** 受け口。GL 実装は {@code glMapBufferRange} / {@code glBufferSubData} に対応づける。 */
    public interface Device {
        /** 永続マップが使えるなら、{@code bytes} バイトの書き込み可能なバッファ。使えなければ {@code null}。 */
        ByteBuffer mapPersistent(long bytes);

        /** フォールバック: {@code [offset, offset+length)} を GPU 側へ転送する。{@code src} は呼び出しの間だけ読む。 */
        void subData(ByteBuffer src, int offset, int length);
    }

    private static final VarHandle INTS = MethodHandles.byteBufferViewVarHandle(int[].class, ByteOrder.nativeOrder());
    private static final VarHandle FLOATS = MethodHandles.byteBufferViewVarHandle(float[].class, ByteOrder.nativeOrder());
    private static final VarHandle SHORTS = MethodHandles.byteBufferViewVarHandle(short[].class, ByteOrder.nativeOrder());

    private final int stride;
    private final int capacity;
    private final Device device;
    /** CPU 側の影。変更の検出と読み出しはここで行う（永続マップの GPU メモリはライトコンバインで、読むと桁違いに遅い）。 */
    private final ByteBuffer data;
    /** 永続マップした GPU メモリ。書き込み専用（読んではならない）。マップしていなければ null。 */
    private final ByteBuffer gpu;
    private final boolean persistent;
    private final int[] freeStack;
    private int freeTop;
    private int highWater;
    private int dirtyLo = Integer.MAX_VALUE;
    private int dirtyHi = -1;
    private long uploadedBytes;

    public PersistentVramBuffer(int stride, int capacity, Device device) {
        if (stride <= 0 || (stride & 3) != 0) throw new IllegalArgumentException("stride: " + stride);
        if (capacity <= 0 || (long) stride * capacity > Integer.MAX_VALUE - 64) throw new IllegalArgumentException("capacity: " + capacity);
        this.stride = stride;
        this.capacity = capacity;
        this.device = device;
        long bytes = (long) stride * capacity;
        ByteBuffer mapped = device == null ? null : device.mapPersistent(bytes);
        ByteBuffer raw = ByteBuffer.allocateDirect((int) bytes + 64);
        int off = (64 - raw.alignmentOffset(0, 64)) & 63;
        this.data = raw.slice(off, (int) bytes).order(ByteOrder.nativeOrder());
        this.gpu = mapped == null ? null : mapped.order(ByteOrder.nativeOrder());
        this.persistent = mapped != null;
        this.freeStack = new int[capacity];
    }

    public int stride() { return stride; }
    public int capacity() { return capacity; }
    public boolean isPersistent() { return persistent; }
    /** 確保済みのスロット数。 */
    public int liveCount() { return highWater - freeTop; }
    /** これまでに使った最大の添字 + 1（GPU が読む範囲の上限）。 */
    public int highWater() { return highWater; }
    /** 累計の転送バイト数（永続マップでは常に 0）。 */
    public long uploadedBytes() { return uploadedBytes; }

    /** スロットを 1 つ確保する。空きが無ければ -1。解放済みの番号を先に再利用する。 */
    public int allocate() {
        if (freeTop > 0) return freeStack[--freeTop];
        if (highWater >= capacity) return -1;
        return highWater++;
    }

    /** スロットを返す（中身は 0 クリアして汚れにする）。二重解放はしないこと。 */
    public void free(int slot) {
        check(slot);
        for (int o = 0; o < stride; o += 4) putInt(slot, o, 0);
        freeStack[freeTop++] = slot;
    }

    public void putInt(int slot, int byteOffset, int v) {
        int at = at(slot, byteOffset, 4);
        if ((int) INTS.get(data, at) == v) return;
        INTS.set(data, at, v);
        if (gpu != null) INTS.set(gpu, at, v);
        touch(slot);
    }

    public void putFloat(int slot, int byteOffset, float v) {
        int at = at(slot, byteOffset, 4);
        if (Float.floatToRawIntBits((float) FLOATS.get(data, at)) == Float.floatToRawIntBits(v)) return;
        FLOATS.set(data, at, v);
        if (gpu != null) FLOATS.set(gpu, at, v);
        touch(slot);
    }

    /** 16-bit 浮動小数（IEEE half）として書く。 */
    public void putHalf(int slot, int byteOffset, float v) {
        int at = at(slot, byteOffset, 2);
        short bits = Float.floatToFloat16(v);
        if ((short) SHORTS.get(data, at) == bits) return;
        SHORTS.set(data, at, bits);
        if (gpu != null) SHORTS.set(gpu, at, bits);
        touch(slot);
    }

    public void putShort(int slot, int byteOffset, int v) {
        int at = at(slot, byteOffset, 2);
        if ((short) SHORTS.get(data, at) == (short) v) return;
        SHORTS.set(data, at, (short) v);
        if (gpu != null) SHORTS.set(gpu, at, (short) v);
        touch(slot);
    }

    public int getInt(int slot, int byteOffset) { return (int) INTS.get(data, at(slot, byteOffset, 4)); }
    public float getFloat(int slot, int byteOffset) { return (float) FLOATS.get(data, at(slot, byteOffset, 4)); }
    public float getHalf(int slot, int byteOffset) { return Float.float16ToFloat((short) SHORTS.get(data, at(slot, byteOffset, 2))); }
    public int getShort(int slot, int byteOffset) { return (short) SHORTS.get(data, at(slot, byteOffset, 2)) & 0xFFFF; }

    /** 汚れたスロットがあるか。 */
    public boolean isDirty() { return dirtyHi >= 0; }

    /**
     * 汚れた範囲を GPU へ送る。永続マップなら転送は無く（コヒーレントなので）範囲を捨てるだけ。
     *
     * @return 今回転送したバイト数
     */
    public int flush() {
        if (dirtyHi < 0) return 0;
        int sent = 0;
        if (!persistent && device != null) {
            int off = dirtyLo * stride;
            sent = (dirtyHi - dirtyLo + 1) * stride;
            device.subData(data.duplicate().order(ByteOrder.nativeOrder()), off, sent);
            uploadedBytes += sent;
        }
        dirtyLo = Integer.MAX_VALUE;
        dirtyHi = -1;
        return sent;
    }

    private void touch(int slot) {
        if (slot < dirtyLo) dirtyLo = slot;
        if (slot > dirtyHi) dirtyHi = slot;
    }

    private void check(int slot) {
        if (slot < 0 || slot >= highWater) throw new IndexOutOfBoundsException(slot);
    }

    private int at(int slot, int byteOffset, int width) {
        check(slot);
        if (byteOffset < 0 || byteOffset + width > stride) throw new IndexOutOfBoundsException(byteOffset);
        return slot * stride + byteOffset;
    }
}
