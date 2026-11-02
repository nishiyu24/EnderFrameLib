package com.nishiyu.enderframelib.core.net;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * YouTube の URL を yt-dlp で再生用の直リンクにする（結果は成功 1 時間・失敗 1 分キャッシュ）。
 * yt-dlp の置き場・HTTP クライアント・許可の判定は呼び出し側が渡すので、Minecraft にも設定の static にも依存しない。
 */
public final class YtDlpResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(YtDlpResolver.class);
    private static final long SUCCESS_TTL_MS = 3_600_000L;
    private static final long FAILURE_TTL_MS = 60_000L;
    private static final AtomicInteger THREADS = new AtomicInteger();
    private static final ExecutorService OUTPUT_EXECUTOR = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "EnderFrameLib-YtDlpReader-" + THREADS.getAndIncrement());
        t.setDaemon(true);
        return t;
    });

    private record Cached(String resolvedUrl, long timestamp) {}

    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

    private YtDlpResolver() {}

    /** 古いキャッシュを捨てる。 */
    public static void expire() {
        long now = System.currentTimeMillis();
        CACHE.entrySet().removeIf(e -> (now - e.getValue().timestamp) > SUCCESS_TTL_MS);
    }

    public static void clear() {
        CACHE.clear();
    }

    /**
     * @param isUrlSafe  外部プロセスへ渡す前の URL 検査（SSRF・許可ドメイン）
     * @param ytDlpDir   yt-dlp の置き場
     * @param client     yt-dlp の取得に使う HTTP クライアント
     * @param enabled    呼ぶたびに読む許可（外部メディア && yt-dlp）
     * @return 再生用の http(s) URL。使えなければ null
     */
    public static String resolve(String youtubeUrl, Function<String, Boolean> isUrlSafe, File ytDlpDir,
                                 SafeHttpClient client, BooleanSupplier enabled) {
        if (!enabled.getAsBoolean()) return null;
        // 外部プロセスに渡す URL は YouTube のホストに限定する（任意 URL でのプロセス起動を防ぐ）
        if (!MediaUrls.isYouTubeUrl(youtubeUrl) || !isUrlSafe.apply(youtubeUrl)) return null;

        long now = System.currentTimeMillis();
        Cached cache = CACHE.get(youtubeUrl);
        if (cache != null) {
            if (cache.resolvedUrl != null && (now - cache.timestamp < SUCCESS_TTL_MS)) return cache.resolvedUrl;
            if (cache.resolvedUrl == null && (now - cache.timestamp < FAILURE_TTL_MS)) return null;
        }

        Process process = null;
        try {
            File ytDlpFile = YtDlpProvisioner.ensureAvailable(ytDlpDir, client, enabled);
            if (ytDlpFile == null) {
                LOGGER.warn("yt-dlp is enabled but could not be prepared at {}; YouTube playback is skipped",
                        YtDlpProvisioner.binaryPath(ytDlpDir));
                fail(youtubeUrl);
                return null;
            }

            ProcessBuilder builder = new ProcessBuilder(
                    ytDlpFile.getAbsolutePath(), "-f", "22/18/b",
                    "--extractor-args", "youtube:player_client=android",
                    "--no-warnings", "--no-playlist", "--no-progress", "-g", youtubeUrl
            );
            // stderr を別に読まないと、エラー出力でパイプが詰まったときにタイムアウトできない。
            builder.redirectErrorStream(true);
            process = builder.start();
            Process running = process;
            Future<String> output = CompletableFuture.supplyAsync(
                    () -> readFirstHttpUrl(running.getInputStream()), OUTPUT_EXECUTOR);

            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                output.cancel(true);
                LOGGER.warn("yt-dlp process timed out while resolving YouTube URL: {}", youtubeUrl);
                fail(youtubeUrl);
                return null;
            }

            String rawUrl;
            try {
                rawUrl = output.get(2, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                output.cancel(true);
                rawUrl = null;
            }

            // yt-dlp の出力も、ネイティブへ渡す前に HTTP(S) の絶対 URL であることだけ確認する。
            // 実際の取得時の SSRF・許可ドメイン検査はリレー側の SafeHttpClient が行う。
            if (rawUrl != null && MediaUrls.isSupportedExternalVideoUrl(rawUrl)) {
                CACHE.put(youtubeUrl, new Cached(rawUrl, System.currentTimeMillis()));
                return rawUrl;
            }
            fail(youtubeUrl);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Interrupted while running yt-dlp: {}", e.getMessage());
            fail(youtubeUrl);
        } catch (Exception e) {
            LOGGER.warn("Exception running yt-dlp: {}", e.getMessage());
            fail(youtubeUrl);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
        return null;
    }

    private static void fail(String url) {
        CACHE.put(url, new Cached(null, System.currentTimeMillis()));
    }

    /** yt-dlp の出力から最初の http(s) の行を取る。 */
    public static String readFirstHttpUrl(InputStream input) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                    return trimmed;
                }
            }
        } catch (IOException ignored) {
            // プロセス終了時のストリーム切断など。URL が取れなければ呼び出し側が失敗扱いにする。
        }
        return null;
    }
}
