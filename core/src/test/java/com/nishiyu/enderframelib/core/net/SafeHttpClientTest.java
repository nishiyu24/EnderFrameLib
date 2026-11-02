package com.nishiyu.enderframelib.core.net;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** core 単体の {@link SafeHttpClient}：注入した {@link HttpPolicy} が画像・メディア共通の検査になる。 */
class SafeHttpClientTest {

    @Test
    void publicOnlyRefusesLoopback() throws Exception {
        HttpServer server = serve("hi");
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/a";
            SafeHttpClient client = new SafeHttpClient(HttpPolicy.publicOnly());
            assertFalse(client.isUrlAllowed(url));
            assertThrows(IOException.class, () -> client.fetchBytes(url, 1024));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void permissiveFetchesAndLimitsBodySize() throws Exception {
        HttpServer server = serve("hello");
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/a";
            SafeHttpClient client = new SafeHttpClient(HttpPolicy.permissive());
            assertArrayEquals("hello".getBytes(), client.fetchBytes(url, 1024));
            assertThrows(IOException.class, () -> client.fetchBytes(url, 2));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void policyIsReadOnEveryCall() throws Exception {
        HttpServer server = serve("x");
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/a";
            AtomicBoolean external = new AtomicBoolean(true);
            SafeHttpClient client = new SafeHttpClient(new HttpPolicy() {
                @Override public boolean allowExternal() { return external.get(); }
                @Override public boolean blockPrivate() { return false; }
                @Override public boolean isHostAllowed(String host) { return true; }
            });
            assertEquals(1, client.fetchBytes(url, 16).length);
            external.set(false);
            assertThrows(IOException.class, () -> client.fetchBytes(url, 16));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void domainRulesAndAddressClassification() throws Exception {
        assertTrue(SafeHttpClient.matchesDomainRule("example.com", "example.com"));
        assertFalse(SafeHttpClient.matchesDomainRule("example.com", "evil.org"));
        assertFalse(SafeHttpClient.isPublicAddress(InetAddress.getByName("127.0.0.1")));
        assertFalse(SafeHttpClient.isPublicAddress(InetAddress.getByName("192.168.1.1")));
        assertTrue(SafeHttpClient.isPublicAddress(InetAddress.getByName("8.8.8.8")));
    }

    private static HttpServer serve(String body) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        s.createContext("/", ex -> {
            byte[] b = body.getBytes();
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        s.start();
        return s;
    }
}
