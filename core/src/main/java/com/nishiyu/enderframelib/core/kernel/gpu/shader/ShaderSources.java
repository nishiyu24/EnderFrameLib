package com.nishiyu.enderframelib.core.kernel.gpu.shader;

/**
 * GLSL シェーダーの源を 1 か所に集めたもの（ENGINE_REARCHITECTURE_PLAN G2）。版の行（{@code #version}）は付けない: 実行時に
 * {@link Glsl} が API ごとの前置きを付ける。ここ以外の本体ファイルに GLSL の main 関数を書いてはならない
 * （{@code PortabilityRatchetTest} が数える）。
 */
public final class ShaderSources {
    private ShaderSources() {}

    /** アニメーション・スクロール評価の断片（{@code GpuAnimation.GLSL}）。先頭に版を付けず、利用側のシェーダーの前へ連結する。 */
    public static final String ANIMATION = """
            EF_UBO(0, PassParams) {
                vec2 uView;
                float uFrameTimeMs;
                int uOrderBase;
                float uYSign;
            };
            EF_TBO_F(20, uTrF);
            EF_TBO_I(21, uTrI);
            EF_TBO_F(22, uScroll);
            EF_TBO_F(23, uCurves);
            EF_TBO_F(24, uEfF);
            EF_TBO_I(25, uEfI);
            float trF(int slot, int i) { return EF_REC_F(uTrF, slot * 16 + i); }
            int trI(int slot, int i) { return EF_REC_I(uTrI, slot * 16 + i); }
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
                    p = vec4(EF_REC_F(uCurves, c), EF_REC_F(uCurves, c + 1), EF_REC_F(uCurves, c + 2), EF_REC_F(uCurves, c + 3));
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
                mat3 m;
                if (trI(slot, 5) == 1) {
                    // 行列のまま書かれた静止した姿勢（分解し直さない。三角関数も補間も無い）
                    m = mat3(trF(slot, 6), trF(slot, 7), 0.0, trF(slot, 8), trF(slot, 9), 0.0, trF(slot, 10), trF(slot, 11), 1.0);
                } else {
                    float dur = trF(slot, 3);
                    float e = dur <= 0.0 ? 1.0 : efEase(trI(slot, 4), efProgress(trF(slot, 2), dur));
                    float v[5];
                    for (int i = 0; i < 5; i++) {
                        float target = trF(slot, 6 + i);
                        float start = dur <= 0.0 ? target : trF(slot, 11 + i);
                        v[i] = start + (target - start) * e;
                    }
                    m = efTrs(v[0], v[1], v[2], v[3], v[4]);
                }
                int sc = trI(slot, 1);
                if (sc >= 0) {
                    m[2].x -= EF_REC_F(uScroll, sc * 2);
                    m[2].y -= EF_REC_F(uScroll, sc * 2 + 1);
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
                    float target = EF_REC_F(uEfF, e * 8);
                    int timing = EF_REC_I(uEfI, e * 8 + 5);
                    float o = target;
                    if (timing >= 0) {
                        float dur = trF(timing, 3);
                        float k = dur <= 0.0 ? 1.0 : efEase(trI(timing, 4), efProgress(trF(timing, 2), dur));
                        float st = EF_REC_F(uEfF, e * 8 + 1);
                        o = st + (target - st) * k;
                    }
                    result *= o;
                    e = EF_REC_I(uEfI, e * 8 + 4);
                }
                return result;
            }
            """;

    /** 矩形（SDF 図形）の頂点シェーダーの本体。{@link #ANIMATION} の後ろに連結する。 */
    public static final String RECT_VERTEX = """
            EF_TBO_F(16, uPrF);
            EF_TBO_I(17, uPrI);
            EF_TBO_I(19, uOrder);
            EF_LOC(0) flat out int vSlot;
            EF_LOC(1) flat out float vScale;
            EF_LOC(2) flat out float vOpacity;
            EF_LOC(3) out vec2 vLocal;
            void main() {
                int slot = EF_REC_I(uOrder, uOrderBase + EF_INSTANCE_ID);
                int base = slot * 32;
                int opcode = EF_REC_I(uPrI, base);
                vec4 rect = vec4(EF_REC_F(uPrF, base + 4), EF_REC_F(uPrF, base + 5),
                                 EF_REC_F(uPrF, base + 6), EF_REC_F(uPrF, base + 7));
                int tr = EF_REC_I(uPrI, base + 1);
                int ef = EF_REC_I(uPrI, base + 3);
                vSlot = slot;
                vOpacity = ef >= 0 ? efOpacity(ef) : 1.0;
                vec2 corner = vec2(float(EF_VERTEX_ID & 1), float(EF_VERTEX_ID >> 1));
                // 描く範囲は形の矩形に余白を足す（斜めの縁の滑らかな画素が矩形の外へはみ出す分。従来のシェーダーと同じ）
                float margin = EF_REC_F(uPrF, base + 27);
                vLocal = (rect.xy - margin) + corner * (rect.zw + 2.0 * margin);
                mat3 m = tr >= 0 ? efWorld(tr) : mat3(1.0);
                vec2 p = (m * vec3(vLocal, 1.0)).xy;
                vScale = sqrt(abs(m[0].x * m[1].y - m[0].y * m[1].x)); // 局所の 1 単位が何画素か（縁の幅を画素で保つ）
                vec2 ndc = p / uView * 2.0 - 1.0;
                gl_Position = opcode == 0 ? vec4(2.0, 2.0, 2.0, 1.0) : vec4(ndc.x, EF_CLIP_Y(ndc.y * uYSign), 0.0, 1.0);
            }
            """;

    /** 矩形（SDF 図形）のフラグメントシェーダー。 */
    public static final String RECT_FRAGMENT = """
            EF_UBO(0, PassParams) {
                vec2 uView;
                float uFrameTimeMs;
                int uOrderBase;
                float uYSign;
            };
            EF_TBO_F(16, uPrF);
            EF_TBO_I(17, uPrI);
            EF_TBO_F(18, uPrH);
            EF_TBO_F(26, uClF);
            EF_TBO_F(27, uClH);
            EF_TEX2D(1, uAtlas);
            EF_TEX2D(2, uImgTex);
            EF_LOC(0) flat in int vSlot;
            EF_LOC(1) flat in float vScale;
            EF_LOC(2) flat in float vOpacity;
            EF_LOC(3) in vec2 vLocal;
            EF_LOC(0) out vec4 o;

            vec4 half4(int i) {
                int b = vSlot * 64 + i;
                return vec4(EF_REC_F(uPrH, b), EF_REC_F(uPrH, b + 1), EF_REC_F(uPrH, b + 2), EF_REC_F(uPrH, b + 3));
            }
            vec4 color(int k) {
                int c = EF_REC_I(uPrI, vSlot * 32 + 22 + k);
                return vec4(float((c >> 16) & 255), float((c >> 8) & 255), float(c & 255), float((c >> 24) & 255)) / 255.0;
            }
            // 従来のバックエンドのシェーダーと同じ角丸矩形の符号付き距離（楕円の角を含む）
            float sdRR(vec2 p, vec4 r, vec4 rx, vec4 ry) {
                vec2 c = (r.xy + r.zw) * 0.5;
                vec2 h = (r.zw - r.xy) * 0.5;
                bool right = p.x > c.x;
                bool bottom = p.y > c.y;
                int k = bottom ? (right ? 2 : 3) : (right ? 1 : 0);
                float rkx = rx[k];
                float rky = ry[k];
                if (rkx <= 0.0 || rky <= 0.0) {
                    vec2 d = abs(p - c) - h;
                    return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0);
                }
                rkx = min(rkx, h.x);
                rky = min(rky, h.y);
                vec2 rk = vec2(rkx, rky);
                vec2 q = abs(p - c) - (h - rk);
                if (q.x > 0.0 && q.y > 0.0) {
                    float len = length(q / rk);
                    float grad = length(q / (rk * rk));
                    return grad <= 0.0 ? 0.0 : (len - 1.0) * len / grad;
                }
                return max(q.x - rkx, q.y - rky);
            }
            float cover(float sd, float scale) {
                return clamp(0.5 - sd * scale, 0.0, 1.0);
            }
            // 影のぼかし（ガウスの縁を誤差関数で）。従来のシェーダーと同じ近似
            float erfcf(float x) {
                float z = abs(x);
                float t = 1.0 / (1.0 + 0.3275911 * z);
                float poly = ((((1.061405429 * t - 1.453152027) * t + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t;
                float r = poly * exp(-z * z);
                return x >= 0.0 ? r : 2.0 - r;
            }
            float blurredEdge(float sd, float sigma) {
                if (sigma <= 0.0) return sd <= 0.0 ? 1.0 : 0.0;
                return 0.5 * erfcf(sd / (sigma * 1.41421356));
            }
            // 続きスロット（直後のスロット）のデータ
            float extF(int i) { return EF_REC_F(uPrF, (vSlot + 1) * 32 + i); }
            vec4 extF4(int i) { return vec4(extF(i), extF(i + 1), extF(i + 2), extF(i + 3)); }
            // クリップ: 従来のシェーダーと同じく、デバイス画素を持ち主の空間へ逆写像して角丸矩形の被覆率を掛ける
            float clipCoverage() {
                int n = EF_REC_I(uPrI, vSlot * 32 + 2);
                if (n <= 0) return 1.0;
                vec2 dev = vec2(gl_FragCoord.x, uYSign < 0.0 ? EF_FRAG_Y(uView.y) : gl_FragCoord.y);
                float cov = 1.0;
                for (int i = 0; i < 4 && i < n; i++) {
                    int w = EF_REC_I(uPrI, vSlot * 32 + 30 + (i >> 1));
                    int id = (i & 1) == 0 ? (w & 65535) : ((w >> 16) & 65535); // 16 ビット ×4（リトルエンディアン）
                    int cb = id * 16;
                    vec4 A = vec4(EF_REC_F(uClF, cb), EF_REC_F(uClF, cb + 1), EF_REC_F(uClF, cb + 2), EF_REC_F(uClF, cb + 3));
                    vec3 T = vec3(EF_REC_F(uClF, cb + 4), EF_REC_F(uClF, cb + 5), EF_REC_F(uClF, cb + 6));
                    vec4 R = vec4(EF_REC_F(uClF, cb + 8), EF_REC_F(uClF, cb + 9), EF_REC_F(uClF, cb + 10), EF_REC_F(uClF, cb + 11));
                    int hb = id * 32 + 24;
                    vec4 rx = vec4(EF_REC_F(uClH, hb), EF_REC_F(uClH, hb + 1), EF_REC_F(uClH, hb + 2), EF_REC_F(uClH, hb + 3));
                    vec4 ry = vec4(EF_REC_F(uClH, hb + 4), EF_REC_F(uClH, hb + 5), EF_REC_F(uClH, hb + 6), EF_REC_F(uClH, hb + 7));
                    vec2 lp = vec2(A.x * dev.x + A.z * dev.y + T.x, A.y * dev.x + A.w * dev.y + T.y);
                    cov *= cover(sdRR(lp, R, rx, ry), T.z);
                    if (cov <= 0.0) return 0.0;
                }
                return cov;
            }
            float quarterArc(float rx, float ry) {
                if (rx <= 0.0 || ry <= 0.0) return 0.0;
                return 0.25 * 3.14159265 * (3.0 * (rx + ry) - sqrt((3.0 * rx + ry) * (rx + 3.0 * ry)));
            }
            // 枠線の中心線上の最も近い位置（箱の中心が原点）。破線・点線の位相に使う
            float pathParam(vec2 p, vec2 b, vec4 rx, vec4 ry) {
                float lt = 2.0 * b.x - rx[0] - rx[1];
                float lr = 2.0 * b.y - ry[1] - ry[2];
                float lb = 2.0 * b.x - rx[2] - rx[3];
                float ll = 2.0 * b.y - ry[3] - ry[0];
                float aTR = quarterArc(rx[1], ry[1]), aBR = quarterArc(rx[2], ry[2]);
                float aBL = quarterArc(rx[3], ry[3]), aTL = quarterArc(rx[0], ry[0]);
                float sTR = lt, sR = sTR + aTR, sBR = sR + lr, sB = sBR + aBR, sBL = sB + lb, sL = sBL + aBL, sTL = sL + ll;
                float hp = 1.57079633;
                float px = p.x, py = p.y;
                if (py < 0.0) {
                    if (px >= 0.0) {
                        float cx = b.x - rx[1], cy = -(b.y - ry[1]);
                        if (px > cx && py < cy) return sTR + atan((px - cx) / max(rx[1], 1e-4), (cy - py) / max(ry[1], 1e-4)) / hp * aTR;
                        if (px <= cx) return px + b.x - rx[0];
                        return sR + (py + b.y - ry[1]);
                    }
                    float cx = -(b.x - rx[0]), cy = -(b.y - ry[0]);
                    if (px < cx && py < cy) return sTL + atan((cy - py) / max(ry[0], 1e-4), (cx - px) / max(rx[0], 1e-4)) / hp * aTL;
                    if (px >= cx) return px + b.x - rx[0];
                    return sL + (b.y - ry[3] - py);
                }
                if (px >= 0.0) {
                    float cx = b.x - rx[2], cy = b.y - ry[2];
                    if (px > cx && py > cy) return sBR + atan((py - cy) / max(ry[2], 1e-4), (px - cx) / max(rx[2], 1e-4)) / hp * aBR;
                    if (py <= cy) return sR + (py + b.y - ry[1]);
                    return sB + (b.x - rx[2] - px);
                }
                float cx = -(b.x - rx[3]), cy = b.y - ry[3];
                if (px < cx && py > cy) return sBL + atan((cx - px) / max(rx[3], 1e-4), (py - cy) / max(ry[3], 1e-4)) / hp * aBL;
                if (px >= cx) return sB + (b.x - rx[2] - px);
                return sL + (b.y - ry[3] - py);
            }
            float atlasFetch(ivec2 p) {
                return texelFetch(uAtlas, ivec2(p.x, EF_ATLAS_Y(uAtlas, p.y)), 0).r;
            }
            float glyphCov(ivec2 c, vec4 gl) {
                if (c.x < 0 || c.y < 0 || c.x >= int(gl.z) || c.y >= int(gl.w)) return 0.0;
                return atlasFetch(ivec2(gl.xy) + c);
            }
            void main() {
                int base = vSlot * 32;
                int op = EF_REC_I(uPrI, base);
                vec4 xywh = vec4(EF_REC_F(uPrF, base + 4), EF_REC_F(uPrF, base + 5),
                                 EF_REC_F(uPrF, base + 6), EF_REC_F(uPrF, base + 7));
                vec4 rect = vec4(xywh.xy, xywh.xy + xywh.zw);
                vec4 rx = half4(16), ry = half4(20);
                vec4 col = color(0);
                float a;
                if (op == 6) {
                    // 字形（デバイス画素の格子に揃えた文字）: アトラスの被覆率をそのまま引く
                    int w0 = EF_REC_I(uPrI, base + 28), w1 = EF_REC_I(uPrI, base + 29);
                    vec4 gl = vec4(float(w0 & 65535), float((w0 >> 16) & 65535), float(w1 & 65535), float((w1 >> 16) & 65535));
                    ivec2 gp = ivec2(floor(vLocal - rect.xy));
                    if (gp.x < 0 || gp.y < 0 || gp.x >= int(gl.z) || gp.y >= int(gl.w)) discard;
                    a = atlasFetch(ivec2(gl.xy) + gp);
                } else if (op == 10) {
                    // 文字の縁取り: SDF の字形（128 = 輪郭）から、輪郭を中心にした帯を描く
                    vec4 gl = extF4(0);
                    ivec2 gp = ivec2(floor(vLocal - rect.xy));
                    if (gp.x < 0 || gp.y < 0 || gp.x >= int(gl.z) || gp.y >= int(gl.w)) discard;
                    float sv = atlasFetch(ivec2(gl.xy) + gp) * 255.0;
                    float sd = (sv - 128.0) * (8.0 / 128.0);
                    a = clamp(extF(4) - abs(sd) + 0.5, 0.0, 1.0);
                } else if (op == 9) {
                    // 回転などの姿勢の下の字形: アトラスを双一次補間で引く
                    vec4 gl = extF4(0), gi = extF4(4), g2 = extF4(8);
                    float gcx = (vLocal.x - gi.x) * gi.z - g2.x - 0.5, gcy = (vLocal.y - gi.y) * gi.z + g2.y - 0.5;
                    ivec2 c0 = ivec2(floor(vec2(gcx, gcy)));
                    vec2 fr = vec2(gcx, gcy) - vec2(c0);
                    float v00 = glyphCov(c0, gl), v10 = glyphCov(c0 + ivec2(1, 0), gl);
                    float v01 = glyphCov(c0 + ivec2(0, 1), gl), v11 = glyphCov(c0 + ivec2(1, 1), gl);
                    a = (v00 * (1.0 - fr.x) + v10 * fr.x) * (1.0 - fr.y) + (v01 * (1.0 - fr.x) + v11 * fr.x) * fr.y;
                } else if (op == 7) {
                    // 画像（事前乗算のテクスチャ）: 角丸の形の被覆率 × 画像の標本
                    a = cover(sdRR(vLocal, extF4(0), extF4(16), extF4(20)), vScale);
                    if (a <= 0.0) discard;
                    vec4 pl = extF4(4), sz = extF4(8);
                    vec2 uv = (vLocal - pl.xy) * pl.zw;
                    vec2 imgSize = sz.xy;
                    if (uv.x < -0.5 || uv.y < -0.5 || uv.x > imgSize.x + 0.5 || uv.y > imgSize.y + 0.5) discard;
                    vec4 tx;
                    int taps = int(sz.z + 0.5);
                    if (taps <= 1) {
                        tx = texture(uImgTex, uv / imgSize);
                    } else {
                        vec2 stp = vec2(extF(12), extF(13));
                        tx = vec4(0.0);
                        for (int j = 0; j < taps; j++) {
                            for (int i = 0; i < taps; i++) {
                                vec2 off = (vec2(float(i), float(j)) + 0.5) / float(taps) - 0.5;
                                tx += texture(uImgTex, (uv + off * stp) / imgSize);
                            }
                        }
                        tx /= float(taps * taps);
                    }
                    if (tx.a <= 0.0) discard;
                    col = vec4(tx.rgb / tx.a, tx.a);
                } else if (op == 8) {
                    // 円の枠: 外半径と内半径の輪。辺の色は対角線で分ける（CPU 版と同じ）
                    vec4 cr = extF4(0);
                    float dist = length(vLocal - cr.xy);
                    a = cover(dist - cr.z, vScale) * (cr.w > 0.0 ? 1.0 - cover(dist - cr.w, vScale) : 1.0);
                    if (a <= 0.0) discard;
                    vec2 dd = vLocal - cr.xy;
                    int cs = abs(dd.y) >= abs(dd.x) - 1e-3 ? (dd.y < 0.0 ? 0 : 2) : (dd.x > 0.0 ? 1 : 3);
                    col = color(cs);
                } else if (op == 3) {
                    // 外側の影: 影の形を σ でぼかし、穴（箱そのもの）があれば抜く
                    a = blurredEdge(sdRR(vLocal, extF4(0), extF4(10), extF4(14)), extF(8));
                    if (a <= 0.0) discard;
                    if ((EF_REC_I(uPrI, (vSlot + 1) * 32 + 9) & 1) != 0) a *= 1.0 - cover(sdRR(vLocal, extF4(4), extF4(18), extF4(22)), vScale);
                } else if (op == 4) {
                    // 内側の影: 箱の中で、穴の外側をぼかして塗る
                    a = cover(sdRR(vLocal, rect, rx, ry), vScale);
                    if (a <= 0.0) discard;
                    a *= blurredEdge(-sdRR(vLocal, extF4(4), extF4(18), extF4(22)), extF(8));
                } else {
                    a = cover(sdRR(vLocal, rect, rx, ry), vScale);
                }
                if (a <= 0.0) discard;
                if (op == 5) {
                    vec4 gA = extF4(0), gB = extF4(4), gc0 = extF4(8), gc1 = extF4(12);
                    vec2 rep = vec2(extF(16), extF(17));
                    int g = int(floor(gA.w + 0.5));
                    int kind = g % 10;
                    int bounds = g / 10;
                    float u;
                    if (kind == 1) {
                        u = vLocal.x * gA.x + vLocal.y * gA.y + gA.z;
                    } else if (kind == 2) {
                        u = length(vec2((vLocal.x - gA.x) * gA.z, (vLocal.y - gA.y) * gB.x));
                    } else {
                        float v = atan(vLocal.x - gA.x, -(vLocal.y - gA.y)) * 0.15915494 - gA.z;
                        u = v - floor(v);
                    }
                    if (rep.y > 0.0) {
                        float d = u - rep.x;
                        u = rep.x + (d - rep.y * floor(d / rep.y));
                    }
                    float t = (u - gB.y) * gB.z;
                    if (((bounds & 1) != 0 && t < -0.001) || ((bounds & 2) != 0 && t > 1.001)) discard;
                    t = clamp(t, 0.0, 1.0);
                    if (gB.w != 1.0) t = pow(t, gB.w);
                    float ga = mix(gc0.a, gc1.a, t);
                    if (ga <= 0.0) discard;
                    col = vec4(mix(gc0.rgb * gc0.a, gc1.rgb * gc1.a, t) / ga, ga);
                }
                if (op == 2) {
                    vec4 inner = vec4(EF_REC_F(uPrF, base + 12), EF_REC_F(uPrF, base + 13),
                                      EF_REC_F(uPrF, base + 14), EF_REC_F(uPrF, base + 15));
                    int strokeFlags = EF_REC_I(uPrI, base + 26);
                    bool innerEmpty = (strokeFlags & 1) != 0;
                    vec4 widths = half4(40);
                    float innerCov = innerEmpty ? 0.0 : cover(sdRR(vLocal, inner, half4(32), half4(36)), vScale);
                    a *= 1.0 - innerCov;
                    if (a <= 0.0) discard;
                    // 一番近い辺（幅で割った距離が最小）の色。幅 0 の辺は飛ばす
                    float d[4] = float[4](vLocal.y - rect.y, rect.z - vLocal.x, rect.w - vLocal.y, vLocal.x - rect.x);
                    int side = -1;
                    float best = 3.4e38;
                    for (int s = 0; s < 4; s++) {
                        if (widths[s] <= 0.0) continue;
                        float v = d[s] / widths[s];
                        if (v < best) { best = v; side = s; }
                    }
                    if (side < 0) discard;
                    col = color(side);
                    if ((strokeFlags & 2) != 0) {
                        // 破線・点線: 中心線に沿った位相で on/off（丸なら点）
                        vec4 t44 = extF4(0), t47 = extF4(12);
                        float sp = pathParam(vLocal - t44.zw, t44.xy, extF4(4), extF4(8));
                        float period = t47.x;
                        float phase = sp - period * floor(sp / period);
                        float along = abs(phase - period * 0.5);
                        float on;
                        if (t47.z > 0.5) {
                            float perp = abs(sdRR(vLocal, rect, rx, ry) + t47.w * 0.5);
                            on = clamp(t47.y * 0.5 - length(vec2(along, perp)) + 0.5, 0.0, 1.0);
                        } else {
                            on = clamp(t47.y * 0.5 - along + 0.5, 0.0, 1.0);
                        }
                        a *= on;
                        if (a <= 0.0) discard;
                    }
                }
                a *= vOpacity * col.a;
                if (a <= 0.0) discard;
                a *= clipCoverage();
                if (a <= 0.0) discard;
                o = vec4(col.rgb * a, a);
            }
            """;

    /** CPU 層・外部テクスチャを写す頂点シェーダー。 */
    public static final String TEX_VERT = """
            #ifndef EF_HAS_PASS_PARAMS
            EF_UBO(0, PassParams) {
                vec4 uDev;
                vec2 uView;
                float uOpacity;
                int uFlip;
            };
            #endif
            void main() {
                vec2 c = vec2(float(EF_VERTEX_ID & 1), float(EF_VERTEX_ID >> 1));
                vec2 d = mix(uDev.xy, uDev.zw, c);
                gl_Position = vec4(d.x / uView.x * 2.0 - 1.0, EF_CLIP_Y(1.0 - d.y / uView.y * 2.0), 0.0, 1.0);
            }
            """;

    /** {@code FX} パス用の頂点シェーダー（{@link #FX_FRAG} と同一の {@code PassParams} ブロックを先頭に持つ）。 */
    static String fxVertex() {
        return """
                #define EF_HAS_PASS_PARAMS
                EF_UBO(0, PassParams) {
                    vec4 uDev;
                    vec2 uView;
                    vec2 uDir;
                    vec4 uRow0;
                    vec4 uRow1;
                    vec4 uRow2;
                    vec4 uShadowColor;
                    vec4 uInvM;
                    vec4 uBD[3];
                    vec4 uClipD[96];
                    vec2 uTexel;
                    vec2 uUvScale;
                    vec2 uUvMax;
                    vec2 uShadowOffset;
                    vec2 uInvT;
                    int uMode;
                    float uOpacity;
                    int uHasMatrix;
                    float uSigma;
                    int uTaps;
                    float uStep;
                    int uK;
                    int uMaskMode;
                    int uBlend;
                    float uShapeScale;
                    int uClipKind;
                    int uEvenOdd;
                    int uClipN;
                    int uHasB;
                };
                """ + TEX_VERT;
    }

    /** 事前乗算の画素を 1 対 1 で写すフラグメントシェーダー（拡縮・補間なし）。 */
    public static final String TEX_FRAG = """
            EF_UBO(0, PassParams) {
                vec4 uDev;
                vec2 uView;
                float uOpacity;
                int uFlip;
            };
            EF_TEX2D(1, uTex);
            EF_LOC(0) out vec4 fragColor;
            void main() {
                int py = uFlip != 0 ? int(uView.y) - 1 - int(floor(gl_FragCoord.y)) : int(floor(gl_FragCoord.y));
                fragColor = texelFetch(uTex, ivec2(int(floor(gl_FragCoord.x)), py), 0) * uOpacity;
            }
            """;

    /** 層の合成（色の行列・ぼかし・影・マスク・不透明度）のフラグメントシェーダー。 */
    public static final String FX_FRAG = """
            EF_UBO(0, PassParams) {
                vec4 uDev;
                vec2 uView;
                vec2 uDir;
                vec4 uRow0;
                vec4 uRow1;
                vec4 uRow2;
                vec4 uShadowColor;
                vec4 uInvM;
                vec4 uBD[3];
                vec4 uClipD[96];
                vec2 uTexel;
                vec2 uUvScale;
                vec2 uUvMax;
                vec2 uShadowOffset;
                vec2 uInvT;
                int uMode;
                float uOpacity;
                int uHasMatrix;
                float uSigma;
                int uTaps;
                float uStep;
                int uK;
                int uMaskMode;
                int uBlend;
                float uShapeScale;
                int uClipKind;
                int uEvenOdd;
                int uClipN;
                int uHasB;
            };
            EF_TEX2D(1, uSrc);
            EF_TEX2D(2, uAux);
            EF_TEX2D(3, uBackdrop);
            EF_LOC(0) out vec4 fragColor;

            float sdEllipse(vec2 p, vec2 r) {
                r = max(r, vec2(0.001));
                vec2 q = abs(p) / r;
                float k0 = length(q);
                float k1 = length(p / (r * r));
                if (k1 < 1e-9) return -min(r.x, r.y);
                return k0 * (k0 - 1.0) / k1;
            }
            float sdInset(vec4 d0, vec4 d1, vec4 d2, vec2 p) {
                vec2 c = vec2(d0.x + d0.z, d0.y + d0.w) * 0.5;
                vec2 b = max(vec2(0.001), vec2(d0.z - d0.x, d0.w - d0.y) * 0.5);
                p -= c;
                int corner = p.x > 0.0 ? (p.y > 0.0 ? 2 : 1) : (p.y > 0.0 ? 3 : 0);
                vec2 rr = vec2(min(d1[corner], b.x), min(d2[corner], b.y));
                vec2 a = abs(p) - b;
                if (rr.x <= 0.0 || rr.y <= 0.0) return length(max(a, 0.0)) + min(max(a.x, a.y), 0.0);
                vec2 q = a + rr;
                if (q.x > 0.0 && q.y > 0.0) {
                    float k0 = length(q / rr);
                    float k1 = length(q / (rr * rr));
                    return k0 * (k0 - 1.0) / max(k1, 1e-6);
                }
                return max(q.x - rr.x, q.y - rr.y);
            }
            float sdSegment(vec2 p, vec2 a, vec2 b) {
                vec2 d = b - a;
                float len2 = dot(d, d);
                float t = len2 <= 0.0 ? 0.0 : clamp(dot(p - a, d) / len2, 0.0, 1.0);
                return length(p - (a + t * d));
            }
            float sdClip(vec2 p) {
                if (uClipKind == 1) return sdInset(uClipD[0], uClipD[1], uClipD[2], p);
                if (uClipKind == 2) return length(p - uClipD[0].xy) - uClipD[0].z;
                if (uClipKind == 3) return sdEllipse(p - uClipD[0].xy, uClipD[0].zw);
                float mn = 3.4e38;
                int winding = 0, crossings = 0;
                for (int i = 0; i < uClipN; i++) {
                    vec2 a = uClipD[i].xy, b = uClipD[i].zw;
                    mn = min(mn, sdSegment(p, a, b));
                    if ((a.y <= p.y) != (b.y <= p.y)) {
                        float t = (p.y - a.y) / (b.y - a.y);
                        if (p.x < a.x + t * (b.x - a.x)) {
                            crossings++;
                            winding += b.y > a.y ? 1 : -1;
                        }
                    }
                }
                bool inside = uEvenOdd == 1 ? (crossings & 1) == 1 : winding != 0;
                return inside ? -mn : mn;
            }
            vec2 boxPos() {
                vec2 dev = vec2(gl_FragCoord.x, EF_FRAG_Y(uView.y));
                return vec2(uInvM.x * dev.x + uInvM.z * dev.y + uInvT.x, uInvM.y * dev.x + uInvM.w * dev.y + uInvT.y);
            }
            float clipCoverage() {
                if (uClipKind == 0) return 1.0;
                return clamp(0.5 - sdClip(boxPos()) * uShapeScale, 0.0, 1.0);
            }

            float lum(vec3 c) { return dot(c, vec3(0.3, 0.59, 0.11)); }
            vec3 clipColor(vec3 c) {
                float l = lum(c);
                float n = min(min(c.r, c.g), c.b);
                float x = max(max(c.r, c.g), c.b);
                if (n < 0.0) c = l + (c - l) * l / (l - n);
                if (x > 1.0) c = l + (c - l) * (1.0 - l) / (x - l);
                return c;
            }
            vec3 setLum(vec3 c, float l) { return clipColor(c + (l - lum(c))); }
            float sat(vec3 c) { return max(max(c.r, c.g), c.b) - min(min(c.r, c.g), c.b); }
            vec3 setSat(vec3 c, float s) {
                float mx = max(max(c.r, c.g), c.b);
                float mn = min(min(c.r, c.g), c.b);
                if (mx <= mn) return vec3(0.0);
                return (c - mn) * s / (mx - mn);
            }
            float hardLight(float cb, float cs) {
                return cs <= 0.5 ? cb * 2.0 * cs : cb + (2.0 * cs - 1.0) - cb * (2.0 * cs - 1.0);
            }
            float blendCh(int mode, float cb, float cs) {
                if (mode == 1) return cb * cs;
                if (mode == 2) return cb + cs - cb * cs;
                if (mode == 3) return hardLight(cs, cb);
                if (mode == 4) return min(cb, cs);
                if (mode == 5) return max(cb, cs);
                if (mode == 6) return cb <= 0.0 ? 0.0 : (cs >= 1.0 ? 1.0 : min(1.0, cb / (1.0 - cs)));
                if (mode == 7) return cb >= 1.0 ? 1.0 : (cs <= 0.0 ? 0.0 : 1.0 - min(1.0, (1.0 - cb) / cs));
                if (mode == 8) return hardLight(cb, cs);
                if (mode == 9) {
                    if (cs <= 0.5) return cb - (1.0 - 2.0 * cs) * cb * (1.0 - cb);
                    float d = cb <= 0.25 ? ((16.0 * cb - 12.0) * cb + 4.0) * cb : sqrt(cb);
                    return cb + (2.0 * cs - 1.0) * (d - cb);
                }
                if (mode == 10) return abs(cb - cs);
                if (mode == 11) return cb + cs - 2.0 * cb * cs;
                return cs;
            }
            vec3 blendRgb(int mode, vec3 cb, vec3 cs) {
                if (mode == 12) return setLum(setSat(cs, sat(cb)), lum(cb));
                if (mode == 13) return setLum(setSat(cb, sat(cs)), lum(cb));
                if (mode == 14) return setLum(cs, lum(cb));
                if (mode == 15) return setLum(cb, lum(cs));
                return vec3(blendCh(mode, cb.r, cs.r), blendCh(mode, cb.g, cs.g), blendCh(mode, cb.b, cs.b));
            }

            vec4 applyMatrix(vec4 pm) {
                if (uHasMatrix == 0) return pm;
                float a = pm.a;
                if (a <= 0.0) return vec4(0.0);
                vec3 c = pm.rgb / a;
                c = clamp(vec3(dot(uRow0.rgb, c) + uRow0.a, dot(uRow1.rgb, c) + uRow1.a, dot(uRow2.rgb, c) + uRow2.a), 0.0, 1.0);
                return vec4(c * a, a);
            }

            void main() {
                vec2 uv = gl_FragCoord.xy / uView;
                if (uMode == 2) {
                    vec2 c0 = min(uv * uUvScale, uUvMax);
                    vec4 sum = texture(uSrc, c0);
                    float wsum = 1.0;
                    for (int i = 1; i <= uTaps; i++) {
                        float d = float(i) * uStep;
                        float w = exp(-0.5 * d * d / (uSigma * uSigma));
                        vec2 o = uDir * uTexel * d;
                        sum += (texture(uSrc, clamp(c0 + o, vec2(0.0), uUvMax)) + texture(uSrc, clamp(c0 - o, vec2(0.0), uUvMax))) * w;
                        wsum += 2.0 * w;
                    }
                    fragColor = sum / wsum;
                    return;
                }
                if (uMode == 7) {
                    // 縮小: 描画先の 1 画素 = 元の uK x uK 画素の平均（2 x 2 の中心を双一次で引くので、標本は (uK/2)^2 個）
                    vec2 base = floor(gl_FragCoord.xy) * float(uK) * uTexel;
                    int n = uK / 2;
                    vec4 acc = vec4(0.0);
                    for (int j = 0; j < n; j++) {
                        for (int i = 0; i < n; i++) {
                            acc += texture(uSrc, min(base + (vec2(float(i), float(j)) * 2.0 + 1.0) * uTexel, vec2(1.0)));
                        }
                    }
                    fragColor = acc / float(n * n);
                    return;
                }
                if (uMode == 8) {
                    // 拡大: 縮小した絵（左下の一部）を双一次で引き伸ばす
                    fragColor = texture(uSrc, min(uv * uUvScale, uUvMax));
                    return;
                }
                if (uMode == 3) {
                    vec4 base = texture(uSrc, uv);
                    float sa = texture(uAux, uv - uShadowOffset).a * uShadowColor.a;
                    vec4 shadow = vec4(uShadowColor.rgb * sa, sa);
                    fragColor = base + shadow * (1.0 - base.a);
                    return;
                }
                if (uMode == 5) {
                    fragColor = texture(uSrc, uv) * clipCoverage();
                    return;
                }
                if (uMode == 6) {
                    // backdrop-filter: 元の背景（uBackdrop）と加工した背景（uSrc）を、形 x クリップ x 不透明度で混ぜる
                    float k = clamp(0.5 - sdInset(uBD[0], uBD[1], uBD[2], boxPos()) * uShapeScale, 0.0, 1.0);
                    k *= clipCoverage() * uOpacity;
                    fragColor = mix(texture(uBackdrop, uv), texture(uSrc, uv), k);
                    return;
                }
                vec4 src = applyMatrix(texture(uSrc, uv));
                if (uMaskMode > 0) {
                    vec4 m = texture(uAux, uv);
                    src *= uMaskMode == 1 ? m.a : dot(m.rgb, vec3(0.2126, 0.7152, 0.0722));
                }
                src *= uOpacity;
                if (uMode == 4) {
                    // mix-blend-mode: 背景（uBackdrop）と混ぜた結果をそのまま書く
                    vec4 bd = texture(uBackdrop, uv);
                    float as = src.a, ab = bd.a;
                    if (as <= 0.0) { fragColor = bd; return; }
                    vec3 cb = ab > 0.0 ? bd.rgb / ab : vec3(0.0);
                    vec3 cs = src.rgb / as;
                    if (uBlend == 16) {
                        fragColor = vec4(min(vec3(1.0), src.rgb + bd.rgb), min(1.0, as + ab));
                        return;
                    }
                    vec3 b = blendRgb(uBlend, cb, cs);
                    vec3 co = as * (1.0 - ab) * cs + as * ab * b + (1.0 - as) * ab * cb;
                    fragColor = vec4(co, as + ab * (1.0 - as));
                    return;
                }
                fragColor = src;
            }
            """;

    /** 3D（モード A）のインスタンス頂点シェーダー。 */
    public static final String SCENE3D_VERTEX = """
            EF_LOC(0) in vec3 aPos;
            EF_LOC(1) in vec3 aNorm;
            EF_LOC(2) in vec2 aUv;

            EF_TBO_F(8, uInstanceTboF);
            EF_TBO_I(9, uInstanceTboI);
            EF_UBO(0, PassParams) {
                mat4 uViewProj;
                vec4 uCameraPos;
                vec4 uLightDir;
                vec4 uLightColor;
                vec4 uAmbientColor;
                int uBaseInstance;
            };

            EF_LOC(0) out vec3 vWorldPos;
            EF_LOC(1) out vec3 vWorldNorm;
            EF_LOC(2) out vec2 vUv;
            EF_LOC(3) out vec4 vBaseColor;
            EF_LOC(4) out vec4 vPbrFlags;
            EF_LOC(5) out vec3 vEmissive;

            void main() {
                int baseTexel = (EF_INSTANCE_ID + uBaseInstance) * 8;
                vec4 c0 = EF_REC_V4(uInstanceTboF, baseTexel + 0);
                vec4 c1 = EF_REC_V4(uInstanceTboF, baseTexel + 1);
                vec4 c2 = EF_REC_V4(uInstanceTboF, baseTexel + 2);
                vec4 c3 = EF_REC_V4(uInstanceTboF, baseTexel + 3);
                mat4 model = mat4(c0, c1, c2, c3);

                vec4 t4 = EF_REC_V4(uInstanceTboF, baseTexel + 4);
                vec4 t5 = EF_REC_V4(uInstanceTboF, baseTexel + 5);
                vec4 t6f = EF_REC_V4(uInstanceTboF, baseTexel + 6);
                mat3 normalMat = mat3(
                    t4.x, t4.y, t4.z,
                    t4.w, t5.x, t5.y,
                    t5.z, t5.w, t6f.x
                );

                ivec4 t6i = EF_REC_I4(uInstanceTboI, baseTexel + 6);
                ivec4 t7i = EF_REC_I4(uInstanceTboI, baseTexel + 7);

                vec4 worldPos = model * vec4(aPos, 1.0);
                vWorldPos = worldPos.xyz;
                vWorldNorm = normalize(normalMat * aNorm);
                vUv = aUv;

                int argb = t7i.x;
                float ca = float((argb >> 24) & 0xFF) / 255.0;
                float cr = float((argb >> 16) & 0xFF) / 255.0;
                float cg = float((argb >> 8) & 0xFF) / 255.0;
                float cb = float(argb & 0xFF) / 255.0;
                vBaseColor = vec4(cr, cg, cb, ca);

                int pbr = t7i.y;
                float metallic = float(pbr & 0xFF) / 255.0;
                float roughness = float((pbr >> 8) & 0xFF) / 255.0;
                float blendCode = float((pbr >> 16) & 0xFF);
                float flags = float(t6i.w);
                vPbrFlags = vec4(metallic, roughness, blendCode, flags);

                int em = t7i.z;
                float er = float((em >> 16) & 0xFF) / 255.0;
                float eg = float((em >> 8) & 0xFF) / 255.0;
                float eb = float(em & 0xFF) / 255.0;
                float eStrength = (float((em >> 24) & 0xFF) / 255.0) * 4.0;
                vEmissive = vec3(er, eg, eb) * eStrength;

                gl_Position = uViewProj * worldPos;
            }
            """;

    /** 3D（モード A）のフラグメントシェーダー。 */
    public static final String SCENE3D_FRAGMENT = """
            EF_LOC(0) in vec3 vWorldPos;
            EF_LOC(1) in vec3 vWorldNorm;
            EF_LOC(2) in vec2 vUv;
            EF_LOC(3) in vec4 vBaseColor;
            EF_LOC(4) in vec4 vPbrFlags;
            EF_LOC(5) in vec3 vEmissive;

            EF_UBO(0, PassParams) {
                mat4 uViewProj;
                vec4 uCameraPos;
                vec4 uLightDir;
                vec4 uLightColor;
                vec4 uAmbientColor;
                int uBaseInstance;
            };

            EF_LOC(0) out vec4 fragColor;

            void main() {
                float alpha = vBaseColor.a;
                int blendCode = int(vPbrFlags.z + 0.5);
                if (blendCode == 1 && alpha < 0.5) {
                    discard;
                }
                int flags = int(vPbrFlags.w + 0.5);
                bool unlit = (flags & 2) != 0;

                vec3 rgb;
                if (unlit) {
                    rgb = vBaseColor.rgb + vEmissive;
                } else {
                    // Cook-Torrance（GGX 法線分布・Smith 相関の可視性・Schlick のフレネル）。
                    // 明るさは「白い拡散面に光量 1 を当てると基本色になる」基準（BRDF に π を掛けて戻す）。
                    // ソフトウェア版（SoftwareScene3DRasterizer.Shader）と同じ式。変えるときは両方を直す。
                    vec3 N = normalize(vWorldNorm);
                    if (!gl_FrontFacing) N = -N;
                    vec3 L = normalize(-uLightDir.xyz);
                    vec3 V = normalize(uCameraPos.xyz - vWorldPos);
                    vec3 H = normalize(L + V);
                    float ndl = max(dot(N, L), 0.0);
                    float ndv = max(dot(N, V), 1e-4);
                    float ndh = max(dot(N, H), 0.0);
                    float vdh = max(dot(V, H), 0.0);

                    float metallic = vPbrFlags.x;
                    float roughness = clamp(vPbrFlags.y, 0.05, 1.0);
                    float a2 = roughness * roughness * roughness * roughness;
                    float dd = ndh * ndh * (a2 - 1.0) + 1.0;
                    float D = a2 / (3.14159265 * dd * dd);
                    float vis = 0.5 / (ndl * sqrt(ndv * ndv * (1.0 - a2) + a2) + ndv * sqrt(ndl * ndl * (1.0 - a2) + a2));
                    vec3 f0 = mix(vec3(0.04), vBaseColor.rgb, metallic);
                    float fw = pow(1.0 - vdh, 5.0);
                    vec3 F = f0 + (1.0 - f0) * fw;

                    vec3 kd = (vec3(1.0) - F) * (1.0 - metallic);
                    vec3 direct = (kd * vBaseColor.rgb + D * vis * F * 3.14159265) * uLightColor.xyz * ndl;
                    // 環境光: 拡散は基本色、鏡面は視線に応じたフレネルで簡易に（IBL は無い）
                    vec3 fEnv = f0 + (max(vec3(1.0 - roughness), f0) - f0) * pow(1.0 - ndv, 5.0);
                    vec3 ambient = uAmbientColor.xyz * (vBaseColor.rgb * (1.0 - metallic) + fEnv * (1.0 - roughness * 0.5));
                    vec3 diffuse = ambient;
                    vec3 spec = direct;
                    rgb = diffuse + spec + vEmissive;
                }
                rgb = clamp(rgb, 0.0, 1.0);
                fragColor = vec4(rgb * alpha, alpha);
            }
            """;

}
