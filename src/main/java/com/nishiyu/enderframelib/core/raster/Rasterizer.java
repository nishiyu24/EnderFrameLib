package com.nishiyu.enderframelib.core.raster;

import java.util.Arrays;
import java.util.List;

/**
 * 折れ線の塗りつぶしを、画素ごとの被覆率（0〜255）にするスキャンライン・ラスタライザ。
 *
 * 縦は 1 画素を {@value #SUBSAMPLES} 本のサブスキャンラインで標本化し、横は各サブスキャンラインの区間の端の
 * 端数を厳密に重みにする。したがって軸に沿った辺は画素の 1/4 刻みで、水平方向は連続した値で被覆率が出る。
 * 塗りの規則は nonzero と evenodd。開いた部分パスは暗黙に閉じる（塗りつぶしの定義）。
 */
public final class Rasterizer {

    public static final int SUBSAMPLES = 4;

    private Rasterizer() {
    }

    /** 領域 {@code (x, y, width, height)} の被覆率（行優先・0〜255）。 */
    public record Coverage(int x, int y, int width, int height, byte[] data) {
        public int at(int px, int py) {
            int lx = px - x;
            int ly = py - y;
            if (lx < 0 || ly < 0 || lx >= width || ly >= height) return 0;
            return data[ly * width + lx] & 0xFF;
        }
    }

    /**
     * 折れ線群を塗った被覆率。{@code [0, clipW) × [0, clipH)} の外は切る。何も塗られなければ {@code null}。
     */
    public static Coverage fill(List<Path2D.Polyline> polys, boolean evenOdd, int clipW, int clipH) {
        int edgeCount = 0;
        for (Path2D.Polyline p : polys) if (p.count() >= 2) edgeCount += p.count();
        if (edgeCount == 0 || clipW <= 0 || clipH <= 0) return null;

        double[] ex0 = new double[edgeCount];
        double[] ey0 = new double[edgeCount];
        double[] ey1 = new double[edgeCount];
        double[] slope = new double[edgeCount];
        int[] dir = new int[edgeCount];
        int n = 0;
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (Path2D.Polyline p : polys) {
            int c = p.count();
            if (c < 2) continue;
            for (int i = 0; i < c; i++) {
                int j = i + 1 == c ? 0 : i + 1;
                double x0 = p.x(i);
                double y0 = p.y(i);
                double x1 = p.x(j);
                double y1 = p.y(j);
                if (!Double.isFinite(x0 + y0 + x1 + y1)) return null;
                minX = Math.min(minX, Math.min(x0, x1));
                maxX = Math.max(maxX, Math.max(x0, x1));
                minY = Math.min(minY, Math.min(y0, y1));
                maxY = Math.max(maxY, Math.max(y0, y1));
                if (y0 == y1) continue;
                if (y0 < y1) {
                    ex0[n] = x0;
                    ey0[n] = y0;
                    ey1[n] = y1;
                    dir[n] = 1;
                } else {
                    ex0[n] = x1;
                    ey0[n] = y1;
                    ey1[n] = y0;
                    dir[n] = -1;
                }
                slope[n] = (x1 - x0) / (y1 - y0);
                n++;
            }
        }
        if (n == 0) return null;
        int bx0 = (int) Math.max(0, Math.floor(minX));
        int bx1 = (int) Math.min(clipW, Math.ceil(maxX));
        int by0 = (int) Math.max(0, Math.floor(minY));
        int by1 = (int) Math.min(clipH, Math.ceil(maxY));
        if (bx0 >= bx1 || by0 >= by1) return null;
        int w = bx1 - bx0;
        int h = by1 - by0;

        Integer[] boxed = new Integer[n];
        for (int i = 0; i < n; i++) boxed[i] = i;
        final double[] yTop = ey0;
        Arrays.sort(boxed, (a, b) -> Double.compare(yTop[a], yTop[b]));
        int[] order = new int[n];
        for (int i = 0; i < n; i++) order[i] = boxed[i];

        byte[] data = new byte[w * h];
        float[] acc = new float[w + 2];
        float[] diff = new float[w + 2];
        int[] active = new int[n];
        int activeCount = 0;
        int next = 0;
        double[] xs = new double[n];
        int[] ds = new int[n];
        final float unit = 1f / SUBSAMPLES;

        for (int row = by0; row < by1; row++) {
            Arrays.fill(acc, 0);
            Arrays.fill(diff, 0);
            boolean any = false;
            for (int k = 0; k < SUBSAMPLES; k++) {
                double sy = row + (k + 0.5) / SUBSAMPLES;
                while (next < n && ey0[order[next]] <= sy) active[activeCount++] = order[next++];
                int m = 0;
                int keep = 0;
                for (int a = 0; a < activeCount; a++) {
                    int e = active[a];
                    if (ey1[e] <= sy) continue;
                    active[keep++] = e;
                    double x = ex0[e] + (sy - ey0[e]) * slope[e];
                    // 挿入ソート（交点は少ない）
                    int pos = m++;
                    while (pos > 0 && xs[pos - 1] > x) {
                        xs[pos] = xs[pos - 1];
                        ds[pos] = ds[pos - 1];
                        pos--;
                    }
                    xs[pos] = x;
                    ds[pos] = dir[e];
                }
                activeCount = keep;
                int winding = 0;
                for (int c = 0; c < m - 1; c++) {
                    winding += ds[c];
                    boolean inside = evenOdd ? ((c + 1) & 1) == 1 : winding != 0;
                    if (!inside) continue;
                    double xa = Math.max(xs[c] - bx0, 0);
                    double xb = Math.min(xs[c + 1] - bx0, w);
                    if (xb <= xa) continue;
                    any = true;
                    int ia = (int) xa;
                    int ib = (int) xb;
                    if (ia == ib) {
                        acc[ia] += (float) (xb - xa) * unit;
                    } else {
                        acc[ia] += (float) (ia + 1 - xa) * unit;
                        diff[ia + 1] += unit;
                        diff[ib] -= unit;
                        acc[ib] += (float) (xb - ib) * unit;
                    }
                }
            }
            if (!any) continue;
            float run = 0;
            int base = (row - by0) * w;
            for (int x = 0; x < w; x++) {
                run += diff[x];
                float v = acc[x] + run;
                int b = Math.round(v * 255f);
                data[base + x] = (byte) (b > 255 ? 255 : Math.max(b, 0));
            }
        }
        return new Coverage(bx0, by0, w, h, data);
    }

    /** 点 {@code (x, y)} が塗りの内側か（折れ線は暗黙に閉じる）。 */
    public static boolean contains(List<Path2D.Polyline> polys, double x, double y, boolean evenOdd) {
        int winding = 0;
        int crossings = 0;
        for (Path2D.Polyline p : polys) {
            int c = p.count();
            if (c < 2) continue;
            for (int i = 0; i < c; i++) {
                int j = i + 1 == c ? 0 : i + 1;
                double x0 = p.x(i);
                double y0 = p.y(i);
                double x1 = p.x(j);
                double y1 = p.y(j);
                if ((y0 <= y) == (y1 <= y)) continue;
                double t = (y - y0) / (y1 - y0);
                double cx = x0 + t * (x1 - x0);
                if (cx > x) {
                    crossings++;
                    winding += y1 > y0 ? 1 : -1;
                }
            }
        }
        return evenOdd ? (crossings & 1) == 1 : winding != 0;
    }
}
