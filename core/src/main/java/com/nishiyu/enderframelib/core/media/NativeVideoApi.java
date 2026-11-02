package com.nishiyu.enderframelib.core.media;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.WString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Locale;

/**
 * 動画デコーダ（{@code lunex_video.dll}）の JNA 定義。Minecraft に依存しない。
 *
 * <p>制約: 同梱のデコーダは Windows x86_64 用のみ。それ以外では {@link #shared} が null を返し、動画は再生されない。
 */
public interface NativeVideoApi extends Library {

    void setVideoProxyPort(int port);
    long initVideo(WString url);
    int getVideoWidth(long handle);
    int getVideoHeight(long handle);
    int checkNewFrame(long handle);
    int copyVideoFrame(long handle, ByteBuffer dst);
    int copyNewVideoFrame(long handle, ByteBuffer dst);
    double getVideoDuration(long handle);
    double getVideoPosition(long handle);
    void seekVideo(long handle, double seconds);
    void setVideoPaused(long handle, int paused);
    int isVideoPaused(long handle);
    void setVideoSpeed(long handle, float speed);
    void setVideoLoop(long handle, int loop);
    int hasAudio(long handle);
    int getAudioSampleRate(long handle);
    int getAudioChannels(long handle);
    int fetchAudioData(long handle, ByteBuffer dst, int max);
    void clearAudioBuffer(long handle);
    void closeVideo(long handle);

    /**
     * 共有のデコーダ。初回だけ同梱の DLL を {@code libDir} へ展開して読み込む。使えなければ（非対応の環境・読み込み失敗）null。
     */
    static NativeVideoApi shared(File libDir) {
        return Loader.shared(libDir);
    }

    /** 読み込みの実装（インターフェースの公開面に出さないための入れ子）。 */
    final class Loader {
        private static final Logger LOGGER = LoggerFactory.getLogger(NativeVideoApi.class);
        private static NativeVideoApi instance;
        private static boolean tried;

        private Loader() {}

        static synchronized NativeVideoApi shared(File libDir) {
            if (!tried) {
                tried = true;
                instance = load(libDir);
            }
            return instance;
        }

        private static NativeVideoApi load(File libDir) {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            boolean x64 = (arch.contains("64") || arch.contains("amd64")) && !arch.contains("aarch64") && !arch.contains("arm");
            if (!os.contains("win") || !x64) {
                LOGGER.warn("Native video playback is not supported on this platform: os.name='{}', os.arch='{}' (requires Windows x86_64)", os, arch);
                return null;
            }
            try {
                if (!libDir.exists()) libDir.mkdirs();
                File lib = new File(libDir, "lunex_video.dll");
                byte[] bundled = null;
                try (InputStream in = NativeVideoApi.class.getResourceAsStream("/assets/enderframelib/natives/lunex_video.dll")) {
                    if (in != null) bundled = in.readAllBytes();
                } catch (Exception e) {
                    LOGGER.warn("Failed to read embedded native library: {}", e.getMessage());
                }
                if (bundled != null) {
                    boolean copy = true;
                    if (lib.exists()) {
                        try {
                            copy = !Arrays.equals(bundled, Files.readAllBytes(lib.toPath()));
                        } catch (Exception ignored) {
                        }
                    }
                    if (copy) {
                        try {
                            Files.write(lib.toPath(), bundled);
                        } catch (Exception e) {
                            LOGGER.warn("Could not write native library to {}: {}", lib.getAbsolutePath(), e.getMessage());
                        }
                    }
                }
                NativeLibrary.addSearchPath("lunex_video", libDir.getAbsolutePath());
                return Native.load("lunex_video", NativeVideoApi.class);
            } catch (Throwable t) {
                LOGGER.warn("Failed to load native video library: {}", t.getMessage());
                return null;
            }
        }
    }
}
