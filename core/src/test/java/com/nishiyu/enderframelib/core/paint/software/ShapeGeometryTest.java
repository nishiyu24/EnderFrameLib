package com.nishiyu.enderframelib.core.paint.software;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.SoftwareRendering;
import com.nishiyu.enderframelib.core.paint.AnimatedValues;
import com.nishiyu.enderframelib.core.paint.BorderPath;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import com.nishiyu.enderframelib.core.text.font.GlyphBitmap;
import com.nishiyu.enderframelib.core.text.font.LoadedFace;
import java.util.List;
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

    // ---- 字形の置き方（旧バックエンドの式をそのまま参照実装にする）

    @Test
    void glyphSlotMatchesTheOriginalInlineArithmetic() {
        Random r = new Random(5);
        for (int n = 0; n < 5000; n++) {
            double devX = (r.nextDouble() - 0.5) * 4000;
            if (n % 3 == 0) devX = Math.floor(devX) + (r.nextInt(9) + r.nextDouble() * 1e-9) / 8.0; // 繰り上がりの境目
            double whole = Math.floor(devX);
            int sub = (int) Math.round((devX - whole) * LoadedFace.SUBPIXEL_STEPS);
            if (sub == LoadedFace.SUBPIXEL_STEPS) { whole += 1; sub = 0; }
            assertEquals((float) whole, ShapeGeometry.glyphOriginX(devX));
            assertEquals(sub, ShapeGeometry.glyphSubpixel(devX));
            assertTrue(ShapeGeometry.glyphSubpixel(devX) < LoadedFace.SUBPIXEL_STEPS);
            double devY = (r.nextDouble() - 0.5) * 4000;
            assertEquals((float) Math.round(devY), ShapeGeometry.glyphOriginY(devY));
        }
    }

    @Test
    void axisAlignedOnlyForPositiveUniformScaleAndTranslation() {
        assertTrue(ShapeGeometry.glyphAxisAligned(Matrix2D.IDENTITY));
        assertTrue(ShapeGeometry.glyphAxisAligned(Matrix2D.scale(2f, 2f)));
        assertFalse(ShapeGeometry.glyphAxisAligned(Matrix2D.scale(2f, 3f)), "縦横で違う拡縮");
        assertFalse(ShapeGeometry.glyphAxisAligned(Matrix2D.scale(-1f, -1f)), "負の拡縮");
        assertFalse(ShapeGeometry.glyphAxisAligned(Matrix2D.IDENTITY.rotate(0.3)), "回転");
    }

    @Test
    void glyphLocalBoundsIsTheBoxPlusOne() {
        GlyphBitmap g = new GlyphBitmap(6, 8, 2, 7, new byte[48]);
        float[] b = ShapeGeometry.glyphLocalBounds(g, 10f, 20f, 2f);
        assertEquals(10f + 2 / 2f - 1, b[0]);
        assertEquals(20f - 7 / 2f - 1, b[1]);
        assertEquals(10f + (2 + 6) / 2f + 1, b[2]);
        assertEquals(20f + (8 - 7) / 2f + 1, b[3]);
    }

    // ---- 画像・円の枠線

    private static DisplayItem firstImage(String style) {
        List<DisplayItem> items = SoftwareRendering.displayList(
                "<html><body style=\"margin:0\"><img style=\"" + style + "\" src=\"x.png\"></body></html>", 100, 80);
        for (DisplayItem it : items) if (it.type() == com.nishiyu.enderframelib.core.paint.model.ElementType.IMAGE) return it;
        fail("no image item");
        return null;
    }

    @Test
    void imageGeometryFollowsObjectFitAndClipsTheRangeToTheBox() {
        DisplayItem el = firstImage("width:40px;height:30px");
        ShapeGeometry.Image g = ShapeGeometry.image(el, 8f, 8f, 8, 8, 1f);
        assertNotNull(g);
        float m = 1f;
        assertTrue(g.bx0() >= el.x() - m - 1e-4f && g.by0() >= el.y() - m - 1e-4f);
        assertTrue(g.bx1() <= el.x() + el.width() + m + 1e-4f && g.by1() <= el.y() + el.height() + m + 1e-4f);
        assertEquals(8f / g.plW(), g.sx(), 1e-6f);
        assertEquals(8f / g.plH(), g.sy(), 1e-6f);
        assertEquals(1, g.taps(), "拡大では 1 標本");
        ShapeGeometry.Image shrunk = ShapeGeometry.image(el, 400f, 400f, 400, 400, 1f);
        assertTrue(shrunk.taps() > 1 && shrunk.taps() <= 4, "縮小では複数標本（最大 4）");
        ShapeGeometry.Image fill = ShapeGeometry.image(el, 0f, 0f, 0, 0, 1f);
        assertEquals(el.width(), fill.plW(), "実寸 0 は既定の fit で箱いっぱいに置く（ObjectFit の従来どおり）");
    }

    @Test
    void circleGeometryUsesAnimatedColorsWhenThereIsNoSpec() {
        DisplayItem el = firstImage("width:40px;height:30px");
        AnimatedValues a = new AnimatedValues();
        if (el.meta() == null || el.meta().circleBorder() == null) {
            assertNull(ShapeGeometry.circle(el, a), "指定も色も無ければ null");
            a.circleColors = new int[]{0x80FF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFFFF};
            a.circleThickness = 3f;
            ShapeGeometry.Circle c = ShapeGeometry.circle(el, a);
            assertArrayEquals(a.circleColors, c.straight());
            for (int i = 0; i < 4; i++) assertEquals(Pixmap.premultiply(a.circleColors[i]), c.premul()[i]);
            assertEquals(Math.min(el.width(), el.height()) / 2f, c.outerR());
            assertEquals(c.outerR() - 3f, c.innerR());
            assertEquals(el.x() + el.width() / 2f, c.cx());
            assertEquals(el.y() - 1, c.by0());
            assertEquals(el.x() + el.width() + 1, c.bx1());
        }
    }

    @Test
    void imageCircleAndGlyphGeometryStayOutOfTheBackend() throws Exception {
        Path src = Path.of("src/main/java/com/nishiyu/enderframelib/core/paint/software/SoftwareRenderBackend.java");
        if (!Files.exists(src)) src = Path.of("core").resolve(src);
        String t = Files.readString(src);
        for (String bad : new String[]{"ObjectFit.place(", "circleBorder()", "SUBPIXEL_STEPS", "Math.abs(pose.b()) < 1e-9", "g.left() / s"}) {
            assertFalse(t.contains(bad), bad + " の幾何は ShapeGeometry へ移した");
        }
    }
}
