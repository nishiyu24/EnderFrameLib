package com.nishiyu.enderframelib.core.paint;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RoundedRectGeometryTest {

    private static double area(List<float[]> rows) {
        double a = 0;
        for (float[] r : rows) a += (r[2] - r[0]) * (r[3] - r[1]);
        return a;
    }

    private static List<float[]> rounded(float x0, float y0, float x1, float y1, float tl, float tr, float br, float bl) {
        List<float[]> rows = new ArrayList<>();
        RoundedRectGeometry.roundedRectRows(x0, y0, x1, y1, tl, tr, br, bl, (a, b, c, d) -> rows.add(new float[]{a, b, c, d}));
        return rows;
    }

    private static List<float[]> clipped(float[] rect, float[] clip, float[] radii) {
        List<float[]> rows = new ArrayList<>();
        RoundedRectGeometry.clippedRows(rect[0], rect[1], rect[2], rect[3], clip[0], clip[1], clip[2], clip[3],
                radii[0], radii[1], radii[2], radii[3], (a, b, c, d) -> rows.add(new float[]{a, b, c, d}));
        return rows;
    }

    @Test
    void cornerInsetBounds() {
        assertEquals(0f, RoundedRectGeometry.computeCornerInset(0f, 5f));
        assertEquals(0f, RoundedRectGeometry.computeCornerInset(8f, 0f));
        assertEquals(0f, RoundedRectGeometry.computeCornerInset(8f, 0.0001f), 0.01f);   // 円弧の中心の高さ: 食い込まない
        assertEquals(8f, RoundedRectGeometry.computeCornerInset(8f, 8f), 1e-6f);        // 端: 半径ぶん食い込む
        assertEquals(8f, RoundedRectGeometry.computeCornerInset(8f, 99f), 1e-6f);       // 半径を超えても頭打ち
    }

    @Test
    void noRadiusIsSingleRect() {
        List<float[]> rows = rounded(10, 20, 110, 70, 0, 0, 0, 0);
        assertEquals(1, rows.size());
        assertArrayEquals(new float[]{10, 20, 110, 70}, rows.get(0));
    }

    @Test
    void roundedAreaApproximatesAnalyticArea() {
        float w = 100, h = 60, r = 20;
        double analytic = w * h - (4 - Math.PI) * r * r;
        assertEquals(analytic, area(rounded(0, 0, w, h, r, r, r, r)), analytic * 0.01);
    }

    @Test
    void rowsStayInsideRectAndAreSymmetric() {
        List<float[]> rows = rounded(5, 5, 105, 65, 12, 12, 12, 12);
        for (float[] row : rows) {
            assertTrue(row[0] >= 5 && row[2] <= 105 && row[1] >= 5 && row[3] <= 65);
            assertTrue(row[0] < row[2] && row[1] < row[3]);
            assertEquals(row[0] - 5, 105 - row[2], 1e-4f);
        }
    }

    @Test
    void perCornerRadiiOnlyCutThatCorner() {
        List<float[]> rows = rounded(0, 0, 50, 50, 10, 0, 0, 0);
        List<float[]> top = rows.stream().filter(r -> r[1] == 0f).toList();
        assertTrue(top.stream().allMatch(r -> r[0] > 0f));    // 左上は欠ける
        assertTrue(top.stream().allMatch(r -> r[2] == 50f));  // 右上は欠けない
        List<float[]> bottom = rows.stream().filter(r -> r[3] == 50f).toList();
        assertTrue(bottom.stream().allMatch(r -> r[0] == 0f && r[2] == 50f));
    }

    @Test
    void middleBandComesFirstThenTopThenBottom() {
        List<float[]> rows = rounded(0, 0, 40, 40, 8, 8, 8, 8);
        assertEquals(0f, rows.get(0)[0]);
        assertEquals(8f, rows.get(0)[1]);
        assertEquals(32f, rows.get(0)[3]);
        assertEquals(0f, rows.get(1)[1]);
        // 下の角の帯は下端から上へ向かって出る
        assertEquals(40f, rows.get(rows.size() - 8)[3]);
        assertEquals(33f, rows.get(rows.size() - 1)[3]);
    }

    @Test
    void cornersMeetingInMiddleApproximateACircle() {
        assertEquals(Math.PI * 100, area(rounded(0, 0, 20, 20, 10, 10, 10, 10)), 4.0);
    }

    @Test
    void plainClipIsIntersection() {
        List<float[]> rows = clipped(new float[]{0, 0, 100, 100}, new float[]{20, 30, 80, 90}, new float[]{0, 0, 0, 0});
        assertEquals(1, rows.size());
        assertArrayEquals(new float[]{20, 30, 80, 90}, rows.get(0));
    }

    @Test
    void disjointClipIsEmpty() {
        assertTrue(clipped(new float[]{0, 0, 10, 10}, new float[]{50, 50, 60, 60}, new float[]{4, 4, 4, 4}).isEmpty());
    }

    @Test
    void roundedClipCutsCornersOfCoveringRect() {
        List<float[]> rows = clipped(new float[]{-10, -10, 60, 60}, new float[]{0, 0, 40, 40}, new float[]{8, 8, 8, 8});
        double analytic = 40 * 40 - (4 - Math.PI) * 64;
        assertEquals(analytic, area(rows), analytic * 0.02);
        for (float[] r : rows) assertTrue(r[0] >= 0 && r[2] <= 40 && r[1] >= 0 && r[3] <= 40);
        for (int i = 1; i < rows.size(); i++) assertTrue(rows.get(i)[1] >= rows.get(i - 1)[1]);
    }

    @Test
    void clipNarrowerThanRectWinsOnX() {
        List<float[]> rows = clipped(new float[]{0, 0, 30, 40}, new float[]{10, 0, 100, 40}, new float[]{6, 6, 6, 6});
        assertFalse(rows.isEmpty());
        for (float[] r : rows) assertTrue(r[0] >= 10 && r[2] <= 30);
    }
}
