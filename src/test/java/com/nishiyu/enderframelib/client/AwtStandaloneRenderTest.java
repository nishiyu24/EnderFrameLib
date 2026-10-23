package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.core.GoldenFiles;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Minecraft を起動せずに、Java 標準の AWT / Graphics2D だけで
 * HTML/CSS をレンダリングできることを検証するテスト。
 */
public class AwtStandaloneRenderTest {

    @Test
    public void testRenderToBufferedImage() {
        String html = """
                <html>
                <head>
                  <style>
                    body {
                      margin: 0;
                      background-color: #1a1a24;
                      color: #ffffff;
                      font-family: sans-serif;
                      padding: 10px;
                    }
                    .box {
                      width: 100px;
                      height: 50px;
                      background-color: #89b4fa;
                      border-radius: 8px;
                    }
                  </style>
                </head>
                <body>
                  <div class="box"></div>
                </body>
                </html>
                """;

        BufferedImage img = EnderFrameStandalone.renderToImage(html, 200, 100);
        assertNotNull(img, "Rendered image should not be null");
        assertEquals(200, img.getWidth());
        assertEquals(100, img.getHeight());

        // 背景色の確認 (0xFF1A1A24 付近)
        int bgPixel = img.getRGB(5, 5);
        int red = (bgPixel >> 16) & 0xFF;
        int green = (bgPixel >> 8) & 0xFF;
        int blue = bgPixel & 0xFF;
        assertEquals(0x1A, red, 5);
        assertEquals(0x1A, green, 5);
        assertEquals(0x24, blue, 5);
    }

    @Test
    public void testRenderToFile() throws IOException {
        String html = """
                <html>
                <head>
                  <style>
                    body {
                      margin: 0;
                      padding: 16px;
                      background: linear-gradient(to right, #f38ba8, #a6e3a1);
                      font-family: sans-serif;
                    }
                    .card {
                      background: #11111b;
                      color: #cdd6f4;
                      padding: 12px;
                      border-radius: 8px;
                    }
                  </style>
                </head>
                <body>
                  <div class="card">
                    <h2>Standalone PNG Export</h2>
                    <p>Generated without Minecraft runtime.</p>
                  </div>
                </body>
                </html>
                """;

        File outFile = new File("build/test-output/standalone-preview.png");
        EnderFrameStandalone.renderToFile(html, 320, 200, outFile);

        assertTrue(outFile.exists(), "Exported PNG file should exist");
        assertTrue(outFile.length() > 0, "Exported PNG file should not be empty");
    }

    @Test
    public void testShowcaseHtmlStandalone() throws IOException {
        String showcaseHtml = GoldenFiles.readFixture("showcase.html");
        File outFile = new File("build/test-output/standalone-showcase.png");
        // Chrome 実測およびゲーム内と同一の 854x480 でレンダリング
        EnderFrameStandalone.renderToFile(showcaseHtml, 854, 480, outFile);

        assertTrue(outFile.exists());
        assertTrue(outFile.length() > 0);
    }
}
