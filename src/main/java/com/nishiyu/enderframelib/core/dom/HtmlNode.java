package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.core.css.SelectorEngine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@org.jetbrains.annotations.ApiStatus.Internal
public class HtmlNode {
    private static final Pattern WHITESPACE_SPLIT = Pattern.compile("\\s+");

    public final NodeType nodeType;
    public final String tag;

    // attrsを真のデータソース（Single Source of Truth）とする
    public final Map<String, String> attrs = new LinkedHashMap<>();

    public final List<HtmlNode> children = new ArrayList<>();
    public String text = "";
    public HtmlNode parent = null;
    public boolean isPseudoNode = false;

    // Elementノード用コンストラクタ
    public HtmlNode(String tag) {
        this(NodeType.ELEMENT, tag);
    }

    // 指定タイプ用コンストラクタ
    public HtmlNode(NodeType type, String tag) {
        this.nodeType = type;
        this.tag = tag;
    }

    // テキストノード用ファクトリ
    public static HtmlNode createTextNode(String text) {
        HtmlNode node = new HtmlNode(NodeType.TEXT, "#text");
        node.text = text;
        return node;
    }

    // ==========================================
    // 1. プロパティ・アクセサ (非正規化の解消)
    // ==========================================

    // IDは attrs("id") から取得/設定
    public String getId() {
        return attrs.getOrDefault("id", "");
    }

    public void setId(String id) {
        if (id == null || id.trim().isEmpty()) {
            attrs.remove("id");
        } else {
            attrs.put("id", id.trim());
        }
    }

    // Classesは attrs("class") をパースして返す
    public List<String> getClasses() {
        String classStr = attrs.get("class");
        if (classStr == null || classStr.trim().isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(WHITESPACE_SPLIT.split(classStr.trim())));
    }

    // ==========================================
    // 2. 検索系 API
    // ==========================================

    public HtmlNode getElementById(String targetId) {
        return DomUtils.findNodeById(this, targetId);
    }

    public Map<String, String> getDataset() {
        Map<String, String> dataset = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : attrs.entrySet()) {
            if (entry.getKey().startsWith("data-")) {
                dataset.put(entry.getKey(), entry.getValue());
            }
        }
        return dataset;
    }

    // ※ フェーズ2-4 で SelectorEngine.query() に委譲するように変更予定
    public HtmlNode querySelector(String selector) {
        if (selector == null || selector.trim().isEmpty()) return null;
        return SelectorEngine.querySelector(this, selector);
    }

    public List<HtmlNode> querySelectorAll(String selector) {
        if (selector == null || selector.trim().isEmpty()) return new ArrayList<>();
        return SelectorEngine.querySelectorAll(this, selector);
    }

    // ==========================================
    // 3. classList 操作系 API
    // ==========================================

    public boolean hasClass(String className) {
        if (className == null) return false;
        return getClasses().contains(className.trim());
    }

    public void addClass(String... classNames) {
        List<String> currentClasses = getClasses();
        boolean modified = false;
        for (String cn : classNames) {
            if (cn == null) continue;
            for (String part : WHITESPACE_SPLIT.split(cn.trim())) {
                if (!part.isEmpty() && !currentClasses.contains(part)) {
                    currentClasses.add(part);
                    modified = true;
                }
            }
        }
        if (modified) syncClassAttribute(currentClasses);
    }

    public void removeClass(String... classNames) {
        List<String> currentClasses = getClasses();
        boolean modified = false;
        for (String cn : classNames) {
            if (cn == null) continue;
            for (String part : WHITESPACE_SPLIT.split(cn.trim())) {
                if (currentClasses.remove(part)) {
                    modified = true;
                }
            }
        }
        if (modified) syncClassAttribute(currentClasses);
    }

    public boolean toggleClass(String className) {
        if (className == null || className.trim().isEmpty()) return false;
        String clean = className.trim();
        List<String> currentClasses = getClasses();

        if (currentClasses.contains(clean)) {
            currentClasses.remove(clean);
            syncClassAttribute(currentClasses);
            return false;
        } else {
            currentClasses.add(clean);
            syncClassAttribute(currentClasses);
            return true;
        }
    }

    private void syncClassAttribute(List<String> classList) {
        if (classList.isEmpty()) {
            this.attrs.remove("class");
        } else {
            this.attrs.put("class", String.join(" ", classList));
        }
    }

    // ==========================================
    // 4. 属性・インラインスタイル・テキスト操作系 API
    // ==========================================

    public String getAttribute(String name) {
        return this.attrs.get(name);
    }

    public void setAttribute(String name, String value) {
        DomUtils.updateAttribute(this, name, value, false);
    }

    public void removeAttribute(String name) {
        DomUtils.updateAttribute(this, name, null, true);
    }

    public void setStyleProperty(String property, String value) {
        if (property == null || property.trim().isEmpty()) return;
        Map<String, String> styleMap = parseInlineStyleMap();
        if (value == null || value.trim().isEmpty()) {
            styleMap.remove(property.trim().toLowerCase());
        } else {
            styleMap.put(property.trim().toLowerCase(), value.trim());
        }
        saveInlineStyleMap(styleMap);
    }

    public void removeStyleProperty(String property) {
        setStyleProperty(property, null);
    }

    private Map<String, String> parseInlineStyleMap() {
        Map<String, String> map = new LinkedHashMap<>();
        String raw = this.attrs.getOrDefault("style", "");
        if (!raw.isEmpty()) {
            for (String decl : raw.split(";")) {
                int colon = decl.indexOf(':');
                if (colon > 0) {
                    String k = decl.substring(0, colon).trim().toLowerCase();
                    String v = decl.substring(colon + 1).trim();
                    if (!k.isEmpty()) map.put(k, v);
                }
            }
        }
        return map;
    }

    private void saveInlineStyleMap(Map<String, String> map) {
        if (map.isEmpty()) {
            this.attrs.remove("style");
        } else {
            String styleStr = map.entrySet().stream()
                    .map(e -> e.getKey() + ": " + e.getValue() + ";")
                    .collect(Collectors.joining(" "));
            this.attrs.put("style", styleStr);
        }
    }

    public void setTextContent(String text) {
        DomUtils.setTextNodeValue(this, text != null ? text : "");
    }

    public String getTextContent() {
        if (this.nodeType == NodeType.TEXT) return this.text;
        StringBuilder sb = new StringBuilder();
        for (HtmlNode child : this.children) {
            sb.append(child.getTextContent());
        }
        return sb.toString();
    }

    public void setInnerHTML(String html) {
        DomUtils.setInnerHTML(this, html);
    }

    public String getInnerHTML() {
        return DomUtils.getInnerHTML(this);
    }

    public String getOuterHTML() {
        return DomUtils.getOuterHTML(this);
    }

    // ==========================================
    // 5. ツリー構造変更系 API
    // ==========================================

    public void appendChild(HtmlNode child) {
        if (child == null) return;
        if (child.parent != null) child.parent.children.remove(child);
        child.parent = this;
        this.children.add(child);
    }

    public void prependChild(HtmlNode child) {
        if (child == null) return;
        if (child.parent != null) child.parent.children.remove(child);
        child.parent = this;
        this.children.addFirst(child);
    }

    public void removeChild(HtmlNode child) {
        if (child != null && this.children.remove(child)) {
            child.parent = null;
        }
    }

    public void remove() {
        if (this.parent != null) {
            this.parent.removeChild(this);
        }
    }

    public HtmlNode cloneNode() {
        HtmlNode copy = new HtmlNode(this.nodeType, this.tag);
        // id, classは attrs に含まれるため、attrsのコピーだけで完結する
        copy.attrs.putAll(this.attrs);
        copy.text = this.text;
        copy.isPseudoNode = this.isPseudoNode;
        for (HtmlNode child : this.children) {
            HtmlNode childCopy = child.cloneNode();
            childCopy.parent = copy;
            copy.children.add(childCopy);
        }
        return copy;
    }
}