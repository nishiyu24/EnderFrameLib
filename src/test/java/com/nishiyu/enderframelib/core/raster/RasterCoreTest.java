package com.nishiyu.enderframelib.core.raster;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** パス・ラスタライザ・線の太らせの単体テスト（画素を直接読む）。 */
class RasterCoreTest {

    private static Rasterizer.Coverage fill(Path2D p, boolean evenOdd, int w, int h) {
        return Rasterizer.fill(p.flatten(Matrix2D.IDENTITY, 0.05), evenOdd, w, h);
    }

    @Test
    void axisAlignedRectCoverageIsExact() {
        Rasterizer.Coverage c = fill(new Path2D().rect(2, 3, 4, 2), false, 10, 10);
        assertEquals(255, c.at(2, 3));
        assertEquals(255, c.at(5, 4));
        assertEquals(0, c.at(6, 3));
        assertEquals(0, c.at(1, 3));
        assertEquals(0, c.at(2, 5));
    }

    @Test
    void fractionalEdgesGiveFractionalCoverage() {
        Rasterizer.Coverage c = fill(new Path2D().rect(0.5, 0, 2, 1), false, 4, 1);
        assertEquals(128, c.at(0, 0), 1);   // 横の端数は連続値
        assertEquals(255, c.at(1, 0));
        assertEquals(128, c.at(2, 0), 1);
        Rasterizer.Coverage v = fill(new Path2D().rect(0, 0.5, 1, 2), false, 1, 4);
        assertEquals(128, v.at(0, 0), 1);   // 縦は 1/4 刻み（0.5 は厳密）
        assertEquals(255, v.at(0, 1));
        assertEquals(128, v.at(0, 2), 1);
    }

    @Test
    void triangleAreaMatches() {
        Rasterizer.Coverage c = fill(new Path2D().moveTo(0, 0).lineTo(20, 0).lineTo(0, 20).closePath(), false, 20, 20);
        double area = 0;
        for (int y = 0; y < 20; y++) for (int x = 0; x < 20; x++) area += c.at(x, y) / 255.0;
        assertEquals(200, area, 4); // 理論値 20*20/2
    }

    @Test
    void circleAreaMatches() {
        Path2D p = new Path2D().arc(25, 25, 20, 0, Math.PI * 2, false);
        Rasterizer.Coverage c = fill(p, false, 50, 50);
        double area = 0;
        for (int y = 0; y < 50; y++) for (int x = 0; x < 50; x++) area += c.at(x, y) / 255.0;
        assertEquals(Math.PI * 400, area, 6);
    }

    @Test
    void fillRulesDiffer() {
        Path2D p = new Path2D().rect(0, 0, 10, 10).rect(2, 2, 6, 6);   // 同じ向きの入れ子
        assertEquals(255, fill(p, false, 10, 10).at(5, 5));              // nonzero: 穴は無い
        assertEquals(0, fill(p, true, 10, 10).at(5, 5));                 // evenodd: 穴
        assertEquals(255, fill(p, true, 10, 10).at(1, 1));
    }

    @Test
    void clipsToBounds() {
        Rasterizer.Coverage c = fill(new Path2D().rect(-5, -5, 100, 100), false, 8, 8);
        assertEquals(0, c.x());
        assertEquals(8, c.width());
        assertEquals(255, c.at(7, 7));
        assertNull(fill(new Path2D().rect(20, 20, 5, 5), false, 8, 8));
    }

    @Test
    void containsAgreesWithFillRule() {
        Path2D p = new Path2D().rect(0, 0, 10, 10).rect(2, 2, 6, 6);
        List<Path2D.Polyline> polys = p.flatten(Matrix2D.IDENTITY, 0.1);
        assertTrue(Rasterizer.contains(polys, 5, 5, false));
        assertFalse(Rasterizer.contains(polys, 5, 5, true));
        assertFalse(Rasterizer.contains(polys, 11, 5, false));
    }

    @Test
    void svgPathDataParsing() {
        double[] b = Path2D.fromSvg("M10 10 h20 v20 h-20 z").bounds(Matrix2D.IDENTITY);
        assertArrayEquals(new double[]{10, 10, 30, 30}, b, 1e-9);
        // 暗黙の lineTo・小数・指数・区切り無しの数
        double[] b2 = Path2D.fromSvg("M0,0 10,0 10,5e0 0 5Z").bounds(Matrix2D.IDENTITY);
        assertArrayEquals(new double[]{0, 0, 10, 5}, b2, 1e-9);
        double[] b3 = Path2D.fromSvg("M1.5.5L3-2").bounds(Matrix2D.IDENTITY);
        assertArrayEquals(new double[]{1.5, -2, 3, 0.5}, b3, 1e-9);
        // 誤りの手前まで
        double[] b4 = Path2D.fromSvg("M0 0 L10 10 L oops 20 20").bounds(Matrix2D.IDENTITY);
        assertArrayEquals(new double[]{0, 0, 10, 10}, b4, 1e-9);
        assertTrue(Path2D.fromSvg("L10 10").isEmpty());   // M で始まらない
    }

    @Test
    void svgArcFlagsWithoutSeparators() {
        // 半円の弧: フラグは区切り無しで続く
        Path2D p = Path2D.fromSvg("M0 0a5 5 0 1110 0");
        double[] b = p.bounds(Matrix2D.IDENTITY);
        assertEquals(0, b[0], 1e-6);
        assertEquals(10, b[2], 1e-6);
        assertEquals(5, Math.abs(b[1]) + Math.abs(b[3]), 0.1);
    }

    @Test
    void arcToProducesTangentArc() {
        Path2D p = new Path2D().moveTo(0, 0).arcTo(10, 0, 10, 10, 5).lineTo(10, 20);
        double[] b = p.bounds(Matrix2D.IDENTITY);
        assertEquals(10, b[2], 1e-6);
        assertEquals(20, b[3], 1e-6);
        // 弧の途中点は角から内側へ (5 - 5/√2) の所
        List<Path2D.Polyline> pl = p.flatten(Matrix2D.IDENTITY, 0.01);
        boolean found = false;
        double expected = 5 - 5 / Math.sqrt(2) ;
        for (Path2D.Polyline l : pl) {
            for (int i = 0; i < l.count(); i++) {
                if (Math.abs(l.x(i) - (10 - expected)) < 0.05 && Math.abs(l.y(i) - expected) < 0.05) found = true;
            }
        }
        assertTrue(found, "arcTo midpoint");
    }

    @Test
    void strokeOfHorizontalLineHasWidth() {
        List<Path2D.Polyline> line = new Path2D().moveTo(2, 5).lineTo(8, 5).flatten(Matrix2D.IDENTITY, 0.1);
        List<Path2D.Polyline> poly = Stroker.stroke(line, 2, Stroker.Cap.BUTT, Stroker.Join.MITER, 10, null, 0, 0.1);
        Rasterizer.Coverage c = Rasterizer.fill(poly, false, 10, 10);
        assertEquals(255, c.at(5, 4));
        assertEquals(255, c.at(5, 5));
        assertEquals(0, c.at(5, 3));
        assertEquals(0, c.at(1, 5));   // butt: 端から出ない
        assertEquals(0, c.at(8, 5));
    }

    @Test
    void capsExtendLine() {
        List<Path2D.Polyline> line = new Path2D().moveTo(3, 5).lineTo(7, 5).flatten(Matrix2D.IDENTITY, 0.1);
        Rasterizer.Coverage square = Rasterizer.fill(
                Stroker.stroke(line, 2, Stroker.Cap.SQUARE, Stroker.Join.MITER, 10, null, 0, 0.1), false, 12, 12);
        assertEquals(255, square.at(2, 5));
        assertEquals(255, square.at(7, 4));
        assertEquals(0, square.at(8, 5));
        Rasterizer.Coverage round = Rasterizer.fill(
                Stroker.stroke(line, 4, Stroker.Cap.ROUND, Stroker.Join.MITER, 10, null, 0, 0.05), false, 12, 12);
        assertEquals(255, round.at(2, 4));
        assertTrue(round.at(0, 0) == 0);
        assertTrue(round.at(1, 3) < 255 && round.at(1, 3) >= 0);
    }

    @Test
    void joinsMiterBevelRound() {
        List<Path2D.Polyline> corner = new Path2D().moveTo(2, 18).lineTo(2, 2).lineTo(18, 2).flatten(Matrix2D.IDENTITY, 0.1);
        // 外側の角の画素 (0,0)〜(1,1) 付近: マイターは埋まり、ベベルは欠ける
        Rasterizer.Coverage miter = Rasterizer.fill(
                Stroker.stroke(corner, 4, Stroker.Cap.BUTT, Stroker.Join.MITER, 10, null, 0, 0.05), false, 20, 20);
        Rasterizer.Coverage bevel = Rasterizer.fill(
                Stroker.stroke(corner, 4, Stroker.Cap.BUTT, Stroker.Join.BEVEL, 10, null, 0, 0.05), false, 20, 20);
        Rasterizer.Coverage round = Rasterizer.fill(
                Stroker.stroke(corner, 4, Stroker.Cap.BUTT, Stroker.Join.ROUND, 10, null, 0, 0.05), false, 20, 20);
        assertEquals(255, miter.at(0, 0));
        assertEquals(0, bevel.at(0, 0));
        assertTrue(round.at(0, 0) < 200);
        assertTrue(round.at(1, 1) > 100);
        // マイター制限を超えたらベベルになる（鋭い角）
        List<Path2D.Polyline> sharp = new Path2D().moveTo(0, 0).lineTo(30, 2).lineTo(0, 4).flatten(Matrix2D.IDENTITY, 0.1);
        Rasterizer.Coverage limited = Rasterizer.fill(
                Stroker.stroke(sharp, 2, Stroker.Cap.BUTT, Stroker.Join.MITER, 2, null, 0, 0.05), false, 60, 10);
        assertNotNull(limited);
        assertTrue(limited.x() + limited.width() <= 33, "miter must be limited");
    }

    @Test
    void dashProducesGaps() {
        List<Path2D.Polyline> line = new Path2D().moveTo(0, 5).lineTo(20, 5).flatten(Matrix2D.IDENTITY, 0.1);
        Rasterizer.Coverage c = Rasterizer.fill(
                Stroker.stroke(line, 2, Stroker.Cap.BUTT, Stroker.Join.MITER, 10, new double[]{4, 4}, 0, 0.1), false, 20, 10);
        assertEquals(255, c.at(1, 5));
        assertEquals(0, c.at(5, 5));
        assertEquals(255, c.at(9, 5));
        assertEquals(0, c.at(13, 5));
        Rasterizer.Coverage offset = Rasterizer.fill(
                Stroker.stroke(line, 2, Stroker.Cap.BUTT, Stroker.Join.MITER, 10, new double[]{4, 4}, 4, 0.1), false, 20, 10);
        assertEquals(0, offset.at(1, 5));
        assertEquals(255, offset.at(5, 5));
    }

    @Test
    void zeroLengthSubpathDrawsDotOnlyWithRoundOrSquareCaps() {
        List<Path2D.Polyline> dot = new Path2D().moveTo(5, 5).lineTo(5, 5).flatten(Matrix2D.IDENTITY, 0.1);
        assertTrue(Stroker.stroke(dot, 4, Stroker.Cap.BUTT, Stroker.Join.MITER, 10, null, 0, 0.1).isEmpty());
        assertEquals(255, Rasterizer.fill(Stroker.stroke(dot, 4, Stroker.Cap.SQUARE, Stroker.Join.MITER, 10, null, 0, 0.1), false, 10, 10).at(5, 5));
        assertEquals(255, Rasterizer.fill(Stroker.stroke(dot, 4, Stroker.Cap.ROUND, Stroker.Join.MITER, 10, null, 0, 0.1), false, 10, 10).at(5, 5));
    }

    @Test
    void matrixInverseRoundTrips() {
        Matrix2D m = Matrix2D.translate(3, 4).multiply(Matrix2D.rotate(0.7)).multiply(Matrix2D.scale(2, 3));
        Matrix2D inv = m.invert();
        double x = m.applyX(1.5, -2);
        double y = m.applyY(1.5, -2);
        assertEquals(1.5, inv.applyX(x, y), 1e-9);
        assertEquals(-2, inv.applyY(x, y), 1e-9);
        assertNull(Matrix2D.scale(0, 1).invert());
    }
}
