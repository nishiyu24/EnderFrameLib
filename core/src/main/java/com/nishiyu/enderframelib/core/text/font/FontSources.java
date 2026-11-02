package com.nishiyu.enderframelib.core.text.font;

import com.nishiyu.enderframelib.core.net.SafeHttpClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@code @font-face} の {@code src} を読む、Minecraft に依存しない {@link FontFaceRules.FontReader}。
 * ローカルのファイル・{@code file:}・{@code classpath:}・{@code data:}・{@code http(s)://} を引く。
 *
 * <p>{@code http(s)} は {@link SafeHttpClient}（SSRF 対策付き）で背景スレッドで取得し、取れるまでは null を返す
 * （描画をブロックしない）。取れたら {@link #consumeLoaded()} が true を返すので、呼び出し側は
 * {@link FontFaceRules#reloadOutlineFonts()} で登録し直して描き直す。取得結果は覚える。相対パスは
 * {@link #setBase} の文書の場所からの相対にする。
 */
public final class FontSources implements FontFaceRules.FontReader {

    /** 書体 1 つの上限。 */
    public static final int MAX_BYTES = 20 * 1024 * 1024;
    private static final int MAX_ENTRIES = 64;
    private static final byte[] PENDING = new byte[0];
    private static final byte[] FAILED = new byte[0];

    private final SafeHttpClient http;
    private final Map<String, byte[]> remote = new ConcurrentHashMap<>();
    private final AtomicBoolean loaded = new AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
    private volatile String base = "";
    private ExecutorService pool;

    /** @param http http(s) の取得に使うクライアント。null なら http は引かない */
    public FontSources(SafeHttpClient http) {
        this.http = http;
    }

    /**
     * 相対パスの基準になる文書の場所（ファイルのパス・{@code file:}・{@code http(s)} の URL）。空なら作業ディレクトリ。
     */
    public FontSources setBase(String documentLocation) {
        this.base = documentLocation == null ? "" : documentLocation;
        return this;
    }

    /** 背景の取得が 1 件でも終わっていたら true（呼ぶと戻る）。 */
    public boolean consumeLoaded() {
        return loaded.getAndSet(false);
    }

    /** 取得中の件数。 */
    public int pending() {
        return inFlight.get();
    }

    public synchronized void close() {
        if (pool != null) pool.shutdownNow();
        pool = null;
    }

    @Override
    public byte[] read(String source) throws IOException {
        if (source == null || source.isBlank()) return null;
        String src = source.trim();
        if (src.startsWith("data:")) return readData(src);
        if (src.startsWith("classpath:")) return readClasspath(src.substring(10));
        String resolved = resolve(src);
        if (resolved == null) return null;
        if (resolved.startsWith("http://") || resolved.startsWith("https://")) return readHttp(resolved);
        Path path = resolved.startsWith("file:") ? Path.of(URI.create(resolved)) : Path.of(resolved);
        if (!Files.isRegularFile(path) || Files.size(path) > MAX_BYTES) return null;
        return Files.readAllBytes(path);
    }

    /** {@code src} を絶対の場所にする。絶対でなければ基準からの相対。 */
    String resolve(String src) {
        if (src.startsWith("http://") || src.startsWith("https://") || src.startsWith("file:")) return src;
        // C:\fonts\a.ttf のようなドライブ付きの絶対パスは、スキームではない
        boolean drive = src.length() > 2 && Character.isLetter(src.charAt(0)) && src.charAt(1) == ':'
                && (src.charAt(2) == '\\' || src.charAt(2) == '/');
        if (!drive && src.indexOf(':') > 0) return null; // ns:path（Minecraft のリソース）などは別の読み手の役目
        if (drive || src.startsWith("/") || base.isEmpty()) {
            return src;
        }
        if (base.startsWith("http://") || base.startsWith("https://")) {
            return URI.create(base).resolve(src.replace(" ", "%20")).toString();
        }
        Path b = base.startsWith("file:") ? Path.of(URI.create(base)) : Path.of(base);
        Path dir = Files.isDirectory(b) ? b : b.toAbsolutePath().getParent();
        return dir == null ? src : dir.resolve(src).normalize().toString();
    }

    private static byte[] readData(String src) {
        int comma = src.indexOf(',');
        if (comma < 0) return null;
        String head = src.substring(5, comma);
        String body = src.substring(comma + 1);
        byte[] bytes = head.endsWith(";base64") ? Base64.getMimeDecoder().decode(body)
                : java.net.URLDecoder.decode(body.replace("+", "%2B"), StandardCharsets.ISO_8859_1).getBytes(StandardCharsets.ISO_8859_1);
        return bytes.length > MAX_BYTES ? null : bytes;
    }

    private static byte[] readClasspath(String path) throws IOException {
        try (InputStream in = FontSources.class.getResourceAsStream(path.startsWith("/") ? path : "/" + path)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private byte[] readHttp(String url) {
        if (http == null) return null;
        byte[] hit = remote.get(url);
        if (hit == FAILED || hit == PENDING) return null;
        if (hit != null) return hit;
        if (remote.size() >= MAX_ENTRIES) return null;
        if (remote.putIfAbsent(url, PENDING) == null) {
            inFlight.incrementAndGet();
            synchronized (this) {
                if (pool == null) {
                    pool = Executors.newFixedThreadPool(2, r -> {
                        Thread t = new Thread(r, "enderframe-font-fetch");
                        t.setDaemon(true);
                        return t;
                    });
                }
                pool.execute(() -> {
                    byte[] result = FAILED;
                    try {
                        result = http.fetchBytes(url, MAX_BYTES);
                        if (result == null || result.length == 0) result = FAILED;
                    } catch (IOException | RuntimeException e) {
                        // 取れない書体は使わない（FAILED のまま）
                    } finally {
                        remote.put(url, result);
                        loaded.set(true);
                        inFlight.decrementAndGet();
                    }
                });
            }
        }
        return null;
    }
}
