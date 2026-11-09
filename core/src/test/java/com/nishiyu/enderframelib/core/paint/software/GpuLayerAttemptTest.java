package com.nishiyu.enderframelib.core.paint.software;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.SoftwareRendering;
import com.nishiyu.enderframelib.core.paint.BlendMode;
import com.nishiyu.enderframelib.core.paint.FilterOp;
import com.nishiyu.enderframelib.core.paint.PaintSceneDriver.Group;
import com.nishiyu.enderframelib.core.paint.RetainPlan;
import com.nishiyu.enderframelib.core.paint.gpu.GpuClip;
import com.nishiyu.enderframelib.core.paint.gpu.GpuScrollLayer;
import com.nishiyu.enderframelib.core.paint.gpu.GpuSink;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.LayerSpec;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Phase A-2 最終: 層（グループ・マスク・スクロール保持層）の GPU 問い合わせを {@link GpuLayerAttempt} へ集約した後の挙動。 */
class GpuLayerAttemptTest {

    private static final class LayerSink implements GpuSink {
        final boolean accept;
        int begins, ends, masks, endMasks, plans, draws, beginScrolls, endScrolls;
        float opacity = -1;
        BlendMode blend;
        boolean sawExtras;
        RetainPlan plan = RetainPlan.NONE;
        boolean endScrollResult = true;

        LayerSink(boolean accept) { this.accept = accept; }

        @Override
        public boolean fillRoundRect(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                     int argb, GpuClip[] clips, float opacity) { return false; }

        @Override
        public boolean strokeRoundBorder(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                         float ix0, float iy0, float ix1, float iy1, float[] irx, float[] iry, boolean innerEmpty,
                                         int[] colors, float[] widths, GpuClip[] clips, float opacity) { return false; }

        @Override
        public boolean beginGroup(float opacity, List<FilterOp> filters, double[] map, boolean masked, boolean maskLuminance,
                                  BlendMode blend, GroupExtras extras) {
            begins++; this.opacity = opacity; this.blend = blend; sawExtras |= extras != null;
            return accept;
        }

        @Override public void endGroup() { ends++; }
        @Override public boolean beginMask() { masks++; return accept; }
        @Override public void endMask() { endMasks++; }
        @Override public RetainPlan planScrollLayer(GpuScrollLayer l) { plans++; return plan; }
        @Override public void drawScrollLayer(GpuScrollLayer l) { draws++; }
        @Override public void beginScrollLayer(GpuScrollLayer l) { beginScrolls++; }
        @Override public boolean endScrollLayer(GpuScrollLayer l) { endScrolls++; return endScrollResult; }
    }

    private static Group group(float opacity, LayerSpec spec) {
        return new Group("g", opacity, 0, spec, new double[]{1, 0, 0, 1}, null, Matrix2D.IDENTITY);
    }

    @Test
    void noSinkMeansNothingIsHeldOnTheGpu() {
        Group g = group(0.5f, null);
        assertFalse(GpuLayerAttempt.beginGroup(null, g));
        assertFalse(GpuLayerAttempt.beginMask(null));
        assertEquals(RetainPlan.NONE, GpuLayerAttempt.planScroll(null, null));
        assertFalse(GpuLayerAttempt.beginScroll(null, new GpuScrollLayer("k", null, Matrix2D.IDENTITY, null, null)));
        assertTrue(GpuLayerAttempt.endScroll(null, null));
        GpuLayerAttempt.drawScroll(null, null); // 例外なし
    }

    @Test
    void groupDetailsReachTheSinkAndAnswerIsPassedBack() {
        LayerSink no = new LayerSink(false), yes = new LayerSink(true);
        Group g = group(0.25f, null);
        assertFalse(GpuLayerAttempt.beginGroup(no, g));
        assertTrue(GpuLayerAttempt.beginGroup(yes, g));
        assertEquals(0.25f, yes.opacity);
        assertEquals(BlendMode.NORMAL, yes.blend, "spec がなければ通常の混合");
        assertFalse(yes.sawExtras, "クリップも backdrop もなければ extras なし");
        assertFalse(GpuLayerAttempt.beginMask(no));
        assertTrue(GpuLayerAttempt.beginMask(yes));
    }

    @Test
    void endRoutesToGroupOrMask() {
        LayerSink s = new LayerSink(true);
        GpuLayerAttempt.end(s, false);
        GpuLayerAttempt.end(s, true);
        GpuLayerAttempt.end(s, true);
        assertEquals(1, s.ends);
        assertEquals(2, s.endMasks);
    }

    @Test
    void scrollLayerProtocolIsForwarded() {
        LayerSink s = new LayerSink(true);
        GpuScrollLayer l = new GpuScrollLayer("k", null, Matrix2D.IDENTITY, null, null);
        s.plan = RetainPlan.RECORD;
        assertEquals(RetainPlan.RECORD, GpuLayerAttempt.planScroll(s, l));
        assertFalse(GpuLayerAttempt.beginScroll(s, null), "層の情報がなければ記録しない");
        assertTrue(GpuLayerAttempt.beginScroll(s, l));
        GpuLayerAttempt.drawScroll(s, l);
        GpuLayerAttempt.drawScroll(s, null);
        assertTrue(GpuLayerAttempt.endScroll(s, l));
        s.endScrollResult = false;
        assertFalse(GpuLayerAttempt.endScroll(s, l), "保持できなかった答えが返る");
        assertTrue(GpuLayerAttempt.endScroll(s, null), "情報がなければ true");
        assertEquals(1, s.plans);
        assertEquals(1, s.beginScrolls);
        assertEquals(1, s.draws);
        assertEquals(2, s.endScrolls);
    }

    private static final String BLUR = "<html><body style=\"margin:0\"><div style=\"width:40px;height:20px;background:#f00;"
            + "filter:blur(2px)\"></div></body></html>";
    private static final String OPACITY = "<html><body style=\"margin:0\"><div style=\"width:40px;height:20px;opacity:.5\">"
            + "<div style=\"width:30px;height:10px;background:#00f\"></div><div style=\"width:30px;height:10px;"
            + "background:#0c0;margin-top:-5px\"></div></div></body></html>";

    private static int[] cpu(String html, GpuSink sink) {
        List<DisplayItem> items = SoftwareRendering.displayList(html, 80, 60);
        try (SoftwareRenderBackend b = new SoftwareRenderBackend(80, 60, 1f)) {
            if (sink == null) b.render(items, 0); else b.renderOnto(items, 0, sink);
            return b.pixmap().pixels().clone();
        }
    }

    @Test
    void acceptedLayersAreOpenedAndClosedInBalance() {
        for (String html : new String[]{BLUR, OPACITY}) {
            LayerSink s = new LayerSink(true);
            List<DisplayItem> items = SoftwareRendering.displayList(html, 80, 60);
            try (SoftwareRenderBackend b = new SoftwareRenderBackend(80, 60, 1f)) {
                b.renderOnto(items, 0, s);
            }
            assertTrue(s.begins >= 1, "層が GPU へ聞かれる: " + html);
            assertEquals(s.begins, s.ends, "開いた数だけ閉じる: " + html);
        }
    }

    @Test
    void refusedLayersFallBackToTheSamePixelsAsNoSink() {
        for (String html : new String[]{BLUR, OPACITY}) {
            LayerSink s = new LayerSink(false);
            int[] with = cpu(html, s);
            assertTrue(s.begins >= 1, html);
            assertEquals(0, s.ends, "断られた層は GPU に閉じさせない: " + html);
            assertArrayEquals(cpu(html, null), with, html);
        }
    }

    @Test
    void backendNeverCallsTheSinkDirectly() throws Exception {
        Path src = Path.of("src/main/java/com/nishiyu/enderframelib/core/paint/software/SoftwareRenderBackend.java");
        if (!Files.exists(src)) src = Path.of("core").resolve(src);
        String t = Files.readString(src);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b(?:sink|rootSink|s)\\.\\w+\\(").matcher(t);
        while (m.find()) fail("GpuSink の直接呼び出しが戻っている: " + m.group());
    }
}
