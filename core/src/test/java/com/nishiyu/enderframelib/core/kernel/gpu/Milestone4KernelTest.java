package com.nishiyu.enderframelib.core.kernel.gpu;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.EasingFunction;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class Milestone4KernelTest {

    /** 転送を数える偽デバイス。persistent=true なら永続マップを返す。 */
    static final class FakeDevice implements PersistentVramBuffer.Device {
        final boolean persistent;
        int subDataCalls;
        long subDataBytes;
        int lastOffset, lastLength;
        ByteBuffer mapped;

        FakeDevice(boolean persistent) { this.persistent = persistent; }

        @Override public ByteBuffer mapPersistent(long bytes) {
            if (!persistent) return null;
            mapped = ByteBuffer.allocateDirect((int) bytes);
            return mapped;
        }

        @Override public void subData(ByteBuffer src, int offset, int length) {
            subDataCalls++;
            subDataBytes += length;
            lastOffset = offset;
            lastLength = length;
        }
    }

    private static PropertyTrees trees(PersistentVramBuffer.Device d) {
        return new PropertyTrees(256, 64, 256, 512, d);
    }

    // ---- PersistentVramBuffer -----------------------------------------------------------------

    @Test
    void fallbackFlushSendsOnlyTheDirtyRange() {
        FakeDevice dev = new FakeDevice(false);
        PersistentVramBuffer b = new PersistentVramBuffer(64, 100, dev);
        assertFalse(b.isPersistent());
        int a = b.allocate(), c = b.allocate(), d = b.allocate(), e = b.allocate();
        assertEquals(0, b.flush(), "何も書いていなければ転送なし");
        b.putInt(c, 0, 7);
        b.putInt(e, 8, 9);
        assertEquals(3 * 64, b.flush(), "slot 1..3 の連続範囲");
        assertEquals(64, dev.lastOffset);
        assertEquals(192, dev.lastLength);
        assertEquals(0, b.flush());
        b.putInt(c, 0, 7); // 同じ値
        assertFalse(b.isDirty(), "同じ値の書き込みは汚れにしない");
        b.putFloat(a, 4, 1.5f);
        assertEquals(64, b.flush());
        assertEquals(1.5f, b.getFloat(a, 4));
        assertEquals(2, dev.subDataCalls);
        assertTrue(d >= 0);
    }

    @Test
    void persistentMappingWritesStraightIntoGpuMemoryAndNeverUploads() {
        FakeDevice dev = new FakeDevice(true);
        PersistentVramBuffer b = new PersistentVramBuffer(64, 10, dev);
        assertTrue(b.isPersistent());
        int s = b.allocate();
        b.putInt(s, 12, 0xCAFE);
        assertEquals(0xCAFE, dev.mapped.order(java.nio.ByteOrder.nativeOrder()).getInt(s * 64 + 12), "GPU メモリへ直接書かれる");
        assertEquals(0, b.flush());
        assertEquals(0, dev.subDataCalls);
        assertEquals(0L, b.uploadedBytes());
        assertFalse(b.isDirty());
    }

    @Test
    void slotsAreReusedAndBoundsAreChecked() {
        PersistentVramBuffer b = new PersistentVramBuffer(32, 3, null);
        int a = b.allocate(), c = b.allocate(), d = b.allocate();
        assertEquals(-1, b.allocate(), "容量いっぱい");
        b.putInt(c, 0, 5);
        b.free(c);
        assertEquals(0, b.getInt(c, 0), "解放で 0 クリア");
        assertEquals(c, b.allocate());
        assertEquals(3, b.liveCount());
        assertThrows(IndexOutOfBoundsException.class, () -> b.putInt(a, 32, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> b.putInt(99, 0, 1));
        assertTrue(d >= 0);
    }

    @Test
    void recordLayoutsRoundTripAndStayInsideTheirStride() {
        PropertyTrees t = trees(null);
        int tr = t.addTransform(PropertyTrees.NONE, 3, 4, 2, 5, 0.5f);
        t.startTransformAnimation(tr, 1, 2, 3, 4, 0.25f, 100f, 250f, PropertyTrees.EASE_IN_OUT);
        assertEquals(2f, t.transformValue(tr, 2));
        assertEquals(5f, t.transformValue(tr, 3));
        assertEquals(250f, t.transforms.getFloat(tr, PropertyTrees.T_DURATION));
        assertEquals(0.25f, t.transforms.getFloat(tr, PropertyTrees.T_START + 16));
        assertTrue(PropertyTrees.T_START + 20 <= PropertyTrees.TRANSFORM_STRIDE);

        int clip = t.clips.allocate();
        t.setClip(clip, 1, 0, 0, 1, 5, 6, 2f, 1, 2, 30, 40, new float[]{4, 8, 12, 16}, new float[]{1, 2, 3, 4});
        assertEquals(30f, t.clips.getFloat(clip, PropertyTrees.C_RECT + 8));
        assertEquals(12f, t.clips.getHalf(clip, PropertyTrees.C_RX + 4));
        assertEquals(4f, t.clips.getHalf(clip, PropertyTrees.C_RY + 6));
        assertEquals(2f, t.clips.getFloat(clip, PropertyTrees.C_SCALE));
        assertEquals(6f, t.clips.getFloat(clip, PropertyTrees.C_INV_T + 4));

        int ef = t.addEffect(PropertyTrees.NONE, 0.5f, 3, 2, 6f);
        t.setEffectAtlas(ef, 1, 2, 65535, 4);
        assertEquals(3, t.effects.getShort(ef, PropertyTrees.E_BLEND));
        assertEquals(65535, t.effects.getShort(ef, PropertyTrees.E_UV + 4));
        assertEquals(PropertyTrees.NONE, t.effects.getInt(ef, PropertyTrees.E_ANIM));

        int p = t.addPrimitive();
        t.setPrimitive(p, 9, tr, clip, ef, 1, 2, 3, 4, 0xFF112233, 0x80445566);
        t.setPrimitiveClips(p, 3, 7, 65535, 0, 1);
        assertEquals(3, t.primitives.getInt(p, PropertyTrees.P_CLIP));
        assertEquals(65535, t.primitives.getShort(p, PropertyTrees.P_CLIPS + 2));
        t.setPrimitiveRadii(p, 1, 2, 3, 4);
        assertEquals(4f, t.primitives.getHalf(p, PropertyTrees.P_RY + 6), "円の角は rx と ry の両方");
        t.setPrimitiveBorderWidths(p, 5, 6, 7, 8);
        t.setPrimitiveAtlas(p, 10, 20, 30, 40);
        assertEquals(0xFF112233, t.primitives.getInt(p, PropertyTrees.P_COLOR));
        assertEquals(4f, t.primitives.getHalf(p, PropertyTrees.P_RX + 6));
        assertEquals(7f, t.primitives.getHalf(p, PropertyTrees.P_WIDTHS + 4));
        assertEquals(40, t.primitives.getShort(p, PropertyTrees.P_UV + 6));
        assertEquals(PropertyTrees.PRIMITIVE_STRIDE, PropertyTrees.P_CLIPS + 8, "プリミティブは 128 バイト（末尾の 8 B はクリップ ID）");
        assertEquals(PropertyTrees.EFFECT_STRIDE, PropertyTrees.E_UV + 8);
        assertEquals(PropertyTrees.CLIP_STRIDE, PropertyTrees.C_RY + 8);
        assertEquals(PropertyTrees.TRANSFORM_STRIDE, PropertyTrees.T_START + 20);
    }

    // ---- GPU 側の評価の参照実装 ------------------------------------------------------------------

    @Test
    void progressClampsAndZeroDurationIsDone() {
        assertEquals(0f, GpuAnimationReference.progress(50f, 100f, 200f));
        assertEquals(0.5f, GpuAnimationReference.progress(200f, 100f, 200f));
        assertEquals(1f, GpuAnimationReference.progress(900f, 100f, 200f));
        assertEquals(1f, GpuAnimationReference.progress(0f, 100f, 0f));
    }

    @Test
    void easingMatchesTheCssEvaluatorForPresetsAndCustomCurves() {
        PropertyTrees t = trees(null);
        String[] names = {"linear", "ease", "ease-in", "ease-out", "ease-in-out"};
        for (int k = 0; k < names.length; k++) {
            for (int i = 0; i <= 200; i++) {
                float x = i / 200f;
                assertEquals(EasingFunction.apply(x, names[k]), GpuAnimationReference.ease(t, k, x), 2e-3f, names[k] + " @" + x);
            }
        }
        int custom = t.registerCurve(0.68f, -0.55f, 0.27f, 1.55f);
        assertEquals(custom, t.registerCurve(0.68f, -0.55f, 0.27f, 1.55f), "同じ曲線は同じ番号");
        assertEquals(1, t.curveCount());
        for (int i = 0; i <= 200; i++) {
            float x = i / 200f;
            assertEquals(EasingFunction.cubicBezier(x, 0.68f, -0.55f, 0.27f, 1.55f), GpuAnimationReference.ease(t, custom, x), 2e-3f, "custom @" + x);
        }
    }

    @Test
    void transformAnimationInterpolatesFromStartToTargetByFrameTime() {
        PropertyTrees t = trees(null);
        int s = t.addTransform(PropertyTrees.NONE, 100, 0, 1, 1, 0);
        t.startTransformAnimation(s, 0, 0, 1, 1, 0, 1000f, 200f, PropertyTrees.EASE_LINEAR);
        float[] out = new float[5];
        GpuAnimationReference.evalTrs(t, s, 900f, out);
        assertEquals(0f, out[0], 1e-4f);
        GpuAnimationReference.evalTrs(t, s, 1100f, out);
        assertEquals(50f, out[0], 1e-3f);
        GpuAnimationReference.evalTrs(t, s, 5000f, out);
        assertEquals(100f, out[0], 1e-4f);
        int still = t.addTransform(PropertyTrees.NONE, 7, 8, 1, 1, 0);
        GpuAnimationReference.evalTrs(t, still, 12345f, out);
        assertEquals(7f, out[0]);
    }

    /** 素朴な参照: 点を子 → 親の順に直接変換する。 */
    private static void applyChain(PropertyTrees t, int slot, float frameMs, float[] p) {
        float[] trs = new float[5];
        for (int n = slot; n >= 0; n = t.transforms.getInt(n, PropertyTrees.T_PARENT)) {
            GpuAnimationReference.evalTrs(t, n, frameMs, trs);
            float x = p[0] * trs[2], y = p[1] * trs[3];
            float c = (float) Math.cos(trs[4]), s = (float) Math.sin(trs[4]);
            float rx = c * x - s * y, ry = s * x + c * y;
            rx += trs[0]; ry += trs[1];
            int sc = t.transforms.getInt(n, PropertyTrees.T_SCROLL);
            if (sc >= 0) { rx -= t.scrollX(sc); ry -= t.scrollY(sc); }
            p[0] = rx; p[1] = ry;
        }
    }

    @Test
    void worldMatrixMatchesDirectPointChainIncludingScrollAndRotation() {
        PropertyTrees t = trees(null);
        Random r = new Random(7);
        int parent = PropertyTrees.NONE;
        int[] chain = new int[6];
        for (int i = 0; i < chain.length; i++) {
            chain[i] = t.addTransform(parent, r.nextFloat() * 20 - 10, r.nextFloat() * 20 - 10,
                    0.5f + r.nextFloat(), 0.5f + r.nextFloat(), r.nextFloat() - 0.5f);
            if (i % 2 == 1) { t.setScroll(chain[i], i); t.setScrollOffset(i, r.nextFloat() * 50, r.nextFloat() * 50); }
            if (i == 3) t.startTransformAnimation(chain[i], 0, 0, 1, 1, 0, 0f, 100f, PropertyTrees.EASE_EASE);
            parent = chain[i];
        }
        float[] m = new float[6], scratch = new float[17];
        for (float frame : new float[]{0f, 30f, 100f, 400f}) {
            GpuAnimationReference.worldMatrix(t, chain[5], frame, m, scratch);
            for (int k = 0; k < 20; k++) {
                float px = r.nextFloat() * 10, py = r.nextFloat() * 10;
                float[] expect = {px, py};
                applyChain(t, chain[5], frame, expect);
                assertEquals(expect[0], m[0] * px + m[2] * py + m[4], 1e-3f, "x @" + frame);
                assertEquals(expect[1], m[1] * px + m[3] * py + m[5], 1e-3f, "y @" + frame);
            }
        }
    }

    @Test
    void scrollOnlyMovesTheScrolledLayer() {
        PropertyTrees t = trees(null);
        int root = t.addTransform(PropertyTrees.NONE, 10, 10, 1, 1, 0);
        int content = t.addTransform(root, 0, 0, 1, 1, 0);
        t.setScroll(content, 3);
        float[] m = new float[6], sc = new float[17];
        t.setScrollOffset(3, 0, 0);
        GpuAnimationReference.worldMatrix(t, content, 0f, m, sc);
        assertEquals(10f, m[5], 1e-5f);
        t.setScrollOffset(3, 0, 120f);
        GpuAnimationReference.worldMatrix(t, content, 0f, m, sc);
        assertEquals(-110f, m[5], 1e-4f);
        GpuAnimationReference.worldMatrix(t, root, 0f, m, sc);
        assertEquals(10f, m[5], 1e-5f, "親は動かない");
    }

    @Test
    void effectOpacityMultipliesTheChainAndAnimatesWithTheTimingTransform() {
        PropertyTrees t = trees(null);
        int parent = t.addEffect(PropertyTrees.NONE, 0.5f, 0, 0, 0f);
        int child = t.addEffect(parent, 0.8f, 0, 0, 0f);
        assertEquals(0.4f, GpuAnimationReference.effectiveOpacity(t, child, 0f), 1e-6f);
        int timing = t.addTransform(PropertyTrees.NONE, 0, 0, 1, 1, 0);
        t.startTransformAnimation(timing, 0, 0, 1, 1, 0, 0f, 100f, PropertyTrees.EASE_LINEAR);
        t.startOpacityAnimation(child, 0f, timing);
        assertEquals(0f, GpuAnimationReference.effectiveOpacity(t, child, 0f), 1e-6f);
        assertEquals(0.5f * 0.4f, GpuAnimationReference.effectiveOpacity(t, child, 50f), 1e-5f);
        assertEquals(0.4f, GpuAnimationReference.effectiveOpacity(t, child, 1000f), 1e-6f);
    }

    @Test
    void glslCarriesTheSameKernelsAsTheJavaReference() {
        for (String needle : new String[]{"efProgress", "efCubic", "efTrs", "efWorld", "uFrameTimeMs", "clamp((uFrameTimeMs - startMs) / durationMs"}) {
            assertTrue(GpuAnimation.GLSL.contains(needle), needle);
        }
    }

    // ---- 表示項目との同期 -------------------------------------------------------------------------

    private static DisplayItem item(String id, int x, int bg, float opacity, float tx) {
        return new DisplayItem(id, id, ElementType.RECT, x, 2, 30, 20, null, 0xFF000000, bg, null, 4, opacity,
                tx, 0f, 1f, 1f, 0f, 0, null, null, null);
    }

    @Test
    void sceneSyncReusesSlotsAndUploadsOnlyWhatChanged() {
        FakeDevice dev = new FakeDevice(false);
        PropertyTrees t = trees(dev);
        GpuSceneSync sync = new GpuSceneSync(t);
        List<DisplayItem> items = new ArrayList<>();
        for (int i = 0; i < 50; i++) items.add(item("n" + i, i, 0xFF202020 + i, 1f, 0f));
        assertEquals(50, sync.sync(items));
        assertTrue(t.flush() > 0);

        assertEquals(50, sync.sync(items));
        assertEquals(0, t.flush(), "同じ内容を再同期しても転送は 0 バイト");

        items.set(20, item("n20", 99, 0xFF202014, 1f, 0f));
        sync.sync(items);
        assertEquals(PropertyTrees.PRIMITIVE_STRIDE, t.flush(), "変わった 1 件分のレコードだけ");
        assertEquals(99f, t.primitives.getFloat(sync.primitiveSlot(items.get(20).key()), PropertyTrees.P_RECT));
    }

    @Test
    void sceneSyncCreatesTransformAndEffectOnlyWhenNeededAndReleasesThem() {
        PropertyTrees t = trees(null);
        GpuSceneSync sync = new GpuSceneSync(t);
        List<DisplayItem> items = new ArrayList<>(List.of(item("a", 0, 1, 1f, 0f), item("b", 0, 1, 0.5f, 12f)));
        sync.sync(items);
        int a = sync.primitiveSlot(items.get(0).key()), b = sync.primitiveSlot(items.get(1).key());
        assertEquals(PropertyTrees.NONE, sync.transformSlot(a));
        assertEquals(PropertyTrees.NONE, sync.effectSlot(a));
        assertTrue(sync.transformSlot(b) >= 0);
        assertEquals(0.5f, t.effects.getFloat(sync.effectSlot(b), PropertyTrees.E_OPACITY));
        assertEquals(0f + 15f + 12f, t.transformValue(sync.transformSlot(b), 0), "x + w/2 + translateX");
        assertEquals(1, t.transforms.liveCount());

        items.set(1, item("b", 0, 1, 1f, 0f)); // 恒等に戻る
        sync.sync(items);
        assertEquals(0, t.transforms.liveCount());
        assertEquals(0, t.effects.liveCount());
    }

    @Test
    void sceneSyncReleasesVanishedItemsAndKeepsDrawOrder() {
        PropertyTrees t = trees(null);
        GpuSceneSync sync = new GpuSceneSync(t);
        List<DisplayItem> items = new ArrayList<>();
        for (int i = 0; i < 10; i++) items.add(item("k" + i, i, 1, 1f, 0f));
        sync.sync(items);
        assertEquals(10, t.primitives.liveCount());

        List<DisplayItem> fewer = new ArrayList<>(List.of(items.get(8), items.get(2), items.get(5)));
        assertEquals(3, sync.sync(fewer));
        assertEquals(3, t.primitives.liveCount(), "消えた項目のスロットは解放される");
        for (int i = 0; i < 3; i++) {
            assertEquals(sync.primitiveSlot(fewer.get(i).key()), sync.orderSlot(i), "並び順 = 描画順");
        }
        assertEquals(-1, sync.primitiveSlot(items.get(0).key()));

        sync.sync(items); // 戻すと空きスロットを再利用し、highWater は増えない
        assertEquals(10, t.primitives.liveCount());
        assertEquals(10, t.primitives.highWater());
    }

    @Test
    void sceneSyncStressMatchesAModelOverRandomMutations() {
        PropertyTrees t = trees(null);
        GpuSceneSync sync = new GpuSceneSync(t);
        Random r = new Random(11);
        for (int step = 0; step < 300; step++) {
            List<DisplayItem> items = new ArrayList<>();
            for (int i = 0; i < 80; i++) {
                if (r.nextInt(3) == 0) continue;
                items.add(item("s" + i, r.nextInt(500), r.nextInt(), r.nextBoolean() ? 1f : 0.25f, r.nextBoolean() ? 0f : r.nextInt(9)));
            }
            sync.sync(items);
            assertEquals(items.size(), t.primitives.liveCount(), "step " + step);
            for (int i = 0; i < items.size(); i++) {
                DisplayItem it = items.get(i);
                int s = sync.primitiveSlot(it.key());
                assertTrue(s >= 0);
                assertEquals(s, sync.orderSlot(i));
                if (it.translateX() == 0f) assertEquals((float) it.x(), t.primitives.getFloat(s, PropertyTrees.P_RECT));
                else assertEquals(-it.width() * 0.5f, t.primitives.getFloat(s, PropertyTrees.P_RECT), "変換つきは中心原点の矩形");
                assertEquals(it.bgColor(), t.primitives.getInt(s, PropertyTrees.P_COLOR));
                assertEquals(it.opacity() != 1f, sync.effectSlot(s) >= 0);
                assertEquals(it.translateX() != 0f, sync.transformSlot(s) >= 0);
            }
        }
    }

    @Test
    void steadyStateSyncAndScrollStepAllocateNothing() {
        PropertyTrees t = trees(new FakeDevice(true));
        GpuSceneSync sync = new GpuSceneSync(t);
        List<DisplayItem> items = new ArrayList<>();
        for (int i = 0; i < 200; i++) items.add(item("z" + i, i, 0xFF000000 | i, i % 3 == 0 ? 0.5f : 1f, i % 4 == 0 ? 3f : 0f));
        for (int i = 0; i < 20_000; i++) { sync.sync(items); t.flush(); t.setScrollOffset(1, 0, i); }
        var mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().getId();
        long before = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 20_000; i++) { sync.sync(items); t.flush(); t.setScrollOffset(1, 0, i); }
        long used = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(used < 8192, "定常状態の同期・スクロール更新がヒープを確保している: " + used + " B");
    }

    @Test
    void scrollStepWritesOnlyTheScrollSlotAndNoRecord() {
        FakeDevice dev = new FakeDevice(false);
        PropertyTrees t = trees(dev);
        int content = t.addTransform(PropertyTrees.NONE, 0, 0, 1, 1, 0);
        t.setScroll(content, 5);
        t.flush();
        long transformsBefore = t.transforms.uploadedBytes();
        for (int i = 1; i <= 100; i++) {
            t.setScrollOffset(5, 0, i * 3f);
            assertEquals(8, t.flush(), "スクロール 1 件 = 8 バイトだけ");
        }
        assertEquals(transformsBefore, t.transforms.uploadedBytes(), "変換レコードは書き換えない");
        assertEquals(300f, t.scrollY(5));
        FakeDevice persistent = new FakeDevice(true);
        PropertyTrees p = trees(persistent);
        p.setScrollOffset(5, 0, 9f);
        assertEquals(0, p.flush(), "永続マップなら転送そのものが無い");
    }
}
