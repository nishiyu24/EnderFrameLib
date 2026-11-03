package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.common.SoftwareRendering;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.font.OutlineFontFace;
import com.nishiyu.enderframelib.core.text.font.SystemFonts;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code -webkit-text-stroke}: SDF（符号付き距離場）の字形から、輪郭の上に中心を置いた帯を描く。 */
class TextStrokeTest {

    private static void assumeFonts() {
        Assumptions.assumeTrue(SystemFonts.system().resolve(null) instanceof OutlineFontFace, "no system fonts");
    }

    private static String html(String textStyle) {
        return "<html><body style='margin:0;background:#fff'><div style='padding:10px;font:bold 72px sans-serif;" + textStyle + "'>HIl</div></body></html>";
    }

    private static SoftwareRenderBackend render(String textStyle, int w, int h, float dpr) {
        return SoftwareRendering.renderHtml(html(textStyle), w, h, dpr, 0L);
    }

    /** 青い縁取りの画素（青が強く赤が弱い）の数。 */
    private static int blueInk(SoftwareRenderBackend b) {
        int n = 0;
        var p = b.pixmap();
        for (int y = 0; y < p.height(); y++) {
            for (int x = 0; x < p.width(); x++) {
                int c = p.getStraight(x, y);
                int a = c >>> 24, r = (c >> 16) & 0xFF, bl = c & 0xFF;
                if (a > 200 && bl > 200 && r < 60) n++;
            }
        }
        return n;
    }

    private static int redInk(SoftwareRenderBackend b) {
        int n = 0;
        var p = b.pixmap();
        for (int y = 0; y < p.height(); y++) {
            for (int x = 0; x < p.width(); x++) {
                int c = p.getStraight(x, y);
                if ((c >>> 24) > 200 && ((c >> 16) & 0xFF) > 200 && (c & 0xFF) < 60) n++;
            }
        }
        return n;
    }

    @Test
    void styleIsParsedFromShorthandAndLonghands() {
        assumeFonts();
        for (String css : new String[]{"color:#f00;-webkit-text-stroke:3px #00f", "color:#f00;-webkit-text-stroke-width:3px;-webkit-text-stroke-color:#00f",
                "color:#f00;-webkit-text-stroke:#00f 3px"}) {
            List<DisplayItem> items = SoftwareRendering.displayList(html(css), 200, 110);
            DisplayItem text = items.stream().filter(i -> i.text() != null && i.text().contains("HIl")).findFirst().orElseThrow();
            assertEquals("3.0 ff0000ff", text.events().get("text-stroke"), css);
        }
        List<DisplayItem> none = SoftwareRendering.displayList(html("color:#f00"), 200, 110);
        assertTrue(none.stream().noneMatch(i -> i.events() != null && i.events().containsKey("text-stroke")), "指定が無ければ縁取りは出ない");
    }

    @Test
    void strokeDrawsABandOverTheFill() {
        assumeFonts();
        SoftwareRenderBackend plain = render("color:#f00", 200, 110, 1f);
        SoftwareRenderBackend stroked = render("color:#f00;-webkit-text-stroke:3px #00f", 200, 110, 1f);
        assertEquals(0, blueInk(plain), "縁取りなしに青は無い");
        assertTrue(blueInk(stroked) > 300, "縁取りの画素がある: " + blueInk(stroked));
        assertTrue(redInk(stroked) > 100, "太い字の中心は塗りのまま残る: " + redInk(stroked));
        assertTrue(redInk(stroked) < redInk(plain), "縁取りの内側半分が塗りを覆う");
    }

    @Test
    void strokeWidthGrowsTheBandAndIsCappedBySdfSpread() {
        assumeFonts();
        int thin = blueInk(render("color:#f00;-webkit-text-stroke:1px #00f", 220, 120, 1f));
        int thick = blueInk(render("color:#f00;-webkit-text-stroke:4px #00f", 220, 120, 1f));
        assertTrue(thick > thin * 2, "太いほど画素が増える: " + thin + " -> " + thick);
        // 広がり（8px）を超える極端な幅でも壊れない（頭打ち）
        int huge = blueInk(render("color:#f00;-webkit-text-stroke:40px #00f", 220, 120, 1f));
        assertTrue(huge > thick && huge < 220 * 120, "huge=" + huge);
    }

    @Test
    void transparentFillLeavesAnOutlineOnly() {
        assumeFonts();
        SoftwareRenderBackend b = render("color:transparent;-webkit-text-stroke:2px #00f", 200, 110, 1f);
        assertTrue(blueInk(b) > 200, "輪郭は描かれる");
        assertEquals(0, redInk(b));
        // 太字 "H" の縦棒を横切る行で「青 → 白（背景のまま）→ 青」が見える = 輪郭の帯だけで、中は塗られていない
        int hollowRows = 0;
        var p = b.pixmap();
        for (int y = 20; y < 70; y++) {
            int state = 0, white = 0; // 0: 青待ち, 1: 青の後の白を数える
            boolean found = false;
            for (int x = 5; x < 120 && !found; x++) {
                int c = p.getStraight(x, y);
                boolean blue = (c & 0xFF) > 200 && ((c >> 16) & 0xFF) < 120;
                boolean bg = ((c >> 16) & 0xFF) > 240 && ((c >> 8) & 0xFF) > 240 && (c & 0xFF) > 240;
                if (state == 0) {
                    if (blue) state = 1;
                } else if (bg) {
                    white++;
                } else if (blue && white >= 3) {
                    found = true;
                } else if (blue) {
                    white = 0;
                } else {
                    state = 0;
                    white = 0;
                }
            }
            if (found) hollowRows++;
        }
        assertTrue(hollowRows > 10, "字の中が塗られず背景のまま残る行: " + hollowRows);
    }

    @Test
    void strokeScalesWithDevicePixelRatioAndRotation() {
        assumeFonts();
        int one = blueInk(render("color:#f00;-webkit-text-stroke:2px #00f", 200, 110, 1f));
        int two = blueInk(render("color:#f00;-webkit-text-stroke:2px #00f", 400, 220, 2f));
        assertTrue(two > one * 3, "2 倍の解像度では画素が約 4 倍: " + one + " -> " + two);
        // 回転（変形の経路）でも縁取りが出る
        SoftwareRenderBackend rot = render("color:#f00;-webkit-text-stroke:3px #00f;transform:rotate(12deg);transform-origin:0 0", 260, 160, 1f);
        assertTrue(blueInk(rot) > 300, "回転した文字にも縁取り: " + blueInk(rot));
        assertNotNull(rot.pixmap());
    }
}
