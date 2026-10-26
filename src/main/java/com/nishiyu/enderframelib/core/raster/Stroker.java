package com.nishiyu.enderframelib.core.raster;

import java.util.ArrayList;
import java.util.List;

/**
 * 折れ線を太さのある線にした多角形の群へ変える（Canvas 2D の {@code stroke()} と SVG の {@code stroke}）。
 *
 * 出力は、線分ごとの矩形・結合部・端の多角形を、すべて同じ向き（正の面積）にそろえたもの。
 * これを nonzero で塗れば和集合になる。線の太さは入力の座標系（ユーザー空間）の長さなので、
 * 呼び出し側は、非一様な拡大でも正しく太るよう、ユーザー空間で作ってから変換する。
 */
public final class Stroker {

    public enum Cap { BUTT, ROUND, SQUARE }

    public enum Join { MITER, ROUND, BEVEL }

    private Stroker() {
    }

    /**
     * @param dash       破線の配列（偶数長で、各要素は 0 以上。空なら実線）
     * @param dashOffset 破線の開始位置
     * @param arcTol     丸みを多角形にするときの誤差の上限（ユーザー空間の長さ）
     */
    public static List<Path2D.Polyline> stroke(List<Path2D.Polyline> lines, double width, Cap cap, Join join,
                                               double miterLimit, double[] dash, double dashOffset, double arcTol) {
        List<Path2D.Polyline> out = new ArrayList<>();
        if (!(width > 0) || !Double.isFinite(width)) return out;
        double hw = width / 2;
        double tol = Math.max(1e-4, arcTol);
        List<Path2D.Polyline> source = lines;
        if (dash != null && dash.length > 0 && isValidDash(dash)) source = applyDash(lines, dash, dashOffset);
        for (Path2D.Polyline line : source) strokeOne(line, hw, cap, join, miterLimit, tol, out);
        return out;
    }

    private static boolean isValidDash(double[] dash) {
        double sum = 0;
        for (double d : dash) {
            if (!(d >= 0) || !Double.isFinite(d)) return false;
            sum += d;
        }
        return sum > 0;
    }

    // ------------------------------------------------------------------ 破線

    private static List<Path2D.Polyline> applyDash(List<Path2D.Polyline> lines, double[] dashIn, double offset) {
        double[] dash = dashIn.length % 2 == 0 ? dashIn : concat(dashIn, dashIn);
        double total = 0;
        for (double d : dash) total += d;
        List<Path2D.Polyline> out = new ArrayList<>();
        for (Path2D.Polyline line : lines) {
            int c = line.count();
            if (c < 2) continue;
            int segs = line.closed() ? c : c - 1;
            int idx = 0;
            double rem;
            boolean on = true;
            double o = offset % total;
            if (o < 0) o += total;
            while (o >= dash[idx]) {
                o -= dash[idx];
                idx = (idx + 1) % dash.length;
                on = !on;
                if (dash[idx] == 0 && o == 0) break;
            }
            rem = dash[idx] - o;
            List<double[]> cur = new ArrayList<>();
            if (on) cur.add(new double[]{line.x(0), line.y(0)});
            int guard = 0;
            for (int s = 0; s < segs; s++) {
                int j = (s + 1) % c;
                double x0 = line.x(s);
                double y0 = line.y(s);
                double dx = line.x(j) - x0;
                double dy = line.y(j) - y0;
                double len = Math.hypot(dx, dy);
                if (len == 0) continue;
                double pos = 0;
                while (len - pos > rem) {
                    if (++guard > 200000) return out;
                    pos += rem;
                    double px = x0 + dx * pos / len;
                    double py = y0 + dy * pos / len;
                    if (on) {
                        cur.add(new double[]{px, py});
                        out.add(toPolyline(cur));
                        cur = new ArrayList<>();
                    } else {
                        cur = new ArrayList<>();
                        cur.add(new double[]{px, py});
                    }
                    on = !on;
                    idx = (idx + 1) % dash.length;
                    rem = dash[idx];
                }
                rem -= len - pos;
                if (on) cur.add(new double[]{line.x(j), line.y(j)});
            }
            if (on && cur.size() >= 1) out.add(toPolyline(cur));
        }
        return out;
    }

    private static double[] concat(double[] a, double[] b) {
        double[] r = new double[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static Path2D.Polyline toPolyline(List<double[]> pts) {
        double[] xy = new double[pts.size() * 2];
        for (int i = 0; i < pts.size(); i++) {
            xy[i * 2] = pts.get(i)[0];
            xy[i * 2 + 1] = pts.get(i)[1];
        }
        return new Path2D.Polyline(xy, pts.size(), false);
    }

    // ------------------------------------------------------------------ 1 本の線

    private static void strokeOne(Path2D.Polyline line, double hw, Cap cap, Join join, double miterLimit,
                                  double tol, List<Path2D.Polyline> out) {
        // 連続する同一点を除く
        int c = line.count();
        double[] px = new double[c];
        double[] py = new double[c];
        int n = 0;
        for (int i = 0; i < c; i++) {
            double x = line.x(i);
            double y = line.y(i);
            if (n > 0 && x == px[n - 1] && y == py[n - 1]) continue;
            px[n] = x;
            py[n] = y;
            n++;
        }
        boolean closed = line.closed();
        if (closed && n > 1 && px[0] == px[n - 1] && py[0] == py[n - 1]) n--;
        if (n == 0) return;
        if (n == 1) {
            // 長さ 0 の線: 丸い端点・四角い端点だけが点を描く
            if (cap == Cap.ROUND) {
                out.add(circle(px[0], py[0], hw, tol));
            } else if (cap == Cap.SQUARE) {
                out.add(poly(px[0] - hw, py[0] - hw, px[0] + hw, py[0] - hw, px[0] + hw, py[0] + hw, px[0] - hw, py[0] + hw));
            }
            return;
        }
        if (n == 2) closed = false;
        int segs = closed ? n : n - 1;
        for (int s = 0; s < segs; s++) {
            int j = (s + 1) % n;
            double dx = px[j] - px[s];
            double dy = py[j] - py[s];
            double len = Math.hypot(dx, dy);
            double nx = -dy / len * hw;
            double ny = dx / len * hw;
            out.add(poly(px[s] + nx, py[s] + ny, px[j] + nx, py[j] + ny, px[j] - nx, py[j] - ny, px[s] - nx, py[s] - ny));
        }
        int first = closed ? 0 : 1;
        int last = closed ? n - 1 : n - 2;
        for (int v = first; v <= last; v++) {
            int prev = (v - 1 + n) % n;
            int nxt = (v + 1) % n;
            addJoin(px[prev], py[prev], px[v], py[v], px[nxt], py[nxt], hw, join, miterLimit, tol, out);
        }
        if (!closed) {
            addCap(px[1], py[1], px[0], py[0], hw, cap, tol, out);
            addCap(px[n - 2], py[n - 2], px[n - 1], py[n - 1], hw, cap, tol, out);
        }
    }

    /** 内側の点 {@code (fx, fy)} から端の点 {@code (tx, ty)} へ向かう線の、端 {@code (tx, ty)} の端点（外向きは端 - 内側）。 */
    private static void addCap(double fx, double fy, double tx, double ty, double hw, Cap cap, double tol,
                               List<Path2D.Polyline> out) {
        if (cap == Cap.BUTT) return;
        double dx = tx - fx;
        double dy = ty - fy;
        double len = Math.hypot(dx, dy);
        if (len == 0) return;
        double ux = dx / len;
        double uy = dy / len;
        double nx = -uy * hw;
        double ny = ux * hw;
        if (cap == Cap.SQUARE) {
            out.add(poly(tx + nx, ty + ny, tx + nx + ux * hw, ty + ny + uy * hw,
                    tx - nx + ux * hw, ty - ny + uy * hw, tx - nx, ty - ny));
        } else {
            // 半円: 法線 (nx, ny) の向きから、外向き (ux, uy) を通って、逆向きの法線まで（角度は減る向き）
            double a0 = Math.atan2(ny, nx);
            int steps = arcSteps(hw, Math.PI, tol);
            double[] pts = new double[(steps + 2) * 2];
            pts[0] = tx;
            pts[1] = ty;
            for (int i = 0; i <= steps; i++) {
                double a = a0 - Math.PI * i / steps;
                pts[2 + i * 2] = tx + Math.cos(a) * hw;
                pts[3 + i * 2] = ty + Math.sin(a) * hw;
            }
            out.add(oriented(pts));
        }
    }

    private static void addJoin(double ax, double ay, double bx, double by, double cx, double cy, double hw,
                                Join join, double miterLimit, double tol, List<Path2D.Polyline> out) {
        double d1x = bx - ax;
        double d1y = by - ay;
        double d2x = cx - bx;
        double d2y = cy - by;
        double l1 = Math.hypot(d1x, d1y);
        double l2 = Math.hypot(d2x, d2y);
        if (l1 == 0 || l2 == 0) return;
        d1x /= l1;
        d1y /= l1;
        d2x /= l2;
        d2y /= l2;
        double cross = d1x * d2y - d1y * d2x;
        double dot = d1x * d2x + d1y * d2y;
        if (Math.abs(cross) < 1e-9 && dot > 0) return;
        // 外側は、曲がる向きの反対側
        double side = cross > 0 ? -1 : 1;
        double n1x = -d1y * hw * side;
        double n1y = d1x * hw * side;
        double n2x = -d2y * hw * side;
        double n2y = d2x * hw * side;
        if (Math.abs(cross) < 1e-9) {
            // 真後ろへ折り返す（dot < 0）: 丸い結合だけ半円を足す。ほかは何も足さない
            if (join == Join.ROUND) out.add(circle(bx, by, hw, tol));
            return;
        }
        switch (join) {
            case ROUND -> {
                double a0 = Math.atan2(n1y, n1x);
                double a1 = Math.atan2(n2y, n2x);
                double delta = a1 - a0;
                while (delta > Math.PI) delta -= Math.PI * 2;
                while (delta < -Math.PI) delta += Math.PI * 2;
                int steps = arcSteps(hw, Math.abs(delta), tol);
                double[] pts = new double[(steps + 2) * 2];
                pts[0] = bx;
                pts[1] = by;
                for (int i = 0; i <= steps; i++) {
                    double a = a0 + delta * i / steps;
                    pts[2 + i * 2] = bx + Math.cos(a) * hw;
                    pts[3 + i * 2] = by + Math.sin(a) * hw;
                }
                out.add(oriented(pts));
            }
            case MITER -> {
                // マイター長 / 太さ = 1 / sin(θ/2)（θ は 2 本の線の内角）
                double cosTheta = -dot;
                double sinHalf = Math.sqrt(Math.max(0, (1 - cosTheta) / 2));
                if (sinHalf > 1e-9 && 1 / sinHalf <= miterLimit) {
                    double mx = n1x + n2x;
                    double my = n1y + n2y;
                    double mlen2 = mx * mx + my * my;
                    double k = 2 * hw * hw / mlen2;
                    out.add(poly(bx, by, bx + n1x, by + n1y, bx + mx * k, by + my * k, bx + n2x, by + n2y));
                } else {
                    out.add(poly(bx, by, bx + n1x, by + n1y, bx + n2x, by + n2y));
                }
            }
            default -> out.add(poly(bx, by, bx + n1x, by + n1y, bx + n2x, by + n2y));
        }
    }

    private static int arcSteps(double r, double angle, double tol) {
        double step = r <= tol ? Math.PI / 2 : 2 * Math.acos(1 - tol / r);
        if (!(step > 0.05)) step = 0.05;
        return Math.max(2, (int) Math.ceil(Math.abs(angle) / step));
    }

    private static Path2D.Polyline circle(double cx, double cy, double r, double tol) {
        int steps = Math.max(8, arcSteps(r, Math.PI * 2, tol));
        double[] pts = new double[steps * 2];
        for (int i = 0; i < steps; i++) {
            double a = Math.PI * 2 * i / steps;
            pts[i * 2] = cx + Math.cos(a) * r;
            pts[i * 2 + 1] = cy + Math.sin(a) * r;
        }
        return oriented(pts);
    }

    private static Path2D.Polyline poly(double... xy) {
        return oriented(xy);
    }

    /** 面積が正になる向きにそろえた閉じた多角形。 */
    private static Path2D.Polyline oriented(double[] xy) {
        int n = xy.length / 2;
        double area = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            area += xy[i * 2] * xy[j * 2 + 1] - xy[j * 2] * xy[i * 2 + 1];
        }
        if (area < 0) {
            double[] r = new double[xy.length];
            for (int i = 0; i < n; i++) {
                r[i * 2] = xy[(n - 1 - i) * 2];
                r[i * 2 + 1] = xy[(n - 1 - i) * 2 + 1];
            }
            xy = r;
        }
        return new Path2D.Polyline(xy, n, true);
    }
}
