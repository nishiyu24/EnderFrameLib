package com.nishiyu.enderframelib.core.kernel.memory;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 64 バイト境界に揃ったオフヒープ {@code int} 列のトップレベル表現（パッケージ外からの参照用）。
 */
public final class OffHeapIntColumn {
    private static final VarHandle INTS = MethodHandles.byteBufferViewVarHandle(int[].class, ByteOrder.nativeOrder());

    private final ByteBuffer buf;
    private final int length;

    public OffHeapIntColumn(int length) {
        this.length = length;
        this.buf = OffHeapColumns.allocateAligned(4L * length);
    }

    public int length() { return length; }
    public int get(int i) { return (int) INTS.get(buf, check(i) << 2); }
    public void set(int i, int v) { INTS.set(buf, check(i) << 2, v); }
    public void fill(int v) { for (int i = 0; i < length; i++) INTS.set(buf, i << 2, v); }
    public boolean isAligned() { return buf.alignmentOffset(0, OffHeapColumns.ALIGNMENT) == 0; }

    public void copyPrefixFrom(OffHeapIntColumn src, int count) {
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
