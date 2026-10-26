package com.nishiyu.enderframelib.client.renderer;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 動画のループバック・リレー。
 *
 * <p>ネイティブデコーダには生の外部 URL を渡さず、{@code http://127.0.0.1:<port>/v/<token>}
 * だけを渡す。リレーは {@link SafeHttpClient} を使って上流を検証・解決し、本文をメモリや
 * ディスクへ全部落とさずにストリーミングする。Range 要求もそのまま上流へ転送するので、
 * シーク時に動画全体をダウンロードする必要はない。</p>
 *
 * <p>サーバーは IPv4 ループバックだけにバインドし、トークンは 128bit の乱数。
 * ネイティブ側も設定されたポート以外の HTTP を拒否する。</p>
 */
final class VideoStreamServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(VideoStreamServer.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(1);
    private static final String PATH_PREFIX = "/v/";

    private static final Map<String, Handle> HANDLES = new ConcurrentHashMap<>();
    private static HttpServer server;
    private static volatile int port;

    private VideoStreamServer() {}

    static final class Handle {
        final String token;
        final String upstreamUrl;
        final long maxBytes;
        private volatile boolean closed;

        private Handle(String token, String upstreamUrl, long maxBytes) {
            this.token = token;
            this.upstreamUrl = upstreamUrl;
            this.maxBytes = maxBytes;
        }

        String loopbackUrl() {
            return "http://127.0.0.1:" + port + PATH_PREFIX + token;
        }

        boolean isClosed() {
            return closed;
        }

        void close() {
            if (closed) return;
            closed = true;
            HANDLES.remove(token, this);
        }
    }

    private record ByteRange(boolean present, boolean valid, long start, long end, long suffix, String header) {
        static final ByteRange NONE = new ByteRange(false, true, -1, -1, -1, null);
        static final ByteRange INVALID = new ByteRange(true, false, -1, -1, -1, null);
    }

    /** 動画用のリレーを開始し、上流 URL を登録する。返した Handle は再生終了時に閉じること。 */
    static Handle register(String upstreamUrl, long maxBytes) throws IOException {
        if (upstreamUrl == null || upstreamUrl.isBlank()) {
            throw new IOException("Missing video URL");
        }
        if (maxBytes <= 0) {
            throw new IOException("Invalid video stream limit");
        }
        if (!SafeHttpClient.isUrlAllowed(upstreamUrl)) {
            throw new IOException("Video URL is not allowed");
        }

        ensureStarted();
        byte[] tokenBytes = new byte[16];
        RANDOM.nextBytes(tokenBytes);
        String token = HexFormat.of().formatHex(tokenBytes);
        Handle handle = new Handle(token, upstreamUrl, maxBytes);
        HANDLES.put(token, handle);
        return handle;
    }

    static int port() {
        return port;
    }

    private static synchronized void ensureStarted() throws IOException {
        if (server != null) return;

        HttpServer created = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        created.createContext(PATH_PREFIX, VideoStreamServer::handle);
        ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable,
                    "EnderFrameLib-VideoRelay-" + THREAD_COUNTER.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        });
        created.setExecutor(executor);
        created.start();

        server = created;
        port = created.getAddress().getPort();
        LOGGER.info("Video loopback relay listening on 127.0.0.1:{}", port);
    }

    private static void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (path == null || !path.startsWith(PATH_PREFIX)) {
                sendError(exchange, 404, "Not Found");
                return;
            }
            String token = path.substring(PATH_PREFIX.length());
            Handle handle = HANDLES.get(token);
            if (handle == null || handle.isClosed()) {
                sendError(exchange, 404, "Not Found");
                return;
            }

            String method = exchange.getRequestMethod();
            if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                sendError(exchange, 405, "Method Not Allowed");
                return;
            }

            ByteRange range = parseRange(exchange.getRequestHeaders().getFirst("Range"));
            if (!range.valid()) {
                sendRangeNotSatisfiable(exchange, null);
                return;
            }

            boolean headRequest = "HEAD".equalsIgnoreCase(method);
            long upstreamLimit = range.present()
                    ? (range.start() >= 0 ? saturatingAdd(handle.maxBytes, range.start()) : handle.maxBytes)
                    : handle.maxBytes;

            SafeHttpClient.streamRange(handle.upstreamUrl, upstreamLimit, range.header(), (headInfo, body) -> {
                if (handle.isClosed()) {
                    throw new IOException("Relay handle is closed");
                }

                if (headInfo.status() == 416) {
                    sendRangeNotSatisfiable(exchange, headInfo.contentRange());
                    return;
                }

                if (headInfo.status() == 206) {
                    long length = headInfo.contentLength();
                    if (length > handle.maxBytes) {
                        sendRangeNotSatisfiable(exchange, null);
                        return;
                    }
                    sendHeaders(exchange, 206, length, headInfo.contentRange(),
                            headInfo.contentType(), headRequest);
                    if (!headRequest) {
                        copy(body, exchange.getResponseBody(), length, handle);
                    }
                    return;
                }

                long contentLength = headInfo.contentLength();
                if (!range.present()) {
                    if (contentLength > handle.maxBytes) {
                        throw new IOException("Video stream exceeds configured limit");
                    }
                    sendHeaders(exchange, 200, contentLength, null,
                            headInfo.contentType(), headRequest);
                    if (!headRequest) {
                        copy(body, exchange.getResponseBody(), contentLength, handle);
                    }
                    return;
                }

                if (contentLength < 0) {
                    // 上流が Range を無視して長さも返さない場合、正しい 206 を作れない。
                    // 200 としてそのまま流す（クライアントは先頭から読む）。
                    sendHeaders(exchange, 200, -1, null, headInfo.contentType(), headRequest);
                    if (!headRequest) {
                        copy(body, exchange.getResponseBody(), -1, handle);
                    }
                    return;
                }

                long sliceStart;
                long sliceEnd;
                if (range.start() >= 0) {
                    sliceStart = range.start();
                    sliceEnd = range.end() >= 0 ? Math.min(range.end(), contentLength - 1) : contentLength - 1;
                } else {
                    sliceStart = Math.max(0, contentLength - range.suffix());
                    sliceEnd = contentLength - 1;
                }

                if (sliceStart < 0 || sliceStart >= contentLength || sliceEnd < sliceStart) {
                    sendRangeNotSatisfiable(exchange, "bytes */" + contentLength);
                    return;
                }

                long length = sliceEnd - sliceStart + 1;
                if (length > handle.maxBytes) {
                    sendRangeNotSatisfiable(exchange, "bytes */" + contentLength);
                    return;
                }

                skipFully(body, sliceStart, handle);
                sendHeaders(exchange, 206, length,
                        "bytes " + sliceStart + "-" + sliceEnd + "/" + contentLength,
                        headInfo.contentType(), headRequest);
                if (!headRequest) {
                    copy(body, exchange.getResponseBody(), length, handle);
                }
            });
        } catch (IOException | RuntimeException e) {
            try {
                sendError(exchange, 502, "Bad Gateway");
            } catch (Exception ignored) {
                // ヘッダー送信後、またはクライアントが既に切断している。
            }
        } finally {
            exchange.close();
        }
    }

    private static void sendHeaders(HttpExchange exchange, int status, long contentLength,
                                     String contentRange, String contentType, boolean head) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", sanitizeHeader(contentType, "application/octet-stream"));
        headers.set("Accept-Ranges", "bytes");
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        if (contentRange != null && !contentRange.isBlank()) {
            headers.set("Content-Range", sanitizeHeader(contentRange, ""));
        }

        if (head) {
            if (contentLength >= 0) headers.set("Content-Length", Long.toString(contentLength));
            exchange.sendResponseHeaders(status, -1);
        } else if (contentLength > 0) {
            exchange.sendResponseHeaders(status, contentLength);
        } else if (contentLength == 0) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, 0); // chunked
        }
    }

    private static void sendRangeNotSatisfiable(HttpExchange exchange, String contentRange) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Accept-Ranges", "bytes");
        headers.set("Cache-Control", "no-store");
        if (contentRange != null && !contentRange.isBlank()) {
            headers.set("Content-Range", sanitizeHeader(contentRange, ""));
        }
        exchange.sendResponseHeaders(416, -1);
    }

    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static void copy(InputStream body, OutputStream output, long length, Handle handle) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long remaining = length;
        try {
            while (!handle.isClosed() && remaining != 0) {
                int max = remaining < 0 ? buffer.length : (int) Math.min((long) buffer.length, remaining);
                int read = body.read(buffer, 0, max);
                if (read < 0) {
                    if (remaining > 0) throw new IOException("Truncated upstream response");
                    break;
                }
                output.write(buffer, 0, read);
                if (remaining > 0) remaining -= read;
            }
            output.flush();
        } catch (IOException e) {
            // ネイティブ側がシークや停止で接続を切った場合もここへ来る。
            throw e;
        }
    }

    private static void skipFully(InputStream body, long bytes, Handle handle) throws IOException {
        byte[] buffer = new byte[8192];
        long remaining = bytes;
        while (remaining > 0 && !handle.isClosed()) {
            int read = body.read(buffer, 0, (int) Math.min((long) buffer.length, remaining));
            if (read < 0) throw new IOException("Truncated upstream response");
            remaining -= read;
        }
        if (remaining > 0) throw new IOException("Relay handle closed");
    }

    private static ByteRange parseRange(String header) {
        if (header == null || header.isBlank()) return ByteRange.NONE;
        String trimmed = header.trim();
        if (!trimmed.toLowerCase(Locale.ROOT).startsWith("bytes=")) return ByteRange.INVALID;
        String spec = trimmed.substring(6).trim();
        if (spec.isEmpty() || spec.indexOf(',') >= 0) return ByteRange.INVALID;
        int dash = spec.indexOf('-');
        if (dash < 0) return ByteRange.INVALID;

        String left = spec.substring(0, dash).trim();
        String right = spec.substring(dash + 1).trim();
        if (left.isEmpty() && right.isEmpty()) return ByteRange.INVALID;

        try {
            if (left.isEmpty()) {
                long suffix = Long.parseLong(right);
                if (suffix <= 0) return ByteRange.INVALID;
                return new ByteRange(true, true, -1, -1, suffix, "bytes=-" + suffix);
            }

            long start = Long.parseLong(left);
            if (start < 0) return ByteRange.INVALID;
            long end = -1;
            if (!right.isEmpty()) {
                end = Long.parseLong(right);
                if (end < start) return ByteRange.INVALID;
            }
            String canonical = "bytes=" + start + "-" + (end >= 0 ? Long.toString(end) : "");
            return new ByteRange(true, true, start, end, -1, canonical);
        } catch (NumberFormatException e) {
            return ByteRange.INVALID;
        }
    }

    private static long saturatingAdd(long left, long right) {
        if (right <= 0) return left;
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static String sanitizeHeader(String value, String fallback) {
        if (value == null) return fallback;
        String cleaned = value.replace('\r', ' ').replace('\n', ' ').trim();
        return cleaned.isEmpty() ? fallback : cleaned;
    }
}
