package com.nishiyu.enderframelib.core.scene3d;

import com.nishiyu.enderframelib.core.kernel.memory.OffHeapFloatColumn;
import java.nio.ByteBuffer;

/**
 * 3D のレイキャスト（X3-3）。{@link Scene3DArena} のワールド AABB から作る BVH で候補を絞り、メッシュの三角形で厳密に判定する。
 * GPU にも描画バックエンドにも依らない純 CPU の処理で、ヘッドレスでも同じ結果になる（クリック・ホバーの当たり判定の土台）。
 *
 * <p>BVH は変換系が更新された版（{@link Scene3DArena#transformEpoch}）が変わったときだけ作り直す。レイごとの確保は結果の {@link Hit} だけ
 * （呼び出し側が使い回す）。可視でない（{@link Scene3DArena#FLAG_VISIBLE}）ノードは当たらない。三角形は表裏を問わない。</p>
 */
public final class Raycaster3D {
    /** 最も近い当たり。{@code node < 0} なら当たりなし。{@code t} はレイの媒介変数（方向が単位長なら距離）。 */
    public static final class Hit {
        public int node = -1;
        public int entityTag;
        public int triangle;
        public float t;
        public float x, y, z;
        /** 三角形内の重心座標（頂点 1・頂点 2 の重み。頂点 0 は {@code 1 - u - v}）。 */
        public float u, v;

        public boolean hit() {
            return node >= 0;
        }

        public void clear() {
            node = -1;
        }
    }

    private static final int LEAF_SIZE = 4;
    private static final int STACK = 96;

    // BVH: ノード i の箱は bb[6i..6i+5]。内部は left=子 i、right=もう 1 つの子。葉は count>0 で first..first+count が prims の範囲。
    private float[] bb = new float[0];
    private int[] left = new int[0], right = new int[0], first = new int[0], count = new int[0];
    private int[] prims = new int[0];
    private float[] primBox = new float[0];
    private int nodeCount;
    private long builtEpoch = -1;
    private int builtAlive = -1;
    private final int[] stack = new int[STACK];
    private int[] tmpOrder = new int[0];

    /** 前回の作り直しで BVH に入れたノード数。 */
    public int primitiveCount() {
        return prims.length;
    }

    /** BVH を作り直した回数（試験用）。 */
    public int rebuilds() {
        return rebuilds;
    }

    private int rebuilds;

    /**
     * 画面上の点（左上原点の画素）から出るレイで最も近い当たりを {@code out} へ。当たりが無ければ {@code out.node} が負。
     * @return 当たったか
     */
    public boolean pickScreen(Scene3DWorld world, float px, float py, int viewportW, int viewportH, Hit out) {
        float[] inv = new float[16];
        if (!invert(world.camera().viewProjMatrix(), inv)) {
            out.clear();
            return false;
        }
        float nx = 2f * px / viewportW - 1f, ny = 1f - 2f * py / viewportH;
        float[] a = unproject(inv, nx, ny, -1f), b = unproject(inv, nx, ny, 1f);
        float dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-12f) {
            out.clear();
            return false;
        }
        return cast(world, a[0], a[1], a[2], dx / len, dy / len, dz / len, Float.POSITIVE_INFINITY, out);
    }

    /** ワールド空間のレイ（始点・方向）。{@code tMax} までで最も近い当たりを {@code out} へ。 */
    public boolean cast(Scene3DWorld world, float ox, float oy, float oz, float dx, float dy, float dz, float tMax, Hit out) {
        Scene3DArena arena = world.arena();
        world.transformSystem().update(arena, world.geometry());
        refit(arena, world.geometry());
        out.clear();
        if (nodeCount == 0) return false;

        float ix = inv(dx), iy = inv(dy), iz = inv(dz);
        float best = tMax;
        ByteBuffer vb = world.geometry().vertexBufferSlice();
        ByteBuffer ib = world.geometry().indexBufferSlice();
        int sp = 0;
        stack[sp++] = 0;
        while (sp > 0) {
            int n = stack[--sp];
            float tn = slab(bb, n * 6, ox, oy, oz, ix, iy, iz, best);
            if (tn < 0) continue;
            if (count[n] > 0) {
                for (int k = first[n], e = k + count[n]; k < e; k++) {
                    int node = prims[k];
                    if (!arena.isVisible(node)) continue;
                    if (slab(primBox, k * 6, ox, oy, oz, ix, iy, iz, best) < 0) continue;
                    best = narrow(arena, world.geometry(), vb, ib, node, ox, oy, oz, dx, dy, dz, best, out);
                }
            } else {
                // 近い子を後に積んで先に取り出す
                int l = left[n], r = right[n];
                float tl = slab(bb, l * 6, ox, oy, oz, ix, iy, iz, best), tr = slab(bb, r * 6, ox, oy, oz, ix, iy, iz, best);
                if (tl >= 0 && tr >= 0) {
                    if (tl <= tr) { stack[sp++] = r; stack[sp++] = l; } else { stack[sp++] = l; stack[sp++] = r; }
                } else if (tl >= 0) stack[sp++] = l;
                else if (tr >= 0) stack[sp++] = r;
            }
        }
        return out.node >= 0;
    }

    private static float inv(float d) {
        return Math.abs(d) < 1e-30f ? (d < 0 ? -1e30f : 1e30f) : 1f / d;
    }

    /** レイと箱の交差。入る距離（始点が箱の中なら 0）。外れ・{@code tMax} 超えは負。 */
    private static float slab(float[] box, int o, float ox, float oy, float oz, float ix, float iy, float iz, float tMax) {
        float t1 = (box[o] - ox) * ix, t2 = (box[o + 3] - ox) * ix;
        float tmin = Math.min(t1, t2), tmax = Math.max(t1, t2);
        t1 = (box[o + 1] - oy) * iy;
        t2 = (box[o + 4] - oy) * iy;
        tmin = Math.max(tmin, Math.min(t1, t2));
        tmax = Math.min(tmax, Math.max(t1, t2));
        t1 = (box[o + 2] - oz) * iz;
        t2 = (box[o + 5] - oz) * iz;
        tmin = Math.max(tmin, Math.min(t1, t2));
        tmax = Math.min(tmax, Math.max(t1, t2));
        if (tmax < 0 || tmin > tmax || tmin > tMax) return -1f;
        return Math.max(tmin, 0f);
    }

    /** ノードのメッシュの三角形で厳密に判定する（レイをローカル空間へ戻して Möller–Trumbore）。よい当たりなら {@code out} を更新して距離を返す。 */
    private static float narrow(Scene3DArena arena, GeometryPool geo, ByteBuffer vb, ByteBuffer ib, int node,
                                float ox, float oy, float oz, float dx, float dy, float dz, float best, Hit out) {
        int meshId = arena.meshId(node);
        MeshHandle mesh = geo.meshOrNull(meshId);
        if (mesh == null) return best;
        OffHeapFloatColumn w = arena.worldMat();
        int b = node * Scene3DArena.MAT_STRIDE;
        float m0 = w.get(b), m1 = w.get(b + 1), m2 = w.get(b + 2);
        float m4 = w.get(b + 4), m5 = w.get(b + 5), m6 = w.get(b + 6);
        float m8 = w.get(b + 8), m9 = w.get(b + 9), m10 = w.get(b + 10);
        float tx = w.get(b + 12), ty = w.get(b + 13), tz = w.get(b + 14);
        // 3x3（列優先）の逆行列
        float c00 = m5 * m10 - m9 * m6, c01 = m9 * m2 - m1 * m10, c02 = m1 * m6 - m5 * m2;
        float det = m0 * c00 + m4 * c01 + m8 * c02;
        if (Math.abs(det) < 1e-20f) return best;
        float id = 1f / det;
        float i0 = c00 * id, i1 = c01 * id, i2 = c02 * id;
        float i4 = (m8 * m6 - m4 * m10) * id, i5 = (m0 * m10 - m8 * m2) * id, i6 = (m4 * m2 - m0 * m6) * id;
        float i8 = (m4 * m9 - m8 * m5) * id, i9 = (m8 * m1 - m0 * m9) * id, i10 = (m0 * m5 - m4 * m1) * id;
        float rx = ox - tx, ry = oy - ty, rz = oz - tz;
        float lox = i0 * rx + i4 * ry + i8 * rz, loy = i1 * rx + i5 * ry + i9 * rz, loz = i2 * rx + i6 * ry + i10 * rz;
        float ldx = i0 * dx + i4 * dy + i8 * dz, ldy = i1 * dx + i5 * dy + i9 * dz, ldz = i2 * dx + i6 * dy + i10 * dz;

        int vBase = mesh.firstVertex() * GeometryPool.VERTEX_STRIDE_BYTES;
        int iBase = mesh.firstIndex() * GeometryPool.INDEX_STRIDE_BYTES;
        float bestT = best;
        int bestTri = -1;
        float bu = 0, bv = 0;
        for (int t = 0, tris = mesh.indexCount() / 3; t < tris; t++) {
            int p0 = vBase + ib.getInt(iBase + (t * 3) * 4) * GeometryPool.VERTEX_STRIDE_BYTES;
            int p1 = vBase + ib.getInt(iBase + (t * 3 + 1) * 4) * GeometryPool.VERTEX_STRIDE_BYTES;
            int p2 = vBase + ib.getInt(iBase + (t * 3 + 2) * 4) * GeometryPool.VERTEX_STRIDE_BYTES;
            float ax = vb.getFloat(p0), ay = vb.getFloat(p0 + 4), az = vb.getFloat(p0 + 8);
            float e1x = vb.getFloat(p1) - ax, e1y = vb.getFloat(p1 + 4) - ay, e1z = vb.getFloat(p1 + 8) - az;
            float e2x = vb.getFloat(p2) - ax, e2y = vb.getFloat(p2 + 4) - ay, e2z = vb.getFloat(p2 + 8) - az;
            float px = ldy * e2z - ldz * e2y, py = ldz * e2x - ldx * e2z, pz = ldx * e2y - ldy * e2x;
            float d = e1x * px + e1y * py + e1z * pz;
            if (Math.abs(d) < 1e-20f) continue;
            float inv = 1f / d;
            float sx = lox - ax, sy = loy - ay, sz = loz - az;
            float u = (sx * px + sy * py + sz * pz) * inv;
            if (u < 0f || u > 1f) continue;
            float qx = sy * e1z - sz * e1y, qy = sz * e1x - sx * e1z, qz = sx * e1y - sy * e1x;
            float v = (ldx * qx + ldy * qy + ldz * qz) * inv;
            if (v < 0f || u + v > 1f) continue;
            float tt = (e2x * qx + e2y * qy + e2z * qz) * inv;
            if (tt < 0f || tt >= bestT) continue;
            bestT = tt;
            bestTri = t;
            bu = u;
            bv = v;
        }
        if (bestTri < 0) return best;
        out.node = node;
        out.entityTag = arena.entityTag(node);
        out.triangle = bestTri;
        out.t = bestT;
        out.x = ox + dx * bestT;
        out.y = oy + dy * bestT;
        out.z = oz + dz * bestT;
        out.u = bu;
        out.v = bv;
        return bestT;
    }

    // ---- BVH の構築 ----

    private void refit(Scene3DArena arena, GeometryPool geo) {
        long epoch = arena.transformEpoch();
        if (epoch == builtEpoch && arena.aliveCount() == builtAlive) return;
        builtEpoch = epoch;
        builtAlive = arena.aliveCount();
        rebuilds++;
        int n = 0;
        int hw = arena.highWater();
        if (tmpOrder.length < hw) tmpOrder = new int[hw];
        OffHeapFloatColumn bounds = arena.bounds();
        float[] box = new float[Math.max(1, hw) * 6];
        for (int id = 0; id < hw; id++) {
            if (!arena.isAlive(id) || arena.meshId(id) < 0 || geo.meshOrNull(arena.meshId(id)) == null) continue;
            int o = id * Scene3DArena.BOUNDS_STRIDE;
            for (int k = 0; k < 6; k++) box[n * 6 + k] = bounds.get(o + k);
            tmpOrder[n] = id;
            n++;
        }
        prims = new int[n];
        primBox = new float[n * 6];
        nodeCount = 0;
        if (n == 0) return;
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        int cap = 2 * n;
        if (bb.length < cap * 6) {
            bb = new float[cap * 6];
            left = new int[cap];
            right = new int[cap];
            first = new int[cap];
            count = new int[cap];
        }
        build(box, idx, 0, n);
        for (int i = 0; i < n; i++) {
            prims[i] = tmpOrder[idx[i]];
            System.arraycopy(box, idx[i] * 6, primBox, i * 6, 6);
        }
    }

    private int build(float[] box, int[] idx, int lo, int hi) {
        int node = nodeCount++;
        float x0 = Float.POSITIVE_INFINITY, y0 = x0, z0 = x0, x1 = Float.NEGATIVE_INFINITY, y1 = x1, z1 = x1;
        float cx0 = x0, cy0 = x0, cz0 = x0, cx1 = x1, cy1 = x1, cz1 = x1;
        for (int i = lo; i < hi; i++) {
            int o = idx[i] * 6;
            x0 = Math.min(x0, box[o]); y0 = Math.min(y0, box[o + 1]); z0 = Math.min(z0, box[o + 2]);
            x1 = Math.max(x1, box[o + 3]); y1 = Math.max(y1, box[o + 4]); z1 = Math.max(z1, box[o + 5]);
            float cx = box[o] + box[o + 3], cy = box[o + 1] + box[o + 4], cz = box[o + 2] + box[o + 5];
            cx0 = Math.min(cx0, cx); cx1 = Math.max(cx1, cx);
            cy0 = Math.min(cy0, cy); cy1 = Math.max(cy1, cy);
            cz0 = Math.min(cz0, cz); cz1 = Math.max(cz1, cz);
        }
        int o = node * 6;
        bb[o] = x0; bb[o + 1] = y0; bb[o + 2] = z0; bb[o + 3] = x1; bb[o + 4] = y1; bb[o + 5] = z1;
        if (hi - lo <= LEAF_SIZE) {
            first[node] = lo;
            count[node] = hi - lo;
            return node;
        }
        float ex = cx1 - cx0, ey = cy1 - cy0, ez = cz1 - cz0;
        int axis = ex >= ey && ex >= ez ? 0 : (ey >= ez ? 1 : 2);
        int mid = (lo + hi) >>> 1;
        select(box, idx, lo, hi - 1, mid, axis);
        count[node] = 0;
        left[node] = build(box, idx, lo, mid);
        right[node] = build(box, idx, mid, hi);
        return node;
    }

    private static float key(float[] box, int prim, int axis) {
        return box[prim * 6 + axis] + box[prim * 6 + 3 + axis];
    }

    /** idx[lo..hi] を、k 番目が軸の中心の順で正しい位置に来るように並べる（クイックセレクト）。 */
    private static void select(float[] box, int[] idx, int lo, int hi, int k, int axis) {
        while (lo < hi) {
            float pivot = key(box, idx[(lo + hi) >>> 1], axis);
            int i = lo, j = hi;
            while (i <= j) {
                while (key(box, idx[i], axis) < pivot) i++;
                while (key(box, idx[j], axis) > pivot) j--;
                if (i <= j) {
                    int t = idx[i]; idx[i] = idx[j]; idx[j] = t;
                    i++;
                    j--;
                }
            }
            if (k <= j) hi = j;
            else if (k >= i) lo = i;
            else return;
        }
    }

    // ---- 行列（列優先 4x4）----

    private static float[] unproject(float[] inv, float x, float y, float z) {
        float ox = inv[0] * x + inv[4] * y + inv[8] * z + inv[12];
        float oy = inv[1] * x + inv[5] * y + inv[9] * z + inv[13];
        float oz = inv[2] * x + inv[6] * y + inv[10] * z + inv[14];
        float ow = inv[3] * x + inv[7] * y + inv[11] * z + inv[15];
        return new float[]{ox / ow, oy / ow, oz / ow};
    }

    /** 4x4（列優先）の逆行列。特異なら false。 */
    static boolean invert(float[] m, float[] out) {
        double[] a = new double[16];
        for (int i = 0; i < 16; i++) a[i] = m[i];
        double[] inv = new double[16];
        inv[0] = a[5] * a[10] * a[15] - a[5] * a[11] * a[14] - a[9] * a[6] * a[15] + a[9] * a[7] * a[14] + a[13] * a[6] * a[11] - a[13] * a[7] * a[10];
        inv[4] = -a[4] * a[10] * a[15] + a[4] * a[11] * a[14] + a[8] * a[6] * a[15] - a[8] * a[7] * a[14] - a[12] * a[6] * a[11] + a[12] * a[7] * a[10];
        inv[8] = a[4] * a[9] * a[15] - a[4] * a[11] * a[13] - a[8] * a[5] * a[15] + a[8] * a[7] * a[13] + a[12] * a[5] * a[11] - a[12] * a[7] * a[9];
        inv[12] = -a[4] * a[9] * a[14] + a[4] * a[10] * a[13] + a[8] * a[5] * a[14] - a[8] * a[6] * a[13] - a[12] * a[5] * a[10] + a[12] * a[6] * a[9];
        inv[1] = -a[1] * a[10] * a[15] + a[1] * a[11] * a[14] + a[9] * a[2] * a[15] - a[9] * a[3] * a[14] - a[13] * a[2] * a[11] + a[13] * a[3] * a[10];
        inv[5] = a[0] * a[10] * a[15] - a[0] * a[11] * a[14] - a[8] * a[2] * a[15] + a[8] * a[3] * a[14] + a[12] * a[2] * a[11] - a[12] * a[3] * a[10];
        inv[9] = -a[0] * a[9] * a[15] + a[0] * a[11] * a[13] + a[8] * a[1] * a[15] - a[8] * a[3] * a[13] - a[12] * a[1] * a[11] + a[12] * a[3] * a[9];
        inv[13] = a[0] * a[9] * a[14] - a[0] * a[10] * a[13] - a[8] * a[1] * a[14] + a[8] * a[2] * a[13] + a[12] * a[1] * a[10] - a[12] * a[2] * a[9];
        inv[2] = a[1] * a[6] * a[15] - a[1] * a[7] * a[14] - a[5] * a[2] * a[15] + a[5] * a[3] * a[14] + a[13] * a[2] * a[7] - a[13] * a[3] * a[6];
        inv[6] = -a[0] * a[6] * a[15] + a[0] * a[7] * a[14] + a[4] * a[2] * a[15] - a[4] * a[3] * a[14] - a[12] * a[2] * a[7] + a[12] * a[3] * a[6];
        inv[10] = a[0] * a[5] * a[15] - a[0] * a[7] * a[13] - a[4] * a[1] * a[15] + a[4] * a[3] * a[13] + a[12] * a[1] * a[7] - a[12] * a[3] * a[5];
        inv[14] = -a[0] * a[5] * a[14] + a[0] * a[6] * a[13] + a[4] * a[1] * a[14] - a[4] * a[2] * a[13] - a[12] * a[1] * a[6] + a[12] * a[2] * a[5];
        inv[3] = -a[1] * a[6] * a[11] + a[1] * a[7] * a[10] + a[5] * a[2] * a[11] - a[5] * a[3] * a[10] - a[9] * a[2] * a[7] + a[9] * a[3] * a[6];
        inv[7] = a[0] * a[6] * a[11] - a[0] * a[7] * a[10] - a[4] * a[2] * a[11] + a[4] * a[3] * a[10] + a[8] * a[2] * a[7] - a[8] * a[3] * a[6];
        inv[11] = -a[0] * a[5] * a[11] + a[0] * a[7] * a[9] + a[4] * a[1] * a[11] - a[4] * a[3] * a[9] - a[8] * a[1] * a[7] + a[8] * a[3] * a[5];
        inv[15] = a[0] * a[5] * a[10] - a[0] * a[6] * a[9] - a[4] * a[1] * a[10] + a[4] * a[2] * a[9] + a[8] * a[1] * a[6] - a[8] * a[2] * a[5];
        double det = a[0] * inv[0] + a[1] * inv[4] + a[2] * inv[8] + a[3] * inv[12];
        if (Math.abs(det) < 1e-30) return false;
        double r = 1.0 / det;
        for (int i = 0; i < 16; i++) out[i] = (float) (inv[i] * r);
        return true;
    }
}
