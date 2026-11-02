package com.nishiyu.enderframelib.core.paint.model;

/**
 * シェーダーが画素ごとに計算して描くグラデーションの 1 区間（2 色。{@code Gradients#shaderGradients}）。
 * 帯や格子に刻まないので、継ぎ目も段もない。色はプリマルチプライドで補間する。
 * 停止点が多いグラデーションは、区間ごとに同じ矩形へ重ねて描く。
 *
 * <p>画素の位置から、停止点の位置と同じ尺度の値 {@code u} を求める。
 * <ul>
 *   <li>線形: {@code gradA = {dx, dy, offset}}、{@code u = dx*x + dy*y + offset}（線の長さを 1 とした位置）</li>
 *   <li>放射: {@code gradA = {cx, cy, 1/rx}}、{@code gradB[0] = 1/ry}、{@code u = |((x-cx)/rx, (y-cy)/ry)|}</li>
 *   <li>円錐: {@code gradA = {cx, cy, 開始角(周)}}、{@code u = 真上から時計回りの角(周) - 開始角} の小数部</li>
 * </ul>
 * 繰り返し（{@code repeatPeriod > 0}）なら {@code u} を {@code [repeatOrigin, repeatOrigin + repeatPeriod)} に畳む。
 * 区間は {@code t = (u - gradB[1]) * gradB[2]}（始まりが 0、終わりが 1）で、色は {@code t} を 0〜1 に切り詰めて補間する。
 * 補間のヒントがあれば {@code t} を {@code gradB[3]} 乗する（なければ 1）。
 *
 * <p>{@code gradA[3]} は種類（{@link #LINEAR} など）に、範囲の印を 10 倍して足した値。
 * {@link #LOWER_CLOSED} は t が負の画素を、{@link #UPPER_CLOSED} は t が 1 を超える画素を捨てる
 * （隣の区間が塗る。閉じない側は端の色が伸びる）。配列は呼び出し側で書き換えない。
 */
public record ShaderGradient(int kind, float[] gradA, float[] gradB, float repeatOrigin, float repeatPeriod,
                             int color0, int color1) {
    public static final int LINEAR = 1;
    public static final int RADIAL = 2;
    public static final int CONIC = 3;

    /** t が負の画素を捨てる（手前は前の区間が塗る）。 */
    public static final int LOWER_CLOSED = 1;
    /** t が 1 を超える画素を捨てる（先は次の区間が塗る）。 */
    public static final int UPPER_CLOSED = 2;

    /**
     * @param g0 / g1 / g2 / g3 種類ごとの形の値（上の表。{@code g3} は放射の {@code 1/ry}）
     * @param p0             区間の始まりの位置（{@code u} と同じ尺度）
     * @param invSpan        区間の幅の逆数
     * @param hintExponent   ヒントによる {@code t} の指数。なければ 1
     * @param repeatPeriod   繰り返しの周期。繰り返さないなら 0
     */
    public static ShaderGradient of(int kind, float g0, float g1, float g2, float g3, float p0, float invSpan, float hintExponent,
                                    float repeatOrigin, float repeatPeriod, int color0, int color1, int bounds) {
        return new ShaderGradient(kind, new float[]{g0, g1, g2, kind + 10f * bounds}, new float[]{g3, p0, invSpan, hintExponent},
                repeatOrigin, repeatPeriod, color0, color1);
    }

    public int bounds() {
        return Math.round(gradA[3]) / 10;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ShaderGradient g && kind == g.kind && color0 == g.color0 && color1 == g.color1
                && repeatOrigin == g.repeatOrigin && repeatPeriod == g.repeatPeriod
                && java.util.Arrays.equals(gradA, g.gradA) && java.util.Arrays.equals(gradB, g.gradB);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(kind, java.util.Arrays.hashCode(gradA), java.util.Arrays.hashCode(gradB),
                repeatOrigin, repeatPeriod, color0, color1);
    }
}
