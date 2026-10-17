package com.nishiyu.enderframelib.core.css;

import java.util.HashMap;
import java.util.Map;

/**
 * 疑似クラス状態を型で表すためのクラス。
 * 従来の "hover-color" などのキープレフィックスを廃止し、状態ごとにマップを保持します。
 */
public class StyleSet {
    public final Map<String, String> base = new HashMap<>();
    public final Map<String, String> hover = new HashMap<>();
    public final Map<String, String> active = new HashMap<>();
    public final Map<String, String> focus = new HashMap<>();

    /**
     * 現在の対話状態に基づいて、適用すべきスタイルを合成して返します。
     */
    public Map<String, String> computeCurrentState(boolean isHovered, boolean isActive, boolean isFocused) {
        Map<String, String> result = new HashMap<>(base);
        if (isHovered) result.putAll(hover);
        if (isFocused) result.putAll(focus);
        if (isActive) result.putAll(active);
        return result;
    }
}