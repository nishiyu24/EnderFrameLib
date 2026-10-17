package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.EnderFrameLib;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Web開発者向け「ブラウザプレビュー＆Web標準DevKit」エクスポーター。
 *
 * Chrome / Edge / Firefox および VS Code Live Server 上で、Minecraft を起動することなく
 * 標準 HTML5 / CSS3 と EnderFrameLib の拡張カスタム要素 (<mc-slot>, <mc-inventory>, <mc-bar>)、
 * リアクティブ属性 (data-model, data-bind, data-if, data-for) を完全再現する
 * スタンドアロン開発キットを出力する。
 */
public final class WebDevKitExporter {

    private WebDevKitExporter() {}

    /**
     * 指定ディレクトリ（通常は <gameDir>/enderframe_devkit）へ DevKit 一式を出力する。
     */
    public static Path exportDevKit(Path baseDir) throws IOException {
        Path outDir = baseDir.resolve("enderframe_devkit");
        Files.createDirectories(outDir);

        Files.writeString(outDir.resolve("enderframe-devkit.css"), buildDevKitCss(), StandardCharsets.UTF_8);
        Files.writeString(outDir.resolve("enderframe-devkit.js"), buildDevKitJs(), StandardCharsets.UTF_8);
        Files.writeString(outDir.resolve("preview-playground.html"), buildPlaygroundHtml(), StandardCharsets.UTF_8);

        EnderFrameLib.LOGGER.info("[EnderFrameLib] Exported Web Developer Kit to: {}", outDir.toAbsolutePath());
        return outDir;
    }

    /**
     * プレビュー用スタイルシート。
     * 本体は resources/assets/enderframelib/preview/enderframe-devkit.css が単一の出所で、
     * 以前はここに 170 行以上のコピーが埋め込まれていた（focus-visible 宣言の 4 つ目のコピー）。
     */
    private static String buildDevKitCss() {
        return CssResources.load(CssResources.DEV_KIT);
    }

    private static final String DEV_KIT_JS = "/assets/enderframelib/preview/enderframe-devkit.js";

    private static String buildDevKitJs() {
        try (java.io.InputStream is = WebDevKitExporter.class.getResourceAsStream(DEV_KIT_JS)) {
            if (is == null) {
                EnderFrameLib.LOGGER.warn("[EnderFrameLib] DevKit JS resource not found: {}", DEV_KIT_JS);
                return "";
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            EnderFrameLib.LOGGER.warn("[EnderFrameLib] Failed to read DevKit JS resource: {}", DEV_KIT_JS, e);
            return "";
        }
    }

    private static String buildPlaygroundHtml() {
        return """
                <!DOCTYPE html>
                <html lang="ja" data-theme="dark">
                <head>
                    <meta charset="UTF-8" />
                    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                    <title>EnderFrameLib - Web Standard UI Playground</title>
                    <link rel="stylesheet" href="enderframe-devkit.css" />
                    <script src="enderframe-devkit.js" defer></script>
                </head>
                <body style="padding: 20px; display: flex; flex-direction: column; gap: 14px; max-width: 854px; margin: 0 auto;">
                    <header class="mc-panel" style="display: flex; justify-content: space-between; align-items: center;">
                        <div>
                            <div class="mc-title" style="margin: 0;">EnderFrameLib Browser DevKit</div>
                            <small style="color: var(--text-sub);">W3C HTML5 / CSS3 Standard UI Authoring Preview</small>
                        </div>
                        <div style="display: flex; gap: 8px; align-items: center;">
                            <label for="theme_sel">Theme:</label>
                            <select id="theme_sel" onchange="document.documentElement.dataset.theme = this.value">
                                <option value="dark">Dark (Catppuccin)</option>
                                <option value="vanilla">Minecraft Vanilla</option>
                                <option value="brass">Create Brass</option>
                            </select>
                        </div>
                    </header>

                    <main style="display: flex; gap: 14px; align-items: flex-start;">
                        <section class="mc-panel" style="flex: 1; display: flex; flex-direction: column; gap: 10px;">
                            <fieldset>
                                <legend>Standard HTML5 Reactive Form</legend>
                                <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px;">
                                    <label for="power_toggle">Machine Active</label>
                                    <input id="power_toggle" type="checkbox" data-model="machineActive" checked />
                                </div>
                                <div style="display: flex; justify-content: space-between; align-items: center;" data-if="machineActive">
                                    <label for="speed_range">Processing Speed (<output data-bind="speed">100</output>%)</label>
                                    <input id="speed_range" type="range" min="0" max="200" value="100" data-model="speed" />
                                </div>
                            </fieldset>

                            <details open class="mc-card">
                                <summary>Machine Inventory &amp; Slots</summary>
                                <div style="display: flex; gap: 16px; align-items: center; margin-top: 8px;">
                                    <mc-slot-grid start="0" cols="3" rows="3" gap="2"></mc-slot-grid>
                                    <mc-bar value="72" max="100" label="72%"></mc-bar>
                                    <mc-slot index="9"></mc-slot>
                                </div>
                                <div style="margin-top: 10px;">
                                    <mc-inventory start="10"></mc-inventory>
                                </div>
                            </details>
                        </section>
                    </main>
                </body>
                </html>
                """;
    }
}