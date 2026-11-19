package com.nishiyu.enderframelib.core.layout;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.SoftwareRendering;
import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * W9 / W9b: float を含む入れ子の flex は測定キャッシュに当たらず、測定が 2^深さに膨らんでレイアウトが終わらなかった
 * （WPT の flexbox-justify-content-horiz-001b.xhtml。HTML では {@code <div class="a"/>} が入れ子になり、float の flex が約 60 段重なる）。
 * W9 の燃料上限が 0 回で終わること（線形回数の測定で収束すること）と、入れ子 float+flex・先立つ float・増分変更で
 * 再利用なしと表示リストが一致することを確かめる。
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
        // 実物（WPT）。以前はレイアウトが終わらなかった（W9 燃料導入時は 66,800 測定 / 燃料 31 回）
        String html = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/test/resources/wpt/reftests/css/css-flexbox/flexbox-justify-content-horiz-001b.xhtml"));
        long before = LayoutBox.measureFuelReused();
        long mBefore = LayoutBox.measurementsComputed();
        SoftwareRendering.displayList(html, 800, 600, FontResolver.FALLBACK);
        long measured = LayoutBox.measurementsComputed() - mBefore;
        assertEquals(before, LayoutBox.measureFuelReused(), "W9b: 燃料は 0 であるべき");
        assertTrue(measured < 1000, "W9b: 測定回数が線形で収束すべき（実測=" + measured + "）");
    }

    @Test
    @Timeout(30)
    void nestedFloatFlexScalesLinearlyAndMatchesUnreused() {
        long f0 = LayoutBox.measureFuelReused();
        long m0 = LayoutBox.measurementsComputed();
        SoftwareRendering.displayList(nestedFloatFlex("flex-start", 12), 800, 600, FontResolver.FALLBACK);
        long m12 = LayoutBox.measurementsComputed() - m0;
        long m1 = LayoutBox.measurementsComputed();
        SoftwareRendering.displayList(nestedFloatFlex("flex-start", 24), 800, 600, FontResolver.FALLBACK);
        long m24 = LayoutBox.measurementsComputed() - m1;
        assertEquals(f0, LayoutBox.measureFuelReused(), "深い入れ子 float+flex でも燃料は 0");
        assertTrue(m24 <= m12 * 3, "深さ 12 -> 24 で指数爆発しない: m12=" + m12 + ", m24=" + m24);

        // 先立つ float がある非 BFC ブロック（hadIncomingFloats=true）や非 BFC の空フローを含む入れ子で、再利用 OFF と結果一致
        String html = """
                <html><head><style>
                  div.flexbox { min-width: 180px; display: flex; float: left; clear: both; margin-bottom: 2px }
                  div.a { flex: 0 20px }
                  div.b { flex: 0 60px }
                  div.wrap { }
                  div.sibFloat { float: left; width: 40px; height: 15px; background: red }
                </style></head><body>
                  <div class="flexbox"><div class="a"><div class="wrap">
                    <div class="sibFloat"></div>
                    <div class="wrap"><div class="flexbox"><div class="b">inner</div></div></div>
                  </div></div></div>
                  <div class="flexbox" style="justify-content:space-between"><div class="a"/><div class="b"/><div class="c"/></div>
                  <div class="flexbox" style="justify-content:space-between"><div class="a"/><div class="b"/><div class="c"/></div>
                  <div class="flexbox" style="justify-content:space-between"><div class="a"/><div class="b"/><div class="c"/></div>
                </body></html>
                """;
        assertReuseMatchesFresh(html, 800, 600);
        assertReuseMatchesFresh(html, 320, 600);
    }

    @Test
    @Timeout(30)
    void incrementalUpdateInNestedFloatFlexMatchesUnreused() {
        String html = "<html><head>" + CSS + "</head><body>"
                + "<div class='flexbox'><div class='a'><div class='b'><div id='target' style='width:30px;height:10px'>hi</div>"
                + "<div class='flexbox'><div class='a'><div class='b'><div class='c'></div></div></div></div>"
                + "</div></div></div></body></html>";
        try (StandaloneView reuseOn = new StandaloneView(html, 800, 600, FontResolver.FALLBACK);
             StandaloneView reuseOff = new StandaloneView(html, 800, 600, FontResolver.FALLBACK)) {
            reuseOn.ui().flushInvalidation();
            setAllReuseFlags(false);
            reuseOff.ui().flushInvalidation();
            setAllReuseFlags(true);
            assertEquals(reuseOff.ui().renderCache(), reuseOn.ui().renderCache(), "初回が一致");

            mutateTarget(reuseOn.root(), "changed text", "width:90px;height:10px");
            reuseOn.ui().flushInvalidation();
            mutateTarget(reuseOff.root(), "changed text", "width:90px;height:10px");
            setAllReuseFlags(false);
            reuseOff.ui().flushInvalidation();
            setAllReuseFlags(true);
            assertEquals(reuseOff.ui().renderCache(), reuseOn.ui().renderCache(), "増分変更後が一致");

            // ビューポート幅の変更（リサイズ）でも一致
            reuseOn.ui().recomputeLayout(320, 600);
            setAllReuseFlags(false);
            reuseOff.ui().recomputeLayout(320, 600);
            setAllReuseFlags(true);
            assertEquals(reuseOff.ui().renderCache(), reuseOn.ui().renderCache(), "リサイズ後が一致");
        } finally {
            setAllReuseFlags(true);
        }
    }

    private static void mutateTarget(HtmlNode root, String text, String style) {
        if (root == null) return;
        HtmlNode target = root.getNodeById("target");
        if (target instanceof HtmlElement el) {
            el.setAttribute("style", style);
            el.updateTextContent(text);
        }
    }

    private static void assertReuseMatchesFresh(String html, int w, int h) {
        try {
            List<DisplayItem> withReuse = SoftwareRendering.displayList(html, w, h, FontResolver.FALLBACK);
            setAllReuseFlags(false);
            List<DisplayItem> withoutReuse = SoftwareRendering.displayList(html, w, h, FontResolver.FALLBACK);
            assertEquals(withoutReuse, withReuse, "再利用 ON/OFF の描画リストが " + w + "x" + h + " で完全一致");
        } finally {
            setAllReuseFlags(true);
        }
    }

    private static void setAllReuseFlags(boolean enabled) {
        com.nishiyu.enderframelib.core.kernel.Shortcut.TENTATIVE_REUSE.set(enabled);
        com.nishiyu.enderframelib.core.kernel.Shortcut.FIT_REUSE.set(enabled);
        com.nishiyu.enderframelib.core.kernel.Shortcut.BLOCK_PERCENT_FREE.set(enabled);
        com.nishiyu.enderframelib.core.kernel.Shortcut.DEFINITE_LEAF_REUSE.set(enabled);
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
