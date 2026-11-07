package com.nishiyu.enderframelib.core.paint.gl;

import com.nishiyu.enderframelib.common.SoftwareRendering;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * インスタンスデータの転送（使用幅の絞り込み＋前回との差分）が、絵を変えずに転送量だけを減らすこと。
 * 同じバックエンドを使い回した結果が、毎回作り直したバックエンドの結果と画素まで一致する。GL を使えない環境ではスキップする。
 */
class InstanceUploadDiffTest {

    private static final long T0 = 10_000L;
    private static LwjglGlRenderBackend probe;
    private static String unavailable;

    /** このクラスは従来経路（GpuBatchPacker）の差分転送を検査するので、新経路を切って走らせる。 */
    private static boolean savedRectTrees;

    @BeforeAll
    static void probe() {
        savedRectTrees = LwjglGlRenderBackend.rectTrees;
        LwjglGlRenderBackend.rectTrees = false;
        try {
            probe = new LwjglGlRenderBackend(16, 16);
        } catch (Throwable t) {
            unavailable = t.toString();
        }
    }

    @AfterAll
    static void cleanup() {
        LwjglGlRenderBackend.rectTrees = savedRectTrees;
        if (probe != null) probe.close();
    }

    private static String page(int rows, String label) {
        StringBuilder sb = new StringBuilder("<html><body style='margin:0;background:#223'>");
        sb.append("<div style='overflow:hidden;width:300px;height:200px;border-radius:12px;border:2px dashed #fa0;")
                .append("box-shadow:0 4px 8px #000'>");
        for (int i = 0; i < rows; i++) {
            sb.append("<div style='height:18px;background:linear-gradient(90deg,#345,#567);border:1px solid #89a;")
                    .append("border-radius:4px;color:#fff'>").append(label).append(i).append("</div>");
        }
        return sb.append("</div></body></html>").toString();
    }

    private static Pixmap copy(Pixmap src) {
        Pixmap c = new Pixmap(src.width(), src.height());
        System.arraycopy(src.pixels(), 0, c.pixels(), 0, src.pixels().length);
        return c;
    }

    private static Pixmap fresh(String html) {
        try (LwjglGlRenderBackend g = new LwjglGlRenderBackend(320, 240)) {
            SoftwareRendering.renderHtml(html, 320, 240, T0, g);
            return copy(g.pixmap());
        }
    }

    @Test
    void reusedBackendMatchesFreshAndSendsLess() {
        Assumptions.assumeTrue(unavailable == null, "GL を使えない環境: " + unavailable);
        String a = page(9, "row "), b = page(9, "ROW "), c = page(5, "x ");
        try (LwjglGlRenderBackend g = new LwjglGlRenderBackend(320, 240)) {
            SoftwareRendering.renderHtml(a, 320, 240, T0, g);
            assertArrayEquals(fresh(a).pixels(), g.pixmap().pixels());
            long first = g.uploadedFloatCount();
            assertTrue(first > 0);

            // 同じ絵をもう一度: 転送は（ほぼ）要らない
            SoftwareRendering.renderHtml(a, 320, 240, T0, g);
            assertArrayEquals(fresh(a).pixels(), g.pixmap().pixels());
            long second = g.uploadedFloatCount() - first;
            assertTrue(second * 4 < first, "同じ絵の 2 回目は転送が大きく減る: 1 回目 " + first + " / 2 回目 " + second);
            assertTrue(g.skippedFloatCount() > 0);

            // 一部が変わる絵、行数が減る絵、元へ戻る絵: どれも新しく作ったバックエンドと一致する
            for (String html : new String[] {b, c, a, b}) {
                SoftwareRendering.renderHtml(html, 320, 240, T0, g);
                assertArrayEquals(fresh(html).pixels(), g.pixmap().pixels());
            }
        }
    }

    /** 行ごとに 3 図形（背景・枠・グラデ）を出す、{@code MAX_BATCH} を超える文書。 */
    private static String tall(int rows, String label) {
        StringBuilder sb = new StringBuilder("<html><body style='margin:0;background:#223'>");
        for (int i = 0; i < rows; i++) {
            sb.append("<div style='height:2px;background:linear-gradient(90deg,#345,#567);border:1px solid #89a;")
                    .append("color:#fff'>").append(label).append("</div>");
        }
        return sb.append("</div></body></html>").toString();
    }

    /** 複数バッチの文書でも、同じ順番のバッチ同士で比べて転送が減る（枠が 1 つだと別のバッチと比べてしまう）。 */
    @Test
    void multiBatchFrameDiffsAgainstSameBatchOfPreviousFrame() {
        Assumptions.assumeTrue(unavailable == null, "GL を使えない環境: " + unavailable);
        String a = tall(300, "a"), b = tall(300, "b");
        try (LwjglGlRenderBackend g = new LwjglGlRenderBackend(320, 240)) {
            SoftwareRendering.renderHtml(a, 320, 240, T0, g);
            long draws1 = g.drawCallCount();
            assertTrue(draws1 >= 2, "2 バッチ以上になる文書: " + draws1);
            long first = g.uploadedFloatCount();
            SoftwareRendering.renderHtml(a, 320, 240, T0, g);
            long second = g.uploadedFloatCount() - first;
            assertTrue(second * 4 < first, "複数バッチでも 2 回目は大きく減る: " + first + " / " + second);
            assertArrayEquals(fresh(a).pixels(), g.pixmap().pixels());
            SoftwareRendering.renderHtml(b, 320, 240, T0, g);
            assertArrayEquals(fresh(b).pixels(), g.pixmap().pixels());
        }
    }

    /** 枠の数（8）を超えるバッチ数でも、絵は新しいバックエンドと一致する。 */
    @Test
    void moreBatchesThanSlotsStillRenderCorrectly() {
        Assumptions.assumeTrue(unavailable == null, "GL を使えない環境: " + unavailable);
        String a = tall(1800, "a"), b = tall(1800, "b");
        try (LwjglGlRenderBackend g = new LwjglGlRenderBackend(320, 240)) {
            SoftwareRendering.renderHtml(a, 320, 240, T0, g);
            assertTrue(g.drawCallCount() > 8, "枠の数を超える: " + g.drawCallCount());
            for (String html : new String[] {a, b, a}) {
                SoftwareRendering.renderHtml(html, 320, 240, T0, g);
                assertArrayEquals(fresh(html).pixels(), g.pixmap().pixels());
            }
        }
    }
}
