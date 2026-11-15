package com.nishiyu.enderframelib.core.kernel.memory;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 64 バイト境界に揃ったオフヒープ {@code float} 列のトップレベル表現（パッケージ外からの参照用）。
 */
public final class OffHeapFloatColumn {
    private static final VarHandle FLOATS = MethodHandles.byteBufferViewVarHandle(float[].class, ByteOrder.nativeOrder());

    private final ByteBuffer buf;
    private final int length;

    public OffHeapFloatColumn(int length) {
        this.length = length;
        this.buf = OffHeapColumns.allocateAligned(4L * length);
    }

    public int length() { return length; }
    public float get(int i) { return (float) FLOATS.get(buf, check(i) << 2); }
    public void set(int i, float v) { FLOATS.set(buf, check(i) << 2, v); }
    public void fill(float v) { for (int i = 0; i < length; i++) FLOATS.set(buf, i << 2, v); }
    public void copyTo(float[] dst, int n) { buf.asFloatBuffer().get(0, dst, 0, n); }
    public void copyFrom(float[] src, int n) { buf.asFloatBuffer().put(0, src, 0, n); }
    public boolean isAligned() { return buf.alignmentOffset(0, OffHeapColumns.ALIGNMENT) == 0; }

    public void copyPrefixFrom(OffHeapFloatColumn src, int count) {
        if (count <= 0) return;
        if (count > src.length || count > this.length) {
            throw new IndexOutOfBoundsException("copy count=" + count);
        }
        buf.put(0, src.buf, 0, count << 2);
    }

    private int check(int i) {
        if (i < 0 || i >= length) throw new IndexOutOfBoundsException(i);
        return i;
    }
}
