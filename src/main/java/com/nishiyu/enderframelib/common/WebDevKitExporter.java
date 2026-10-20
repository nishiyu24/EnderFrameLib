package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.common.UiDocumentCompiler.CssResources;
import com.nishiyu.enderframelib.EnderFrameLib;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Web開発者向け「ブラウザプレビュー＆Web標準DevKit」エクスポーター。
 *
 * Chrome / Edge / Firefox および VS Code Live Server 上で、Minecraft を起動することなく
 * 標準 HTML5 / CSS3 と EnderFrameLib の拡張カスタム要素 (&lt;mc-slot&gt;, &lt;mc-inventory&gt;, &lt;mc-bar&gt;)、
 * リアクティブ属性 (data-model, data-bind, data-if, data-for) を完全再現する
 * スタンドアロン開発キットを出力する。
 *
 * 何が壊れていたか: 以前はプレビュー用に独自の UA 相当スタイル
 * （enderframe-devkit.css）を持っていたため、プレビューで作った見た目が
 * ゲーム内で再現しなかった。現在は ua.css と組み込みテーマを**そのまま同梱**し、
 * プレビュー専用のスタイルはプレビューページ自身の枠組みだけに限る。
 */
public final class WebDevKitExporter {

    /** ゲーム内と同じ UA シートの出力名。プレビューの &lt;link&gt; の先頭に置く。 */
    private static final String UA_CSS_FILE = "enderframe-ua.css";
    /** 組み込みテーマ。ゲーム内で &lt;link&gt; で選ぶのと同じものをそのまま出す。 */
    private static final String[] THEMES = {"dark", "vanilla", "brass"};

    private static final String DEV_KIT_JS = "/assets/enderframelib/preview/enderframe-devkit.js";
    private static final String PLAYGROUND_HTML = "/assets/enderframelib/preview/preview-playground.html";
    private static final String FIDELITY_DUMP_HTML = "/assets/enderframelib/preview/fidelity-dump.html";
    private static final String FIDELITY_CASE_INDEX = "/assets/enderframelib/preview/fidelity/cases.txt";
    private static final String FIDELITY_CASE_DIR = "/assets/enderframelib/preview/fidelity/cases/";

    private WebDevKitExporter() {}

    /**
     * 指定ディレクトリ（通常は &lt;gameDir&gt;/enderframe_devkit）へ DevKit 一式を出力する。
     */
    public static Path exportDevKit(Path baseDir) throws IOException {
        Path outDir = baseDir.resolve("enderframe_devkit");
        Files.createDirectories(outDir);

        write(outDir.resolve(UA_CSS_FILE), CssResources.load(CssResources.USER_AGENT));
        for (String theme : THEMES) {
            write(outDir.resolve(themeFile(theme)), CssResources.load(CssResources.themePath(theme)));
        }
        write(outDir.resolve("enderframe-devkit.css"), CssResources.load(CssResources.DEV_KIT));
        write(outDir.resolve("enderframe-devkit.js"), readResource(DEV_KIT_JS));
        write(outDir.resolve("preview-playground.html"), buildPlaygroundHtml());
        write(outDir.resolve("fidelity-dump.html"), buildFidelityDumpHtml());

        EnderFrameLib.LOGGER.info("[EnderFrameLib] Exported Web Developer Kit to: {}", outDir.toAbsolutePath());
        return outDir;
    }

    private static String themeFile(String theme) {
        return "theme-" + theme + ".css";
    }

    private static void write(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    /**
     * ブラウザ実測を採取するページ。
     * コーパスと UA シートを<b>埋め込んで</b>出すのは、file:// で開いても
     * fetch の制約を受けずに動くようにするため。
     */
    static String buildFidelityDumpHtml() {
        String template = readResource(FIDELITY_DUMP_HTML);
        return template
                .replace("/*__UA_CSS__*/ \"\"", jsonString(CssResources.load(CssResources.USER_AGENT)))
                .replace("/*__CASES__*/ {}", buildFidelityCasesJson());
    }

    private static String buildFidelityCasesJson() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (String name : fidelityCaseNames()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(jsonString(name)).append(':')
              .append(jsonString(readResource(FIDELITY_CASE_DIR + name + ".html")));
        }
        return sb.append('}').toString();
    }

    private static List<String> fidelityCaseNames() {
        List<String> names = new ArrayList<>();
        for (String line : readResource(FIDELITY_CASE_INDEX).split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) names.add(trimmed);
        }
        return names;
    }

    /** JavaScript のリテラルとして安全な形に包む。 */
    private static String jsonString(String raw) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                // "</script>" と行区切り文字はそのまま出すと HTML / JS の構文を壊す
                case '<' -> sb.append("\\u003c");
                case ' ' -> sb.append("\\u2028");
                case ' ' -> sb.append("\\u2029");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    private static String readResource(String path) {
        try (InputStream is = WebDevKitExporter.class.getResourceAsStream(path)) {
            if (is == null) {
                EnderFrameLib.LOGGER.warn("[EnderFrameLib] DevKit resource not found: {}", path);
                return "";
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            EnderFrameLib.LOGGER.warn("[EnderFrameLib] Failed to read DevKit resource: {}", path, e);
            return "";
        }
    }

    static String buildPlaygroundHtml() {
        return readResource(PLAYGROUND_HTML);
    }
}
