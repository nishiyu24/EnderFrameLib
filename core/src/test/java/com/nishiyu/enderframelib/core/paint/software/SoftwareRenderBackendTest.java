package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.common.SoftwareRendering;
import com.nishiyu.enderframelib.core.GoldenFiles;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.text.font.OutlineFontFace;
import com.nishiyu.enderframelib.core.text.font.SystemFonts;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SoftwareRenderBackend} を、OpenGL も Minecraft も無しで HTML から画素まで通して確かめる。
 * 画素は非乗算の ARGB で読む。
 */
class SoftwareRenderBackendTest {

    private static final String BODY0 = "<html><body style='margin:0'>";
    private static final long T0 = 10_000L;

    private static SoftwareRenderBackend render(String body, int w, int h) {
        return SoftwareRendering.renderHtml(BODY0 + body + "</body></html>", w, h, T0);
    }

    private static int px(SoftwareRenderBackend b, int x, int y) {
        return b.pixmap().getStraight(x, y);
    }

    private static int a(int argb) { return argb >>> 24; }
    private static int r(int argb) { return (argb >> 16) & 0xFF; }
    private static int g(int argb) { return (argb >> 8) & 0xFF; }
    private static int b(int argb) { return argb & 0xFF; }

    private static long ink(SoftwareRenderBackend backend, int x0, int y0, int x1, int y1) {
        long sum = 0;
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) sum += backend.pixmap().get(x, y) >>> 24;
        return sum;
    }

    // ------------------------------------------------------------------ 箱

    @Test
    void solidBackgroundFillsExactlyTheBox() {
        SoftwareRenderBackend s = render("<div style='margin-left:10px;margin-top:10px;width:40px;height:30px;background:#ff0000'></div>", 80, 60);
        assertEquals(0xFFFF0000, px(s, 20, 20));
        assertEquals(0xFFFF0000, px(s, 10, 10), "top-left pixel of the box");
        assertEquals(0xFFFF0000, px(s, 49, 39), "bottom-right pixel of the box");
        assertEquals(0, px(s, 9, 20));
        assertEquals(0, px(s, 50, 20));
        assertEquals(0, px(s, 20, 9));
        assertEquals(0, px(s, 20, 40));
    }

    @Test
    void groupOpacityScalesParts() {
        SoftwareRenderBackend s = render("<div style='width:40px;height:40px;background:#ff0000;opacity:0.5'></div>", 60, 60);
        int c = px(s, 20, 20);
        assertEquals(128, a(c), 2);
        assertEquals(255, r(c), 1);
        assertEquals(0, g(c));
    }

    @Test
    void roundedCornersAreAntialiased() {
        SoftwareRenderBackend s = render("<div style='width:100px;height:100px;background:#0000ff;border-radius:30px'></div>", 120, 120);
        assertEquals(0, a(px(s, 0, 0)), "corner pixel is outside the arc");
        assertEquals(255, a(px(s, 50, 50)));
        assertEquals(255, a(px(s, 50, 0)), "straight top edge is fully covered");
        // 弧の上の画素は部分的に覆われる
        boolean partial = false;
        for (int i = 0; i < 30; i++) {
            int al = a(px(s, i, i));
            if (al > 10 && al < 245) partial = true;
        }
        assertTrue(partial, "arc pixels have fractional coverage");
        // 円の 45 度の点 (30 - 30/sqrt2 = 8.79) の内側は塗られ、外側は塗られない
        assertEquals(255, a(px(s, 11, 11)));
        assertEquals(0, a(px(s, 6, 6)));
    }

    @Test
    void ellipticalCornersUseSeparateRadii() {
        SoftwareRenderBackend s = render("<div style='width:100px;height:60px;background:#00ff00;border-radius:50px / 10px'></div>", 120, 80);
        // 横 50 縦 10 の楕円の角: 上端の左 5px は欠け、縦 12px 下では埋まっている
        assertTrue(a(px(s, 2, 0)) < 128);
        assertEquals(255, a(px(s, 2, 12)));
    }

    // ------------------------------------------------------------------ グラデーション

    @Test
    void linearGradientRunsFromStartToEnd() {
        SoftwareRenderBackend s = render("<div style='width:100px;height:20px;background:linear-gradient(to right,#ff0000,#0000ff)'></div>", 120, 30);
        int left = px(s, 2, 10), mid = px(s, 50, 10), right = px(s, 97, 10);
        assertTrue(r(left) > 230 && b(left) < 25, "starts red: " + Integer.toHexString(left));
        assertTrue(b(right) > 230 && r(right) < 25, "ends blue: " + Integer.toHexString(right));
        assertEquals(128, r(mid), 12);
        assertEquals(128, b(mid), 12);
        assertEquals(255, a(mid));
    }

    @Test
    void radialAndConicGradientsAreRendered() {
        SoftwareRenderBackend radial = render("<div style='width:80px;height:80px;background:radial-gradient(circle,#ffffff,#000000)'></div>", 100, 100);
        assertTrue(r(px(radial, 40, 40)) > r(px(radial, 3, 3)) + 100, "center is brighter than the corner");
        SoftwareRenderBackend conic = render("<div style='width:80px;height:80px;background:conic-gradient(#ff0000,#0000ff,#ff0000)'></div>", 100, 100);
        assertNotEquals(px(conic, 60, 20), px(conic, 20, 60));
    }

    // ------------------------------------------------------------------ 枠線

    @Test
    void uniformBorderRingAndInterior() {
        SoftwareRenderBackend s = render("<div style='width:42px;height:42px;border:4px solid #00ff00;background:#ffffff'></div>", 60, 60);
        assertEquals(0xFF00FF00, px(s, 1, 25), "left border");
        assertEquals(0xFF00FF00, px(s, 25, 1), "top border");
        assertEquals(0xFFFFFFFF, px(s, 25, 25), "interior keeps the background");
        assertEquals(0xFF00FF00, px(s, 48, 25), "right border of the 50px box");
        assertEquals(0, a(px(s, 51, 25)), "outside the 50px box");
    }

    @Test
    void borderSidesKeepTheirOwnColorsWithDiagonalJoin() {
        SoftwareRenderBackend s = render("<div style='width:40px;height:40px;border:10px solid;border-color:#ff0000 #00ff00 #0000ff #ffff00'></div>", 80, 80);
        assertEquals(0xFFFF0000, px(s, 30, 3), "top");
        assertEquals(0xFF00FF00, px(s, 57, 30), "right");
        assertEquals(0xFF0000FF, px(s, 30, 57), "bottom");
        assertEquals(0xFFFFFF00, px(s, 3, 30), "left");
    }

    @Test
    void dashedBorderAlternatesAlongTheEdge() {
        SoftwareRenderBackend s = render("<div style='width:194px;height:50px;border:3px dashed #000000'></div>", 210, 70);
        int transitions = 0;
        boolean prev = a(px(s, 0, 1)) > 128;
        for (int x = 8; x < 192; x++) {
            boolean on = a(px(s, x, 1)) > 128;
            if (on != prev) transitions++;
            prev = on;
        }
        assertTrue(transitions >= 8, "dashes toggle along the top edge, transitions=" + transitions);
        assertEquals(0, a(px(s, 100, 25)), "interior is empty");
    }

    // ------------------------------------------------------------------ グループの不透明度

    private static final String OVERLAP = "<div style='width:40px;height:40px;padding:10px;background:#ff0000;opacity:0.5'>"
            + "<div style='width:30px;height:30px;background:#0000ff'></div></div>";

    @Test
    void groupOpacityDoesNotShowOverlappingPartsThrough() {
        SoftwareRenderBackend s = render(OVERLAP, 80, 80);
        int overlap = px(s, 30, 30), onlyParent = px(s, 5, 5);
        assertEquals(128, a(overlap), 2, "group alpha, not 0.75");
        assertEquals(255, overlap & 0xFF, 2, "child is opaque blue inside the group");
        assertEquals(0, (overlap >> 16) & 0xFF, 2, "parent red does not show through the child");
        assertEquals(128, a(onlyParent), 2);
        assertEquals(255, (onlyParent >> 16) & 0xFF, 2);
    }

    @Test
    void nestedGroupOpacityMultiplies() {
        SoftwareRenderBackend s = render("<div style='width:40px;height:40px;padding:10px;background:#ff0000;opacity:0.5'>"
                + "<div style='width:30px;height:30px;background:#0000ff;opacity:0.5'></div></div>", 80, 80);
        // 内側の青（不透明度 0.5）が外側の層の赤（不透明）に重なり、層全体に 0.5
        int c = px(s, 30, 30);
        assertEquals(128, a(c), 2);
        assertEquals(128, c & 0xFF, 3, "blue half over opaque red inside the layer");
        assertEquals(127, (c >> 16) & 0xFF, 3);
    }

    @Test
    void groupOpacityWithoutOverlapMatchesPerPartOpacity() {
        SoftwareRenderBackend s = render("<div style='width:40px;height:40px;background:#00ff00;opacity:0.25'></div>", 60, 60);
        assertEquals(64, a(px(s, 20, 20)), 2);
    }

    @Test
    void layersDoNotLeakBetweenFrames() {
        var items = SoftwareRendering.displayList(BODY0 + OVERLAP + "</body></html>", 80, 80);
        SoftwareRenderBackend s = new SoftwareRenderBackend(80, 80);
        s.render(items, T0);
        int first = px(s, 30, 30);
        s.render(items, T0);
        assertEquals(first, px(s, 30, 30));
        assertEquals(0, a(px(s, 70, 70)));
    }

    // ------------------------------------------------------------------ filter / mask

    @Test
    void grayscaleFilterRemovesColor() {
        SoftwareRenderBackend s = render("<div style='width:40px;height:40px;background:#ff0000;filter:grayscale(1)'></div>", 60, 60);
        int c = px(s, 20, 20);
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        assertEquals(255, a(c));
        assertEquals(r, g, 1);
        assertEquals(g, b, 1);
        assertEquals(54, r, 2, "0.2126 * 255");
    }

    @Test
    void filterAppliesToTheWholeGroupNotToEachPart() {
        // 親（赤）の上に子（青）。invert はグループ全体にかかり、子の下の赤は見えない
        SoftwareRenderBackend s = render("<div style='width:40px;height:40px;padding:10px;background:#ff0000;filter:invert(1)'>"
                + "<div style='width:30px;height:30px;background:#0000ff'></div></div>", 80, 80);
        int child = px(s, 30, 30), parentOnly = px(s, 5, 5);
        assertEquals(0xFFFFFF00, child, "inverted blue");
        assertEquals(0xFF00FFFF, parentOnly, "inverted red");
    }

    @Test
    void opacityFilterScalesAlpha() {
        SoftwareRenderBackend s = render("<div style='width:40px;height:40px;background:#00ff00;filter:opacity(0.5)'></div>", 60, 60);
        assertEquals(128, a(px(s, 20, 20)), 2);
    }

    @Test
    void blurSpreadsTheEdgeOutsideTheBox() {
        SoftwareRenderBackend s = render("<div style='margin:20px;width:30px;height:30px;background:#000000;filter:blur(4px)'></div>", 80, 80);
        assertEquals(255, a(px(s, 35, 35)), 3, "center stays solid");
        int outside = a(px(s, 17, 35));
        assertTrue(outside > 5 && outside < 128, "blur leaks outside the box: " + outside);
        int edge = a(px(s, 20, 35));
        assertTrue(edge > 100 && edge < 200, "box edge is half transparent: " + edge);
        assertEquals(0, a(px(s, 2, 2)));
    }

    @Test
    void dropShadowFilterIsDrawnBehindAndOffset() {
        SoftwareRenderBackend s = render("<div style='margin:10px;width:20px;height:20px;background:#ff0000;filter:drop-shadow(10px 10px 0px #0000ff)'></div>", 60, 60);
        assertEquals(0xFFFF0000, px(s, 15, 15), "box on top");
        assertEquals(0xFF0000FF, px(s, 35, 35), "shadow offset by 10,10");
        assertEquals(0, a(px(s, 45, 45)));
    }

    @Test
    void alphaMaskRevealsWhereMaskIsOpaque() {
        SoftwareRenderBackend s = render("<div style='width:60px;height:40px;background:#ff0000;"
                + "mask-image:linear-gradient(to right, #000000 0%, #000000 50%, transparent 50%, transparent 100%)'></div>", 80, 60);
        assertEquals(255, a(px(s, 10, 20)), 2, "left half visible");
        assertEquals(0, a(px(s, 50, 20)), "right half hidden");
    }

    @Test
    void luminanceMaskUsesBrightness() {
        SoftwareRenderBackend s = render("<div style='width:60px;height:40px;background:#ff0000;mask-mode:luminance;"
                + "mask-image:linear-gradient(to right, #ffffff 0%, #ffffff 50%, #000000 50%, #000000 100%)'></div>", 80, 60);
        assertEquals(255, a(px(s, 10, 20)), 2);
        assertEquals(0, a(px(s, 50, 20)), 2);
    }

    @Test
    void filterAndMaskLayersDoNotLeakBetweenFrames() {
        var items = SoftwareRendering.displayList(BODY0 + "<div style='width:40px;height:40px;background:#ff0000;filter:blur(3px);"
                + "mask-image:linear-gradient(#000000,#000000)'></div></body></html>", 80, 80);
        SoftwareRenderBackend s = new SoftwareRenderBackend(80, 80);
        s.render(items, T0);
        int first = px(s, 20, 20);
        s.render(items, T0);
        assertEquals(first, px(s, 20, 20));
        assertEquals(0, a(px(s, 70, 70)));
    }

    // ------------------------------------------------------------------ clip-path / backdrop-filter

    @Test
    void clipPathCircleCutsOutsideTheShape() {
        SoftwareRenderBackend s = render("<div style='width:60px;height:60px;background:#ff0000;clip-path:circle(30px at 30px 30px)'></div>", 80, 80);
        assertEquals(0xFFFF0000, px(s, 30, 30));
        assertEquals(0, a(px(s, 2, 2)), "corner is outside the circle");
        assertEquals(0xFFFF0000, px(s, 30, 6));
        int edge = a(px(s, 59, 30));
        assertTrue(edge > 0 && edge < 255 || edge == 0, "circle edge is anti-aliased or just outside");
    }

    @Test
    void clipPathInsetRoundAndPolygon() {
        SoftwareRenderBackend s = render("<div style='width:60px;height:60px;background:#00aa00;clip-path:inset(10px 20px 10px 10px)'></div>", 80, 80);
        assertEquals(0, a(px(s, 5, 30)), "left inset");
        assertEquals(0xFF00AA00, px(s, 20, 30));
        assertEquals(0, a(px(s, 45, 30)), "right inset");
        SoftwareRenderBackend t = render("<div style='width:60px;height:60px;background:#0000ff;clip-path:polygon(0 0, 100% 0, 50% 100%)'></div>", 80, 80);
        assertEquals(0xFF0000FF, px(t, 30, 10));
        assertEquals(0, a(px(t, 3, 50)), "below the triangle's left edge");
        assertEquals(0, a(px(t, 12, 58)), "far from the tip");
    }

    @Test
    void clipPathAppliesToChildrenAsAGroup() {
        SoftwareRenderBackend s = render("<div style='width:60px;height:60px;clip-path:circle(20px at 30px 30px)'>"
                + "<div style='width:60px;height:30px;background:#ff0000'></div><div style='width:60px;height:30px;background:#0000ff'></div></div>", 80, 80);
        assertEquals(0xFFFF0000, px(s, 30, 20));
        assertEquals(0xFF0000FF, px(s, 30, 40));
        assertEquals(0, a(px(s, 5, 20)));
        assertEquals(0, a(px(s, 55, 40)));
    }

    @Test
    void backdropFilterInvertsWhatIsBehindTheBox() {
        SoftwareRenderBackend s = render("<div style='width:80px;height:40px;background:#ff0000'></div>"
                + "<div style='margin-top:-40px;width:40px;height:40px;backdrop-filter:invert(1)'></div>", 80, 40);
        assertEquals(0xFF00FFFF, px(s, 20, 20), "inside the box the background is inverted");
        assertEquals(0xFFFF0000, px(s, 60, 20), "outside it is untouched");
    }

    @Test
    void backdropFilterBlurSmoothsTheEdgeBehindTheBox() {
        SoftwareRenderBackend s = render("<div style='width:40px;height:60px;background:#ffffff;float:left'></div>"
                + "<div style='width:40px;height:60px;background:#000000;float:left'></div>"
                + "<div style='position:absolute;left:0;top:0;width:80px;height:60px;backdrop-filter:blur(6px)'></div>", 80, 60);
        int mid = r(px(s, 40, 30)), left = r(px(s, 36, 30)), far = r(px(s, 5, 30));
        assertTrue(mid > 60 && mid < 200, "edge is gray: " + mid);
        assertTrue(left > mid, "gets lighter toward the white side");
        assertTrue(far > 200, "far from the edge stays light: " + far);
    }

    @Test
    void backdropFilterFollowsRoundedCorners() {
        SoftwareRenderBackend s = render("<div style='width:80px;height:80px;background:#ff0000'></div>"
                + "<div style='margin-top:-80px;width:80px;height:80px;border-radius:40px;backdrop-filter:invert(1)'></div>", 80, 80);
        assertEquals(0xFF00FFFF, px(s, 40, 40));
        assertEquals(0xFFFF0000, px(s, 2, 2), "corner is outside the rounded box");
    }

    // ------------------------------------------------------------------ 影

    @Test
    void blurredShadowFadesWithDistance() {
        SoftwareRenderBackend s = render("<div style='margin:30px;width:40px;height:40px;background:#ffffff;box-shadow:0 0 12px #000000'></div>", 100, 100);
        int near = a(px(s, 27, 50)), mid = a(px(s, 22, 50)), far = a(px(s, 12, 50));
        assertTrue(near > mid && mid > far, "shadow alpha decreases outward: " + near + " > " + mid + " > " + far);
        assertTrue(near > 40, "near the box the shadow is visible");
        assertEquals(0xFFFFFFFF, px(s, 50, 50), "the box itself is drawn over the shadow");
    }

    @Test
    void insetShadowDarkensTheEdgesOnly() {
        SoftwareRenderBackend s = render("<div style='width:80px;height:80px;background:#ffffff;box-shadow:inset 0 0 16px #000000'></div>", 100, 100);
        assertTrue(r(px(s, 1, 40)) < r(px(s, 40, 40)) - 60, "edge is darker than the center");
        assertTrue(r(px(s, 40, 40)) > 235, "center stays white");
    }

    // ------------------------------------------------------------------ 変形・クリップ

    @Test
    void translateMovesThePaintedArea() {
        SoftwareRenderBackend s = render("<div style='width:20px;height:20px;background:#ff0000;transform:translate(50px,10px)'></div>", 100, 60);
        assertEquals(0, a(px(s, 10, 10)), "original position is empty");
        assertEquals(0xFFFF0000, px(s, 60, 20));
    }

    @Test
    void rotationTurnsAWideBoxUpright() {
        SoftwareRenderBackend s = render("<div style='margin:30px;width:40px;height:10px;background:#0000ff;transform:rotate(90deg)'></div>", 100, 100);
        // 中心 (50, 35)。90 度回すと縦長になる
        assertEquals(0xFF0000FF, px(s, 50, 20));
        assertEquals(0xFF0000FF, px(s, 50, 50));
        assertEquals(0, a(px(s, 35, 35)), "no longer extends horizontally");
        assertEquals(0, a(px(s, 65, 35)));
    }

    @Test
    void scaleGrowsAroundTheCenter() {
        SoftwareRenderBackend s = render("<div style='margin:40px;width:20px;height:20px;background:#00ff00;transform:scale(2)'></div>", 100, 100);
        assertEquals(0xFF00FF00, px(s, 35, 50), "scaled box reaches 10px beyond the original edge");
        assertEquals(0, a(px(s, 28, 50)));
    }

    @Test
    void childTransformFollowsTheParent() {
        SoftwareRenderBackend s = render("<div style='width:20px;height:20px;transform:translate(60px,0)'><div style='width:20px;height:20px;background:#ff00ff'></div></div>", 100, 40);
        assertEquals(0xFFFF00FF, px(s, 70, 10));
        assertEquals(0, a(px(s, 10, 10)));
    }

    @Test
    void overflowHiddenWithRadiusClipsChildren() {
        SoftwareRenderBackend s = render("<div style='width:50px;height:50px;overflow:hidden;border-radius:25px'>"
                + "<div style='width:200px;height:200px;background:#ff0000'></div></div>", 100, 100);
        assertEquals(0xFFFF0000, px(s, 25, 25));
        assertEquals(0, a(px(s, 70, 25)), "outside the parent box");
        assertEquals(0, a(px(s, 2, 2)), "corner of the rounded clip");
    }

    // ------------------------------------------------------------------ 文字・画像・部品

    private static void assumeFonts() {
        Assumptions.assumeTrue(SystemFonts.system().resolve(null) instanceof OutlineFontFace, "no system fonts");
    }

    @Test
    void textDrawsInkInsideItsLineBox() {
        assumeFonts();
        SoftwareRenderBackend s = render("<div style='padding:10px;font:20px sans-serif;color:#000000'>Hello</div>", 120, 50);
        assertTrue(ink(s, 10, 10, 80, 38) > 255 * 40, "glyph pixels exist");
        assertEquals(0, ink(s, 0, 0, 120, 8), "nothing above the text");
        assertEquals(0, ink(s, 100, 0, 120, 50), "nothing to the right of the text");
        // 黒い字だけなので RGB は 0、アルファだけ変わる
        for (int y = 10; y < 38; y++) for (int x = 10; x < 80; x++) {
            int c = px(s, x, y);
            if (a(c) > 0) { assertEquals(0, r(c) + g(c) + b(c)); }
        }
    }

    @Test
    void boldTextHasMoreInkAndBiggerTextMoreStill() {
        assumeFonts();
        SoftwareRenderBackend normal = render("<div style='font:16px sans-serif;color:#000'>Hamburger</div>", 200, 40);
        SoftwareRenderBackend bold = render("<div style='font:bold 16px sans-serif;color:#000'>Hamburger</div>", 200, 40);
        SoftwareRenderBackend big = render("<div style='font:24px sans-serif;color:#000'>Hamburger</div>", 200, 40);
        long n = ink(normal, 0, 0, 200, 40), bo = ink(bold, 0, 0, 200, 40), bi = ink(big, 0, 0, 200, 40);
        assertTrue(bo > n, "bold " + bo + " > normal " + n);
        assertTrue(bi > n * 1.5, "24px " + bi + " >> 16px " + n);
    }

    @Test
    void underlineAddsAHorizontalRule() {
        assumeFonts();
        SoftwareRenderBackend plain = render("<div style='font:20px sans-serif;color:#000'>aaaa</div>", 120, 40);
        SoftwareRenderBackend under = render("<div style='font:20px sans-serif;color:#000;text-decoration:underline'>aaaa</div>", 120, 40);
        assertTrue(ink(under, 0, 0, 120, 40) > ink(plain, 0, 0, 120, 40));
    }

    @Test
    void rotatedTextStillRenders() {
        assumeFonts();
        SoftwareRenderBackend s = render("<div style='margin:30px;width:80px;font:20px sans-serif;color:#000;transform:rotate(90deg)'>Rotate</div>", 140, 140);
        assertTrue(ink(s, 0, 0, 140, 140) > 255 * 40);
    }

    private static String pngDataUri(int w, int h, int argb) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, argb);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    }

    @Test
    void pngDataUriImageIsScaledIntoItsBox() throws Exception {
        String uri = pngDataUri(4, 4, 0xFF336699);
        SoftwareRenderBackend s = render("<img style='width:40px;height:30px' src='" + uri + "'>", 60, 40);
        assertEquals(0xFF336699, px(s, 20, 15));
        assertEquals(0, a(px(s, 45, 15)));
    }

    @Test
    void canvasIsDrawnFromTheHostProvidedPixels() {
        var items = SoftwareRendering.displayList(BODY0 + "<canvas width='4' height='4' style='display:block;width:40px;height:30px'></canvas></body></html>", 60, 40);
        var canvas = items.stream().filter(i -> i.type() == com.nishiyu.enderframelib.core.paint.model.ElementType.CANVAS).findFirst().orElseThrow();
        // 提供元が無ければ何も描かない
        SoftwareRenderBackend none = new SoftwareRenderBackend(60, 40);
        none.render(items, T0);
        assertEquals(0, a(px(none, 20, 15)));
        // ホストが画素を渡せば、箱いっぱいに貼られる
        com.nishiyu.enderframelib.core.raster.Pixmap pm = new com.nishiyu.enderframelib.core.raster.Pixmap(4, 4);
        for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) pm.setStraight(x, y, 0xFF336699);
        SoftwareRenderBackend s = new SoftwareRenderBackend(60, 40);
        s.images(new ImageProvider() {
            @Override public Image load(String src, int w, int h) { return null; }
            @Override public Image load(com.nishiyu.enderframelib.core.paint.model.DisplayItem it, int w, int h) {
                return it.type() == com.nishiyu.enderframelib.core.paint.model.ElementType.CANVAS ? new Image(pm, 4, 4) : null;
            }
        });
        s.render(items, T0);
        assertEquals(0xFF336699, px(s, 20, 15));
        assertEquals(0, a(px(s, 45, 15)));
    }

    @Test
    void svgDataUriImageIsRasterized() {
        String svg = "data:image/svg+xml;utf8,<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"><rect width=\"10\" height=\"10\" fill=\"red\"/></svg>";
        SoftwareRenderBackend s = render("<img style='width:30px;height:30px' src='" + svg + "'>", 40, 40);
        int c = px(s, 15, 15);
        assertEquals(255, r(c), 2);
        assertEquals(255, a(c), 2);
    }

    @Test
    void progressBarFillsProportionally() {
        SoftwareRenderBackend s = render("<progress value='30' max='100' style='width:100px;height:16px'></progress>", 120, 30);
        int filled = px(s, 10, 8), empty = px(s, 80, 8);
        assertNotEquals(filled, empty);
        assertEquals(255, a(filled));
        // 境目は 30% 付近
        int edge = -1;
        for (int x = 1; x < 100; x++) if (px(s, x, 8) != filled) { edge = x; break; }
        assertEquals(30, edge, 4, "fill/track boundary");
    }

    @Test
    void checkedCheckboxDrawsAMark() {
        SoftwareRenderBackend off = render("<input type='checkbox' style='width:26px;height:26px;margin:0'>", 40, 40);
        SoftwareRenderBackend on = render("<input type='checkbox' checked style='width:26px;height:26px;margin:0'>", 40, 40);
        boolean differs = false;
        for (int y = 0; y < 40 && !differs; y++) for (int x = 0; x < 40; x++) if (px(off, x, y) != px(on, x, y)) { differs = true; break; }
        assertTrue(differs, "the checked box differs from the unchecked one");
    }

    // ------------------------------------------------------------------ アニメーション・出力

    @Test
    void keyframeOpacityFollowsTheClock() {
        String html = BODY0 + "<style>@keyframes fade{from{opacity:0}to{opacity:1}}"
                + "#b{width:30px;height:30px;background:#000;animation:fade 1s linear forwards}</style><div id='b'></div></body></html>";
        List<DisplayItem> items = SoftwareRendering.displayList(html, 40, 40);
        SoftwareRenderBackend s = new SoftwareRenderBackend(40, 40);
        s.render(items, 5_000);
        int start = a(px(s, 15, 15));
        s.render(items, 5_500);
        int half = a(px(s, 15, 15));
        s.render(items, 6_500);
        int end = a(px(s, 15, 15));
        assertTrue(start < 40, "start " + start);
        assertEquals(128, half, 30);
        assertEquals(255, end);
    }

    @Test
    void deviceRatioScalesTheCanvasAndTheGeometry() {
        SoftwareRenderBackend s = SoftwareRendering.renderHtml(BODY0 + "<div style='width:10px;height:10px;background:#ff0000'></div></body></html>", 30, 20, 2f, T0);
        assertEquals(60, s.pixmap().width());
        assertEquals(40, s.pixmap().height());
        assertEquals(0xFFFF0000, px(s, 19, 19));
        assertEquals(0, a(px(s, 21, 10)));
    }

    @Test
    void renderingIsDeterministicAndClearsBetweenFrames() {
        String html = BODY0 + "<div style='width:30px;height:30px;background:#123456;border-radius:6px;box-shadow:0 2px 6px #000'></div></body></html>";
        List<DisplayItem> items = SoftwareRendering.displayList(html, 60, 60);
        SoftwareRenderBackend s = new SoftwareRenderBackend(60, 60);
        s.render(items, T0);
        int[] first = s.pixmap().pixels().clone();
        s.render(items, T0);
        assertTrue(java.util.Arrays.equals(first, s.pixmap().pixels()), "same input, same pixels (no accumulation)");
        s.render(List.of(), T0);
        for (int p : s.pixmap().pixels()) assertEquals(0, p);
    }

    @Test
    void clearColorFillsTheBackdrop() {
        SoftwareRenderBackend s = new SoftwareRenderBackend(10, 10).clearColor(0xFF102030);
        s.render(SoftwareRendering.displayList(BODY0 + "</body></html>", 10, 10), T0);
        assertEquals(0xFF102030, px(s, 5, 5));
    }

    @Test
    void pngExportMatchesThePixmap() throws Exception {
        SoftwareRenderBackend s = render("<div style='width:20px;height:20px;background:rgba(255,0,0,0.5)'></div>", 30, 30);
        Path out = Path.of("build/test-output/software-export.png");
        s.writePng(out);
        BufferedImage back = ImageIO.read(out.toFile());
        assertEquals(30, back.getWidth());
        assertEquals(a(px(s, 10, 10)), back.getRGB(10, 10) >>> 24);
        // 全画素が、ピクセルマップの非乗算 ARGB と ImageIO の復号で一致する（独自エンコーダの検証）
        int[] straight = s.pixmap().toStraightArgb();
        for (int y = 0; y < 30; y++) for (int x = 0; x < 30; x++) assertEquals(straight[y * 30 + x], back.getRGB(x, y), x + "," + y);
    }

    @Test
    void showcaseDocumentRendersWithoutOpenGl() throws Exception {
        assumeFonts();
        String html = GoldenFiles.readFixture("showcase.html");
        SoftwareRenderBackend s = new SoftwareRenderBackend(854, 480).clearColor(0xFFFFFFFF);
        s.render(SoftwareRendering.displayList(html, 854, 480), T0);
        Path out = Path.of("build/test-output/software-showcase.png");
        s.writePng(out);
        assertTrue(Files.size(out) > 2000);
        Pixmap p = s.pixmap();
        int distinct = (int) java.util.Arrays.stream(p.pixels()).distinct().count();
        assertTrue(distinct > 50, "a real page has many colours, got " + distinct);
    }

    // ------------------------------------------------------------------ mix-blend-mode

    private static final String BLEND_BASE = "<div style='width:40px;height:40px;background:#ff8800'></div>";

    @Test
    void multiplyBlendsWithTheBackdrop() {
        SoftwareRenderBackend s = render(BLEND_BASE + "<div style='margin-top:-20px;width:40px;height:40px;background:#80c0ff;mix-blend-mode:multiply'></div>", 60, 70);
        int overlap = px(s, 20, 30), only = px(s, 20, 50), base = px(s, 20, 10);
        assertEquals(0xFFFF8800, base);
        assertEquals(0xFF80C0FF, only, "no backdrop: the layer's own colour");
        assertEquals(255, a(overlap));
        assertEquals(128, r(overlap), 2);
        assertEquals(102, g(overlap), 2);
        assertEquals(0, b(overlap), 2);
    }

    @Test
    void differenceAndScreenBlend() {
        SoftwareRenderBackend d = render(BLEND_BASE + "<div style='margin-top:-20px;width:40px;height:40px;background:#ffffff;mix-blend-mode:difference'></div>", 60, 70);
        int c = px(d, 20, 30);
        assertEquals(0, r(c), 2);
        assertEquals(255 - 136, g(c), 2);
        assertEquals(255, b(c), 2);
        SoftwareRenderBackend sc = render(BLEND_BASE + "<div style='margin-top:-20px;width:40px;height:40px;background:#000000;mix-blend-mode:screen'></div>", 60, 70);
        assertEquals(0xFFFF8800, px(sc, 20, 30), "screen with black leaves the backdrop");
    }

    @Test
    void blendHonoursGroupOpacity() {
        SoftwareRenderBackend s = render(BLEND_BASE + "<div style='margin-top:-20px;width:40px;height:40px;background:#000000;opacity:0.5;mix-blend-mode:multiply'></div>", 60, 70);
        int c = px(s, 20, 30);
        assertEquals(128, r(c), 3, "half-way between the backdrop and the multiplied black");
    }

    @Test
    void isolationKeepsChildBlendInsideTheGroup() {
        // 孤立した箱の中の multiply は、箱の外の背景（橙）には効かない
        SoftwareRenderBackend s = render(BLEND_BASE + "<div style='margin-top:-30px;width:30px;height:30px;isolation:isolate'>"
                + "<div style='width:30px;height:30px;background:#80c0ff;mix-blend-mode:multiply'></div></div>", 60, 70);
        assertEquals(0xFF80C0FF, px(s, 15, 15));
    }
}
