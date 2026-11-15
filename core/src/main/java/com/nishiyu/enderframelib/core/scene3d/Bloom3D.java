package com.nishiyu.enderframelib.core.scene3d;

import com.nishiyu.enderframelib.core.kernel.gpu.DualKawase;

/**
 * 3D の HDR Bloom（X3-2）。GL / Vulkan のシェーダー（{@code ShaderSources.BLOOM_FRAGMENT}）と同じ式の CPU 実装で、
 * ソフトウェア描画（{@code SoftwareScene3DRasterizer}）が使い、GPU 版の照合基準にもなる。
 *
 * <p>流れ: ①HDR の画素（事前乗算の RGBA、制限なし）から、しきい値を超える分だけを取り出しつつ 1/2 へ縮小（Dual-Kawase の 5 タップ。
 * 各タップに取り出しを掛ける）②さらに 1/2 ずつ {@code levels} 段まで縮小 ③アップサンプル（8 タップ）しながら対応する段を足し戻す
 * ④結果（1/2 解像度）を双線形で拡大して元の HDR に足し、ACES で 8 bit へ。段数とタップ間隔は {@link DualKawase} の計画
 * （σ → 段数・オフセット）に従う。サンプリングは端を引き伸ばす双線形で、GPU の {@code CLAMP_TO_EDGE + LINEAR} と同じ。
 */
public final class Bloom3D {
    private Bloom3D() {}

    /** しきい値の「ひざ」（滑らかに立ち上げる幅）の、しきい値に対する比。 */
    public static final float KNEE_RATIO = 0.5f;

    /** 計画: 段数（≥ 1）とタップ間隔。 */
    public record Plan(int levels, float offset) {}

    /** 画像の短辺で上限を掛けた計画。σ は最終解像度の画素。 */
    public static Plan plan(float sigmaPx, int width, int height) {
        int p = Math.max(1, DualKawase.passes(sigmaPx));
        int maxByShortSide = 1;
        while ((Math.min(width, height) >> maxByShortSide) >= 2 && maxByShortSide < DualKawase.MAX_PASSES) maxByShortSide++;
        p = Math.min(p, maxByShortSide);
        return new Plan(p, DualKawase.offset(sigmaPx, p));
    }

    /** 1/2 解像度の RGBA（事前乗算・HDR）。 */
    public record Level(float[] rgba, int w, int h) {}

    /** 段の大きさ（GPU のテクスチャと同じ規則）。 */
    public static int half(int n) {
        return Math.max(1, (n + 1) / 2);
    }

    /** ブルーム本体（元の半分の解像度）。{@code hdr} は {@code w*h*4} の事前乗算 RGBA。 */
    public static Level bloom(float[] hdr, int w, int h, float threshold, float sigmaPx) {
        Plan plan = plan(sigmaPx, w, h);
        float knee = Math.max(1e-4f, threshold * KNEE_RATIO);
        int p = plan.levels();
        Level[] down = new Level[p + 1];
        down[0] = new Level(hdr, w, h);
        for (int i = 1; i <= p; i++) {
            Level s = down[i - 1];
            down[i] = down(s, plan.offset(), i == 1, threshold, knee);
        }
        Level cur = down[p];
        for (int i = p - 1; i >= 1; i--) cur = up(cur, down[i], plan.offset());
        return cur;
    }

    /** HDR に足して ACES で 8 bit（事前乗算 ARGB）にする。{@code intensity} はブルームの強さ。 */
    public static void composite(float[] hdr, int w, int h, Level bloom, float intensity, int[] outArgb) {
        float[] b = new float[4];
        float[] bl = bloom.rgba();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = (y * w + x) * 4;
                // GPU: uv = (画素の中心) / 描画先の大きさ を、ブルームのテクスチャへ双線形で引く
                float cx = (x + 0.5f) / w * bloom.w(), cy = (y + 0.5f) / h * bloom.h();
                bilinear(bl, bloom.w(), bloom.h(), cx, cy, b);
                float a = Math.min(1f, Math.max(0f, hdr[i + 3] + b[3] * intensity));
                float r = aces(hdr[i] + b[0] * intensity), g = aces(hdr[i + 1] + b[1] * intensity), bb = aces(hdr[i + 2] + b[2] * intensity);
                r = Math.min(r, a);
                g = Math.min(g, a);
                bb = Math.min(bb, a);
                outArgb[y * w + x] = (q(a) << 24) | (q(r) << 16) | (q(g) << 8) | q(bb);
            }
        }
    }

    /** ACES filmic（Narkowicz の近似）。 */
    public static float aces(float x) {
        if (!(x > 0f)) return 0f;
        float v = (x * (2.51f * x + 0.03f)) / (x * (2.43f * x + 0.59f) + 0.14f);
        return Math.min(1f, v);
    }

    private static int q(float v) {
        return v <= 0f ? 0 : v >= 1f ? 255 : Math.round(v * 255f);
    }

    // ------------------------------------------------------------------ Dual-Kawase（DualKawase の参照実装と同じタップ。4 チャンネル）

    private static Level down(Level s, float o, boolean prefilter, float threshold, float knee) {
        int dw = half(s.w()), dh = half(s.h());
        float[] d = new float[dw * dh * 4];
        float[][] t = new float[5][4];
        for (int y = 0; y < dh; y++) {
            for (int x = 0; x < dw; x++) {
                float cx = (x + 0.5f) * 2f, cy = (y + 0.5f) * 2f;
                bilinear(s.rgba(), s.w(), s.h(), cx, cy, t[0]);
                bilinear(s.rgba(), s.w(), s.h(), cx - o, cy - o, t[1]);
                bilinear(s.rgba(), s.w(), s.h(), cx + o, cy - o, t[2]);
                bilinear(s.rgba(), s.w(), s.h(), cx - o, cy + o, t[3]);
                bilinear(s.rgba(), s.w(), s.h(), cx + o, cy + o, t[4]);
                if (prefilter) for (float[] v : t) prefilter(v, threshold, knee);
                int i = (y * dw + x) * 4;
                for (int c = 0; c < 4; c++) d[i + c] = (t[0][c] * 4f + t[1][c] + t[2][c] + t[3][c] + t[4][c]) / 8f;
            }
        }
        return new Level(d, dw, dh);
    }

    /** {@code s} を {@code target} の大きさへ広げ、{@code target} を足し戻す。 */
    private static Level up(Level s, Level target, float o) {
        int dw = target.w(), dh = target.h();
        float[] d = new float[dw * dh * 4];
        float[][] t = new float[8][4];
        float q = o * 0.5f;
        for (int y = 0; y < dh; y++) {
            for (int x = 0; x < dw; x++) {
                float cx = (x + 0.5f) / 2f, cy = (y + 0.5f) / 2f;
                bilinear(s.rgba(), s.w(), s.h(), cx - o, cy, t[0]);
                bilinear(s.rgba(), s.w(), s.h(), cx + o, cy, t[1]);
                bilinear(s.rgba(), s.w(), s.h(), cx, cy - o, t[2]);
                bilinear(s.rgba(), s.w(), s.h(), cx, cy + o, t[3]);
                bilinear(s.rgba(), s.w(), s.h(), cx - q, cy - q, t[4]);
                bilinear(s.rgba(), s.w(), s.h(), cx + q, cy - q, t[5]);
                bilinear(s.rgba(), s.w(), s.h(), cx - q, cy + q, t[6]);
                bilinear(s.rgba(), s.w(), s.h(), cx + q, cy + q, t[7]);
                int i = (y * dw + x) * 4;
                for (int c = 0; c < 4; c++) {
                    d[i + c] = ((t[0][c] + t[1][c] + t[2][c] + t[3][c]) * 2f + t[4][c] + t[5][c] + t[6][c] + t[7][c]) / 12f
                            + target.rgba()[i + c];
                }
            }
        }
        return new Level(d, dw, dh);
    }

    /** しきい値を超える分だけを残す（ひざ付き）。シェーダー {@code prefilter} と同じ式。 */
    static void prefilter(float[] c, float threshold, float knee) {
        float br = Math.max(c[0], Math.max(c[1], c[2]));
        float rq = Math.min(Math.max(br - threshold + knee, 0f), 2f * knee);
        rq = rq * rq / (4f * knee + 1e-5f);
        float w = Math.max(rq, br - threshold) / Math.max(br, 1e-5f);
        w = Math.max(w, 0f);
        for (int i = 0; i < 4; i++) c[i] *= w;
    }

    /** 双線形（端は引き伸ばし）。座標は画素の左上原点で中心が +0.5。 */
    static void bilinear(float[] s, int w, int h, float x, float y, float[] out) {
        float fx = x - 0.5f, fy = y - 0.5f;
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        float tx = fx - x0, ty = fy - y0;
        int xa = clamp(x0, w), xb = clamp(x0 + 1, w), ya = clamp(y0, h), yb = clamp(y0 + 1, h);
        for (int c = 0; c < 4; c++) {
            float a = s[(ya * w + xa) * 4 + c], b = s[(ya * w + xb) * 4 + c];
            float cc = s[(yb * w + xa) * 4 + c], d = s[(yb * w + xb) * 4 + c];
            out[c] = (a * (1 - tx) + b * tx) * (1 - ty) + (cc * (1 - tx) + d * tx) * ty;
        }
    }

    private static int clamp(int v, int n) {
        return v < 0 ? 0 : Math.min(v, n - 1);
    }
}
