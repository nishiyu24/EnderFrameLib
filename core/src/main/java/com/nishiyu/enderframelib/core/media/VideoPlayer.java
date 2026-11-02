package com.nishiyu.enderframelib.core.media;

import com.nishiyu.enderframelib.core.net.MediaUrls;
import com.nishiyu.enderframelib.core.net.SafeHttpClient;
import com.nishiyu.enderframelib.core.net.VideoStreamServer;
import com.nishiyu.enderframelib.core.net.YtDlpResolver;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.sun.jna.WString;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/**
 * {@code <video>} の再生（映像）。Minecraft・OpenGL・OpenAL に依存せず、フレームを {@link Pixmap} に書く。
 *
 * <p>初期化（リレーの登録とデコーダの起動）は {@code executor} 上で行い、描画スレッドは {@link #update()} を呼んで
 * 新しいフレームを取り込む。外部 URL はデコーダへ直接渡さず、{@link VideoStreamServer} の検証済みループバック経由にする。
 * 音声は再生しない（デコーダの音声バッファは、止まらないよう読み捨てる）。
 */
public final class VideoPlayer implements AutoCloseable {

    private final NativeVideoApi api;
    private final String url;
    private final Object lock = new Object();
    private final ByteBuffer audioDrain = ByteBuffer.allocateDirect(16384).order(ByteOrder.nativeOrder());

    private long handle;
    private Runnable relay; // リレーを閉じる処理
    private ByteBuffer pixels;
    private Pixmap frame;
    private boolean hasAudio;
    private boolean attributesApplied;
    private boolean looping;

    private volatile int width;
    private volatile int height;
    private volatile boolean initialized;
    private volatile boolean failed;
    private volatile boolean closed;

    /**
     * @param ytDlpEnabled 呼ぶたびに読む YouTube（yt-dlp）の許可
     * @param ytDlpDir     yt-dlp の置き場
     * @param executor     初期化を走らせる先（ネットワーク I/O を含むので描画スレッドでは呼ばない）
     */
    public VideoPlayer(NativeVideoApi api, SafeHttpClient http, String url, long maxBytes,
                       BooleanSupplier ytDlpEnabled, File ytDlpDir, Executor executor) {
        this.api = api;
        this.url = url;
        if (api == null) {
            failed = true;
            return;
        }
        executor.execute(() -> init(http, maxBytes, ytDlpEnabled, ytDlpDir));
    }

    private void init(SafeHttpClient http, long maxBytes, BooleanSupplier ytDlpEnabled, File ytDlpDir) {
        Runnable rel = null;
        long h = 0L;
        try {
            String target = url;
            int at = target.lastIndexOf('@');
            if (at != -1) target = target.substring(0, at);
            target = MediaUrls.convertToDirectLink(target);
            String stream = MediaUrls.isYouTubeUrl(target)
                    ? YtDlpResolver.resolve(target, http::isUrlAllowed, ytDlpDir, http, ytDlpEnabled)
                    : target;
            if (stream == null || stream.isBlank()) {
                failed = true;
                return;
            }
            var handle0 = VideoStreamServer.register(http, stream, maxBytes);
            rel = handle0::close;
            synchronized (lock) {
                if (closed) {
                    rel.run();
                    return;
                }
                relay = rel;
            }
            // initVideo はネットワーク I/O を含むので、ロックを持ったまま呼ばない
            api.setVideoProxyPort(VideoStreamServer.port());
            h = api.initVideo(new WString(handle0.loopbackUrl()));
            synchronized (lock) {
                if (closed || h == 0L) {
                    if (h != 0L) api.closeVideo(h);
                    relay = null;
                    rel.run();
                    if (!closed) failed = true;
                    return;
                }
                handle = h;
                api.setVideoLoop(h, looping ? 1 : 0);
                int w = api.getVideoWidth(h), ht = api.getVideoHeight(h);
                hasAudio = api.hasAudio(h) == 1;
                if (w > 0 && ht > 0 && w <= Pixmap.MAX_DIMENSION && ht <= Pixmap.MAX_DIMENSION) {
                    pixels = ByteBuffer.allocateDirect(w * ht * 4).order(ByteOrder.nativeOrder());
                    frame = new Pixmap(w, ht);
                    width = w;
                    height = ht;
                    initialized = true;
                } else if (!hasAudio) {
                    failed = true;
                }
            }
        } catch (Throwable t) {
            failed = true;
            synchronized (lock) {
                if (handle != 0L) {
                    api.closeVideo(handle);
                    handle = 0L;
                } else if (h != 0L) {
                    api.closeVideo(h);
                }
                if (relay != null) relay.run();
                else if (rel != null) rel.run();
                relay = null;
            }
        }
    }

    public String url() {
        return url;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public boolean isFailed() {
        return failed;
    }

    public boolean isClosed() {
        return closed;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** HTML の {@code loop}。変わったときだけデコーダへ伝える。 */
    public void applyAttributes(boolean loop) {
        synchronized (lock) {
            if (attributesApplied && looping == loop) return;
            attributesApplied = true;
            looping = loop;
            if (handle != 0L) api.setVideoLoop(handle, loop ? 1 : 0);
        }
    }

    public void setPaused(boolean paused) {
        synchronized (lock) {
            if (handle != 0L) api.setVideoPaused(handle, paused ? 1 : 0);
        }
    }

    public double currentTime() {
        synchronized (lock) {
            return handle != 0L ? Math.max(0.0, api.getVideoPosition(handle)) : 0.0;
        }
    }

    public double duration() {
        synchronized (lock) {
            return handle != 0L ? Math.max(0.0, api.getVideoDuration(handle)) : 0.0;
        }
    }

    public void seekTo(double seconds) {
        synchronized (lock) {
            if (handle == 0L) return;
            double d = api.getVideoDuration(handle);
            double t = Math.max(0.0, seconds);
            api.seekVideo(handle, d > 0.0 ? Math.min(t, d) : t);
        }
    }

    /**
     * 新しいフレームが届いていれば取り込む。描画スレッドから毎フレーム呼ぶ。
     *
     * @return 表示用の画素（使い回し。更新したときは {@code markChanged()} 済み）。まだ映像が無ければ null
     */
    public Pixmap update() {
        synchronized (lock) {
            if (closed || failed || handle == 0L) return null;
            if (hasAudio) {
                // 音声は鳴らさないが、デコーダが音声バッファの空きを待って止まらないよう読み捨てる
                while (true) {
                    audioDrain.clear();
                    if (api.fetchAudioData(handle, audioDrain, audioDrain.capacity()) <= 0) break;
                }
            }
            if (!initialized) return null;
            pixels.clear();
            if (api.copyNewVideoFrame(handle, pixels) == 1) {
                int[] dst = frame.pixels();
                for (int i = 0, n = width * height; i < n; i++) {
                    int o = i * 4;
                    // RGBA のバイト列 → 不透明の ARGB（動画は不透明）
                    dst[i] = 0xFF000000 | ((pixels.get(o) & 0xFF) << 16) | ((pixels.get(o + 1) & 0xFF) << 8) | (pixels.get(o + 2) & 0xFF);
                }
                frame.markChanged();
            }
            return frame;
        }
    }

    @Override
    public void close() {
        long h;
        Runnable rel;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            h = handle;
            handle = 0L;
            rel = relay;
            relay = null;
        }
        try {
            if (h != 0L) api.closeVideo(h);
        } finally {
            if (rel != null) rel.run();
        }
    }
}
