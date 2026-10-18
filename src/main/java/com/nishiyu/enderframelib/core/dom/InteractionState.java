package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.ElementState;
import com.nishiyu.enderframelib.core.css.SelectorMatcher;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ビュー単位の対話状態（疑似クラス状態）や算出スタイルキャッシュを管理するクラス。
 * これにより、1つの {@link HtmlNode}（文書ツリー）を複数のビューで共有しつつ、
 * 状態変化をスタイル・レイアウトパイプラインへの入力として扱う。
 */
public class InteractionState implements SelectorMatcher.StateLookup {

    private final Map<String, StateFlags> states = new HashMap<>();
    private final Set<String> hoveredChainIds = new HashSet<>();
    private final Set<String> activeChainIds = new HashSet<>();
    private final Set<String> focusWithinChainIds = new HashSet<>();
    private String focusedNodeId = "";

    public static class StateFlags {
        public boolean isHovered;
        public boolean isFocused;
        public boolean isFocusVisible;
        public boolean isFocusWithin;
        public boolean isActive;
        public Boolean isChecked;
        public Boolean isDisabled;
        public Boolean isIndeterminate;
        public Boolean isOpen;

        /** カスケード解決後の生宣言キャッシュ。 */
        public Map<String, String> computedStyleCache;
        /** 確定済みの算出スタイルキャッシュ。 */
        public ComputedStyle computedStyle;
        /** 算出時の親フォントサイズ（px）。 */
        public float cachedParentFontSize = -1f;
        /** 算出時のルート要素のフォントサイズ（px、rem の基準）。 */
        public float cachedRootFontSize = -1f;
        /** 算出時のビューポート。font-size の vw / vh が算出値に入るので、変わったら再スタイルする。 */
        public int cachedViewportW = -1;
        public int cachedViewportH = -1;
        /** この要素に対話状態（:hover 等）のルールが存在するか。 */
        public boolean hasInteractiveRules;
        /** スタイル再計算が必要かどうかのフラグ。 */
        public boolean isStyleDirty = true;
    }

    public StateFlags getOrCreate(String nodeId) {
        return states.computeIfAbsent(nodeId, k -> new StateFlags());
    }

    public StateFlags get(String nodeId) {
        return states.get(nodeId);
    }

    @Override
    public ElementState stateOf(HtmlNode node) {
        if (node == null) return ElementState.NONE;
        int bits = ElementState.fromNodeAttributes(node).flags();
        StateFlags flags = states.get(node.getId());
        if (flags != null) {
            if (flags.isHovered) bits |= ElementState.HOVER;
            if (flags.isActive) bits |= ElementState.ACTIVE;
            if (flags.isFocused) bits |= ElementState.FOCUS;
            if (flags.isFocusVisible) bits |= ElementState.FOCUS_VISIBLE;
            if (flags.isFocusWithin || flags.isFocused) bits |= ElementState.FOCUS_WITHIN;
            if (flags.isChecked != null) {
                if (flags.isChecked) bits |= ElementState.CHECKED;
                else bits &= ~ElementState.CHECKED;
            }
            if (flags.isDisabled != null) {
                if (flags.isDisabled) bits |= ElementState.DISABLED;
                else bits &= ~ElementState.DISABLED;
            }
            if (flags.isIndeterminate != null) {
                if (flags.isIndeterminate) bits |= ElementState.INDETERMINATE;
                else bits &= ~ElementState.INDETERMINATE;
            }
            if (flags.isOpen != null) {
                if (flags.isOpen) bits |= ElementState.OPEN;
                else bits &= ~ElementState.OPEN;
            }
        }
        return bits == 0 ? ElementState.NONE : new ElementState(bits);
    }

    public boolean setHovered(String nodeId, boolean hovered) {
        if (nodeId == null || nodeId.isEmpty()) return false;
        StateFlags flags = getOrCreate(nodeId);
        if (flags.isHovered != hovered) {
            flags.isHovered = hovered;
            flags.isStyleDirty = true;
            return true;
        }
        return false;
    }

    /**
     * 現在ホバー中のノード（およびその祖先チェーン）を一括更新する。
     * 変化があった場合は true を返す。
     */
    public boolean setHoveredNode(HtmlNode root, String nodeId) {
        Set<String> nextChain = collectAncestorIds(root, nodeId);
        if (hoveredChainIds.equals(nextChain)) return false;

        boolean changed = false;
        for (String oldId : hoveredChainIds) {
            if (!nextChain.contains(oldId)) {
                changed |= setHovered(oldId, false);
            }
        }
        for (String newId : nextChain) {
            if (!hoveredChainIds.contains(newId)) {
                changed |= setHovered(newId, true);
            }
        }
        hoveredChainIds.clear();
        hoveredChainIds.addAll(nextChain);
        return changed;
    }

    public boolean setActive(String nodeId, boolean active) {
        if (nodeId == null || nodeId.isEmpty()) return false;
        StateFlags flags = getOrCreate(nodeId);
        if (flags.isActive != active) {
            flags.isActive = active;
            flags.isStyleDirty = true;
            return true;
        }
        return false;
    }

    /**
     * 現在押下中（:active）のノード（およびその祖先チェーン）を一括更新する。
     */
    public boolean setActiveNode(HtmlNode root, String nodeId) {
        Set<String> nextChain = collectAncestorIds(root, nodeId);
        if (activeChainIds.equals(nextChain)) return false;

        boolean changed = false;
        for (String oldId : activeChainIds) {
            if (!nextChain.contains(oldId)) {
                changed |= setActive(oldId, false);
            }
        }
        for (String newId : nextChain) {
            if (!activeChainIds.contains(newId)) {
                changed |= setActive(newId, true);
            }
        }
        activeChainIds.clear();
        activeChainIds.addAll(nextChain);
        return changed;
    }

    public boolean setFocused(String nodeId, boolean focused) {
        return setFocused(nodeId, focused, false);
    }

    public boolean setFocused(String nodeId, boolean focused, boolean focusVisible) {
        if (nodeId == null || nodeId.isEmpty()) return false;
        StateFlags flags = getOrCreate(nodeId);
        boolean nextVisible = focused && focusVisible;
        if (flags.isFocused != focused || flags.isFocusVisible != nextVisible) {
            flags.isFocused = focused;
            flags.isFocusVisible = nextVisible;
            flags.isFocusWithin = focused;
            flags.isStyleDirty = true;
            return true;
        }
        return false;
    }

    public boolean setFocusVisible(String nodeId, boolean focusVisible) {
        if (nodeId == null || nodeId.isEmpty()) return false;
        StateFlags flags = getOrCreate(nodeId);
        boolean nextFocused = flags.isFocused || focusVisible;
        if (flags.isFocused != nextFocused || flags.isFocusVisible != focusVisible) {
            flags.isFocused = nextFocused;
            flags.isFocusVisible = focusVisible;
            flags.isFocusWithin = nextFocused;
            flags.isStyleDirty = true;
            return true;
        }
        return false;
    }

    /**
     * フォーカス要素と :focus-within 祖先チェーンを一括更新する。
     *
     * @param focusVisible キーボード操作由来のフォーカスなら true
     */
    public boolean setFocusedNode(HtmlNode root, String nodeId, boolean focusVisible) {
        String safeId = nodeId != null ? nodeId : "";
        Set<String> nextChain = collectAncestorIds(root, safeId);
        boolean changed = false;

        if (!Objects.equals(focusedNodeId, safeId)) {
            if (!focusedNodeId.isEmpty()) {
                StateFlags oldFlags = getOrCreate(focusedNodeId);
                if (oldFlags.isFocused || oldFlags.isFocusVisible) {
                    oldFlags.isFocused = false;
                    oldFlags.isFocusVisible = false;
                    oldFlags.isStyleDirty = true;
                    changed = true;
                }
            }
            focusedNodeId = safeId;
        }

        if (!safeId.isEmpty()) {
            changed |= setFocused(safeId, true, focusVisible);
        }

        for (String oldId : focusWithinChainIds) {
            if (!nextChain.contains(oldId)) {
                StateFlags f = getOrCreate(oldId);
                if (f.isFocusWithin) {
                    f.isFocusWithin = false;
                    f.isStyleDirty = true;
                    changed = true;
                }
            }
        }
        for (String newId : nextChain) {
            StateFlags f = getOrCreate(newId);
            if (!f.isFocusWithin) {
                f.isFocusWithin = true;
                f.isStyleDirty = true;
                changed = true;
            }
        }
        focusWithinChainIds.clear();
        focusWithinChainIds.addAll(nextChain);
        return changed;
    }

    public boolean setChecked(String nodeId, Boolean checked) {
        if (nodeId == null || nodeId.isEmpty()) return false;
        StateFlags flags = getOrCreate(nodeId);
        if (!Objects.equals(flags.isChecked, checked)) {
            flags.isChecked = checked;
            flags.isStyleDirty = true;
            return true;
        }
        return false;
    }

    public boolean setDisabled(String nodeId, Boolean disabled) {
        if (nodeId == null || nodeId.isEmpty()) return false;
        StateFlags flags = getOrCreate(nodeId);
        if (!Objects.equals(flags.isDisabled, disabled)) {
            flags.isDisabled = disabled;
            flags.isStyleDirty = true;
            return true;
        }
        return false;
    }

    public boolean setIndeterminate(String nodeId, Boolean indeterminate) {
        if (nodeId == null || nodeId.isEmpty()) return false;
        StateFlags flags = getOrCreate(nodeId);
        if (!Objects.equals(flags.isIndeterminate, indeterminate)) {
            flags.isIndeterminate = indeterminate;
            flags.isStyleDirty = true;
            return true;
        }
        return false;
    }

    public boolean setOpen(String nodeId, Boolean open) {
        if (nodeId == null || nodeId.isEmpty()) return false;
        StateFlags flags = getOrCreate(nodeId);
        if (!Objects.equals(flags.isOpen, open)) {
            flags.isOpen = open;
            flags.isStyleDirty = true;
            return true;
        }
        return false;
    }

    public void markDirty(String nodeId) {
        if (nodeId == null || nodeId.isEmpty()) return;
        getOrCreate(nodeId).isStyleDirty = true;
    }

    public void markAllDirty() {
        for (StateFlags flags : states.values()) {
            flags.isStyleDirty = true;
        }
    }

    public boolean hasDirtyNodes() {
        for (StateFlags flags : states.values()) {
            if (flags.isStyleDirty) return true;
        }
        return false;
    }

    public void clear() {
        states.clear();
        hoveredChainIds.clear();
        activeChainIds.clear();
        focusWithinChainIds.clear();
        focusedNodeId = "";
    }

    private static Set<String> collectAncestorIds(HtmlNode root, String targetId) {
        Set<String> ids = new HashSet<>();
        if (targetId == null || targetId.isEmpty()) return ids;
        ids.add(targetId);
        if (root == null) return ids;
        HtmlNode cur = DomUtils.findNodeById(root, targetId);
        while (cur != null) {
            if (cur.getId() != null && !cur.getId().isEmpty()) {
                ids.add(cur.getId());
            }
            cur = cur.parent;
        }
        return ids;
    }
}