package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.core.paint.ShaderGradientEmulator;
import com.nishiyu.enderframelib.core.paint.model.ShaderGradient;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.text.font.GlyphBitmap;

/** 画素を書く命令（形の塗り・グラデーション・字形）の本体と、行ごとの画素塗りの部品。{@link SoftwareRenderBackend} は記録だけを行い、画素の書き込みはここ。 */
final class PixelOps {
    private PixelOps() {}

    /** 形の塗り（シェーダー＋近道の領域）の命令。 */
    static final class PaintOp extends Op {
        Matrix2D pose, inv;
        Clip clip;
        boolean clipped;
        float opacity;
        Shader shader;
        Fast fast;
        int nr, nRound;
        final int[] dx0 = new int[Fast.MAX], dx1 = new int[Fast.MAX], dy0 = new int[Fast.MAX], dy1 = new int[Fast.MAX], col = new int[Fast.MAX];
        final int[] roundColor = new int[Fast.MAX_ROUND];

        @Override
        void run(int ax0, int ay0, int ax1, int ay1, Scratch s) {
            boolean any = nr > 0 || nRound > 0;
            for (int y = ay0; y < ay1; y++) {
                double dy = y + 0.5;
                if (!any) {
                    paintPixels(px, w, y, dy, ax0, ax1, inv, clip, clipped, opacity, shader);
                    continue;
                }
                // この行の「塗りだけ／飛ばす」区間 [a, b) と色 c（0 は飛ばす）
                int ns = 0;
                for (int k = 0; k < nr; k++) {
                    if (y < dy0[k] || y >= dy1[k]) continue;
                    s.a[ns] = dx0[k]; s.b[ns] = dx1[k]; s.c[ns] = col[k]; ns++;
                }
                for (int k = 0; k < nRound; k++) {
                    if (roundRow(pose, inv, clip, clipped && roundColor[k] != 0, dy, ax0, ax1, fast, k, s.a, s.b, ns, s.out)) {
                        s.c[ns] = roundColor[k]; ns++;
                    }
                }
                for (int i = 1; i < ns; i++) { // 開始位置の昇順（区間は多くて数個）
                    int a = s.a[i], b = s.b[i], c = s.c[i], j = i - 1;
                    while (j >= 0 && s.a[j] > a) { s.a[j + 1] = s.a[j]; s.b[j + 1] = s.b[j]; s.c[j + 1] = s.c[j]; j--; }
                    s.a[j + 1] = a; s.b[j + 1] = b; s.c[j + 1] = c;
                }
                int cur = ax0;
                int base = y * w;
                for (int i = 0; i < ns; i++) {
                    int a = Math.max(s.a[i], cur), b = Math.min(s.b[i], ax1);
                    if (b <= a) continue;
                    if (a > cur) paintPixels(px, w, y, dy, cur, a, inv, clip, clipped, opacity, shader);
                    int c = s.c[i];
                    if (c != 0) {
                        if ((c >>> 24) == 255) java.util.Arrays.fill(px, base + a, base + b, c);
                        else for (int x = a; x < b; x++) px[base + x] = SoftShapes.over(c, px[base + x]);
                    }
                    cur = b;
                }
                if (cur < ax1) paintPixels(px, w, y, dy, cur, ax1, inv, clip, clipped, opacity, shader);
            }
        }
    }

    /** 1 行ぶんの角丸矩形の内側の画素区間を {@code a[ns], b[ns]} に書く。無ければ false。 */
    static boolean roundRow(Matrix2D pose, Matrix2D inv, Clip clip, boolean clipped, double dy, int x0, int x1, Fast fast, int k,
                                    int[] a, int[] b, int ns, double[] out) {
        double ly = inv.applyY(x0 + 0.5, dy);
        if (!fast.roundSpan(k, ly, out)) return false;
        double p = pose.applyX(out[0], 0), q = pose.applyX(out[1], 0);
        double lo = Math.min(p, q) + 1e-3, hi = Math.max(p, q) - 1e-3;
        if (clipped) {
            if (dy < clip.iy0() || dy > clip.iy1()) return false;
            lo = Math.max(lo, clip.ix0()); hi = Math.min(hi, clip.ix1());
        }
        if (hi < lo) return false;
        int s = Math.max(x0, (int) Math.ceil(lo - 0.5)), e = Math.min(x1, (int) Math.floor(hi - 0.5) + 1);
        if (e <= s) return false;
        a[ns] = s; b[ns] = e;
        return true;
    }

    static void paintPixels(int[] px, int w, int y, double dy, int xa, int xb, Matrix2D inv, Clip clip, boolean clipped,
                                    float opacity, Shader shader) {
        for (int x = xa; x < xb; x++) {
            double dx = x + 0.5;
            int c = shader.shade((float) inv.applyX(dx, dy), (float) inv.applyY(dx, dy));
            if (c == 0) continue;
            float k = opacity;
            if (clipped) {
                k *= clip.coverage(dx, dy);
                if (k <= 0f) continue;
            }
            if (k < 1f) c = SoftShapes.scalePremul(c, k);
            int i = y * w + x;
            px[i] = SoftShapes.over(c, px[i]);
        }
    }

    /** グラデーションで形を塗る命令。 */
    static final class GradientOp extends Op {
        Matrix2D inv;
        Clip clip;
        boolean clipped, axis;
        float opacity, sx0, sy0, sx1, sy1, scale, fIx0, fIx1, fIy0, fIy1;
        ShaderGradient g;
        float[] rx, ry, lxs, lys;
        int spLo, spHi;
        int[] colFinal;
        boolean hasCol;

        @Override
        void run(int ax0, int ay0, int ax1, int ay1, Scratch s) {
            ShaderGradientEmulator.Prepared shader = ShaderGradientEmulator.prepare(g); // 直前の色を覚えるのでスレッドごと
            boolean rowConst = axis && shader.constantAlongRows();
            int a = Math.max(ax0, Math.min(ax1, spLo)), b = Math.max(ax0, Math.min(ax1, spHi)); // 内側の列の範囲をタイルに収める
            for (int y = ay0; y < ay1; y++) {
                double dy = y + 0.5;
                if (axis && spHi > spLo) {
                    float lyRow = lys[y - y0];
                    if (lyRow >= fIy0 && lyRow <= fIy1 && (!clipped || (dy >= clip.iy0() && dy <= clip.iy1()))) {
                        int base = y * w;
                        paintGradientPixels(px, w, y, dy, ax0, a, inv, clip, clipped, opacity, shader, sx0, sy0, sx1, sy1, rx, ry, scale, fIx0, fIx1, fIy0, fIy1);
                        if (hasCol) {
                            for (int x = a; x < b; x++) {
                                int c = colFinal[x - spLo];
                                if (c != 0) px[base + x] = SoftShapes.over(c, px[base + x]);
                            }
                        } else if (rowConst) {
                            if (b > a) {
                                int c = spanColor(shader.colorOrZero(lxs[a - x0], lyRow), opacity);
                                if (c != 0) {
                                    if ((c >>> 24) == 255) java.util.Arrays.fill(px, base + a, base + b, c);
                                    else for (int x = a; x < b; x++) px[base + x] = SoftShapes.over(c, px[base + x]);
                                }
                            }
                        } else {
                            for (int x = a; x < b; x++) {
                                int c = spanColor(shader.colorOrZero(lxs[x - x0], lyRow), opacity);
                                if (c != 0) px[base + x] = SoftShapes.over(c, px[base + x]);
                            }
                        }
                        paintGradientPixels(px, w, y, dy, b, ax1, inv, clip, clipped, opacity, shader, sx0, sy0, sx1, sy1, rx, ry, scale, fIx0, fIx1, fIy0, fIy1);
                        continue;
                    }
                }
                paintGradientPixels(px, w, y, dy, ax0, ax1, inv, clip, clipped, opacity, shader, sx0, sy0, sx1, sy1, rx, ry, scale, fIx0, fIx1, fIy0, fIy1);
            }
        }
    }

    /** 被覆率 1・クリップ 1 の画素の最終の色（事前乗算・不透明度込み）。0 は塗らない。画素ごとの経路と同じ式。 */
    static int spanColor(int straight, float opacity) {
        if ((straight >>> 24) == 0) return 0;
        int c = SoftShapes.scalePremul(Pixmap.premultiply(straight), 1f);
        if (c == 0) return 0;
        return opacity < 1f ? SoftShapes.scalePremul(c, opacity) : c;
    }

    static void paintGradientPixels(int[] px, int w, int y, double dy, int xa, int xb, Matrix2D inv, Clip clip, boolean clipped,
                                            float opacity, ShaderGradientEmulator.Prepared shader,
                                            float x0, float y0, float x1, float y1, float[] rx, float[] ry, float scale,
                                            float ix0, float ix1, float iy0, float iy1) {
        for (int x = xa; x < xb; x++) {
            double dx = x + 0.5;
            float lx = (float) inv.applyX(dx, dy), ly = (float) inv.applyY(dx, dy);
            float cov = lx >= ix0 && lx <= ix1 && ly >= iy0 && ly <= iy1 ? 1f
                    : SoftShapes.coverage(SoftShapes.sdRoundRect(lx, ly, x0, y0, x1, y1, rx, ry), scale);
            if (cov <= 0f) continue;
            int c = shader.colorOrZero(lx, ly);
            if ((c >>> 24) == 0) continue;
            c = SoftShapes.scalePremul(Pixmap.premultiply(c), cov);
            if (c == 0) continue;
            float k = opacity;
            if (clipped) {
                k *= clip.coverage(dx, dy);
                if (k <= 0f) continue;
            }
            if (k < 1f) c = SoftShapes.scalePremul(c, k);
            int i = y * w + x;
            px[i] = SoftShapes.over(c, px[i]);
        }
    }

    /** 字形を画素の格子に貼る命令。 */
    static final class GlyphOp extends Op {
        GlyphBitmap g;
        int bx, by;
        int[] lut;
        Clip clip;
        boolean clipped;
        float opacity;

        @Override
        void run(int ax0, int ay0, int ax1, int ay1, Scratch s) {
            for (int y = ay0; y < ay1; y++) {
                double dy = y + 0.5;
                int row = y - by, base = y * w;
                for (int x = ax0; x < ax1; x++) {
                    int cov = g.coverageAt(x - bx, row);
                    if (cov == 0) continue;
                    int c = lut[cov];
                    if (c == 0) continue;
                    float k = opacity;
                    if (clipped) {
                        k *= clip.coverage(x + 0.5, dy);
                        if (k <= 0f) continue;
                    }
                    if (k < 1f) c = SoftShapes.scalePremul(c, k);
                    px[base + x] = SoftShapes.over(c, px[base + x]);
                }
            }
        }
    }

}
