package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.common.StandaloneView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code text-shadow}（ずらし・ぼかし・色）が文字の外へ色を落とすこと。 */
class TextEffectsTest {
    private static String page(String css) {
        return "<html><head><style>body{margin:0;background:#000}"
                + "#t{position:absolute;left:40px;top:40px;font-size:40px;font-weight:bold;color:#ffffff;" + css + "}"
                + "</style></head><body><div id='t'>HI</div></body></html>";
    }

    private static int[] render(String css) {
        StandaloneView view = new StandaloneView(page(css), 200, 160);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(200, 160, 1f)) {
            view.frame(1000, cpu, true);
            int[] px = new int[200 * 160];
            for (int y = 0; y < 160; y++) for (int x = 0; x < 200; x++) px[y * 200 + x] = cpu.pixmap().getStraight(x, y) & 0xFFFFFF;
            return px;
        }
    }

    /** 赤みの強い（影の色が出ている）画素の数。本体は白、背景は黒なので、赤だけが影。 */
    private static int redPixels(int[] px) {
        int n = 0;
        for (int c : px) if (((c >> 16) & 0xFF) > 40 && ((c >> 8) & 0xFF) < ((c >> 16) & 0xFF) / 2) n++;
        return n;
    }

    @Test
    void withoutShadowThereIsNoColor() {
        assertEquals(0, redPixels(render("")));
    }

    @Test
    void offsetShadowIsPaintedInItsColor() {
        assertTrue(redPixels(render("text-shadow:6px 6px 0 #ff0000")) > 50);
    }

    @Test
    void blurredShadowSpreadsWiderThanTheHardOne() {
        int hard = redPixels(render("text-shadow:0 0 0 #ff0000"));
        int soft = redPixels(render("text-shadow:0 0 12px #ff0000"));
        assertTrue(soft > hard + 100, "ぼかした影は広がる: hard=" + hard + " soft=" + soft);
    }

    @Test
    void legacyTrueKeepsTheDarkCopy() {
        // 旧来の text-shadow:true は影の色を持たず、本体の暗い複製（赤みなし）
        assertEquals(0, redPixels(render("text-shadow:true")));
    }

    private static int[] renderClip(String clipDecl) {
        String html = "<html><head><style>body{margin:0;background:#000}"
                + "#t{position:absolute;left:10px;top:10px;width:180px;height:70px;font-size:56px;font-weight:bold;color:transparent;"
                + "background:linear-gradient(90deg,#ff0000,#ff0000);" + clipDecl + "}"
                + "</style></head><body><div id='t'>HI</div></body></html>";
        StandaloneView view = new StandaloneView(html, 200, 100);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(200, 100, 1f)) {
            view.frame(1000, cpu, true);
            int[] px = new int[200 * 100];
            for (int y = 0; y < 100; y++) for (int x = 0; x < 200; x++) px[y * 200 + x] = cpu.pixmap().getStraight(x, y) & 0xFFFFFF;
            return px;
        }
    }

    private static int count(int[] px, int rgb) {
        int n = 0;
        for (int c : px) if (c == rgb) n++;
        return n;
    }

    @Test
    void backgroundClipTextKeepsTheBackgroundOnlyInsideTheGlyphs() {
        int whole = count(renderClip(""), 0xFF0000);                       // 文字は透明、箱いっぱいが赤
        int clipped = count(renderClip("background-clip:text"), 0xFF0000); // 文字の形だけ赤
        assertTrue(whole > 180 * 70 * 0.9, "対照: 箱全体が赤 " + whole);
        assertTrue(clipped > 150, "文字の中は赤が残る: " + clipped);
        assertTrue(clipped < whole / 2, "文字の外は背景が消える: " + clipped + " < " + whole);
    }

    @Test
    void webkitPrefixedBackgroundClipTextWorksToo() {
        assertTrue(count(renderClip("-webkit-background-clip:text"), 0xFF0000) < 180 * 70 / 2);
    }

    private static int[] renderBox(String boxCss, long now) {
        String html = "<html><head><style>body{margin:0;background:#000}"
                + "#b{position:absolute;left:75px;top:75px;width:50px;height:50px;background:#00ff00;" + boxCss + "}"
                + "</style></head><body><div id='b'></div></body></html>";
        StandaloneView view = new StandaloneView(html, 200, 200);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(200, 200, 1f)) {
            view.frame(now, cpu, true);
            int[] px = new int[200 * 200];
            for (int y = 0; y < 200; y++) for (int x = 0; x < 200; x++) px[y * 200 + x] = cpu.pixmap().getStraight(x, y) & 0xFFFFFF;
            return px;
        }
    }

    @Test
    void skewXShearsTheBox() {
        int[] px = renderBox("transform:skewX(45deg)", 1000);
        // 中心 (100,100) から下の点は右へ、上の点は左へ、距離ぶんずれる。y=80 では 55..105、y=120 では 95..145
        assertEquals(0x00FF00, px[80 * 200 + 60], "上辺付近は左へずれる");
        assertEquals(0x000000, px[80 * 200 + 110], "元の右上は抜ける");
        assertEquals(0x00FF00, px[120 * 200 + 140], "下辺付近は右へずれる");
        assertEquals(0x000000, px[120 * 200 + 80], "元の左下は抜ける");
    }

    @Test
    void rotateYFlipSquashesTheBoxHorizontally() {
        int[] px = renderBox("transform:rotateY(60deg)", 1000); // 幅が cos60 = 半分
        assertEquals(0x00FF00, px[100 * 200 + 100], "中心");
        assertEquals(0x000000, px[100 * 200 + 78], "左端は縮んで空く");
        assertEquals(0x00FF00, px[100 * 200 + 90], "縮んだ幅の中は塗られる");
    }

    @Test
    void keyframeSkewProgressesOverTime() {
        String html = "<html><head><style>body{margin:0;background:#000}"
                + "#b{position:absolute;left:75px;top:75px;width:50px;height:50px;background:#00ff00;animation:k 2s linear infinite}"
                + "@keyframes k{from{transform:skewX(0deg)}to{transform:skewX(45deg)}}</style></head><body><div id='b'></div></body></html>";
        StandaloneView view = new StandaloneView(html, 200, 200);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(200, 200, 1f)) {
            view.frame(1000, cpu, true);
            assertEquals(0x000000, cpu.pixmap().getStraight(140, 120) & 0xFFFFFF, "開始時は未変形");
            view.frame(2950, cpu, true); // 開始から 1950ms ＝ ほぼ終点（約 44°）
            assertEquals(0x00FF00, cpu.pixmap().getStraight(140, 120) & 0xFFFFFF, "終点近くでは下辺が右へずれている");
        }
    }

    @Test
    void backgroundClipTextWorksOnAnInlineSpan() {
        String html = "<html><head><style>body{margin:0;background:#000;font-size:56px;font-weight:bold}"
                + ".g{color:transparent;background:linear-gradient(90deg,#ff0000,#ff0000);background-clip:text}"
                + "</style></head><body><span class='g'>HI</span></body></html>";
        StandaloneView view = new StandaloneView(html, 200, 100);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(200, 100, 1f)) {
            view.frame(1000, cpu, true);
            int n = 0;
            for (int y = 0; y < 100; y++) for (int x = 0; x < 200; x++) if ((cpu.pixmap().getStraight(x, y) & 0xFFFFFF) == 0xFF0000) n++;
            assertTrue(n > 150, "インラインでも文字の中は背景色: " + n);
        }
    }
}
