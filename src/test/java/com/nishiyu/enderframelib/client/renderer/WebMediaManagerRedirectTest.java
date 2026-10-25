package com.nishiyu.enderframelib.client.renderer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * メディア取得の SSRF 防御（REFACTORING_PLAN Phase 0 / 監査 2026-10-25）。
 *
 * <p>リダイレクト先は {@link WebMediaManager#isUrlSafe} を通ってから辿られる。ここでは
 * 「リダイレクト先として現れ得る URL 表記」がすべて遮断されることと、リダイレクトを辿るコード側が
 * 301 / 302 / 303 / 307 / 308 を同じ検査で扱い、検査なしに自動追従しないことを固定する。</p>
 */
public class WebMediaManagerRedirectTest {

    private static final Path SOURCE = Path.of("src/main/java/com/nishiyu/enderframelib/client/renderer/WebMediaManager.java");

    private boolean savedBlock;
    private boolean savedAllow;

    @BeforeEach
    void setUp() {
        savedBlock = WebMediaManager.blockPrivateNetworkAccess;
        savedAllow = WebMediaManager.allowExternalMedia;
        WebMediaManager.blockPrivateNetworkAccess = true;
        WebMediaManager.allowExternalMedia = true;
        WebMediaManager.ALLOWED_DOMAINS.clear();
    }

    @AfterEach
    void tearDown() {
        WebMediaManager.blockPrivateNetworkAccess = savedBlock;
        WebMediaManager.allowExternalMedia = savedAllow;
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

    /**
     * 画像取得の 2 本目の接続は自動追従が既定のままで、1 回目のリダイレクト先の次のリダイレクト
     * （安全な URL → 安全に見える URL → ループバック）を検査せずに辿る。また 307 / 308 を扱わず、
     * 相対 Location は常に遮断になる（正当なリダイレクトも失敗する）。
     * Phase 1 で検査つきの多段リダイレクト解決（{@code openCheckedConnection}）に置き換えた。
     */
    @Test
    @DisplayName("画像取得は自動追従せず、全リダイレクト種別を 1 つの検査経路で扱う")
    void redirectFollowingIsAlwaysInspected() throws IOException {
        String source = Files.readString(SOURCE);
        String imageDownload = source.substring(source.indexOf("private static void downloadImageAsync"),
                source.indexOf("// 2. Video Player System"));

        int opened = count(imageDownload, "openConnection\\(\\)");
        int manual = count(imageDownload, "setInstanceFollowRedirects\\(false\\)");
        assertEquals(opened, manual, "openConnection のたびに自動追従を切ること（開いた数 " + opened + " / 切った数 " + manual + "）");

        for (int status : new int[] {301, 302, 303, 307, 308}) {
            boolean handled = imageDownload.contains(String.valueOf(status))
                    || imageDownload.contains(status == 301 ? "HTTP_MOVED_PERM" : status == 302 ? "HTTP_MOVED_TEMP"
                    : status == 303 ? "HTTP_SEE_OTHER" : "isRedirect");
            assertTrue(handled, "リダイレクト " + status + " が扱われていること");
        }
        assertTrue(imageDownload.contains("resolve("), "相対 Location を基準 URL に対して解決していること");
    }

    private static int count(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        int n = 0;
        while (matcher.find()) n++;
        return n;
    }
}
