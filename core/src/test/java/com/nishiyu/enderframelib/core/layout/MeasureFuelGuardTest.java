package com.nishiyu.enderframelib.core.layout;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.SoftwareRendering;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * W9: float を含む入れ子の flex は測定キャッシュに当たらず、測定が 2^深さに膨らんでレイアウトが終わらなかった
 * （WPT の flexbox-justify-content-horiz-001b.xhtml。HTML では {@code <div class="a"/>} が入れ子になり、float の flex が約 60 段重なる）。
 * 箱の数に比べた測り直しの上限（燃料）で必ず終わることと、普通の文書では燃料が働かないことを確かめる。
 */
class MeasureFuelGuardTest {
    private static final String CSS = "<style>div.flexbox{min-width:200px;display:flex;margin-bottom:2px;float:left;clear:both}"
            + "div.a{height:10px;flex:0 10px;background:lightgreen}div.b{height:10px;flex:0 50px}div.c{height:10px;flex:0 100px}</style>";

    private static String nestedFloatFlex(String justify, int groups) {
        StringBuilder sb = new StringBuilder("<html><head>").append(CSS).append("</head><body>");
        for (int i = 0; i < groups; i++) {
            sb.append("<div class=\"flexbox\" style=\"justify-content:").append(justify).append("\">")
                    .append("<div class=\"a\"/><div class=\"b\"/><div class=\"c\"/></div>");
        }
        return sb.append("</body></html>").toString();
    }

    @Test
    @Timeout(30)
    void deeplyNestedFloatFlexTerminates() throws java.io.IOException {
        // 実物（WPT）。以前はレイアウトが終わらなかった
        String html = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/test/resources/wpt/reftests/css/css-flexbox/flexbox-justify-content-horiz-001b.xhtml"));
        long before = LayoutBox.measureFuelReused();
        SoftwareRendering.displayList(html, 800, 600, FontResolver.FALLBACK);
        assertTrue(LayoutBox.measureFuelReused() > before, "病的な文書では燃料が働く");
    }

    @Test
    void ordinaryDocumentsNeverRunOutOfFuel() {
        long before = LayoutBox.measureFuelReused();
        StringBuilder sb = new StringBuilder("<html><body>");
        for (int i = 0; i < 2000; i++) sb.append("<div style='display:flex;gap:4px'><span>item ").append(i).append("</span><div style='flex:1'>x</div></div>");
        SoftwareRendering.displayList(sb.append("</body></html>").toString(), 800, 600, FontResolver.FALLBACK);
        SoftwareRendering.displayList(nestedFloatFlex("flex-start", 8), 800, 600, FontResolver.FALLBACK);
        assertEquals(before, LayoutBox.measureFuelReused(), "普通の文書・浅い入れ子では前回の測定へ落とさない");
    }
}
