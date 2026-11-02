package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.core.media.NativeVideoApi;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** デコーダを偽物にして、窓（{@code ImageProvider.web()}）で動画の映像が Pixmap に届くことを確かめる。 */
class VideoWindowTest {

    static final class Fake {
        final List<String> calls = new ArrayList<>();
        final AtomicInteger frames = new AtomicInteger(1); // 届けるフレームの数
        final AtomicInteger audioChunks = new AtomicInteger(3);
        volatile int loop = -1;
        volatile int r = 255;

        NativeVideoApi api() {
            return (NativeVideoApi) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{NativeVideoApi.class},
                    (proxy, m, a) -> {
                        synchronized (calls) {
                            calls.add(m.getName());
                        }
                        switch (m.getName()) {
                            case "initVideo": return 7L;
                            case "getVideoWidth": return 2;
                            case "getVideoHeight": return 2;
                            case "hasAudio": return 1;
                            case "setVideoLoop": loop = (Integer) a[1]; return null;
                            case "fetchAudioData": return audioChunks.getAndUpdate(n -> Math.max(0, n - 1)) > 0 ? 100 : 0;
                            case "copyNewVideoFrame": {
                                if (frames.getAndUpdate(n -> Math.max(0, n - 1)) <= 0) return 0;
                                ByteBuffer b = (ByteBuffer) a[1];
                                for (int i = 0; i < 4; i++) {
                                    b.put(i * 4, (byte) r).put(i * 4 + 1, (byte) 0).put(i * 4 + 2, (byte) 0).put(i * 4 + 3, (byte) 255);
                                }
                                return 1;
                            }
                            default:
                                Class<?> t = m.getReturnType();
                                if (t == int.class) return 0;
                                if (t == long.class) return 0L;
                                if (t == double.class) return 0.0;
                                return null;
                        }
                    });
        }
    }

    private static DisplayItem video(String src, Map<String, String> events) {
        return new DisplayItem("v", "v", ElementType.VIDEO, 0, 0, 2, 2, src, 0, 0, events,
                0, 1f, 0, 0, 1f, 1f, 0, 0, null, null, null);
    }

    @Test
    void videoFramesReachThePixmap() throws Exception {
        Fake fake = new Fake();
        ImageProvider.Web web = new ImageProvider.Web(true).nativeVideo(fake::api);
        try {
            DisplayItem item = video("http://127.0.0.1:9/clip.mp4", Map.of("media-loop", "1"));
            ImageProvider.Image img = null;
            long end = System.currentTimeMillis() + 5000;
            while (img == null && System.currentTimeMillis() < end) {
                img = web.load(item, 2, 2);
                if (img == null) Thread.sleep(20);
            }
            assertNotNull(img, "初期化の後にフレームが返る");
            assertEquals(0xFFFF0000, img.pixmap().get(1, 1));
            assertEquals(2, img.cssW());
            assertEquals(1, fake.loop, "media-loop がデコーダへ伝わる");
            assertTrue(web.hasAnimation());
            assertEquals(0, fake.audioChunks.get(), "音声バッファは読み捨てられる");

            // 次のフレーム（緑にしたい: r を変える）。同じ Pixmap が書き換わり version が進む
            int v = img.pixmap().version();
            fake.r = 0;
            fake.frames.set(1);
            ImageProvider.Image again = web.load(item, 2, 2);
            assertSame(img.pixmap(), again.pixmap());
            assertTrue(again.pixmap().version() > v);
            assertEquals(0xFF000000, again.pixmap().get(0, 0));

            web.close();
            assertTrue(fake.calls.contains("closeVideo"));
            assertFalse(web.hasAnimation());
        } finally {
            web.close();
        }
    }

    @Test
    void unavailableDecoderDrawsNothing() {
        ImageProvider.Web web = new ImageProvider.Web(true).nativeVideo(() -> null);
        try {
            assertNull(web.load(video("http://127.0.0.1:9/clip.mp4", Map.of()), 2, 2));
            assertFalse(web.hasAnimation());
        } finally {
            web.close();
        }
    }

    @Test
    void localPathsAreNeverHandedToTheDecoder() {
        Fake fake = new Fake();
        ImageProvider.Web web = new ImageProvider.Web(true).nativeVideo(fake::api);
        try {
            assertNull(web.load(video("file:///c:/secret.mp4", Map.of()), 2, 2));
            assertNull(web.load(video("C:/clip.mp4", Map.of()), 2, 2));
            assertTrue(fake.calls.isEmpty());
        } finally {
            web.close();
        }
    }
}
