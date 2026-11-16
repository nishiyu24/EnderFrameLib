package com.nishiyu.enderframelib.core.scene3d;

import com.nishiyu.enderframelib.core.kernel.gpu.PersistentVramBuffer;
import com.nishiyu.enderframelib.core.kernel.memory.OffHeapFloatColumn;
import com.nishiyu.enderframelib.core.kernel.memory.OffHeapIntColumn;

import java.util.Arrays;

/**
 * フラスタムカリング、プリミティブソート、128B/インスタンスの TBO パッキング、
 * {@code (meshId, blendMode, doubleSided)} ごとのバッチ化を行う。
 */
public final class InstancedBatcher {
    public static final int INSTANCE_STRIDE_BYTES = 128;
    public static final int FLAG_DOUBLE_SIDED = 1 << 0;
    public static final int FLAG_UNLIT = 1 << 1;
    /** ベースカラー画像を掛ける。 */
    public static final int FLAG_TEXTURED = 1 << 2;
    /** 画像の UV を {@code fract} で繰り返す。 */
    public static final int FLAG_REPEAT = 1 << 3;

    private long[] sortKeys = new long[256];
    private int[] sortNodes = new int[256];

    public void preparePlan(Scene3DArena arena, GeometryPool geometry, MaterialPool materials,
                            CameraSpec camera, Scene3DUploadPlan plan) {
        int highWater = arena.highWater();
        plan.reset(highWater);
        if (sortKeys.length < highWater) {
            int nextCap = Math.max(highWater, sortKeys.length * 2);
            sortKeys = new long[nextCap];
            sortNodes = new int[nextCap];
        }

        OffHeapIntColumn render = arena.renderRef();
        OffHeapFloatColumn bounds = arena.bounds();
        camera.ensureUpdated();

        int visible = 0;
        int culled = 0;
        int totalRenderables = 0;

        for (int id = 0; id < highWater; id++) {
            int rb = id * Scene3DArena.RENDER_STRIDE;
            int flags = render.get(rb + Scene3DArena.RENDER_FLAGS);
            if ((flags & Scene3DArena.FLAG_ALIVE) == 0 || (flags & Scene3DArena.FLAG_VISIBLE) == 0) {
                continue;
            }
            int meshId = render.get(rb + Scene3DArena.RENDER_MESH_ID);
            if (meshId < 0 || geometry.meshOrNull(meshId) == null) {
                continue;
            }
            totalRenderables++;

            int bb = id * Scene3DArena.BOUNDS_STRIDE;
            if (!camera.intersectsAabb(
                    bounds.get(bb), bounds.get(bb + 1), bounds.get(bb + 2),
                    bounds.get(bb + 3), bounds.get(bb + 4), bounds.get(bb + 5))) {
                culled++;
                continue;
            }

            int matId = render.get(rb + Scene3DArena.RENDER_MATERIAL_ID);
            MaterialDesc mat = materials.get(matId);
            long key = (((long) mat.blendMode().code() & 0x3L) << 60)
                    | ((mat.doubleSided() ? 1L : 0L) << 59)
                    | (((long) meshId & 0x7FFFFFFL) << 32)
                    | ((long) mat.materialId() & 0xFFFFFFFFL);

            sortKeys[visible] = key;
            sortNodes[visible] = id;
            visible++;
        }

        if (visible > 1) {
            quicksort(sortKeys, sortNodes, 0, visible - 1);
        }

        int[] dstNodes = plan.sortedNodesArray();
        System.arraycopy(sortNodes, 0, dstNodes, 0, visible);
        plan.setCounts(visible, culled, totalRenderables);

        int i = 0;
        while (i < visible) {
            int firstNode = dstNodes[i];
            int rb0 = firstNode * Scene3DArena.RENDER_STRIDE;
            int meshId = render.get(rb0 + Scene3DArena.RENDER_MESH_ID);
            MaterialDesc mat0 = materials.get(render.get(rb0 + Scene3DArena.RENDER_MATERIAL_ID));
            BlendMode3D blend = mat0.blendMode();
            boolean doubleSided = mat0.doubleSided();
            Texture3D texture = mat0.baseColorMap();

            int j = i + 1;
            while (j < visible) {
                int node = dstNodes[j];
                int rbj = node * Scene3DArena.RENDER_STRIDE;
                int nextMesh = render.get(rbj + Scene3DArena.RENDER_MESH_ID);
                MaterialDesc nextMat = materials.get(render.get(rbj + Scene3DArena.RENDER_MATERIAL_ID));
                if (nextMesh != meshId || nextMat.blendMode() != blend || nextMat.doubleSided() != doubleSided
                        || nextMat.baseColorMap() != texture) {
                    break;
                }
                j++;
            }
            plan.addBatch(new DrawBatch3D(meshId, blend, i, j - i, doubleSided, texture));
            i = j;
        }
    }

    public int writeInstances(Scene3DArena arena, MaterialPool materials,
                              Scene3DUploadPlan plan, PersistentVramBuffer instanceBuffer) {
        return writeInstances(arena, materials, plan, instanceBuffer, 0);
    }

    /**
     * {@code firstSlot} 番目のスロットから詰める。1 フレームに複数のワールドを描くとき、先のワールドの範囲を上書きしないよう
     * 後のワールドは後ろへ置く（描画コマンドは書き込みのあとで実行されるため）。
     */
    public int writeInstances(Scene3DArena arena, MaterialPool materials,
                              Scene3DUploadPlan plan, PersistentVramBuffer instanceBuffer, int firstSlot) {
        int count = Math.min(plan.instanceCount(), Math.max(0, instanceBuffer.capacity() - firstSlot));
        while (instanceBuffer.highWater() < firstSlot + count) {
            if (instanceBuffer.allocate() < 0) break;
        }
        OffHeapFloatColumn world = arena.worldMat();
        OffHeapIntColumn render = arena.renderRef();
        int[] nodes = plan.sortedNodesArray();

        for (int i = 0; i < count; i++) {
            int slot = firstSlot + i;
            int id = nodes[i];
            int wb = id * Scene3DArena.MAT_STRIDE;
            int rb = id * Scene3DArena.RENDER_STRIDE;

            float m0 = world.get(wb), m1 = world.get(wb + 1), m2 = world.get(wb + 2), m3 = world.get(wb + 3);
            float m4 = world.get(wb + 4), m5 = world.get(wb + 5), m6 = world.get(wb + 6), m7 = world.get(wb + 7);
            float m8 = world.get(wb + 8), m9 = world.get(wb + 9), m10 = world.get(wb + 10), m11 = world.get(wb + 11);
            float m12 = world.get(wb + 12), m13 = world.get(wb + 13), m14 = world.get(wb + 14), m15 = world.get(wb + 15);

            // Texel 0..3: Model 4x4 (64B)
            instanceBuffer.putFloat(slot, 0, m0);
            instanceBuffer.putFloat(slot, 4, m1);
            instanceBuffer.putFloat(slot, 8, m2);
            instanceBuffer.putFloat(slot, 12, m3);
            instanceBuffer.putFloat(slot, 16, m4);
            instanceBuffer.putFloat(slot, 20, m5);
            instanceBuffer.putFloat(slot, 24, m6);
            instanceBuffer.putFloat(slot, 28, m7);
            instanceBuffer.putFloat(slot, 32, m8);
            instanceBuffer.putFloat(slot, 36, m9);
            instanceBuffer.putFloat(slot, 40, m10);
            instanceBuffer.putFloat(slot, 44, m11);
            instanceBuffer.putFloat(slot, 48, m12);
            instanceBuffer.putFloat(slot, 52, m13);
            instanceBuffer.putFloat(slot, 56, m14);
            instanceBuffer.putFloat(slot, 60, m15);

            // Texel 4..6.x: 法線行列 3x3 = (A^-1)^T (36B)
            // 法線行列 = A⁻¹ の転置。列優先で渡すので、各列には A⁻¹ の「行」（= 隣り合う 2 列の外積 / det）を入れる
            float det = m0 * (m5 * m10 - m9 * m6) + m4 * (m9 * m2 - m1 * m10) + m8 * (m1 * m6 - m5 * m2);

            float nm0, nm1, nm2, nm3, nm4, nm5, nm6, nm7, nm8;
            if (Math.abs(det) > 1e-12f) {
                float invDet = 1.0f / det;
                nm0 = (m5 * m10 - m6 * m9) * invDet;
                nm1 = (m6 * m8 - m4 * m10) * invDet;
                nm2 = (m4 * m9 - m5 * m8) * invDet;
                nm3 = (m9 * m2 - m10 * m1) * invDet;
                nm4 = (m10 * m0 - m8 * m2) * invDet;
                nm5 = (m8 * m1 - m9 * m0) * invDet;
                nm6 = (m1 * m6 - m2 * m5) * invDet;
                nm7 = (m2 * m4 - m0 * m6) * invDet;
                nm8 = (m0 * m5 - m1 * m4) * invDet;
            } else {
                nm0 = 1.0f; nm1 = 0.0f; nm2 = 0.0f;
                nm3 = 0.0f; nm4 = 1.0f; nm5 = 0.0f;
                nm6 = 0.0f; nm7 = 0.0f; nm8 = 1.0f;
            }

            instanceBuffer.putFloat(slot, 64, nm0);
            instanceBuffer.putFloat(slot, 68, nm1);
            instanceBuffer.putFloat(slot, 72, nm2);
            instanceBuffer.putFloat(slot, 76, nm3);
            instanceBuffer.putFloat(slot, 80, nm4);
            instanceBuffer.putFloat(slot, 84, nm5);
            instanceBuffer.putFloat(slot, 88, nm6);
            instanceBuffer.putFloat(slot, 92, nm7);
            instanceBuffer.putFloat(slot, 96, nm8);

            // Texel 6.yza & Texel 7: マテリアル・タグ・パック属性 (28B)
            int matId = render.get(rb + Scene3DArena.RENDER_MATERIAL_ID);
            int entityTag = render.get(rb + Scene3DArena.RENDER_ENTITY_TAG);
            MaterialDesc mat = materials.get(matId);
            int instFlags = (mat.doubleSided() ? FLAG_DOUBLE_SIDED : 0) | (mat.unlit() ? FLAG_UNLIT : 0)
                    | (mat.baseColorMap() != null ? FLAG_TEXTURED : 0) | (mat.mapRepeat() ? FLAG_REPEAT : 0);

            int metalByte = clampByte(mat.metallic() * 255.0f);
            int roughByte = clampByte(mat.roughness() * 255.0f);
            int pbrPacked = (metalByte & 0xFF)
                    | ((roughByte & 0xFF) << 8)
                    | ((mat.blendMode().code() & 0xFF) << 16);

            int emissiveStrengthByte = clampByte((mat.emissiveStrength() / 4.0f) * 255.0f);
            int emissivePacked = (mat.emissiveRgb() & 0x00FFFFFF) | ((emissiveStrengthByte & 0xFF) << 24);

            instanceBuffer.putInt(slot, 100, mat.materialId());
            instanceBuffer.putInt(slot, 104, entityTag);
            instanceBuffer.putInt(slot, 108, instFlags);
            instanceBuffer.putInt(slot, 112, mat.baseColorArgb());
            instanceBuffer.putInt(slot, 116, pbrPacked);
            instanceBuffer.putInt(slot, 120, emissivePacked);
            instanceBuffer.putInt(slot, 124, 0);
        }
        return count;
    }

    public int batch(Scene3DArena arena, GeometryPool geometry, MaterialPool materials,
                     CameraSpec camera, PersistentVramBuffer instanceBuffer, Scene3DUploadPlan plan) {
        preparePlan(arena, geometry, materials, camera, plan);
        return writeInstances(arena, materials, plan, instanceBuffer);
    }

    private static int clampByte(float v) {
        return Math.max(0, Math.min(255, Math.round(v)));
    }

    private static void quicksort(long[] keys, int[] vals, int lo, int hi) {
        while (lo < hi) {
            if (hi - lo < 16) {
                for (int i = lo + 1; i <= hi; i++) {
                    long k = keys[i];
                    int v = vals[i];
                    int j = i - 1;
                    while (j >= lo && keys[j] > k) {
                        keys[j + 1] = keys[j];
                        vals[j + 1] = vals[j];
                        j--;
                    }
                    keys[j + 1] = k;
                    vals[j + 1] = v;
                }
                return;
            }
            int mid = (lo + hi) >>> 1;
            long pivot = keys[mid];
            int i = lo;
            int j = hi;
            while (i <= j) {
                while (keys[i] < pivot) i++;
                while (keys[j] > pivot) j--;
                if (i <= j) {
                    long tk = keys[i]; keys[i] = keys[j]; keys[j] = tk;
                    int tv = vals[i]; vals[i] = vals[j]; vals[j] = tv;
                    i++;
                    j--;
                }
            }
            if (j - lo < hi - i) {
                if (lo < j) quicksort(keys, vals, lo, j);
                lo = i;
            } else {
                if (i < hi) quicksort(keys, vals, i, hi);
                hi = j;
            }
        }
    }
}
