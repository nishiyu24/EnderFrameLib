package com.nishiyu.enderframelib.core.dom.parser;

import com.nishiyu.enderframelib.core.dom.*;
import com.nishiyu.enderframelib.core.dom.node.*;

import com.nishiyu.enderframelib.core.dom.parser.HtmlParser.VoidElements;

import java.util.Map;
import java.util.function.BiFunction;

public class DomUtils {

    /**
     * UIParserへの依存を切り離すためのフック。引数は (HTML, 文脈の要素のタグ)。
     */
    public static BiFunction<String, String, HtmlNode> innerHtmlParser = null;

    // --- DOM検索・更新系 ---

    public static void updateAttribute(HtmlNode target, String attrName, String attrValue, boolean remove) {
        if (target == null || attrName == null) return;
        if (remove) {
            target.attrs.remove(attrName);
        } else {
            String val = attrValue != null ? attrValue : "true";
            target.attrs.put(attrName, val);
        }
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

    // --- innerHTML / outerHTML 操作系 ---

    public static void setInnerHTML(HtmlNode target, String html) {
        if (target == null) return;
        // template の中身は要素の子ではなく content の子
        HtmlNode container = target instanceof HtmlElement el && el.isTemplateElement() ? el.getContent() : target;
        if (container == null) return;
        container.children.clear();
        if (html != null && !html.trim().isEmpty()) {
            if (innerHtmlParser != null) {
                HtmlNode parsedRoot = innerHtmlParser.apply(html, target.tag);
                if (parsedRoot != null) {
                    for (HtmlNode child : parsedRoot.children) {
                        child.parent = container;
                        container.children.add(child);
                    }
                }
            }
        }
    }

    /** 直列化する子の並び。template は中身（content）を子として書き出す。 */
    private static java.util.List<HtmlNode> serializedChildren(HtmlNode node) {
        if (node instanceof HtmlElement el && el.isTemplateElement()) {
            HtmlNode container = el.getContent();
            return container != null ? container.children : java.util.List.of();
        }
        return node.children;
    }

    public static String getInnerHTML(HtmlNode node) {
        if (node == null) return "";
        if (node.nodeType == NodeType.TEXT) return node.text != null ? node.text : "";
        StringBuilder sb = new StringBuilder();
        for (HtmlNode child : serializedChildren(node)) {
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
        for (HtmlNode child : serializedChildren(node)) {
            sb.append(getOuterHTML(child));
        }
        sb.append("</").append(node.tag).append(">");
        return sb.toString();
    }

    /** {@code select} の現在値に対応する表示ラベル。該当が無ければ最初の option のラベル。 */
    public static String resolveSelectDisplayLabel(HtmlNode selectNode, String currentVal) {
        if (selectNode == null) return currentVal != null ? currentVal : "";
        String val = currentVal != null ? currentVal : selectNode.attrs.getOrDefault("value", "");
        String firstLabel = null;
        for (HtmlNode child : selectNode.children) {
            if ("option".equalsIgnoreCase(child.tag)) {
                String optVal = child.attrs.getOrDefault("value", child.getTextContent().trim());
                String optLabel = child.getTextContent().trim();
                if (firstLabel == null) firstLabel = optLabel.isEmpty() ? optVal : optLabel;
                if (val.isEmpty() || val.equals(optVal)) {
                    return optLabel.isEmpty() ? optVal : optLabel;
                }
            }
        }
        return firstLabel != null ? firstLabel : val;
    }
}