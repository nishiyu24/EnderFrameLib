package com.nishiyu.enderframelib.core.raster;

import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.client.renderer.DisplayListBuilder;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.raster.svg.SvgImage;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R8 のソフトウェア描画の実測（{@code ./gradlew benchmark}）。厳密な閾値は benchmark.enforce=true のときだけ判定する
 * （通常の {@code test} は値を出すだけ。GC や他テストの負荷で実時間が揺れるため）。
 *
 * 予算は「1 フレーム（16.7ms）に何回入るか」から逆算した広めの上限: ラスタライズは寸法が変わったときと初回だけで、
 * 同じ内容・寸法は {@link RasterImageStore} から引くだけ（署名の計算とハッシュ引き）にしたい。
 */
class RasterBenchmarkTest {

    private static final boolean ENFORCE = Boolean.getBoolean("benchmark.enforce");

    private static String icon() {
        StringBuilder sb = new StringBuilder("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'>"
                + "<defs><linearGradient id='g' x1='0' y1='0' x2='1' y2='1'><stop offset='0' stop-color='#f80'/>"
                + "<stop offset='1' stop-color='#08f'/></linearGradient></defs>"
                + "<rect width='100' height='100' rx='16' fill='url(#g)'/>");
        for (int i = 0; i < 20; i++) {
            sb.append("<circle cx='").append(10 + i * 4).append("' cy='").append(20 + (i % 5) * 12)
                    .append("' r='6' fill='white' fill-opacity='.6' stroke='#123' stroke-width='1.5'/>");
        }
        sb.append("<path d='M10 90 C 30 50, 70 130, 90 70 S 60 20, 20 40' fill='none' stroke='#fff' stroke-width='4' stroke-linecap='round'/>");
        return sb.append("</svg>").toString();
    }

    private static double medianMs(int runs, Runnable r) {
        for (int i = 0; i < 3; i++) r.run();
        double[] t = new double[runs];
        for (int i = 0; i < runs; i++) {
            long s = System.nanoTime();
            r.run();
            t[i] = (System.nanoTime() - s) / 1e6;
        }
        Arrays.sort(t);
        return t[runs / 2];
    }

    @Test
    void svgRasterizeCostAndCachedLookupCost() {
        SvgImage img = SvgImage.parse(icon());
        double r64 = medianMs(15, () -> img.rasterize(64, 64, 0xFF000000, null));
        double r256 = medianMs(15, () -> img.rasterize(256, 256, 0xFF000000, null));
        double sig = medianMs(200, () -> com.nishiyu.enderframelib.core.raster.svg.SvgRenderer.signature(img.root()));
        double parse = medianMs(30, () -> SvgImage.parse(icon()));
        System.out.printf("%n[R8 benchmark] svg 22 shapes+gradient: raster 64px %.2f ms, 256px %.2f ms, signature %.3f ms, parse %.2f ms%n",
                r64, r256, sig, parse);
        if (ENFORCE) {
            assertTrue(r64 < 20, "64px のラスタライズが遅い: " + r64);
            assertTrue(r256 < 60, "256px のラスタライズが遅い: " + r256);
            assertTrue(sig < 0.2, "署名の計算が遅い（キャッシュ引きのたびに払う）: " + sig);
        }
    }

    @Test
    void canvasPrimitiveCosts() {
        CanvasRenderingContext2D c = new CanvasRenderingContext2D(300, 150);
        double rects = medianMs(15, () -> {
            for (int i = 0; i < 500; i++) {
                c.fillStyle("#" + String.format("%06x", i * 977 & 0xFFFFFF));
                c.fillRect(i % 280, (i * 7) % 130, 20, 20);
            }
        });
        double strokes = medianMs(15, () -> {
            c.beginPath();
            c.moveTo(0, 0);
            for (int i = 0; i < 200; i++) c.lineTo(i * 1.5, (i * 37) % 150);
            c.lineWidth(2);
            c.stroke();
        });
        double arcs = medianMs(15, () -> {
            for (int i = 0; i < 50; i++) {
                c.beginPath();
                c.arc(30 + i * 4, 75, 25, 0, Math.PI * 2, false);
                c.fill();
            }
        });
        double imageData = medianMs(15, () -> c.putImageData(c.getImageData(0, 0, 300, 150), 0, 0));
        System.out.printf("[R8 benchmark] canvas 300x150: 500 fillRect %.2f ms, 200-segment stroke %.2f ms, 50 arcs (r25) %.2f ms, get+putImageData %.2f ms%n",
                rects, strokes, arcs, imageData);
        if (ENFORCE) {
            assertTrue(rects < 20, "fillRect 500 回: " + rects);
            assertTrue(strokes < 15, "200 区間の線: " + strokes);
            assertTrue(arcs < 10, "円 50 個: " + arcs);
            assertTrue(imageData < 10, "ImageData の往復: " + imageData);
        }
    }

    @Test
    void displayListWithInlineSvgsReusesTheRasterAcrossBuilds() {
        StringBuilder html = new StringBuilder();
        for (int i = 0; i < 40; i++) html.append("<svg width='48' height='48' viewBox='0 0 100 100'>").append(icon(), icon().indexOf("<defs>"), icon().length()).append("<span>x</span>");
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument("<html><head><style>body{margin:0}</style></head><body>" + html + "</body></html>", "", 800, 600);
        LayoutBox root = parser.layoutDocument(doc, 800, 600, Map.of(), new ScrollState(), FontResolver.FALLBACK);
        RasterImageStore.clear();
        double first = medianMs(1, () -> new DisplayListBuilder(800, 600, doc.sheet.keyframes, new ScrollState()).render(root));
        double warm = medianMs(15, () -> new DisplayListBuilder(800, 600, doc.sheet.keyframes, new ScrollState()).render(root));
        System.out.printf("[R8 benchmark] display list with 40 inline svgs: cold %.2f ms, warm (raster cached) %.2f ms, rasters stored %d%n",
                first, warm, RasterImageStore.size());
        assertTrue(RasterImageStore.size() >= 1);
        if (ENFORCE) {
            assertTrue(warm < 10, "ラスタライズ済みの SVG 40 個の表示リスト: " + warm);
        }
    }
}
