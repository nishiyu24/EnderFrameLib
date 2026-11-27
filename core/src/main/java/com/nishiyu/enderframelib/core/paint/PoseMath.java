package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.raster.Matrix2D;

/**
 * 項目の姿勢（親の行列に自分の平行移動・変形を掛けた行列）の算術と、前回の姿勢を使い回してよいかの判定（K4e）。
 * 状態は掛け算の作業用の配列だけで、{@link SceneWalker} の 1 インスタンスが 1 つ持つ。
 */
final class PoseMath {
    private final double[] m = new double[6];

    /** {@code base} に平行移動 {@code (tx, ty)} だけを掛けた姿勢。 */
    static Matrix2D translated(Matrix2D prev, Matrix2D base, float tx, float ty) {
        return reuse(prev, base.a(), base.b(), base.c(), base.d(),
                base.e() + base.a() * tx + base.c() * ty,
                base.f() + base.b() * tx + base.d() * ty);
    }

    /**
     * {@code base · T(原点 + 移動) · R · skewX · S · T(-原点)}。途中の行列を作らずに、{@link Matrix2D#multiply} と同じ式・同じ順で掛ける
     * （結果はビット単位で同じ。動く項目ごとに毎フレーム 4〜6 個の行列を作っていた。B12）。
     */
    Matrix2D transformed(Matrix2D prev, Matrix2D base, AnimatedValues anim, double lx, double ly, double tx, double ty) {
        m[0] = base.a(); m[1] = base.b(); m[2] = base.c(); m[3] = base.d(); m[4] = base.e(); m[5] = base.f();
        mulInto(1, 0, 0, 1, tx, ty);
        if (anim.rot() != 0f) {
            double rad = Math.toRadians(anim.rot()), cos = Math.cos(rad), sin = Math.sin(rad);
            mulInto(cos, sin, -sin, cos, 0, 0);
        }
        if (anim.skew() != 0f) mulInto(1, 0, Math.tan(Math.toRadians(anim.skew())), 1, 0, 0); // skewX（回転の後・拡縮の前）
        if (anim.sx() != 1f || anim.sy() != 1f) mulInto(anim.sx(), 0, 0, anim.sy(), 0, 0);
        mulInto(1, 0, 0, 1, -lx, -ly);
        return reuse(prev, m[0], m[1], m[2], m[3], m[4], m[5]);
    }

    /** {@code m = m · o}（{@link Matrix2D#multiply} と同じ式）。 */
    private void mulInto(double oa, double ob, double oc, double od, double oe, double of) {
        double a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5];
        m[0] = a * oa + c * ob;
        m[1] = b * oa + d * ob;
        m[2] = a * oc + c * od;
        m[3] = b * oc + d * od;
        m[4] = a * oe + c * of + e;
        m[5] = b * oe + d * of + f;
    }

    /** 前回の姿勢 {@code prev} と値が同じならそれを返し（補間値だけが変わった項目の行列の作り直しを省く）、違えば作る。 */
    private static Matrix2D reuse(Matrix2D prev, double a, double b, double c, double d, double e, double f) {
        if (prev != null && Double.compare(prev.a(), a) == 0 && Double.compare(prev.b(), b) == 0 && Double.compare(prev.c(), c) == 0
                && Double.compare(prev.d(), d) == 0 && Double.compare(prev.e(), e) == 0 && Double.compare(prev.f(), f) == 0) return prev;
        return new Matrix2D(a, b, c, d, e, f);
    }
}
