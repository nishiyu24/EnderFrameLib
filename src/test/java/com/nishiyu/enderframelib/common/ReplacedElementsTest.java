package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.client.renderer.DisplayListBuilder;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.ImageSources;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.ImageFitMode;
import com.nishiyu.enderframelib.core.raster.RasterImageStore;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** R8: 置換要素（インライン SVG・画像としての SVG・canvas・srcset / image-set・object-fit）のレイアウトと描画項目。 */
public class ReplacedElementsTest {

    private static final int W = 400;
    private static final int H = 300;

    @AfterEach
    void resetDensity() {
        ImageSources.setDevicePixelRatio(1f);
        RasterImageStore.clear();
    }

    private record Rendered(LayoutBox root, List<DisplayItem> elements) {}

    private static Rendered render(String html) {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument(
                "<html><head><style>body { margin: 0; }</style></head><body>" + html + "</body></html>", "", W, H);
        LayoutBox root = parser.layoutDocument(doc, W, H, Map.of(), new ScrollState(), FontResolver.FALLBACK);
        DisplayListBuilder builder = new DisplayListBuilder(W, H, doc.sheet.keyframes, new ScrollState());
        return new Rendered(root, builder.render(root));
    }

    private static LayoutBox find(LayoutBox box, String id) {
        if (id.equals(box.node.attrs.get("id"))) return box;
        for (LayoutBox child : box.elementChildren) {
            LayoutBox hit = find(child, id);
            if (hit != null) return hit;
        }
        return null;
    }

    private static DisplayItem item(Rendered r, String id) {
        return r.elements.stream().filter(e -> e.id().equals(id)).findFirst().orElse(null);
    }

    private static String dataUri(String svg) {
        return "data:image/svg+xml;utf8," + URLEncoder.encode(svg, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static int[] sizeOf(String rasterSrc) {
        int at = rasterSrc.lastIndexOf('@');
        String[] wh = rasterSrc.substring(at + 1).split("x");
        return new int[]{Integer.parseInt(wh[0]), Integer.parseInt(wh[1])};
    }

    private static int pixel(String rasterSrc, int x, int y) {
        int at = rasterSrc.lastIndexOf('@');
        RasterImageStore.Entry e = RasterImageStore.get(RasterImageStore.keyOf(rasterSrc.substring(0, at)));
        assertNotNull(e, "ラスタ画像がストアに無い: " + rasterSrc);
        return e.pixmap().getStraight(x, y);
    }

    // ------------------------------------------------------------------ インライン SVG

    @Test
    public void inlineSvgGetsIntrinsicSizeFromAttributesAndRasterizesItsShapes() {
        Rendered r = render("<svg id=\"s\" width=\"40\" height=\"20\"><rect width=\"20\" height=\"20\" fill=\"#ff0000\"/>"
                + "<rect x=\"20\" width=\"20\" height=\"20\" fill=\"#0000ff\"/></svg>");
        LayoutBox box = find(r.root, "s");
        assertEquals(40, box.w, 0.01);
        assertEquals(20, box.h, 0.01);
        DisplayItem svg = item(r, "s_svg");
        assertNotNull(svg);
        assertEquals(ElementType.IMAGE, svg.type());
        assertTrue(RasterImageStore.isRasterSrc(svg.text()));
        assertArrayEquals(new int[]{40, 20}, sizeOf(svg.text()));
        assertEquals(0xFFFF0000, pixel(svg.text(), 5, 10));
        assertEquals(0xFF0000FF, pixel(svg.text(), 35, 10));
    }

    @Test
    public void inlineSvgChildrenDoNotBecomeBoxes() {
        Rendered r = render("<svg id=\"s\" width=\"40\" height=\"20\"><rect width=\"20\" height=\"20\"/><g><circle r=\"5\"/></g></svg>");
        assertTrue(find(r.root, "s").elementChildren.isEmpty());
        assertEquals(1, r.elements.stream().filter(e -> e.id().startsWith("s_")).count());
    }

    @Test
    public void svgWithoutSizeIs300By150AndViewBoxSetsTheRatio() {
        assertEquals(300, find(render("<svg id=\"s\"></svg>").root, "s").w, 0.01);
        assertEquals(150, find(render("<svg id=\"s\"></svg>").root, "s").h, 0.01);
        LayoutBox vb = find(render("<svg id=\"s\" viewBox=\"0 0 80 20\"></svg>").root, "s");
        assertEquals(80, vb.w, 0.01);
        assertEquals(20, vb.h, 0.01);
    }

    @Test
    public void cssSizeStretchesTheSvgAndRasterizesAtThatSize() {
        Rendered r = render("<svg id=\"s\" viewBox=\"0 0 10 10\" style=\"width: 100px; height: 100px\">"
                + "<rect width=\"5\" height=\"10\" fill=\"#00ff00\"/></svg>");
        DisplayItem svg = item(r, "s_svg");
        assertArrayEquals(new int[]{100, 100}, sizeOf(svg.text()));
        assertEquals(0xFF00FF00, pixel(svg.text(), 20, 50));
        assertEquals(0, pixel(svg.text(), 80, 50));
    }

    @Test
    public void svgUsesTheElementColorForCurrentColor() {
        Rendered r = render("<svg id=\"s\" width=\"10\" height=\"10\" style=\"color: #ff00ff\"><rect width=\"10\" height=\"10\" fill=\"currentColor\"/></svg>");
        assertEquals(0xFFFF00FF, pixel(item(r, "s_svg").text(), 5, 5));
    }

    @Test
    public void devicePixelRatioScalesTheRasterButNotTheCssSize() {
        ImageSources.setDevicePixelRatio(2f);
        Rendered r = render("<svg id=\"s\" width=\"40\" height=\"20\"><rect width=\"40\" height=\"20\" fill=\"red\"/></svg>");
        String src = item(r, "s_svg").text();
        assertArrayEquals(new int[]{40, 20}, sizeOf(src));
        int at = src.lastIndexOf('@');
        RasterImageStore.Entry e = RasterImageStore.get(RasterImageStore.keyOf(src.substring(0, at)));
        assertEquals(80, e.pixmap().width());
        assertEquals(40, e.pixmap().height());
    }

    @Test
    public void identicalSvgsShareOneRaster() {
        render("<svg width=\"10\" height=\"10\"><rect width=\"10\" height=\"10\"/></svg>"
                + "<svg width=\"10\" height=\"10\"><rect width=\"10\" height=\"10\"/></svg>");
        assertEquals(1, RasterImageStore.size());
    }

    // ------------------------------------------------------------------ img の SVG

    @Test
    public void imgWithSvgDataUriUsesViewBoxSizeAndRasterizes() {
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 30 10\"><rect width=\"15\" height=\"10\" fill=\"red\"/></svg>";
        Rendered r = render("<img id=\"i\" src=\"" + dataUri(svg) + "\">");
        LayoutBox box = find(r.root, "i");
        assertEquals(30, box.w, 0.01);
        assertEquals(10, box.h, 0.01);
        DisplayItem img = item(r, "i_img");
        assertTrue(RasterImageStore.isRasterSrc(img.text()), img.text());
        assertEquals(0xFFFF0000, pixel(img.text(), 5, 5));
    }

    @Test
    public void imgSvgKeepsItsAspectUnderObjectFitContainAndCover() {
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 20 10\"><rect width=\"20\" height=\"10\" fill=\"blue\"/></svg>";
        Rendered contain = render("<img id=\"i\" src=\"" + dataUri(svg) + "\" style=\"width: 100px; height: 100px; object-fit: contain\">");
        DisplayItem c = item(contain, "i_img");
        assertEquals(ImageFitMode.CONTAIN, c.fitMode());
        assertArrayEquals(new int[]{100, 50}, sizeOf(c.text()));
        Rendered cover = render("<img id=\"i\" src=\"" + dataUri(svg) + "\" style=\"width: 100px; height: 100px; object-fit: cover; object-position: left top\">");
        DisplayItem v = item(cover, "i_img");
        assertArrayEquals(new int[]{200, 100}, sizeOf(v.text()));
        assertEquals("left top", v.events().get("object-position"));
    }

    @Test
    public void remoteOrUnloadedSvgPaintsNothingYet() {
        Rendered r = render("<img id=\"i\" src=\"res:icons/none.svg\" style=\"width: 20px; height: 20px\">");
        DisplayItem img = item(r, "i_img");
        assertNotNull(img);
        assertEquals("", img.text());
    }

    // ------------------------------------------------------------------ srcset / image-set

    @Test
    public void srcsetPicksTheCandidateForTheDensity() {
        String html = "<img id=\"i\" src=\"a.png\" srcset=\"a.png 1x, b.png 2x, c.png 3x\" style=\"width: 20px; height: 20px\">";
        assertEquals("a.png", item(render(html), "i_img").text());
        ImageSources.setDevicePixelRatio(2f);
        assertEquals("b.png", item(render(html), "i_img").text());
        ImageSources.setDevicePixelRatio(2.5f);
        assertEquals("c.png", item(render(html), "i_img").text());
        ImageSources.setDevicePixelRatio(5f);
        assertEquals("c.png", item(render(html), "i_img").text());
    }

    @Test
    public void srcsetWithWidthDescriptorsUsesSizes() {
        String html = "<img id=\"i\" srcset=\"s.png 200w, m.png 400w, l.png 800w\" sizes=\"(max-width: 500px) 200px, 800px\" style=\"width: 20px; height: 20px\">";
        // ビューポート 400px → sizes は 200px。200w は 1x、400w は 2x
        assertEquals("s.png", item(render(html), "i_img").text());
        ImageSources.setDevicePixelRatio(2f);
        assertEquals("m.png", item(render(html), "i_img").text());
    }

    @Test
    public void backgroundImageSetPicksByDensity() {
        String html = "<div id=\"d\" style=\"width: 50px; height: 50px; background-image: image-set(url(a.png) 1x, url(b.png) 2x)\"></div>";
        List<DisplayItem> one = render(html).elements.stream().filter(e -> e.type() == ElementType.IMAGE).toList();
        assertEquals(1, one.size());
        assertEquals("a.png", one.getFirst().text());
        ImageSources.setDevicePixelRatio(2f);
        List<DisplayItem> two = render(html).elements.stream().filter(e -> e.type() == ElementType.IMAGE).toList();
        assertEquals("b.png", two.getFirst().text());
    }

    @Test
    public void backgroundSvgIsRasterizedAtTheTileSize() {
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\"><rect width=\"10\" height=\"10\" fill=\"#00ff00\"/></svg>";
        Rendered r = render("<div id=\"d\" style=\"width: 60px; height: 40px; background-image: url('" + dataUri(svg) + "'); background-size: 30px 20px; background-repeat: no-repeat\"></div>");
        DisplayItem tile = r.elements.stream().filter(e -> e.type() == ElementType.IMAGE).findFirst().orElseThrow();
        assertTrue(RasterImageStore.isRasterSrc(tile.text()), tile.text());
        assertArrayEquals(new int[]{30, 20}, sizeOf(tile.text()));
    }

    // ------------------------------------------------------------------ canvas

    @Test
    public void canvasHasDefaultAndAttributeSizesAndCarriesThemToThePainter() {
        Rendered d = render("<canvas id=\"c\"></canvas>");
        LayoutBox box = find(d.root, "c");
        assertEquals(300, box.w, 0.01);
        assertEquals(150, box.h, 0.01);
        Rendered a = render("<canvas id=\"c\" width=\"64\" height=\"32\" style=\"object-fit: contain\"></canvas>");
        assertEquals(64, find(a.root, "c").w, 0.01);
        DisplayItem item = item(a, "c_canvas");
        assertEquals(ElementType.CANVAS, item.type());
        assertEquals(ImageFitMode.CONTAIN, item.fitMode());
        assertEquals("64", item.events().get("canvas-width"));
        assertEquals("32", item.events().get("canvas-height"));
    }

    @Test
    public void cssSizeStretchesTheCanvasBitmapBox() {
        Rendered r = render("<canvas id=\"c\" width=\"10\" height=\"10\" style=\"width: 100px; height: 50px\"></canvas>");
        assertEquals(100, find(r.root, "c").w, 0.01);
        assertEquals(50, find(r.root, "c").h, 0.01);
    }
}
