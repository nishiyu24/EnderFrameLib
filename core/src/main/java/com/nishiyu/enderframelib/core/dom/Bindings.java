package com.nishiyu.enderframelib.core.dom;


import com.nishiyu.enderframelib.core.dom.node.*;
import com.nishiyu.enderframelib.core.dom.node.HtmlDocument.HtmlDocumentFragment;

import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * テンプレート置換および宣言的バインディング（data-*）を一元的に解釈する純 Java エンジン。
 *
 * <p>STRUCTURE_PLAN S6 に準拠し、DOM に対するバインディング処理（{{...}} プレースホルダ、
 * 国際化属性、モデル同期、属性・スタイル・条件付き表示、繰り返しリスト、仮想リスト構築など）の
 * 解釈をこの 1 クラスに集約する。</p>
 *
 * <p>状態の値の DOM への同期は {@link ReactiveDomBinder}、キー単位の依存追跡は {@link TemplateDeps} にある。</p>
 */
public final class Bindings {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{([^}]+)}}");
    private static final Pattern LOCAL_PLACEHOLDER = Pattern.compile("\\{\\{@([a-zA-Z0-9_.:-]+)}}");
    static final Pattern FOR_EXPR_PATTERN = Pattern.compile("^([a-zA-Z0-9_]+)\\s+in\\s+([a-zA-Z0-9_.-]+)$");
    private static final Pattern COMPARE_EXPR_PATTERN = Pattern.compile("^([a-zA-Z0-9_.-]+)\\s*(==|!=|>=|<=|>|<)\\s*(.+)$");


    private Bindings() {}

    // ------------------------------------------------------------------
    // プレースホルダ解決（旧 TemplateEngine）
    // ------------------------------------------------------------------

    /**
     * テンプレート走査の共通実装。
     */
    public static void applyTemplate(HtmlNode node, Function<String, String> replacer) {
        if (node == null || replacer == null) return;

        if (node.text != null && node.text.contains("{{")) {
            node.text = replacer.apply(node.text);
        }

        for (String key : new ArrayList<>(node.attrs.keySet())) {
            String val = node.attrs.get(key);
            if (val != null && val.contains("{{")) {
                DomUtils.updateAttribute(node, key, replacer.apply(val), false);
            }
        }

        if (node.attrs.containsKey("id")) {
            node.setId(node.attrs.get("id"));
        }

        for (HtmlNode child : node.children) {
            applyTemplate(child, replacer);
        }
    }

    /** {@code key, arg1, arg2} を分ける。引用符で囲んだ引数の中のカンマは区切りにしない。 */
    static List<String> splitI18n(String body) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (char c : body.toCharArray()) {
            if (quote != 0) {
                if (c == quote) quote = 0;
                cur.append(c);
            } else if (c == '\'' || c == '"') {
                quote = c;
                cur.append(c);
            } else if (c == ',') {
                parts.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        parts.add(cur.toString().trim());
        return parts;
    }

    /** 引数。状態にあるキーならその値、引用符つきなら中身、それ以外は書かれたまま。 */
    private static String i18nArg(String arg, Map<String, String> data) {
        if (arg.length() >= 2 && (arg.charAt(0) == '\'' || arg.charAt(0) == '"') && arg.charAt(arg.length() - 1) == arg.charAt(0)) {
            return arg.substring(1, arg.length() - 1);
        }
        return data != null && data.containsKey(arg) ? data.get(arg) : arg;
    }

    /**
     * i18n、ローカル環境変数（@key）、およびスコープデータ（key）の展開を一元的に処理します。
     */
    public static String resolve(String text, Map<String, String> data, Map<String, String> env) {
        if (text == null || !text.contains("{{")) return text != null ? text : "";

        Matcher m = PLACEHOLDER_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String expr = m.group(1).trim();
            String replacement = null;

            if (expr.startsWith("i18n:") || expr.startsWith("@i18n:")) {
                List<String> parts = splitI18n(expr.substring(expr.indexOf(':') + 1));
                if (parts.size() == 1) {
                    replacement = BindingHooks.current().i18nResolver().apply(parts.get(0));
                } else {
                    Object[] args = new Object[parts.size() - 1];
                    for (int i = 1; i < parts.size(); i++) args[i - 1] = i18nArg(parts.get(i), data);
                    replacement = BindingHooks.current().i18nFormatter().apply(parts.get(0), args);
                }
            } else if (expr.contains("|") && expr.toLowerCase(Locale.ROOT).endsWith("i18n")) {
                String rawKey = expr.substring(0, expr.indexOf('|')).trim().replaceAll("^['\"]+|['\"]+$", "");
                String resolvedKey = data != null ? data.getOrDefault(rawKey, rawKey) : rawKey;
                replacement = BindingHooks.current().i18nResolver().apply(resolvedKey);
            } else if (expr.startsWith("@")) {
                if (env != null && env.containsKey(expr)) {
                    replacement = env.get(expr);
                } else {
                    replacement = m.group(0);
                }
            } else {
                if (data != null && data.containsKey(expr)) {
                    replacement = data.get(expr);
                } else {
                    replacement = m.group(0);
                }
            }

            if (replacement == null) replacement = m.group(0);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 国際化属性（data-i18n*）
    // ------------------------------------------------------------------

    public static boolean applyI18nAttributes(HtmlNode vNode) {
        if (vNode == null) return false;
        boolean changed = false;

        if (vNode.attrs.containsKey("data-i18n")) {
            String key = vNode.attrs.get("data-i18n").trim();
            if (!key.isEmpty()) {
                String tr = BindingHooks.current().i18nResolver().apply(key);
                if (!Objects.equals(vNode.getTextContent(), tr)) {
                    vNode.updateTextContent(tr);
                    changed = true;
                }
            }
        }
        if (vNode.attrs.containsKey("data-i18n-placeholder")) {
            String tr = BindingHooks.current().i18nResolver().apply(vNode.attrs.get("data-i18n-placeholder").trim());
            if (!Objects.equals(vNode.attrs.get("placeholder"), tr)) {
                vNode.setAttribute("placeholder", tr);
                changed = true;
            }
        }
        if (vNode.attrs.containsKey("data-i18n-title")) {
            String tr = BindingHooks.current().i18nResolver().apply(vNode.attrs.get("data-i18n-title").trim());
            if (!Objects.equals(vNode.attrs.get("title"), tr)) {
                vNode.setAttribute("title", tr);
                changed = true;
            }
        }
        return changed;
    }

    // ------------------------------------------------------------------
    // ローカル環境変数の収集と適用
    // ------------------------------------------------------------------

    public static void collectLocalVariableKeys(HtmlNode node, Set<String> keys) {
        if (node == null || keys == null) return;
        if (node.text != null && node.text.contains("{{@")) {
            Matcher m = LOCAL_PLACEHOLDER.matcher(node.text);
            while (m.find()) {
                String rawKey = m.group(1);
                if (rawKey.startsWith("i18n:")) keys.add("@lang");
                else keys.add("@" + rawKey);
            }
        }
        if (node.attrs.containsKey("data-i18n")
                || node.attrs.containsKey("data-i18n-placeholder")
                || node.attrs.containsKey("data-i18n-title")) {
            keys.add("@lang");
        }
        for (String val : node.attrs.values()) {
            if (val != null && val.contains("{{@")) {
                Matcher m = LOCAL_PLACEHOLDER.matcher(val);
                while (m.find()) {
                    String rawKey = m.group(1);
                    if (rawKey.startsWith("i18n:")) keys.add("@lang");
                    else keys.add("@" + rawKey);
                }
            }
        }
        for (HtmlNode child : node.children) {
            collectLocalVariableKeys(child, keys);
        }
    }

    public static boolean applyLocalVariables(HtmlNode origNode, HtmlNode vNode, Map<String, String> env) {
        if (origNode == null || vNode == null) return false;
        boolean changed = applyI18nAttributes(vNode);

        if (origNode.text != null && (origNode.text.contains("{{@") || origNode.text.contains("{{i18n:"))) {
            String resolved = resolve(origNode.text, null, env);
            if (!resolved.equals(vNode.text)) {
                vNode.text = resolved;
                changed = true;
            }
        }

        for (String key : new ArrayList<>(origNode.attrs.keySet())) {
            String origVal = origNode.attrs.get(key);
            if (origVal != null && (origVal.contains("{{@") || origVal.contains("{{i18n:"))) {
                String resolved = resolve(origVal, null, env);
                if (!resolved.equals(vNode.attrs.get(key))) {
                    vNode.setAttribute(key, resolved);
                    changed = true;
                }
            }
        }

        int min = Math.min(origNode.children.size(), vNode.children.size());
        for (int i = 0; i < min; i++) {
            if (applyLocalVariables(origNode.children.get(i), vNode.children.get(i), env)) {
                changed = true;
            }
        }
        return changed;
    }

    // ------------------------------------------------------------------
    // 条件評価（旧 ReactiveStateStore.evaluateCondition）
    // ------------------------------------------------------------------

    public static Boolean evaluateCondition(String expr, Map<String, String> scopeData) {
        if (expr == null || expr.isEmpty()) return null;
        Matcher cm = COMPARE_EXPR_PATTERN.matcher(expr);
        if (cm.matches()) {
            String key = cm.group(1).trim();
            String op = cm.group(2).trim();
            String rhsRaw = cm.group(3).trim().replaceAll("^['\"]+|['\"]+$", "");
            if (scopeData == null || !scopeData.containsKey(key)) return null;
            String lhsRaw = scopeData.getOrDefault(key, "");

            try {
                double lhsNum = Double.parseDouble(lhsRaw);
                double rhsNum = Double.parseDouble(rhsRaw);
                return switch (op) {
                    case "==" -> Math.abs(lhsNum - rhsNum) < 1e-6;
                    case "!=" -> Math.abs(lhsNum - rhsNum) >= 1e-6;
                    case ">" -> lhsNum > rhsNum;
                    case ">=" -> lhsNum >= rhsNum;
                    case "<" -> lhsNum < rhsNum;
                    case "<=" -> lhsNum <= rhsNum;
                    default -> false;
                };
            } catch (NumberFormatException ignored) {
                int cmp = lhsRaw.compareTo(rhsRaw);
                return switch (op) {
                    case "==" -> lhsRaw.equals(rhsRaw);
                    case "!=" -> !lhsRaw.equals(rhsRaw);
                    case ">" -> cmp > 0;
                    case ">=" -> cmp >= 0;
                    case "<" -> cmp < 0;
                    case "<=" -> cmp <= 0;
                    default -> false;
                };
            }
        }

        boolean invert = expr.startsWith("!");
        String key = invert ? expr.substring(1).trim() : expr;
        if (scopeData == null || !scopeData.containsKey(key)) return null;
        String val = scopeData.getOrDefault(key, "");
        boolean truthy = !val.isEmpty() && !"false".equalsIgnoreCase(val) && !"0".equals(val);
        return invert != truthy;
    }

    // ------------------------------------------------------------------
    // テンプレート子要素抽出・ID一意化
    // ------------------------------------------------------------------

    public static List<HtmlNode> extractTemplateChildren(HtmlNode src) {
        if (src == null) return Collections.emptyList();
        List<HtmlNode> extracted = new ArrayList<>();
        for (HtmlNode c : src.children) {
            if ("template".equalsIgnoreCase(c.tag)) {
                for (HtmlNode tc : templateContentChildren(c)) extracted.add(tc.cloneNode(true));
            }
        }
        if (extracted.isEmpty()) {
            for (HtmlNode c : src.children) {
                extracted.add(c.cloneNode(true));
            }
        }
        return extracted;
    }

    /** {@code template} の不活性な中身。テンプレート以外では空。 */
    private static List<HtmlNode> templateContentChildren(HtmlNode template) {
        if (template instanceof HtmlElement el && el.isTemplateElement()) {
            HtmlDocumentFragment content = el.getContent();
            return content != null ? content.children : Collections.emptyList();
        }
        return Collections.emptyList();
    }

    /** 仮想リストが1行ごとに複製する雛形。template なら content の中身、そうでなければ子。 */
    private static List<HtmlNode> templateChildrenForRender(HtmlNode template) {
        if (template instanceof HtmlElement el && el.isTemplateElement()) return templateContentChildren(template);
        return template.children;
    }

    public static void uniquifyIds(HtmlNode node, int index, String rowKey) {
        if (node == null) return;
        if (!node.getId().isEmpty()) {
            String suffix = (rowKey != null && !rowKey.isEmpty()) ? "_k_" + rowKey : "_row_" + index;
            node.setId(node.getId() + suffix);
        }
        for (HtmlNode child : node.children) uniquifyIds(child, index, rowKey);
    }

    // ------------------------------------------------------------------
    // 宣言的バインディング走査（旧 DeclarativeBindingManager）
    // ------------------------------------------------------------------

    public interface DeclarativeBindingHandler {
        void bindData(String targetId, String channel, String path, String targetAttr);
        void bindVirtualList(String targetId, String channel, HtmlNode templateNode, int itemW, int itemH);
        void bindHud(String targetId, String origDisplay, HtmlNode templateRoot, List<String> requireNbt);
        void bindTracker(HtmlNode templateNode, double radius, double yOffset, String targetType, List<String> requireNbt);
    }

    public static void scanDeclarativeBindings(HtmlNode rootNode, DeclarativeBindingHandler handler) {
        if (rootNode == null || handler == null) return;

        if (rootNode.attrs.containsKey("data-bind")) {
            String bindDefinition = rootNode.attrs.get("data-bind");
            String targetAttr = "text";
            String path = bindDefinition;

            if (bindDefinition.contains(":")) {
                String[] parts = bindDefinition.split(":", 2);
                targetAttr = parts[0].trim();
                path = parts[1].trim();
            }
            String channel = resolveChannel(rootNode, path);
            handler.bindData(rootNode.key(), channel, path, targetAttr);
        }

        if (rootNode.attrs.containsKey("data-list")) {
            String listPath = rootNode.attrs.get("data-list");
            HtmlNode templateNode = findTemplateChild(rootNode);

            if (templateNode != null) {
                int itemW = 36;
                int itemH = 36;
                try { itemW = Integer.parseInt(rootNode.attrs.getOrDefault("data-item-width", "36")); } catch (Exception ignored) {}
                try { itemH = Integer.parseInt(rootNode.attrs.getOrDefault("data-item-height", "36")); } catch (Exception ignored) {}
                String channel = resolveChannel(rootNode, listPath);
                handler.bindVirtualList(rootNode.key(), channel, templateNode, itemW, itemH);
            }
        }

        if (rootNode.attrs.containsKey("data-hud")) {
            String origDisplay = rootNode.attrs.getOrDefault("data-hud-display", "flex");
            List<String> requireNbt = new ArrayList<>();
            if (rootNode.attrs.containsKey("data-hud-require-nbt")) {
                String[] nbtKeys = rootNode.attrs.get("data-hud-require-nbt").split(",");
                for (String key : nbtKeys) requireNbt.add(key.trim());
            }
            handler.bindHud(rootNode.key(), origDisplay, rootNode, requireNbt);
        }

        if (rootNode.attrs.containsKey("data-tracker")) {
            double radius = 24.0;
            double yOffset = 0.8;
            String targetType = "living";
            List<String> requireNbt = new ArrayList<>();

            if (rootNode.attrs.containsKey("data-tracker-radius")) {
                try { radius = Double.parseDouble(rootNode.attrs.get("data-tracker-radius")); } catch (Exception ignored) {}
            }
            if (rootNode.attrs.containsKey("data-tracker-y-offset")) {
                try { yOffset = Double.parseDouble(rootNode.attrs.get("data-tracker-y-offset")); } catch (Exception ignored) {}
            }
            if (rootNode.attrs.containsKey("data-tracker-type")) {
                targetType = rootNode.attrs.get("data-tracker-type");
            }
            if (rootNode.attrs.containsKey("data-tracker-require-nbt")) {
                String[] nbtKeys = rootNode.attrs.get("data-tracker-require-nbt").split(",");
                for (String key : nbtKeys) requireNbt.add(key.trim());
            }

            handler.bindTracker(rootNode, radius, yOffset, targetType, requireNbt);
        }

        for (HtmlNode child : rootNode.children) {
            scanDeclarativeBindings(child, handler);
        }
    }

    private static String resolveChannel(HtmlNode node, String bindKey) {
        String attrName = "data-channel-" + bindKey.toLowerCase(Locale.ROOT);
        HtmlNode current = node;
        while (current != null) {
            if (current.attrs.containsKey(attrName)) {
                return current.attrs.get(attrName);
            }
            current = current.parent;
        }
        return Objects.requireNonNull(node).attrs.getOrDefault("data-channel", bindKey);
    }

    private static HtmlNode findTemplateChild(HtmlNode parent) {
        for (HtmlNode child : parent.children) {
            if ("template".equalsIgnoreCase(child.tag)) return child;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 仮想リスト構築（旧 ClientPubSubManager.applyBindingsToDOM）
    // ------------------------------------------------------------------

    public static boolean renderVirtualList(HtmlNode targetNode, HtmlNode templateNode,
                                            List<Map<String, String>> dataList,
                                            int itemW, int itemH,
                                            int defaultContainerWidth, int defaultVisibleRows) {
        if (targetNode == null) return false;

        targetNode.children.removeIf(node -> !"template".equalsIgnoreCase(node.tag));
        if (dataList == null) return true;

        int scrollY = 0;
        try { scrollY = (int) Float.parseFloat(targetNode.attrs.getOrDefault("data-scroll-y", "0")); } catch (Exception ignored) {}

        int estimatedContainerW = defaultContainerWidth;
        if (targetNode.attrs.containsKey("data-container-width")) {
            try { estimatedContainerW = Integer.parseInt(targetNode.attrs.get("data-container-width")); } catch (Exception ignored) {}
        }
        int itemsPerRow = Math.max(1, estimatedContainerW / Math.max(1, itemW));
        int totalRows = (int) Math.ceil((double) dataList.size() / itemsPerRow);

        int visibleRows = defaultVisibleRows;
        if (targetNode.attrs.containsKey("data-visible-rows")) {
            try { visibleRows = Integer.parseInt(targetNode.attrs.get("data-visible-rows")); } catch (Exception ignored) {}
        }
        int startRow = Math.max(0, scrollY / Math.max(1, itemH));
        int endRow = Math.min(totalRows, startRow + visibleRows);

        int topPadding = startRow * itemH;
        int bottomPadding = Math.max(0, totalRows - endRow) * itemH;

        HtmlNode topSpacer = new HtmlElement("div");
        topSpacer.setAttribute("style", "width: 100%; height: " + topPadding + "px; flex-shrink: 0;");
        topSpacer.parent = targetNode;
        targetNode.children.add(topSpacer);

        HtmlNode templateRoot = templateNode != null ? templateNode.cloneNode(true) : new HtmlNode("template");
        int startIndex = startRow * itemsPerRow;
        int endIndex = Math.min(dataList.size(), endRow * itemsPerRow);

        for (int i = startIndex; i < endIndex; i++) {
            Map<String, String> localData = dataList.get(i);
            for (HtmlNode tmpl : templateChildrenForRender(templateRoot)) {
                HtmlNode itemNode = tmpl.cloneNode(true);
                applyTemplate(itemNode, text -> resolve(text, localData, null));
                itemNode.parent = targetNode;
                targetNode.children.add(itemNode);
            }
        }

        HtmlNode botSpacer = new HtmlElement("div");
        botSpacer.setAttribute("style", "width: 100%; height: " + bottomPadding + "px; flex-shrink: 0;");
        botSpacer.parent = targetNode;
        targetNode.children.add(botSpacer);

        String scrollH = String.valueOf(totalRows * itemH);
        if (!scrollH.equals(targetNode.getAttribute("data-scroll-h"))) targetNode.setAttribute("data-scroll-h", scrollH);
        return true;
    }

    // ------------------------------------------------------------------
    // 単一データバインディング適用
    // ------------------------------------------------------------------

    public static boolean applyDataBinding(HtmlNode targetNode, String targetAttr, String val) {
        if (targetNode == null || targetAttr == null) return false;

        if ("text".equals(targetAttr)) {
            if (!Objects.equals(targetNode.getTextContent(), val)) {
                targetNode.updateTextContent(val);
                return true;
            }
        } else if (targetAttr.startsWith("style.")) {
            String styleKey = targetAttr.substring(6);
            String prevStyle = targetNode.attrs.getOrDefault("style", "");
            targetNode.setStyleProperty(styleKey, val);
            if (!Objects.equals(prevStyle, targetNode.attrs.getOrDefault("style", ""))) {
                return true;
            }
        } else {
            String prevVal = targetNode.attrs.get(targetAttr);
            if (!Objects.equals(prevVal, val)) {
                DomUtils.updateAttribute(targetNode, targetAttr, val, false);
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // HUD / トラッカー用テンプレートデータ適用（旧 WebHudRenderer.applyTemplateData）
    // ------------------------------------------------------------------

    public static void applyHudTemplateData(HtmlNode node, Map<String, String> data, Map<String, String> localEnv) {
        if (node == null) return;
        applyI18nAttributes(node);
        applyTemplate(node, text -> resolve(text, data, localEnv));

        if (node.attrs.containsKey("data-show")) {
            String showVal = node.attrs.get("data-show").trim();
            Boolean condResult = evaluateCondition(showVal, data);
            boolean hide = (condResult != null) ? !condResult
                    : (showVal.isEmpty() || "false".equalsIgnoreCase(showVal) || "0".equals(showVal) || showVal.contains("{{"));
            if (hide) node.setStyleProperty("display", "none");
            node.removeAttribute("data-show");
        }

        if (node.attrs.containsKey("data-list")) {
            String jsonStr = node.getAttribute("data-list");
            node.removeAttribute("data-list");
            List<HtmlNode> templateChildren = extractTemplateChildren(node);
            node.children.clear();

            if (jsonStr != null && !jsonStr.isEmpty() && !jsonStr.contains("{{") && BindingHooks.current().jsonListParser() != null) {
                try {
                    List<Map<String, String>> parsedItems = BindingHooks.current().jsonListParser().apply(jsonStr);
                    if (parsedItems != null) {
                        for (Map<String, String> itemMap : parsedItems) {
                            Map<String, String> localData = new HashMap<>(data);
                            localData.putAll(itemMap);
                            appendClonedTemplateItems(node, templateChildren, localData, localEnv);
                        }
                    }
                } catch (Exception ignored) {}
            }
            return;
        }

        for (HtmlNode child : node.children) {
            applyHudTemplateData(child, data, localEnv);
        }
    }

    private static void appendClonedTemplateItems(HtmlNode parent, List<HtmlNode> templateChildren,
                                                  Map<String, String> localData, Map<String, String> localEnv) {
        for (HtmlNode tmplChild : templateChildren) {
            HtmlNode clonedChild = tmplChild.cloneNode();
            applyHudTemplateData(clonedChild, localData, localEnv);
            clonedChild.parent = parent;
            parent.children.add(clonedChild);
        }
    }

    // ------------------------------------------------------------------
    // モデルキー判定用ヘルパー
    // ------------------------------------------------------------------

    public static String getModelKey(HtmlNode node) {
        if (node == null || !node.attrs.containsKey("data-model")) return null;
        String key = node.attrs.get("data-model").trim();
        return key.isEmpty() ? null : key;
    }
}
