package com.nishiyu.enderframelib.core.kernel.simd;

/**
 * データ並列の演算カーネル。既定実装（{@link ScalarSimd}）は分岐のない counted loop で、
 * JVM フラグ無しで HotSpot C2 の SuperWord が AVX2/AVX-512/NEON に自動ベクトル化する。
 * インキュベータ版の Vector API を使う実装は {@link java.util.ServiceLoader} 経由の任意プラグインとして
 * 差し込める（モジュールが無い環境では {@link #isAvailable()} が false か読み込み失敗で、既定へ戻る）。
 */
public interface SimdBackend {
    /** 名前（診断用）。 */
    String name();

    /** この環境で使えるか。 */
    boolean isAvailable();

    /** 大きいほど優先される。既定実装は 0。 */
    int priority();

    /** {@code out[i] = (a[i] + b[i]) + (c[i] + d[i])}（余白・枠・パディングの合算）。 */
    void sum4(float[] a, float[] b, float[] c, float[] d, float[] out, int n);

    /** {@code v[i] = min(max(v[i], lo[i]), hi[i])}。 */
    void clamp(float[] v, float[] lo, float[] hi, int n);

    /** {@code out[i] = a[i] * b[i] + c[i]}。 */
    void fma(float[] a, float[] b, float[] c, float[] out, int n);

    /** 点 (px,py) を含む最後（最前面）の矩形の添字。無ければ -1。 */
    int hitTestLast(float[] x, float[] y, float[] w, float[] h, int n, float px, float py);
}
