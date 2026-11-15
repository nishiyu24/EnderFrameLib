package com.nishiyu.enderframelib.core.scene3d;

import com.nishiyu.enderframelib.core.kernel.memory.OffHeapFloatColumn;
import com.nishiyu.enderframelib.core.kernel.memory.OffHeapIntColumn;

import java.util.Arrays;

/**
 * 3D シーンノードのオフヒープ SoA アリーナ。
 *
 * <p>全列は 64 バイト境界に揃ったオフヒープ列（{@link OffHeapFloatColumn} / {@link OffHeapIntColumn}）で確保し、
 * フリーリストによる O(1) 再利用と親優先のトポロジカル走査順を保持する。</p>
 */
public final class Scene3DArena {
    public static final int NULL_NODE = -1;

    public static final int TRS_STRIDE = 10;
    public static final int MAT_STRIDE = 16;
    public static final int BOUNDS_STRIDE = 6;
    public static final int TOPO_STRIDE = 4;
    public static final int RENDER_STRIDE = 4;

    public static final int TRS_POS_X = 0;
    public static final int TRS_POS_Y = 1;
    public static final int TRS_POS_Z = 2;
    public static final int TRS_ROT_X = 3;
    public static final int TRS_ROT_Y = 4;
    public static final int TRS_ROT_Z = 5;
    public static final int TRS_ROT_W = 6;
    public static final int TRS_SCALE_X = 7;
    public static final int TRS_SCALE_Y = 8;
    public static final int TRS_SCALE_Z = 9;

    public static final int TOPO_PARENT = 0;
    public static final int TOPO_FIRST_CHILD = 1;
    public static final int TOPO_NEXT_SIBLING = 2;
    public static final int TOPO_DEPTH = 3;

    public static final int RENDER_MESH_ID = 0;
    public static final int RENDER_MATERIAL_ID = 1;
    public static final int RENDER_ENTITY_TAG = 2;
    public static final int RENDER_FLAGS = 3;

    public static final int FLAG_VISIBLE = 1 << 0;
    public static final int FLAG_DIRTY = 1 << 1;
    public static final int FLAG_ALIVE = 1 << 2;

    private static final int INITIAL_CAPACITY = 64;

    private int capacity;
    private int highWater;
    private int aliveCount;

    private OffHeapFloatColumn localTrs;
    private OffHeapFloatColumn worldMat;
    private OffHeapFloatColumn bounds;
    private OffHeapIntColumn topology;
    private OffHeapIntColumn renderRef;

    private int[] freeStack;
    private int freeTop;

    private int[] topoOrder;
    private int[] dfsStack;
    private int topoCount;
    private boolean topoDirty = true;
    /** 見た目に効く変更のたびに増える（静止シーンの再描画を飛ばす判定用。{@link Scene3DWorld#changeCount()}）。 */
    private long changes;
    private boolean anyTransformDirty = true;

    public Scene3DArena() {
        this(INITIAL_CAPACITY);
    }

    public Scene3DArena(int initialCapacity) {
        this.capacity = Math.max(16, initialCapacity);
        this.localTrs = new OffHeapFloatColumn(this.capacity * TRS_STRIDE);
        this.worldMat = new OffHeapFloatColumn(this.capacity * MAT_STRIDE);
        this.bounds = new OffHeapFloatColumn(this.capacity * BOUNDS_STRIDE);
        this.topology = new OffHeapIntColumn(this.capacity * TOPO_STRIDE);
        this.renderRef = new OffHeapIntColumn(this.capacity * RENDER_STRIDE);
        this.freeStack = new int[this.capacity];
        this.topoOrder = new int[this.capacity];
        this.dfsStack = new int[this.capacity];
    }

    public int capacity() { return capacity; }
    public int highWater() { return highWater; }
    public int aliveCount() { return aliveCount; }

    public OffHeapFloatColumn localTrs() { return localTrs; }
    public OffHeapFloatColumn worldMat() { return worldMat; }
    public OffHeapFloatColumn bounds() { return bounds; }
    public OffHeapIntColumn topology() { return topology; }
    public OffHeapIntColumn renderRef() { return renderRef; }

    public int allocateNode() {
        return allocateNode(NULL_NODE);
    }

    /** 変更の通し回数。単調に増える。 */
    public long changeCount() { return changes; }

    public int allocateNode(int parent) {
        changes++;
        if (parent != NULL_NODE) {
            checkAlive(parent);
        }
        int id;
        if (freeTop > 0) {
            id = freeStack[--freeTop];
        } else {
            if (highWater >= capacity) {
                grow(Math.max(capacity * 2, capacity + 64));
            }
            id = highWater++;
        }
        aliveCount++;
        initNode(id);
        if (parent != NULL_NODE) {
            linkChild(id, parent);
        } else {
            topoDirty = true;
        }
        return id;
    }

    private void initNode(int id) {
        int trsBase = id * TRS_STRIDE;
        localTrs.set(trsBase + TRS_POS_X, 0.0f);
        localTrs.set(trsBase + TRS_POS_Y, 0.0f);
        localTrs.set(trsBase + TRS_POS_Z, 0.0f);
        localTrs.set(trsBase + TRS_ROT_X, 0.0f);
        localTrs.set(trsBase + TRS_ROT_Y, 0.0f);
        localTrs.set(trsBase + TRS_ROT_Z, 0.0f);
        localTrs.set(trsBase + TRS_ROT_W, 1.0f);
        localTrs.set(trsBase + TRS_SCALE_X, 1.0f);
        localTrs.set(trsBase + TRS_SCALE_Y, 1.0f);
        localTrs.set(trsBase + TRS_SCALE_Z, 1.0f);

        int matBase = id * MAT_STRIDE;
        for (int i = 0; i < 16; i++) {
            worldMat.set(matBase + i, (i % 5 == 0) ? 1.0f : 0.0f);
        }

        int bBase = id * BOUNDS_STRIDE;
        for (int i = 0; i < BOUNDS_STRIDE; i++) {
            bounds.set(bBase + i, 0.0f);
        }

        int tBase = id * TOPO_STRIDE;
        topology.set(tBase + TOPO_PARENT, NULL_NODE);
        topology.set(tBase + TOPO_FIRST_CHILD, NULL_NODE);
        topology.set(tBase + TOPO_NEXT_SIBLING, NULL_NODE);
        topology.set(tBase + TOPO_DEPTH, 0);

        int rBase = id * RENDER_STRIDE;
        renderRef.set(rBase + RENDER_MESH_ID, -1);
        renderRef.set(rBase + RENDER_MATERIAL_ID, 0);
        renderRef.set(rBase + RENDER_ENTITY_TAG, 0);
        renderRef.set(rBase + RENDER_FLAGS, FLAG_ALIVE | FLAG_VISIBLE | FLAG_DIRTY);
        anyTransformDirty = true;
    }

    public boolean isAlive(int id) {
        if (id < 0 || id >= highWater) return false;
        return (renderRef.get(id * RENDER_STRIDE + RENDER_FLAGS) & FLAG_ALIVE) != 0;
    }

    public boolean isVisible(int id) {
        if (!isAlive(id)) return false;
        return (renderRef.get(id * RENDER_STRIDE + RENDER_FLAGS) & FLAG_VISIBLE) != 0;
    }

    public void setVisible(int id, boolean visible) {
        checkAlive(id);
        int idx = id * RENDER_STRIDE + RENDER_FLAGS;
        int flags = renderRef.get(idx);
        renderRef.set(idx, visible ? (flags | FLAG_VISIBLE) : (flags & ~FLAG_VISIBLE));
        changes++;
    }

    public void setParent(int child, int newParent) {
        checkAlive(child);
        if (newParent != NULL_NODE) {
            checkAlive(newParent);
            if (child == newParent) {
                throw new IllegalArgumentException("Node cannot be its own parent: " + child);
            }
            for (int anc = newParent; anc != NULL_NODE; anc = topology.get(anc * TOPO_STRIDE + TOPO_PARENT)) {
                if (anc == child) {
                    throw new IllegalArgumentException("Cycle detected reparenting " + child + " under " + newParent);
                }
            }
        }
        int currentParent = topology.get(child * TOPO_STRIDE + TOPO_PARENT);
        if (currentParent == newParent) return;
        if (currentParent != NULL_NODE) {
            unlinkChild(child, currentParent);
        }
        if (newParent != NULL_NODE) {
            linkChild(child, newParent);
        } else {
            topology.set(child * TOPO_STRIDE + TOPO_PARENT, NULL_NODE);
            topology.set(child * TOPO_STRIDE + TOPO_NEXT_SIBLING, NULL_NODE);
            topology.set(child * TOPO_STRIDE + TOPO_DEPTH, 0);
        }
        markDirty(child);
        topoDirty = true;
    }

    private void linkChild(int child, int parent) {
        int parentFirstIdx = parent * TOPO_STRIDE + TOPO_FIRST_CHILD;
        int prevFirst = topology.get(parentFirstIdx);
        topology.set(child * TOPO_STRIDE + TOPO_PARENT, parent);
        topology.set(child * TOPO_STRIDE + TOPO_NEXT_SIBLING, prevFirst);
        topology.set(parentFirstIdx, child);
        topoDirty = true;
    }

    private void unlinkChild(int child, int parent) {
        int parentFirstIdx = parent * TOPO_STRIDE + TOPO_FIRST_CHILD;
        int cur = topology.get(parentFirstIdx);
        if (cur == child) {
            topology.set(parentFirstIdx, topology.get(child * TOPO_STRIDE + TOPO_NEXT_SIBLING));
            topology.set(child * TOPO_STRIDE + TOPO_NEXT_SIBLING, NULL_NODE);
            return;
        }
        while (cur != NULL_NODE) {
            int nextIdx = cur * TOPO_STRIDE + TOPO_NEXT_SIBLING;
            int next = topology.get(nextIdx);
            if (next == child) {
                topology.set(nextIdx, topology.get(child * TOPO_STRIDE + TOPO_NEXT_SIBLING));
                topology.set(child * TOPO_STRIDE + TOPO_NEXT_SIBLING, NULL_NODE);
                return;
            }
            cur = next;
        }
    }

    /**
     * ノードとその子孫サブツリーを解放し、フリーリストへ戻す。
     */
    public void freeNode(int id) {
        changes++;
        checkAlive(id);
        int parent = topology.get(id * TOPO_STRIDE + TOPO_PARENT);
        if (parent != NULL_NODE) {
            unlinkChild(id, parent);
        }
        int sp = 0;
        dfsStack[sp++] = id;
        while (sp > 0) {
            int cur = dfsStack[--sp];
            int child = topology.get(cur * TOPO_STRIDE + TOPO_FIRST_CHILD);
            while (child != NULL_NODE) {
                int next = topology.get(child * TOPO_STRIDE + TOPO_NEXT_SIBLING);
                if (sp >= dfsStack.length) {
                    dfsStack = Arrays.copyOf(dfsStack, dfsStack.length * 2);
                }
                dfsStack[sp++] = child;
                child = next;
            }
            topology.set(cur * TOPO_STRIDE + TOPO_PARENT, NULL_NODE);
            topology.set(cur * TOPO_STRIDE + TOPO_FIRST_CHILD, NULL_NODE);
            topology.set(cur * TOPO_STRIDE + TOPO_NEXT_SIBLING, NULL_NODE);
            renderRef.set(cur * RENDER_STRIDE + RENDER_MESH_ID, -1);
            renderRef.set(cur * RENDER_STRIDE + RENDER_FLAGS, 0);
            freeStack[freeTop++] = cur;
            aliveCount--;
        }
        topoDirty = true;
    }

    public void setPosition(int id, float x, float y, float z) {
        checkAlive(id);
        int b = id * TRS_STRIDE;
        localTrs.set(b + TRS_POS_X, x);
        localTrs.set(b + TRS_POS_Y, y);
        localTrs.set(b + TRS_POS_Z, z);
        markDirty(id);
    }

    public void setRotationQuat(int id, float qx, float qy, float qz, float qw) {
        checkAlive(id);
        float lenSq = qx * qx + qy * qy + qz * qz + qw * qw;
        if (lenSq > 1e-12f) {
            float inv = (float) (1.0 / Math.sqrt(lenSq));
            qx *= inv;
            qy *= inv;
            qz *= inv;
            qw *= inv;
        } else {
            qx = 0.0f;
            qy = 0.0f;
            qz = 0.0f;
            qw = 1.0f;
        }
        int b = id * TRS_STRIDE;
        localTrs.set(b + TRS_ROT_X, qx);
        localTrs.set(b + TRS_ROT_Y, qy);
        localTrs.set(b + TRS_ROT_Z, qz);
        localTrs.set(b + TRS_ROT_W, qw);
        markDirty(id);
    }

    public void setRotationEulerRadians(int id, float rx, float ry, float rz) {
        float hx = rx * 0.5f;
        float hy = ry * 0.5f;
        float hz = rz * 0.5f;
        float sx = (float) Math.sin(hx), cx = (float) Math.cos(hx);
        float sy = (float) Math.sin(hy), cy = (float) Math.cos(hy);
        float sz = (float) Math.sin(hz), cz = (float) Math.cos(hz);
        // XYZ intrinsic == ZYX extrinsic? Standard Y*X*Z or Z*Y*X: q = qz * qy * qx
        float qw = cz * cy * cx + sz * sy * sx;
        float qx = cz * cy * sx - sz * sy * cx;
        float qy = cz * sy * cx + sz * cy * sx;
        float qz = sz * cy * cx - cz * sy * sx;
        setRotationQuat(id, qx, qy, qz, qw);
    }

    public void setScale(int id, float sx, float sy, float sz) {
        checkAlive(id);
        int b = id * TRS_STRIDE;
        localTrs.set(b + TRS_SCALE_X, sx);
        localTrs.set(b + TRS_SCALE_Y, sy);
        localTrs.set(b + TRS_SCALE_Z, sz);
        markDirty(id);
    }

    public void setMesh(int id, int meshId) {
        checkAlive(id);
        renderRef.set(id * RENDER_STRIDE + RENDER_MESH_ID, meshId);
        markDirty(id);
    }

    public void setMaterial(int id, int materialId) {
        checkAlive(id);
        renderRef.set(id * RENDER_STRIDE + RENDER_MATERIAL_ID, materialId);
        changes++;
    }

    public void setEntityTag(int id, int entityTag) {
        checkAlive(id);
        renderRef.set(id * RENDER_STRIDE + RENDER_ENTITY_TAG, entityTag);
    }

    public int meshId(int id) {
        checkAlive(id);
        return renderRef.get(id * RENDER_STRIDE + RENDER_MESH_ID);
    }

    public int materialId(int id) {
        checkAlive(id);
        return renderRef.get(id * RENDER_STRIDE + RENDER_MATERIAL_ID);
    }

    public int entityTag(int id) {
        checkAlive(id);
        return renderRef.get(id * RENDER_STRIDE + RENDER_ENTITY_TAG);
    }

    public int parentOf(int id) {
        checkAlive(id);
        return topology.get(id * TOPO_STRIDE + TOPO_PARENT);
    }

    public void markDirty(int id) {
        int idx = id * RENDER_STRIDE + RENDER_FLAGS;
        renderRef.set(idx, renderRef.get(idx) | FLAG_DIRTY);
        anyTransformDirty = true;
        changes++;
    }

    public void markAllDirty() {
        changes++;
        for (int i = 0; i < highWater; i++) {
            int idx = i * RENDER_STRIDE + RENDER_FLAGS;
            int f = renderRef.get(idx);
            if ((f & FLAG_ALIVE) != 0) {
                renderRef.set(idx, f | FLAG_DIRTY);
            }
        }
        anyTransformDirty = true;
    }

    /** 変換系（ワールド行列・AABB）が更新された回数。レイキャストの BVH が作り直しの要否を決める。 */
    public long transformEpoch() { return transformEpoch; }

    void bumpTransformEpoch() { transformEpoch++; }

    private long transformEpoch;

    boolean consumeTransformDirty() {
        boolean d = anyTransformDirty || topoDirty;
        anyTransformDirty = false;
        return d;
    }

    /**
     * 親が必ず子より先に現れるトポロジカル走査順配列を返す（必要な時のみ再構築）。
     */
    public int[] topologicalOrder() {
        if (topoDirty) {
            rebuildTopoOrder();
        }
        return topoOrder;
    }

    public int topologicalCount() {
        if (topoDirty) {
            rebuildTopoOrder();
        }
        return topoCount;
    }

    private void rebuildTopoOrder() {
        topoCount = 0;
        for (int root = 0; root < highWater; root++) {
            int flags = renderRef.get(root * RENDER_STRIDE + RENDER_FLAGS);
            if ((flags & FLAG_ALIVE) == 0) continue;
            if (topology.get(root * TOPO_STRIDE + TOPO_PARENT) != NULL_NODE) continue;
            topology.set(root * TOPO_STRIDE + TOPO_DEPTH, 0);
            int sp = 0;
            dfsStack[sp++] = root;
            while (sp > 0) {
                int cur = dfsStack[--sp];
                topoOrder[topoCount++] = cur;
                int childDepth = topology.get(cur * TOPO_STRIDE + TOPO_DEPTH) + 1;
                int child = topology.get(cur * TOPO_STRIDE + TOPO_FIRST_CHILD);
                while (child != NULL_NODE) {
                    topology.set(child * TOPO_STRIDE + TOPO_DEPTH, childDepth);
                    if (sp >= dfsStack.length) {
                        dfsStack = Arrays.copyOf(dfsStack, dfsStack.length * 2);
                    }
                    dfsStack[sp++] = child;
                    child = topology.get(child * TOPO_STRIDE + TOPO_NEXT_SIBLING);
                }
            }
        }
        topoDirty = false;
    }

    private void checkAlive(int id) {
        if (!isAlive(id)) {
            throw new IllegalArgumentException("Dead or invalid Scene3D node: " + id);
        }
    }

    private void grow(int newCapacity) {
        OffHeapFloatColumn newTrs = new OffHeapFloatColumn(newCapacity * TRS_STRIDE);
        newTrs.copyPrefixFrom(localTrs, highWater * TRS_STRIDE);
        OffHeapFloatColumn newWorld = new OffHeapFloatColumn(newCapacity * MAT_STRIDE);
        newWorld.copyPrefixFrom(worldMat, highWater * MAT_STRIDE);
        OffHeapFloatColumn newBounds = new OffHeapFloatColumn(newCapacity * BOUNDS_STRIDE);
        newBounds.copyPrefixFrom(bounds, highWater * BOUNDS_STRIDE);
        OffHeapIntColumn newTopo = new OffHeapIntColumn(newCapacity * TOPO_STRIDE);
        newTopo.copyPrefixFrom(topology, highWater * TOPO_STRIDE);
        OffHeapIntColumn newRender = new OffHeapIntColumn(newCapacity * RENDER_STRIDE);
        newRender.copyPrefixFrom(renderRef, highWater * RENDER_STRIDE);

        this.localTrs = newTrs;
        this.worldMat = newWorld;
        this.bounds = newBounds;
        this.topology = newTopo;
        this.renderRef = newRender;
        this.freeStack = Arrays.copyOf(freeStack, newCapacity);
        this.topoOrder = Arrays.copyOf(topoOrder, newCapacity);
        this.dfsStack = Arrays.copyOf(dfsStack, newCapacity);
        this.capacity = newCapacity;
    }
}
