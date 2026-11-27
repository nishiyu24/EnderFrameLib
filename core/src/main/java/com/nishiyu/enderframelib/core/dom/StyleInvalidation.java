package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.core.css.InvalidationSets;
import com.nishiyu.enderframelib.core.css.InvalidationSets.Relation;
import com.nishiyu.enderframelib.core.css.model.ElementState;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 1 つのビュー（{@link InteractionState}）の再スタイルの印と無効化: 対話状態・属性・子の並びの変化から、規則が読む範囲
 * （{@link InvalidationSets}）の要素を汚し、変更台帳へ書く。汚れた節の個数の帳簿と、影響範囲を絞らずに汚した節の記録も持つ
 * （M5-2 で {@code InteractionState} から分けた。公開の入口は {@code InteractionState} の同名のメソッド）。
 */
final class StyleInvalidation {

    private final InteractionState s;

    StyleInvalidation(InteractionState s) {
        this.s = s;
    }

    /**
     * {@link StateFlags#isStyleDirty} が立っている節の個数の上限（O(1) の問い合わせ用）。
     * 0 なら汚れた節は確実に無い。節が回収されると汚れたまま表から消えて実際より大きくなり得るので、
     * 正の値は「あるかもしれない」で、{@link #hasDirtyNodes} が確かめて数え直す。
     */
    final java.util.concurrent.atomic.AtomicInteger dirtyCount =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * {@link StateFlags#unscoped} が立った節の記録（{@link #propagateDirtyToAncestors} が全走査せずに済むように）。
     * 古い記録（その後に片付いた節）が残ってもよい。使う側が印を見直す。使い終わったら空にする。
     */
    final List<HtmlNode> unscopedLog = new ArrayList<>();

    /**
     * 理由を絞らずに要素を汚す。子孫も祖先も再スタイルの対象になる。変化の種類が分かる呼び出しは
     * {@link #invalidateState} などで影響範囲を絞ること。
     */
    void markDirty(HtmlNode node) {
        if (node == null) return;
        StateFlags flags = s.getOrCreate(node); s.ledger.append(node, com.nishiyu.enderframelib.core.dom.node.ChangeKind.STYLE);
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
        for (HtmlNode cur = node.parent; cur != null; cur = cur.parent) s.getOrCreate(cur).markDirtyBelow();
    }

    /** 対話状態（{@code :hover} など）のビットが変わった要素の影響範囲を汚す。 */
    void invalidateState(HtmlNode node, int stateBits) {
        InvalidationSets sets = s.invalidationSets();
        if (sets == null) {
            markDirty(node);
            return;
        }
        // :hover / :active は規則からしか読まれない: この要素の状態を読む規則が無ければ、自分も兄弟・子孫も変わらない（:has() は除く）
        if ((stateBits & ~(ElementState.HOVER | ElementState.ACTIVE)) == 0 && !s.sheet.usesHasSelectors() && (com.nishiyu.enderframelib.core.css.StyleStateAutomaton.interactiveMask(node, s.sheet) & stateBits) == 0) return;
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
    void invalidateAttribute(HtmlNode node, String name, String oldValue, String newValue) {
        InvalidationSets sets = s.invalidationSets();
        if (sets == null) {
            markDirty(node);
            return;
        }
        StateFlags flags = s.getOrCreate(node);
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
    static boolean containsInheritIgnoreCase(String value) {
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
    void invalidateChildList(HtmlNode parent, int changedAt) {
        InvalidationSets sets = s.invalidationSets();
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

    void dirty(HtmlNode node, boolean subtree) {
        StateFlags flags = s.getOrCreate(node); s.ledger.append(node, subtree ? com.nishiyu.enderframelib.core.dom.node.ChangeKind.STYLE_SUBTREE : com.nishiyu.enderframelib.core.dom.node.ChangeKind.STYLE); // 子孫まで及ぶなら台帳にもそう書く
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

    void markAllDirty() { s.ledger.appendWhole();
        s.forEachFlags((node, flags) -> {
            flags.markStyleDirty();
            flags.markSubtreeDirty();
            flags.markDirtyBelow();
        });
    }

    /**
     * 影響範囲を絞らずに汚した要素の祖先も汚す。{@code :has()} は子孫・後続の兄弟の状態で要素自身の見た目が変わるので、
     * 子孫の状態が変わったら、{@code :has()} の持ち主になり得る祖先を再スタイルに含める必要がある。
     */
    void propagateDirtyToAncestors() {
        if (unscopedLog.isEmpty()) return;
        List<HtmlNode> dirty = new ArrayList<>();
        for (HtmlNode node : unscopedLog) {
            StateFlags flags = s.get(node);
            // 影響範囲を絞って汚した要素の祖先は、無効化セットが既に汚している
            if (flags != null && flags.isStyleDirty() && flags.unscoped()) {
                dirty.add(node);
                flags.clearUnscoped();
            }
        }
        unscopedLog.clear();
        for (HtmlNode node : dirty) {
            for (HtmlNode cur = node.parent; cur != null; cur = cur.parent) {
                StateFlags flags = s.get(cur);
                if (flags != null) {
                    flags.markStyleDirty(); s.ledger.append(cur, com.nishiyu.enderframelib.core.dom.node.ChangeKind.STYLE); // 新経路は台帳を読む
                    flags.markDirtyBelow();
                }
            }
        }
    }

    /** {@code :has()} を使わない文書では {@link #propagateDirtyToAncestors} が呼ばれないので、記録が溜まらないよう捨てる。 */
    void discardUnscopedLog() { unscopedLog.clear(); }

    /**
     * 問い合わせコンテナの寸法を記録する。以前と違う（NaN から測れた場合も含む）ならその要素を汚して true を返す。
     * 寸法が変わるとコンテナ問い合わせの結果とコンテナ単位の長さが変わるので、呼び出し側は再スタイルして測り直す。
     */
    boolean setContainerSize(HtmlNode node, float width, float height) {
        if (node == null) return false;
        StateFlags flags = s.getOrCreate(node);
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

    /** 再スタイルが必要な印を持つ節が、すべて {@code allowed}（同一性で比べる）に含まれるか。印が無ければ true。 */
    boolean styleDirtyOnlyIn(java.util.Collection<HtmlNode> allowed) {
        int dirtyUpper = dirtyCount.get();
        if (dirtyUpper <= 0) return true;
        Set<HtmlNode> set = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        set.addAll(allowed);
        // dirtyCount は実数以上なので、allowed の中の汚れた節の数がそれに届けば、外に汚れた節は無い（全走査を省ける）
        int inside = 0;
        for (HtmlNode n : set) {
            StateFlags f = s.get(n);
            if (f != null && f.isStyleDirty()) inside++;
        }
        if (inside >= dirtyUpper) return true;
        // 届かないときは、回収された節の分の過大か、外に本当に汚れた節があるか。確かめる
        boolean[] outside = {false};
        s.forEachFlags((node, flags) -> {
            if (flags.isStyleDirty() && !set.contains(node)) outside[0] = true;
        });
        return !outside[0];
    }

    /**
     * 文字の節の再スタイルの印を片付ける。文字の節のスタイルは親から継承するだけで、文字が変わっても規則の当たり方は
     * 変わらないので、箱の文字を差し替えれば済む（祖先の「子孫に汚れがある」印は、次の走査が片付ける）。
     */
    void clearStyleDirty(HtmlNode node) {
        StateFlags flags = s.get(node);
        if (flags == null) return;
        flags.clearStyleDirtyFlag();
        flags.clearSubtreeDirty();
        flags.clearUnscoped();
    }

    /** 全ての節の再スタイルの印を片付ける（新経路: 変化は変更台帳に写してあるので印は使わない。M2c ④）。 */
    void clearAllStyleDirty() {
        if (dirtyCount.get() <= 0) return;
        s.forEachFlags((n, f) -> {
            f.clearStyleDirtyFlag();
            f.clearSubtreeDirty();
            f.clearDirtyBelow();
            f.clearUnscoped();
        });
        dirtyCount.set(0);
    }
    /** 汚れた節が残っているかもしれないか（O(1)。個数の帳簿は実数以上なので、false なら確実に無い）。 */
    boolean mayHaveDirtyNodes() { return dirtyCount.get() > 0; }

    boolean hasDirtyNodes() {
        if (!mayHaveDirtyNodes()) return false; // 止まっている画面は、毎フレームここで O(1) で終わる
        boolean[] any = {false};
        s.forEachFlags((node, flags) -> {
            if (flags.isStyleDirty()) any[0] = true;
        });
        if (any[0]) return true;
        dirtyCount.set(0); // 汚れたまま回収された節の分。帳簿を合わせ直す
        return false;
    }
}
