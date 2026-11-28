package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.common.UiView;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 描画器の口のフレームの値（M3b-4c-2a。{@link RenderFrame}・{@link PreparedFrame}・{@link RenderBackend#prepare}）。
 *
 * <ul>
 *   <li>重ね描きは列の後ろに描き、シーンから組んだ部分の長さは列の長さ（重ね描きを列に足してシーンの部分を推定しない）。</li>
 *   <li>準備で比べる相手を 1 度だけ進め、変わらないフレームは描き直しが要らないと答える。</li>
 *   <li>最後に準備した値でない値（前に準備した値・2 度目の描画・準備しない描画）は全面を描き、画素は全面の描画と同じ。</li>
 * </ul>
 */
class RenderFrameTest {
    private static final int W = 240, H = 160;
    private static final String HTML = "<style>body{margin:0}#a{width:60px;height:20px;background:#3a3}#b{margin:8px;width:40px;height:30px;"
            + "background:#33a}</style><div id='a'></div><div id='b'></div><p>text</p>";

    private static RenderFrame frameOf(UiView ui, List<DisplayItem> overlays, long now) {
        return ui.renderFrame(overlays, null, now);
    }

    private static int[] fullPixels(SoftwareRenderBackend b, RenderFrame f) {
        try (SoftwareRenderBackend fresh = new SoftwareRenderBackend(W, H, 1f, b.sessionId())) {
            fresh.render(f); // 新しい描画器の最初のフレームは全面
            return fresh.pixmap().pixels().clone();
        }
    }

    @Test
    void overlaysAreDrawnAfterTheSceneItemsWithoutCopying() {
        DisplayItem a = new DisplayItem("a", "a", ElementType.RECT, 0, 0, 5, 5, "", 0, 0xFF00FF00, java.util.Map.of(), 0, 1f,
                0f, 0f, 1f, 1f, 0f, 0, null, null, null);
        DisplayItem o = new DisplayItem("o", "o", ElementType.RECT, 9, 9, 5, 5, "", 0, 0xFFFF0000, java.util.Map.of(), 0, 1f,
                0f, 0f, 1f, 1f, 0f, 0, null, null, null);
        RenderFrame plain = RenderFrame.of(List.of(a), 0L);
        assertSame(plain.items(), plain.drawList(), "重ね描きが無ければ列そのもの");
        assertEquals(0, PreparedFrame.full(plain).sceneItemCount(), "シーンが無ければシーンの部分は 0");
        RenderFrame with = new RenderFrame(List.of(a), List.of(o), null, null, null, 5L);
        assertEquals(List.of(a, o), with.drawList());
        assertFalse(with.isEmpty());
        assertTrue(RenderFrame.of(List.of(), 0L).isEmpty());
        RenderFrame snap = plain.snapshot();
        assertEquals(plain.items(), snap.items());
        assertSame(snap, snap.snapshot(), "不変の写しはそのまま共有する");
    }

    @Test
    void preparingAdvancesTheComparisonOnceAndUnchangedFramesNeedNoRepaint() {
        try (StandaloneView v = new StandaloneView(HTML, W, H, FontResolver.FALLBACK);
             SoftwareRenderBackend b = new SoftwareRenderBackend(W, H)) {
            long t = 1000;
            v.frame(t, b, true);
            RenderFrame f = frameOf(v.ui(), List.of(), t += 16);
            assertNotNull(f.scene(), "ビューのフレームはシーンを持つ");
            assertTrue(f.items().isEmpty(), "ビューのフレームは列を持たない（描画器が準備で組む）");
            PreparedFrame p = b.prepare(f);
            assertFalse(p.items().isEmpty(), "準備した値はシーンから組んだ列を持つ");
            assertEquals(p.items().size(), p.sceneItemCount());
            assertFalse(p.needsRepaint(), "何も変わらなければ描き直しは要らない");
            assertNotNull(p.damage());
            assertTrue(p.damage().isEmpty());
            assertSame(f, p.frame());

            DisplayItem paint = p.items().stream().filter(i -> i.type() != ElementType.NODE && i.width() > 0).findFirst().orElseThrow();
            RenderFrame withOverlay = frameOf(v.ui(), List.of(paint.withPosition(paint.x() + 90, paint.y() + 90)), t += 16);
            PreparedFrame q = b.prepare(withOverlay);
            assertTrue(q.needsRepaint(), "重ね描きを足したら描く");
            long partial = b.partialFrames();
            b.render(q);
            assertEquals(partial + 1, b.partialFrames(), "準備した値の範囲だけを描く（重ね描きはシーンの外の範囲）");
            assertArrayEquals(fullPixels(b, withOverlay), b.pixmap().pixels(), "範囲だけ描いた画素 = 全面の描画");
        }
    }

    @Test
    void staleOrReusedPreparedFramesArePaintedInFull() {
        try (StandaloneView v = new StandaloneView(HTML, W, H, FontResolver.FALLBACK);
             SoftwareRenderBackend b = new SoftwareRenderBackend(W, H)) {
            long t = 1000;
            v.frame(t, b, true);
            RenderFrame f1 = frameOf(v.ui(), List.of(), t += 16);
            PreparedFrame older = b.prepare(f1);
            RenderFrame f2 = frameOf(v.ui(), List.of(), t += 16);
            PreparedFrame newer = b.prepare(f2);
            long partial = b.partialFrames();
            // どちらも変わらないフレームで、準備の範囲は空。範囲を信じて描けば部分の描き直しに数えられる
            b.render(older); // 前に準備した値: 比べる相手は f2 まで進んでいるので、範囲を信じず全面
            assertEquals(partial, b.partialFrames(), "最後に準備した値でなければ全面");
            assertArrayEquals(fullPixels(b, f1), b.pixmap().pixels());
            b.render(newer); // 1 度目の render で最後の準備を捨てたので、これも全面
            assertEquals(partial, b.partialFrames(), "使った後の準備は信じない");
            assertArrayEquals(fullPixels(b, f2), b.pixmap().pixels());
            b.render(f2); // 準備して描く（変わらないので範囲は空。それでも画素は正しい）
            assertArrayEquals(fullPixels(b, f2), b.pixmap().pixels());
        }
    }
}
