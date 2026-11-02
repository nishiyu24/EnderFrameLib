package com.nishiyu.enderframelib.core.text.font;

import com.nishiyu.enderframelib.core.net.HttpPolicy;
import com.nishiyu.enderframelib.core.net.SafeHttpClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class FontSourcesTest {

    private byte[] ttf;

    @BeforeEach
    void setUp() throws Exception {
        SystemFonts system = SystemFonts.system().withLocale(Locale.ENGLISH);
        String name = system.hasFamily("Arial") ? "Arial" : system.hasFamily("DejaVu Sans") ? "DejaVu Sans" : null;
        Assumptions.assumeTrue(name != null, "Arial / DejaVu Sans が無い環境");
        SystemFonts.Face face = system.family(name).stream().filter(f -> f.weight() == 400 && !f.italic()).findFirst().orElseThrow();
        Assumptions.assumeTrue(face.path() != null && face.faceIndex() == 0
                && face.path().toString().toLowerCase(Locale.ROOT).endsWith(".ttf"), "単体の TTF が無い環境");
        ttf = Files.readAllBytes(face.path());
        WebFonts.clear();
    }

    @AfterEach
    void tearDown() {
        FontFaceRules.setFontReader(null);
        WebFonts.clear();
    }

    @Test
    void relativeFileFromTheDocumentLocation(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("fonts"));
        Files.write(dir.resolve("fonts/a.ttf"), ttf);
        Path page = dir.resolve("page.html");
        Files.writeString(page, "<html></html>");
        FontSources src = new FontSources(null).setBase(page.toString());
        FontFaceRules.setFontReader(src);

        FontFaceRules.extractAndRegister("@font-face{font-family:'Local One';src:url(fonts/a.ttf)}", null);
        assertTrue(WebFonts.has("local one"));
        assertNull(src.read("fonts/missing.ttf"));
    }

    @Test
    void dataUriAndNonFileSchemes() throws Exception {
        FontSources src = new FontSources(null);
        FontFaceRules.setFontReader(src);
        String uri = "data:font/ttf;base64," + Base64.getEncoder().encodeToString(ttf);
        FontFaceRules.extractAndRegister("@font-face{font-family:Inline;src:url(" + uri + ".ttf)}", null);
        // data: は拡張子で TTF と見なさないので、ここでは読み手が直接読めることだけを確かめる
        assertArrayEquals(ttf, src.read(uri));
        assertNull(src.read("mod:fonts/x.ttf"), "ns:path は Minecraft 側の読み手の役目");
        assertNull(src.read("http://example.test/x.ttf"), "http クライアント無しでは引かない");
    }

    @Test
    void httpFontIsFetchedInTheBackgroundThenRegistered() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/f/web.ttf", ex -> {
            ex.sendResponseHeaders(200, ttf.length);
            ex.getResponseBody().write(ttf);
            ex.close();
        });
        server.start();
        FontSources src = new FontSources(new SafeHttpClient(HttpPolicy.permissive()));
        try {
            FontFaceRules.setFontReader(src);
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/f/web.ttf?v=1";
            FontFaceRules.extractAndRegister("@font-face{font-family:Remote;src:url(" + url + ")}", null);
            assertFalse(WebFonts.has("remote"), "取得中はまだ使えない（描画を止めない）");

            long end = System.currentTimeMillis() + 5000;
            while (!src.consumeLoaded() && System.currentTimeMillis() < end) Thread.sleep(20);
            assertEquals(0, src.pending());
            FontFaceRules.reloadOutlineFonts();
            assertTrue(WebFonts.has("remote"), "取れたら登録し直して使える");
            assertFalse(src.consumeLoaded(), "合図は 1 回だけ");
        } finally {
            src.close();
            server.stop(0);
        }
    }

    @Test
    void failedHttpFontIsNotRetriedForever() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ex -> {
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        server.start();
        FontSources src = new FontSources(new SafeHttpClient(HttpPolicy.permissive()));
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/none.ttf";
            assertNull(src.read(url));
            long end = System.currentTimeMillis() + 5000;
            while (!src.consumeLoaded() && System.currentTimeMillis() < end) Thread.sleep(20);
            assertNull(src.read(url));
            assertEquals(0, src.pending(), "失敗は覚えて、取り直さない");
        } finally {
            src.close();
            server.stop(0);
        }
    }

    @Test
    void outlineNamesIgnoreQueryAndFragment() {
        assertTrue(FontFaceRules.isOutlineName("https://x/y/a.TTF?v=2#f"));
        assertTrue(FontFaceRules.isOutlineName("a.otf"));
        assertFalse(FontFaceRules.isOutlineName("a.woff2"));
    }
}
