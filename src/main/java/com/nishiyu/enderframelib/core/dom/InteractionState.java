// ==========================================
// 新規: core/dom/InteractionState.java
// ==========================================
package com.nishiyu.enderframelib.core.dom;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * ビュー単位の対話状態やスタイルキャッシュを管理するクラス。
 * これにより、1つのHtmlNode（文書ツリー）を複数のビューで共有できるようになります。
 */
public class InteractionState {
    // ノードIDをキーにした状態管理
    private final Map<String, StateFlags> states = new HashMap<>();

    public static class StateFlags {
        public boolean isHovered;
        public boolean isFocused;
        public boolean isActive;
        public boolean isChecked;

        // 旧 computedStyle の役割（ビュー側でキャッシュ）
        public Map<String, String> computedStyleCache;
        // スタイル再計算が必要かどうかのフラグ
        public boolean isStyleDirty = true;
    }

    public StateFlags getOrCreate(String nodeId) {
        return states.computeIfAbsent(nodeId, k -> new StateFlags());
    }

    public void setHovered(String nodeId, boolean hovered) {
        StateFlags flags = getOrCreate(nodeId);
        if (flags.isHovered != hovered) {
            flags.isHovered = hovered;
            flags.isStyleDirty = true; // 状態が変わればスタイル再計算
        }
    }

    // ... focus, active, checked などのセッターも同様に追加 ...
}