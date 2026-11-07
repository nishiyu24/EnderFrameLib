package com.nishiyu.enderframelib.core.kernel.memory;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class OffHeapColumnsTest {

    @Test
    void floatsRoundTripAreAlignedAndZeroed() {
        OffHeapColumns.Floats f = new OffHeapColumns.Floats(1000);
        assertTrue(f.isAligned());
        assertEquals(0f, f.get(0));
        assertEquals(0f, f.get(999));
        for (int i = 0; i < 1000; i++) f.set(i, i * 0.5f);
        for (int i = 0; i < 1000; i++) assertEquals(i * 0.5f, f.get(i));
        f.fill(7f);
        assertEquals(7f, f.get(500));
    }

    @Test
    void intsAndLongsRoundTrip() {
        OffHeapColumns.Ints a = new OffHeapColumns.Ints(33);
        OffHeapColumns.Longs l = new OffHeapColumns.Longs(33);
        assertTrue(a.isAligned());
        assertTrue(l.isAligned());
        for (int i = 0; i < 33; i++) { a.set(i, -i * 31); l.set(i, Long.MIN_VALUE + i * 0x1234567L); }
        for (int i = 0; i < 33; i++) {
            assertEquals(-i * 31, a.get(i));
            assertEquals(Long.MIN_VALUE + i * 0x1234567L, l.get(i));
        }
        a.fill(-1);
        assertEquals(-1, a.get(32));
    }

    @Test
    void neighbouringElementsDoNotBleed() {
        OffHeapColumns.Ints a = new OffHeapColumns.Ints(4);
        a.set(1, 0xFFFFFFFF);
        assertEquals(0, a.get(0));
        assertEquals(0, a.get(2));
    }

    @Test
    void outOfRangeThrows() {
        OffHeapColumns.Floats f = new OffHeapColumns.Floats(4);
        assertThrows(IndexOutOfBoundsException.class, () -> f.get(4));
        assertThrows(IndexOutOfBoundsException.class, () -> f.set(-1, 1f));
        assertThrows(IndexOutOfBoundsException.class, () -> new OffHeapColumns.Longs(2).get(2));
    }

    @Test
    void bulkCopyMatches() {
        OffHeapColumns.Floats f = new OffHeapColumns.Floats(37);
        float[] src = new float[37];
        for (int i = 0; i < 37; i++) src[i] = i * 1.25f;
        f.copyFrom(src, 37);
        for (int i = 0; i < 37; i++) assertEquals(src[i], f.get(i));
        float[] dst = new float[37];
        f.copyTo(dst, 37);
        assertArrayEquals(src, dst);
    }

    @Test
    void zeroLengthAndHugeSizeAreHandled() {
        assertEquals(0, new OffHeapColumns.Floats(0).length());
        assertThrows(IllegalArgumentException.class, () -> new OffHeapColumns.Longs(Integer.MAX_VALUE));
    }
}
