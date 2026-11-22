package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.PaintFrame;
import com.nishiyu.enderframelib.core.paint.model.PaintOffsets;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * S7 ③: {@link PaintOffsets} のフレーム境界スナップショット契約と {@link PaintFrame} PDU の
 * 凍結・並行描画（B7 前提）を検証する。
 */
class FrameBoundarySnapshotTest {

    private static final class NoDrawBackend implements RenderBackend, AutoCloseable {
        @Override public void render(List<DisplayItem> items, long nowMs) {}
        @Override public Pixmap pixmap() { return null; }
        @Override public float devicePixelRatio() { return 1f; }
        @Override public String sessionId() { return "s7-test"; }
        @Override public void close() {}
    }

    private static DisplayItem node(String id, int x, int y, int w, int h) {
        return new DisplayItem(id, id, ElementType.NODE, x, y, w, h, "", 0, 0, Map.of(), 0, 1f,
                0f, 0f, 1f, 1f, 0f, 0, null, null, null);
    }

    private static DisplayItem rect(String id, int x, int y, int w, int h, int bg) {
        return new DisplayItem(id, id, ElementType.RECT, x, y, w, h, "", 0, bg, Map.of(), 0, 1f,
                0f, 0f, 1f, 1f, 0f, 0, null, null, null);
    }

    @Test
    void paintFrameFreezesMutableInputsAndSharesImmutableInputs() {
        List<DisplayItem> mutableList = new ArrayList<>();
        mutableList.add(node("n1", 0, 0, 20, 20));
        mutableList.add(rect("n1_bg", 0, 0, 20, 20, 0xFF224466));
        PaintOffsets mutableOff = new PaintOffsets();
        mutableOff.set("n1", 12f, -3f);

        PaintFrame frame = PaintFrame.of(mutableList, mutableOff);
        assertTrue(frame.offsets().isImmutable());
        assertThrows(UnsupportedOperationException.class, () -> frame.items().add(rect("x", 0, 0, 1, 1, 0)));
        assertThrows(UnsupportedOperationException.class, () -> frame.offsets().set("n1", 99f, 99f));

        // 元の可変リストと可変表を変えても、フレーム PDU は不変
        mutableList.clear();
        mutableOff.set("n1", 100f, 200f);
        assertEquals(2, frame.items().size());
        assertEquals(12f, frame.offsets().dx("n1"));
        assertEquals(-3f, frame.offsets().dy("n1"));

        // ImmutableItemList と不変 PaintOffsets は複製せずそのまま共有される
        ImmutableItemList frozenList = ImmutableItemList.from(null, frame.items());
        PaintFrame shared = PaintFrame.of(frozenList, frame.offsets());
        assertSame(frozenList, shared.items());
        assertSame(frame.offsets(), shared.offsets());
    }

    @Test
    void uiViewPaintOffsetsAndFrameSnapshotAreIsolatedFromSubsequentMutations() {
        StandaloneView view = new StandaloneView(
                "<style>body{margin:0}#box{position:absolute;left:0;top:0;width:80px;height:80px;background:#369}</style><div id='box'>hi</div>",
                120, 120, FontResolver.FALLBACK);
        try (var backend = new NoDrawBackend()) {
            view.frame(1000, backend, true);
            view.ui().setPaintOffset("box", 15f, 25f);
            PaintOffsets snap1 = view.ui().paintOffsets();
            PaintFrame frame1 = view.frameSnapshot();
            assertTrue(snap1.isImmutable());
            assertSame(snap1, frame1.offsets());
            assertThrows(UnsupportedOperationException.class, () -> snap1.set("box", 0f, 0f));

            // 後の変更が既取得スナップショットに影響しない
            view.ui().setPaintOffset("box", 80f, 90f);
            assertEquals(15f, snap1.dx("box"));
            assertEquals(25f, snap1.dy("box"));
            assertEquals(15f, frame1.offsets().dx("box"));

            view.ui().clearPaintOffset("box");
            assertEquals(15f, snap1.dx("box"));
            assertEquals(0f, view.ui().paintOffsets().dx("box"));
        } finally {
            view.close();
        }
    }

    @Test
    void concurrentLogicUpdatesAndRenderThreadConsumptionNeverTearOrThrow() throws Exception {
        StandaloneView view = new StandaloneView(
                "<style>body{margin:0}#box{position:absolute;left:0;top:0;width:20px;height:20px;background:#0f0}</style><div id='box'></div>",
                100, 100, FontResolver.FALLBACK);
        try (var noDraw = new NoDrawBackend();
             SoftwareRenderBackend sw = new SoftwareRenderBackend(100, 100, 1f)) {
            view.frame(1000, noDraw, true);
            AtomicReference<PaintFrame> bus = new AtomicReference<>(view.frameSnapshot());
            StaticFrameGate gate = new StaticFrameGate(true);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<?> logic = pool.submit(() -> {
                    start.await();
                    for (int i = 1; i <= 300; i++) {
                        float dx = i % 60;
                        view.ui().setPaintOffset("box", dx, dx * 2f);
                        bus.set(view.frameSnapshot());
                    }
                    return null;
                });
                Future<?> render = pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 300; i++) {
                        PaintFrame f = bus.get();
                        assertTrue(f.offsets().isImmutable());
                        float dx = f.offsets().dx("box");
                        float dy = f.offsets().dy("box");
                        assertEquals(dx * 2f, dy, 0.001f, "dx/dy が別フレームの値と混ざらない");
                        if (!gate.canSkip(f, 0)) {
                            sw.render(f, 1000L + i);
                            gate.painted(f, 0, false);
                        }
                    }
                    return null;
                });
                start.countDown();
                logic.get();
                render.get();
            } finally {
                pool.shutdownNow();
            }
        } finally {
            view.close();
        }
    }
}
