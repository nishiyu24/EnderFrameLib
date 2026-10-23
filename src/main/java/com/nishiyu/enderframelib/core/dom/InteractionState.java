package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.core.dom.node.*;
import com.nishiyu.enderframelib.core.dom.parser.*;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.model.ElementState;
import com.nishiyu.enderframelib.core.css.model.StateLookup;
import com.nishiyu.enderframelib.core.css.model.ContainerBox;
import com.nishiyu.enderframelib.core.css.model.ContainerType;
import com.nishiyu.enderframelib.core.css.SelectorMatcher;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * ビュー単位の対話状態（疑似クラス状態）や算出スタイルキャッシュを管理するクラス。
 *
 * <p>ノードオブジェクト自身（{@link HtmlNode}）が唯一のキー。文字列 ID は、ネットワークと描画の境界で
 * ノードへ解決してから渡す（解決は {@link HtmlNode#getNodeById} の索引が担う）。
 * 持ち主の節が文書から外れたら状態も一緒に回収されるよう、キーは弱参照で持つ。</p>
 */
public class InteractionState implements StateLookup {

    private final Map<HtmlNode, StateFlags> flagsByNode = new WeakHashMap<>();

    /** 現在 {@code :hover} な節（ポインタの下の節とその祖先）。 */
    private Set<HtmlNode> hoveredChain = new HashSet<>();
    /** 現在 {@code :active} な節（押下中の節とその祖先）。 */
    private Set<HtmlNode> activeChain = new HashSet<>();
    /** 現在 {@code :focus-within} な節（フォーカス中の節とその祖先）。 */
    private Set<HtmlNode> focusWithinChain = new HashSet<>();

    /** ポインタの下の節（{@link #hoveredChain} の末端）。 */
    private HtmlNode hoveredNode = null;
    /** 押下中の節（{@link #activeChain} の末端）。 */
    private HtmlNode activeNode = null;
    /** フォーカス中の節。 */
    private HtmlNode focusedNode = null;

    /** ポインタの下の節。無ければ null。 */
    public HtmlNode hoveredNode() {
        return hoveredNode;
    }

    /** 押下中の節。無ければ null。 */
    public HtmlNode activeNode() {
        return activeNode;
    }

    /** フォーカス中の節。無ければ null。 */
    public HtmlNode focusedNode() {
        return focusedNode;
    }

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
        /** 算出時のルート要素の行の高さ（px、rlh の基準）。 */
        public float cachedRootLineHeight = -1f;
        /** 算出時のビューポート。font-size の vw / vh が算出値に入るので、変わったら再スタイルする。 */
        public int cachedViewportW = -1;
        public int cachedViewportH = -1;
        /**
         * 宣言値がビューポートの大きさに依存し得るか（{@code vw} / {@code cqw} などの単位、{@code var()}）。
         * 依存しない要素は、ビューポートが変わっても算出スタイルのキャッシュをそのまま使う。
         */
        public boolean viewportDependent = true;
        /** この要素に対話状態（:hover 等）のルールが存在するか。 */
        public boolean hasInteractiveRules;
        /** スタイル再計算が必要かどうかのフラグ。 */
        public boolean isStyleDirty = true;
        /** 問い合わせコンテナとして測った内容ボックスの寸法（px）。測っていなければ NaN。 */
        public float containerWidth = Float.NaN;
        public float containerHeight = Float.NaN;
    }

    public StateFlags getOrCreate(HtmlNode node) {
        if (node == null) return new StateFlags();
        return flagsByNode.computeIfAbsent(node, key -> new StateFlags());
    }

    public StateFlags get(HtmlNode node) {
        return node != null ? flagsByNode.get(node) : null;
    }

    @Override
    public ElementState stateOf(HtmlNode node) {
        if (node == null) return ElementState.NONE;
        int bits = ElementState.fromNodeAttributes(node).flags();
        StateFlags flags = get(node);
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

    public boolean setHovered(HtmlNode node, boolean hovered) {
        if (node == null) return false;
        StateFlags flags = getOrCreate(node);
        if (flags.isHovered == hovered) return false;
        flags.isHovered = hovered;
        flags.isStyleDirty = true;
        return true;
    }

    /** ポインタの下の節を更新する。{@code target} とその祖先が {@code :hover}、それ以外は外れる。null で全解除。 */
    public boolean setHoveredNode(HtmlNode target) {
        Set<HtmlNode> nextChain = ancestorsAndSelf(target);
        hoveredNode = target;
        if (hoveredChain.equals(nextChain)) return false;

        boolean changed = false;
        for (HtmlNode left : hoveredChain) {
            if (!nextChain.contains(left)) changed |= setHovered(left, false);
        }
        for (HtmlNode entered : nextChain) {
            if (!hoveredChain.contains(entered)) changed |= setHovered(entered, true);
        }
        hoveredChain = nextChain;
        return changed;
    }

    public boolean setActive(HtmlNode node, boolean active) {
        if (node == null) return false;
        StateFlags flags = getOrCreate(node);
        if (flags.isActive == active) return false;
        flags.isActive = active;
        flags.isStyleDirty = true;
        return true;
    }

    /** 押下中の節を更新する。{@code target} とその祖先が {@code :active}、それ以外は外れる。null で全解除。 */
    public boolean setActiveNode(HtmlNode target) {
        Set<HtmlNode> nextChain = ancestorsAndSelf(target);
        activeNode = target;
        if (activeChain.equals(nextChain)) return false;

        boolean changed = false;
        for (HtmlNode released : activeChain) {
            if (!nextChain.contains(released)) changed |= setActive(released, false);
        }
        for (HtmlNode pressed : nextChain) {
            if (!activeChain.contains(pressed)) changed |= setActive(pressed, true);
        }
        activeChain = nextChain;
        return changed;
    }

    public boolean setFocused(HtmlNode node, boolean focused, boolean focusVisible) {
        if (node == null) return false;
        StateFlags flags = getOrCreate(node);
        boolean nextVisible = focused && focusVisible;
        if (flags.isFocused == focused && flags.isFocusVisible == nextVisible) return false;
        flags.isFocused = focused;
        flags.isFocusVisible = nextVisible;
        flags.isFocusWithin = focused;
        flags.isStyleDirty = true;
        return true;
    }

    /**
     * フォーカス中の節を更新する。{@code target} が {@code :focus}、{@code target} とその祖先が
     * {@code :focus-within}、前にフォーカスされていた節は外れる。null でフォーカスを外す。
     */
    public boolean setFocusedNode(HtmlNode target, boolean focusVisible) {
        boolean changed = false;

        if (focusedNode != target) {
            if (focusedNode != null) {
                StateFlags oldFlags = getOrCreate(focusedNode);
                if (oldFlags.isFocused || oldFlags.isFocusVisible) {
                    oldFlags.isFocused = false;
                    oldFlags.isFocusVisible = false;
                    oldFlags.isStyleDirty = true;
                    changed = true;
                }
            }
            focusedNode = target;
        }

        if (target != null) {
            changed |= setFocused(target, true, focusVisible);
        }

        Set<HtmlNode> nextChain = ancestorsAndSelf(target);
        for (HtmlNode left : focusWithinChain) {
            if (nextChain.contains(left)) continue;
            StateFlags flags = getOrCreate(left);
            if (flags.isFocusWithin) {
                flags.isFocusWithin = false;
                flags.isStyleDirty = true;
                changed = true;
            }
        }
        for (HtmlNode entered : nextChain) {
            StateFlags flags = getOrCreate(entered);
            if (!flags.isFocusWithin) {
                flags.isFocusWithin = true;
                flags.isStyleDirty = true;
                changed = true;
            }
        }
        focusWithinChain = nextChain;
        return changed;
    }

    public boolean setChecked(HtmlNode node, Boolean checked) {
        if (node == null) return false;
        StateFlags flags = getOrCreate(node);
        if (Objects.equals(flags.isChecked, checked)) return false;
        flags.isChecked = checked;
        flags.isStyleDirty = true;
        return true;
    }

    public boolean setDisabled(HtmlNode node, Boolean disabled) {
        if (node == null) return false;
        StateFlags flags = getOrCreate(node);
        if (Objects.equals(flags.isDisabled, disabled)) return false;
        flags.isDisabled = disabled;
        flags.isStyleDirty = true;
        return true;
    }

    public boolean setIndeterminate(HtmlNode node, Boolean indeterminate) {
        if (node == null) return false;
        StateFlags flags = getOrCreate(node);
        if (Objects.equals(flags.isIndeterminate, indeterminate)) return false;
        flags.isIndeterminate = indeterminate;
        flags.isStyleDirty = true;
        return true;
    }

    public boolean setOpen(HtmlNode node, Boolean open) {
        if (node == null) return false;
        StateFlags flags = getOrCreate(node);
        if (Objects.equals(flags.isOpen, open)) return false;
        flags.isOpen = open;
        flags.isStyleDirty = true;
        return true;
    }

    public void markDirty(HtmlNode node) {
        if (node == null) return;
        getOrCreate(node).isStyleDirty = true;
    }

    public void markAllDirty() {
        for (StateFlags flags : flagsByNode.values()) {
            flags.isStyleDirty = true;
        }
    }

    /**
     * 汚れた要素の祖先も汚す。{@code :has()} は子孫・後続の兄弟の状態で要素自身の見た目が変わるので、
     * 子孫の状態が変わったら、{@code :has()} の持ち主になり得る祖先を再スタイルに含める必要がある。
     */
    public void propagateDirtyToAncestors() {
        List<HtmlNode> dirty = new ArrayList<>();
        for (Map.Entry<HtmlNode, StateFlags> e : flagsByNode.entrySet()) {
            if (e.getValue().isStyleDirty) dirty.add(e.getKey());
        }
        for (HtmlNode node : dirty) {
            for (HtmlNode cur = node.parent; cur != null; cur = cur.parent) {
                StateFlags flags = flagsByNode.get(cur);
                if (flags != null) flags.isStyleDirty = true;
            }
        }
    }

    /**
     * 問い合わせコンテナの寸法を記録する。以前と違う（NaN から測れた場合も含む）ならその要素を汚して true を返す。
     * 寸法が変わるとコンテナ問い合わせの結果とコンテナ単位の長さが変わるので、呼び出し側は再スタイルして測り直す。
     */
    public boolean setContainerSize(HtmlNode node, float width, float height) {
        if (node == null) return false;
        StateFlags flags = getOrCreate(node);
        boolean changed = differs(flags.containerWidth, width) || differs(flags.containerHeight, height);
        flags.containerWidth = width;
        flags.containerHeight = height;
        if (changed) flags.isStyleDirty = true;
        return changed;
    }

    private static boolean differs(float a, float b) {
        if (Float.isNaN(a) || Float.isNaN(b)) return Float.isNaN(a) != Float.isNaN(b);
        return Math.abs(a - b) > 0.01f;
    }

    @Override
    public ContainerBox containerOf(HtmlNode node) {
        StateFlags flags = get(node);
        if (flags == null || flags.computedStyle == null) return null;
        ComputedStyle style = flags.computedStyle;
        ContainerType type = switch (style.keyword("container-type", "normal")) {
            case "size" -> ContainerType.SIZE;
            case "inline-size" -> ContainerType.INLINE_SIZE;
            default -> ContainerType.NORMAL;
        };
        List<String> names = new ArrayList<>();
        for (String name : style.keyword("container-name", "none").split("\s+")) {
            if (!name.isEmpty() && !name.equals("none")) names.add(name);
        }
        return new ContainerBox(flags.containerWidth, flags.containerHeight, type, names,
                style.customProperties());
    }

    public boolean hasDirtyNodes() {
        for (StateFlags flags : flagsByNode.values()) {
            if (flags.isStyleDirty) return true;
        }
        return false;
    }

    public void clear() {
        flagsByNode.clear();
        hoveredChain = new HashSet<>();
        activeChain = new HashSet<>();
        focusWithinChain = new HashSet<>();
        hoveredNode = null;
        activeNode = null;
        focusedNode = null;
    }

    private static Set<HtmlNode> ancestorsAndSelf(HtmlNode node) {
        Set<HtmlNode> chain = new HashSet<>();
        for (HtmlNode cur = node; cur != null; cur = cur.parent) {
            chain.add(cur);
        }
        return chain;
    }
}
