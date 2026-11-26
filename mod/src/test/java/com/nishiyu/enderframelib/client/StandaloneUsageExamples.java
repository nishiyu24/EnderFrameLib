package com.nishiyu.enderframelib.client;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.io.File;
import java.io.IOException;

/**
 * Minecraft なしで EnderFrameLib を実際に使うための完全なサンプルコード集。
 */
public class StandaloneUsageExamples {

    // =========================================================================
    // 使い方 1: HTML/CSS から PNG 画像ファイルを生成・保存する (画像生成 / 帳票 / OGP)
    // =========================================================================
    public static void exampleExportPngImage() throws IOException {
        String html = """
                <!DOCTYPE html>
                <html>
                <head>
                  <style>
                    body {
                      margin: 0;
                      padding: 24px;
                      background-color: #1e1e2e;
                      color: #cdd6f4;
                      font-family: sans-serif;
                    }
                    .banner {
                      background: linear-gradient(to right, #89b4fa, #cba6f7);
                      padding: 20px;
                      border-radius: 12px;
                      color: #11111b;
                    }
                    h1 { margin: 0 0 8px 0; font-size: 24px; }
                    p { margin: 0; font-size: 14px; }
                  </style>
                </head>
                <body>
                  <div class="banner">
                    <h1>Server Status: ONLINE</h1>
                    <p>Players: 42 / 100 &bull; Uptime: 99.9%</p>
                  </div>
                </body>
                </html>
                """;

        File outputFile = new File("build/output-status.png");
        // 幅 500px, 高さ 200px でレンダリングして保存
        EnderFrameStandalone.renderToFile(html, 500, 200, outputFile);
        System.out.println("Generated image at: " + outputFile.getAbsolutePath());
    }

    // =========================================================================
    // 使い方 2: デスクトップ GUI ウィンドウを表示し、ボタン操作に反応する (Swing)
    // =========================================================================
    public static void exampleDesktopInteractiveWindow() {
        String initialHtml = """
                <!DOCTYPE html>
                <html>
                <head>
                  <style>
                    body {
                      margin: 0;
                      padding: 24px;
                      background-color: #181825;
                      color: #cdd6f4;
                      font-family: sans-serif;
                      display: flex;
                      flex-direction: column;
                      gap: 16px;
                    }
                    .card {
                      background-color: #313244;
                      border: 1px solid #45475a;
                      border-radius: 10px;
                      padding: 20px;
                      display: flex;
                      flex-direction: column;
                      gap: 12px;
                    }
                    h2 { margin: 0; color: #89b4fa; }
                    .count-badge {
                      background-color: #a6e3a1;
                      color: #11111b;
                      font-weight: bold;
                      padding: 4px 12px;
                      border-radius: 6px;
                      width: 120px;
                      text-align: center;
                    }
                    button {
                      background-color: #fab387;
                      color: #11111b;
                      border: none;
                      padding: 10px 18px;
                      border-radius: 8px;
                      cursor: pointer;
                      font-size: 14px;
                      font-weight: bold;
                      align-self: flex-start;
                    }
                  </style>
                </head>
                <body>
                  <div class="card">
                    <h2>EnderFrame Desktop App</h2>
                    <p>Click count: <span class="count-badge" id="counter">0 clicks</span></p>
                    <button id="increment-btn">Click to Increment</button>
                  </div>
                </body>
                </html>
                """;

        JFrame frame = new JFrame("EnderFrame Desktop Application");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        // EnderPanel を Swing ウィンドウに配置
        EnderPanel panel = new EnderPanel();
        panel.setPreferredSize(new Dimension(500, 320));
        panel.loadHtml(initialHtml);

        // クリックイベントのハンドリング
        int[] clickCount = {0};
        panel.addClickListener((elementId, mouseEvent) -> {
            if ("increment-btn".equals(elementId)) {
                clickCount[0]++;
                String updatedHtml = initialHtml.replace(
                        "0 clicks",
                        clickCount[0] + " clicks"
                );
                // HTML を差し替えて即座に再描画
                panel.loadHtml(updatedHtml);
                System.out.println("Button clicked! Total: " + clickCount[0]);
            }
        });

        frame.getContentPane().add(panel, BorderLayout.CENTER);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    /**
     * 直接実行して体験できるメインメソッド
     */
    public static void main(String[] args) throws Exception {
        // 1. 画像を出力
        exampleExportPngImage();

        // 2. インタラクティブなデスクトップ画面を起動
        SwingUtilities.invokeLater(() -> {
            exampleDesktopInteractiveWindow();
        });
    }
}
