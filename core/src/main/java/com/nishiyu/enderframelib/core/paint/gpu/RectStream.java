package com.nishiyu.enderframelib.core.paint.gpu;

import com.nishiyu.enderframelib.core.paint.port.GpuClip;
import com.nishiyu.enderframelib.core.kernel.EngineFlags;
import com.nishiyu.enderframelib.core.kernel.gpu.PropertyTrees;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuDevice;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuPass;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.GpuTexture;
import com.nishiyu.enderframelib.core.paint.model.ShaderGradient;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import java.util.Arrays;

/**
 * バックエンドが受け取る角丸矩形・枠線・影・グラデーション・字形・画像を、{@link GpuTreeStore} の
 * プリミティブとして RHI 経由で GPU へ流す API 非依存ストリーム。
 */
public final class RectStream implements AutoCloseable {
    private static final float GRID_EPS = 1e-3f;
    private static final int CLIP_CAP = 1024, KEY = 20;
    private static final float[] ZERO4 = new float[4];

    /** 診断・切り分け用: 新経路に載せる図形の種類（ビット 0〜11）。 */
    public static volatile int opMask = 0xFFF;

    private final GpuTreeStore store;
    private final RectRenderer renderer;
    private final PropertyTrees trees;
    private final int capacity, usableCapacity, deadSlot, clipCapacity;

    private final float[] clipKeys;
    private final int[] clipTable;
    private final int[] clipSlotVersion;
    private int clipVerNext = 1, clipHighWater;
    private int clipCount;

    private int slots;
    private int count;
    private int runStart;
    private long accepted;
    private long drawCalls;

    public final long[] rejects = new long[4];
    public long clipRejects;
    public long overflows;

    // 項目単位の保持（再生）
    private long frameNo;
    private int[] spSlot0 = new int[0], spSlot1, spCount0, spCount1, spClip0, spClip1, spAtlas;
    private long[] spFrame;
    private int[] spGlyphs;
    private int[][] spRefs;
    private int[][] spRefVers;
    private boolean spanOn, spanUnclean;
    private int spanIndex, spanSlot0, spanCount0, spanClip0, spanAtlas;
    private long spanOverflows;
    private int[] spanRefs = new int[16];
    private int spanRefCount;
    public long replayedShapes, replayedItems;
    public int replayedGlyphs;

    // 差分インプレース上書き
    private int prevFrameSlots, prevFrameCount, prevFrameClipCount;
    private boolean prevFrameValid;
    private boolean patchMode, patchSpanOn, patchOverflow;
    private int patchSlotCursor, patchSlotLimit, patchOrderCursor, patchOrderLimit, patchSpanIndex, patchOldShapes;
    private int[] patchDoneIndices = new int[32];
    private int patchDoneCount;

    private final float[] trs = new float[4];
    private GpuClip[] lastClips;
    private int lastA, lastB, lastC, lastD;

    public RectStream(GpuDevice device, int requestedCapacity) {
        // 1 プリミティブ = 128 バイト（R16F で 64 テクセル）。maxBufferBytes は R16F 基準の上限バイト数。
        int maxPrimitives = (int) Math.min(Integer.MAX_VALUE, Math.max(16L, device.maxBufferBytes() / 128L));
        this.capacity = Math.max(16, Math.min(requestedCapacity, maxPrimitives));
        this.usableCapacity = this.capacity - 1;
        this.deadSlot = this.usableCapacity;
        this.clipCapacity = Math.min(CLIP_CAP, Math.max(4, maxPrimitives));
        this.store = new GpuTreeStore(device, capacity, clipCapacity, capacity, capacity);
        this.clipKeys = new float[clipCapacity * KEY];
        this.clipTable = new int[Integer.highestOneBit(clipCapacity * 2 - 1) << 1];
        this.clipSlotVersion = new int[clipCapacity];
        this.trees = store.trees;
        this.renderer = new RectRenderer(device);

        for (int k = 0; k < clipCapacity; k++) trees.clips.allocate();
        for (int k = 0; k < capacity; k++) {
            trees.addTransform(PropertyTrees.NONE, 0, 0, 1, 1, 0);
            trees.addEffect(PropertyTrees.NONE, 1f, 0, 0, 0f);
            trees.addPrimitive();
        }
        trees.setPrimitive(deadSlot, 0, PropertyTrees.NONE, 0, PropertyTrees.NONE, 0f, 0f, 0f, 0f, 0, 0);
        store.flush();
    }

    public boolean isPersistent() { return store.isPersistent(); }
    public int capacity() { return capacity; }
    public int pending() { return count - runStart; }
    public long accepted() { return accepted; }
    public long drawCalls() { return drawCalls; }
    public int frameCount() { return count; }
    public GpuTreeStore store() { return store; }

    private void ensureSpan(int index) {
        if (index < spSlot0.length) return;
        int n = Math.max(index + 1, spSlot0.length * 2 + 64);
        spSlot0 = Arrays.copyOf(spSlot0, n);
        spSlot1 = spSlot1 == null ? new int[n] : Arrays.copyOf(spSlot1, n);
        spCount0 = spCount0 == null ? new int[n] : Arrays.copyOf(spCount0, n);
        spCount1 = spCount1 == null ? new int[n] : Arrays.copyOf(spCount1, n);
        spClip0 = spClip0 == null ? new int[n] : Arrays.copyOf(spClip0, n);
        spClip1 = spClip1 == null ? new int[n] : Arrays.copyOf(spClip1, n);
        spAtlas = spAtlas == null ? new int[n] : Arrays.copyOf(spAtlas, n);
        spFrame = spFrame == null ? new long[n] : Arrays.copyOf(spFrame, n);
        spGlyphs = spGlyphs == null ? new int[n] : Arrays.copyOf(spGlyphs, n);
        spRefs = spRefs == null ? new int[n][] : Arrays.copyOf(spRefs, n);
        spRefVers = spRefVers == null ? new int[n][] : Arrays.copyOf(spRefVers, n);
    }

    public void spanBegin(int index, int atlasResets) {
        spanOn = true;
        spanUnclean = false;
        spanIndex = index;
        spanSlot0 = slots;
        spanCount0 = count;
        spanClip0 = clipCount;
        spanAtlas = atlasResets;
        spanOverflows = overflows;
        spanRefCount = 0;
        lastClips = null;
    }

    private void spanRef(int id) {
        for (int i = 0; i < spanRefCount; i++) if (spanRefs[i] == id) return;
        if (spanRefCount == spanRefs.length) spanRefs = Arrays.copyOf(spanRefs, spanRefCount * 2);
        spanRefs[spanRefCount++] = id;
    }

    public void spanEnd(boolean ok, int atlasResets, int glyphs) {
        if (!spanOn) return;
        spanOn = false;
        int index = spanIndex;
        ensureSpan(index);
        if (verifyIndex == index) spanVerifyCheck(index, ok && !spanUnclean && overflows == spanOverflows && atlasResets == spanAtlas); // 記録を書き換える前に、前フレームの出力と比べる
        if (!ok || spanUnclean || overflows != spanOverflows || atlasResets != spanAtlas || slots - spanSlot0 > 64) {
            spFrame[index] = -1;
            return;
        }
        spSlot0[index] = spanSlot0; spSlot1[index] = slots;
        spCount0[index] = spanCount0; spCount1[index] = count;
        spClip0[index] = spanClip0; spClip1[index] = clipCount;
        spAtlas[index] = atlasResets;
        spGlyphs[index] = glyphs;
        int[] refs = spRefs[index];
        int[] vers = spRefVers[index];
        if (refs == null || refs.length != spanRefCount) {
            refs = new int[spanRefCount];
            vers = new int[spanRefCount];
            spRefs[index] = refs;
            spRefVers[index] = vers;
        }
        System.arraycopy(spanRefs, 0, refs, 0, spanRefCount);
        for (int i = 0; i < spanRefCount; i++) vers[i] = clipSlotVersion[refs[i]];
        spFrame[index] = frameNo;
    }

    // 検証（VERIFY_MEMO）: 再生できる項目を再生せずに描き直させ、描き直した出力が前フレームに残っている出力と同じか比べる
    /** 検証で照合した項目の数（診断・テスト用）。 */
    public static final java.util.concurrent.atomic.LongAdder STAT_VERIFIED_SPANS = new java.util.concurrent.atomic.LongAdder();
    private int verifyIndex = -1;
    private byte[] vTransforms, vEffects, vPrimitives, vClips;
    private int[] vOrder;

    /**
     * 検証モード: 項目 {@code index} が再生できるときに、前フレームの出力（影）を写して true を返す。呼び出し側は再生せず、
     * 通常どおり描き直す。{@link #spanEnd} が描き直した出力と比べる。再生できないなら false。
     */
    public boolean spanVerifyArm(int index, int atlasResets) {
        if (!spanCanReplay(index, atlasResets)) return false;
        int s0 = spSlot0[index], s1 = spSlot1[index], c0 = spCount0[index], c1 = spCount1[index];
        vTransforms = trees.transforms.copyShadow(s0, s1);
        vEffects = trees.effects.copyShadow(s0, s1);
        vPrimitives = trees.primitives.copyShadow(s0, s1);
        vClips = trees.clips.copyShadow(spClip0[index], spClip1[index]);
        vOrder = new int[c1 - c0];
        for (int i = 0; i < vOrder.length; i++) vOrder[i] = trees.order.getInt(c0 + i, 0);
        verifyIndex = index;
        return true;
    }

    private void spanVerifyCheck(int index, boolean ok) {
        if (verifyIndex != index) return;
        verifyIndex = -1;
        int s0 = spSlot0[index], s1 = spSlot1[index], c0 = spCount0[index], c1 = spCount1[index];
        String why = null;
        if (!ok) why = "描き直した出力が記録できなかった（再生は記録できる前提だった）";
        else if (s0 != spanSlot0 || slots != s1 || count != c1 || clipCount != spClip1[index]) why = "描き直した出力の長さが違う（slot " + (slots - spanSlot0) + " ≠ " + (s1 - s0) + "）";
        else {
            int d;
            if ((d = trees.transforms.firstShadowDifference(s0, vTransforms)) >= 0) why = "変形の slot " + d;
            else if ((d = trees.effects.firstShadowDifference(s0, vEffects)) >= 0) why = "効果の slot " + d;
            else if ((d = trees.primitives.firstShadowDifference(s0, vPrimitives)) >= 0) why = "図形の slot " + d;
            else if ((d = trees.clips.firstShadowDifference(spClip0[index], vClips)) >= 0) why = "クリップの slot " + d;
            else for (int i = 0; i < vOrder.length && why == null; i++) if (trees.order.getInt(c0 + i, 0) != vOrder[i]) why = "描画順 " + (c0 + i);
        }
        if (why != null) throw new AssertionError("項目の再生が古い: 項目 " + index + " " + why);
        STAT_VERIFIED_SPANS.increment();
    }

    public void spanDrop(int index) {
        spanOn = false;
        if (verifyIndex == index) verifyIndex = -1;
        if (index < spFrame.length) spFrame[index] = -1;
    }

    public boolean spanCanReplay(int index, int atlasResets) {
        if (index >= spSlot0.length || spFrame[index] != frameNo - 1) return false;
        if (slots != spSlot0[index] || count != spCount0[index] || clipCount != spClip0[index] || atlasResets != spAtlas[index]) return false;
        int[] refs = spRefs[index];
        int[] vers = spRefVers[index];
        int c0 = spClip0[index], c1 = spClip1[index];
        for (int i = 0; i < refs.length; i++) {
            int r = refs[i];
            if (r >= clipCapacity || clipSlotVersion[r] != vers[i]) return false;
            if (r >= clipCount && (r < c0 || r >= c1)) return false;
        }
        return true;
    }

    public void spanApply(int index) {
        int c0 = spClip0[index], c1 = spClip1[index];
        for (int slot = c0; slot < c1; slot++) insertClipSlot(slot);
        clipCount = c1;
        replayedShapes += spCount1[index] - spCount0[index];
        accepted += spCount1[index] - spCount0[index];
        replayedItems++;
        replayedGlyphs = spGlyphs[index];
        slots = spSlot1[index];
        count = spCount1[index];
        spFrame[index] = frameNo;
        lastClips = null;
    }

    private void insertClipSlot(int slot) {
        float[] key = clipKeys;
        int o = slot * KEY;
        int h = 17;
        h = h * 31 + Float.floatToIntBits(key[o]); h = h * 31 + Float.floatToIntBits(key[o + 1]); h = h * 31 + Float.floatToIntBits(key[o + 2]);
        h = h * 31 + Float.floatToIntBits(key[o + 3]); h = h * 31 + Float.floatToIntBits(key[o + 4]); h = h * 31 + Float.floatToIntBits(key[o + 5]);
        h = h * 31 + Float.floatToIntBits(key[o + 7]) + 7 * Float.floatToIntBits(key[o + 8]) + 13 * Float.floatToIntBits(key[o + 9]) + 29 * Float.floatToIntBits(key[o + 10]);
        h ^= h >>> 15;
        int mask = clipTable.length - 1;
        int i = h & mask;
        while (clipTable[i] >= 0) i = (i + 1) & mask;
        clipTable[i] = slot;
    }

    public void begin() {
        verifyIndex = -1; // 検証の武装は 1 フレーム限り（描き直さずに終わった項目の写しを次のフレームへ持ち越さない）
        prevFrameSlots = slots;
        prevFrameCount = count;
        prevFrameClipCount = clipCount;
        prevFrameValid = count > 0;
        slots = 0;
        count = 0;
        runStart = 0;
        clipCount = 0;
        lastClips = null;
        patchMode = false;
        patchSpanOn = false;
        patchOverflow = false;
        frameNo++;
        Arrays.fill(clipTable, -1);
    }

    public boolean beginDiffPatch() {
        if (!prevFrameValid || prevFrameSlots > usableCapacity || count != 0 || slots != 0 || patchMode) return false;
        slots = prevFrameSlots;
        count = prevFrameCount;
        clipCount = prevFrameClipCount;
        runStart = 0;
        lastClips = null;
        for (int s = 0; s < clipCount; s++) insertClipSlot(s);
        frameNo--;
        patchMode = true;
        patchSpanOn = false;
        patchOverflow = false;
        patchOldShapes = 0;
        patchDoneCount = 0;
        return true;
    }

    public boolean canPatchSpan(int index, int atlasResets) {
        if (!patchMode || index < 0 || index >= spSlot0.length || spFrame[index] != frameNo) return false;
        if (spSlot1[index] <= spSlot0[index] || spCount1[index] <= spCount0[index]) return false;
        return atlasResets == spAtlas[index];
    }

    public boolean patchSpanBegin(int index, int atlasResets) {
        if (!canPatchSpan(index, atlasResets)) return false;
        patchSpanOn = true;
        patchOverflow = false;
        patchSpanIndex = index;
        patchSlotCursor = spSlot0[index];
        patchSlotLimit = spSlot1[index];
        patchOrderCursor = spCount0[index];
        patchOrderLimit = spCount1[index];
        spanOn = true;
        spanUnclean = false;
        spanIndex = index;
        spanAtlas = atlasResets;
        spanOverflows = overflows;
        spanRefCount = 0;
        lastClips = null;
        return true;
    }

    public boolean patchSpanEnd(int index, boolean ok, int atlasResets, int glyphs) {
        if (!patchSpanOn || patchSpanIndex != index) return false;
        patchSpanOn = false;
        spanOn = false;
        if (!ok || patchOverflow || spanUnclean || overflows != spanOverflows || atlasResets != spanAtlas) {
            return false;
        }
        for (int p = patchOrderCursor; p < patchOrderLimit; p++) {
            trees.order.putInt(p, 0, deadSlot);
        }
        patchOldShapes += patchOrderLimit - spCount0[index];
        spAtlas[index] = atlasResets;
        spGlyphs[index] = glyphs;
        int[] refs = spRefs[index];
        int[] vers = spRefVers[index];
        if (refs == null || refs.length != spanRefCount) {
            refs = new int[spanRefCount];
            vers = new int[spanRefCount];
            spRefs[index] = refs;
            spRefVers[index] = vers;
        }
        System.arraycopy(spanRefs, 0, refs, 0, spanRefCount);
        for (int i = 0; i < spanRefCount; i++) vers[i] = clipSlotVersion[refs[i]];
        spFrame[index] = frameNo;
        if (patchDoneCount == patchDoneIndices.length) {
            patchDoneIndices = Arrays.copyOf(patchDoneIndices, patchDoneCount * 2);
        }
        patchDoneIndices[patchDoneCount++] = index;
        return true;
    }

    public void abortDiffPatch() {
        patchMode = false;
        patchSpanOn = false;
        spanOn = false;
        patchOverflow = false;
        frameNo++;
        for (int k = 0; k < patchDoneCount; k++) spFrame[patchDoneIndices[k]] = -1;
        patchDoneCount = 0;
        slots = 0;
        count = 0;
        runStart = 0;
        clipCount = 0;
        lastClips = null;
        Arrays.fill(clipTable, -1);
        prevFrameValid = false;
    }

    public void endDiffPatch() {
        if (!patchMode) return;
        int reused = prevFrameCount - patchOldShapes;
        if (reused > 0) {
            accepted += reused;
            replayedShapes += reused;
        }
        replayedItems += prevFrameCount;
        patchMode = false;
    }

    public boolean accepts(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry, GpuClip[] clips, float opacity) {
        return accepts(pose, x0, y0, x1, y1, rx, ry, clips, opacity, 1);
    }

    public boolean acceptsWithExt(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry, GpuClip[] clips, float opacity) {
        return accepts(pose, x0, y0, x1, y1, rx, ry, clips, opacity, 2);
    }

    public boolean acceptsOuterShadow(Matrix2D pose, float qx0, float qy0, float qx1, float qy1, GpuClip[] clips, float opacity) {
        return accepts(pose, qx0, qy0, qx1, qy1, ZERO4, ZERO4, clips, opacity, 2);
    }

    private boolean accepts(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry, GpuClip[] clips,
                            float opacity, int slotsNeeded) {
        if (clips != null && clips.length > 0 && (clips.length > PropertyTrees.MAX_CLIPS_PER_PRIMITIVE || clipCount + clips.length > clipCapacity)) {
            rejects[3]++;
            if (clips.length <= PropertyTrees.MAX_CLIPS_PER_PRIMITIVE) overflows++;
            return false;
        }
        if (patchSpanOn) {
            if (patchSlotCursor + slotsNeeded > patchSlotLimit || patchOrderCursor >= patchOrderLimit) {
                patchOverflow = true;
                return false;
            }
        } else if (slots + slotsNeeded > usableCapacity) { rejects[3]++; overflows++; return false; }
        if (!(opacity > 0f) || !pose.isFinite() || !(x1 > x0) || !(y1 > y0)) { rejects[3]++; return false; }
        if (!decompose(pose)) { rejects[0]++; return false; }
        boolean rounded = false;
        for (int i = 0; i < 4; i++) if (rx[i] > 0f || ry[i] > 0f) rounded = true;
        if (rounded && Math.abs(Math.abs(trs[3]) - trs[2]) > 1e-4f * trs[2]) { rejects[2]++; return false; }
        return true;
    }

    private boolean decompose(Matrix2D pose) {
        double a = pose.a(), b = pose.b(), c = pose.c(), d = pose.d();
        if (b == 0 && c == 0 && a > 1e-9 && d > 1e-9) {
            trs[0] = (float) pose.e();
            trs[1] = (float) pose.f();
            trs[2] = (float) a;
            trs[3] = (float) d;
            return true;
        }
        double sx = Math.hypot(a, b);
        if (!(sx > 1e-9)) return false;
        double sy = (a * d - b * c) / sx;
        double cs = a / sx, sn = b / sx;
        double tol = 1e-5 * Math.max(sx, Math.abs(sy));
        if (Math.abs(c + sn * sy) > tol || Math.abs(d - cs * sy) > tol || !(Math.abs(sy) > 1e-9)) return false;
        trs[0] = (float) pose.e();
        trs[1] = (float) pose.f();
        trs[2] = (float) sx;
        trs[3] = (float) sy;
        return true;
    }

    public boolean acceptsGlyph(int bx, int by, int w, int h, GpuClip[] clips, float opacity) {
        return accepts(Matrix2D.IDENTITY, bx, by, bx + w, by + h, ZERO4, ZERO4, clips, opacity, 1);
    }

    public void pushGlyph(int bx, int by, int w, int h, int atlasX, int atlasY, int argb, GpuClip[] clips, float opacity) {
        int k = open(Matrix2D.IDENTITY, PropertyTrees.OP_GLYPH, bx, by, bx + w, by + h, argb, clips, opacity, 0f);
        trees.setPrimitiveAtlas(k, atlasX, atlasY, w, h);
    }

    public boolean acceptsPosed(Matrix2D pose, float x0, float y0, float x1, float y1, GpuClip[] clips, float opacity) {
        return accepts(pose, x0, y0, x1, y1, ZERO4, ZERO4, clips, opacity, 2);
    }

    public boolean acceptsImage(Matrix2D pose, float bx0, float by0, float bx1, float by1, float[] rx, float[] ry, GpuClip[] clips, float opacity) {
        return accepts(pose, bx0, by0, bx1, by1, rx, ry, clips, opacity, 2);
    }

    private int nextExtSlot() {
        return patchSpanOn ? patchSlotCursor++ : slots++;
    }

    public void pushGlyphStroke(int bx, int by, int w, int h, int atlasX, int atlasY, float half, int argb, GpuClip[] clips, float opacity) {
        open(Matrix2D.IDENTITY, PropertyTrees.OP_GLYPH_STROKE, bx, by, bx + w, by + h, argb, clips, opacity, 0f);
        int ext = nextExtSlot();
        trees.setExtFloat(ext, 0, atlasX);
        trees.setExtFloat(ext, 1, atlasY);
        trees.setExtFloat(ext, 2, w);
        trees.setExtFloat(ext, 3, h);
        trees.setExtFloat(ext, 4, half);
    }

    public void pushGlyphTransformed(Matrix2D pose, float bx0, float by0, float bx1, float by1, int atlasX, int atlasY, int w, int h,
                                     float ox, float oy, float s, float left, float top, int argb, GpuClip[] clips, float opacity) {
        open(pose, PropertyTrees.OP_GLYPH_TRANSFORMED, bx0, by0, bx1, by1, argb, clips, opacity, 0f);
        int ext = nextExtSlot();
        trees.setExtFloat(ext, 0, atlasX);
        trees.setExtFloat(ext, 1, atlasY);
        trees.setExtFloat(ext, 2, w);
        trees.setExtFloat(ext, 3, h);
        trees.setExtFloat(ext, 4, ox);
        trees.setExtFloat(ext, 5, oy);
        trees.setExtFloat(ext, 6, s);
        trees.setExtFloat(ext, 8, left);
        trees.setExtFloat(ext, 9, top);
    }

    public void pushCircleBorder(Matrix2D pose, float cx, float cy, float outerR, float innerR,
                                 float bx0, float by0, float bx1, float by1, int[] colors, GpuClip[] clips, float opacity) {
        int k = open(pose, PropertyTrees.OP_CIRCLE_BORDER, bx0, by0, bx1, by1, colors[0], clips, opacity, 0f);
        trees.setPrimitiveColors(k, colors[0], colors[1], colors[2], colors[3]);
        int ext = nextExtSlot();
        trees.setExtFloat(ext, 0, cx);
        trees.setExtFloat(ext, 1, cy);
        trees.setExtFloat(ext, 2, outerR);
        trees.setExtFloat(ext, 3, innerR);
    }

    public void pushImage(Matrix2D pose, float bx0, float by0, float bx1, float by1, float px0, float py0, float sx, float sy,
                          float x0, float y0, float x1, float y1, float[] rx, float[] ry, int imgW, int imgH,
                          int taps, float stepX, float stepY, GpuClip[] clips, float opacity) {
        spanUnclean = true;
        open(pose, PropertyTrees.OP_IMAGE, bx0, by0, bx1, by1, 0, clips, opacity, 0f);
        int ext = nextExtSlot();
        trees.setExtFloat(ext, 0, x0);
        trees.setExtFloat(ext, 1, y0);
        trees.setExtFloat(ext, 2, x1);
        trees.setExtFloat(ext, 3, y1);
        trees.setExtFloat(ext, 4, px0);
        trees.setExtFloat(ext, 5, py0);
        trees.setExtFloat(ext, 6, sx);
        trees.setExtFloat(ext, 7, sy);
        trees.setExtFloat(ext, 8, imgW);
        trees.setExtFloat(ext, 9, imgH);
        trees.setExtFloat(ext, 10, taps);
        trees.setExtFloat(ext, 12, stepX);
        trees.setExtFloat(ext, 13, stepY);
        for (int i = 0; i < 4; i++) {
            trees.setExtFloat(ext, 16 + i, rx[i]);
            trees.setExtFloat(ext, 20 + i, ry[i]);
        }
    }

    private int open(Matrix2D pose, int opcode, float x0, float y0, float x1, float y1, int argb, GpuClip[] clips, float opacity,
                     float margin) {
        int k = patchSpanOn ? patchSlotCursor++ : slots++;
        trees.setTransformMatrix(k, (float) pose.a(), (float) pose.b(), (float) pose.c(), (float) pose.d(),
                (float) pose.e(), (float) pose.f());
        int ef = PropertyTrees.NONE;
        if (opacity < 1f) {
            trees.setEffect(k, PropertyTrees.NONE, opacity, 0, 0, 0f);
            ef = k;
        }
        trees.setPrimitive(k, opcode, k, clips == null ? 0 : clips.length, ef, x0, y0, x1 - x0, y1 - y0, argb, 0);
        trees.setPrimitiveMargin(k, margin);
        writeClips(k, clips);
        trees.order.putInt(patchSpanOn ? patchOrderCursor++ : count++, 0, k);
        accepted++;
        return k;
    }

    public void push(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry, int argb, GpuClip[] clips, float opacity) {
        int k = open(pose, PropertyTrees.OP_FILL, x0, y0, x1, y1, argb, clips, opacity, 1f / Math.max((float) pose.meanScale(), 0.01f));
        trees.setPrimitiveRadiiXY(k, rx, ry);
    }

    public void pushStroke(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                           float ix0, float iy0, float ix1, float iy1, float[] irx, float[] iry, boolean innerEmpty,
                           int[] colors, float[] widths, GpuClip[] clips, float opacity) {
        int k = open(pose, PropertyTrees.OP_STROKE, x0, y0, x1, y1, colors[0], clips, opacity, 1f / Math.max((float) pose.meanScale(), 0.01f));
        trees.setPrimitiveColors(k, colors[0], colors[1], colors[2], colors[3]);
        trees.setPrimitiveRadiiXY(k, rx, ry);
        trees.setPrimitiveInner(k, ix0, iy0, ix1, iy1, irx, iry, innerEmpty ? PropertyTrees.FLAG_INNER_EMPTY : 0);
        trees.setPrimitiveBorderWidths(k, widths[0], widths[1], widths[2], widths[3]);
    }

    public void pushStrokeDashed(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                 float ix0, float iy0, float ix1, float iy1, float[] irx, float[] iry, boolean innerEmpty,
                                 int[] colors, float[] widths, float bx, float by, float cxm, float cym, float[] pcx, float[] pcy,
                                 float period, float dash, boolean round, float thickness, GpuClip[] clips, float opacity) {
        int k = open(pose, PropertyTrees.OP_STROKE, x0, y0, x1, y1, colors[0], clips, opacity, 1f / Math.max((float) pose.meanScale(), 0.01f));
        trees.setPrimitiveColors(k, colors[0], colors[1], colors[2], colors[3]);
        trees.setPrimitiveRadiiXY(k, rx, ry);
        trees.setPrimitiveInner(k, ix0, iy0, ix1, iy1, irx, iry,
                (innerEmpty ? PropertyTrees.FLAG_INNER_EMPTY : 0) | PropertyTrees.FLAG_DASHED);
        trees.setPrimitiveBorderWidths(k, widths[0], widths[1], widths[2], widths[3]);
        trees.setExtDash(nextExtSlot(), bx, by, cxm, cym, pcx, pcy, period, dash, round, thickness);
    }

    public void pushGradient(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                             ShaderGradient g, GpuClip[] clips, float opacity) {
        int k = open(pose, PropertyTrees.OP_GRADIENT, x0, y0, x1, y1, 0, clips, opacity, 1f / Math.max((float) pose.meanScale(), 0.01f));
        trees.setPrimitiveRadiiXY(k, rx, ry);
        trees.setPrimitiveGradient(nextExtSlot(), g.gradA(), g.gradB(), g.repeatOrigin(), g.repeatPeriod(), g.color0(), g.color1());
    }

    public void pushOuterShadow(Matrix2D pose, float qx0, float qy0, float qx1, float qy1,
                                float sx0, float sy0, float sx1, float sy1, float[] srx, float[] sry, float sigma,
                                float[] hole, float[] hrx, float[] hry, int argb, GpuClip[] clips, float opacity) {
        open(pose, PropertyTrees.OP_OUTER_SHADOW, qx0, qy0, qx1, qy1, argb, clips, opacity, 0f);
        trees.setPrimitiveShadow(nextExtSlot(), sx0, sy0, sx1, sy1, srx, sry, sigma, hole, hrx, hry);
    }

    public void pushInsetShadow(Matrix2D pose, float qx0, float qy0, float qx1, float qy1, float[] rx, float[] ry,
                                float sigma, float[] hole, float[] hrx, float[] hry, int argb, GpuClip[] clips, float opacity) {
        int k = open(pose, PropertyTrees.OP_INSET_SHADOW, qx0, qy0, qx1, qy1, argb, clips, opacity, 1f);
        trees.setPrimitiveRadiiXY(k, rx, ry);
        trees.setPrimitiveShadow(nextExtSlot(), qx0, qy0, qx1, qy1, rx, ry, sigma, hole, hrx, hry);
    }

    private void writeClips(int k, GpuClip[] clips) {
        int n = clips == null ? 0 : clips.length;
        if (n > 0 && clips == lastClips) {
            trees.setPrimitiveClips(k, n, lastA, lastB, lastC, lastD);
            return;
        }
        int a = 0, b = 0, c = 0, d = 0;
        for (int i = 0; i < n; i++) {
            int id = clipSlot(clips[i]);
            switch (i) { case 0 -> a = id; case 1 -> b = id; case 2 -> c = id; default -> d = id; }
        }
        if (n > 0) { lastClips = clips; lastA = a; lastB = b; lastC = c; lastD = d; }
        if (spanOn) { if (n > 0) spanRef(a); if (n > 1) spanRef(b); if (n > 2) spanRef(c); if (n > 3) spanRef(d); }
        trees.setPrimitiveClips(k, n, a, b, c, d);
    }

    private int clipSlot(GpuClip c) {
        Matrix2D v = c.inv();
        float[] key = clipKeys;
        float f0 = (float) v.a(), f1 = (float) v.b(), f2 = (float) v.c(), f3 = (float) v.d(), f4 = (float) v.e(), f5 = (float) v.f();
        int h = 17;
        h = h * 31 + Float.floatToIntBits(f0); h = h * 31 + Float.floatToIntBits(f1); h = h * 31 + Float.floatToIntBits(f2);
        h = h * 31 + Float.floatToIntBits(f3); h = h * 31 + Float.floatToIntBits(f4); h = h * 31 + Float.floatToIntBits(f5);
        h = h * 31 + Float.floatToIntBits(c.x0()) + 7 * Float.floatToIntBits(c.y0()) + 13 * Float.floatToIntBits(c.x1()) + 29 * Float.floatToIntBits(c.y1());
        h ^= h >>> 15;
        int mask = clipTable.length - 1;
        int i = h & mask;
        while (clipTable[i] >= 0) {
            int slot = clipTable[i];
            int o = slot * KEY;
            if (key[o] == f0 && key[o + 1] == f1 && key[o + 2] == f2 && key[o + 3] == f3 && key[o + 4] == f4 && key[o + 5] == f5
                    && key[o + 6] == c.scale() && key[o + 7] == c.x0() && key[o + 8] == c.y0() && key[o + 9] == c.x1() && key[o + 10] == c.y1()
                    && same(key, o + 11, c.rx()) && same(key, o + 15, c.ry())) {
                return slot;
            }
            i = (i + 1) & mask;
        }
        int slot = clipCount++;
        int o = slot * KEY;
        boolean changed = slot >= clipHighWater;
        if (changed) {
            clipHighWater = slot + 1;
        } else {
            changed = key[o] != f0 || key[o + 1] != f1 || key[o + 2] != f2 || key[o + 3] != f3 || key[o + 4] != f4 || key[o + 5] != f5
                    || key[o + 6] != c.scale() || key[o + 7] != c.x0() || key[o + 8] != c.y0() || key[o + 9] != c.x1() || key[o + 10] != c.y1()
                    || !same(key, o + 11, c.rx()) || !same(key, o + 15, c.ry());
        }
        if (changed) {
            clipSlotVersion[slot] = clipVerNext++;
            key[o] = f0; key[o + 1] = f1; key[o + 2] = f2; key[o + 3] = f3; key[o + 4] = f4; key[o + 5] = f5;
            key[o + 6] = c.scale(); key[o + 7] = c.x0(); key[o + 8] = c.y0(); key[o + 9] = c.x1(); key[o + 10] = c.y1();
            for (int j = 0; j < 4; j++) { key[o + 11 + j] = c.rx()[j]; key[o + 15 + j] = c.ry()[j]; }
        }
        clipTable[i] = slot;
        trees.setClip(slot, v.a(), v.b(), v.c(), v.d(), v.e(), v.f(), c.scale(), c.x0(), c.y0(), c.x1(), c.y1(), c.rx(), c.ry());
        return slot;
    }

    private static boolean same(float[] key, int o, float[] r) {
        return key[o] == r[0] && key[o + 1] == r[1] && key[o + 2] == r[2] && key[o + 3] == r[3];
    }

    @SuppressWarnings("unused")
    private static boolean onGrid(float v) {
        return Math.abs(v - Math.round(v)) <= GRID_EPS;
    }

    /** 溜めた矩形を開いている {@link GpuPass} へ 1 回のインスタンス描画で吐く。 */
    public void drain(GpuPass pass, GpuTexture atlasTex, GpuTexture imgTex, int viewW, int viewH) {
        int n = count - runStart;
        if (n <= 0) return;
        store.flush();
        renderer.draw(pass, store, atlasTex, imgTex, runStart, n, 0f, viewW, viewH, true);
        runStart = count;
        drawCalls++;
    }

    public long changedWrites() {
        return trees.primitives.changedWrites() + trees.transforms.changedWrites() + trees.effects.changedWrites();
    }

    public long uploadedBytes() {
        return trees.primitives.uploadedBytes() + trees.transforms.uploadedBytes() + trees.effects.uploadedBytes();
    }

    @Override
    public void close() {
        renderer.close();
        store.close();
    }
}
