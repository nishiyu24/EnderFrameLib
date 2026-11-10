package com.nishiyu.enderframelib.api;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 統合エントリーポイント EnderEngine / WebViewSurface（ヘッドレス・カスタムホスト・入力・文書）。 */
class EnderEngineTest {

    private static final String RED_BOX =
            "<html><body style=\"margin:0\"><div id=\"box\" class=\"c\" style=\"width:40px;height:30px;background:#ff0000\"></div></body></html>";

    private static int alpha(int argb) { return argb >>> 24; }
    private static int red(int argb) { return (argb >> 16) & 0xFF; }
    private static int green(int argb) { return (argb >> 8) & 0xFF; }

    @Test
    void headlessSurfaceRendersAFewLinesOfCode() {
        try (WebViewSurface s = EnderEngine.html(RED_BOX).size(100, 60).clearColor(0xFFFFFFFF).build()) {
            assertEquals(100, s.width());
            assertEquals(60, s.height());
            assertTrue(s.frame(0, true), "最初のフレームは描く");
            assertEquals(100, s.pixelWidth());
            assertEquals(60, s.pixelHeight());
            int[] px = s.pixels();
            int inside = px[10 * s.pixelWidth() + 10];
            int outside = px[50 * s.pixelWidth() + 90];
            assertEquals(255, red(inside));
            assertEquals(0, green(inside));
            assertEquals(255, green(outside), "箱の外は背景の白");
        }
    }

    @Test
    void idleSurfaceDoesNotRedrawUnlessForced() {
        try (WebViewSurface s = EnderEngine.html(RED_BOX).size(50, 50).build()) {
            assertTrue(s.frame(0, false));
            assertFalse(s.frame(16, false), "変化が無ければ描かない");
            assertTrue(s.frame(32, true), "force なら描く");
        }
    }

    @Test
    void devicePixelRatioScalesThePixelBuffer() {
        try (WebViewSurface s = EnderEngine.html(RED_BOX).size(50, 40).devicePixelRatio(2f).build()) {
            s.frame(0, true);
            assertEquals(50, s.width());
            assertEquals(100, s.pixelWidth());
            assertEquals(80, s.pixelHeight());
            assertEquals(100 * 80, s.pixels().length);
        }
    }

    @Test
    void documentViewExposesNoNodeTypes() {
        try (WebViewSurface s = EnderEngine.html(RED_BOX).size(50, 50).build()) {
            WebDocument d = s.document();
            assertTrue(d.hasElement("box"));
            assertFalse(d.hasElement("nope"));
            assertEquals("div", d.tagOf("box"));
            assertEquals("c", d.attributeOf("box", "class"));
            assertNull(d.tagOf("nope"));
        }
    }

    @Test
    void writesPng(@TempDir Path dir) throws Exception {
        try (WebViewSurface s = EnderEngine.html(RED_BOX).size(40, 30).build()) {
            s.frame(0, true);
            Path out = dir.resolve("sub/out.png");
            s.writePng(out);
            byte[] b = Files.readAllBytes(out);
            assertTrue(b.length > 8);
            assertEquals((byte) 0x89, b[0]);
            assertEquals((byte) 'P', b[1]);
        }
    }

    @Test
    void clickFocusesAnInputAndRoutesCharsThroughTheSurface() {
        String html = "<html><body style=\"margin:0\"><input id=\"i\" style=\"position:absolute;left:10px;top:10px;width:120px;height:30px\"></body></html>";
        try (WebViewSurface s = EnderEngine.html(html).size(200, 100).build()) {
            s.frame(0, true);
            assertTrue(s.isPointerOverUi(20, 20));
            assertFalse(s.isPointerOverUi(190, 90));
            assertNull(s.focusedId());
            s.mouseMove(20, 20);
            s.frame(16, false);
            s.mouseButton(true);
            s.mouseButton(false);
            s.frame(32, false);
            assertEquals("i", s.focusedId());
            assertTrue(s.charTyped('a'));
        }
    }

    @Test
    void linkClickReachesTheNavigationHandler() {
        String html = "<html><body style=\"margin:0\"><a id=\"l\" href=\"next.html\" style=\"display:block;width:100px;height:30px\">go</a></body></html>";
        List<String> navigated = new ArrayList<>();
        try (WebViewSurface s = EnderEngine.html(html).size(200, 100).build()) {
            s.onNavigate(navigated::add);
            s.frame(0, true);
            s.mouseMove(20, 10);
            s.frame(16, false);
            s.mouseButton(true);
            s.mouseButton(false);
            s.frame(32, false);
            assertEquals(List.of("next.html"), navigated);
        }
    }

    @Test
    void resizeChangesTheDeliveredPixelSize() {
        List<int[]> sizes = new ArrayList<>();
        try (WebViewSurface s = EnderEngine.html(RED_BOX).size(100, 60)
                .sink((px, w, h) -> sizes.add(new int[] {w, h, px.length})).build()) {
            s.frame(0, true);
            s.resize(200, 120);
            assertEquals(200, s.width());
            assertTrue(s.frame(16, true));
            assertEquals(200, s.pixelWidth());
            assertEquals(120, s.pixelHeight());
            assertArrayEquals(new int[] {100, 60, 6000}, sizes.get(0));
            assertArrayEquals(new int[] {200, 120, 24000}, sizes.get(1));
            s.resize(200, 120);
            assertEquals(2, sizes.size(), "同じ大きさなら何もしない");
            assertThrows(IllegalArgumentException.class, () -> s.resize(0, 10));
        }
    }

    @Test
    void customHostSinkReceivesOnlyDrawnFrames() {
        AtomicInteger deliveries = new AtomicInteger();
        int[] firstPixel = new int[1];
        WebViewSurface s = EnderEngine.html(RED_BOX).size(50, 50).clearColor(0xFF00FF00)
                .sink((px, w, h) -> {
                    deliveries.incrementAndGet();
                    firstPixel[0] = px[0];
                }).build();
        assertTrue(s.frame(0, true));
        assertEquals(1, deliveries.get());
        assertEquals(255, red(firstPixel[0]), "左上は赤い箱");
        assertFalse(s.frame(16, false));
        assertEquals(1, deliveries.get(), "描かなかったフレームは渡さない");
        s.close();
    }

    @Test
    void platformIsDiscoveredThroughServiceLoader() {
        boolean found = false;
        for (EnginePlatform p : java.util.ServiceLoader.load(EnginePlatform.class)) {
            found = true;
        }
        assertTrue(found, "META-INF/services/com.nishiyu.enderframelib.api.EnginePlatform が必要");
    }

    @Test
    void builderRejectsBadArguments() {
        assertThrows(IllegalArgumentException.class, () -> EnderEngine.html("x").size(0, 5));
        assertThrows(IllegalArgumentException.class, () -> EnderEngine.html("x").devicePixelRatio(0f));
        assertThrows(NullPointerException.class, () -> EnderEngine.html(null));
        assertThrows(NullPointerException.class, () -> EnderEngine.html("x").target(null));
    }

    @Test
    void publicSurfaceApiLeaksNoEngineInternals() {
        // api の公開シグネチャに core.* / common.* の型が現れない
        for (Class<?> c : List.of(WebViewSurface.class, WebDocument.class, PixelSink.class, EnginePlatform.class, EnderEngine.class, EnderEngine.Builder.class)) {
            for (Method m : c.getMethods()) {
                List<Class<?>> types = new ArrayList<>(List.of(m.getParameterTypes()));
                types.add(m.getReturnType());
                for (Class<?> t : types) {
                    assertFalse(t.getName().startsWith("com.nishiyu.enderframelib.core"), c.getSimpleName() + "#" + m.getName() + " が " + t.getName() + " を公開している");
                    assertFalse(t.getName().startsWith("com.nishiyu.enderframelib.common"), c.getSimpleName() + "#" + m.getName() + " が " + t.getName() + " を公開している");
                }
            }
        }
    }
}
