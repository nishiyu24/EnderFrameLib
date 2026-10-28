package com.nishiyu.enderframelib.client.renderer;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.IDN;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * 外部メディア取得用の最小 HTTP/1.1 クライアント。
 *
 * <p>SSRF 対策の要は「検証したアドレスへ、そのまま接続する」こと。{@link InetAddress#getAllByName(String)}
 * で解決した全アドレスを検査したうえで、{@link Socket#connect(java.net.SocketAddress, int)} には
 * その {@link InetAddress} を直接渡す。URL を {@code openConnection()} に渡す実装ではないので、
 * 検証と接続の間で DNS を引き直す余地（DNS Rebinding / TOCTOU）が無い。</p>
 *
 * <p>TLS では元のホスト名を SNI と証明書のホスト名検証に使うため、IP へ直接接続しても
 * 証明書検証は省略されない。リダイレクトは自動追従せず、各ホップを同じ経路で検証・解決する。</p>
 */
final class SafeHttpClient {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final int MAX_REDIRECT_HOPS = 5;
    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final int MAX_LINE_BYTES = 8 * 1024;

    private SafeHttpClient() {}

    @FunctionalInterface
    private interface BodyConsumer {
        void accept(InputStream body, long contentLength) throws IOException;
    }

    /**
     * ストリーミング応答のヘッダー。動画リレーが Range / Content-Range をそのまま
     * クライアントへ返せるように、必要な値だけを公開する。
     */
    record StreamHead(int status, long contentLength, String contentRange,
                      String contentType, String acceptRanges) {}

    @FunctionalInterface
    interface StreamConsumer {
        void accept(StreamHead head, InputStream body) throws IOException;
    }

    /** URL の形式・許可ドメイン・解決先アドレスを検査する（実際の取得は行わない）。 */
    static boolean isUrlAllowed(String urlString) {
        if (!WebMediaManager.allowExternalMedia || urlString == null || urlString.isBlank()) return false;
        try {
            URI uri = parseAndValidate(urlString);
            if (WebMediaManager.blockPrivateNetworkAccess) {
                resolveChecked(uri);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 上限付きで本文をメモリへ取得する。画像・SVG など小さいリソース用。 */
    static byte[] fetchBytes(String urlString, long maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        fetch(urlString, maxBytes, (body, contentLength) -> {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = body.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        });
        return out.toByteArray();
    }

    /**
     * メディア許可ドメインとは別に、固定した配布元ホストからの取得だけを許可する。
     *
     * <p>yt-dlp の公式リリースのように、利用者が明示的に有効化した信頼済みの
     * ダウンロードにだけ使う。スキーム・リダイレクト・SSRF の検査は通常経路と
     * 同じで、許可するホストだけを完全一致の集合で差し替える。</p>
     */
    static byte[] fetchBytesFromTrustedHosts(String urlString, long maxBytes, Set<String> trustedHosts)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Predicate<String> hostValidator = host -> isTrustedHost(trustedHosts, host);
        fetchWithValidator(urlString, maxBytes, hostValidator, (body, contentLength) -> {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = body.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        });
        return out.toByteArray();
    }

    /** 信頼済みホストからストリーミングでファイルへ書き出す。 */
    static void downloadFromTrustedHosts(String urlString, long maxBytes, Set<String> trustedHosts, Path target)
            throws IOException {
        Predicate<String> hostValidator = host -> isTrustedHost(trustedHosts, host);
        try (OutputStream out = Files.newOutputStream(target,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            fetchWithValidator(urlString, maxBytes, hostValidator,
                    (body, contentLength) -> body.transferTo(out));
        }
    }

    private static void fetch(String urlString, long maxBytes, BodyConsumer consumer) throws IOException {
        if (!WebMediaManager.allowExternalMedia) throw new IOException("External media is disabled");
        fetchWithValidator(urlString, maxBytes, SafeHttpClient::isHostAllowed, consumer);
    }

    private static void fetchWithValidator(String urlString, long maxBytes,
                                           Predicate<String> hostValidator, BodyConsumer consumer)
            throws IOException {
        if (maxBytes <= 0) throw new IOException("Invalid download limit");

        URI current = parseAndValidate(urlString, hostValidator);
        for (int hop = 0; hop <= MAX_REDIRECT_HOPS; hop++) {
            List<InetAddress> addresses = resolveChecked(current);

            try (Socket socket = openSocket(current, addresses)) {
                HttpHead head = sendGet(socket, current);

                if (isRedirect(head.status)) {
                    String location = head.first("Location");
                    if (location == null || location.isBlank()) {
                        throw new IOException("Redirect response without Location");
                    }
                    URI next;
                    try {
                        next = current.resolve(location.trim());
                    } catch (IllegalArgumentException e) {
                        throw new IOException("Invalid redirect Location", e);
                    }
                    current = parseAndValidate(next.toString(), hostValidator);
                    continue;
                }

                if (head.status < 200 || head.status >= 300) {
                    throw new IOException("HTTP " + head.status + " for " + current);
                }
                if (head.contentLength > maxBytes) {
                    throw new IOException("Response exceeds download limit");
                }

                InputStream body = socket.getInputStream();
                if (head.chunked) {
                    body = new ChunkedInputStream(body);
                } else if (head.contentLength >= 0) {
                    body = new FixedLengthInputStream(body, head.contentLength);
                }
                body = new BufferedInputStream(body, 8192);
                if (head.gzip) {
                    body = new GZIPInputStream(body);
                } else if (head.deflate) {
                    body = new InflaterInputStream(body);
                }

                consumer.accept(new LimitedInputStream(body, maxBytes), head.contentLength);
                return;
            }
        }
        throw new IOException("Too many redirects");
    }

    /**
     * 本文をメモリへ保持せずにストリーミングする。動画リレー専用。
     *
     * <p>{@code rangeHeader} はそのまま上流へ転送する（例: {@code bytes=0-}）。
     * 上流が Range を無視して 200 を返した場合の切り出しは呼び出し側が行う。
     * 416 もコールバックへ渡すので、リレーは Content-Range をそのまま返せる。</p>
     */
    static void streamRange(String urlString, long maxBytes, String rangeHeader, StreamConsumer consumer)
            throws IOException {
        if (!WebMediaManager.allowExternalMedia) throw new IOException("External media is disabled");
        if (maxBytes <= 0) throw new IOException("Invalid stream limit");
        if (rangeHeader != null && hasControlCharacter(rangeHeader)) {
            throw new IOException("Invalid Range header");
        }

        URI original = parseAndValidate(urlString);

        // Range のたびにリダイレクト鎖（drive.google.com → googleusercontent.com など）を
        // 辿り直すと遅いので、解決済みの最終 URL を短時間だけメモリに覚える。
        FinalUrl cached = FINAL_URLS.get(urlString);
        if (cached != null) {
            if (System.currentTimeMillis() < cached.expiresAt) {
                AtomicBoolean started = new AtomicBoolean(false);
                try {
                    streamRangeFrom(cached.uri, original, urlString, maxBytes, rangeHeader, consumer, started);
                    return;
                } catch (IOException e) {
                    // 応答を返し始めた後の失敗は、やり直せないのでそのまま伝える。
                    if (started.get()) throw e;
                    FINAL_URLS.remove(urlString, cached);
                }
            } else {
                FINAL_URLS.remove(urlString, cached);
            }
        }
        streamRangeFrom(original, original, urlString, maxBytes, rangeHeader, consumer, new AtomicBoolean(false));
    }

    private record FinalUrl(URI uri, long expiresAt) {}

    private static final Map<String, FinalUrl> FINAL_URLS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long FINAL_URL_TTL_MS = 5 * 60_000L;
    private static final int MAX_FINAL_URLS = 64;

    private static void streamRangeFrom(URI startUri, URI original, String cacheKey, long maxBytes,
                                        String rangeHeader, StreamConsumer consumer, AtomicBoolean started)
            throws IOException {
        URI current = startUri;
        for (int hop = 0; hop <= MAX_REDIRECT_HOPS; hop++) {
            List<InetAddress> addresses = resolveChecked(current);

            try (Socket socket = openSocket(current, addresses)) {
                HttpHead head = sendGet(socket, current, rangeHeader);

                if (isRedirect(head.status)) {
                    String location = head.first("Location");
                    if (location == null || location.isBlank()) {
                        throw new IOException("Redirect response without Location");
                    }
                    URI next;
                    try {
                        next = current.resolve(location.trim());
                    } catch (IllegalArgumentException e) {
                        throw new IOException("Invalid redirect Location", e);
                    }
                    current = parseAndValidate(next.toString());
                    continue;
                }

                if (head.status != 200 && head.status != 206 && head.status != 416) {
                    throw new IOException("HTTP " + head.status + " for " + current);
                }
                // Range 応答は呼び出し側が「実際に返すバイト数」を決める。ここで
                // Content-Length を全量と比較すると、Range を無視した上流に対して
                // 先頭付近の小さな Range まで拒否してしまう。
                boolean hasRange = rangeHeader != null && !rangeHeader.isBlank();
                if (!hasRange && head.status != 416 && head.contentLength > maxBytes) {
                    throw new IOException("Response exceeds stream limit");
                }
                // 動画は identity を要求している。Range と圧縮の併用は壊れた
                // バイト列になるため、圧縮応答は受け入れない。
                if (head.gzip || head.deflate) {
                    throw new IOException("Compressed video responses are not supported");
                }

                StreamHead streamHead = new StreamHead(
                        head.status,
                        head.contentLength,
                        head.first("Content-Range"),
                        head.first("Content-Type"),
                        head.first("Accept-Ranges"));

                if (!current.equals(original)) {
                    if (FINAL_URLS.size() >= MAX_FINAL_URLS) FINAL_URLS.clear();
                    FINAL_URLS.put(cacheKey, new FinalUrl(current, System.currentTimeMillis() + FINAL_URL_TTL_MS));
                }

                started.set(true);
                if (head.status == 416) {
                    consumer.accept(streamHead, InputStream.nullInputStream());
                    return;
                }

                InputStream body = socket.getInputStream();
                if (head.chunked) {
                    body = new ChunkedInputStream(body);
                } else if (head.contentLength >= 0) {
                    body = new FixedLengthInputStream(body, head.contentLength);
                }
                body = new BufferedInputStream(body, 64 * 1024);
                consumer.accept(streamHead, new LimitedInputStream(body, maxBytes));
                return;
            }
        }
        throw new IOException("Too many redirects");
    }

    private static URI parseAndValidate(String urlString) throws IOException {
        return parseAndValidate(urlString, SafeHttpClient::isHostAllowed);
    }

    private static URI parseAndValidate(String urlString, Predicate<String> hostValidator) throws IOException {
        if (urlString == null) throw new IOException("URL is null");
        String trimmed = urlString.trim();
        if (trimmed.isEmpty()) throw new IOException("URL is empty");
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new IOException("Invalid URL", e);
        }
        return validate(uri, hostValidator);
    }

    private static URI validate(URI uri, Predicate<String> hostValidator) throws IOException {
        if (!uri.isAbsolute()) throw new IOException("Relative URL is not allowed");
        String scheme = uri.getScheme();
        if (scheme == null
                || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new IOException("Unsupported URL scheme");
        }
        if (uri.getRawUserInfo() != null) throw new IOException("User info in URL is not allowed");

        String host = uri.getHost();
        if (host == null || host.isBlank()) throw new IOException("URL has no host");
        String normalized = normalizeHost(host);
        if (normalized.isEmpty() || normalized.indexOf('%') >= 0 || hasControlCharacter(normalized)) {
            throw new IOException("Invalid host");
        }
        if (WebMediaManager.blockPrivateNetworkAccess
                && ("localhost".equals(normalized)
                || normalized.endsWith(".local")
                || normalized.endsWith(".internal"))) {
            throw new IOException("Local host name is blocked");
        }
        if (!hostValidator.test(normalized)) throw new IOException("Host is not in the allowlist");

        int port = uri.getPort();
        if (port == 0 || port > 65535) throw new IOException("Invalid port");
        return uri;
    }

    private static String normalizeHost(String host) throws IOException {
        String result = host;
        if (result.startsWith("[") && result.endsWith("]")) {
            result = result.substring(1, result.length() - 1);
        }
        while (result.endsWith(".")) {
            result = result.substring(0, result.length() - 1);
        }
        if (result.isEmpty()) return result;
        if (!isAscii(result)) {
            try {
                result = IDN.toASCII(result, IDN.ALLOW_UNASSIGNED);
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid internationalized host", e);
            }
        }
        return result.toLowerCase(Locale.ROOT);
    }

    private static boolean isAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 0x7F) return false;
        }
        return true;
    }

    private static boolean hasControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= 0x20 || c == 0x7F) return true;
        }
        return false;
    }

    private static boolean isHostAllowed(String host) {
        if (!WebMediaManager.ALLOWED_DOMAINS.isEmpty()) {
            for (String rule : WebMediaManager.ALLOWED_DOMAINS) {
                if (matchesDomainRule(rule, host)) return true;
            }
        }
        Set<String> configRules = WebMediaManager.CONFIG_ALLOWED_DOMAINS;
        if (!configRules.isEmpty()) {
            for (String rule : configRules) {
                if (matchesDomainRule(rule, host)) return true;
            }
        }
        return WebMediaManager.ALLOWED_DOMAINS.isEmpty() && configRules.isEmpty();
    }

    /**
     * 公式配布物の取得に使う完全一致のホスト検査。サブドメインへの拡張はしない。
     * ここへ渡す集合はコード内の定数だけにし、設定ファイルから受け取らない。
     */
    private static boolean isTrustedHost(Set<String> trustedHosts, String host) {
        if (trustedHosts == null || trustedHosts.isEmpty()) return false;
        for (String raw : trustedHosts) {
            if (raw == null) continue;
            String rule = raw.trim().toLowerCase(Locale.ROOT);
            while (rule.endsWith(".")) rule = rule.substring(0, rule.length() - 1);
            if (!rule.isEmpty() && host.equals(rule)) return true;
        }
        return false;
    }

    private static boolean matchesDomainRule(String rawRule, String host) {
        if (rawRule == null) return false;
        String rule = rawRule.trim().toLowerCase(Locale.ROOT);
        while (rule.startsWith("*.")) rule = rule.substring(2);
        while (rule.startsWith(".")) rule = rule.substring(1);
        while (rule.endsWith(".")) rule = rule.substring(0, rule.length() - 1);
        if (rule.isEmpty()) return false;
        if (!isAscii(rule)) {
            try {
                rule = IDN.toASCII(rule, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        return host.equals(rule) || host.endsWith("." + rule);
    }

    /** ホスト名を解決し、検査が必要なら全アドレスが公開アドレスであることを確認する。 */
    private static List<InetAddress> resolveChecked(URI uri) throws IOException {
        String host = normalizeHost(uri.getHost());
        InetAddress[] resolved;
        try {
            resolved = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IOException("Cannot resolve host: " + host, e);
        }
        if (resolved.length == 0) throw new IOException("Cannot resolve host: " + host);
        if (WebMediaManager.blockPrivateNetworkAccess) {
            for (InetAddress address : resolved) {
                if (!isPublicAddress(address)) {
                    throw new IOException("Blocked non-public address for " + host);
                }
            }
        }
        return Arrays.asList(resolved);
    }

    private static Socket openSocket(URI uri, List<InetAddress> addresses) throws IOException {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        boolean https = scheme.equals("https");
        int port = uri.getPort() != -1 ? uri.getPort() : (https ? 443 : 80);
        String host = normalizeHost(uri.getHost());

        IOException last = null;
        for (InetAddress address : addresses) {
            Socket raw = new Socket();
            try {
                raw.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT_MS);
                raw.setSoTimeout(READ_TIMEOUT_MS);
                raw.setTcpNoDelay(true);
                if (!https) return raw;

                SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                        .createSocket(raw, host, port, true);
                SSLParameters parameters = ssl.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                if (!isIpLiteral(host)) {
                    parameters.setServerNames(Collections.singletonList(new SNIHostName(host)));
                }
                ssl.setSSLParameters(parameters);
                ssl.startHandshake();
                return ssl;
            } catch (IOException | RuntimeException e) {
                last = e instanceof IOException io ? io : new IOException(e);
                try {
                    raw.close();
                } catch (IOException ignored) {
                    // 接続に失敗したソケットの後始末。次のアドレスを試す。
                }
            }
        }
        throw last != null ? last : new IOException("No address for " + host);
    }

    private static HttpHead sendGet(Socket socket, URI uri) throws IOException {
        return sendGet(socket, uri, null);
    }

    private static HttpHead sendGet(Socket socket, URI uri, String rangeHeader) throws IOException {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        boolean https = scheme.equals("https");
        int port = uri.getPort() != -1 ? uri.getPort() : (https ? 443 : 80);
        String host = normalizeHost(uri.getHost());
        boolean defaultPort = (https && port == 443) || (!https && port == 80);

        String hostHeader = (host.indexOf(':') >= 0 ? "[" + host + "]" : host)
                + (defaultPort ? "" : ":" + port);
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
        if (hasControlCharacter(path) || hasControlCharacter(hostHeader)) {
            throw new IOException("Invalid request target");
        }

        StringBuilder requestBuilder = new StringBuilder();
        requestBuilder.append("GET ").append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(hostHeader).append("\r\n")
                .append("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) EnderFrameLib/1.0\r\n")
                .append("Accept: */*\r\n")
                .append("Accept-Encoding: identity\r\n")
                .append("Connection: close\r\n");
        if (rangeHeader != null && !rangeHeader.isBlank()) {
            requestBuilder.append("Range: ").append(rangeHeader).append("\r\n");
        }
        requestBuilder.append("\r\n");
        String request = requestBuilder.toString();
        OutputStream output = socket.getOutputStream();
        output.write(request.getBytes(StandardCharsets.ISO_8859_1));
        output.flush();

        InputStream input = socket.getInputStream();
        while (true) {
            String statusLine = readAsciiLine(input, MAX_LINE_BYTES);
            if (statusLine == null || statusLine.isEmpty()) {
                throw new IOException("Empty HTTP response");
            }
            int status = parseStatus(statusLine);
            Map<String, List<String>> headers = readHeaders(input);
            if (status >= 100 && status < 200 && status != 101) {
                continue; // 100 Continue などの情報レスポンスは読み飛ばす
            }
            if (status == 101) throw new IOException("Unexpected protocol switch");

            long contentLength = -1;
            String lengthHeader = first(headers, "Content-Length");
            if (lengthHeader != null) {
                try {
                    contentLength = Long.parseLong(lengthHeader.trim());
                } catch (NumberFormatException ignored) {
                    contentLength = -1;
                }
                if (contentLength < 0) contentLength = -1;
            }
            boolean chunked = headerContainsToken(headers, "Transfer-Encoding", "chunked");
            boolean gzip = headerContainsToken(headers, "Content-Encoding", "gzip");
            boolean deflate = headerContainsToken(headers, "Content-Encoding", "deflate");
            return new HttpHead(status, headers, contentLength, chunked, gzip, deflate);
        }
    }

    private static Map<String, List<String>> readHeaders(InputStream input) throws IOException {
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        int total = 0;
        while (true) {
            String line = readAsciiLine(input, MAX_LINE_BYTES);
            if (line == null) throw new IOException("Unexpected end of HTTP headers");
            if (line.isEmpty()) return headers;
            total += line.length();
            if (total > MAX_HEADER_BYTES) throw new IOException("HTTP headers are too large");
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (name.isEmpty()) continue;
            headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        }
    }

    private static int parseStatus(String statusLine) throws IOException {
        if (!statusLine.startsWith("HTTP/")) throw new IOException("Invalid HTTP status line");
        int space = statusLine.indexOf(' ');
        if (space < 0 || statusLine.length() < space + 4) {
            throw new IOException("Invalid HTTP status line");
        }
        try {
            return Integer.parseInt(statusLine.substring(space + 1, space + 4));
        } catch (NumberFormatException e) {
            throw new IOException("Invalid HTTP status code", e);
        }
    }

    private static String first(Map<String, List<String>> headers, String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static boolean headerContainsToken(Map<String, List<String>> headers, String name, String token) {
        List<String> values = headers.get(name);
        if (values == null) return false;
        for (String value : values) {
            for (String part : value.split(",")) {
                if (part.trim().equalsIgnoreCase(token)) return true;
            }
        }
        return false;
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static boolean isIpLiteral(String host) {
        if (host.indexOf(':') >= 0) return true;
        return host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    /** 公開インターネットへ向かないアドレス（ループバック・LAN・リンクローカル・予約域など）を弾く。 */
    static boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return isPublicIpv4(bytes, 0);
        }
        if (bytes.length != 16) return false;

        boolean zeroPrefix = true;
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                zeroPrefix = false;
                break;
            }
        }
        // IPv4-mapped (::ffff:a.b.c.d) と旧 IPv4-compatible (::a.b.c.d)
        if (zeroPrefix && ((bytes[10] == 0 && bytes[11] == 0)
                || (bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF))) {
            return isPublicIpv4(bytes, 12);
        }
        // 6to4 (2002:V4ADDR::/48)
        if ((bytes[0] & 0xFF) == 0x20 && (bytes[1] & 0xFF) == 0x02) {
            return isPublicIpv4(bytes, 2);
        }
        // NAT64 well-known prefix (64:ff9b::/96)
        if ((bytes[0] & 0xFF) == 0x00 && (bytes[1] & 0xFF) == 0x64
                && (bytes[2] & 0xFF) == 0xFF && (bytes[3] & 0xFF) == 0x9B) {
            for (int i = 4; i < 12; i++) {
                if (bytes[i] != 0) return false; // 64:ff9b:1::/48 などローカル用途
            }
            return isPublicIpv4(bytes, 12);
        }

        // グローバルユニキャスト (2000::/3) 以外（ULA fc00::/7、リンクローカル、マルチキャスト等）は拒否
        if ((bytes[0] & 0xE0) != 0x20) return false;
        // 2001::/23 IETF プロトコル割当（Teredo・ORCHID・ベンチマーク・AMT など）
        if ((bytes[0] & 0xFF) == 0x20 && (bytes[1] & 0xFF) == 0x01
                && (bytes[2] & 0xFF) <= 0x01) return false;
        // 2001:db8::/32 ドキュメント用
        if ((bytes[0] & 0xFF) == 0x20 && (bytes[1] & 0xFF) == 0x01
                && (bytes[2] & 0xFF) == 0x0D && (bytes[3] & 0xFF) == 0xB8) return false;
        // 3fff::/20 ドキュメント用
        if ((bytes[0] & 0xFF) == 0x3F && (bytes[1] & 0xFF) == 0xFF
                && (bytes[2] & 0xF0) == 0) return false;
        return true;
    }

    private static boolean isPublicIpv4(byte[] bytes, int offset) {
        int a0 = bytes[offset] & 0xFF;
        int a1 = bytes[offset + 1] & 0xFF;
        int a2 = bytes[offset + 2] & 0xFF;
        if (a0 == 0 || a0 == 10 || a0 == 127 || a0 >= 224) return false; // this-network/private/loopback/multicast+reserved
        if (a0 == 169 && a1 == 254) return false; // link-local
        if (a0 == 172 && a1 >= 16 && a1 <= 31) return false; // private
        if (a0 == 192 && a1 == 168) return false; // private
        if (a0 == 100 && a1 >= 64 && a1 <= 127) return false; // carrier-grade NAT
        if (a0 == 192 && a1 == 0 && (a2 == 0 || a2 == 2)) return false; // IETF protocol assignments / TEST-NET-1
        if (a0 == 198 && (a1 == 18 || a1 == 19)) return false; // benchmarking
        if (a0 == 198 && a1 == 51 && a2 == 100) return false; // TEST-NET-2
        if (a0 == 203 && a1 == 0 && a2 == 113) return false; // TEST-NET-3
        if (a0 == 192 && a1 == 88 && a2 == 99) return false; // 6to4 relay anycast
        if (a0 == 255 && a1 == 255 && a2 == 255) return false; // broadcast
        return true;
    }

    private static String readAsciiLine(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
        int read;
        while ((read = input.read()) != -1) {
            if (read == '\n') break;
            if (read != '\r') buffer.write(read);
            if (buffer.size() > maxBytes) throw new IOException("HTTP header line is too long");
        }
        if (read == -1 && buffer.size() == 0) return null;
        return buffer.toString(StandardCharsets.ISO_8859_1);
    }

    private record HttpHead(int status, Map<String, List<String>> headers, long contentLength,
                            boolean chunked, boolean gzip, boolean deflate) {
        String first(String name) {
            List<String> values = headers.get(name);
            return values == null || values.isEmpty() ? null : values.get(0);
        }
    }

    private static final class FixedLengthInputStream extends InputStream {
        private final InputStream delegate;
        private long remaining;

        private FixedLengthInputStream(InputStream delegate, long length) {
            this.delegate = delegate;
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            int value = delegate.read();
            if (value >= 0) remaining--;
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (remaining <= 0) return -1;
            int read = delegate.read(buffer, offset, (int) Math.min(length, remaining));
            if (read > 0) remaining -= read;
            return read;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    private static final class ChunkedInputStream extends InputStream {
        private final InputStream delegate;
        private long remaining;
        private boolean eof;

        private ChunkedInputStream(InputStream delegate) {
            this.delegate = delegate;
        }

        private boolean nextChunk() throws IOException {
            if (eof) return false;
            if (remaining > 0) return true;
            String line = readAsciiLine(delegate, 1024);
            if (line == null) throw new IOException("Truncated chunked response");
            int semicolon = line.indexOf(';');
            String sizeText = (semicolon >= 0 ? line.substring(0, semicolon) : line).trim();
            long size;
            try {
                size = Long.parseLong(sizeText, 16);
            } catch (NumberFormatException e) {
                throw new IOException("Invalid chunk size", e);
            }
            if (size < 0) throw new IOException("Invalid chunk size");
            if (size == 0) {
                String trailer;
                while ((trailer = readAsciiLine(delegate, MAX_LINE_BYTES)) != null && !trailer.isEmpty()) {
                    // トレーラは無視する
                }
                eof = true;
                return false;
            }
            remaining = size;
            return true;
        }

        private void consumeChunkTerminator() throws IOException {
            int first = delegate.read();
            if (first == '\r') {
                if (delegate.read() != '\n') throw new IOException("Malformed chunk terminator");
            } else if (first != '\n') {
                throw new IOException("Malformed chunk terminator");
            }
        }

        @Override
        public int read() throws IOException {
            if (!nextChunk()) return -1;
            int value = delegate.read();
            if (value < 0) throw new IOException("Truncated chunk");
            remaining--;
            if (remaining == 0) consumeChunkTerminator();
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (!nextChunk()) return -1;
            int read = delegate.read(buffer, offset, (int) Math.min(length, remaining));
            if (read < 0) throw new IOException("Truncated chunk");
            remaining -= read;
            if (remaining == 0) consumeChunkTerminator();
            return read;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    private static final class LimitedInputStream extends InputStream {
        private final InputStream delegate;
        private long remaining;

        private LimitedInputStream(InputStream delegate, long limit) {
            this.delegate = delegate;
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                if (delegate.read() >= 0) throw new IOException("Response exceeds download limit");
                return -1;
            }
            int value = delegate.read();
            if (value >= 0) remaining--;
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (remaining <= 0) {
                if (delegate.read() >= 0) throw new IOException("Response exceeds download limit");
                return -1;
            }
            int max = (int) Math.min((long) length, remaining + 1);
            int read = delegate.read(buffer, offset, max);
            if (read > remaining) throw new IOException("Response exceeds download limit");
            if (read > 0) remaining -= read;
            return read;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
