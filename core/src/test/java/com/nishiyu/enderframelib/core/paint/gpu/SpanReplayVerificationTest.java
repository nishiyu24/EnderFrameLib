package com.nishiyu.enderframelib.core.paint.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.kernel.Shortcut;
import com.nishiyu.enderframelib.core.kernel.gpu.PropertyTrees;
import com.nishiyu.enderframelib.core.kernel.gpu.rhi.RecordingGpuDevice;
import com.nishiyu.enderframelib.core.paint.gl.CpuLayerFallback;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

/**
 * 項目の再生（{@code spanReplay}）の検証モード（C0 ②。{@code VERIFY_MEMO}）。再生できる項目を再生せずに描き直し、前フレームの出力
 * （変形・効果・図形・クリップ・描画順）と同じか比べる。GPU を使わない {@link RecordingGpuDevice} で CI でも回る。
 */
class SpanReplayVerificationTest {
    private static String html() {
        StringBuilder sb = new StringBuilder("<body style=\"margin:0;background:#10141c\">");
        for (int i = 0; i < 24; i++) {
            sb.append("<div style=\"width:60px;height:12px;margin:2px;border-radius:3px;background:rgb(").append(40 + i * 7).append(",90,160)\"></div>");
        }
        return sb.append("<p id=\"t\" style=\"color:#fff;font-size:12px\">0</p></body>").toString();
    }

    private static GpuRenderBackend backend(RecordingGpuDevice rec) {
        return new GpuRenderBackend(() -> rec, CpuLayerFallback::new, 200, 400, 1f);
    }

    private static RectStream rects(GpuRenderBackend b) throws ReflectiveOperationException {
        var f = GpuRenderBackend.class.getDeclaredField("rects");
        f.setAccessible(true);
        return (RectStream) f.get(b);
    }

    private static void withVerify(boolean on, Runnable body) {
        boolean was = Shortcut.VERIFY_MEMO.on();
        Shortcut.VERIFY_MEMO.set(on);
        try { body.run(); } finally { Shortcut.VERIFY_MEMO.set(was); }
    }

    @Test
    void unchangedItemsMatchTheirReplayAcrossTextChanges() {
        withVerify(true, () -> {
            RecordingGpuDevice rec = new RecordingGpuDevice();
            try (GpuRenderBackend backend = backend(rec); StandaloneView view = new StandaloneView(html(), 200, 400, FontResolver.FALLBACK)) {
                long verified0 = RectStream.STAT_VERIFIED_SPANS.sum();
                long now = 1000;
                view.frame(now, backend, true);
                HtmlNode text = view.root().getNodeById("t");
                for (int i = 1; i <= 6; i++) {
                    text.updateTextContent("n" + i);
                    now += 16;
                    view.frame(now, backend, true);
                }
                assertTrue(RectStream.STAT_VERIFIED_SPANS.sum() - verified0 > 10, "再生できる項目が検証された: " + (RectStream.STAT_VERIFIED_SPANS.sum() - verified0));
            }
        });
    }

    @Test
    void aStaleReplayIsCaught() throws Exception {
        withVerify(true, () -> {
            RecordingGpuDevice rec = new RecordingGpuDevice();
            try (GpuRenderBackend backend = backend(rec); StandaloneView view = new StandaloneView(html(), 200, 400, FontResolver.FALLBACK)) {
                long now = 1000;
                view.frame(now, backend, true);
                now += 16;
                view.frame(now, backend, true);
                // 前フレームの出力（図形）が、入力の変化を取りこぼして古くなっている状態を作る
                PropertyTrees trees = rects(backend).store().trees;
                for (int s = 0; s < 200; s++) trees.primitives.putFloat(s, 4, 12345f);
                HtmlNode text = view.root().getNodeById("t");
                text.updateTextContent("changed");
                now += 16;
                long t = now;
                AssertionError e = assertThrows(AssertionError.class, () -> view.frame(t, backend, true));
                assertTrue(e.getMessage().contains("項目の再生が古い"), e.getMessage());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    void withoutVerifyTheItemsAreReplayedInstead() {
        withVerify(false, () -> {
            RecordingGpuDevice rec = new RecordingGpuDevice();
            try (GpuRenderBackend backend = backend(rec); StandaloneView view = new StandaloneView(html(), 200, 400, FontResolver.FALLBACK)) {
                long verified0 = RectStream.STAT_VERIFIED_SPANS.sum();
                long now = 1000;
                view.frame(now, backend, true);
                HtmlNode text = view.root().getNodeById("t");
                for (int i = 1; i <= 4; i++) { text.updateTextContent("n" + i); now += 16; view.frame(now, backend, true); }
                assertEquals(0, RectStream.STAT_VERIFIED_SPANS.sum() - verified0);
                assertTrue(backend.rectTreeReplayedItems() > 0, "検証が無ければ再生される: " + backend.rectTreeReplayedItems());
            }
        });
    }
}
