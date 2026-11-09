package com.nishiyu.enderframelib.core.kernel.simd;

/** 追加依存なしの既定実装。分岐のない単純ループにして C2 の自動ベクトル化に任せる。 */
public final class ScalarSimd implements SimdBackend {
    public static final ScalarSimd INSTANCE = new ScalarSimd();

    @Override public String name() { return "scalar-autovec"; }
    @Override public boolean isAvailable() { return true; }
    @Override public int priority() { return 0; }

    @Override
    public void sum4(float[] a, float[] b, float[] c, float[] d, float[] out, int n) {
        for (int i = 0; i < n; i++) out[i] = (a[i] + b[i]) + (c[i] + d[i]);
    }

    @Override
    public void clamp(float[] v, float[] lo, float[] hi, int n) {
        for (int i = 0; i < n; i++) v[i] = Math.min(Math.max(v[i], lo[i]), hi[i]);
    }

    @Override
    public void fma(float[] a, float[] b, float[] c, float[] out, int n) {
        for (int i = 0; i < n; i++) out[i] = Math.fma(a[i], b[i], c[i]);
    }

    @Override
    public int hitTestLast(float[] x, float[] y, float[] w, float[] h, int n, float px, float py) {
        int hit = -1;
        for (int i = 0; i < n; i++) {
            boolean in = (px >= x[i]) & (px < x[i] + w[i]) & (py >= y[i]) & (py < y[i] + h[i]);
            hit = in ? i : hit;
        }
        return hit;
    }
}
