package com.nishiyu.enderframelib.core.scene3d;

import com.nishiyu.enderframelib.core.kernel.gpu.PersistentVramBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Scene3DCoreTest {

    @Test
    void arenaColumnsRemain64ByteAlignedAcrossGrowthAndFreeSubtrees() {
        Scene3DArena arena = new Scene3DArena(16);
        assertTrue(arena.localTrs().isAligned());
        assertTrue(arena.worldMat().isAligned());
        assertTrue(arena.bounds().isAligned());
        assertTrue(arena.topology().isAligned());
        assertTrue(arena.renderRef().isAligned());

        int root = arena.allocateNode();
        int c1 = arena.allocateNode(root);
        int g1 = arena.allocateNode(c1);
        int c2 = arena.allocateNode(root);
        assertEquals(4, arena.aliveCount());

        // 16 スロットを超えて拡張しても 64B アラインメントと既存トポロジが保たれること
        for (int i = 0; i < 40; i++) {
            arena.allocateNode();
        }
        assertTrue(arena.capacity() >= 44);
        assertTrue(arena.localTrs().isAligned());
        assertTrue(arena.worldMat().isAligned());
        assertTrue(arena.bounds().isAligned());
        assertTrue(arena.topology().isAligned());
        assertTrue(arena.renderRef().isAligned());

        // c1 サブツリー (c1, g1) を解放すると c1 と g1 の両方が再利用キューに戻る
        arena.freeNode(c1);
        assertFalse(arena.isAlive(c1));
        assertFalse(arena.isAlive(g1));
        assertTrue(arena.isAlive(root));
        assertTrue(arena.isAlive(c2));
        assertEquals(42, arena.aliveCount());

        int reused1 = arena.allocateNode();
        int reused2 = arena.allocateNode();
        assertTrue(reused1 == c1 || reused1 == g1);
        assertTrue(reused2 == c1 || reused2 == g1);
        assertNotEquals(reused1, reused2);
    }

    @Test
    void transformSystemComputesHierarchyAndWorldAabb() {
        Scene3DArena arena = new Scene3DArena();
        GeometryPool geometry = new GeometryPool();
        MeshHandle box = geometry.createBox(2.0f, 4.0f, 6.0f);

        int parent = arena.allocateNode();
        arena.setPosition(parent, 10.0f, 0.0f, 0.0f);
        arena.setRotationEulerRadians(parent, 0.0f, 0.0f, (float) (Math.PI * 0.5));

        int child = arena.allocateNode(parent);
        arena.setPosition(child, 2.0f, 0.0f, 0.0f);
        arena.setMesh(child, box.meshId());

        Transform3DSystem sys = new Transform3DSystem();
        sys.update(arena, geometry);

        // トポロジカル順で親が必ず子より先
        int[] order = arena.topologicalOrder();
        assertEquals(2, arena.topologicalCount());
        assertEquals(parent, order[0]);
        assertEquals(child, order[1]);

        // 親: (10, 0, 0) + 90 deg Z 回転 → 子ローカル (2, 0, 0) はワールド (10, 2, 0)
        int cw = child * Scene3DArena.MAT_STRIDE;
        assertEquals(10.0f, arena.worldMat().get(cw + 12), 1e-4f);
        assertEquals(2.0f, arena.worldMat().get(cw + 13), 1e-4f);
        assertEquals(0.0f, arena.worldMat().get(cw + 14), 1e-4f);

        // ローカル AABB [-1..1, -2..2, -3..3] が 90 deg Z 回転してワールド X=[-2..2], Y=[-1..1], Z=[-3..3] + offset(10, 2, 0)
        int cb = child * Scene3DArena.BOUNDS_STRIDE;
        assertEquals(8.0f, arena.bounds().get(cb), 1e-4f);
        assertEquals(1.0f, arena.bounds().get(cb + 1), 1e-4f);
        assertEquals(-3.0f, arena.bounds().get(cb + 2), 1e-4f);
        assertEquals(12.0f, arena.bounds().get(cb + 3), 1e-4f);
        assertEquals(3.0f, arena.bounds().get(cb + 4), 1e-4f);
        assertEquals(3.0f, arena.bounds().get(cb + 5), 1e-4f);
    }

    @Test
    void batcherPacks128BytesAndCullsAndBatchesOverThousandInstances() {
        Scene3DWorld world = new Scene3DWorld(42L);
        MeshHandle cube = world.geometry().createCube(1.0f);
        MaterialDesc matFirst = world.materials().register(
                0xFF336699, 0.5f, 0.25f, 0x00112233, 2.0f, BlendMode3D.OPAQUE, false, false);
        MaterialDesc matOpaque = world.materials().register(
                0xFF4477AA, 0.2f, 0.5f, 0, 0.0f, BlendMode3D.OPAQUE, false, false);
        MaterialDesc matTransparent = world.materials().register(
                0x80FF0000, 0.0f, 0.8f, 0, 0.0f, BlendMode3D.TRANSPARENT, true, true);

        world.camera().lookAt(0, 0, 10, 0, 0, 0, 0, 1, 0);
        world.camera().setPerspective((float) Math.toRadians(60.0), 1.0f, 0.5f, 100.0f);

        // 1,200 個の可視 OPAQUE ノード（非一様スケール・最小 materialId で先頭スロットに確定配置、残りと同一 DrawBatch3D に統合）
        int first = world.arena().allocateNode();
        world.arena().setPosition(first, 0.0f, 0.0f, 0.0f);
        world.arena().setScale(first, 2.0f, 1.0f, 0.5f);
        world.arena().setMesh(first, cube.meshId());
        world.arena().setMaterial(first, matFirst.materialId());
        world.arena().setEntityTag(first, 777);

        for (int i = 1; i < 1200; i++) {
            int n = world.arena().allocateNode();
            world.arena().setPosition(n, ((i % 20) - 10) * 0.2f, ((i / 20) % 20 - 10) * 0.2f, 0.0f);
            world.arena().setMesh(n, cube.meshId());
            world.arena().setMaterial(n, matOpaque.materialId());
        }

        // 透明ノード 1 個（ブレンド順で最後になるはず）
        int trans = world.arena().allocateNode();
        world.arena().setPosition(trans, 0.0f, 0.0f, 1.0f);
        world.arena().setMesh(trans, cube.meshId());
        world.arena().setMaterial(trans, matTransparent.materialId());

        // カメラ後方 (z = +50) のノードはフラスタムカリングされる
        int behind = world.arena().allocateNode();
        world.arena().setPosition(behind, 0.0f, 0.0f, 50.0f);
        world.arena().setMesh(behind, cube.meshId());
        world.arena().setMaterial(behind, matOpaque.materialId());

        Scene3DUploadPlan plan = world.updateAndPrepare(800, 800);
        assertEquals(1202, plan.totalRenderableCount());
        assertEquals(1, plan.culledCount());
        assertEquals(1201, plan.instanceCount());
        assertEquals(2, plan.batchCount());

        DrawBatch3D b0 = plan.batches().get(0);
        assertEquals(BlendMode3D.OPAQUE, b0.blendMode());
        assertEquals(0, b0.baseInstance());
        assertEquals(1200, b0.instanceCount());

        DrawBatch3D b1 = plan.batches().get(1);
        assertEquals(BlendMode3D.TRANSPARENT, b1.blendMode());
        assertEquals(1200, b1.baseInstance());
        assertEquals(1, b1.instanceCount());
        assertTrue(b1.doubleSided());

        // CPU シャドウ側 PersistentVramBuffer に 128B/インスタンスでパックして中身を検証
        PersistentVramBuffer vram = new PersistentVramBuffer(InstancedBatcher.INSTANCE_STRIDE_BYTES, 1300, (PersistentVramBuffer.Device) null);
        int written = world.packInstances(vram);
        assertEquals(1201, written);

        // 先頭スロット（first ノード: 非一様スケール 2, 1, 0.5）のモデル・法線行列（逆転置 0.5, 1, 2）を確認
        assertEquals(2.0f, vram.getFloat(0, 0), 1e-5f);
        assertEquals(1.0f, vram.getFloat(0, 20), 1e-5f);
        assertEquals(0.5f, vram.getFloat(0, 40), 1e-5f);
        assertEquals(0.5f, vram.getFloat(0, 64), 1e-5f);
        assertEquals(1.0f, vram.getFloat(0, 80), 1e-5f);
        assertEquals(2.0f, vram.getFloat(0, 96), 1e-5f);
        assertEquals(matFirst.materialId(), vram.getInt(0, 100));
        assertEquals(777, vram.getInt(0, 104));
        assertEquals(0xFF336699, vram.getInt(0, 112));
        assertEquals(0, vram.getInt(0, 124));

        // 最後のスロット（trans ノード）の UNLIT | DOUBLE_SIDED フラグを確認
        assertEquals(matTransparent.materialId(), vram.getInt(1200, 100));
        assertEquals(InstancedBatcher.FLAG_DOUBLE_SIDED | InstancedBatcher.FLAG_UNLIT, vram.getInt(1200, 108));
    }
}
