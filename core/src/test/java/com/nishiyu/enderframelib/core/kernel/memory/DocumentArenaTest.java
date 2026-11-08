package com.nishiyu.enderframelib.core.kernel.memory;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DocumentArenaTest {

    @Test
    void allocateGrowsPastInitialCapacityAndKeepsColumns() {
        DocumentArena a = new DocumentArena(16);
        Object[] owners = new Object[1000];
        for (int i = 0; i < owners.length; i++) {
            owners[i] = new Object();
            int id = a.allocate(owners[i]);
            assertEquals(i, id);
            a.setIdentity(id, 1, 7, i, 0xF0F0L + i);
        }
        assertTrue(a.capacity() >= 1000);
        assertTrue(a.isAligned());
        for (int i = 0; i < owners.length; i++) {
            assertSame(owners[i], a.owner(i));
            assertEquals(i, a.idAtom(i));
            assertEquals(0xF0F0L + i, a.selfBloom(i));
            assertEquals(DocumentArena.packTagAndType(1, 7), a.tagAndType(i));
        }
        assertEquals(1000, a.liveCount());
    }

    @Test
    void staticIdsAreNeverReusedButDynamicIdsAre() {
        DocumentArena a = new DocumentArena();
        Object s0 = new Object(), s1 = new Object(), s2 = new Object();
        int i0 = a.allocate(s0), i1 = a.allocate(s1), i2 = a.allocate(s2);
        a.sealStatic();
        assertEquals(3, a.staticCount());

        a.free(i1); // 静的: 番号は予約のまま
        assertFalse(a.isLive(i1));
        int d0 = a.allocate(new Object());
        assertEquals(3, d0, "静的な欠番は再利用されず、新しい番号は動的領域から");

        int gen = a.generation(d0);
        a.free(d0);
        assertEquals(gen + 1, a.generation(d0), "解放で世代が進む");
        int d1 = a.allocate(new Object());
        assertEquals(d0, d1, "動的な番号は再利用される");

        assertTrue(a.reclaimStatic(i1, s1), "静的ノードは同じ番号で戻れる");
        assertSame(s1, a.owner(i1));
        assertFalse(a.reclaimStatic(i0, s0), "使用中の番号は取り戻せない");
        assertEquals(i2, 2);
    }

    @Test
    void topologyAppendDetachRelink() {
        DocumentArena a = new DocumentArena();
        int root = a.allocate("root");
        int c1 = a.allocate("c1");
        int g1 = a.allocate("g1"); // c1 の子
        int c2 = a.allocate("c2");
        int c3 = a.allocate("c3");
        a.appendChild(root, c1);
        a.appendChild(c1, g1);
        a.appendChild(root, c2);
        a.appendChild(root, c3);

        assertEquals(DocumentArena.NONE, a.parent(root));
        assertEquals(c1, a.firstChild(root));
        assertEquals(c3, a.lastChild(root));
        assertEquals(c2, a.nextSibling(c1));
        assertEquals(c1, a.prevSibling(c2));
        assertEquals(DocumentArena.NONE, a.nextSibling(c3));

        a.detach(c2);
        assertEquals(c3, a.nextSibling(c1));
        assertEquals(c1, a.prevSibling(c3));
        assertEquals(DocumentArena.NONE, a.parent(c2));

        a.relinkChildren(root, new int[]{c3, c1}, 2);
        assertEquals(c3, a.firstChild(root));
        assertEquals(c1, a.lastChild(root));
        assertEquals(c1, a.nextSibling(c3));
        assertEquals(c3, a.prevSibling(c1));
    }

    @Test
    void bloomPropagatesToAncestorsAndFiltersAbsentAtoms() {
        AtomTable atoms = AtomTable.shared();
        DocumentArena a = new DocumentArena();
        int root = a.allocate("r"), child = a.allocate("c");
        a.appendChild(root, child);
        long needle = AtomTable.bloomBits(atoms.intern("m1-needle-class"));
        a.setIdentity(child, 1, 5, 0, needle);
        a.orSubtreeBloomUp(child, needle);
        assertTrue(AtomTable.mayContain(a.subtreeBloom(root), needle), ":has() 用の子孫 Bloom は祖先まで届く");
        assertTrue(AtomTable.mayContain(a.subtreeBloom(child), needle));
        assertEquals(0L, a.selfBloom(root), "祖先自身の Bloom には混ざらない");
    }

    @Test
    void nodeSlotsDropValuesWhenIdsAreRecycledOrArenaIsReset() {
        DocumentArena a = new DocumentArena();
        Object owner = new Object();
        DocumentArena.NodeSlots<String> slots = a.slots(owner);
        a.sealStatic();
        int id = a.allocate("n");
        slots.put(id, "state");
        assertEquals("state", slots.get(id));

        a.free(id);
        assertNull(slots.get(id), "番号が解放されたら状態は無効");
        int again = a.allocate("n2");
        assertEquals(id, again);
        assertNull(slots.get(again), "再利用された番号に古い状態は見えない");

        slots.put(again, "fresh");
        a.reset();
        assertNull(slots.get(again), "reset で全部無効");
        assertSame(slots, a.slots(owner), "同じ持ち主には同じ表");
        a.dropSlots(owner);
        assertNotSame(slots, a.slots(owner));
    }

    @Test
    void restoreRebuildsExactIdsAndFreeList() {
        DocumentArena a = new DocumentArena();
        Object o2 = new Object(), o5 = new Object(), o0 = new Object();
        a.reset();
        a.restore(5, o5);
        a.restore(0, o0);
        a.restore(2, o2);
        a.finishRestore(3);
        assertSame(o5, a.owner(5));
        assertSame(o2, a.owner(2));
        assertEquals(3, a.liveCount());
        assertEquals(3, a.staticCount());
        // 静的領域の欠番（1）は予約、動的領域の欠番（3, 4）は空きリストの小さい順
        assertEquals(3, a.allocate("x"));
        assertEquals(4, a.allocate("y"));
        assertEquals(6, a.allocate("z"));
        assertThrows(IllegalStateException.class, () -> a.restore(5, new Object()));
    }

    @Test
    void steadyStateAllocationAndFreeAreZeroGc() {
        DocumentArena a = new DocumentArena(4096);
        Object owner = new Object();
        a.sealStatic();
        cycle(a, owner); // ウォームアップ（JIT・空きリストの形を作る）
        cycle(a, owner);
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().getId();
        long before = mx.getThreadAllocatedBytes(tid);
        for (int round = 0; round < 50; round++) cycle(a, owner);
        long allocated = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(allocated < 16_384, "20 万回の確保・解放・列の書き込みで Java ヒープを " + allocated + " B 使った（0 に近いはず）");
        assertEquals(0, a.liveCount());
    }

    private static void cycle(DocumentArena a, Object owner) {
        for (int i = 0; i < 4000; i++) {
            int id = a.allocate(owner);
            a.setIdentity(id, 1, 7, i, 1L);
        }
        for (int i = 0; i < 4000; i++) a.free(i);
    }

    @Test
    void unreadDerivedColumnsStayRemoved() {
        for (var m : DocumentArena.class.getDeclaredMethods()) {
            assertFalse(m.getName().toLowerCase().contains("subtreeend"), "読み手のない部分木終端が戻っている: " + m);
        }
    }

    @Test
    void dirtyColumnStaysRemoved() {
        // 書かれるだけで誰も読まない写しだった汚れ列（§9.5.15）。復活させない
        for (var m : DocumentArena.class.getDeclaredMethods()) {
            assertFalse(m.getName().toLowerCase().contains("dirty"), "汚れ列の API が戻っている: " + m);
        }
        for (var f : DocumentArena.class.getDeclaredFields()) {
            assertFalse(f.getName().toLowerCase().contains("dirty"), "汚れ列が戻っている: " + f);
        }
    }

    @Test
    void freedIdsComeBackCleanAfterReuse() {
        DocumentArena a = new DocumentArena(16);
        Object o = new Object();
        int x = a.allocate(o);
        a.setIdentity(x, 1, 7, 9, 0xFFL);
        a.sealStatic();
        int d = a.allocate(o);
        a.setIdentity(d, 1, 7, 9, 0xFFL);
        a.free(d);
        int again = a.allocate(o);
        assertEquals(d, again, "動的領域の番号は再利用される");
        assertEquals(0, a.idAtom(again));
        assertEquals(0L, a.selfBloom(again));
        assertEquals(DocumentArena.NONE, a.parent(again));
    }
}
