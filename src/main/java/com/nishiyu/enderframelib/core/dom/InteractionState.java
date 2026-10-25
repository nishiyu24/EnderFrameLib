package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.core.dom.node.*;
import com.nishiyu.enderframelib.core.dom.parser.*;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.model.ElementState;
import com.nishiyu.enderframelib.core.css.model.StateLookup;
import com.nishiyu.enderframelib.core.css.model.ContainerBox;
import com.nishiyu.enderframelib.core.css.model.ContainerType;
import com.nishiyu.enderframelib.core.css.SelectorMatcher;
import com.nishiyu.enderframelib.core.css.InvalidationSets;
import com.nishiyu.enderframelib.core.css.InvalidationSets.Relation;
import com.nishiyu.enderframelib.core.css.StyleAnimator;
import com.nishiyu.enderframelib.core.css.StyleSheet;

import java.util.ArrayList;
import java.util.EnumSet;
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

    /**
     * 無効化セットの出所。結ばれていれば、変化の影響が及ぶ要素だけを汚す。結ばれていない間（最初の描画の前）は、
     * 変化した要素の子孫も祖先も汚す（従来の安全側の動き）。
     */
    private StyleSheet sheet;

    /** 無効化セットを引くシートを結ぶ。描画のたびに呼んでよい（同じシートなら何もしない）。 */
    public void bindStyleSheet(StyleSheet sheet) {
        this.sheet = sheet;
    }

    private InvalidationSets invalidationSets() {
        return sheet != null ? sheet.invalidationSets() : null;
    }

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
        /**
         * この要素の子孫も、継承元が変わったかどうかによらず再スタイルが要るか
         * （{@code .a:hover .b} のように、祖先の状態・class・属性を読む規則がある）。
         */
        public boolean subtreeDirty = true;
        /** 影響範囲を絞らずに汚した（{@link InteractionState#markDirty}）。祖先も汚し直す必要がある。 */
        public boolean unscoped = true;
        /** style 属性が {@code inherit} を含む。親の非継承プロパティの変化が子へ届く。 */
        public boolean inheritsExplicitly;
        /** 問い合わせコンテナとして測った内容ボックスの寸法（px）。測っていなければ NaN。 */
        public float containerWidth = Float.NaN;
        public float containerHeight = Float.NaN;
    }

    public StateFlags getOrCreate(HtmlNode node) {
        if (node == null) return new StateFlags();
        return flagsByNode.computeIfAbsent(node, key -> new StateFlags());
    }

    /** 確定済みの算出スタイル。まだ再スタイルされていなければ null。 */
    public ComputedStyle computedStyleOf(HtmlNode node) {
        StateFlags flags = get(node);
        return flags != null ? flags.computedStyle : null;
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
        invalidateState(node, ElementState.HOVER);
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
        invalidateState(node, ElementState.ACTIVE);
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
        invalidateState(node, ElementState.FOCUS | ElementState.FOCUS_VISIBLE | ElementState.FOCUS_WITHIN);
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
                    invalidateState(focusedNode, ElementState.FOCUS | ElementState.FOCUS_VISIBLE);
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
                invalidateState(left, ElementState.FOCUS_WITHIN);
                changed = true;
            }
        }
        for (HtmlNode entered : nextChain) {
            StateFlags flags = getOrCreate(entered);
            if (!flags.isFocusWithin) {
                flags.isFocusWithin = true;
                invalidateState(entered, ElementState.FOCUS_WITHIN);
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
        invalidateState(node, ElementState.CHECKED);
        return true;
    }

    public boolean setDisabled(HtmlNode node, Boolean disabled) {
        if (node == null) return false;
        StateFlags flags = getOrCreate(node);
        if (Objects.equals(flags.isDisabled, disabled)) return false;
        flags.isDisabled = disabled;
        invalidateState(node, ElementState.DISABLED);
        return true;
    }

    public boolean setIndeterminate(HtmlNode node, Boolean indeterminate) {
        if (node == null) return false;
        StateFlags flags = getOrCreate(node);
        if (Objects.equals(flags.isIndeterminate, indeterminate)) return false;
        flags.isIndeterminate = indeterminate;
        invalidateState(node, ElementState.INDETERMINATE);
        return true;
    }

    public boolean setOpen(HtmlNode node, Boolean open) {
        if (node == null) return false;
        StateFlags flags = getOrCreate(node);
        if (Objects.equals(flags.isOpen, open)) return false;
        flags.isOpen = open;
        invalidateState(node, ElementState.OPEN);
        return true;
    }

    /**
     * 理由を絞らずに要素を汚す。子孫も祖先も再スタイルの対象になる。変化の種類が分かる呼び出しは
     * {@link #invalidateState} などで影響範囲を絞ること。
     */
    public void markDirty(HtmlNode node) {
        if (node == null) return;
        StateFlags flags = getOrCreate(node);
        flags.isStyleDirty = true;
        flags.subtreeDirty = true;
        flags.unscoped = true;
    }

    /** 対話状態（{@code :hover} など）のビットが変わった要素の影響範囲を汚す。 */
    public void invalidateState(HtmlNode node, int stateBits) {
        InvalidationSets sets = invalidationSets();
        if (sets == null) {
            markDirty(node);
            return;
        }
        dirty(node, false);
        invalidate(node, sets.forState(stateBits));
    }

    /**
     * 属性の変化で汚す。要素自身は常に再スタイルする（{@code hidden}・{@code style}・フォームの属性は規則の外で読まれる）。
     * 加えて、規則がその属性・class・id を読んでいる範囲の要素を汚す。
     *
     * @param oldValue 変わる前の値（無ければ null）
     * @param newValue 変わった後の値（無ければ null）
     */
    public void invalidateAttribute(HtmlNode node, String name, String oldValue, String newValue) {
        InvalidationSets sets = invalidationSets();
        if (sets == null) {
            markDirty(node);
            return;
        }
        StateFlags flags = getOrCreate(node);
        flags.isStyleDirty = true;
        Set<Relation> relations = EnumSet.noneOf(Relation.class);
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.equals("class")) {
            relations.addAll(sets.forAttribute("class"));
            Set<String> before = tokens(oldValue);
            Set<String> after = tokens(newValue);
            Set<String> changed = new HashSet<>(before);
            changed.addAll(after);
            Set<String> kept = new HashSet<>(before);
            kept.retainAll(after);
            changed.removeAll(kept);
            for (String cls : changed) relations.addAll(sets.forClass(cls));
        } else if (lower.equals("id")) {
            relations.addAll(sets.forId(oldValue == null ? "" : oldValue));
            relations.addAll(sets.forId(newValue == null ? "" : newValue));
        } else {
            relations.addAll(sets.forAttribute(lower));
            // checked / disabled / open などは属性から状態が決まる。そのビットを読む規則があれば一緒に汚す
            relations.addAll(sets.forState(ElementState.CHECKED | ElementState.DISABLED
                    | ElementState.INDETERMINATE | ElementState.OPEN));
        }
        if (lower.equals("style")) {
            flags.inheritsExplicitly = newValue != null
                    && newValue.toLowerCase(java.util.Locale.ROOT).contains("inherit");
        }
        invalidate(node, relations);
    }

    /**
     * 子の増減で汚す。{@code changedAt} は {@code parent} の子のうち増減があった位置
     * （挿入ならその子の位置、削除なら削除前にその子がいた位置）。
     */
    public void invalidateChildList(HtmlNode parent, int changedAt) {
        InvalidationSets sets = invalidationSets();
        if (sets == null) {
            markDirty(parent);
            return;
        }
        dirty(parent, false);
        Set<Relation> relations = sets.forStructureChange();
        if (parent.parent == null) relations.addAll(sets.forRootChange());
        if (relations.isEmpty()) return;
        List<HtmlNode> siblings = parent.children;
        int size = siblings.size();
        for (Relation relation : relations) {
            switch (relation) {
                case FOLLOWING_SIBLINGS, FOLLOWING_SIBLINGS_SUBTREE -> {
                    // 挿入ならその子の次から、削除なら同じ位置に詰めた子から
                    boolean subtree = relation == Relation.FOLLOWING_SIBLINGS_SUBTREE;
                    for (int i = Math.max(0, changedAt); i < size; i++) dirty(siblings.get(i), subtree);
                }
                case PRECEDING_SIBLINGS -> {
                    for (int i = 0; i < Math.min(changedAt, size); i++) dirty(siblings.get(i), false);
                }
                case ANCESTORS -> {
                    for (HtmlNode cur = parent; cur != null; cur = cur.parent) dirty(cur, false);
                }
                case WHOLE_TREE -> markAllDirty();
                case SELF -> dirty(parent, false);
                case DESCENDANTS -> dirty(parent, true);
            }
        }
    }

    private void invalidate(HtmlNode node, Set<Relation> relations) {
        for (Relation relation : relations) {
            switch (relation) {
                case SELF -> dirty(node, false);
                case DESCENDANTS -> dirty(node, true);
                case FOLLOWING_SIBLINGS, FOLLOWING_SIBLINGS_SUBTREE -> {
                    boolean subtree = relation == Relation.FOLLOWING_SIBLINGS_SUBTREE;
                    List<HtmlNode> siblings = node.parent != null ? node.parent.children : List.of();
                    int index = siblings.indexOf(node);
                    for (int i = index + 1; index >= 0 && i < siblings.size(); i++) dirty(siblings.get(i), subtree);
                }
                case PRECEDING_SIBLINGS -> {
                    List<HtmlNode> siblings = node.parent != null ? node.parent.children : List.of();
                    int index = siblings.indexOf(node);
                    for (int i = 0; i < index; i++) dirty(siblings.get(i), false);
                }
                case ANCESTORS -> {
                    for (HtmlNode cur = node.parent; cur != null; cur = cur.parent) dirty(cur, false);
                }
                case WHOLE_TREE -> markAllDirty();
            }
        }
    }

    private void dirty(HtmlNode node, boolean subtree) {
        StateFlags flags = getOrCreate(node);
        flags.isStyleDirty = true;
        if (subtree) flags.subtreeDirty = true;
    }

    private static Set<String> tokens(String classAttribute) {
        Set<String> out = new HashSet<>();
        if (classAttribute == null) return out;
        for (String token : classAttribute.trim().split("\\s+")) {
            if (!token.isEmpty()) out.add(token);
        }
        return out;
    }

    public void markAllDirty() {
        for (StateFlags flags : flagsByNode.values()) {
            flags.isStyleDirty = true;
            flags.subtreeDirty = true;
        }
    }

    /**
     * 影響範囲を絞らずに汚した要素の祖先も汚す。{@code :has()} は子孫・後続の兄弟の状態で要素自身の見た目が変わるので、
     * 子孫の状態が変わったら、{@code :has()} の持ち主になり得る祖先を再スタイルに含める必要がある。
     */
    public void propagateDirtyToAncestors() {
        List<HtmlNode> dirty = new ArrayList<>();
        for (Map.Entry<HtmlNode, StateFlags> e : flagsByNode.entrySet()) {
            StateFlags flags = e.getValue();
            // 影響範囲を絞って汚した要素の祖先は、無効化セットが既に汚している
            if (flags.isStyleDirty && flags.unscoped) {
                dirty.add(e.getKey());
                flags.unscoped = false;
            }
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
        if (changed) {
            flags.isStyleDirty = true;
            flags.subtreeDirty = true;
        }
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

    // ==========================================
    // アニメーション（トランジションと @keyframes のうち、再スタイルで動かす分）
    // ==========================================

    private StyleAnimator animator;
    private long animationTimeMs = Long.MIN_VALUE;

    /** この対話状態の要素が持つ、時間で動くスタイルの状態。最初に使うまで作らない。 */
    public StyleAnimator animator() {
        if (animator == null) animator = new StyleAnimator();
        return animator;
    }

    /** 再スタイルがアニメーションの値を決めるときの時刻（ms）。フレームの先頭で決めると、同じフレームの値が揃う。 */
    public void setAnimationTime(long ms) {
        this.animationTimeMs = ms;
    }

    /** 設定されていなければ現在時刻。 */
    public long animationTimeMs() {
        return animationTimeMs != Long.MIN_VALUE ? animationTimeMs : System.currentTimeMillis();
    }

    /**
     * 時刻を進め、値が変わる要素を再スタイルの対象にする（要素自身だけ。子孫への波及は再スタイルが算出値の変化から決める）。
     *
     * @return まだ動いているものがあるか（あれば次のフレームも呼ぶ）
     */
    public boolean advanceAnimations(long now) {
        if (animator == null) return false;
        animationTimeMs = now;
        for (HtmlNode node : animator.advance(now)) dirty(node, false);
        return animator.isRunning(now);
    }

    public boolean hasDirtyNodes() {
        for (StateFlags flags : flagsByNode.values()) {
            if (flags.isStyleDirty) return true;
        }
        return false;
    }

    public void clear() {
        flagsByNode.clear();
        if (animator != null) animator.clear();
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
