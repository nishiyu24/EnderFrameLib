package com.nishiyu.enderframelib.client.renderer;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.common.ServerImageRegistry;
import com.nishiyu.enderframelib.core.model.UIElement;
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
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL21;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class WebMediaManager {

    public interface IExternalVideoContext {
        boolean isYouTubeSupportEnabled();
        List<String> getActiveSessionIdsFromHotbar();
        boolean isScreenOrSpeaker(BlockPos pos);
        float getSpeakerMaxDistance(BlockPos pos);
    }

    public static IExternalVideoContext externalContext = null;

    // ==========================================
    // セキュリティ・ガードレール設定 (大手MODパック基準)
    // ==========================================
    public static boolean allowExternalMedia = true;
    public static boolean blockPrivateNetworkAccess = true;
    public static long maxImageDownloadBytes = 10L * 1024L * 1024L; // 最大10MB
    public static final Set<String> ALLOWED_DOMAINS = ConcurrentHashMap.newKeySet();

    public static boolean isUrlSafe(String urlString) {
        if (!allowExternalMedia || urlString == null || urlString.isEmpty()) return false;
        try {
            URI uri = URI.create(urlString.trim());
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("https") && !scheme.equalsIgnoreCase("http"))) {
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
                if (lowerHost.equals("localhost") || lowerHost.endsWith(".local") || lowerHost.endsWith(".internal")) {
                    return false;
                }
                InetAddress addr = InetAddress.getByName(host);
                if (addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress() || addr.isAnyLocalAddress()) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ==========================================
    // 1. Web Image Cache System
    // ==========================================
    private static final ConcurrentHashMap<String, ImageInfo> IMG_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> IMG_DOWNLOADING = new ConcurrentHashMap<>();

    public record ImageInfo(ResourceLocation textureId, int width, int height) {}

    public static ImageInfo getImage(String urlString) {
        if (urlString == null || urlString.isEmpty() || !allowExternalMedia) return null;
        if (IMG_CACHE.containsKey(urlString)) return IMG_CACHE.get(urlString);
        if (!IMG_DOWNLOADING.containsKey(urlString)) {
            IMG_DOWNLOADING.put(urlString, true);
            downloadImageAsync(urlString);
        }
        return null;
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
        Thread thread = new Thread(() -> {
            try {
                String directUrlString = convertToDirectLink(originalUrl);
                if (!isUrlSafe(directUrlString)) {
                    IMG_DOWNLOADING.remove(originalUrl);
                    return;
                }

                HttpURLConnection conn = (HttpURLConnection) new URL(directUrlString).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");

                int status = conn.getResponseCode();
                if (status == HttpURLConnection.HTTP_MOVED_TEMP || status == HttpURLConnection.HTTP_MOVED_PERM || status == HttpURLConnection.HTTP_SEE_OTHER) {
                    String newUrl = conn.getHeaderField("Location");
                    if (!isUrlSafe(newUrl)) {
                        IMG_DOWNLOADING.remove(originalUrl);
                        return;
                    }
                    conn = (HttpURLConnection) new URL(newUrl).openConnection();
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0");
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

                    Minecraft.getInstance().execute(() -> {
                        DynamicTexture texture = new DynamicTexture(nativeImage);
                        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("enderframelib", "webimg_" + Math.abs(originalUrl.hashCode()));
                        Minecraft.getInstance().getTextureManager().register(id, texture);

                        IMG_CACHE.put(originalUrl, new ImageInfo(id, imgW, imgH));
                        IMG_DOWNLOADING.remove(originalUrl);
                        // 画像サイズ判明時に ServerImageRegistry へ登録し、待機中の全セッションを自動リフロー
                        ServerImageRegistry.setSize(originalUrl, imgW, imgH);
                    });
                }
            } catch (Exception e) {
                IMG_DOWNLOADING.remove(originalUrl);
            }
        });
        thread.setName("EnderFrameLib-WebImageDownloader");
        thread.setDaemon(true);
        thread.start();
    }

    // ==========================================
    // 2. Video Player System
    // ==========================================
    private static final Map<String, VideoTexture> ACTIVE_VIDEOS = new ConcurrentHashMap<>();
    private static final Map<BlockPos, List<BlockPos>> SCREEN_AUDIO_LINKS = new ConcurrentHashMap<>();
    private static final Map<String, YtDlpCache> YT_DLP_CACHE = new ConcurrentHashMap<>();
    private static boolean ytDlpUpdated = false;

    static {
        try {
            NeoForge.EVENT_BUS.addListener(WebMediaManager::onClientTick);
            NeoForge.EVENT_BUS.addListener(WebMediaManager::onLevelUnload);
        } catch (Throwable ignored) {}
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
        return ACTIVE_VIDEOS.computeIfAbsent(url, (k) -> {
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

    public static void cleanup() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isPaused() || mc.level == null || mc.player == null) return;

        long now = System.currentTimeMillis();
        List<String> activeIps = new ArrayList<>();

        if (externalContext != null) {
            activeIps.addAll(externalContext.getActiveSessionIdsFromHotbar());
        }

        for (String sessionKey : activeIps) {
            List<UIElement> els = ClientWebManager.getElements(sessionKey);
            if (els != null) {
                for (UIElement el : els) {
                    if ("video".equals(el.type()) && el.text() != null) {
                        VideoTexture videoTex = ACTIVE_VIDEOS.get(el.text());
                        if (videoTex != null) {
                            videoTex.keepAlive2D(sessionKey);
                            videoTex.updateFrame();
                        }
                    }
                }
            }
        }

        ACTIVE_VIDEOS.entrySet().removeIf((entry) -> {
            VideoTexture tex = entry.getValue();
            if (tex.forceClose) {
                tex.close();
                return true;
            }

            tex.activeIps.removeIf(sessionKey -> {
                List<UIElement> els = ClientWebManager.getElements(sessionKey);
                if (els == null || els.isEmpty()) return true;
                for (UIElement el : els) {
                    if ("video".equals(el.type()) && tex.url.equals(el.text())) return false;
                }
                return true;
            });

            tex.active2DAudioIps.entrySet().removeIf(e -> (now - e.getValue()) > 1500L);
            tex.activeAudioPositions.entrySet().removeIf(e -> {
                BlockPos pos = e.getKey();
                if (!mc.level.isLoaded(pos)) return false;
                if (externalContext != null && externalContext.isScreenOrSpeaker(pos)) {
                    List<UIElement> els = ClientWebManager.getElements("block:" + pos.getX() + "," + pos.getY() + "," + pos.getZ());
                    if (els != null) {
                        for (UIElement el : els) {
                            if ("video".equals(el.type()) && tex.url.equals(el.text())) {
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
    }

    private static String resolveYouTubeUrlViaYtDlp(String youtubeUrl) {
        if (!allowExternalMedia) return null;
        if (externalContext != null && !externalContext.isYouTubeSupportEnabled()) return null;

        long now = System.currentTimeMillis();
        YtDlpCache cache = YT_DLP_CACHE.get(youtubeUrl);
        if (cache != null) {
            if (cache.resolvedUrl != null && (now - cache.timestamp < 3600000L)) return cache.resolvedUrl;
            else if (cache.resolvedUrl == null && (now - cache.timestamp < 60000L)) return null;
        }

        try {
            String osName = System.getProperty("os.name").toLowerCase();
            String ytDlpFileName = "yt-dlp";
            String downloadUrlStr = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp";
            if (osName.contains("win")) {
                ytDlpFileName = "yt-dlp.exe";
                downloadUrlStr = downloadUrlStr + ".exe";
            } else if (osName.contains("mac")) {
                ytDlpFileName = "yt-dlp_macos";
                downloadUrlStr = downloadUrlStr + "_macos";
            }

            File dir = new File(Minecraft.getInstance().gameDirectory, "enderframelib");
            if (!dir.exists()) dir.mkdirs();

            File ytDlpFile = new File(dir, ytDlpFileName);
            if (!ytDlpFile.exists()) {
                URL downloadUrl = new URL(downloadUrlStr);
                HttpURLConnection conn = (HttpURLConnection) downloadUrl.openConnection();
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                conn.setInstanceFollowRedirects(true);
                try (InputStream in = conn.getInputStream()) {
                    Files.copy(in, ytDlpFile.toPath(), new CopyOption[]{StandardCopyOption.REPLACE_EXISTING});
                }
                ytDlpFile.setExecutable(true);
            }

            if (!ytDlpUpdated && ytDlpFile.exists()) {
                ytDlpUpdated = true;
                try {
                    new ProcessBuilder(ytDlpFile.getAbsolutePath(), "-U").start().waitFor();
                } catch (Exception ignored) {}
            }

            Process process = new ProcessBuilder(
                    ytDlpFile.getAbsolutePath(), "-f", "22/18/b",
                    "--extractor-args", "youtube:player_client=android",
                    "--no-warnings", "-g", youtubeUrl
            ).start();
            Scanner scanner = new Scanner(process.getInputStream(), StandardCharsets.UTF_8);
            String rawUrl = null;

            while (scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (line.startsWith("http")) {
                    rawUrl = line;
                    break;
                }
            }
            scanner.close();
            process.waitFor();

            if (rawUrl != null && rawUrl.startsWith("http")) {
                YT_DLP_CACHE.put(youtubeUrl, new YtDlpCache(rawUrl, System.currentTimeMillis()));
                return rawUrl;
            }
            YT_DLP_CACHE.put(youtubeUrl, new YtDlpCache(null, System.currentTimeMillis()));
        } catch (Exception e) {
            YT_DLP_CACHE.put(youtubeUrl, new YtDlpCache(null, System.currentTimeMillis()));
        }
        return null;
    }

    private static String resolveDirectStreamUrl(String targetUrl) {
        try {
            if (!targetUrl.contains("youtube.com/watch") && !targetUrl.contains("youtu.be/")) {
                if (!targetUrl.startsWith("file://") && !targetUrl.contains(":\\") && !targetUrl.startsWith("/") && targetUrl.contains("://")) {
                    if (!isUrlSafe(targetUrl)) return null;
                    URL url = new URL(targetUrl);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                    conn.setInstanceFollowRedirects(false);
                    int status = conn.getResponseCode();
                    if (status == 302 || status == 301 || status == 303) {
                        String redirectUrl = conn.getHeaderField("Location");
                        if (redirectUrl != null && !redirectUrl.isEmpty() && isUrlSafe(redirectUrl)) {
                            return resolveDirectStreamUrl(redirectUrl);
                        }
                        return null;
                    }
                    return targetUrl;
                } else {
                    return null;
                }
            } else {
                return resolveYouTubeUrlViaYtDlp(targetUrl);
            }
        } catch (Exception var5) {
            return null;
        }
    }

    public interface NativeVideoAPI extends Library {
        NativeVideoAPI INSTANCE = loadLibrary();
        static NativeVideoAPI loadLibrary() {
            try {
                File customLibDir = new File(Minecraft.getInstance().gameDirectory, "enderframelib");
                if (!customLibDir.exists()) customLibDir.mkdirs();

                String osName = System.getProperty("os.name").toLowerCase();
                String libName = "lunex_video";
                String libFileName;
                if (osName.contains("win")) libFileName = libName + ".dll";
                else if (osName.contains("mac")) libFileName = "lib" + libName + ".dylib";
                else libFileName = "lib" + libName + ".so";

                File extractedLib = new File(customLibDir, libFileName);

                try (InputStream in = WebMediaManager.class.getResourceAsStream("/assets/enderframelib/natives/" + libFileName)) {
                    if (in != null) Files.copy(in, extractedLib.toPath(), new CopyOption[]{StandardCopyOption.REPLACE_EXISTING});
                } catch (Exception ignored) {}

                NativeLibrary.addSearchPath(libName, customLibDir.getAbsolutePath());
                return (NativeVideoAPI) Native.load(libName, NativeVideoAPI.class);
            } catch (Throwable t) {
                return null;
            }
        }
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

    private static class YtDlpCache {
        String resolvedUrl;
        long timestamp;
        YtDlpCache(String url, long time) { this.resolvedUrl = url; this.timestamp = time; }
    }

    public static class VideoTexture extends AbstractTexture {
        public final String url;
        public final ResourceLocation textureLocation;
        public final Map<BlockPos, Long> activeAudioPositions = new ConcurrentHashMap<>();
        public final Map<String, Long> active2DAudioIps = new ConcurrentHashMap<>();
        public final Set<String> activeIps = ConcurrentHashMap.newKeySet();

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
        private int alFormat = 4353;
        private ByteBuffer pixelBuffer = null;
        private ByteBuffer audioReadBuffer = null;

        public VideoTexture(String url) {
            this.url = url;
            this.lastRenderTime = System.currentTimeMillis();
            this.textureLocation = ResourceLocation.fromNamespaceAndPath("enderframelib", "video_" + Math.abs(url.hashCode()));
            if (NativeVideoAPI.INSTANCE != null) {
                new Thread(() -> {
                    try {
                        String targetUrl = url;
                        int atIndex = targetUrl.lastIndexOf("@");
                        if (atIndex != -1) targetUrl = targetUrl.substring(0, atIndex);

                        if (targetUrl.contains("drive.google.com/file/d/")) {
                            String[] parts = targetUrl.split("/file/d/");
                            if (parts.length > 1) {
                                String id = parts[1].split("/")[0];
                                targetUrl = "https://drive.google.com/uc?export=download&id=" + id;
                            }
                        }

                        targetUrl = WebMediaManager.resolveDirectStreamUrl(targetUrl);
                        if (targetUrl == null) { this.isFailed = true; return; }

                        if (!targetUrl.contains(".mp4") && targetUrl.startsWith("http") && !targetUrl.contains("googlevideo.com")) {
                            targetUrl = targetUrl + "#.mp4";
                        }

                        if (this.isClosed) return;

                        long handle = NativeVideoAPI.INSTANCE.initVideo(new WString(targetUrl));
                        if (this.isClosed) {
                            NativeVideoAPI.INSTANCE.closeVideo(handle);
                            return;
                        }

                        if (handle != 0L) {
                            this.nativeHandle = handle;
                            this.width = NativeVideoAPI.INSTANCE.getVideoWidth(handle);
                            this.height = NativeVideoAPI.INSTANCE.getVideoHeight(handle);
                            if (this.width > 0 && this.height > 0) {
                                this.pixelBuffer = ByteBuffer.allocateDirect(this.width * this.height * 4).order(ByteOrder.nativeOrder());
                                RenderSystem.recordRenderCall(() -> {
                                    RenderSystem.bindTexture(this.getId());
                                    TextureUtil.prepareImage(this.getId(), this.width, this.height);
                                    this.setFilter(true, false);
                                    this.isInitialized = true;
                                });
                            }

                            if (NativeVideoAPI.INSTANCE.hasAudio(handle) == 1) {
                                this.hasAudio = true;
                                this.audioSampleRate = NativeVideoAPI.INSTANCE.getAudioSampleRate(handle);
                                this.audioChannels = NativeVideoAPI.INSTANCE.getAudioChannels(handle);
                                this.alFormat = this.audioChannels == 1 ? 4353 : 4355;
                                this.audioReadBuffer = ByteBuffer.allocateDirect(65536).order(ByteOrder.nativeOrder());

                                RenderSystem.recordRenderCall(() -> {
                                    try {
                                        AL10.alGenBuffers(this.alBuffers);
                                        for (int b : this.alBuffers) this.freeBuffers.add(b);
                                    } catch (Exception ignored) {}
                                });
                            }
                        } else {
                            this.isFailed = true;
                        }
                    } catch (Throwable t) {
                        this.isFailed = true;
                    }
                }, "Video-Init-Thread").start();
            } else {
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

        public void load(@NotNull ResourceManager manager) {}

        public void setVolume(float volume) {
            RenderSystem.recordRenderCall(() -> {
                for (int src : this.alSources) {
                    if (AL10.alIsSource(src)) AL10.alSourcef(src, 4106, Math.clamp(volume, 0.0F, 1.0F));
                }
            });
        }

        public void setPlaybackSpeed(float speed) {
            this.playbackSpeed = speed;
            if (this.nativeHandle != 0L && NativeVideoAPI.INSTANCE != null)
                NativeVideoAPI.INSTANCE.setVideoSpeed(this.nativeHandle, speed);

            RenderSystem.recordRenderCall(() -> {
                for (int src : this.alSources) {
                    if (AL10.alIsSource(src)) {
                        if (speed > 0.0F) {
                            AL10.alSourcef(src, 4099, Math.clamp(speed, 0.1F, 2.0F));
                            if (this.isPaused) AL10.alSourcePause(src);
                            else if (AL10.alGetSourcei(src, 4112) != 4114) AL10.alSourcePlay(src);
                        } else {
                            AL10.alSourcePause(src);
                        }
                    }
                }
            });
        }

        public void setPaused(boolean paused) {
            this.isPaused = paused;
            if (this.nativeHandle != 0L && NativeVideoAPI.INSTANCE != null)
                NativeVideoAPI.INSTANCE.setVideoPaused(this.nativeHandle, paused ? 1 : 0);

            RenderSystem.recordRenderCall(() -> {
                for (int src : this.alSources) {
                    if (AL10.alIsSource(src)) {
                        if (!paused && !(this.playbackSpeed <= 0.0F)) AL10.alSourcePlay(src);
                        else AL10.alSourcePause(src);
                    }
                }
            });
        }

        public void seekTo(double seconds) {
            if (this.nativeHandle != 0L && NativeVideoAPI.INSTANCE != null) {
                NativeVideoAPI.INSTANCE.seekVideo(this.nativeHandle, seconds);
                if (this.audioReadBuffer != null) this.audioReadBuffer.clear();

                RenderSystem.recordRenderCall(() -> {
                    for (int src : this.alSources) {
                        if (AL10.alIsSource(src)) {
                            AL10.alGetError();
                            AL10.alSourceStop(src);
                            AL10.alSourcei(src, 4105, 0);
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
                    for (BlockPos sp : linked) {
                        Minecraft mc = Minecraft.getInstance();
                        if (mc.level != null && externalContext != null && externalContext.isScreenOrSpeaker(sp)) {
                            if (!newSpeakers.contains(sp)) newSpeakers.add(sp);
                        }
                    }
                } else {
                    if (!newSpeakers.contains(pos)) newSpeakers.add(pos);
                }
            }

            boolean needsRebuild = this.is2DEnabled != needs2D;
            if (!needsRebuild) {
                if (this.customSpeakers.size() != newSpeakers.size()) {
                    needsRebuild = true;
                } else {
                    for (int i = 0; i < newSpeakers.size(); i++) {
                        if (!this.customSpeakers.get(i).equals(newSpeakers.get(i))) {
                            needsRebuild = true;
                            break;
                        }
                    }
                }
            }

            if (needsRebuild) {
                this.customSpeakers.clear();
                this.customSpeakers.addAll(newSpeakers);
                this.is2DEnabled = needs2D;

                RenderSystem.recordRenderCall(() -> {
                    for (int src : this.alSources) {
                        if (AL10.alIsSource(src)) {
                            AL10.alSourceStop(src);
                            AL10.alSourcei(src, 4105, 0);
                            AL10.alDeleteSources(src);
                        }
                    }
                    this.alSources.clear();

                    if (this.is2DEnabled) {
                        int src = AL10.alGenSources();
                        AL10.alSourcef(src, 4106, 1.0F);
                        AL10.alSourcef(src, 4099, Math.clamp(this.playbackSpeed, 0.1F, 2.0F));
                        this.alSources.add(src);
                    }

                    if (!newSpeakers.isEmpty()) {
                        for (int i = 0; i < newSpeakers.size(); i++) {
                            int src = AL10.alGenSources();
                            AL10.alSourcef(src, 4106, 1.0F);
                            AL10.alSourcef(src, 4099, Math.clamp(this.playbackSpeed, 0.1F, 2.0F));
                            this.alSources.add(src);
                        }
                    }

                    this.freeBuffers.clear();
                    for (int b : this.alBuffers) this.freeBuffers.add(b);
                    if (this.audioReadBuffer != null) this.audioReadBuffer.clear();
                });
            }
        }

        public synchronized void updateFrame() {
            if (!this.isFailed && !this.isClosed && this.nativeHandle != 0L && this.isRegistered && this.isInitialized && this.pixelBuffer != null) {
                long currentTime = System.currentTimeMillis();
                long timeSinceLast = currentTime - this.lastRenderTime;

                updateAudioSourcesIfNeeded();

                if (timeSinceLast > 500L && this.hasAudio && !this.alSources.isEmpty()) {
                    this.audioReadBuffer.clear();
                    while ((NativeVideoAPI.INSTANCE != null ? NativeVideoAPI.INSTANCE.fetchAudioData(this.nativeHandle, this.audioReadBuffer, 65536) : 0) > 0) {
                        this.audioReadBuffer.clear();
                    }
                    for (int src : this.alSources) {
                        if (AL10.alIsSource(src)) {
                            AL10.alGetError();
                            AL10.alSourceStop(src);
                            AL10.alSourcei(src, 4105, 0);
                        }
                    }
                    this.freeBuffers.clear();
                    for (int b : this.alBuffers) this.freeBuffers.add(b);
                }

                try {
                    if ((NativeVideoAPI.INSTANCE != null ? NativeVideoAPI.INSTANCE.checkNewFrame(this.nativeHandle) : 0) == 1) {
                        this.pixelBuffer.clear();
                        if (NativeVideoAPI.INSTANCE.copyVideoFrame(this.nativeHandle, this.pixelBuffer) == 1) {
                            RenderSystem.bindTexture(this.getId());
                            GL11.glBindTexture(3553, this.getId());
                            GL21.glBindBuffer(35052, 0);
                            GL11.glPixelStorei(3314, 0);
                            GL11.glPixelStorei(3316, 0);
                            GL11.glPixelStorei(3315, 0);
                            GL11.glPixelStorei(3317, 1);
                            GL11.glTexSubImage2D(3553, 0, 0, 0, this.width, this.height, 6408, 5121, this.pixelBuffer);
                        }
                    }
                } catch (Exception ignored) {}

                if (this.hasAudio && !this.isPaused && this.playbackSpeed > 0.0F && this.audioReadBuffer != null) {
                    if (this.alSources.isEmpty()) {
                        while ((NativeVideoAPI.INSTANCE != null ? NativeVideoAPI.INSTANCE.fetchAudioData(this.nativeHandle, this.audioReadBuffer, 16384) : 0) > 0) {
                            this.audioReadBuffer.clear();
                        }
                    } else {
                        try {
                            AL10.alGetError();
                            Minecraft mc = Minecraft.getInstance();
                            int sourceIndex = 0;

                            if (this.is2DEnabled && sourceIndex < this.alSources.size()) {
                                int src = this.alSources.get(sourceIndex++);
                                AL10.alSourcei(src, 514, 1);
                                AL10.alSource3f(src, 4100, 0.0f, 0.0f, 0.0f);
                                AL10.alSourcef(src, 4106, 1.0f);
                                AL10.alSourcef(src, 4128, 2.0F);
                                AL10.alSourcei(src, 53248, 53252);
                            }

                            for (int i = 0; i < this.customSpeakers.size() && sourceIndex < this.alSources.size(); i++) {
                                int src = this.alSources.get(sourceIndex++);
                                BlockPos sp = this.customSpeakers.get(i);
                                AL10.alSourcei(src, 514, 0);

                                float maxDist = 10.0f;
                                if (mc.level != null && externalContext != null) {
                                    maxDist = externalContext.getSpeakerMaxDistance(sp);
                                }

                                AL10.alSource3f(src, 4100, sp.getX() + 0.5f, sp.getY() + 0.5f, sp.getZ() + 0.5f);

                                float gain = 0.0f;
                                if (maxDist > 0.0f && mc.player != null) {
                                    double distance = Math.sqrt(mc.player.blockPosition().distSqr(sp));
                                    if (distance <= maxDist) gain = 1.0f - (float) (distance / maxDist);
                                }
                                AL10.alSourcef(src, 4106, gain);
                                AL10.alSourcef(src, 4128, 2.0F);
                                AL10.alSourcei(src, 53248, 53252);
                            }

                            int minProcessed = Integer.MAX_VALUE;
                            for (int src : this.alSources) {
                                int processed = AL10.alGetSourcei(src, 4118);
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
                                int readBytes = NativeVideoAPI.INSTANCE.fetchAudioData(this.nativeHandle, this.audioReadBuffer, chunkSize);
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
                                int state = AL10.alGetSourcei(src, 4112);
                                int queued = AL10.alGetSourcei(src, 4117);
                                if (state != 4114 && queued > 0) AL10.alSourcePlay(src);
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        }

        public synchronized void close() {
            if (!this.isClosed) {
                this.isClosed = true;
                long handleToClose = this.nativeHandle;
                this.nativeHandle = 0L;
                this.isFailed = true;

                if (!this.alSources.isEmpty()) {
                    List<Integer> srcs = new ArrayList<>(this.alSources);
                    int[] bufs = this.alBuffers;
                    this.alSources.clear();
                    RenderSystem.recordRenderCall(() -> {
                        try {
                            for (int src : srcs) {
                                if (AL10.alIsSource(src)) {
                                    AL10.alSourceStop(src);
                                    AL10.alSourcei(src, 4105, 0);
                                    AL10.alDeleteSources(src);
                                }
                            }
                            AL10.alDeleteBuffers(bufs);
                        } catch (Exception ignored) {}
                    });
                }

                Thread cleanupThread = new Thread(() -> {
                    if (handleToClose != 0L && NativeVideoAPI.INSTANCE != null) {
                        try {
                            NativeVideoAPI.INSTANCE.closeVideo(handleToClose);
                        } catch (Exception ignored) {}
                    }
                }, "Video-Cleanup-Thread");
                cleanupThread.setDaemon(true);
                cleanupThread.start();
                super.close();
            }
        }
    }
}