package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.core.paint.BlendMode;
import com.nishiyu.enderframelib.core.paint.PaintSceneDriver.Group;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** CPU ラスタの描画先状態（根・層スタック・汚れ範囲・層の合成）の単体検査。 */
class CpuRasterTest {

    private static final int W = 41, H = 23;

    private static Group group(String id, float opacity, String maskOf) {
        return new Group(id, opacity, 0, null, null, maskOf, Matrix2D.IDENTITY);
    }

    @Test
    void rootIsLazyAndTargetFollowsIt() {
        CpuRaster r = new CpuRaster(W, H);
        assertFalse(r.isRootAllocated());
        assertEquals(W, r.targetWidth());
        assertEquals(H, r.targetHeight());
        assertFalse(r.isRootAllocated(), "大きさを聞くだけでは確保しない");
        Pixmap t = r.target();
        assertTrue(r.isRootAllocated());
        assertSame(r.root(), t);
    }

    @Test
    void cpuLayerSwitchesTargetAndRestoresDirtyOnClose() {
        CpuRaster r = new CpuRaster(W, H);
        r.beginFrame();
        r.dirty().add(1, 2, 5, 6);
        r.pushCpuLayer(group("g", 1f, null));
        assertEquals(1, r.depth());
        assertTrue(r.hasCpuLayer());
        assertNotSame(r.root(), r.target());
        assertNull(r.dirtyBounds(), "層の中は層のぶんだけを追う");
        r.dirty().add(10, 10, 12, 12);
        CpuRaster.Layer l = r.popLayer();
        r.closeCpuLayer(l, null, r.hasCpuLayer());
        assertSame(r.root(), r.target());
        assertArrayEquals(new int[]{1, 2, 12, 12}, r.dirtyBounds(), "親の範囲に層の範囲が足される");
    }

    @Test
    void compositeEqualsPerPixelReferenceAndClearsTheLayer() {
        Random rnd = new Random(11);
        for (int round = 0; round < 20; round++) {
            CpuRaster r = new CpuRaster(W, H);
            r.beginFrame();
            int[] base = new int[W * H];
            for (int i = 0; i < base.length; i++) base[i] = rnd.nextInt(3) == 0 ? 0 : premul(rnd.nextInt());
            System.arraycopy(base, 0, r.root().pixels(), 0, base.length);
            float opacity = rnd.nextFloat();
            r.pushCpuLayer(group("g", opacity, null));
            int[] lay = r.target().pixels();
            int x0 = rnd.nextInt(W / 2), y0 = rnd.nextInt(H / 2), x1 = x0 + 1 + rnd.nextInt(W / 2), y1 = y0 + 1 + rnd.nextInt(H / 2);
            int[] src = new int[W * H];
            for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) {
                int c = rnd.nextInt(4) == 0 ? 0 : premul(rnd.nextInt());
                lay[y * W + x] = c;
                src[y * W + x] = c;
            }
            r.dirty().add(x0, y0, x1, y1);
            CpuRaster.Layer l = r.popLayer();
            r.closeCpuLayer(l, null, false);

            int[] got = r.root().pixels();
            for (int i = 0; i < got.length; i++) {
                int c = src[i];
                int want = base[i];
                if (c != 0) want = SoftShapes.over(opacity < 1f ? SoftShapes.scalePremul(c, opacity) : c, base[i]);
                assertEquals(want, got[i], "round " + round + " px " + i);
            }
            // 層に使った Pixmap は 0 に戻って次の層へ貸される
            r.pushCpuLayer(group("h", 1f, null));
            for (int v : r.target().pixels()) assertEquals(0, v);
        }
    }

    @Test
    void emptyLayerDoesNotTouchTheParent() {
        CpuRaster r = new CpuRaster(W, H);
        r.beginFrame();
        java.util.Arrays.fill(r.root().pixels(), 0x80402010);
        r.pushCpuLayer(group("g", 0.5f, null));
        CpuRaster.Layer l = r.popLayer();
        r.closeCpuLayer(l, null, false);
        for (int v : r.root().pixels()) assertEquals(0x80402010, v);
        assertNull(r.dirtyBounds());
    }

    @Test
    void maskLayerIsHandedToItsOwnerOnlyWhenDrawn() {
        CpuRaster r = new CpuRaster(W, H);
        r.beginFrame();
        r.pushCpuLayer(group("owner", 1f, null));
        r.pushCpuLayer(group("m", 1f, "owner"));
        r.target().pixels()[3] = 0xFF000000;
        r.dirty().add(3, 0, 4, 1);
        CpuRaster.Layer m = r.popLayer();
        r.closeCpuLayer(m, null, true);
        CpuRaster.Layer owner = r.top();
        assertNotNull(owner.maskPixmap, "描かれたマスクは中身の層に預ける");
        assertArrayEquals(new int[]{3, 0, 4, 1}, owner.maskRegion);

        // 描かれなかったマスクは預けない
        CpuRaster r2 = new CpuRaster(W, H);
        r2.beginFrame();
        r2.pushCpuLayer(group("owner", 1f, null));
        r2.pushCpuLayer(group("m", 1f, "owner"));
        CpuRaster.Layer m2 = r2.popLayer();
        r2.closeCpuLayer(m2, null, true);
        assertNull(r2.top().maskPixmap);
    }

    @Test
    void gpuLayersHoldNoPixelsAndDoNotCountAsCpuLayers() {
        CpuRaster r = new CpuRaster(W, H);
        r.beginFrame();
        r.pushGpuLayer(group("g", 1f, null));
        assertEquals(1, r.depth());
        assertFalse(r.hasCpuLayer());
        assertNull(r.top().pixmap);
        assertFalse(r.isRootAllocated(), "GPU の層は CPU 画素を確保させない");
    }

    @Test
    void endFrameReleasesOpenLayersAndResetsTarget() {
        CpuRaster r = new CpuRaster(W, H);
        r.beginFrame();
        r.pushCpuLayer(group("a", 1f, null));
        r.pushCpuLayer(group("b", 1f, null));
        r.target().pixels()[0] = 123;
        r.endFrame();
        assertEquals(0, r.depth());
        assertSame(r.root(), r.target());
    }

    @Test
    void clearDirtyZeroesOnlyTheDirtyRows() {
        CpuRaster r = new CpuRaster(W, H);
        java.util.Arrays.fill(r.root().pixels(), 7);
        r.dirty().add(2, 3, 6, 5);
        r.clearDirty();
        assertNull(r.dirtyBounds());
        int[] px = r.root().pixels();
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            boolean inside = x >= 2 && x < 6 && y >= 3 && y < 5;
            assertEquals(inside ? 0 : 7, px[y * W + x]);
        }
    }

    @Test
    void blendPixelHasTheExpectedIdentities() {
        int dst = premul(0xFF336699);
        assertEquals(dst, CpuRaster.blendPixel(BlendMode.MULTIPLY, 0, dst), "src が透明なら dst のまま");
        int white = 0xFFFFFFFF;
        assertEquals(dst, CpuRaster.blendPixel(BlendMode.MULTIPLY, white, dst), "不透明な白との乗算は dst のまま");
        assertEquals(0xFFFFFFFF, CpuRaster.blendPixel(BlendMode.SCREEN, white, dst));
    }

    @Test
    void rasterStateStaysOutOfTheBackend() throws Exception {
        Path src = Path.of("src/main/java/com/nishiyu/enderframelib/core/paint/software/SoftwareRenderBackend.java");
        if (!Files.exists(src)) src = Path.of("core").resolve(src);
        String t = Files.readString(src);
        for (String bad : new String[]{"new Pixmap(", "new PixmapPool(", "new DirtyRect(", "class Layer", "LayerFx.", "blendPixel(",
                "ArrayList<Layer>", "private Pixmap target", "private Pixmap rootPixmap"}) {
            assertFalse(t.contains(bad), bad + " は CpuRaster へ移した");
        }
    }

    private static int premul(int argb) {
        return Pixmap.premultiply(argb);
    }
}
