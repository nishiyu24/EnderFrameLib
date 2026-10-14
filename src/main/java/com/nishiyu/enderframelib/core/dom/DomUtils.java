package com.nishiyu.enderframelib.core.dom;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DomUtils {

    private static final List<String> VOID_TAGS = List.of("br", "hr", "img", "input", "meta", "link");

    // --- DOM検索・更新系 ---

    public static HtmlNode findNodeById(HtmlNode node, String id) {
        if (node == null || id == null) return null;
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
                    target.classes.addAll(Arrays.asList(val.trim().split("\\s+")));
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

    public static void setTextNodeValue(HtmlNode node, String text) {
        if (node == null) return;
        String safeText = text != null ? text : "";
        if ("#text".equals(node.tag)) {
            node.text = safeText;
            node.markDirty();
            return;
        }
        // 既存の子要素をクリアして単一のテキストノードにする（または既存の#textを更新）
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
            HtmlNode parsedRoot = HtmlParser.parse(html);
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
            // 自動生成されたIDは出力時にノイズになる場合でも、バインディング維持のため保持
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
        if (node == null || data == null || data.isEmpty()) return;

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
                node.classes.addAll(Arrays.asList(cls.split("\\s+")));
            }
        }

        for (HtmlNode child : node.children) {
            applyTemplateData(child, data);
        }
    }

    private static String replacePlaceholders(String text, Map<String, String> data) {
        Matcher m = Pattern.compile("\\{\\{([a-zA-Z0-9_.-]+)}}").matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String replacement = data.getOrDefault(key, "");
            if (!data.containsKey(key)) replacement = "{{" + key + "}}";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}