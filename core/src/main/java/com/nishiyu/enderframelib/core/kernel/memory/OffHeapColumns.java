package com.nishiyu.enderframelib.core.kernel.memory;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Off-Heap の SoA 列。標準 JDK 21 のダイレクト {@link ByteBuffer} と
 * {@link MethodHandles#byteBufferViewVarHandle} だけで作る（Panama FFM のプレビュー API も
 * JVM フラグも要らない）。C2 は static final の VarHandle アクセスを単発のロード/ストアに展開する。
 *
 * <p>先頭は 64 バイト境界（キャッシュライン）に揃え、0 クリアされる。添字が範囲外なら例外。</p>
 */
public final class OffHeapColumns {
    public static final int ALIGNMENT = 64;

    private static final VarHandle FLOATS = MethodHandles.byteBufferViewVarHandle(float[].class, ByteOrder.nativeOrder());
    private static final VarHandle INTS = MethodHandles.byteBufferViewVarHandle(int[].class, ByteOrder.nativeOrder());
    private static final VarHandle LONGS = MethodHandles.byteBufferViewVarHandle(long[].class, ByteOrder.nativeOrder());

    private OffHeapColumns() {}

    /** 64 バイト境界に揃ったダイレクトバッファ（0 クリア済み、ネイティブ順序）。 */
    static ByteBuffer allocateAligned(long bytes) {
        if (bytes < 0 || bytes > Integer.MAX_VALUE - ALIGNMENT) throw new IllegalArgumentException("size: " + bytes);
        ByteBuffer raw = ByteBuffer.allocateDirect((int) bytes + ALIGNMENT);
        int offset = (ALIGNMENT - raw.alignmentOffset(0, ALIGNMENT)) & (ALIGNMENT - 1); // 境界まで進める量（alignedSlice は末尾も切り詰めるので使わない）
        return raw.slice(offset, (int) bytes).order(ByteOrder.nativeOrder());
    }

    /** {@code float} の列。 */
    public static final class Floats {
        private final ByteBuffer buf;
        private final int length;

        public Floats(int length) {
            this.length = length;
            this.buf = allocateAligned(4L * length);
        }

        public int length() { return length; }
        public float get(int i) { return (float) FLOATS.get(buf, check(i) << 2); }
        public void set(int i, float v) { FLOATS.set(buf, check(i) << 2, v); }
        public void fill(float v) { for (int i = 0; i < length; i++) FLOATS.set(buf, i << 2, v); }
        /** ヒープ配列へ一括コピー（自動ベクトル化されるループへ渡す用）。 */
        public void copyTo(float[] dst, int n) { buf.asFloatBuffer().get(0, dst, 0, n); }
        public void copyFrom(float[] src, int n) { buf.asFloatBuffer().put(0, src, 0, n); }
        public boolean isAligned() { return buf.alignmentOffset(0, ALIGNMENT) == 0; }
        private int check(int i) { if (i < 0 || i >= length) throw new IndexOutOfBoundsException(i); return i; }
    }

    /** {@code int} の列。 */
    public static final class Ints {
        private final ByteBuffer buf;
        private final int length;

        public Ints(int length) {
            this.length = length;
            this.buf = allocateAligned(4L * length);
        }

        public int length() { return length; }
        public int get(int i) { return (int) INTS.get(buf, check(i) << 2); }
        public void set(int i, int v) { INTS.set(buf, check(i) << 2, v); }
        public void fill(int v) { for (int i = 0; i < length; i++) INTS.set(buf, i << 2, v); }
        public boolean isAligned() { return buf.alignmentOffset(0, ALIGNMENT) == 0; }
        private int check(int i) { if (i < 0 || i >= length) throw new IndexOutOfBoundsException(i); return i; }
    }

    /** {@code long} の列（Bloom マスクなど）。 */
    public static final class Longs {
        private final ByteBuffer buf;
        private final int length;

        public Longs(int length) {
            this.length = length;
            this.buf = allocateAligned(8L * length);
        }

        public int length() { return length; }
        public long get(int i) { return (long) LONGS.get(buf, check(i) << 3); }
        public void set(int i, long v) { LONGS.set(buf, check(i) << 3, v); }
        public boolean isAligned() { return buf.alignmentOffset(0, ALIGNMENT) == 0; }
        private int check(int i) { if (i < 0 || i >= length) throw new IndexOutOfBoundsException(i); return i; }
    }
}
