package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.core.kernel.Clock;
import com.nishiyu.enderframelib.core.dom.node.*;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.model.ElementState;
import com.nishiyu.enderframelib.core.css.model.StateLookup;
import com.nishiyu.enderframelib.core.css.model.ContainerBox;
import com.nishiyu.enderframelib.core.css.InvalidationSets;
import com.nishiyu.enderframelib.core.css.StyleAnimator;
import com.nishiyu.enderframelib.core.css.StyleSheet;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * ビュー単位の対話状態（疑似クラス状態）や算出スタイルキャッシュを管理するクラス。
 *
 * <p>ノードオブジェクト自身（{@link HtmlNode}）が唯一のキー。文字列 ID は、ネットワークと描画の境界で
 * ノードへ解決してから渡す（解決は {@link HtmlNode#getNodeById} の索引が担う）。
 * 状態の実体は各ビューの表に保持し（文書上の節は {@link NodeIds.NodeSlots}、仮想の節は
 * {@link VirtualNodeSlots}）、{@link HtmlNode} 自体は書き換えない。節ごとの状態は {@link StateFlags}、再スタイルの印と無効化は
 * {@link StyleInvalidation}、節の鎖（hover・active・focus-within）と Top Layer・不活性は {@link InteractionChains} が持つ。擬似状態は {@link StateFlags#state} の 1 本の整数
 * （{@link ElementState} のビット）が唯一の記憶で、番号の表の列へ写し書きはしない
 * （写しは誰にも読まれず、書くだけの二重コストだった）。</p>
 */
public class InteractionState implements StyledDocumentState {

    /** 番号の空間ごとの列挙用の表。節が文書から外れて番号の世代が進めば、その項は自然に無効になる。 */
    private record Tab(NodeIds ids, NodeIds.NodeSlots<StateFlags> slots) {}

    private final List<Tab> tabs = new ArrayList<>(2);
    private volatile Tab lastTab;
    private static final int MAX_TABS = 8;
    /** 直近の {@code restyleBoxRecursive} が根の子孫へ降りたか（ルート専用再レイアウトの印付け省略判定用）。 */
    public boolean restyleDescended;
    /** 再スタイルの印と無効化。 */
    final StyleInvalidation invalidation = new StyleInvalidation(this);

    private Tab findTab(NodeIds ids) {
        Tab t = lastTab;
        if (t != null && t.ids == ids) return t;
        synchronized (this) {
            for (Tab other : tabs) {
                if (other.ids == ids) return lastTab = other;
            }
            if (ids.hasSlots(this)) return tabFor(ids);
            return null;
        }
    }

    private synchronized Tab tabFor(NodeIds ids) {
        Tab t = lastTab;
        if (t != null && t.ids == ids) return t;
        for (Tab other : tabs) {
            if (other.ids == ids) return lastTab = other;
        }
        if (tabs.size() >= MAX_TABS) {
            int evict = (tabs.get(0) == lastTab && tabs.size() > 1) ? 1 : 0;
            Tab dropped = tabs.remove(evict);
            dropped.ids.dropSlots(this);
        }
        t = new Tab(ids, ids.slots(this));
        tabs.add(t);
        return lastTab = t;
    }

    /** 番号を持たない節（仮想の節）の表。節が回収されれば項は消える。 */
    private volatile VirtualNodeSlots<StateFlags> loose;

    void forEachFlags(BiConsumer<HtmlNode, StateFlags> visitor) {
        VirtualNodeSlots<StateFlags> v;
        List<Tab> tabCopy;
        synchronized (this) {
            v = loose;
            tabCopy = new ArrayList<>(tabs);
        }
        if (v != null) v.forEach(visitor);
        for (Tab tab : tabCopy) {
            tab.slots.forEach((id, flags) -> {
                if (tab.ids.owner(id) instanceof HtmlNode node) visitor.accept(node, flags);
            });
        }
    }

    /**
     * 無効化セットの出所。結ばれていれば、変化の影響が及ぶ要素だけを汚す。結ばれていない間（最初の描画の前）は、
     * 変化した要素の子孫も祖先も汚す（従来の安全側の動き）。
     */
    StyleSheet sheet;

    /** 無効化セットを引くシートを結ぶ。描画のたびに呼んでよい（同じシートなら何もしない）。 */
    /** 1 回の再スタイル／箱の木の組み立ての間だけ使う、スタイル解決の使い回し表。走査の前後で空にする。 */
    public final com.nishiyu.enderframelib.core.css.ShareCache styleShare =
            new com.nishiyu.enderframelib.core.css.ShareCache();

    public void bindStyleSheet(StyleSheet sheet) { this.sheet = sheet; }

    InvalidationSets invalidationSets() { return sheet != null ? sheet.invalidationSets() : null; }

    /** 節の鎖（hover・active・focus-within）と Top Layer の積み順・不活性。 */
    private final InteractionChains chains = new InteractionChains(this);

    /** ポインタの下の節。無ければ null。 */
    public HtmlNode hoveredNode() { return chains.hoveredNode; } // 押下中・フォーカス中の節も同じく読むだけ

    /** 押下中の節。無ければ null。 */
    public HtmlNode activeNode() { return chains.activeNode; }

    /** フォーカス中の節。無ければ null。 */
    public HtmlNode focusedNode() { return chains.focusedNode; }

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
            Tab t = findTab(node.nodeIds());
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
                invalidation.dirtyCount.incrementAndGet();
                invalidation.unscopedLog.add(node); // 作りたては unscoped
                loose.put(node, created);
                return created;
            }
        }
        synchronized (this) {
            NodeIds a = node.nodeIds();
            int id = node.nodeId();
            Tab t = tabFor(a);
            StateFlags found = t.slots.get(id);
            if (found != null) return found;
            StateFlags created = new StateFlags(); // 作りたては汚れている
            created.owner = this;
            invalidation.dirtyCount.incrementAndGet();
            invalidation.unscopedLog.add(node); // 作りたては unscoped
            t.slots.put(id, created);
            return created;
        }
    }


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
            Tab t = findTab(node.nodeIds());
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
    public boolean setHoveredNode(HtmlNode target) { return chains.setHoveredNode(target); }

    public boolean setActive(HtmlNode node, boolean active) {
        if (node == null) return false;
        StateFlags flags = getOrCreate(node);
        if (!flags.setBit(ElementState.ACTIVE, active)) return false;
        invalidateState(node, ElementState.ACTIVE);
        return true;
    }

    /** 押下中の節を更新する。{@code target} とその祖先が {@code :active}、それ以外は外れる。null で全解除。 */
    public boolean setActiveNode(HtmlNode target) { return chains.setActiveNode(target); }

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
    public boolean setFocusedNode(HtmlNode target, boolean focusVisible) { return chains.setFocusedNode(target, focusVisible); }

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
    public boolean setModal(HtmlNode node, boolean modal) {
        if (node == null) return false;
        StateFlags flags = getOrCreate(node);
        if (!flags.setBit(ElementState.MODAL, modal)) return false;
        chains.stackTopLayer(node, modal);
        invalidateState(node, ElementState.MODAL);
        return true;
    }

    /** {@code :popover-open}（{@code showPopover()} 中）。Top Layer の所属と表示順を決める。 */
    public boolean setPopoverOpen(HtmlNode node, boolean open) {
        if (node == null) return false;
        StateFlags flags = getOrCreate(node);
        if (!flags.setBit(ElementState.POPOVER_OPEN, open)) return false;
        chains.stackTopLayer(node, open);
        invalidateState(node, ElementState.POPOVER_OPEN);
        return true;
    }

    /** 最前面で開いているモーダル {@code <dialog>}。無ければ null。 */
    public HtmlNode activeModalDialog() { return chains.activeModalDialog(); }

    /** モーダル {@code <dialog>} が 1 つ以上開いているか。 */
    public boolean hasActiveModal() { return activeModalDialog() != null; }

    /**
     * 節が不活性（{@code inert} 属性の配下、または最前面モーダル {@code <dialog>} の外側）か。
     * モーダルより後に Top Layer へ積まれた popover 配下は不活性にしない。
     */
    public boolean isInert(HtmlNode node) { return chains.isInert(node); }

    /** 不活性になった節からホバー／押下状態を外す（フォーカス解除は焦点イベントを伴うため呼び出し側で行う）。 */
    public void clearInertPointerStates() { chains.clearInertPointerStates(); }

    public boolean setOpen(HtmlNode node, Boolean open) {
        if (node == null) return false;
        StateFlags flags = getOrCreate(node);
        if (!flags.setTri(ElementState.OPEN, open)) return false;
        invalidateState(node, ElementState.OPEN);
        return true;
    }

    // ==========================================
    // 再スタイルの印と無効化（中身は StyleInvalidation）
    // ==========================================

    /** 理由を絞らずに要素を汚す。子孫も祖先も再スタイルの対象になる。 */
    public void markDirty(HtmlNode node) { invalidation.markDirty(node); }

    /** 対話状態（{@code :hover} など）のビットが変わった要素の影響範囲を汚す。 */
    public void invalidateState(HtmlNode node, int stateBits) { invalidation.invalidateState(node, stateBits); }

    /** 属性の変化で汚す（要素自身と、規則がその属性・class・id を読んでいる範囲）。 */
    public void invalidateAttribute(HtmlNode node, String name, String oldValue, String newValue) {
        invalidation.invalidateAttribute(node, name, oldValue, newValue);
    }

    /** 子の増減で汚す。{@code changedAt} は {@code parent} の子のうち増減があった位置。 */
    public void invalidateChildList(HtmlNode parent, int changedAt) { invalidation.invalidateChildList(parent, changedAt); }

    public void markAllDirty() { invalidation.markAllDirty(); }

    /** 影響範囲を絞らずに汚した要素の祖先も汚す（{@code :has()} の持ち主になり得る祖先）。 */
    public void propagateDirtyToAncestors() { invalidation.propagateDirtyToAncestors(); }

    /** {@code :has()} を使わない文書では {@link #propagateDirtyToAncestors} が呼ばれないので、記録が溜まらないよう捨てる。 */
    public void discardUnscopedLog() { invalidation.unscopedLog.clear(); }

    /** 問い合わせコンテナの寸法を記録する。以前と違えばその要素を汚して true。 */
    public boolean setContainerSize(HtmlNode node, float width, float height) { return invalidation.setContainerSize(node, width, height); }

    @Override
    public ContainerBox containerOf(HtmlNode node) {
        StateFlags flags = get(node);
        return flags == null ? null : flags.containerBox();
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
    public long animationTimeMs() { return animationTimeMs != Long.MIN_VALUE ? animationTimeMs : Clock.SYSTEM.millis(); }

    /**
     * 時刻を進め、値が変わる要素を再スタイルの対象にする（要素自身だけ。子孫への波及は再スタイルが算出値の変化から決める）。
     *
     * @return まだ動いているものがあるか（あれば次のフレームも呼ぶ）
     */
    public boolean advanceAnimations(long now) {
        if (animator == null) return false;
        animationTimeMs = now;
        for (HtmlNode node : animator.advance(now)) invalidation.dirty(node, false);
        return animator.isRunning(now);
    }


    /** 再スタイルが必要な印を持つ節が、すべて {@code allowed}（同一性で比べる）に含まれるか。印が無ければ true。 */
    public boolean styleDirtyOnlyIn(java.util.Collection<HtmlNode> allowed) { return invalidation.styleDirtyOnlyIn(allowed); }

    /** 文字の節の再スタイルの印を片付ける。 */
    public void clearStyleDirty(HtmlNode node) { invalidation.clearStyleDirty(node); }

    /** 全ての節の再スタイルの印を片付ける（新経路: 変化は変更台帳に写してあるので印は使わない。M2c ④）。 */
    public void clearAllStyleDirty() { invalidation.clearAllStyleDirty(); }

    /** 汚れた節が残っているかもしれないか（O(1)。個数の帳簿は実数以上なので、false なら確実に無い）。 */
    public boolean mayHaveDirtyNodes() { return invalidation.mayHaveDirtyNodes(); }

    public boolean hasDirtyNodes() { return invalidation.hasDirtyNodes(); }


    public synchronized void clear() {
        if (loose != null) loose.clear();
        for (Tab tab : tabs) tab.ids.dropSlots(this);
        tabs.clear();
        lastTab = null;
        invalidation.dirtyCount.set(0);
        invalidation.unscopedLog.clear();
        if (animator != null) animator.clear();
        chains.clear();
    }
}
