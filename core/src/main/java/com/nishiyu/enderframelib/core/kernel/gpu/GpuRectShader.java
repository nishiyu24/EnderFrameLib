package com.nishiyu.enderframelib.core.kernel.gpu;

/**
 * プリミティブ（角丸矩形の塗りと角丸の枠線）を {@link PropertyTrees} から直接描くシェーダー（GLSL 1.50）。GL を知らない文字列だけ。
 *
 * <p>頂点シェーダーは {@code gl_InstanceID} → 描画順の表 → プリミティブのスロットを引き、矩形と変換・効果だけを読んで四角形を置く。
 * 変換は {@link GpuAnimation#GLSL} の {@code efWorld}（アニメ・スクロール込み）、不透明度は {@code efOpacity}。
 * フラグメントシェーダーが、従来の {@code LwjglGlRenderBackend} のシェーダーと同じ式（{@code sdRR}・{@code cover}・
 * 枠線の辺の判定）で被覆率を出し、スロットのレコードから半径・内側の矩形・幅・色を読む。
 * 出力はプリマルチプライドで、合成は {@code glBlendFunc(ONE, ONE_MINUS_SRC_ALPHA)}。</p>
 *
 * <p>レコードの配置は {@link PropertyTrees}（128 B）。float 添字 = バイト / 4、半精度添字 = バイト / 2。クリップ・破線・
 * 傾きのある姿勢は未対応。クリップは最大 4 個（従来経路と同じ上限）。</p>
 */
public final class GpuRectShader {
    private GpuRectShader() {}

    public static final String VERTEX = "#version 150\n" + GpuAnimation.GLSL + """
            uniform samplerBuffer uPrF;
            uniform isamplerBuffer uPrI;
            uniform isamplerBuffer uOrder;
            uniform vec2 uView;
            uniform int uOrderBase;
            uniform float uYSign;
            flat out int vSlot;
            flat out float vScale;
            flat out float vOpacity;
            out vec2 vLocal;
            void main() {
                int slot = texelFetch(uOrder, uOrderBase + gl_InstanceID).r;
                int base = slot * 32;
                int opcode = texelFetch(uPrI, base).r;
                vec4 rect = vec4(texelFetch(uPrF, base + 4).r, texelFetch(uPrF, base + 5).r,
                                 texelFetch(uPrF, base + 6).r, texelFetch(uPrF, base + 7).r);
                int tr = texelFetch(uPrI, base + 1).r;
                int ef = texelFetch(uPrI, base + 3).r;
                vSlot = slot;
                vOpacity = ef >= 0 ? efOpacity(ef) : 1.0;
                vec2 corner = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
                // 描く範囲は形の矩形に余白を足す（斜めの縁の滑らかな画素が矩形の外へはみ出す分。従来のシェーダーと同じ）
                float margin = texelFetch(uPrF, base + 27).r;
                vLocal = (rect.xy - margin) + corner * (rect.zw + 2.0 * margin);
                mat3 m = tr >= 0 ? efWorld(tr) : mat3(1.0);
                vec2 p = (m * vec3(vLocal, 1.0)).xy;
                vScale = sqrt(abs(m[0].x * m[1].y - m[0].y * m[1].x)); // 局所の 1 単位が何画素か（縁の幅を画素で保つ）
                vec2 ndc = p / uView * 2.0 - 1.0;
                gl_Position = opcode == 0 ? vec4(2.0, 2.0, 2.0, 1.0) : vec4(ndc.x, ndc.y * uYSign, 0.0, 1.0);
            }
            """;

    public static final String FRAGMENT = """
            #version 150
            uniform samplerBuffer uPrF;
            uniform isamplerBuffer uPrI;
            uniform samplerBuffer uPrH;
            uniform samplerBuffer uClF;
            uniform samplerBuffer uClH;
            uniform sampler2D uAtlas;
            uniform sampler2D uImgTex;
            uniform vec2 uView;
            uniform float uYSign;
            flat in int vSlot;
            flat in float vScale;
            flat in float vOpacity;
            in vec2 vLocal;
            out vec4 o;

            vec4 half4(int i) {
                int b = vSlot * 64 + i;
                return vec4(texelFetch(uPrH, b).r, texelFetch(uPrH, b + 1).r, texelFetch(uPrH, b + 2).r, texelFetch(uPrH, b + 3).r);
            }
            vec4 color(int k) {
                int c = texelFetch(uPrI, vSlot * 32 + 22 + k).r;
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
            float extF(int i) { return texelFetch(uPrF, (vSlot + 1) * 32 + i).r; }
            vec4 extF4(int i) { return vec4(extF(i), extF(i + 1), extF(i + 2), extF(i + 3)); }
            // クリップ: 従来のシェーダーと同じく、デバイス画素を持ち主の空間へ逆写像して角丸矩形の被覆率を掛ける
            float clipCoverage() {
                int n = texelFetch(uPrI, vSlot * 32 + 2).r;
                if (n <= 0) return 1.0;
                vec2 dev = vec2(gl_FragCoord.x, uYSign < 0.0 ? uView.y - gl_FragCoord.y : gl_FragCoord.y);
                float cov = 1.0;
                for (int i = 0; i < 4 && i < n; i++) {
                    int w = texelFetch(uPrI, vSlot * 32 + 30 + (i >> 1)).r;
                    int id = (i & 1) == 0 ? (w & 65535) : ((w >> 16) & 65535); // 16 ビット ×4（リトルエンディアン）
                    int cb = id * 16;
                    vec4 A = vec4(texelFetch(uClF, cb).r, texelFetch(uClF, cb + 1).r, texelFetch(uClF, cb + 2).r, texelFetch(uClF, cb + 3).r);
                    vec3 T = vec3(texelFetch(uClF, cb + 4).r, texelFetch(uClF, cb + 5).r, texelFetch(uClF, cb + 6).r);
                    vec4 R = vec4(texelFetch(uClF, cb + 8).r, texelFetch(uClF, cb + 9).r, texelFetch(uClF, cb + 10).r, texelFetch(uClF, cb + 11).r);
                    int hb = id * 32 + 24;
                    vec4 rx = vec4(texelFetch(uClH, hb).r, texelFetch(uClH, hb + 1).r, texelFetch(uClH, hb + 2).r, texelFetch(uClH, hb + 3).r);
                    vec4 ry = vec4(texelFetch(uClH, hb + 4).r, texelFetch(uClH, hb + 5).r, texelFetch(uClH, hb + 6).r, texelFetch(uClH, hb + 7).r);
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
            float glyphCov(ivec2 c, vec4 gl) {
                if (c.x < 0 || c.y < 0 || c.x >= int(gl.z) || c.y >= int(gl.w)) return 0.0;
                return texelFetch(uAtlas, ivec2(gl.xy) + c, 0).r;
            }
            void main() {
                int base = vSlot * 32;
                int op = texelFetch(uPrI, base).r;
                vec4 xywh = vec4(texelFetch(uPrF, base + 4).r, texelFetch(uPrF, base + 5).r,
                                 texelFetch(uPrF, base + 6).r, texelFetch(uPrF, base + 7).r);
                vec4 rect = vec4(xywh.xy, xywh.xy + xywh.zw);
                vec4 rx = half4(16), ry = half4(20);
                vec4 col = color(0);
                float a;
                if (op == 6) {
                    // 字形（デバイス画素の格子に揃えた文字）: アトラスの被覆率をそのまま引く
                    int w0 = texelFetch(uPrI, base + 28).r, w1 = texelFetch(uPrI, base + 29).r;
                    vec4 gl = vec4(float(w0 & 65535), float((w0 >> 16) & 65535), float(w1 & 65535), float((w1 >> 16) & 65535));
                    ivec2 gp = ivec2(floor(vLocal - rect.xy));
                    if (gp.x < 0 || gp.y < 0 || gp.x >= int(gl.z) || gp.y >= int(gl.w)) discard;
                    a = texelFetch(uAtlas, ivec2(gl.xy) + gp, 0).r;
                } else if (op == 10) {
                    // 文字の縁取り: SDF の字形（128 = 輪郭）から、輪郭を中心にした帯を描く
                    vec4 gl = extF4(0);
                    ivec2 gp = ivec2(floor(vLocal - rect.xy));
                    if (gp.x < 0 || gp.y < 0 || gp.x >= int(gl.z) || gp.y >= int(gl.w)) discard;
                    float sv = texelFetch(uAtlas, ivec2(gl.xy) + gp, 0).r * 255.0;
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
                    if ((texelFetch(uPrI, (vSlot + 1) * 32 + 9).r & 1) != 0) a *= 1.0 - cover(sdRR(vLocal, extF4(4), extF4(18), extF4(22)), vScale);
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
                    vec4 inner = vec4(texelFetch(uPrF, base + 12).r, texelFetch(uPrF, base + 13).r,
                                      texelFetch(uPrF, base + 14).r, texelFetch(uPrF, base + 15).r);
                    int strokeFlags = texelFetch(uPrI, base + 26).r;
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
}
