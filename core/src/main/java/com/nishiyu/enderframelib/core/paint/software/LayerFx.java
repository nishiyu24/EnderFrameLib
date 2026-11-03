package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.core.paint.ClipShape;
import com.nishiyu.enderframelib.core.paint.FilterOp;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import com.nishiyu.enderframelib.core.raster.Pixmap;

import java.util.ArrayList;
import java.util.List;

/**
 * 層（{@link Pixmap}）に {@code filter} と {@code mask} を掛ける CPU 版。GPU 版（{@code LwjglGlRenderBackend} の層の合成）と
 * 同じ式・同じ段の分け方で、ぼかしは「標本の間隔 {@code max(1, σ/12)}・片側 {@code min(40, ceil(3σ/間隔))} 点」の
 * 正規分布の重み（間は線形補間、描画先の縁は端の画素を伸ばす）。
 *
 * <p>画素は事前乗算の ARGB。色の行列は、アルファで割った（ストレートな）色に掛ける。
 */
final class LayerFx {
    private LayerFx() {}

    /** 領域 {@code {x0, y0, x1, y1}}（x1・y1 は含まない）に、浮動小数点の事前乗算 RGBA（a, r, g, b の順）を持つ作業面。 */
    private static final class Buf {
        final int x0, y0, x1, y1;
        final float[] d;

        Buf(int x0, int y0, int x1, int y1) {
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            this.d = new float[Math.max(0, (x1 - x0) * (y1 - y0)) * 4];
        }

        int w() {
            return x1 - x0;
        }
    }

    private enum Kind { COLOR, BLUR, DROP }

    private static final class Stage {
        Kind kind;
        float[] matrix;
        float opacity = 1f;
        float sigma, dx, dy;
        int color;
    }

    /** 色の行列と {@code opacity()} の連続は 1 段に畳む。 */
    private static List<Stage> stages(List<FilterOp> ops) {
        List<Stage> out = new ArrayList<>();
        for (FilterOp op : ops) {
            switch (op.kind()) {
                case COLOR_MATRIX, OPACITY -> {
                    Stage s = out.isEmpty() || out.get(out.size() - 1).kind != Kind.COLOR ? null : out.get(out.size() - 1);
                    if (s == null) {
                        s = new Stage();
                        s.kind = Kind.COLOR;
                        out.add(s);
                    }
                    if (op.matrix() == null) { // OPACITY
                        s.opacity *= op.amount();
                    } else {
                        s.matrix = s.matrix == null ? op.matrix() : FilterOp.optimize(List.of(FilterOp.color(s.matrix), FilterOp.color(op.matrix()))).get(0).matrix();
                    }
                }
                case BLUR -> {
                    Stage s = new Stage();
                    s.kind = Kind.BLUR;
                    s.sigma = op.amount();
                    out.add(s);
                }
                case DROP_SHADOW -> {
                    Stage s = new Stage();
                    s.kind = Kind.DROP;
                    s.sigma = op.amount();
                    s.dx = op.dx();
                    s.dy = op.dy();
                    s.color = op.color();
                    out.add(s);
                }
            }
        }
        return out;
    }

    /**
     * {@code region} の範囲に描いた層 {@code pm} へ {@code ops} を順に掛け、結果を層に書き戻す。
     * ぼかしや影で広がった領域を返す（描画先の外へは出ない）。{@code map} は錨の姿勢の線形部 {@code {a, b, c, d}}。
     */
    static int[] filter(Pixmap pm, int[] region, List<FilterOp> ops, double[] map) {
        int w = pm.width(), h = pm.height();
        Buf cur = new Buf(Math.max(0, region[0]), Math.max(0, region[1]), Math.min(w, region[2]), Math.min(h, region[3]));
        int[] px = pm.pixels();
        for (int y = cur.y0; y < cur.y1; y++) {
            for (int x = cur.x0; x < cur.x1; x++) unpack(px[y * w + x], cur.d, ((y - cur.y0) * cur.w() + (x - cur.x0)) * 4);
        }
        for (Stage st : stages(ops)) {
            switch (st.kind) {
                case COLOR -> color(cur, st);
                case BLUR -> cur = blur(cur, st.sigma, map, w, h);
                case DROP -> cur = drop(cur, st, map, w, h);
            }
        }
        // 領域が広がったぶんも含めて書き戻す
        for (int y = cur.y0; y < cur.y1; y++) {
            for (int x = cur.x0; x < cur.x1; x++) px[y * w + x] = pack(cur.d, ((y - cur.y0) * cur.w() + (x - cur.x0)) * 4);
        }
        return new int[]{cur.x0, cur.y0, cur.x1, cur.y1};
    }

    private static void unpack(int c, float[] d, int i) {
        d[i] = (c >>> 24) / 255f;
        d[i + 1] = ((c >> 16) & 0xFF) / 255f;
        d[i + 2] = ((c >> 8) & 0xFF) / 255f;
        d[i + 3] = (c & 0xFF) / 255f;
    }

    private static int pack(float[] d, int i) {
        int a = clamp255(d[i]);
        return (a << 24) | (Math.min(a, clamp255(d[i + 1])) << 16) | (Math.min(a, clamp255(d[i + 2])) << 8) | Math.min(a, clamp255(d[i + 3]));
    }

    private static int clamp255(float v) {
        return v <= 0f ? 0 : v >= 1f ? 255 : Math.round(v * 255f);
    }

    private static void color(Buf b, Stage st) {
        float[] m = st.matrix;
        for (int i = 0; i < b.d.length; i += 4) {
            float a = b.d[i];
            if (a <= 0f) continue;
            if (m != null) {
                float r = b.d[i + 1] / a, g = b.d[i + 2] / a, bl = b.d[i + 3] / a;
                float nr = clamp01(m[0] * r + m[1] * g + m[2] * bl + m[3]);
                float ng = clamp01(m[4] * r + m[5] * g + m[6] * bl + m[7]);
                float nb = clamp01(m[8] * r + m[9] * g + m[10] * bl + m[11]);
                b.d[i + 1] = nr * a;
                b.d[i + 2] = ng * a;
                b.d[i + 3] = nb * a;
            }
            if (st.opacity != 1f) {
                b.d[i] *= st.opacity;
                b.d[i + 1] *= st.opacity;
                b.d[i + 2] *= st.opacity;
                b.d[i + 3] *= st.opacity;
            }
        }
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }

    /** 画素 {@code (x, y)} のチャンネル {@code c}。描画先の外は端の画素、作業面の外は 0。 */
    private static float at(Buf b, int x, int y, int c, int w, int h) {
        x = Math.max(0, Math.min(w - 1, x));
        y = Math.max(0, Math.min(h - 1, y));
        if (x < b.x0 || x >= b.x1 || y < b.y0 || y >= b.y1) return 0f;
        return b.d[((y - b.y0) * b.w() + (x - b.x0)) * 4 + c];
    }

    private static Buf blur(Buf in, float sigma, double[] m, int w, int h) {
        float sx = sigma * (float) Math.hypot(m[0], m[1]);
        float sy = sigma * (float) Math.hypot(m[2], m[3]);
        if (sx < 0.05f && sy < 0.05f) return in;
        Buf mid = pass(in, sx, true, w, h);
        return pass(mid, sy, false, w, h);
    }

    /** この σ（画素）以上は、ガウスの重みを直に畳む代わりに 3 回のボックスぼかしで近似する（Filter Effects §ぼかし が認める近似）。 */
    private static final float BOX_SIGMA = 12f;

    private static Buf pass(Buf in, float sigma, boolean horizontal, int w, int h) {
        if (sigma < 0.05f) return in;
        if (sigma >= BOX_SIGMA) return boxPass(in, sigma, horizontal, w, h);
        float step = Math.max(1f, sigma / 12f);
        int taps = (int) Math.min(40, Math.ceil(3f * sigma / step));
        int reach = (int) Math.ceil(taps * step) + 1;
        Buf out = horizontal
                ? new Buf(Math.max(0, in.x0 - reach), in.y0, Math.min(w, in.x1 + reach), in.y1)
                : new Buf(in.x0, Math.max(0, in.y0 - reach), in.x1, Math.min(h, in.y1 + reach));
        float[] wt = new float[taps + 1];
        float wsum = 1f;
        wt[0] = 1f;
        for (int i = 1; i <= taps; i++) {
            float d = i * step;
            wt[i] = (float) Math.exp(-0.5 * d * d / (sigma * sigma));
            wsum += 2f * wt[i];
        }
        for (int y = out.y0; y < out.y1; y++) {
            for (int x = out.x0; x < out.x1; x++) {
                int o = ((y - out.y0) * out.w() + (x - out.x0)) * 4;
                for (int c = 0; c < 4; c++) {
                    float sum = at(in, x, y, c, w, h);
                    for (int i = 1; i <= taps; i++) {
                        float d = i * step;
                        sum += wt[i] * (line(in, x, y, d, horizontal, c, w, h) + line(in, x, y, -d, horizontal, c, w, h));
                    }
                    out.d[o + c] = sum / wsum;
                }
            }
        }
        return out;
    }

    /**
     * σ のガウスぼかしを、幅 d のボックスぼかし 3 回（d = floor(σ·3√(2π)/4 + ½)。偶数なら幅 d を 2 回ずらして 1 回は d+1）で近似する。
     * 1 画素あたりの手数が σ に依らない（走る和）。作業面の外は透明として扱う。
     */
    private static Buf boxPass(Buf in, float sigma, boolean horizontal, int w, int h) {
        int d = (int) Math.floor(sigma * 3.0 * Math.sqrt(2 * Math.PI) / 4.0 + 0.5);
        if (d < 2) return in;
        // 各回の (左へ, 右へ) の広がり。奇数は対称、偶数は片側へずらした 2 回と、反対側へずらした d+1 の 1 回
        int[][] spans = d % 2 == 1
                ? new int[][]{{d / 2, d / 2}, {d / 2, d / 2}, {d / 2, d / 2}}
                : new int[][]{{d / 2, d / 2 - 1}, {d / 2 - 1, d / 2}, {d / 2, d / 2}};
        int[] widths = d % 2 == 1 ? new int[]{d, d, d} : new int[]{d, d, d + 1};
        Buf cur = in;
        for (int k = 0; k < 3; k++) cur = box(cur, spans[k][0], spans[k][1], widths[k], horizontal, w, h);
        return cur;
    }

    private static Buf box(Buf in, int left, int right, int width, boolean horizontal, int w, int h) {
        // 出力 A の値は、入力の [A - right, A + left] の和 / width。非ゼロになるのは [in 始点 - left, in 終点 + right)
        Buf out = horizontal
                ? new Buf(Math.max(0, in.x0 - left), in.y0, Math.min(w, in.x1 + right), in.y1)
                : new Buf(in.x0, Math.max(0, in.y0 - left), in.x1, Math.min(h, in.y1 + right));
        float inv = 1f / width;
        int inW = in.w(), outW = out.w();
        int lines = horizontal ? out.y1 - out.y0 : out.x1 - out.x0;
        int len = horizontal ? out.x1 - out.x0 : out.y1 - out.y0;
        int inLen = horizontal ? in.x1 - in.x0 : in.y1 - in.y0;
        int inOrigin = horizontal ? in.x0 : in.y0, outOrigin = horizontal ? out.x0 : out.y0;
        for (int ln = 0; ln < lines; ln++) {
            float sa = 0, sr = 0, sg = 0, sb = 0;
            // 出力 -1 の窓 [outOrigin-1-right, outOrigin-1+left] の和を先に作る
            for (int a = outOrigin - 1 - right; a <= outOrigin - 1 + left; a++) {
                int k = a - inOrigin;
                if (k < 0 || k >= inLen) continue;
                int i = horizontal ? (ln * inW + k) * 4 : (k * inW + ln) * 4;
                sa += in.d[i]; sr += in.d[i + 1]; sg += in.d[i + 2]; sb += in.d[i + 3];
            }
            for (int p = 0; p < len; p++) {
                int addIn = outOrigin + p + left - inOrigin;      // 窓の右端へ入る画素
                int subIn = outOrigin + p - 1 - right - inOrigin; // 窓の左端から出る画素
                if (addIn >= 0 && addIn < inLen) {
                    int i = horizontal ? (ln * inW + addIn) * 4 : (addIn * inW + ln) * 4;
                    sa += in.d[i]; sr += in.d[i + 1]; sg += in.d[i + 2]; sb += in.d[i + 3];
                }
                if (subIn >= 0 && subIn < inLen) {
                    int i = horizontal ? (ln * inW + subIn) * 4 : (subIn * inW + ln) * 4;
                    sa -= in.d[i]; sr -= in.d[i + 1]; sg -= in.d[i + 2]; sb -= in.d[i + 3];
                }
                int o = horizontal ? (ln * outW + p) * 4 : (p * outW + ln) * 4;
                out.d[o] = sa * inv; out.d[o + 1] = sr * inv; out.d[o + 2] = sg * inv; out.d[o + 3] = sb * inv;
            }
        }
        return out;
    }

    /** 軸に沿って {@code d} 画素ずれた位置の線形補間。 */
    private static float line(Buf b, int x, int y, float d, boolean horizontal, int c, int w, int h) {
        int lo = (int) Math.floor(d);
        float f = d - lo;
        if (horizontal) {
            return f == 0f ? at(b, x + lo, y, c, w, h) : at(b, x + lo, y, c, w, h) * (1 - f) + at(b, x + lo + 1, y, c, w, h) * f;
        }
        return f == 0f ? at(b, x, y + lo, c, w, h) : at(b, x, y + lo, c, w, h) * (1 - f) + at(b, x, y + lo + 1, c, w, h) * f;
    }

    /** 影を落とす。ぼかした層のアルファを、箱の座標のずれ（画素に直したもの）だけずらして色を付け、元の層の下に敷く。 */
    private static Buf drop(Buf in, Stage st, double[] m, int w, int h) {
        Buf blurred = blur(in, st.sigma, m, w, h);
        float ox = (float) (m[0] * st.dx + m[2] * st.dy);
        float oy = (float) (m[1] * st.dx + m[3] * st.dy);
        int ex = (int) Math.ceil(Math.abs(ox)) + 1, ey = (int) Math.ceil(Math.abs(oy)) + 1;
        int bx0 = Math.min(in.x0, blurred.x0), by0 = Math.min(in.y0, blurred.y0);
        int bx1 = Math.max(in.x1, blurred.x1), by1 = Math.max(in.y1, blurred.y1);
        Buf out = new Buf(Math.max(0, bx0 - ex), Math.max(0, by0 - ey), Math.min(w, bx1 + ex), Math.min(h, by1 + ey));
        float ca = ((st.color >>> 24) & 0xFF) / 255f, cr = ((st.color >>> 16) & 0xFF) / 255f;
        float cg = ((st.color >>> 8) & 0xFF) / 255f, cb = (st.color & 0xFF) / 255f;
        for (int y = out.y0; y < out.y1; y++) {
            for (int x = out.x0; x < out.x1; x++) {
                int o = ((y - out.y0) * out.w() + (x - out.x0)) * 4;
                float base = at(in, x, y, 0, w, h);
                // 影のアルファ（ずらした位置を線形補間）
                float sx = x - ox, sy = y - oy;
                int ix = (int) Math.floor(sx), iy = (int) Math.floor(sy);
                float fx = sx - ix, fy = sy - iy;
                float a00 = at(blurred, ix, iy, 0, w, h), a10 = at(blurred, ix + 1, iy, 0, w, h);
                float a01 = at(blurred, ix, iy + 1, 0, w, h), a11 = at(blurred, ix + 1, iy + 1, 0, w, h);
                float sa = ((a00 * (1 - fx) + a10 * fx) * (1 - fy) + (a01 * (1 - fx) + a11 * fx) * fy) * ca;
                float k = 1f - base;
                out.d[o] = base + sa * k;
                out.d[o + 1] = at(in, x, y, 1, w, h) + cr * sa * k;
                out.d[o + 2] = at(in, x, y, 2, w, h) + cg * sa * k;
                out.d[o + 3] = at(in, x, y, 3, w, h) + cb * sa * k;
            }
        }
        return out;
    }

    /**
     * 層 {@code pm}（領域 {@code region}）にマスク画像 {@code mask}（領域 {@code maskRegion}）を掛ける。マスクの領域の外は 0（見えない）。
     * {@code luminance} なら輝度（事前乗算の色から）、そうでなければアルファ。
     */
    static void mask(Pixmap pm, int[] region, Pixmap mask, int[] maskRegion, boolean luminance) {
        int w = pm.width();
        int[] px = pm.pixels(), mp = mask.pixels();
        for (int y = region[1]; y < region[3]; y++) {
            for (int x = region[0]; x < region[2]; x++) {
                int i = y * w + x;
                int c = px[i];
                if (c == 0) continue;
                if (x < maskRegion[0] || x >= maskRegion[2] || y < maskRegion[1] || y >= maskRegion[3]) {
                    px[i] = 0;
                    continue;
                }
                int m = mp[i];
                float k = luminance
                        ? (0.2126f * ((m >> 16) & 0xFF) + 0.7152f * ((m >> 8) & 0xFF) + 0.0722f * (m & 0xFF)) / 255f
                        : (m >>> 24) / 255f;
                px[i] = SoftShapes.scalePremul(c, k);
            }
        }
    }

    /** 箱の座標の矩形 {@code box} を、姿勢 {@code pose} でデバイスへ写した覆いの箱（整数画素・描画先に収めたもの）。 */
    static int[] deviceBounds(float[] box, Matrix2D pose, int w, int h, int pad) {
        double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            double bx = (i & 1) == 0 ? box[0] : box[2], by = (i & 2) == 0 ? box[1] : box[3];
            double dx = pose.applyX(bx, by), dy = pose.applyY(bx, by);
            x0 = Math.min(x0, dx);
            x1 = Math.max(x1, dx);
            y0 = Math.min(y0, dy);
            y1 = Math.max(y1, dy);
        }
        return new int[]{(int) Math.max(0, Math.floor(x0) - pad), (int) Math.max(0, Math.floor(y0) - pad),
                (int) Math.min(w, Math.ceil(x1) + pad), (int) Math.min(h, Math.ceil(y1) + pad)};
    }

    /** 箱の 1 単位が何画素か（GPU の {@code uShapeScale} と同じ）。 */
    static float shapeScale(Matrix2D pose) {
        return (float) Math.sqrt(Math.abs(pose.determinant()));
    }

    /** 層 {@code pm}（領域 {@code region}）を {@code clip-path} の図形で切る。図形の外は 0、縁は覆い率で薄める。 */
    static void clip(Pixmap pm, int[] region, ClipShape shape, Matrix2D pose) {
        Matrix2D inv = pose.invert();
        if (inv == null) {
            return;
        }
        float scale = shapeScale(pose);
        int w = pm.width();
        int[] px = pm.pixels();
        for (int y = region[1]; y < region[3]; y++) {
            for (int x = region[0]; x < region[2]; x++) {
                int c = px[y * w + x];
                if (c == 0) continue;
                px[y * w + x] = SoftShapes.scalePremul(c, shape.coverage(inv, scale, x, y));
            }
        }
    }

    /**
     * {@code backdrop-filter}: 親の絵 {@code parent} のうち {@code shape}（箱の角丸）の中を {@code ops} で加工して貼る。
     * 貼る強さは 形の覆い率 × {@code clip} の覆い率 × {@code opacity}。加工した絵と元の絵を、この強さで混ぜる（{@code 元 * (1-k) + 加工 * k}）。
     * {@code scratch} は全部 0 の作業用の画素（使ったあと 0 に戻す）。貼った範囲（デバイス画素）を返す。何もしなければ null。
     */
    static int[] backdrop(Pixmap parent, Pixmap scratch, List<FilterOp> ops, double[] map, Matrix2D pose,
                          ClipShape shape, ClipShape clip, float opacity) {
        Matrix2D inv = pose.invert();
        if (inv == null || opacity <= 0f) return null;
        int w = parent.width(), h = parent.height();
        int[] area = deviceBounds(shape.bounds(), pose, w, h, 1);
        if (clip != null) {
            int[] c = deviceBounds(clip.bounds(), pose, w, h, 1);
            area = new int[]{Math.max(area[0], c[0]), Math.max(area[1], c[1]), Math.min(area[2], c[2]), Math.min(area[3], c[3])};
        }
        if (area[2] <= area[0] || area[3] <= area[1]) return null;
        // 加工の入力は、ぼかしが届く範囲まで広げる
        float reach = 0f;
        for (Stage st : stages(ops)) {
            if (st.kind == Kind.BLUR || st.kind == Kind.DROP) {
                reach += 3f * st.sigma * (float) Math.max(Math.hypot(map[0], map[1]), Math.hypot(map[2], map[3]))
                        + (st.kind == Kind.DROP ? Math.abs(st.dx) + Math.abs(st.dy) : 0f);
            }
        }
        int m = (int) Math.ceil(reach) + 2;
        int[] in = {Math.max(0, area[0] - m), Math.max(0, area[1] - m), Math.min(w, area[2] + m), Math.min(h, area[3] + m)};
        int[] pp = parent.pixels(), sp = scratch.pixels();
        for (int y = in[1]; y < in[3]; y++) System.arraycopy(pp, y * w + in[0], sp, y * w + in[0], in[2] - in[0]);
        int[] out = filter(scratch, in, ops, map);
        float scale = shapeScale(pose);
        for (int y = area[1]; y < area[3]; y++) {
            for (int x = area[0]; x < area[2]; x++) {
                float k = shape.coverage(inv, scale, x, y);
                if (k <= 0f) continue;
                if (clip != null) k *= clip.coverage(inv, scale, x, y);
                k *= opacity;
                if (k <= 0f) continue;
                int i = y * w + x;
                boolean inside = x >= out[0] && x < out[2] && y >= out[1] && y < out[3];
                int f = inside ? sp[i] : 0;
                pp[i] = mix(pp[i], f, k);
            }
        }
        for (int y = out[1]; y < out[3]; y++) java.util.Arrays.fill(sp, y * w + out[0], y * w + out[2], 0);
        return area;
    }

    /** 事前乗算の ARGB を {@code a * (1-k) + b * k} で混ぜる。 */
    private static int mix(int a, int b, float k) {
        int r = 0;
        for (int s = 0; s <= 24; s += 8) {
            float v = ((a >>> s) & 0xFF) * (1f - k) + ((b >>> s) & 0xFF) * k;
            r |= clamp255(v / 255f) << s;
        }
        return r;
    }
}
