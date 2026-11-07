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
}
