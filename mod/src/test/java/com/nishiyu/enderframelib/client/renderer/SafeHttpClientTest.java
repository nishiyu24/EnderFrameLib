package com.nishiyu.enderframelib.client.renderer;

import com.sun.net.httpserver.HttpExchange;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SafeHttpClient} のリダイレクト・サイズ上限・私設網遮断を、実際の HTTP サーバーに対して固定する。
 */
public class SafeHttpClientTest {

    private HttpServer server;
    private String baseUrl;
    private boolean savedAllow;
    private boolean savedBlock;

    @BeforeEach
    void setUp() throws IOException {
        savedAllow = WebMediaManager.allowExternalMedia;
        savedBlock = WebMediaManager.blockPrivateNetworkAccess;
        WebMediaManager.allowExternalMedia = true;
        WebMediaManager.blockPrivateNetworkAccess = false;
        WebMediaManager.ALLOWED_DOMAINS.clear();
        WebMediaManager.CONFIG_ALLOWED_DOMAINS = Set.of();

        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/redirect", exchange -> redirect(exchange, "/final"));
        server.createContext("/redirect-to-file", exchange -> redirect(exchange, "file:///etc/passwd"));
        server.createContext("/redirect-loop", exchange -> redirect(exchange, "/redirect-loop"));
        server.createContext("/redirect-to-localhost", exchange ->
                redirect(exchange, "http://localhost:" + server.getAddress().getPort() + "/final"));
        server.createContext("/final", exchange -> respond(exchange, 200, "hello".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/chunked", exchange -> {
            byte[] body = "chunked-body".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, 0); // 0 = Transfer-Encoding: chunked
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/too-large", exchange -> respond(exchange, 200, new byte[64]));
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

    @Test
    @DisplayName("相対 Location のリダイレクトを 1 ホップずつ検証して追跡する")
    void followsRedirectsAfterValidation() throws IOException {
        byte[] body = SafeHttpClient.fetchBytes(baseUrl + "/redirect", 1024);
        assertEquals("hello", new String(body, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("chunked レスポンスをデコードして上限内で読み切る")
    void decodesChunkedResponses() throws IOException {
        byte[] body = SafeHttpClient.fetchBytes(baseUrl + "/chunked", 1024);
        assertEquals("chunked-body", new String(body, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Content-Length が上限を超えるレスポンスは本文を読まずに拒否する")
    void rejectsOversizedResponses() {
        assertThrows(IOException.class, () -> SafeHttpClient.fetchBytes(baseUrl + "/too-large", 8));
    }

    @Test
    @DisplayName("リダイレクト先が file: など非 http(s) なら拒否する")
    void rejectsNonHttpRedirectTargets() {
        assertThrows(IOException.class, () -> SafeHttpClient.fetchBytes(baseUrl + "/redirect-to-file", 1024));
    }

    @Test
    @DisplayName("リダイレクトの上限ホップ数を超えたら停止する")
    void stopsRedirectLoops() {
        assertThrows(IOException.class, () -> SafeHttpClient.fetchBytes(baseUrl + "/redirect-loop", 1024));
    }

    @Test
    @DisplayName("私設網遮断が有効ならループバック URL は事前検査でも取得でも拒否する")
    void blocksLoopbackWhenPrivateNetworkAccessIsEnabled() {
        WebMediaManager.blockPrivateNetworkAccess = true;
        assertFalse(WebMediaManager.isUrlSafe(baseUrl + "/final"));
        assertThrows(IOException.class, () -> SafeHttpClient.fetchBytes(baseUrl + "/final", 1024));
    }

    @Test
    @DisplayName("許可ドメインを設定すると、それ以外のホストは取得前に拒否する")
    void enforcesDomainAllowlist() {
        WebMediaManager.CONFIG_ALLOWED_DOMAINS = Set.of("example.com");
        assertFalse(WebMediaManager.isUrlSafe(baseUrl + "/final"));
    }

    @Test
    @DisplayName("信頼ホスト取得はメディア許可ドメインを迂回するが、完全一致のホストだけを許可する")
    void trustedHostFetchUsesItsOwnExactHostList() throws IOException {
        WebMediaManager.CONFIG_ALLOWED_DOMAINS = Set.of("example.com");

        byte[] body = SafeHttpClient.fetchBytesFromTrustedHosts(
                baseUrl + "/final", 1024, Set.of("127.0.0.1"));
        assertEquals("hello", new String(body, StandardCharsets.UTF_8));

        assertThrows(IOException.class, () -> SafeHttpClient.fetchBytesFromTrustedHosts(
                baseUrl + "/final", 1024, Set.of("github.com")));
    }

    @Test
    @DisplayName("信頼ホスト取得でもリダイレクト先が許可ホスト外なら拒否する")
    void trustedHostRedirectsAreRevalidated() {
        assertThrows(IOException.class, () -> SafeHttpClient.fetchBytesFromTrustedHosts(
                baseUrl + "/redirect-to-localhost", 1024, Set.of("127.0.0.1")));
    }

    @Test
    @DisplayName("信頼ホスト取得でも私設網遮断は迂回しない")
    void trustedHostFetchStillBlocksPrivateAddresses() {
        WebMediaManager.blockPrivateNetworkAccess = true;
        assertThrows(IOException.class, () -> SafeHttpClient.fetchBytesFromTrustedHosts(
                baseUrl + "/final", 1024, Set.of("127.0.0.1")));
    }

    @Test
    @DisplayName("IPv4-mapped IPv6・6to4・NAT64 に埋め込まれた私設 IPv4 も公開アドレスと見なさない")
    void rejectsPrivateIpv4EmbeddedInIpv6() throws Exception {
        assertFalse(SafeHttpClient.isPublicAddress(InetAddress.getByName("::ffff:127.0.0.1")));
        assertFalse(SafeHttpClient.isPublicAddress(InetAddress.getByName("::ffff:169.254.169.254")));
        assertFalse(SafeHttpClient.isPublicAddress(InetAddress.getByName("2002:7f00:1::")));
        assertFalse(SafeHttpClient.isPublicAddress(InetAddress.getByName("64:ff9b::7f00:1")));
        assertFalse(SafeHttpClient.isPublicAddress(InetAddress.getByName("2001:1::1")));
        assertFalse(SafeHttpClient.isPublicAddress(InetAddress.getByName("2001:20::1")));
        assertTrue(SafeHttpClient.isPublicAddress(InetAddress.getByName("8.8.8.8")));
        assertTrue(SafeHttpClient.isPublicAddress(InetAddress.getByName("::ffff:8.8.8.8")));
        assertTrue(SafeHttpClient.isPublicAddress(InetAddress.getByName("2001:4860:4860::8888")));
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
