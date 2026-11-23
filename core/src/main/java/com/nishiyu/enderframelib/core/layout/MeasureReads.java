package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.layout.model.ExternalEpochs;
import com.nishiyu.enderframelib.core.layout.model.IntrinsicSizeResolver;
import com.nishiyu.enderframelib.core.text.model.FontResolver;

import java.util.Arrays;

/**
 * 測定中の読み取り集合（入力 {@code READ_*}・親による子への読み {@code PARENT_READ_*}）と、
 * スレッドごとの測定作業状態（燃料・下降深さ・外部世代キャッシュ・測定スタック）（C3 ②③）。
 *
 * <p>以前の {@code MeasureMemo.FUEL} / {@code DESCENT} の 2 つの {@code ThreadLocal} を 1 つへ統合し、
 * 同じパスの間は {@link ExternalEpochs} を使い回して主パスでの追加確保を出さない。</p>
 */
final class MeasureReads {
    private MeasureReads() {}

    // ---- 自分の測定が読んだ外部入力（BoxMeasurer + 子からの伝播）----

    static final int READ_AVAIL_INLINE = 1 << 0;
    static final int READ_PERCENT_BLOCK = 1 << 1;
    static final int READ_SHRINK = 1 << 2;
    static final int READ_STRETCH = 1 << 3;
    static final int READ_VIEWPORT_W = 1 << 4;
    static final int READ_VIEWPORT_H = 1 << 5;
    static final int READ_SHAPE_EPOCH = 1 << 6;
    static final int READ_FIT_BOUNDED = 1 << 7;
    static final int READ_FILL_FIT = 1 << 8;

    static final int TRANSITIVE_MASK = READ_VIEWPORT_W | READ_VIEWPORT_H | READ_PERCENT_BLOCK;

    // ---- 親（または祖先）が自分から読んだ出力（cutoff の可否・照合用）----

    static final int PARENT_READ_OUTER = 1 << 0;
    static final int PARENT_READ_CONTENT_EXTENT = 1 << 1;
    static final int PARENT_READ_MIN_CONTENT = 1 << 2;
    static final int PARENT_READ_FIRST_BASELINE = 1 << 3;
    static final int PARENT_READ_LAST_BASELINE = 1 << 4;
    static final int PARENT_READ_FIT_NEED = 1 << 5;
    static final int PARENT_READ_STRETCHED = 1 << 6;
    static final int PARENT_READ_MULTI_SPACE = 1 << 7;
    static final int PARENT_READ_OPAQUE = 1 << 8;

    static final int PARENT_CUTOFF_BLOCKERS = PARENT_READ_STRETCHED | PARENT_READ_MULTI_SPACE | PARENT_READ_OPAQUE;

    /** スレッドごとの測定状態（燃料・下降深さ・外部世代キャッシュ・測定中ボックススタック）。 */
    static final class ThreadState {
        long fuelRun = -1;
        long fuelCount;
        long fuelFirstBoxes;
        int descentDepth;
        ExternalEpochs cachedEpochs;
        LayoutBox[] boxStack = new LayoutBox[32];
        int[] readStack = new int[32];
        int depth;

        // 配置の読み取りスタック（K1: 同じ ThreadLocal に載せ、static 欄を増やさない）
        LayoutBox[] placeBoxStack = new LayoutBox[32];
        int[] placeReadStack = new int[32];
        float[] placeOriginX = new float[32];
        float[] placeOriginY = new float[32];
        float[] placeParentW = new float[32];
        float[] placeParentH = new float[32];
        int placeDepth;
        LayoutReads.PlaceRecorder placeRecorder;
    }

    private static final ThreadLocal<ThreadState> STATE = ThreadLocal.withInitial(ThreadState::new);

    static ThreadState state() {
        return STATE.get();
    }

    static int pushMeasuring(LayoutBox box) {
        ThreadState st = STATE.get();
        int idx = st.depth;
        if (idx == st.boxStack.length) {
            int next = idx << 1;
            st.boxStack = Arrays.copyOf(st.boxStack, next);
            st.readStack = Arrays.copyOf(st.readStack, next);
        }
        st.boxStack[idx] = box;
        st.readStack[idx] = 0;
        st.depth = idx + 1;
        return idx;
    }

    static int popMeasuring(int slot) {
        ThreadState st = STATE.get();
        int bits = st.readStack[slot];
        st.boxStack[slot] = null;
        st.readStack[slot] = 0;
        st.depth = slot;
        return bits;
    }

    static LayoutBox currentMeasuringBox() {
        ThreadState st = STATE.get();
        return st.depth > 0 ? st.boxStack[st.depth - 1] : null;
    }

    static boolean isOnStack(LayoutBox box) {
        if (box == null) return false;
        ThreadState st = STATE.get();
        LayoutBox[] stack = st.boxStack;
        for (int i = st.depth - 1; i >= 0; i--) {
            if (stack[i] == box) return true;
        }
        return false;
    }

    static void recordInput(int bits) {
        ThreadState st = STATE.get();
        if (st.depth > 0 && st.boxStack[st.depth - 1] != null) {
            st.readStack[st.depth - 1] |= bits;
        }
    }

    static void propagateChildReads(LayoutBox child) {
        ThreadState st = STATE.get();
        if (st.depth > 0 && st.boxStack[st.depth - 1] == child.parent && child.parent != null) {
            st.readStack[st.depth - 1] |= child.measureMemo.transitiveReads();
        }
    }

    static long fontGeneration(LayoutContext ctx) {
        FontResolver fonts = ctx.fonts();
        return fonts != null ? fonts.resolveGeneration() : -1L;
    }

    static long intrinsicGeneration(LayoutContext ctx) {
        IntrinsicSizeResolver intrinsics = ctx.intrinsicResolver();
        return intrinsics != null ? intrinsics.resolveGeneration() : 0L;
    }

    static ExternalEpochs currentEpochs(LayoutContext ctx, int measureEpoch, int shapeEpoch) {
        ThreadState st = STATE.get();
        FontResolver fonts = ctx.fonts();
        long fontGen = fonts != null ? fonts.resolveGeneration() : -1L;
        IntrinsicSizeResolver intrinsics = ctx.intrinsicResolver();
        long intrGen = intrinsics != null ? intrinsics.resolveGeneration() : 0L;
        ExternalEpochs cached = st.cachedEpochs;
        if (cached != null && cached.matchesExact(measureEpoch, shapeEpoch, fonts, fontGen, intrinsics, intrGen)) {
            return cached;
        }
        ExternalEpochs fresh = new ExternalEpochs(measureEpoch, shapeEpoch, fonts, fontGen, intrinsics, intrGen);
        st.cachedEpochs = fresh;
        return fresh;
    }
}
