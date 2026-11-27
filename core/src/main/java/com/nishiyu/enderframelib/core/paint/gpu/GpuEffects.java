package com.nishiyu.enderframelib.core.paint.gpu;

import com.nishiyu.enderframelib.core.paint.port.GpuLayerSink.GroupExtras;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.LoadOp;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuBindings;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuPass;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuTexture;
import com.nishiyu.enderframelib.core.kernel.gpu.shader.ShaderPrograms;
import com.nishiyu.enderframelib.core.paint.model.BlendMode;
import com.nishiyu.enderframelib.core.paint.model.ClipShape;
import com.nishiyu.enderframelib.core.paint.model.FilterOp;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** フィルタの効果の段（{@code filter} の各段・ぼかし〔縮小つき〕・{@code backdrop-filter}）をオフスクリーンの層へ掛け、グループを閉じて親へ合成する。状態（層・パス・画面の大きさ）は {@link GpuRenderBackend} が持つ。A8 で {@code GpuRenderBackend} から分けた。 */
final class GpuEffects {

    private final GpuRenderBackend gpu;

    GpuEffects(GpuRenderBackend gpu) {
        this.gpu = gpu;
    }

    private int[] glRectToPassScissor(int[] r) {
        return new int[]{r[0], gpu.height - r[3], r[2] - r[0], r[3] - r[1]};
    }

    private static int[] toPassScissor(int[] r) {
        return r == null ? null : new int[]{r[0], r[1], r[2] - r[0], r[3] - r[1]};
    }

    private static int[] clampTo(int[] r, int w, int h) {
        int x0 = Math.max(0, r[0]), y0 = Math.max(0, r[1]), x1 = Math.min(w, r[2]), y1 = Math.min(h, r[3]);
        return x1 > x0 && y1 > y0 ? new int[]{x0, y0, x1, y1} : null;
    }

    private int[] clampInts(int[] r) { return clampTo(r, gpu.width, gpu.height); }

    private int[] clampToScreen(float[] r) {
        int x0 = Math.max(0, (int) Math.floor(r[0])), y0 = Math.max(0, (int) Math.floor(r[1]));
        int x1 = Math.min(gpu.width, (int) Math.ceil(r[2])), y1 = Math.min(gpu.height, (int) Math.ceil(r[3]));
        return x1 > x0 && y1 > y0 ? new int[]{x0, y0, x1, y1} : null;
    }

    private void touchUnbounded() {
        if (!gpu.layerStack.isEmpty()) gpu.layerStack.get(gpu.layerStack.size() - 1).unbounded = true;
    }

    /** {@link #endGroup} の作業用（途中の層）。入れ子にならない（閉じるのは 1 つずつ）。 */
    private final List<GpuTexture> groupTemps = new ArrayList<>();

    private final float[] fxClipD = new float[GpuRenderBackend.MAX_CLIP_VEC * 4];

    private final float[] fxBD = new float[12];

    private int fxClipKind, fxEvenOdd, fxClipN, fxHasB;

    private float fxInvA, fxInvB, fxInvC, fxInvD, fxInvE, fxInvF, fxShapeScale;

    private int fxMaskMode, fxBlend;

    private float fxShadowOffX, fxShadowOffY, fxShadowR, fxShadowG, fxShadowB, fxShadowA;

    private float fxUvScaleX, fxUvScaleY, fxUvMaxX, fxUvMaxY;

    private int fxTaps, fxK;

    private float fxDirX, fxDirY, fxSigma, fxStep, fxTexelX, fxTexelY;

    private final float[] fxMat = new float[12];

    private int fxHasMatrix;

    private float fxOpacity;

    private int fxMode;

    private float fxViewW, fxViewH;

    // FX uniforms 作業状態（29 個の順を ShaderPrograms.FX と一致させる）
    private float fxDevX0, fxDevY0, fxDevX1, fxDevY1;

    private final ByteBuffer fxUniforms = ByteBuffer.allocateDirect(ShaderPrograms.FX.uniformBytes())
            .order(ByteOrder.nativeOrder());

    private final GpuTexture[] fxSamplers = new GpuTexture[3];

    private static final int[] OFFSCREEN = new int[0];

    private static List<GpuRenderBackend.Stage> stages(List<FilterOp> ops) {
        List<GpuRenderBackend.Stage> out = new ArrayList<>();
        for (FilterOp op : ops) {
            switch (op.kind()) {
                case COLOR_MATRIX, OPACITY -> {
                    GpuRenderBackend.Stage st = out.isEmpty() || out.get(out.size() - 1).kind != GpuRenderBackend.StageKind.COLOR ? null : out.get(out.size() - 1);
                    if (st == null) {
                        st = new GpuRenderBackend.Stage();
                        st.kind = GpuRenderBackend.StageKind.COLOR;
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
                    GpuRenderBackend.Stage st = new GpuRenderBackend.Stage();
                    st.kind = GpuRenderBackend.StageKind.BLUR;
                    st.sigma = op.amount();
                    out.add(st);
                }
                case DROP_SHADOW -> {
                    GpuRenderBackend.Stage st = new GpuRenderBackend.Stage();
                    st.kind = GpuRenderBackend.StageKind.DROP;
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

    void endGroup(GpuRenderBackend.Entry e) {
        List<GpuTexture> temps = groupTemps;
        temps.clear();
        GpuTexture cur = e.layer;
        List<GpuRenderBackend.Stage> stages = e.filters.isEmpty() ? List.of() : stages(e.filters);
        float[] finalMatrix = null;
        float finalOpacity = e.opacity;
        if (!stages.isEmpty() && stages.get(stages.size() - 1).kind == GpuRenderBackend.StageKind.COLOR) {
            GpuRenderBackend.Stage last = stages.remove(stages.size() - 1);
            finalMatrix = last.matrix;
            finalOpacity *= last.opacity;
        }
        boolean bounded = com.nishiyu.enderframelib.core.kernel.Shortcut.GL_BOUNDED_LAYERS.on() && !e.unbounded && (e.extras == null || e.extras.clip() == null) && stages.size() <= 1;
        if (!bounded || e.mask != null) gpu.ensureCleared(e);
        int[] region = bounded ? clampToScreen(e.bounds) : null;
        boolean hasBackdrop = e.extras != null && e.extras.backdropShape() != null && !e.extras.backdrop().isEmpty();
        if (bounded && region == null && !hasBackdrop) {
            gpu.layerPool.addFirst(e.layer);
            if (e.mask != null) gpu.layerPool.addFirst(e.mask);
            return;
        }
        if (hasBackdrop) applyBackdropFilter(e, temps);
        if (bounded && region == null) {
            gpu.layerPool.addFirst(e.layer);
            if (e.mask != null) gpu.layerPool.addFirst(e.mask);
            for (GpuTexture t : temps) gpu.layerPool.addFirst(t);
            return;
        }
        for (GpuRenderBackend.Stage st : stages) {
            GpuTexture next = runStage(st, cur, e.map, temps, region);
            if (next == null) break;
            cur = next;
        }
        if (e.extras != null && e.extras.clip() != null) {
            GpuTexture out = gpu.acquireLayer();
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
            if (!gpu.layerStack.isEmpty()) gpu.ensureCleared(gpu.layerStack.get(gpu.layerStack.size() - 1));
            resetFx(4);
            fxOpacity = finalOpacity;
            fxMaskMode = e.mask == null ? 0 : (e.luminance ? 2 : 1);
            fxBlend = e.blend.ordinal();
            setMatrix(finalMatrix);
            drawFx(gpu.currentTarget(), false, cur, e.mask, backdrop, scissor);
            if (region != null) gpu.touchAndEnsureCleared(region[0], region[1], region[2], region[3]);
            else gpu.touchAndEnsureCleared(0, 0, gpu.width, gpu.height);
            gpu.layerPool.addFirst(e.layer);
            if (e.mask != null) gpu.layerPool.addFirst(e.mask);
            for (GpuTexture t : temps) gpu.layerPool.addFirst(t);
            return;
        }
        if (!gpu.layerStack.isEmpty()) gpu.ensureCleared(gpu.layerStack.get(gpu.layerStack.size() - 1));
        resetFx(1);
        fxOpacity = finalOpacity;
        fxMaskMode = e.mask == null ? 0 : (e.luminance ? 2 : 1);
        setMatrix(finalMatrix);
        drawFx(gpu.currentTarget(), true, cur, e.mask, null, scissor);
        if (region != null) gpu.touchAndEnsureCleared(region[0], region[1], region[2], region[3]);
        else if (e.unbounded) touchUnbounded();
        else gpu.touchAndEnsureCleared(0, 0, gpu.width, gpu.height);

        gpu.layerPool.addFirst(e.layer);
        if (e.mask != null) gpu.layerPool.addFirst(e.mask);
        for (GpuTexture t : temps) gpu.layerPool.addFirst(t);
    }

    private void applyBackdropFilter(GpuRenderBackend.Entry e, List<GpuTexture> temps) {
        List<GpuRenderBackend.Stage> bstages = stages(e.extras.backdrop());
        int[] shape = com.nishiyu.enderframelib.core.kernel.Shortcut.GL_BOUNDED_LAYERS.on() && bstages.size() == 1 ? backdropDeviceBounds(e) : null;
        if (shape == OFFSCREEN) return;
        int[] copyRegion = null;
        if (shape != null) {
            int margin = 0;
            GpuRenderBackend.Stage st = bstages.get(0);
            if (st.kind != GpuRenderBackend.StageKind.COLOR) {
                float sx = st.sigma * (float) Math.hypot(e.map[0], e.map[1]);
                float sy = st.sigma * (float) Math.hypot(e.map[2], e.map[3]);
                margin = (int) Math.ceil(3.5f * Math.max(sx, sy)) + 4;
            }
            copyRegion = clampInts(new int[]{shape[0] - margin, shape[1] - margin, shape[2] + margin, shape[3] + margin});
        }
        GpuTexture orig = copyBackdrop(copyRegion);
        if (orig == null) return;
        if (shape != null) gpu.touchAndEnsureCleared(shape[0], shape[1], shape[2], shape[3]);
        else touchUnbounded();
        temps.add(orig);
        GpuTexture filtered = orig;
        for (GpuRenderBackend.Stage st : bstages) {
            int[] out = shape == null ? null : shape.clone();
            GpuTexture next = st.kind == GpuRenderBackend.StageKind.BLUR ? blur(st.sigma, filtered, e.map, temps, out, false)
                    : runStage(st, filtered, e.map, temps, out);
            if (next == null) break;
            filtered = next;
        }
        resetFx(6);
        fxOpacity = e.opacity;
        setShapes(e.extras, true);
        drawFx(gpu.currentTarget(), false, filtered, null, orig, toPassScissor(shape));
    }

    private int[] backdropDeviceBounds(GpuRenderBackend.Entry e) {
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
        GpuTexture bd = gpu.acquireLayer();
        if (bd == null) return null;
        if (!gpu.layerStack.isEmpty()) gpu.ensureCleared(gpu.layerStack.get(gpu.layerStack.size() - 1));
        gpu.endPass();
        int[] copyRect = region == null ? new int[]{0, 0, gpu.width, gpu.height}
                : new int[]{region[0], region[1], region[2] - region[0], region[3] - region[1]};
        gpu.activeFrame.copy(gpu.currentTarget(), bd, copyRect);
        return bd;
    }

    private GpuTexture runStage(GpuRenderBackend.Stage st, GpuTexture in, double[] m, List<GpuTexture> temps, int[] region) {
        switch (st.kind) {
            case COLOR -> {
                GpuTexture out = gpu.acquireLayer();
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
                GpuTexture out = gpu.acquireLayer();
                if (out == null) return null;
                temps.add(out);
                float ox = (float) (m[0] * st.dx + m[2] * st.dy);
                float oy = (float) (m[1] * st.dx + m[3] * st.dy);
                resetFx(3);
                fxShadowOffX = ox / gpu.width;
                fxShadowOffY = gpu.device.caps().bottomUpFramebuffer() ? -oy / gpu.height : oy / gpu.height;
                fxShadowR = ((st.color >>> 16) & 0xFF) / 255f;
                fxShadowG = ((st.color >>> 8) & 0xFF) / 255f;
                fxShadowB = (st.color & 0xFF) / 255f;
                fxShadowA = ((st.color >>> 24) & 0xFF) / 255f;
                int[] sc = null;
                if (region != null) {
                    int sx0 = Math.max(0, Math.min(region[0] + Math.round(ox), inRegion[0]));
                    int sy0 = Math.max(0, Math.min(region[1] + Math.round(oy), inRegion[1]));
                    int sx1 = Math.min(gpu.width, Math.max(region[2] + Math.round(ox), inRegion[2]));
                    int sy1 = Math.min(gpu.height, Math.max(region[3] + Math.round(oy), inRegion[3]));
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
        if (region != null && com.nishiyu.enderframelib.core.kernel.Shortcut.GL_DOWNSAMPLED_BLUR.on()) {
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
            GpuTexture out = gpu.acquireLayer();
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
        int sw = (gpu.width + k - 1) / k, sh = (gpu.height + k - 1) / k;
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
        boolean flipY = gpu.device.caps().bottomUpFramebuffer();
        int[] g = flipY
                ? new int[]{region[0], gpu.height - region[3], region[2], gpu.height - region[1]}
                : new int[]{region[0], region[1], region[2], region[3]};
        int[] content = {g[0] / k, g[1] / k, (g[2] + k - 1) / k, (g[3] + k - 1) / k};
        int ex = expand ? reach[0] : 1, ey = expand ? reach[1] : 1;
        int[] r2 = clampTo(new int[]{content[0] - ex, content[1] - ey, content[2] + ex, content[3] + ey}, sw, sh);
        if (r2 == null) return in;
        int[] r1 = clampTo(new int[]{r2[0], r2[1] - reach[1], r2[2], r2[3] + reach[1]}, sw, sh);
        int[] dsr = r1 == null ? null : clampTo(new int[]{r1[0] - reach[0], r1[1], r1[2] + reach[0], r1[3]}, sw, sh);
        if (dsr == null) return in;

        GpuTexture a = gpu.acquireLayer(), b = a == null ? null : gpu.acquireLayer(), c = b == null ? null : gpu.acquireLayer();
        GpuTexture out = c == null ? null : gpu.acquireLayer();
        if (out == null) {
            for (GpuTexture l : new GpuTexture[]{a, b, c}) if (l != null) gpu.layerPool.addFirst(l);
            return null;
        }
        temps.add(a); temps.add(b); temps.add(c); temps.add(out);

        // API 固有のテクスチャ原点側の [0..sw, 0..sh] 領域へ縮小して描く
        float dsY0 = flipY ? gpu.height - sh : 0;
        float dsY1 = flipY ? gpu.height : sh;
        resetFx(7);
        fxDevX0 = 0; fxDevY0 = dsY0; fxDevX1 = sw; fxDevY1 = dsY1;
        fxViewW = gpu.width; fxViewH = gpu.height;
        fxK = k;
        fxUvScaleX = (float) sw / gpu.width; fxUvScaleY = (float) sh / gpu.height;
        fxUvMaxX = (sw - 0.5f) / gpu.width; fxUvMaxY = (sh - 0.5f) / gpu.height;
        drawFx(a, false, in, null, null, flipY ? glRectToPassScissor(dsr) : toPassScissor(dsr));

        GpuTexture cur = a;
        for (int axis = 0; axis < 2; axis++) {
            if (sg[axis] < 0.05f) continue;
            GpuTexture dst = axis == 0 ? b : c;
            resetFx(2);
            fxDevX0 = 0; fxDevY0 = dsY0; fxDevX1 = sw; fxDevY1 = dsY1;
            fxViewW = gpu.width; fxViewH = gpu.height;
            fxUvScaleX = 1f; fxUvScaleY = 1f;
            fxUvMaxX = (sw - 0.5f) / gpu.width; fxUvMaxY = (sh - 0.5f) / gpu.height;
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
                (r2[2] < sw ? r2[2] - 1 : sw) * k, (r2[3] < sh ? r2[3] - 1 : sh) * k}, gpu.width, gpu.height);
        if (up == null) return in;
        resetFx(8);
        fxUvScaleX = (float) sw / gpu.width; fxUvScaleY = (float) sh / gpu.height;
        fxUvMaxX = (sw - 0.5f) / gpu.width; fxUvMaxY = (sh - 0.5f) / gpu.height;
        drawFx(out, false, cur, null, null, flipY ? glRectToPassScissor(up) : toPassScissor(up));
        if (expand) {
            region[0] = up[0];
            region[1] = flipY ? gpu.height - up[3] : up[1];
            region[2] = up[2];
            region[3] = flipY ? gpu.height - up[1] : up[3];
        }
        return out;
    }

    private void resetFx(int mode) {
        fxDevX0 = 0; fxDevY0 = 0; fxDevX1 = gpu.width; fxDevY1 = gpu.height;
        fxViewW = gpu.width; fxViewH = gpu.height;
        fxMode = mode;
        fxOpacity = 1f;
        fxHasMatrix = 0;
        fxDirX = 0f; fxDirY = 0f; fxSigma = 0f; fxTaps = 0; fxStep = 0f;
        fxTexelX = 1f / gpu.width; fxTexelY = 1f / gpu.height;
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
        gpu.endPass();
        fxUniforms.clear();
        fxUniforms.putFloat(fxDevX0).putFloat(fxDevY0).putFloat(fxDevX1).putFloat(fxDevY1);
        fxUniforms.putFloat(fxViewW).putFloat(fxViewH);
        fxUniforms.putFloat(fxDirX).putFloat(fxDirY);
        for (int i = 0; i < 12; i++) fxUniforms.putFloat(fxMat[i]);
        fxUniforms.putFloat(fxShadowR).putFloat(fxShadowG).putFloat(fxShadowB).putFloat(fxShadowA);
        fxUniforms.putFloat(fxInvA).putFloat(fxInvB).putFloat(fxInvC).putFloat(fxInvD);
        for (int i = 0; i < 12; i++) fxUniforms.putFloat(fxBD[i]);
        for (int i = 0; i < GpuRenderBackend.MAX_CLIP_VEC * 4; i++) fxUniforms.putFloat(fxClipD[i]);
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

        fxSamplers[0] = src != null ? src : gpu.dummyTex;
        fxSamplers[1] = aux != null ? aux : gpu.dummyTex;
        fxSamplers[2] = backdrop != null ? backdrop : gpu.dummyTex;

        GpuPass pass = gpu.activeFrame.beginPass(dst, LoadOp.LOAD, 0, scissor);
        pass.bind(blend ? gpu.fxBlendPipeline : gpu.fxCopyPipeline,
                new GpuBindings(GpuRenderBackend.NO_BUFFERS, fxSamplers, fxUniforms));
        pass.drawInstanced(4, 1, 0);
        pass.end();
    }
}
