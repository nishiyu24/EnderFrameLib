package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.client.gui.AbstractWebScreen;
import com.nishiyu.enderframelib.client.renderer.UIRenderer;
import com.nishiyu.enderframelib.core.css.CssParser;
import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.dom.HtmlParser;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.model.UIElement;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UIParser {

    /**
     * ResourceLocation 形式のパス ("modid:path") からテキストリソースを読み込むリゾルバ
     */
    public static Function<String, String> resourceTextResolver = path -> {
        try {
            ResourceLocation loc = ResourceLocation.parse(path.trim());
            return AbstractWebScreen.loadHtmlResource(loc);
        } catch (Exception e) {
            return "";
        }
    };

    public UIParser() {}

    public CssParser.StyleSheet buildStyleSheet(String authorCss, int rootW) {
        String defaultCss = """
                    body, html { background-color: #ffffff; color: #333333; margin: 0; padding: 0; width: 100%; min-height: 100%; box-sizing: border-box; overflow: auto; }
                    head, meta, title, link, style, script, template, option { display: none; }
                    h1 { font-size: 2rem; font-weight: bold; margin: 0.67em 0; display: block; }
                    h2 { font-size: 1.5rem; font-weight: bold; margin: 0.83em 0; display: block; }
                    h3 { font-size: 1.17rem; font-weight: bold; margin: 1em 0; display: block; }
                    p { margin: 1em 0; display: block; }
                    ul { padding-left: 40px; margin: 1em 0; display: block; }
                    li { display: block; }
                    a { color: #0000ee; text-decoration: none; }
                    b, strong { font-weight: bold; }
                    img, canvas { display: inline-block; object-fit: fill; }
                    div, section, main, header, footer, article, nav { display: block; }
                    hr { display: block; border: none; border-top: 1px solid #777777; margin: 10px 0; width: 100%; height: 0; }
                    button { display: inline-block; cursor: pointer; background-color: #f0f0f0; color: #000000; border: 1px solid #767676; padding: 2px 6px; border-radius: 2px; transition: 0.1s; }
                    button:hover { background-color: #e5e5e5; }
                    button:active { background-color: #d5d5d5; border-color: #4f4f4f; transform: scale(0.95); }
                    input, select, textarea { display: inline-block; background-color: #ffffff; color: #000000; border: 1px solid #777777; padding: 4px; box-sizing: border-box; border-radius: 2px; transition: 0.15s; }
                    textarea { display: block; width: 100%; height: 120px; overflow-y: auto; }
                    input:focus, select:hover, textarea:focus { background-color: #f8fbff; }
                    input[type="checkbox"], input[type="radio"] { width: 16px; height: 16px; padding: 0; border: 2px solid #555555; transition: 0.1s; }
                    input[type="checkbox"]:hover { border-color: #aaaaaa; }
                    input[type="range"] { background-color: #ffffff; color: #007bff; border: none; padding: 0; height: 16px; border-radius: 8px; }
                    progress { display: inline-block; background-color: #333333; color: #28a745; border: none; padding: 0; width: 120px; height: 12px; border-radius: 6px; }
                """;

        CssParser.StyleSheet sheet = CssParser.parse(defaultCss, rootW);

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
        Matcher incM = Pattern.compile("(?is)<include\\b[^>]*\\bsrc=[\"']([^\"']+)[\"'][^>]*(?:/>|>\\s*</include>)").matcher(html);
        StringBuilder sb = new StringBuilder();
        boolean found = false;
        while (incM.find()) {
            found = true;
            String src = incM.group(1);
            String loaded = resourceTextResolver != null ? resourceTextResolver.apply(src) : "";
            incM.appendReplacement(sb, Matcher.quoteReplacement(loaded != null ? loaded : ""));
        }
        incM.appendTail(sb);
        return found ? resolveIncludes(sb.toString(), depth + 1) : html;
    }

    public Document parseDocument(String html, String externalCss, int rootW, int rootH) {
        if (html != null) {
            html = resolveIncludes(html, 0);
            if (html.length() > 131072) html = html.substring(0, 131072);
        }
        if (externalCss != null && externalCss.length() > 65536) externalCss = externalCss.substring(0, 65536);

        StringBuilder combinedCss = new StringBuilder();
        if (externalCss != null) combinedCss.append(externalCss).append("\n");

        StringBuilder extractedScript = new StringBuilder();

        if (html != null) {
            // <link rel="stylesheet" href="modid:path/to.css"> の解決
            Matcher linkM = Pattern.compile("(?is)<link\\b[^>]*\\bhref=[\"']([^\"']+)[\"'][^>]*>").matcher(html);
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

            Matcher scriptM = Pattern.compile("(?is)<script\\b[^>]*>\\s*(.*?)\\s*</script>").matcher(html);
            while (scriptM.find()) {
                extractedScript.append(scriptM.group(1)).append("\n");
            }
            html = scriptM.replaceAll("");

            Matcher styleM = Pattern.compile("(?is)<style\\b[^>]*>\\s*(.*?)\\s*</style>").matcher(html);
            while (styleM.find()) combinedCss.append(styleM.group(1)).append("\n");
            html = styleM.replaceAll("");
        }

        String authorCss = combinedCss.toString();
        CssParser.StyleSheet sheet = buildStyleSheet(authorCss, rootW);
        HtmlNode htmlRoot = HtmlParser.parse(html == null ? "" : html);
        htmlRoot.isDirty = true;

        return new Document(htmlRoot, sheet, authorCss, extractedScript.toString());
    }

    public List<UIElement> renderDocument(Document doc, int rootW, int rootH, Map<String, String> env) {
        Map<String, String> rootStyle = new HashMap<>();
        rootStyle.put("width", String.valueOf(rootW));
        rootStyle.put("min-height", String.valueOf(rootH));
        rootStyle.put("color", "#f5f5f0");
        rootStyle.put("box-sizing", "border-box");

        LayoutBox rootBox = buildLayoutTree(doc.root, rootStyle, doc.sheet, env, false);

        rootBox.computeSize(rootW, rootH, false, false, "stretch", false, rootW, rootH);
        rootBox.computeSize(rootW, rootH, false, false, "stretch", false, rootW, rootH);
        rootBox.layout(0, 0, rootW, rootH, rootW, rootH);

        UIRenderer renderer = new UIRenderer(rootW, rootH, doc.sheet.keyframes);
        return renderer.render(rootBox);
    }

    private LayoutBox buildLayoutTree(HtmlNode node, Map<String, String> inheritedStyle, CssParser.StyleSheet sheet, Map<String, String> env, boolean forceDirty) {
        if (forceDirty) node.isDirty = true;
        boolean wasDirty = node.isDirty;

        Map<String, String> style = CssParser.computeNodeStyle(node, inheritedStyle, sheet, env);
        LayoutBox box = new LayoutBox(node, style, env);

        HtmlNode beforeNode = createPseudoElement(node, sheet, true);
        if (beforeNode != null) {
            box.children.add(buildLayoutTree(beforeNode, style, sheet, env, wasDirty));
        }

        // textarea の場合は内部の #text を個別レイアウトボックス化せず、自身の value として扱う
        if (!"textarea".equalsIgnoreCase(node.tag)) {
            for (HtmlNode child : node.children) {
                if (child.tag.equals("#text") && child.text.trim().isEmpty()) continue;
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
            textNode.text = content.replaceAll("^[\"']|[\"']$", "");
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