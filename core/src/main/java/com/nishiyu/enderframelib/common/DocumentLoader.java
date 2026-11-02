package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.css.CssParser;
import com.nishiyu.enderframelib.core.css.StyleSheet;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.parser.HtmlParser;
import com.nishiyu.enderframelib.core.dom.parser.MarkdownParser;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import org.jetbrains.annotations.ApiStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文書の読み込み（HTML/Markdown のパース・スタイルシート構築・外部CSS収集）。
 */
@ApiStatus.Internal
public class DocumentLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentLoader.class);

    private static final Pattern INCLUDE_PATTERN = Pattern.compile("(?is)<include\\b[^>]*\\bsrc=[\"']([^\"']+)[\"'][^>]*(?:/>|>\\s*</include>)");

    public static Function<String, String> clientResourceFallback = null;

    /** サーバー（データパック）側リソースを解決するための依存性注入用フック */
    public static Function<String, String> datapackResourceFallback = null;

    /**
     * innerHTML 相当のフラグメント解析（引数は HTML と文脈の要素のタグ）。
     * Markdown は文字列段階、カスタムタグはパース後の DOM 段階で展開する。
     */
    public static final BiFunction<String, String, HtmlNode> INNER_HTML_PARSER = (html, contextTag) -> {
        HtmlNode root = HtmlParser.parseFragment(MarkdownParser.processDocumentOrBlocks(html), contextTag);
        CustomTagExpander.expand(root);
        return root;
    };

    public static final Function<String, String> DEFAULT_RESOURCE_TEXT_RESOLVER = path -> {
        if (path == null) return "";
        String clean = path.trim();
        String builtIn = resolveBuiltInTheme(clean);
        if (builtIn != null) return builtIn;

        Function<String, String> dpFallback = datapackResourceFallback != null
                ? datapackResourceFallback : UiDocumentCompiler.datapackResourceFallback;
        if (dpFallback != null) {
            String fromDatapack = dpFallback.apply(clean);
            if (fromDatapack != null && !fromDatapack.isEmpty()) return fromDatapack;
        }

        Function<String, String> clFallback = clientResourceFallback != null
                ? clientResourceFallback : UiDocumentCompiler.clientResourceFallback;
        if (clFallback != null) {
            String fromClient = clFallback.apply(clean);
            if (fromClient != null && !fromClient.isEmpty()) return fromClient;
        }

        if (clean.contains(":")) {
            String[] sp = clean.split(":", 2);
            String namespace = sanitizeNamespace(sp[0]);
            String resourcePath = sanitizeResourcePath(sp[1]);
            if (namespace == null || resourcePath == null) {
                LOGGER.warn("不正なリソースパスを拒否しました: {}", clean);
                return "";
            }
            String fromClasspath = readClasspath("/assets/" + namespace + "/" + resourcePath);
            if (fromClasspath != null) return fromClasspath;
            fromClasspath = readClasspath("/data/" + namespace + "/enderframe_ui/" + resourcePath);
            if (fromClasspath != null) return fromClasspath;
        }
        return "";
    };

    public static Function<String, String> resourceTextResolver = DEFAULT_RESOURCE_TEXT_RESOLVER;

    public static Function<String, String> effectiveResourceTextResolver() {
        if (UiDocumentCompiler.resourceTextResolver != null && UiDocumentCompiler.resourceTextResolver != DEFAULT_RESOURCE_TEXT_RESOLVER) {
            return UiDocumentCompiler.resourceTextResolver;
        }
        return resourceTextResolver != null ? resourceTextResolver : DEFAULT_RESOURCE_TEXT_RESOLVER;
    }

    private static final Pattern SAFE_NAMESPACE = Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final Pattern SAFE_RESOURCE_PATH = Pattern.compile("[a-zA-Z0-9_./-]{1,256}");

    public static String sanitizeNamespace(String raw) {
        if (raw == null) return null;
        String value = raw.trim().toLowerCase();
        return SAFE_NAMESPACE.matcher(value).matches() ? value : null;
    }

    public static String sanitizeResourcePath(String raw) {
        if (raw == null) return null;
        String value = raw.trim().replace('\\', '/');
        while (value.startsWith("/")) value = value.substring(1);
        if (value.isEmpty() || !SAFE_RESOURCE_PATH.matcher(value).matches()) return null;
        for (String segment : value.split("/")) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) return null;
        }
        return value;
    }

    public static String readClasspath(String absolutePath) {
        try (InputStream is = DocumentLoader.class.getResourceAsStream(absolutePath)) {
            if (is != null) return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("リソースの読み込みに失敗しました: {} ({})", absolutePath, e.getMessage());
        }
        return null;
    }

    private static final String[] BUILT_IN_THEMES = {"dark", "vanilla", "brass"};

    public static String resolveBuiltInTheme(String href) {
        if (href == null) return null;
        String lower = href.toLowerCase().trim();
        for (String name : BUILT_IN_THEMES) {
            if (lower.endsWith("theme/" + name + ".css")
                    || lower.endsWith("themes/" + name + ".css")
                    || lower.equals("enderframelib:" + name + ".css")) {
                String css = CssResources.load(CssResources.themePath(name));
                return css.isEmpty() ? null : css;
            }
        }
        return null;
    }

    public static String userAgentCss() {
        return CssResources.load(CssResources.USER_AGENT);
    }

    public static StyleSheet buildStyleSheet(String authorCss, int rootW) {
        return buildStyleSheet(authorCss, rootW, 0);
    }

    public static StyleSheet buildStyleSheet(String authorCss, int rootW, int rootH) {
        return buildStyleSheet(null, authorCss, rootW, rootH);
    }

    public static StyleSheet buildStyleSheet(String userCss, String authorCss, int rootW, int rootH) {
        CssParser.MediaEnvironment media =
                CssParser.MediaEnvironment.of(rootW, rootH)
                        .withImportResolver(effectiveResourceTextResolver());
        StyleSheet.CascadeOrigin userAgent =
                StyleSheet.CascadeOrigin.USER_AGENT;
        StyleSheet sheet = CssParser.parse(userAgentCss(), media, userAgent);

        if (userCss != null && !userCss.isEmpty()) {
            sheet.merge(CssParser.parse(userCss, media, StyleSheet.CascadeOrigin.USER));
        }
        if (authorCss != null && !authorCss.isEmpty()) {
            sheet.merge(CssParser.parse(authorCss, media, StyleSheet.CascadeOrigin.AUTHOR));
        }

        sheet.updateMediaEnvironment(media);
        sheet.sortCascade();
        return sheet;
    }

    public static String resolveIncludes(String html, int depth) {
        if (html == null || depth > 5 || !html.contains("<include")) return html;
        Matcher incM = INCLUDE_PATTERN.matcher(html);
        StringBuilder sb = new StringBuilder();
        boolean found = false;
        while (incM.find()) {
            found = true;
            String src = incM.group(1);
            Function<String, String> res = effectiveResourceTextResolver();
            String loaded = res != null ? res.apply(src) : "";
            if (loaded != null && src.toLowerCase().endsWith(".md")) {
                loaded = MarkdownParser.toHtml(loaded);
            }
            incM.appendReplacement(sb, Matcher.quoteReplacement(loaded != null ? loaded : ""));
        }
        incM.appendTail(sb);
        return found ? resolveIncludes(sb.toString(), depth + 1) : html;
    }

    public static Document parseDocument(String html, String externalCss, int rootW, int rootH) {
        if (html != null) {
            html = resolveIncludes(html, 0);
            html = MarkdownParser.processDocumentOrBlocks(html);
            if (html.length() > 131072) html = html.substring(0, 131072);
        }
        if (externalCss != null && externalCss.length() > 65536) externalCss = externalCss.substring(0, 65536);

        HtmlNode htmlRoot = HtmlParser.parseDocument(html == null ? "" : html);
        CustomTagExpander.expand(htmlRoot);

        StringBuilder combinedCss = new StringBuilder();
        if (externalCss != null && !externalCss.isEmpty()) {
            combinedCss.append(externalCss).append("\n");
        }
        collectStyleSheets(htmlRoot, combinedCss);

        String authorCss = combinedCss.toString();
        StyleSheet sheet = buildStyleSheet(authorCss, rootW, rootH);
        return new Document(htmlRoot, sheet, authorCss);
    }

    public static void collectStyleSheets(HtmlNode node, StringBuilder css) {
        for (HtmlNode child : node.children) {
            if (child.nodeType != NodeType.ELEMENT || "template".equals(child.tag)) continue;
            if ("style".equals(child.tag)) {
                css.append(child.getTextContent().trim()).append("\n");
            } else if ("link".equals(child.tag)) {
                String href = child.attrs.get("href");
                String rel = child.attrs.getOrDefault("rel", "").toLowerCase(Locale.ROOT);
                if (href != null && (rel.contains("stylesheet") || href.endsWith(".css"))) {
                    Function<String, String> res = effectiveResourceTextResolver();
                    String loadedCss = res != null ? res.apply(href) : "";
                    if (loadedCss != null && !loadedCss.isEmpty()) css.append(loadedCss).append("\n");
                }
            }
            collectStyleSheets(child, css);
        }
    }
}
