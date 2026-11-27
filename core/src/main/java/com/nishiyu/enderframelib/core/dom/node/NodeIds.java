package com.nishiyu.enderframelib.core.dom.node;

import java.util.Arrays;
import java.util.Map;

/**
 * 木 1 つ分の節の番号の空間。節は {@code int} の番号を持ち、ネットワークの鍵（{@code ~番号}）・描画の整数鍵・
 * 対話状態の表（{@link NodeSlots}）の添字に使う。
 *
 * <p><b>番号だけを持つ</b>（M5-1）。以前の {@code DocumentArena} はオフヒープの列に木の形（親・子・兄弟）と
 * タグ・id・Bloom を写して持ち、{@link HtmlNode} の子の並びと二重に同期していた。木の形の正本は子の並びだけにし
 * （前の兄弟は {@link HtmlNode#siblingIndex} で O(1)）、セレクタの足切りは {@link SelectorIndex} が節の欄で持つ。</p>
 *
 * <p>領域は 2 つ。{@link #sealStatic()} までに確保した番号は<b>静的領域</b>で、解放しても再利用しない
 * （パース順で決まる安定した鍵になる）。その後に確保する番号は<b>動的領域</b>で、解放すると空きリストに戻り再利用される。
 * 再利用された番号は世代（{@link #generation}）が進むので、古い番号に紐づく状態は {@link NodeSlots} が自動で無効にする。</p>
 *
 * <p>スレッド安全ではない（描画スレッドが持ち主）。{@link NodeSlots} の読み取りだけは並行してよい。</p>
 */
public final class NodeIds {
    public static final int NONE = -1;
    /** 静的領域の欠番（再利用しない。同じ節が戻ったときの {@link NodeSlots} 保持のため世代は進めない）。 */
    private static final int STATIC_HOLE = -2;

    private int highWater; // 次に新規確保する番号
    private int staticCount; // sealStatic 時点の highWater
    private int live;
    private int freeHead = NONE;
    private int epoch;
    /** {@link #allocate} の通し番号（単調に増える。解放では減らない）。構造が増えたかを、木を歩かずに調べる用。 */
    private int allocSerial;

    private Object[] owners;
    /** 世代（解放のたびに +1）。 */
    private int[] generation;
    /** 空きリストの次（使用中は {@link #NONE}、静的な欠番は {@link #STATIC_HOLE}）。 */
    private int[] nextFree;

    /**
     * 持ち主（{@code InteractionState} など）ごとの列挙用の表。キーも値も弱参照にする。表の実体は持ち主が持つ
     * （{@link #slots} の戻り値を持ち主が保持する）。強参照だと、持ち主が手放されても {@link #dropSlots} が呼ばれない限り、
     * その持ち主が持つ全節ぶんの状態（算出スタイル）が文書の寿命の間残ってしまう。値の表から持ち主へ強い参照が戻る
     * （{@code StateFlags.owner}）ので、値を弱参照にしないとキーだけ弱くしても回収されない。
     * 持ち主は {@code equals} / {@code hashCode} を上書きしない前提（同一性で比べる）。
     */
    private final Map<Object, java.lang.ref.WeakReference<NodeSlots<?>>> tables = new java.util.WeakHashMap<>();

    public NodeIds() {
        this(64);
    }

    public NodeIds(int initialCapacity) {
        int cap = Math.max(16, initialCapacity);
        owners = new Object[cap];
        generation = new int[cap];
        nextFree = new int[cap];
    }

    private void grow() {
        int cap = owners.length * 2;
        owners = Arrays.copyOf(owners, cap);
        generation = Arrays.copyOf(generation, cap);
        nextFree = Arrays.copyOf(nextFree, cap);
    }

    // ------------------------------------------------------------------
    // 確保・解放
    // ------------------------------------------------------------------

    public int allocSerial() { return allocSerial; }

    /** 番号を 1 つ確保する。静的領域が閉じていれば空きリストを優先する。 */
    public int allocate(Object owner) {
        allocSerial++;
        int id;
        if (freeHead != NONE) {
            id = freeHead;
            freeHead = nextFree[id];
        } else {
            if (highWater == owners.length) grow();
            id = highWater++;
        }
        owners[id] = owner;
        nextFree[id] = NONE;
        live++;
        return id;
    }

    /**
     * 番号を解放する。動的領域なら再利用に回し、静的領域なら番号は予約したまま空にするだけ
     * （静的な節が文書へ戻ってきても同じ鍵になる）。
     */
    public void free(int id) {
        if (!isLive(id)) return;
        owners[id] = null;
        live--;
        if (id >= staticCount) {
            generation[id]++;
            nextFree[id] = freeHead;
            freeHead = id;
        } else {
            nextFree[id] = STATIC_HOLE;
        }
    }

    /** 静的な番号を同じ番号で取り戻す（文書から外れた静的な節が同じ文書へ戻るとき）。 */
    public boolean reclaimStatic(int id, Object owner) {
        if (id < 0 || id >= staticCount || owners[id] != null || nextFree[id] != STATIC_HOLE) return false;
        owners[id] = owner;
        nextFree[id] = NONE;
        live++;
        return true;
    }

    /**
     * 外から受け取った番号（ネットワーク越しの文書など）をそのまま確保する。{@link #reset} の直後から、
     * {@link #finishRestore} までの間だけ使う。途中の飛び番号は欠番になる。
     */
    public void restore(int id, Object owner) {
        if (id < 0 || owner == null) throw new IllegalArgumentException("id=" + id);
        while (id >= owners.length) grow();
        if (id < highWater && owners[id] != null) throw new IllegalStateException("番号が重複: " + id);
        for (int i = highWater; i < id; i++) nextFree[i] = STATIC_HOLE;
        if (id >= highWater) highWater = id + 1;
        owners[id] = owner;
        nextFree[id] = NONE;
        live++;
    }

    /** {@link #restore} を終える。{@code staticCount} 未満の欠番は予約のまま、それ以上の欠番は空きリストへ。 */
    public void finishRestore(int staticCountValue) {
        staticCount = Math.min(staticCountValue, highWater);
        freeHead = NONE;
        for (int i = highWater - 1; i >= staticCount; i--) {
            if (owners[i] == null) {
                nextFree[i] = freeHead;
                freeHead = i;
            }
        }
        for (int i = 0; i < staticCount; i++) {
            if (owners[i] == null) nextFree[i] = STATIC_HOLE;
        }
    }

    /** ここまでに確保した番号を静的領域として閉じる。 */
    public void sealStatic() {
        staticCount = highWater;
    }

    /** 全ての番号を捨てて初期状態へ戻す（世代が全体で進み、{@link NodeSlots} は空になる）。 */
    public void reset() {
        epoch++;
        for (int i = 0; i < highWater; i++) {
            owners[i] = null;
            generation[i]++;
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

    public int capacity() { return owners.length; }
    public int highWater() { return highWater; }
    public int staticCount() { return staticCount; }
    public int liveCount() { return live; }
    public int epoch() { return epoch; }
    public int generation(int id) { return generation[id]; }

    // ------------------------------------------------------------------
    // 番号に紐づく外部の状態表
    // ------------------------------------------------------------------

    /**
     * {@code owner}（対話状態など）専用の、番号で引く表。世代が合わない値は null として返す
     * （番号が再利用された、または {@link #reset} された）。弱参照は要らない。
     */
    @SuppressWarnings("unchecked")
    public <T> NodeSlots<T> slots(Object owner) {
        synchronized (tables) {
            java.lang.ref.WeakReference<NodeSlots<?>> ref = tables.get(owner);
            NodeSlots<?> existing = ref != null ? ref.get() : null;
            if (existing != null) return (NodeSlots<T>) existing;
            NodeSlots<T> created = new NodeSlots<>(this);
            tables.put(owner, new java.lang.ref.WeakReference<>(created));
            return created;
        }
    }

    /** 持ち主がこの空間に表を既に持っているか（無いなら表を作らず false）。 */
    public boolean hasSlots(Object owner) {
        synchronized (tables) {
            java.lang.ref.WeakReference<NodeSlots<?>> ref = tables.get(owner);
            return ref != null && ref.get() != null;
        }
    }

    /** 持ち主が手放すとき。 */
    public void dropSlots(Object owner) {
        synchronized (tables) {
            tables.remove(owner);
        }
    }

    /** 別の空間へ節が移るとき、この空間上の全持ち主の登録状態を {@code dst} へ移す。 */
    @SuppressWarnings("unchecked")
    void migrateSlots(int oldId, NodeIds dst, int newId) {
        if (dst == null || dst == this) return;
        java.util.List<Map.Entry<Object, NodeSlots<?>>> entries = null;
        synchronized (tables) {
            if (tables.isEmpty()) return;
            for (var e : tables.entrySet()) {
                NodeSlots<?> s = e.getValue().get();
                if (s != null) {
                    if (entries == null) entries = new java.util.ArrayList<>(tables.size());
                    entries.add(Map.entry(e.getKey(), s));
                }
            }
        }
        if (entries == null) return;
        for (var e : entries) {
            Object val = e.getValue().take(oldId);
            if (val != null) ((NodeSlots<Object>) dst.slots(e.getKey())).put(newId, val);
        }
    }

    public static final class NodeSlots<T> {
        private static final java.lang.invoke.VarHandle VALUES =
                java.lang.invoke.MethodHandles.arrayElementVarHandle(Object[].class);

        private record Slot<T>(int gen, T value) {}

        private final NodeIds ids;
        private volatile Object[] values = new Object[16];
        private volatile int epoch;

        private NodeSlots(NodeIds ids) {
            this.ids = ids;
            this.epoch = ids.epoch;
        }

        @SuppressWarnings("unchecked")
        public T get(int id) {
            Object[] a = values;
            if (id < 0 || id >= a.length) return null;
            if (epoch != ids.epoch) { clear(); return null; }
            Slot<T> s = (Slot<T>) VALUES.getAcquire(a, id);
            if (s == null) return null;
            if (s.gen != ids.generation(id)) {
                VALUES.compareAndSet(a, id, s, null);
                return null;
            }
            if (!ids.isLive(id)) return null;
            return s.value;
        }

        public synchronized void put(int id, T value) {
            if (epoch != ids.epoch) clear();
            Object[] a = values;
            Slot<T> entry = value == null ? null : new Slot<>(ids.generation(id), value);
            if (id >= a.length) {
                int n = a.length;
                while (n <= id) n *= 2;
                a = Arrays.copyOf(a, n);
                a[id] = entry;
                values = a;
                return;
            }
            VALUES.setRelease(a, id, entry);
        }

        synchronized T take(int id) {
            T v = get(id);
            if (v != null) remove(id);
            return v;
        }

        public synchronized void remove(int id) {
            Object[] a = values;
            if (id >= 0 && id < a.length) VALUES.setRelease(a, id, null);
        }

        public synchronized void clear() {
            Arrays.fill(values, null);
            epoch = ids.epoch;
        }

        /** 世代が合う値を順に渡す。 */
        @SuppressWarnings("unchecked")
        public synchronized void forEach(java.util.function.BiConsumer<Integer, T> visitor) {
            if (epoch != ids.epoch) { clear(); return; }
            Object[] a = values;
            int n = Math.min(a.length, ids.highWater);
            for (int i = 0; i < n; i++) {
                Slot<T> s = (Slot<T>) a[i];
                if (s == null) continue;
                if (s.gen != ids.generation(i)) { a[i] = null; continue; }
                if (!ids.isLive(i)) continue;
                visitor.accept(i, s.value);
            }
        }
    }
}
