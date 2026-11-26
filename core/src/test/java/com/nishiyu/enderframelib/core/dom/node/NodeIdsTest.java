package com.nishiyu.enderframelib.core.dom.node;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** 節の番号の空間（M5-1 で {@code DocumentArena} から木の形・タグ・Bloom の写しを除いたもの）。 */
class NodeIdsTest {

    @Test
    void allocateGrowsPastInitialCapacityAndKeepsOwners() {
        NodeIds a = new NodeIds(16);
        Object[] owners = new Object[1000];
        for (int i = 0; i < owners.length; i++) {
            owners[i] = new Object();
            assertEquals(i, a.allocate(owners[i]));
        }
        assertTrue(a.capacity() >= 1000);
        for (int i = 0; i < owners.length; i++) assertSame(owners[i], a.owner(i));
        assertEquals(1000, a.liveCount());
    }

    @Test
    void staticIdsAreNeverReusedButDynamicIdsAre() {
        NodeIds a = new NodeIds();
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

        assertTrue(a.reclaimStatic(i1, s1), "静的な節は同じ番号で戻れる");
        assertSame(s1, a.owner(i1));
        assertFalse(a.reclaimStatic(i0, s0), "使用中の番号は取り戻せない");
        assertEquals(2, i2);
    }

    @Test
    void nodeSlotsDropValuesWhenIdsAreRecycledOrReset() {
        NodeIds a = new NodeIds();
        Object owner = new Object();
        NodeIds.NodeSlots<String> slots = a.slots(owner);
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
        NodeIds a = new NodeIds();
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
        assertFalse(a.reclaimStatic(0, o0), "使用中の静的な番号は取り戻せない");
        assertThrows(IllegalStateException.class, () -> a.restore(5, new Object()));
    }

    @Test
    void steadyStateAllocationAndFreeAreZeroGc() {
        NodeIds a = new NodeIds(4096);
        Object owner = new Object();
        a.sealStatic();
        cycle(a, owner); // ウォームアップ（JIT・空きリストの形を作る）
        cycle(a, owner);
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().getId();
        long before = mx.getThreadAllocatedBytes(tid);
        for (int round = 0; round < 50; round++) cycle(a, owner);
        long allocated = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(allocated < 16_384, "20 万回の確保・解放で Java ヒープを " + allocated + " B 使った（0 に近いはず）");
        assertEquals(0, a.liveCount());
    }

    private static void cycle(NodeIds a, Object owner) {
        for (int i = 0; i < 4000; i++) a.allocate(owner);
        for (int i = 0; i < 4000; i++) a.free(i);
    }

    /**
     * 番号の空間は番号と世代だけを持つ。木の形（親・子・兄弟）・タグ・id・Bloom の写しを戻さない
     * （M5-1。木の形の正本は子の並び、セレクタの足切りは {@link SelectorIndex}）。
     */
    @Test
    void treeAndIdentityCopiesStayRemoved() {
        Set<String> forbidden = Set.of("parent", "firstchild", "lastchild", "prevsibling", "nextsibling",
                "appendchild", "relinkchildren", "detach", "bloom", "tagandtype", "idatom", "setidentity", "dirty",
                "subtreeend");
        for (var m : NodeIds.class.getDeclaredMethods()) {
            String name = m.getName().toLowerCase();
            for (String f : forbidden) assertFalse(name.contains(f), "番号の空間に写しの API が戻った: " + m);
        }
        for (var f : NodeIds.class.getDeclaredFields()) {
            String name = f.getName().toLowerCase();
            for (String bad : forbidden) assertFalse(name.contains(bad), "番号の空間に写しの列が戻った: " + f);
            assertFalse(f.getType().getName().contains("OffHeap"), "番号の空間はヒープの配列で持つ: " + f);
        }
    }

    @Test
    void freedIdsComeBackLiveForTheNewOwner() {
        NodeIds a = new NodeIds(16);
        Object o = new Object(), p = new Object();
        a.allocate(o);
        a.sealStatic();
        int d = a.allocate(o);
        a.free(d);
        assertNull(a.owner(d));
        int again = a.allocate(p);
        assertEquals(d, again, "動的領域の番号は再利用される");
        assertSame(p, a.owner(again));
        assertTrue(a.isLive(again));
    }
}
