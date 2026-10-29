package com.nishiyu.enderframelib.client.renderer;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link SafeHttpClient#streamRange} が解決済みの最終 URL を覚え、Range のたびに
 * リダイレクトを辿り直さないこと、キャッシュが失効しても取り直せることを固定する。
 */
public class SafeHttpClientStreamCacheTest {

    private HttpServer server;
    private String baseUrl;
    private boolean savedAllow;
    private boolean savedBlock;
    private final AtomicInteger startHits = new AtomicInteger();
    private final AtomicInteger mediaHits = new AtomicInteger();
    private final AtomicInteger flakyHits = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        savedAllow = WebMediaManager.allowExternalMedia;
        savedBlock = WebMediaManager.blockPrivateNetworkAccess;
        WebMediaManager.allowExternalMedia = true;
        WebMediaManager.blockPrivateNetworkAccess = false;
        WebMediaManager.ALLOWED_DOMAINS.clear();
        WebMediaManager.CONFIG_ALLOWED_DOMAINS = Set.of();

        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/start", exchange -> {
            startHits.incrementAndGet();
            exchange.getResponseHeaders().set("Location", "/media");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/media", exchange -> {
            mediaHits.incrementAndGet();
            send(exchange, 200, "video-bytes");
        });
        server.createContext("/start-flaky", exchange -> {
            startHits.incrementAndGet();
            exchange.getResponseHeaders().set("Location", "/flaky");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        // 1 回目は成功、2 回目（キャッシュ済み URL の期限切れを模す）は 403、以降は成功。
        server.createContext("/flaky", exchange -> {
            int n = flakyHits.incrementAndGet();
            if (n == 2) send(exchange, 403, "expired");
            else send(exchange, 200, "video-bytes");
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        WebMediaManager.allowExternalMedia = savedAllow;
        WebMediaManager.blockPrivateNetworkAccess = savedBlock;
        WebMediaManager.ALLOWED_DOMAINS.clear();
        WebMediaManager.CONFIG_ALLOWED_DOMAINS = Set.of();
    }

    private static void send(com.sun.net.httpserver.HttpExchange exchange, int status, String text)
            throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static String read(String url) throws IOException {
        StringBuilder out = new StringBuilder();
        SafeHttpClient.streamRange(url, 1024, null, (head, body) ->
                out.append(new String(body.readAllBytes(), StandardCharsets.UTF_8)));
        return out.toString();
    }

    @Test
    @DisplayName("2 回目以降の取得はリダイレクト元を再訪せず、解決済みの最終 URL へ直接行く")
    void secondRequestSkipsRedirectChain() throws IOException {
        assertEquals("video-bytes", read(baseUrl + "/start"));
        assertEquals(1, startHits.get());

        assertEquals("video-bytes", read(baseUrl + "/start"));
        assertEquals("video-bytes", read(baseUrl + "/start"));

        assertEquals(1, startHits.get(), "リダイレクト元は最初の 1 回しか叩かれない");
        assertEquals(3, mediaHits.get());
    }

    @Test
    @DisplayName("キャッシュ済みの最終 URL が失敗したら、元の URL から取り直して成功する")
    void staleCachedUrlFallsBackToOriginal() throws IOException {
        assertEquals("video-bytes", read(baseUrl + "/start-flaky"));
        assertEquals(1, startHits.get());

        // 2 回目: キャッシュ URL が 403 → 元 URL から取り直す
        assertEquals("video-bytes", read(baseUrl + "/start-flaky"));
        assertEquals(2, startHits.get());
        assertEquals(3, flakyHits.get());
    }

    @Test
    @DisplayName("応答を返し始めた後の失敗はやり直さず、そのまま伝える")
    void failureAfterStreamStartedIsNotRetried() throws IOException {
        assertEquals("video-bytes", read(baseUrl + "/start"));
        int before = mediaHits.get();

        assertThrows(IOException.class, () -> SafeHttpClient.streamRange(
                baseUrl + "/start", 1024, null, (head, body) -> {
                    throw new IOException("consumer failed");
                }));
        assertEquals(before + 1, mediaHits.get());
    }

    @Test
    @DisplayName("キャッシュした最終 URL にも私設網遮断を毎回適用する")
    void cachedUrlIsStillCheckedForPrivateAddresses() throws IOException {
        assertEquals("video-bytes", read(baseUrl + "/start"));

        WebMediaManager.blockPrivateNetworkAccess = true;
        assertThrows(IOException.class, () -> read(baseUrl + "/start"));
    }
}
