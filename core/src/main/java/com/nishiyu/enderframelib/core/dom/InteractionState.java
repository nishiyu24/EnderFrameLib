package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.core.kernel.Clock;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.dom.node.*;

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
 * 状態の実体は各ビューの表に保持し（文書上の節は {@link DocumentArena.NodeSlots}、仮想の節は
 * {@link VirtualNodeSlots}）、{@link HtmlNode} 自体は書き換えない。擬似状態は {@link StateFlags#state} の 1 本の整数
 * （{@link ElementState} のビット）が唯一の記憶で、アリーナの列へ写し書きはしない
 * （写しは誰にも読まれず、書くだけの二重コストだった）。</p>
 */
public class InteractionState implements StyledDocumentState {

    /** アリーナごとの列挙用の表。節が文書から外れて番号の世代が進めば、その項は自然に無効になる。 */
    private record Tab(DocumentArena arena, DocumentArena.NodeSlots<StateFlags> slots) {}

    private final List<Tab> tabs = new ArrayList<>(2);
    private volatile Tab lastTab;
    private static final int MAX_TABS = 8;
    /** 直近の {@code restyleBoxRecursive} が根の子孫へ降りたか（ルート専用再レイアウトの印付け省略判定用）。 */
    public boolean restyleDescended;

    private Tab findTab(DocumentArena arena) {
        Tab t = lastTab;
        if (t != null && t.arena == arena) return t;
        synchronized (this) {
            for (Tab other : tabs) {
                if (other.arena == arena) return lastTab = other;
            }
            if (arena.hasSlots(this)) return tabFor(arena);
            return null;
        }
    }

    private synchronized Tab tabFor(DocumentArena arena) {
        Tab t = lastTab;
        if (t != null && t.arena == arena) return t;
        for (Tab other : tabs) {
            if (other.arena == arena) return lastTab = other;
        }
        if (tabs.size() >= MAX_TABS) {
            int evict = (tabs.get(0) == lastTab && tabs.size() > 1) ? 1 : 0;
            Tab dropped = tabs.remove(evict);
            dropped.arena.dropSlots(this);
        }
        t = new Tab(arena, arena.slots(this));
        tabs.add(t);
        return lastTab = t;
    }

    /** アリーナに載せない節（仮想の節）の表。節が回収されれば項は消える。 */
    private volatile VirtualNodeSlots<StateFlags> loose;

    private void forEachFlags(BiConsumer<HtmlNode, StateFlags> visitor) {
        VirtualNodeSlots<StateFlags> v;
        List<Tab> tabCopy;
        synchronized (this) {
            v = loose;
            tabCopy = new ArrayList<>(tabs);
        }
        if (v != null) v.forEach(visitor);
        for (Tab tab : tabCopy) {
            tab.slots.forEach((id, flags) -> {
                if (tab.arena.owner(id) instanceof HtmlNode node) visitor.accept(node, flags);
            });
        }
    }

    /**
     * 無効化セットの出所。結ばれていれば、変化の影響が及ぶ要素だけを汚す。結ばれていない間（最初の描画の前）は、
     * 変化した要素の子孫も祖先も汚す（従来の安全側の動き）。
     */
    private StyleSheet sheet;

    /** 無効化セットを引くシートを結ぶ。描画のたびに呼んでよい（同じシートなら何もしない）。 */
    /** 1 回の再スタイル／箱の木の組み立ての間だけ使う、スタイル解決の使い回し表。走査の前後で空にする。 */
    public final com.nishiyu.enderframelib.core.css.ShareCache styleShare =
            new com.nishiyu.enderframelib.core.css.ShareCache();

    public void bindStyleSheet(StyleSheet sheet) { this.sheet = sheet; }

    private InvalidationSets invalidationSets() { return sheet != null ? sheet.invalidationSets() : null; }

    /** 現在 {@code :hover} な節（ポインタの下の節とその祖先）。 */
    private ArrayList<HtmlNode> hoveredChain = new ArrayList<>(16);
    /** 現在 {@code :active} な節（押下中の節とその祖先）。 */
    private ArrayList<HtmlNode> activeChain = new ArrayList<>(16);
    /** 現在 {@code :focus-within} な節（フォーカス中の節とその祖先）。 */
    private ArrayList<HtmlNode> focusWithinChain = new ArrayList<>(16);
    /** 鎖更新時の作業用リスト（フレームごとの確保を避ける）。 */
    private ArrayList<HtmlNode> scratchChain = new ArrayList<>(16);

    /** ポインタの下の節（{@link #hoveredChain} の末端）。 */
    private HtmlNode hoveredNode = null;
    /** 押下中の節（{@link #activeChain} の末端）。 */
    private HtmlNode activeNode = null;
    /** フォーカス中の節。 */
    private HtmlNode focusedNode = null;

    /** ポインタの下の節。無ければ null。 */
    public HtmlNode hoveredNode() { return hoveredNode; } // 押下中・フォーカス中の節も同じく読むだけ

    /** 押下中の節。無ければ null。 */
    public HtmlNode activeNode() { return activeNode; }

    /** フォーカス中の節。無ければ null。 */
    public HtmlNode focusedNode() { return focusedNode; }

    public static class StateFlags {
        /**
         * 明示された対話状態。{@link ElementState} のビット（HOVER / ACTIVE / FOCUS / FOCUS_VISIBLE / FOCUS_WITHIN /
         * CHECKED / DISABLED / INDETERMINATE / OPEN）が唯一の記憶。
         */
        public int state;
        /** {@link #state} のうち CHECKED / DISABLED / INDETERMINATE / OPEN は 3 値（未指定・真・偽）。指定済みのビット。 */
        public int overridden;

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
        /** {@link #viewportDependent} を一度でも確かめたか（作りたては未確認で、依存するとみなす）。 */
        private boolean viewportDependenceKnown;

        /**
         * 要素のビューポート依存を記録する。ルート以外で依存が増えた・初めて確かめたときは、持ち主の世代
         * （{@link InteractionState#viewportDependenceEpoch}）を進めて「依存する要素は無い」の記憶を捨てさせる。
         */
        public void noteViewportDependence(boolean root, boolean dependent) {
            if (!root && (!viewportDependenceKnown || viewportDependent != dependent) && owner != null) owner.viewportDependenceEpoch.incrementAndGet();
            viewportDependenceKnown = true;
            viewportDependent = dependent;
        }

        public boolean viewportDependenceKnown() { return viewportDependenceKnown; }
        /** {@code content-visibility: auto} で直近の配置時に表示域内（非スキップ）だったか、最後に記録された内容寸法。 */
        public boolean contentAutoActive;
        public float lastRememberedWidth = Float.NaN;
        public float lastRememberedHeight = Float.NaN;
        /** この要素に対話状態（:hover 等）のルールが存在するか。 */
        public boolean hasInteractiveRules;
        /**
         * {@link #hasInteractiveRules} を求めたときの入力（シート・規則の版・節の属性の版・親）。入力が同じなら、親の算出スタイルだけが
         * 変わった再スタイル（継承の変化）で求め直さなくてよい。祖先・兄弟・構造の変化は、汚れの印（FORCE_SUBTREE / DIRTY_STYLE）で別に伝わる。
         */
        public Object interactiveSheet;
        public int interactiveVersion;
        public int interactiveAttrVersion;
        public HtmlNode interactiveParent;

        public boolean isHovered() { return (state & ElementState.HOVER) != 0; }
        public boolean isActive() { return (state & ElementState.ACTIVE) != 0; }
        public boolean isFocused() { return (state & ElementState.FOCUS) != 0; }
        public boolean isFocusVisible() { return (state & ElementState.FOCUS_VISIBLE) != 0; }
        public boolean isFocusWithin() { return (state & ElementState.FOCUS_WITHIN) != 0; }

        /** 2 値のビットを書く。変わったら true。 */
        boolean setBit(int bit, boolean on) {
            int next = on ? (state | bit) : (state & ~bit);
            if (next == state) return false;
            state = next;
            return true;
        }

        /** 3 値のビット（未指定は null）。 */
        public Boolean tri(int bit) {
            return (overridden & bit) == 0 ? null : (state & bit) != 0;
        }

        /** 3 値のビットを書く。変わったら true。 */
        boolean setTri(int bit, Boolean value) {
            if (java.util.Objects.equals(tri(bit), value)) return false;
            if (value == null) {
                overridden &= ~bit;
                state &= ~bit;
            } else {
                overridden |= bit;
                state = value ? (state | bit) : (state & ~bit);
            }
            return true;
        }

        /** 持ち主。汚れた節の個数（{@link InteractionState#dirtyCount}）を O(1) で保つ。持ち主の無い使い捨てなら null。 */
        private InteractionState owner;

        /** 自身の再スタイルが必要。 */
        public static final int DIRTY_STYLE = 1;
        /**
         * この要素の子孫も、継承元が変わったかどうかによらず再スタイルが要るか
         * （{@code .a:hover .b} のように、祖先の状態・class・属性を読む規則がある）。
         */
        public static final int DIRTY_SUBTREE = 2;
        /**
         * 子孫のどこかが再スタイルの対象（{@link #DIRTY_STYLE} / {@link #DIRTY_SUBTREE}）になっている。汚す側が祖先へ印を付け、
         * 再スタイルの走査はこれが無く自分も汚れていない部分木を丸ごと飛ばす（ブラウザの dirty-bit と同じ）。
         */
        public static final int DIRTY_BELOW = 4;
        /** 影響範囲を絞らずに汚した（{@link InteractionState#markDirty}）。祖先も汚し直す必要がある。 */
        public static final int DIRTY_UNSCOPED = 8;

        /** 汚れ印（{@code DIRTY_*} のビット）の唯一の記憶。作りたては安全側にすべて立つ。 */
        private int dirty = DIRTY_STYLE | DIRTY_SUBTREE | DIRTY_BELOW | DIRTY_UNSCOPED;

        /** 汚れ印の生のビット（検査・テスト用）。 */
        public int dirtyBits() { return dirty; }

        public boolean isStyleDirty() { return (dirty & DIRTY_STYLE) != 0; }
        public boolean subtreeDirty() { return (dirty & DIRTY_SUBTREE) != 0; }
        public boolean dirtyBelow() { return (dirty & DIRTY_BELOW) != 0; }
        public boolean unscoped() { return (dirty & DIRTY_UNSCOPED) != 0; }

        public void markSubtreeDirty() { dirty |= DIRTY_SUBTREE; }
        public void clearSubtreeDirty() { dirty &= ~DIRTY_SUBTREE; }
        public void markDirtyBelow() { dirty |= DIRTY_BELOW; }
        public void clearDirtyBelow() { dirty &= ~DIRTY_BELOW; }
        void markUnscoped() { dirty |= DIRTY_UNSCOPED; }
        public void clearUnscoped() { dirty &= ~DIRTY_UNSCOPED; }

        /** {@link #DIRTY_STYLE} を立てる。書き込みは必ずここを通す（個数の帳簿が合わなくなる）。 */
        public void markStyleDirty() {
            if ((dirty & DIRTY_STYLE) == 0) {
                dirty |= DIRTY_STYLE;
                if (owner != null) owner.dirtyCount.incrementAndGet();
            }
        }

        /** {@link #DIRTY_STYLE} を下ろす。 */
        public void clearStyleDirtyFlag() {
            if ((dirty & DIRTY_STYLE) != 0) {
                dirty &= ~DIRTY_STYLE;
                if (owner != null) owner.dirtyCount.updateAndGet(v -> v > 0 ? v - 1 : 0);
            }
        }
        /** style 属性が {@code inherit} を含む。親の非継承プロパティの変化が子へ届く。 */
        public boolean inheritsExplicitly;
        /** {@link #inheritsExplicitly} を評価したときの {@code node.attrVersion()}。未評価は -1。 */
        public int inheritsAttrVersion = -1;
        /** 問い合わせコンテナとして測った内容ボックスの寸法（px）。測っていなければ NaN。 */
        public float containerWidth = Float.NaN;
        public float containerHeight = Float.NaN;
        /** {@link #containerOf} の結果の使い回し（算出スタイルと寸法が同じ間だけ有効）。 */
        ComputedStyle containerStyle;
        float containerBoxW, containerBoxH;
        ContainerBox containerBox;
    }

    /** ルート以外の要素のビューポート依存が変わるたびに進む世代。{@code RenderPipeline} の「依存する要素は無い」の記憶の鍵。 */
    public final java.util.concurrent.atomic.AtomicInteger viewportDependenceEpoch =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 「ルート以外にビューポートへ依存する要素は無い」と確かめた木・世代・シートの版（無ければ null / -1）。 */
    public Object viewportFreeRoot;
    public int viewportFreeEpoch = -1;
    public Object viewportFreeSheet;
    public int viewportFreeVersion;
    public boolean viewportFreeResult;

    /** 子ノードの追加・削除など、DOMの木構造が変わるたびに進む版番号（K5b L4 で変更台帳へ置き換える）。 */
    public int domStructureVersion;
    /** 前回箱の木（BoxTreeBuilder）を組んだときの {@link #domStructureVersion}。 */
    public int lastBuiltStructureVersion;
    public final ChangeLedger ledger = new ChangeLedger(); // 変更台帳（K5b L0。各段が読む唯一の変更の入口へ置き換えていく。設計書 §3.6）
    @Override public ChangeLedger changeLedger() { return ledger; }

    public void notifyDomStructureChanged() { domStructureVersion++; }

    /** 再スタイルの走査が、汚れていない部分木を飛ばしてよいか（走査のたびに {@code RenderPipeline} が決める）。 */
    public boolean pruneRestyle;

    public StateFlags getOrCreate(HtmlNode node) {
        if (node == null) return new StateFlags();
        if (node.hasNodeId()) {
            Tab t = findTab(node.arena());
            if (t != null) {
                StateFlags found = t.slots.get(node.nodeId());
                if (found != null) return found;
            }
        } else if (node.isVirtualNode()) {
            synchronized (this) {
                if (loose == null) loose = new VirtualNodeSlots<>();
                StateFlags found = loose.get(node);
                if (found != null) return found;
                StateFlags created = new StateFlags(); // 作りたては汚れている
                created.owner = this;
                dirtyCount.incrementAndGet();
                unscopedLog.add(node); // 作りたては unscoped
                loose.put(node, created);
                return created;
            }
        }
        synchronized (this) {
            DocumentArena a = node.arena();
            int id = node.nodeId();
            Tab t = tabFor(a);
            StateFlags found = t.slots.get(id);
            if (found != null) return found;
            StateFlags created = new StateFlags(); // 作りたては汚れている
            created.owner = this;
            dirtyCount.incrementAndGet();
            unscopedLog.add(node); // 作りたては unscoped
            t.slots.put(id, created);
            return created;
        }
    }

    /**
     * {@link StateFlags#isStyleDirty} が立っている節の個数の上限（O(1) の問い合わせ用）。
     * 0 なら汚れた節は確実に無い。節が回収されると汚れたまま表から消えて実際より大きくなり得るので、
     * 正の値は「あるかもしれない」で、{@link #hasDirtyNodes} が確かめて数え直す。
     */
    private final java.util.concurrent.atomic.AtomicInteger dirtyCount =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * {@link StateFlags#unscoped} が立った節の記録（{@link #propagateDirtyToAncestors} が全走査せずに済むように）。
     * 古い記録（その後に片付いた節）が残ってもよい。使う側が印を見直す。使い終わったら空にする。
     */
    private final List<HtmlNode> unscopedLog = new ArrayList<>();

    /** 算出スタイルの問い合わせ先（新経路のビューが箱の木を結ぶ。null なら旧経路の印の値）。 */
    private java.util.function.Function<HtmlNode, ComputedStyle> styleLookup;
    public void bindStyleLookup(java.util.function.Function<HtmlNode, ComputedStyle> lookup) { styleLookup = lookup; }

    /** 確定済みの算出スタイル。まだ再スタイルされていなければ null。 */
    public ComputedStyle computedStyleOf(HtmlNode node) {
        if (styleLookup != null) return styleLookup.apply(node);
        StateFlags flags = get(node);
        return flags != null ? flags.computedStyle : null;
    }

    /** 記録した問い合わせコンテナの内容ボックスの幅（{@code height} なら高さ）。{@link #setContainerSize}。測っていなければ NaN。 */
    public float containerSize(HtmlNode node, boolean height) { StateFlags f = get(node); return f == null ? Float.NaN : height ? f.containerHeight : f.containerWidth; }

    public StateFlags get(HtmlNode node) {
        if (node == null) return null;
        if (node.hasNodeId()) {
            Tab t = findTab(node.arena());
            return t != null ? t.slots.get(node.nodeId()) : null;
        }
        if (node.isVirtualNode()) {
            VirtualNodeSlots<StateFlags> v = loose;
            return v != null ? v.get(node) : null;
        }
        return null;
    }

    @Override
    public ElementState stateOf(HtmlNode node) { return ElementState.of(stateBitsOf(node)); }

    /** {@link #stateOf} のビット集合そのもの（値オブジェクトを作らない）。 */
    private static final int TRI_MASK = ElementState.CHECKED | ElementState.DISABLED
            | ElementState.INDETERMINATE | ElementState.OPEN;

    public int stateBitsOf(HtmlNode node) {
        if (node == null) return 0;
        int bits = ElementState.fromNodeAttributes(node).flags();
        StateFlags flags = get(node);
        if (flags != null) {
            int explicit = flags.state;
            int over = flags.overridden;
            // 2 値のビットは足し、3 値のビットは指定があれば属性から決まる値を上書きする
            bits |= explicit & ~TRI_MASK;
            if ((explicit & ElementState.FOCUS) != 0) bits |= ElementState.FOCUS_WITHIN;
            bits = (bits & ~over) | (explicit & over);
        }
        return bits;
    }

    public boolean setHovered(HtmlNode node, boolean hovered) {
        if (node == null) return false;
        StateFlags flags = getOrCreate(node);
        if (!flags.setBit(ElementState.HOVER, hovered)) return false;
        invalidateState(node, ElementState.HOVER);
        return true;
    }

    /** ポインタの下の節を更新する。{@code target} とその祖先が {@code :hover}、それ以外は外れる。null で全解除。 */
    public boolean setHoveredNode(HtmlNode target) {
        ArrayList<HtmlNode> nextChain = fillAncestorsAndSelf(target, scratchChain);
        hoveredNode = target;
        if (sameChain(hoveredChain, nextChain)) return false;

        boolean changed = false;
        for (int i = 0, n = hoveredChain.size(); i < n; i++) {
            HtmlNode left = hoveredChain.get(i);
            if (!containsRef(nextChain, left)) changed |= setHovered(left, false);
        }
        for (int i = 0, n = nextChain.size(); i < n; i++) {
            HtmlNode entered = nextChain.get(i);
            if (!containsRef(hoveredChain, entered)) changed |= setHovered(entered, true);
        }
        scratchChain = hoveredChain;
        hoveredChain = nextChain;
        return changed;
    }

    public boolean setActive(HtmlNode node, boolean active) {
        if (node == null) return false;
        StateFlags flags = getOrCreate(node);
        if (!flags.setBit(ElementState.ACTIVE, active)) return false;
        invalidateState(node, ElementState.ACTIVE);
        return true;
    }

    /** 押下中の節を更新する。{@code target} とその祖先が {@code :active}、それ以外は外れる。null で全解除。 */
    public boolean setActiveNode(HtmlNode target) {
        ArrayList<HtmlNode> nextChain = fillAncestorsAndSelf(target, scratchChain);
        activeNode = target;
        if (sameChain(activeChain, nextChain)) return false;

        boolean changed = false;
        for (int i = 0, n = activeChain.size(); i < n; i++) {
            HtmlNode released = activeChain.get(i);
            if (!containsRef(nextChain, released)) changed |= setActive(released, false);
        }
        for (int i = 0, n = nextChain.size(); i < n; i++) {
            HtmlNode pressed = nextChain.get(i);
            if (!containsRef(activeChain, pressed)) changed |= setActive(pressed, true);
        }
        scratchChain = activeChain;
        activeChain = nextChain;
        return changed;
    }

    public boolean setFocused(HtmlNode node, boolean focused, boolean focusVisible) {
        if (node == null) return false;
        StateFlags flags = getOrCreate(node);
        boolean nextVisible = focused && focusVisible;
        boolean changed = flags.setBit(ElementState.FOCUS, focused);
        changed |= flags.setBit(ElementState.FOCUS_VISIBLE, nextVisible);
        // 旧実装は focused/visible が変わらなければ FOCUS_WITHIN も触らずに戻った。その挙動を保つ
        if (!changed) return false;
        flags.setBit(ElementState.FOCUS_WITHIN, focused);
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
                if (oldFlags.setBit(ElementState.FOCUS | ElementState.FOCUS_VISIBLE, false)) {
                    invalidateState(focusedNode, ElementState.FOCUS | ElementState.FOCUS_VISIBLE);
                    changed = true;
                }
            }
            focusedNode = target;
        }

        if (target != null) {
            changed |= setFocused(target, true, focusVisible);
        }

        ArrayList<HtmlNode> nextChain = fillAncestorsAndSelf(target, scratchChain);
        for (int i = 0, n = focusWithinChain.size(); i < n; i++) {
            HtmlNode left = focusWithinChain.get(i);
            if (containsRef(nextChain, left)) continue;
            StateFlags flags = getOrCreate(left);
            if (flags.setBit(ElementState.FOCUS_WITHIN, false)) {
                invalidateState(left, ElementState.FOCUS_WITHIN);
                changed = true;
            }
        }
        for (int i = 0, n = nextChain.size(); i < n; i++) {
            HtmlNode entered = nextChain.get(i);
            StateFlags flags = getOrCreate(entered);
            if (flags.setBit(ElementState.FOCUS_WITHIN, true)) {
                invalidateState(entered, ElementState.FOCUS_WITHIN);
                changed = true;
            }
        }
        scratchChain = focusWithinChain;
        focusWithinChain = nextChain;
        return changed;
    }

    public boolean setChecked(HtmlNode node, Boolean checked) {
        if (node == null) return false;
        StateFlags flags = getOrCreate(node);
        if (!flags.setTri(ElementState.CHECKED, checked)) return false;
        invalidateState(node, ElementState.CHECKED);
        return true;
    }

    public boolean setDisabled(HtmlNode node, Boolean disabled) {
        if (node == null) return false;
        StateFlags flags = getOrCreate(node);
        if (!flags.setTri(ElementState.DISABLED, disabled)) return false;
        invalidateState(node, ElementState.DISABLED);
        return true;
    }

    public boolean setIndeterminate(HtmlNode node, Boolean indeterminate) {
        if (node == null) return false;
        StateFlags flags = getOrCreate(node);
        if (!flags.setTri(ElementState.INDETERMINATE, indeterminate)) return false;
        invalidateState(node, ElementState.INDETERMINATE);
        return true;
    }

    /** {@code :modal}（{@code dialog.showModal()} 中）。Top Layer の所属と表示順を決める。 */
    private final ArrayList<HtmlNode> topLayerOrder = new ArrayList<>(4);

    public boolean setModal(HtmlNode node, boolean modal) {
        if (node == null) return false;
        StateFlags flags = getOrCreate(node);
        if (!flags.setBit(ElementState.MODAL, modal)) return false;
        removeIdentity(topLayerOrder, node);
        if (modal) topLayerOrder.add(node);
        invalidateState(node, ElementState.MODAL);
        return true;
    }

    /** {@code :popover-open}（{@code showPopover()} 中）。Top Layer の所属と表示順を決める。 */
    public boolean setPopoverOpen(HtmlNode node, boolean open) {
        if (node == null) return false;
        StateFlags flags = getOrCreate(node);
        if (!flags.setBit(ElementState.POPOVER_OPEN, open)) return false;
        removeIdentity(topLayerOrder, node);
        if (open) topLayerOrder.add(node);
        invalidateState(node, ElementState.POPOVER_OPEN);
        return true;
    }

    /** 最前面で開いているモーダル {@code <dialog>}。無ければ null。 */
    public HtmlNode activeModalDialog() {
        for (int i = topLayerOrder.size() - 1; i >= 0; i--) {
            HtmlNode n = topLayerOrder.get(i);
            if (stateOf(n).modal() && rootOf(n) instanceof HtmlDocument) return n;
        }
        return null;
    }

    /** モーダル {@code <dialog>} が 1 つ以上開いているか。 */
    public boolean hasActiveModal() { return activeModalDialog() != null; }

    /**
     * 節が不活性（{@code inert} 属性の配下、または最前面モーダル {@code <dialog>} の外側）か。
     * モーダルより後に Top Layer へ積まれた popover 配下は不活性にしない。
     */
    public boolean isInert(HtmlNode node) {
        if (node == null) return false;
        HtmlNode topModal = activeModalDialog();
        HtmlNode cur = node;
        HtmlNode root = null;
        while (cur != null) {
            if (cur.attrs != null && cur.attrs.containsKey("inert")) return true;
            if (cur == topModal) return false;
            root = cur;
            cur = cur.parent;
        }
        if (topModal == null) return false;
        if (root != rootOf(topModal)) return false;
        int modalIdx = indexOfIdentity(topLayerOrder, topModal);
        for (int i = modalIdx + 1, n = topLayerOrder.size(); i < n; i++) {
            HtmlNode upper = topLayerOrder.get(i);
            if (stateOf(upper).popoverOpen() && isDescendantOrSelf(node, upper)) return false;
        }
        return true;
    }

    /** 不活性になった節からホバー／押下状態を外す（フォーカス解除は焦点イベントを伴うため呼び出し側で行う）。 */
    public void clearInertPointerStates() {
        if (hoveredNode != null && isInert(hoveredNode)) setHoveredNode(null);
        if (activeNode != null && isInert(activeNode)) setActiveNode(null);
    }

    private static HtmlNode rootOf(HtmlNode n) {
        HtmlNode cur = n;
        while (cur != null && cur.parent != null) cur = cur.parent;
        return cur;
    }

    private static boolean isDescendantOrSelf(HtmlNode node, HtmlNode ancestor) {
        for (HtmlNode cur = node; cur != null; cur = cur.parent) {
            if (cur == ancestor) return true;
        }
        return false;
    }

    private static void removeIdentity(ArrayList<HtmlNode> list, HtmlNode target) {
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i) == target) list.remove(i);
        }
    }

    private static int indexOfIdentity(ArrayList<HtmlNode> list, HtmlNode target) {
        for (int i = 0, n = list.size(); i < n; i++) {
            if (list.get(i) == target) return i;
        }
        return -1;
    }

    public boolean setOpen(HtmlNode node, Boolean open) {
        if (node == null) return false;
        StateFlags flags = getOrCreate(node);
        if (!flags.setTri(ElementState.OPEN, open)) return false;
        invalidateState(node, ElementState.OPEN);
        return true;
    }

    /**
     * 理由を絞らずに要素を汚す。子孫も祖先も再スタイルの対象になる。変化の種類が分かる呼び出しは
     * {@link #invalidateState} などで影響範囲を絞ること。
     */
    public void markDirty(HtmlNode node) {
        if (node == null) return;
        StateFlags flags = getOrCreate(node); ledger.append(node, com.nishiyu.enderframelib.core.dom.node.ChangeKind.STYLE);
        flags.markStyleDirty();
        flags.markSubtreeDirty();
        if (!flags.unscoped()) {
            flags.markUnscoped();
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
        for (HtmlNode cur = node.parent; cur != null; cur = cur.parent) getOrCreate(cur).markDirtyBelow();
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
            flags.inheritsExplicitly = containsInheritIgnoreCase(newValue);
            flags.inheritsAttrVersion = node.attrVersion();
        }
        invalidate(node, relations);
    }

    /** ASCII 大文字小文字を無視して {@code "inherit"} を含むかを割り当て無しで判定する。 */
    public static boolean containsInheritIgnoreCase(String value) {
        if (value == null || value.length() < 7) return false;
        int limit = value.length() - 7;
        for (int i = 0; i <= limit; i++) {
            char c = value.charAt(i);
            if ((c == 'i' || c == 'I')
                    && asciiLower(value.charAt(i + 1)) == 'n'
                    && asciiLower(value.charAt(i + 2)) == 'h'
                    && asciiLower(value.charAt(i + 3)) == 'e'
                    && asciiLower(value.charAt(i + 4)) == 'r'
                    && asciiLower(value.charAt(i + 5)) == 'i'
                    && asciiLower(value.charAt(i + 6)) == 't') {
                return true;
            }
        }
        return false;
    }

    private static char asciiLower(char c) { return (c >= 'A' && c <= 'Z') ? (char) (c + 32) : c; }

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
        StateFlags flags = getOrCreate(node); ledger.append(node, com.nishiyu.enderframelib.core.dom.node.ChangeKind.STYLE);
        flags.markStyleDirty();
        if (subtree) flags.markSubtreeDirty();
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

    public void markAllDirty() { ledger.appendWhole();
        forEachFlags((node, flags) -> {
            flags.markStyleDirty();
            flags.markSubtreeDirty();
            flags.markDirtyBelow();
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
            if (flags != null && flags.isStyleDirty() && flags.unscoped()) {
                dirty.add(node);
                flags.clearUnscoped();
            }
        }
        unscopedLog.clear();
        for (HtmlNode node : dirty) {
            for (HtmlNode cur = node.parent; cur != null; cur = cur.parent) {
                StateFlags flags = get(cur);
                if (flags != null) {
                    flags.markStyleDirty(); ledger.append(cur, com.nishiyu.enderframelib.core.dom.node.ChangeKind.STYLE); // 新経路は台帳を読む
                    flags.markDirtyBelow();
                }
            }
        }
    }

    /** {@code :has()} を使わない文書では {@link #propagateDirtyToAncestors} が呼ばれないので、記録が溜まらないよう捨てる。 */
    public void discardUnscopedLog() { unscopedLog.clear(); }

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
            flags.markSubtreeDirty();
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
        Style st = style.typed();
        ContainerType type = switch (st.rare.containerType) {
            case SIZE -> ContainerType.SIZE; case INLINE_SIZE -> ContainerType.INLINE_SIZE; default -> ContainerType.NORMAL;
        };
        String nameList = st.rare.containerName;
        List<String> names;
        if (nameList == null || nameList.isBlank() || "none".equalsIgnoreCase(nameList.trim())) names = List.of();
        else {
            names = new ArrayList<>();
            for (String name : nameList.trim().toLowerCase(java.util.Locale.ROOT).split("\\s+")) {
                if (!name.isEmpty() && !name.equals("none")) names.add(name);
            }
        }
        ContainerBox box = new ContainerBox(flags.containerWidth, flags.containerHeight, type, names,
                st.custom != null ? st.custom.asMap() : Map.of());
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
    public void setAnimationTime(long ms) { this.animationTimeMs = ms; }

    /** 設定されていなければ現在時刻。 */
    public long animationTimeMs() {
        return animationTimeMs != Long.MIN_VALUE ? animationTimeMs : Clock.SYSTEM.millis();
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
        int dirtyUpper = dirtyCount.get();
        if (dirtyUpper <= 0) return true;
        Set<HtmlNode> set = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        set.addAll(allowed);
        // dirtyCount は実数以上なので、allowed の中の汚れた節の数がそれに届けば、外に汚れた節は無い（全走査を省ける）
        int inside = 0;
        for (HtmlNode n : set) {
            StateFlags f = get(n);
            if (f != null && f.isStyleDirty()) inside++;
        }
        if (inside >= dirtyUpper) return true;
        // 届かないときは、回収された節の分の過大か、外に本当に汚れた節があるか。確かめる
        boolean[] outside = {false};
        forEachFlags((node, flags) -> {
            if (flags.isStyleDirty() && !set.contains(node)) outside[0] = true;
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
        flags.clearSubtreeDirty();
        flags.clearUnscoped();
    }

    /** 全ての節の再スタイルの印を片付ける（新経路: 変化は変更台帳に写してあるので印は使わない。M2c ④）。 */ public void clearAllStyleDirty() { if (dirtyCount.get() > 0) { forEachFlags((n, f) -> { f.clearStyleDirtyFlag(); f.clearSubtreeDirty(); f.clearDirtyBelow(); f.clearUnscoped(); }); dirtyCount.set(0); } }
    /** 汚れた節が残っているかもしれないか（O(1)。個数の帳簿は実数以上なので、false なら確実に無い）。 */
    public boolean mayHaveDirtyNodes() { return dirtyCount.get() > 0; }

    public boolean hasDirtyNodes() {
        if (!mayHaveDirtyNodes()) return false; // 止まっている画面は、毎フレームここで O(1) で終わる
        boolean[] any = {false};
        forEachFlags((node, flags) -> {
            if (flags.isStyleDirty()) any[0] = true;
        });
        if (any[0]) return true;
        dirtyCount.set(0); // 汚れたまま回収された節の分。帳簿を合わせ直す
        return false;
    }

    public synchronized void clear() {
        if (loose != null) loose.clear();
        for (Tab tab : tabs) tab.arena.dropSlots(this);
        tabs.clear();
        lastTab = null;
        dirtyCount.set(0);
        unscopedLog.clear();
        if (animator != null) animator.clear();
        hoveredChain.clear();
        activeChain.clear();
        focusWithinChain.clear();
        scratchChain.clear();
        hoveredNode = null;
        activeNode = null;
        focusedNode = null;
        topLayerOrder.clear();
    }

    private static ArrayList<HtmlNode> fillAncestorsAndSelf(HtmlNode node, ArrayList<HtmlNode> out) {
        out.clear();
        for (HtmlNode cur = node; cur != null; cur = cur.parent) {
            out.add(cur);
        }
        return out;
    }

    private static boolean sameChain(ArrayList<HtmlNode> a, ArrayList<HtmlNode> b) {
        int n = a.size();
        if (n != b.size()) return false;
        for (int i = 0; i < n; i++) {
            if (a.get(i) != b.get(i)) return false;
        }
        return true;
    }

    private static boolean containsRef(ArrayList<HtmlNode> list, HtmlNode target) {
        for (int i = 0, n = list.size(); i < n; i++) {
            if (list.get(i) == target) return true;
        }
        return false;
    }
}
