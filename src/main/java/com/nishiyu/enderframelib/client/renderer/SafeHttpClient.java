package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.core.net.HttpPolicy;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.Set;

/**
 * 外部メディア取得の入口。実体は core の {@link com.nishiyu.enderframelib.core.net.SafeHttpClient}
 * （SSRF 対策・リダイレクト検査・DNS 固定接続）で、ここは {@link WebMediaManager} の設定値を
 * {@link HttpPolicy} として注入するだけ。画像・SVG・動画リレー・yt-dlp 取得が同じ検査を通る。
 */
final class SafeHttpClient {

    private static final HttpPolicy POLICY = new HttpPolicy() {
        @Override public boolean allowExternal() { return WebMediaManager.allowExternalMedia; }
        @Override public boolean blockPrivate() { return WebMediaManager.blockPrivateNetworkAccess; }

        @Override
        public boolean isHostAllowed(String host) {
            Set<String> configRules = WebMediaManager.CONFIG_ALLOWED_DOMAINS;
            boolean any = !WebMediaManager.ALLOWED_DOMAINS.isEmpty() || !configRules.isEmpty();
            for (String rule : WebMediaManager.ALLOWED_DOMAINS) {
                if (com.nishiyu.enderframelib.core.net.SafeHttpClient.matchesDomainRule(rule, host)) return true;
            }
            for (String rule : configRules) {
                if (com.nishiyu.enderframelib.core.net.SafeHttpClient.matchesDomainRule(rule, host)) return true;
            }
            return !any;
        }
    };

    private static final com.nishiyu.enderframelib.core.net.SafeHttpClient CLIENT =
            new com.nishiyu.enderframelib.core.net.SafeHttpClient(POLICY);

    private SafeHttpClient() {}

    /** 動画リレー・yt-dlp 取得など、core のクラスへ渡す共有クライアント（設定値は呼び出しごとに読む）。 */
    static com.nishiyu.enderframelib.core.net.SafeHttpClient shared() {
        return CLIENT;
    }

    static boolean isUrlAllowed(String urlString) {
        return CLIENT.isUrlAllowed(urlString);
    }

    static byte[] fetchBytes(String urlString, long maxBytes) throws IOException {
        return CLIENT.fetchBytes(urlString, maxBytes);
    }

    static byte[] fetchBytesFromTrustedHosts(String urlString, long maxBytes, Set<String> trustedHosts)
            throws IOException {
        return CLIENT.fetchBytesFromTrustedHosts(urlString, maxBytes, trustedHosts);
    }

    static void downloadFromTrustedHosts(String urlString, long maxBytes, Set<String> trustedHosts, Path target)
            throws IOException {
        CLIENT.downloadFromTrustedHosts(urlString, maxBytes, trustedHosts, target);
    }

    static void streamRange(String urlString, long maxBytes, String rangeHeader, StreamConsumer consumer)
            throws IOException {
        CLIENT.streamRange(urlString, maxBytes, rangeHeader,
                (head, body) -> consumer.accept(new StreamHead(head.status(), head.contentLength(),
                        head.contentRange(), head.contentType(), head.acceptRanges()), body));
    }

    static boolean isPublicAddress(InetAddress address) {
        return com.nishiyu.enderframelib.core.net.SafeHttpClient.isPublicAddress(address);
    }

    record StreamHead(int status, long contentLength, String contentRange,
                      String contentType, String acceptRanges) {}

    @FunctionalInterface
    interface StreamConsumer {
        void accept(StreamHead head, InputStream body) throws IOException;
    }
}
