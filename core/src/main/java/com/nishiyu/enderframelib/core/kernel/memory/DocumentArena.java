package com.nishiyu.enderframelib.core.kernel.memory;

import java.util.Map;

/**
 * 文書 1 つ分の Off-Heap SoA アリーナ。ノードは単なる {@code int nodeId}。
 * 列は {@link OffHeapColumns}（標準 JDK 21 のダイレクト ByteBuffer + VarHandle）で持つので、JVM フラグも追加依存も要らない。
 *
 * <p>領域は 2 つ。{@link #sealStatic()} までに確保した ID は <b>静的領域</b>で、解放しても番号を再利用しない
 * （パース順で決まる安定した鍵になる）。その後に確保する ID は<b>動的領域</b>で、解放すると空きリストに戻り再利用される。
 * 再利用された ID は世代（{@link #generation}）が進むので、古い ID に紐づく状態は {@link NodeSlots} が自動で無効にする。</p>
 *
 * <p>スレッド安全ではない（描画スレッドが持ち主）。</p>
 */
public final class DocumentArena {
    public static final int NONE = -1;

    private int capacity;
    private int highWater; // 次に新規確保する ID
    private int staticCount; // sealStatic 時点の highWater
    private int live;
    private int freeHead = NONE;
    private int epoch;

    // Topology
    private OffHeapColumns.Ints parent, firstChild, lastChild, prevSibling, nextSibling;
    // Identity & Bloom
    private OffHeapColumns.Ints tagAndType, idAtom;
    private OffHeapColumns.Longs selfBloom, subtreeBloom;
    // 世代（解放のたびに +1）と、空きリストの次/使用中フラグ
    private OffHeapColumns.Ints generation, nextFree;
    private Object[] owners;

    /**
     * 持ち主（{@code InteractionState} など）ごとの列挙用の表。キーも値も弱参照にする。表の実体は持ち主が持つ
     * （{@link #slots} の戻り値を持ち主が保持する）。強参照だと、持ち主が手放されても {@link #dropSlots} が呼ばれない限り、
     * その持ち主が持つ全節ぶんの状態（算出スタイル）が文書の寿命の間残ってしまう（文書に対して {@code InteractionState} を
     * 作り捨てると、ヒープが際限なく増えて再スタイルも遅くなっていた）。値の表から持ち主へ強い参照が戻る
     * （{@code StateFlags.owner}）ので、値を弱参照にしないとキーだけ弱くしても回収されない。
     * 持ち主は {@code equals} / {@code hashCode} を上書きしない前提（同一性で比べる）。
     */
    private final Map<Object, java.lang.ref.WeakReference<NodeSlots<?>>> tables = new java.util.WeakHashMap<>();

    public DocumentArena() {
        this(64);
    }

    public DocumentArena(int initialCapacity) {
        allocateColumns(Math.max(16, initialCapacity));
    }

    private void allocateColumns(int cap) {
        capacity = cap;
        parent = new OffHeapColumns.Ints(cap);
        firstChild = new OffHeapColumns.Ints(cap);
        lastChild = new OffHeapColumns.Ints(cap);
        prevSibling = new OffHeapColumns.Ints(cap);
        nextSibling = new OffHeapColumns.Ints(cap);
        tagAndType = new OffHeapColumns.Ints(cap);
        idAtom = new OffHeapColumns.Ints(cap);
        selfBloom = new OffHeapColumns.Longs(cap);
        subtreeBloom = new OffHeapColumns.Longs(cap);
        generation = new OffHeapColumns.Ints(cap);
        nextFree = new OffHeapColumns.Ints(cap);
        owners = new Object[cap];
    }

    private void grow() {
        OffHeapColumns.Ints[] ints = {parent, firstChild, lastChild, prevSibling, nextSibling,
                tagAndType, idAtom, generation, nextFree};
        OffHeapColumns.Longs[] longs = {selfBloom, subtreeBloom};
        Object[] oldOwners = owners;
        int oldCap = capacity;
        allocateColumns(oldCap * 2);
        OffHeapColumns.Ints[] now = {parent, firstChild, lastChild, prevSibling, nextSibling,
                tagAndType, idAtom, generation, nextFree};
        for (int c = 0; c < ints.length; c++) {
            for (int i = 0; i < oldCap; i++) now[c].set(i, ints[c].get(i));
        }
        OffHeapColumns.Longs[] nowL = {selfBloom, subtreeBloom};
        for (int c = 0; c < longs.length; c++) {
            for (int i = 0; i < oldCap; i++) nowL[c].set(i, longs[c].get(i));
        }
        System.arraycopy(oldOwners, 0, owners, 0, oldCap);
    }

    // ------------------------------------------------------------------
    // 確保・解放
    // ------------------------------------------------------------------

    /** ノードを 1 つ確保する。静的領域が閉じていれば空きリストを優先する。 */
    public int allocate(Object owner) {
        int id;
        if (freeHead != NONE) {
            id = freeHead;
            freeHead = nextFree.get(id);
        } else {
            if (highWater == capacity) grow();
            id = highWater++;
        }
        owners[id] = owner;
        clearNode(id);
        live++;
        return id;
    }

    private void clearNode(int id) {
        parent.set(id, NONE);
        firstChild.set(id, NONE);
        lastChild.set(id, NONE);
        prevSibling.set(id, NONE);
        nextSibling.set(id, NONE);
        tagAndType.set(id, 0);
        idAtom.set(id, 0);
        selfBloom.set(id, 0L);
        subtreeBloom.set(id, 0L);
        nextFree.set(id, NONE);
    }

    /**
     * ノードを解放する。動的領域なら番号を再利用に回し、静的領域なら番号は予約したまま空にするだけ
     * （静的ノードが文書へ戻ってきても同じ鍵になる）。
     */
    public void free(int id) {
        if (!isLive(id)) return;
        owners[id] = null;
        live--;
        generation.set(id, generation.get(id) + 1);
        if (id >= staticCount) {
            nextFree.set(id, freeHead);
            freeHead = id;
        } else {
            nextFree.set(id, -2); // 静的な欠番（再利用しない）
        }
        parent.set(id, NONE);
        firstChild.set(id, NONE);
        lastChild.set(id, NONE);
        prevSibling.set(id, NONE);
        nextSibling.set(id, NONE);
    }

    /** 静的ノードを同じ番号で取り戻す（文書から外れた静的ノードが同じ文書へ戻るとき）。 */
    public boolean reclaimStatic(int id, Object owner) {
        if (id < 0 || id >= staticCount || owners[id] != null || nextFree.get(id) != -2) return false;
        owners[id] = owner;
        nextFree.set(id, NONE);
        live++;
        return true;
    }

    /**
     * 外から受け取った番号（ネットワーク越しの文書など）をそのまま確保する。{@link #reset} の直後から、
     * {@link #finishRestore} までの間だけ使う。途中の飛び番号は欠番になる。
     */
    public void restore(int id, Object owner) {
        if (id < 0 || owner == null) throw new IllegalArgumentException("id=" + id);
        while (id >= capacity) grow();
        if (id < highWater && owners[id] != null) throw new IllegalStateException("番号が重複: " + id);
        for (int i = highWater; i < id; i++) {
            clearNode(i);
            nextFree.set(i, -2);
        }
        if (id >= highWater) highWater = id + 1;
        owners[id] = owner;
        clearNode(id);
        live++;
    }

    /** {@link #restore} を終える。{@code staticCount} 未満の欠番は予約のまま、それ以上の欠番は空きリストへ。 */
    public void finishRestore(int staticCountValue) {
        staticCount = Math.min(staticCountValue, highWater);
        freeHead = NONE;
        for (int i = highWater - 1; i >= staticCount; i--) {
            if (owners[i] == null) {
                nextFree.set(i, freeHead);
                freeHead = i;
            }
        }
        for (int i = 0; i < staticCount; i++) {
            if (owners[i] == null) nextFree.set(i, -2);
        }
    }

    /** ここまでに確保した番号を静的領域として閉じる。 */
    public void sealStatic() {
        staticCount = highWater;
    }

    /** 全ノードを捨てて初期状態へ戻す（世代が全体で進み、{@link NodeSlots} は空になる）。 */
    public void reset() {
        epoch++;
        for (int i = 0; i < highWater; i++) {
            owners[i] = null;
            generation.set(i, generation.get(i) + 1);
        }
        highWater = 0;
        staticCount = 0;
        live = 0;
        freeHead = NONE;
    }

    public boolean isLive(int id) {
        return id >= 0 && id < highWater && owners[id] != null;
    }

    public Object owner(int id) {
        return id >= 0 && id < highWater ? owners[id] : null;
    }

    public int capacity() { return capacity; }
    public int highWater() { return highWater; }
    public int staticCount() { return staticCount; }
    public int liveCount() { return live; }
    public int epoch() { return epoch; }
    public int generation(int id) { return generation.get(id); }
    public boolean isAligned() { return parent.isAligned() && selfBloom.isAligned(); }

    // ------------------------------------------------------------------
    // Topology
    // ------------------------------------------------------------------

    public int parent(int id) { return parent.get(id); }
    public int firstChild(int id) { return firstChild.get(id); }
    public int lastChild(int id) { return lastChild.get(id); }
    public int prevSibling(int id) { return prevSibling.get(id); }
    public int nextSibling(int id) { return nextSibling.get(id); }

    /** {@code child} を {@code parentId} の最後の子として結ぶ。child は親・兄弟を持っていないこと。 */
    public void appendChild(int parentId, int child) {
        int last = lastChild.get(parentId);
        parent.set(child, parentId);
        prevSibling.set(child, last);
        nextSibling.set(child, NONE);
        if (last == NONE) firstChild.set(parentId, child);
        else nextSibling.set(last, child);
        lastChild.set(parentId, child);
    }

    /** {@code parentId} の子の並びを {@code children[0..n)} に結び直す（途中の挿入・削除・並べ替え用）。 */
    public void relinkChildren(int parentId, int[] children, int n) {
        int prev = NONE;
        firstChild.set(parentId, n > 0 ? children[0] : NONE);
        lastChild.set(parentId, n > 0 ? children[n - 1] : NONE);
        for (int i = 0; i < n; i++) {
            int c = children[i];
            parent.set(c, parentId);
            prevSibling.set(c, prev);
            nextSibling.set(c, i + 1 < n ? children[i + 1] : NONE);
            prev = c;
        }
    }

    /** {@code child} を親・兄弟との結びから切り離す（子孫との結びは保つ）。親の子の並びも繋ぎ直す。 */
    public void detach(int child) {
        int p = parent.get(child);
        int prev = prevSibling.get(child);
        int next = nextSibling.get(child);
        if (p != NONE) {
            if (prev == NONE) firstChild.set(p, next); else nextSibling.set(prev, next);
            if (next == NONE) lastChild.set(p, prev); else prevSibling.set(next, prev);
        }
        parent.set(child, NONE);
        prevSibling.set(child, NONE);
        nextSibling.set(child, NONE);
    }

    // ------------------------------------------------------------------
    // Identity & Bloom
    // ------------------------------------------------------------------

    public int tagAndType(int id) { return tagAndType.get(id); }
    public int idAtom(int id) { return idAtom.get(id); }
    public long selfBloom(int id) { return selfBloom.get(id); }
    public long subtreeBloom(int id) { return subtreeBloom.get(id); }

    public static int packTagAndType(int nodeType, int tagAtom) {
        return ((nodeType & 0xFF) << 24) | (tagAtom & 0xFFFFFF);
    }

    public static int nodeTypeOf(int tagAndType) { return tagAndType >>> 24; }
    public static int tagAtomOf(int tagAndType) { return tagAndType & 0xFFFFFF; }

    public void setIdentity(int id, int nodeType, int tagAtom, int idAtomValue, long self) {
        tagAndType.set(id, packTagAndType(nodeType, tagAtom));
        idAtom.set(id, idAtomValue);
        selfBloom.set(id, self);
        subtreeBloom.set(id, subtreeBloom.get(id) | self);
    }

    /** 自分と祖先の子孫 Bloom に {@code bits} を足す。子孫が消えても落とさない（偽陽性は許される上位集合）。 */
    public void orSubtreeBloomUp(int id, long bits) {
        for (int cur = id; cur != NONE; cur = parent.get(cur)) {
            long before = subtreeBloom.get(cur);
            if ((before | bits) == before && cur != id) return; // 祖先はもう含んでいる
            subtreeBloom.set(cur, before | bits);
        }
    }

    /** {@code id} 以下の子孫 Bloom を、トポロジー列から下から上へ作り直す。返り値は {@code id} の子孫 Bloom。 */
    public long rebuildSubtreeBloom(int id) {
        long bits = selfBloom.get(id);
        for (int c = firstChild.get(id); c != NONE; c = nextSibling.get(c)) bits |= rebuildSubtreeBloom(c);
        subtreeBloom.set(id, bits);
        return bits;
    }

    // ------------------------------------------------------------------
    // ノード番号に紐づく外部の状態表
    // ------------------------------------------------------------------

    /**
     * {@code owner}（対話状態など）専用の、ノード番号で引く表。世代が合わない値は null として返す
     * （番号が再利用された、またはアリーナが {@link #reset} された）。弱参照は要らない。
     */
    @SuppressWarnings("unchecked")
    public <T> NodeSlots<T> slots(Object owner) {
        java.lang.ref.WeakReference<NodeSlots<?>> ref = tables.get(owner);
        NodeSlots<?> existing = ref != null ? ref.get() : null;
        if (existing != null) return (NodeSlots<T>) existing;
        NodeSlots<T> created = new NodeSlots<>(this);
        tables.put(owner, new java.lang.ref.WeakReference<>(created));
        return created;
    }

    /** 持ち主が手放すとき。 */
    public void dropSlots(Object owner) {
        tables.remove(owner);
    }

    public static final class NodeSlots<T> {
        private final DocumentArena arena;
        private Object[] values = new Object[16];
        private int[] gens = new int[16];
        private int epoch;

        private NodeSlots(DocumentArena arena) {
            this.arena = arena;
            this.epoch = arena.epoch;
        }

        @SuppressWarnings("unchecked")
        public T get(int id) {
            if (id < 0 || id >= values.length) return null;
            if (epoch != arena.epoch) { clear(); return null; }
            Object v = values[id];
            if (v == null) return null;
            if (gens[id] != arena.generation(id)) { values[id] = null; return null; }
            return (T) v;
        }

        public void put(int id, T value) {
            if (epoch != arena.epoch) clear();
            if (id >= values.length) {
                int n = values.length;
                while (n <= id) n *= 2;
                values = java.util.Arrays.copyOf(values, n);
                gens = java.util.Arrays.copyOf(gens, n);
            }
            values[id] = value;
            gens[id] = arena.generation(id);
        }

        public void remove(int id) {
            if (id >= 0 && id < values.length) values[id] = null;
        }

        public void clear() {
            java.util.Arrays.fill(values, null);
            epoch = arena.epoch;
        }

        /** 世代が合う値を順に渡す。 */
        @SuppressWarnings("unchecked")
        public void forEach(java.util.function.BiConsumer<Integer, T> visitor) {
            if (epoch != arena.epoch) { clear(); return; }
            int n = Math.min(values.length, arena.highWater);
            for (int i = 0; i < n; i++) {
                Object v = values[i];
                if (v == null) continue;
                if (gens[i] != arena.generation(i)) { values[i] = null; continue; }
                visitor.accept(i, (T) v);
            }
        }
    }
}
