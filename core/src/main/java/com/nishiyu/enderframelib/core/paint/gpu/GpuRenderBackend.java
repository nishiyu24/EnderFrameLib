package com.nishiyu.enderframelib.core.paint.gpu;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.LoadOp;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.PipelineDesc;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.TextureDesc;

import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuBindings;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuDevice;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuFrame;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuPass;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuPipeline;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuTexture;
import com.nishiyu.enderframelib.core.kernel.gpu.shader.ShaderPrograms;
import com.nishiyu.enderframelib.core.paint.BlendMode;
import com.nishiyu.enderframelib.core.paint.ClipShape;
import com.nishiyu.enderframelib.core.paint.FilterOp;
import com.nishiyu.enderframelib.core.paint.PaintSceneDriver;
import com.nishiyu.enderframelib.core.paint.RenderBackend;
import com.nishiyu.enderframelib.core.paint.RetainPlan;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.ShaderGradient;
import com.nishiyu.enderframelib.core.paint.software.ImageProvider;
import com.nishiyu.enderframelib.core.paint.software.PaintResources;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.scene3d.Scene3DWorld;
import com.nishiyu.enderframelib.core.text.font.GlyphBitmap;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongFunction;
import org.slf4j.LoggerFactory;

/**
 * RHI ({@link GpuDevice}) 上だけで動く API 非依存の GPU 描画バックエンド。
 * GL・GLFW・OS ウィンドウの型を一切持たず、コンテキスト制御と表示は {@link GpuSurfaceHost}、
 * CPU 保険層は {@link CpuFallback} を通じて行う。
 */
public final class GpuRenderBackend implements RenderBackend, GpuSink {
    private static final AtomicInteger SESSIONS = new AtomicInteger();
    private static final GpuBufferDummy EMPTY_RECORDS_HOLDER = new GpuBufferDummy();

    private static final class GpuBufferDummy {
        final com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuBuffer[] empty =
                new com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuBuffer[0];
    }

    /** 診断・切り分け用フラグ。 */
    public static volatile boolean rectTrees = !"false".equals(System.getProperty("enderframe.gl.recttrees"));
    public static volatile int maxLayerDepth = 32;
    public static volatile boolean boundedLayers = !Boolean.getBoolean("enderframe.gl.fullscreenlayers");
    public static volatile boolean downsampledBlur = !Boolean.getBoolean("enderframe.gl.fullresblur");
    public static volatile boolean retainedScroll = !"false".equals(System.getProperty("enderframe.gl.retainscroll"));

    private static final long LAYER_VRAM_BUDGET = 512L * 1024 * 1024;
    private static final int ATLAS_SIZE = 1024;
    private static final int ATLAS_MAX_SIZE = 4096;
    private static final int IMAGE_CACHE_LIMIT = 64;
    private static final int MAX_CLIP_VEC = 96;
    private static final int MAX_RETAINED = 3;
    private static final int[] OFFSCREEN = new int[0];

    private final GpuSurfaceHost host;
    private final GpuDevice device;
    private final CpuFallback.Factory cpuFactory;

    private int width;
    private int height;
    private final float dpr;
    private final String sessionId;
    private final PaintResources resources = new PaintResources();
    private CpuFallback cpu;
    private Scene3DRenderer scene3dRenderer;
    private RectStream rects;

    private GpuPipeline texBlendPipeline;
    private GpuPipeline texCopyPipeline;
    private GpuPipeline fxBlendPipeline;
    private GpuPipeline fxCopyPipeline;

    private GpuTexture mainTarget;
    private GpuTexture overlayTex;
    private GpuTexture atlasTex;
    private GpuTexture dummyTex;
    private GpuTexture rectRunImg;

    private final AtlasPacker atlas = new AtlasPacker(ATLAS_SIZE, ATLAS_MAX_SIZE);
    private final ImageTextureCache<Pixmap> imageTex = new ImageTextureCache<>(IMAGE_CACHE_LIMIT);
    private final Map<Long, GpuTexture> externalTexCache = new HashMap<>();

    private final GpuTexture[] texSampler = new GpuTexture[1];
    private final GpuTexture[] fxSamplers = new GpuTexture[3];
    private final ByteBuffer texUniforms = ByteBuffer.allocateDirect(ShaderPrograms.TEX.uniformBytes())
            .order(ByteOrder.nativeOrder());
    private final ByteBuffer fxUniforms = ByteBuffer.allocateDirect(ShaderPrograms.FX.uniformBytes())
            .order(ByteOrder.nativeOrder());
    private ByteBuffer scratchRgbaBuf;
    private ByteBuffer scratchGlyphBuf;

    private Pixmap readback;
    private Pixmap snapshotPm;
    private int clearPremul = 0;
    private boolean readbackValid;
    private boolean closed;

    private GpuFrame activeFrame;
    private GpuPass activePass;
    private boolean activeMainCleared;

    // ---- 損傷範囲（B10）: 差分パッチで書き換えた項目の旧・新の範囲だけを、シザー付きのクリアで描き直す
    /** 損傷範囲による部分描画を使うか（{@code -Denderframe.gpu.nodamage=true} で無効）。 */
    static volatile boolean damageEnabled = !Boolean.getBoolean("enderframe.gpu.nodamage");

    /** テスト・診断用: 損傷範囲による部分描画を切り替える。 */
    public static void setDamageEnabled(boolean on) {
        damageEnabled = on;
    }

    public long partialFrameCount() { return partialFrames; }

    public long fullFrameCount() { return fullFrames; }
    /** スパンごとの、積んだ図形のデバイス画素の外接矩形（x0, y0, x1, y1。x1 = {@link Integer#MIN_VALUE} なら未知）。 */
    private int[] spanBounds = new int[0];
    private final int[] spanAcc = new int[4];
    private boolean spanAccOn;
    /** このフレームの差分パッチが書き換えた範囲の和。{@link #patchDamageValid} が偽なら不明（全面）。 */
    private final int[] patchDamage = new int[4];
    private boolean patchDamageValid;
    /** 差分パッチが成功して終わったフレームの損傷範囲（{@code null} = 部分描画にできない）。 */
    private int[] frameDamage;
    /** 次に開く主パスのシザー（{@code null} = 全面）。 */
    private int[] mainScissor;
    private long partialFrames, fullFrames;

    private long gpuGlyphs, gpuImages, overlayFlushes, readbacks;
    private long spanGlyph0;
    private long scrollLayersSkipped, scrollLayersRecorded;
    private long seenOverflows;
    private boolean retainOverflow;

    // 差分インプレース上書きの前提状態
    private boolean prevFrameCleanSinglePass;
    private boolean inDiffPatch;
    private int prevPatchWidth, prevPatchHeight, prevPatchClearPremul, prevPatchAtlasResets;
    private long prevPatchScrollOps, frameLayerOps;
    private boolean frameHadImg;

    // 層の管理
    private final ArrayDeque<GpuTexture> layerPool = new ArrayDeque<>();
    private final ArrayList<Entry> layerStack = new ArrayList<>();
    private final ArrayList<GpuTexture> allLayers = new ArrayList<>();
    private final LinkedHashMap<String, Retained> retained = new LinkedHashMap<>(8, 0.75f, true);
    private final HashMap<String, Matrix2D> seenScrollPose = new HashMap<>();
    private final HashMap<String, Object> seenScrollToken = new HashMap<>();

    // FX uniforms 作業状態（29 個の順を ShaderPrograms.FX と一致させる）
    private float fxDevX0, fxDevY0, fxDevX1, fxDevY1;
    private float fxViewW, fxViewH;
    private int fxMode;
    private float fxOpacity;
    private int fxHasMatrix;
    private final float[] fxMat = new float[12];
    private float fxDirX, fxDirY, fxSigma, fxStep, fxTexelX, fxTexelY;
    private int fxTaps, fxK;
    private float fxUvScaleX, fxUvScaleY, fxUvMaxX, fxUvMaxY;
    private float fxShadowOffX, fxShadowOffY, fxShadowR, fxShadowG, fxShadowB, fxShadowA;
    private int fxMaskMode, fxBlend;
    private float fxInvA, fxInvB, fxInvC, fxInvD, fxInvE, fxInvF, fxShapeScale;
    private int fxClipKind, fxEvenOdd, fxClipN, fxHasB;
    private final float[] fxBD = new float[12];
    private final float[] fxClipD = new float[MAX_CLIP_VEC * 4];

    private static final double[] IDENTITY_MAP = {1, 0, 0, 1};

    private static final class Entry {
        GpuTexture layer;
        float opacity;
        List<FilterOp> filters = List.of();
        /** 読むだけ（書き換えない）。既定は共有の単位行列。 */
        double[] map = IDENTITY_MAP;
        boolean masked, luminance, isMask;
        GpuTexture mask;
        BlendMode blend = BlendMode.NORMAL;
        GroupExtras extras;
        final float[] bounds = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        boolean unbounded;
        boolean cleared;
        String retainKey;

        void reset() {
            layer = null;
            opacity = 0f;
            filters = List.of();
            map = IDENTITY_MAP;
            masked = luminance = isMask = false;
            mask = null;
            blend = BlendMode.NORMAL;
            extras = null;
            bounds[0] = bounds[1] = Float.MAX_VALUE;
            bounds[2] = bounds[3] = -Float.MAX_VALUE;
            unbounded = cleared = false;
            retainKey = null;
        }
    }

    /**
     * 層の記録（{@link Entry}）の使い回し（B12）。不透明度が動くアニメーションは毎フレーム層を開くので、層ごとの記録と既定の配列を
     * 作らない。開くと {@link #obtainEntry}、閉じて使い終えたら {@link #releaseEntry}。
     */
    private final java.util.ArrayDeque<Entry> entryPool = new java.util.ArrayDeque<>();
    /** {@link #endGroup} の作業用（途中の層）。入れ子にならない（閉じるのは 1 つずつ）。 */
    private final List<GpuTexture> groupTemps = new ArrayList<>();

    private Entry obtainEntry() {
        Entry e = entryPool.pollFirst();
        return e != null ? e : new Entry();
    }

    private void releaseEntry(Entry e) {
        e.reset();
        if (entryPool.size() < 64) entryPool.addFirst(e);
    }

    private static final class Retained {
        GpuTexture layer;
        Object token;
        Matrix2D pose;
    }

    private enum StageKind { COLOR, BLUR, DROP }

    private static final class Stage {
        StageKind kind;
        float[] matrix;
        float opacity = 1f;
        float sigma, dx, dy;
        int color;
    }

    public GpuRenderBackend(GpuSurfaceHost host, CpuFallback.Factory cpuFactory,
                            int cssW, int cssH, float devicePixelRatio) {
        if (!(devicePixelRatio > 0f)) throw new IllegalArgumentException("devicePixelRatio must be > 0");
        this.host = host;
        this.device = host.device();
        this.cpuFactory = cpuFactory;
        this.dpr = devicePixelRatio;
        this.width = Math.max(1, Math.round(cssW * devicePixelRatio));
        this.height = Math.max(1, Math.round(cssH * devicePixelRatio));
        this.sessionId = "gl-" + SESSIONS.incrementAndGet();
        this.cpu = cpuFactory.create(cssW, cssH, devicePixelRatio, sessionId, resources);
        this.readback = new Pixmap(width, height);
        host.enter();
        try {
            initResources();
        } finally {
            host.exit();
        }
    }

    private void initResources() {
        this.mainTarget = device.createTexture(TextureDesc.rgba8(width, height, true));
        int maxTex = Math.min(ATLAS_MAX_SIZE, device.caps().maxTextureSize());
        atlas.setMaxSize(maxTex);
        this.atlasTex = device.createTexture(TextureDesc.r8(ATLAS_SIZE, ATLAS_SIZE));
        this.dummyTex = device.createTexture(TextureDesc.rgba8(1, 1, false));
        GpuFrame initFrame = device.beginFrame();
        ByteBuffer zero = ByteBuffer.allocateDirect(4);
        initFrame.upload(dummyTex, 0, 0, 1, 1, zero);
        initFrame.end();

        this.texBlendPipeline = device.createPipeline(new PipelineDesc("tex", true, false));
        this.texCopyPipeline = device.createPipeline(new PipelineDesc("tex", false, false));
        this.fxBlendPipeline = device.createPipeline(new PipelineDesc("fx", true, false));
        this.fxCopyPipeline = device.createPipeline(new PipelineDesc("fx", false, false));

        if (rectTrees) {
            try {
                this.rects = new RectStream(device, Integer.getInteger("enderframe.gl.rectcapacity", 16384));
                LoggerFactory.getLogger(GpuRenderBackend.class).info(
                        "GPU プリミティブ経路を使う（永続マップ={}, 容量={}）", rects.isPersistent(), rects.capacity());
            } catch (RuntimeException | LinkageError e) {
                this.rects = null;
            }
        }
    }

    // ------------------------------------------------------------------ 公開・構成メソッド

    public GpuTexture colorTarget() { return mainTarget; }
    public long colorTexture() { return device.nativeHandle(mainTarget); }
    public int textureWidth() { return width; }
    public int textureHeight() { return height; }

    public GpuRenderBackend fonts(FontResolver resolver) {
        if (!retained.isEmpty()) {
            host.enter();
            try {
                releaseRetained();
            } finally {
                host.exit();
            }
        }
        resources.fonts(resolver);
        return this;
    }

    public GpuRenderBackend images(ImageProvider provider) {
        resources.images(provider);
        return this;
    }

    public GpuRenderBackend scene3dWorlds(LongFunction<Scene3DWorld> provider) {
        resources.scene3dWorlds(provider);
        return this;
    }

    public GpuRenderBackend clearColor(int argb) {
        this.clearPremul = Pixmap.premultiply(argb);
        return this;
    }

    public Scene3DRenderer scene3dRenderer() { return scene3dRenderer; }
    @Override public String sessionId() { return sessionId; }
    @Override public float devicePixelRatio() { return dpr; }
    public boolean hasActiveAnimation(List<DisplayItem> items, long nowMs) { return PaintSceneDriver.anyActiveAt(sessionId, items, nowMs); }
    public long gpuShapeCount() { return rects != null ? rects.accepted() - gpuGlyphs - gpuImages : 0; }
    public long rectTreeDrawCalls() { return rects != null ? rects.drawCalls() : 0; }
    public long rectTreeChangedWrites() { return rects != null ? rects.changedWrites() : 0; }
    public long rectTreeReplayedItems() { return rects != null ? rects.replayedItems : 0; }
    public long rectTreeReplayedShapes() { return rects != null ? rects.replayedShapes : 0; }
    public long rectTreeShapes() { return rects != null ? rects.accepted() : 0; }

    public long[] rectTreeRejects() {
        long[] r = new long[5];
        if (rects != null) {
            System.arraycopy(rects.rejects, 0, r, 0, 4);
            r[4] = rects.clipRejects;
        }
        return r;
    }

    public long gpuGlyphCount() { return gpuGlyphs; }
    public long gpuImageCount() { return gpuImages; }
    public long drawCallCount() { return rects != null ? rects.drawCalls() : 0; }
    public long cpuOverlayFlushCount() { return overlayFlushes; }
    public long readbackCount() { return readbacks; }
    public int retainedLayerCount() { return retained.size(); }
    public long scrollLayersSkipped() { return scrollLayersSkipped; }
    public long scrollLayersRecorded() { return scrollLayersRecorded; }
    public int atlasSize() { return atlas.size(); }
    public boolean cpuPixelsAllocated() { return cpu.isAllocated(); }
    public boolean overlayTextureAllocated() { return overlayTex != null; }

    public void resize(int cssW, int cssH) {
        if (closed || cssW <= 0 || cssH <= 0) return;
        int nextW = Math.max(1, Math.round(cssW * dpr));
        int nextH = Math.max(1, Math.round(cssH * dpr));
        if (nextW == width && nextH == height) return;
        host.enter();
        try {
            this.width = nextW;
            this.height = nextH;
            this.cpu = cpuFactory.create(cssW, cssH, dpr, sessionId, resources);
            this.readback = new Pixmap(width, height);
            this.snapshotPm = null;
            releaseRetained();
            retainOverflow = false;
            for (GpuTexture l : allLayers) l.close();
            allLayers.clear();
            layerPool.clear();
            if (mainTarget != null) mainTarget.close();
            mainTarget = device.createTexture(TextureDesc.rgba8(width, height, true));
            if (overlayTex != null) {
                overlayTex.close();
                overlayTex = device.createTexture(TextureDesc.rgba8(width, height, false));
            }
        } finally {
            host.exit();
        }
    }

    // ------------------------------------------------------------------ フレーム実行

    @Override
    public void render(List<DisplayItem> items, long nowMs) {
        if (closed) throw new IllegalStateException("closed");
        host.enter();
        try {
            host.beginTiming();
            try {
                renderLocked(items, nowMs);
            } finally {
                host.endTiming();
            }
        } finally {
            host.exit();
        }
    }

    private void renderLocked(List<DisplayItem> items, long nowMs) {
        readbackValid = false;
        cpu.clearDirty();
        for (Entry left : layerStack) releaseEntry(left);
        layerStack.clear();
        layerPool.clear();
        layerPool.addAll(allLayers);
        rectRunImg = null;
        inDiffPatch = false;
        frameLayerOps = 0;
        frameHadImg = false;
        long overlay0 = overlayFlushes, readbacks0 = readbacks, scrollOps0 = scrollLayersSkipped + scrollLayersRecorded;
        if (rects != null) rects.begin();

        activeMainCleared = false;
        frameDamage = null;
        mainScissor = null;
        activeFrame = device.beginFrame();
        boolean ended = false;
        // 前のフレームが主ターゲットへ全部描いて終わっていて、差分パッチの前提（beginDiffPatch と同じ）が揃うなら、
        // 主パスを開くのを遅らせる。パッチが成功すれば範囲だけをクリアして描き直し、失敗したら従来どおり全面
        boolean mayPartial = damageEnabled && rects != null && prevFrameCleanSinglePass && width == prevPatchWidth && height == prevPatchHeight
                && clearPremul == prevPatchClearPremul && atlas.resets() == prevPatchAtlasResets && retained.isEmpty();
        try {
            if (!mayPartial) {
                // 根のターゲットはクリアしておく（何も描かれないフレームでも確定させる）
                activePass = activeFrame.beginPass(mainTarget, LoadOp.CLEAR, clearPremul, null);
                activeMainCleared = true;
            }

            cpu.renderOnto(items, nowMs, this);
            while (!layerStack.isEmpty()) endGroup();
            if (mayPartial && activePass == null && !activeMainCleared) {
                int[] d = layerStack.isEmpty() && frameLayerOps == 0 ? frameDamage : null;
                if (d != null) {
                    // 何も変わらなかったフレームも、1 画素だけ描き直す（パスと描画の流れを変えずに済ませる）
                    mainScissor = d[2] > d[0] && d[3] > d[1] ? damageScissor(d) : new int[]{0, 0, 1, 1};
                    partialFrames++;
                } else {
                    fullFrames++;
                }
                ensurePass();
                mainScissor = null;
            }
            flushAll();
            endPass();
            activeFrame.end();
            ended = true;
        } finally {
            if (activePass != null) {
                activePass.end();
                activePass = null;
            }
            if (!ended && activeFrame != null) {
                activeFrame.end();
            }
            activeFrame = null;
        }

        prevFrameCleanSinglePass = ended
                && frameLayerOps == 0
                && retained.isEmpty()
                && !frameHadImg
                && overlayFlushes == overlay0
                && readbacks == readbacks0
                && scrollLayersSkipped + scrollLayersRecorded == scrollOps0
                && layerStack.isEmpty()
                && rects != null;
        if (prevFrameCleanSinglePass) {
            prevPatchWidth = width;
            prevPatchHeight = height;
            prevPatchClearPremul = clearPremul;
            prevPatchAtlasResets = atlas.resets();
        }
        inDiffPatch = false;

        if (rects != null && rects.overflows != seenOverflows) {
            seenOverflows = rects.overflows;
            if (retainedScroll && !retainOverflow) {
                retainOverflow = true;
                releaseRetained();
            }
        }
        host.publishFrame();
    }

    private GpuTexture currentTarget() {
        return layerStack.isEmpty() ? mainTarget : layerStack.get(layerStack.size() - 1).layer;
    }

    private void endPass() {
        if (activePass != null) {
            activePass.end();
            activePass = null;
        }
    }

    /** 現在の描画ターゲット（main または最上層）に対する通常パスを開く（必要なら初回クリア）。 */
    private GpuPass ensurePass() {
        if (activePass != null) return activePass;
        GpuTexture target = currentTarget();
        if (layerStack.isEmpty()) {
            LoadOp op = activeMainCleared ? LoadOp.LOAD : LoadOp.CLEAR;
            activeMainCleared = true;
            activePass = activeFrame.beginPass(target, op, clearPremul, op == LoadOp.CLEAR ? mainScissor : null);
        } else {
            Entry top = layerStack.get(layerStack.size() - 1);
            LoadOp op = top.cleared ? LoadOp.LOAD : LoadOp.CLEAR;
            top.cleared = true;
            activePass = activeFrame.beginPass(target, op, 0, null);
        }
        return activePass;
    }

    private void flushAll() {
        drainRects();
        flushOverlay();
    }

    private void drainRects() {
        if (rects == null || rects.pending() == 0) return;
        if (rectRunImg != null) frameHadImg = true;
        GpuPass pass = ensurePass();
        GpuTexture img = rectRunImg != null ? rectRunImg : dummyTex;
        rects.drain(pass, atlasTex, img, width, height);
        rectRunImg = null;
    }

    private void flushOverlay() {
        int[] b = cpu.dirtyBounds();
        if (b == null) return;
        overlayFlushes++;
        drainRects();
        drawPixels(cpu.pixels(), b, false);
        cpu.clearDirty();
    }

    @Override
    public Pixmap snapshotTarget() {
        return snapshotTarget(new int[]{0, 0, width, height});
    }

    @Override
    public Pixmap snapshotTarget(int[] region) {
        readbacks++;
        int x0 = Math.max(0, region[0]), y0 = Math.max(0, region[1]);
        int x1 = Math.min(width, region[2]), y1 = Math.min(height, region[3]);
        if (snapshotPm == null) snapshotPm = new Pixmap(width, height);
        Pixmap pm = snapshotPm;
        int rw = x1 - x0, rh = y1 - y0;
        if (rw <= 0 || rh <= 0) return pm;
        ByteBuffer buf = ensureScratchRgba(rw * rh * 4);
        if (activeFrame != null) {
            flushAll();
            if (!layerStack.isEmpty()) ensureCleared(layerStack.get(layerStack.size() - 1));
            endPass();
            activeFrame.readback(currentTarget(), new int[]{x0, y0, rw, rh}, buf);
        } else {
            host.enter();
            try {
                GpuFrame f = device.beginFrame();
                try {
                    f.readback(currentTarget(), new int[]{x0, y0, rw, rh}, buf);
                } finally {
                    f.end();
                }
            } finally {
                host.exit();
            }
        }
        buf.flip();
        IntBuffer ib = buf.asIntBuffer();
        int[] dst = pm.pixels();
        for (int y = 0; y < rh; y++) {
            int rowOff = (y0 + y) * width + x0;
            for (int x = 0; x < rw; x++) {
                dst[rowOff + x] = Integer.rotateRight(ib.get(), 8);
            }
        }
        return pm;
    }

    @Override
    public void overwriteTarget(Pixmap pixmap, int[] region) {
        flushAll();
        drawPixels(pixmap.pixels(), region, true);
    }

    private void drawPixels(int[] src, int[] b, boolean overwrite) {
        touchBounds(b[0], b[1], b[2], b[3]);
        int bw = b[2] - b[0], bh = b[3] - b[1];
        endPass();
        if (overlayTex == null) {
            overlayTex = device.createTexture(TextureDesc.rgba8(width, height, false));
        }
        ByteBuffer buf = ensureScratchRgba(bw * bh * 4);
        IntBuffer ib = buf.asIntBuffer();
        for (int y = 0; y < bh; y++) {
            int rowOff = (b[1] + y) * width + b[0];
            for (int x = 0; x < bw; x++) {
                ib.put(Integer.rotateLeft(src[rowOff + x], 8));
            }
        }
        buf.limit(bw * bh * 4).position(0);
        activeFrame.upload(overlayTex, b[0], b[1], bw, bh, buf);

        GpuPass pass = ensurePass();
        texUniforms.clear();
        texUniforms.putFloat(b[0]).putFloat(b[1]).putFloat(b[2]).putFloat(b[3]);
        texUniforms.putFloat(width).putFloat(height);
        texUniforms.putFloat(1f);
        texUniforms.putInt(0); // RHI upload 済みテクスチャは描画先と同じ向き
        texUniforms.flip();
        texSampler[0] = overlayTex;
        pass.bind(overwrite ? texCopyPipeline : texBlendPipeline,
                new GpuBindings(EMPTY_RECORDS_HOLDER.empty, texSampler, texUniforms));
        pass.drawInstanced(4, 1, 0);
    }

    // ------------------------------------------------------------------ グループの層

    private void ensureCleared(Entry e) {
        if (e.cleared) return;
        e.cleared = true;
        endPass();
        GpuPass p = activeFrame.beginPass(e.layer, LoadOp.CLEAR, 0, null);
        p.end();
    }

    private void touchBounds(float x0, float y0, float x1, float y1) {
        if (layerStack.isEmpty()) return;
        Entry top = layerStack.get(layerStack.size() - 1);
        float[] b = top.bounds;
        if (x0 < b[0]) b[0] = x0;
        if (y0 < b[1]) b[1] = y0;
        if (x1 > b[2]) b[2] = x1;
        if (y1 > b[3]) b[3] = y1;
    }

    private void touchAndEnsureCleared(float x0, float y0, float x1, float y1) {
        if (layerStack.isEmpty()) return;
        Entry top = layerStack.get(layerStack.size() - 1);
        ensureCleared(top);
        float[] b = top.bounds;
        if (x0 < b[0]) b[0] = x0;
        if (y0 < b[1]) b[1] = y0;
        if (x1 > b[2]) b[2] = x1;
        if (y1 > b[3]) b[3] = y1;
    }

    private void touchUnbounded() {
        if (!layerStack.isEmpty()) layerStack.get(layerStack.size() - 1).unbounded = true;
    }

    private GpuTexture acquireLayer() {
        GpuTexture l = layerPool.pollFirst();
        if (l != null) return l;
        return createLayer(true);
    }

    private GpuTexture createLayer(boolean pooled) {
        GpuTexture l = device.createTexture(TextureDesc.rgba8Linear(width, height, true));
        if (pooled) allLayers.add(l);
        if (activeFrame != null) {
            endPass();
            GpuPass p = activeFrame.beginPass(l, LoadOp.CLEAR, 0, null);
            p.end();
        } else {
            GpuFrame f = device.beginFrame();
            GpuPass p = f.beginPass(l, LoadOp.CLEAR, 0, null);
            p.end();
            f.end();
        }
        return l;
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
        if (layerStack.size() >= maxLayerDepth - 1) return false;
        if (layerPool.isEmpty() && (allLayers.size() + 1L) * width * height * 4L > LAYER_VRAM_BUDGET) return false;
        flushAll();
        endPass();
        GpuTexture l = acquireLayer();
        if (l == null) return false;
        frameLayerOps++;
        Entry e = obtainEntry();
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
        endPass();
        GpuTexture l = acquireLayer();
        if (l == null) return false;
        frameLayerOps++;
        Entry e = obtainEntry();
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
        endPass();
        Entry m = layerStack.remove(layerStack.size() - 1);
        ensureCleared(m);
        layerStack.get(layerStack.size() - 1).mask = m.layer;
        releaseEntry(m);
    }

    private static List<Stage> stages(List<FilterOp> ops) {
        List<Stage> out = new ArrayList<>();
        for (FilterOp op : ops) {
            switch (op.kind()) {
                case COLOR_MATRIX, OPACITY -> {
                    Stage st = out.isEmpty() || out.get(out.size() - 1).kind != StageKind.COLOR ? null : out.get(out.size() - 1);
                    if (st == null) {
                        st = new Stage();
                        st.kind = StageKind.COLOR;
                        out.add(st);
                    }
                    if (op.matrix() == null) {
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
        endPass();
        Entry e = layerStack.remove(layerStack.size() - 1);
        try {
            endGroup(e);
        } finally {
            releaseEntry(e);
        }
    }

    private void endGroup(Entry e) {
        List<GpuTexture> temps = groupTemps;
        temps.clear();
        GpuTexture cur = e.layer;
        List<Stage> stages = e.filters.isEmpty() ? List.of() : stages(e.filters);
        float[] finalMatrix = null;
        float finalOpacity = e.opacity;
        if (!stages.isEmpty() && stages.get(stages.size() - 1).kind == StageKind.COLOR) {
            Stage last = stages.remove(stages.size() - 1);
            finalMatrix = last.matrix;
            finalOpacity *= last.opacity;
        }
        boolean bounded = boundedLayers && !e.unbounded && (e.extras == null || e.extras.clip() == null) && stages.size() <= 1;
        if (!bounded || e.mask != null) ensureCleared(e);
        int[] region = bounded ? clampToScreen(e.bounds) : null;
        boolean hasBackdrop = e.extras != null && e.extras.backdropShape() != null && !e.extras.backdrop().isEmpty();
        if (bounded && region == null && !hasBackdrop) {
            layerPool.addFirst(e.layer);
            if (e.mask != null) layerPool.addFirst(e.mask);
            return;
        }
        if (hasBackdrop) applyBackdropFilter(e, temps);
        if (bounded && region == null) {
            layerPool.addFirst(e.layer);
            if (e.mask != null) layerPool.addFirst(e.mask);
            for (GpuTexture t : temps) layerPool.addFirst(t);
            return;
        }
        for (Stage st : stages) {
            GpuTexture next = runStage(st, cur, e.map, temps, region);
            if (next == null) break;
            cur = next;
        }
        if (e.extras != null && e.extras.clip() != null) {
            GpuTexture out = acquireLayer();
            if (out != null) {
                temps.add(out);
                resetFx(5);
                setShapes(e.extras, false);
                drawFx(out, false, cur, null, null, null);
                cur = out;
            }
        }

        GpuTexture backdrop = e.blend != BlendMode.NORMAL ? copyBackdrop(region) : null;
        int[] scissor = toPassScissor(region);
        if (backdrop != null) {
            temps.add(backdrop);
            if (!layerStack.isEmpty()) ensureCleared(layerStack.get(layerStack.size() - 1));
            resetFx(4);
            fxOpacity = finalOpacity;
            fxMaskMode = e.mask == null ? 0 : (e.luminance ? 2 : 1);
            fxBlend = e.blend.ordinal();
            setMatrix(finalMatrix);
            drawFx(currentTarget(), false, cur, e.mask, backdrop, scissor);
            if (region != null) touchAndEnsureCleared(region[0], region[1], region[2], region[3]);
            else touchAndEnsureCleared(0, 0, width, height);
            layerPool.addFirst(e.layer);
            if (e.mask != null) layerPool.addFirst(e.mask);
            for (GpuTexture t : temps) layerPool.addFirst(t);
            return;
        }
        if (!layerStack.isEmpty()) ensureCleared(layerStack.get(layerStack.size() - 1));
        resetFx(1);
        fxOpacity = finalOpacity;
        fxMaskMode = e.mask == null ? 0 : (e.luminance ? 2 : 1);
        setMatrix(finalMatrix);
        drawFx(currentTarget(), true, cur, e.mask, null, scissor);
        if (region != null) touchAndEnsureCleared(region[0], region[1], region[2], region[3]);
        else if (e.unbounded) touchUnbounded();
        else touchAndEnsureCleared(0, 0, width, height);

        layerPool.addFirst(e.layer);
        if (e.mask != null) layerPool.addFirst(e.mask);
        for (GpuTexture t : temps) layerPool.addFirst(t);
    }

    private void applyBackdropFilter(Entry e, List<GpuTexture> temps) {
        List<Stage> bstages = stages(e.extras.backdrop());
        int[] shape = boundedLayers && bstages.size() == 1 ? backdropDeviceBounds(e) : null;
        if (shape == OFFSCREEN) return;
        int[] copyRegion = null;
        if (shape != null) {
            int margin = 0;
            Stage st = bstages.get(0);
            if (st.kind != StageKind.COLOR) {
                float sx = st.sigma * (float) Math.hypot(e.map[0], e.map[1]);
                float sy = st.sigma * (float) Math.hypot(e.map[2], e.map[3]);
                margin = (int) Math.ceil(3.5f * Math.max(sx, sy)) + 4;
            }
            copyRegion = clampInts(new int[]{shape[0] - margin, shape[1] - margin, shape[2] + margin, shape[3] + margin});
        }
        GpuTexture orig = copyBackdrop(copyRegion);
        if (orig == null) return;
        if (shape != null) touchAndEnsureCleared(shape[0], shape[1], shape[2], shape[3]);
        else touchUnbounded();
        temps.add(orig);
        GpuTexture filtered = orig;
        for (Stage st : bstages) {
            int[] out = shape == null ? null : shape.clone();
            GpuTexture next = st.kind == StageKind.BLUR ? blur(st.sigma, filtered, e.map, temps, out, false)
                    : runStage(st, filtered, e.map, temps, out);
            if (next == null) break;
            filtered = next;
        }
        resetFx(6);
        fxOpacity = e.opacity;
        setShapes(e.extras, true);
        drawFx(currentTarget(), false, filtered, null, orig, toPassScissor(shape));
    }

    private int[] backdropDeviceBounds(Entry e) {
        ClipShape shape = e.extras.backdropShape();
        float[] d = shape == null ? null : shape.data();
        if (d == null || d.length < 4) return null;
        Matrix2D pose = e.extras.pose();
        double[] xs = {d[0], d[2], d[0], d[2]}, ys = {d[1], d[1], d[3], d[3]};
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            double x = pose.applyX(xs[i], ys[i]), y = pose.applyY(xs[i], ys[i]);
            minX = Math.min(minX, x); minY = Math.min(minY, y);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
        }
        int[] r = clampToScreen(new float[]{(float) minX - 1, (float) minY - 1, (float) maxX + 1, (float) maxY + 1});
        return r == null ? OFFSCREEN : r;
    }

    private GpuTexture copyBackdrop(int[] region) {
        GpuTexture bd = acquireLayer();
        if (bd == null) return null;
        if (!layerStack.isEmpty()) ensureCleared(layerStack.get(layerStack.size() - 1));
        endPass();
        int[] copyRect = region == null ? new int[]{0, 0, width, height}
                : new int[]{region[0], region[1], region[2] - region[0], region[3] - region[1]};
        activeFrame.copy(currentTarget(), bd, copyRect);
        return bd;
    }

    private GpuTexture runStage(Stage st, GpuTexture in, double[] m, List<GpuTexture> temps, int[] region) {
        switch (st.kind) {
            case COLOR -> {
                GpuTexture out = acquireLayer();
                if (out == null) return null;
                temps.add(out);
                resetFx(1);
                fxOpacity = st.opacity;
                fxMaskMode = 0;
                setMatrix(st.matrix);
                drawFx(out, false, in, null, null, null);
                return out;
            }
            case BLUR -> {
                return blur(st.sigma, in, m, temps, region, true);
            }
            default -> {
                int[] inRegion = region == null ? null : region.clone();
                GpuTexture blurred = blur(st.sigma, in, m, temps, region, true);
                if (blurred == null) return null;
                GpuTexture out = acquireLayer();
                if (out == null) return null;
                temps.add(out);
                float ox = (float) (m[0] * st.dx + m[2] * st.dy);
                float oy = (float) (m[1] * st.dx + m[3] * st.dy);
                resetFx(3);
                fxShadowOffX = ox / width;
                fxShadowOffY = device.caps().bottomUpFramebuffer() ? -oy / height : oy / height;
                fxShadowR = ((st.color >>> 16) & 0xFF) / 255f;
                fxShadowG = ((st.color >>> 8) & 0xFF) / 255f;
                fxShadowB = (st.color & 0xFF) / 255f;
                fxShadowA = ((st.color >>> 24) & 0xFF) / 255f;
                int[] sc = null;
                if (region != null) {
                    int sx0 = Math.max(0, Math.min(region[0] + Math.round(ox), inRegion[0]));
                    int sy0 = Math.max(0, Math.min(region[1] + Math.round(oy), inRegion[1]));
                    int sx1 = Math.min(width, Math.max(region[2] + Math.round(ox), inRegion[2]));
                    int sy1 = Math.min(height, Math.max(region[3] + Math.round(oy), inRegion[3]));
                    region[0] = sx0; region[1] = sy0; region[2] = sx1; region[3] = sy1;
                    sc = toPassScissor(region);
                }
                drawFx(out, false, in, blurred, null, sc);
                return out;
            }
        }
    }

    private GpuTexture blur(float sigma, GpuTexture in, double[] m, List<GpuTexture> temps, int[] region, boolean expand) {
        float sx = sigma * (float) Math.hypot(m[0], m[1]);
        float sy = sigma * (float) Math.hypot(m[2], m[3]);
        if (sx < 0.05f && sy < 0.05f) return in;
        float[] step = new float[2];
        int[] reach = new int[2];
        for (int axis = 0; axis < 2; axis++) {
            float sg = axis == 0 ? sx : sy;
            if (sg < 0.05f) continue;
            step[axis] = Math.max(1f, sg / 12f);
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
        GpuTexture cur = in;
        for (int axis = 0; axis < 2; axis++) {
            float sg = axis == 0 ? sx : sy;
            if (sg < 0.05f) continue;
            GpuTexture out = acquireLayer();
            if (out == null) return null;
            temps.add(out);
            int taps = (int) Math.min(40, Math.ceil(3f * sg / step[axis]));
            resetFx(2);
            fxDirX = axis == 0 ? 1f : 0f;
            fxDirY = axis == 0 ? 0f : 1f;
            fxSigma = sg;
            fxTaps = taps;
            fxStep = step[axis];
            int[] sc = region == null ? null : toPassScissor(axis == 0 && sy >= 0.05f ? first : second);
            drawFx(out, false, cur, null, null, sc);
            cur = out;
        }
        if (region != null && expand) {
            region[0] = second[0]; region[1] = second[1]; region[2] = second[2]; region[3] = second[3];
        }
        return cur;
    }

    private GpuTexture blurDownsampled(float sx, float sy, int k, GpuTexture in, List<GpuTexture> temps, int[] region, boolean expand) {
        int sw = (width + k - 1) / k, sh = (height + k - 1) / k;
        float[] sg = {sx / k, sy / k};
        int[] reach = new int[2];
        float[] step = new float[2];
        int[] taps = new int[2];
        for (int axis = 0; axis < 2; axis++) {
            if (sg[axis] < 0.05f) continue;
            step[axis] = Math.max(1f, sg[axis] / 12f);
            taps[axis] = (int) Math.min(40, Math.ceil(3f * sg[axis] / step[axis]));
            reach[axis] = (int) Math.ceil(taps[axis] * step[axis]) + 2;
        }
        boolean flipY = device.caps().bottomUpFramebuffer();
        int[] g = flipY
                ? new int[]{region[0], height - region[3], region[2], height - region[1]}
                : new int[]{region[0], region[1], region[2], region[3]};
        int[] content = {g[0] / k, g[1] / k, (g[2] + k - 1) / k, (g[3] + k - 1) / k};
        int ex = expand ? reach[0] : 1, ey = expand ? reach[1] : 1;
        int[] r2 = clampTo(new int[]{content[0] - ex, content[1] - ey, content[2] + ex, content[3] + ey}, sw, sh);
        if (r2 == null) return in;
        int[] r1 = clampTo(new int[]{r2[0], r2[1] - reach[1], r2[2], r2[3] + reach[1]}, sw, sh);
        int[] dsr = r1 == null ? null : clampTo(new int[]{r1[0] - reach[0], r1[1], r1[2] + reach[0], r1[3]}, sw, sh);
        if (dsr == null) return in;

        GpuTexture a = acquireLayer(), b = a == null ? null : acquireLayer(), c = b == null ? null : acquireLayer();
        GpuTexture out = c == null ? null : acquireLayer();
        if (out == null) {
            for (GpuTexture l : new GpuTexture[]{a, b, c}) if (l != null) layerPool.addFirst(l);
            return null;
        }
        temps.add(a); temps.add(b); temps.add(c); temps.add(out);

        // API 固有のテクスチャ原点側の [0..sw, 0..sh] 領域へ縮小して描く
        float dsY0 = flipY ? height - sh : 0;
        float dsY1 = flipY ? height : sh;
        resetFx(7);
        fxDevX0 = 0; fxDevY0 = dsY0; fxDevX1 = sw; fxDevY1 = dsY1;
        fxViewW = width; fxViewH = height;
        fxK = k;
        fxUvScaleX = (float) sw / width; fxUvScaleY = (float) sh / height;
        fxUvMaxX = (sw - 0.5f) / width; fxUvMaxY = (sh - 0.5f) / height;
        drawFx(a, false, in, null, null, flipY ? glRectToPassScissor(dsr) : toPassScissor(dsr));

        GpuTexture cur = a;
        for (int axis = 0; axis < 2; axis++) {
            if (sg[axis] < 0.05f) continue;
            GpuTexture dst = axis == 0 ? b : c;
            resetFx(2);
            fxDevX0 = 0; fxDevY0 = dsY0; fxDevX1 = sw; fxDevY1 = dsY1;
            fxViewW = width; fxViewH = height;
            fxUvScaleX = 1f; fxUvScaleY = 1f;
            fxUvMaxX = (sw - 0.5f) / width; fxUvMaxY = (sh - 0.5f) / height;
            fxDirX = axis == 0 ? 1f : 0f;
            fxDirY = axis == 0 ? 0f : 1f;
            fxSigma = sg[axis];
            fxTaps = taps[axis];
            fxStep = step[axis];
            int[] stepRect = axis == 0 && sg[1] >= 0.05f ? r1 : r2;
            drawFx(dst, false, cur, null, null, flipY ? glRectToPassScissor(stepRect) : toPassScissor(stepRect));
            cur = dst;
        }

        int[] up = clampTo(new int[]{(r2[0] > 0 ? r2[0] + 1 : 0) * k, (r2[1] > 0 ? r2[1] + 1 : 0) * k,
                (r2[2] < sw ? r2[2] - 1 : sw) * k, (r2[3] < sh ? r2[3] - 1 : sh) * k}, width, height);
        if (up == null) return in;
        resetFx(8);
        fxUvScaleX = (float) sw / width; fxUvScaleY = (float) sh / height;
        fxUvMaxX = (sw - 0.5f) / width; fxUvMaxY = (sh - 0.5f) / height;
        drawFx(out, false, cur, null, null, flipY ? glRectToPassScissor(up) : toPassScissor(up));
        if (expand) {
            region[0] = up[0];
            region[1] = flipY ? height - up[3] : up[1];
            region[2] = up[2];
            region[3] = flipY ? height - up[1] : up[3];
        }
        return out;
    }

    private void resetFx(int mode) {
        fxDevX0 = 0; fxDevY0 = 0; fxDevX1 = width; fxDevY1 = height;
        fxViewW = width; fxViewH = height;
        fxMode = mode;
        fxOpacity = 1f;
        fxHasMatrix = 0;
        fxDirX = 0f; fxDirY = 0f; fxSigma = 0f; fxTaps = 0; fxStep = 0f;
        fxTexelX = 1f / width; fxTexelY = 1f / height;
        fxUvScaleX = 1f; fxUvScaleY = 1f; fxUvMaxX = 1f; fxUvMaxY = 1f;
        fxK = 1;
        fxShadowOffX = 0f; fxShadowOffY = 0f;
        fxShadowR = 0f; fxShadowG = 0f; fxShadowB = 0f; fxShadowA = 0f;
        fxMaskMode = 0; fxBlend = 0;
        fxInvA = 1f; fxInvB = 0f; fxInvC = 0f; fxInvD = 1f; fxInvE = 0f; fxInvF = 0f; fxShapeScale = 1f;
        fxClipKind = 0; fxEvenOdd = 0; fxClipN = 0; fxHasB = 0;
    }

    private void setMatrix(float[] m) {
        fxHasMatrix = m == null ? 0 : 1;
        if (m != null) System.arraycopy(m, 0, fxMat, 0, 12);
    }

    private void setShapes(GroupExtras x, boolean withBackdrop) {
        Matrix2D inv = x.pose().invert();
        fxInvA = (float) inv.a(); fxInvB = (float) inv.b(); fxInvC = (float) inv.c(); fxInvD = (float) inv.d();
        fxInvE = (float) inv.e(); fxInvF = (float) inv.f();
        fxShapeScale = (float) Math.sqrt(Math.abs(x.pose().determinant()));
        ClipShape c = x.clip();
        fxClipKind = c == null ? 0 : switch (c.kind()) {
            case INSET -> 1; case CIRCLE -> 2; case ELLIPSE -> 3; case POLYGON -> 4;
        };
        if (c != null) {
            float[] d = c.data();
            Arrays.fill(fxClipD, 0f);
            System.arraycopy(d, 0, fxClipD, 0, d.length);
            fxClipN = d.length / 4;
            fxEvenOdd = c.evenOdd() ? 1 : 0;
        }
        fxHasB = withBackdrop && x.backdropShape() != null ? 1 : 0;
        if (fxHasB != 0) {
            float[] d = x.backdropShape().data();
            System.arraycopy(d, 0, fxBD, 0, Math.min(12, d.length));
        }
    }

    private void drawFx(GpuTexture dst, boolean blend, GpuTexture src, GpuTexture aux, GpuTexture backdrop, int[] scissor) {
        endPass();
        fxUniforms.clear();
        fxUniforms.putFloat(fxDevX0).putFloat(fxDevY0).putFloat(fxDevX1).putFloat(fxDevY1);
        fxUniforms.putFloat(fxViewW).putFloat(fxViewH);
        fxUniforms.putFloat(fxDirX).putFloat(fxDirY);
        for (int i = 0; i < 12; i++) fxUniforms.putFloat(fxMat[i]);
        fxUniforms.putFloat(fxShadowR).putFloat(fxShadowG).putFloat(fxShadowB).putFloat(fxShadowA);
        fxUniforms.putFloat(fxInvA).putFloat(fxInvB).putFloat(fxInvC).putFloat(fxInvD);
        for (int i = 0; i < 12; i++) fxUniforms.putFloat(fxBD[i]);
        for (int i = 0; i < MAX_CLIP_VEC * 4; i++) fxUniforms.putFloat(fxClipD[i]);
        fxUniforms.putFloat(fxTexelX).putFloat(fxTexelY);
        fxUniforms.putFloat(fxUvScaleX).putFloat(fxUvScaleY);
        fxUniforms.putFloat(fxUvMaxX).putFloat(fxUvMaxY);
        fxUniforms.putFloat(fxShadowOffX).putFloat(fxShadowOffY);
        fxUniforms.putFloat(fxInvE).putFloat(fxInvF);
        fxUniforms.putInt(fxMode).putFloat(fxOpacity);
        fxUniforms.putInt(fxHasMatrix).putFloat(fxSigma).putInt(fxTaps).putFloat(fxStep);
        fxUniforms.putInt(fxK).putInt(fxMaskMode).putInt(fxBlend).putFloat(fxShapeScale);
        fxUniforms.putInt(fxClipKind).putInt(fxEvenOdd).putInt(fxClipN).putInt(fxHasB);
        fxUniforms.flip();

        fxSamplers[0] = src != null ? src : dummyTex;
        fxSamplers[1] = aux != null ? aux : dummyTex;
        fxSamplers[2] = backdrop != null ? backdrop : dummyTex;

        GpuPass pass = activeFrame.beginPass(dst, LoadOp.LOAD, 0, scissor);
        pass.bind(blend ? fxBlendPipeline : fxCopyPipeline,
                new GpuBindings(EMPTY_RECORDS_HOLDER.empty, fxSamplers, fxUniforms));
        pass.drawInstanced(4, 1, 0);
        pass.end();
    }

    private int[] clampToScreen(float[] r) {
        int x0 = Math.max(0, (int) Math.floor(r[0])), y0 = Math.max(0, (int) Math.floor(r[1]));
        int x1 = Math.min(width, (int) Math.ceil(r[2])), y1 = Math.min(height, (int) Math.ceil(r[3]));
        return x1 > x0 && y1 > y0 ? new int[]{x0, y0, x1, y1} : null;
    }

    private int[] clampInts(int[] r) { return clampTo(r, width, height); }

    private static int[] clampTo(int[] r, int w, int h) {
        int x0 = Math.max(0, r[0]), y0 = Math.max(0, r[1]), x1 = Math.min(w, r[2]), y1 = Math.min(h, r[3]);
        return x1 > x0 && y1 > y0 ? new int[]{x0, y0, x1, y1} : null;
    }

    private static int[] toPassScissor(int[] r) {
        return r == null ? null : new int[]{r[0], r[1], r[2] - r[0], r[3] - r[1]};
    }

    private int[] glRectToPassScissor(int[] r) {
        return new int[]{r[0], height - r[3], r[2] - r[0], r[3] - r[1]};
    }

    // ------------------------------------------------------------------ GPU の図形 (GpuSink)

    private void beforeRectTreeShape(Matrix2D pose, float x0, float y0, float x1, float y1) {
        if (spanAccOn) accumulate(spanAcc, pose, x0, y0, x1, y1);
        if (!inDiffPatch && (rects.pending() == 0 || cpu.dirtyBounds() != null)) {
            flushOverlay();
        }
        if (!layerStack.isEmpty()) {
            double ax = pose.applyX(x0, y0), ay = pose.applyY(x0, y0), bx = pose.applyX(x1, y0), by = pose.applyY(x1, y0);
            double cx = pose.applyX(x0, y1), cy = pose.applyY(x0, y1), dx = pose.applyX(x1, y1), dy = pose.applyY(x1, y1);
            touchAndEnsureCleared(
                    (float) Math.min(Math.min(ax, bx), Math.min(cx, dx)),
                    (float) Math.min(Math.min(ay, by), Math.min(cy, dy)),
                    (float) Math.max(Math.max(ax, bx), Math.max(cx, dx)),
                    (float) Math.max(Math.max(ay, by), Math.max(cy, dy)));
        }
    }

    /** 損傷範囲 {@code {x0, y0, x1, y1}} を、パスのシザー {@code {x, y, 幅, 高さ}} にする（周りに 2 画素の余白。描画先の内側に収める）。 */
    private int[] damageScissor(int[] d) {
        int x0 = Math.max(0, d[0] - 2), y0 = Math.max(0, d[1] - 2), x1 = Math.min(width, d[2] + 2), y1 = Math.min(height, d[3] + 2);
        if (x1 <= x0 || y1 <= y0) return new int[]{0, 0, 1, 1};
        return new int[]{x0, y0, x1 - x0, y1 - y0};
    }

    private static void accumulate(int[] box, Matrix2D pose, float x0, float y0, float x1, float y1) {
        double ax = pose.applyX(x0, y0), ay = pose.applyY(x0, y0), bx = pose.applyX(x1, y0), by = pose.applyY(x1, y0);
        double cx = pose.applyX(x0, y1), cy = pose.applyY(x0, y1), dx = pose.applyX(x1, y1), dy = pose.applyY(x1, y1);
        int bx0 = (int) Math.floor(Math.min(Math.min(ax, bx), Math.min(cx, dx))) - 1;
        int by0 = (int) Math.floor(Math.min(Math.min(ay, by), Math.min(cy, dy))) - 1;
        int bx1 = (int) Math.ceil(Math.max(Math.max(ax, bx), Math.max(cx, dx))) + 1;
        int by1 = (int) Math.ceil(Math.max(Math.max(ay, by), Math.max(cy, dy))) + 1;
        if (box[2] == Integer.MIN_VALUE) { box[0] = bx0; box[1] = by0; box[2] = bx1; box[3] = by1; return; }
        if (bx0 < box[0]) box[0] = bx0;
        if (by0 < box[1]) box[1] = by0;
        if (bx1 > box[2]) box[2] = bx1;
        if (by1 > box[3]) box[3] = by1;
    }

    private static void resetBox(int[] box) {
        box[0] = box[1] = Integer.MAX_VALUE;
        box[2] = box[3] = Integer.MIN_VALUE;
    }

    private void growSpanBounds(int index) {
        int need = (index + 1) * 4;
        if (spanBounds.length >= need) return;
        int[] n = new int[Math.max(need, spanBounds.length * 2)];
        System.arraycopy(spanBounds, 0, n, 0, spanBounds.length);
        for (int i = spanBounds.length; i < n.length; i += 4) n[i + 2] = Integer.MIN_VALUE; // 未知
        spanBounds = n;
    }

    private void storeSpanBounds(int index, boolean ok) {
        growSpanBounds(index);
        int b = index * 4;
        if (!ok) { // 記録に失敗したスパン: 範囲は未知（次に書き換えるときは全面）
            spanBounds[b] = spanBounds[b + 1] = spanBounds[b + 3] = 0;
            spanBounds[b + 2] = Integer.MIN_VALUE;
            return;
        }
        if (spanAcc[2] == Integer.MIN_VALUE) { // 何も積まなかった（描かない項目）: 空の範囲
            spanBounds[b] = spanBounds[b + 1] = spanBounds[b + 2] = spanBounds[b + 3] = 0;
            return;
        }
        spanBounds[b] = spanAcc[0];
        spanBounds[b + 1] = spanAcc[1];
        spanBounds[b + 2] = spanAcc[2];
        spanBounds[b + 3] = spanAcc[3];
    }

    /** スパン {@code index} の記録済みの範囲を損傷へ足す。未知なら損傷を不明にする。 */
    private void addSpanToDamage(int index) {
        int b = index * 4;
        if (b + 3 >= spanBounds.length || spanBounds[b + 2] == Integer.MIN_VALUE) { patchDamageValid = false; return; }
        addToDamage(spanBounds[b], spanBounds[b + 1], spanBounds[b + 2], spanBounds[b + 3]);
    }

    private void addToDamage(int x0, int y0, int x1, int y1) {
        if (x1 <= x0 || y1 <= y0) return;
        if (patchDamage[2] == Integer.MIN_VALUE) { patchDamage[0] = x0; patchDamage[1] = y0; patchDamage[2] = x1; patchDamage[3] = y1; return; }
        if (x0 < patchDamage[0]) patchDamage[0] = x0;
        if (y0 < patchDamage[1]) patchDamage[1] = y0;
        if (x1 > patchDamage[2]) patchDamage[2] = x1;
        if (y1 > patchDamage[3]) patchDamage[3] = y1;
    }

    private static float shapeMargin(Matrix2D pose) {
        return 1f / Math.max((float) pose.meanScale(), 0.01f);
    }

    private boolean spanOk() {
        return rects != null && RectStream.replayEnabled && layerStack.isEmpty();
    }

    @Override
    public boolean snapsScrollOffsets() {
        return retainedScroll;
    }

    @Override
    public boolean spanReplay(int index, boolean cpuPending) {
        if (!spanOk() || !rects.spanCanReplay(index, atlas.resets())) return false;
        if (cpuPending && (rects.pending() == 0 || cpu.dirtyBounds() != null)) flushOverlay();
        rects.spanApply(index);
        gpuGlyphs += rects.replayedGlyphs;
        return true;
    }

    @Override
    public boolean spanBegin(int index) {
        if (!spanOk()) return false;
        rects.spanBegin(index, atlas.resets());
        spanGlyph0 = gpuGlyphs;
        resetBox(spanAcc);
        spanAccOn = true;
        return true;
    }

    @Override
    public void spanEnd(int index, boolean ok) {
        if (rects == null) return;
        if (ok && !layerStack.isEmpty()) ok = false;
        spanAccOn = false;
        storeSpanBounds(index, ok);
        rects.spanEnd(ok, atlas.resets(), (int) (gpuGlyphs - spanGlyph0));
    }

    @Override
    public boolean beginDiffPatch() {
        if (!spanOk() || !prevFrameCleanSinglePass || width != prevPatchWidth || height != prevPatchHeight
                || clearPremul != prevPatchClearPremul || atlas.resets() != prevPatchAtlasResets
                || !retained.isEmpty()) {
            return false;
        }
        if (!rects.beginDiffPatch()) return false;
        inDiffPatch = true;
        resetBox(patchDamage);
        patchDamageValid = true;
        frameDamage = null;
        return true;
    }

    @Override
    public boolean canPatchSpan(int index) {
        return inDiffPatch && spanOk() && rects.canPatchSpan(index, atlas.resets());
    }

    @Override
    public boolean patchSpanBegin(int index) {
        if (!inDiffPatch || !spanOk()) return false;
        if (!rects.patchSpanBegin(index, atlas.resets())) return false;
        spanGlyph0 = gpuGlyphs;
        addSpanToDamage(index); // 書き換える前の範囲（古い絵を消す）
        resetBox(spanAcc);
        spanAccOn = true;
        return true;
    }

    @Override
    public boolean patchSpanEnd(int index, boolean ok) {
        if (rects == null || !inDiffPatch) return false;
        if (ok && !layerStack.isEmpty()) ok = false;
        spanAccOn = false;
        storeSpanBounds(index, ok);
        if (ok) {
            if (spanAcc[2] != Integer.MIN_VALUE) addToDamage(spanAcc[0], spanAcc[1], spanAcc[2], spanAcc[3]); // 新しい範囲
        } else {
            patchDamageValid = false;
        }
        return rects.patchSpanEnd(index, ok, atlas.resets(), (int) (gpuGlyphs - spanGlyph0));
    }

    @Override
    public void abortDiffPatch() {
        inDiffPatch = false;
        spanAccOn = false;
        patchDamageValid = false;
        frameDamage = null;
        cpu.clearDirty();
        if (rects != null) rects.abortDiffPatch();
    }

    @Override
    public void endDiffPatch() {
        inDiffPatch = false;
        spanAccOn = false;
        frameDamage = patchDamageValid ? new int[]{patchDamage[0], patchDamage[1], patchDamage[2], patchDamage[3]} : null;
        if (rects != null) rects.endDiffPatch();
    }

    @Override
    public boolean fillRoundRect(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                 int argb, GpuClip[] clips, float opacity) {
        if (rects != null && (RectStream.opMask & 1) != 0 && rects.accepts(pose, x0, y0, x1, y1, rx, ry, clips, opacity)) {
            float mg = shapeMargin(pose);
            beforeRectTreeShape(pose, x0 - mg, y0 - mg, x1 + mg, y1 + mg);
            rects.push(pose, x0, y0, x1, y1, rx, ry, argb, clips, opacity);
            return true;
        }
        return false;
    }

    @Override
    public boolean strokeRoundBorder(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                     float ix0, float iy0, float ix1, float iy1, float[] irx, float[] iry, boolean innerEmpty,
                                     int[] colors, float[] widths, GpuClip[] clips, float opacity) {
        if (rects != null && (RectStream.opMask & 2) != 0 && rects.accepts(pose, x0, y0, x1, y1, rx, ry, clips, opacity)) {
            float mg = shapeMargin(pose);
            beforeRectTreeShape(pose, x0 - mg, y0 - mg, x1 + mg, y1 + mg);
            rects.pushStroke(pose, x0, y0, x1, y1, rx, ry, ix0, iy0, ix1, iy1, irx, iry, innerEmpty, colors, widths, clips, opacity);
            return true;
        }
        return false;
    }

    @Override
    public boolean strokeDashedBorder(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                      float ix0, float iy0, float ix1, float iy1, float[] irx, float[] iry, boolean innerEmpty,
                                      int[] colors, float[] widths, float bx, float by, float cxm, float cym, float[] pcx, float[] pcy,
                                      float period, float dash, boolean round, float thickness, GpuClip[] clips, float opacity) {
        if (rects != null && (RectStream.opMask & 1024) != 0 && period > 0f && rects.acceptsWithExt(pose, x0, y0, x1, y1, rx, ry, clips, opacity)) {
            float mg = shapeMargin(pose);
            beforeRectTreeShape(pose, x0 - mg, y0 - mg, x1 + mg, y1 + mg);
            rects.pushStrokeDashed(pose, x0, y0, x1, y1, rx, ry, ix0, iy0, ix1, iy1, irx, iry, innerEmpty, colors, widths,
                    bx, by, cxm, cym, pcx, pcy, period, dash, round, thickness, clips, opacity);
            return true;
        }
        return false;
    }

    @Override
    public boolean strokeCircleBorder(Matrix2D pose, float cx, float cy, float outerR, float innerR,
                                      float bx0, float by0, float bx1, float by1, int[] colors, GpuClip[] clips, float opacity) {
        if (rects != null && (RectStream.opMask & 128) != 0 && rects.acceptsPosed(pose, bx0, by0, bx1, by1, clips, opacity)) {
            beforeRectTreeShape(pose, bx0, by0, bx1, by1);
            rects.pushCircleBorder(pose, cx, cy, outerR, innerR, bx0, by0, bx1, by1, colors, clips, opacity);
            return true;
        }
        return false;
    }

    @Override
    public boolean fillGradient(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                ShaderGradient g, GpuClip[] clips, float opacity) {
        if (rects != null && (RectStream.opMask & 16) != 0 && rects.acceptsWithExt(pose, x0, y0, x1, y1, rx, ry, clips, opacity)) {
            float mg = shapeMargin(pose);
            beforeRectTreeShape(pose, x0 - mg, y0 - mg, x1 + mg, y1 + mg);
            rects.pushGradient(pose, x0, y0, x1, y1, rx, ry, g, clips, opacity);
            return true;
        }
        return false;
    }

    @Override
    public boolean outerShadow(Matrix2D pose, float qx0, float qy0, float qx1, float qy1,
                               float sx0, float sy0, float sx1, float sy1, float[] srx, float[] sry, float sigma,
                               float[] hole, float[] hrx, float[] hry, int argb, GpuClip[] clips, float opacity) {
        if (rects != null && (RectStream.opMask & 4) != 0 && rects.acceptsOuterShadow(pose, qx0, qy0, qx1, qy1, clips, opacity)) {
            beforeRectTreeShape(pose, qx0, qy0, qx1, qy1);
            rects.pushOuterShadow(pose, qx0, qy0, qx1, qy1, sx0, sy0, sx1, sy1, srx, sry, sigma, hole, hrx, hry, argb, clips, opacity);
            return true;
        }
        return false;
    }

    @Override
    public boolean insetShadow(Matrix2D pose, float qx0, float qy0, float qx1, float qy1, float[] rx, float[] ry,
                               float sigma, float[] hole, float[] hrx, float[] hry, int argb, GpuClip[] clips, float opacity) {
        if (rects != null && (RectStream.opMask & 8) != 0 && rects.acceptsWithExt(pose, qx0, qy0, qx1, qy1, rx, ry, clips, opacity)) {
            beforeRectTreeShape(pose, qx0 - 1, qy0 - 1, qx1 + 1, qy1 + 1);
            rects.pushInsetShadow(pose, qx0, qy0, qx1, qy1, rx, ry, sigma, hole, hrx, hry, argb, clips, opacity);
            return true;
        }
        return false;
    }

    @Override
    public boolean drawGlyph(GlyphBitmap g, int bx, int by, int argb, GpuClip[] clips, float opacity) {
        if (rects == null || (RectStream.opMask & 32) == 0 || !atlas.fits(g)
                || !rects.acceptsGlyph(bx, by, g.width(), g.height(), clips, opacity)) return false;
        int[] slot = atlasSlot(g);
        beforeRectTreeShape(Matrix2D.IDENTITY, bx, by, bx + g.width(), by + g.height());
        rects.pushGlyph(bx, by, g.width(), g.height(), slot[0], slot[1], argb, clips, opacity);
        gpuGlyphs++;
        return true;
    }

    @Override
    public boolean drawGlyphStroke(GlyphBitmap sdf, int bx, int by, float half, int argb, GpuClip[] clips, float opacity) {
        if (rects == null || (RectStream.opMask & 512) == 0 || !atlas.fits(sdf)
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
        if (rects == null || (RectStream.opMask & 256) == 0 || !atlas.fits(g)
                || !rects.acceptsPosed(pose, bx0, by0, bx1, by1, clips, opacity)) return false;
        int[] slot = atlasSlot(g);
        beforeRectTreeShape(pose, bx0, by0, bx1, by1);
        rects.pushGlyphTransformed(pose, bx0, by0, bx1, by1, slot[0], slot[1], g.width(), g.height(), ox, oy, s, g.left(), g.top(),
                argb, clips, opacity);
        gpuGlyphs++;
        return true;
    }

    private int[] atlasSlot(GlyphBitmap g) {
        int[] slot = atlas.slot(g, this::flushAll);
        if (!atlas.lastPlacementWasNew()) return slot;
        if (atlas.lastPlacementGrew()) {
            growAtlasTexture();
            return slot;
        }
        uploadGlyph(g, slot);
        return slot;
    }

    private void growAtlasTexture() {
        endPass();
        if (atlasTex != null) atlasTex.close();
        atlasTex = device.createTexture(TextureDesc.r8(atlas.size(), atlas.size()));
        atlas.forEach(this::uploadGlyph);
    }

    private void uploadGlyph(GlyphBitmap g, int[] slot) {
        endPass();
        int len = g.width() * g.height();
        if (scratchGlyphBuf == null || scratchGlyphBuf.capacity() < len) {
            scratchGlyphBuf = ByteBuffer.allocateDirect(Math.max(len, 4096));
        }
        scratchGlyphBuf.clear();
        scratchGlyphBuf.put(g.coverage(), 0, len).flip();
        activeFrame.upload(atlasTex, slot[0], slot[1], g.width(), g.height(), scratchGlyphBuf);
    }

    @Override
    public boolean drawImage(Matrix2D pose, Pixmap image, float bx0, float by0, float bx1, float by1,
                             float px0, float py0, float sx, float sy,
                             float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                             int taps, float stepX, float stepY, GpuClip[] clips, float opacity) {
        if (image.width() > 8192 || image.height() > 8192) return false;
        if (!acceptsRectImage(pose, bx0, by0, bx1, by1, rx, ry, clips, opacity, 64)) return false;
        flushOverlay();
        GpuTexture tex = imageTexture(image);
        boolean flip = device.caps().bottomUpFramebuffer();
        float adjPy0 = flip ? py0 + image.height() / sy : py0;
        float adjSy = flip ? -sy : sy;
        pushRectImage(pose, tex, image.width(), image.height(), bx0, by0, bx1, by1, px0, adjPy0, sx, adjSy,
                x0, y0, x1, y1, rx, ry, taps, stepX, stepY, clips, opacity);
        return true;
    }

    @Override
    public boolean drawTexture(Matrix2D pose, long handle, int imgW, int imgH, float bx0, float by0, float bx1, float by1,
                               float px0, float py0, float sx, float sy,
                               float x0, float y0, float x1, float y1, float[] rx, float[] ry, GpuClip[] clips, float opacity) {
        if (handle <= 0 || handle > Integer.MAX_VALUE || imgW <= 0 || imgH <= 0 || imgW > 16384 || imgH > 16384) return false;
        if (!acceptsRectImage(pose, bx0, by0, bx1, by1, rx, ry, clips, opacity, 2048)) return false;
        flushOverlay();
        GpuTexture tex = wrapExternalCached(handle, imgW, imgH);
        pushRectImage(pose, tex, imgW, imgH, bx0, by0, bx1, by1, px0, py0, sx, sy, x0, y0, x1, y1, rx, ry, 1, 0f, 0f, clips, opacity);
        return true;
    }

    private GpuTexture wrapExternalCached(long handle, int w, int h) {
        GpuTexture t = externalTexCache.get(handle);
        if (t != null && t.width() == w && t.height() == h) return t;
        if (t != null) t.close();
        t = device.wrapExternal(handle, w, h);
        externalTexCache.put(handle, t);
        return t;
    }

    @Override
    public boolean drawScene3D(Matrix2D pose, Scene3DWorld world, int fboW, int fboH,
                               float x0, float y0, float x1, float y1,
                               float[] rx, float[] ry, GpuClip[] clips, float opacity) {
        if (world == null || fboW <= 0 || fboH <= 0 || !(x1 > x0) || !(y1 > y0)) return false;
        if (!acceptsRectImage(pose, x0, y0, x1, y1, rx, ry, clips, opacity, 2048)) return false;
        flushOverlay();
        endPass();
        if (scene3dRenderer == null) {
            scene3dRenderer = new Scene3DRenderer(device);
        }
        GpuTexture tex = scene3dRenderer.render(activeFrame, world, fboW, fboH);
        float w = x1 - x0, h = y1 - y0;
        boolean flip = device.caps().bottomUpFramebuffer();
        float sx = (float) fboW / w;
        float py0 = flip ? y1 : y0;
        float sy = flip ? -(float) fboH / h : (float) fboH / h;
        pushRectImage(pose, tex, fboW, fboH, x0, y0, x1, y1, x0, py0, sx, sy, x0, y0, x1, y1, rx, ry, 1, 0f, 0f, clips, opacity);
        return true;
    }

    @Override
    public Pixmap renderScene3DPixmap(Scene3DWorld world, int fboW, int fboH) {
        if (world == null || fboW <= 0 || fboH <= 0) return null;
        if (scene3dRenderer == null) {
            scene3dRenderer = new Scene3DRenderer(device);
        }
        Pixmap pm = new Pixmap(fboW, fboH);
        if (activeFrame != null) {
            endPass();
            GpuTexture tex = scene3dRenderer.render(activeFrame, world, fboW, fboH);
            scene3dRenderer.readTexturePixelsArgb(activeFrame, tex, fboW, fboH, pm.pixels());
        } else {
            host.enter();
            try {
                GpuTexture tex = scene3dRenderer.render(world, fboW, fboH);
                int[] argb = scene3dRenderer.readTexturePixelsArgb(tex, fboW, fboH);
                System.arraycopy(argb, 0, pm.pixels(), 0, Math.min(argb.length, pm.pixels().length));
            } finally {
                host.exit();
            }
        }
        return pm;
    }

    private boolean acceptsRectImage(Matrix2D pose, float bx0, float by0, float bx1, float by1, float[] rx, float[] ry,
                                     GpuClip[] clips, float opacity, int maskBit) {
        if (rects == null || (RectStream.opMask & maskBit) == 0) return false;
        float m = 1f / Math.max((float) pose.meanScale(), 0.01f);
        return rects.acceptsImage(pose, bx0 - m, by0 - m, bx1 + m, by1 + m, rx, ry, clips, opacity);
    }

    private void pushRectImage(Matrix2D pose, GpuTexture tex, int imgW, int imgH, float bx0, float by0, float bx1, float by1,
                               float px0, float py0, float sx, float sy, float x0, float y0, float x1, float y1,
                               float[] rx, float[] ry, int taps, float stepX, float stepY, GpuClip[] clips, float opacity) {
        float m = 1f / Math.max((float) pose.meanScale(), 0.01f);
        if (rects.pending() > 0 && rectRunImg != null && rectRunImg != tex) drainRects();
        beforeRectTreeShape(pose, bx0 - m, by0 - m, bx1 + m, by1 + m);
        rectRunImg = tex;
        rects.pushImage(pose, bx0 - m, by0 - m, bx1 + m, by1 + m, px0, py0, sx, sy, x0, y0, x1, y1, rx, ry, imgW, imgH,
                taps, stepX, stepY, clips, opacity);
        gpuImages++;
    }

    private GpuTexture imageTexture(Pixmap image) {
        ImageTextureCache.Entry e = imageTex.get(image);
        if (e != null) {
            GpuTexture tex = (GpuTexture) e.handle;
            if (e.version != image.version()) {
                flushAll();
                endPass();
                uploadArgbFull(tex, image);
                e.version = image.version();
            }
            return tex;
        }
        if (imageTex.isFull()) {
            ImageTextureCache.Entry old = imageTex.evictOldest();
            GpuTexture oldTex = (GpuTexture) old.handle;
            if (oldTex == rectRunImg) flushAll();
            oldTex.close();
        }
        endPass();
        GpuTexture tex = device.createTexture(TextureDesc.rgba8Linear(image.width(), image.height(), false));
        uploadArgbFull(tex, image);
        imageTex.putHandle(image, tex, image.version());
        return tex;
    }

    private void uploadArgbFull(GpuTexture dst, Pixmap pm) {
        int w = pm.width(), h = pm.height();
        int[] src = pm.pixels();
        int bytes = w * h * 4;
        ByteBuffer buf = ensureScratchRgba(bytes);
        IntBuffer ib = buf.asIntBuffer();
        for (int i = 0; i < src.length; i++) {
            ib.put(Integer.rotateLeft(src[i], 8));
        }
        buf.limit(bytes).position(0);
        activeFrame.upload(dst, 0, 0, w, h, buf);
    }

    private ByteBuffer ensureScratchRgba(int bytes) {
        if (scratchRgbaBuf == null || scratchRgbaBuf.capacity() < bytes) {
            scratchRgbaBuf = ByteBuffer.allocateDirect(Math.max(bytes, 4096)).order(ByteOrder.BIG_ENDIAN);
        }
        scratchRgbaBuf.clear();
        return scratchRgbaBuf;
    }

    // ------------------------------------------------------------------ 保持するスクロール層

    private void releaseRetained() {
        for (Retained r : retained.values()) {
            if (r.layer != null) r.layer.close();
        }
        retained.clear();
        seenScrollPose.clear();
        seenScrollToken.clear();
    }

    @Override
    public RetainPlan planScrollLayer(GpuScrollLayer l) {
        int[] bb = l.bounds();
        return planScrollLayerBounds(l.key(), l.token(), l.pose(), bb[0], bb[1], bb[2], bb[3]);
    }

    @Override
    public RetainPlan planScrollLayerBounds(String key, Object token, Matrix2D pose, int x0, int y0, int x1, int y1) {
        RetainPlan NONE = RetainPlan.NONE;
        if (!retainedScroll || retainOverflow || rects == null || closed || !layerStack.isEmpty()) return NONE;
        if ((long) (x1 - x0) * (y1 - y0) * 8L < (long) width * height) return NONE;
        Retained r = retained.get(key);
        if (r == null) {
            Matrix2D was = seenScrollPose.get(key);
            Object wasToken = seenScrollToken.get(key);
            seenScrollPose.put(key, pose);
            seenScrollToken.put(key, token);
            boolean moved = was != null && wasToken == token && (was.e() != pose.e() || was.f() != pose.f());
            if (!moved) return NONE;
        }
        if (r != null && r.token == token && samePose(r.pose, pose)) {
            int dx = (int) Math.round(pose.e() - r.pose.e()), dy = (int) Math.round(pose.f() - r.pose.f());
            if (x0 - dx >= 0 && y0 - dy >= 0 && x1 - dx <= width && y1 - dy <= height) return RetainPlan.SKIP;
        }
        if (r == null) {
            while (retained.size() >= MAX_RETAINED) {
                String oldest = retained.keySet().iterator().next();
                Retained rem = retained.remove(oldest);
                if (rem.layer != null) rem.layer.close();
            }
            GpuTexture layer = createLayer(false);
            if (layer == null) return NONE;
            r = new Retained();
            r.layer = layer;
            retained.put(key, r);
        }
        r.token = null;
        return RetainPlan.RECORD;
    }

    private static boolean samePose(Matrix2D a, Matrix2D b) {
        return a != null && a.a() == b.a() && a.b() == b.b() && a.c() == b.c() && a.d() == b.d();
    }

    @Override
    public void beginScrollLayer(GpuScrollLayer l) {
        Retained r = retained.get(l.key());
        if (r == null) return;
        flushAll();
        endPass();
        Entry e = obtainEntry();
        e.layer = r.layer;
        e.opacity = 1f;
        e.retainKey = l.key();
        layerStack.add(e);
    }

    @Override
    public boolean endScrollLayer(GpuScrollLayer l) {
        if (layerStack.isEmpty() || !l.key().equals(layerStack.get(layerStack.size() - 1).retainKey)) return false;
        flushAll();
        endPass();
        Entry e = layerStack.remove(layerStack.size() - 1);
        ensureCleared(e);
        releaseEntry(e);
        Retained r = retained.get(l.key());
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

    private void compositeRetained(Retained r, int dx, int dy, GpuScrollLayer l) {
        int[] b = l.bounds();
        float x0 = Math.max(0, b[0]), y0 = Math.max(0, b[1]), x1 = Math.min(width, b[2]), y1 = Math.min(height, b[3]);
        if (x1 <= x0 || y1 <= y0) return;
        float[] zero = new float[4];
        if (!acceptsRectImage(Matrix2D.IDENTITY, x0, y0, x1, y1, zero, zero, l.clip(), 1f, 2048)) return;
        flushOverlay();
        pushRectImage(Matrix2D.IDENTITY, r.layer, width, height, x0, y0, x1, y1,
                dx, dy + height, 1f, -1f, x0, y0, x1, y1, zero, zero, 1, 0f, 0f, l.clip(), 1f);
    }

    // ------------------------------------------------------------------ 結果・表示・終了

    @Override
    public Pixmap pixmap() {
        if (closed) throw new IllegalStateException("closed");
        if (readbackValid) return readback;
        host.enter();
        try {
            GpuFrame f = device.beginFrame();
            ByteBuffer buf = ensureScratchRgba(width * height * 4);
            f.readback(mainTarget, new int[]{0, 0, width, height}, buf);
            f.end();
            buf.flip();
            IntBuffer ib = buf.asIntBuffer();
            int[] dst = readback.pixels();
            for (int i = 0; i < dst.length; i++) {
                dst[i] = Integer.rotateRight(ib.get(), 8);
            }
        } finally {
            host.exit();
        }
        readbackValid = true;
        return readback;
    }

    public boolean enableGpuTiming() { return host.enableGpuTiming(); }
    public double lastGpuMillis() { return host.lastGpuMillis(); }
    public void finish() { host.finish(); }
    public void waitForRender() { host.waitForRender(); }
    public void present() { host.present(mainTarget, width, height); }
    public boolean shouldClose() { return host.shouldClose(); }
    public void pollEvents() { host.pollEvents(); }
    public long windowHandle() { return host.windowHandle(); }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        host.enter();
        try {
            releaseRetained();
            if (rects != null) { rects.close(); rects = null; }
            if (scene3dRenderer != null) { scene3dRenderer.close(); scene3dRenderer = null; }
            if (texBlendPipeline != null) { texBlendPipeline.close(); texBlendPipeline = null; }
            if (texCopyPipeline != null) { texCopyPipeline.close(); texCopyPipeline = null; }
            if (fxBlendPipeline != null) { fxBlendPipeline.close(); fxBlendPipeline = null; }
            if (fxCopyPipeline != null) { fxCopyPipeline.close(); fxCopyPipeline = null; }
            for (GpuTexture l : allLayers) l.close();
            allLayers.clear();
            layerPool.clear();
            if (mainTarget != null) { mainTarget.close(); mainTarget = null; }
            if (overlayTex != null) { overlayTex.close(); overlayTex = null; }
            if (atlasTex != null) { atlasTex.close(); atlasTex = null; }
            if (dummyTex != null) { dummyTex.close(); dummyTex = null; }
            for (Object h : imageTex.clearHandles()) ((GpuTexture) h).close();
            for (GpuTexture t : externalTexCache.values()) t.close();
            externalTexCache.clear();
        } finally {
            host.exit();
            host.close();
        }
    }
}
