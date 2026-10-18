package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.core.dom.HtmlNode.NodeType;
import com.nishiyu.enderframelib.core.dom.HtmlParser.VoidElements;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DomUtils {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{([a-zA-Z0-9_.:-]+)}}");

    public static Function<String, String> i18nResolver = key -> key;

    /**
     * UIParserへの依存を切り離すためのフック。引数は (HTML, 文脈の要素のタグ)。
     * 文脈を渡すのは断片の解析が挿入モードを文脈から決めるため（tbody の innerHTML の {@code <tr>} を捨てない）。
     */
    public static BiFunction<String, String, HtmlNode> innerHtmlParser = null;

    // --- DOM検索・更新系 ---

    public static HtmlNode findNodeById(HtmlNode node, String id) {
        if (node == null || id == null || id.isEmpty()) return null;
        if (id.equals(node.getId())) return node;
        for (HtmlNode child : node.children) {
            HtmlNode found = findNodeById(child, id);
            if (found != null) return found;
        }
        return null;
    }

    public static void updateAttribute(HtmlNode target, String attrName, String attrValue, boolean remove) {
        if (target == null || attrName == null) return;
        if (remove) {
            target.attrs.remove(attrName);
        } else {
            String val = attrValue != null ? attrValue : "true";
            target.attrs.put(attrName, val);
        }
        // markDirty() の呼び出しは削除。スタイル無効化は InteractionState など別の仕組みで行う。
    }

    public static void toggleAttribute(HtmlNode target, String attrName) {
        if (target == null || attrName == null) return;
        if (target.attrs.containsKey(attrName)) {
            updateAttribute(target, attrName, null, true);
        } else {
            updateAttribute(target, attrName, "true", false);
        }
    }

    public static void selectRadioButton(HtmlNode root, HtmlNode selectedRadio) {
        if (selectedRadio == null) return;
        String groupName = selectedRadio.attrs.getOrDefault("name", "");
        if (root != null && !groupName.isEmpty()) {
            clearRadioGroupRecursive(root, groupName, selectedRadio);
        }
        updateAttribute(selectedRadio, "checked", "true", false);
    }

    private static void clearRadioGroupRecursive(HtmlNode current, String groupName, HtmlNode keepNode) {
        if (current != keepNode
                && "input".equalsIgnoreCase(current.tag)
                && "radio".equalsIgnoreCase(current.attrs.getOrDefault("type", ""))
                && groupName.equals(current.attrs.get("name"))) {
            if (current.attrs.containsKey("checked")) {
                updateAttribute(current, "checked", null, true);
            }
        }
        for (HtmlNode child : current.children) {
            clearRadioGroupRecursive(child, groupName, keepNode);
        }
    }

    public static void setTextNodeValue(HtmlNode node, String text) {
        if (node == null) return;
        String safeText = text != null ? text : "";
        if (node.nodeType == NodeType.TEXT) {
            node.text = safeText;
            return;
        }
        node.children.removeIf(c -> c.nodeType != NodeType.TEXT);
        for (HtmlNode child : node.children) {
            if (child.nodeType == NodeType.TEXT) {
                child.text = safeText;
                return;
            }
        }
        HtmlNode txtNode = HtmlNode.createTextNode(safeText);
        txtNode.setId(node.getId() + "_txt");
        txtNode.parent = node;
        node.children.add(txtNode);
    }

    // --- innerHTML / outerHTML 操作系 ---

    public static void setInnerHTML(HtmlNode target, String html) {
        if (target == null) return;
        target.children.clear();
        if (html != null && !html.trim().isEmpty()) {
            if (innerHtmlParser != null) {
                HtmlNode parsedRoot = innerHtmlParser.apply(html, target.tag);
                if (parsedRoot != null) {
                    for (HtmlNode child : parsedRoot.children) {
                        child.parent = target;
                        target.children.add(child);
                    }
                }
            }
        }
    }

    public static String getInnerHTML(HtmlNode node) {
        if (node == null) return "";
        if (node.nodeType == NodeType.TEXT) return node.text != null ? node.text : "";
        StringBuilder sb = new StringBuilder();
        for (HtmlNode child : node.children) {
            sb.append(getOuterHTML(child));
        }
        return sb.toString();
    }

    public static String getOuterHTML(HtmlNode node) {
        if (node == null) return "";
        if (node.nodeType == NodeType.TEXT) return node.text != null ? node.text : "";
        if (node.nodeType == NodeType.ROOT) return getInnerHTML(node);

        StringBuilder sb = new StringBuilder();
        sb.append("<").append(node.tag);
        for (Map.Entry<String, String> entry : node.attrs.entrySet()) {
            sb.append(" ").append(entry.getKey()).append("=\"").append(entry.getValue()).append("\"");
        }
        if (VoidElements.isVoid(node.tag)) {
            sb.append(" />");
            return sb.toString();
        }
        sb.append(">");
        for (HtmlNode child : node.children) {
            sb.append(getOuterHTML(child));
        }
        sb.append("</").append(node.tag).append(">");
        return sb.toString();
    }

    // --- テンプレート（プレースホルダー置換）系 ---

    /**
     * テンプレート走査の共通実装。置換方法は呼び出し側が {@code replacer} で与える。
     * 以前はこの走査処理が 4 箇所に複製されていた。
     */
    public static void applyTemplate(HtmlNode node, java.util.function.Function<String, String> replacer) {
        if (node == null || replacer == null) return;

        if (node.text != null && node.text.contains("{{")) {
            node.text = replacer.apply(node.text);
        }

        for (String key : new ArrayList<>(node.attrs.keySet())) {
            String val = node.attrs.get(key);
            if (val != null && val.contains("{{")) {
                updateAttribute(node, key, replacer.apply(val), false);
            }
        }

        if (node.attrs.containsKey("id")) {
            node.setId(node.attrs.get("id"));
        }

        for (HtmlNode child : node.children) {
            applyTemplate(child, replacer);
        }
    }

    public static String replacePlaceholders(String text, Map<String, String> data) {
        if (text == null || !text.contains("{{")) return text != null ? text : "";
        Matcher m = PLACEHOLDER_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            if (key.startsWith("i18n:") || key.startsWith("@i18n:")) {
                String i18nKey = key.substring(key.indexOf(':') + 1);
                String translated = i18nResolver != null ? i18nResolver.apply(i18nKey) : i18nKey;
                m.appendReplacement(sb, Matcher.quoteReplacement(translated));
                continue;
            }
            String replacement = data != null ? data.getOrDefault(key, "{{" + key + "}}") : "{{" + key + "}}";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}