package com.nishiyu.enderframelib.client.renderer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GIF を動画デコーダへ回す判定。リモートの .gif だけが対象で、静止画のパスは変えない。 */
public class GifRoutingTest {

    @Test
    @DisplayName("http(s) の .gif はクエリや大文字小文字が付いていても GIF として扱う")
    void remoteGifIsDetected() {
        assertTrue(ControlPainter.isRemoteGif("https://example.com/a.gif"));
        assertTrue(ControlPainter.isRemoteGif("http://example.com/dir/A.GIF?v=2#x"));
    }

    @Test
    @DisplayName("GIF 以外・ローカルスキーム・クエリ内の .gif は対象にしない")
    void nonGifIsIgnored() {
        assertFalse(ControlPainter.isRemoteGif("https://example.com/a.png"));
        assertFalse(ControlPainter.isRemoteGif("https://example.com/a.png?name=b.gif"));
        assertFalse(ControlPainter.isRemoteGif("mod:enderframelib/a.gif"));
        assertFalse(ControlPainter.isRemoteGif("file:///a.gif"));
        assertFalse(ControlPainter.isRemoteGif(""));
        assertFalse(ControlPainter.isRemoteGif(null));
    }
}
