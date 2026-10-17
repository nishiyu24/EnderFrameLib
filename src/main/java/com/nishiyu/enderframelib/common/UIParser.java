package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.css.CssParser;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.dom.HtmlParser;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.markdown.MarkdownParser;
import com.nishiyu.enderframelib.core.model.UIElement;
import com.nishiyu.enderframelib.server.EnderFrameDataPackManager;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UIParser {

    private static final Pattern INCLUDE_PATTERN = Pattern.compile("(?is)<include\\b[^>]*\\bsrc=[\"']([^\"']+)[\"'][^>]*(?:/>|>\\s*</include>)");
    private static final Pattern MC_SLOT_PATTERN = Pattern.compile("(?is)<mc-slot\\b([^>]*)(?:/>|>\\s*</mc-slot>)");
    private static final Pattern MC_SLOT_GRID_PATTERN = Pattern.compile("(?is)<mc-slot-grid\\b([^>]*)(?:/>|>\\s*</mc-slot-grid>)");
    private static final Pattern MC_INVENTORY_PATTERN = Pattern.compile("(?is)<mc-inventory\\b([^>]*)(?:/>|>\\s*</mc-inventory>)");
    private static final Pattern MC_BAR_PATTERN = Pattern.compile("(?is)<mc-bar\\b([^>]*)(?:/>|>\\s*</mc-bar>)");
    private static final Pattern LINK_HREF_PATTERN = Pattern.compile("(?is)<link\\b[^>]*\\bhref=[\"']([^\"']+)[\"'][^>]*>");
    private static final Pattern SCRIPT_TAG_PATTERN = Pattern.compile("(?is)<script\\b[^>]*>\\s*(.*?)\\s*</script>");
    private static final Pattern STYLE_TAG_PATTERN = Pattern.compile("(?is)<style\\b[^>]*>\\s*(.*?)\\s*</style>");
    private static final Pattern TRIM_QUOTES_PATTERN = Pattern.compile("^[\"']|[\"']$");
    private static final Map<String, Pattern> ATTR_PATTERN_CACHE = new ConcurrentHashMap<>();

    @FunctionalInterface
    public interface ILayoutBoxRenderer {
        List<UIElement> render(LayoutBox rootBox, int rootW, int rootH, Map<String, String> keyframes);
    }

    /**
     * クライアント起動時に EnderFrameClientInit から UIRenderer がバインドされる
     */
    public static ILayoutBoxRenderer clientBoxRenderer = null;

    /**
     * クライアント起動時に ResourceManager ベースのリゾルバが注入される（サーバー側でもデータパック＆クラスパスから安全に解決）
     */
    public static Function<String, String> clientResourceFallback = null;

    public static Function<String, String> resourceTextResolver = path -> {
        if (path == null) return "";
        String clean = path.trim();
        String builtIn = resolveBuiltInTheme(clean);
        if (builtIn != null) return builtIn;

        // 1. データパックから検索
        String fromDatapack = EnderFrameDataPackManager.getHtml(clean);
        if (fromDatapack != null && !fromDatapack.isEmpty()) return fromDatapack;

        // 2. クライアントのリソースパックから検索
        if (clientResourceFallback != null) {
            String fromClient = clientResourceFallback.apply(clean);
            if (fromClient != null && !fromClient.isEmpty()) return fromClient;
        }

        // 3. サーバー/共通のクラスパス(/assets/ または /data/)から検索
        if (clean.contains(":")) {
            String[] sp = clean.split(":", 2);
            try (InputStream is = UIParser.class.getResourceAsStream("/assets/" + sp[0] + "/" + sp[1])) {
                if (is != null) return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception ignored) {}
            try (InputStream is = UIParser.class.getResourceAsStream("/data/" + sp[0] + "/enderframe_ui/" + sp[1])) {
                if (is != null) return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception ignored) {}
        }
        return "";
    };

    public UIParser() {}

    public static String resolveBuiltInTheme(String href) {
        if (href == null) return null;
        String lower = href.toLowerCase().trim();
        if (lower.endsWith("theme/dark.css") || lower.endsWith("themes/dark.css") || lower.equals("enderframelib:dark.css")) {
            return """
                    :root { --bg-base: #1e1e2e; --bg-surface: #313244; --bg-elevated: #45475a; --text-main: #cdd6f4; --text-sub: #a6adc8; --accent: #89b4fa; --success: #a6e3a1; --danger: #f38ba8; --warning: #f9e2af; }
                    body, html { background-color: #181825; color: #cdd6f4; }
                    .mc-panel { background-color: #1e1e2e; border: 1px solid #45475a; border-radius: 6px; padding: 12px; box-shadow: 0 4px 8px rgba(0,0,0,0.4); }
                    .mc-card { background-color: #313244; border: 1px solid #45475a; border-radius: 4px; padding: 8px; }
                    .mc-title { color: #89b4fa; font-weight: bold; font-size: 1.1rem; margin-bottom: 6px; }
                    .mc-btn { background-color: #313244; color: #cdd6f4; border: 1px solid #585b70; border-radius: 4px; padding: 4px 10px; transition: 0.12s; }
                    .mc-btn:hover { background-color: #45475a; color: #ffffff; }
                    .mc-btn:active { transform: scale(0.95); }
                    .mc-btn-primary { background-color: #89b4fa; color: #11111b; border: 1px solid #b4befe; font-weight: bold; }
                    .mc-btn-primary:hover { background-color: #b4befe; }
                    .mc-slot { width: 18px; height: 18px; background-color: #11111b; border: 1px solid #45475a; border-radius: 2px; box-sizing: border-box; transition: 0.1s; }
                    .mc-slot:hover { background-color: #313244; border-color: #89b4fa; }
                    """;
        }
        if (lower.endsWith("theme/vanilla.css") || lower.endsWith("themes/vanilla.css") || lower.equals("enderframelib:vanilla.css")) {
            return """
                    :root { --bg-base: #c6c6c6; --bg-surface: #8b8b8b; --text-main: #3f3f3f; --accent: #5555ff; }
                    body, html { background-color: transparent; color: #3f3f3f; }
                    .mc-panel { background-color: #c6c6c6; border-top: 2px solid #ffffff; border-left: 2px solid #ffffff; border-right: 2px solid #555555; border-bottom: 2px solid #555555; border-radius: 2px; padding: 10px; }
                    .mc-card { background-color: #b0b0b0; border-top: 1px solid #555555; border-left: 1px solid #555555; border-right: 1px solid #ffffff; border-bottom: 1px solid #ffffff; padding: 6px; }
                    .mc-title { color: #3f3f3f; font-weight: bold; margin-bottom: 4px; }
                    .mc-btn { background-color: #8b8b8b; color: #ffffff; border-top: 1px solid #ffffff; border-left: 1px solid #ffffff; border-right: 1px solid #373737; border-bottom: 1px solid #373737; padding: 3px 8px; }
                    .mc-btn:hover { background-color: #a0a0c8; color: #ffffa0; }
                    .mc-slot { width: 18px; height: 18px; background-color: #8b8b8b; border-top: 1px solid #373737; border-left: 1px solid #373737; border-right: 1px solid #ffffff; border-bottom: 1px solid #ffffff; box-sizing: border-box; }
                    .mc-slot:hover { background-color: #a8a8a8; }
                    """;
        }
        if (lower.endsWith("theme/brass.css") || lower.endsWith("themes/brass.css") || lower.equals("enderframelib:brass.css")) {
            return """
                    :root { --bg-base: #3b2d22; --bg-surface: #261c15; --text-main: #f5e0dc; --accent: #e5c890; }
                    body, html { background-color: #1c140f; color: #f5e0dc; }
                    .mc-panel { background-color: #3b2d22; border: 2px solid #c49a45; border-radius: 4px; padding: 12px; box-shadow: 0 4px 8px rgba(0,0,0,0.5); }
                    .mc-card { background-color: #261c15; border: 1px solid #8c6a2b; border-radius: 3px; padding: 8px; }
                    .mc-title { color: #f9e2af; font-weight: bold; margin-bottom: 6px; }
                    .mc-btn { background-color: #5c4328; color: #f9e2af; border: 1px solid #d4af37; border-radius: 3px; padding: 3px 9px; transition: 0.1s; }
                    .mc-btn:hover { background-color: #7a5935; color: #ffffff; }
                    .mc-slot { width: 18px; height: 18px; background-color: #1e150f; border: 1px solid #9c7a38; border-radius: 2px; box-sizing: border-box; }
                    .mc-slot:hover { background-color: #38281c; border-color: #f9e2af; }
                    """;
        }
        return null;
    }

    public static final String DEFAULT_USER_AGENT_CSS = """
            body, html { background-color: #ffffff; color: #333333; margin: 0; padding: 0; width: 100%; min-height: 100%; box-sizing: border-box; overflow: auto; }
            head, meta, title, link, style, script, template, option { display: none; }
            *[hidden] { display: none; }
            h1 { font-size: 2rem; font-weight: bold; margin: 0.67em 0; display: block; }
            h2 { font-size: 1.5rem; font-weight: bold; margin: 0.83em 0; display: block; }
            h3 { font-size: 1.17rem; font-weight: bold; margin: 1em 0; display: block; }
            h4 { font-size: 1.0rem; font-weight: bold; margin: 0.8em 0; display: block; }
            h5 { font-size: 0.9rem; font-weight: bold; margin: 0.6em 0; display: block; }
            h6 { font-size: 0.8rem; font-weight: bold; margin: 0.5em 0; color: #a6adc8; display: block; }
            p { margin: 0.6em 0; display: block; }
            ul, ol { padding-left: 20px; margin: 0.6em 0; display: block; }
            li { display: block; margin: 2px 0; }
            .task-list-item { display: flex; flex-direction: row; align-items: center; gap: 6px; }
            a { color: #89b4fa; text-decoration: underline; cursor: pointer; display: inline-block; }
            a:hover { color: #b4befe; }
            b, strong { font-weight: bold; }
            i, em { font-style: italic; }
            s, strike, del { text-decoration: line-through; }
            u, ins { text-decoration: underline; }
            code, kbd { background-color: rgba(137, 180, 250, 0.14); color: #f9e2af; padding: 1px 4px; border-radius: 3px; display: inline-block; }
            kbd { border: 1px solid #585b70; background-color: #313244; color: #cdd6f4; }
            pre { background-color: #11111b; color: #cdd6f4; border: 1px solid #45475a; border-radius: 4px; padding: 8px 10px; margin: 6px 0; display: block; overflow-x: auto; }
            blockquote { border-left: 3px solid #89b4fa; background-color: rgba(137, 180, 250, 0.08); padding: 6px 10px; margin: 6px 0; border-radius: 2px; display: block; }
            .markdown-alert-title { font-weight: bold; margin-bottom: 4px; color: #89b4fa; display: block; }
            .markdown-alert-tip { border-left-color: #a6e3a1; background-color: rgba(166, 227, 161, 0.08); }
            .markdown-alert-important { border-left-color: #cba6f7; background-color: rgba(203, 166, 247, 0.08); }
            .markdown-alert-warning { border-left-color: #f9e2af; background-color: rgba(249, 226, 175, 0.08); }
            .markdown-alert-caution { border-left-color: #f38ba8; background-color: rgba(243, 139, 168, 0.08); }
            table { display: flex; flex-direction: column; width: 100%; border: 1px solid #45475a; border-radius: 4px; margin: 6px 0; overflow: hidden; }
            thead, tbody { display: flex; flex-direction: column; width: 100%; }
            thead { background-color: rgba(137, 180, 250, 0.12); font-weight: bold; }
            tr { display: flex; flex-direction: row; width: 100%; border-bottom: 1px solid #313244; }
            th, td { flex: 1; padding: 4px 8px; box-sizing: border-box; display: block; }
            th { font-weight: bold; color: #89b4fa; }
            label, output { display: inline-block; }
            form, fieldset, details, summary, dialog, aside, figure, figcaption { display: block; }
            fieldset { border: 1px solid #585b70; border-radius: 4px; padding: 8px 12px; margin: 4px 0; }
            legend { font-weight: bold; padding: 0 4px; display: block; }
            summary { cursor: pointer; font-weight: bold; user-select: none; }
            dialog:not([open]) { display: none; }
            dialog[open] { position: fixed; z-index: 100; background-color: #1e1e2e; color: #cdd6f4; border: 1px solid #89b4fa; border-radius: 6px; padding: 16px; box-shadow: 0 4px 12px rgba(0,0,0,0.6); }
            img, canvas, mc-fluid, mc-entity, mc-scene { display: inline-block; object-fit: fill; }
            div, section, main, header, footer, article, nav { display: block; }
            hr { display: block; border: none; border-top: 1px solid #585b70; margin: 8px 0; width: 100%; height: 1px; background-color: #585b70; }
            button { display: inline-block; cursor: pointer; background-color: #f0f0f0; color: #000000; border: 1px solid #767676; padding: 2px 6px; border-radius: 2px; transition: 0.1s; min-width: min-content; }
            button:hover { background-color: #e5e5e5; }
            button:active { background-color: #d5d5d5; border-color: #4f4f4f; transform: scale(0.95); }
            button:focus-visible, input:focus-visible, select:focus-visible, textarea:focus-visible, summary:focus-visible, a:focus-visible { border-color: #89b4fa; box-shadow: 0 0 0 2px rgba(137, 180, 250, 0.5); }
            button[disabled], input[disabled], select[disabled], textarea[disabled] { opacity: 0.5; }
            input, select, textarea { display: inline-block; background-color: #ffffff; color: #000000; border: 1px solid #777777; padding: 4px; box-sizing: border-box; border-radius: 2px; transition: 0.15s; min-width: min-content; }
            textarea { display: block; width: 100%; height: 120px; overflow-y: auto; }
            input:focus, select:hover, textarea:focus { background-color: #f8fbff; }
            input[type="checkbox"], input[type="radio"] { width: 16px; height: 16px; padding: 0; border: 2px solid #555555; transition: 0.1s; }
            input[type="checkbox"]:hover { border-color: #aaaaaa; }
            input[type="range"] { background-color: #ffffff; color: #007bff; border: none; padding: 0; height: 16px; border-radius: 8px; }
            input[type="color"] { width: 48px; height: 22px; padding: 2px; border-radius: 4px; }
            progress, meter { display: inline-block; background-color: #333333; color: #28a745; border: none; padding: 0; width: 120px; height: 12px; border-radius: 6px; }
            .mc-slot { width: 18px; height: 18px; background-color: #2b2b36; border: 1px solid #585b70; border-radius: 2px; box-sizing: border-box; display: inline-block; }
            .mc-slot:hover { background-color: #3e4055; border-color: #89b4fa; }
            .mc-inventory { display: flex; flex-direction: column; gap: 4px; width: 162px; }
            .mc-inventory-row { display: flex; flex-direction: row; gap: 0px; }
            .mc-bar { position: relative; display: inline-block; width: 140px; height: 14px; background-color: #181825; border: 1px solid #45475a; border-radius: 4px; overflow: hidden; }
            """;

    public CssParser.StyleSheet buildStyleSheet(String authorCss, int rootW) {
        CssParser.StyleSheet sheet = CssParser.parse(DEFAULT_USER_AGENT_CSS, rootW);

        if (authorCss != null && !authorCss.isEmpty()) {
            CssParser.StyleSheet authorSheet = CssParser.parse(authorCss, rootW);
            for (CssParser.StyleRule rule : authorSheet.rules) {
                rule.specificity += 10000;
            }
            sheet.rules.addAll(authorSheet.rules);
            sheet.keyframes.putAll(authorSheet.keyframes);
            sheet.variables.putAll(authorSheet.variables);
        }

        sheet.rules.sort((a, b) -> Integer.compare(a.specificity, b.specificity));
        return sheet;
    }

    private String resolveIncludes(String html, int depth) {
        if (html == null || depth > 5 || !html.contains("<include")) return html;
        Matcher incM = INCLUDE_PATTERN.matcher(html);
        StringBuilder sb = new StringBuilder();
        boolean found = false;
        while (incM.find()) {
            found = true;
            String src = incM.group(1);
            String loaded = resourceTextResolver != null ? resourceTextResolver.apply(src) : "";
            if (loaded != null && src.toLowerCase().endsWith(".md")) {
                loaded = MarkdownParser.toHtml(loaded);
            }
            incM.appendReplacement(sb, Matcher.quoteReplacement(loaded != null ? loaded : ""));
        }
        incM.appendTail(sb);
        return found ? resolveIncludes(sb.toString(), depth + 1) : html;
    }

    private static String extractAttr(String attrsStr, String attrName, String defaultVal) {
        if (attrsStr == null || attrsStr.isEmpty()) return defaultVal;
        Pattern p = ATTR_PATTERN_CACHE.computeIfAbsent(attrName.toLowerCase(),
                k -> Pattern.compile("(?i)\\b" + Pattern.quote(k) + "\\s*=\\s*[\"']([^\"']*)[\"']"));
        Matcher m = p.matcher(attrsStr);
        if (m.find()) return m.group(1).trim();
        return defaultVal;
    }

    public static String expandCustomTags(String html) {
        if (html == null) return null;
        html = MarkdownParser.processDocumentOrBlocks(html);
        if (!html.contains("<mc-")) return html;

        Matcher slotM = MC_SLOT_PATTERN.matcher(html);
        StringBuilder sb = new StringBuilder();
        while (slotM.find()) {
            String attrs = slotM.group(1);
            String idx = extractAttr(attrs, "index", extractAttr(attrs, "slot", "0"));
            String cls = extractAttr(attrs, "class", "");
            String style = extractAttr(attrs, "style", "");
            String id = extractAttr(attrs, "id", "");
            String idAttr = id.isEmpty() ? "" : " id=\"" + id + "\"";
            String styleAttr = style.isEmpty() ? "" : " style=\"" + style + "\"";
            String replacement = "<div" + idAttr + " class=\"mc-slot" + (cls.isEmpty() ? "" : " " + cls) + "\" data-slot=\"" + idx + "\"" + styleAttr + "></div>";
            slotM.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        slotM.appendTail(sb);
        html = sb.toString();

        Matcher gridM = MC_SLOT_GRID_PATTERN.matcher(html);
        sb.setLength(0);
        while (gridM.find()) {
            String attrs = gridM.group(1);
            int start = 0, cols = 3, rows = 3, gap = 0;
            try { start = Integer.parseInt(extractAttr(attrs, "start", "0")); } catch (Exception ignored) {}
            try { cols = Math.max(1, Integer.parseInt(extractAttr(attrs, "cols", "3"))); } catch (Exception ignored) {}
            try { rows = Math.max(1, Integer.parseInt(extractAttr(attrs, "rows", "3"))); } catch (Exception ignored) {}
            try { gap = Math.max(0, Integer.parseInt(extractAttr(attrs, "gap", "0"))); } catch (Exception ignored) {}
            String extraStyle = extractAttr(attrs, "style", "");

            StringBuilder gridHtml = new StringBuilder();
            gridHtml.append("<div style=\"display:flex;flex-direction:column;gap:").append(gap).append("px;").append(extraStyle).append("\">");
            int idx = start;
            for (int r = 0; r < rows; r++) {
                gridHtml.append("<div style=\"display:flex;flex-direction:row;gap:").append(gap).append("px;\">");
                for (int c = 0; c < cols; c++) {
                    gridHtml.append("<div class=\"mc-slot\" data-slot=\"").append(idx++).append("\"></div>");
                }
                gridHtml.append("</div>");
            }
            gridHtml.append("</div>");
            gridM.appendReplacement(sb, Matcher.quoteReplacement(gridHtml.toString()));
        }
        gridM.appendTail(sb);
        html = sb.toString();

        Matcher invM = MC_INVENTORY_PATTERN.matcher(html);
        sb.setLength(0);
        while (invM.find()) {
            String attrs = invM.group(1);
            int start = 0;
            try { start = Integer.parseInt(extractAttr(attrs, "start", "0")); } catch (Exception ignored) {}
            boolean hotbarFirst = "true".equalsIgnoreCase(extractAttr(attrs, "hotbar-first", "false"));
            String hotbarStartStr = extractAttr(attrs, "hotbar", "");

            int mainStart = hotbarFirst ? (start + 9) : start;
            int hotbarStart = hotbarFirst ? start : (start + 27);
            if (!hotbarStartStr.isEmpty()) {
                try { hotbarStart = Integer.parseInt(hotbarStartStr); } catch (Exception ignored) {}
            }

            String extraStyle = extractAttr(attrs, "style", "");
            StringBuilder invHtml = new StringBuilder();
            invHtml.append("<div class=\"mc-inventory\" style=\"display:flex;flex-direction:column;gap:4px;").append(extraStyle).append("\">");
            invHtml.append("<div style=\"display:flex;flex-direction:column;gap:0px;\">");
            int mIdx = mainStart;
            for (int r = 0; r < 3; r++) {
                invHtml.append("<div class=\"mc-inventory-row\" style=\"display:flex;flex-direction:row;gap:0px;\">");
                for (int c = 0; c < 9; c++) {
                    invHtml.append("<div class=\"mc-slot\" data-slot=\"").append(mIdx++).append("\"></div>");
                }
                invHtml.append("</div>");
            }
            invHtml.append("</div>");

            invHtml.append("<div class=\"mc-inventory-row\" style=\"display:flex;flex-direction:row;gap:0px;\">");
            int hIdx = hotbarStart;
            for (int c = 0; c < 9; c++) {
                invHtml.append("<div class=\"mc-slot\" data-slot=\"").append(hIdx++).append("\"></div>");
            }
            invHtml.append("</div></div>");
            invM.appendReplacement(sb, Matcher.quoteReplacement(invHtml.toString()));
        }
        invM.appendTail(sb);
        html = sb.toString();

        Matcher barM = MC_BAR_PATTERN.matcher(html);
        sb.setLength(0);
        while (barM.find()) {
            String attrs = barM.group(1);
            String val = extractAttr(attrs, "value", "0");
            String min = extractAttr(attrs, "min", "0");
            String max = extractAttr(attrs, "max", "100");
            String color = extractAttr(attrs, "color", "#89b4fa");
            String bg = extractAttr(attrs, "bg", "#181825");
            String label = extractAttr(attrs, "label", "");
            String extraStyle = extractAttr(attrs, "style", "");

            StringBuilder barHtml = new StringBuilder();
            barHtml.append("<div class=\"mc-bar\" style=\"position:relative;overflow:hidden;background-color:").append(bg).append(";").append(extraStyle).append("\">");
            barHtml.append("<progress value=\"").append(val).append("\" min=\"").append(min).append("\" max=\"").append(max)
                    .append("\" style=\"width:100%;height:100%;background-color:transparent;color:").append(color).append(";border-radius:0;\"></progress>");
            if (!label.isEmpty()) {
                barHtml.append("<span style=\"position:absolute;left:0;top:0;width:100%;height:100%;text-align:center;color:#ffffff;text-shadow:true;font-size:0.75rem;\">")
                        .append(label).append("</span>");
            }
            barHtml.append("</div>");
            barM.appendReplacement(sb, Matcher.quoteReplacement(barHtml.toString()));
        }
        barM.appendTail(sb);
        return sb.toString();
    }

    public Document parseDocument(String html, String externalCss, int rootW, int rootH) {
        if (html != null) {
            html = resolveIncludes(html, 0);
            html = expandCustomTags(html);
            if (html.length() > 131072) html = html.substring(0, 131072);
        }
        if (externalCss != null && externalCss.length() > 65536) externalCss = externalCss.substring(0, 65536);

        StringBuilder combinedCss = new StringBuilder();
        if (externalCss != null && !externalCss.isEmpty()) {
            combinedCss.append(externalCss).append("\n");
        }

        StringBuilder extractedScript = new StringBuilder();

        if (html != null) {
            Matcher linkM = LINK_HREF_PATTERN.matcher(html);
            while (linkM.find()) {
                String tagFull = linkM.group(0).toLowerCase();
                if (tagFull.contains("stylesheet") || linkM.group(1).endsWith(".css")) {
                    String href = linkM.group(1);
                    String loadedCss = resourceTextResolver != null ? resourceTextResolver.apply(href) : "";
                    if (loadedCss != null && !loadedCss.isEmpty()) {
                        combinedCss.append(loadedCss).append("\n");
                    }
                }
            }

            Matcher scriptM = SCRIPT_TAG_PATTERN.matcher(html);
            while (scriptM.find()) {
                extractedScript.append(scriptM.group(1)).append("\n");
            }
            html = scriptM.replaceAll("");

            Matcher styleM = STYLE_TAG_PATTERN.matcher(html);
            while (styleM.find()) {
                combinedCss.append(styleM.group(1)).append("\n");
            }
            html = styleM.replaceAll("");
        }

        String authorCss = combinedCss.toString();
        CssParser.StyleSheet sheet = buildStyleSheet(authorCss, rootW);
        HtmlNode htmlRoot = HtmlParser.parse(html == null ? "" : html);
        htmlRoot.isDirty = true;

        return new Document(htmlRoot, sheet, authorCss, extractedScript.toString());
    }

    public List<UIElement> renderDocument(Document doc, int rootW, int rootH, Map<String, String> env) {
        if (clientBoxRenderer == null) return new ArrayList<>();

        Map<String, String> rootStyle = new HashMap<>();
        rootStyle.put("width", String.valueOf(rootW));
        rootStyle.put("min-height", String.valueOf(rootH));
        rootStyle.put("color", "#f5f5f0");
        rootStyle.put("box-sizing", "border-box");

        LayoutBox rootBox = buildLayoutTree(doc.root, rootStyle, doc.sheet, env, false);

        rootBox.computeSize(rootW, rootH, false, false, "stretch", false, rootW, rootH);
        rootBox.computeSize(rootW, rootH, false, false, "stretch", false, rootW, rootH);
        rootBox.layout(0, 0, rootW, rootH, rootW, rootH);

        return clientBoxRenderer.render(rootBox, rootW, rootH, doc.sheet.keyframes);
    }

    private LayoutBox buildLayoutTree(HtmlNode node, Map<String, String> inheritedStyle, CssParser.StyleSheet sheet, Map<String, String> env, boolean forceDirty) {
        if (forceDirty) node.isDirty = true;
        boolean wasDirty = node.isDirty;

        Map<String, String> style = CssParser.computeNodeStyle(node, inheritedStyle, sheet, env);
        if (node.attrs.containsKey("hidden")) {
            style.put("display", "none");
        }
        LayoutBox box = new LayoutBox(node, style, env);

        HtmlNode beforeNode = createPseudoElement(node, sheet, true);
        if (beforeNode != null) {
            box.children.add(buildLayoutTree(beforeNode, style, sheet, env, wasDirty));
        }

        if (!"textarea".equalsIgnoreCase(node.tag)) {
            boolean isClosedDetails = "details".equalsIgnoreCase(node.tag) && !node.attrs.containsKey("open");
            for (HtmlNode child : node.children) {
                if (child.tag.equals("#text") && child.text.trim().isEmpty()) continue;
                if (isClosedDetails && !"summary".equalsIgnoreCase(child.tag)) continue;
                box.children.add(buildLayoutTree(child, style, sheet, env, wasDirty));
            }
        }

        HtmlNode afterNode = createPseudoElement(node, sheet, false);
        if (afterNode != null) {
            box.children.add(buildLayoutTree(afterNode, style, sheet, env, wasDirty));
        }
        return box;
    }

    private HtmlNode createPseudoElement(HtmlNode parent, CssParser.StyleSheet sheet, boolean isBefore) {
        Map<String, String> pseudoStyle = new HashMap<>();
        boolean matched = false;

        for (CssParser.StyleRule rule : sheet.rules) {
            if ((isBefore && rule.isBefore) || (!isBefore && rule.isAfter)) {
                if (rule.matches(parent)) {
                    pseudoStyle.putAll(rule.properties);
                    matched = true;
                }
            }
        }
        if (!matched || !pseudoStyle.containsKey("content")) return null;

        String content = pseudoStyle.get("content");
        if (content.equals("none") || content.equals("normal")) return null;

        HtmlNode pseudo = new HtmlNode("span");
        pseudo.isPseudoNode = true;
        pseudo.isDirty = parent.isDirty;
        pseudo.parent = parent;
        pseudo.id = parent.id + (isBefore ? "_before" : "_after");

        HtmlNode textNode = new HtmlNode("#text");
        textNode.parent = pseudo;
        textNode.isDirty = pseudo.isDirty;
        textNode.id = pseudo.id + "_txt";

        if (content.startsWith("attr(") && content.endsWith(")")) {
            String attrName = content.substring(5, content.length() - 1).trim();
            textNode.text = parent.attrs.getOrDefault(attrName, "");
        } else {
            textNode.text = TRIM_QUOTES_PATTERN.matcher(content).replaceAll("");
        }

        pseudo.children.add(textNode);

        StringBuilder styleStr = new StringBuilder();
        for (Map.Entry<String, String> entry : pseudoStyle.entrySet()) {
            styleStr.append(entry.getKey()).append(":").append(entry.getValue()).append(";");
        }
        pseudo.attrs.put("style", styleStr.toString());

        return pseudo;
    }

    public static class Document {
        public HtmlNode root;
        public CssParser.StyleSheet sheet;
        public String authorCss;
        public String clientScript;

        public Document(HtmlNode root, CssParser.StyleSheet sheet, String authorCss, String clientScript) {
            this.root = root;
            this.sheet = sheet;
            this.authorCss = authorCss;
            this.clientScript = clientScript;
        }
    }
}