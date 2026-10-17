package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.common.UIParser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DomUtils {

    private static final Set<String> VOID_TAGS = Set.of("br", "hr", "img", "input", "meta", "link");
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{([a-zA-Z0-9_.:-]+)}}");
    private static final Pattern WHITESPACE_SPLIT = Pattern.compile("\\s+");

    /**
     * クライアント環境では ClientLocalEnvProvider により I18n.get が自動バインドされる。
     * 専用サーバー環境でもクラッシュせずキー文字列にフォールバックする。
     */
    public static Function<String, String> i18nResolver = key -> key;

    // --- DOM検索・更新系 ---

    public static HtmlNode findNodeById(HtmlNode node, String id) {
        if (node == null || id == null || id.isEmpty()) return null;
        if (id.equals(node.id)) return node;
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
            if ("class".equals(attrName)) target.classes.clear();
            if ("hover".equals(attrName)) target.isHovered = false;
            if ("checked".equals(attrName)) target.isChecked = false;
        } else {
            String val = attrValue != null ? attrValue : "true";
            target.attrs.put(attrName, val);
            if ("id".equals(attrName)) target.id = val;
            if ("class".equals(attrName)) {
                target.classes.clear();
                if (!val.trim().isEmpty()) {
                    target.classes.addAll(Arrays.asList(WHITESPACE_SPLIT.split(val.trim())));
                }
            }
            if ("hover".equals(attrName)) target.isHovered = true;
            if ("checked".equals(attrName)) target.isChecked = true;
        }
        target.markDirty();
    }

    public static void toggleAttribute(HtmlNode target, String attrName) {
        if (target == null || attrName == null) return;
        if (target.attrs.containsKey(attrName)) {
            updateAttribute(target, attrName, null, true);
        } else {
            updateAttribute(target, attrName, "true", false);
        }
    }

    /**
     * ラジオボタン (input[type="radio"]) を選択状態にし、同一ツリー内で同じ name 属性を持つ他のラジオボタンの選択を解除する。
     */
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
            if (current.attrs.containsKey("checked") || current.isChecked) {
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
        if ("#text".equals(node.tag)) {
            node.text = safeText;
            node.markDirty();
            return;
        }
        node.children.removeIf(c -> !"#text".equals(c.tag));
        for (HtmlNode child : node.children) {
            if ("#text".equals(child.tag)) {
                child.text = safeText;
                child.markDirty();
                node.markDirty();
                return;
            }
        }
        HtmlNode txtNode = new HtmlNode("#text");
        txtNode.text = safeText;
        txtNode.id = node.id + "_txt";
        txtNode.parent = node;
        node.children.add(txtNode);
        node.markDirty();
    }

    // --- innerHTML / outerHTML 操作系 ---

    public static void setInnerHTML(HtmlNode target, String html) {
        if (target == null) return;
        target.children.clear();
        if (html != null && !html.trim().isEmpty()) {
            String expanded = UIParser.expandCustomTags(html);
            HtmlNode parsedRoot = HtmlParser.parse(expanded);
            for (HtmlNode child : parsedRoot.children) {
                child.parent = target;
                child.isDirty = true;
                target.children.add(child);
            }
        }
        target.markDirty();
    }

    public static String getInnerHTML(HtmlNode node) {
        if (node == null) return "";
        if ("#text".equals(node.tag)) return node.text != null ? node.text : "";
        StringBuilder sb = new StringBuilder();
        for (HtmlNode child : node.children) {
            sb.append(getOuterHTML(child));
        }
        return sb.toString();
    }

    public static String getOuterHTML(HtmlNode node) {
        if (node == null) return "";
        if ("#text".equals(node.tag)) return node.text != null ? node.text : "";
        if ("root".equals(node.tag)) return getInnerHTML(node);

        StringBuilder sb = new StringBuilder();
        sb.append("<").append(node.tag);
        for (Map.Entry<String, String> entry : node.attrs.entrySet()) {
            sb.append(" ").append(entry.getKey()).append("=\"").append(entry.getValue()).append("\"");
        }
        if (VOID_TAGS.contains(node.tag)) {
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

    public static void applyTemplateData(HtmlNode node, Map<String, String> data) {
        if (node == null || data == null) return;

        if (node.text != null && node.text.contains("{{")) {
            node.text = replacePlaceholders(node.text, data);
        }

        for (String key : new ArrayList<>(node.attrs.keySet())) {
            String val = node.attrs.get(key);
            if (val != null && val.contains("{{")) {
                node.attrs.put(key, replacePlaceholders(val, data));
            }
        }

        if (node.attrs.containsKey("id")) {
            node.id = node.attrs.get("id");
        }
        if (node.attrs.containsKey("class")) {
            node.classes.clear();
            String cls = node.attrs.get("class").trim();
            if (!cls.isEmpty()) {
                node.classes.addAll(Arrays.asList(WHITESPACE_SPLIT.split(cls)));
            }
        }

        for (HtmlNode child : node.children) {
            applyTemplateData(child, data);
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