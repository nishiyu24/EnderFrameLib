package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.common.SoftwareRendering;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.raster.PngEncoder;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** border-image（9-slice）: 画像の項目 9 つへの展開と、CPU の画素。GL との一致は LwjglGlRenderBackendTest。 */
public class BorderImageTest {

    static final int RED = 0xFFFF0000, GREEN = 0xFF00FF00, BLUE = 0xFF0000FF, YELLOW = 0xFFFFFF00, WHITE = 0xFFFFFFFF,
            CYAN = 0xFF00FFFF, MAGENTA = 0xFFFF00FF, GRAY = 0xFF808080, BLACK = 0xFF000000;

    /** 12×12、3×3 の区画が 4 画素ずつ。左上から 赤 緑 青 / 黄 白 シアン / マゼンタ 灰 黒。 */
    public static String nineColorPngDataUrl() throws Exception {
        int[] c = {RED, GREEN, BLUE, YELLOW, WHITE, CYAN, MAGENTA, GRAY, BLACK};
        int[] px = new int[12 * 12];
        for (int y = 0; y < 12; y++) for (int x = 0; x < 12; x++) px[y * 12 + x] = c[(y / 4) * 3 + x / 4];
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(PngEncoder.encode(12, 12, px));
    }

    public static String html(String url, String borderImage) {
        return "<div style=\"width:24px;height:24px;border:8px solid #000;border-image:url(" + url + ") " + borderImage + "\"></div>";
    }

    @Test
    void expandsToNineImageItemsWithCropSources() throws Exception {
        String url = nineColorPngDataUrl();
        List<DisplayItem> items = SoftwareRendering.displayList(
                "<html><body style='margin:0'>" + html(url, "4 fill / 1 / 0 stretch") + "</body></html>", 60, 60);
        long images = items.stream().filter(i -> i.type() == ElementType.IMAGE && i.id().contains("_bi")).count();
        assertEquals(9, images, "枠 8 + fill の中央 1");
        assertTrue(items.stream().filter(i -> i.id().contains("_bi")).allMatch(i -> i.text().startsWith("crop:")));
        // 通常の枠線（_b_ の帯）は出ない
        assertTrue(items.stream().noneMatch(i -> i.partSuffix().startsWith("_b_")), "border-image があれば通常の枠線は描かない");
    }

    @Test
    void withoutFillTheMiddleIsEmpty() throws Exception {
        List<DisplayItem> items = SoftwareRendering.displayList(
                "<html><body style='margin:0'>" + html(nineColorPngDataUrl(), "4 / 1") + "</body></html>", 60, 60);
        assertEquals(8, items.stream().filter(i -> i.id().contains("_bi")).count());
    }

    @Test
    void cpuPaintsEachCellFromItsSlice() throws Exception {
        List<DisplayItem> items = SoftwareRendering.displayList(
                "<html><body style='margin:0'>" + html(nineColorPngDataUrl(), "4 fill / 1 / 0 stretch") + "</body></html>", 60, 60);
        try (SoftwareRenderBackend b = new SoftwareRenderBackend(60, 60, 1f)) {
            b.render(items, 0);
            // 箱は 40×40（枠 8 + 内側 24）。角 8×8、辺は 24 に伸び、中央 24×24
            assertEquals(RED, b.pixmap().getStraight(3, 3));
            assertEquals(GREEN, b.pixmap().getStraight(20, 3));
            assertEquals(BLUE, b.pixmap().getStraight(36, 3));
            assertEquals(YELLOW, b.pixmap().getStraight(3, 20));
            assertEquals(WHITE, b.pixmap().getStraight(20, 20));
            assertEquals(CYAN, b.pixmap().getStraight(36, 20));
            assertEquals(MAGENTA, b.pixmap().getStraight(3, 36));
            assertEquals(GRAY, b.pixmap().getStraight(20, 36));
            assertEquals(BLACK, b.pixmap().getStraight(36, 36));
        }
    }

    @Test
    void geometryScalesBordersThatDoNotFit() {
        // 幅 10 の箱に左右 8 ずつの枠 → 合計 16 > 10 なので 10/16 に縮む
        var style = new com.nishiyu.enderframelib.core.css.ComputedStyle(java.util.Map.of(
                "border-image-slice", "4", "border-image-width", "1", "font-size", "16px"));
        List<BorderImage.Cell> cells = BorderImage.cells(style, 12, 12, 0, 0, 10, 40, new float[]{8, 8, 8, 8});
        BorderImage.Cell tl = cells.get(0);
        assertEquals(5f, tl.dw(), 1e-4);
        // 上の辺は幅 0 になって落ち、右上の角は箱の右端に接する
        BorderImage.Cell tr = cells.stream().filter(c -> c.dy() == 0 && c.dx() > 0).findFirst().orElseThrow();
        assertEquals(10f, tr.dx() + tr.dw(), 1e-4);
    }

    @Test
    void unreadableSourceFallsBackToTheNormalBorder() {
        List<DisplayItem> items = SoftwareRendering.displayList(
                "<html><body style='margin:0'>" + html("data:image/png;base64,AAAA", "4") + "</body></html>", 60, 60);
        assertTrue(items.stream().noneMatch(i -> i.id().contains("_bi")));
        assertTrue(items.stream().anyMatch(i -> i.partSuffix().startsWith("_b")), "通常の枠線が描かれる");
    }
}
