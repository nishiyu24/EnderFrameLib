package com.nishiyu.enderframelib.core.paint.software;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.scene3d.BlendMode3D;
import com.nishiyu.enderframelib.core.scene3d.LightDesc;
import com.nishiyu.enderframelib.core.scene3d.MaterialDesc;
import com.nishiyu.enderframelib.core.scene3d.MeshHandle;
import com.nishiyu.enderframelib.core.scene3d.Scene3DWorld;
import com.nishiyu.enderframelib.core.scene3d.Texture3D;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** X3-2: GPU なしの 3D 描画。幾何（前面・深度・クリップ・上下）と、並列でも結果が変わらないことを確かめる。 */
class SoftwareScene3DRasterizerTest {

    private static final int BG = 0xFF101820;

    private static Scene3DWorld world() {
        Scene3DWorld w = new Scene3DWorld();
        w.clearColorArgb(BG);
        w.camera().lookAt(0, 0, 5, 0, 0, 0, 0, 1, 0);
        w.camera().setPerspective((float) Math.toRadians(45), 1f, 0.1f, 50f);
        return w;
    }

    private static int node(Scene3DWorld w, MeshHandle mesh, MaterialDesc mat, float x, float y, float z) {
        int n = w.arena().allocateNode();
        w.arena().setPosition(n, x, y, z);
        w.arena().setMesh(n, mesh.meshId());
        w.arena().setMaterial(n, mat.materialId());
        return n;
    }

    private static MaterialDesc unlit(Scene3DWorld w, int argb) {
        return w.materials().register(argb, 0f, 0.5f, 0, 0f, null, false, true);
    }

    private static int rgb(int px) { return px & 0xFFFFFF; }

    @Test
    void emptyWorldIsTheClearColor() {
        Pixmap p = SoftwareScene3DRasterizer.render(world(), 32, 24);
        for (int px : p.pixels()) assertEquals(BG, px);
        assertEquals(32, p.width());
        assertEquals(24, p.height());
    }

    @Test
    void anObjectAbovePutsPixelsInTheTopRows() {
        Scene3DWorld w = world();
        MeshHandle box = w.geometry().createBox(1f, 1f, 1f);
        node(w, box, unlit(w, 0xFFFF0000), 0f, 1.2f, 0f);
        Pixmap p = SoftwareScene3DRasterizer.render(w, 64, 64);
        assertEquals(0xFF0000, rgb(p.get(32, 20)), "+Y は画面の上側");
        assertEquals(BG, p.get(32, 50), "下側は背景のまま");
    }

    @Test
    void theNearerObjectWinsRegardlessOfInsertionOrder() {
        for (boolean nearFirst : new boolean[]{true, false}) {
            Scene3DWorld w = world();
            MeshHandle box = w.geometry().createBox(2f, 2f, 0.2f);
            MaterialDesc red = unlit(w, 0xFFFF0000), blue = unlit(w, 0xFF0000FF);
            if (nearFirst) {
                node(w, box, red, 0, 0, 1f);
                node(w, box, blue, 0, 0, -1f);
            } else {
                node(w, box, blue, 0, 0, -1f);
                node(w, box, red, 0, 0, 1f);
            }
            assertEquals(0xFF0000, rgb(SoftwareScene3DRasterizer.render(w, 48, 48).get(24, 24)), "nearFirst=" + nearFirst);
        }
    }

    @Test
    void singleSidedPlaneIsCulledFromBehindAndDoubleSidedIsNot() {
        // createPlane は +Y 向き。真上から見れば見え、真下から見れば背面
        for (boolean doubleSided : new boolean[]{false, true}) {
            for (boolean fromBelow : new boolean[]{false, true}) {
                Scene3DWorld w = new Scene3DWorld();
                w.clearColorArgb(BG);
                w.camera().lookAt(0, fromBelow ? -4 : 4, 0.001f, 0, 0, 0, 0, 1, 0);
                w.camera().setPerspective((float) Math.toRadians(45), 1f, 0.1f, 50f);
                MeshHandle plane = w.geometry().createPlane(3f, 3f);
                MaterialDesc m = w.materials().register(0xFF00FF00, 0f, 0.5f, 0, 0f, null, doubleSided, true);
                node(w, plane, m, 0, 0, 0);
                int c = rgb(SoftwareScene3DRasterizer.render(w, 32, 32).get(16, 16));
                boolean visible = doubleSided || !fromBelow;
                assertEquals(visible ? 0x00FF00 : rgb(BG), c, "doubleSided=" + doubleSided + " fromBelow=" + fromBelow);
            }
        }
    }

    @Test
    void geometryCrossingTheNearPlaneIsClippedNotDropped() {
        Scene3DWorld w = world();
        MeshHandle big = w.geometry().createBox(40f, 40f, 40f); // 視点を内側に含む。近クリップ面をまたぐ
        MaterialDesc m = w.materials().register(0xFF336699, 0f, 0.5f, 0, 0f, null, true, true);
        node(w, big, m, 0, 0, 0);
        Pixmap p = SoftwareScene3DRasterizer.render(w, 32, 32);
        assertEquals(0x336699, rgb(p.get(16, 16)));
        assertEquals(0x336699, rgb(p.get(1, 1)));
    }

    @Test
    void transparentObjectsBlendOverWhatIsBehind() {
        Scene3DWorld w = world();
        MeshHandle slab = w.geometry().createBox(2f, 2f, 0.2f);
        node(w, slab, unlit(w, 0xFF0000FF), 0, 0, -1f);
        MaterialDesc glass = w.materials().register(0x80FF0000, 0f, 0.5f, 0, 0f, BlendMode3D.TRANSPARENT, false, true);
        node(w, slab, glass, 0, 0, 1f);
        int px = SoftwareScene3DRasterizer.render(w, 48, 48).get(24, 24);
        int r = (px >> 16) & 0xFF, b = px & 0xFF;
        assertTrue(r > 100 && r < 160, "半透明の赤が約半分: " + Integer.toHexString(px));
        assertTrue(b > 100 && b < 160, "後ろの青が約半分透けて見える: " + Integer.toHexString(px));
        assertEquals(255, px >>> 24);
    }

    @Test
    void maskMaterialDiscardsLowAlpha() {
        Scene3DWorld w = world();
        MeshHandle box = w.geometry().createBox(2f, 2f, 0.2f);
        MaterialDesc cut = w.materials().register(0x40FF0000, 0f, 0.5f, 0, 0f, BlendMode3D.MASK, false, true);
        node(w, box, cut, 0, 0, 0);
        assertEquals(BG, SoftwareScene3DRasterizer.render(w, 32, 32).get(16, 16));
    }

    @Test
    void litSurfaceFacingTheLightIsBrighterThanOneFacingAway() {
        Scene3DWorld w = world();
        w.light().setDirection(0, 0, -1); // 光は -Z へ進む = +Z 向きの面が明るい
        MeshHandle box = w.geometry().createBox(2f, 2f, 2f);
        MaterialDesc m = w.materials().register(0xFFCCCCCC, 0f, 0.8f, 0, 0f, null, false, false);
        node(w, box, m, 0, 0, 0);
        int lit = SoftwareScene3DRasterizer.render(w, 32, 32).get(16, 16) & 0xFF;
        w.light().setDirection(0, 0, 1);
        int dark = SoftwareScene3DRasterizer.render(w, 32, 32).get(16, 16) & 0xFF;
        assertTrue(lit > dark + 40, "lit=" + lit + " dark=" + dark);
    }

    @Test
    void largeSceneIsDeterministicAcrossTheParallelTiles() {
        Scene3DWorld w = world();
        MeshHandle cube = w.geometry().createBox(0.5f, 0.5f, 0.5f);
        MaterialDesc m = w.materials().register(0xFFE0A030, 0.2f, 0.4f, 0, 0f, null, false, false);
        for (int i = 0; i < 60; i++) {
            int n = node(w, cube, m, (i % 6 - 2.5f) * 0.8f, (i / 6 - 4.5f) * 0.5f, -(i % 5) * 0.4f);
            w.arena().setRotationEulerRadians(n, i * 0.3f, i * 0.2f, 0f);
        }
        Pixmap a = SoftwareScene3DRasterizer.render(w, 320, 240); // 5×4 = 20 タイル（並列）
        Pixmap b = SoftwareScene3DRasterizer.render(w, 320, 240);
        assertArrayEquals(a.pixels(), b.pixels());
        long covered = Arrays.stream(a.pixels()).filter(px -> px != BG).count();
        assertTrue(covered > 320 * 240 / 10, "立方体が描かれている: " + covered);
    }

    @Test
    void trianglesSpanningTileBordersLeaveNoSeams() {
        Scene3DWorld w = world();
        MeshHandle box = w.geometry().createBox(6f, 6f, 0.2f);
        node(w, box, unlit(w, 0xFF55AA55), 0, 0, 0);
        Pixmap p = SoftwareScene3DRasterizer.render(w, 256, 192);
        int holes = 0;
        for (int y = 40; y < 150; y++) for (int x = 40; x < 216; x++) if (rgb(p.get(x, y)) != 0x55AA55) holes++;
        assertEquals(0, holes, "面の内側に継ぎ目の穴がある");
    }
    // ------------------------------------------------------------------ PBR（GGX）の性質

    private static int channel(int px, int shift) { return (px >> shift) & 0xFF; }

    @Test
    void aRoughDielectricFacingTheLightReturnsRoughlyItsBaseColor() {
        // 基準: 光量 1 の光を真正面から受けた白い拡散面は基本色そのもの。誘電体は F0=0.04 なので 96% + わずかな鏡面
        Scene3DWorld w = world();
        w.light().setDirection(0, 0, -1);
        w.light().setColor(1f, 1f, 1f, 1f);
        w.light().setAmbient(0f, 0f, 0f);
        MeshHandle box = w.geometry().createBox(2f, 2f, 2f);
        MaterialDesc m = w.materials().register(0xFF808080, 0f, 1f, 0, 0f, null, false, false);
        node(w, box, m, 0, 0, 0);
        int px = SoftwareScene3DRasterizer.render(w, 32, 32).get(16, 16);
        int g = channel(px, 8);
        assertTrue(g >= 118 && g <= 132, "約 0x80 × 0.96: " + g);
    }

    @Test
    void smoothMetalHasASmallerBrighterHighlightThanRoughMetal() {
        int[] peak = new int[2], bright = new int[2];
        float[] roughness = {0.2f, 0.8f};
        for (int i = 0; i < 2; i++) {
            Scene3DWorld w = world();
            w.light().setDirection(0, 0, -1); // 光源 = カメラの向き: ハイライトは球の正面中央
            w.light().setColor(1f, 1f, 1f, 0.5f);
            w.light().setAmbient(0f, 0f, 0f);
            MeshHandle sphere = w.geometry().createSphere(1.2f, 32, 48);
            MaterialDesc m = w.materials().register(0xFFB0B0B0, 1f, roughness[i], 0, 0f, null, false, false);
            node(w, sphere, m, 0, 0, 0);
            Pixmap p = SoftwareScene3DRasterizer.render(w, 96, 96);
            for (int px : p.pixels()) {
                int v = channel(px, 8);
                if (px != BG) peak[i] = Math.max(peak[i], v);
                if (v > 40 && px != BG) bright[i]++;
            }
        }
        assertTrue(peak[0] >= peak[1], "滑らかな金属のほうが頂点が明るい: " + Arrays.toString(peak));
        assertTrue(bright[0] < bright[1], "滑らかなほうが明るい領域は狭い: " + Arrays.toString(bright));
    }

    @Test
    void metalTintsTheHighlightButADielectricDoesNot() {
        int[] highlight = new int[2];
        float[] metallic = {1f, 0f};
        for (int i = 0; i < 2; i++) {
            Scene3DWorld w = world();
            w.light().setDirection(0, 0, -1);
            w.light().setColor(1f, 1f, 1f, 0.005f); // 鏡面の頂点（D ≈ 150）が 8 bit でクリップしない光量
            w.light().setAmbient(0f, 0f, 0f);
            MeshHandle sphere = w.geometry().createSphere(1.2f, 32, 48);
            MaterialDesc m = w.materials().register(0xFFE0A020, metallic[i], 0.25f, 0, 0f, null, false, false);
            node(w, sphere, m, 0, 0, 0);
            highlight[i] = SoftwareScene3DRasterizer.render(w, 96, 96).get(48, 48);
        }
        // 金属の鏡面は基本色（橙）なので青が少ない。誘電体は鏡面が白に近く、その分だけ青の割合が高い
        double metalBlue = channel(highlight[0], 0) / (double) channel(highlight[0], 16);
        double dielectricBlue = channel(highlight[1], 0) / (double) channel(highlight[1], 16);
        assertTrue(metalBlue < 0.3, "金属は橙: " + Integer.toHexString(highlight[0]));
        assertTrue(dielectricBlue > metalBlue + 0.1, "誘電体の鏡面は白寄り: " + Integer.toHexString(highlight[1]));
    }

    @Test
    void generatedSphereTrianglesFaceOutward() {
        // 回帰: createSphere の巻き方向が逆で、背面カリングのもとで球の奥の内側が描かれていた
        Scene3DWorld w = world();
        MeshHandle sphere = w.geometry().createSphere(1f, 12, 16);
        java.nio.ByteBuffer vb = w.geometry().vertexBufferSlice(), ib = w.geometry().indexBufferSlice();
        int stride = com.nishiyu.enderframelib.core.scene3d.GeometryPool.VERTEX_STRIDE_BYTES;
        int bad = 0, tested = 0;
        for (int t = 0; t < sphere.indexCount() / 3; t++) {
            float[][] p = new float[3][3];
            for (int k = 0; k < 3; k++) {
                int v = ib.getInt((t * 3 + k) * 4);
                for (int c = 0; c < 3; c++) p[k][c] = vb.getFloat(v * stride + c * 4);
            }
            float ex = p[1][0] - p[0][0], ey = p[1][1] - p[0][1], ez = p[1][2] - p[0][2];
            float fx = p[2][0] - p[0][0], fy = p[2][1] - p[0][1], fz = p[2][2] - p[0][2];
            float nx = ey * fz - ez * fy, ny = ez * fx - ex * fz, nz = ex * fy - ey * fx;
            if (nx * nx + ny * ny + nz * nz < 1e-12f) continue; // 極の縮退三角形
            float cx = p[0][0] + p[1][0] + p[2][0], cy = p[0][1] + p[1][1] + p[2][1], cz = p[0][2] + p[1][2] + p[2][2];
            tested++;
            if (nx * cx + ny * cy + nz * cz <= 0f) bad++;
        }
        assertTrue(tested > 100);
        assertEquals(0, bad, "内向きの三角形");
        // 画面でも: 単面の unlit な球は手前側が描かれる
        node(w, sphere, unlit(w, 0xFFFF8800), 0, 0, 0);
        assertEquals(0xFF8800, rgb(SoftwareScene3DRasterizer.render(w, 48, 48).get(24, 24)));
    }

    // ------------------------------------------------------------------ 追加ライト（ポイント / スポット）

    /** 床（+Y 向きの板）を真上から見る。主ライトと環境光は 0 にして、追加ライトだけの寄与を見る。 */
    private static Scene3DWorld floorWorld() {
        Scene3DWorld w = world();
        w.camera().lookAt(0, 6, 0.01f, 0, 0, 0, 0, 1, 0);
        w.light().setColor(0f, 0f, 0f, 0f);
        w.light().setAmbient(0f, 0f, 0f);
        MeshHandle slab = w.geometry().createBox(8f, 0.1f, 8f);
        MaterialDesc m = w.materials().register(0xFFC8C8C8, 0f, 0.9f, 0, 0f, null, false, false);
        node(w, slab, m, 0, -0.05f, 0);
        return w;
    }

    private static int lum(Pixmap p, int x, int y) { return channel(p.get(x, y), 8); }

    @Test
    void aPointLightBrightensTheNearSideAndFallsOffWithDistance() {
        Scene3DWorld w = floorWorld();
        assertEquals(0, lum(SoftwareScene3DRasterizer.render(w, 64, 64), 32, 32) , "追加ライト無しなら床は暗い");
        assertTrue(w.light().addPoint(0, 1.5f, 0, 1f, 1f, 1f, 6f, 8f));
        Pixmap p = SoftwareScene3DRasterizer.render(w, 64, 64);
        int center = lum(p, 32, 32), edge = lum(p, 32 + 20, 32);
        assertTrue(center > 60, "真下は明るい: " + center);
        assertTrue(center > edge * 1.4, "離れるほど暗い: center=" + center + " edge=" + edge);
    }

    @Test
    void aPointLightBeyondItsRangeContributesNothing() {
        Scene3DWorld w = floorWorld();
        w.light().addPoint(0, 1.5f, 0, 1f, 1f, 1f, 50f, 1.0f); // 範囲 1 < 高さ 1.5
        assertEquals(0, lum(SoftwareScene3DRasterizer.render(w, 64, 64), 32, 32));
    }

    @Test
    void aSpotLightLightsOnlyInsideItsCone() {
        Scene3DWorld w = floorWorld();
        // 真下へ向けた細いスポット（半角 内 8°・外 14°）。高さ 3 → 外縁の半径 ≈ 0.75
        assertTrue(w.light().addSpot(0, 3f, 0, 0, -1, 0, 1f, 1f, 1f, 30f, 20f, 8f, 14f));
        Pixmap p = SoftwareScene3DRasterizer.render(w, 64, 64);
        assertTrue(lum(p, 32, 32) > 60, "円錐の中心は明るい: " + lum(p, 32, 32));
        assertEquals(0, lum(p, 32 + 24, 32), "円錐の外は真っ暗");
    }

    @Test
    void extraLightsAddUpAndStopAtTheCapacity() {
        LightDesc l = new LightDesc();
        for (int i = 0; i < LightDesc.MAX_EXTRA; i++) assertTrue(l.addPoint(i, 1, 0, 1, 1, 1, 1, 5));
        assertFalse(l.addPoint(0, 0, 0, 1, 1, 1, 1, 5), "満杯では足せない");
        assertEquals(LightDesc.MAX_EXTRA, l.extraCount());
        long before = l.changeCount();
        l.clearExtras();
        assertEquals(0, l.extraCount());
        assertTrue(l.changeCount() > before, "外すと見た目の変更として数える");
        l.clearExtras();
        assertEquals(before + 1, l.changeCount(), "空のまま外しても数えない");

        Scene3DWorld w = floorWorld();
        w.light().addPoint(-1.5f, 1.5f, 0, 1f, 1f, 1f, 4f, 8f);
        int one = lum(SoftwareScene3DRasterizer.render(w, 64, 64), 32, 32);
        w.light().addPoint(1.5f, 1.5f, 0, 1f, 1f, 1f, 4f, 8f);
        int two = lum(SoftwareScene3DRasterizer.render(w, 64, 64), 32, 32);
        assertTrue(two > one * 1.5, "2 灯は 1 灯より明るい: " + one + " → " + two);
    }

    @Test
    void extraLightsAreSeenByTheRedrawGate() {
        Scene3DWorld w = floorWorld();
        long before = w.changeCount();
        w.light().addPoint(0, 1, 0, 1, 1, 1, 1, 4);
        assertNotEquals(before, w.changeCount(), "ライトを足したら描き直しが要る");
    }

    // ------------------------------------------------------------------ ベースカラー画像

    /** 左半分が赤・右半分が青の 2×1 画像。 */
    private static Texture3D redBlue() {
        return new Texture3D(2, 1, new int[]{0xFFFF0000, 0xFF0000FF});
    }

    private static MaterialDesc textured(Scene3DWorld w, int base, Texture3D tex, boolean repeat, BlendMode3D mode) {
        return w.materials().register(base, 0f, 0.5f, 0, 0f, mode, false, true, tex, repeat);
    }

    @Test
    void aTexturedFaceShowsTheImageLeftToRight() {
        Scene3DWorld w = world();
        MeshHandle box = w.geometry().createBox(2f, 2f, 0.2f); // +Z 面: 左 (x<0) が u<0.5
        node(w, box, textured(w, 0xFFFFFFFF, redBlue(), false, null), 0, 0, 0);
        Pixmap p = SoftwareScene3DRasterizer.render(w, 64, 64);
        int left = rgb(p.get(24, 32)), right = rgb(p.get(40, 32));
        assertTrue(channel(left, 16) > 200 && channel(left, 0) < 60, "左は赤: " + Integer.toHexString(left));
        assertTrue(channel(right, 0) > 200 && channel(right, 16) < 60, "右は青: " + Integer.toHexString(right));
    }

    @Test
    void theBaseColorTintsTheImage() {
        Scene3DWorld w = world();
        MeshHandle box = w.geometry().createBox(2f, 2f, 0.2f);
        node(w, box, textured(w, 0xFF808080, redBlue(), false, null), 0, 0, 0); // 灰 × 赤 = 暗い赤
        int px = rgb(SoftwareScene3DRasterizer.render(w, 64, 64).get(24, 32));
        int r = channel(px, 16);
        assertTrue(r > 110 && r < 145, "0xFF x 0x80 は約 0x80: " + r);
        assertTrue(channel(px, 8) < 10 && channel(px, 0) < 10);
    }

    @Test
    void imageAlphaCutsAMaskMaterial() {
        Scene3DWorld w = world();
        MeshHandle box = w.geometry().createBox(2f, 2f, 0.2f);
        Texture3D cut = new Texture3D(2, 1, new int[]{0xFFFF0000, 0x00FF0000}); // 右半分は透明
        node(w, box, textured(w, 0xFFFFFFFF, cut, false, BlendMode3D.MASK), 0, 0, 0);
        Pixmap p = SoftwareScene3DRasterizer.render(w, 64, 64);
        assertEquals(0xFF0000, rgb(p.get(20, 32)), "左は残る");
        assertEquals(BG, p.get(44, 32), "右は捨てられて背景");
    }

    @Test
    void repeatDoesNotChangeAFaceWhoseUvStaysInsideTheImage() {
        Pixmap[] out = new Pixmap[2];
        for (int pass = 0; pass < 2; pass++) {
            Scene3DWorld w = world();
            MeshHandle box = w.geometry().createBox(2f, 2f, 0.2f);
            node(w, box, textured(w, 0xFFFFFFFF, redBlue(), pass == 1, null), 0, 0, 0);
            out[pass] = SoftwareScene3DRasterizer.render(w, 64, 64);
        }
        // UV は 0〜1 の内側なので fract(uv) = uv。繰り返しの有無で絵は変わらない（端の補間の差だけ許す）
        int far = 0;
        for (int i = 0; i < 64 * 64; i++) {
            int a = out[0].pixels()[i], b = out[1].pixels()[i];
            if (Math.abs(channel(a, 16) - channel(b, 16)) > 3 || Math.abs(channel(a, 0) - channel(b, 0)) > 3) far++;
        }
        assertEquals(0, far);
    }

    @Test
    void texturedAndPlainMaterialsCanBeMixedInOneScene() {
        Scene3DWorld w = world();
        MeshHandle box = w.geometry().createBox(1f, 1f, 0.2f);
        node(w, box, textured(w, 0xFFFFFFFF, redBlue(), false, null), -1.2f, 0, 0);
        node(w, box, unlit(w, 0xFF00FF00), 1.2f, 0, 0);
        Pixmap p = SoftwareScene3DRasterizer.render(w, 96, 64);
        int red = 0, blue = 0;
        for (int px : p.pixels()) {
            if (channel(px, 16) > 150 && channel(px, 0) < 80) red++;
            if (channel(px, 0) > 150 && channel(px, 16) < 80) blue++;
        }
        assertTrue(red > 30 && blue > 30, "画像つきは左が赤・右が青: red=" + red + " blue=" + blue);
        long green = Arrays.stream(p.pixels()).filter(px -> rgb(px) == 0x00FF00).count();
        assertTrue(green > 30, "画像なしは基本色のまま: " + green);
    }
}
