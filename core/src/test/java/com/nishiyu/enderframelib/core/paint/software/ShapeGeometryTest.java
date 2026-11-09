package com.nishiyu.enderframelib.core.paint.software;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.paint.BorderPath;
import com.nishiyu.enderframelib.core.paint.BoxDecorationPainter;
import com.nishiyu.enderframelib.core.paint.model.RoundedBorderSpec;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Phase A「試行の向きの反転」ステップ ①: 図形の幾何を {@link ShapeGeometry} の純粋関数へ分けた。 */
class ShapeGeometryTest {

    @Test
    void borderMatchesAnIndependentDerivation() {
        Random r = new Random(11);
        for (int n = 0; n < 300; n++) {
            float x = r.nextFloat() * 50, y = r.nextFloat() * 50, w = 5 + r.nextFloat() * 120, h = 5 + r.nextFloat() * 90;
            float[] ws = {r.nextFloat() * 8, r.nextFloat() * 8, r.nextFloat() * 8, r.nextFloat() * 8};
            float[] rx = new float[4], ry = new float[4];
            for (int i = 0; i < 4; i++) { rx[i] = r.nextFloat() * 20; ry[i] = r.nextFloat() * 20; }
            RoundedBorderSpec spec = new RoundedBorderSpec(ws[0], ws[1], ws[2], ws[3], r.nextInt(), r.nextInt(), r.nextInt(), r.nextInt());
            ShapeGeometry.Border b = ShapeGeometry.border(x, y, w, h, spec, rx, ry);
            assertEquals(x + ws[3], b.ix0());
            assertEquals(y + ws[0], b.iy0());
            assertEquals(x + w - ws[1], b.ix1());
            assertEquals(y + h - ws[2], b.iy1());
            assertEquals(b.ix1() <= b.ix0() || b.iy1() <= b.iy0(), b.innerEmpty());
            float[] sideH = {ws[3], ws[1], ws[1], ws[3]}, sideV = {ws[0], ws[0], ws[2], ws[2]};
            for (int i = 0; i < 4; i++) {
                float ex = Math.max(0f, rx[i] - sideH[i]), ey = Math.max(0f, ry[i] - sideV[i]);
                if (ex <= 0f || ey <= 0f) ex = ey = 0f;
                assertEquals(ex, b.irx()[i]);
                assertEquals(ey, b.iry()[i]);
                assertEquals(Pixmap.premultiply(spec.color(i)), b.colors()[i]);
            }
            assertArrayEquals(ws, b.widths());
            assertNull(b.dash(), "実線は破線を持たない");
        }
    }

    @Test
    void dashOnlyWhenAllSidesHaveTheSameThickness() {
        float[] rx = {6, 6, 6, 6}, ry = {6, 6, 6, 6};
        RoundedBorderSpec same = new RoundedBorderSpec(2, 2, 2, 2, 1, 2, 3, 4, BorderPath.DASHED);
        ShapeGeometry.Border d = ShapeGeometry.border(0, 0, 80, 40, same, rx, ry);
        assertNotNull(d.dash());
        assertEquals(80 / 2f - 1f, d.bx(), 1e-6f);
        assertEquals(40 / 2f - 1f, d.by(), 1e-6f);
        assertEquals(40f, d.cxm());
        assertEquals(20f, d.cym());
        assertEquals(BorderPath.pattern(BorderPath.DASHED, 2f, BorderPath.length(d.bx(), d.by(), d.pcx(), d.pcy())), d.dash());

        RoundedBorderSpec differ = new RoundedBorderSpec(2, 3, 2, 2, 1, 2, 3, 4, BorderPath.DASHED);
        assertNull(ShapeGeometry.border(0, 0, 80, 40, differ, rx, ry).dash(), "太さが違えば破線にしない");
        RoundedBorderSpec none = new RoundedBorderSpec(0, 0, 0, 0, 1, 2, 3, 4, BorderPath.DOTTED);
        assertNull(ShapeGeometry.border(0, 0, 80, 40, none, rx, ry).dash(), "太さ 0 は破線にしない");
    }

    @Test
    void outerShadowShrinksByReachAndClampsRadii() {
        float blur = 4f, reach = BoxDecorationPainter.SHADOW_REACH * blur;
        float[] rx = {50, 1, 2, 3}, ry = {50, 4, 5, 6};
        ShapeGeometry.OuterShadow s = ShapeGeometry.outerShadow(10, 20, 10 + 100, 20 + 60, blur, rx, ry);
        assertEquals(10 + reach, s.sx0());
        assertEquals(20 + reach, s.sy0());
        assertEquals(110 - reach, s.sx1());
        assertEquals(80 - reach, s.sy1());
        float limit = Math.min(s.sx1() - s.sx0(), s.sy1() - s.sy0()) / 2f;
        assertEquals(Math.min(50f, limit), s.srx()[0]);
        assertEquals(Math.min(50f, limit), s.sry()[0]);
        assertEquals(1f, s.srx()[1]);
        assertEquals(6f, s.sry()[3]);
    }

    @Test
    void outerShadowIsNullWhenTheBoxIsSmallerThanTheReach() {
        assertNull(ShapeGeometry.outerShadow(0, 0, 8, 8, 10f, new float[4], new float[4]));
    }

    @Test
    void geometryIsComputedOnlyInShapeGeometry() throws Exception {
        Path src = Path.of("src/main/java/com/nishiyu/enderframelib/core/paint/software/SoftwareRenderBackend.java");
        if (!Files.exists(src)) src = Path.of("core").resolve(src);
        String t = Files.readString(src);
        for (String bad : new String[]{"SHADOW_REACH * blur", "BorderPath.length(", "BorderPath.pattern(", "float[] sideH"}) {
            assertFalse(t.contains(bad), bad + " の幾何は ShapeGeometry へ移した");
        }
    }
}
