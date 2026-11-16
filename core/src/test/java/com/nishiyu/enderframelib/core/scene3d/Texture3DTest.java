package com.nishiyu.enderframelib.core.scene3d;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** X3-2: ベースカラー画像のサンプリング（GPU の線形補間と同じ規則）と、画像ごとのバッチ分割。 */
class Texture3DTest {

    /** 2×2: 左上 赤・右上 緑・左下 青・右下 白（α 255）。 */
    private static Texture3D quad() {
        return new Texture3D(2, 2, new int[]{0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFFFF});
    }

    private static float[] at(Texture3D t, float u, float v, boolean repeat) {
        float[] out = new float[4];
        t.sample(u, v, repeat, out);
        return out;
    }

    @Test
    void texelCentersReturnExactlyThatTexel() {
        Texture3D t = quad();
        assertArrayEquals(new float[]{1, 0, 0, 1}, at(t, 0.25f, 0.25f, false), 1e-5f);
        assertArrayEquals(new float[]{0, 1, 0, 1}, at(t, 0.75f, 0.25f, false), 1e-5f);
        assertArrayEquals(new float[]{0, 0, 1, 1}, at(t, 0.25f, 0.75f, false), 1e-5f);
    }

    @Test
    void theCenterOfTheImageAveragesAllFourTexels() {
        float[] c = at(quad(), 0.5f, 0.5f, false);
        assertEquals(0.5f, c[0], 1e-5f);
        assertEquals(0.5f, c[1], 1e-5f);
        assertEquals(0.5f, c[2], 1e-5f);
    }

    @Test
    void outsideTheImageClampsToTheEdgeWithoutRepeat() {
        Texture3D t = quad();
        assertArrayEquals(at(t, 0.25f, 0.25f, false), at(t, -3f, -3f, false), 1e-5f);
        assertArrayEquals(at(t, 0.75f, 0.75f, false), at(t, 9f, 9f, false), 1e-5f);
    }

    @Test
    void repeatTilesTheImage() {
        Texture3D t = quad();
        assertArrayEquals(at(t, 0.3f, 0.6f, true), at(t, 2.3f, -0.4f, true), 1e-4f);
        assertArrayEquals(at(t, 0.3f, 0.6f, false), at(t, 0.3f, 0.6f, true), 1e-6f);
    }

    @Test
    void alphaIsInterpolatedToo() {
        Texture3D t = new Texture3D(2, 1, new int[]{0xFF000000, 0x00000000});
        assertEquals(0.5f, at(t, 0.5f, 0.5f, false)[3], 1e-5f);
    }

    @Test
    void invalidSizesAreRejectedAndPixelsAreCopied() {
        assertThrows(IllegalArgumentException.class, () -> new Texture3D(0, 1, new int[1]));
        assertThrows(IllegalArgumentException.class, () -> new Texture3D(2, 2, new int[3]));
        int[] px = {0xFFFF0000};
        Texture3D t = new Texture3D(1, 1, px);
        px[0] = 0;
        assertEquals(0xFFFF0000, t.argb(0, 0), "呼び出し側の配列を後から変えても影響しない");
    }

    @Test
    void materialsWithDifferentImagesNeverShareADrawBatch() {
        Scene3DWorld w = new Scene3DWorld();
        w.camera().lookAt(0, 0, 5, 0, 0, 0, 0, 1, 0);
        w.camera().setPerspective((float) Math.toRadians(45), 1f, 0.1f, 50f);
        MeshHandle box = w.geometry().createBox(1, 1, 1);
        Texture3D a = quad(), b = quad();
        MaterialDesc ma = w.materials().register(0xFFFFFFFF, 0, 0.5f, 0, 0, null, false, false, a, false);
        MaterialDesc mb = w.materials().register(0xFFFFFFFF, 0, 0.5f, 0, 0, null, false, false, b, false);
        for (MaterialDesc m : new MaterialDesc[]{ma, mb}) {
            int n = w.arena().allocateNode();
            w.arena().setMesh(n, box.meshId());
            w.arena().setMaterial(n, m.materialId());
        }
        Scene3DUploadPlan plan = w.updateAndPrepare(64, 64);
        Set<Texture3D> seen = new HashSet<>();
        for (DrawBatch3D batch : plan.batches()) {
            assertNotNull(batch.texture());
            seen.add(batch.texture());
        }
        assertEquals(2, seen.size(), "同じ形・同じ材質設定でも、画像が違えば別のドロー");
        assertEquals(2, plan.batches().size());
    }

    @Test
    void anUntexturedMaterialHasNoImageInItsBatch() {
        Scene3DWorld w = new Scene3DWorld();
        w.camera().lookAt(0, 0, 5, 0, 0, 0, 0, 1, 0);
        w.camera().setPerspective((float) Math.toRadians(45), 1f, 0.1f, 50f);
        MeshHandle box = w.geometry().createBox(1, 1, 1);
        int n = w.arena().allocateNode();
        w.arena().setMesh(n, box.meshId());
        w.arena().setMaterial(n, w.materials().registerColor(0xFF336699).materialId());
        Scene3DUploadPlan plan = w.updateAndPrepare(64, 64);
        assertEquals(1, plan.batches().size());
        assertNull(plan.batches().get(0).texture());
    }
}
