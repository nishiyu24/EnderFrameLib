package com.nishiyu.enderframelib.core.kernel.gpu;

/**
 * GPU シェーダーが行うアニメーション・スクロール評価の GLSL。本番はこのシェーダーだけが評価を行う。
 *
 * <p>シェーダーは {@code uFrameTimeMs} と {@link PropertyTrees} のレコードだけから現在の行列・不透明度を決める。
 * 同じ手順の CPU 参照実装は {@code src/test} 側にある（テストで GPU の結果と突き合わせる）。</p>
 */
public final class GpuAnimation {
    private GpuAnimation() {}

    /**
     * 同じ評価をするシェーダー断片（GLSL 1.50 / OpenGL 3.2 core で動く）。先頭に {@code #version 150} を付けて使う。
     * レコードはバッファテクスチャで読む: 浮動小数は {@code R32F}、整数は {@code R32I} の 2 つのビューを同じバッファに張る
     * （1.50 にはビット再解釈の関数が無いため）。添字は「レコード先頭 + バイトオフセット / 4」。
     * {@link PropertyTrees} のレコード配置と一致させること（テストが GPU の結果を Java 参照と突き合わせる）。
     */
    public static final String GLSL = """
            uniform samplerBuffer uTrF;
            uniform isamplerBuffer uTrI;
            uniform samplerBuffer uScroll;
            uniform samplerBuffer uCurves;
            uniform samplerBuffer uEfF;
            uniform isamplerBuffer uEfI;
            uniform float uFrameTimeMs;
            float trF(int slot, int i) { return texelFetch(uTrF, slot * 16 + i).r; }
            int trI(int slot, int i) { return texelFetch(uTrI, slot * 16 + i).r; }
            float efProgress(float startMs, float durationMs) {
                return durationMs <= 0.0 ? 1.0 : clamp((uFrameTimeMs - startMs) / durationMs, 0.0, 1.0);
            }
            float efCubic(float x, vec4 p) {
                float cx = 3.0 * p.x, bx = 3.0 * (p.z - p.x) - cx, ax = 1.0 - cx - bx;
                float cy = 3.0 * p.y, by = 3.0 * (p.w - p.y) - cy, ay = 1.0 - cy - by;
                float u = x;
                for (int i = 0; i < 8; i++) {
                    float e = ((ax * u + bx) * u + cx) * u - x;
                    float d = (3.0 * ax * u + 2.0 * bx) * u + cx;
                    if (abs(d) < 1e-6) break;
                    u = clamp(u - e / d, 0.0, 1.0);
                }
                float lo = 0.0, hi = 1.0;
                for (int i = 0; i < 12; i++) {
                    float xe = ((ax * u + bx) * u + cx) * u;
                    if (abs(xe - x) < 1e-6) break;
                    if (x > xe) lo = u; else hi = u;
                    u = 0.5 * (lo + hi);
                }
                return ((ay * u + by) * u + cy) * u;
            }
            float efEase(int easing, float t) {
                if (t <= 0.0) return 0.0;
                if (t >= 1.0) return 1.0;
                int kind = easing & 255;
                if (kind == 0) return t;
                vec4 p;
                if (kind == 5) {
                    int c = (easing >> 8) * 4;
                    p = vec4(texelFetch(uCurves, c).r, texelFetch(uCurves, c + 1).r, texelFetch(uCurves, c + 2).r, texelFetch(uCurves, c + 3).r);
                } else if (kind == 1) p = vec4(0.25, 0.1, 0.25, 1.0);
                else if (kind == 2) p = vec4(0.42, 0.0, 1.0, 1.0);
                else if (kind == 3) p = vec4(0.0, 0.0, 0.58, 1.0);
                else p = vec4(0.42, 0.0, 0.58, 1.0);
                return efCubic(t, p);
            }
            mat3 efTrs(float tx, float ty, float sx, float sy, float rot) {
                float c = cos(rot), s = sin(rot);
                return mat3(c * sx, s * sx, 0.0, -s * sy, c * sy, 0.0, tx, ty, 1.0);
            }
            mat3 efLocal(int slot) {
                float dur = trF(slot, 3);
                float e = dur <= 0.0 ? 1.0 : efEase(trI(slot, 4), efProgress(trF(slot, 2), dur));
                float v[5];
                for (int i = 0; i < 5; i++) {
                    float target = trF(slot, 6 + i);
                    float start = dur <= 0.0 ? target : trF(slot, 11 + i);
                    v[i] = start + (target - start) * e;
                }
                mat3 m = efTrs(v[0], v[1], v[2], v[3], v[4]);
                int sc = trI(slot, 1);
                if (sc >= 0) {
                    m[2].x -= texelFetch(uScroll, sc * 2).r;
                    m[2].y -= texelFetch(uScroll, sc * 2 + 1).r;
                }
                return m;
            }
            mat3 efWorld(int slot) {
                mat3 world = mat3(1.0);
                for (int depth = 0; depth < 64 && slot >= 0; depth++) {
                    world = efLocal(slot) * world;
                    slot = trI(slot, 0);
                }
                return world;
            }
            float efOpacity(int e) {
                float result = 1.0;
                for (int depth = 0; depth < 64 && e >= 0; depth++) {
                    float target = texelFetch(uEfF, e * 8).r;
                    int timing = texelFetch(uEfI, e * 8 + 5).r;
                    float o = target;
                    if (timing >= 0) {
                        float dur = trF(timing, 3);
                        float k = dur <= 0.0 ? 1.0 : efEase(trI(timing, 4), efProgress(trF(timing, 2), dur));
                        float st = texelFetch(uEfF, e * 8 + 1).r;
                        o = st + (target - st) * k;
                    }
                    result *= o;
                    e = texelFetch(uEfI, e * 8 + 4).r;
                }
                return result;
            }
            """;
}
