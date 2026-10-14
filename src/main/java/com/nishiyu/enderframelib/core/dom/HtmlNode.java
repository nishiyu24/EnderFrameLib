package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.core.css.CssParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class HtmlNode {
    public String tag;
    public String id = "";
    public List<String> classes = new ArrayList<>();
    public Map<String, String> attrs = new HashMap<>();
    public List<HtmlNode> children = new ArrayList<>();
    public String text = "";
    public HtmlNode parent = null;
    public boolean isHovered = false;
    public boolean isChecked = false;
    public boolean isActive = false;
    public boolean isFocused = false;
    public boolean isDirty = true;
    public Map<String, String> computedStyle = null;
    public boolean isPseudoNode = false;

    public HtmlNode(String tag) {
        this.tag = tag;
    }

    // ==========================================
    // 1. 検索系 API
    // ==========================================

    public HtmlNode getElementById(String targetId) {
        if (targetId == null || targetId.isEmpty()) return null;
        if (targetId.equals(this.id)) return this;
        for (HtmlNode child : children) {
            HtmlNode found = child.getElementById(targetId);
            if (found != null) return found;
        }
        return null;
    }

    public Map<String, String> getDataset() {
        Map<String, String> dataset = new HashMap<>();
        for (Map.Entry<String, String> entry : attrs.entrySet()) {
            if (entry.getKey().startsWith("data-")) {
                dataset.put(entry.getKey(), entry.getValue());
            }
        }
        return dataset;
    }

    public HtmlNode querySelector(String selector) {
        CssParser.StyleRule rule = new CssParser.StyleRule(selector);
        return querySelectorInternal(this, rule);
    }

    private HtmlNode querySelectorInternal(HtmlNode node, CssParser.StyleRule rule) {
        if (rule.matches(node)) return node;
        for (HtmlNode child : node.children) {
            HtmlNode found = querySelectorInternal(child, rule);
            if (found != null) return found;
        }
        return null;
    }

    public List<HtmlNode> querySelectorAll(String selector) {
        CssParser.StyleRule rule = new CssParser.StyleRule(selector);
        List<HtmlNode> results = new ArrayList<>();
        querySelectorAllInternal(this, rule, results);
        return results;
    }

    private void querySelectorAllInternal(HtmlNode node, CssParser.StyleRule rule, List<HtmlNode> results) {
        if (rule.matches(node)) results.add(node);
        for (HtmlNode child : node.children) {
            querySelectorAllInternal(child, rule, results);
        }
    }

    // ==========================================
    // 2. classList 操作系 API
    // ==========================================

    public boolean hasClass(String className) {
        return className != null && this.classes.contains(className.trim());
    }

    public void addClass(String... classNames) {
        boolean modified = false;
        for (String cn : classNames) {
            if (cn == null) continue;
            for (String part : cn.trim().split("\\s+")) {
                if (!part.isEmpty() && !this.classes.contains(part)) {
                    this.classes.add(part);
                    modified = true;
                }
            }
        }
        if (modified) syncClassAttribute();
    }

    public void removeClass(String... classNames) {
        boolean modified = false;
        for (String cn : classNames) {
            if (cn == null) continue;
            for (String part : cn.trim().split("\\s+")) {
                if (this.classes.remove(part)) {
                    modified = true;
                }
            }
        }
        if (modified) syncClassAttribute();
    }

    public boolean toggleClass(String className) {
        if (className == null || className.trim().isEmpty()) return false;
        String clean = className.trim();
        if (this.classes.contains(clean)) {
            this.classes.remove(clean);
            syncClassAttribute();
            return false;
        } else {
            this.classes.add(clean);
            syncClassAttribute();
            return true;
        }
    }

    private void syncClassAttribute() {
        if (this.classes.isEmpty()) {
            this.attrs.remove("class");
        } else {
            this.attrs.put("class", String.join(" ", this.classes));
        }
        this.markDirty();
    }

    // ==========================================
    // 3. 属性・インラインスタイル・テキスト操作系 API
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
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : map.entrySet()) {
                sb.append(e.getKey()).append(": ").append(e.getValue()).append("; ");
            }
            this.attrs.put("style", sb.toString().trim());
        }
        this.markDirty();
    }

    public void setTextContent(String text) {
        DomUtils.setTextNodeValue(this, text != null ? text : "");
    }

    public String getTextContent() {
        if ("#text".equals(this.tag)) return this.text;
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
    // 4. ツリー構造変更系 API
    // ==========================================

    public void appendChild(HtmlNode child) {
        if (child == null) return;
        if (child.parent != null) child.parent.children.remove(child);
        child.parent = this;
        this.children.add(child);
        child.markDirty();
        this.markDirty();
    }

    public void prependChild(HtmlNode child) {
        if (child == null) return;
        if (child.parent != null) child.parent.children.remove(child);
        child.parent = this;
        this.children.addFirst(child);
        child.markDirty();
        this.markDirty();
    }

    public void removeChild(HtmlNode child) {
        if (child != null && this.children.remove(child)) {
            child.parent = null;
            this.markDirty();
        }
    }

    public void remove() {
        if (this.parent != null) {
            this.parent.removeChild(this);
        }
    }

    public void markDirty() {
        this.isDirty = true;
        if (this.parent != null) {
            for (HtmlNode sibling : this.parent.children) {
                sibling.isDirty = true;
            }
        }
    }

    public HtmlNode cloneNode() {
        HtmlNode copy = new HtmlNode(this.tag);
        copy.id = this.id;
        copy.classes.addAll(this.classes);
        copy.attrs.putAll(this.attrs);
        copy.text = this.text;
        copy.isHovered = this.isHovered;
        copy.isChecked = this.isChecked;
        copy.isActive = this.isActive;
        copy.isFocused = this.isFocused;
        copy.isPseudoNode = this.isPseudoNode;
        for (HtmlNode child : this.children) {
            HtmlNode childCopy = child.cloneNode();
            childCopy.parent = copy;
            copy.children.add(childCopy);
        }
        return copy;
    }
}