package com.nishiyu.enderframelib.client.renderer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * yt-dlp の自動配置が、同意ゲート・SHA-256 検証・失敗時の後始末を守ることを固定する。
 * 実ネットワークへは出ず、ローカル HTTP サーバーを公式リリースに見立てる。
 */
public class YtDlpProvisionerTest {

    private static final byte[] BINARY = "fake yt-dlp binary".getBytes(StandardCharsets.UTF_8);
    private static final String BINARY_HASH = sha256Hex(BINARY);
    private static final String BAD_HASH = "0".repeat(64);

    @TempDir
    Path tempDir;

    private HttpServer server;
    private String baseUrl;
    private boolean savedAllow;
    private boolean savedYtDlp;
    private boolean savedBlock;

    @BeforeEach
    void setUp() throws Exception {
        savedAllow = WebMediaManager.allowExternalMedia;
        savedYtDlp = WebMediaManager.allowYtDlp;
        savedBlock = WebMediaManager.blockPrivateNetworkAccess;
        WebMediaManager.blockPrivateNetworkAccess = false;

        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/yt-dlp.exe", exchange -> respond(exchange, 200, BINARY));
        server.createContext("/SHA2-256SUMS", exchange -> respond(exchange, 200,
                (BINARY_HASH + "  yt-dlp.exe\n").getBytes(StandardCharsets.UTF_8)));
        server.createContext("/mismatched-sums", exchange -> respond(exchange, 200,
                (BAD_HASH + "  yt-dlp.exe\n").getBytes(StandardCharsets.UTF_8)));
        server.createContext("/missing-sums", exchange -> respond(exchange, 200,
                ("not-a-hash  another-file.exe\n").getBytes(StandardCharsets.UTF_8)));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        WebMediaManager.allowExternalMedia = savedAllow;
        WebMediaManager.allowYtDlp = savedYtDlp;
        WebMediaManager.blockPrivateNetworkAccess = savedBlock;
    }

    @Test
    @DisplayName("SHA2-256SUMS は資産名の完全一致で読み、コメントや別名を拾わない")
    void parsesChecksumsByExactAssetName() {
        String sums = "\uFEFF# generated\n"
                + "a".repeat(64) + "  yt-dlp.exe\n"
                + "b".repeat(64) + " *yt-dlp_linux\n"
                + "not-a-hash  yt-dlp\n";

        assertEquals("a".repeat(64), YtDlpProvisioner.parseChecksum(sums, "yt-dlp.exe"));
        assertEquals("b".repeat(64), YtDlpProvisioner.parseChecksum(sums, "yt-dlp_linux"));
        assertNull(YtDlpProvisioner.parseChecksum(sums, "yt-dlp"));
        assertNull(YtDlpProvisioner.parseChecksum(null, "yt-dlp.exe"));
    }

    @Test
    @DisplayName("検証済みのバイナリだけを最終パスへ配置する")
    void installsVerifiedBinary() throws IOException {
        Path target = tempDir.resolve("enderframelib").resolve("yt-dlp.exe");

        YtDlpProvisioner.install(target, "yt-dlp.exe", baseUrl + "/yt-dlp.exe",
                baseUrl + "/SHA2-256SUMS", Set.of("127.0.0.1"));

        assertTrue(Files.isRegularFile(target));
        assertArrayEquals(BINARY, Files.readAllBytes(target));
    }

    @Test
    @DisplayName("チェックサム不一致なら実行ファイルを残さず、一時ファイルも消す")
    void rejectsChecksumMismatch() throws IOException {
        Path target = tempDir.resolve("enderframelib").resolve("yt-dlp.exe");

        assertThrows(IOException.class, () -> YtDlpProvisioner.install(
                target, "yt-dlp.exe", baseUrl + "/yt-dlp.exe",
                baseUrl + "/mismatched-sums", Set.of("127.0.0.1")));

        assertFalse(Files.exists(target));
        try (Stream<Path> entries = Files.list(target.getParent())) {
            assertTrue(entries.noneMatch(path -> path.getFileName().toString().endsWith(".part")),
                    "検証に失敗した .part を残さないこと");
        }
    }

    @Test
    @DisplayName("チェックサム一覧に資産が無ければ配置しない")
    void rejectsMissingChecksumEntry() {
        Path target = tempDir.resolve("enderframelib").resolve("yt-dlp.exe");

        assertThrows(IOException.class, () -> YtDlpProvisioner.install(
                target, "yt-dlp.exe", baseUrl + "/yt-dlp.exe",
                baseUrl + "/missing-sums", Set.of("127.0.0.1")));

        assertFalse(Files.exists(target));
    }

    @Test
    @DisplayName("同意ゲートが片方でも無効なら配置せず、有効なら自動配置する")
    void ensureAvailableHonorsConsentGate() throws IOException {
        AtomicBoolean installerCalled = new AtomicBoolean(false);
        WebMediaManager.allowExternalMedia = false;
        WebMediaManager.allowYtDlp = true;

        assertNull(YtDlpProvisioner.ensureAvailable(tempDir.toFile(), (target, asset) -> {
            installerCalled.set(true);
            Files.write(target, BINARY);
        }));
        assertFalse(installerCalled.get(), "外部メディアが無効ならダウンロードしないこと");

        WebMediaManager.allowExternalMedia = true;
        File installed = YtDlpProvisioner.ensureAvailable(tempDir.toFile(), (target, asset) -> {
            installerCalled.set(true);
            Files.write(target, BINARY);
        });

        assertTrue(installerCalled.get());
        assertNotNull(installed);
        assertEquals(YtDlpProvisioner.assetForCurrentPlatform().fileName(), installed.getName());
        assertArrayEquals(BINARY, Files.readAllBytes(installed.toPath()));
    }

    @Test
    @DisplayName("公式ダウンロード先は GitHub の配布ホストだけに固定されている")
    void officialDownloadHostsAreFixed() {
        assertTrue(YtDlpProvisioner.CHECKSUMS_URL.startsWith(
                "https://github.com/yt-dlp/yt-dlp/releases/latest/download/"));
        assertTrue(YtDlpProvisioner.DOWNLOAD_HOSTS.contains("github.com"));
        assertTrue(YtDlpProvisioner.DOWNLOAD_HOSTS.contains("release-assets.githubusercontent.com"));
        assertFalse(YtDlpProvisioner.DOWNLOAD_HOSTS.contains("localhost"));
        assertFalse(YtDlpProvisioner.DOWNLOAD_HOSTS.contains("127.0.0.1"));
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder result = new StringBuilder(64);
            for (byte b : digest.digest(bytes)) {
                result.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
            }
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
        exchange.close();
    }
}
