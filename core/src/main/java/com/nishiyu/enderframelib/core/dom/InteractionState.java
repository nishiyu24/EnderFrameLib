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
import com.nishiyu.enderframelib.core.kernel.memory.DocumentArena;
import java.util.function.BiConsumer;

/**
 * ビュー単位の対話状態（疑似クラス状態）や算出スタイルキャッシュを管理するクラス。
 *
 * <p>ノードオブジェクト自身（{@link HtmlNode}）が唯一のキー。文字列 ID は、ネットワークと描画の境界で
 * ノードへ解決してから渡す（解決は {@link HtmlNode#getNodeById} の索引が担う）。
 * 状態の実体は節が直接持ち（持ち主の節と一緒に回収される。弱参照の表は使わない）、
 * 列挙用の表は {@link DocumentArena} のノード番号で引く。擬似状態と汚れの印は、アリーナの
 * {@code stateBits} / {@code dirtyBits} 列にも書き込む。</p>
 */
public class InteractionState implements StateLookup {

    /** アリーナごとの列挙用の表。節が文書から外れて番号の世代が進めば、その項は自然に無効になる。 */
    private record Tab(DocumentArena arena, DocumentArena.NodeSlots<StateFlags> slots) {}

    private final List<Tab> tabs = new ArrayList<>(2);
    private Tab lastTab;
    private static final int MAX_TABS = 8;
    /** {@link #clear} のたびに進む。古い版の状態は節に残っていても無かったことにする。 */
    private int epoch;

    private Tab tabFor(DocumentArena arena) {
        Tab t = lastTab;
        if (t != null && t.arena == arena) return t;
        for (Tab other : tabs) {
            if (other.arena == arena) return lastTab = other;
        }
        if (tabs.size() >= MAX_TABS) {
            Tab dropped = tabs.remove(0);
            dropped.arena.dropSlots(this);
        }
        t = new Tab(arena, arena.slots(this));
        tabs.add(t);
        return lastTab = t;
    }

    /** アリーナに載せない節（仮想の節）の一覧。節が回収されれば項は消える。 */
    private final List<java.lang.ref.WeakReference<HtmlNode>> loose = new ArrayList<>();

    private void forEachFlags(BiConsumer<HtmlNode, StateFlags> visitor) {
        for (int i = 0; i < loose.size(); i++) {
            HtmlNode node = loose.get(i).get();
            StateFlags f = node != null && node.interactionOwner == this ? (StateFlags) node.interactionFlags : null;
            if (f == null || f.epoch != epoch) {
                loose.remove(i--);
                if (f != null) f.loose = false;
                continue;
            }
            visitor.accept(node, f);
        }
        for (Tab tab : new ArrayList<>(tabs)) {
            tab.slots.forEach((id, flags) -> {
                if (tab.arena.owner(id) instanceof HtmlNode node) visitor.accept(node, flags);
            });
        }
    }

    /** 節の状態がアリーナの現在の番号の表に載っているようにし、擬似状態・汚れの列へ写す。 */
    private void register(HtmlNode node, StateFlags flags) {
        if (node.isVirtualNode() && !node.hasNodeId()) {
            // 疑似要素・匿名ボックスなどの仮想の節は文書のアリーナに数えない。列挙は弱参照の一覧で行う
            if (!flags.loose) {
                flags.loose = true;
                loose.add(new java.lang.ref.WeakReference<>(node));
            }
            return;
        }
        DocumentArena a = node.arena();
        int id = node.nodeId();
        if (flags.mirrorArena == a && flags.mirrorId == id && flags.mirrorGen == a.generation(id)) return;
        flags.mirrorArena = a;
        flags.mirrorId = id;
        flags.mirrorGen = a.generation(id);
        tabFor(a).slots.put(id, flags);
        flags.writeMirror();
    }

    /**
     * 無効化セットの出所。結ばれていれば、変化の影響が及ぶ要素だけを汚す。結ばれていない間（最初の描画の前）は、
     * 変化した要素の子孫も祖先も汚す（従来の安全側の動き）。
     */
    private StyleSheet sheet;

    /** 無効化セットを引くシートを結ぶ。描画のたびに呼んでよい（同じシートなら何もしない）。 */
    /** 1 回の再スタイル／箱の木の組み立ての間だけ使う、スタイル解決の使い回し表。走査の前後で空にする。 */
    public final com.nishiyu.enderframelib.core.css.StyleResolver.ShareCache styleShare =
            new com.nishiyu.enderframelib.core.css.StyleResolver.ShareCache();

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
        /** 状態 → 算出スタイルの表（自身だけの再スタイルで使い回す）。 */
        public com.nishiyu.enderframelib.core.kernel.compiler.StateStyleMemo stateMemo;
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
        /** アリーナ側の列への写し先（番号の世代が変われば無効）。 */
        DocumentArena mirrorArena;
        int mirrorId = -1;
        int mirrorGen;
        int epoch;
        boolean loose;

        /** 擬似状態と汚れの印をアリーナの列へ書く（番号が有効なときだけ）。 */
        void writeMirror() {
            DocumentArena a = mirrorArena;
            if (a == null || mirrorGen != a.generation(mirrorId)) return;
            int bits = 0;
            if (isHovered) bits |= DocumentArena.STATE_HOVER;
            if (isActive) bits |= DocumentArena.STATE_ACTIVE;
            if (isFocused) bits |= DocumentArena.STATE_FOCUS;
            if (isFocusVisible) bits |= DocumentArena.STATE_FOCUS_VISIBLE;
            if (isFocusWithin) bits |= DocumentArena.STATE_FOCUS_WITHIN;
            if (isChecked != null && isChecked) bits |= DocumentArena.STATE_CHECKED;
            if (isDisabled != null && isDisabled) bits |= DocumentArena.STATE_DISABLED;
            if (isIndeterminate != null && isIndeterminate) bits |= DocumentArena.STATE_INDETERMINATE;
            if (isOpen != null && isOpen) bits |= DocumentArena.STATE_OPEN;
            a.setStateBits(mirrorId, bits);
            a.setDirty(mirrorId, DocumentArena.DIRTY_STYLE, isStyleDirty);
        }
        /** 持ち主。汚れた節の個数（{@link InteractionState#dirtyCount}）を O(1) で保つ。持ち主の無い使い捨てなら null。 */
        private InteractionState owner;

        /** {@link #isStyleDirty} を立てる。書き込みは必ずここを通す（個数の帳簿が合わなくなる）。 */
        public void markStyleDirty() {
            if (!isStyleDirty) {
                isStyleDirty = true;
                if (owner != null) owner.dirtyCount++;
                writeMirror();
            }
        }

        /** {@link #isStyleDirty} を下ろす。 */
        public void clearStyleDirtyFlag() {
            if (isStyleDirty) {
                isStyleDirty = false;
                if (owner != null && owner.dirtyCount > 0) owner.dirtyCount--;
                writeMirror();
            }
        }
        /**
         * この要素の子孫も、継承元が変わったかどうかによらず再スタイルが要るか
         * （{@code .a:hover .b} のように、祖先の状態・class・属性を読む規則がある）。
         */
        public boolean subtreeDirty = true;
        /**
         * 子孫のどこかが再スタイルの対象（{@link #isStyleDirty} / {@link #subtreeDirty}）になっている。汚す側が祖先へ印を付け、
         * 再スタイルの走査はこれが無く自分も汚れていない部分木を丸ごと飛ばす（ブラウザの dirty-bit と同じ）。
         * 作りたては安全側に true。
         */
        public boolean dirtyBelow = true;
        /** 影響範囲を絞らずに汚した（{@link InteractionState#markDirty}）。祖先も汚し直す必要がある。 */
        public boolean unscoped = true;
        /** style 属性が {@code inherit} を含む。親の非継承プロパティの変化が子へ届く。 */
        public boolean inheritsExplicitly;
        /** 問い合わせコンテナとして測った内容ボックスの寸法（px）。測っていなければ NaN。 */
        public float containerWidth = Float.NaN;
        public float containerHeight = Float.NaN;
        /** {@link #containerOf} の結果の使い回し（算出スタイルと寸法が同じ間だけ有効）。 */
        ComputedStyle containerStyle;
        float containerBoxW, containerBoxH;
        ContainerBox containerBox;
    }

    /** 再スタイルの走査が、汚れていない部分木を飛ばしてよいか（走査のたびに {@code RenderPipeline} が決める）。 */
    public boolean pruneRestyle;

    public StateFlags getOrCreate(HtmlNode node) {
        if (node == null) return new StateFlags();
        StateFlags found = node.interactionOwner == this ? (StateFlags) node.interactionFlags : null;
        if (found != null && found.epoch != epoch) found = null; // clear() の前の状態
        if (found != null) {
            register(node, found);
            return found;
        }
        StateFlags created = new StateFlags(); // 作りたては汚れている
        created.owner = this;
        created.epoch = epoch;
        dirtyCount++;
        unscopedLog.add(node); // 作りたては unscoped
        node.interactionOwner = this;
        node.interactionFlags = created;
        register(node, created);
        return created;
    }

    /**
     * {@link StateFlags#isStyleDirty} が立っている節の個数の上限（O(1) の問い合わせ用）。
     * 0 なら汚れた節は確実に無い。節が回収されると汚れたまま表から消えて実際より大きくなり得るので、
     * 正の値は「あるかもしれない」で、{@link #hasDirtyNodes} が確かめて数え直す。
     */
    private int dirtyCount;

    /**
     * {@link StateFlags#unscoped} が立った節の記録（{@link #propagateDirtyToAncestors} が全走査せずに済むように）。
     * 古い記録（その後に片付いた節）が残ってもよい。使う側が印を見直す。使い終わったら空にする。
     */
    private final List<HtmlNode> unscopedLog = new ArrayList<>();

    /** 確定済みの算出スタイル。まだ再スタイルされていなければ null。 */
    public ComputedStyle computedStyleOf(HtmlNode node) {
        StateFlags flags = get(node);
        return flags != null ? flags.computedStyle : null;
    }

    public StateFlags get(HtmlNode node) {
        if (node == null || node.interactionOwner != this) return null;
        StateFlags found = (StateFlags) node.interactionFlags;
        if (found == null || found.epoch != epoch) return null;
        register(node, found);
        return found;
    }

    @Override
    public ElementState stateOf(HtmlNode node) {
        return ElementState.of(stateBitsOf(node));
    }

    /** {@link #stateOf} のビット集合そのもの（値オブジェクトを作らない）。 */
    public int stateBitsOf(HtmlNode node) {
        if (node == null) return 0;
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
        return bits;
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
        flags.markStyleDirty();
        flags.subtreeDirty = true;
        if (!flags.unscoped) {
            flags.unscoped = true;
            unscopedLog.add(node);
        }
        markAncestorsDirtyBelow(node);
    }

    /**
     * 祖先すべてに「子孫に汚れがある」印を付ける（根まで。深さの分だけで済む）。
     * 印が付いている祖先で止める早期終了は入れない: 「印が付いていれば祖先も付いている」が成り立たない場面がある
     * （IncrementalLayoutFuzzTest で、印済みの節の祖先が未印の状態が見つかった）。
     */
    private void markAncestorsDirtyBelow(HtmlNode node) {
        for (HtmlNode cur = node.parent; cur != null; cur = cur.parent) getOrCreate(cur).dirtyBelow = true;
    }

    /** 対話状態（{@code :hover} など）のビットが変わった要素の影響範囲を汚す。 */
    public void invalidateState(HtmlNode node, int stateBits) {
        StateFlags written = get(node);
        if (written != null) written.writeMirror(); // 擬似状態のビットをアリーナ列へ写す
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
        flags.markStyleDirty();
        markAncestorsDirtyBelow(node);
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
        flags.markStyleDirty();
        if (subtree) flags.subtreeDirty = true;
        markAncestorsDirtyBelow(node);
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
        forEachFlags((node, flags) -> {
            flags.markStyleDirty();
            flags.subtreeDirty = true;
            flags.dirtyBelow = true;
        });
    }

    /**
     * 影響範囲を絞らずに汚した要素の祖先も汚す。{@code :has()} は子孫・後続の兄弟の状態で要素自身の見た目が変わるので、
     * 子孫の状態が変わったら、{@code :has()} の持ち主になり得る祖先を再スタイルに含める必要がある。
     */
    public void propagateDirtyToAncestors() {
        if (unscopedLog.isEmpty()) return;
        List<HtmlNode> dirty = new ArrayList<>();
        for (HtmlNode node : unscopedLog) {
            StateFlags flags = get(node);
            // 影響範囲を絞って汚した要素の祖先は、無効化セットが既に汚している
            if (flags != null && flags.isStyleDirty && flags.unscoped) {
                dirty.add(node);
                flags.unscoped = false;
            }
        }
        unscopedLog.clear();
        for (HtmlNode node : dirty) {
            for (HtmlNode cur = node.parent; cur != null; cur = cur.parent) {
                StateFlags flags = get(cur);
                if (flags != null) {
                    flags.markStyleDirty();
                    flags.dirtyBelow = true;
                }
            }
        }
    }

    /** {@code :has()} を使わない文書では {@link #propagateDirtyToAncestors} が呼ばれないので、記録が溜まらないよう捨てる。 */
    public void discardUnscopedLog() {
        unscopedLog.clear();
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
            flags.markStyleDirty();
            flags.subtreeDirty = true;
            markAncestorsDirtyBelow(node);
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
        // 祖先をたどる問い合わせのたびに作り直さない（名前の分割・カスタムプロパティの表は、算出スタイルが変わるまで同じ）
        ContainerBox hit = flags.containerBox;
        if (hit != null && flags.containerStyle == style
                && Float.floatToIntBits(flags.containerBoxW) == Float.floatToIntBits(flags.containerWidth)
                && Float.floatToIntBits(flags.containerBoxH) == Float.floatToIntBits(flags.containerHeight)) {
            return hit;
        }
        ContainerType type = switch (style.keyword("container-type", "normal")) {
            case "size" -> ContainerType.SIZE;
            case "inline-size" -> ContainerType.INLINE_SIZE;
            default -> ContainerType.NORMAL;
        };
        String nameList = style.keyword("container-name", "none");
        List<String> names;
        if (nameList.equals("none") || nameList.isEmpty()) {
            names = List.of(); // 正規表現の分割を避ける（大半の要素は名前を持たない）
        } else {
            names = new ArrayList<>();
            for (String name : nameList.split("\s+")) {
                if (!name.isEmpty() && !name.equals("none")) names.add(name);
            }
        }
        ContainerBox box = new ContainerBox(flags.containerWidth, flags.containerHeight, type, names,
                style.customProperties());
        flags.containerStyle = style;
        flags.containerBoxW = flags.containerWidth;
        flags.containerBoxH = flags.containerHeight;
        flags.containerBox = box;
        return box;
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

    /** 再スタイルが必要な印を持つ節が、すべて {@code allowed}（同一性で比べる）に含まれるか。印が無ければ true。 */
    public boolean styleDirtyOnlyIn(java.util.Collection<HtmlNode> allowed) {
        if (dirtyCount <= 0) return true;
        Set<HtmlNode> set = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        set.addAll(allowed);
        // dirtyCount は実数以上なので、allowed の中の汚れた節の数がそれに届けば、外に汚れた節は無い（全走査を省ける）
        int inside = 0;
        for (HtmlNode n : set) {
            StateFlags f = get(n);
            if (f != null && f.isStyleDirty) inside++;
        }
        if (inside >= dirtyCount) return true;
        // 届かないときは、回収された節の分の過大か、外に本当に汚れた節があるか。確かめる
        boolean[] outside = {false};
        forEachFlags((node, flags) -> {
            if (flags.isStyleDirty && !set.contains(node)) outside[0] = true;
        });
        return !outside[0];
    }

    /**
     * 文字の節の再スタイルの印を片付ける。文字の節のスタイルは親から継承するだけで、文字が変わっても規則の当たり方は
     * 変わらないので、箱の文字を差し替えれば済む（祖先の「子孫に汚れがある」印は、次の走査が片付ける）。
     */
    public void clearStyleDirty(HtmlNode node) {
        StateFlags flags = get(node);
        if (flags == null) return;
        flags.clearStyleDirtyFlag();
        flags.subtreeDirty = false;
        flags.unscoped = false;
    }

    public boolean hasDirtyNodes() {
        if (dirtyCount <= 0) return false; // 止まっている画面は、毎フレームここで O(1) で終わる
        boolean[] any = {false};
        forEachFlags((node, flags) -> {
            if (flags.isStyleDirty) any[0] = true;
        });
        if (any[0]) return true;
        dirtyCount = 0; // 汚れたまま回収された節の分。帳簿を合わせ直す
        return false;
    }

    public void clear() {
        epoch++;
        loose.clear();
        for (Tab tab : tabs) tab.arena.dropSlots(this);
        tabs.clear();
        lastTab = null;
        dirtyCount = 0;
        unscopedLog.clear();
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
