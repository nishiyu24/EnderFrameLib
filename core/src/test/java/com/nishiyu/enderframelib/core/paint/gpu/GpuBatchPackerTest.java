package com.nishiyu.enderframelib.core.paint.gpu;

import com.nishiyu.enderframelib.common.SoftwareRendering;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 64 テクセルのインスタンスデータへの詰め込み（GL なしで確かめられる、API 非依存の部分）。 */
public class GpuBatchPackerTest {

    private static final int S = GpuBatchPacker.STRIDE * 4;
    private static final float[] R0 = {0, 0, 0, 0};
    private static final GpuSink.Clip[] NO_CLIPS = new GpuSink.Clip[0];

    private static final class RecordingHost implements GpuBatchPacker.Host {
        int before, flushes, touches;
        boolean tracks;
        float[] lastTouch;
        GpuBatchPacker packer;

        @Override
        public void beforeShape() {
            before++;
        }

        @Override
        public void flushBatch() {
            flushes++;
            packer.clear();
        }

        @Override
        public boolean tracksBounds() {
            return tracks;
        }

        @Override
        public void touch(float x0, float y0, float x1, float y1) {
            touches++;
            lastTouch = new float[]{x0, y0, x1, y1};
        }
    }

    private static GpuBatchPacker newPacker(RecordingHost host) {
        GpuBatchPacker p = new GpuBatchPacker(host);
        host.packer = p;
        return p;
    }

    @Test
    public void fillRoundRectPacksRectColorPoseAndMode() {
        RecordingHost host = new RecordingHost();
        GpuBatchPacker p = newPacker(host);
        Matrix2D pose = new Matrix2D(2, 0, 0, 3, 10, 20);
        assertTrue(p.fillRoundRect(pose, 1, 2, 30, 40, new float[]{4, 4, 4, 4}, new float[]{5, 5, 5, 5}, 0x80FF0000, NO_CLIPS, 0.5f));
        assertEquals(1, p.count());
        assertEquals(1, host.before, "積む前に、先に描くべきものを描かせる");
        float[] d = p.data();
        // テクセル 0: 描く範囲（1 画素ぶんの余白付き。姿勢の平均拡大率の逆数）
        float m = 1f / Math.max((float) pose.meanScale(), 0.01f);
        assertEquals(1 - m, d[0], 1e-4);
        assertEquals(40 + m, d[3], 1e-4);
        // テクセル 1-2: 姿勢と不透明度
        assertEquals(2f, d[4]);
        assertEquals(3f, d[4 + 3]);
        assertEquals(10f, d[8]);
        assertEquals(20f, d[9]);
        assertEquals(0.5f, d[8 + 3]);
        // テクセル 3: モード・穴・クリップ数
        assertEquals(GpuBatchPacker.MODE_FILL, d[12]);
        assertEquals(0f, d[15]);
        // テクセル 4-6: 矩形と角の半径
        assertEquals(30f, d[16 + 2]);
        assertEquals(4f, d[20]);
        assertEquals(5f, d[24]);
        // テクセル 15: 色（非乗算の ARGB を 0..1 の RGBA へ）
        assertEquals(1f, d[60], 1e-6);
        assertEquals(0f, d[61], 1e-6);
        assertEquals(128 / 255f, d[63], 1e-6);
    }

    @Test
    public void brokenPoseOrZeroOpacityPacksNothing() {
        RecordingHost host = new RecordingHost();
        GpuBatchPacker p = newPacker(host);
        Matrix2D singular = new Matrix2D(0, 0, 0, 0, 0, 0);
        assertTrue(p.fillRoundRect(singular, 0, 0, 10, 10, R0, R0, 0xFF000000, NO_CLIPS, 1f), "受け取った（描くものが無い）");
        assertTrue(p.fillRoundRect(Matrix2D.IDENTITY, 0, 0, 10, 10, R0, R0, 0xFF000000, NO_CLIPS, 0f));
        assertEquals(0, p.count());
        assertEquals(0, p.committedTotal());
    }

    @Test
    public void flushesWhenTheBatchIsFull() {
        RecordingHost host = new RecordingHost();
        GpuBatchPacker p = newPacker(host);
        for (int i = 0; i < GpuBatchPacker.MAX_BATCH; i++) {
            p.fillRoundRect(Matrix2D.IDENTITY, 0, 0, 5, 5, R0, R0, 0xFF000000, NO_CLIPS, 1f);
        }
        assertEquals(0, host.flushes);
        assertEquals(GpuBatchPacker.MAX_BATCH, p.count());
        p.fillRoundRect(Matrix2D.IDENTITY, 0, 0, 5, 5, R0, R0, 0xFF000000, NO_CLIPS, 1f);
        assertEquals(1, host.flushes);
        assertEquals(1, p.count());
        assertEquals(GpuBatchPacker.MAX_BATCH + 1, p.committedTotal());
    }

    @Test
    public void clipsAreWrittenFromTexelTwentyFour() {
        RecordingHost host = new RecordingHost();
        GpuBatchPacker p = newPacker(host);
        GpuSink.Clip clip = new GpuSink.Clip(new Matrix2D(1, 0, 0, 1, 7, 8), 2f, 1, 2, 3, 4,
                new float[]{1, 2, 3, 4}, new float[]{5, 6, 7, 8});
        p.fillRoundRect(Matrix2D.IDENTITY, 0, 0, 5, 5, R0, R0, 0xFF000000, new GpuSink.Clip[]{clip}, 1f);
        float[] d = p.data();
        assertEquals(1f, d[3 * 4 + 3], "クリップ数");
        assertEquals(7f, d[25 * 4]);
        assertEquals(2f, d[25 * 4 + 2], "スケール");
        assertEquals(4f, d[26 * 4 + 3]);
        assertEquals(1f, d[27 * 4]);
        assertEquals(8f, d[28 * 4 + 3]);
    }

    @Test
    public void dashedBorderMarksTheRowAndNeedsAPeriod() {
        RecordingHost host = new RecordingHost();
        GpuBatchPacker p = newPacker(host);
        float[] z = {0, 0, 0, 0};
        float[] w = {1, 1, 1, 1};
        int[] c = {0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000};
        assertFalse(p.strokeDashedBorder(Matrix2D.IDENTITY, 0, 0, 20, 20, z, z, 1, 1, 19, 19, z, z, false, c, w,
                10, 10, 10, 10, z, z, 0f, 2f, false, 1f, NO_CLIPS, 1f), "周期が無ければ詰めない");
        assertEquals(0, p.count());
        assertTrue(p.strokeDashedBorder(Matrix2D.IDENTITY, 0, 0, 20, 20, z, z, 1, 1, 19, 19, z, z, false, c, w,
                10, 10, 10, 10, z, z, 6f, 3f, true, 1f, NO_CLIPS, 1f));
        float[] d = p.data();
        assertEquals(1, p.count());
        assertEquals(GpuBatchPacker.MODE_STROKE, d[12]);
        assertEquals(1f, d[14], "破線の印");
        assertEquals(6f, d[47 * 4]);
        assertEquals(1f, d[47 * 4 + 2]);
    }

    @Test
    public void layerBoundsAreReportedOnlyWhenTracked() {
        RecordingHost host = new RecordingHost();
        GpuBatchPacker p = newPacker(host);
        int[] white = {-1, -1, -1, -1};
        p.strokeCircleBorder(Matrix2D.IDENTITY, 5, 5, 4, 2, 0, 0, 10, 10, white, NO_CLIPS, 1f);
        assertEquals(0, host.touches);
        host.tracks = true;
        p.strokeCircleBorder(new Matrix2D(1, 0, 0, 1, 3, 4), 5, 5, 4, 2, 0, 0, 10, 10, white, NO_CLIPS, 1f);
        assertEquals(1, host.touches);
        assertEquals(3f, host.lastTouch[0]);
        assertEquals(14f, host.lastTouch[3]);
    }

    /** GL を一切使わず、ソフトウェア描画から図形の命令だけを受け取ってパックできる（別 API のバックエンドと同じ入口）。 */
    @Test
    public void aHeadlessSinkCanConsumeTheScene() {
        RecordingHost host = new RecordingHost();
        GpuBatchPacker p = newPacker(host);
        List<Integer> modes = new ArrayList<>();
        GpuSink sink = new GpuSink() {
            @Override
            public boolean fillRoundRect(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                         int argb, Clip[] clips, float opacity) {
                boolean r = p.fillRoundRect(pose, x0, y0, x1, y1, rx, ry, argb, clips, opacity);
                modes.add((int) p.data()[(p.count() - 1) * S + 12]);
                return r;
            }

            @Override
            public boolean strokeRoundBorder(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                             float ix0, float iy0, float ix1, float iy1, float[] irx, float[] iry, boolean innerEmpty,
                                             int[] colors, float[] widths, Clip[] clips, float opacity) {
                boolean r = p.strokeRoundBorder(pose, x0, y0, x1, y1, rx, ry, ix0, iy0, ix1, iy1, irx, iry, innerEmpty,
                        colors, widths, clips, opacity);
                modes.add((int) p.data()[(p.count() - 1) * S + 12]);
                return r;
            }
        };
        List<DisplayItem> items = SoftwareRendering.displayList(
                "<html><body style='margin:0'><div style='width:80px;height:40px;background:#c33;border:3px solid #333;border-radius:8px'></div></body></html>",
                100, 60);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(100, 60, 1f)) {
            cpu.renderOnto(items, 0, sink);
        }
        assertTrue(modes.contains(GpuBatchPacker.MODE_FILL), "背景の塗りが届く: " + modes);
        assertTrue(modes.contains(GpuBatchPacker.MODE_STROKE), "枠線が届く: " + modes);
    }

    /** 転送 → 描画の順に、溜めた分だけを渡し、渡したあとは空になる（どの API の BatchDevice でも守る契約）。 */
    @Test
    public void flushToUploadsThenDrawsThenEmpties() {
        RecordingHost host = new RecordingHost();
        GpuBatchPacker p = newPacker(host);
        List<String> calls = new ArrayList<>();
        float[][] seen = new float[1][];
        BatchDevice device = new BatchDevice() {
            @Override
            public void uploadInstances(float[] data, int n) {
                calls.add("upload" + n);
                seen[0] = java.util.Arrays.copyOf(data, n * S);
            }

            @Override
            public void drawInstances(int n) {
                calls.add("draw" + n);
            }
        };
        assertEquals(0, p.flushTo(device), "空なら何も呼ばない");
        assertTrue(calls.isEmpty());
        p.fillRoundRect(Matrix2D.IDENTITY, 0, 0, 5, 5, R0, R0, 0xFF112233, NO_CLIPS, 1f);
        p.fillRoundRect(Matrix2D.IDENTITY, 1, 1, 6, 6, R0, R0, 0xFF445566, NO_CLIPS, 1f);
        float[] expected = java.util.Arrays.copyOf(p.data(), 2 * S);
        assertEquals(2, p.flushTo(device));
        assertEquals(List.of("upload2", "draw2"), calls);
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, seen[0]);
        assertEquals(0, p.count());
        assertEquals(0, p.flushTo(device), "渡したあとは空");
    }

    /** 縁取りの字形は、専用のモードで、アトラスの位置・大きさと縁取りの半幅（slot 22）を詰める。 */
    @Test
    public void glyphStrokePacksSlotAndHalfWidth() {
        RecordingHost host = new RecordingHost();
        GpuBatchPacker p = newPacker(host);
        com.nishiyu.enderframelib.core.text.font.GlyphBitmap sdf = new com.nishiyu.enderframelib.core.text.font.GlyphBitmap(6, 5, -2, 7, new byte[30]);
        assertTrue(p.glyphStroke(sdf, new int[]{10, 20}, 100, 50, 1.5f, 0xFF3366FF, NO_CLIPS, 1f));
        assertEquals(1, p.count());
        float[] d = p.data();
        assertEquals(GpuBatchPacker.MODE_GLYPH_STROKE, (int) d[12]);
        assertEquals(10f, d[21 * 4]);
        assertEquals(20f, d[21 * 4 + 1]);
        assertEquals(6f, d[21 * 4 + 2]);
        assertEquals(5f, d[21 * 4 + 3]);
        assertEquals(1.5f, d[22 * 4], 1e-6);
        assertEquals(1, host.before, "描く前に beforeShape が呼ばれる");
    }
}
