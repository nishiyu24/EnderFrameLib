package com.nishiyu.enderframelib.core.scene3d;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** X3-3: レイキャスト（BVH で絞り、三角形で厳密に判定）。BVH の結果は全ノードを 1 つずつ試した結果と一致しなければならない。 */
class Raycaster3DTest {
    private static int addCube(Scene3DWorld w, MeshHandle cube, float x, float y, float z, int tag) {
        Scene3DArena a = w.arena();
        int n = a.allocateNode();
        a.setMesh(n, cube.meshId());
        a.setPosition(n, x, y, z);
        a.setEntityTag(n, tag);
        return n;
    }

    @Test
    void hitsTheNearestCubeAndReportsPointAndTag() {
        Scene3DWorld w = new Scene3DWorld();
        MeshHandle cube = w.geometry().createCube(2f); // ±1
        addCube(w, cube, 0, 0, 0, 7);
        addCube(w, cube, 0, 0, -5, 9);
        Raycaster3D rc = new Raycaster3D();
        Raycaster3D.Hit h = new Raycaster3D.Hit();
        assertTrue(rc.cast(w, 0, 0, 10, 0, 0, -1, Float.POSITIVE_INFINITY, h));
        assertEquals(7, h.entityTag);
        assertEquals(9f, h.t, 1e-4f, "手前の立方体の +Z 面（z=1）まで 9");
        assertEquals(1f, h.z, 1e-4f);
        assertFalse(rc.cast(w, 3f, 0, 10, 0, 0, -1, Float.POSITIVE_INFINITY, h), "横に外れる");
        assertFalse(h.hit());
        assertFalse(rc.cast(w, 0, 0, 10, 0, 0, -1, 5f, h), "tMax より遠い");
    }

    @Test
    void honoursRotationScaleAndTranslation() {
        Scene3DWorld w = new Scene3DWorld();
        MeshHandle cube = w.geometry().createCube(1f); // ±0.5
        int n = addCube(w, cube, 10, 0, 0, 1);
        w.arena().setScale(n, 4f, 2f, 2f); // x 方向に ±2
        Raycaster3D rc = new Raycaster3D();
        Raycaster3D.Hit h = new Raycaster3D.Hit();
        assertTrue(rc.cast(w, 0, 0, 0, 1, 0, 0, 100f, h));
        assertEquals(8f, h.t, 1e-4f, "x=10-2 の面");
        // z 軸まわりに 90° 回すと、x の幅 ±2 が y の幅になり、x 方向の幅は ±1 になる
        w.arena().setRotationEulerRadians(n, 0, 0, (float) (Math.PI / 2));
        assertTrue(rc.cast(w, 0, 0, 0, 1, 0, 0, 100f, h));
        assertEquals(9f, h.t, 1e-3f);
        assertTrue(rc.cast(w, 10, 1.5f, 5, 0, 0, -1, 100f, h), "y 方向に伸びた分には当たる");
        assertFalse(rc.cast(w, 10, 3f, 5, 0, 0, -1, 100f, h));
    }

    @Test
    void invisibleNodesAreSkipped() {
        Scene3DWorld w = new Scene3DWorld();
        MeshHandle cube = w.geometry().createCube(2f);
        int front = addCube(w, cube, 0, 0, 0, 1);
        addCube(w, cube, 0, 0, -5, 2);
        Raycaster3D rc = new Raycaster3D();
        Raycaster3D.Hit h = new Raycaster3D.Hit();
        w.arena().setVisible(front, false);
        assertTrue(rc.cast(w, 0, 0, 10, 0, 0, -1, 100f, h));
        assertEquals(2, h.entityTag);
    }

    @Test
    void pickScreenUnprojectsThroughTheCamera() {
        Scene3DWorld w = new Scene3DWorld();
        addCube(w, w.geometry().createCube(2f), 0, 0, 0, 5);
        w.camera().lookAt(0, 0, 8, 0, 0, 0, 0, 1, 0);
        w.camera().setPerspective((float) Math.toRadians(60), 800f / 600f, 0.1f, 100f);
        Raycaster3D rc = new Raycaster3D();
        Raycaster3D.Hit h = new Raycaster3D.Hit();
        assertTrue(rc.pickScreen(w, 400, 300, 800, 600, h), "画面の中心");
        assertEquals(5, h.entityTag);
        assertEquals(6.9f, h.t, 1e-2f); // レイの始点は近クリップ面（z=7.9）なので、面 z=1 まで 6.9
        assertEquals(1f, h.z, 1e-3f);
        assertFalse(rc.pickScreen(w, 5, 5, 800, 600, h), "隅は何も無い");
    }

    @Test
    void bvhMatchesTestingEveryNodeAlone() {
        Random r = new Random(42);
        Scene3DWorld w = new Scene3DWorld();
        MeshHandle cube = w.geometry().createCube(1f);
        int count = 80;
        int[] nodes = new int[count];
        for (int i = 0; i < count; i++) {
            nodes[i] = addCube(w, cube, r.nextFloat() * 20 - 10, r.nextFloat() * 20 - 10, r.nextFloat() * 20 - 10, i);
            w.arena().setScale(nodes[i], 0.5f + r.nextFloat() * 2, 0.5f + r.nextFloat() * 2, 0.5f + r.nextFloat() * 2);
            w.arena().setRotationEulerRadians(nodes[i], r.nextFloat() * 6, r.nextFloat() * 6, r.nextFloat() * 6);
        }
        Raycaster3D rc = new Raycaster3D();
        Raycaster3D.Hit h = new Raycaster3D.Hit(), one = new Raycaster3D.Hit();
        int hits = 0;
        for (int ray = 0; ray < 150; ray++) {
            float ox = r.nextFloat() * 30 - 15, oy = r.nextFloat() * 30 - 15, oz = r.nextFloat() * 30 - 15;
            float dx = r.nextFloat() - 0.5f, dy = r.nextFloat() - 0.5f, dz = r.nextFloat() - 0.5f;
            float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            dx /= len; dy /= len; dz /= len;
            boolean got = rc.cast(w, ox, oy, oz, dx, dy, dz, Float.POSITIVE_INFINITY, h);
            float bestT = Float.POSITIVE_INFINITY;
            int bestNode = -1;
            for (int i = 0; i < count; i++) {
                for (int k = 0; k < count; k++) w.arena().setVisible(nodes[k], k == i);
                if (rc.cast(w, ox, oy, oz, dx, dy, dz, Float.POSITIVE_INFINITY, one) && one.t < bestT) {
                    bestT = one.t;
                    bestNode = one.node;
                }
            }
            for (int k = 0; k < count; k++) w.arena().setVisible(nodes[k], true);
            assertEquals(bestNode >= 0, got, "ray " + ray);
            if (got) {
                hits++;
                assertEquals(bestNode, h.node, "ray " + ray);
                assertEquals(bestT, h.t, 1e-3f);
            }
        }
        assertTrue(hits > 10, "試験が当たりを含む: " + hits);
    }

    @Test
    void bvhIsRebuiltOnlyWhenTransformsChange() {
        Scene3DWorld w = new Scene3DWorld();
        MeshHandle cube = w.geometry().createCube(1f);
        int n = addCube(w, cube, 0, 0, 0, 1);
        Raycaster3D rc = new Raycaster3D();
        Raycaster3D.Hit h = new Raycaster3D.Hit();
        rc.cast(w, 0, 0, 5, 0, 0, -1, 100f, h);
        int before = rc.rebuilds();
        for (int i = 0; i < 20; i++) rc.cast(w, 0, 0, 5, 0, 0, -1, 100f, h);
        assertEquals(before, rc.rebuilds(), "動かさない間は作り直さない");
        w.arena().setPosition(n, 5, 0, 0);
        assertFalse(rc.cast(w, 0, 0, 5, 0, 0, -1, 100f, h), "動いたので外れる");
        assertTrue(rc.rebuilds() > before);
        assertTrue(rc.cast(w, 5, 0, 5, 0, 0, -1, 100f, h));
    }

    @Test
    @Tag("timing") // 実時間で合否を決める（共有ランナーの CI では外す）
    void tenThousandMeshesRaycastUnderTheBudget() {
        Random r = new Random(7);
        Scene3DWorld w = new Scene3DWorld();
        MeshHandle cube = w.geometry().createCube(1f);
        for (int i = 0; i < 10_000; i++) addCube(w, cube, r.nextFloat() * 400 - 200, r.nextFloat() * 400 - 200, r.nextFloat() * 400 - 200, i);
        Raycaster3D rc = new Raycaster3D();
        Raycaster3D.Hit h = new Raycaster3D.Hit();
        int rays = 2000;
        float[][] dirs = new float[rays][6];
        for (float[] d : dirs) {
            d[0] = r.nextFloat() * 400 - 200; d[1] = r.nextFloat() * 400 - 200; d[2] = r.nextFloat() * 400 - 200;
            float dx = r.nextFloat() - 0.5f, dy = r.nextFloat() - 0.5f, dz = r.nextFloat() - 0.5f;
            float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            d[3] = dx / len; d[4] = dy / len; d[5] = dz / len;
        }
        for (int rep = 0; rep < 3; rep++) for (float[] d : dirs) rc.cast(w, d[0], d[1], d[2], d[3], d[4], d[5], 1000f, h); // 温め
        long t0 = System.nanoTime();
        for (float[] d : dirs) rc.cast(w, d[0], d[1], d[2], d[3], d[4], d[5], 1000f, h);
        double ms = (System.nanoTime() - t0) / 1e6 / rays;
        System.out.printf("RAYCAST 10000 meshes: %.4f ms/ray%n", ms);
        assertTrue(ms < 0.1, "1 本あたり " + ms + " ms");
    }
}
