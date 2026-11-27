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
    void neighbouringElementsDoNotBleed() {
        OffHeapColumns.Floats f = new OffHeapColumns.Floats(4);
        f.set(1, Float.intBitsToFloat(0xFFFFFFFF));
        assertEquals(0f, f.get(0));
        assertEquals(0f, f.get(2));
    }

    @Test
    void outOfRangeThrows() {
        OffHeapColumns.Floats f = new OffHeapColumns.Floats(4);
        assertThrows(IndexOutOfBoundsException.class, () -> f.get(4));
        assertThrows(IndexOutOfBoundsException.class, () -> f.set(-1, 1f));
        assertThrows(IndexOutOfBoundsException.class, () -> new OffHeapColumns.Floats(2).get(2));
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
        assertThrows(IllegalArgumentException.class, () -> new OffHeapColumns.Floats(Integer.MAX_VALUE));
    }
}
