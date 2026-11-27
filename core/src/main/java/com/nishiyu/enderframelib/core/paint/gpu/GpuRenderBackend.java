package com.nishiyu.enderframelib.core.paint.gpu;
import com.nishiyu.enderframelib.core.paint.model.*;
import com.nishiyu.enderframelib.core.paint.port.*;
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
import com.nishiyu.enderframelib.core.paint.SceneWalker;
import com.nishiyu.enderframelib.core.paint.RenderBackend;
import com.nishiyu.enderframelib.core.paint.RetainPlan;
import com.nishiyu.enderframelib.core.paint.SceneSource;
import com.nishiyu.enderframelib.core.paint.resource.PaintResources;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.LoggerFactory;

/**
 * RHI ({@link GpuDevice}) 上だけで動く API 非依存の GPU 描画バックエンド。
 * GL・GLFW・OS ウィンドウの型を一切持たず、コンテキスト制御と表示は {@link GpuSurfaceHost}、
 * CPU 保険層は {@link CpuFallback} を通じて行う。
 */
public final class GpuRenderBackend implements RenderBackend, GpuSink {
    private static final AtomicInteger SESSIONS = new AtomicInteger();
    static final com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuBuffer[] NO_BUFFERS = new com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuBuffer[0];

    public static volatile int maxLayerDepth = 32;

    private static final long LAYER_VRAM_BUDGET = 512L * 1024 * 1024;
    private static final int ATLAS_SIZE = 1024;
    private static final int ATLAS_MAX_SIZE = 4096;
    private static final int IMAGE_CACHE_LIMIT = 64;
    static final int MAX_CLIP_VEC = 96;
    private static final int MAX_RETAINED = 3;

    private final GpuSurfaceHost host;
    final GpuDevice device;
    /** フィルタの効果の段（{@link GpuEffects}）。 */
    private final GpuEffects fx = new GpuEffects(this);
    private final CpuFallback.Factory cpuFactory;

    int width;
    int height;
    private final float dpr;
    private final String sessionId;
    private final PaintResources resources = new PaintResources();
    private CpuFallback cpu;
    private Scene3DRenderer scene3dRenderer;
    private RectStream rects;

    private GpuPipeline texBlendPipeline;
    private GpuPipeline texCopyPipeline;
    GpuPipeline fxBlendPipeline;
    GpuPipeline fxCopyPipeline;

    private GpuTexture mainTarget;
    private GpuTexture overlayTex;
    private GpuTexture atlasTex;
    GpuTexture dummyTex;
    private GpuTexture rectRunImg;

    private final AtlasPacker atlas = new AtlasPacker(ATLAS_SIZE, ATLAS_MAX_SIZE);
    private final ImageTextureCache<Pixmap> imageTex = new ImageTextureCache<>(IMAGE_CACHE_LIMIT);
    private final Map<Long, GpuTexture> externalTexCache = new HashMap<>();

    private final GpuTexture[] texSampler = new GpuTexture[1];
    private final ByteBuffer texUniforms = ByteBuffer.allocateDirect(ShaderPrograms.TEX.uniformBytes())
            .order(ByteOrder.nativeOrder());
    private ByteBuffer scratchRgbaBuf;
    private ByteBuffer scratchGlyphBuf;

    private Pixmap readback;
    private Pixmap snapshotPm;
    private int clearPremul = 0;
    private boolean readbackValid;
    private boolean closed;
    /** 描き終えた前のフレームの塗りの色（符号なし）。描き終えていなければ -1（{@link #needsRepaint}。M3b-4b-4b）。 */
    private long paintedClear = -1L;

    GpuFrame activeFrame;
    private GpuPass activePass;
    private boolean activeMainCleared;

    /** 次に開く主パスのシザー（{@code null} = 全面）。 */
    private int[] mainScissor;

    private long gpuGlyphs, gpuImages, overlayFlushes, readbacks;
    private long scrollLayersSkipped, scrollLayersRecorded;
    private long seenOverflows;
    private boolean retainOverflow;

    private long frameLayerOps;
    private boolean frameHadImg;

    // 層の管理
    final ArrayDeque<GpuTexture> layerPool = new ArrayDeque<>();
    final ArrayList<Entry> layerStack = new ArrayList<>();
    private final ArrayList<GpuTexture> allLayers = new ArrayList<>();
    private final LinkedHashMap<String, Retained> retained = new LinkedHashMap<>(8, 0.75f, true);
    private final HashMap<String, Matrix2D> seenScrollPose = new HashMap<>();
    private final HashMap<String, Object> seenScrollToken = new HashMap<>();

    private static final double[] IDENTITY_MAP = {1, 0, 0, 1};

    static final class Entry {
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

    enum StageKind { COLOR, BLUR, DROP }

    static final class Stage {
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

        if (com.nishiyu.enderframelib.core.kernel.Shortcut.GL_RECT_TREES.on()) {
            try {
                this.rects = new RectStream(device, com.nishiyu.enderframelib.core.kernel.EngineFlags.GL_RECT_CAPACITY);
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

    /** 画像・3D の場面・ホストの中身の供給元（差し替えは持ち主に書けば、CPU の保険の描画器にも見える）。書体は {@link #fonts}。 */
    public PaintResources resources() { return resources; }

    public GpuRenderBackend clearColor(int argb) {
        this.clearPremul = Pixmap.premultiply(argb);
        return this;
    }

    public Scene3DRenderer scene3dRenderer() { return scene3dRenderer; }
    @Override public String sessionId() { return sessionId; }
    @Override public float devicePixelRatio() { return dpr; }
    /** 前に描き終えた画素を今の入力でも使えるなら false（{@code RenderBackend#needsRepaint}。範囲は CPU の保険の描画器が求め、描くときは全面）。 */
    @Override
    public boolean needsRepaint(List<DisplayItem> items, PaintOffsets offsets, SceneSource source, long nowMs) {
        return closed || cpu.needsRepaint(items, offsets, source, nowMs) || paintedClear != Integer.toUnsignedLong(clearPremul);
    }
    /** 次の {@link #needsRepaint} を true にする（書体の登録など、描画器の外の事情で絵が変わるとホストが知ったとき）。 */
    public void invalidate() { paintedClear = -1L; }
    public long gpuShapeCount() { return rects != null ? rects.accepted() - gpuGlyphs - gpuImages : 0; }
    public long rectTreeDrawCalls() { return rects != null ? rects.drawCalls() : 0; }
    public long rectTreeChangedWrites() { return rects != null ? rects.changedWrites() : 0; }
    public long rectTreeShapes() { return rects != null ? rects.accepted() : 0; }
    public long[] rectTreeRejects() {
        long[] r = new long[5];
        if (rects != null) { System.arraycopy(rects.rejects, 0, r, 0, 4); r[4] = rects.clipRejects; }
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
            this.paintedClear = -1L;
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

    @Override public void render(List<DisplayItem> items, long nowMs) { render(items, PaintOffsets.EMPTY, nowMs); }
    @Override public void render(List<DisplayItem> items, PaintOffsets offsets, long nowMs) { render(items, offsets, null, nowMs); }

    /** {@code source} は描き直す範囲（{@link #needsRepaint}）と、スクロールコンテナの中身を保持層にするのに使う（{@link SceneWalker}）。 */
    @Override
    public void render(List<DisplayItem> items, PaintOffsets offsets, SceneSource source, long nowMs) {
        if (closed) throw new IllegalStateException("closed");
        host.enter();
        try {
            host.beginTiming();
            try {
                readbackValid = false;
                paintedClear = -1L; // 描き終えるまでは前の画素を信じない
                cpu.clearDirty();
                for (Entry left : layerStack) releaseEntry(left);
                layerStack.clear();
                layerPool.clear();
                layerPool.addAll(allLayers);
                rectRunImg = null;
                frameLayerOps = 0;
                frameHadImg = false;
                if (rects != null) rects.begin();

                activeMainCleared = false;
                mainScissor = null;
                activeFrame = device.beginFrame();
                boolean ended = false;
                try {
                    activePass = activeFrame.beginPass(mainTarget, LoadOp.CLEAR, clearPremul, null);
                    activeMainCleared = true;
                    cpu.renderOnto(items, offsets, source, nowMs, this);
                    while (!layerStack.isEmpty()) endGroup();
                    flushAll();
                    endPass();
                    activeFrame.end();
                    ended = true;
                } finally {
                    if (activePass != null) { activePass.end(); activePass = null; }
                    if (!ended && activeFrame != null) activeFrame.end();
                    activeFrame = null;
                }

                if (rects != null && rects.overflows != seenOverflows) {
                    seenOverflows = rects.overflows;
                    if (com.nishiyu.enderframelib.core.kernel.Shortcut.GL_RETAINED_SCROLL.on() && !retainOverflow) {
                        retainOverflow = true;
                        releaseRetained();
                    }
                }
                host.publishFrame();
                paintedClear = Integer.toUnsignedLong(clearPremul);
            } finally {
                host.endTiming();
            }
        } finally {
            host.exit();
        }
    }

    GpuTexture currentTarget() {
        return layerStack.isEmpty() ? mainTarget : layerStack.get(layerStack.size() - 1).layer;
    }

    void endPass() {
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
                new GpuBindings(NO_BUFFERS, texSampler, texUniforms));
        pass.drawInstanced(4, 1, 0);
    }

    // ------------------------------------------------------------------ グループの層

    void ensureCleared(Entry e) {
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

    void touchAndEnsureCleared(float x0, float y0, float x1, float y1) {
        if (layerStack.isEmpty()) return;
        Entry top = layerStack.get(layerStack.size() - 1);
        ensureCleared(top);
        float[] b = top.bounds;
        if (x0 < b[0]) b[0] = x0;
        if (y0 < b[1]) b[1] = y0;
        if (x1 > b[2]) b[2] = x1;
        if (y1 > b[3]) b[3] = y1;
    }

    GpuTexture acquireLayer() {
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

    @Override
    public void endGroup() {
        if (layerStack.isEmpty()) return;
        if (layerStack.get(layerStack.size() - 1).isMask) endMask();
        flushAll();
        endPass();
        Entry e = layerStack.remove(layerStack.size() - 1);
        try {
            fx.endGroup(e);
        } finally {
            releaseEntry(e);
        }
    }

    // ------------------------------------------------------------------ GPU の図形 (GpuSink)

    private void beforeRectTreeShape(Matrix2D pose, float x0, float y0, float x1, float y1) {
        if ((rects.pending() == 0 || cpu.dirtyBounds() != null)) {
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

    private static float shapeMargin(Matrix2D pose) {
        return 1f / Math.max((float) pose.meanScale(), 0.01f);
    }

    @Override
    public boolean snapsScrollOffsets() {
        return com.nishiyu.enderframelib.core.kernel.Shortcut.GL_RETAINED_SCROLL.on();
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
        if (!com.nishiyu.enderframelib.core.kernel.Shortcut.GL_RETAINED_SCROLL.on() || retainOverflow || rects == null || closed || !layerStack.isEmpty()) return NONE;
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
        boolean flip = device.caps().bottomUpFramebuffer(); // 層の行の向きは API に依る（GL は下から上・Vulkan は上から下。以前は GL 決め打ちで Vulkan だけ上下反転。T2b）
        flushOverlay();
        pushRectImage(Matrix2D.IDENTITY, r.layer, width, height, x0, y0, x1, y1, dx, flip ? dy + height : dy, 1f, flip ? -1f : 1f, x0, y0, x1, y1, zero, zero, 1, 0f, 0f, l.clip(), 1f);
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
        closed = true; com.nishiyu.enderframelib.core.paint.CssAnimator.clearSession(sessionId); // 名前は自分が発行し CPU 描画器へ貸している
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
