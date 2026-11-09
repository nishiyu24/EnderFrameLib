package com.nishiyu.enderframelib.core.paint.software;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.SoftwareRendering;
import com.nishiyu.enderframelib.core.paint.gpu.GpuClip;
import com.nishiyu.enderframelib.core.paint.gpu.GpuSink;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Phase A-2 最終: バックエンドが {@code GpuSink} を保持せず、{@link GpuRoute} だけが向き先を持つ。 */
class GpuRouteTest {

    private static final class NullSink implements GpuSink {
        @Override
        public boolean fillRoundRect(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                     int argb, GpuClip[] clips, float opacity) { return false; }

        @Override
        public boolean strokeRoundBorder(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                         float ix0, float iy0, float ix1, float iy1, float[] irx, float[] iry, boolean innerEmpty,
                                         int[] colors, float[] widths, GpuClip[] clips, float opacity) { return false; }
    }

    @Test
    void routeFollowsCpuLayerNesting() {
        GpuSink gpu = new NullSink();
        GpuRoute r = new GpuRoute();
        assertNull(r.current());
        r.begin(gpu);
        assertSame(gpu, r.current());
        r.enterCpuLayer();
        assertNull(r.current(), "CPU の層の中は CPU が描く");
        assertSame(gpu, r.root(), "根の先は残る");
        r.leaveCpuLayer(true);
        assertNull(r.current(), "外にまだ CPU の層があるなら CPU のまま");
        r.leaveCpuLayer(false);
        assertSame(gpu, r.current(), "最後の CPU の層を閉じたら根の先へ戻る");
        r.end();
        assertNull(r.current());
        assertNull(r.root());
    }

    @Test
    void renderingWithAndWithoutSinkGivesSamePixelsWhenSinkRefusesEverything() {
        String html = "<html><body style='margin:0'><div style='width:40px;height:20px;background:#36c;border-radius:6px;opacity:.6'>"
                + "<div style='width:20px;height:10px;background:#c33'></div></div></body></html>";
        List<DisplayItem> items = SoftwareRendering.displayList(html, 80, 60);
        int[] a, b;
        try (SoftwareRenderBackend s = new SoftwareRenderBackend(80, 60, 1f)) {
            s.render(items, 0);
            a = s.pixmap().pixels().clone();
        }
        try (SoftwareRenderBackend s = new SoftwareRenderBackend(80, 60, 1f)) {
            s.renderOnto(items, 0, new NullSink());
            b = s.pixmap().pixels().clone();
        }
        assertArrayEquals(a, b);
    }

    @Test
    void backendHoldsNoGpuSinkField() throws Exception {
        Path src = Path.of("src/main/java/com/nishiyu/enderframelib/core/paint/software/SoftwareRenderBackend.java");
        if (!Files.exists(src)) src = Path.of("core").resolve(src);
        String t = Files.readString(src);
        for (String bad : new String[]{"private GpuSink", "GpuSink rootSink", "rootSink", "sink = "}) {
            assertFalse(t.contains(bad), bad + " は GpuRoute へ移した");
        }
    }
}
