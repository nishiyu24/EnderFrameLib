package com.nishiyu.enderframelib.core.dom.node;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 属性の書き込みの入口（{@link #set} / {@link #remove}）と、その上に作る便利な操作（class の語の一覧・インライン style の宣言・
 * dataset）。{@link HtmlNode} から分けた（M5-1）。便利な操作の書き込みも {@link #set} / {@link #remove} を通す
 * （MutationObserver と文書への通知が 1 か所に集まる）。
 */
final class NodeAttributes {
    private static final Pattern WHITESPACE_SPLIT = Pattern.compile("\\s+");

    private NodeAttributes() {}

    static void set(HtmlNode n, String name, String value) {
        if (name == null) return;
        String oldVal = n.attrs.get(name);
        String newVal = value != null ? value : "";
        n.attrsForWrite().put(name, newVal);
        if (n instanceof HtmlElement el && "style".equals(name)) el.syncStyleFromAttribute(newVal);
        MutationObserverManager.queueAttributes(n, name, oldVal);
        n.notifyAttributeChanged(name, oldVal, newVal);
    }

    static void remove(HtmlNode n, String name) {
        if (name == null || !n.attrs.containsKey(name)) return;
        String oldVal = n.attrs.remove(name);
        if (n instanceof HtmlElement el && "style".equals(name)) el.syncStyleFromAttribute(null);
        MutationObserverManager.queueAttributes(n, name, oldVal);
        n.notifyAttributeChanged(name, oldVal, null);
    }

    static List<String> classes(HtmlNode n) {
        String classStr = n.attrs.get("class");
        if (classStr == null || classStr.trim().isEmpty()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(WHITESPACE_SPLIT.split(classStr.trim())));
    }

    static void addClass(HtmlNode n, String... classNames) {
        List<String> current = classes(n);
        boolean modified = false;
        for (String cn : classNames) {
            if (cn == null) continue;
            for (String part : WHITESPACE_SPLIT.split(cn.trim())) {
                if (!part.isEmpty() && !current.contains(part)) {
                    current.add(part);
                    modified = true;
                }
            }
        }
        if (modified) writeClasses(n, current);
    }

    static void removeClass(HtmlNode n, String... classNames) {
        List<String> current = classes(n);
        boolean modified = false;
        for (String cn : classNames) {
            if (cn == null) continue;
            for (String part : WHITESPACE_SPLIT.split(cn.trim())) {
                if (current.remove(part)) modified = true;
            }
        }
        if (modified) writeClasses(n, current);
    }

    static boolean toggleClass(HtmlNode n, String className) {
        if (className == null || className.trim().isEmpty()) return false;
        String clean = className.trim();
        List<String> current = classes(n);
        boolean add = !current.remove(clean);
        if (add) current.add(clean);
        writeClasses(n, current);
        return add;
    }

    private static void writeClasses(HtmlNode n, List<String> classList) {
        if (classList.isEmpty()) n.removeAttribute("class");
        else n.setAttribute("class", String.join(" ", classList));
    }

    static Map<String, String> dataset(HtmlNode n) {
        Map<String, String> dataset = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : n.attrs.entrySet()) {
            if (entry.getKey().startsWith("data-")) dataset.put(entry.getKey(), entry.getValue());
        }
        return dataset;
    }

    static void setStyleProperty(HtmlNode n, String property, String value) {
        if (property == null || property.trim().isEmpty()) return;
        Map<String, String> styleMap = parseInlineStyle(n);
        String key = property.trim().toLowerCase();
        if (value == null || value.trim().isEmpty()) styleMap.remove(key);
        else styleMap.put(key, value.trim());
        if (styleMap.isEmpty()) {
            n.removeAttribute("style");
        } else {
            n.setAttribute("style", styleMap.entrySet().stream()
                    .map(e -> e.getKey() + ": " + e.getValue() + ";")
                    .collect(Collectors.joining(" ")));
        }
    }

    static String styleProperty(HtmlNode n, String property) {
        if (property == null || property.trim().isEmpty()) return null;
        return parseInlineStyle(n).get(property.trim().toLowerCase());
    }

    private static Map<String, String> parseInlineStyle(HtmlNode n) {
        Map<String, String> map = new LinkedHashMap<>();
        String raw = n.attrs.getOrDefault("style", "");
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
}
