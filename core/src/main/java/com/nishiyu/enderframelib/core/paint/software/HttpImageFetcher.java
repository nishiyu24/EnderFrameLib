package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.core.net.HttpPolicy;
import com.nishiyu.enderframelib.core.net.SafeHttpClient;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 画像の {@code http(s)://} を背景スレッドで取得して覚える。描画をブロックしないので、
 * 取得中は {@link #PENDING}、失敗は null、取れたらバイト列を返す（次のフレームから見える）。
 *
 * <p>取得は {@link SafeHttpClient}（SSRF 対策付き）に任せる。検証した IP へそのまま接続し（DNS の引き直しなし）、
 * リダイレクトは自動追従せず各ホップを同じ検査にかける。既定では LAN・ループバック等へ解決される URL は断る。
 * 本文は {@link #MAX_BYTES} まで。
 */
public final class HttpImageFetcher {
    /** 取得中を表す目印（中身は見ない。参照で比べる）。 */
    public static final byte[] PENDING = new byte[0];
    public static final int MAX_BYTES = 20 * 1024 * 1024;
    private static final int MAX_ENTRIES = 256;
    private static final Object FAILED = new Object();

    private final SafeHttpClient http;
    private final ExecutorService pool;
    private final Map<String, Object> entries = new ConcurrentHashMap<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger completed = new AtomicInteger();

    public HttpImageFetcher() {
        this(HttpPolicy.publicOnly());
    }

    /** テスト用: true なら LAN／ループバックへも接続する。 */
    HttpImageFetcher(boolean allowPrivate) {
        this(allowPrivate ? HttpPolicy.permissive() : HttpPolicy.publicOnly());
    }

    /** 設定値（許可・私設網の遮断・許可ドメイン）を {@code policy} から読む。Minecraft 側と同じ検査を通せる。 */
    public HttpImageFetcher(HttpPolicy policy) {
        this(new SafeHttpClient(policy));
    }

    /** 外部メディアなど、ほかの取得と同じクライアントを共有する。 */
    public HttpImageFetcher(SafeHttpClient http) {
        this.http = http;
        AtomicInteger n = new AtomicInteger();
        pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "enderframe-http-image-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /** @return {@link #PENDING}（取得中。初回はここで開始）・null（失敗）・バイト列 */
    public byte[] get(String url) {
        Object e = entries.get(url);
        if (e instanceof byte[] b && b != PENDING) return b;
        if (e == FAILED) return null;
        if (e == null) {
            if (entries.size() >= MAX_ENTRIES) entries.clear();
            if (entries.putIfAbsent(url, PENDING) == null) {
                inFlight.incrementAndGet();
                pool.execute(() -> {
                    Object result = FAILED;
                    try {
                        byte[] data = download(url);
                        if (data != null) result = data;
                    } catch (IOException | RuntimeException ex) {
                        // 失敗は FAILED のまま
                    } finally {
                        entries.put(url, result);
                        completed.incrementAndGet();
                        inFlight.decrementAndGet();
                    }
                });
            }
        }
        return PENDING;
    }

    /** 取得中の件数。 */
    public int pending() {
        return inFlight.get();
    }

    /** 取得が終わった累計。変わったら描き直す合図に使える。 */
    public int completedCount() {
        return completed.get();
    }

    public void close() {
        pool.shutdownNow();
    }

    private byte[] download(String url) throws IOException {
        return http.fetchBytes(url, MAX_BYTES);
    }
}
