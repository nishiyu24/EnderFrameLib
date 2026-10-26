package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.core.raster.svg.SvgImage;
import com.nishiyu.enderframelib.core.paint.model.*;

import com.nishiyu.enderframelib.core.layout.model.IntrinsicSizeResolver;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.SessionContext;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.WString;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL21;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class WebMediaManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebMediaManager.class);

    public interface IExternalVideoContext {
        boolean isYouTubeSupportEnabled();
        List<String> getActiveSessionIdsFromHotbar();
        boolean isScreenOrSpeaker(BlockPos pos);
        float getSpeakerMaxDistance(BlockPos pos);
    }

    public static IExternalVideoContext externalContext = null;

    // 共通非同期メディアI/Oスレッドプール（無制限なスレッド生成を防止）
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(1);
    private static final ExecutorService MEDIA_IO_EXECUTOR = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "EnderFrameLib-MediaWorker-" + THREAD_COUNTER.getAndIncrement());
        t.setDaemon(true);
        return t;
    });

    // ==========================================
    // セキュリティ・ガードレール設定 (大手MODパック基準)
    // ==========================================
    public static boolean allowExternalMedia = true;
    public static boolean allowYtDlp = false; // 既定で無効化 (外部プロセス実行に対する同意ゲート)
    public static boolean blockPrivateNetworkAccess = true;
    public static long maxImageDownloadBytes = 10L * 1024L * 1024L; // 最大10MB
    public static final Set<String> ALLOWED_DOMAINS = ConcurrentHashMap.newKeySet();

    public static boolean isUrlSafe(String urlString) {
        if (!allowExternalMedia || urlString == null || urlString.isEmpty()) return false;
        try {
            URI uri = URI.create(urlString.trim());
            String scheme = uri.getScheme();
            if (scheme == null || (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme))) {
                return false;
            }
            String host = uri.getHost();
            if (host == null || host.isEmpty()) return false;
            String lowerHost = host.toLowerCase(Locale.ROOT);

            if (!ALLOWED_DOMAINS.isEmpty()) {
                boolean matched = false;
                for (String domain : ALLOWED_DOMAINS) {
                    String d = domain.toLowerCase(Locale.ROOT);
                    if (lowerHost.equals(d) || lowerHost.endsWith("." + d)) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) return false;
            }

            if (blockPrivateNetworkAccess) {
                if ("localhost".equals(lowerHost) || lowerHost.endsWith(".local") || lowerHost.endsWith(".internal")) {
                    return false;
                }
                InetAddress addr = InetAddress.getByName(host);
                if (addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress() || addr.isAnyLocalAddress()
                        || addr.isMulticastAddress() || isUniqueLocalIpv6(addr)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** IPv6 のユニークローカル（fc00::/7）。{@code isSiteLocalAddress} は旧 fec0::/10 しか見ないので別に判定する。 */
    private static boolean isUniqueLocalIpv6(InetAddress addr) {
        return addr instanceof java.net.Inet6Address && (addr.getAddress()[0] & 0xFE) == 0xFC;
    }

    // ==========================================
    // 1. Web Image Cache System
    // ==========================================
    private static final int MAX_CACHED_IMAGES = 256;
    private static final Map<String, ImageInfo> IMG_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<String, ImageInfo>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ImageInfo> eldest) {
                    if (size() > MAX_CACHED_IMAGES) {
                        ImageInfo removed = eldest.getValue();
                        if (removed != null && removed.textureId() != null) {
                            try {
                                Minecraft.getInstance().getTextureManager().release(removed.textureId());
                            } catch (Exception ignored) {}
                        }
                        return true;
                    }
                    return false;
                }
            }
    );
    private static final ConcurrentHashMap<String, Boolean> IMG_DOWNLOADING = new ConcurrentHashMap<>();

    private static String computeUrlHash(String url) {
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

    public record ImageInfo(ResourceLocation textureId, int width, int height) {}

    public static ImageInfo getImage(String urlString) {
        if (urlString == null || urlString.isEmpty() || !allowExternalMedia) return null;
        ImageInfo cached = IMG_CACHE.get(urlString);
        if (cached != null) return cached;
        if (IMG_DOWNLOADING.putIfAbsent(urlString, Boolean.TRUE) == null) {
            downloadImageAsync(urlString);
        }
        return null;
    }

    // ==========================================
    // 1b. SVG（画像としての SVG はビットマップではなく、描く大きさで毎回ラスタライズする）
    // ==========================================
    private static final Map<String, SvgImage> SVG_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<String, SvgImage>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, SvgImage> eldest) {
                    return size() > 64;
                }
            });
    private static final ConcurrentHashMap<String, Boolean> SVG_DOWNLOADING = new ConcurrentHashMap<>();

    /** 取得済みの SVG。未取得なら取りに行って null（届いたら固有サイズを登録してレイアウトし直す）。 */
    public static SvgImage getSvg(String urlString) {
        if (urlString == null || urlString.isEmpty() || !allowExternalMedia) return null;
        SvgImage cached = SVG_CACHE.get(urlString);
        if (cached != null) return cached;
        if (SVG_DOWNLOADING.putIfAbsent(urlString, Boolean.TRUE) == null) {
            downloadSvgAsync(urlString);
        }
        return null;
    }

    private static void downloadSvgAsync(String originalUrl) {
        MEDIA_IO_EXECUTOR.submit(() -> {
            try {
                String directUrl = convertToDirectLink(originalUrl);
                if (!isUrlSafe(directUrl)) return;
                HttpURLConnection conn = openCheckedConnection(directUrl);
                if (conn == null) return;
                long limit = Math.min(maxImageDownloadBytes, SvgImage.MAX_BYTES);
                if (conn.getContentLengthLong() > limit) return;
                try (InputStream is = conn.getInputStream()) {
                    byte[] bytes = is.readNBytes((int) limit + 1);
                    if (bytes.length > limit) return;
                    SvgImage svg = SvgImage.parse(new String(bytes, StandardCharsets.UTF_8));
                    if (svg == null) return;
                    Minecraft.getInstance().execute(() -> {
                        SVG_CACHE.put(originalUrl, svg);
                        ClientImageRegistry.setSize(originalUrl, (int) Math.max(1, Math.round(svg.width())),
                                (int) Math.max(1, Math.round(svg.height())));
                    });
                }
            } catch (Exception ignored) {
                // 取れない SVG は描かない（再試行は SVG_DOWNLOADING を外した次回の参照）
            } finally {
                SVG_DOWNLOADING.remove(originalUrl);
            }
        });
    }

    private static String convertToDirectLink(String urlString) {
        if (urlString.contains("drive.google.com/file/d/")) {
            try {
                String[] parts = urlString.split("/file/d/");
                if (parts.length > 1) {
                    String fileId = parts[1].split("/")[0];
                    return "https://drive.google.com/uc?export=download&id=" + fileId;
                }
            } catch (Exception ignored) {}
        }
        return urlString;
    }

    private static void downloadImageAsync(String originalUrl) {
        MEDIA_IO_EXECUTOR.submit(() -> {
            try {
                String directUrlString = convertToDirectLink(originalUrl);
                if (!isUrlSafe(directUrlString)) {
                    IMG_DOWNLOADING.remove(originalUrl);
                    return;
                }

                HttpURLConnection conn = openCheckedConnection(directUrlString);
                if (conn == null) {
                    IMG_DOWNLOADING.remove(originalUrl);
                    return;
                }

                long contentLength = conn.getContentLengthLong();
                if (contentLength > maxImageDownloadBytes) {
                    IMG_DOWNLOADING.remove(originalUrl);
                    return;
                }

                try (InputStream is = conn.getInputStream()) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    long totalRead = 0;
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        totalRead += n;
                        if (totalRead > maxImageDownloadBytes) {
                            IMG_DOWNLOADING.remove(originalUrl);
                            return;
                        }
                        baos.write(buf, 0, n);
                    }

                    NativeImage nativeImage = NativeImage.read(new ByteArrayInputStream(baos.toByteArray()));
                    int imgW = nativeImage.getWidth();
                    int imgH = nativeImage.getHeight();
                    String safeHash = computeUrlHash(originalUrl);

                    Minecraft.getInstance().execute(() -> {
                        DynamicTexture texture = new DynamicTexture(nativeImage);
                        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("enderframelib", "webimg_" + safeHash);
                        Minecraft.getInstance().getTextureManager().register(id, texture);

                        IMG_CACHE.put(originalUrl, new ImageInfo(id, imgW, imgH));
                        IMG_DOWNLOADING.remove(originalUrl);
                        ClientImageRegistry.setSize(originalUrl, imgW, imgH);
                    });
                }
            } catch (Exception e) {
                IMG_DOWNLOADING.remove(originalUrl);
            }
        });
    }

    private static final int MAX_REDIRECT_HOPS = 5;

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /**
     * リダイレクトを自動追従せず 1 ホップずつ辿り、各ホップの URL を {@link #isUrlSafe} で検査する。
     * 相対 {@code Location} は直前の URL に対して解決する。最終応答（リダイレクトでないもの）を返し、
     * 遮断された・{@code Location} が無い・{@link #MAX_REDIRECT_HOPS} を超えたときは {@code null}。
     */
    private static HttpURLConnection openCheckedConnection(String startUrl) throws java.io.IOException {
        String current = startUrl;
        for (int hop = 0; hop <= MAX_REDIRECT_HOPS; hop++) {
            if (!isUrlSafe(current)) return null;
            HttpURLConnection conn = (HttpURLConnection) URI.create(current).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            int status = conn.getResponseCode();
            if (!isRedirect(status)) return conn;
            String location = conn.getHeaderField("Location");
            conn.disconnect();
            if (location == null || location.isBlank()) return null;
            current = URI.create(current).resolve(location.trim()).toString();
        }
        return null;
    }

    // ==========================================
    // 2. Video Player System
    // ==========================================
    private static final Map<String, VideoTexture> ACTIVE_VIDEOS = new ConcurrentHashMap<>();
    private static final Map<BlockPos, List<BlockPos>> SCREEN_AUDIO_LINKS = new ConcurrentHashMap<>();
    private static final Map<String, YtDlpCache> YT_DLP_CACHE = new ConcurrentHashMap<>();

    static {
        try {
            NeoForge.EVENT_BUS.addListener(WebMediaManager::onClientTick);
            NeoForge.EVENT_BUS.addListener(WebMediaManager::onLevelUnload);
        } catch (Throwable t) {
            LOGGER.warn("Failed to register WebMediaManager event listeners: {}", t.getMessage(), t);
        }
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        cleanup();
    }

    private static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) closeAll();
    }

    public static void closeAll() {
        for (VideoTexture tex : ACTIVE_VIDEOS.values()) tex.close();
        ACTIVE_VIDEOS.clear();
        SCREEN_AUDIO_LINKS.clear();
    }

    public static VideoTexture getVideoTexture(String url) {
        if (url == null || url.isEmpty() || !allowExternalMedia) return null;
        VideoTexture existing = ACTIVE_VIDEOS.get(url);
        if (existing != null && (existing.isClosed || existing.isFailed)) {
            ACTIVE_VIDEOS.remove(url);
        }
        return ACTIVE_VIDEOS.computeIfAbsent(url, k -> {
            VideoTexture tex = new VideoTexture(url);
            if (RenderSystem.isOnRenderThread()) {
                Minecraft.getInstance().getTextureManager().register(tex.textureLocation, tex);
                tex.isRegistered = true;
            } else {
                RenderSystem.recordRenderCall(() -> {
                    Minecraft.getInstance().getTextureManager().register(tex.textureLocation, tex);
                    tex.isRegistered = true;
                });
            }
            return tex;
        });
    }

    public static void linkScreenToSpeakers(BlockPos screenPos, List<BlockPos> speakers) {
        if (speakers == null || speakers.isEmpty()) {
            SCREEN_AUDIO_LINKS.remove(screenPos);
        } else {
            List<BlockPos> existing = SCREEN_AUDIO_LINKS.get(screenPos);
            if (existing != null && existing.equals(speakers)) return;
            SCREEN_AUDIO_LINKS.put(screenPos, new ArrayList<>(speakers));
        }
    }

    /**
     * セッションが消えている場合も、旧 {@code getElements} と同じく描画キューの処理だけは行う。
     * 消えたセッションの描画要素は常に空。
     */
    private static List<DisplayItem> elementsOf(String sessionId) {
        SessionContext session = ClientWebManager.getSession(sessionId);
        if (session != null) return session.elements();
        ClientWebManager.processRenderQueue();
        return List.of();
    }

    public static void cleanup() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isPaused() || mc.level == null || mc.player == null) return;

        long now = System.currentTimeMillis();
        List<String> activeIps = new ArrayList<>();

        if (externalContext != null) {
            activeIps.addAll(externalContext.getActiveSessionIdsFromHotbar());
        }

        for (String sessionKey : activeIps) {
            List<DisplayItem> els = elementsOf(sessionKey);
            if (els != null) {
                for (DisplayItem el : els) {
                    if (el.type() == ElementType.VIDEO && el.text() != null) {
                        VideoTexture videoTex = ACTIVE_VIDEOS.get(el.text());
                        if (videoTex != null) {
                            videoTex.keepAlive2D(sessionKey);
                            videoTex.updateFrame();
                        }
                    }
                }
            }
        }

        ACTIVE_VIDEOS.entrySet().removeIf(entry -> {
            VideoTexture tex = entry.getValue();
            if (tex.forceClose) {
                tex.close();
                return true;
            }

            tex.activeIps.removeIf(sessionKey -> {
                List<DisplayItem> els = elementsOf(sessionKey);
                if (els == null || els.isEmpty()) return true;
                for (DisplayItem el : els) {
                    if (el.type() == ElementType.VIDEO && tex.url.equals(el.text())) return false;
                }
                return true;
            });

            tex.active2DAudioIps.entrySet().removeIf(e -> (now - e.getValue()) > 1500L);
            tex.activeAudioPositions.entrySet().removeIf(e -> {
                BlockPos pos = e.getKey();
                if (!mc.level.isLoaded(pos)) return false;
                if (externalContext != null && externalContext.isScreenOrSpeaker(pos)) {
                    List<DisplayItem> els = elementsOf("block:" + pos.getX() + "," + pos.getY() + "," + pos.getZ());
                    if (els != null) {
                        for (DisplayItem el : els) {
                            if (el.type() == ElementType.VIDEO && tex.url.equals(el.text())) {
                                tex.activeAudioPositions.put(pos, now);
                                return false;
                            }
                        }
                    }
                }
                return (now - e.getValue()) > 1000L;
            });

            if (!tex.activeIps.isEmpty() || !tex.activeAudioPositions.isEmpty() || !tex.active2DAudioIps.isEmpty()) {
                tex.lastRenderTime = now;
            }

            long timeout = tex.isInitialized ? 1500L : 30000L;
            if (now - tex.lastRenderTime > timeout) {
                tex.close();
                return true;
            }
            return false;
        });

        YT_DLP_CACHE.entrySet().removeIf(e -> (now - e.getValue().timestamp) > 3600000L);
    }

    private static String resolveYouTubeUrlViaYtDlp(String youtubeUrl) {
        if (!allowExternalMedia || !allowYtDlp) return null;
        if (!isUrlSafe(youtubeUrl)) return null;
        if (externalContext != null && !externalContext.isYouTubeSupportEnabled()) return null;

        long now = System.currentTimeMillis();
        YtDlpCache cache = YT_DLP_CACHE.get(youtubeUrl);
        if (cache != null) {
            if (cache.resolvedUrl != null && (now - cache.timestamp < 3600000L)) return cache.resolvedUrl;
            if (cache.resolvedUrl == null && (now - cache.timestamp < 60000L)) return null;
        }

        try {
            String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
            String ytDlpFileName = "yt-dlp";
            if (osName.contains("win")) {
                ytDlpFileName = "yt-dlp.exe";
            } else if (osName.contains("mac")) {
                ytDlpFileName = "yt-dlp_macos";
            }

            File dir = new File(Minecraft.getInstance().gameDirectory, "enderframelib");
            if (!dir.exists()) dir.mkdirs();

            // 実行ファイルは利用者が事前に置いたものだけを使う。無検証のダウンロードと自己更新（-U）はしない。
            File ytDlpFile = new File(dir, ytDlpFileName);
            if (!ytDlpFile.isFile()) {
                LOGGER.warn("yt-dlp is enabled but {} was not found; place a yt-dlp binary there to play YouTube URLs", ytDlpFile);
                YT_DLP_CACHE.put(youtubeUrl, new YtDlpCache(null, System.currentTimeMillis()));
                return null;
            }

            Process process = new ProcessBuilder(
                    ytDlpFile.getAbsolutePath(), "-f", "22/18/b",
                    "--extractor-args", "youtube:player_client=android",
                    "--no-warnings", "-g", youtubeUrl
            ).start();
            String rawUrl = null;
            try (Scanner scanner = new Scanner(process.getInputStream(), StandardCharsets.UTF_8)) {
                while (scanner.hasNextLine()) {
                    String line = scanner.nextLine().trim();
                    if (line.startsWith("http")) {
                        rawUrl = line;
                        break;
                    }
                }
            }
            boolean finished = process.waitFor(15, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                LOGGER.warn("yt-dlp process timed out while resolving YouTube URL: {}", youtubeUrl);
                YT_DLP_CACHE.put(youtubeUrl, new YtDlpCache(null, System.currentTimeMillis()));
                return null;
            }

            if (rawUrl != null && rawUrl.startsWith("http")) {
                YT_DLP_CACHE.put(youtubeUrl, new YtDlpCache(rawUrl, System.currentTimeMillis()));
                return rawUrl;
            }
            YT_DLP_CACHE.put(youtubeUrl, new YtDlpCache(null, System.currentTimeMillis()));
        } catch (Exception e) {
            LOGGER.warn("Exception running yt-dlp: {}", e.getMessage());
            YT_DLP_CACHE.put(youtubeUrl, new YtDlpCache(null, System.currentTimeMillis()));
        }
        return null;
    }

    private static String resolveDirectStreamUrl(String targetUrl) {
        try {
            if (!targetUrl.contains("youtube.com/watch") && !targetUrl.contains("youtu.be/")) {
                if (!targetUrl.startsWith("file://") && !targetUrl.contains(":\\") && !targetUrl.startsWith("/") && targetUrl.contains("://")) {
                    HttpURLConnection conn = openCheckedConnection(targetUrl);
                    if (conn == null) return null;
                    String finalUrl = conn.getURL().toString();
                    conn.disconnect();
                    return finalUrl;
                }
                return null;
            }
            return resolveYouTubeUrlViaYtDlp(targetUrl);
        } catch (Exception e) {
            return null;
        }
    }

    public interface NativeVideoAPI extends Library {
        class Holder {
            private static final AtomicBoolean WARNED_UNSUPPORTED = new AtomicBoolean(false);
            private static final AtomicBoolean WARNED_LOAD_FAIL = new AtomicBoolean(false);
            private static final NativeVideoAPI INSTANCE = loadLibrary();

            /**
             * LIMITATION(制約): 同杳のデコーダは lunex_video.dll のみ。
             * Windows x86_64 以外では <video> は再生されず、要素は空のままになる。
             * README の "Not supported" と NOTICE の第三者表記と対応。
             */
            private static NativeVideoAPI loadLibrary() {
                String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
                String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

                boolean isWindows = osName.contains("win");
                boolean isX64 = (osArch.contains("64") || osArch.contains("amd64")) && !osArch.contains("aarch64") && !osArch.contains("arm");

                if (!isWindows || !isX64) {
                    if (WARNED_UNSUPPORTED.compareAndSet(false, true)) {
                        LOGGER.warn("Native video playback is not supported on this platform: os.name='{}', os.arch='{}' (requires Windows x86_64)", osName, osArch);
                    }
                    return null;
                }

                try {
                    File customLibDir = new File(Minecraft.getInstance().gameDirectory, "enderframelib");
                    if (!customLibDir.exists()) customLibDir.mkdirs();

                    String libName = "lunex_video";
                    String libFileName = libName + ".dll";
                    File extractedLib = new File(customLibDir, libFileName);

                    byte[] resourceBytes = null;
                    try (InputStream in = WebMediaManager.class.getResourceAsStream("/assets/enderframelib/natives/" + libFileName)) {
                        if (in != null) {
                            resourceBytes = in.readAllBytes();
                        }
                    } catch (Exception e) {
                        LOGGER.warn("Failed to read embedded native library: {}", e.getMessage());
                    }

                    if (resourceBytes != null) {
                        boolean needCopy = true;
                        if (extractedLib.exists()) {
                            try {
                                byte[] existingBytes = Files.readAllBytes(extractedLib.toPath());
                                if (Arrays.equals(resourceBytes, existingBytes)) {
                                    needCopy = false;
                                }
                            } catch (Exception ignored) {}
                        }
                        if (needCopy) {
                            try {
                                Files.write(extractedLib.toPath(), resourceBytes);
                            } catch (Exception e) {
                                LOGGER.warn("Could not write native library to {}: {}", extractedLib.getAbsolutePath(), e.getMessage());
                            }
                        }
                    }

                    NativeLibrary.addSearchPath(libName, customLibDir.getAbsolutePath());
                    return Native.load(libName, NativeVideoAPI.class);
                } catch (Throwable t) {
                    if (WARNED_LOAD_FAIL.compareAndSet(false, true)) {
                        LOGGER.warn("Failed to load native video library: {}", t.getMessage(), t);
                    }
                    return null;
                }
            }
        }

        static NativeVideoAPI getInstance() {
            return Holder.INSTANCE;
        }

        NativeVideoAPI INSTANCE = Holder.INSTANCE;
        long initVideo(WString var1);
        int getVideoWidth(long var1);
        int getVideoHeight(long var1);
        int checkNewFrame(long var1);
        int copyVideoFrame(long var1, ByteBuffer var3);
        void seekVideo(long var1, double var3);
        void setVideoPaused(long var1, int var3);
        void setVideoSpeed(long var1, float var3);
        int hasAudio(long var1);
        int getAudioSampleRate(long var1);
        int getAudioChannels(long var1);
        int fetchAudioData(long var1, ByteBuffer var3, int var4);
        void closeVideo(long var1);
    }

    private record YtDlpCache(String resolvedUrl, long timestamp) {}

    public static class VideoTexture extends AbstractTexture {
        public final String url;
        public final ResourceLocation textureLocation;
        public final Map<BlockPos, Long> activeAudioPositions = new ConcurrentHashMap<>();
        public final Map<String, Long> active2DAudioIps = new ConcurrentHashMap<>();
        public final Set<String> activeIps = ConcurrentHashMap.newKeySet();

        private final Object stateLock = new Object();
        private final int[] alBuffers = new int[4];
        private final Queue<Integer> freeBuffers = new ConcurrentLinkedQueue<>();
        private final List<Integer> alSources = new ArrayList<>();
        private final List<BlockPos> customSpeakers = new ArrayList<>();
        public volatile int width = 0;
        public volatile int height = 0;
        public volatile long lastRenderTime;
        public volatile boolean isInitialized = false;
        public volatile boolean isRegistered = false;
        public volatile boolean forceClose = false;
        private boolean is2DEnabled = false;
        private volatile long nativeHandle = 0L;
        private volatile boolean isFailed = false;
        private volatile boolean isClosed = false;
        private boolean isPaused = false;
        private float playbackSpeed = 1.0F;
        private boolean hasAudio = false;
        private int audioSampleRate = 44100;
        private int audioChannels = 1;
        private int alFormat = AL10.AL_FORMAT_MONO16;
        private ByteBuffer pixelBuffer = null;
        private ByteBuffer audioReadBuffer = null;

        public VideoTexture(String url) {
            this.url = url;
            this.lastRenderTime = System.currentTimeMillis();
            // 32bit の String.hashCode では別の動画 URL が同じテクスチャを掴みうる。
            this.textureLocation = ResourceLocation.fromNamespaceAndPath("enderframelib", "video_" + computeUrlHash(url));
            if (NativeVideoAPI.getInstance() != null) {
                MEDIA_IO_EXECUTOR.submit(this::initVideoWorker);
            } else {
                this.isFailed = true;
            }
        }

        private void initVideoWorker() {
            try {
                NativeVideoAPI api = NativeVideoAPI.getInstance();
                if (api == null) {
                    this.isFailed = true;
                    return;
                }

                String targetUrl = url;
                int atIndex = targetUrl.lastIndexOf('@');
                if (atIndex != -1) targetUrl = targetUrl.substring(0, atIndex);

                targetUrl = convertToDirectLink(targetUrl);
                targetUrl = WebMediaManager.resolveDirectStreamUrl(targetUrl);
                if (targetUrl == null) {
                    this.isFailed = true;
                    return;
                }

                if (!targetUrl.contains(".mp4") && targetUrl.startsWith("http") && !targetUrl.contains("googlevideo.com")) {
                    targetUrl = targetUrl + "#.mp4";
                }

                synchronized (stateLock) {
                    if (this.isClosed) return;
                }

                long handle = api.initVideo(new WString(targetUrl));
                if (handle == 0L) {
                    this.isFailed = true;
                    return;
                }

                synchronized (stateLock) {
                    if (this.isClosed) {
                        MEDIA_IO_EXECUTOR.submit(() -> {
                            try { api.closeVideo(handle); } catch (Exception ignored) {}
                        });
                        return;
                    }
                    this.nativeHandle = handle;
                }

                this.width = api.getVideoWidth(handle);
                this.height = api.getVideoHeight(handle);
                if (this.width > 0 && this.height > 0) {
                    this.pixelBuffer = ByteBuffer.allocateDirect(this.width * this.height * 4).order(ByteOrder.nativeOrder());
                    RenderSystem.recordRenderCall(() -> {
                        RenderSystem.bindTexture(this.getId());
                        TextureUtil.prepareImage(this.getId(), this.width, this.height);
                        this.setFilter(true, false);
                        this.isInitialized = true;
                    });
                }

                if (api.hasAudio(handle) == 1) {
                    this.hasAudio = true;
                    this.audioSampleRate = api.getAudioSampleRate(handle);
                    this.audioChannels = api.getAudioChannels(handle);
                    this.alFormat = this.audioChannels == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
                    this.audioReadBuffer = ByteBuffer.allocateDirect(65536).order(ByteOrder.nativeOrder());

                    RenderSystem.recordRenderCall(() -> {
                        try {
                            AL10.alGenBuffers(this.alBuffers);
                            for (int b : this.alBuffers) this.freeBuffers.add(b);
                        } catch (Exception ignored) {}
                    });
                }
            } catch (Throwable t) {
                this.isFailed = true;
            }
        }

        public void keepAlive(BlockPos audioPos, String contextIp) {
            this.lastRenderTime = System.currentTimeMillis();
            if (audioPos != null) this.activeAudioPositions.put(audioPos, this.lastRenderTime);
            if (contextIp != null && !contextIp.isEmpty()) this.activeIps.add(contextIp);
        }

        public void keepAlive2D(String contextIp) {
            this.lastRenderTime = System.currentTimeMillis();
            if (contextIp != null && !contextIp.isEmpty()) {
                this.activeIps.add(contextIp);
                this.active2DAudioIps.put(contextIp, this.lastRenderTime);
            }
        }

        @Override
        public void load(@NotNull ResourceManager manager) {}

        public void setVolume(float volume) {
            RenderSystem.recordRenderCall(() -> {
                for (int src : this.alSources) {
                    if (AL10.alIsSource(src)) AL10.alSourcef(src, AL10.AL_GAIN, Math.clamp(volume, 0.0F, 1.0F));
                }
            });
        }

        public void setPlaybackSpeed(float speed) {
            this.playbackSpeed = speed;
            NativeVideoAPI api = NativeVideoAPI.getInstance();
            if (this.nativeHandle != 0L && api != null) {
                api.setVideoSpeed(this.nativeHandle, speed);
            }

            RenderSystem.recordRenderCall(() -> {
                for (int src : this.alSources) {
                    if (AL10.alIsSource(src)) {
                        if (speed > 0.0F) {
                            AL10.alSourcef(src, AL10.AL_PITCH, Math.clamp(speed, 0.1F, 2.0F));
                            if (this.isPaused) AL10.alSourcePause(src);
                            else if (AL10.alGetSourcei(src, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) AL10.alSourcePlay(src);
                        } else {
                            AL10.alSourcePause(src);
                        }
                    }
                }
            });
        }

        public void setPaused(boolean paused) {
            this.isPaused = paused;
            NativeVideoAPI api = NativeVideoAPI.getInstance();
            if (this.nativeHandle != 0L && api != null) {
                api.setVideoPaused(this.nativeHandle, paused ? 1 : 0);
            }

            RenderSystem.recordRenderCall(() -> {
                for (int src : this.alSources) {
                    if (AL10.alIsSource(src)) {
                        if (!paused && this.playbackSpeed > 0.0F) AL10.alSourcePlay(src);
                        else AL10.alSourcePause(src);
                    }
                }
            });
        }

        public void seekTo(double seconds) {
            NativeVideoAPI api = NativeVideoAPI.getInstance();
            if (this.nativeHandle != 0L && api != null) {
                api.seekVideo(this.nativeHandle, seconds);
                if (this.audioReadBuffer != null) this.audioReadBuffer.clear();

                RenderSystem.recordRenderCall(() -> {
                    for (int src : this.alSources) {
                        if (AL10.alIsSource(src)) {
                            AL10.alGetError();
                            AL10.alSourceStop(src);
                            AL10.alSourcei(src, AL10.AL_BUFFER, 0);
                        }
                    }
                    this.freeBuffers.clear();
                    for (int b : this.alBuffers) this.freeBuffers.add(b);
                    if (!this.isPaused && this.playbackSpeed > 0.0F) {
                        for (int src : this.alSources) AL10.alSourcePlay(src);
                    }
                });
            }
        }

        private void updateAudioSourcesIfNeeded() {
            if (!this.hasAudio) return;

            boolean needs2D = !this.active2DAudioIps.isEmpty();
            List<BlockPos> newSpeakers = new ArrayList<>();

            for (BlockPos pos : this.activeAudioPositions.keySet()) {
                List<BlockPos> linked = SCREEN_AUDIO_LINKS.get(pos);
                if (linked != null && !linked.isEmpty()) {
                    Minecraft mc = Minecraft.getInstance();
                    for (BlockPos sp : linked) {
                        if (mc.level != null && externalContext != null && externalContext.isScreenOrSpeaker(sp)) {
                            if (!newSpeakers.contains(sp)) newSpeakers.add(sp);
                        }
                    }
                } else if (!newSpeakers.contains(pos)) {
                    newSpeakers.add(pos);
                }
            }

            boolean needsRebuild = (this.is2DEnabled != needs2D) || !this.customSpeakers.equals(newSpeakers);
            if (needsRebuild) {
                this.customSpeakers.clear();
                this.customSpeakers.addAll(newSpeakers);
                this.is2DEnabled = needs2D;

                RenderSystem.recordRenderCall(() -> {
                    for (int src : this.alSources) {
                        if (AL10.alIsSource(src)) {
                            AL10.alSourceStop(src);
                            AL10.alSourcei(src, AL10.AL_BUFFER, 0);
                            AL10.alDeleteSources(src);
                        }
                    }
                    this.alSources.clear();

                    int totalSources = (this.is2DEnabled ? 1 : 0) + newSpeakers.size();
                    for (int i = 0; i < totalSources; i++) {
                        int src = AL10.alGenSources();
                        AL10.alSourcef(src, AL10.AL_GAIN, 1.0F);
                        AL10.alSourcef(src, AL10.AL_PITCH, Math.clamp(this.playbackSpeed, 0.1F, 2.0F));
                        this.alSources.add(src);
                    }

                    this.freeBuffers.clear();
                    for (int b : this.alBuffers) this.freeBuffers.add(b);
                    if (this.audioReadBuffer != null) this.audioReadBuffer.clear();
                });
            }
        }

        public synchronized void updateFrame() {
            if (this.isFailed || this.isClosed || this.nativeHandle == 0L || !this.isRegistered || !this.isInitialized || this.pixelBuffer == null) {
                return;
            }
            NativeVideoAPI api = NativeVideoAPI.getInstance();
            if (api == null) return;

            long currentTime = System.currentTimeMillis();
            long timeSinceLast = currentTime - this.lastRenderTime;

            updateAudioSourcesIfNeeded();

            if (timeSinceLast > 500L && this.hasAudio && !this.alSources.isEmpty()) {
                this.audioReadBuffer.clear();
                while (api.fetchAudioData(this.nativeHandle, this.audioReadBuffer, 65536) > 0) {
                    this.audioReadBuffer.clear();
                }
                for (int src : this.alSources) {
                    if (AL10.alIsSource(src)) {
                        AL10.alGetError();
                        AL10.alSourceStop(src);
                        AL10.alSourcei(src, AL10.AL_BUFFER, 0);
                    }
                }
                this.freeBuffers.clear();
                for (int b : this.alBuffers) this.freeBuffers.add(b);
            }

            try {
                if (api.checkNewFrame(this.nativeHandle) == 1) {
                    this.pixelBuffer.clear();
                    if (api.copyVideoFrame(this.nativeHandle, this.pixelBuffer) == 1) {
                        RenderSystem.bindTexture(this.getId());
                        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.getId());
                        GL21.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
                        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
                        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
                        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
                        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
                        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, this.width, this.height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, this.pixelBuffer);
                    }
                }
            } catch (Exception ignored) {}

            if (this.hasAudio && !this.isPaused && this.playbackSpeed > 0.0F && this.audioReadBuffer != null) {
                if (this.alSources.isEmpty()) {
                    while (api.fetchAudioData(this.nativeHandle, this.audioReadBuffer, 16384) > 0) {
                        this.audioReadBuffer.clear();
                    }
                } else {
                    try {
                        AL10.alGetError();
                        Minecraft mc = Minecraft.getInstance();
                        int sourceIndex = 0;

                        if (this.is2DEnabled && sourceIndex < this.alSources.size()) {
                            int src = this.alSources.get(sourceIndex++);
                            AL10.alSourcei(src, AL10.AL_SOURCE_RELATIVE, 1);
                            AL10.alSource3f(src, AL10.AL_POSITION, 0.0f, 0.0f, 0.0f);
                            AL10.alSourcef(src, AL10.AL_GAIN, 1.0f);
                            AL10.alSourcef(src, AL10.AL_ROLLOFF_FACTOR, 2.0F);
                            AL10.alSourcei(src, AL11.AL_DISTANCE_MODEL, AL11.AL_LINEAR_DISTANCE_CLAMPED);
                        }

                        for (int i = 0; i < this.customSpeakers.size() && sourceIndex < this.alSources.size(); i++) {
                            int src = this.alSources.get(sourceIndex++);
                            BlockPos sp = this.customSpeakers.get(i);
                            AL10.alSourcei(src, AL10.AL_SOURCE_RELATIVE, 0);

                            float maxDist = (mc.level != null && externalContext != null)
                                    ? externalContext.getSpeakerMaxDistance(sp) : 10.0f;

                            AL10.alSource3f(src, AL10.AL_POSITION, sp.getX() + 0.5f, sp.getY() + 0.5f, sp.getZ() + 0.5f);

                            float gain = 0.0f;
                            if (maxDist > 0.0f && mc.player != null) {
                                double distance = Math.sqrt(mc.player.blockPosition().distSqr(sp));
                                if (distance <= maxDist) gain = 1.0f - (float) (distance / maxDist);
                            }
                            AL10.alSourcef(src, AL10.AL_GAIN, gain);
                            AL10.alSourcef(src, AL10.AL_ROLLOFF_FACTOR, 2.0F);
                            AL10.alSourcei(src, AL11.AL_DISTANCE_MODEL, AL11.AL_LINEAR_DISTANCE_CLAMPED);
                        }

                        int minProcessed = Integer.MAX_VALUE;
                        for (int src : this.alSources) {
                            int processed = AL10.alGetSourcei(src, AL10.AL_BUFFERS_PROCESSED);
                            if (processed < minProcessed) minProcessed = processed;
                        }

                        for (int p = 0; p < minProcessed; p++) {
                            int unqueued = 0;
                            for (int src : this.alSources) unqueued = AL10.alSourceUnqueueBuffers(src);
                            if (unqueued != 0) this.freeBuffers.add(unqueued);
                        }

                        int chunkSize = 16384;
                        int frameSize = this.audioChannels * 2;

                        while (!this.freeBuffers.isEmpty()) {
                            this.audioReadBuffer.clear();
                            int readBytes = api.fetchAudioData(this.nativeHandle, this.audioReadBuffer, chunkSize);
                            if (readBytes <= 0) break;

                            int validBytes = readBytes - readBytes % frameSize;
                            if (validBytes > 0) {
                                this.audioReadBuffer.limit(validBytes);
                                int bufferId = this.freeBuffers.poll();
                                AL10.alBufferData(bufferId, this.alFormat, this.audioReadBuffer, this.audioSampleRate);
                                for (int src : this.alSources) AL10.alSourceQueueBuffers(src, bufferId);
                            }
                        }

                        for (int src : this.alSources) {
                            int state = AL10.alGetSourcei(src, AL10.AL_SOURCE_STATE);
                            int queued = AL10.alGetSourcei(src, AL10.AL_BUFFERS_QUEUED);
                            if (state != AL10.AL_PLAYING && queued > 0) AL10.alSourcePlay(src);
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        @Override
        public void close() {
            long handleToClose;
            synchronized (stateLock) {
                if (this.isClosed) return;
                this.isClosed = true;
                handleToClose = this.nativeHandle;
                this.nativeHandle = 0L;
                this.isFailed = true;
            }

            NativeVideoAPI api = NativeVideoAPI.getInstance();
            List<Integer> srcs;
            int[] bufs;
            synchronized (this) {
                srcs = new ArrayList<>(this.alSources);
                bufs = this.alBuffers;
                this.alSources.clear();
            }

            RenderSystem.recordRenderCall(() -> {
                try {
                    for (int src : srcs) {
                        if (AL10.alIsSource(src)) {
                            AL10.alSourceStop(src);
                            AL10.alSourcei(src, AL10.AL_BUFFER, 0);
                            AL10.alDeleteSources(src);
                        }
                    }
                    for (int b : bufs) {
                        if (b != 0 && AL10.alIsBuffer(b)) {
                            AL10.alDeleteBuffers(b);
                        }
                    }
                } catch (Exception ignored) {}
            });

            if (handleToClose != 0L && api != null) {
                MEDIA_IO_EXECUTOR.submit(() -> {
                    try {
                        api.closeVideo(handleToClose);
                    } catch (Exception ignored) {}
                });
            }
            super.close();
        }
    }

    /**
     * クライアント側で非同期ロードされた外部画像の解像度を保持し、
     * サイズ判明時にレイアウトの自動リフローをトリガーするレジストリ。
     */
    public static class ClientImageRegistry {

        private static final int MAX_SIZES = 2000;
        private static final Map<String, int[]> SIZES = Collections.synchronizedMap(
                new LinkedHashMap<String, int[]>(512, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, int[]> eldest) {
                        return size() > MAX_SIZES;
                    }
                }
        );

        private static final List<WeakReference<Runnable>> REFLOW_LISTENERS = new CopyOnWriteArrayList<>();

        public static final IntrinsicSizeResolver RESOLVER_ADAPTER = ClientImageRegistry::getSize;

        public static void setSize(String url, int width, int height) {
            int[] existing = SIZES.get(url);
            if (existing != null && existing[0] == width && existing[1] == height) return;

            SIZES.put(url, new int[]{width, height});

            REFLOW_LISTENERS.removeIf(ref -> {
                Runnable listener = ref.get();
                if (listener == null) {
                    return true;
                } else {
                    listener.run();
                    return false;
                }
            });
        }

        public static int[] getSize(String url) {
            int[] known = SIZES.get(url);
            if (known != null) return known;
            // data: とリソースパックの SVG は同期で読めるので、レイアウトの途中でも寸法が決まる（再レイアウトは要らない）
            if (SvgImage.looksLikeSvg(url) && !url.startsWith("http://") && !url.startsWith("https://")) {
                return SvgSources.intrinsicSize(url);
            }
            return null;
        }

        /**
         * 画像のサイズ確定時に自動リフローを呼び出すリスナーを登録する。
         * <p>
         * リスナーは弱参照（{@link WeakReference}）で保持される。
         *
         * @param listener サイズ確定時に実行されるコールバック
         */
        public static void addReflowListener(Runnable listener) {
            boolean exists = false;
            for (WeakReference<Runnable> ref : REFLOW_LISTENERS) {
                if (ref.get() == listener) {
                    exists = true;
                    break;
                }
            }

            if (!exists) {
                REFLOW_LISTENERS.add(new WeakReference<>(listener));
            }
        }

        /**
         * 画像のサイズ確定時に自動リフローを呼び出すリスナーを登録解除する。
         * <p>
         * 画面やレンダラーの破棄時にリスナーを明示的に解除するための差し込み口。
         *
         * @param listener 解除するリスナー
         */
        public static void removeReflowListener(Runnable listener) {
            REFLOW_LISTENERS.removeIf(ref -> ref.get() == null || ref.get() == listener);
        }
    }
}