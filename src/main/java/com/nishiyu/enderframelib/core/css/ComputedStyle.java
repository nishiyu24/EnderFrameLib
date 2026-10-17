package com.nishiyu.enderframelib.core.css;

import java.util.Collections;
import java.util.Map;

/**
 * 算出スタイルを保持する不変の値型。
 * LayoutBox や レンダラ が直接 Map を書き換えることを防ぎます。
 */
public class ComputedStyle {

    private final Map<String, String> properties;
    private final float elementFontSize;
    private final float rootFontSize;

    public ComputedStyle(Map<String, String> properties, float elementFontSize, float rootFontSize) {
        this.properties = Collections.unmodifiableMap(properties);
        this.elementFontSize = elementFontSize;
        this.rootFontSize = rootFontSize;
    }

    public ComputedStyle(Map<String, String> properties) {
        this(properties, 16.0f, 16.0f);
    }

    public String get(String propertyName) {
        return properties.get(propertyName);
    }

    public String getOrDefault(String propertyName, String defaultValue) {
        return properties.getOrDefault(propertyName, defaultValue);
    }

    public boolean containsKey(String propertyName) {
        return properties.containsKey(propertyName);
    }

    public int length(String propertyName, int maxPixels, int rootW, int rootH) {
        String val = properties.get(propertyName);
        if (val == null) return 0;
        return LengthResolver.parseDim(val, maxPixels, rootW, rootH, elementFontSize, rootFontSize);
    }

    public int color(String propertyName, int defaultColor) {
        String val = properties.get(propertyName);
        if (val == null) return defaultColor;
        return ColorParser.parseColor(val);
    }

    public Map<String, String> getRawMap() {
        return properties;
    }
}