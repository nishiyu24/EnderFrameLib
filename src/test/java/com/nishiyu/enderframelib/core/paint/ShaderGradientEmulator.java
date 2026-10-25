package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.model.ShaderGradient;

import java.util.List;

/**
 * フラグメントシェーダー（{@code WebSdfShader}）のグラデーションの計算を CPU で再現する。
 * 式・範囲の外を捨てる・繰り返し・ヒント・色の補間を、シェーダーと同じ順で行う。
 * テストの基準との比較と、AWT のテスト用描画で使う。
 */
public final class ShaderGradientEmulator {
    private ShaderGradientEmulator() {}

    /** 1 区間が 1 画素に付ける色（ARGB）。範囲の外で捨てられるなら null。 */
    public static Integer colorOf(ShaderGradient s, float x, float y) {
        float[] a = s.gradA(), b = s.gradB();
        int g = Math.round(a[3]);
        int kind = g % 10, bounds = g / 10;
        float u;
        if (kind == ShaderGradient.LINEAR) {
            u = x * a[0] + y * a[1] + a[2];
        } else if (kind == ShaderGradient.RADIAL) {
            float dx = (x - a[0]) * a[2], dy = (y - a[1]) * b[0];
            u = (float) Math.hypot(dx, dy);
        } else {
            double angle = Math.atan2(x - a[0], -(y - a[1])) * 0.15915494;
            double v = angle - a[2];
            u = (float) (v - Math.floor(v));
        }
        if (s.repeatPeriod() > 0) {
            float d = u - s.repeatOrigin();
            u = s.repeatOrigin() + (d - s.repeatPeriod() * (float) Math.floor(d / s.repeatPeriod()));
        }
        float t = (u - b[1]) * b[2];
        if (((bounds & 1) != 0 && t < -0.001f) || ((bounds & 2) != 0 && t > 1.001f)) return null;
        t = Math.clamp(t, 0f, 1f);
        if (b[3] != 1f) t = (float) Math.pow(t, b[3]);
        return Gradients.lerpPremultiplied(s.color0(), s.color1(), t);
    }

    /** 区間を重ねたときの 1 画素の色。どの区間も塗らなければ 0（抜け）。重なれば最初のもの。 */
    public static int colorOf(List<ShaderGradient> segments, float x, float y) {
        for (ShaderGradient s : segments) {
            Integer c = colorOf(s, x, y);
            if (c != null) return c;
        }
        return 0;
    }
}
