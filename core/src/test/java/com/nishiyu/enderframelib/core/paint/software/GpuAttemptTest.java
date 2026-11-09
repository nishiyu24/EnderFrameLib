package com.nishiyu.enderframelib.core.paint.software;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.SoftwareRendering;
import com.nishiyu.enderframelib.core.paint.gpu.GpuClip;
import com.nishiyu.enderframelib.core.paint.gpu.GpuSink;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Phase A-2: 単色角丸矩形の「GPU へ渡せるか」の試行を CPU の命令生成から分けた窓口 {@link GpuAttempt}。 */
class GpuAttemptTest {

    /** 単色角丸矩形だけを記録する GPU 側。{@code accept} が false なら断る。 */
    private static final class FakeSink implements GpuSink {
        final boolean accept;
        final List<Integer> fills = new ArrayList<>();
        int strokes;
        int lastClips = -1;

        FakeSink(boolean accept) { this.accept = accept; }

        @Override
        public boolean fillRoundRect(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                     int argb, GpuClip[] clips, float opacity) {
            fills.add(argb);
            lastClips = clips.length;
            return accept;
        }

        @Override
        public boolean strokeRoundBorder(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                         float ix0, float iy0, float ix1, float iy1, float[] irx, float[] iry, boolean innerEmpty,
                                         int[] colors, float[] widths, GpuClip[] clips, float opacity) {
            strokes++;
            return false;
        }
    }

    private static Clip clipWith(int parts) {
        ClipPart p = new ClipPart(Matrix2D.IDENTITY, 1f, 0, 0, 10, 10, new float[4], new float[4], 0, 0, 10, 10, 0, 0, 10, 10, 0, 0, 10, 10);
        return new Clip(Collections_nCopies(parts, p), 0, 0, 10, 10, 0, 0, 10, 10);
    }

    private static List<ClipPart> Collections_nCopies(int n, ClipPart p) {
        return java.util.Collections.nCopies(n, p);
    }

    private static final float[] Z = new float[4];

    @Test
    void noSinkOrRefusalOrTooManyClipsMeansTheCpuPaints() {
        assertFalse(GpuAttempt.fillRoundRect(null, Matrix2D.IDENTITY, 0, 0, 5, 5, Z, Z, 0xff112233, clipWith(0), 1f));
        FakeSink no = new FakeSink(false);
        assertFalse(GpuAttempt.fillRoundRect(no, Matrix2D.IDENTITY, 0, 0, 5, 5, Z, Z, 0xff112233, clipWith(0), 1f));
        assertEquals(List.of(0xff112233), no.fills, "断られても試行は 1 回だけ");
        FakeSink yes = new FakeSink(true);
        assertTrue(GpuAttempt.fillRoundRect(yes, Matrix2D.IDENTITY, 0, 0, 5, 5, Z, Z, 0xff112233, clipWith(2), 1f));
        assertEquals(2, yes.lastClips);
        FakeSink many = new FakeSink(true);
        assertFalse(GpuAttempt.fillRoundRect(many, Matrix2D.IDENTITY, 0, 0, 5, 5, Z, Z, 0xff112233, clipWith(GpuSink.MAX_CLIPS + 1), 1f));
        assertTrue(many.fills.isEmpty(), "クリップが多すぎるときは GPU に聞かない");
    }

    private static final String HTML = "<html><body style='margin:0'><div style='width:60px;height:30px;background:#c33;border-radius:8px'></div></body></html>";

    @Test
    void acceptedFillIsNotPaintedByTheCpu() {
        List<DisplayItem> items = SoftwareRendering.displayList(HTML, 80, 50);
        FakeSink sink = new FakeSink(true);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(80, 50, 1f)) {
            cpu.renderOnto(items, 0, sink);
            assertFalse(sink.fills.isEmpty(), "背景の塗りが GPU に届く");
            assertNull(cpu.dirtyBounds(), "GPU が受け取った図形は CPU が塗らない");
            assertFalse(cpu.isRootAllocated(), "CPU の層は確保されない");
        }
    }

    @Test
    void refusedFillFallsBackToExactlyTheSamePixelsAsNoSink() {
        List<DisplayItem> items = SoftwareRendering.displayList(HTML, 80, 50);
        int[] withRefusal, plain;
        FakeSink sink = new FakeSink(false);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(80, 50, 1f)) {
            cpu.renderOnto(items, 0, sink);
            withRefusal = cpu.pixmap().pixels().clone();
        }
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(80, 50, 1f)) {
            cpu.render(items, 0);
            plain = cpu.pixmap().pixels().clone();
        }
        assertFalse(sink.fills.isEmpty());
        assertTrue(Arrays.stream(plain).anyMatch(v -> v != 0), "何か塗られている");
        assertArrayEquals(plain, withRefusal);
    }

    @Test
    void gpuClipConversionStaysOutOfTheBackend() throws Exception {
        Path src = Path.of("src/main/java/com/nishiyu/enderframelib/core/paint/software/SoftwareRenderBackend.java");
        if (!Files.exists(src)) src = Path.of("core").resolve(src);
        String t = Files.readString(src);
        assertFalse(t.contains("GpuClip[] gpuClips("), "クリップの GPU 変換は GpuAttempt へ移した");
        assertFalse(t.contains("sink.fillRoundRect("), "単色角丸矩形の GPU 試行は GpuAttempt 経由");
    }
}
