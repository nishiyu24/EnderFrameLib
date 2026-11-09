package com.nishiyu.enderframelib.core.paint.gl;

import com.nishiyu.enderframelib.core.paint.BlendMode;
import com.nishiyu.enderframelib.core.paint.ClipShape;
import com.nishiyu.enderframelib.core.paint.FilterOp;
import com.nishiyu.enderframelib.core.paint.RenderBackend;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ShaderGradient;
import com.nishiyu.enderframelib.core.paint.gpu.AtlasPacker;
import com.nishiyu.enderframelib.core.paint.gpu.ImageTextureCache;
import com.nishiyu.enderframelib.core.paint.OffsetItemList;
import com.nishiyu.enderframelib.core.paint.gpu.GpuClip;
import com.nishiyu.enderframelib.core.paint.gpu.GpuScrollLayer;
import com.nishiyu.enderframelib.core.paint.gpu.GpuSink;
import com.nishiyu.enderframelib.core.paint.software.ImageProvider;
import com.nishiyu.enderframelib.core.paint.software.PaintResources;
import com.nishiyu.enderframelib.core.paint.PaintSceneDriver;
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
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.ARBTimerQuery;
import com.nishiyu.enderframelib.core.diag.PipelineStage;
import com.nishiyu.enderframelib.core.diag.StageProfiler;
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
 * 回転した文字・縮小した画像・破線の枠線などは {@link CpuLayerFallback} 越しに CPU が描いた層を、
 * 描画順を保ったままテクスチャとして重ねる（変わった範囲だけを転送する）。したがって画は
 * CPU 描画と同じで、図形の多いページほど CPU の仕事が減る。
 *
 * <p>GL のコンテキストは作ったスレッドに結び付く。{@link #render}・{@link #pixmap}・{@link #close} はそのスレッドから呼ぶ。
 * Minecraft の中（すでに GL が初期化された JVM）では使わないこと。
 */
public final class LwjglGlRenderBackend implements RenderBackend, GpuSink {

    private static int liveInstances = 0;
    private static GLFWErrorCallback errorCallback;

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
            uniform vec2 uTexel;   // 層のテクスチャの 1 画素（正規化座標）
            uniform vec2 uUvScale; // 描画先の正規化座標 → テクスチャの正規化座標（縮小した絵は左下の一部だけを使う）
            uniform vec2 uUvMax;   // 読んでよい範囲の端（端の画素の中心）
            uniform int uK;        // 縮小の倍率
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

    private final int width;
    private final int height;
    private final float dpr;
    private static final java.util.concurrent.atomic.AtomicInteger SESSIONS = new java.util.concurrent.atomic.AtomicInteger();
    /** アニメーション状態のセッション名。CPU 描画器（保険）にも同じ名前を使わせる。 */
    private final String sessionId;
    /** 文字・画像の供給元。保険の {@link #cpu} と共有し、差し替えはここに 1 回書く（CPU 描画器へ転送しない）。 */
    private final PaintResources resources = new PaintResources();
    private final CpuLayerFallback cpu;
    /** 図形を Property Tree ＋ 1 回のインスタンス描画で描く GPU 経路（{@link #rectTrees} が false、または作れなければ null。そのときは全て CPU が描く）。 */
    private GlRectStream rects;
    /**
     * GPU 経路を使うか（既定は有効。{@code -Denderframe.gl.recttrees=false} で図形を全て CPU 描画にする）。
     * GPU が受けない図形（せん断の姿勢・縦横比の違う拡縮の角丸・容量／クリップの超過）は、CPU が描いた層を描画順を保って重ねる。
     * GPU へ転送するインスタンスデータは定常フレームで 0 バイト。バッファテクスチャが使えない環境・
     * テクスチャユニットが足りない環境では、作成に失敗して CPU 描画だけで動く。旧バッチ（データテクスチャ）経路は撤去済み。
     */
    public static volatile boolean rectTrees = !"false".equals(System.getProperty("enderframe.gl.recttrees"));
    private final Pixmap readback;
    private final Map<String, Integer> uniforms = new HashMap<>();

    private static final int ATLAS_SIZE = 1024;
    /** アトラスを広げる上限（R8 で 4096² = 16MB）。実際は GL の最大テクスチャ寸法と比べて小さい方。 */
    private static final int ATLAS_MAX_SIZE = 4096;
    private static final int IMAGE_CACHE_LIMIT = 64;
    /** 字形アトラス（R8）。棚詰め。満ちたら空にして詰め直す（字形は描く直前に載せるので、すでに描いた分には響かない）。 */
    private int atlasTex;
    private final AtlasPacker atlas = new AtlasPacker(ATLAS_SIZE, ATLAS_MAX_SIZE);
    private final ImageTextureCache<Pixmap> imageTex = new ImageTextureCache<>(IMAGE_CACHE_LIMIT);
    private long gpuGlyphs, gpuImages;


    private long window;
    /** 0 でなければ、この GL コンテキストとオブジェクト（テクスチャ）を共有して作った（ホストのウィンドウ）。 */
    private final long shareWith;
    /** GLFW の初期化・終了・エラーコールバックをホスト（Minecraft など）が持っているか。 */
    private final boolean hostManaged;
    private GLCapabilities myCaps;
    private int enterDepth;
    private long prevContext;
    private GLCapabilities prevCaps;
    private int fbo, fboTex, overlayTex /* 遅延確保 */, vao, texProgram;
    private int clearPremul = 0;
    private boolean readbackValid;
    private boolean closed;

    // ---- GPU の所要時間（タイマークエリ。既定は無効） ----
    private static final int GPU_QUERY_RING = 4;
    private int[] gpuQueries;
    private final boolean[] gpuQueryPending = new boolean[GPU_QUERY_RING];
    private int gpuQueryNext;
    private boolean gpuQueryOpen;
    private volatile double lastGpuMs = -1;

    /**
     * GPU 側の所要時間の計測を有効にする（{@code GL_TIME_ELAPSED}）。結果は読み戻しを待たず、数フレーム遅れて届き、
     * {@link #lastGpuMillis} と {@link PipelineStage#GPU} に入る。GL 3.3 か {@code ARB_timer_query} が無いときは false を返し何もしない。
     * 無効の間は GL を一切呼ばない。ホストが同じ種類のクエリを張っているコンテキストでは使わないこと（入れ子にできない）。
     */
    public boolean enableGpuTiming() {
        if (closed || myCaps == null || !(myCaps.OpenGL33 || myCaps.GL_ARB_timer_query)) return false;
        if (gpuQueries != null) return true;
        enter();
        try {
            gpuQueries = new int[GPU_QUERY_RING];
            for (int i = 0; i < GPU_QUERY_RING; i++) gpuQueries[i] = GL15.glGenQueries();
        } finally {
            exit();
        }
        return true;
    }

    /** 直近に届いた GPU のフレーム時間（ミリ秒）。まだ無い・無効なら -1。 */
    public double lastGpuMillis() {
        return lastGpuMs;
    }

    /** 終わったクエリの結果を拾う（待たない）。 */
    private void gpuCollect() {
        for (int i = 0; i < GPU_QUERY_RING; i++) {
            if (!gpuQueryPending[i]) continue;
            if (GL15.glGetQueryObjecti(gpuQueries[i], GL15.GL_QUERY_RESULT_AVAILABLE) == GL11.GL_FALSE) continue;
            long ns = myCaps.OpenGL33 ? GL33.glGetQueryObjecti64(gpuQueries[i], GL15.GL_QUERY_RESULT)
                    : ARBTimerQuery.glGetQueryObjecti64(gpuQueries[i], GL15.GL_QUERY_RESULT);
            gpuQueryPending[i] = false;
            lastGpuMs = ns / 1e6;
            StageProfiler.record(PipelineStage.GPU, ns);
        }
    }

    private void gpuBegin() {
        if (gpuQueries == null) return;
        gpuCollect();
        if (gpuQueryPending[gpuQueryNext]) return; // 輪が一巡して未回収。このフレームは測らない
        GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, gpuQueries[gpuQueryNext]);
        gpuQueryOpen = true;
    }

    private void gpuEnd() {
        if (!gpuQueryOpen) return;
        GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
        gpuQueryOpen = false;
        gpuQueryPending[gpuQueryNext] = true;
        gpuQueryNext = (gpuQueryNext + 1) % GPU_QUERY_RING;
    }
    /** 共有時、直近のフレームの完了を表すフェンス（0 は無し）。{@link #waitForRender} が GPU 側で待つ。 */
    private long renderFence;

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
        this.sessionId = "gl-" + SESSIONS.incrementAndGet();
        this.cpu = new CpuLayerFallback(cssW, cssH, devicePixelRatio, sessionId, resources);
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

    /** 溜まった GL 命令をすべて終わらせる。別のコンテキストが {@link #colorTexture()} を読む前に CPU を止めて待ちたいときに使う（共有時の {@link #render} はフェンスを置くだけで、ホストは {@link #waitForRender} を呼ぶ）。 */
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
        if (!retained.isEmpty()) { // 文字の形が変わるので、保持した層は使えない
            enter();
            try {
                releaseRetained();
            } finally {
                exit();
            }
        }
        resources.fonts(resolver);
        return this;
    }

    @Override
    public String sessionId() {
        return sessionId;
    }

    /** 直近に描いた {@code items} に、時刻 {@code nowMs} でまだ動いているアニメーションがあるか。false なら同じ項目の再描画は同じ絵。 */
    public boolean hasActiveAnimation(List<DisplayItem> items, long nowMs) {
        return PaintSceneDriver.anyActiveAt(sessionId, items, nowMs);
    }

    public LwjglGlRenderBackend images(ImageProvider provider) {
        resources.images(provider);
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
        return rects != null ? rects.accepted() - gpuGlyphs - gpuImages : 0;
    }

    /** 新経路（Property Tree）の矩形描画の回数の累計。 */
    public long rectTreeDrawCalls() {
        return rects != null ? rects.drawCalls() : 0;
    }

    /** 新経路に載せた矩形の数の累計。 */
    public long rectTreeShapes() {
        return rects != null ? rects.accepted() : 0;
    }

    /** 診断: 新経路が受け付けなかった矩形の理由別の数（姿勢・格子・角・容量／不透明度）と、クリップで回した数。 */
    public long[] rectTreeRejects() {
        long[] r = new long[5];
        if (rects != null) {
            System.arraycopy(rects.rejects, 0, r, 0, 4);
            r[4] = rects.clipRejects;
        }
        return r;
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
        return rects != null ? rects.drawCalls() : 0;
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
        // overlayTex は CPU が実際に画素を塗った最初の描画（drawPixels）まで作らない
        fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, fboTex, 0);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("フレームバッファが不完全: 0x" + Integer.toHexString(status));
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        atlasTex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlasTex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, ATLAS_SIZE, ATLAS_SIZE, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        atlas.setMaxSize(Math.min(ATLAS_MAX_SIZE, GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE)));
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        texProgram = link(TEX_VERT, TEX_FRAG);
        if (rectTrees) {
            try {
                rects = new GlRectStream(4096);
                org.slf4j.LoggerFactory.getLogger(LwjglGlRenderBackend.class).info(
                        "GPU プリミティブ経路を使う（永続マップ={}, 容量={}）", rects.isPersistent(), rects.capacity());
            } catch (RuntimeException | LinkageError e) {
                rects = null; // 作れない環境（バッファテクスチャ非対応など）は従来経路だけで描く
            }
        }
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
            gpuBegin();
            try {
                renderLocked(items, nowMs);
            } finally {
                gpuEnd();
            }
        } finally {
            exit();
        }
    }

    /**
     * スクロール節（{@code _scrollc}）の移動量を、デバイス画素の整数に丸める。スクロールを層の貼り付け位置の移動で済ませる経路
     * （{@link #retainedScroll}）と、毎フレーム描き直す経路が、同じ位置に描くようにする（保持した層は整数画素だけずらせる）。
     * 差し替えの表（{@link OffsetItemList}）の中の節だけを見る（基底の節の移動量は 0）。
     */
    private List<DisplayItem> snapScrollNodes(List<DisplayItem> items) {
        if (!retainedScroll || !(items instanceof OffsetItemList ol)) return items;
        float dpr = devicePixelRatio();
        DisplayItem[] repl = null;
        int n = ol.overrideCount();
        for (int k = 0; k < n; k++) {
            DisplayItem it = ol.replacementAt(k);
            if (it.type() != com.nishiyu.enderframelib.core.paint.model.ElementType.NODE
                    || !it.id().endsWith(com.nishiyu.enderframelib.core.paint.PaintSceneDriver.SCROLL_CONTENT_SUFFIX)) continue;
            float tx = Math.round(it.translateX() * dpr) / dpr, ty = Math.round(it.translateY() * dpr) / dpr;
            if (tx == it.translateX() && ty == it.translateY()) continue;
            if (repl == null) {
                repl = new DisplayItem[n];
                for (int j = 0; j < n; j++) repl[j] = ol.replacementAt(j);
            }
            repl[k] = it.withTranslation(tx, ty);
        }
        if (repl == null) return items;
        int[] pos = new int[n];
        for (int k = 0; k < n; k++) pos[k] = ol.positionAt(k);
        return new OffsetItemList(ol.base(), pos, repl);
    }

    private void renderLocked(List<DisplayItem> items, long nowMs) {
        items = snapScrollNodes(items);
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
        layerStack.clear();
        layerPool.clear();
        layerPool.addAll(allLayers);
        if (rects != null) rects.begin();
        cpu.renderOnto(items, nowMs, this);
        while (!layerStack.isEmpty()) endGroup(); // 例外で閉じ損ねた層があっても根の描画先へ戻す
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        flushAll();
        if (rects != null) rects.end();
        if (hostManaged) publishFrame();
    }

    /**
     * 共有時にフレームの書き込みをホストへ引き渡す。{@code glFinish} で CPU を止める代わりに、
     * {@code glFlush} でコマンドを送り出し、完了フェンスを置く（ホストは {@link #waitForRender} で GPU 側だけ待つ）。
     * フェンスを使えない GL（3.2 未満）では従来どおり {@code glFinish}。
     */
    private void publishFrame() {
        if (renderFence != 0) {
            GL32.glDeleteSync(renderFence);
            renderFence = 0;
        }
        if (!myCaps.OpenGL32) {
            GL11.glFinish();
            return;
        }
        renderFence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        GL11.glFlush(); // フェンスをコマンドキューに確実に流す（流さないと待つ側が永久に待つ）
    }

    /**
     * ホストのコンテキストを現在にした状態で、{@link #colorTexture()} を読む命令の直前に呼ぶ。
     * 直近の {@link #render} の GPU 処理が終わるまで、ホストの GPU コマンドの実行を止める（{@code glWaitSync}）。
     * CPU スレッドはブロックしない。共有でない場合・フェンスが無い場合は何もしない。
     */
    public void waitForRender() {
        if (closed || renderFence == 0) return;
        GL32.glWaitSync(renderFence, 0, GL32.GL_TIMEOUT_IGNORED);
    }

    /** CPU の層を重ね、続けて溜めた図形を描き切る（描画順は CPU 層 → 図形）。 */
    private void flushAll() {
        drainRects();
        flushOverlay();
    }

    /** 新経路に溜めた矩形を、いまの描画先へ吐く。 */
    private void drainRects() {
        if (rects == null || rects.pending() == 0) return;
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, currentFbo());
        GL11.glViewport(0, 0, width, height);
        if (rectRunImg != 0) { // 溜めた画像のテクスチャを結び直す（imageTexture() が新しい画像でユニット 2 を上書きしていることがある）
            GL13.glActiveTexture(GL13.GL_TEXTURE2);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, rectRunImg);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
        }
        rects.drain(width, height);
        rectRunImg = 0;
    }

    /** 新経路に溜めた矩形のうち、画像が使うテクスチャ（ユニット 2）。0 なら画像は無い。テクスチャを替える前に吐き切る。 */
    private int rectRunImg;

    /** CPU が描いた層のうち、変わった範囲をテクスチャにして重ね、層を空に戻す。 */
    /** CPU の根の画素（画面サイズ）を確保済みか。図形が全部 GPU に受け取られている間は false のまま（テスト用）。 */
    boolean cpuPixelsAllocated() {
        return cpu.isAllocated();
    }

    /** CPU 層の重ね用テクスチャを確保済みか（テスト用）。 */
    boolean overlayTextureAllocated() {
        return overlayTex != 0;
    }

    private void flushOverlay() {
        int[] b = cpu.dirtyBounds();
        if (b == null) return;
        drainRects(); // 溜めた矩形は、あとから来る CPU の層より先に描く（描画順）
        drawPixels(cpu.pixels(), b, false);
        cpu.clearDirty();
    }

    @Override
    public Pixmap snapshotTarget() {
        return snapshotTarget(new int[]{0, 0, width, height});
    }

    /** 読み戻し先。フレーム間・呼び出し間で使い回す（範囲外は前回の残りで不定）。 */
    private Pixmap snapshotPm;
    private int[] snapshotRaw = new int[0];

    @Override
    public Pixmap snapshotTarget(int[] region) {
        readbacks++;
        flushAll();
        if (!layerStack.isEmpty()) ensureCleared(layerStack.get(layerStack.size() - 1));
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, currentFbo());
        int x0 = Math.max(0, region[0]), y0 = Math.max(0, region[1]);
        int x1 = Math.min(width, region[2]), y1 = Math.min(height, region[3]);
        if (snapshotPm == null) snapshotPm = new Pixmap(width, height);
        Pixmap pm = snapshotPm;
        int rw = x1 - x0, rh = y1 - y0;
        if (rw <= 0 || rh <= 0) return pm;
        if (snapshotRaw.length < rw * rh) snapshotRaw = new int[rw * rh];
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
        GL11.glReadPixels(x0, height - y1, rw, rh, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, snapshotRaw);
        int[] dst = pm.pixels();
        for (int y = 0; y < rh; y++) System.arraycopy(snapshotRaw, (rh - 1 - y) * rw, dst, (y0 + y) * width + x0, rw);
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
        touch(b[0], b[1], b[2], b[3]);
        int bw = b[2] - b[0], bh = b[3] - b[1];
        IntBuffer buf = MemoryUtil.memAllocInt(bw * bh);
        try {
            for (int y = 0; y < bh; y++) buf.put(src, (b[1] + y) * width + b[0], bw);
            buf.flip();
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            if (overlayTex == 0) overlayTex = newTexture(null); // 初めて CPU の層を重ねるときに確保
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
        /** この層に描いた範囲（デバイス画素 {@code x0, y0, x1, y1}。何も描いていなければ x0 > x1）。層の外は透明。 */
        float[] bounds = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        /** 描いた範囲を追えない描き方（背景のフィルターなど）をした: 効果は画面全体に掛ける。 */
        boolean unbounded;
        /** 層を透明で塗り済みか。最初に描くとき（{@link #touch}）まで塗らない: 何も描かない層（画面の外など）の塗りを省く。 */
        boolean cleared;
        /** 保持するスクロール層への記録なら、その鍵。通常の層のようには合成せず、{@link #endScrollLayer} が保存して重ねる。 */
        String retainKey;
    }

    /** 層を、まだなら透明で塗る。塗ったあとは、その層が描画先として結ばれている。 */
    private void ensureCleared(Entry e) {
        if (e.cleared) return;
        e.cleared = true;
        clearLayer(e.layer);
    }

    /**
     * 今の層（無ければ根）に、デバイス画素の矩形を描いたと記録する。層のフィルターは、描いた範囲（とフィルターの到達距離）の
     * 中だけに掛ければよい。図形・字形・CPU の層の貼り付けは、すべてここを通す。
     */
    private void touch(float x0, float y0, float x1, float y1) {
        if (layerStack.isEmpty()) return;
        Entry top = layerStack.get(layerStack.size() - 1);
        ensureCleared(top); // この層への最初の描画: ここで透明にする（描くものが無い層は塗らない）
        float[] b = top.bounds;
        if (x0 < b[0]) b[0] = x0;
        if (y0 < b[1]) b[1] = y0;
        if (x1 > b[2]) b[2] = x1;
        if (y1 > b[3]) b[3] = y1;
    }

    private void touchUnbounded() {
        if (!layerStack.isEmpty()) layerStack.get(layerStack.size() - 1).unbounded = true;
    }

    /** {@code region}（{@code x0, y0, x1, y1}）を画面の内側に収め、整数に丸め広げる。空なら null。 */
    private int[] clampToScreen(float[] r) {
        int x0 = Math.max(0, (int) Math.floor(r[0])), y0 = Math.max(0, (int) Math.floor(r[1]));
        int x1 = Math.min(width, (int) Math.ceil(r[2])), y1 = Math.min(height, (int) Math.ceil(r[3]));
        return x1 > x0 && y1 > y0 ? new int[]{x0, y0, x1, y1} : null;
    }

    /** 以降の描画を {@code r}（デバイス画素。上が 0）の中だけに限る。GL の y は上向きなので反転する。 */
    private void scissorTo(int[] r) {
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(r[0], height - r[3], r[2] - r[0], r[3] - r[1]);
    }

    private void scissorOff() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    /**
     * GPU の層を入れ子にできる深さ。超えると CPU の層に落ち、{@code mix-blend-mode} / {@code backdrop-filter} は GPU の描画先を
     * 読み戻して混ぜる（遅く、コマンドバッファ型 API では不可能な経路）。深さそのものは VRAM（{@link #LAYER_VRAM_BUDGET}）で縛る。
     * 以前は 8 固定だった。読み戻しの経路を検証するテストは、この値を下げて通す。
     */
    public static volatile int maxLayerDepth = 32;
    /** 層のテクスチャ（1 枚 {@code 幅 × 高さ × 4} バイト）の合計の上限。超える分は CPU の層に落ちる。 */
    private static final long LAYER_VRAM_BUDGET = 512L * 1024 * 1024;
    /** 診断: 途中の読み戻し（{@link #snapshotTarget}）の累計回数。 */
    private long readbacks;

    /** 診断: 描画先を CPU へ読み戻した回数の累計（GPU の層で完結していれば増えない）。 */
    public long readbackCount() {
        return readbacks;
    }
    /** 層の効果を、描いた範囲の中だけに掛けるか。false なら従来どおり画面全体（切り分け用。{@code -Denderframe.gl.fullscreenlayers=true}）。 */
    public static volatile boolean boundedLayers = !Boolean.getBoolean("enderframe.gl.fullscreenlayers");
    private final java.util.ArrayDeque<GpuLayer> layerPool = new java.util.ArrayDeque<>();
    private final java.util.ArrayList<Entry> layerStack = new java.util.ArrayList<>();
    private final java.util.ArrayList<GpuLayer> allLayers = new java.util.ArrayList<>();
    private int fxProgram;

    private GpuLayer acquireLayer() {
        GpuLayer l = layerPool.pollFirst();
        if (l != null) return l;
        return createLayer(true);
    }

    /** 画面と同じ大きさの描画先を作る。{@code pooled} なら毎フレーム返される層の表に載せる（保持する層は載せない）。 */
    private GpuLayer createLayer(boolean pooled) {
        GpuLayer l;
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
        if (pooled) allLayers.add(l);
        // 作りたてのテクスチャの中身は決まっていない（前に使われたメモリが残る）。範囲を限って塗る効果が、塗っていない所を読んでも
        // 値が揺れないよう、最初に透明で塗っておく
        GL11.glViewport(0, 0, width, height);
        GL11.glClearColor(0f, 0f, 0f, 0f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
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
        if (layerStack.size() >= maxLayerDepth - 1) return false;
        if (layerPool.isEmpty() && (allLayers.size() + 1L) * width * height * 4L > LAYER_VRAM_BUDGET) return false;
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
        return true;
    }

    @Override
    public void endMask() {
        if (layerStack.isEmpty() || !layerStack.get(layerStack.size() - 1).isMask) return;
        flushAll();
        Entry m = layerStack.remove(layerStack.size() - 1);
        ensureCleared(m); // マスクに何も描かれなくても、合成が読むので透明にしておく
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
        // 描いた範囲が分かる層は、フィルターも合成も、その範囲（とぼかしの到達距離）の中だけに掛ける（全画面を毎回塗らない）
        // mix-blend-mode も範囲を限れる: 透明な画素は混ぜても背景のまま（CSS Compositing）なので、層の外は何も変わらない
        boolean bounded = boundedLayers && !e.unbounded && (e.extras == null || e.extras.clip() == null)
                && stages.size() <= 1;
        if (!bounded || e.mask != null) ensureCleared(e); // 範囲を限らない合成は層の全面を読むので、描かれていなくても透明にしておく
        int[] region = bounded ? clampToScreen(e.bounds) : null;
        boolean hasBackdrop = e.extras != null && e.extras.backdropShape() != null && !e.extras.backdrop().isEmpty();
        if (bounded && region == null && !hasBackdrop) { // 何も描いていない層
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, currentFbo());
            layerPool.addFirst(e.layer);
            if (e.mask != null) layerPool.addFirst(e.mask);
            return;
        }
        GL11.glDisable(GL11.GL_BLEND);
        if (hasBackdrop) applyBackdropFilter(e, temps);
        if (bounded && region == null) { // 中身は何も描いていない（背景を加工するだけの箱）: 層の合成は要らない
            GL11.glEnable(GL11.GL_BLEND);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, currentFbo());
            layerPool.addFirst(e.layer);
            if (e.mask != null) layerPool.addFirst(e.mask);
            for (GpuLayer t : temps) layerPool.addFirst(t);
            return;
        }
        for (Stage st : stages) {
            GpuLayer next = runStage(st, cur, e.map, temps, region);
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
        GpuLayer backdrop = e.blend != BlendMode.NORMAL ? copyBackdrop(region) : null;
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
            if (region != null) scissorTo(region);
            GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
            if (region != null) scissorOff();
            GL11.glEnable(GL11.GL_BLEND);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            if (region != null) touch(region[0], region[1], region[2], region[3]); // 混ぜた結果で親を上書きした範囲
            else touch(0, 0, width, height);
            layerPool.addFirst(e.layer);
            if (e.mask != null) layerPool.addFirst(e.mask);
            for (GpuLayer t : temps) layerPool.addFirst(t);
            return;
        }
        if (!layerStack.isEmpty()) ensureCleared(layerStack.get(layerStack.size() - 1)); // 親へ重ねる前に、親を透明にしておく（描いたあとに塗ると消える）
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, currentFbo());
        GL11.glViewport(0, 0, width, height);
        useFx(1, cur, e.mask);
        GL20.glUniform1f(loc(fxProgram, "uOpacity"), finalOpacity);
        GL20.glUniform1i(loc(fxProgram, "uMaskMode"), e.mask == null ? 0 : (e.luminance ? 2 : 1));
        setMatrix(finalMatrix);
        GL14Blend.premultiplied();
        if (region != null) scissorTo(region);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        if (region != null) scissorOff();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        // 親の層に、貼った範囲を伝える（フィルターで広がったあとの範囲）
        if (region != null) touch(region[0], region[1], region[2], region[3]);
        else if (e.unbounded) touchUnbounded();
        else touch(0, 0, width, height);

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
        List<Stage> bstages = stages(e.extras.backdrop());
        // 形（箱）の範囲だけに限る: 範囲の外は、混ぜても元のまま。段が 1 つ（ぼかしか色）のときだけ（段が重なると、前の段の出力を範囲の外まで読む）
        int[] shape = boundedLayers && bstages.size() == 1 ? backdropDeviceBounds(e) : null;
        if (shape == OFFSCREEN) return; // 形が画面の外: 何も変わらない
        int[] copyRegion = null;
        if (shape != null) {
            int margin = 0;
            Stage st = bstages.get(0);
            if (st.kind != StageKind.COLOR) {
                float sx = st.sigma * (float) Math.hypot(e.map[0], e.map[1]), sy = st.sigma * (float) Math.hypot(e.map[2], e.map[3]);
                margin = (int) Math.ceil(3.5f * Math.max(sx, sy)) + 4;
            }
            copyRegion = clampTo(new int[]{shape[0] - margin, shape[1] - margin, shape[2] + margin, shape[3] + margin}, width, height);
        }
        GpuLayer orig = copyBackdrop(copyRegion);
        if (orig == null) return;
        if (shape != null) touch(shape[0], shape[1], shape[2], shape[3]);
        else touchUnbounded(); // 形が分からない: 親の効果は画面全体に掛ける
        temps.add(orig);
        GpuLayer filtered = orig;
        for (Stage st : bstages) {
            int[] out = shape == null ? null : shape.clone();
            GpuLayer next = st.kind == StageKind.BLUR ? blur(st.sigma, filtered, e.map, temps, out, false)
                    : runStage(st, filtered, e.map, temps, out);
            if (next == null) break;
            filtered = next;
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, currentFbo());
        GL11.glViewport(0, 0, width, height);
        useFx(6, filtered, null, orig);
        GL20.glUniform1f(loc(fxProgram, "uOpacity"), e.opacity);
        setShapes(e.extras, true);
        if (shape != null) scissorTo(shape);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        if (shape != null) scissorOff();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    /** {@code backdrop-filter} の形（箱の座標）をデバイス画素の外接矩形にして画面に収める。形が読めなければ null。 */
    private int[] backdropDeviceBounds(Entry e) {
        ClipShape shape = e.extras.backdropShape();
        float[] d = shape == null ? null : shape.data();
        if (d == null || d.length < 4) return null;
        Matrix2D pose = e.extras.pose();
        double[] xs = {d[0], d[2], d[0], d[2]}, ys = {d[1], d[1], d[3], d[3]};
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            double x = pose.applyX(xs[i], ys[i]), y = pose.applyY(xs[i], ys[i]);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }
        int[] r = clampToScreen(new float[]{(float) minX - 1, (float) minY - 1, (float) maxX + 1, (float) maxY + 1});
        return r == null ? OFFSCREEN : r;
    }

    /** {@link #backdropDeviceBounds} が返す、形が画面の外にあるという印。 */
    private static final int[] OFFSCREEN = new int[0];

    /** 現在の描画先の絵を 1 枚の層に写す（{@code mix-blend-mode} が読む背景）。層が借りられなければ null。 */
    private GpuLayer copyBackdrop() {
        return copyBackdrop(null);
    }

    /** {@code region}（デバイス画素）があれば、その範囲だけを写す（範囲の外は、読まれない前提）。 */
    private GpuLayer copyBackdrop(int[] region) {
        GpuLayer bd = acquireLayer();
        if (bd == null) return null;
        if (!layerStack.isEmpty()) ensureCleared(layerStack.get(layerStack.size() - 1)); // 読むのは今の描画先: まだ塗っていない層なら先に透明にする
        int[] g = region == null ? new int[]{0, 0, width, height} : toGl(region);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, currentFbo());
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, bd.fbo());
        GL30.glBlitFramebuffer(g[0], g[1], g[2], g[3], g[0], g[1], g[2], g[3], GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, currentFbo());
        return bd;
    }

    /** フィルターの段を 1 つ実行して結果の層を返す（借りられなければ null）。 */
    private GpuLayer runStage(Stage st, GpuLayer in, double[] m, List<GpuLayer> temps, int[] region) {
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
                return blur(st.sigma, in, m, temps, region);
            }
            default -> {
                int[] inRegion = region == null ? null : region.clone();
                GpuLayer blurred = blur(st.sigma, in, m, temps, region);
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
                if (region != null) {
                    // 影はぼかした範囲を (ox, oy) ずらした所に出る。元の絵の範囲との和
                    int sx0 = Math.max(0, Math.min(region[0] + Math.round(ox), inRegion[0])), sy0 = Math.max(0, Math.min(region[1] + Math.round(oy), inRegion[1]));
                    int sx1 = Math.min(width, Math.max(region[2] + Math.round(ox), inRegion[2])), sy1 = Math.min(height, Math.max(region[3] + Math.round(oy), inRegion[3]));
                    region[0] = sx0;
                    region[1] = sy0;
                    region[2] = sx1;
                    region[3] = sy1;
                    scissorTo(region);
                }
                GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
                if (region != null) scissorOff();
                return out;
            }
        }
    }

    /**
     * 横・縦の 2 回のガウスぼかし（{@code LayerFx} と同じ標本の取り方）。{@code region}（{@code x0, y0, x1, y1}）があれば、
     * 入力の絵が描かれている範囲で、その外は透明。結果が描かれる範囲（ぼかしの到達距離だけ広げたもの）に書き換え、その中だけを
     * 塗る。{@code null} なら画面全体を塗る。
     *
     * <p>縦のパスは横のパスの出力を縦の到達距離だけ上下に読むので、横のパスは縦の分を 2 倍に広げた範囲まで塗る
     * （範囲の外には、前に使われたときの絵が残っているので、読まれる所は全部塗っておく）。</p>
     */
    private GpuLayer blur(float sigma, GpuLayer in, double[] m, List<GpuLayer> temps, int[] region) {
        return blur(sigma, in, m, temps, region, true);
    }

    /**
     * @param expand true: {@code region} は絵が描かれている範囲（外は透明）で、ぼかしで広がった範囲に書き換える。
     *               false: {@code region} は欲しい出力の範囲で、入力は全面に有効（背景のコピー）。範囲は変えない
     */
    private GpuLayer blur(float sigma, GpuLayer in, double[] m, List<GpuLayer> temps, int[] region, boolean expand) {
        float sx = sigma * (float) Math.hypot(m[0], m[1]);
        float sy = sigma * (float) Math.hypot(m[2], m[3]);
        if (sx < 0.05f && sy < 0.05f) return in;
        float[] step = new float[2];
        int[] reach = new int[2];
        for (int axis = 0; axis < 2; axis++) {
            float sg = axis == 0 ? sx : sy;
            if (sg < 0.05f) continue;
            step[axis] = Math.max(1f, sg / 12f);
            // +2: 標本は双一次補間で隣の画素まで読む。塗っていない所（前の絵や未初期化の値が残る）を読まないよう、少し広く塗る
            reach[axis] = (int) Math.ceil(Math.min(40, Math.ceil(3f * sg / step[axis])) * step[axis]) + 2;
        }
        if (region != null && downsampledBlur) {
            float smax = Math.max(sx, sy);
            int k = 1;
            while (smax / k > 6f && k < 16) k *= 2;
            if (k > 1) return blurDownsampled(sx, sy, k, in, temps, region, expand);
        }
        int[] first = null, second = null;
        if (region != null) {
            int ex = expand ? reach[0] : 0, ey = expand ? reach[1] : 0;
            second = new int[]{region[0] - ex, region[1] - ey, region[2] + ex, region[3] + ey};
            first = new int[]{second[0], second[1] - reach[1], second[2], second[3] + reach[1]};
            first = clampInts(first);
            second = clampInts(second);
            if (first == null || second == null) return in;
        }
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
            int taps = (int) Math.min(40, Math.ceil(3f * sg / step[axis]));
            GL20.glUniform2f(loc(fxProgram, "uDir"), axis == 0 ? 1f : 0f, axis == 0 ? 0f : 1f);
            GL20.glUniform1f(loc(fxProgram, "uSigma"), sg);
            GL20.glUniform1i(loc(fxProgram, "uTaps"), taps);
            GL20.glUniform1f(loc(fxProgram, "uStep"), step[axis]);
            if (region != null) scissorTo(axis == 0 && sy >= 0.05f ? first : second);
            GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
            if (region != null) scissorOff();
            cur = out;
        }
        if (region != null && expand) {
            region[0] = second[0];
            region[1] = second[1];
            region[2] = second[2];
            region[3] = second[3];
        }
        return cur;
    }

    /** 大きな標準偏差のぼかしを縮小して行うか。false なら全解像度（切り分け用。{@code -Denderframe.gl.fullresblur=true}）。 */
    public static volatile boolean downsampledBlur = !Boolean.getBoolean("enderframe.gl.fullresblur");

    /** デバイス画素（上が 0）の矩形 → GL の画素（下が 0）の矩形。 */
    private int[] toGl(int[] dev) {
        return new int[]{dev[0], height - dev[3], dev[2], height - dev[1]};
    }

    private static int[] clampTo(int[] r, int w, int h) {
        int x0 = Math.max(0, r[0]), y0 = Math.max(0, r[1]), x1 = Math.min(w, r[2]), y1 = Math.min(h, r[3]);
        return x1 > x0 && y1 > y0 ? new int[]{x0, y0, x1, y1} : null;
    }

    private void scissorGl(int[] r) {
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(r[0], r[1], r[2] - r[0], r[3] - r[1]);
    }

    /** 縮小した絵（層テクスチャの左下 {@code sw x sh}）への描画の準備: 視点・四角・読む範囲を縮小側に合わせる。 */
    private void smallPass(GpuLayer dst, int mode, GpuLayer src, int sw, int sh, int k) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, dst.fbo());
        GL11.glViewport(0, 0, sw, sh);
        useFx(mode, src, null);
        GL20.glUniform4f(loc(fxProgram, "uDev"), 0, 0, sw, sh);
        GL20.glUniform2f(loc(fxProgram, "uView"), sw, sh);
        GL20.glUniform1i(loc(fxProgram, "uK"), k);
        GL20.glUniform2f(loc(fxProgram, "uUvScale"), (float) sw / width, (float) sh / height);
        GL20.glUniform2f(loc(fxProgram, "uUvMax"), (sw - 0.5f) / width, (sh - 0.5f) / height);
    }

    /**
     * 標準偏差が大きいぼかしを、{@code k} 分の 1 に縮小して行う（ぼかした絵は滑らかなので、縮小してもほぼ同じに見える）。
     * 縮小（箱平均）→ 横 → 縦（縮小した絵の上で、標準偏差は {@code 1/k}）→ 双一次で拡大。描画の範囲は縮小側の座標で持ち、
     * 必要な所（描いた範囲 + ぼかしの到達距離）だけを塗る。縮小した絵の端は読まない（端の画素を伸ばす）ので、画面の端に掛かる絵は
     * 全解像度のときと同じく端が伸びる。
     */
    private GpuLayer blurDownsampled(float sx, float sy, int k, GpuLayer in, List<GpuLayer> temps, int[] region, boolean expand) {
        int sw = (width + k - 1) / k, sh = (height + k - 1) / k;
        float[] sg = {sx / k, sy / k};
        int[] reach = new int[2];
        float[] step = new float[2];
        int[] taps = new int[2];
        for (int axis = 0; axis < 2; axis++) {
            if (sg[axis] < 0.05f) continue;
            step[axis] = Math.max(1f, sg[axis] / 12f);
            taps[axis] = (int) Math.min(40, Math.ceil(3f * sg[axis] / step[axis]));
            reach[axis] = (int) Math.ceil(taps[axis] * step[axis]) + 2; // +2: 双一次補間の読み出しの余白
        }
        int[] g = toGl(region);
        int[] content = {g[0] / k, g[1] / k, (g[2] + k - 1) / k, (g[3] + k - 1) / k};
        // 出力の範囲が決まっている（背景のコピー）ときも、拡大で内側へ縮める 1 テクセルの分は広く塗る
        int ex = expand ? reach[0] : 1, ey = expand ? reach[1] : 1;
        int[] r2 = clampTo(new int[]{content[0] - ex, content[1] - ey, content[2] + ex, content[3] + ey}, sw, sh);
        if (r2 == null) return in;
        int[] r1 = clampTo(new int[]{r2[0], r2[1] - reach[1], r2[2], r2[3] + reach[1]}, sw, sh);
        int[] dsr = clampTo(new int[]{r1[0] - reach[0], r1[1], r1[2] + reach[0], r1[3]}, sw, sh);

        GpuLayer a = acquireLayer(), b = a == null ? null : acquireLayer(), c = b == null ? null : acquireLayer();
        GpuLayer out = c == null ? null : acquireLayer();
        if (out == null) {
            for (GpuLayer l : new GpuLayer[]{a, b, c}) if (l != null) layerPool.addFirst(l);
            return null;
        }
        temps.add(a);
        temps.add(b);
        temps.add(c);
        temps.add(out);

        smallPass(a, 7, in, sw, sh, k);
        scissorGl(dsr);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);

        GpuLayer cur = a, spare = b;
        for (int axis = 0; axis < 2; axis++) {
            if (sg[axis] < 0.05f) continue;
            GpuLayer dst = axis == 0 ? b : c;
            smallPass(dst, 2, cur, sw, sh, 1);
            GL20.glUniform2f(loc(fxProgram, "uDir"), axis == 0 ? 1f : 0f, axis == 0 ? 0f : 1f);
            GL20.glUniform1f(loc(fxProgram, "uSigma"), sg[axis]);
            GL20.glUniform1i(loc(fxProgram, "uTaps"), taps[axis]);
            GL20.glUniform1f(loc(fxProgram, "uStep"), step[axis]);
            scissorGl(axis == 0 && sg[1] >= 0.05f ? r1 : r2);
            GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
            cur = dst;
        }

        // 拡大: 縮小した絵の r2（縮小側の GL 画素）に当たる範囲だけを、全解像度の層へ
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, out.fbo());
        GL11.glViewport(0, 0, width, height);
        useFx(8, cur, null);
        GL20.glUniform2f(loc(fxProgram, "uUvScale"), (float) sw / width, (float) sh / height);
        GL20.glUniform2f(loc(fxProgram, "uUvMax"), (sw - 0.5f) / width, (sh - 0.5f) / height);
        // 双一次補間は範囲の外の 1 テクセルまで読む（そこは塗っていない）ので、画面の端でない辺は 1 テクセル内側までを使う
        int[] up = clampTo(new int[]{(r2[0] > 0 ? r2[0] + 1 : 0) * k, (r2[1] > 0 ? r2[1] + 1 : 0) * k,
                (r2[2] < sw ? r2[2] - 1 : sw) * k, (r2[3] < sh ? r2[3] - 1 : sh) * k}, width, height);
        if (up == null) return in;
        scissorGl(up);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        scissorOff();
        // 結果の範囲（デバイス画素）。背景のコピーのぼかし（expand = false）は、欲しい範囲のまま
        if (expand) {
            region[0] = up[0];
            region[1] = height - up[3];
            region[2] = up[2];
            region[3] = height - up[1];
        }
        return out;
    }

    private int[] clampInts(int[] r) {
        int x0 = Math.max(0, r[0]), y0 = Math.max(0, r[1]), x1 = Math.min(width, r[2]), y1 = Math.min(height, r[3]);
        return x1 > x0 && y1 > y0 ? new int[]{x0, y0, x1, y1} : null;
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
        GL20.glUniform2f(loc(fxProgram, "uTexel"), 1f / width, 1f / height);
        GL20.glUniform2f(loc(fxProgram, "uUvScale"), 1f, 1f);
        GL20.glUniform2f(loc(fxProgram, "uUvMax"), 1f, 1f);
        GL20.glUniform1i(loc(fxProgram, "uK"), 1);
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

    /**
     * 新経路に積む前の順序づけ: 先に溜まっている CPU の層を重ね、層に描く範囲を記録する。
     * 描画順は「他の何かを描く直前に溜めた矩形を吐く」ことで保つ。
     */
    private void beforeRectTreeShape(Matrix2D pose, float x0, float y0, float x1, float y1) {
        if (rects.pending() == 0) {
            flushOverlay();
        } else if (cpu.dirtyBounds() != null) {
            flushOverlay();
        }
        if (!layerStack.isEmpty()) { // 層に描く範囲（四隅を姿勢で写した外接矩形）を記録する
            double ax = pose.applyX(x0, y0), ay = pose.applyY(x0, y0), bx = pose.applyX(x1, y0), by = pose.applyY(x1, y0);
            double cx = pose.applyX(x0, y1), cy = pose.applyY(x0, y1), dx = pose.applyX(x1, y1), dy = pose.applyY(x1, y1);
            touch((float) Math.min(Math.min(ax, bx), Math.min(cx, dx)), (float) Math.min(Math.min(ay, by), Math.min(cy, dy)),
                    (float) Math.max(Math.max(ax, bx), Math.max(cx, dx)), (float) Math.max(Math.max(ay, by), Math.max(cy, dy)));
        }
    }

    /** 従来経路の {@code beginShape} と同じ余白（局所の単位）。 */
    private static float shapeMargin(Matrix2D pose) {
        return 1f / Math.max((float) pose.meanScale(), 0.01f);
    }

    @Override
    public boolean fillRoundRect(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                 int argb, GpuClip[] clips, float opacity) {
        if (rects != null && (GlRectStream.opMask & 1) != 0 && rects.accepts(pose, x0, y0, x1, y1, rx, ry, clips, opacity)) {
            float mg = shapeMargin(pose);
            beforeRectTreeShape(pose, x0 - mg, y0 - mg, x1 + mg, y1 + mg);
            rects.push(pose, x0, y0, x1, y1, rx, ry, argb, clips, opacity);
            return true;
        }
        return false; // 新経路が受けない図形は CPU が描く
    }

    @Override
    public boolean strokeRoundBorder(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                     float ix0, float iy0, float ix1, float iy1, float[] irx, float[] iry, boolean innerEmpty,
                                     int[] colors, float[] widths, GpuClip[] clips, float opacity) {
        if (rects != null && (GlRectStream.opMask & 2) != 0 && rects.accepts(pose, x0, y0, x1, y1, rx, ry, clips, opacity)) {
            float mg = shapeMargin(pose);
            beforeRectTreeShape(pose, x0 - mg, y0 - mg, x1 + mg, y1 + mg);
            rects.pushStroke(pose, x0, y0, x1, y1, rx, ry, ix0, iy0, ix1, iy1, irx, iry, innerEmpty, colors, widths, clips, opacity);
            return true;
        }
        return false; // 新経路が受けない図形は CPU が描く
    }

    @Override
    public boolean strokeDashedBorder(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                      float ix0, float iy0, float ix1, float iy1, float[] irx, float[] iry, boolean innerEmpty,
                                      int[] colors, float[] widths,
                                      float bx, float by, float cxm, float cym, float[] pcx, float[] pcy,
                                      float period, float dash, boolean round, float thickness,
                                      GpuClip[] clips, float opacity) {
        if (rects != null && (GlRectStream.opMask & 1024) != 0 && period > 0f
                && rects.acceptsWithExt(pose, x0, y0, x1, y1, rx, ry, clips, opacity)) {
            float mg = shapeMargin(pose);
            beforeRectTreeShape(pose, x0 - mg, y0 - mg, x1 + mg, y1 + mg);
            rects.pushStrokeDashed(pose, x0, y0, x1, y1, rx, ry, ix0, iy0, ix1, iy1, irx, iry, innerEmpty, colors, widths,
                    bx, by, cxm, cym, pcx, pcy, period, dash, round, thickness, clips, opacity);
            return true;
        }
        return false; // 新経路が受けない図形は CPU が描く
    }

    @Override
    public boolean strokeCircleBorder(Matrix2D pose, float cx, float cy, float outerR, float innerR,
                                      float bx0, float by0, float bx1, float by1, int[] colors, GpuClip[] clips, float opacity) {
        if (rects != null && (GlRectStream.opMask & 128) != 0 && rects.acceptsPosed(pose, bx0, by0, bx1, by1, clips, opacity)) {
            beforeRectTreeShape(pose, bx0, by0, bx1, by1);
            rects.pushCircleBorder(pose, cx, cy, outerR, innerR, bx0, by0, bx1, by1, colors, clips, opacity);
            return true;
        }
        return false; // 新経路が受けない図形は CPU が描く
    }

    @Override
    public boolean fillGradient(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                ShaderGradient g, GpuClip[] clips, float opacity) {
        if (rects != null && (GlRectStream.opMask & 16) != 0 && rects.acceptsWithExt(pose, x0, y0, x1, y1, rx, ry, clips, opacity)) {
            float mg = shapeMargin(pose);
            beforeRectTreeShape(pose, x0 - mg, y0 - mg, x1 + mg, y1 + mg);
            rects.pushGradient(pose, x0, y0, x1, y1, rx, ry, g, clips, opacity);
            return true;
        }
        return false; // 新経路が受けない図形は CPU が描く
    }

    @Override
    public boolean outerShadow(Matrix2D pose, float qx0, float qy0, float qx1, float qy1,
                               float sx0, float sy0, float sx1, float sy1, float[] srx, float[] sry, float sigma,
                               float[] hole, float[] hrx, float[] hry, int argb, GpuClip[] clips, float opacity) {
        if (rects != null && (GlRectStream.opMask & 4) != 0 && rects.acceptsOuterShadow(pose, qx0, qy0, qx1, qy1, clips, opacity)) {
            beforeRectTreeShape(pose, qx0, qy0, qx1, qy1);
            rects.pushOuterShadow(pose, qx0, qy0, qx1, qy1, sx0, sy0, sx1, sy1, srx, sry, sigma, hole, hrx, hry, argb, clips, opacity);
            return true;
        }
        return false; // 新経路が受けない図形は CPU が描く
    }

    @Override
    public boolean insetShadow(Matrix2D pose, float qx0, float qy0, float qx1, float qy1, float[] rx, float[] ry,
                               float sigma, float[] hole, float[] hrx, float[] hry, int argb, GpuClip[] clips, float opacity) {
        if (rects != null && (GlRectStream.opMask & 8) != 0 && rects.acceptsWithExt(pose, qx0, qy0, qx1, qy1, rx, ry, clips, opacity)) {
            beforeRectTreeShape(pose, qx0 - 1, qy0 - 1, qx1 + 1, qy1 + 1);
            rects.pushInsetShadow(pose, qx0, qy0, qx1, qy1, rx, ry, sigma, hole, hrx, hry, argb, clips, opacity);
            return true;
        }
        return false; // 新経路が受けない図形は CPU が描く
    }

    @Override
    public boolean drawGlyph(GlyphBitmap g, int bx, int by, int argb, GpuClip[] clips, float opacity) {
        if (rects == null || (GlRectStream.opMask & 32) == 0 || !atlas.fits(g)
                || !rects.acceptsGlyph(bx, by, g.width(), g.height(), clips, opacity)) return false;
        int[] slot = atlasSlot(g);
        beforeRectTreeShape(Matrix2D.IDENTITY, bx, by, bx + g.width(), by + g.height());
        rects.pushGlyph(bx, by, g.width(), g.height(), slot[0], slot[1], argb, clips, opacity);
        gpuGlyphs++;
        return true;
    }

    /** 字形をアトラスに載せて（まだなら転送して）左上の位置を返す。置き場所は {@link AtlasPacker} が決める。 */
    private int[] atlasSlot(GlyphBitmap g) {
        int[] slot = atlas.slot(g, this::flushAll);
        if (!atlas.lastPlacementWasNew()) return slot;
        if (atlas.lastPlacementGrew()) {
            growAtlasTexture();
            return slot; // 新しい字形も含めて全部載せ直した
        }
        uploadGlyph(g, slot);
        return slot;
    }

    /** アトラスが広がった: 同じテクスチャ名で大きく作り直し（中身は捨てられる）、置いてある全字形を載せ直す。 */
    /** 字形アトラスの現在の一辺（画素）。テスト用。 */
    int atlasSize() {
        return atlas.size();
    }

    private void growAtlasTexture() {
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlasTex);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, atlas.size(), atlas.size(), 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        atlas.forEach(this::uploadGlyph);
    }

    private void uploadGlyph(GlyphBitmap g, int[] slot) {
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
    }

    @Override
    public boolean drawGlyphStroke(GlyphBitmap sdf, int bx, int by, float half, int argb, GpuClip[] clips, float opacity) {
        if (rects == null || (GlRectStream.opMask & 512) == 0 || !atlas.fits(sdf)
                || !rects.acceptsPosed(Matrix2D.IDENTITY, bx, by, bx + sdf.width(), by + sdf.height(), clips, opacity)) return false;
        int[] slot = atlasSlot(sdf);
        beforeRectTreeShape(Matrix2D.IDENTITY, bx, by, bx + sdf.width(), by + sdf.height());
        rects.pushGlyphStroke(bx, by, sdf.width(), sdf.height(), slot[0], slot[1], half, argb, clips, opacity);
        gpuGlyphs++;
        return true;
    }

    @Override
    public boolean drawGlyphTransformed(Matrix2D pose, GlyphBitmap g, float ox, float oy, float s,
                                        float bx0, float by0, float bx1, float by1, int argb, GpuClip[] clips, float opacity) {
        if (rects == null || (GlRectStream.opMask & 256) == 0 || !atlas.fits(g)
                || !rects.acceptsPosed(pose, bx0, by0, bx1, by1, clips, opacity)) return false;
        int[] slot = atlasSlot(g);
        beforeRectTreeShape(pose, bx0, by0, bx1, by1);
        rects.pushGlyphTransformed(pose, bx0, by0, bx1, by1, slot[0], slot[1], g.width(), g.height(), ox, oy, s, g.left(), g.top(),
                argb, clips, opacity);
        gpuGlyphs++;
        return true;
    }

    @Override
    public boolean drawImage(Matrix2D pose, Pixmap image, float bx0, float by0, float bx1, float by1,
                             float px0, float py0, float sx, float sy,
                             float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                             int taps, float stepX, float stepY, GpuClip[] clips, float opacity) {
        if (image.width() > 8192 || image.height() > 8192) return false;
        if (!acceptsRectImage(pose, bx0, by0, bx1, by1, rx, ry, clips, opacity, 64)) return false;
        flushOverlay();
        int tex = imageTexture(image);
        pushRectImage(pose, tex, image.width(), image.height(), bx0, by0, bx1, by1, px0, py0, sx, sy, x0, y0, x1, y1, rx, ry,
                taps, stepX, stepY, clips, opacity);
        return true;
    }

    // ------------------------------------------------------------------ 保持するスクロール層

    /** 保持したスクロール層。鍵 = スクロール節の項目 id。 */
    private static final class Retained {
        GpuLayer layer;
        Object token;
        Matrix2D pose;
    }

    private static final int MAX_RETAINED = 3;
    private final java.util.LinkedHashMap<String, Retained> retained = new java.util.LinkedHashMap<>(8, 0.75f, true);

    /**
     * スクロールコンテナの中身を GPU の層に保持し、スクロールでは層の貼り付け位置だけを動かすか
     * （既定は有効。{@code -Denderframe.gl.retainscroll=false} で無効）。節の移動量は整数画素の差だけずらすので、
     * 小数のスクロール量では保持した層と描き直しの文字の位置が最大 0.5 画素ずれうる（再記録のたびに合う）。
     */
    public static volatile boolean retainedScroll = !"false".equals(System.getProperty("enderframe.gl.retainscroll"));

    /** 保持した層を使っているスクロール層の数（診断用）。 */
    public int retainedLayerCount() {
        return retained.size();
    }

    /** 保持する前に見たスクロール節の姿勢と中身の印（動いたかの判定用）。 */
    private final java.util.HashMap<String, Matrix2D> seenScrollPose = new java.util.HashMap<>();
    private final java.util.HashMap<String, Object> seenScrollToken = new java.util.HashMap<>();
    private long scrollLayersSkipped, scrollLayersRecorded;

    /** 保持した層を重ねて項目の走査を省いた回数と、層を記録した回数（診断用）。 */
    public long scrollLayersSkipped() { return scrollLayersSkipped; }
    public long scrollLayersRecorded() { return scrollLayersRecorded; }

    private void releaseRetained() {
        for (Retained r : retained.values()) deleteLayer(r.layer);
        retained.clear();
        seenScrollPose.clear();
        seenScrollToken.clear();
    }

    private static void deleteLayer(GpuLayer l) {
        if (l == null) return;
        GL30.glDeleteFramebuffers(l.fbo());
        GL11.glDeleteTextures(l.tex());
    }

    @Override
    public com.nishiyu.enderframelib.core.paint.RetainPlan planScrollLayer(GpuScrollLayer l) {
        var NONE = com.nishiyu.enderframelib.core.paint.RetainPlan.NONE;
        if (!retainedScroll || rects == null || closed || !layerStack.isEmpty()) return NONE;
        Retained r = retained.get(l.key());
        if (r == null) {
            // スクロールが実際に起きるまでは保持しない（静止した文書の見た目を変えない）: 節の位置が前フレームから動いたときに記録を始める
            Matrix2D was = seenScrollPose.get(l.key());
            Object wasToken = seenScrollToken.get(l.key());
            seenScrollPose.put(l.key(), l.pose());
            seenScrollToken.put(l.key(), l.token());
            boolean moved = was != null && wasToken == l.token() && (was.e() != l.pose().e() || was.f() != l.pose().f());
            if (!moved) return NONE;
        }
        if (r != null && r.token == l.token() && samePose(r.pose, l.pose())) {
            int dx = (int) Math.round(l.pose().e() - r.pose.e()), dy = (int) Math.round(l.pose().f() - r.pose.f());
            int[] b = l.bounds();
            // 重ねる範囲を、保持した層の同じ範囲から取れるか（層は画面と同じ範囲。画面の外へ出る分は層に無い）
            if (b[0] - dx >= 0 && b[1] - dy >= 0 && b[2] - dx <= width && b[3] - dy <= height) return com.nishiyu.enderframelib.core.paint.RetainPlan.SKIP;
        }
        if (r == null) {
            while (retained.size() >= MAX_RETAINED) { // 最も使っていない層を捨てる
                String oldest = retained.keySet().iterator().next();
                deleteLayer(retained.remove(oldest).layer);
            }
            enter();
            GpuLayer layer;
            try {
                layer = createLayer(false);
            } finally {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, currentFbo()); // 作った層が結ばれたままにしない
                exit();
            }
            if (layer == null) return NONE;
            r = new Retained();
            r.layer = layer;
            retained.put(l.key(), r);
        }
        r.token = null; // 記録が終わるまで使わせない
        return com.nishiyu.enderframelib.core.paint.RetainPlan.RECORD;
    }

    private static boolean samePose(Matrix2D a, Matrix2D b) {
        return a != null && a.a() == b.a() && a.b() == b.b() && a.c() == b.c() && a.d() == b.d();
    }

    @Override
    public void beginScrollLayer(GpuScrollLayer l) {
        Retained r = retained.get(l.key());
        if (r == null) return;
        flushAll(); // 親の描画先に溜めた分を先に描き切る（描画順）
        Entry e = new Entry();
        e.layer = r.layer;
        e.opacity = 1f;
        e.retainKey = l.key();
        layerStack.add(e);
    }

    @Override
    public boolean endScrollLayer(GpuScrollLayer l) {
        if (layerStack.isEmpty() || !l.key().equals(layerStack.get(layerStack.size() - 1).retainKey)) return false;
        flushAll();
        Entry e = layerStack.remove(layerStack.size() - 1);
        ensureCleared(e); // 何も描かなかった層も、前の内容を残さない
        Retained r = retained.get(l.key());
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, currentFbo());
        GL11.glViewport(0, 0, width, height);
        if (r == null) return false;
        r.token = l.token();
        r.pose = l.pose();
        scrollLayersRecorded++;
        compositeRetained(r, 0, 0, l);
        return true;
    }

    @Override
    public void drawScrollLayer(GpuScrollLayer l) {
        Retained r = retained.get(l.key());
        if (r == null || r.token == null) return;
        scrollLayersSkipped++;
        compositeRetained(r, (int) Math.round(l.pose().e() - r.pose.e()), (int) Math.round(l.pose().f() - r.pose.f()), l);
    }

    /**
     * 保持した層を {@code (dx, dy)} だけずらして、コンテナの切り取りで切って重ねる。層は画面と同じ範囲の FBO なので、
     * 外部テクスチャとして同じ画像の経路（クリップ・描画順の処理を含む）で描く。FBO の行は下から数えるので上下を反転して引く。
     */
    private void compositeRetained(Retained r, int dx, int dy, GpuScrollLayer l) {
        int[] b = l.bounds();
        float x0 = Math.max(0, b[0]), y0 = Math.max(0, b[1]), x1 = Math.min(width, b[2]), y1 = Math.min(height, b[3]);
        if (x1 <= x0 || y1 <= y0) return;
        float[] zero = new float[4];
        drawTexture(Matrix2D.IDENTITY, r.layer.tex(), width, height, x0, y0, x1, y1,
                dx, dy + height, 1f, -1f, x0, y0, x1, y1, zero, zero, l.clip(), 1f);
    }

    /**
     * 外部テクスチャ（{@code handle} = このコンテキストで有効な {@code GL_TEXTURE_2D} の名前）をそのまま貼る。
     * 画素は触らず、補間・折り返しもホストの設定のまま。すでに溜めた図形とは描画順を保つため、別のテクスチャに切り替わるとき先に描き切る。
     */
    @Override
    public boolean drawTexture(Matrix2D pose, long handle, int imgW, int imgH, float bx0, float by0, float bx1, float by1,
                               float px0, float py0, float sx, float sy,
                               float x0, float y0, float x1, float y1, float[] rx, float[] ry, GpuClip[] clips, float opacity) {
        if (handle <= 0 || handle > Integer.MAX_VALUE || imgW <= 0 || imgH <= 0 || imgW > 16384 || imgH > 16384) return false;
        int tex = (int) handle;
        if (!acceptsRectImage(pose, bx0, by0, bx1, by1, rx, ry, clips, opacity, 2048)) return false;
        flushOverlay();
        pushRectImage(pose, tex, imgW, imgH, bx0, by0, bx1, by1, px0, py0, sx, sy, x0, y0, x1, y1, rx, ry, 1, 0f, 0f, clips, opacity);
        return true;
    }

    /** 画像・外部テクスチャを新経路で描けるか（積みはしない）。 */
    private boolean acceptsRectImage(Matrix2D pose, float bx0, float by0, float bx1, float by1, float[] rx, float[] ry,
                                     GpuClip[] clips, float opacity, int maskBit) {
        if (rects == null || (GlRectStream.opMask & maskBit) == 0) return false;
        float m = 1f / Math.max((float) pose.meanScale(), 0.01f); // 回転の下では斜めの画素が箱の外へはみ出す分の余白
        return rects.acceptsImage(pose, bx0 - m, by0 - m, bx1 + m, by1 + m, rx, ry, clips, opacity);
    }

    /**
     * 画像・外部テクスチャを新経路に積む（{@link #acceptsRectImage} が true のとき）。
     * 画像は 1 回の描画で 1 枚のテクスチャしか引けないので、別のテクスチャへ替わる前に溜めた分を吐き、テクスチャはユニット 2 に結ぶ。
     */
    private void pushRectImage(Matrix2D pose, int tex, int imgW, int imgH, float bx0, float by0, float bx1, float by1,
                               float px0, float py0, float sx, float sy, float x0, float y0, float x1, float y1,
                               float[] rx, float[] ry, int taps, float stepX, float stepY, GpuClip[] clips, float opacity) {
        float m = 1f / Math.max((float) pose.meanScale(), 0.01f);
        if (rects.pending() > 0 && rectRunImg != 0 && rectRunImg != tex) drainRects();
        beforeRectTreeShape(pose, bx0 - m, by0 - m, bx1 + m, by1 + m);
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        rectRunImg = tex;
        rects.pushImage(pose, bx0 - m, by0 - m, bx1 + m, by1 + m, px0, py0, sx, sy, x0, y0, x1, y1, rx, ry, imgW, imgH,
                taps, stepX, stepY, clips, opacity);
        gpuImages++;
    }

    /** 画像（事前乗算）のテクスチャ。同じ {@link Pixmap} は使い回す（画像の中身は変わらない前提）。 */
    private int imageTexture(Pixmap image) {
        ImageTextureCache.Entry e = imageTex.get(image);
        if (e != null) {
            if (e.version != image.version()) {
                // 同じ Pixmap の中身が書き換わった（動画のフレームなど）。すでに溜めた図形は古い画素で描く
                flushAll();
                GL13.glActiveTexture(GL13.GL_TEXTURE2);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, e.texture);
                GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
                GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, image.width(), image.height(), GL12.GL_BGRA,
                        GL12.GL_UNSIGNED_INT_8_8_8_8_REV, image.pixels());
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                e.version = image.version();
            }
            return e.texture;
        }
        if (imageTex.isFull()) {
            // 最も使われていない 1 枚だけ捨てる。溜めた図形が束ねている画像なら先に描き切る
            ImageTextureCache.Entry old = imageTex.evictOldest();
            if (old.texture == rectRunImg) flushAll();
            GL11.glDeleteTextures(old.texture);
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
        imageTex.put(image, tex, image.version());
        return tex;
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
            releaseRetained();
            if (rects != null) {
                rects.close();
                rects = null;
            }
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
            for (int t : imageTex.clear()) GL11.glDeleteTextures(t);
            if (vao != 0) GL30.glDeleteVertexArrays(vao);
            if (gpuQueries != null) {
                for (int q : gpuQueries) GL15.glDeleteQueries(q);
                gpuQueries = null;
            }
            if (renderFence != 0) {
                GL32.glDeleteSync(renderFence);
                renderFence = 0;
            }
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
