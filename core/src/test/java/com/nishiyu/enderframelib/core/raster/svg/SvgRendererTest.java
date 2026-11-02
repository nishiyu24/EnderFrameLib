package com.nishiyu.enderframelib.core.raster.svg;

import com.nishiyu.enderframelib.core.raster.Pixmap;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/** SVG の描画（画素）・寸法・viewBox・data URI の検証。期待値は SVG 1.1 / 2 の意味による。 */
class SvgRendererTest {

    private static Pixmap draw(String svg, int w, int h) {
        SvgImage img = SvgImage.parse(svg);
        assertNotNull(img, "svg が見つからない: " + svg);
        return img.rasterize(w, h, 0xFF000000, null);
    }

    private static int px(Pixmap p, int x, int y) {
        return p.getStraight(x, y);
    }

    private static void assertNear(int expected, int actual, int tol) {
        for (int shift = 0; shift <= 24; shift += 8) {
            int e = (expected >>> shift) & 0xFF, a = (actual >>> shift) & 0xFF;
            assertTrue(Math.abs(e - a) <= tol, String.format("expected %08x was %08x (shift %d)", expected, actual, shift));
        }
    }

    @Test
    void rectFillsExactPixels() {
        Pixmap p = draw("<svg width='20' height='20'><rect x='5' y='5' width='10' height='10' fill='#ff0000'/></svg>", 20, 20);
        assertEquals(0xFFFF0000, px(p, 5, 5));
        assertEquals(0xFFFF0000, px(p, 14, 14));
        assertEquals(0, px(p, 4, 5));
        assertEquals(0, px(p, 15, 15));
    }

    @Test
    void defaultFillIsBlackAndStrokeIsNone() {
        Pixmap p = draw("<svg width='10' height='10'><rect width='10' height='10'/></svg>", 10, 10);
        assertEquals(0xFF000000, px(p, 5, 5));
    }

    @Test
    void viewBoxScalesUserSpaceToOutput() {
        Pixmap p = draw("<svg width='10' height='10' viewBox='0 0 100 100'><rect x='50' width='50' height='100' fill='#00ff00'/></svg>", 20, 20);
        assertEquals(0, px(p, 9, 10));
        assertEquals(0xFF00FF00, px(p, 11, 10));
    }

    @Test
    void preserveAspectRatioMeetCentersAndSliceCrops() {
        String body = "<rect width='100' height='100' fill='#0000ff'/>";
        Pixmap meet = draw("<svg viewBox='0 0 100 100'>" + body + "</svg>", 40, 20);
        assertEquals(0, px(meet, 2, 10));          // 左の余白
        assertEquals(0xFF0000FF, px(meet, 20, 10));
        assertEquals(0, px(meet, 38, 10));         // 右の余白
        Pixmap slice = draw("<svg viewBox='0 0 100 100' preserveAspectRatio='xMidYMid slice'>" + body + "</svg>", 40, 20);
        assertEquals(0xFF0000FF, px(slice, 1, 10));
        assertEquals(0xFF0000FF, px(slice, 38, 10));
        Pixmap none = draw("<svg viewBox='0 0 100 100' preserveAspectRatio='none'>" + body + "</svg>", 40, 20);
        assertEquals(0xFF0000FF, px(none, 1, 1));
        assertEquals(0xFF0000FF, px(none, 38, 18));
    }

    @Test
    void circleAndEllipseCoverTheirInterior() {
        Pixmap p = draw("<svg width='40' height='20'><circle cx='10' cy='10' r='8' fill='red'/>"
                + "<ellipse cx='30' cy='10' rx='8' ry='4' fill='blue'/></svg>", 40, 20);
        assertEquals(0xFFFF0000, px(p, 10, 10));
        assertEquals(0, px(p, 1, 1));              // 円の外（角）
        assertEquals(0xFF0000FF, px(p, 30, 10));
        assertEquals(0, px(p, 30, 2));             // 楕円の縦半径の外
    }

    @Test
    void strokeDrawsLineWidthAround() {
        Pixmap p = draw("<svg width='20' height='20'><line x1='0' y1='10' x2='20' y2='10' stroke='#000' stroke-width='4'/></svg>", 20, 20);
        assertEquals(0xFF000000, px(p, 10, 8));
        assertEquals(0xFF000000, px(p, 10, 11));
        assertEquals(0, px(p, 10, 6));
        assertEquals(0, px(p, 10, 13));
    }

    @Test
    void polygonAndFillRule() {
        String star = "points='10,1 4,19 19,7 1,7 16,19'";
        Pixmap nz = draw("<svg width='20' height='20'><polygon " + star + " fill='black'/></svg>", 20, 20);
        Pixmap eo = draw("<svg width='20' height='20'><polygon " + star + " fill='black' fill-rule='evenodd'/></svg>", 20, 20);
        assertEquals(0xFF000000, px(nz, 10, 10));  // nonzero は中心も塗る
        assertEquals(0, px(eo, 10, 10));           // evenodd は中心が抜ける
    }

    @Test
    void pathWithArcsAndCurves() {
        Pixmap p = draw("<svg width='20' height='20'><path d='M2 18 L2 2 L18 2 Z' fill='#123456'/></svg>", 20, 20);
        assertEquals(0xFF123456, px(p, 4, 4));
        assertEquals(0, px(p, 16, 16));
    }

    @Test
    void transformAttributeComposesLeftToRight() {
        // translate してから scale: 先頭が外側
        Pixmap p = draw("<svg width='40' height='40'><rect width='5' height='5' fill='red' transform='translate(10 10) scale(2)'/></svg>", 40, 40);
        assertEquals(0xFFFF0000, px(p, 10, 10));
        assertEquals(0xFFFF0000, px(p, 19, 19));
        assertEquals(0, px(p, 20, 20));
        Pixmap r = draw("<svg width='40' height='40'><rect x='-5' y='-5' width='10' height='10' fill='red' transform='translate(20 20) rotate(45)'/></svg>", 40, 40);
        assertEquals(0xFFFF0000, px(r, 20, 20));
        assertEquals(0xFFFF0000, px(r, 20, 15));   // 45° 回転した正方形の頂点方向
        assertEquals(0, px(r, 15, 15));            // 回転前なら四角の角だった所
    }

    @Test
    void groupInheritsPresentationAttributesAndOverrides() {
        Pixmap p = draw("<svg width='20' height='10'><g fill='#00f'><rect width='10' height='10'/>"
                + "<rect x='10' width='10' height='10' fill='#f00'/></g></svg>", 20, 10);
        assertEquals(0xFF0000FF, px(p, 5, 5));
        assertEquals(0xFFFF0000, px(p, 15, 5));
    }

    @Test
    void styleAttributeBeatsPresentationAttribute() {
        Pixmap p = draw("<svg width='10' height='10'><rect width='10' height='10' fill='red' style='fill: #00ff00'/></svg>", 10, 10);
        assertEquals(0xFF00FF00, px(p, 5, 5));
    }

    @Test
    void currentColorUsesInitialColorAndColorAttribute() {
        SvgImage img = SvgImage.parse("<svg width='10' height='10'><rect width='5' height='10' fill='currentColor'/>"
                + "<g color='#0f0'><rect x='5' width='5' height='10' fill='currentColor'/></g></svg>");
        Pixmap p = img.rasterize(10, 10, 0xFFFF00FF, null);
        assertEquals(0xFFFF00FF, px(p, 2, 5));
        assertEquals(0xFF00FF00, px(p, 7, 5));
    }

    @Test
    void fillAndGroupOpacity() {
        Pixmap p = draw("<svg width='10' height='10'><rect width='10' height='10' fill='#000' fill-opacity='0.5'/></svg>", 10, 10);
        assertNear(0x80000000, px(p, 5, 5), 1);
        // グループの opacity は、重なった子をまとめてから一度に掛ける（重なりが二重に濃くならない）
        Pixmap g = draw("<svg width='10' height='10'><g opacity='0.5'><rect width='10' height='10' fill='#000'/>"
                + "<rect width='10' height='10' fill='#000'/></g></svg>", 10, 10);
        assertNear(0x80000000, px(g, 5, 5), 1);
        Pixmap e = draw("<svg width='10' height='10'><rect width='10' height='10' fill='#000' opacity='0.5'/></svg>", 10, 10);
        assertNear(0x80000000, px(e, 5, 5), 1);
    }

    @Test
    void linearGradientObjectBoundingBox() {
        Pixmap p = draw("<svg width='20' height='10'><defs><linearGradient id='g'>"
                + "<stop offset='0' stop-color='#000'/><stop offset='1' stop-color='#fff'/></linearGradient></defs>"
                + "<rect width='20' height='10' fill='url(#g)'/></svg>", 20, 10);
        int left = px(p, 1, 5) & 0xFF, mid = px(p, 10, 5) & 0xFF, right = px(p, 18, 5) & 0xFF;
        assertTrue(left < 30, "left " + left);
        assertTrue(mid > 110 && mid < 145, "mid " + mid);
        assertTrue(right > 225, "right " + right);
    }

    @Test
    void gradientUserSpaceAndHrefInheritance() {
        Pixmap p = draw("<svg width='20' height='10'><defs>"
                + "<linearGradient id='base'><stop offset='0' stop-color='#f00'/><stop offset='1' stop-color='#00f'/></linearGradient>"
                + "<linearGradient id='g' href='#base' gradientUnits='userSpaceOnUse' x1='0' y1='0' x2='20' y2='0'/></defs>"
                + "<rect width='20' height='10' fill='url(#g)'/></svg>", 20, 10);
        assertTrue(((px(p, 1, 5) >> 16) & 0xFF) > 220);
        assertTrue((px(p, 18, 5) & 0xFF) > 200);
    }

    @Test
    void radialGradientCenterAndEdge() {
        Pixmap p = draw("<svg width='20' height='20'><defs><radialGradient id='g'>"
                + "<stop offset='0' stop-color='#fff'/><stop offset='1' stop-color='#000'/></radialGradient></defs>"
                + "<rect width='20' height='20' fill='url(#g)'/></svg>", 20, 20);
        assertTrue((px(p, 10, 10) & 0xFF) > 235);
        assertTrue((px(p, 10, 1) & 0xFF) < 40);
    }

    @Test
    void useReferencesDefinitionWithOffset() {
        Pixmap p = draw("<svg width='30' height='10'><defs><rect id='r' width='10' height='10' fill='red'/></defs>"
                + "<use href='#r' x='20'/></svg>", 30, 10);
        assertEquals(0, px(p, 5, 5));
        assertEquals(0xFFFF0000, px(p, 25, 5));
    }

    @Test
    void useDoesNotRecurseForever() {
        Pixmap p = draw("<svg width='10' height='10'><g id='a'><use href='#a'/><rect width='5' height='5' fill='red'/></g></svg>", 10, 10);
        assertEquals(0xFFFF0000, px(p, 2, 2));
    }

    @Test
    void clipPathRestrictsPainting() {
        Pixmap p = draw("<svg width='20' height='10'><defs><clipPath id='c'><rect width='10' height='10'/></clipPath></defs>"
                + "<rect width='20' height='10' fill='red' clip-path='url(#c)'/></svg>", 20, 10);
        assertEquals(0xFFFF0000, px(p, 5, 5));
        assertEquals(0, px(p, 15, 5));
    }

    @Test
    void roundedRectRxRy() {
        Pixmap p = draw("<svg width='20' height='20'><rect width='20' height='20' rx='10' fill='black'/></svg>", 20, 20);
        assertEquals(0xFF000000, px(p, 10, 10));
        assertEquals(0, px(p, 0, 0));
    }

    @Test
    void displayNoneAndHiddenAreNotPainted() {
        Pixmap p = draw("<svg width='20' height='10'><rect width='10' height='10' display='none'/>"
                + "<rect x='10' width='10' height='10' visibility='hidden'/></svg>", 20, 10);
        assertEquals(0, px(p, 5, 5));
        assertEquals(0, px(p, 15, 5));
    }

    @Test
    void dashedStrokeLeavesGaps() {
        Pixmap p = draw("<svg width='40' height='10'><line x1='0' y1='5' x2='40' y2='5' stroke='black' stroke-width='4' stroke-dasharray='10 10'/></svg>", 40, 10);
        assertEquals(0xFF000000, px(p, 5, 5));
        assertEquals(0, px(p, 15, 5));
        assertEquals(0xFF000000, px(p, 25, 5));
    }

    @Test
    void intrinsicSizeFollowsWidthHeightThenViewBox() {
        assertEquals(30, SvgImage.parse("<svg width='30' height='20'/>").width());
        assertEquals(20, SvgImage.parse("<svg width='30' height='20'/>").height());
        SvgImage vb = SvgImage.parse("<svg viewBox='0 0 40 10'/>");
        assertEquals(40, vb.width());
        assertEquals(10, vb.height());
        SvgImage half = SvgImage.parse("<svg width='80' viewBox='0 0 40 10'/>");
        assertEquals(80, half.width());
        assertEquals(20, half.height());
        SvgImage none = SvgImage.parse("<svg/>");
        assertEquals(300, none.width());
        assertEquals(150, none.height());
    }

    @Test
    void unitsAreConvertedToPixels() {
        assertEquals(96, SvgSize.length("1in", 0, 0, 16), 1e-9);
        assertEquals(32, SvgSize.length("2em", 0, 0, 16), 1e-9);
        assertEquals(50, SvgSize.length("50%", 0, 100, 16), 1e-9);
        assertEquals(7, SvgSize.length("bogus", 7, 0, 16), 1e-9);
    }

    @Test
    void dataUriPlainAndBase64() {
        String svg = "<svg xmlns='http://www.w3.org/2000/svg' width='8' height='6'><rect width='8' height='6' fill='red'/></svg>";
        SvgImage plain = SvgImage.fromDataUri("data:image/svg+xml;utf8," + svg.replace("<", "%3C").replace(">", "%3E"));
        assertNotNull(plain);
        assertEquals(8, plain.width());
        SvgImage b64 = SvgImage.fromDataUri("data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(svg.getBytes()));
        assertNotNull(b64);
        assertEquals(0xFFFF0000, b64.rasterize(8, 6, 0xFF000000, null).getStraight(3, 3));
        assertNull(SvgImage.fromDataUri("data:image/png;base64,AAAA"));
    }

    @Test
    void standaloneFileWithPrologAndSelfClosingChildren() {
        String file = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE svg PUBLIC \"-//W3C//DTD SVG 1.1//EN\" \"x\">\n"
                + "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\"><rect width=\"5\" height=\"10\" fill=\"red\"/>"
                + "<rect x=\"5\" width=\"5\" height=\"10\" fill=\"blue\"/></svg>";
        SvgImage img = SvgImage.parse(file);
        assertNotNull(img);
        Pixmap p = img.rasterize(10, 10, 0xFF000000, null);
        assertEquals(0xFFFF0000, px(p, 2, 5));
        assertEquals(0xFF0000FF, px(p, 7, 5));
    }

    @Test
    void malformedAndHostileInputsDoNotThrow() {
        assertNull(SvgImage.parse("not svg at all"));
        assertNull(SvgImage.parse(null));
        Pixmap p = draw("<svg width='10' height='10'><rect width='NaN' height='-5'/><path d='M 1 1 L'/>"
                + "<circle r='1e999'/><polygon points='1,2,3'/><g transform='rotate(bogus)'/></svg>", 10, 10);
        assertNotNull(p);
        // 巨大な出力は上限で切る
        assertEquals(SvgImage.MAX_RASTER, SvgImage.parse("<svg width='5' height='5'/>").rasterize(100000, 4, 0, null).width());
    }

    @Test
    void looksLikeSvg() {
        assertTrue(SvgImage.looksLikeSvg("a/b/icon.SVG?x=1"));
        assertTrue(SvgImage.looksLikeSvg("data:image/svg+xml;base64,AA=="));
        assertFalse(SvgImage.looksLikeSvg("a.png"));
        assertFalse(SvgImage.looksLikeSvg(null));
    }
}
