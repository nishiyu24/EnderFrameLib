package com.nishiyu.enderframelib.core.kernel.memory;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class AtomTableTest {

    @Test
    void reservedNamesAreStableAndRoundTrip() {
        AtomTable a = new AtomTable(), b = new AtomTable();
        assertEquals(a.intern("div"), b.intern("div"));
        assertTrue(a.intern("div") >= 1 && a.intern("div") <= AtomTable.LAST_RESERVED);
        assertEquals("div", a.nameOf(a.intern("div")));
        assertTrue(AtomTable.reservedCount() <= AtomTable.LAST_RESERVED);
    }

    @Test
    void dynamicAtomsStartAfterReservedAndAreIdempotent() {
        AtomTable t = new AtomTable();
        int x = t.intern("my-custom-class");
        assertTrue(x > AtomTable.LAST_RESERVED);
        assertEquals(x, t.intern("my-custom-class"));
        assertEquals(x, t.lookup("my-custom-class"));
        assertEquals(AtomTable.NONE, t.lookup("never-seen"));
        assertEquals("my-custom-class", t.nameOf(x));
    }

    @Test
    void emptyAndNullAreNone() {
        AtomTable t = new AtomTable();
        assertEquals(AtomTable.NONE, t.intern(null));
        assertEquals(AtomTable.NONE, t.intern(""));
        assertEquals("", t.nameOf(AtomTable.NONE));
        assertEquals("", t.nameOf(-5));
        assertEquals("", t.nameOf(9_999_999));
    }

    @Test
    void bloomBitsHaveAtMostTwoBitsAndSupersetWorks() {
        AtomTable t = new AtomTable();
        long all = 0;
        for (int i = 0; i < 200; i++) {
            long bits = AtomTable.bloomBits(t.intern("c" + i));
            assertTrue(Long.bitCount(bits) >= 1 && Long.bitCount(bits) <= 2);
            all |= bits;
        }
        assertEquals(0L, AtomTable.bloomBits(AtomTable.NONE));
        long one = AtomTable.bloomBits(t.intern("c7"));
        assertTrue(AtomTable.mayContain(all, one));
        assertTrue(AtomTable.mayContain(0L, 0L));
        assertFalse(AtomTable.mayContain(0L, one));
    }

    @Test
    void concurrentInternGivesOneIdPerName() {
        AtomTable t = new AtomTable();
        ConcurrentHashMap<String, Integer> seen = new ConcurrentHashMap<>();
        IntStream.range(0, 8000).parallel().forEach(i -> {
            String name = "n" + (i % 500);
            int id = t.intern(name);
            Integer prev = seen.putIfAbsent(name, id);
            if (prev != null) assertEquals(prev.intValue(), id);
            assertEquals(name, t.nameOf(id));
        });
        assertEquals(500, seen.values().stream().distinct().count());
    }

    @Test
    void capacityIsObservableAndExhaustionFailsWithATypedException() {
        AtomTable t = new AtomTable(300);
        assertEquals(300, t.capacity());
        int before = t.remaining();
        assertEquals(300 - (AtomTable.LAST_RESERVED + 1), before);
        t.intern("a-new-name");
        assertEquals(before - 1, t.remaining());
        t.intern("a-new-name"); // 既出は消費しない
        assertEquals(before - 1, t.remaining());
        for (int i = 0; t.remaining() > 0; i++) t.intern("fill" + i);
        assertEquals(0, t.remaining());
        assertThrows(AtomTable.ExhaustedException.class, () -> t.intern("one-too-many"));
        assertEquals(AtomTable.NONE, t.lookup("one-too-many"), "失敗した名前は辞書に残らない");
        // 既存の名前は上限後も引ける
        assertEquals(t.lookup("fill0"), t.intern("fill0"));
        assertThrows(IllegalArgumentException.class, () -> new AtomTable(10));
    }

    @Test
    void nearLimitListenerFiresOnceAtNinetyPercent() {
        AtomTable t = new AtomTable(1000);
        java.util.concurrent.atomic.AtomicInteger fired = new java.util.concurrent.atomic.AtomicInteger();
        int[] remainingAtFire = {-1};
        t.onNearLimit(x -> {
            fired.incrementAndGet();
            remainingAtFire[0] = x.remaining();
        });
        for (int i = 0; i < 600; i++) t.intern("n" + i);
        assertEquals(0, fired.get(), "90% 未満では呼ばない");
        for (int i = 600; i < 740; i++) t.intern("n" + i);
        assertEquals(1, fired.get(), "90% を超えた最初の 1 回だけ");
        assertTrue(remainingAtFire[0] <= 100 && remainingAtFire[0] >= 0, "残り " + remainingAtFire[0]);
    }

    @Test
    void repeatedSameNamesNeverGrowTheTable() {
        AtomTable t = new AtomTable(400);
        for (int i = 0; i < 100_000; i++) t.intern("row-" + (i % 50));
        assertEquals(50, t.size() - AtomTable.reservedCount());
    }
}
