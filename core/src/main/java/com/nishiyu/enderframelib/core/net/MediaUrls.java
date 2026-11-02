package com.nishiyu.enderframelib.core.net;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** 画像・動画の URL の判定と正規化（Minecraft に依存しないブラウザ共通の規則）。 */
public final class MediaUrls {

    private MediaUrls() {}

    /**
     * 動画として受け付けるのは HTTP(S) だけ。{@code file:} や {@code mod:} を
     * そのままネイティブへ渡すと、HTML 側からローカルファイルを読む経路になる。
     */
    public static boolean isSupportedExternalVideoUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            return scheme != null
                    && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    && uri.getHost() != null;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isYouTubeUrl(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            return host.equals("youtube.com") || host.endsWith(".youtube.com") || host.equals("youtu.be");
        } catch (Exception e) {
            return false;
        }
    }

    /** Google Drive の共有リンクを直接ダウンロードの URL にする。それ以外はそのまま。 */
    public static String convertToDirectLink(String urlString) {
        if (urlString.contains("drive.google.com/file/d/")) {
            try {
                String[] parts = urlString.split("/file/d/");
                if (parts.length > 1) {
                    String fileId = parts[1].split("/")[0];
                    return "https://drive.google.com/uc?export=download&id=" + fileId;
                }
            } catch (Exception ignored) {
                // 解けなければ元の URL のまま
            }
        }
        return urlString;
    }

    /** URL の SHA-256 の先頭 16 バイトを 16 進にしたもの（テクスチャ名などの安定した識別子）。 */
    public static String urlHash(String url) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toUnsignedString(url.hashCode(), 16);
        }
    }
}
