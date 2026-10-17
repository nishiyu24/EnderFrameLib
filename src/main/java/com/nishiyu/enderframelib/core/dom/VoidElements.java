// ==========================================
// 新規: core/dom/VoidElements.java
// ==========================================
package com.nishiyu.enderframelib.core.dom;

import java.util.Set;

/**
 * 空要素（閉じタグを持たない要素）の定義。
 * 複数箇所にあった定義を1本化します。
 */
public class VoidElements {
    // HTML標準の空要素 + EnderFrame固有の空要素
    public static final Set<String> TAGS = Set.of(
            "br", "hr", "img", "input", "meta", "link",
            "mc-fluid", "mc-entity", "mc-scene", "mc-slot", "mc-slot-grid", "mc-inventory", "mc-bar"
    );

    public static boolean isVoid(String tagName) {
        return TAGS.contains(tagName != null ? tagName.toLowerCase() : "");
    }
}