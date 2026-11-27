package com.nishiyu.enderframelib.core.dom.node;

import com.nishiyu.enderframelib.api.dom.CSSStyleDeclaration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * Concrete implementation of {@link CSSStyleDeclaration} backed by an {@link HtmlElement}'s style attribute.
 */
public final class HtmlCSSStyleDeclaration implements CSSStyleDeclaration {

    private record PropertyEntry(String value, String priority) {}

    private final HtmlElement element;
    private final Map<String, PropertyEntry> properties = new LinkedHashMap<>();
    private boolean isSyncing = false;

    public HtmlCSSStyleDeclaration(HtmlElement element) {
        this.element = element;
        syncFromAttribute(element.getAttribute("style"));
    }

    public void syncFromAttribute(@Nullable String styleText) {
        if (isSyncing) return;
        properties.clear();
        if (styleText == null || styleText.trim().isEmpty()) return;

        for (String decl : styleText.split(";")) {
            int colon = decl.indexOf(':');
            if (colon <= 0) continue;
            String prop = decl.substring(0, colon).trim().toLowerCase();
            String val = decl.substring(colon + 1).trim();
            if (prop.isEmpty()) continue;

            String priority = "";
            if (val.toLowerCase().endsWith("!important")) {
                priority = "important";
                val = val.substring(0, val.length() - 10).trim();
            }
            properties.put(prop, new PropertyEntry(val, priority));
        }
    }

    private void syncToAttribute() {
        if (isSyncing) return;
        isSyncing = true;
        try {
            if (properties.isEmpty()) {
                element.removeAttribute("style");
            } else {
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, PropertyEntry> entry : properties.entrySet()) {
                    sb.append(entry.getKey()).append(": ").append(entry.getValue().value());
                    if ("important".equalsIgnoreCase(entry.getValue().priority())) {
                        sb.append(" !important");
                    }
                    sb.append("; ");
                }
                element.setAttribute("style", sb.toString().trim());
            }
        } finally {
            isSyncing = false;
        }
    }

    @Override
    public String getCssText() {
        String attr = element.getAttribute("style");
        return attr != null ? attr : "";
    }

    @Override
    public void setCssText(String cssText) {
        syncFromAttribute(cssText);
        syncToAttribute();
    }

    @Override
    public int getLength() {
        return properties.size();
    }

    @Nullable
    @Override
    public String item(int index) {
        if (index < 0 || index >= properties.size()) return null;
        return new ArrayList<>(properties.keySet()).get(index);
    }

    @Override
    public String getPropertyValue(String property) {
        if (property == null) return "";
        PropertyEntry entry = properties.get(property.trim().toLowerCase());
        return entry != null ? entry.value() : "";
    }

    @Override
    public String getPropertyPriority(String property) {
        if (property == null) return "";
        PropertyEntry entry = properties.get(property.trim().toLowerCase());
        return entry != null ? entry.priority() : "";
    }

    @Override
    public void setProperty(String property, @Nullable String value, @Nullable String priority) {
        if (property == null || property.trim().isEmpty()) return;
        String name = property.trim().toLowerCase();
        if (value == null || value.trim().isEmpty()) {
            removeProperty(name);
            return;
        }
        String pri = priority != null && "important".equalsIgnoreCase(priority.trim()) ? "important" : "";
        properties.put(name, new PropertyEntry(value.trim(), pri));
        syncToAttribute();
    }

    @Override
    public String removeProperty(String property) {
        if (property == null) return "";
        String name = property.trim().toLowerCase();
        PropertyEntry removed = properties.remove(name);
        if (removed != null) {
            syncToAttribute();
            return removed.value();
        }
        return "";
    }
}
