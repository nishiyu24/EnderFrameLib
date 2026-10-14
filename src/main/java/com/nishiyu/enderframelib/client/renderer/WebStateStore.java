package com.nishiyu.enderframelib.client.binding;

import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.core.dom.DomUtils;
import com.nishiyu.enderframelib.core.dom.HtmlNode;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ローカルGUI画面やWebセッション向けに、リアクティブな状態管理とDOM自動バインディングを提供するストア
 */
public class WebStateStore {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{(?!@)([a-zA-Z0-9_.-]+)}}");

    private final String sessionId;
    private final Map<String, String> values = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, String>>> lists = new ConcurrentHashMap<>();
    private final Map<String, List<HtmlNode>> listTemplates = new ConcurrentHashMap<>();
    private final Map<String, String> originalShowDisplay = new ConcurrentHashMap<>();
    private boolean dirty = false;

    public WebStateStore(String sessionId) {
        this.sessionId = sessionId;
    }

    public WebStateStore set(String key, Object value) {
        String strVal = value != null ? String.valueOf(value) : "";
        String prev = values.put(key, strVal);
        if (!Objects.equals(prev, strVal)) {
            dirty = true;
            syncToDom();
        }
        return this;
    }

    public String get(String key) {
        return values.getOrDefault(key, "");
    }

    public boolean getBoolean(String key) {
        String v = values.getOrDefault(key, "false").trim();
        return "true".equalsIgnoreCase(v) || "1".equals(v);
    }

    public WebStateStore setList(String key, List<Map<String, String>> items) {
        List<Map<String, String>> copy = new ArrayList<>();
        if (items != null) {
            for (Map<String, String> item : items) copy.add(new HashMap<>(item));
        }
        List<Map<String, String>> prev = lists.put(key, copy);
        if (!Objects.equals(prev, copy)) {
            dirty = true;
            syncToDom();
        }
        return this;
    }

    public void forceSync() {
        dirty = true;
        listTemplates.clear();
        syncToDom();
    }

    public void syncToDom() {
        if (!dirty) return;
        HtmlNode vRoot = ClientWebManager.getVirtualRoot(sessionId);
        HtmlNode tmplRoot = ClientWebManager.getTemplateRoot(sessionId);
        if (vRoot == null) return;

        dirty = false;
        if ( applyRecursive(tmplRoot != null ? tmplRoot : vRoot, vRoot, values) ) {
            ClientWebManager.requestRender(sessionId);
        }
    }

    private boolean applyRecursive(HtmlNode origNode, HtmlNode vNode, Map<String, String> scopeData) {
        if (vNode == null) return false;
        boolean changed = false;

        // 1. テキストの {{key}} プレースホルダー解決
        String rawText = (origNode != null && origNode.text != null) ? origNode.text : vNode.text;
        if (rawText != null && rawText.contains("{{") && !rawText.contains("{{@")) {
            String resolved = replacePlaceholders(rawText, scopeData);
            if (!Objects.equals(resolved, vNode.text)) {
                vNode.text = resolved;
                vNode.markDirty();
                changed = true;
            }
        }

        // 2. 属性の {{key}} プレースホルダー解決
        HtmlNode attrSource = origNode != null ? origNode : vNode;
        for (Map.Entry<String, String> entry : new ArrayList<>(attrSource.attrs.entrySet())) {
            String k = entry.getKey();
            String rawVal = entry.getValue();
            if (rawVal != null && rawVal.contains("{{") && !rawVal.contains("{{@")) {
                String resolved = replacePlaceholders(rawVal, scopeData);
                if (!Objects.equals(resolved, vNode.attrs.get(k))) {
                    DomUtils.updateAttribute(vNode, k, resolved, false);
                    changed = true;
                }
            }
        }

        // 3. data-bind="key" または data-bind="attr:key, style.color:colorKey"
        if (vNode.attrs.containsKey("data-bind")) {
            String bindDef = vNode.attrs.get("data-bind");
            for (String part : bindDef.split(",")) {
                part = part.trim();
                if (part.isEmpty()) continue;
                String target = "text";
                String key = part;
                if (part.contains(":")) {
                    String[] kv = part.split(":", 2);
                    target = kv[0].trim();
                    key = kv[1].trim();
                }
                if (scopeData.containsKey(key)) {
                    String val = scopeData.get(key);
                    if ("text".equalsIgnoreCase(target)) {
                        if (!Objects.equals(vNode.getTextContent(), val)) {
                            vNode.setTextContent(val);
                            changed = true;
                        }
                    } else if (target.startsWith("style.")) {
                        String prop = target.substring(6).trim();
                        vNode.setStyleProperty(prop, val);
                        changed = true;
                    } else {
                        if (!Objects.equals(vNode.attrs.get(target), val)) {
                            DomUtils.updateAttribute(vNode, target, val, false);
                            changed = true;
                        }
                    }
                }
            }
        }

        // 4. data-show="condition" / data-show="!condition"
        if (vNode.attrs.containsKey("data-show")) {
            String cond = vNode.attrs.get("data-show").trim();
            boolean invert = cond.startsWith("!");
            String key = invert ? cond.substring(1).trim() : cond;
            if (scopeData.containsKey(key)) {
                String val = scopeData.getOrDefault(key, "");
                boolean truthy = !val.isEmpty() && !"false".equalsIgnoreCase(val) && !"0".equals(val);
                boolean visible = invert != truthy;

                String origDisp = originalShowDisplay.computeIfAbsent(vNode.id, id ->
                        vNode.attrs.getOrDefault("data-orig-display", "flex"));
                String targetDisp = visible ? origDisp : "none";
                String curStyle = vNode.attrs.getOrDefault("style", "");
                if (!curStyle.contains("display: " + targetDisp)) {
                    vNode.setStyleProperty("display", targetDisp);
                    changed = true;
                }
            }
        }

        // 5. data-class="className:conditionKey, otherClass:!conditionKey"
        if (vNode.attrs.containsKey("data-class")) {
            String clsDef = vNode.attrs.get("data-class");
            for (String item : clsDef.split(",")) {
                String[] kv = item.trim().split(":", 2);
                if (kv.length == 2) {
                    String className = kv[0].trim();
                    String cond = kv[1].trim();
                    boolean invert = cond.startsWith("!");
                    String key = invert ? cond.substring(1).trim() : cond;
                    if (scopeData.containsKey(key)) {
                        String val = scopeData.getOrDefault(key, "");
                        boolean truthy = !val.isEmpty() && !"false".equalsIgnoreCase(val) && !"0".equals(val);
                        boolean shouldHave = invert != truthy;
                        if (shouldHave && !vNode.hasClass(className)) {
                            vNode.addClass(className);
                            changed = true;
                        } else if (!shouldHave && vNode.hasClass(className)) {
                            vNode.removeClass(className);
                            changed = true;
                        }
                    }
                }
            }
        }

        // 6. data-list="listKey" によるテンプレート反復展開
        if (vNode.attrs.containsKey("data-list")) {
            String listKey = vNode.attrs.get("data-list").trim();
            if (lists.containsKey(listKey)) {
                List<HtmlNode> tmplNodes = listTemplates.computeIfAbsent(vNode.id, id -> {
                    List<HtmlNode> extracted = new ArrayList<>();
                    HtmlNode src = origNode != null ? origNode : vNode;
                    for (HtmlNode c : src.children) {
                        if ("template".equalsIgnoreCase(c.tag)) {
                            for (HtmlNode tc : c.children) extracted.add(tc.cloneNode());
                        }
                    }
                    return extracted;
                });

                if (!tmplNodes.isEmpty()) {
                    vNode.children.clear();
                    List<Map<String, String>> itemDataList = lists.get(listKey);
                    int idx = 0;
                    for (Map<String, String> itemMap : itemDataList) {
                        Map<String, String> merged = new HashMap<>(scopeData);
                        merged.putAll(itemMap);
                        merged.put("_index", String.valueOf(idx));
                        for (HtmlNode tmpl : tmplNodes) {
                            HtmlNode instance = tmpl.cloneNode();
                            uniquifyIds(instance, idx);
                            applyRecursive(instance.cloneNode(), instance, merged);
                            instance.parent = vNode;
                            instance.isDirty = true;
                            vNode.children.add(instance);
                        }
                        idx++;
                    }
                    vNode.markDirty();
                    return true;
                }
            }
        }

        int vSize = vNode.children.size();
        for (int i = 0; i < vSize; i++) {
            HtmlNode vChild = vNode.children.get(i);
            HtmlNode oChild = (origNode != null && i < origNode.children.size()) ? origNode.children.get(i) : null;
            if (applyRecursive(oChild, vChild, scopeData)) {
                changed = true;
            }
        }
        return changed;
    }

    private void uniquifyIds(HtmlNode node, int index) {
        if (node.id != null && !node.id.isEmpty()) {
            node.id = node.id + "_row_" + index;
            node.attrs.put("id", node.id);
        }
        for (HtmlNode child : node.children) uniquifyIds(child, index);
    }

    private String replacePlaceholders(String text, Map<String, String> data) {
        Matcher m = PLACEHOLDER_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String replacement = data.getOrDefault(key, m.group(0));
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}