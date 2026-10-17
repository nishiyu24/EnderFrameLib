package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssParser;
import com.nishiyu.enderframelib.core.css.StyleResolver;
import com.nishiyu.enderframelib.core.css.ElementState;
import com.nishiyu.enderframelib.core.css.StyleSet;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.dom.HtmlParser;
import com.nishiyu.enderframelib.core.layout.FormattingContext;
import com.nishiyu.enderframelib.core.layout.IntrinsicSizeResolver;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.LayoutContext;
import com.nishiyu.enderframelib.core.layout.ScrollState;
import com.nishiyu.enderframelib.core.layout.Viewport;
import com.nishiyu.enderframelib.core.markdown.MarkdownParser;
import com.nishiyu.enderframelib.core.model.UIElement;
import com.nishiyu.enderframelib.core.text.FontMetrics;
import com.nishiyu.enderframelib.server.EnderFrameDataPackManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@org.jetbrains.annotations.ApiStatus.Internal
public class UIParser {

    private static final Logger LOGGER = LoggerFactory.getLogger(UIParser.class);

    private static final Pattern INCLUDE_PATTERN = Pattern.compile("(?is)<include\\b[^>]*\\bsrc=[\"']([^\"']+)[\"'][^>]*(?:/>|>\\s*</include>)");
    private static final Pattern LINK_HREF_PATTERN = Pattern.compile("(?is)<link\\b[^>]*\\bhref=[\"']([^\"']+)[\"'][^>]*>");
    private static final Pattern SCRIPT_TAG_PATTERN = Pattern.compile("(?is)<script\\b[^>]*>\\s*(.*?)\\s*</script>");
    private static final Pattern STYLE_TAG_PATTERN = Pattern.compile("(?is)<style\\b[^>]*>\\s*(.*?)\\s*</style>");
    private static final Pattern TRIM_QUOTES_PATTERN = Pattern.compile("^[\"']|[\"']$");

    @FunctionalInterface
    public interface ILayoutBoxRenderer {
        List<UIElement> render(LayoutBox rootBox, int rootW, int rootH, Map<String, String> keyframes, ScrollState scrollState);
    }

    /**
     * 測定パスの回数。
     * 1 回目で子の内容幅が分かり、2 回目でそれを踏まえた親の幅が子に伝わる。
     * 3 回目以降で変わるケースは現状確認されていないため 2 で固定している。
     */
    private static final int LAYOUT_PASSES = 2;

    public static ILayoutBoxRenderer clientBoxRenderer = null;
    public static Function<String, String> clientResourceFallback = null;

    /**
     * 置換要素（リモート画像など）の固有サイズ解決。
     * core 側に public static な可変フックを置かず、ここから LayoutContext に載せて渡す。
     */
    public static IntrinsicSizeResolver intrinsicSizeResolver = null;

    /**
     * innerHTML 相当のフラグメント解析。
     * Markdown は文字列段階、カスタムタグはパース後の DOM 段階で展開する。
     */
    public static final Function<String, HtmlNode> INNER_HTML_PARSER = html -> {
        HtmlNode root = HtmlParser.parse(MarkdownParser.processDocumentOrBlocks(html));
        CustomTagExpander.expand(root);
        return root;
    };

    public static Function<String, String> resourceTextResolver = path -> {
        if (path == null) return "";
        String clean = path.trim();
        String builtIn = resolveBuiltInTheme(clean);
        if (builtIn != null) return builtIn;

        String fromDatapack = EnderFrameDataPackManager.getHtml(clean);
        if (fromDatapack != null && !fromDatapack.isEmpty()) return fromDatapack;

        if (clientResourceFallback != null) {
            String fromClient = clientResourceFallback.apply(clean);
            if (fromClient != null && !fromClient.isEmpty()) return fromClient;
        }

        if (clean.contains(":")) {
            String[] sp = clean.split(":", 2);
            String namespace = sanitizeNamespace(sp[0]);
            String resourcePath = sanitizeResourcePath(sp[1]);
            // クラスパスの読み出しなので、".." や "/" 始まりを通すと jar 外（他 Mod のリソース）に出られる
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

    private static final Pattern SAFE_NAMESPACE = Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final Pattern SAFE_RESOURCE_PATH = Pattern.compile("[a-zA-Z0-9_./-]{1,256}");

    /** リソース名前空間の検証。Minecraft の名前空間と同じ文字種だけを許す。 */
    private static String sanitizeNamespace(String raw) {
        if (raw == null) return null;
        String value = raw.trim().toLowerCase();
        return SAFE_NAMESPACE.matcher(value).matches() ? value : null;
    }

    /**
     * クラスパス相対パスの正規化（I-9）。
     * 絶対パス・親ディレクトリ参照・バックスラッシュ・空セグメントを一切通さない。
     */
    private static String sanitizeResourcePath(String raw) {
        if (raw == null) return null;
        String value = raw.trim().replace('\\', '/');
        while (value.startsWith("/")) value = value.substring(1);
        if (value.isEmpty() || !SAFE_RESOURCE_PATH.matcher(value).matches()) return null;
        for (String segment : value.split("/")) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) return null;
        }
        return value;
    }

    private static String readClasspath(String absolutePath) {
        try (InputStream is = UIParser.class.getResourceAsStream(absolutePath)) {
            if (is != null) return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("リソースの読み込みに失敗しました: {} ({})", absolutePath, e.getMessage());
        }
        return null;
    }

    public UIParser() {}

    /** 組み込みテーマ名 -> href の末尾として受け付ける形。 */
    private static final String[] BUILT_IN_THEMES = {"dark", "vanilla", "brass"};

    /**
     * 組み込みテーマの href を解決する。
     * CSS 本体は resources/assets/enderframelib/css/theme/*.css が単一の出所。
     */
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

    /**
     * UA スタイルシート。本体は resources/assets/enderframelib/css/ua.css。
     * 以前は約 60 行の CSS が Java の文字列リテラルとして埋め込まれていた。
     */
    public static String userAgentCss() {
        return CssResources.load(CssResources.USER_AGENT);
    }

    public com.nishiyu.enderframelib.core.css.StyleSheet buildStyleSheet(String authorCss, int rootW) {
        com.nishiyu.enderframelib.core.css.StyleSheet sheet =
                CssParser.parse(userAgentCss(), rootW, com.nishiyu.enderframelib.core.css.CascadeOrigin.USER_AGENT);

        if (authorCss != null && !authorCss.isEmpty()) {
            com.nishiyu.enderframelib.core.css.StyleSheet authorSheet = CssParser.parse(authorCss, rootW);
            for (com.nishiyu.enderframelib.core.css.StyleRule rule : authorSheet.rules) {
                rule.setOrigin(com.nishiyu.enderframelib.core.css.CascadeOrigin.AUTHOR);
            }
            sheet.rules.addAll(authorSheet.rules);
            sheet.keyframes.putAll(authorSheet.keyframes);
            sheet.variables.putAll(authorSheet.variables);
        }

        sheet.rules.sort(com.nishiyu.enderframelib.core.css.StyleRule::compareSpecificity);
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

    public Document parseDocument(String html, String externalCss, int rootW, int rootH) {
        if (html != null) {
            html = resolveIncludes(html, 0);
            html = MarkdownParser.processDocumentOrBlocks(html);
            if (html.length() > 131072) html = html.substring(0, 131072);
        }
        if (externalCss != null && externalCss.length() > 65536) externalCss = externalCss.substring(0, 65536);

        StringBuilder combinedCss = new StringBuilder();
        if (externalCss != null && !externalCss.isEmpty()) {
            combinedCss.append(externalCss).append("\n");
        }

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
            html = scriptM.replaceAll("");

            Matcher styleM = STYLE_TAG_PATTERN.matcher(html);
            while (styleM.find()) {
                combinedCss.append(styleM.group(1)).append("\n");
            }
            html = styleM.replaceAll("");
        }

        String authorCss = combinedCss.toString();
        com.nishiyu.enderframelib.core.css.StyleSheet sheet = buildStyleSheet(authorCss, rootW);
        HtmlNode htmlRoot = HtmlParser.parse(html == null ? "" : html);
        // <mc-slot> などのマクロは、正規表現での文字列置換ではなく DOM の部分木差し替えで展開する
        CustomTagExpander.expand(htmlRoot);

        return new Document(htmlRoot, sheet, authorCss);
    }

    public List<UIElement> renderDocument(Document doc, int rootW, int rootH, Map<String, String> env, ScrollState scrollState, FontMetrics fontMetrics) {
        if (clientBoxRenderer == null) return new ArrayList<>();

        Map<String, String> rootStyleRaw = new HashMap<>();
        rootStyleRaw.put("width", String.valueOf(rootW));
        rootStyleRaw.put("min-height", String.valueOf(rootH));
        rootStyleRaw.put("color", "#f5f5f0");
        rootStyleRaw.put("box-sizing", "border-box");

        LayoutBox rootBox = buildLayoutTree(doc.root, rootStyleRaw, doc.sheet, env, false);

        Viewport viewport = new Viewport(rootW, rootH);
        LayoutContext ctx = new LayoutContext(viewport, intrinsicSizeResolver, scrollState, fontMetrics);
        FormattingContext fmtCtx = new FormattingContext("block", "stretch", false);

        // 測定は 1 パスでは収束しない。shrink-to-fit の子は親の幅が決まらないと最終幅が決まらず、
        // 親の幅はその子の幅から決まるという相互依存があるため、同じパスを規定回数だけ回す。
        // （以前は意図の説明がないまま measure() が 2 回並んでいた）
        for (int pass = 0; pass < LAYOUT_PASSES; pass++) {
            rootBox.measure(rootW, rootH, fmtCtx, ctx);
        }
        rootBox.layout(0, 0, rootW, rootH, ctx);

        return clientBoxRenderer.render(rootBox, rootW, rootH, doc.sheet.keyframes, scrollState);
    }

    private LayoutBox buildLayoutTree(HtmlNode node, Map<String, String> inheritedStyle, com.nishiyu.enderframelib.core.css.StyleSheet sheet, Map<String, String> env, boolean forceDirty) {
        return buildLayoutTree(node, inheritedStyle, sheet, env, forceDirty, ComputedStyle.DEFAULT_FONT_SIZE);
    }

    private LayoutBox buildLayoutTree(HtmlNode node, Map<String, String> inheritedStyle, com.nishiyu.enderframelib.core.css.StyleSheet sheet, Map<String, String> env, boolean forceDirty, float parentFontSize) {
        StyleSet styleSet = StyleResolver.resolveStyleSet(node, inheritedStyle, sheet);
        // 対話状態はビュー側（InteractionState）が持つため、レイアウト構築時は素の状態で解決する
        Map<String, String> rawStyle = styleSet.resolve(ElementState.NONE);
        if (node.attrs.containsKey("hidden")) {
            rawStyle.put(CssProperty.DISPLAY, "none");
        }

        // em は要素自身、rem はルートのフォントサイズが基準になるよう親の値を引き継ぐ
        ComputedStyle computed = StyleResolver.finalizeComputedStyle(
                rawStyle, sheet, env, parentFontSize, ComputedStyle.DEFAULT_FONT_SIZE);
        LayoutBox box = new LayoutBox(node, computed);

        HtmlNode beforeNode = createPseudoElement(node, sheet, true);
        if (beforeNode != null) box.children.add(buildLayoutTree(beforeNode, rawStyle, sheet, env, forceDirty, computed.fontSizePx()));

        if (!"textarea".equalsIgnoreCase(node.tag)) {
            boolean isClosedDetails = "details".equalsIgnoreCase(node.tag) && !node.attrs.containsKey("open");
            for (HtmlNode child : node.children) {
                if (child.tag.equals("#text") && child.text.trim().isEmpty()) continue;
                if (isClosedDetails && !"summary".equalsIgnoreCase(child.tag)) continue;
                box.children.add(buildLayoutTree(child, rawStyle, sheet, env, forceDirty, computed.fontSizePx()));
            }
        }

        HtmlNode afterNode = createPseudoElement(node, sheet, false);
        if (afterNode != null) box.children.add(buildLayoutTree(afterNode, rawStyle, sheet, env, forceDirty, computed.fontSizePx()));

        return box;
    }

    private HtmlNode createPseudoElement(HtmlNode parent, com.nishiyu.enderframelib.core.css.StyleSheet sheet, boolean isBefore) {
        Map<String, String> pseudoStyle = new HashMap<>();
        boolean matched = false;

        for (com.nishiyu.enderframelib.core.css.StyleRule rule : sheet.rules) {
            if ((isBefore && rule.isBefore) || (!isBefore && rule.isAfter)) {
                if (rule.matches(parent)) {
                    pseudoStyle.putAll(rule.properties);
                    matched = true;
                }
            }
        }
        if (!matched || !pseudoStyle.containsKey(CssProperty.CONTENT)) return null;

        String content = pseudoStyle.get(CssProperty.CONTENT);
        if (content.equals("none") || content.equals("normal")) return null;

        HtmlNode pseudo = new HtmlNode("span");
        pseudo.isPseudoNode = true;
        pseudo.parent = parent;
        pseudo.setId(parent.getId() + (isBefore ? "_before" : "_after"));

        HtmlNode textNode = HtmlNode.createTextNode("");
        textNode.parent = pseudo;
        textNode.setId(pseudo.getId() + "_txt");

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

    @org.jetbrains.annotations.ApiStatus.Internal
    public static class Document implements com.nishiyu.enderframelib.api.WebDocument {
        public HtmlNode root;
        public com.nishiyu.enderframelib.core.css.StyleSheet sheet;
        public String authorCss;

        public Document(HtmlNode root, com.nishiyu.enderframelib.core.css.StyleSheet sheet, String authorCss) {
            this.root = root;
            this.sheet = sheet;
            this.authorCss = authorCss;
        }

        @Override
        public String authorCss() {
            return authorCss != null ? authorCss : "";
        }

        @Override
        public boolean hasElement(String elementId) {
            return find(elementId) != null;
        }

        @Override
        public String tagOf(String elementId) {
            HtmlNode node = find(elementId);
            return node != null ? node.tag : null;
        }

        @Override
        public String attributeOf(String elementId, String attributeName) {
            HtmlNode node = find(elementId);
            return node != null && attributeName != null ? node.attrs.get(attributeName) : null;
        }

        private HtmlNode find(String elementId) {
            if (root == null || elementId == null || elementId.isEmpty()) return null;
            return root.getElementById(elementId);
        }
    }
}