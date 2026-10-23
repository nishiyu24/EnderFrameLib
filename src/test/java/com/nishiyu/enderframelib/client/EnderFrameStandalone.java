package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.client.renderer.DisplayListBuilder;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Minecraft を起動せずに EnderFrameLib の HTML/CSS レンダリングを利用・確認するためのスタンドアロン API。
 */
public class EnderFrameStandalone {

    /**
     * ゲーム内および Chrome と同一のフォント解決エンジン（SystemFonts）を取得します。
     */
    public static FontResolver defaultFontResolver() {
        try {
            return com.nishiyu.enderframelib.core.text.font.SystemFonts.system().withLocale(java.util.Locale.getDefault());
        } catch (Throwable t) {
            return AwtFontResolver.INSTANCE;
        }
    }

    /**
     * HTML 文字列をレンダリングし、{@link BufferedImage} として取得します。
     * ゲーム内（Chrome 忠実度）と同じフォント測定とスタイル解決を使用します。
     *
     * @param html   HTML 文字列（&lt;style&gt; タグを含むインライン CSS を含む）
     * @param width  ビューポートの幅 (px)
     * @param height ビューポートの高さ (px)
     * @return 描画済みの ARGB BufferedImage
     */
    public static BufferedImage renderToImage(String html, int width, int height) {
        return renderToImage(html, width, height, defaultFontResolver());
    }

    /**
     * フォントリゾルバーを指定して HTML 文字列をレンダリングします。
     */
    public static BufferedImage renderToImage(String html, int width, int height, FontResolver fontResolver) {
        UiDocumentCompiler compiler = new UiDocumentCompiler();
        Document doc = compiler.parseDocument(html, "", width, height);

        ScrollState scrollState = new ScrollState();
        InteractionState interactionState = new InteractionState();
        LayoutBox root = compiler.layoutDocument(doc, width, height, Map.of(), scrollState, fontResolver, interactionState);

        DisplayListBuilder builder = new DisplayListBuilder(width, height, doc.sheet.keyframes, scrollState);
        List<DisplayItem> items = builder.render(root);

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();
        try {
            AwtDisplayItemDrawer.render(g2d, items);
        } finally {
            g2d.dispose();
        }
        return image;
    }

    /**
     * HTML をレンダリングし、PNG 画像ファイルとして保存します。
     */
    public static void renderToFile(String html, int width, int height, File outputFile) throws IOException {
        BufferedImage img = renderToImage(html, width, height);
        File parent = outputFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        ImageIO.write(img, "png", outputFile);
    }

    /**
     * Swing のプレビューウィンドウを起動して、HTML/CSS をデスクトップ画面に表示します。
     */
    public static JFrame openPreview(String html, int width, int height, String title) {
        JFrame frame = new JFrame(title != null ? title : "EnderFrame Standalone Preview");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        EnderPanel panel = new EnderPanel();
        panel.setPreferredSize(new Dimension(width, height));
        frame.getContentPane().add(panel, BorderLayout.CENTER);
        frame.pack();
        frame.setLocationRelativeTo(null);

        panel.loadHtml(html);
        frame.setVisible(true);
        return frame;
    }

    /**
     * デモ画面を単体で起動するエントリポイント。
     */
    public static void main(String[] args) {
        String sampleHtml = """
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
                    .header {
                      background: linear-gradient(to right, #89b4fa, #cba6f7);
                      padding: 16px 20px;
                      border-radius: 12px;
                      color: #11111b;
                    }
                    h1 { margin: 0 0 6px 0; font-size: 22px; }
                    p { margin: 0; font-size: 14px; opacity: 0.9; }
                    .grid-container {
                      display: grid;
                      grid-template-columns: 1fr 1fr;
                      gap: 16px;
                    }
                    .card {
                      background-color: #313244;
                      border: 1px solid #45475a;
                      border-radius: 10px;
                      padding: 16px;
                      display: flex;
                      flex-direction: column;
                      gap: 10px;
                    }
                    .card h3 { margin: 0; color: #89b4fa; font-size: 16px; }
                    .badge {
                      display: inline-block;
                      background-color: #a6e3a1;
                      color: #11111b;
                      padding: 4px 10px;
                      border-radius: 6px;
                      font-size: 12px;
                      width: 140px;
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
                      align-self: flex-start;
                    }
                  </style>
                </head>
                <body>
                  <div class="header">
                    <h1>EnderFrameLib Standalone Preview</h1>
                    <p>Running entirely on pure Java (AWT / Swing) without Minecraft!</p>
                  </div>
                  
                  <div class="grid-container">
                    <div class="card">
                      <h3>Layout Engine</h3>
                      <p>Flexbox &amp; CSS Grid working with subpixel layout calculation.</p>
                      <div class="badge">CSS Grid 2-Columns</div>
                    </div>
                    
                    <div class="card">
                      <h3>Modern CSS Styling</h3>
                      <p>Linear gradients, custom border-radius, shadows, and opacity.</p>
                      <button id="demo-btn">Interactive Button</button>
                    </div>
                  </div>
                </body>
                </html>
                """;

        SwingUtilities.invokeLater(() -> {
            JFrame frame = openPreview(sampleHtml, 640, 420, "EnderFrameLib - Pure Java Standalone");
            System.out.println("Standalone preview window opened: " + frame.getTitle());
        });
    }
}
