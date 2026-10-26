package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.core.paint.model.*;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

/**
 * Web仮想座標空間(localPos)とスクリーン/ワールド変換行列(poseMatrix)を分離し、
 * 2D GUI・3Dブロック表面・手持ちアイテム表面の全てにおいて、
 * 単色およびグラデーションの角丸・親角丸クリッピングを 1-Quad で描画するSDFシェーダー。
 *
 * <p>矩形・角丸・クリップは頂点属性として持つので、パスの間（{@link #beginPass}〜{@link #endPass}）に積んだ
 * Quad は、同じ変換行列のあいだ 1 回の draw にまとめて描く（1 要素 1 draw だと、1 画面で千回を超える）。</p>
 */
public class WebSdfShader {

    private static int programId = -1;
    private static boolean initialized = false;
    private static boolean available = false;

    private static int uModelViewMat = -1;
    private static int uProjMat = -1;
    private static int uPoseMat = -1;
    private static int uSegment = -1;
    private static int uSegWidth = -1;

    private static final String VERTEX_GLSL = """
            #version 150
            in vec3 Position;
            in vec4 Color;
            in vec4 Rect;       // (minX, minY, maxX, maxY)
            in vec4 Radii;      // (rTL, rTR, rBR, rBL)
            in vec4 ClipRect;   // (cx0, cy0, cx1, cy1)
            in vec4 ClipRadii;  // (cRTL, cRTR, cRBR, cRBL)
            in float Feather;   // 影のぼかし半径。0 なら通常の角丸矩形
            in vec4 Border;     // 枠線の太さ (上, 右, 下, 左)。全部 0 なら塗りつぶし
            in vec2 GradRepeat; // グラデーションの繰り返し (畳み始めの位置, 周期)。周期 0 なら繰り返さない
            in vec4 GradA;      // グラデーション。w = 種類（0 なし、1 線形、2 放射）+ 10 * 範囲の印。線形は (dx, dy, offset)、放射は (cx, cy, 1/rx)
            in vec4 GradB;      // 放射の (1/ry, p0, 1/(p1-p0))
            in vec4 Color2;     // グラデーションの終わりの色（始まりの色は Color）。影では穴の縦の半径
            in vec4 RadiiV;     // 縦の半径 (rTL, rTR, rBR, rBL)。全部 0 なら円の角（縦は Radii と同じ）
            in vec4 ClipRadiiV; // クリップの縦の半径。全部 0 なら円の角
            in vec4 BorderDash; // 枠線の破線 (種類 0 実線・1 破線・2 点線, 周期の数, 線の長さ（点は直径）, 丸い点なら 1)

            uniform mat4 ModelViewMat;
            uniform mat4 ProjMat;
            uniform mat4 uPoseMat;

            out vec2 vLocalPos;
            out vec4 vColor;
            flat out vec4 vRect;
            flat out vec4 vRadii;
            flat out vec4 vClipRect;
            flat out vec4 vClipRadii;
            flat out float vFeather;
            flat out vec4 vBorder;
            flat out vec2 vGradRepeat;
            flat out vec4 vGradA;
            flat out vec4 vGradB;
            flat out vec4 vColor2;
            flat out vec4 vRadiiV;
            flat out vec4 vClipRadiiV;
            flat out vec4 vBorderDash;

            void main() {
                vLocalPos = Position.xy;
                vColor = Color;
                vRect = Rect;
                vRadii = Radii;
                vClipRect = ClipRect;
                vClipRadii = ClipRadii;
                vFeather = Feather;
                vBorder = Border;
                vGradRepeat = GradRepeat;
                vGradA = GradA;
                vGradB = GradB;
                vColor2 = Color2;
                vRadiiV = RadiiV;
                vClipRadiiV = ClipRadiiV;
                vBorderDash = BorderDash;
                gl_Position = ProjMat * ModelViewMat * uPoseMat * vec4(Position, 1.0);
            }
            """;

    private static final String FRAGMENT_GLSL = """
            #version 150
            in vec2 vLocalPos;
            in vec4 vColor;
            flat in vec4 vRect;
            flat in vec4 vRadii;
            flat in vec4 vClipRect;
            flat in vec4 vClipRadii;
            flat in float vFeather;
            flat in vec4 vBorder;
            flat in vec2 vGradRepeat;
            flat in vec4 vGradA;
            flat in vec4 vGradB;
            flat in vec4 vColor2;
            flat in vec4 vRadiiV;
            flat in vec4 vClipRadiiV;
            flat in vec4 vBorderDash;

            uniform vec4 uSegment;   // 線分の両端 (ax, ay, bx, by)。uSegWidth > 0 のときだけ使う
            uniform float uSegWidth; // 線分の太さ。0 なら角丸矩形

            out vec4 fragColor;

            // 両端で直角に切った太い線分（向きのある矩形）までの距離
            float sdOrientedBox(vec2 p, vec2 a, vec2 b, float th) {
                float l = max(length(b - a), 0.001);
                vec2 d = (b - a) / l;
                vec2 q = p - (a + b) * 0.5;
                q = mat2(d.x, -d.y, d.y, d.x) * q;
                q = abs(q) - vec2(l, th) * 0.5;
                return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0);
            }

            float sdRoundBox(vec2 p, vec2 b, vec4 r) {
                vec2 rSel = (p.x > 0.0) ? vec2(r.y, r.z) : vec2(r.x, r.w);
                float rad = (p.y > 0.0) ? rSel.y : rSel.x;
                rad = min(rad, min(b.x, b.y));
                vec2 q = abs(p) - b + rad;
                return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - rad;
            }

            // 楕円の角を持つ角丸矩形までの符号付き距離。rx / ry は (左上, 右上, 右下, 左下) の横と縦の半径。
            // 角の領域は楕円の勾配で正規化した距離の近似（円なら厳密）、辺の領域は辺までの距離。どちらかが 0 の角は直角
            float sdEllipseBox(vec2 p, vec2 b, vec4 rx, vec4 ry) {
                vec2 sx = (p.x > 0.0) ? vec2(rx.y, rx.z) : vec2(rx.x, rx.w);
                vec2 sy = (p.x > 0.0) ? vec2(ry.y, ry.z) : vec2(ry.x, ry.w);
                vec2 rr = min(vec2((p.y > 0.0) ? sx.y : sx.x, (p.y > 0.0) ? sy.y : sy.x), b);
                if (rr.x <= 0.0 || rr.y <= 0.0) {
                    vec2 e = abs(p) - b;
                    return length(max(e, 0.0)) + min(max(e.x, e.y), 0.0);
                }
                vec2 q = abs(p) - b + rr;
                if (q.x > 0.0 && q.y > 0.0) {
                    float k0 = length(q / rr);
                    float k1 = length(q / (rr * rr));
                    return k0 * (k0 - 1.0) / max(k1, 1e-6);
                }
                return max(q.x - rr.x, q.y - rr.y);
            }

            // 楕円の 4 分の 1 の弧長（Ramanujan の近似）。BorderPath.quarterArc と同じ
            float quarterArc(float rx, float ry) {
                if (rx <= 0.0 || ry <= 0.0) return 0.0;
                return 0.7853982 * (3.0 * (rx + ry) - sqrt((3.0 * rx + ry) * (rx + 3.0 * ry)));
            }

            // 角丸矩形の中心線の全長。BorderPath.length と同じ
            float pathLength(vec2 b, vec4 rx, vec4 ry) {
                return (2.0 * b.x - rx.x - rx.y) + (2.0 * b.y - ry.y - ry.z) + (2.0 * b.x - rx.z - rx.w) + (2.0 * b.y - ry.w - ry.x)
                        + quarterArc(rx.x, ry.x) + quarterArc(rx.y, ry.y) + quarterArc(rx.z, ry.z) + quarterArc(rx.w, ry.w);
            }

            // 点 p に最も近い中心線の位置（上辺の左の角の終わりが 0、時計回り）。BorderPath.param と同じ
            float pathParam(vec2 p, vec2 b, vec4 rx, vec4 ry) {
                float lt = 2.0 * b.x - rx.x - rx.y;
                float lr = 2.0 * b.y - ry.y - ry.z;
                float lb = 2.0 * b.x - rx.z - rx.w;
                float ll = 2.0 * b.y - ry.w - ry.x;
                float sR = lt + quarterArc(rx.y, ry.y);
                float sBR = sR + lr;
                float sB = sBR + quarterArc(rx.z, ry.z);
                float sBL = sB + lb;
                float sL = sBL + quarterArc(rx.w, ry.w);
                float sTL = sL + ll;
                const float K = 0.63661977; // 2 / pi
                if (p.y < 0.0) {
                    if (p.x >= 0.0) {
                        vec2 c = vec2(b.x - rx.y, -(b.y - ry.y));
                        if (p.x > c.x && p.y < c.y) return lt + atan((p.x - c.x) / max(rx.y, 1e-4), (c.y - p.y) / max(ry.y, 1e-4)) * K * quarterArc(rx.y, ry.y);
                        if (p.x <= c.x) return p.x + b.x - rx.x;
                        return sR + (p.y + b.y - ry.y);
                    }
                    vec2 c = vec2(-(b.x - rx.x), -(b.y - ry.x));
                    if (p.x < c.x && p.y < c.y) return sTL + atan((c.y - p.y) / max(ry.x, 1e-4), (c.x - p.x) / max(rx.x, 1e-4)) * K * quarterArc(rx.x, ry.x);
                    if (p.x >= c.x) return p.x + b.x - rx.x;
                    return sL + (b.y - ry.w - p.y);
                }
                if (p.x >= 0.0) {
                    vec2 c = vec2(b.x - rx.z, b.y - ry.z);
                    if (p.x > c.x && p.y > c.y) return sBR + atan((p.y - c.y) / max(ry.z, 1e-4), (p.x - c.x) / max(rx.z, 1e-4)) * K * quarterArc(rx.z, ry.z);
                    if (p.y <= c.y) return sR + (p.y + b.y - ry.y);
                    return sB + (b.x - rx.z - p.x);
                }
                vec2 c = vec2(-(b.x - rx.w), b.y - ry.w);
                if (p.x < c.x && p.y > c.y) return sBL + atan((c.x - p.x) / max(rx.w, 1e-4), (p.y - c.y) / max(ry.w, 1e-4)) * K * quarterArc(rx.w, ry.w);
                if (p.x >= c.x) return sB + (b.x - rx.z - p.x);
                return sL + (b.y - ry.w - p.y);
            }

            void main() {
                vec2 center = (vRect.xy + vRect.zw) * 0.5;
                vec2 halfSize = max(vec2(0.001), (vRect.zw - vRect.xy) * 0.5);
                bool ell = any(notEqual(vRadiiV, vec4(0.0)));
                bool clipEll = any(notEqual(vClipRadiiV, vec4(0.0)));
                float distOuter = uSegWidth > 0.0
                        ? sdOrientedBox(vLocalPos, uSegment.xy, uSegment.zw, uSegWidth)
                        : (ell ? sdEllipseBox(vLocalPos - center, halfSize, vRadii, vRadiiV) : sdRoundBox(vLocalPos - center, halfSize, vRadii));
                float distBox = distOuter;

                if (vBorder.x + vBorder.y + vBorder.z + vBorder.w > 0.0) {
                    // 枠線: 外側の角丸矩形から、太さの分だけ縮めた内側の角丸矩形を引いた輪
                    vec2 innerHalf = max(vec2(0.001), halfSize - vec2(vBorder.w + vBorder.y, vBorder.x + vBorder.z) * 0.5);
                    vec2 innerCenter = center + vec2(vBorder.w - vBorder.y, vBorder.x - vBorder.z) * 0.5;
                    float distInner;
                    if (ell) {
                        // 楕円の角: 内側は外側の半径から、その軸に接する辺の太さを引く（どちらかが 0 になれば直角）
                        vec4 innerRx = max(vRadii - vec4(vBorder.w, vBorder.y, vBorder.y, vBorder.w), 0.0);
                        vec4 innerRy = max(vRadiiV - vec4(vBorder.x, vBorder.x, vBorder.z, vBorder.z), 0.0);
                        distInner = sdEllipseBox(vLocalPos - innerCenter, innerHalf, innerRx, innerRy);
                    } else {
                        vec4 innerRadii = max(vRadii - vec4(max(vBorder.w, vBorder.x), max(vBorder.y, vBorder.x),
                                max(vBorder.y, vBorder.z), max(vBorder.w, vBorder.z)), 0.0);
                        distInner = sdRoundBox(vLocalPos - innerCenter, innerHalf, innerRadii);
                    }
                    distBox = max(distBox, -distInner);
                    if (vBorderDash.x > 0.5) {
                        // 破線・点線: 中心線に沿った位置を周期に畳み、周期の中央の線（点）だけを残す。4 辺が同じ太さのときだけ
                        float w = vBorder.x;
                        vec2 bC = max(halfSize - vec2(0.5 * w), vec2(0.001));
                        vec4 rxC = min(max(vRadii - 0.5 * w, 0.0), vec4(bC.x));
                        vec4 ryC = min(max((ell ? vRadiiV : vRadii) - 0.5 * w, 0.0), vec4(bC.y));
                        vec4 round = vec4(rxC.x > 0.0 && ryC.x > 0.0 ? 1.0 : 0.0, rxC.y > 0.0 && ryC.y > 0.0 ? 1.0 : 0.0,
                                rxC.z > 0.0 && ryC.z > 0.0 ? 1.0 : 0.0, rxC.w > 0.0 && ryC.w > 0.0 ? 1.0 : 0.0);
                        rxC *= round;
                        ryC *= round;
                        float period = pathLength(bC, rxC, ryC) / vBorderDash.y;
                        float ds = mod(pathParam(vLocalPos - center, bC, rxC, ryC), period) - 0.5 * period;
                        float across = distOuter + 0.5 * w;
                        float dd = vBorderDash.w > 0.5 ? length(vec2(ds, across)) - 0.5 * vBorderDash.z : abs(ds) - 0.5 * vBorderDash.z;
                        distBox = max(distBox, dd);
                    }
                }

                vec2 clipCenter = (vClipRect.xy + vClipRect.zw) * 0.5;
                vec2 clipHalf = max(vec2(0.001), (vClipRect.zw - vClipRect.xy) * 0.5);
                float distClip = clipEll ? sdEllipseBox(vLocalPos - clipCenter, clipHalf, vClipRadii, vClipRadiiV)
                        : sdRoundBox(vLocalPos - clipCenter, clipHalf, vClipRadii);

                float dist = max(distBox, distClip);
                float aa = clamp(fwidth(dist), 0.35, 1.25);
                float alphaMask = 1.0 - smoothstep(-aa * 0.5, aa * 0.5, dist);
                if (vFeather > 0.0) {
                    // 影のぼかし: 形の辺からの距離で、ガウスぼかし（標準偏差 = ぼかし半径 / 2）の累積分布に近い滑らかな減衰にする
                    float sigma = vFeather * 0.5;
                    float falloff = 1.0 / (1.0 + exp(1.702 * distBox / sigma));
                    if (vBorder.x < -1.5) falloff = 1.0 - falloff;           // 内側の影: 穴の外が影
                    alphaMask = falloff * (1.0 - smoothstep(-aa * 0.5, aa * 0.5, distClip));
                    if (vBorder.x < -0.5 && vBorder.x > -1.5) {              // 外側の影: 箱の中は抜く
                        vec2 holeCenter = (vGradA.xy + vGradA.zw) * 0.5;
                        vec2 holeHalf = max(vec2(0.001), (vGradA.zw - vGradA.xy) * 0.5);
                        float distHole = any(notEqual(vColor2, vec4(0.0))) ? sdEllipseBox(vLocalPos - holeCenter, holeHalf, vGradB, vColor2)
                                : sdRoundBox(vLocalPos - holeCenter, holeHalf, vGradB);
                        alphaMask *= smoothstep(-aa * 0.5, aa * 0.5, distHole);
                    }
                }
                if (alphaMask <= 0.003) discard;

                vec4 col = vColor;
                if (vFeather <= 0.0 && vGradA.w > 0.5) {
                    // グラデーションの 1 区間を画素ごとに計算する（プリマルチプライドで補間。透明へ向かうと色が黒くならない）。
                    // w = 種類（1 線形・2 放射・3 円錐）+ 10 * 範囲の印（1 = t < 0 を捨てる、2 = t > 1 を捨てる）。
                    // 停止点が 3 つ以上のときは区間ごとに重ねて描き、各画素は 1 区間だけが塗る
                    int g = int(vGradA.w + 0.5);
                    int kind = g % 10;
                    int bounds = g / 10;
                    float u;
                    if (kind == 1) {
                        u = dot(vLocalPos, vGradA.xy) + vGradA.z;
                    } else if (kind == 2) {
                        vec2 d = vec2((vLocalPos.x - vGradA.x) * vGradA.z, (vLocalPos.y - vGradA.y) * vGradB.x);
                        u = length(d);
                    } else {
                        // 真上から時計回りの角（周）。開始角を引いて 0〜1 に畳む
                        vec2 d = vLocalPos - vGradA.xy;
                        u = fract(atan(d.x, -d.y) * 0.15915494 - vGradA.z);
                    }
                    if (vGradRepeat.y > 0.0) u = vGradRepeat.x + mod(u - vGradRepeat.x, vGradRepeat.y);
                    float t = (u - vGradB.y) * vGradB.z;
                    // 隣の区間と少し重ねる（区間ごとの浮動小数点の誤差で、境目に 1 画素の抜けが出ないように）
                    if (((bounds & 1) != 0 && t < -0.001) || ((bounds & 2) != 0 && t > 1.001)) discard;
                    t = clamp(t, 0.0, 1.0);
                    if (vGradB.w != 1.0) t = pow(t, vGradB.w);
                    vec4 pm = mix(vec4(vColor.rgb * vColor.a, vColor.a), vec4(vColor2.rgb * vColor2.a, vColor2.a), t);
                    col = pm.a > 0.0001 ? vec4(pm.rgb / pm.a, pm.a) : vec4(0.0);
                }
                col.a *= alphaMask;
                if (col.a <= 0.003) discard;
                fragColor = col;
            }
            """;

    /**
     * 頂点: 位置(12) + 色(4) + 矩形(16) + 角丸(16) + クリップ矩形(16) + クリップ角丸(16) + ぼかし(4) + 枠線の太さ(16)
     * + グラデーションの繰り返し(8) + グラデーション(16+16) + 終わりの色(16) + 縦の半径(16) + クリップの縦の半径(16) + 破線(16) = 204 バイト。
     */
    private static final int VERTEX_STRIDE = 204;
    /** 次に積む頂点のぼかし半径（影のとき）。{@link #drawSdfShadow} の間だけ 0 でない。 */
    private static float pendingFeather = 0f;
    /** 次に積む頂点の枠線の太さ（上, 右, 下, 左）。{@link #drawSdfBorder} の間だけ 0 でない。 */
    private static float pendingBorderT = 0f, pendingBorderR = 0f, pendingBorderB = 0f, pendingBorderL = 0f;
    /** 次に積む頂点のグラデーションの記述と終わりの色。{@link #drawSdfShaderGradient} の間だけ 0 でない。 */
    private static final float[] pendingGradA = new float[4], pendingGradB = new float[4], pendingColor2 = new float[4];
    private static final float[] pendingGradRepeat = new float[2];
    /** 次に積む頂点の縦の半径（楕円の角）、クリップの縦の半径、枠線の破線。全部 0 なら円の角・実線。縦の半径は {@link #setEllipseRadii} で入る。 */
    private static final float[] pendingRadiiV = new float[4], pendingClipRadiiV = new float[4], pendingDash = new float[4];

    /** 以降に積む Quad の角として、各 draw の {@code rTL…} の代わりに使う横の半径。{@code null} なら各 draw の値。 */
    private static final float[] ellipseRx = new float[4];
    private static boolean ellipseActive = false;

    /**
     * 以降に積む Quad の角を楕円にする（横 {@code rx}・縦 {@code ry}。左上・右上・右下・左下の順）。各 draw の {@code rTL…} は
     * 円の角用なので、楕円の間は {@code rx} で置き換える。{@code null} で円の角に戻す。呼び出し側は描き終えたら戻すこと
     * （要素ごとの背景・枠線の前後）。
     */
    public static void setEllipseRadii(float[] rx, float[] ry) {
        ellipseActive = rx != null && ry != null;
        if (ellipseActive) System.arraycopy(rx, 0, ellipseRx, 0, 4);
        copyOrZero(ellipseActive ? ry : null, pendingRadiiV);
    }

    /** 以降に積む Quad のクリップの縦の半径。{@code null} か全部 0 で円の角。 */
    public static void setClipVerticalRadii(float[] v) {
        copyOrZero(v, pendingClipRadiiV);
    }

    /** いま {@link #setEllipseRadii} に入っている横の半径。無ければ {@code fallback}（円の角）。 */
    static float[] currentEllipseRx(float... fallback) {
        return ellipseActive ? ellipseRx.clone() : fallback.clone();
    }

    /** 同じく縦の半径。無ければ円の角なので {@code fallback}（横と同じ）。 */
    static float[] currentEllipseRy(float... fallback) {
        return ellipseActive ? pendingRadiiV.clone() : fallback.clone();
    }

    private static void copyOrZero(float[] from, float[] to) {
        if (from == null) java.util.Arrays.fill(to, 0f);
        else System.arraycopy(from, 0, to, 0, 4);
    }

    private static final int INITIAL_BATCH_BYTES = 1 << 16;

    private static VertexFormat batchFormat;
    private static ByteBufferBuilder batchBytes;
    private static int batchVertexCount = 0;

    /** 要素ごとの translate(c) → translate(-c) の丸め誤差では、バッチを切らない。 */
    private static final float POSE_EPSILON = 1e-4f;


    /** {@link #beginPass} から {@link #endPass} までの間、Quad を積んで後でまとめて描く。 */
    private static boolean passActive = false;
    /** 積んでいる Quad の変換行列（参照ではなく値で持つ。呼び出し側の行列は pushPose の間に書き換わる）。 */
    private static final Matrix4f batchPose = new Matrix4f();

    public static boolean isAvailable() {
        if (!RenderSystem.isOnRenderThread()) return false;
        if (!initialized) {
            initShader();
        }
        return available;
    }

    public static synchronized void deleteProgram() {
        endPass();
        if (programId != -1) {
            GL20.glDeleteProgram(programId);
            programId = -1;
        }
        initialized = false;
        available = false;
    }

    public static synchronized void reload() {
        if (RenderSystem.isOnRenderThread()) {
            deleteProgram();
        } else {
            RenderSystem.recordRenderCall(WebSdfShader::deleteProgram);
        }
    }

    /**
     * Quad を積み始める。{@link #endPass()} か行列の切り替わりまで、描画は遅らせる（GL の状態もそのとき初めて触る）。
     * パスの外から呼んだ描画は、その場で 1 回の draw にする。
     */
    public static void beginPass(Matrix4f poseMatrix) {
        if (!isAvailable()) return;
        if (!passActive) {
            // 先に積まれている頂点（前の画面の文字など）は描き切る。以後の Quad より手前に残さない
            Minecraft.getInstance().renderBuffers().bufferSource().endBatch();
            batchPose.set(poseMatrix);
            passActive = true;
        } else if (!poseMatrix.equals(batchPose, POSE_EPSILON)) {
            // 行列が変わるので、それまでに積んだ分を前の行列で描き切る
            flushBatch(null);
            batchPose.set(poseMatrix);
        }
    }

    /** Quad を積んでいる間（{@link #beginPass} から {@link #endPass} まで）か。 */
    public static boolean isBatching() {
        return passActive;
    }

    /** 積んだ Quad をここまでで描き切る（パスは続く）。この後に描く文字・画像との前後を守りたいときに呼ぶ。 */
    public static void flushPass() {
        if (passActive) flushBatch(null);
    }

    public static void endPass() {
        if (passActive) {
            flushBatch(null);
            passActive = false;
        }
    }

    private static void initShader() {
        initialized = true;
        int vShader = -1;
        int fShader = -1;
        try {
            vShader = compileShader(GL20.GL_VERTEX_SHADER, VERTEX_GLSL);
            if (vShader == -1) return;

            fShader = compileShader(GL20.GL_FRAGMENT_SHADER, FRAGMENT_GLSL);
            if (fShader == -1) return;

            programId = GL20.glCreateProgram();
            // 属性の場所は batchFormat の要素の並びと同じ
            GL20.glBindAttribLocation(programId, 0, "Position");
            GL20.glBindAttribLocation(programId, 1, "Color");
            GL20.glBindAttribLocation(programId, 2, "Rect");
            GL20.glBindAttribLocation(programId, 3, "Radii");
            GL20.glBindAttribLocation(programId, 4, "ClipRect");
            GL20.glBindAttribLocation(programId, 5, "ClipRadii");
            GL20.glBindAttribLocation(programId, 6, "Feather");
            GL20.glBindAttribLocation(programId, 7, "Border");
            GL20.glBindAttribLocation(programId, 8, "GradRepeat");
            GL20.glBindAttribLocation(programId, 9, "GradA");
            GL20.glBindAttribLocation(programId, 10, "GradB");
            GL20.glBindAttribLocation(programId, 11, "Color2");
            GL20.glBindAttribLocation(programId, 12, "RadiiV");
            GL20.glBindAttribLocation(programId, 13, "ClipRadiiV");
            GL20.glBindAttribLocation(programId, 14, "BorderDash");

            GL20.glAttachShader(programId, vShader);
            GL20.glAttachShader(programId, fShader);
            GL20.glLinkProgram(programId);

            if (GL20.glGetProgrami(programId, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                GL20.glDeleteProgram(programId);
                programId = -1;
                return;
            }

            uModelViewMat = GL20.glGetUniformLocation(programId, "ModelViewMat");
            uProjMat = GL20.glGetUniformLocation(programId, "ProjMat");
            uPoseMat = GL20.glGetUniformLocation(programId, "uPoseMat");
            uSegment = GL20.glGetUniformLocation(programId, "uSegment");
            uSegWidth = GL20.glGetUniformLocation(programId, "uSegWidth");

            if (batchFormat == null) {
                batchFormat = VertexFormat.builder()
                        .add("Position", VertexFormatElement.POSITION)
                        .add("Color", VertexFormatElement.COLOR)
                        .add("Rect", genericVec4())
                        .add("Radii", genericVec4())
                        .add("ClipRect", genericVec4())
                        .add("ClipRadii", genericVec4())
                        .add("Feather", genericFloat())
                        .add("Border", genericVec4())
                        .add("GradRepeat", genericVec2())
                        .add("GradA", genericVec4())
                        .add("GradB", genericVec4())
                        .add("Color2", genericVec4())
                        .add("RadiiV", genericVec4())
                        .add("ClipRadiiV", genericVec4())
                        .add("BorderDash", genericVec4())
                        .build();
            }
            if (batchBytes == null) {
                batchBytes = new ByteBufferBuilder(INITIAL_BATCH_BYTES);
            }

            available = true;
        } catch (Throwable t) {
            available = false;
        } finally {
            if (vShader != -1) GL20.glDeleteShader(vShader);
            if (fShader != -1) GL20.glDeleteShader(fShader);
        }
    }

    private static VertexFormatElement genericFloat() {
        return VertexFormatElement.register(VertexFormatElement.findNextId(), 0,
                VertexFormatElement.Type.FLOAT, VertexFormatElement.Usage.GENERIC, 1);
    }

    private static VertexFormatElement genericVec2() {
        return VertexFormatElement.register(VertexFormatElement.findNextId(), 0,
                VertexFormatElement.Type.FLOAT, VertexFormatElement.Usage.GENERIC, 2);
    }

    private static VertexFormatElement genericVec4() {
        return VertexFormatElement.register(VertexFormatElement.findNextId(), 0,
                VertexFormatElement.Type.FLOAT, VertexFormatElement.Usage.GENERIC, 4);
    }

    private static int compileShader(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            GL20.glDeleteShader(shader);
            return -1;
        }
        return shader;
    }

    /**
     * 積んだ Quad を 1 回の draw で描く。空なら何もしない。{@code segment}（ax, ay, bx, by, width）があれば
     * 線分として描く。
     */
    private static void flushBatch(float[] segment) {
        if (batchVertexCount == 0) return;
        int vertexCount = batchVertexCount;
        batchVertexCount = 0;
        ByteBufferBuilder.Result result = batchBytes.build();
        if (result == null) return;
        int indexCount = vertexCount / 4 * 6;
        MeshData mesh = new MeshData(result, new MeshData.DrawState(batchFormat, vertexCount, indexCount,
                VertexFormat.Mode.QUADS, VertexFormat.IndexType.least(indexCount)));

        RenderSystem.enableBlend();
        applyBlend();
        RenderSystem.enableDepthTest();
        GL20.glUseProgram(programId);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buf = stack.mallocFloat(16);
            RenderSystem.getModelViewMatrix().get(buf);
            GL20.glUniformMatrix4fv(uModelViewMat, false, buf);
            RenderSystem.getProjectionMatrix().get(buf);
            GL20.glUniformMatrix4fv(uProjMat, false, buf);
            batchPose.get(buf);
            GL20.glUniformMatrix4fv(uPoseMat, false, buf);
        }
        if (segment != null) {
            GL20.glUniform4f(uSegment, segment[0], segment[1], segment[2], segment[3]);
            GL20.glUniform1f(uSegWidth, segment[4]);
        } else {
            GL20.glUniform1f(uSegWidth, 0f);
        }
        try {
            BufferUploader.draw(mesh);
        } finally {
            GL20.glUseProgram(0);
            applyBlend();
        }
    }

    /**
     * 通常のブレンド。レイヤー（FBO）の中では、アルファも重ねて溜める（{@code ONE / ONE_MINUS_SRC_ALPHA}）。
     * そうしないと、半透明の部品を重ねたときにアルファが最後の部品の値で上書きされ、合成の透け方が狂う。
     */
    private static void applyBlend() {
        if (LayerCompositor.isRecording()) {
            RenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        } else {
            RenderSystem.defaultBlendFunc();
        }
    }

    private static void putVertex(float x, float y, float z, float r, float g, float b, float a,
                                  float rx0, float ry0, float rx1, float ry1,
                                  float rTL, float rTR, float rBR, float rBL,
                                  float cx0, float cy0, float cx1, float cy1,
                                  float cRTL, float cRTR, float cRBR, float cRBL) {
        if (ellipseActive) {
            rTL = ellipseRx[0];
            rTR = ellipseRx[1];
            rBR = ellipseRx[2];
            rBL = ellipseRx[3];
        }
        long p = batchBytes.reserve(VERTEX_STRIDE);
        MemoryUtil.memPutFloat(p, x);
        MemoryUtil.memPutFloat(p + 4, y);
        MemoryUtil.memPutFloat(p + 8, z);
        MemoryUtil.memPutByte(p + 12, toByte(r));
        MemoryUtil.memPutByte(p + 13, toByte(g));
        MemoryUtil.memPutByte(p + 14, toByte(b));
        MemoryUtil.memPutByte(p + 15, toByte(a));
        MemoryUtil.memPutFloat(p + 16, rx0);
        MemoryUtil.memPutFloat(p + 20, ry0);
        MemoryUtil.memPutFloat(p + 24, rx1);
        MemoryUtil.memPutFloat(p + 28, ry1);
        MemoryUtil.memPutFloat(p + 32, rTL);
        MemoryUtil.memPutFloat(p + 36, rTR);
        MemoryUtil.memPutFloat(p + 40, rBR);
        MemoryUtil.memPutFloat(p + 44, rBL);
        MemoryUtil.memPutFloat(p + 48, cx0);
        MemoryUtil.memPutFloat(p + 52, cy0);
        MemoryUtil.memPutFloat(p + 56, cx1);
        MemoryUtil.memPutFloat(p + 60, cy1);
        MemoryUtil.memPutFloat(p + 64, cRTL);
        MemoryUtil.memPutFloat(p + 68, cRTR);
        MemoryUtil.memPutFloat(p + 72, cRBR);
        MemoryUtil.memPutFloat(p + 76, cRBL);
        MemoryUtil.memPutFloat(p + 80, pendingFeather);
        MemoryUtil.memPutFloat(p + 84, pendingBorderT);
        MemoryUtil.memPutFloat(p + 88, pendingBorderR);
        MemoryUtil.memPutFloat(p + 92, pendingBorderB);
        MemoryUtil.memPutFloat(p + 96, pendingBorderL);
        MemoryUtil.memPutFloat(p + 100, pendingGradRepeat[0]);
        MemoryUtil.memPutFloat(p + 104, pendingGradRepeat[1]);
        for (int i = 0; i < 4; i++) {
            MemoryUtil.memPutFloat(p + 108 + i * 4L, pendingGradA[i]);
            MemoryUtil.memPutFloat(p + 124 + i * 4L, pendingGradB[i]);
            MemoryUtil.memPutFloat(p + 140 + i * 4L, pendingColor2[i]);
            MemoryUtil.memPutFloat(p + 156 + i * 4L, pendingRadiiV[i]);
            MemoryUtil.memPutFloat(p + 172 + i * 4L, pendingClipRadiiV[i]);
            MemoryUtil.memPutFloat(p + 188 + i * 4L, pendingDash[i]);
        }
        batchVertexCount++;
    }

    private static byte toByte(float v) {
        return (byte) Math.round(Math.max(0f, Math.min(1f, v)) * 255f);
    }

    public static boolean drawSdfRect(
            Matrix4f poseMatrix,
            float minX, float minY, float maxX, float maxY,
            float rTL, float rTR, float rBR, float rBL,
            float r, float g, float b, float a, float zOffset,
            float cx0, float cy0, float cx1, float cy1,
            float cRTL, float cRTR, float cRBR, float cRBL
    ) {
        return drawSdfGradientRect(
                poseMatrix, minX, minY, maxX, maxY,
                rTL, rTR, rBR, rBL,
                r, g, b, a,
                r, g, b, a,
                r, g, b, a,
                r, g, b, a,
                zOffset, cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL
        );
    }

    /**
     * 2 色のグラデーション（停止点が多いものは区間ごとの 1 枚）を 1 枚の Quad で描く。色は画素ごとにシェーダーが計算するので、帯や格子に刻んだときの継ぎ目や
     * 段が出ない。{@code gradA} / {@code gradB} は記述（{@link ShaderGradient}）、{@code c0*} は始まりの色、
     * {@code c1*} は終わりの色。シェーダーが使えなければ false。
     */
    public static boolean drawSdfShaderGradient(
            Matrix4f poseMatrix,
            float minX, float minY, float maxX, float maxY,
            float r0, float g0, float b0, float a0,
            float r1, float g1, float b1, float a1,
            float[] gradA, float[] gradB, float repeatOrigin, float repeatPeriod, float zOffset,
            float cx0, float cy0, float cx1, float cy1,
            float cRTL, float cRTR, float cRBR, float cRBL
    ) {
        pendingGradRepeat[0] = repeatOrigin;
        pendingGradRepeat[1] = repeatPeriod;
        System.arraycopy(gradA, 0, pendingGradA, 0, 4);
        System.arraycopy(gradB, 0, pendingGradB, 0, 4);
        pendingColor2[0] = r1;
        pendingColor2[1] = g1;
        pendingColor2[2] = b1;
        pendingColor2[3] = a1;
        try {
            return drawSdfRect(poseMatrix, minX, minY, maxX, maxY, 0f, 0f, 0f, 0f, r0, g0, b0, a0, zOffset,
                    cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL);
        } finally {
            java.util.Arrays.fill(pendingGradA, 0f);
            java.util.Arrays.fill(pendingGradB, 0f);
            java.util.Arrays.fill(pendingColor2, 0f);
            java.util.Arrays.fill(pendingGradRepeat, 0f);
        }
    }

    /**
     * 角丸矩形の枠線（太さ {@code wT, wR, wB, wL} の輪）を 1 枚の Quad で描く。外側も内側もアンチエイリアスがかかり、
     * 内側の角丸は太さの分だけ小さくなる。4 辺が同じ色のときに使う。シェーダーが使えなければ false。
     */
    public static boolean drawSdfBorder(
            Matrix4f poseMatrix,
            float minX, float minY, float maxX, float maxY,
            float rTL, float rTR, float rBR, float rBL,
            float wT, float wR, float wB, float wL,
            float r, float g, float b, float a, float zOffset,
            float cx0, float cy0, float cx1, float cy1,
            float cRTL, float cRTR, float cRBR, float cRBL
    ) {
        return drawSdfBorder(poseMatrix, minX, minY, maxX, maxY, rTL, rTR, rBR, rBL, wT, wR, wB, wL, r, g, b, a, zOffset,
                cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL, null);
    }

    /**
     * 同上。{@code dash}（種類 1 破線・2 点線, 周期の数, 線の長さ（点は直径）, 丸い点なら 1）があれば、枠線の中心線に沿って破線・点線にする
     * （4 辺が同じ太さのときだけ）。
     */
    public static boolean drawSdfBorder(
            Matrix4f poseMatrix,
            float minX, float minY, float maxX, float maxY,
            float rTL, float rTR, float rBR, float rBL,
            float wT, float wR, float wB, float wL,
            float r, float g, float b, float a, float zOffset,
            float cx0, float cy0, float cx1, float cy1,
            float cRTL, float cRTR, float cRBR, float cRBL,
            float[] dash
    ) {
        if (dash != null) System.arraycopy(dash, 0, pendingDash, 0, 4);
        pendingBorderT = wT;
        pendingBorderR = wR;
        pendingBorderB = wB;
        pendingBorderL = wL;
        try {
            return drawSdfRect(poseMatrix, minX, minY, maxX, maxY, rTL, rTR, rBR, rBL, r, g, b, a, zOffset,
                    cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL);
        } finally {
            pendingBorderT = pendingBorderR = pendingBorderB = pendingBorderL = 0f;
            java.util.Arrays.fill(pendingDash, 0f);
        }
    }

    /**
     * ぼかした影を 1 枚の Quad で描く。{@code q*} は Quad（ぼかしが消える所まで含めた広さ）、{@code s*} は影の形
     * （角丸矩形。辺の前後 {@code feather} の幅で透明から影の色へ変わる）。形の外側の減衰はシェーダーが計算する。
     * シェーダーが使えなければ false。
     */
    /** {@link #drawSdfShadow} の {@code mode}: 外側の影。{@code h*} の箱の形（角丸矩形）の内側には影を出さない。 */
    public static final float SHADOW_HOLE = -1f;
    /** 同: 内側の影。形（{@code s*}）が影の無い穴で、その外側が影になる。 */
    public static final float SHADOW_INSET = -2f;

    public static boolean drawSdfShadow(
            Matrix4f poseMatrix,
            float qMinX, float qMinY, float qMaxX, float qMaxY,
            float sMinX, float sMinY, float sMaxX, float sMaxY,
            float rTL, float rTR, float rBR, float rBL,
            float r, float g, float b, float a, float zOffset,
            float cx0, float cy0, float cx1, float cy1,
            float cRTL, float cRTR, float cRBR, float cRBL,
            float feather,
            float mode, float hx0, float hy0, float hx1, float hy1,
            float hrTL, float hrTR, float hrBR, float hrBL
    ) {
        return drawSdfShadow(poseMatrix, qMinX, qMinY, qMaxX, qMaxY, sMinX, sMinY, sMaxX, sMaxY, rTL, rTR, rBR, rBL,
                r, g, b, a, zOffset, cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL, feather,
                mode, hx0, hy0, hx1, hy1, hrTL, hrTR, hrBR, hrBL, null);
    }

    /** 同上。{@code holeV} は穴の縦の半径（楕円の角のとき。{@code null} なら円の角）。影の形の縦の半径は {@link #setEllipseRadii} で渡す。 */
    public static boolean drawSdfShadow(
            Matrix4f poseMatrix,
            float qMinX, float qMinY, float qMaxX, float qMaxY,
            float sMinX, float sMinY, float sMaxX, float sMaxY,
            float rTL, float rTR, float rBR, float rBL,
            float r, float g, float b, float a, float zOffset,
            float cx0, float cy0, float cx1, float cy1,
            float cRTL, float cRTR, float cRBR, float cRBL,
            float feather,
            float mode, float hx0, float hy0, float hx1, float hy1,
            float hrTL, float hrTR, float hrBR, float hrBL,
            float[] holeV
    ) {
        if (!isAvailable()) return false;
        if (Math.max(qMinX, cx0) >= Math.min(qMaxX, cx1) || Math.max(qMinY, cy0) >= Math.min(qMaxY, cy1)) {
            return true;
        }
        boolean localPass = !passActive;
        beginPass(poseMatrix);
        try {
            pendingFeather = feather;
            // 影の形のほかの穴（外側の影は箱、内側の影は形そのもの）は、影では使わない枠線とグラデーションの欄で運ぶ
            pendingBorderT = mode;
            pendingGradA[0] = hx0; pendingGradA[1] = hy0; pendingGradA[2] = hx1; pendingGradA[3] = hy1;
            pendingGradB[0] = hrTL; pendingGradB[1] = hrTR; pendingGradB[2] = hrBR; pendingGradB[3] = hrBL;
            if (holeV != null) System.arraycopy(holeV, 0, pendingColor2, 0, 4);
            putVertex(qMinX, qMaxY, zOffset, r, g, b, a, sMinX, sMinY, sMaxX, sMaxY, rTL, rTR, rBR, rBL, cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL);
            putVertex(qMaxX, qMaxY, zOffset, r, g, b, a, sMinX, sMinY, sMaxX, sMaxY, rTL, rTR, rBR, rBL, cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL);
            putVertex(qMaxX, qMinY, zOffset, r, g, b, a, sMinX, sMinY, sMaxX, sMaxY, rTL, rTR, rBR, rBL, cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL);
            putVertex(qMinX, qMinY, zOffset, r, g, b, a, sMinX, sMinY, sMaxX, sMaxY, rTL, rTR, rBR, rBL, cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL);
            return true;
        } catch (Throwable t) {
            batchVertexCount = 0;
            batchBytes.discard();
            return false;
        } finally {
            pendingFeather = 0f;
            pendingBorderT = 0f;
            java.util.Arrays.fill(pendingGradA, 0f);
            java.util.Arrays.fill(pendingGradB, 0f);
            java.util.Arrays.fill(pendingColor2, 0f);
            if (localPass) endPass();
        }
    }

    /**
     * 太さ {@code width} の線分（両端は直角に切る）を、アンチエイリアスと親の角丸クリップ付きで描く。
     * チェックボックスの印のような斜めの線に使う。シェーダーが使えなければ false。
     * 線分は端点を uniform で渡すので、積んでいる Quad を描き切ってから単独で描く。
     */
    public static boolean drawSdfSegment(
            Matrix4f poseMatrix, float ax, float ay, float bx, float by, float width,
            float r, float g, float b, float a, float zOffset,
            float cx0, float cy0, float cx1, float cy1,
            float cRTL, float cRTR, float cRBR, float cRBL
    ) {
        if (!isAvailable()) return false;
        float pad = width + 1f;
        float minX = Math.min(ax, bx) - pad, maxX = Math.max(ax, bx) + pad;
        float minY = Math.min(ay, by) - pad, maxY = Math.max(ay, by) + pad;
        if (Math.max(minX, cx0) >= Math.min(maxX, cx1) || Math.max(minY, cy0) >= Math.min(maxY, cy1)) return true;

        boolean localPass = !passActive;
        beginPass(poseMatrix);
        try {
            flushBatch(null);
            addQuad(minX, minY, maxX, maxY, 0f, 0f, 0f, 0f, zOffset,
                    r, g, b, a, r, g, b, a, r, g, b, a, r, g, b, a,
                    cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL);
            flushBatch(new float[]{ax, ay, bx, by, width});
            return true;
        } catch (Throwable t) {
            batchVertexCount = 0;
            batchBytes.discard();
            return false;
        } finally {
            if (localPass) endPass();
        }
    }

    /**
     * 4頂点それぞれの色（グラデーション対応）と4隅角丸・親角丸クリップを 1-Quad で描画する。
     * 描画は {@link #endPass()}（または行列の切り替わり）までまとめて遅らせる。
     */
    public static boolean drawSdfGradientRect(
            Matrix4f poseMatrix,
            float minX, float minY, float maxX, float maxY,
            float rTL, float rTR, float rBR, float rBL,
            float rTLCol, float gTLCol, float bTLCol, float aTLCol,
            float rTRCol, float gTRCol, float bTRCol, float aTRCol,
            float rBRCol, float gBRCol, float bBRCol, float aBRCol,
            float rBLCol, float gBLCol, float bBLCol, float aBLCol,
            float zOffset,
            float cx0, float cy0, float cx1, float cy1,
            float cRTL, float cRTR, float cRBR, float cRBL
    ) {
        if (!isAvailable()) return false;
        if (Math.max(minX, cx0) >= Math.min(maxX, cx1) || Math.max(minY, cy0) >= Math.min(maxY, cy1)) {
            return true;
        }

        boolean localPass = !passActive;
        beginPass(poseMatrix);

        try {
            addQuad(minX, minY, maxX, maxY, rTL, rTR, rBR, rBL, zOffset,
                    rTLCol, gTLCol, bTLCol, aTLCol,
                    rTRCol, gTRCol, bTRCol, aTRCol,
                    rBRCol, gBRCol, bBRCol, aBRCol,
                    rBLCol, gBLCol, bBLCol, aBLCol,
                    cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL);
            return true;
        } catch (Throwable t) {
            batchVertexCount = 0;
            batchBytes.discard();
            return false;
        } finally {
            if (localPass) {
                endPass();
            }
        }
    }

    private static void addQuad(
            float minX, float minY, float maxX, float maxY,
            float rTL, float rTR, float rBR, float rBL, float z,
            float rTLCol, float gTLCol, float bTLCol, float aTLCol,
            float rTRCol, float gTRCol, float bTRCol, float aTRCol,
            float rBRCol, float gBRCol, float bBRCol, float aBRCol,
            float rBLCol, float gBLCol, float bBLCol, float aBLCol,
            float cx0, float cy0, float cx1, float cy1,
            float cRTL, float cRTR, float cRBR, float cRBL
    ) {
        putVertex(minX, maxY, z, rBLCol, gBLCol, bBLCol, aBLCol, minX, minY, maxX, maxY, rTL, rTR, rBR, rBL, cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL);
        putVertex(maxX, maxY, z, rBRCol, gBRCol, bBRCol, aBRCol, minX, minY, maxX, maxY, rTL, rTR, rBR, rBL, cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL);
        putVertex(maxX, minY, z, rTRCol, gTRCol, bTRCol, aTRCol, minX, minY, maxX, maxY, rTL, rTR, rBR, rBL, cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL);
        putVertex(minX, minY, z, rTLCol, gTLCol, bTLCol, aTLCol, minX, minY, maxX, maxY, rTL, rTR, rBR, rBL, cx0, cy0, cx1, cy1, cRTL, cRTR, cRBR, cRBL);
    }
}
