package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.api.dom.CSSStyleDeclaration;
import com.nishiyu.enderframelib.api.dom.DOMException;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.dom.node.LayoutQuery;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code getComputedStyle()} の読み取り専用の宣言。値は算出値で、{@code width} / {@code height} と余白だけは
 * レイアウト済みなら使用値（解決値）の px を返す（CSSOM §6.7.2 の解決値に当たる）。書き込みは
 * {@code NoModificationAllowedError}。
 */
final class ComputedStyleDeclaration implements CSSStyleDeclaration {

    @Nullable
    private final Style style;
    @Nullable
    private Map<String, String> lazyMap;
    @Nullable
    private final LayoutQuery.BoxMetrics box;

    ComputedStyleDeclaration(@Nullable ComputedStyle style, @Nullable LayoutQuery.BoxMetrics box) {
        this.style = style != null ? style.typed() : null;
        this.box = box;
    }

    private Map<String, String> map() {
        Map<String, String> m = lazyMap;
        if (m == null) lazyMap = m = style != null ? style.toCssMap() : Map.of();
        return m;
    }

    private List<String> names() {
        return new ArrayList<>(map().keySet());
    }

    @Override
    public String getCssText() {
        StringBuilder sb = new StringBuilder();
        for (String name : names()) {
            if (name.startsWith("--")) continue;
            sb.append(name).append(": ").append(getPropertyValue(name)).append("; ");
        }
        return sb.toString().trim();
    }

    @Override
    public void setCssText(String cssText) {
        throw readOnly();
    }

    @Override
    public int getLength() {
        return map().size();
    }

    @Nullable
    @Override
    public String item(int index) {
        List<String> names = names();
        return index >= 0 && index < names.size() ? names.get(index) : null;
    }

    @Override
    public String getPropertyValue(String property) {
        if (property == null) return "";
        String name = property.startsWith("--") ? property : property.toLowerCase(Locale.ROOT);
        if (box != null) {
            String used = usedValue(name);
            if (used != null) return used;
        }
        String value = style != null ? style.cssomProperty(name) : null;
        return value != null ? value : "";
    }

    @Nullable
    private String usedValue(String name) {
        float v;
        switch (name) {
            case "width" -> v = box.borderBoxSizing() ? box.w() : box.w() - box.bL() - box.bR() - box.pL() - box.pR();
            case "height" -> v = box.borderBoxSizing() ? box.h() : box.h() - box.bT() - box.bB() - box.pT() - box.pB();
            case "margin-top" -> v = box.mT();
            case "margin-right" -> v = box.mR();
            case "margin-bottom" -> v = box.mB();
            case "margin-left" -> v = box.mL();
            case "padding-top" -> v = box.pT();
            case "padding-right" -> v = box.pR();
            case "padding-bottom" -> v = box.pB();
            case "padding-left" -> v = box.pL();
            default -> {
                return null;
            }
        }
        float rounded = Math.round(Math.max(0, v) * 1000f) / 1000f;
        return (rounded == (long) rounded ? String.valueOf((long) rounded) : String.valueOf(rounded)) + "px";
    }

    @Override
    public String getPropertyPriority(String property) {
        return "";
    }

    @Override
    public void setProperty(String property, @Nullable String value, @Nullable String priority) {
        throw readOnly();
    }

    @Override
    public String removeProperty(String property) {
        throw readOnly();
    }

    private static DOMException readOnly() {
        return new DOMException("The computed style declaration is read-only.", "NoModificationAllowedError");
    }
}
