package com.nishiyu.enderframelib.core.net;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class MediaUrlsTest {

    @Test @DisplayName("動画は http(s) とホストがあるものだけ")
    void videoUrls() {
        assertTrue(MediaUrls.isSupportedExternalVideoUrl("https://e.test/a.mp4"));
        assertTrue(MediaUrls.isSupportedExternalVideoUrl("HTTP://e.test/a.mp4"));
        assertFalse(MediaUrls.isSupportedExternalVideoUrl("file:///etc/passwd"));
        assertFalse(MediaUrls.isSupportedExternalVideoUrl("mod:video/a.mp4"));
        assertFalse(MediaUrls.isSupportedExternalVideoUrl("http:///nohost"));
        assertFalse(MediaUrls.isSupportedExternalVideoUrl("not a url"));
    }

    @Test @DisplayName("YouTube のホスト判定は末尾ドットを許し、似たホストを許さない")
    void youtube() {
        assertTrue(MediaUrls.isYouTubeUrl("https://www.youtube.com/watch?v=1"));
        assertTrue(MediaUrls.isYouTubeUrl("https://youtu.be/abc"));
        assertTrue(MediaUrls.isYouTubeUrl("https://YouTube.com./x"));
        assertFalse(MediaUrls.isYouTubeUrl("https://notyoutube.com/x"));
        assertFalse(MediaUrls.isYouTubeUrl("https://youtube.com.evil.test/x"));
        assertFalse(MediaUrls.isYouTubeUrl("nonsense"));
    }

    @Test @DisplayName("Google Drive の共有リンクは直リンクになる")
    void drive() {
        assertEquals("https://drive.google.com/uc?export=download&id=ABC",
                MediaUrls.convertToDirectLink("https://drive.google.com/file/d/ABC/view?usp=sharing"));
        assertEquals("https://e.test/a", MediaUrls.convertToDirectLink("https://e.test/a"));
    }

    @Test @DisplayName("URL のハッシュは 32 桁で安定し、URL ごとに違う")
    void hash() {
        assertEquals(32, MediaUrls.urlHash("a").length());
        assertEquals(MediaUrls.urlHash("a"), MediaUrls.urlHash("a"));
        assertNotEquals(MediaUrls.urlHash("a"), MediaUrls.urlHash("b"));
    }

    @Test @DisplayName("yt-dlp の出力から最初の http(s) 行を取る")
    void firstHttpLine() {
        String out = "WARNING: x\n  https://cdn.test/v.mp4  \nhttps://second\n";
        assertEquals("https://cdn.test/v.mp4",
                YtDlpResolver.readFirstHttpUrl(new ByteArrayInputStream(out.getBytes(StandardCharsets.UTF_8))));
        assertNull(YtDlpResolver.readFirstHttpUrl(new ByteArrayInputStream("none".getBytes(StandardCharsets.UTF_8))));
    }

    @Test @DisplayName("無効・YouTube 以外・安全でない URL では yt-dlp を起動しない")
    void resolverGuards() {
        assertNull(YtDlpResolver.resolve("https://e.test/x", u -> true, new java.io.File("."), null, () -> true));
        assertNull(YtDlpResolver.resolve("https://youtu.be/x", u -> true, new java.io.File("."), null, () -> false));
        assertNull(YtDlpResolver.resolve("https://youtu.be/x", u -> false, new java.io.File("."), null, () -> true));
    }
}
