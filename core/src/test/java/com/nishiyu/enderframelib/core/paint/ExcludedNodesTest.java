package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.common.UiView;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * フレームの値の描かない節（M3b-4c-2b-1。{@link ExcludedNodes}）: ホストは列を絞らずに節を除く（MOD の HUD の {@code data-mount-entity}）。
 *
 * <ul>
 *   <li>除いた節が自分で塗る部品は描かず、節の子孫と兄弟は描く。除いた節の子は親の原点の上に描く。</li>
 *   <li>フレームの値はシーンを持ったまま: 何も変わらなければ描き直しは要らず、ほかの要素の変化は範囲だけ描き直す（画素は全面の描画と同じ）。</li>
 *   <li>除く節が前のフレームと違えば全面を描く。</li>
 * </ul>
 */
class ExcludedNodesTest {
    private static final int W = 200, H = 120;
    private static final ExcludedNodes MOUNTED = new ExcludedNodes("data-mount-entity");
    private static final String HTML = "<style>body{margin:0}div{position:absolute}"
            + "#m{left:10px;top:10px;width:80px;height:60px;background:#f00}"
            + "#c{left:20px;top:20px;width:20px;height:20px;background:#0f0}"
            + "#s{left:120px;top:10px;width:40px;height:40px;background:#00f}#s:hover{background:#ff0}</style>"
            + "<div id='m' data-mount-entity='1'><div id='c'></div></div><div id='s'></div>";

    private static int[] fullPixels(SoftwareRenderBackend b, RenderFrame f) {
        try (SoftwareRenderBackend fresh = new SoftwareRenderBackend(W, H, 1f, b.sessionId())) {
            fresh.render(f); // 新しい描画器の最初のフレームは全面
            return fresh.pixmap().pixels().clone();
        }
    }

    private static int rgb(SoftwareRenderBackend b, int x, int y) {
        return b.pixmap().get(x, y) & 0xFFFFFF;
    }

    @Test
    void excludedNodesPaintNothingOfTheirOwnButKeepTheirDescendantsAndSiblings() {
        try (StandaloneView v = new StandaloneView(HTML, W, H, FontResolver.FALLBACK);
             SoftwareRenderBackend b = new SoftwareRenderBackend(W, H, 1f)) {
            v.frame(1000, b, true);
            assertEquals(0xFF0000, rgb(b, 15, 15), "除かなければ赤い背景");
            UiView ui = v.ui();
            b.render(ui.renderFrame(List.of(), MOUNTED, 1016));
            assertEquals(b.pixmap().get(195, 115), b.pixmap().get(15, 15), "除いた節の背景は描かない（何も無い所と同じ色）");
            assertEquals(0x00FF00, rgb(b, 10 + 20 + 5, 10 + 20 + 5), "除いた節の子は描き、親の原点の上に置く");
            assertEquals(0x0000FF, rgb(b, 130, 20), "兄弟は描く");
            assertTrue(new ExcludedNodes("data-mount-entity").equals(MOUNTED), "値で比べる");
        }
    }

    @Test
    void framesWithExclusionKeepTheSceneSoStaticFramesSkipAndChangesRepaintOnlyTheirRange() {
        try (StandaloneView v = new StandaloneView(HTML, W, H, FontResolver.FALLBACK);
             SoftwareRenderBackend b = new SoftwareRenderBackend(W, H, 1f)) {
            UiView ui = v.ui();
            v.frame(1000, b, true);
            RenderFrame first = ui.renderFrame(List.of(), MOUNTED, 1016);
            assertNotNull(first.scene(), "除いてもフレームの値はシーンを持つ（絞った列と違い、前のフレームと比べられる）");
            b.render(first); // 除く節が変わったので全面
            PreparedFrame still = b.prepare(ui.renderFrame(List.of(), new ExcludedNodes("data-mount-entity"), 1032));
            assertFalse(still.needsRepaint(), "同じ値の除く節で何も変わらなければ描き直しは要らない");
            b.render(still);

            ui.setHoveredElement("s");
            ui.flushInvalidation();
            RenderFrame hovered = ui.renderFrame(List.of(), MOUNTED, 1048);
            long partial = b.partialFrames();
            b.render(hovered);
            assertEquals(partial + 1, b.partialFrames(), "兄弟の色の変化は範囲だけ描き直す");
            assertEquals(0xFFFF00, rgb(b, 130, 20));
            assertArrayEquals(fullPixels(b, hovered), b.pixmap().pixels(), "範囲だけ描いた画素 = 全面の描画（除く節つき）");
        }
    }

    @Test
    void changingTheExclusionRepaintsInFull() {
        try (StandaloneView v = new StandaloneView(HTML, W, H, FontResolver.FALLBACK);
             SoftwareRenderBackend b = new SoftwareRenderBackend(W, H, 1f)) {
            UiView ui = v.ui();
            v.frame(1000, b, true);
            b.render(ui.renderFrame(List.of(), MOUNTED, 1016));
            PreparedFrame back = b.prepare(ui.renderFrame(List.of(), null, 1032));
            assertTrue(back.needsRepaint(), "除く節をやめたら描き直す");
            assertNotNull(back.damage());
            assertTrue(back.damage().full(), "シーンが同じでも全面");
            long partial = b.partialFrames();
            b.render(back);
            assertEquals(partial, b.partialFrames());
            assertEquals(0xFF0000, rgb(b, 15, 15), "除くのをやめた節の背景を描く");
            assertNotEquals(MOUNTED, new ExcludedNodes("data-other"));
        }
    }
}
