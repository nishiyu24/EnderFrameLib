package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.PaintOffsets;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * C6: 描画上の平行移動の外部表（{@link PaintOffsets}）と不変リストのブロック共有、および外部表を通した
 * ソフトウェア描画・姿勢解決の統合を検証する。
 */
class PaintOffsetsTest {

    private static DisplayItem node(String id, int x, int y, int w, int h) {
        return new DisplayItem(id, id, ElementType.NODE, x, y, w, h, "", 0, 0, Map.of(), 0, 1f,
                0f, 0f, 1f, 1f, 0f, 0, null, null, null);
    }

    private static DisplayItem rect(String id, int x, int y, int w, int h, int bg) {
        return new DisplayItem(id, id, ElementType.RECT, x, y, w, h, "", 0, bg, Map.of(), 0, 1f,
                0f, 0f, 1f, 1f, 0f, 0, null, null, null);
    }

    @Test
    void emptyTableIsImmutableAndAllMutationsWorkWithAccurateVersioning() {
        assertThrows(UnsupportedOperationException.class, () -> PaintOffsets.EMPTY.set("a", 1f, 2f));
        assertThrows(UnsupportedOperationException.class, () -> PaintOffsets.EMPTY.clear("a"));
        assertThrows(UnsupportedOperationException.class, () -> PaintOffsets.EMPTY.clearAll());

        PaintOffsets off = new PaintOffsets();
        assertTrue(off.isEmpty());
        assertEquals(0, off.version());
        assertEquals(0, off.keysVersion());

        // (0, 0) の新規登録は keysVersion だけ進める
        assertFalse(off.set("zero", 0f, 0f));
        assertEquals(0, off.version());
        assertEquals(1, off.keysVersion());

        assertTrue(off.set("win", 12f, -4f));
        int v1 = off.version();
        int kv1 = off.keysVersion();
        assertEquals(1, v1);
        assertEquals(2, kv1);

        // 同じ値の再設定は何も変えない
        assertFalse(off.set("win", 12f, -4f));
        assertEquals(v1, off.version());
        assertEquals(kv1, off.keysVersion());

        // 値だけの更新は version だけ進める
        assertTrue(off.set("win", 20f, -4f));
        assertTrue(off.version() > v1);
        assertEquals(kv1, off.keysVersion());

        // ElementType.NODE だけに効く
        DisplayItem n = node("win", 0, 0, 50, 50);
        DisplayItem r = rect("win", 0, 0, 50, 50, 0xFF112233);
        assertEquals(20f, off.translateX(n));
        assertEquals(-4f, off.translateY(n));
        assertEquals(0f, off.translateX(r));
        assertEquals(0f, off.translateY(r));

        // snapshot は元の表の変更を受けない不変写し（変更が無ければ同じ実体を返し、再設定時だけ新しくする）
        assertFalse(off.isImmutable());
        assertTrue(PaintOffsets.EMPTY.isImmutable());
        PaintOffsets snap = off.snapshot();
        assertTrue(snap.isImmutable());
        assertSame(snap, off.snapshot(), "変更が無ければ同じスナップショットを共有する");
        assertSame(snap, snap.snapshot());
        assertTrue(off.clear("win"));
        assertEquals(20f, snap.dx("win"));
        assertEquals(0f, off.dx("win"));
        assertThrows(UnsupportedOperationException.class, () -> snap.set("x", 1f, 1f));
    }

    @Test
    void immutableItemListSharesUnchangedBlocksAndPreservesIdentityWhenUnchanged() {
        List<DisplayItem> src = new ArrayList<>();
        for (int i = 0; i < 80; i++) src.add(rect("i" + i, i, 0, 10, 10, 0xFF000000 | i));
        ImmutableItemList a = ImmutableItemList.from(null, src);
        assertSame(a, ImmutableItemList.from(a, new ArrayList<>(src)), "差し替え無ければ同じスナップショット");

        List<DisplayItem> mod = new ArrayList<>(src);
        mod.set(5, rect("i5", 5, 1, 10, 10, 0xFF123456));
        ImmutableItemList b = ImmutableItemList.from(a, mod);
        assertNotSame(a, b);
        assertNotSame(a.chunkAt(0), b.chunkAt(0), "変更ブロック 0 だけ新しい配列");
        assertSame(a.chunkAt(1), b.chunkAt(1), "未変更ブロック 1 は共有");
        assertSame(a.chunkAt(2), b.chunkAt(2), "未変更ブロック 2 は共有");
    }

    @Test
    void softwareRenderBackendRendersOffsetsAndScrollSnapWithoutMutatingItems() {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            rows.append("<div style='height:20px;margin:2px;background:#").append((i % 2 == 0) ? "48c" : "c84").append("'>row ").append(i).append("</div>");
        }
        String html = "<style>body{margin:0}#box{position:absolute;left:10px;top:10px;width:140px;height:120px;overflow-y:auto}</style>"
                + "<div id='box'>" + rows + "</div>";
        StandaloneView view = new StandaloneView(html, 180, 160, FontResolver.FALLBACK);
        try (SoftwareRenderBackend inc = new SoftwareRenderBackend(180, 160, 1f);
             SoftwareRenderBackend full = new SoftwareRenderBackend(180, 160, 1f)) {
            view.frame(1000, inc, true);
            List<DisplayItem> before = view.ui().renderCache();
            view.mouseMove(80, 70, inc.sessionId());
            view.scroll(0, -3, inc.sessionId());
            view.frame(1016, inc, true);
            List<DisplayItem> after = view.ui().renderCache();
            assertSame(before, after, "スクロールしても不変リストは同じ実体");
            assertFalse(view.ui().paintOffsets().isEmpty());

            // 新しい SoftwareRenderBackend でゼロから描いた絵と完全一致
            full.render(com.nishiyu.enderframelib.core.paint.RenderFrame.of(after, view.ui().paintOffsets(), 1016));
            assertArrayEquals(full.pixmap().pixels(), inc.pixmap().pixels());

            // GPU のスクロール丸めは項目や PaintOffsets を書き換えずに姿勢だけ丸める
            PaintOffsets o = new PaintOffsets();
            DisplayItem scrollNode = new DisplayItem("s_scrollc", "s", ElementType.NODE, 0, 0, 100, 100, "", 0, 0,
                    Map.of(), 0, 1f, 0f, 0f, 1f, 1f, 0f, 0, null, null, null,
                    DisplayItem.keyOf(7, DisplayItem.PART_SCROLL_CONTENT));
            o.set("s_scrollc", 0f, -10.3f);
            SceneWalker walker = new SceneWalker("snap-test");
            walker.setScrollSnapDpr(2f);
            float[] f = {Float.NaN};
            walker.run(com.nishiyu.enderframelib.core.paint.RenderFrame.of(List.of(scrollNode), o.snapshot(), 1000), Matrix2D.IDENTITY, new Painter() {
                @Override public void syncLayers(List<SceneWalker.Group> want) { f[0] = (float) walker.resolveAt(0).pose().f(); }
                @Override public void drawItem(DisplayItem el, int index, float flat) { }
            }, null);
            assertEquals(-10.5f, f[0], 1e-4f);
            assertEquals(-10.3f, o.dy("s_scrollc"), "元の PaintOffsets は丸めで上書きしない");
            assertEquals(0f, scrollNode.translateY(), "項目も不変");
        } finally {
            view.close();
        }
    }

    @Test
    void setIfActiveSkipsDeadZeroEntriesAndUpdatesExistingBackToZero() {
        PaintOffsets off = new PaintOffsets();
        assertFalse(off.setIfActive("idle", 0f, 0f));
        assertTrue(off.isEmpty());
        assertEquals(0, off.version());
        assertEquals(0, off.keysVersion());

        assertTrue(off.setIfActive("box", 4f, -8f));
        assertEquals(1, off.size());
        assertEquals(1, off.version());
        assertEquals(1, off.keysVersion());

        PaintOffsets snap1 = off.snapshot();
        assertEquals(4f, snap1.dx("box"));
        assertTrue(off.setIfActive("box", 0f, 0f));
        assertEquals(2, off.version());
        assertEquals(1, off.keysVersion());
        assertEquals(0f, off.dx("box"));
        assertEquals(0f, off.dy("box"));
        assertEquals(4f, snap1.dx("box"));

        PaintOffsets snap2 = off.snapshot();
        assertThrows(UnsupportedOperationException.class, () -> PaintOffsets.EMPTY.setIfActive("x", 1f, 0f));
        assertThrows(UnsupportedOperationException.class, () -> snap2.setIfActive("x", 1f, 0f));
    }
}
