package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

/** Minecraft も OpenGL も無しで、ホバー・スクロール・トランジション・HTTP 画像が動くことを確かめる。 */
class StandaloneViewTest {

    private static final String HOVER = "<html><head><style>"
            + "#b{position:absolute;left:10px;top:10px;width:50px;height:50px;background:#ff0000}"
            + "#b:hover{background:#0000ff}</style></head><body style='margin:0'><div id='b'></div></body></html>";

    private static int rgb(SoftwareRenderBackend b, int x, int y) {
        return b.pixmap().getStraight(x, y) & 0xFFFFFF;
    }

    @Test
    void hoverChangesTheColor() {
        StandaloneView view = new StandaloneView(HOVER, 100, 100);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(100, 100, 1f)) {
            assertTrue(view.frame(1000, cpu, true));
            assertEquals(0xFF0000, rgb(cpu, 30, 30));

            view.mouseMove(30, 30, cpu.sessionId());
            assertTrue(view.needsFrame());
            assertTrue(view.frame(1016, cpu, false));
            assertEquals(0x0000FF, rgb(cpu, 30, 30));

            view.mouseMove(90, 90, cpu.sessionId());
            assertTrue(view.frame(1032, cpu, false));
            assertEquals(0xFF0000, rgb(cpu, 30, 30));
        }
    }

    @Test
    void idleViewDoesNotRedraw() {
        StandaloneView view = new StandaloneView(HOVER, 100, 100);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(100, 100, 1f)) {
            view.frame(1000, cpu, true);
            assertFalse(view.needsFrame());
            assertFalse(view.frame(1016, cpu, false));
        }
    }

    @Test
    void transitionRunsOverTimeAndSettles() {
        String html = "<html><head><style>"
                + "#b{position:absolute;left:0;top:0;width:50px;height:50px;background:#000000;transition:background-color 1000ms linear}"
                + "#b:hover{background:#ffffff}</style></head><body style='margin:0'><div id='b'></div></body></html>";
        StandaloneView view = new StandaloneView(html, 60, 60);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(60, 60, 1f)) {
            view.frame(0, cpu, true);
            view.mouseMove(10, 10, cpu.sessionId());
            view.frame(10, cpu, false);
            view.frame(500, cpu, false);
            int mid = rgb(cpu, 10, 10) & 0xFF;
            assertTrue(mid > 40 && mid < 215, "途中の色になる: " + mid);
            view.frame(2000, cpu, false);
            view.frame(2016, cpu, false);
            assertFalse(view.needsFrame(), "落ち着いたら描き直さない");
            assertEquals(0xFFFFFF, rgb(cpu, 10, 10));
        }
    }

    @Test
    void wheelScrollsTheContainer() {
        String html = "<html><body style='margin:0'><div id='s' style='position:absolute;left:0;top:0;width:100px;height:100px;overflow-y:scroll'>"
                + "<div style='height:50px;background:#ff0000'></div><div style='height:400px;background:#00ff00'></div></div></body></html>";
        StandaloneView view = new StandaloneView(html, 100, 100);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(100, 100, 1f)) {
            view.frame(0, cpu, true);
            assertEquals(0xFF0000, rgb(cpu, 20, 20));
            view.mouseMove(20, 20, cpu.sessionId());
            assertTrue(view.scroll(0, -2, cpu.sessionId()), "下へスクロール");
            assertTrue(view.needsFrame(), "減速しながら動く");
            settle(view, cpu, 16);
            assertFalse(view.needsFrame());
            assertEquals(0x00FF00, rgb(cpu, 20, 20));
        }
    }

/** スクロールの減速と transition が済むまで、フレームを進める。 */
private static void settle(StandaloneView v, SoftwareRenderBackend cpu, long from) {
    long t = from;
    for (int i = 0; i < 60 && v.needsFrame(); i++) v.frame(t += 50, cpu, false);
    v.frame(t + 2000, cpu, false);
}

private static void click(StandaloneView v, SoftwareRenderBackend cpu, double x, double y, long now) {
        v.mouseMove(x, y, cpu.sessionId());
        v.frame(now, cpu, false);
        v.mouseButton(true, cpu.sessionId());
        v.mouseButton(false, cpu.sessionId());
        settle(v, cpu, now + 16);
    }

    @Test
    void clickingSummaryTogglesDetails() {
        String html = "<html><body style='margin:0'><details id='d' style='position:absolute;left:0;top:0;width:100px'>"
                + "<summary style='height:30px;background:#ff0000'>Q</summary>"
                + "<div style='height:40px;background:#00ff00'></div></details></body></html>";
        StandaloneView view = new StandaloneView(html, 100, 100);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(100, 100, 1f)) {
            view.frame(0, cpu, true);
            assertEquals(0, rgb(cpu, 50, 50), "閉じていると中身は出ない");
            click(view, cpu, 50, 10, 100);
            assertEquals(0x00FF00, rgb(cpu, 50, 50), "開く");
            click(view, cpu, 50, 10, 10_000);
            assertEquals(0, rgb(cpu, 50, 50), "閉じる");
        }
    }

    @Test
    void anchorLinkScrollsToTarget() {
        String html = "<html><body style='margin:0'><div style='position:absolute;left:0;top:0;width:100px;height:100px;overflow-y:scroll'>"
                + "<a href='#t' style='display:block;height:20px;background:#0000ff'>go</a>"
                + "<div style='height:300px;background:#ff0000'></div>"
                + "<div id='t' style='height:200px;background:#00ff00'></div></div></body></html>";
        StandaloneView view = new StandaloneView(html, 100, 100);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(100, 100, 1f)) {
            view.frame(0, cpu, true);
            assertEquals(0x0000FF, rgb(cpu, 20, 5));
            click(view, cpu, 20, 5, 100);
            assertEquals(0x00FF00, rgb(cpu, 20, 5), "#t が先頭に来る");
        }
    }

    @Test
    void privateAddressesAreRefused() {
        var client = new com.nishiyu.enderframelib.core.net.SafeHttpClient(
                com.nishiyu.enderframelib.core.net.HttpPolicy.publicOnly());
        assertTrue(!com.nishiyu.enderframelib.core.net.SafeHttpClient.isPublicAddress(InetAddress.getLoopbackAddress()));
        assertThrows(java.io.IOException.class, () -> client.fetchBytes("http://127.0.0.1/x.png", 1024));
        assertThrows(java.io.IOException.class, () -> client.fetchBytes("http://192.168.1.1/x.png", 1024));
        assertThrows(java.io.IOException.class, () -> client.fetchBytes("file:///etc/passwd", 1024));

        ImageProvider.Web web = ImageProvider.web();
        try {
            assertNull(web.load("http://127.0.0.1:9/x.png", 10, 10));
            waitDone(web);
            assertNull(web.load("http://127.0.0.1:9/x.png", 10, 10), "失敗のまま");
        } finally {
            web.fetcher().close();
        }
    }

    @Test
    void imageOverHttpArrivesAsynchronously() throws Exception {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) img.setRGB(x, y, 0xFF00FF00);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);
        byte[] bytes = png.toByteArray();

        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/i.png", ex -> {
            ex.getResponseHeaders().add("Content-Type", "image/png");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        ImageProvider.Web web = new ImageProvider.Web(true);
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/i.png";
            assertNull(web.load(url, 4, 4), "初回は取得中");
            waitDone(web);
            ImageProvider.Image got = web.load(url, 4, 4);
            assertNotNull(got);
            assertEquals(4, got.pixmap().width());
            assertEquals(0x00FF00, got.pixmap().getStraight(1, 1) & 0xFFFFFF);
        } finally {
            web.fetcher().close();
            server.stop(0);
        }
    }

    @Test
    void standardProviderStillRefusesHttp() {
        assertNull(ImageProvider.standard().load("http://example.com/x.png", 10, 10));
    }

    private static void waitDone(ImageProvider.Web web) throws RuntimeException {
        long end = System.currentTimeMillis() + 5000;
        while (web.fetcher().pending() > 0 && System.currentTimeMillis() < end) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        assertEquals(0, web.fetcher().pending());
    }
}
