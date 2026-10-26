package com.nishiyu.enderframelib.client.renderer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * メディア取得の SSRF 防御（REFACTORING_PLAN Phase 0 / 監査 2026-10-25）。
 *
 * <p>リダイレクト先は {@link WebMediaManager#isUrlSafe} を通ってから辿られる。ここでは
 * 「リダイレクト先として現れ得る URL 表記」がすべて遮断されること、取得が検証済み IP へ
 * 直接接続する共通クライアントに一本化されていること、ネイティブ動画ローダーに生 URL を
 * 渡していないことを固定する。</p>
 */
public class WebMediaManagerRedirectTest {

    private static final Path SOURCE = Path.of("src/main/java/com/nishiyu/enderframelib/client/renderer/WebMediaManager.java");
    private static final Path SAFE_CLIENT_SOURCE = Path.of("src/main/java/com/nishiyu/enderframelib/client/renderer/SafeHttpClient.java");
    private static final Path YT_DLP_SOURCE = Path.of("src/main/java/com/nishiyu/enderframelib/client/renderer/YtDlpProvisioner.java");
    private static final Path CLIENT_INIT_SOURCE = Path.of("src/main/java/com/nishiyu/enderframelib/client/EnderFrameClientInit.java");

    private boolean savedBlock;
    private boolean savedAllow;
    private Set<String> savedConfigAllowed;

    @BeforeEach
    void setUp() {
        savedBlock = WebMediaManager.blockPrivateNetworkAccess;
        savedAllow = WebMediaManager.allowExternalMedia;
        savedConfigAllowed = WebMediaManager.CONFIG_ALLOWED_DOMAINS;
        WebMediaManager.blockPrivateNetworkAccess = true;
        WebMediaManager.allowExternalMedia = true;
        WebMediaManager.ALLOWED_DOMAINS.clear();
        WebMediaManager.CONFIG_ALLOWED_DOMAINS = Set.of();
    }

    @AfterEach
    void tearDown() {
        WebMediaManager.blockPrivateNetworkAccess = savedBlock;
        WebMediaManager.allowExternalMedia = savedAllow;
        WebMediaManager.CONFIG_ALLOWED_DOMAINS = savedConfigAllowed;
    }

    @Test
    @DisplayName("リダイレクト先がループバック・プライベート・リンクローカルの IP リテラルなら遮断される")
    void privateRedirectTargetsAreRejected() {
        List<String> targets = List.of(
                "http://127.0.0.1/admin",
                "http://127.0.0.1:25565/",
                "http://localhost/secret",
                "http://10.0.0.5/a.png",
                "http://172.16.0.1/a.png",
                "http://192.168.1.1/router.png",
                "http://169.254.169.254/latest/meta-data/",
                "http://0.0.0.0/",
                "http://[::1]/",
                "http://[::ffff:127.0.0.1]/",
                "http://[fe80::1]/",
                "http://internal.local/x",
                "http://service.internal/x");
        for (String target : targets) {
            assertFalse(WebMediaManager.isUrlSafe(target), "遮断されるべき: " + target);
        }
    }

    @Test
    @DisplayName("IPv4 の別表記（10 進整数・16 進・8 進・省略形）のループバックも遮断される")
    void obfuscatedLoopbackIsRejected() {
        for (String target : List.of(
                "http://2130706433/",      // 127.0.0.1 の 10 進
                "http://0x7f000001/",      // 16 進
                "http://017700000001/",    // 8 進
                "http://127.1/"            // 省略形 （Java は 0177.0.0.1 を 10 進の 177.0.0.1 と読むので、8 進の個別表記は対象外）
        )) {
            assertFalse(WebMediaManager.isUrlSafe(target), "遮断されるべき: " + target);
        }
    }

    @Test
    @DisplayName("http(s) 以外のスキームと、ホストの無い相対 Location は遮断される")
    void nonHttpAndRelativeLocationsAreRejected() {
        for (String target : List.of(
                "file:///etc/passwd", "ftp://example.com/a.png", "jar:file:/x.jar!/a", "gopher://example.com/",
                "/relative/path.png", "//no-scheme.example.com/a.png", "", "   ")) {
            assertFalse(WebMediaManager.isUrlSafe(target), "遮断されるべき: " + target);
        }
        assertFalse(WebMediaManager.isUrlSafe(null));
    }

    @Test
    @DisplayName("外部メディアを無効にすると、安全な見た目の URL でも遮断される")
    void externalMediaSwitchBlocksEverything() {
        WebMediaManager.allowExternalMedia = false;
        assertFalse(WebMediaManager.isUrlSafe("https://example.com/a.png"));
    }

    @Test
    @DisplayName("許可ドメインを設定すると、そのサブドメイン以外へのリダイレクトは遮断される")
    void allowedDomainsRestrictRedirectTargets() {
        WebMediaManager.ALLOWED_DOMAINS.add("example.com");
        try {
            assertFalse(WebMediaManager.isUrlSafe("https://evil.test/a.png"));
            assertFalse(WebMediaManager.isUrlSafe("https://notexample.com/a.png"));
            assertFalse(WebMediaManager.isUrlSafe("https://example.com.evil.test/a.png"));
        } finally {
            WebMediaManager.ALLOWED_DOMAINS.clear();
        }
    }

    /** IPv6 のユニークローカル（fc00::/7）は {@code isSiteLocalAddress} に含まれず、現状は素通りする。 */
    @Test
    @DisplayName("IPv6 ユニークローカル（fc00::/7）宛てのリダイレクトも遮断される")
    void uniqueLocalIpv6IsRejected() {
        for (String target : List.of("http://[fd00::1]/", "http://[fc00::1]/", "http://[fdff:ffff::1]/")) {
            assertFalse(WebMediaManager.isUrlSafe(target), "遮断されるべき: " + target);
        }
    }

    @Test
    @DisplayName("動画として受け付けるのは HTTP(S) だけで、file: や mod: は受け付けない")
    void videoSourcesAreRestrictedToHttp() throws IOException {
        String source = Files.readString(SOURCE);
        String helper = source.substring(source.indexOf("private static boolean isSupportedExternalVideoUrl"),
                source.indexOf("public static VideoTexture getVideoTexture"));

        assertTrue(helper.contains("scheme.equalsIgnoreCase(\"http\")"));
        assertTrue(helper.contains("scheme.equalsIgnoreCase(\"https\")"));
        assertTrue(helper.contains("uri.getHost() != null"));
        assertFalse(helper.contains("file:"));
        assertFalse(helper.contains("mod:"));
    }

    /**
     * 画像・SVG は「検証してから別の接続で取りに行く」旧経路をやめ、検証と接続を同じ
     * {@link SafeHttpClient} の呼び出しにまとめる。{@code openConnection()} を直接使うと
     * 検証時と接続時で DNS を引き直すため、DNS Rebinding の余地が残る。
     */
    @Test
    @DisplayName("画像・SVG の取得は検証済み IP へ接続する共通クライアントだけを使う")
    void mediaDownloadsUsePinnedSafeClient() throws IOException {
        String source = Files.readString(SOURCE);
        String imageDownload = source.substring(source.indexOf("private static void downloadImageAsync"),
                source.indexOf("// 2. Video Player System"));
        String svgDownload = source.substring(source.indexOf("private static void downloadSvgAsync"),
                source.indexOf("private static String convertToDirectLink"));

        assertTrue(imageDownload.contains("SafeHttpClient.fetchBytes"), "画像は SafeHttpClient 経由で取得すること");
        assertTrue(svgDownload.contains("SafeHttpClient.fetchBytes"), "SVG は SafeHttpClient 経由で取得すること");
        assertFalse(imageDownload.contains("openConnection"), "画像取得に openConnection を使わないこと");
        assertFalse(svgDownload.contains("openConnection"), "SVG 取得に openConnection を使わないこと");
    }

    @Test
    @DisplayName("リダイレクトは自動追従せず、各ホップを同じ検査経路で扱う")
    void redirectFollowingIsAlwaysInspected() throws IOException {
        String source = Files.readString(SAFE_CLIENT_SOURCE);

        assertFalse(source.contains(".openConnection()"), "HttpURLConnection の自動追従を使わないこと");
        assertTrue(source.contains("current.resolve("), "相対 Location を基準 URL に対して解決していること");
        assertTrue(source.contains("parseAndValidate(next.toString())"), "リダイレクト先を毎ホップ検証していること");
        for (int status : new int[] {301, 302, 303, 307, 308}) {
            assertTrue(source.contains(String.valueOf(status)), "リダイレクト " + status + " が扱われていること");
        }
    }

    @Test
    @DisplayName("ネイティブ動画ローダーには生 URL ではなくループバック・リレーの URL を渡す")
    void nativeVideoLoaderNeverReceivesRawUrl() throws IOException {
        String source = Files.readString(SOURCE);
        String init = source.substring(source.indexOf("private void initVideoWorker"),
                source.indexOf("public void keepAlive"));

        assertTrue(init.contains("VideoStreamServer.register"), "検証済みストリームをリレーへ登録すること");
        assertTrue(init.contains("initVideo(new WString(relayHandle.loopbackUrl()))"),
                "ネイティブにはループバック・リレーの URL だけを渡すこと");
        assertFalse(init.contains("fetchVideoToTempFile"), "動画を一時ファイルへ全体ダウンロードしないこと");
        assertFalse(init.contains("initVideo(new WString(targetUrl"), "生の URL をネイティブへ渡さないこと");
        assertFalse(init.contains("initVideo(new WString(streamUrl"), "解決後 URL をネイティブへ渡さないこと");
    }

    @Test
    @DisplayName("DNS 解決結果を保持し、検証済み InetAddress へ直接接続する")
    void dnsResolutionIsPinnedToValidatedAddress() throws IOException {
        String source = Files.readString(SAFE_CLIENT_SOURCE);
        assertTrue(source.contains("InetAddress.getAllByName"), "ホスト名を明示的に解決すること");
        assertTrue(source.contains("new InetSocketAddress(address, port)"),
                "解決済みの InetAddress へ直接接続すること（再解決しない）");
    }

    @Test
    @DisplayName("yt-dlp の自動配置は両方の同意設定が揃ったときだけ始まり、取得元は固定ホストに限る")
    void ytDlpProvisioningIsGatedAndPinned() throws IOException {
        String init = Files.readString(CLIENT_INIT_SOURCE);
        assertTrue(init.contains("WebMediaManager.allowExternalMedia && WebMediaManager.allowYtDlp"),
                "外部メディアと yt-dlp の両方が有効なときだけ自動配置すること");
        assertTrue(init.contains("WebMediaManager.ensureYtDlpAvailableAsync()"),
                "設定反映時に自動配置を開始すること");

        String provisioner = Files.readString(YT_DLP_SOURCE);
        assertTrue(provisioner.contains("SafeHttpClient.fetchBytesFromTrustedHosts"),
                "チェックサム取得は固定ホスト用の安全なクライアントを使うこと");
        assertTrue(provisioner.contains("SafeHttpClient.downloadFromTrustedHosts"),
                "本体取得は固定ホスト用の安全なクライアントを使うこと");
        assertTrue(provisioner.contains("SHA2-256SUMS"), "公式チェックサムと照合すること");
        assertFalse(provisioner.contains("openConnection"), "自動配置に自動追従の HTTP クライアントを使わないこと");
    }
}
