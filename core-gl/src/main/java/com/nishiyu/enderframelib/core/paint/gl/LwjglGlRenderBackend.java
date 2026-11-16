package com.nishiyu.enderframelib.core.paint.gl;

import com.nishiyu.enderframelib.core.paint.BlendMode;
import com.nishiyu.enderframelib.core.paint.FilterOp;
import com.nishiyu.enderframelib.core.paint.RenderBackend;
import com.nishiyu.enderframelib.core.paint.RetainPlan;
import com.nishiyu.enderframelib.core.paint.gpu.GpuClip;
import com.nishiyu.enderframelib.core.paint.gpu.GpuRenderBackend;
import com.nishiyu.enderframelib.core.paint.gpu.GpuScrollLayer;
import com.nishiyu.enderframelib.core.paint.gpu.GpuSink;
import com.nishiyu.enderframelib.core.paint.gpu.RectStream;
import com.nishiyu.enderframelib.core.paint.gpu.Scene3DRenderer;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ShaderGradient;
import com.nishiyu.enderframelib.core.paint.software.ImageProvider;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.scene3d.Scene3DWorld;
import com.nishiyu.enderframelib.core.text.font.GlyphBitmap;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.List;
import java.util.function.LongFunction;

/**
 * {@link RenderBackend} と {@link GpuSink} の LWJGL (GLFW + OpenGL 3.2 core) 向けファサード。
 * GL/GLFW コンテキストと {@link GlDevice} は {@link GlContext}、API 非依存の GPU 描画組み立ては {@link GpuRenderBackend}、
 * CPU 保険層は {@link CpuLayerFallback} へ委譲し、このクラス自身は GL/GLFW の API を直接呼ばない。
 */
public final class LwjglGlRenderBackend implements RenderBackend, GpuSink {
    public static volatile boolean rectTrees = GpuRenderBackend.rectTrees;
    public static volatile int maxLayerDepth = GpuRenderBackend.maxLayerDepth;
    public static volatile boolean boundedLayers = GpuRenderBackend.boundedLayers;
    public static volatile boolean downsampledBlur = GpuRenderBackend.downsampledBlur;
    public static volatile boolean retainedScroll = GpuRenderBackend.retainedScroll;

    private final GlContext context;
    private final GpuRenderBackend gpu;

    public LwjglGlRenderBackend(int cssW, int cssH) {
        this(cssW, cssH, 1f, false, "EnderFrame");
    }

    public LwjglGlRenderBackend(int cssW, int cssH, float devicePixelRatio) {
        this(cssW, cssH, devicePixelRatio, false, "EnderFrame");
    }

    public LwjglGlRenderBackend(int cssW, int cssH, float devicePixelRatio, boolean visible, String title) {
        this(cssW, cssH, devicePixelRatio, visible, title, 0L, false);
    }

    public LwjglGlRenderBackend(int cssW, int cssH, float devicePixelRatio, boolean visible, String title, boolean resizable) {
        this(cssW, cssH, devicePixelRatio, visible, title, 0L, resizable);
    }

    public static LwjglGlRenderBackend sharedWith(long hostWindow, int cssW, int cssH, float devicePixelRatio) {
        if (hostWindow == 0L) throw new IllegalArgumentException("hostWindow is null");
        return new LwjglGlRenderBackend(cssW, cssH, devicePixelRatio, false, "EnderFrame", hostWindow, false);
    }

    private LwjglGlRenderBackend(int cssW, int cssH, float devicePixelRatio, boolean visible, String title, long shareWith, boolean resizable) {
        if (!(devicePixelRatio > 0f)) throw new IllegalArgumentException("devicePixelRatio must be > 0");
        syncFlags();
        int widthPx = Math.max(1, Math.round(cssW * devicePixelRatio));
        int heightPx = Math.max(1, Math.round(cssH * devicePixelRatio));
        this.context = new GlContext(widthPx, heightPx, devicePixelRatio, visible, title, shareWith, resizable);
        this.gpu = new GpuRenderBackend(context, CpuLayerFallback::new, cssW, cssH, devicePixelRatio);
    }

    private static void syncFlags() {
        GpuRenderBackend.rectTrees = rectTrees;
        GpuRenderBackend.maxLayerDepth = maxLayerDepth;
        GpuRenderBackend.boundedLayers = boundedLayers;
        GpuRenderBackend.downsampledBlur = downsampledBlur;
        GpuRenderBackend.retainedScroll = retainedScroll;
    }

    public boolean enableGpuTiming() { return gpu.enableGpuTiming(); }
    public double lastGpuMillis() { return gpu.lastGpuMillis(); }
    public int colorTexture() { return (int) gpu.colorTexture(); }
    public int textureWidth() { return gpu.textureWidth(); }
    public int textureHeight() { return gpu.textureHeight(); }
    public void resize(int cssW, int cssH) { gpu.resize(cssW, cssH); }
    public void finish() { gpu.finish(); }
    public void waitForRender() { gpu.waitForRender(); }
    public void present() { gpu.present(); }
    public boolean shouldClose() { return gpu.shouldClose(); }
    public void pollEvents() { gpu.pollEvents(); }
    public long windowHandle() { return gpu.windowHandle(); }

    public LwjglGlRenderBackend fonts(FontResolver resolver) { gpu.fonts(resolver); return this; }
    public LwjglGlRenderBackend images(ImageProvider provider) { gpu.images(provider); return this; }
    public LwjglGlRenderBackend scene3dWorlds(LongFunction<Scene3DWorld> provider) { gpu.scene3dWorlds(provider); return this; }
    public LwjglGlRenderBackend clearColor(int argb) { gpu.clearColor(argb); return this; }

    public Scene3DRenderer scene3dRenderer() { return gpu.scene3dRenderer(); }
    public boolean hasActiveAnimation(List<DisplayItem> items, long nowMs) { return gpu.hasActiveAnimation(items, nowMs); }
    public long gpuShapeCount() { return gpu.gpuShapeCount(); }
    public long rectTreeDrawCalls() { return gpu.rectTreeDrawCalls(); }
    public long rectTreeChangedWrites() { return gpu.rectTreeChangedWrites(); }
    public long rectTreeReplayedItems() { return gpu.rectTreeReplayedItems(); }
    public long rectTreeReplayedShapes() { return gpu.rectTreeReplayedShapes(); }
    public long rectTreeShapes() { return gpu.rectTreeShapes(); }
    public long[] rectTreeRejects() { return gpu.rectTreeRejects(); }
    public long gpuGlyphCount() { return gpu.gpuGlyphCount(); }
    public long gpuImageCount() { return gpu.gpuImageCount(); }
    public long drawCallCount() { return gpu.drawCallCount(); }
    public long cpuOverlayFlushCount() { return gpu.cpuOverlayFlushCount(); }
    public long readbackCount() { return gpu.readbackCount(); }
    public int retainedLayerCount() { return gpu.retainedLayerCount(); }
    public long scrollLayersSkipped() { return gpu.scrollLayersSkipped(); }
    public long scrollLayersRecorded() { return gpu.scrollLayersRecorded(); }
    int atlasSize() { return gpu.atlasSize(); }
    boolean cpuPixelsAllocated() { return gpu.cpuPixelsAllocated(); }
    boolean overlayTextureAllocated() { return gpu.overlayTextureAllocated(); }
    public GpuRenderBackend gpuBackend() { return gpu; }

    @Override public String sessionId() { return gpu.sessionId(); }
    @Override public float devicePixelRatio() { return gpu.devicePixelRatio(); }

    @Override
    public void render(List<DisplayItem> items, long nowMs) {
        syncFlags();
        gpu.render(items, nowMs);
    }

    @Override public Pixmap pixmap() { return gpu.pixmap(); }
    @Override public void close() { gpu.close(); }

    // ------------------------------------------------------------------ GpuSink 委譲

    @Override public Pixmap snapshotTarget() { return gpu.snapshotTarget(); }
    @Override public Pixmap snapshotTarget(int[] region) { return gpu.snapshotTarget(region); }
    @Override public void overwriteTarget(Pixmap pixmap, int[] region) { gpu.overwriteTarget(pixmap, region); }
    @Override public boolean beginGroup(float opacity, List<FilterOp> filters, double[] map, boolean masked, boolean maskLuminance) {
        return gpu.beginGroup(opacity, filters, map, masked, maskLuminance);
    }
    @Override public boolean beginGroup(float opacity, List<FilterOp> filters, double[] map, boolean masked, boolean maskLuminance, BlendMode blend) {
        return gpu.beginGroup(opacity, filters, map, masked, maskLuminance, blend);
    }
    @Override public boolean beginGroup(float opacity, List<FilterOp> filters, double[] map, boolean masked, boolean maskLuminance, BlendMode blend, GroupExtras extras) {
        return gpu.beginGroup(opacity, filters, map, masked, maskLuminance, blend, extras);
    }
    @Override public boolean beginMask() { return gpu.beginMask(); }
    @Override public void endMask() { gpu.endMask(); }
    @Override public void endGroup() { gpu.endGroup(); }
    @Override public boolean snapsScrollOffsets() { return gpu.snapsScrollOffsets(); }
    @Override public boolean spanReplay(int index, boolean cpuPending) { return gpu.spanReplay(index, cpuPending); }
    @Override public boolean spanBegin(int index) { return gpu.spanBegin(index); }
    @Override public void spanEnd(int index, boolean ok) { gpu.spanEnd(index, ok); }

    @Override public boolean fillRoundRect(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                           int argb, GpuClip[] clips, float opacity) {
        return gpu.fillRoundRect(pose, x0, y0, x1, y1, rx, ry, argb, clips, opacity);
    }
    @Override public boolean strokeRoundBorder(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                               float ix0, float iy0, float ix1, float iy1, float[] irx, float[] iry, boolean innerEmpty,
                                               int[] colors, float[] widths, GpuClip[] clips, float opacity) {
        return gpu.strokeRoundBorder(pose, x0, y0, x1, y1, rx, ry, ix0, iy0, ix1, iy1, irx, iry, innerEmpty, colors, widths, clips, opacity);
    }
    @Override public boolean strokeDashedBorder(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                                float ix0, float iy0, float ix1, float iy1, float[] irx, float[] iry, boolean innerEmpty,
                                                int[] colors, float[] widths, float bx, float by, float cxm, float cym, float[] pcx, float[] pcy,
                                                float period, float dash, boolean round, float thickness, GpuClip[] clips, float opacity) {
        return gpu.strokeDashedBorder(pose, x0, y0, x1, y1, rx, ry, ix0, iy0, ix1, iy1, irx, iry, innerEmpty, colors, widths,
                bx, by, cxm, cym, pcx, pcy, period, dash, round, thickness, clips, opacity);
    }
    @Override public boolean strokeCircleBorder(Matrix2D pose, float cx, float cy, float outerR, float innerR,
                                                float bx0, float by0, float bx1, float by1, int[] colors, GpuClip[] clips, float opacity) {
        return gpu.strokeCircleBorder(pose, cx, cy, outerR, innerR, bx0, by0, bx1, by1, colors, clips, opacity);
    }
    @Override public boolean fillGradient(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                          ShaderGradient g, GpuClip[] clips, float opacity) {
        return gpu.fillGradient(pose, x0, y0, x1, y1, rx, ry, g, clips, opacity);
    }
    @Override public boolean outerShadow(Matrix2D pose, float qx0, float qy0, float qx1, float qy1,
                                         float sx0, float sy0, float sx1, float sy1, float[] srx, float[] sry, float sigma,
                                         float[] hole, float[] hrx, float[] hry, int argb, GpuClip[] clips, float opacity) {
        return gpu.outerShadow(pose, qx0, qy0, qx1, qy1, sx0, sy0, sx1, sy1, srx, sry, sigma, hole, hrx, hry, argb, clips, opacity);
    }
    @Override public boolean insetShadow(Matrix2D pose, float qx0, float qy0, float qx1, float qy1, float[] rx, float[] ry,
                                         float sigma, float[] hole, float[] hrx, float[] hry, int argb, GpuClip[] clips, float opacity) {
        return gpu.insetShadow(pose, qx0, qy0, qx1, qy1, rx, ry, sigma, hole, hrx, hry, argb, clips, opacity);
    }
    @Override public boolean drawGlyph(GlyphBitmap g, int bx, int by, int argb, GpuClip[] clips, float opacity) {
        return gpu.drawGlyph(g, bx, by, argb, clips, opacity);
    }
    @Override public boolean drawGlyphStroke(GlyphBitmap sdf, int bx, int by, float half, int argb, GpuClip[] clips, float opacity) {
        return gpu.drawGlyphStroke(sdf, bx, by, half, argb, clips, opacity);
    }
    @Override public boolean drawGlyphTransformed(Matrix2D pose, GlyphBitmap g, float ox, float oy, float s,
                                                  float bx0, float by0, float bx1, float by1, int argb, GpuClip[] clips, float opacity) {
        return gpu.drawGlyphTransformed(pose, g, ox, oy, s, bx0, by0, bx1, by1, argb, clips, opacity);
    }
    @Override public boolean drawImage(Matrix2D pose, Pixmap image, float bx0, float by0, float bx1, float by1,
                                       float px0, float py0, float sx, float sy,
                                       float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                       int taps, float stepX, float stepY, GpuClip[] clips, float opacity) {
        return gpu.drawImage(pose, image, bx0, by0, bx1, by1, px0, py0, sx, sy, x0, y0, x1, y1, rx, ry, taps, stepX, stepY, clips, opacity);
    }
    @Override public RetainPlan planScrollLayer(GpuScrollLayer l) { return gpu.planScrollLayer(l); }
    @Override public RetainPlan planScrollLayerBounds(String key, Object token, Matrix2D pose, int x0, int y0, int x1, int y1) {
        return gpu.planScrollLayerBounds(key, token, pose, x0, y0, x1, y1);
    }
    @Override public void beginScrollLayer(GpuScrollLayer l) { gpu.beginScrollLayer(l); }
    @Override public boolean endScrollLayer(GpuScrollLayer l) { return gpu.endScrollLayer(l); }
    @Override public void drawScrollLayer(GpuScrollLayer l) { gpu.drawScrollLayer(l); }
    @Override public boolean drawTexture(Matrix2D pose, long handle, int imgW, int imgH, float bx0, float by0, float bx1, float by1,
                                         float px0, float py0, float sx, float sy,
                                         float x0, float y0, float x1, float y1, float[] rx, float[] ry, GpuClip[] clips, float opacity) {
        return gpu.drawTexture(pose, handle, imgW, imgH, bx0, by0, bx1, by1, px0, py0, sx, sy, x0, y0, x1, y1, rx, ry, clips, opacity);
    }
    @Override public boolean drawScene3D(Matrix2D pose, Scene3DWorld world, int fboW, int fboH,
                                         float x0, float y0, float x1, float y1,
                                         float[] rx, float[] ry, GpuClip[] clips, float opacity) {
        return gpu.drawScene3D(pose, world, fboW, fboH, x0, y0, x1, y1, rx, ry, clips, opacity);
    }
    @Override public Pixmap renderScene3DPixmap(Scene3DWorld world, int fboW, int fboH) {
        return gpu.renderScene3DPixmap(world, fboW, fboH);
    }
}
