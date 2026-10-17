package com.nishiyu.enderframelib.core.css;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 疑似クラスの状態ごとに宣言集合を持つスタイル。
 *
 * 以前は {@code "hover-color"} のようなキープレフィックスで 5 つのプロパティだけを
 * 特別扱いしていたため、{@code :hover} ブロック内のそれ以外のプロパティが黙って捨てられていた。
 * ここでは状態ごとに宣言をそのまま保持するので、全プロパティが効く。
 */
public class StyleSet {
    public final Map<String, String> base = new LinkedHashMap<>();
    public final Map<String, String> hover = new LinkedHashMap<>();
    public final Map<String, String> active = new LinkedHashMap<>();
    public final Map<String, String> focus = new LinkedHashMap<>();

    /** 現在の対話状態に基づいて、適用すべき宣言を合成して返す。 */
    public Map<String, String> resolve(ElementState state) {
        Map<String, String> result = new LinkedHashMap<>(base);
        if (state == null) return result;
        if (state.hover()) result.putAll(hover);
        if (state.focus()) result.putAll(focus);
        if (state.active()) result.putAll(active);
        return result;
    }
}
