package com.nishiyu.enderframelib.core.scene3d;

import com.nishiyu.enderframelib.core.kernel.memory.OffHeapFloatColumn;
import com.nishiyu.enderframelib.core.kernel.memory.OffHeapIntColumn;

/**
 * {@link Scene3DArena} のトポロジカル順配列を走査し、ヒープ確保ゼロでワールド変換行列と
 * ワールド AABB を更新するシステム。
 */
public final class Transform3DSystem {

    public void update(Scene3DArena arena, GeometryPool geometry) {
        if (arena.aliveCount() == 0) return;
        int count = arena.topologicalCount();
        int[] order = arena.topologicalOrder();

        OffHeapFloatColumn trs = arena.localTrs();
        OffHeapFloatColumn world = arena.worldMat();
        OffHeapFloatColumn bounds = arena.bounds();
        OffHeapIntColumn topo = arena.topology();
        OffHeapIntColumn render = arena.renderRef();

        boolean meshBoundsChanged = geometry != null && geometry.version() != lastGeometryVersion;
        if (meshBoundsChanged) {
            lastGeometryVersion = geometry.version();
        }
        boolean needsAnyUpdate = arena.consumeTransformDirty() || meshBoundsChanged;
        if (!needsAnyUpdate) {
            return;
        }
        arena.bumpTransformEpoch();

        for (int k = 0; k < count; k++) {
            int id = order[k];
            int rBase = id * Scene3DArena.RENDER_STRIDE;
            int flags = render.get(rBase + Scene3DArena.RENDER_FLAGS);
            int parent = topo.get(id * Scene3DArena.TOPO_STRIDE + Scene3DArena.TOPO_PARENT);
            boolean parentDirty = parent != Scene3DArena.NULL_NODE
                    && (render.get(parent * Scene3DArena.RENDER_STRIDE + Scene3DArena.RENDER_FLAGS)
                    & Scene3DArena.FLAG_DIRTY) != 0;
            boolean dirty = meshBoundsChanged || parentDirty || ((flags & Scene3DArena.FLAG_DIRTY) != 0);
            if (!dirty) {
                continue;
            }
            if (parentDirty) {
                render.set(rBase + Scene3DArena.RENDER_FLAGS, flags | Scene3DArena.FLAG_DIRTY);
            }

            int tb = id * Scene3DArena.TRS_STRIDE;
            float px = trs.get(tb + Scene3DArena.TRS_POS_X);
            float py = trs.get(tb + Scene3DArena.TRS_POS_Y);
            float pz = trs.get(tb + Scene3DArena.TRS_POS_Z);
            float qx = trs.get(tb + Scene3DArena.TRS_ROT_X);
            float qy = trs.get(tb + Scene3DArena.TRS_ROT_Y);
            float qz = trs.get(tb + Scene3DArena.TRS_ROT_Z);
            float qw = trs.get(tb + Scene3DArena.TRS_ROT_W);
            float sx = trs.get(tb + Scene3DArena.TRS_SCALE_X);
            float sy = trs.get(tb + Scene3DArena.TRS_SCALE_Y);
            float sz = trs.get(tb + Scene3DArena.TRS_SCALE_Z);

            float xx = qx * qx, yy = qy * qy, zz = qz * qz;
            float xy = qx * qy, xz = qx * qz, yz = qy * qz;
            float wx = qw * qx, wy = qw * qy, wz = qw * qz;

            float l0 = (1.0f - 2.0f * (yy + zz)) * sx;
            float l1 = (2.0f * (xy + wz)) * sx;
            float l2 = (2.0f * (xz - wy)) * sx;
            float l4 = (2.0f * (xy - wz)) * sy;
            float l5 = (1.0f - 2.0f * (xx + zz)) * sy;
            float l6 = (2.0f * (yz + wx)) * sy;
            float l8 = (2.0f * (xz + wy)) * sz;
            float l9 = (2.0f * (yz - wx)) * sz;
            float l10 = (1.0f - 2.0f * (xx + yy)) * sz;

            int wb = id * Scene3DArena.MAT_STRIDE;
            float m0, m1, m2, m4, m5, m6, m8, m9, m10, m12, m13, m14;
            if (parent == Scene3DArena.NULL_NODE) {
                m0 = l0; m1 = l1; m2 = l2;
                m4 = l4; m5 = l5; m6 = l6;
                m8 = l8; m9 = l9; m10 = l10;
                m12 = px; m13 = py; m14 = pz;
            } else {
                int pb = parent * Scene3DArena.MAT_STRIDE;
                float p0 = world.get(pb), p1 = world.get(pb + 1), p2 = world.get(pb + 2);
                float p4 = world.get(pb + 4), p5 = world.get(pb + 5), p6 = world.get(pb + 6);
                float p8 = world.get(pb + 8), p9 = world.get(pb + 9), p10 = world.get(pb + 10);
                float p12 = world.get(pb + 12), p13 = world.get(pb + 13), p14 = world.get(pb + 14);

                m0 = p0 * l0 + p4 * l1 + p8 * l2;
                m1 = p1 * l0 + p5 * l1 + p9 * l2;
                m2 = p2 * l0 + p6 * l1 + p10 * l2;

                m4 = p0 * l4 + p4 * l5 + p8 * l6;
                m5 = p1 * l4 + p5 * l5 + p9 * l6;
                m6 = p2 * l4 + p6 * l5 + p10 * l6;

                m8 = p0 * l8 + p4 * l9 + p8 * l10;
                m9 = p1 * l8 + p5 * l9 + p9 * l10;
                m10 = p2 * l8 + p6 * l9 + p10 * l10;

                m12 = p0 * px + p4 * py + p8 * pz + p12;
                m13 = p1 * px + p5 * py + p9 * pz + p13;
                m14 = p2 * px + p6 * py + p10 * pz + p14;
            }

            world.set(wb, m0);   world.set(wb + 1, m1);   world.set(wb + 2, m2);   world.set(wb + 3, 0.0f);
            world.set(wb + 4, m4);   world.set(wb + 5, m5);   world.set(wb + 6, m6);   world.set(wb + 7, 0.0f);
            world.set(wb + 8, m8);   world.set(wb + 9, m9);   world.set(wb + 10, m10); world.set(wb + 11, 0.0f);
            world.set(wb + 12, m12); world.set(wb + 13, m13); world.set(wb + 14, m14); world.set(wb + 15, 1.0f);

            int meshId = render.get(rBase + Scene3DArena.RENDER_MESH_ID);
            int bb = id * Scene3DArena.BOUNDS_STRIDE;
            MeshHandle mesh = (meshId >= 0 && geometry != null) ? geometry.meshOrNull(meshId) : null;
            if (mesh == null) {
                bounds.set(bb, m12);
                bounds.set(bb + 1, m13);
                bounds.set(bb + 2, m14);
                bounds.set(bb + 3, m12);
                bounds.set(bb + 4, m13);
                bounds.set(bb + 5, m14);
            } else {
                // Arvo の AABB 変換
                float lx0 = mesh.minX(), ly0 = mesh.minY(), lz0 = mesh.minZ();
                float lx1 = mesh.maxX(), ly1 = mesh.maxY(), lz1 = mesh.maxZ();

                float minX = m12 + (m0 >= 0 ? m0 * lx0 : m0 * lx1)
                        + (m4 >= 0 ? m4 * ly0 : m4 * ly1)
                        + (m8 >= 0 ? m8 * lz0 : m8 * lz1);
                float maxX = m12 + (m0 >= 0 ? m0 * lx1 : m0 * lx0)
                        + (m4 >= 0 ? m4 * ly1 : m4 * ly0)
                        + (m8 >= 0 ? m8 * lz1 : m8 * lz0);

                float minY = m13 + (m1 >= 0 ? m1 * lx0 : m1 * lx1)
                        + (m5 >= 0 ? m5 * ly0 : m5 * ly1)
                        + (m9 >= 0 ? m9 * lz0 : m9 * lz1);
                float maxY = m13 + (m1 >= 0 ? m1 * lx1 : m1 * lx0)
                        + (m5 >= 0 ? m5 * ly1 : m5 * ly0)
                        + (m9 >= 0 ? m9 * lz1 : m9 * lz0);

                float minZ = m14 + (m2 >= 0 ? m2 * lx0 : m2 * lx1)
                        + (m6 >= 0 ? m6 * ly0 : m6 * ly1)
                        + (m10 >= 0 ? m10 * lz0 : m10 * lz1);
                float maxZ = m14 + (m2 >= 0 ? m2 * lx1 : m2 * lx0)
                        + (m6 >= 0 ? m6 * ly1 : m6 * ly0)
                        + (m10 >= 0 ? m10 * lz1 : m10 * lz0);

                bounds.set(bb, minX);
                bounds.set(bb + 1, minY);
                bounds.set(bb + 2, minZ);
                bounds.set(bb + 3, maxX);
                bounds.set(bb + 4, maxY);
                bounds.set(bb + 5, maxZ);
            }
        }

        // トポロジカル順で親→子の dirty 伝播を完了させたあと FLAG_DIRTY を落とす
        for (int k = 0; k < count; k++) {
            int id = order[k];
            int idx = id * Scene3DArena.RENDER_STRIDE + Scene3DArena.RENDER_FLAGS;
            render.set(idx, render.get(idx) & ~Scene3DArena.FLAG_DIRTY);
        }
    }

    private long lastGeometryVersion = -1L;
}
