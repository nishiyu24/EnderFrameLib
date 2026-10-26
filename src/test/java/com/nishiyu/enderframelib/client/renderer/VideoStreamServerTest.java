package com.nishiyu.enderframelib.client.renderer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ループバック動画リレーが、全体取得と Range 取得をディスクへ落とさずに中継できることを固定する。
 */
public class VideoStreamServerTest {

    private static final byte[] VIDEO = "0123456789abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.US_ASCII);

    private HttpServer upstream;
    private String upstreamUrl;
    private boolean savedAllow;
    private boolean savedBlock;
    private VideoStreamServer.Handle handle;

    @BeforeEach
    void setUp() throws IOException {
        savedAllow = WebMediaManager.allowExternalMedia;
        savedBlock = WebMediaManager.blockPrivateNetworkAccess;
        WebMediaManager.allowExternalMedia = true;
        WebMediaManager.blockPrivateNetworkAccess = false;
        WebMediaManager.ALLOWED_DOMAINS.clear();
        WebMediaManager.CONFIG_ALLOWED_DOMAINS = Set.of();

        upstream = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        upstream.createContext("/video", this::serveVideo);
        upstream.start();
        upstreamUrl = "http://127.0.0.1:" + upstream.getAddress().getPort() + "/video";
    }

    @AfterEach
    void tearDown() {
        if (handle != null) handle.close();
        if (upstream != null) upstream.stop(0);
        WebMediaManager.allowExternalMedia = savedAllow;
        WebMediaManager.blockPrivateNetworkAccess = savedBlock;
        WebMediaManager.ALLOWED_DOMAINS.clear();
        WebMediaManager.CONFIG_ALLOWED_DOMAINS = Set.of();
    }

    @Test
    @DisplayName("リレー経由で動画全体を読み取れる")
    void relaysWholeVideo() throws IOException {
        handle = VideoStreamServer.register(upstreamUrl, 1024);
        HttpURLConnection connection = open(handle.loopbackUrl(), null);

        assertEquals(200, connection.getResponseCode());
        assertArrayEquals(VIDEO, connection.getInputStream().readAllBytes());
        connection.disconnect();
    }

    @Test
    @DisplayName("Range 要求を上流へ転送し、206 と Content-Range を返す")
    void relaysRangeRequests() throws IOException {
        handle = VideoStreamServer.register(upstreamUrl, 1024);
        HttpURLConnection connection = open(handle.loopbackUrl(), "bytes=10-19");

        assertEquals(206, connection.getResponseCode());
        assertEquals("bytes 10-19/" + VIDEO.length, connection.getHeaderField("Content-Range"));
        assertArrayEquals("abcdefghij".getBytes(StandardCharsets.US_ASCII),
                connection.getInputStream().readAllBytes());
        connection.disconnect();
    }

    @Test
    @DisplayName("Range が不正なら上流へ接続せず 416 を返す")
    void rejectsInvalidRange() throws IOException {
        handle = VideoStreamServer.register(upstreamUrl, 1024);
        HttpURLConnection connection = open(handle.loopbackUrl(), "bytes=abc");

        assertEquals(416, connection.getResponseCode());
        connection.disconnect();
    }

    @Test
    @DisplayName("Handle を閉じた後のトークンは 404 になる")
    void unregistersClosedHandle() throws IOException {
        handle = VideoStreamServer.register(upstreamUrl, 1024);
        String url = handle.loopbackUrl();
        handle.close();

        HttpURLConnection connection = open(url, null);
        assertEquals(404, connection.getResponseCode());
        connection.disconnect();
        handle = null;
    }

    @Test
    @DisplayName("設定したストリーム上限を超える動画は中継しない")
    void rejectsStreamsAboveLimit() throws IOException {
        handle = VideoStreamServer.register(upstreamUrl, 8);
        HttpURLConnection connection = open(handle.loopbackUrl(), null);

        assertTrue(connection.getResponseCode() >= 400);
        connection.disconnect();
    }

    private static HttpURLConnection open(String url, String range) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestProperty("Connection", "close");
        if (range != null) connection.setRequestProperty("Range", range);
        return connection;
    }

    private void serveVideo(HttpExchange exchange) throws IOException {
        String range = exchange.getRequestHeaders().getFirst("Range");
        if (range == null || range.isBlank()) {
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
            exchange.sendResponseHeaders(200, VIDEO.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(VIDEO);
            }
            return;
        }

        String spec = range.substring("bytes=".length()).trim();
        int dash = spec.indexOf('-');
        int start = Integer.parseInt(spec.substring(0, dash));
        int end = spec.substring(dash + 1).isEmpty()
                ? VIDEO.length - 1
                : Math.min(Integer.parseInt(spec.substring(dash + 1)), VIDEO.length - 1);
        int length = end - start + 1;

        exchange.getResponseHeaders().set("Content-Type", "video/mp4");
        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
        exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + VIDEO.length);
        exchange.sendResponseHeaders(206, length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(VIDEO, start, length);
        }
    }
}
