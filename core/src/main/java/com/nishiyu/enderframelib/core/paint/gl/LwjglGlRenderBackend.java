package com.nishiyu.enderframelib.core.paint.gl;

import com.nishiyu.enderframelib.core.paint.BlendMode;
import com.nishiyu.enderframelib.core.paint.ClipShape;
import com.nishiyu.enderframelib.core.paint.FilterOp;
import com.nishiyu.enderframelib.core.paint.RenderBackend;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ShaderGradient;
import com.nishiyu.enderframelib.core.paint.software.GpuSink;
import com.nishiyu.enderframelib.core.paint.software.ImageProvider;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.text.font.GlyphBitmap;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minecraft（Blaze3D）に依存せず、LWJGL（GLFW + OpenGL 3.2 core）だけで動く GPU 描画バックエンド。
 * GLFW のウィンドウ（見えない物でもよい）に GL コンテキストを作り、フレームバッファオブジェクトに描く。
 *
 * <p><b>何を GPU が描くか</b>: 単色の角丸矩形（背景・進捗バー・つまみなど。楕円の角を含む）と角丸の枠線を、
 * SDF のフラグメントシェーダー 1 本で描く（クリップの連鎖・変形・不透明度は GPU 側で掛ける）。
 * グラデーション・影・字形（回転なし）・画像（縮小でなければ）も GPU が描く。
 * 回転した文字・縮小した画像・破線の枠線などは {@link SoftwareRenderBackend} が CPU で描いた層を、
 * 描画順を保ったままテクスチャとして重ねる（変わった範囲だけを転送する）。したがって画は
 * {@link SoftwareRenderBackend} と同じで、図形の多いページほど CPU の仕事が減る。
 *
 * <p>GL のコンテキストは作ったスレッドに結び付く。{@link #render}・{@link #pixmap}・{@link #close} はそのスレッドから呼ぶ。
 * Minecraft の中（すでに GL が初期化された JVM）では使わないこと。
 */
public final class LwjglGlRenderBackend implements RenderBackend, GpuSink {

    private static int liveInstances = 0;
    private static GLFWErrorCallback errorCallback;

    /**
     * 図形 1 つ分のパラメータは「データテクスチャ」の 1 行（{@link #STRIDE} テクセル）に詰め、
     * インスタンス描画で 1 回の draw にまとめる。頂点シェーダーはインスタンス番号で行を引く。
     * テクセルの並び: 0 範囲 / 1 姿勢 a,b,c,d / 2 姿勢 e,f,拡大率,不透明度 / 3 モード,内側空,穴,クリップ数 /
     * 4 矩形 / 5,6 rx,ry / 7 内側矩形 / 8,9 内側 rx,ry / 10 枠幅 / 11-14 枠色 / 15 色 / 16 σ,繰り返し原点,周期 /
     * 17,18 グラデ A,B / 19,20 グラデ色 / 21 字形 / 22 画像 / 23 画像寸法 / 24-43 クリップ 4 段 × 5。
     */
    private static final String SHAPE_VERT = """
            #version 150
            uniform sampler2D uData;
            uniform vec2 uView;
            flat out int vInst;
            out vec2 vLocal;
            void main() {
                vec4 b = texelFetch(uData, ivec2(0, gl_InstanceID), 0);
                vec4 pa = texelFetch(uData, ivec2(1, gl_InstanceID), 0);
                vec4 pt = texelFetch(uData, ivec2(2, gl_InstanceID), 0);
                vec2 c = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
                vec2 l = mix(b.xy, b.zw, c);
                vec2 d = vec2(pa.x * l.x + pa.z * l.y + pt.x, pa.y * l.x + pa.w * l.y + pt.y);
                gl_Position = vec4(d.x / uView.x * 2.0 - 1.0, 1.0 - d.y / uView.y * 2.0, 0.0, 1.0);
                vLocal = l;
                vInst = gl_InstanceID;
            }
            """;

    /** 角丸矩形の符号付き距離と被覆率は {@code SoftShapes} と同じ式（CPU 版と画素を揃えるため）。 */
    private static final String SHAPE_FRAG = """
            #version 150
            in vec2 vLocal;
            flat in int vInst;
            uniform vec2 uView;
            uniform sampler2D uData;
            uniform sampler2D uAtlas;
            uniform sampler2D uImgTex;
            #define F(k) texelFetch(uData, ivec2(k, vInst), 0)
            out vec4 fragColor;

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

            float quarterArc(float rx, float ry) {
                if (rx <= 0.0 || ry <= 0.0) return 0.0;
                return 0.25 * 3.14159265 * (3.0 * (rx + ry) - sqrt((3.0 * rx + ry) * (rx + 3.0 * ry)));
            }

            // BorderPath.param と同じ: 中心線上の最も近い位置（箱の中心が原点）
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

            float cover(float sd, float scale) {
                return clamp(0.5 - sd * scale, 0.0, 1.0);
            }

            void main() {
                vec4 t2 = F(2), t3 = F(3), t16 = F(16);
                float uScale = t2.z;
                float uOpacity = t2.w;
                int uMode = int(t3.x + 0.5);
                int uInnerEmpty = int(t3.y + 0.5);
                int uHole = int(t3.z + 0.5);
                int uClipN = int(t3.w + 0.5);
                vec4 uRect = F(4), uRx = F(5), uRy = F(6);
                vec4 uInner = F(7), uIRx = F(8), uIRy = F(9);
                vec4 uWidths = F(10);
                vec4 uColor = F(15);
                float uSigma = t16.x;
                vec2 uRep = t16.yz;
                vec4 uGradA = F(17), uGradB = F(18), uGC0 = F(19), uGC1 = F(20);
                vec4 uGlyph = F(21), uImg = F(22);
                vec2 uImgSize = F(23).xy;
                float a;
                vec4 col = uColor;
                if (uMode == 5) {
                    ivec2 gp = ivec2(floor(vLocal - uRect.xy));
                    if (gp.x < 0 || gp.y < 0 || gp.x >= int(uGlyph.z) || gp.y >= int(uGlyph.w)) discard;
                    a = texelFetch(uAtlas, ivec2(uGlyph.xy) + gp, 0).r;
                } else if (uMode == 9) {
                    vec4 gi = F(22), g2 = F(23);
                    float gcx = (vLocal.x - gi.x) * gi.z - g2.x - 0.5, gcy = (vLocal.y - gi.y) * gi.z + g2.y - 0.5;
                    ivec2 c0 = ivec2(floor(vec2(gcx, gcy)));
                    vec2 fr = vec2(gcx, gcy) - vec2(c0);
                    float v00 = glyphCov(c0, uGlyph), v10 = glyphCov(c0 + ivec2(1, 0), uGlyph);
                    float v01 = glyphCov(c0 + ivec2(0, 1), uGlyph), v11 = glyphCov(c0 + ivec2(1, 1), uGlyph);
                    a = (v00 * (1.0 - fr.x) + v10 * fr.x) * (1.0 - fr.y) + (v01 * (1.0 - fr.x) + v11 * fr.x) * fr.y;
                } else if (uMode == 6) {
                    a = cover(sdRR(vLocal, uRect, uRx, uRy), uScale);
                    if (a <= 0.0) discard;
                    vec2 uv = (vLocal - uImg.xy) * uImg.zw;
                    if (uv.x < -0.5 || uv.y < -0.5 || uv.x > uImgSize.x + 0.5 || uv.y > uImgSize.y + 0.5) discard;
                    vec4 tx;
                    int taps = int(F(23).z + 0.5);
                    if (taps <= 1) {
                        tx = texture(uImgTex, uv / uImgSize);
                    } else {
                        vec2 stp = F(48).xy;
                        tx = vec4(0.0);
                        for (int j = 0; j < taps; j++) {
                            for (int i = 0; i < taps; i++) {
                                vec2 off = (vec2(float(i), float(j)) + 0.5) / float(taps) - 0.5;
                                tx += texture(uImgTex, (uv + off * stp) / uImgSize);
                            }
                        }
                        tx /= float(taps * taps);
                    }
                    if (tx.a <= 0.0) discard;
                    col = vec4(tx.rgb / tx.a, tx.a);
                } else if (uMode == 8) {
                    float dist = length(vLocal - uRect.xy);
                    a = cover(dist - uRect.z, uScale) * (uRect.w > 0.0 ? 1.0 - cover(dist - uRect.w, uScale) : 1.0);
                    if (a <= 0.0) discard;
                    vec2 dd = vLocal - uRect.xy;
                    int cs = abs(dd.y) >= abs(dd.x) ? (dd.y < 0.0 ? 0 : 2) : (dd.x > 0.0 ? 1 : 3);
                    col = F(11 + cs);
                } else if (uMode == 3) {
                    a = blurredEdge(sdRR(vLocal, uRect, uRx, uRy), uSigma);
                    if (a <= 0.0) discard;
                    if (uHole != 0) a *= 1.0 - cover(sdRR(vLocal, uInner, uIRx, uIRy), uScale);
                } else if (uMode == 4) {
                    a = cover(sdRR(vLocal, uRect, uRx, uRy), uScale);
                    if (a <= 0.0) discard;
                    a *= blurredEdge(-sdRR(vLocal, uInner, uIRx, uIRy), uSigma);
                } else {
                    a = cover(sdRR(vLocal, uRect, uRx, uRy), uScale);
                }
                if (a <= 0.0) discard;
                if (uMode == 2) {
                    int g = int(floor(uGradA.w + 0.5));
                    int kind = g % 10;
                    int bounds = g / 10;
                    float u;
                    if (kind == 1) {
                        u = vLocal.x * uGradA.x + vLocal.y * uGradA.y + uGradA.z;
                    } else if (kind == 2) {
                        u = length(vec2((vLocal.x - uGradA.x) * uGradA.z, (vLocal.y - uGradA.y) * uGradB.x));
                    } else {
                        float v = atan(vLocal.x - uGradA.x, -(vLocal.y - uGradA.y)) * 0.15915494 - uGradA.z;
                        u = v - floor(v);
                    }
                    if (uRep.y > 0.0) {
                        float d = u - uRep.x;
                        u = uRep.x + (d - uRep.y * floor(d / uRep.y));
                    }
                    float t = (u - uGradB.y) * uGradB.z;
                    if (((bounds & 1) != 0 && t < -0.001) || ((bounds & 2) != 0 && t > 1.001)) discard;
                    t = clamp(t, 0.0, 1.0);
                    if (uGradB.w != 1.0) t = pow(t, uGradB.w);
                    float ga = mix(uGC0.a, uGC1.a, t);
                    if (ga <= 0.0) discard;
                    col = vec4(mix(uGC0.rgb * uGC0.a, uGC1.rgb * uGC1.a, t) / ga, ga);
                }
                if (uMode == 1) {
                    float inner = uInnerEmpty != 0 ? 0.0 : cover(sdRR(vLocal, uInner, uIRx, uIRy), uScale);
                    a *= 1.0 - inner;
                    if (a <= 0.0) discard;
                    float d[4] = float[4](vLocal.y - uRect.y, uRect.z - vLocal.x, uRect.w - vLocal.y, vLocal.x - uRect.x);
                    int side = -1;
                    float best = 3.4e38;
                    for (int s = 0; s < 4; s++) {
                        if (uWidths[s] <= 0.0) continue;
                        float v = d[s] / uWidths[s];
                        if (v < best) { best = v; side = s; }
                    }
                    if (side < 0) discard;
                    col = F(11 + side);
                    if (uHole != 0) {
                        vec4 t44 = F(44), t47 = F(47);
                        float s = pathParam(vLocal - t44.zw, t44.xy, F(45), F(46));
                        float period = t47.x;
                        float phase = s - period * floor(s / period);
                        float along = abs(phase - period * 0.5);
                        float on;
                        if (t47.z > 0.5) {
                            float perp = abs(sdRR(vLocal, uRect, uRx, uRy) + t47.w * 0.5);
                            on = clamp(t47.y * 0.5 - length(vec2(along, perp)) + 0.5, 0.0, 1.0);
                        } else {
                            on = clamp(t47.y * 0.5 - along + 0.5, 0.0, 1.0);
                        }
                        a *= on;
                        if (a <= 0.0) discard;
                    }
                }
                vec2 dev = vec2(gl_FragCoord.x, uView.y - gl_FragCoord.y);
                for (int i = 0; i < uClipN; i++) {
                    vec4 A = F(24 + i * 5);
                    vec4 T = F(25 + i * 5);
                    vec2 lp = vec2(A.x * dev.x + A.z * dev.y + T.x, A.y * dev.x + A.w * dev.y + T.y);
                    a *= cover(sdRR(lp, F(26 + i * 5), F(27 + i * 5), F(28 + i * 5)), T.z);
                    if (a <= 0.0) discard;
                }
                a *= uOpacity * col.a;
                if (a <= 0.0) discard;
                fragColor = vec4(col.rgb, a);
            }
            """;

    private static final String TEX_VERT = """
            #version 150
            uniform vec4 uDev;
            uniform vec2 uView;
            void main() {
                vec2 c = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
                vec2 d = mix(uDev.xy, uDev.zw, c);
                gl_Position = vec4(d.x / uView.x * 2.0 - 1.0, 1.0 - d.y / uView.y * 2.0, 0.0, 1.0);
            }
            """;

    /** 事前乗算の画素を 1 対 1 で写す（拡縮・補間なし）。 */
    private static final String TEX_FRAG = """
            #version 150
            uniform sampler2D uTex;
            uniform vec2 uView;
            uniform float uOpacity;
            uniform int uFlip;
            out vec4 fragColor;
            void main() {
                int py = uFlip != 0 ? int(uView.y) - 1 - int(floor(gl_FragCoord.y)) : int(floor(gl_FragCoord.y));
                fragColor = texelFetch(uTex, ivec2(int(floor(gl_FragCoord.x)), py), 0) * uOpacity;
            }
            """;

    /** 層の合成（色の行列・ぼかし・影・マスク・不透明度）。単位 0 が入力、単位 4 が補助（ぼかした絵かマスク）。 */
    private static final String FX_FRAG = """
            #version 150
            uniform sampler2D uSrc;
            uniform sampler2D uAux;
            uniform vec2 uView;
            uniform int uMode;
            uniform float uOpacity;
            uniform int uHasMatrix;
            uniform vec4 uRow0;
            uniform vec4 uRow1;
            uniform vec4 uRow2;
            uniform vec2 uDir;
            uniform float uSigma;
            uniform int uTaps;
            uniform float uStep;
            uniform vec2 uShadowOffset;
            uniform vec4 uShadowColor;
            uniform int uMaskMode;
            uniform sampler2D uBackdrop;
            uniform int uBlend;
            uniform vec4 uInvM;        // 箱の座標へ戻す変換 {a, b, c, d}
            uniform vec2 uInvT;        // {e, f}
            uniform float uShapeScale; // 箱の 1 単位が何画素か
            uniform int uClipKind;     // 0 なし, 1 inset, 2 circle, 3 ellipse, 4 polygon
            uniform int uEvenOdd;
            uniform int uClipN;
            uniform vec4 uClipD[96];
            uniform int uHasB;         // backdrop-filter の形（inset の 12 値）があるか
            uniform vec4 uBD[3];
            out vec4 fragColor;

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
                vec2 dev = vec2(gl_FragCoord.x, uView.y - gl_FragCoord.y);
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
                    vec4 sum = texture(uSrc, uv);
                    float wsum = 1.0;
                    for (int i = 1; i <= uTaps; i++) {
                        float d = float(i) * uStep;
                        float w = exp(-0.5 * d * d / (uSigma * uSigma));
                        vec2 o = uDir / uView * d;
                        sum += (texture(uSrc, uv + o) + texture(uSrc, uv - o)) * w;
                        wsum += 2.0 * w;
                    }
                    fragColor = sum / wsum;
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

    private final int width;
    private final int height;
    private final float dpr;
    private final SoftwareRenderBackend cpu;
    private final Pixmap readback;
    private final Map<String, Integer> uniforms = new HashMap<>();

    private static final int ATLAS_SIZE = 1024;
    private static final int IMAGE_CACHE_LIMIT = 64;
    /** 字形アトラス（R8）。棚詰め。満ちたら空にして詰め直す（字形は描く直前に載せるので、すでに描いた分には響かない）。 */
    private int atlasTex;
    private int atlasX, atlasY, atlasRowH;
    private final Map<GlyphBitmap, int[]> atlasEntries = new IdentityHashMap<>();
    private final Map<Pixmap, int[]> imageTex = new IdentityHashMap<>();
    private long gpuGlyphs, gpuImages;

    /** 1 インスタンスのテクセル数（データテクスチャの幅）と、1 回の draw にまとめる最大数（高さ）。 */
    private static final int STRIDE = 64;
    private static final int MAX_BATCH = 512;
    private final float[] batch = new float[STRIDE * MAX_BATCH * 4];
    private int batchN;
    private int base;
    private int dataTex;
    private int boundImg;
    private long drawCalls;

    private long window;
    /** 0 でなければ、この GL コンテキストとオブジェクト（テクスチャ）を共有して作った（ホストのウィンドウ）。 */
    private final long shareWith;
    /** GLFW の初期化・終了・エラーコールバックをホスト（Minecraft など）が持っているか。 */
    private final boolean hostManaged;
    private GLCapabilities myCaps;
    private int enterDepth;
    private long prevContext;
    private GLCapabilities prevCaps;
    private int fbo, fboTex, overlayTex, vao, shapeProgram, texProgram;
    private int clearPremul = 0;
    private boolean readbackValid;
    private boolean closed;
    private long gpuShapes;

    /** CSS px の大きさ {@code cssW × cssH}・等倍・見えないウィンドウで作る。GL が使えなければ {@link IllegalStateException}。 */
    public LwjglGlRenderBackend(int cssW, int cssH) {
        this(cssW, cssH, 1f, false, "EnderFrame");
    }

    public LwjglGlRenderBackend(int cssW, int cssH, float devicePixelRatio) {
        this(cssW, cssH, devicePixelRatio, false, "EnderFrame");
    }

    /**
     * @param visible {@code true} なら画面にウィンドウを出す（{@link #present} で映す）。{@code false} は画面外の描画だけ。
     */
    public LwjglGlRenderBackend(int cssW, int cssH, float devicePixelRatio, boolean visible, String title) {
        this(cssW, cssH, devicePixelRatio, visible, title, MemoryUtil.NULL);
    }

    /**
     * ホストアプリ（Minecraft など）の GL コンテキストとテクスチャを共有する、見えないコンテキストで作る。
     * 結果は {@link #colorTexture()} でホスト側の GL からそのまま引ける。GLFW はホストが初期化済みとみなし、
     * 初期化・終了・エラーコールバックには触れない。{@link #render} などの呼び出しの前後で、
     * 呼び出し元の現在のコンテキストと LWJGL の {@code GLCapabilities} を元に戻す（ホストの GL 状態の
     * キャッシュを壊さない）。ホストと同じスレッド（GL スレッド）から呼ぶこと。
     *
     * @param hostWindow ホストの GLFW ウィンドウのハンドル（そのコンテキストが現在でなくてもよい）
     */
    public static LwjglGlRenderBackend sharedWith(long hostWindow, int cssW, int cssH, float devicePixelRatio) {
        if (hostWindow == MemoryUtil.NULL) throw new IllegalArgumentException("hostWindow is null");
        return new LwjglGlRenderBackend(cssW, cssH, devicePixelRatio, false, "EnderFrame", hostWindow);
    }

    private LwjglGlRenderBackend(int cssW, int cssH, float devicePixelRatio, boolean visible, String title, long shareWith) {
        this.shareWith = shareWith;
        this.hostManaged = shareWith != MemoryUtil.NULL;
        if (!(devicePixelRatio > 0f)) throw new IllegalArgumentException("devicePixelRatio must be > 0");
        this.dpr = devicePixelRatio;
        this.width = Math.max(1, Math.round(cssW * devicePixelRatio));
        this.height = Math.max(1, Math.round(cssH * devicePixelRatio));
        this.cpu = new SoftwareRenderBackend(cssW, cssH, devicePixelRatio);
        this.readback = new Pixmap(width, height);
        long outerContext = GLFW.glfwGetCurrentContext();
        GLCapabilities outerCaps = currentCaps();
        try {
            initContext(visible, title == null ? "EnderFrame" : title);
            initGl();
            myCaps = GL.getCapabilities();
            if (hostManaged) GL11.glFinish();
        } catch (RuntimeException | Error e) {
            if (hostManaged) restoreContext(outerContext, outerCaps);
            destroy();
            throw e;
        }
        if (hostManaged) restoreContext(outerContext, outerCaps);
    }

    private static GLCapabilities currentCaps() {
        try {
            return GL.getCapabilities();
        } catch (IllegalStateException none) {
            return null;
        }
    }

    private static void restoreContext(long context, GLCapabilities caps) {
        GLFW.glfwMakeContextCurrent(context);
        GL.setCapabilities(caps);
    }

    /** このバックエンドのコンテキストを現在にする（入れ子可）。抜けるときに {@link #exit} で呼び出し元へ戻す。 */
    private void enter() {
        if (enterDepth++ > 0) return;
        prevContext = GLFW.glfwGetCurrentContext();
        prevCaps = prevContext != MemoryUtil.NULL ? currentCaps() : null;
        if (prevContext != window) GLFW.glfwMakeContextCurrent(window);
        if (myCaps != null) GL.setCapabilities(myCaps);
    }

    private void exit() {
        if (--enterDepth > 0) return;
        enterDepth = 0;
        if (hostManaged) {
            restoreContext(prevContext, prevCaps);
        }
    }

    /**
     * 直近のフレームを入れた GL テクスチャ（事前乗算・BGRA8・下端が先頭の GL の向き）。
     * {@link #sharedWith} で作ったときは、ホストのコンテキストからそのまま使える。
     * 描き直すたびに中身は変わるが、閉じるまで id は変わらない。
     */
    public int colorTexture() {
        return fboTex;
    }

    /** {@link #colorTexture()} の幅（画素）。 */
    public int textureWidth() {
        return width;
    }

    /** {@link #colorTexture()} の高さ（画素）。 */
    public int textureHeight() {
        return height;
    }

    /** 溜まった GL 命令をすべて終わらせる。別のコンテキストが {@link #colorTexture()} を読む前に必要（共有時は {@link #render} が呼ぶ）。 */
    public void finish() {
        if (closed) return;
        enter();
        try {
            GL11.glFinish();
        } finally {
            exit();
        }
    }

    // ------------------------------------------------------------------ 設定

    public LwjglGlRenderBackend fonts(FontResolver resolver) {
        cpu.fonts(resolver);
        return this;
    }

    @Override
    public String sessionId() {
        return cpu.sessionId();
    }

    public LwjglGlRenderBackend images(ImageProvider provider) {
        cpu.images(provider);
        return this;
    }

    /** フレームの最初に塗る色（非乗算の ARGB。既定は透明）。 */
    public LwjglGlRenderBackend clearColor(int argb) {
        this.clearPremul = Pixmap.premultiply(argb);
        return this;
    }

    @Override
    public float devicePixelRatio() {
        return dpr;
    }

    /** 直近の {@link #render} で GPU が描いた図形（塗り・枠線）の数の累計。CPU に回った分は含まない。 */
    public long gpuShapeCount() {
        return gpuShapes;
    }

    /** GPU が描いた字形の数の累計（字形アトラスから引いた分）。 */
    public long gpuGlyphCount() {
        return gpuGlyphs;
    }

    /** GPU が描いた画像の数の累計。 */
    public long gpuImageCount() {
        return gpuImages;
    }

    /** これまでに発行した図形の draw の数（バッチ化で、図形の数よりずっと少なくなる）。CPU 層の転送は含まない。 */
    public long drawCallCount() {
        return drawCalls;
    }

    /** GLFW のウィンドウのハンドル（見えないウィンドウでも有効）。入力のコールバック登録などに使う。 */
    public long windowHandle() {
        return window;
    }

    // ------------------------------------------------------------------ コンテキスト

    private void initContext(boolean visible, String title) {
        if (!hostManaged) synchronized (LwjglGlRenderBackend.class) {
            if (liveInstances == 0) {
                errorCallback = GLFWErrorCallback.createPrint(System.err);
                GLFW.glfwSetErrorCallback(errorCallback);
                if (!GLFW.glfwInit()) {
                    throw new IllegalStateException("GLFW を初期化できない（ディスプレイが無い環境かもしれない）");
                }
            }
            liveInstances++;
        }
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, visible ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_FALSE);
        int winW = Math.max(1, Math.round(width / dpr)), winH = Math.max(1, Math.round(height / dpr));
        window = GLFW.glfwCreateWindow(winW, winH, title, MemoryUtil.NULL, shareWith);
        if (window == MemoryUtil.NULL) {
            throw new IllegalStateException("OpenGL 3.2 のコンテキストを作れない");
        }
        GLFW.glfwMakeContextCurrent(window);
        if (!hostManaged) GLFW.glfwSwapInterval(1);
        GL.createCapabilities();
    }

    private void initGl() {
        vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vao);

        fboTex = newTexture(null);
        overlayTex = newTexture(null);
        fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, fboTex, 0);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("フレームバッファが不完全: 0x" + Integer.toHexString(status));
        }
        shapeProgram = link(SHAPE_VERT, SHAPE_FRAG);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        atlasTex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlasTex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, ATLAS_SIZE, ATLAS_SIZE, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL20.glUseProgram(shapeProgram);
        GL20.glUniform1i(loc(shapeProgram, "uAtlas"), 1);
        GL20.glUniform1i(loc(shapeProgram, "uImgTex"), 2);
        GL20.glUniform1i(loc(shapeProgram, "uData"), 3);
        GL13.glActiveTexture(GL13.GL_TEXTURE3);
        dataTex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, dataTex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, STRIDE, MAX_BATCH, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (java.nio.FloatBuffer) null);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        texProgram = link(TEX_VERT, TEX_FRAG);
        fxProgram = link(TEX_VERT, FX_FRAG);
    }

    private int newTexture(IntBuffer data) {
        int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL12.GL_BGRA,
                GL12.GL_UNSIGNED_INT_8_8_8_8_REV, data);
        return tex;
    }

    private static int compile(int type, String source) {
        int id = GL20.glCreateShader(type);
        GL20.glShaderSource(id, source);
        GL20.glCompileShader(id);
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(id);
            GL20.glDeleteShader(id);
            throw new IllegalStateException("シェーダーのコンパイルに失敗: " + log);
        }
        return id;
    }

    private static int link(String vert, String frag) {
        int v = compile(GL20.GL_VERTEX_SHADER, vert), f = compile(GL20.GL_FRAGMENT_SHADER, frag);
        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, v);
        GL20.glAttachShader(p, f);
        GL20.glLinkProgram(p);
        GL20.glDeleteShader(v);
        GL20.glDeleteShader(f);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(p);
            GL20.glDeleteProgram(p);
            throw new IllegalStateException("シェーダーのリンクに失敗: " + log);
        }
        return p;
    }

    private int loc(int program, String name) {
        return uniforms.computeIfAbsent(program + ":" + name, k -> GL20.glGetUniformLocation(program, name));
    }

    // ------------------------------------------------------------------ フレーム

    @Override
    public void render(List<DisplayItem> items, long nowMs) {
        if (closed) throw new IllegalStateException("closed");
        enter();
        try {
            renderLocked(items, nowMs);
        } finally {
            exit();
        }
    }

    private void renderLocked(List<DisplayItem> items, long nowMs) {
        GL30.glBindVertexArray(vao);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL11.glViewport(0, 0, width, height);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        float ca = ((clearPremul >>> 24) & 0xFF) / 255f, cr = ((clearPremul >> 16) & 0xFF) / 255f;
        float cg = ((clearPremul >> 8) & 0xFF) / 255f, cb = (clearPremul & 0xFF) / 255f;
        GL11.glClearColor(cr, cg, cb, ca);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        readbackValid = false;
        cpu.clearDirty();
        batchN = 0;
        boundImg = 0;
        layerStack.clear();
        layerPool.clear();
        layerPool.addAll(allLayers);
        cpu.renderOnto(items, nowMs, this);
        while (!layerStack.isEmpty()) endGroup(); // 例外で閉じ損ねた層があっても根の描画先へ戻す
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        flushAll();
        if (hostManaged) GL11.glFinish();
    }

    /** CPU の層を重ね、続けて溜めた図形を描き切る（描画順は CPU 層 → 図形）。 */
    private void flushAll() {
        flushOverlay();
        flushBatch();
    }

    /** 溜めた図形を 1 回のインスタンス描画で描く。 */
    private void flushBatch() {
        if (batchN == 0) return;
        int n = batchN;
        batchN = 0;
        java.nio.FloatBuffer fb = MemoryUtil.memAllocFloat(n * STRIDE * 4);
        try {
            fb.put(batch, 0, n * STRIDE * 4).flip();
            GL13.glActiveTexture(GL13.GL_TEXTURE3);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, dataTex);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, STRIDE, n, GL11.GL_RGBA, GL11.GL_FLOAT, fb);
        } finally {
            MemoryUtil.memFree(fb);
        }
        if (boundImg != 0) {
            GL13.glActiveTexture(GL13.GL_TEXTURE2);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, boundImg);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL20.glUseProgram(shapeProgram);
        GL20.glUniform2f(loc(shapeProgram, "uView"), width, height);
        GL14Blend.straight();
        GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_STRIP, 0, 4, n);
        drawCalls++;
    }

    private void put(int texel, float x, float y, float z, float w) {
        int i = base + texel * 4;
        batch[i] = x;
        batch[i + 1] = y;
        batch[i + 2] = z;
        batch[i + 3] = w;
    }

    private int curMode, curInnerEmpty, curHole, curClipN;
    private float curSigma, curRepO, curRepP;

    private void commit() {
        put(3, curMode, curInnerEmpty, curHole, curClipN);
        put(16, curSigma, curRepO, curRepP, 0);
        batchN++;
    }

    /** CPU が描いた層のうち、変わった範囲をテクスチャにして重ね、層を空に戻す。 */
    private void flushOverlay() {
        int[] b = cpu.dirtyBounds();
        if (b == null) return;
        flushBatch();
        drawPixels(cpu.pixmap().pixels(), b, false);
        cpu.clearDirty();
    }

    @Override
    public Pixmap snapshotTarget() {
        flushAll();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, currentFbo());
        int[] raw = new int[width * height];
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
        GL11.glReadPixels(0, 0, width, height, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, raw);
        Pixmap pm = new Pixmap(width, height);
        int[] dst = pm.pixels();
        for (int y = 0; y < height; y++) System.arraycopy(raw, (height - 1 - y) * width, dst, y * width, width);
        return pm;
    }

    @Override
    public void overwriteTarget(Pixmap pixmap, int[] region) {
        flushAll();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, currentFbo());
        GL11.glViewport(0, 0, width, height);
        drawPixels(pixmap.pixels(), region, true);
        GL11.glEnable(GL11.GL_BLEND);
    }

    /** {@code src}（全面・左上原点・事前乗算）の {@code b} の範囲をテクスチャにして現在の描画先へ描く。{@code overwrite} ならブレンドなしで上書き。 */
    private void drawPixels(int[] src, int[] b, boolean overwrite) {
        int bw = b[2] - b[0], bh = b[3] - b[1];
        IntBuffer buf = MemoryUtil.memAllocInt(bw * bh);
        try {
            for (int y = 0; y < bh; y++) buf.put(src, (b[1] + y) * width + b[0], bw);
            buf.flip();
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, overlayTex);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, b[0], b[1], bw, bh, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buf);
        } finally {
            MemoryUtil.memFree(buf);
        }
        GL20.glUseProgram(texProgram);
        GL20.glUniform4f(loc(texProgram, "uDev"), b[0], b[1], b[2], b[3]);
        GL20.glUniform2f(loc(texProgram, "uView"), width, height);
        GL20.glUniform1i(loc(texProgram, "uTex"), 0);
        GL20.glUniform1f(loc(texProgram, "uOpacity"), 1f);
        GL20.glUniform1i(loc(texProgram, "uFlip"), 1);
        if (overwrite) GL11.glDisable(GL11.GL_BLEND);
        else GL14Blend.premultiplied();
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
    }

    // ------------------------------------------------------------------ グループの層（FBO）

    /** 層 1 枚分の GL オブジェクト。 */
    private record GpuLayer(int fbo, int tex) {}

    /** 開いている層。マスクの層（{@code isMask}）は閉じると、直前の中身の層に預けられる。 */
    private static final class Entry {
        GpuLayer layer;
        float opacity;
        List<FilterOp> filters = List.of();
        double[] map = {1, 0, 0, 1};
        boolean masked, luminance, isMask;
        GpuLayer mask;
        BlendMode blend = BlendMode.NORMAL;
        GroupExtras extras;
    }

    private static final int MAX_LAYER_DEPTH = 8;
    private final java.util.ArrayDeque<GpuLayer> layerPool = new java.util.ArrayDeque<>();
    private final java.util.ArrayList<Entry> layerStack = new java.util.ArrayList<>();
    private final java.util.ArrayList<GpuLayer> allLayers = new java.util.ArrayList<>();
    private int fxProgram;

    private GpuLayer acquireLayer() {
        GpuLayer l = layerPool.pollFirst();
        if (l != null) return l;
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int tex = newTexture(null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        int f = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, f);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0);
        if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, currentFbo());
            GL30.glDeleteFramebuffers(f);
            GL11.glDeleteTextures(tex);
            return null;
        }
        l = new GpuLayer(f, tex);
        allLayers.add(l);
        return l;
    }

    private void clearLayer(GpuLayer l) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, l.fbo());
        GL11.glViewport(0, 0, width, height);
        GL11.glClearColor(0f, 0f, 0f, 0f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
    }

    @Override
    public boolean beginGroup(float opacity, List<FilterOp> filters, double[] map, boolean masked, boolean maskLuminance) {
        return beginGroup(opacity, filters, map, masked, maskLuminance, BlendMode.NORMAL);
    }

    @Override
    public boolean beginGroup(float opacity, List<FilterOp> filters, double[] map, boolean masked, boolean maskLuminance,
                              BlendMode blend) {
        return beginGroup(opacity, filters, map, masked, maskLuminance, blend, null);
    }

    @Override
    public boolean beginGroup(float opacity, List<FilterOp> filters, double[] map, boolean masked, boolean maskLuminance,
                              BlendMode blend, GroupExtras extras) {
        if (extras != null && (extras.pose() == null || extras.pose().invert() == null
                || (extras.clip() != null && (extras.clip().data().length + 3) / 4 > MAX_CLIP_VEC))) {
            return false;
        }
        // マスクの層をいつでも開けるように、1 段空けておく
        if (layerStack.size() >= MAX_LAYER_DEPTH - 1) return false;
        flushAll(); // 親の層に溜めた分を先に描き切る（描画順）
        GpuLayer l = acquireLayer();
        if (l == null) return false;
        Entry e = new Entry();
        e.layer = l;
        e.opacity = opacity;
        e.filters = filters == null ? List.of() : filters;
        e.map = map;
        e.masked = masked;
        e.luminance = maskLuminance;
        e.blend = blend == null ? BlendMode.NORMAL : blend;
        e.extras = extras;
        layerStack.add(e);
        clearLayer(l);
        return true;
    }

    @Override
    public boolean beginMask() {
        if (layerStack.isEmpty() || layerStack.get(layerStack.size() - 1).isMask || !layerStack.get(layerStack.size() - 1).masked) return false;
        flushAll();
        GpuLayer l = acquireLayer();
        if (l == null) return false;
        Entry e = new Entry();
        e.layer = l;
        e.opacity = 1f;
        e.isMask = true;
        layerStack.add(e);
        clearLayer(l);
        return true;
    }

    @Override
    public void endMask() {
        if (layerStack.isEmpty() || !layerStack.get(layerStack.size() - 1).isMask) return;
        flushAll();
        Entry m = layerStack.remove(layerStack.size() - 1);
        layerStack.get(layerStack.size() - 1).mask = m.layer;
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, currentFbo());
    }

    private enum StageKind { COLOR, BLUR, DROP }

    private static final class Stage {
        StageKind kind;
        float[] matrix;
        float opacity = 1f;
        float sigma, dx, dy;
        int color;
    }

    private static List<Stage> stages(List<FilterOp> ops) {
        List<Stage> out = new java.util.ArrayList<>();
        for (FilterOp op : ops) {
            switch (op.kind()) {
                case COLOR_MATRIX, OPACITY -> {
                    Stage st = out.isEmpty() || out.get(out.size() - 1).kind != StageKind.COLOR ? null : out.get(out.size() - 1);
                    if (st == null) {
                        st = new Stage();
                        st.kind = StageKind.COLOR;
                        out.add(st);
                    }
                    if (op.matrix() == null) { // OPACITY
                        st.opacity *= op.amount();
                    } else {
                        st.matrix = st.matrix == null ? op.matrix()
                                : FilterOp.optimize(List.of(FilterOp.color(st.matrix), FilterOp.color(op.matrix()))).get(0).matrix();
                    }
                }
                case BLUR -> {
                    Stage st = new Stage();
                    st.kind = StageKind.BLUR;
                    st.sigma = op.amount();
                    out.add(st);
                }
                case DROP_SHADOW -> {
                    Stage st = new Stage();
                    st.kind = StageKind.DROP;
                    st.sigma = op.amount();
                    st.dx = op.dx();
                    st.dy = op.dy();
                    st.color = op.color();
                    out.add(st);
                }
            }
        }
        return out;
    }

    @Override
    public void endGroup() {
        if (layerStack.isEmpty()) return;
        if (layerStack.get(layerStack.size() - 1).isMask) endMask();
        flushAll();
        Entry e = layerStack.remove(layerStack.size() - 1);
        List<GpuLayer> temps = new java.util.ArrayList<>();
        GpuLayer cur = e.layer;
        List<Stage> stages = stages(e.filters);
        float[] finalMatrix = null;
        float finalOpacity = e.opacity;
        if (!stages.isEmpty() && stages.get(stages.size() - 1).kind == StageKind.COLOR) {
            Stage last = stages.remove(stages.size() - 1);
            finalMatrix = last.matrix;
            finalOpacity *= last.opacity;
        }
        GL11.glDisable(GL11.GL_BLEND);
        if (e.extras != null && e.extras.backdropShape() != null && !e.extras.backdrop().isEmpty()) applyBackdropFilter(e, temps);
        for (Stage st : stages) {
            GpuLayer next = runStage(st, cur, e.map, temps);
            if (next == null) break; // 層が足りない: 以降の段は諦める
            cur = next;
        }
        if (e.extras != null && e.extras.clip() != null) {
            GpuLayer out = acquireLayer();
            if (out != null) {
                temps.add(out);
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, out.fbo());
                GL11.glViewport(0, 0, width, height);
                useFx(5, cur, null);
                setShapes(e.extras, false);
                GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
                cur = out;
            }
        }
        GL11.glEnable(GL11.GL_BLEND);

        // 最終の合成: マスクと不透明度を掛けて親へ重ねる
        GpuLayer backdrop = e.blend != BlendMode.NORMAL ? copyBackdrop() : null;
        if (backdrop != null) {
            temps.add(backdrop);
            // 背景を読むので、親の描画先へは「混ぜた結果で上書き」する（ブレンドなし）
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, currentFbo());
            GL11.glViewport(0, 0, width, height);
            GL11.glDisable(GL11.GL_BLEND);
            useFx(4, cur, e.mask, backdrop);
            GL20.glUniform1f(loc(fxProgram, "uOpacity"), finalOpacity);
            GL20.glUniform1i(loc(fxProgram, "uMaskMode"), e.mask == null ? 0 : (e.luminance ? 2 : 1));
            GL20.glUniform1i(loc(fxProgram, "uBlend"), e.blend.ordinal());
            setMatrix(finalMatrix);
            GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
            GL11.glEnable(GL11.GL_BLEND);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            layerPool.addFirst(e.layer);
            if (e.mask != null) layerPool.addFirst(e.mask);
            for (GpuLayer t : temps) layerPool.addFirst(t);
            return;
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, currentFbo());
        GL11.glViewport(0, 0, width, height);
        useFx(1, cur, e.mask);
        GL20.glUniform1f(loc(fxProgram, "uOpacity"), finalOpacity);
        GL20.glUniform1i(loc(fxProgram, "uMaskMode"), e.mask == null ? 0 : (e.luminance ? 2 : 1));
        setMatrix(finalMatrix);
        GL14Blend.premultiplied();
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);

        layerPool.addFirst(e.layer);
        if (e.mask != null) layerPool.addFirst(e.mask);
        for (GpuLayer t : temps) layerPool.addFirst(t);
    }

    private static final int MAX_CLIP_VEC = 96;

    /** {@code clip-path} の図形（と、{@code withBackdrop} なら {@code backdrop-filter} の形）をシェーダーの uniform に渡す。 */
    private void setShapes(GroupExtras x, boolean withBackdrop) {
        Matrix2D inv = x.pose().invert();
        GL20.glUniform4f(loc(fxProgram, "uInvM"), (float) inv.a(), (float) inv.b(), (float) inv.c(), (float) inv.d());
        GL20.glUniform2f(loc(fxProgram, "uInvT"), (float) inv.e(), (float) inv.f());
        GL20.glUniform1f(loc(fxProgram, "uShapeScale"), (float) Math.sqrt(Math.abs(x.pose().determinant())));
        ClipShape c = x.clip();
        int kind = c == null ? 0 : switch (c.kind()) {
            case INSET -> 1;
            case CIRCLE -> 2;
            case ELLIPSE -> 3;
            case POLYGON -> 4;
        };
        GL20.glUniform1i(loc(fxProgram, "uClipKind"), kind);
        if (c != null) {
            float[] d = c.data();
            int n = (d.length + 3) / 4;
            float[] pad = java.util.Arrays.copyOf(d, n * 4);
            for (int i = 0; i < n; i++) {
                GL20.glUniform4f(loc(fxProgram, "uClipD[" + i + "]"), pad[i * 4], pad[i * 4 + 1], pad[i * 4 + 2], pad[i * 4 + 3]);
            }
            GL20.glUniform1i(loc(fxProgram, "uClipN"), d.length / 4);
            GL20.glUniform1i(loc(fxProgram, "uEvenOdd"), c.evenOdd() ? 1 : 0);
        }
        boolean hasB = withBackdrop && x.backdropShape() != null;
        GL20.glUniform1i(loc(fxProgram, "uHasB"), hasB ? 1 : 0);
        if (hasB) {
            float[] d = x.backdropShape().data();
            for (int i = 0; i < 3; i++) {
                GL20.glUniform4f(loc(fxProgram, "uBD[" + i + "]"), d[i * 4], d[i * 4 + 1], d[i * 4 + 2], d[i * 4 + 3]);
            }
        }
    }

    /**
     * {@code backdrop-filter}: 親の絵を写し、フィルターを掛けた絵と元の絵を、箱の形 x クリップ x 不透明度で混ぜて親へ書く
     * （層の絵を重ねる前）。ブレンドは切ってある前提（混ぜた結果で上書きする）。
     */
    private void applyBackdropFilter(Entry e, List<GpuLayer> temps) {
        GpuLayer orig = copyBackdrop();
        if (orig == null) return;
        temps.add(orig);
        GpuLayer filtered = orig;
        for (Stage st : stages(e.extras.backdrop())) {
            GpuLayer next = runStage(st, filtered, e.map, temps);
            if (next == null) break;
            filtered = next;
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, currentFbo());
        GL11.glViewport(0, 0, width, height);
        useFx(6, filtered, null, orig);
        GL20.glUniform1f(loc(fxProgram, "uOpacity"), e.opacity);
        setShapes(e.extras, true);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    /** 現在の描画先の絵を 1 枚の層に写す（{@code mix-blend-mode} が読む背景）。層が借りられなければ null。 */
    private GpuLayer copyBackdrop() {
        GpuLayer bd = acquireLayer();
        if (bd == null) return null;
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, currentFbo());
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, bd.fbo());
        GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, currentFbo());
        return bd;
    }

    /** フィルターの段を 1 つ実行して結果の層を返す（借りられなければ null）。 */
    private GpuLayer runStage(Stage st, GpuLayer in, double[] m, List<GpuLayer> temps) {
        switch (st.kind) {
            case COLOR -> {
                GpuLayer out = acquireLayer();
                if (out == null) return null;
                temps.add(out);
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, out.fbo());
                GL11.glViewport(0, 0, width, height);
                useFx(1, in, null);
                GL20.glUniform1f(loc(fxProgram, "uOpacity"), st.opacity);
                GL20.glUniform1i(loc(fxProgram, "uMaskMode"), 0);
                setMatrix(st.matrix);
                GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
                return out;
            }
            case BLUR -> {
                return blur(st.sigma, in, m, temps);
            }
            default -> {
                GpuLayer blurred = blur(st.sigma, in, m, temps);
                if (blurred == null) return null;
                GpuLayer out = acquireLayer();
                if (out == null) return null;
                temps.add(out);
                float ox = (float) (m[0] * st.dx + m[2] * st.dy);
                float oy = (float) (m[1] * st.dx + m[3] * st.dy);
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, out.fbo());
                GL11.glViewport(0, 0, width, height);
                useFx(3, in, blurred);
                // 層の絵は上下が逆（GL の y は上向き）なので、縦のずれは符号を反転する
                GL20.glUniform2f(loc(fxProgram, "uShadowOffset"), ox / width, -oy / height);
                GL20.glUniform4f(loc(fxProgram, "uShadowColor"), ((st.color >>> 16) & 0xFF) / 255f, ((st.color >>> 8) & 0xFF) / 255f,
                        (st.color & 0xFF) / 255f, ((st.color >>> 24) & 0xFF) / 255f);
                GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
                return out;
            }
        }
    }

    /** 横・縦の 2 回のガウスぼかし（{@code LayerFx} と同じ標本の取り方）。 */
    private GpuLayer blur(float sigma, GpuLayer in, double[] m, List<GpuLayer> temps) {
        float sx = sigma * (float) Math.hypot(m[0], m[1]);
        float sy = sigma * (float) Math.hypot(m[2], m[3]);
        if (sx < 0.05f && sy < 0.05f) return in;
        GpuLayer cur = in;
        for (int axis = 0; axis < 2; axis++) {
            float sg = axis == 0 ? sx : sy;
            if (sg < 0.05f) continue;
            GpuLayer out = acquireLayer();
            if (out == null) return null;
            temps.add(out);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, out.fbo());
            GL11.glViewport(0, 0, width, height);
            useFx(2, cur, null);
            float step = Math.max(1f, sg / 12f);
            int taps = (int) Math.min(40, Math.ceil(3f * sg / step));
            GL20.glUniform2f(loc(fxProgram, "uDir"), axis == 0 ? 1f : 0f, axis == 0 ? 0f : 1f);
            GL20.glUniform1f(loc(fxProgram, "uSigma"), sg);
            GL20.glUniform1i(loc(fxProgram, "uTaps"), taps);
            GL20.glUniform1f(loc(fxProgram, "uStep"), step);
            GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
            cur = out;
        }
        return cur;
    }

    /** 層の合成用シェーダーを選び、入力（単位 0）と補助（単位 4）を結ぶ。 */
    private void useFx(int mode, GpuLayer src, GpuLayer aux, GpuLayer backdrop) {
        useFx(mode, src, aux);
        GL13.glActiveTexture(GL13.GL_TEXTURE5);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, backdrop.tex());
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL20.glUniform1i(loc(fxProgram, "uBackdrop"), 5);
    }

    private void useFx(int mode, GpuLayer src, GpuLayer aux) {
        GL20.glUseProgram(fxProgram);
        GL13.glActiveTexture(GL13.GL_TEXTURE4);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, aux != null ? aux.tex() : 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, src.tex());
        GL20.glUniform4f(loc(fxProgram, "uDev"), 0, 0, width, height);
        GL20.glUniform2f(loc(fxProgram, "uView"), width, height);
        GL20.glUniform1i(loc(fxProgram, "uSrc"), 0);
        GL20.glUniform1i(loc(fxProgram, "uAux"), 4);
        GL20.glUniform1i(loc(fxProgram, "uMode"), mode);
        GL20.glUniform1f(loc(fxProgram, "uOpacity"), 1f);
        GL20.glUniform1i(loc(fxProgram, "uMaskMode"), 0);
        setMatrix(null);
    }

    private void setMatrix(float[] m) {
        GL20.glUniform1i(loc(fxProgram, "uHasMatrix"), m == null ? 0 : 1);
        if (m != null) {
            GL20.glUniform4f(loc(fxProgram, "uRow0"), m[0], m[1], m[2], m[3]);
            GL20.glUniform4f(loc(fxProgram, "uRow1"), m[4], m[5], m[6], m[7]);
            GL20.glUniform4f(loc(fxProgram, "uRow2"), m[8], m[9], m[10], m[11]);
        }
    }

    private int currentFbo() {
        return layerStack.isEmpty() ? fbo : layerStack.get(layerStack.size() - 1).layer.fbo();
    }

    // ------------------------------------------------------------------ GPU の図形

    @Override
    public boolean fillRoundRect(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                 int argb, Clip[] clips, float opacity) {
        if (!beginShape(pose, x0, y0, x1, y1, clips, opacity)) return true;
        mode(0);
        setRect(rx, ry, x0, y0, x1, y1, "uRect", "uRx", "uRy");
        setColor("uColor", argb);
        commit();
        gpuShapes++;
        return true;
    }

    @Override
    public boolean strokeRoundBorder(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                     float ix0, float iy0, float ix1, float iy1, float[] irx, float[] iry, boolean innerEmpty,
                                     int[] colors, float[] widths, Clip[] clips, float opacity) {
        if (!beginShape(pose, x0, y0, x1, y1, clips, opacity)) return true;
        mode(1);
        setRect(rx, ry, x0, y0, x1, y1, "uRect", "uRx", "uRy");
        setRect(irx, iry, ix0, iy0, ix1, iy1, "uInner", "uIRx", "uIRy");
        curInnerEmpty = innerEmpty ? 1 : 0;
        put(10, widths[0], widths[1], widths[2], widths[3]);
        float[] cs = new float[16];
        for (int i = 0; i < 4; i++) {
            cs[i * 4] = ((colors[i] >> 16) & 0xFF) / 255f;
            cs[i * 4 + 1] = ((colors[i] >> 8) & 0xFF) / 255f;
            cs[i * 4 + 2] = (colors[i] & 0xFF) / 255f;
            cs[i * 4 + 3] = ((colors[i] >>> 24) & 0xFF) / 255f;
        }
        for (int i = 0; i < 4; i++) put(11 + i, cs[i * 4], cs[i * 4 + 1], cs[i * 4 + 2], cs[i * 4 + 3]);
        commit();
        gpuShapes++;
        return true;
    }

    @Override
    public boolean strokeDashedBorder(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                      float ix0, float iy0, float ix1, float iy1, float[] irx, float[] iry, boolean innerEmpty,
                                      int[] colors, float[] widths,
                                      float bx, float by, float cxm, float cym, float[] pcx, float[] pcy,
                                      float period, float dash, boolean round, float thickness,
                                      Clip[] clips, float opacity) {
        if (!(period > 0f)) return false;
        long before = gpuShapes;
        if (!strokeRoundBorder(pose, x0, y0, x1, y1, rx, ry, ix0, iy0, ix1, iy1, irx, iry, innerEmpty,
                colors, widths, clips, opacity)) return false;
        if (gpuShapes == before) return true; // 姿勢が壊れていて何も積まれていない
        // 直前に積んだ行を破線にする
        base = (batchN - 1) * STRIDE * 4;
        put(44, bx, by, cxm, cym);
        put(45, pcx[0], pcx[1], pcx[2], pcx[3]);
        put(46, pcy[0], pcy[1], pcy[2], pcy[3]);
        put(47, period, dash, round ? 1f : 0f, thickness);
        batch[base + 3 * 4 + 2] = 1f; // 破線の印（3 番目のテクセルの穴の欄）
        return true;
    }

    @Override
    public boolean strokeCircleBorder(Matrix2D pose, float cx, float cy, float outerR, float innerR,
                                      float bx0, float by0, float bx1, float by1, int[] colors, Clip[] clips, float opacity) {
        if (!beginShapeIn(pose, bx0, by0, bx1, by1, clips, opacity)) return true;
        mode(8);
        put(4, cx, cy, outerR, innerR);
        for (int i = 0; i < 4; i++) setColorAt(11 + i, colors[i]);
        commit();
        gpuShapes++;
        return true;
    }

    private void setColorAt(int t, int argb) {
        put(t, ((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f, ((argb >>> 24) & 0xFF) / 255f);
    }

    @Override
    public boolean fillGradient(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                ShaderGradient g, Clip[] clips, float opacity) {
        if (!beginShape(pose, x0, y0, x1, y1, clips, opacity)) return true;
        mode(2);
        setRect(rx, ry, x0, y0, x1, y1, "uRect", "uRx", "uRy");
        float[] a = g.gradA(), b = g.gradB();
        put(17, a[0], a[1], a[2], a[3]);
        put(18, b[0], b[1], b[2], b[3]);
        curRepO = g.repeatOrigin();
        curRepP = g.repeatPeriod();
        setColor("uGC0", g.color0());
        setColor("uGC1", g.color1());
        commit();
        gpuShapes++;
        return true;
    }

    @Override
    public boolean outerShadow(Matrix2D pose, float qx0, float qy0, float qx1, float qy1,
                               float sx0, float sy0, float sx1, float sy1, float[] srx, float[] sry, float sigma,
                               float[] hole, float[] hrx, float[] hry, int argb, Clip[] clips, float opacity) {
        if (!beginShapeIn(pose, qx0, qy0, qx1, qy1, clips, opacity)) return true;
        mode(3);
        setRect(srx, sry, sx0, sy0, sx1, sy1, "uRect", "uRx", "uRy");
        curSigma = sigma;
        curHole = hole != null ? 1 : 0;
        if (hole != null) setRect(hrx, hry, hole[0], hole[1], hole[2], hole[3], "uInner", "uIRx", "uIRy");
        setColor("uColor", argb);
        commit();
        gpuShapes++;
        return true;
    }

    @Override
    public boolean insetShadow(Matrix2D pose, float qx0, float qy0, float qx1, float qy1, float[] rx, float[] ry,
                               float sigma, float[] hole, float[] hrx, float[] hry, int argb, Clip[] clips, float opacity) {
        if (!beginShapeIn(pose, qx0 - 1, qy0 - 1, qx1 + 1, qy1 + 1, clips, opacity)) return true;
        mode(4);
        setRect(rx, ry, qx0, qy0, qx1, qy1, "uRect", "uRx", "uRy");
        curSigma = sigma;
        setRect(hrx, hry, hole[0], hole[1], hole[2], hole[3], "uInner", "uIRx", "uIRy");
        setColor("uColor", argb);
        commit();
        gpuShapes++;
        return true;
    }

    @Override
    public boolean drawGlyph(GlyphBitmap g, int bx, int by, int argb, Clip[] clips, float opacity) {
        if (g.width() <= 0 || g.height() <= 0 || g.width() > ATLAS_SIZE || g.height() > ATLAS_SIZE) return false;
        int[] slot = atlasSlot(g);
        if (!beginShapeIn(Matrix2D.IDENTITY, bx, by, bx + g.width(), by + g.height(), clips, opacity)) return true;
        mode(5);
        put(4, bx, by, bx + g.width(), by + g.height());
        put(21, slot[0], slot[1], g.width(), g.height());
        setColor("uColor", argb);
        commit();
        gpuGlyphs++;
        return true;
    }

    /** 字形をアトラスに載せて（まだなら）左上の位置を返す。 */
    private int[] atlasSlot(GlyphBitmap g) {
        int[] slot = atlasEntries.get(g);
        if (slot != null) return slot;
        if (atlasX + g.width() > ATLAS_SIZE) {
            atlasX = 0;
            atlasY += atlasRowH;
            atlasRowH = 0;
        }
        if (atlasY + g.height() > ATLAS_SIZE) {
            flushAll();
            atlasEntries.clear();
            atlasX = atlasY = atlasRowH = 0;
        }
        slot = new int[]{atlasX, atlasY};
        ByteBuffer buf = MemoryUtil.memAlloc(g.width() * g.height());
        try {
            buf.put(g.coverage(), 0, g.width() * g.height()).flip();
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlasTex);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, slot[0], slot[1], g.width(), g.height(), GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, buf);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
        } finally {
            MemoryUtil.memFree(buf);
        }
        atlasX += g.width();
        atlasRowH = Math.max(atlasRowH, g.height());
        atlasEntries.put(g, slot);
        return slot;
    }

    @Override
    public boolean drawGlyphTransformed(Matrix2D pose, GlyphBitmap g, float ox, float oy, float s,
                                        float bx0, float by0, float bx1, float by1, int argb, Clip[] clips, float opacity) {
        if (g.width() <= 0 || g.height() <= 0 || g.width() > ATLAS_SIZE || g.height() > ATLAS_SIZE) return false;
        int[] slot = atlasSlot(g);
        if (!beginShapeIn(pose, bx0, by0, bx1, by1, clips, opacity)) return true;
        mode(9);
        put(21, slot[0], slot[1], g.width(), g.height());
        put(22, ox, oy, s, 0);
        put(23, g.left(), g.top(), 0, 0);
        setColor("uColor", argb);
        commit();
        gpuGlyphs++;
        return true;
    }

    @Override
    public boolean drawImage(Matrix2D pose, Pixmap image, float bx0, float by0, float bx1, float by1,
                             float px0, float py0, float sx, float sy,
                             float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                             int taps, float stepX, float stepY, Clip[] clips, float opacity) {
        if (image.width() > 8192 || image.height() > 8192) return false;
        flushOverlay();
        int tex = imageTexture(image);
        if (batchN > 0 && boundImg != tex) flushBatch();
        boundImg = tex;
        // 回転の下では、辺の斜めの画素が箱の外側にもはみ出す（CPU 版は外接矩形の全画素を評価する）。1 画素分広げて合わせる
        float m = 1f / Math.max((float) pose.meanScale(), 0.01f);
        if (!beginShapeIn(pose, bx0 - m, by0 - m, bx1 + m, by1 + m, clips, opacity)) return true;
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        mode(6);
        setRect(rx, ry, x0, y0, x1, y1, "uRect", "uRx", "uRy");
        put(22, px0, py0, sx, sy);
        put(23, image.width(), image.height(), taps, 0);
        put(48, stepX, stepY, 0, 0);
        commit();
        gpuImages++;
        return true;
    }

    /** 画像（事前乗算）のテクスチャ。同じ {@link Pixmap} は使い回す（画像の中身は変わらない前提）。 */
    private int imageTexture(Pixmap image) {
        int[] e = imageTex.get(image);
        if (e != null) {
            if (e[1] != image.version()) {
                // 同じ Pixmap の中身が書き換わった（動画のフレームなど）。すでに溜めた図形は古い画素で描く
                flushAll();
                boundImg = 0;
                GL13.glActiveTexture(GL13.GL_TEXTURE2);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, e[0]);
                GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
                GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, image.width(), image.height(), GL12.GL_BGRA,
                        GL12.GL_UNSIGNED_INT_8_8_8_8_REV, image.pixels());
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                e[1] = image.version();
            }
            return e[0];
        }
        if (imageTex.size() >= IMAGE_CACHE_LIMIT) {
            flushAll();
            boundImg = 0;
            for (int[] old : imageTex.values()) GL11.glDeleteTextures(old[0]);
            imageTex.clear();
        }
        int tex = GL11.glGenTextures();
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, image.width(), image.height(), 0, GL12.GL_BGRA,
                GL12.GL_UNSIGNED_INT_8_8_8_8_REV, image.pixels());
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        imageTex.put(image, new int[]{tex, image.version()});
        return tex;
    }

    /**
     * 図形を描く共通の準備。先に CPU の層を描き切って順序を守り、変形・クリップ・不透明度を渡す。
     * 描くものが無い（姿勢が壊れている）なら {@code false}。
     */
    private boolean beginShape(Matrix2D pose, float x0, float y0, float x1, float y1, Clip[] clips, float opacity) {
        float m = 1f / Math.max((float) pose.meanScale(), 0.01f);
        return beginShapeIn(pose, x0 - m, y0 - m, x1 + m, y1 + m, clips, opacity);
    }

    /** {@link #beginShape} の、描く範囲（余白込み）を直接渡す版。 */
    private boolean beginShapeIn(Matrix2D pose, float bx0, float by0, float bx1, float by1, Clip[] clips, float opacity) {
        flushOverlay();
        if (!pose.isFinite() || pose.invert() == null || opacity <= 0f) return false;
        if (batchN == MAX_BATCH) flushBatch();
        base = batchN * STRIDE * 4;
        curMode = 0;
        curInnerEmpty = 0;
        curHole = 0;
        curSigma = 0f;
        curRepO = 0f;
        curRepP = 0f;
        put(0, bx0, by0, bx1, by1);
        put(1, (float) pose.a(), (float) pose.b(), (float) pose.c(), (float) pose.d());
        put(2, (float) pose.e(), (float) pose.f(), (float) pose.meanScale(), opacity);
        int n = clips.length;
        curClipN = n;
        for (int i = 0; i < n; i++) {
            Clip c = clips[i];
            Matrix2D v = c.inv();
            int t = 24 + i * 5;
            put(t, (float) v.a(), (float) v.b(), (float) v.c(), (float) v.d());
            put(t + 1, (float) v.e(), (float) v.f(), c.scale(), 0);
            put(t + 2, c.x0(), c.y0(), c.x1(), c.y1());
            put(t + 3, c.rx()[0], c.rx()[1], c.rx()[2], c.rx()[3]);
            put(t + 4, c.ry()[0], c.ry()[1], c.ry()[2], c.ry()[3]);
        }
        return true;
    }

    private void setRect(float[] rx, float[] ry, float x0, float y0, float x1, float y1, String rect, String nx, String ny) {
        int t = switch (rect) {
            case "uRect" -> 4;
            default -> 7; // uInner
        };
        put(t, x0, y0, x1, y1);
        put(t + 1, rx[0], rx[1], rx[2], rx[3]);
        put(t + 2, ry[0], ry[1], ry[2], ry[3]);
    }

    private void setColor(String name, int argb) {
        int t = switch (name) {
            case "uGC0" -> 19;
            case "uGC1" -> 20;
            default -> 15; // uColor
        };
        put(t, ((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f, ((argb >>> 24) & 0xFF) / 255f);
    }

    private void mode(int m) {
        curMode = m;
    }

    /** ブレンドの組み合わせ（描き先は事前乗算の画素）。 */
    private static final class GL14Blend {
        static void straight() {
            org.lwjgl.opengl.GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        }

        static void premultiplied() {
            GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        }
    }

    // ------------------------------------------------------------------ 結果の取り出し・表示

    /** 直近のフレームを GPU から読み戻す（事前乗算の ARGB、左上原点）。 */
    @Override
    public Pixmap pixmap() {
        if (closed) throw new IllegalStateException("closed");
        if (readbackValid) return readback;
        enter();
        int[] raw = new int[width * height];
        try {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
            GL11.glReadPixels(0, 0, width, height, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, raw);
        } finally {
            exit();
        }
        int[] dst = readback.pixels();
        for (int y = 0; y < height; y++) System.arraycopy(raw, (height - 1 - y) * width, dst, y * width, width);
        readbackValid = true;
        return readback;
    }

    /** 直近のフレームをウィンドウへ映して入れ替える（見えるウィンドウで作ったとき）。 */
    public void present() {
        if (hostManaged) throw new IllegalStateException("共有コンテキストには表示するウィンドウが無い");
        GLFW.glfwMakeContextCurrent(window);
        int[] w = new int[1], h = new int[1];
        GLFW.glfwGetFramebufferSize(window, w, h);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
        GL11.glDisable(GL11.GL_BLEND);
        GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, w[0], h[0], GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        GL11.glEnable(GL11.GL_BLEND);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GLFW.glfwSwapBuffers(window);
    }

    /** ウィンドウの閉じる操作が来たか。 */
    public boolean shouldClose() {
        return GLFW.glfwWindowShouldClose(window);
    }

    /** ウィンドウのイベントを処理する。 */
    public void pollEvents() {
        GLFW.glfwPollEvents();
    }

    // ------------------------------------------------------------------ 後始末

    @Override
    public void close() {
        destroy();
    }

    private void destroy() {
        if (closed) return;
        closed = true;
        if (window != MemoryUtil.NULL) {
            enter();
            if (shapeProgram != 0) GL20.glDeleteProgram(shapeProgram);
            if (texProgram != 0) GL20.glDeleteProgram(texProgram);
            if (fxProgram != 0) GL20.glDeleteProgram(fxProgram);
            for (GpuLayer l : allLayers) {
                GL30.glDeleteFramebuffers(l.fbo());
                GL11.glDeleteTextures(l.tex());
            }
            allLayers.clear();
            if (fbo != 0) GL30.glDeleteFramebuffers(fbo);
            if (fboTex != 0) GL11.glDeleteTextures(fboTex);
            if (overlayTex != 0) GL11.glDeleteTextures(overlayTex);
            if (atlasTex != 0) GL11.glDeleteTextures(atlasTex);
            if (dataTex != 0) GL11.glDeleteTextures(dataTex);
            for (int[] t : imageTex.values()) GL11.glDeleteTextures(t[0]);
            imageTex.clear();
            if (vao != 0) GL30.glDeleteVertexArrays(vao);
            if (hostManaged) GL11.glFinish();
            exit();
            if (!hostManaged) GLFW.glfwMakeContextCurrent(MemoryUtil.NULL);
            GLFW.glfwDestroyWindow(window);
            window = MemoryUtil.NULL;
        }
        if (!hostManaged) synchronized (LwjglGlRenderBackend.class) {
            if (liveInstances > 0 && --liveInstances == 0) {
                GLFW.glfwTerminate();
                if (errorCallback != null) {
                    // 解放した関数ポインタを GLFW に残すと、次の初期化で「前のコールバック」として触って壊れる
                    GLFW.glfwSetErrorCallback(null);
                    errorCallback.free();
                    errorCallback = null;
                }
            }
        }
    }
}
