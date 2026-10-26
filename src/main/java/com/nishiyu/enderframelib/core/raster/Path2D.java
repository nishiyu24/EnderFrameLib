package com.nishiyu.enderframelib.core.raster;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 2D のパス（Canvas 2D の {@code Path2D} と SVG のパスデータ）。
 * 直線・2 次/3 次ベジェ・閉じる、の 4 種の操作で持つ。弧は 3 次ベジェに直して入れる。
 *
 * メソッドの意味は HTML Standard の Canvas 2D（現在点が無いときの {@code lineTo} は {@code moveTo} になる、
 * {@code closePath} の後の操作は閉じた部分パスの始点から新しい部分パスを始める、など）に従う。
 */
public final class Path2D {

    private static final byte OP_MOVE = 0;
    private static final byte OP_LINE = 1;
    private static final byte OP_QUAD = 2;
    private static final byte OP_CUBIC = 3;
    private static final byte OP_CLOSE = 4;

    /** 平坦化した 1 本の折れ線（{@code xy} は x, y の並び。{@code count} は点の数）。 */
    public record Polyline(double[] xy, int count, boolean closed) {
        public double x(int i) {
            return xy[i * 2];
        }

        public double y(int i) {
            return xy[i * 2 + 1];
        }
    }

    private byte[] ops = new byte[16];
    private double[] pts = new double[64];
    private int opCount;
    private int ptCount;

    private double startX;
    private double startY;
    private double curX;
    private double curY;
    private boolean lastClose;

    public Path2D() {
    }

    public Path2D(Path2D other) {
        ops = Arrays.copyOf(other.ops, Math.max(16, other.opCount));
        pts = Arrays.copyOf(other.pts, Math.max(64, other.ptCount));
        opCount = other.opCount;
        ptCount = other.ptCount;
        startX = other.startX;
        startY = other.startY;
        curX = other.curX;
        curY = other.curY;
        lastClose = other.lastClose;
    }

    /** SVG のパスデータ（{@code d} 属性）から作る。誤りのある所までを読む（SVG 1.1 §8.3.9 のエラー処理）。 */
    public static Path2D fromSvg(String d) {
        return SvgPathData.parse(d);
    }

    public boolean isEmpty() {
        return opCount == 0;
    }

    public boolean hasCurrentPoint() {
        return opCount > 0;
    }

    public double currentX() {
        return curX;
    }

    public double currentY() {
        return curY;
    }

    public void clear() {
        opCount = 0;
        ptCount = 0;
        lastClose = false;
    }

    // ------------------------------------------------------------------ 基本操作

    public Path2D moveTo(double x, double y) {
        if (!finite(x, y)) return this;
        pushOp(OP_MOVE);
        pushPt(x, y);
        startX = curX = x;
        startY = curY = y;
        lastClose = false;
        return this;
    }

    public Path2D lineTo(double x, double y) {
        if (!finite(x, y)) return this;
        if (opCount == 0) return moveTo(x, y);
        implicitMove();
        pushOp(OP_LINE);
        pushPt(x, y);
        curX = x;
        curY = y;
        return this;
    }

    public Path2D quadTo(double cx, double cy, double x, double y) {
        if (!finite(cx, cy) || !finite(x, y)) return this;
        if (opCount == 0) moveTo(cx, cy);
        implicitMove();
        pushOp(OP_QUAD);
        pushPt(cx, cy);
        pushPt(x, y);
        curX = x;
        curY = y;
        return this;
    }

    public Path2D cubicTo(double c1x, double c1y, double c2x, double c2y, double x, double y) {
        if (!finite(c1x, c1y) || !finite(c2x, c2y) || !finite(x, y)) return this;
        if (opCount == 0) moveTo(c1x, c1y);
        implicitMove();
        pushOp(OP_CUBIC);
        pushPt(c1x, c1y);
        pushPt(c2x, c2y);
        pushPt(x, y);
        curX = x;
        curY = y;
        return this;
    }

    public Path2D closePath() {
        if (opCount == 0 || lastClose) return this;
        pushOp(OP_CLOSE);
        curX = startX;
        curY = startY;
        lastClose = true;
        return this;
    }

    private void implicitMove() {
        if (lastClose) {
            double sx = startX;
            double sy = startY;
            pushOp(OP_MOVE);
            pushPt(sx, sy);
            curX = sx;
            curY = sy;
            lastClose = false;
        }
    }

    // ------------------------------------------------------------------ 図形

    public Path2D rect(double x, double y, double w, double h) {
        if (!finite(x, y) || !finite(w, h)) return this;
        moveTo(x, y);
        lineTo(x + w, y);
        lineTo(x + w, y + h);
        lineTo(x, y + h);
        closePath();
        return this;
    }

    /** 角ごとの半径（左上・右上・右下・左下の順。1 つなら全部同じ）で角丸にした矩形。 */
    public Path2D roundRect(double x, double y, double w, double h, double... radii) {
        if (!finite(x, y) || !finite(w, h)) return this;
        double tl;
        double tr;
        double br;
        double bl;
        if (radii == null || radii.length == 0) {
            tl = tr = br = bl = 0;
        } else if (radii.length == 1) {
            tl = tr = br = bl = radii[0];
        } else if (radii.length == 2) {
            tl = br = radii[0];
            tr = bl = radii[1];
        } else if (radii.length == 3) {
            tl = radii[0];
            tr = bl = radii[1];
            br = radii[2];
        } else {
            tl = radii[0];
            tr = radii[1];
            br = radii[2];
            bl = radii[3];
        }
        if (tl < 0 || tr < 0 || br < 0 || bl < 0) return this;
        if (w < 0) {
            x += w;
            w = -w;
            double t = tl;
            tl = tr;
            tr = t;
            t = bl;
            bl = br;
            br = t;
        }
        if (h < 0) {
            y += h;
            h = -h;
            double t = tl;
            tl = bl;
            bl = t;
            t = tr;
            tr = br;
            br = t;
        }
        double scale = 1;
        if (tl + tr > w) scale = Math.min(scale, w / (tl + tr));
        if (bl + br > w) scale = Math.min(scale, w / (bl + br));
        if (tl + bl > h) scale = Math.min(scale, h / (tl + bl));
        if (tr + br > h) scale = Math.min(scale, h / (tr + br));
        tl *= scale;
        tr *= scale;
        br *= scale;
        bl *= scale;
        moveTo(x + tl, y);
        lineTo(x + w - tr, y);
        if (tr > 0) quarter(x + w - tr, y + tr, tr, -Math.PI / 2);
        lineTo(x + w, y + h - br);
        if (br > 0) quarter(x + w - br, y + h - br, br, 0);
        lineTo(x + bl, y + h);
        if (bl > 0) quarter(x + bl, y + h - bl, bl, Math.PI / 2);
        lineTo(x, y + tl);
        if (tl > 0) quarter(x + tl, y + tl, tl, Math.PI);
        closePath();
        return this;
    }

    private void quarter(double cx, double cy, double r, double startAngle) {
        arcSegments(cx, cy, r, r, 0, startAngle, Math.PI / 2);
    }

    /** Canvas の {@code arc()}。現在の部分パスがあれば、現在点から弧の始点へ直線を引く。 */
    public Path2D arc(double cx, double cy, double r, double startAngle, double endAngle, boolean counterclockwise) {
        return ellipse(cx, cy, r, r, 0, startAngle, endAngle, counterclockwise);
    }

    /** Canvas の {@code ellipse()}。 */
    public Path2D ellipse(double cx, double cy, double rx, double ry, double rotation,
                          double startAngle, double endAngle, boolean counterclockwise) {
        if (!finite(cx, cy) || !finite(rx, ry) || !finite(rotation, startAngle) || !Double.isFinite(endAngle)) return this;
        if (rx < 0 || ry < 0) return this;
        double sweep;
        double twoPi = Math.PI * 2;
        if (!counterclockwise) {
            if (endAngle - startAngle >= twoPi) {
                sweep = twoPi;
            } else {
                sweep = mod(endAngle - startAngle, twoPi);
            }
        } else {
            if (startAngle - endAngle >= twoPi) {
                sweep = -twoPi;
            } else {
                sweep = -mod(startAngle - endAngle, twoPi);
            }
        }
        double cosR = Math.cos(rotation);
        double sinR = Math.sin(rotation);
        double sx = cx + cosR * rx * Math.cos(startAngle) - sinR * ry * Math.sin(startAngle);
        double sy = cy + sinR * rx * Math.cos(startAngle) + cosR * ry * Math.sin(startAngle);
        lineTo(sx, sy);
        if (rx == 0 || ry == 0) {
            return lineTo(cx + cosR * rx * Math.cos(startAngle + sweep) - sinR * ry * Math.sin(startAngle + sweep),
                    cy + sinR * rx * Math.cos(startAngle + sweep) + cosR * ry * Math.sin(startAngle + sweep));
        }
        arcSegments(cx, cy, rx, ry, rotation, startAngle, sweep);
        return this;
    }

    private static double mod(double v, double m) {
        double r = v % m;
        return r < 0 ? r + m : r;
    }

    /** 現在点が弧の始点にある前提で、{@code sweep} ぶんの弧を 3 次ベジェで足す。 */
    private void arcSegments(double cx, double cy, double rx, double ry, double rotation, double start, double sweep) {
        int n = Math.max(1, (int) Math.ceil(Math.abs(sweep) / (Math.PI / 2) - 1e-9));
        double step = sweep / n;
        double cosR = Math.cos(rotation);
        double sinR = Math.sin(rotation);
        double k = 4.0 / 3.0 * Math.tan(step / 4);
        for (int i = 0; i < n; i++) {
            double t1 = start + step * i;
            double t2 = t1 + step;
            double c1 = Math.cos(t1);
            double s1 = Math.sin(t1);
            double c2 = Math.cos(t2);
            double s2 = Math.sin(t2);
            double p1x = rx * c1;
            double p1y = ry * s1;
            double p2x = rx * c2;
            double p2y = ry * s2;
            double d1x = -rx * s1;
            double d1y = ry * c1;
            double d2x = -rx * s2;
            double d2y = ry * c2;
            double a1x = p1x + k * d1x;
            double a1y = p1y + k * d1y;
            double a2x = p2x - k * d2x;
            double a2y = p2y - k * d2y;
            if (opCount == 0) moveTo(cx + cosR * p1x - sinR * p1y, cy + sinR * p1x + cosR * p1y);
            cubicTo(cx + cosR * a1x - sinR * a1y, cy + sinR * a1x + cosR * a1y,
                    cx + cosR * a2x - sinR * a2y, cy + sinR * a2x + cosR * a2y,
                    cx + cosR * p2x - sinR * p2y, cy + sinR * p2x + cosR * p2y);
        }
    }

    /** Canvas の {@code arcTo()}。 */
    public Path2D arcTo(double x1, double y1, double x2, double y2, double r) {
        if (!finite(x1, y1) || !finite(x2, y2) || !Double.isFinite(r) || r < 0) return this;
        if (opCount == 0) moveTo(x1, y1);
        double x0 = curX;
        double y0 = curY;
        double d0x = x0 - x1;
        double d0y = y0 - y1;
        double d2x = x2 - x1;
        double d2y = y2 - y1;
        double len0 = Math.hypot(d0x, d0y);
        double len2 = Math.hypot(d2x, d2y);
        double cross = d0x * d2y - d0y * d2x;
        if (len0 == 0 || len2 == 0 || r == 0 || Math.abs(cross) < 1e-12 * len0 * len2) {
            return lineTo(x1, y1);
        }
        d0x /= len0;
        d0y /= len0;
        d2x /= len2;
        d2y /= len2;
        double cos = Math.max(-1, Math.min(1, d0x * d2x + d0y * d2y));
        double angle = Math.acos(cos);
        double tangent = r / Math.tan(angle / 2);
        double t0x = x1 + d0x * tangent;
        double t0y = y1 + d0y * tangent;
        double t2x = x1 + d2x * tangent;
        double t2y = y1 + d2y * tangent;
        double bx = d0x + d2x;
        double by = d0y + d2y;
        double blen = Math.hypot(bx, by);
        double dist = r / Math.sin(angle / 2);
        double cx = x1 + bx / blen * dist;
        double cy = y1 + by / blen * dist;
        double a0 = Math.atan2(t0y - cy, t0x - cx);
        double a1 = Math.atan2(t2y - cy, t2x - cx);
        double delta = a1 - a0;
        while (delta > Math.PI) delta -= Math.PI * 2;
        while (delta <= -Math.PI) delta += Math.PI * 2;
        lineTo(t0x, t0y);
        arcSegments(cx, cy, r, r, 0, a0, delta);
        return this;
    }

    /** SVG の楕円弧（{@code A} コマンド。角度は度）。 */
    public Path2D svgArcTo(double rx, double ry, double xAxisRotationDeg, boolean largeArc, boolean sweepFlag,
                           double x, double y) {
        if (!finite(rx, ry) || !finite(x, y) || !Double.isFinite(xAxisRotationDeg)) return this;
        if (opCount == 0) moveTo(x, y);
        double x0 = curX;
        double y0 = curY;
        if (x0 == x && y0 == y) return this;
        rx = Math.abs(rx);
        ry = Math.abs(ry);
        if (rx == 0 || ry == 0) return lineTo(x, y);
        double phi = Math.toRadians(xAxisRotationDeg % 360);
        double cosPhi = Math.cos(phi);
        double sinPhi = Math.sin(phi);
        double dx2 = (x0 - x) / 2;
        double dy2 = (y0 - y) / 2;
        double x1p = cosPhi * dx2 + sinPhi * dy2;
        double y1p = -sinPhi * dx2 + cosPhi * dy2;
        double lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry);
        if (lambda > 1) {
            double s = Math.sqrt(lambda);
            rx *= s;
            ry *= s;
        }
        double num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p;
        double den = rx * rx * y1p * y1p + ry * ry * x1p * x1p;
        double coef = den == 0 ? 0 : Math.sqrt(Math.max(0, num / den));
        if (largeArc == sweepFlag) coef = -coef;
        double cxp = coef * (rx * y1p / ry);
        double cyp = coef * -(ry * x1p / rx);
        double cx = cosPhi * cxp - sinPhi * cyp + (x0 + x) / 2;
        double cy = sinPhi * cxp + cosPhi * cyp + (y0 + y) / 2;
        double theta1 = Math.atan2((y1p - cyp) / ry, (x1p - cxp) / rx);
        double theta2 = Math.atan2((-y1p - cyp) / ry, (-x1p - cxp) / rx);
        double dtheta = theta2 - theta1;
        if (sweepFlag && dtheta < 0) dtheta += Math.PI * 2;
        else if (!sweepFlag && dtheta > 0) dtheta -= Math.PI * 2;
        arcSegments(cx, cy, rx, ry, phi, theta1, dtheta);
        // 丸め誤差で終点がずれないように、終点を固定する
        curX = x;
        curY = y;
        pts[ptCount - 2] = x;
        pts[ptCount - 1] = y;
        return this;
    }

    // ------------------------------------------------------------------ 合成

    /** {@code src} を変換して末尾に足す（始点の moveTo はそのまま）。 */
    public Path2D append(Path2D src, Matrix2D m) {
        return append(src, m, false);
    }

    /**
     * {@code connectFirst} が真で現在点があれば、{@code src} の最初の moveTo を lineTo にして、
     * 現在の部分パスに続ける（Canvas の {@code arc()} などの意味）。
     */
    public Path2D append(Path2D src, Matrix2D m, boolean connectFirst) {
        int p = 0;
        boolean first = true;
        for (int i = 0; i < src.opCount; i++) {
            switch (src.ops[i]) {
                case OP_MOVE -> {
                    double x = m.applyX(src.pts[p], src.pts[p + 1]);
                    double y = m.applyY(src.pts[p], src.pts[p + 1]);
                    p += 2;
                    if (first && connectFirst && opCount > 0) lineTo(x, y);
                    else moveTo(x, y);
                }
                case OP_LINE -> {
                    lineTo(m.applyX(src.pts[p], src.pts[p + 1]), m.applyY(src.pts[p], src.pts[p + 1]));
                    p += 2;
                }
                case OP_QUAD -> {
                    quadTo(m.applyX(src.pts[p], src.pts[p + 1]), m.applyY(src.pts[p], src.pts[p + 1]),
                            m.applyX(src.pts[p + 2], src.pts[p + 3]), m.applyY(src.pts[p + 2], src.pts[p + 3]));
                    p += 4;
                }
                case OP_CUBIC -> {
                    cubicTo(m.applyX(src.pts[p], src.pts[p + 1]), m.applyY(src.pts[p], src.pts[p + 1]),
                            m.applyX(src.pts[p + 2], src.pts[p + 3]), m.applyY(src.pts[p + 2], src.pts[p + 3]),
                            m.applyX(src.pts[p + 4], src.pts[p + 5]), m.applyY(src.pts[p + 4], src.pts[p + 5]));
                    p += 6;
                }
                default -> closePath();
            }
            first = false;
        }
        return this;
    }

    /** 変換した複製。 */
    public Path2D transformed(Matrix2D m) {
        return new Path2D().append(this, m);
    }

    // ------------------------------------------------------------------ 平坦化

    /**
     * 折れ線に直す。{@code m} で変換した座標で返し、曲線は {@code tolerance}（変換後の長さ）以内の誤差で近似する。
     * 長さ 0 の部分パス（{@code M} だけ・{@code M Z}）も 1 点の折れ線として残す（太さのある線の丸い端点・四角い端点のため）。
     */
    public List<Polyline> flatten(Matrix2D m, double tolerance) {
        List<Polyline> out = new ArrayList<>();
        double tol = Math.max(1e-4, tolerance);
        double[] buf = new double[64];
        int n = 0;
        int p = 0;
        double cx = 0;
        double cy = 0;
        boolean open = false;
        for (int i = 0; i < opCount; i++) {
            switch (ops[i]) {
                case OP_MOVE -> {
                    if (open) out.add(new Polyline(Arrays.copyOf(buf, n * 2), n, false));
                    cx = m.applyX(pts[p], pts[p + 1]);
                    cy = m.applyY(pts[p], pts[p + 1]);
                    p += 2;
                    n = 0;
                    buf = ensure(buf, n + 1);
                    buf[n * 2] = cx;
                    buf[n * 2 + 1] = cy;
                    n++;
                    open = true;
                }
                case OP_LINE -> {
                    cx = m.applyX(pts[p], pts[p + 1]);
                    cy = m.applyY(pts[p], pts[p + 1]);
                    p += 2;
                    buf = ensure(buf, n + 1);
                    buf[n * 2] = cx;
                    buf[n * 2 + 1] = cy;
                    n++;
                }
                case OP_QUAD -> {
                    double c1x = m.applyX(pts[p], pts[p + 1]);
                    double c1y = m.applyY(pts[p], pts[p + 1]);
                    double ex = m.applyX(pts[p + 2], pts[p + 3]);
                    double ey = m.applyY(pts[p + 2], pts[p + 3]);
                    p += 4;
                    double ddx = cx - 2 * c1x + ex;
                    double ddy = cy - 2 * c1y + ey;
                    int segs = clampSegments(Math.sqrt(Math.hypot(ddx, ddy) / (4 * tol)));
                    buf = ensure(buf, n + segs);
                    for (int s = 1; s <= segs; s++) {
                        double t = (double) s / segs;
                        double u = 1 - t;
                        buf[n * 2] = u * u * cx + 2 * u * t * c1x + t * t * ex;
                        buf[n * 2 + 1] = u * u * cy + 2 * u * t * c1y + t * t * ey;
                        n++;
                    }
                    cx = ex;
                    cy = ey;
                }
                case OP_CUBIC -> {
                    double c1x = m.applyX(pts[p], pts[p + 1]);
                    double c1y = m.applyY(pts[p], pts[p + 1]);
                    double c2x = m.applyX(pts[p + 2], pts[p + 3]);
                    double c2y = m.applyY(pts[p + 2], pts[p + 3]);
                    double ex = m.applyX(pts[p + 4], pts[p + 5]);
                    double ey = m.applyY(pts[p + 4], pts[p + 5]);
                    p += 6;
                    double d0 = Math.hypot(cx - 2 * c1x + c2x, cy - 2 * c1y + c2y);
                    double d1 = Math.hypot(c1x - 2 * c2x + ex, c1y - 2 * c2y + ey);
                    int segs = clampSegments(Math.sqrt(3 * Math.max(d0, d1) / (4 * tol)));
                    buf = ensure(buf, n + segs);
                    for (int s = 1; s <= segs; s++) {
                        double t = (double) s / segs;
                        double u = 1 - t;
                        double b0 = u * u * u;
                        double b1 = 3 * u * u * t;
                        double b2 = 3 * u * t * t;
                        double b3 = t * t * t;
                        buf[n * 2] = b0 * cx + b1 * c1x + b2 * c2x + b3 * ex;
                        buf[n * 2 + 1] = b0 * cy + b1 * c1y + b2 * c2y + b3 * ey;
                        n++;
                    }
                    cx = ex;
                    cy = ey;
                }
                default -> {
                    if (open) out.add(new Polyline(Arrays.copyOf(buf, n * 2), n, true));
                    open = false;
                    n = 0;
                }
            }
        }
        if (open) out.add(new Polyline(Arrays.copyOf(buf, n * 2), n, false));
        return out;
    }

    private static int clampSegments(double v) {
        if (!(v >= 1)) return 1;
        return (int) Math.min(200, Math.ceil(v));
    }

    private static double[] ensure(double[] buf, int points) {
        return buf.length >= points * 2 ? buf : Arrays.copyOf(buf, Math.max(buf.length * 2, points * 2));
    }

    /** 外接矩形（{@code minX, minY, maxX, maxY}）。空なら {@code null}。 */
    public double[] bounds(Matrix2D m) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (Polyline pl : flatten(m, 0.05)) {
            for (int i = 0; i < pl.count(); i++) {
                minX = Math.min(minX, pl.x(i));
                maxX = Math.max(maxX, pl.x(i));
                minY = Math.min(minY, pl.y(i));
                maxY = Math.max(maxY, pl.y(i));
            }
        }
        return minX > maxX ? null : new double[]{minX, minY, maxX, maxY};
    }

    // ------------------------------------------------------------------ 内部

    private void pushOp(byte op) {
        if (opCount == ops.length) ops = Arrays.copyOf(ops, opCount * 2);
        ops[opCount++] = op;
    }

    private void pushPt(double x, double y) {
        if (ptCount + 2 > pts.length) pts = Arrays.copyOf(pts, pts.length * 2);
        pts[ptCount++] = x;
        pts[ptCount++] = y;
    }

    private static boolean finite(double a, double b) {
        return Double.isFinite(a) && Double.isFinite(b);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "Path2D[%d ops]", opCount);
    }
}
